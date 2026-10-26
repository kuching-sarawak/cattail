// Command cattail-helper joins the tailnet as an ephemeral node carrying the tag
// given with --tag and relays a loopback TCP port (and optionally a fixed
// loopback UDP voice port) to the game server over Tailscale.
//
// Protocol (stdout): exactly one "READY <port> <tailscale-ip>" line, then only
// "WARN <code> <detail>" / "ERR <code> <detail>" lines. stdin: "QUIT" or EOF
// stops the helper, as do SIGINT and SIGTERM.
//
// Exit codes: 0 normal, 1 relay failure after READY, 2 usage, 3 Up/auth
// failure, 4 loopback bind failure.
package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"io"
	"log"
	"net"
	"net/netip"
	"os"
	"os/signal"
	"path/filepath"
	"runtime/debug"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"

	"tailscale.com/envknob"
	"tailscale.com/ipn/ipnstate"
	"tailscale.com/logtail"
	"tailscale.com/net/tsaddr"
	"tailscale.com/tsnet"

	"github.com/kuching-sarawak/cattail/helper/internal/node"
	"github.com/kuching-sarawak/cattail/helper/internal/proto"
	"github.com/kuching-sarawak/cattail/helper/internal/relay"
)

// version is overridden at build time with -ldflags "-X main.version=...".
var version = "dev"

const (
	exitOK    = 0
	exitRelay = 1
	exitUsage = 2
	exitUp    = 3
	exitBind  = 4

	upTimeout    = 20 * time.Second
	upGrace      = 5 * time.Second // extra wait for Up to honour its context
	voiceIdle    = 60 * time.Second
	probeTimeout = 5 * time.Second
)

func main() {
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	code := run(ctx, os.Args[1:], os.Stdin, os.Stdout, os.Getenv)
	stop()
	os.Exit(code)
}

// lockedWriter serialises protocol lines written from several goroutines.
type lockedWriter struct {
	mu sync.Mutex
	w  io.Writer
}

func (l *lockedWriter) Write(p []byte) (int, error) {
	l.mu.Lock()
	defer l.mu.Unlock()
	return l.w.Write(p)
}

type config struct {
	target   netip.AddrPort
	voice    int
	hostname string
	tag      string
	logDir   string
	probes   []string
}

// parseArgs parses and validates the command line. done is true when the
// caller should exit with code without starting the node.
func parseArgs(args []string, stdout io.Writer) (cfg config, code int, done bool) {
	fs := flag.NewFlagSet("cattail-helper", flag.ContinueOnError)
	fs.SetOutput(io.Discard) // stdout is the protocol channel; errors become ERR lines
	target := fs.String("target", "", "game server `ip:port` on the tailnet (required)")
	voice := fs.Int("voice", 0, "loopback UDP voice `port` to relay to the same host (0 = off)")
	hostname := fs.String("hostname", "", "tailnet hostname (mc-<name>; sanitised again)")
	tag := fs.String("tag", "", "tailnet `tag` the node advertises, tag:<name> (required)")
	logDir := fs.String("log-dir", ".", "base `dir`; logs go to <dir>/cattail/helper.log")
	showVersion := fs.Bool("version", false, "print the version and exit")
	probe := registerProbe(fs)

	usage := func(detail string) (config, int, bool) {
		proto.Err(stdout, "usage", detail)
		return config{}, exitUsage, true
	}
	if err := fs.Parse(args); err != nil {
		if errors.Is(err, flag.ErrHelp) {
			return usage("flags: --target ip:port --tag tag:name [--voice port] [--hostname name] [--log-dir dir] [--version]")
		}
		return usage(err.Error())
	}
	if fs.NArg() > 0 {
		return usage("unexpected argument " + strconv.Quote(fs.Arg(0)))
	}
	if *showVersion {
		fmt.Fprintln(stdout, "cattail-helper "+version)
		return config{}, exitOK, true
	}

	if !node.ValidTag(*tag) {
		return usage(node.ErrBadTag.Error())
	}
	cfg.tag = *tag

	if probe != nil && *probe != "" {
		for a := range strings.SplitSeq(*probe, ",") {
			a = strings.TrimSpace(a)
			if _, err := netip.ParseAddrPort(a); err != nil {
				return usage("--probe " + strconv.Quote(a) + " is not ip:port")
			}
			cfg.probes = append(cfg.probes, a)
		}
	}
	if *target == "" {
		if len(cfg.probes) == 0 {
			return usage("--target is required")
		}
	} else {
		ap, err := netip.ParseAddrPort(*target)
		switch {
		case err != nil:
			return usage("--target " + strconv.Quote(*target) + " is not ip:port")
		case !tsaddr.IsTailscaleIP(ap.Addr().Unmap()):
			return usage("--target " + strconv.Quote(*target) + " is not a Tailscale address")
		case ap.Port() == 0:
			return usage("--target port must not be 0")
		}
		cfg.target = netip.AddrPortFrom(ap.Addr().Unmap(), ap.Port())
	}
	if *voice < 0 || *voice > 65535 {
		return usage("--voice must be 0..65535")
	}
	cfg.voice = *voice
	cfg.hostname = node.NormalizeHostname(*hostname)
	cfg.logDir = *logDir
	return cfg, 0, false
}

