package relay

import (
	"bytes"
	"context"
	"fmt"
	"net"
	"testing"
	"time"
)

// echoUDP starts a UDP echo server and returns its address.
func echoUDP(t *testing.T) string {
	t.Helper()
	pc, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { pc.Close() })
	go func() {
		buf := make([]byte, 65535)
		for {
			n, addr, err := pc.ReadFrom(buf)
			if err != nil {
				return
			}
			_, _ = pc.WriteTo(buf[:n], addr)
		}
	}()
	return pc.LocalAddr().String()
}

func startUDPRelay(t *testing.T, target string, idle time.Duration) (*UDPRelay, net.Addr) {
	t.Helper()
	pc, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	r := NewUDPRelay(pc, &netDialer{}, target, idle)
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() { done <- r.Serve(ctx) }()
	t.Cleanup(func() {
		cancel()
		select {
		case <-done:
		case <-time.After(2 * time.Second):
			t.Error("Serve did not return after cancel")
		}
	})
	return r, pc.LocalAddr()
}

func udpClient(t *testing.T, relay net.Addr) *net.UDPConn {
	t.Helper()
	c, err := net.DialUDP("udp", nil, relay.(*net.UDPAddr))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { c.Close() })
	return c
}

func TestUDPRelayTwoSources(t *testing.T) {
	r, addr := startUDPRelay(t, echoUDP(t), time.Minute)
	clients := []*net.UDPConn{udpClient(t, addr), udpClient(t, addr)}
	buf := make([]byte, 65535)
	for i := 0; i < 5; i++ {
		// Send from both before reading so replies could interleave.
		for ci, c := range clients {
			if _, err := c.Write([]byte(fmt.Sprintf("client%d-pkt%d", ci, i))); err != nil {
				t.Fatal(err)
			}
		}
		for ci, c := range clients {
			_ = c.SetReadDeadline(time.Now().Add(2 * time.Second))
			n, err := c.Read(buf)
			if err != nil {
				t.Fatalf("client %d read: %v", ci, err)
			}
			want := []byte(fmt.Sprintf("client%d-pkt%d", ci, i))
			if !bytes.Equal(buf[:n], want) {
				t.Fatalf("client %d got %q, want %q", ci, buf[:n], want)
			}
		}
	}
	if got := r.Sessions(); got != 2 {
		t.Fatalf("Sessions() = %d, want 2", got)
	}
}

func TestUDPRelayIdleExpiry(t *testing.T) {
	r, addr := startUDPRelay(t, echoUDP(t), 200*time.Millisecond)
	c := udpClient(t, addr)
	if _, err := c.Write([]byte("ping")); err != nil {
		t.Fatal(err)
	}
	buf := make([]byte, 16)
	_ = c.SetReadDeadline(time.Now().Add(2 * time.Second))
	n, err := c.Read(buf)
	if err != nil || string(buf[:n]) != "ping" {
		t.Fatalf("echo: n=%d err=%v data=%q", n, err, buf[:n])
	}
	last := time.Now()
	if got := r.Sessions(); got != 1 {
		t.Fatalf("Sessions() = %d after packet, want 1", got)
	}
	for r.Sessions() != 0 {
		if time.Since(last) > time.Second {
			t.Fatalf("session still present %v after last packet", time.Since(last))
		}
		time.Sleep(10 * time.Millisecond)
	}
}

func TestUDPRelayServeReturnsOnPacketConnError(t *testing.T) {
	pc, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	r := NewUDPRelay(pc, &netDialer{}, echoUDP(t), time.Minute)
	done := make(chan error, 1)
	go func() { done <- r.Serve(context.Background()) }()
	time.Sleep(20 * time.Millisecond)
	pc.Close()
	select {
	case err := <-done:
		if err == nil {
			t.Fatal("want error from closed PacketConn, got nil")
		}
	case <-time.After(2 * time.Second):
		t.Fatal("Serve did not return after PacketConn closed")
	}
}
