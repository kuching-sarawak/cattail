//go:build !probe

package main

import (
	"context"
	"flag"
	"io"

	"github.com/kuching-sarawak/cattail/helper/internal/relay"
)

// registerProbe returns nil: --probe exists only in builds with -tags probe.
func registerProbe(*flag.FlagSet) *string { return nil }

// runProbe is never called without the probe tag.
func runProbe(context.Context, relay.Dialer, []string, io.Writer) {}