// run is the whole helper; ctx is cancelled by SIGINT/SIGTERM.
func run(ctx context.Context, args []string, stdin io.Reader, rawStdout io.Writer, getenv func(string) string) int {
	stdout := &lockedWriter{w: rawStdout}
	cfg, code, done := parseArgs(args, stdout)
	if done {
		return code
	}

	authKey := getenv("TS_AUTHKEY")
	if authKey == "" {
		proto.Err(stdout, "auth", "TS_AUTHKEY is not set")
		return exitUp
	}
	// The key lives only in tsnet.Server.AuthKey from here on.
	_ = os.Unsetenv("TS_AUTHKEY")
	if getenv("CATTAIL_FORCE_RELAY") == "1" {
		// magicsock reads this knob at package init, so os.Setenv would be
		// too late; envknob.Setenv also updates the registered value.
		envknob.Setenv("TS_DEBUG_ALWAYS_USE_DERP", "true")
	}
	// No log uploads to log.tailscale.com (spec: no telemetry beyond the
	// control plane). Disabled, logtail also stops buffering to disk and its
	// shutdown no longer waits up to 5 s for a final upload.
	logtail.Disable()

	// Anything in the process that uses the standard logger (including tsnet
	// dependencies) goes to the log file, never to stdout/stderr.
	logw := node.Log(cfg.logDir)
	log.SetOutput(logw)
	log.SetFlags(log.LstdFlags | log.Lmicroseconds)
	defer logw.Close()
	log.Printf("cattail-helper %s starting: hostname=%s tag=%s target=%v voice=%d probes=%d",
		version, cfg.hostname, cfg.tag, cfg.target, cfg.voice, len(cfg.probes))

	// Built with -H windowsgui the helper has no console, and the game discards
	// what it does not read: a panic or runtime fatal error also goes to a file.
	if f, err := openCrashLog(cfg.logDir); err != nil {
		log.Printf("crash log unavailable: %v", err)
	} else {
		if err := debug.SetCrashOutput(f, debug.CrashOptions{}); err != nil {
			log.Printf("SetCrashOutput: %v", err)
		}
		f.Close() // SetCrashOutput keeps its own duplicate of the descriptor
	}

	srv, err := node.New(node.Options{Hostname: cfg.hostname, AuthKey: authKey, Tag: cfg.tag, LogDir: cfg.logDir})
	if err != nil { // parseArgs already validated the tag
		proto.Err(stdout, "usage", err.Error())
		return exitUsage
	}

	ctx, cancel := context.WithCancel(ctx)
	defer cancel()
	if len(cfg.probes) == 0 { // a probe run ignores stdin; only signals stop it
		quit := proto.WatchStdin(stdin)
		go func() {
			select {
			case <-quit:
				log.Printf("stdin closed or QUIT received")
				cancel()
			case <-ctx.Done():
			}
		}()
	}

	st, upErr, finished := up(ctx, srv)
	if !finished {
		// Up ignored its context (tsnet's startup is not cancellable);
		// Close must not run concurrently with Start, so just exit.
		log.Printf("Up did not return within %v", upTimeout+upGrace)
		proto.Err(stdout, "up-timeout", fmt.Sprintf("tailnet not reached within %v", upTimeout))
		return exitUp
	}
	if upErr != nil {
		defer closeServer(srv)
		if ctx.Err() != nil { // QUIT/EOF/signal while starting
			return exitOK
		}
		detail := redact(upErr.Error(), authKey)
		log.Printf("Up failed: %s", detail)
		if errors.Is(upErr, context.DeadlineExceeded) {
			proto.Err(stdout, "up-timeout", fmt.Sprintf("tailnet not reached within %v", upTimeout))
		} else {
			proto.Err(stdout, "auth", detail)
		}
		return exitUp
	}
	defer closeServer(srv)
	ip, ok := node.FirstIPv4(st.TailscaleIPs)
	if !ok {
		proto.Err(stdout, "up-timeout", "node has no Tailscale IPv4 address")
		return exitUp
	}
	log.Printf("up as %s (%v)", ip, st.TailscaleIPs)
	d := node.NewDialer(srv)

	if len(cfg.probes) > 0 {
		runProbe(ctx, d, cfg.probes, stdout)
		return exitOK
	}

	l, port, err := relay.ListenLoopbackTCP()
	if err != nil {
		proto.Err(stdout, "bind", err.Error())
		return exitBind
	}

	var udp *relay.UDPRelay
	var voiceErr error
	if cfg.voice > 0 {
		pc, err := net.ListenPacket("udp", net.JoinHostPort("127.0.0.1", strconv.Itoa(cfg.voice)))
		if err != nil {
			log.Printf("voice bind failed: %v", err)
			voiceErr = err
		} else {
			vt := netip.AddrPortFrom(cfg.target.Addr(), uint16(cfg.voice)).String()
			udp = relay.NewUDPRelay(pc, d, vt, voiceIdle)
		}
	}

	proto.Ready(stdout, port, ip)
	log.Printf("READY %d %s", port, ip)
	if voiceErr != nil { // READY is always the first line; the game works without voice
		proto.Warn(stdout, "voice-port-busy", voiceErr.Error())
	}

	// Both sockets are already bound, so starting the relays after READY
	// loses nothing (the kernel queues early connections and datagrams) and
	// guarantees any WARN/ERR they print comes after READY.
	target := cfg.target.String()
	serveTCP := func(ctx context.Context) error { return relay.ServeTCP(ctx, l, d, target) }
	var serveUDP func(context.Context) error
	if udp != nil {
		serveUDP = udp.Serve
	}
	code = serveRelays(ctx, serveTCP, serveUDP, stdout)
	log.Printf("relays stopped")
	return code
}

