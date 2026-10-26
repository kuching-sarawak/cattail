//go:build probe

package main

import (
	"context"
	"flag"
	"io"

	"github.com/kuching-sarawak/cattail/helper/internal/relay"
)

// registerProbe adds the hidden --probe flag (probe builds only).
func registerProbe(fs *flag.FlagSet) *string {
	return fs.String("probe", "", "comma-separated `ip:port` list to test-dial after Up, then exit")
}

// runProbe dials each address over the tailnet with a 5 s timeout and prints
// "PROBE <addr> allowed" or "PROBE <addr> denied" for each.
func runProbe(ctx context.Context, d relay.Dialer, addrs []string, w io.Writer) {
	for _, a := range addrs {
		pctx, cancel := context.WithTimeout(ctx, probeTimeout)
		c, err := d.Dial(pctx, "tcp", a)
		cancel()
		verdict := "denied"
		if err == nil {
			c.Close()
			verdict = "allowed"
		}
		_, _ = io.WriteString(w, "PROBE "+a+" "+verdict+"\n")
	}
}
