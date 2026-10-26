package relay

import (
	"bytes"
	"context"
	"crypto/rand"
	"errors"
	"io"
	"net"
	"testing"
	"time"
)

// echoTCP starts a TCP echo server. Each time a server-side connection ends
// (the client closed it), a value is sent on the returned channel.
func echoTCP(t *testing.T) (string, <-chan struct{}) {
	t.Helper()
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { l.Close() })
	closed := make(chan struct{}, 16)
	go func() {
		for {
			c, err := l.Accept()
			if err != nil {
				return
			}
			go func() {
				_, _ = io.Copy(c, c)
				c.Close()
				closed <- struct{}{}
			}()
		}
	}()
	return l.Addr().String(), closed
}

func startTCPRelay(t *testing.T, d Dialer, target string) int {
	t.Helper()
	l, port, err := ListenLoopbackTCP()
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() { done <- ServeTCP(ctx, l, d, target) }()
	t.Cleanup(func() {
		cancel()
		select {
		case <-done:
		case <-time.After(2 * time.Second):
			t.Error("ServeTCP did not return after cancel")
		}
	})
	return port
}

func dialLoopback(t *testing.T, port int) net.Conn {
	t.Helper()
	c, err := net.DialTCP("tcp", nil, &net.TCPAddr{IP: net.IPv4(127, 0, 0, 1), Port: port})
	if err != nil {
		t.Fatal(err)
	}
	return c
}

func TestTCPRelayEcho(t *testing.T) {
	target, closed := echoTCP(t)
	port := startTCPRelay(t, &netDialer{}, target)
	c := dialLoopback(t, port)
	defer c.Close()

	want := make([]byte, 1<<20)
	if _, err := rand.Read(want); err != nil {
		t.Fatal(err)
	}
	werr := make(chan error, 1)
	go func() { _, err := c.Write(want); werr <- err }()

	got := make([]byte, len(want))
	_ = c.SetReadDeadline(time.Now().Add(5 * time.Second))
	if _, err := io.ReadFull(c, got); err != nil {
		t.Fatalf("read echo: %v", err)
	}
	if err := <-werr; err != nil {
		t.Fatalf("write: %v", err)
	}
	if !bytes.Equal(got, want) {
		t.Fatal("echoed bytes differ from sent bytes")
	}

	c.Close()
	select {
	case <-closed:
	case <-time.After(time.Second):
		t.Fatal("target side not closed within 1s of client close")
	}
}

// netDialer adapts net.Dialer (whose context method is DialContext) to Dialer,
// mirroring the *tsnet.Server signature used in production.
type netDialer struct{ net.Dialer }

func (d *netDialer) Dial(ctx context.Context, network, address string) (net.Conn, error) {
	return d.DialContext(ctx, network, address)
}

type failDialer struct{}

func (failDialer) Dial(context.Context, string, string) (net.Conn, error) {
	return nil, errors.New("dial refused")
}

func TestTCPRelayDialFailure(t *testing.T) {
	port := startTCPRelay(t, failDialer{}, "127.0.0.1:1")
	c := dialLoopback(t, port)
	defer c.Close()
	_ = c.SetReadDeadline(time.Now().Add(time.Second))
	n, err := c.Read(make([]byte, 1))
	if n != 0 || !errors.Is(err, io.EOF) {
		t.Fatalf("want immediate EOF, got n=%d err=%v", n, err)
	}
}
