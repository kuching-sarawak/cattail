package node

import (
	"net/netip"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"testing"

	"tailscale.com/ipn/store/mem"
)

func TestNewNodeShape(t *testing.T) {
	t.Setenv("TS_LOGS_DIR", "") // restored after the test; New sets it
	dir := t.TempDir()
	srv, err := New(Options{Hostname: "mc-alice", AuthKey: "test-auth-key", Tag: "tag:example", LogDir: dir})
	if err != nil {
		t.Fatal(err)
	}
	if !srv.Ephemeral {
		t.Error("Ephemeral = false")
	}
	if _, ok := srv.Store.(*mem.Store); !ok {
		t.Errorf("Store = %T, want *mem.Store", srv.Store)
	}
	if srv.RunWebClient {
		t.Error("RunWebClient = true")
	}
	if srv.Hostname != "mc-alice" || srv.AuthKey != "test-auth-key" {
		t.Errorf("Hostname/AuthKey not passed through: %q", srv.Hostname)
	}
	if want := filepath.Join(dir, "cattail", "state"); srv.Dir != want {
		t.Errorf("Dir = %q, want %q", srv.Dir, want)
	}
	if got := os.Getenv("TS_LOGS_DIR"); got != srv.Dir {
		t.Errorf("TS_LOGS_DIR = %q, want the state dir %q", got, srv.Dir)
	}
	if srv.Logf == nil || srv.UserLogf == nil {
		t.Error("Logf/UserLogf must be set (nil UserLogf means log.Printf to stderr)")
	}
}

func TestAdvertiseTagsFromConfig(t *testing.T) {
	t.Setenv("TS_LOGS_DIR", "")
	srv, err := New(Options{Hostname: "mc-alice", Tag: "tag:example", LogDir: t.TempDir()})
	if err != nil {
		t.Fatal(err)
	}
	if !slices.Equal(srv.AdvertiseTags, []string{"tag:example"}) {
		t.Errorf("AdvertiseTags = %v, want [tag:example]", srv.AdvertiseTags)
	}
}

func TestNewRejectsBadTag(t *testing.T) {
	t.Setenv("TS_LOGS_DIR", "")
	for _, tag := range []string{"", "mc-player", "tag:", "tag:Example", "tag:a b", "tag:a,tag:b", " tag:example"} {
		srv, err := New(Options{Hostname: "mc-alice", Tag: tag, LogDir: t.TempDir()})
		if err == nil || srv != nil {
			t.Errorf("New(Tag %q) = %v, %v; want an error", tag, srv, err)
			continue
		}
		if err.Error() != "tag must match ^tag:[a-z0-9-]+$" {
			t.Errorf("New(Tag %q) error %q", tag, err)
		}
	}
}

func TestNewLogsToHelperLog(t *testing.T) {
	dir := t.TempDir()
	srv, err := New(Options{Hostname: "mc-bob", Tag: "tag:example", LogDir: dir})
	if err != nil {
		t.Fatal(err)
	}
	srv.Logf("backend %d", 1)
	srv.Logf("[v\x00JSON]%d%s", 1, `{"x":1}`) // tailscale's structured-log marker
	srv.UserLogf("user %d", 2)
	p := filepath.Join(dir, "cattail", "helper.log")
	data, err := os.ReadFile(p)
	if err != nil {
		t.Fatal(err)
	}
	for _, want := range []string{"backend 1\n", "[vJSON]1{\"x\":1}\n", "user: "} {
		if !strings.Contains(string(data), want) {
			t.Errorf("helper.log lacks %q:\n%s", want, data)
		}
	}
	if strings.ContainsRune(string(data), 0) {
		t.Error("helper.log contains NUL bytes")
	}
}

func TestNormalizeHostname(t *testing.T) {
	for in, want := range map[string]string{
		"mc-alice":                      "mc-alice",
		"mc-claude-live":                "mc-claude-live",
		"MC-Alice_Smith":                "mc-alice-smith",
		"alice":                         "mc-alice",
		"mc-mc-foo":                     "mc-mc-foo",
		"":                              "mc-player",
		"mc-":                           "mc-player",
		"mc-abcdefghijklmnopqrstuvwxyz": "mc-abcdefghijklmnop",
	} {
		if got := NormalizeHostname(in); got != want {
			t.Errorf("NormalizeHostname(%q) = %q, want %q", in, got, want)
		}
		// Idempotent: the mod already sanitised it once.
		if got := NormalizeHostname(NormalizeHostname(in)); got != want {
			t.Errorf("not idempotent for %q: %q", in, got)
		}
	}
}

func TestFirstIPv4(t *testing.T) {
	v6 := netip.MustParseAddr("fd7a:115c:a1e0::1")
	v4 := netip.MustParseAddr("100.101.102.103")
	if got, ok := FirstIPv4([]netip.Addr{v6, v4}); !ok || got != v4 {
		t.Errorf("FirstIPv4 = %v, %v", got, ok)
	}
	if _, ok := FirstIPv4([]netip.Addr{v6}); ok {
		t.Error("FirstIPv4 found an IPv4 in an IPv6-only list")
	}
}