// serveRelays runs the game (TCP) relay and, if serveUDP is non-nil, the
// voice (UDP) relay until ctx is done. A voice failure prints
// "WARN voice-failed <detail>" and the game relay keeps running; a game relay
// failure prints "ERR relay <detail>" and returns exitRelay. Both relays have
// stopped when it returns.
func serveRelays(ctx context.Context, serveTCP, serveUDP func(context.Context) error, stdout io.Writer) int {
	var wg sync.WaitGroup
	defer wg.Wait()
	ctx, cancel := context.WithCancel(ctx)
	defer cancel() // deferred last, so it runs before wg.Wait

	tcpErr := make(chan error, 1)
	wg.Go(func() { tcpErr <- serveTCP(ctx) })
	if serveUDP != nil {
		wg.Go(func() {
			if err := serveUDP(ctx); err != nil && ctx.Err() == nil {
				log.Printf("voice relay failed: %v", err)
				proto.Warn(stdout, "voice-failed", err.Error())
			}
		})
	}

	select {
	case <-ctx.Done():
		return exitOK
	case err := <-tcpErr:
		if err != nil && ctx.Err() == nil {
			log.Printf("relay failed: %v", err)
			proto.Err(stdout, "relay", err.Error())
			return exitRelay
		}
		return exitOK
	}
}

// up runs srv.Up with upTimeout. finished is false if Up has not returned
// upGrace after its deadline.
func up(ctx context.Context, srv *tsnet.Server) (st *ipnstate.Status, err error, finished bool) {
	upCtx, cancel := context.WithTimeout(ctx, upTimeout)
	defer cancel()
	type result struct {
		st  *ipnstate.Status
		err error
	}
	ch := make(chan result, 1)
	go func() {
		st, err := srv.Up(upCtx)
		ch <- result{st, err}
	}()
	select {
	case r := <-ch:
		return r.st, r.err, true
	case <-time.After(upTimeout + upGrace):
		return nil, nil, false
	}
}

func closeServer(srv *tsnet.Server) {
	t0 := time.Now()
	err := srv.Close()
	log.Printf("tsnet closed in %v (err=%v)", time.Since(t0).Round(time.Millisecond), err)
}

// openCrashLog opens (appending, creating it and its directory if needed)
// <logDir>/cattail/helper-crash.log, the extra crash output for panics and
// fatal runtime errors.
func openCrashLog(logDir string) (*os.File, error) {
	dir := filepath.Join(logDir, "cattail")
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return nil, err
	}
	return os.OpenFile(filepath.Join(dir, "helper-crash.log"), os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o600)
}

// redact removes secret (and its part before any '?') from s.
func redact(s, secret string) string {
	if secret == "" {
		return s
	}
	s = strings.ReplaceAll(s, secret, "<redacted>")
	if base, _, ok := strings.Cut(secret, "?"); ok && base != "" {
		s = strings.ReplaceAll(s, base, "<redacted>")
	}
	return s
}
