package main

import (
	"bytes"
	"context"
	"errors"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
	"time"
)

func runForTest(t *testing.T, env map[string]string, args ...string) (int, string) {
	t.Helper()
	var out bytes.Buffer
	getenv := func(k string) string { return env[k] }
	code := run(context.Background(), args, strings.NewReader(""), &out, getenv)
	return code, out.String()
}

func TestUsageErrors(t *testing.T) {
	key := map[string]string{"TS_AUTHKEY": "test-auth-key"}
	for name, args := range map[string][]string{
		"no target":        {"--tag", "tag:example"},
		"bad target":       {"--target", "nonsense", "--tag", "tag:example"},
		"hostname target":  {"--target", "server.example:25565", "--tag", "tag:example"},
		"public ip target": {"--target", "8.8.8.8:25565", "--tag", "tag:example"},
		"zero port":        {"--target", "100.101.102.103:0", "--tag", "tag:example"},
		"voice too big":    {"--target", "100.101.102.103:25565", "--tag", "tag:example", "--voice", "70000"},
		"voice negative":   {"--target", "100.101.102.103:25565", "--tag", "tag:example", "--voice", "-1"},
		"unknown flag":     {"--target", "100.101.102.103:25565", "--tag", "tag:example", "--bogus"},
		"positional arg":   {"--target", "100.101.102.103:25565", "--tag", "tag:example", "extra"},
		"help":             {"-h"},
		"missing tag":      {"--target", "198.51.100.7:25565"},
		"bad tag":          {"--target", "198.51.100.7:25565", "--tag", "mc-player"},
	} {
		t.Run(name, func(t *testing.T) {
			code, out := runForTest(t, key, args...)
			if code != 2 {
				t.Errorf("exit %d, want 2", code)
			}
			if !strings.HasPrefix(out, "ERR usage ") || strings.Count(out, "\n") != 1 {
				t.Errorf("stdout %q, want one ERR usage line", out)
			}
			if strings.HasSuffix(name, " tag") && out != "ERR usage tag must match ^tag:[a-z0-9-]+$\n" {
				t.Errorf("stdout %q, want the tag usage error", out)
			}
		})
	}
}

func TestMissingAuthKey(t *testing.T) {
	code, out := runForTest(t, nil, "--target", "100.101.102.103:25565", "--tag", "tag:example", "--log-dir", t.TempDir())
	if code != 3 {
		t.Errorf("exit %d, want 3", code)
	}
	if !strings.HasPrefix(out, "ERR auth ") {
		t.Errorf("stdout %q", out)
	}
}

func TestVersion(t *testing.T) {
	code, out := runForTest(t, nil, "--version")
	if code != 0 || out != "cattail-helper "+version+"\n" {
		t.Errorf("exit %d, stdout %q", code, out)
	}
}

func TestRedact(t *testing.T) {
	got := redact("oauth failed for test-secret-abc?ephemeral=true: 401", "test-secret-abc?ephemeral=true")
	if strings.Contains(got, "test-secret-abc") {
		t.Errorf("secret survived: %q", got)
	}
	if got := redact("x", ""); got != "x" {
		t.Errorf("empty secret changed text: %q", got)
	}
}

func TestServeRelaysVoiceFailureKeepsGameRelay(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	var out bytes.Buffer
	stdout := &lockedWriter{w: &out}
	tcpStopped := make(chan struct{})
	serveTCP := func(ctx context.Context) error {
		<-ctx.Done() // a healthy game relay runs until shutdown
		close(tcpStopped)
		return nil
	}
	serveUDP := func(context.Context) error { return errors.New("udp boom") }

	done := make(chan int, 1)
	go func() { done <- serveRelays(ctx, serveTCP, serveUDP, stdout) }()

	// The WARN arrives and the run keeps going.
	deadline := time.Now().Add(2 * time.Second)
	for !strings.Contains(readLocked(stdout, &out), "WARN voice-failed udp boom\n") {
		if time.Now().After(deadline) {
			t.Fatalf("no WARN voice-failed; stdout %q", readLocked(stdout, &out))
		}
		time.Sleep(5 * time.Millisecond)
	}
	select {
	case code := <-done:
		t.Fatalf("run ended (exit %d) after a voice failure", code)
	case <-tcpStopped:
		t.Fatal("game relay was stopped after a voice failure")
	case <-time.After(200 * time.Millisecond):
	}

	cancel() // QUIT/EOF/signal
	select {
	case code := <-done:
		if code != exitOK {
			t.Errorf("exit %d after shutdown, want 0", code)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("serveRelays did not return after cancel")
	}
	if got := readLocked(stdout, &out); got != "WARN voice-failed udp boom\n" {
		t.Errorf("stdout %q, want only the WARN line", got)
	}
}

func TestServeRelaysTCPFailureIsFatal(t *testing.T) {
	var out bytes.Buffer
	stdout := &lockedWriter{w: &out}
	udpStopped := make(chan struct{})
	serveTCP := func(context.Context) error { return errors.New("tcp boom") }
	serveUDP := func(ctx context.Context) error {
		<-ctx.Done()
		close(udpStopped)
		return nil
	}
	code := serveRelays(context.Background(), serveTCP, serveUDP, stdout)
	if code != exitRelay {
		t.Errorf("exit %d, want %d", code, exitRelay)
	}
	if got := out.String(); got != "ERR relay tcp boom\n" {
		t.Errorf("stdout %q", got)
	}
	select {
	case <-udpStopped:
	default:
		t.Error("voice relay still running after serveRelays returned")
	}
}

func TestServeRelaysNoVoice(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	var out bytes.Buffer
	code := serveRelays(ctx, func(ctx context.Context) error { <-ctx.Done(); return nil }, nil, &lockedWriter{w: &out})
	if code != exitOK || out.Len() != 0 {
		t.Errorf("exit %d, stdout %q", code, out.String())
	}
}

func readLocked(l *lockedWriter, b *bytes.Buffer) string {
	l.mu.Lock()
	defer l.mu.Unlock()
	return b.String()
}

func TestCrashLogFileIsCreated(t *testing.T) {
	dir := t.TempDir()
	f, err := openCrashLog(dir)
	if err != nil {
		t.Fatalf("openCrashLog: %v", err)
	}
	defer f.Close()
	want := filepath.Join(dir, "cattail", "helper-crash.log")
	if f.Name() != want {
		t.Errorf("crash log %q, want %q", f.Name(), want)
	}
	fi, err := os.Stat(want)
	if err != nil {
		t.Fatalf("stat: %v", err)
	}
	if fi.Mode().Perm()&0o077 != 0 && runtime.GOOS != "windows" {
		t.Errorf("crash log mode %v, want no group/other access", fi.Mode().Perm())
	}
	if _, err := f.WriteString("probe\n"); err != nil {
		t.Errorf("crash log not writable: %v", err)
	}
}
