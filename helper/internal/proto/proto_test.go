package proto

import (
	"bytes"
	"io"
	"net/netip"
	"strings"
	"testing"
	"time"
)

func TestReadyLine(t *testing.T) {
	var buf bytes.Buffer
	Ready(&buf, 51234, netip.MustParseAddr("100.1.2.3"))
	if got, want := buf.String(), "READY 51234 100.1.2.3\n"; got != want {
		t.Errorf("Ready wrote %q, want %q", got, want)
	}
}

func TestWarnErrLines(t *testing.T) {
	var buf bytes.Buffer
	Warn(&buf, "voice-port-busy", "detail")
	if got, want := buf.String(), "WARN voice-port-busy detail\n"; got != want {
		t.Errorf("Warn wrote %q, want %q", got, want)
	}

	buf.Reset()
	Err(&buf, "up-timeout", "detail")
	if got, want := buf.String(), "ERR up-timeout detail\n"; got != want {
		t.Errorf("Err wrote %q, want %q", got, want)
	}

	buf.Reset()
	Err(&buf, "up-timeout", "line one\nline two\r\nline three\rend")
	if got, want := buf.String(), "ERR up-timeout line one line two line three end\n"; got != want {
		t.Errorf("Err with newlines wrote %q, want %q", got, want)
	}
}

func waitClosed(t *testing.T, ch <-chan struct{}) {
	t.Helper()
	select {
	case _, ok := <-ch:
		if ok {
			t.Fatal("channel received a value instead of closing")
		}
	case <-time.After(2 * time.Second):
		t.Fatal("channel did not close")
	}
}

func TestWatchStdinQuit(t *testing.T) {
	pr, pw := io.Pipe()
	defer pw.Close()
	ch := WatchStdin(pr)

	if _, err := io.WriteString(pw, "hello\nQUITX\n"); err != nil {
		t.Fatal(err)
	}
	select {
	case <-ch:
		t.Fatal("channel closed before QUIT")
	case <-time.After(50 * time.Millisecond):
	}

	if _, err := io.WriteString(pw, "QUIT\r\n"); err != nil {
		t.Fatal(err)
	}
	waitClosed(t, ch)
}

func TestWatchStdinEOF(t *testing.T) {
	waitClosed(t, WatchStdin(strings.NewReader("something\nelse\n")))
}
