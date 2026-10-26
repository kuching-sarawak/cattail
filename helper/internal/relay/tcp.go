// Package relay forwards local loopback TCP connections and UDP datagrams to a
// target reached through a Dialer (in production a *tsnet.Server).
package relay

import (
	"context"
	"errors"
	"io"
	"net"
	"sync"
	"time"
)

// Dialer opens connections to the target. *tsnet.Server satisfies it.
type Dialer interface {
	Dial(ctx context.Context, network, address string) (net.Conn, error)
}

// ListenLoopbackTCP listens on 127.0.0.1 with a kernel-chosen port and
// returns the listener and that port.
func ListenLoopbackTCP() (net.Listener, int, error) {
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return nil, 0, err
	}
	return l, l.Addr().(*net.TCPAddr).Port, nil
}

// ServeTCP accepts connections on l and relays each one to target via d.
// It takes ownership of l, closes it when ctx is done, closes every active
// connection, waits for the per-connection goroutines and returns nil.
// It returns a non-nil error only if the listener fails for another reason.
func ServeTCP(ctx context.Context, l net.Listener, d Dialer, target string) error {
	stop := context.AfterFunc(ctx, func() { l.Close() })
	defer stop()
	var wg sync.WaitGroup
	defer wg.Wait()
	for {
		c, err := l.Accept()
		if err != nil {
			if ctx.Err() != nil {
				return nil
			}
			if errors.Is(err, net.ErrClosed) {
				return err
			}
			// Transient failure (e.g. out of file descriptors): back off briefly.
			select {
			case <-ctx.Done():
				return nil
			case <-time.After(50 * time.Millisecond):
			}
			continue
		}
		wg.Go(func() { relayTCP(ctx, c, d, target) })
	}
}

func relayTCP(ctx context.Context, client net.Conn, d Dialer, target string) {
	defer client.Close()
	remote, err := d.Dial(ctx, "tcp", target)
	if err != nil {
		return
	}
	defer remote.Close()
	setNoDelay(client)
	setNoDelay(remote)

	var once sync.Once
	closeBoth := func() {
		once.Do(func() {
			client.Close()
			remote.Close()
		})
	}
	stop := context.AfterFunc(ctx, closeBoth)
	defer stop()

	var wg sync.WaitGroup
	wg.Go(func() { _, _ = io.Copy(remote, client); closeBoth() })
	wg.Go(func() { _, _ = io.Copy(client, remote); closeBoth() })
	wg.Wait()
}

func setNoDelay(c net.Conn) {
	if tc, ok := c.(*net.TCPConn); ok {
		_ = tc.SetNoDelay(true)
	}
}
