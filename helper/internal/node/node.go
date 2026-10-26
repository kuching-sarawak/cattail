package node

import (
	"context"
	"errors"
	"fmt"
	"log"
	"net"
	"net/netip"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"sync"

	"tailscale.com/client/local"
	"tailscale.com/ipn/store/mem"
	"tailscale.com/tsnet"
)

// TagPattern is the form of the tag given with --tag, the only tag a helper
// node advertises; the tailnet policy grants it the game server's port and
// nothing else.
const TagPattern = `^tag:[a-z0-9-]+$`

var tagRE = regexp.MustCompile(TagPattern)

// ErrBadTag is returned by New for a tag that does not match TagPattern.
var ErrBadTag = errors.New("tag must match " + TagPattern)

// ValidTag reports whether tag matches TagPattern.
func ValidTag(tag string) bool { return tagRE.MatchString(tag) }

// Options configures the helper's tsnet node.
type Options struct {
	Hostname string // tailnet hostname, already normalised (see NormalizeHostname)
	AuthKey  string // auth key or OAuth client secret; never logged
	Tag      string // the tag given with --tag; must match TagPattern
	LogDir   string // base directory; logs and state go under <LogDir>/cattail/
}

// New returns an unstarted tsnet server for a helper node: ephemeral,
// in-memory state, tagged with opts.Tag only, no web client. tsnet's backend and
// user logs both go to <LogDir>/cattail/helper.log (rotated, 3 × 2 MB); its
// scratch directory is <LogDir>/cattail/state, so nothing is written under the
// user's config directory. It returns ErrBadTag when opts.Tag does not match
// TagPattern.
func New(opts Options) (*tsnet.Server, error) {
	if !ValidTag(opts.Tag) {
		return nil, ErrBadTag
	}
	base := filepath.Join(opts.LogDir, "cattail")
	state := filepath.Join(base, "state")
	// LocalBackend asks logpolicy.LogsDir for a directory and, unless
	// TS_LOGS_DIR names an existing one, creates ~/.local/share/tailscale
	// (Linux) or picks %LocalAppData%\Tailscale (Windows). Point it at our
	// state dir instead (process-wide; the helper runs one node).
	if err := os.MkdirAll(state, 0o700); err == nil {
		_ = os.Setenv("TS_LOGS_DIR", state)
	}
	w := Log(opts.LogDir)
	backend := log.New(w, "", log.LstdFlags|log.Lmicroseconds)
	user := log.New(w, "user: ", log.LstdFlags|log.Lmicroseconds)
	return &tsnet.Server{
		Dir:           state,
		Store:         new(mem.Store),
		Hostname:      opts.Hostname,
		AuthKey:       opts.AuthKey,
		Ephemeral:     true,
		AdvertiseTags: []string{opts.Tag},
		RunWebClient:  false,
		Logf:          stripNUL(backend.Printf),
		UserLogf:      stripNUL(user.Printf),
	}, nil
}

// stripNUL drops NUL bytes from the format, so tailscale's structured-log
// marker "[v\x00JSON]" does not turn helper.log into a "binary" file.
func stripNUL(logf func(string, ...any)) func(string, ...any) {
	return func(format string, args ...any) {
		logf(strings.ReplaceAll(format, "\x00", ""), args...)
	}
}

var (
	logsMu sync.Mutex
	logs   = map[string]*RotatingFile{}
)

// Log returns the shared rotating writer for <logDir>/cattail/helper.log, so
// every logger in the process (tsnet and the standard log package) rotates
// the same file.
func Log(logDir string) *RotatingFile {
	p := filepath.Join(logDir, "cattail", "helper.log")
	logsMu.Lock()
	defer logsMu.Unlock()
	w, ok := logs[p]
	if !ok {
		w = NewRotatingFile(p, MaxLogSize, MaxLogBackups)
		logs[p] = w
	}
	return w
}

// NormalizeHostname sanitises a --hostname value that the mod has normally
// already built with Hostname: it strips one leading "mc-" (case-insensitive)
// and applies Hostname again, so the result is idempotent.
func NormalizeHostname(s string) string {
	s = strings.ToLower(s)
	return Hostname(strings.TrimPrefix(s, "mc-"))
}

// FirstIPv4 returns the first IPv4 address in ips.
func FirstIPv4(ips []netip.Addr) (netip.Addr, bool) {
	for _, ip := range ips {
		if ip.Is4() {
			return ip, true
		}
	}
	return netip.Addr{}, false
}

// ErrNotPeer is returned by Dialer.Dial for a destination that is not a
// peer in the node's netmap.
var ErrNotPeer = errors.New("destination is not a tailnet peer")

// Dialer dials over the tailnet only. tsnet's own Dial falls back to the host
// network stack for addresses that are not peers (on a machine running its
// own Tailscale that would bypass the policy for the tag given with --tag), so this wrapper
// first checks the destination is a peer of this node and refuses otherwise.
// The address must be an IP literal with a port.
type Dialer struct {
	srv *tsnet.Server

	once sync.Once
	lc   *local.Client
	err  error
}

// inProcessOnly makes lc talk to the in-process tsnet backend and nothing else. Without
// OmitAuth, every request first calls safesocket.LocalTCPPortAndToken, which on macOS runs
// `lsof` in a subprocess to look for the Tailscale desktop app's token (a token the in-process
// backend never asks for). That fork intermittently never returns in the child, which keeps
// Apple's fork handlers spinning (Network.framework, then os_log), and the request, hence the
// dial, waits on it forever: seen on macOS 26 as a join or a server-list ping that never
// completes, with a second cattail-helper process at 100% CPU. OmitAuth is the documented
// switch for a client whose Dial is set, as tsnet's is. Windows and Linux never take that path.
func inProcessOnly(lc *local.Client) { lc.OmitAuth = true }

// NewDialer returns a Dialer for srv. srv must have been brought Up.
func NewDialer(srv *tsnet.Server) *Dialer { return &Dialer{srv: srv} }

// Dial connects to address (ip:port) over the tailnet.
func (d *Dialer) Dial(ctx context.Context, network, address string) (net.Conn, error) {
	ap, err := netip.ParseAddrPort(address)
	if err != nil {
		return nil, fmt.Errorf("dial %s: need ip:port: %w", address, err)
	}
	d.once.Do(func() {
		d.lc, d.err = d.srv.LocalClient()
		if d.lc != nil {
			inProcessOnly(d.lc)
		}
	})
	if d.err != nil {
		return nil, d.err
	}
	if _, err := d.lc.WhoIs(ctx, ap.Addr().String()); err != nil {
		if errors.Is(err, local.ErrPeerNotFound) {
			return nil, fmt.Errorf("dial %s: %w", address, ErrNotPeer)
		}
		return nil, fmt.Errorf("dial %s: whois: %w", address, err)
	}
	return d.srv.Dial(ctx, network, address)
}
