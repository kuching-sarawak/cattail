//go:build integration

// Live test against a tailnet. Opt-in: it is skipped unless CATTAIL_TEST_TARGET
// is set. Environment:
//
//	CATTAIL_TEST_TARGET   ip:port of a Minecraft server on the tailnet that the
//	                     policy grants tag:mc-player (required, else SKIP)
//	CATTAIL_TEST_NONPEER  ip:port of a tailnet address that is NOT a peer of a
//	                     tag:mc-player node (optional; that check is skipped
//	                     when unset)
//	TS_AUTHKEY           OAuth client secret with
//	                     "?ephemeral=true&preauthorized=true" appended
//
// The denied-port check dials port 22 on the target's host, which the policy
// must not grant. Run with:
//
//	go test -tags integration ./internal/node/ -run TestLive -v
package node

import (
	"bufio"
	"bytes"
	"context"
	"encoding/binary"
	"errors"
	"io"
	"net"
	"os"
	"strconv"
	"strings"
	"testing"
	"time"
)

const liveHostname = "mc-claude-itest"

func TestLive(t *testing.T) {
	liveTarget := os.Getenv("CATTAIL_TEST_TARGET") // Minecraft server allowed by the policy
	if liveTarget == "" {
		t.Skip("set CATTAIL_TEST_TARGET=<ip:port> (and CATTAIL_TEST_NONPEER) to run the live tailnet test")
	}
	liveNonPeer := os.Getenv("CATTAIL_TEST_NONPEER") // not a peer of a tag:mc-player node
	targetHost, targetPortStr, err := net.SplitHostPort(liveTarget)
	if err != nil {
		t.Fatalf("CATTAIL_TEST_TARGET=%q: %v", liveTarget, err)
	}
	targetPort, err := strconv.ParseUint(targetPortStr, 10, 16)
	if err != nil {
		t.Fatalf("CATTAIL_TEST_TARGET=%q: bad port: %v", liveTarget, err)
	}
	liveDenied := net.JoinHostPort(targetHost, "22") // same peer, port not granted
	if liveNonPeer != "" {
		if _, _, err := net.SplitHostPort(liveNonPeer); err != nil {
			t.Fatalf("CATTAIL_TEST_NONPEER=%q: %v", liveNonPeer, err)
		}
	}

	key := os.Getenv("TS_AUTHKEY")
	if key == "" {
		t.Fatal("TS_AUTHKEY is not set")
	}
	srv, err := New(Options{Hostname: liveHostname, AuthKey: key, Tag: "tag:mc-player", LogDir: t.TempDir()})
	if err != nil {
		t.Fatal(err)
	}
	defer srv.Close()

	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	start := time.Now()
	st, err := srv.Up(ctx)
	if err != nil {
		t.Fatalf("Up: %v", err)
	}
	t.Logf("up in %v, ips %v", time.Since(start).Round(time.Millisecond), st.TailscaleIPs)
	if _, ok := FirstIPv4(st.TailscaleIPs); !ok {
		t.Fatalf("no IPv4 in %v", st.TailscaleIPs)
	}

	d := NewDialer(srv)

	// Allowed: a Minecraft server-list status exchange works end to end.
	dctx, dcancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer dcancel()
	c, err := d.Dial(dctx, "tcp", liveTarget)
	if err != nil {
		t.Fatalf("Dial %s: %v", liveTarget, err)
	}
	_ = c.SetDeadline(time.Now().Add(10 * time.Second))
	js, err := statusPing(c, targetHost, uint16(targetPort))
	c.Close()
	if err != nil {
		t.Fatalf("status ping: %v", err)
	}
	if !strings.Contains(js, `"version"`) {
		t.Fatalf("status JSON has no \"version\": %.200s", js)
	}

	// Denied by the policy: the peer drops the SYN, so the dial must fail
	// within 5 s (we give it exactly that budget).
	t0 := time.Now()
	pctx, pcancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer pcancel()
	if c, err := d.Dial(pctx, "tcp", liveDenied); err == nil {
		c.Close()
		t.Fatalf("Dial %s succeeded; policy should deny it", liveDenied)
	}
	if el := time.Since(t0); el > 5500*time.Millisecond {
		t.Fatalf("denied dial took %v, want <= 5 s", el)
	}

	// Not a peer: must be refused locally, never dialled via the host network.
	if liveNonPeer == "" {
		t.Log("CATTAIL_TEST_NONPEER not set; skipping the non-peer check")
	} else {
		nctx, ncancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer ncancel()
		if c, err := d.Dial(nctx, "tcp", liveNonPeer); err == nil {
			c.Close()
			t.Fatalf("Dial %s succeeded; non-peers must be refused", liveNonPeer)
		} else if !errors.Is(err, ErrNotPeer) {
			t.Fatalf("Dial %s: got %v, want ErrNotPeer", liveNonPeer, err)
		}
	}

	if err := srv.Close(); err != nil {
		t.Fatalf("Close: %v", err)
	}
}

// statusPing performs a Minecraft server-list status request on c and returns
// the JSON response.
func statusPing(c net.Conn, host string, port uint16) (string, error) {
	var body bytes.Buffer
	body.WriteByte(0x00) // handshake packet id
	body.Write(binary.AppendUvarint(nil, 767))
	body.Write(binary.AppendUvarint(nil, uint64(len(host))))
	body.WriteString(host)
	_ = binary.Write(&body, binary.BigEndian, port)
	body.WriteByte(1) // next state: status
	pkt := binary.AppendUvarint(nil, uint64(body.Len()))
	pkt = append(pkt, body.Bytes()...)
	pkt = append(pkt, 0x01, 0x00) // status request: length 1, id 0
	if _, err := c.Write(pkt); err != nil {
		return "", err
	}
	r := bufio.NewReader(c)
	if _, err := binary.ReadUvarint(r); err != nil { // packet length
		return "", err
	}
	id, err := binary.ReadUvarint(r)
	if err != nil {
		return "", err
	}
	if id != 0 {
		return "", errors.New("unexpected packet id")
	}
	n, err := binary.ReadUvarint(r)
	if err != nil {
		return "", err
	}
	if n > 1<<20 {
		return "", errors.New("status JSON too large")
	}
	js := make([]byte, n)
	if _, err := io.ReadFull(r, js); err != nil {
		return "", err
	}
	return string(js), nil
}
