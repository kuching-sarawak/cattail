package relay

import (
	"context"
	"net"
	"net/netip"
	"sync"
	"sync/atomic"
	"time"
)

const maxDatagram = 65535

// UDPRelay forwards datagrams arriving on a local PacketConn to target, with
// one dialed connection (session) per local source address. Replies on a
// session are written back to the source it belongs to.
type UDPRelay struct {
	pc     net.PacketConn
	d      Dialer
	target string
	idle   time.Duration

	mu       sync.Mutex
	sessions map[netip.AddrPort]*udpSession
	wg       sync.WaitGroup
}

type udpSession struct {
	conn net.Conn
	last atomic.Int64 // unix nanoseconds of the last packet in either direction
}

func (s *udpSession) touch() { s.last.Store(time.Now().UnixNano()) }

// NewUDPRelay returns a relay reading from pc. A session is closed after idle
// passes with no packets in either direction.
func NewUDPRelay(pc net.PacketConn, d Dialer, target string, idle time.Duration) *UDPRelay {
	return &UDPRelay{
		pc:       pc,
		d:        d,
		target:   target,
		idle:     idle,
		sessions: make(map[netip.AddrPort]*udpSession),
	}
}

// Sessions returns the number of live sessions.
func (r *UDPRelay) Sessions() int {
	r.mu.Lock()
	defer r.mu.Unlock()
	return len(r.sessions)
}

// Serve relays until ctx is done or pc fails. It takes ownership of pc and
// closes it, closes all sessions and waits for their goroutines before
// returning. It returns nil when stopped by ctx.
func (r *UDPRelay) Serve(ctx context.Context) error {
	stop := context.AfterFunc(ctx, func() { r.pc.Close() })
	defer stop()
	defer r.wg.Wait()
	defer r.closeAll()
	// expire must also stop when Serve returns because pc failed.
	expCtx, cancelExp := context.WithCancel(ctx)
	defer cancelExp()

	r.wg.Go(func() { r.expire(expCtx) })

	buf := make([]byte, maxDatagram)
	for {
		n, addr, err := r.pc.ReadFrom(buf)
		if err != nil {
			if ctx.Err() != nil {
				return nil
			}
			return err
		}
		src, ok := addrPort(addr)
		if !ok {
			continue
		}
		s := r.session(ctx, src)
		if s == nil {
			continue // dial failed; drop the datagram
		}
		s.touch()
		_, _ = s.conn.Write(buf[:n])
	}
}

// session returns the session for src, dialing a new one if needed.
func (r *UDPRelay) session(ctx context.Context, src netip.AddrPort) *udpSession {
	r.mu.Lock()
	s := r.sessions[src]
	r.mu.Unlock()
	if s != nil {
		return s
	}
	conn, err := r.d.Dial(ctx, "udp", r.target)
	if err != nil {
		return nil
	}
	s = &udpSession{conn: conn}
	s.touch()
	r.mu.Lock()
	r.sessions[src] = s
	r.mu.Unlock()
	r.wg.Go(func() { r.readReplies(src, s) })
	return s
}

// readReplies copies datagrams from the session back to its source until the
// session conn is closed or fails, then removes the session.
func (r *UDPRelay) readReplies(src netip.AddrPort, s *udpSession) {
	defer r.remove(src, s)
	dst := net.UDPAddrFromAddrPort(src)
	buf := make([]byte, maxDatagram)
	for {
		n, err := s.conn.Read(buf)
		if err != nil {
			return
		}
		s.touch()
		if _, err := r.pc.WriteTo(buf[:n], dst); err != nil {
			return
		}
	}
}

func (r *UDPRelay) remove(src netip.AddrPort, s *udpSession) {
	r.mu.Lock()
	if r.sessions[src] == s {
		delete(r.sessions, src)
	}
	r.mu.Unlock()
	s.conn.Close()
}

// expire closes sessions idle for longer than r.idle, checking every idle/2.
func (r *UDPRelay) expire(ctx context.Context) {
	t := time.NewTicker(max(r.idle/2, time.Millisecond))
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case now := <-t.C:
			cutoff := now.Add(-r.idle).UnixNano()
			r.mu.Lock()
			for src, s := range r.sessions {
				if s.last.Load() < cutoff {
					delete(r.sessions, src)
					s.conn.Close() // its reader goroutine then exits
				}
			}
			r.mu.Unlock()
		}
	}
}

func (r *UDPRelay) closeAll() {
	r.mu.Lock()
	defer r.mu.Unlock()
	for src, s := range r.sessions {
		delete(r.sessions, src)
		s.conn.Close()
	}
}

func addrPort(a net.Addr) (netip.AddrPort, bool) {
	if ua, ok := a.(*net.UDPAddr); ok {
		ap := ua.AddrPort()
		return ap, ap.IsValid()
	}
	ap, err := netip.ParseAddrPort(a.String())
	return ap, err == nil
}
