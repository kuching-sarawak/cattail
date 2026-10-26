// Package proto implements the line protocol the helper speaks with the mod:
// READY/WARN/ERR lines on stdout and a QUIT command (or EOF) on stdin.
package proto

import (
	"bufio"
	"io"
	"net/netip"
	"strconv"
	"strings"
)

var lineBreaks = strings.NewReplacer("\r\n", " ", "\r", " ", "\n", " ")

// writeLine writes one complete line in a single Write call so concurrent
// callers never interleave partial lines.
func writeLine(w io.Writer, line string) {
	_, _ = io.WriteString(w, line+"\n")
}

// Ready writes "READY <port> <ip>\n".
func Ready(w io.Writer, port int, ip netip.Addr) {
	writeLine(w, "READY "+strconv.Itoa(port)+" "+ip.String())
}

// Warn writes "WARN <code> <detail>\n"; CR/LF in detail become a space.
func Warn(w io.Writer, code, detail string) {
	writeLine(w, "WARN "+code+" "+lineBreaks.Replace(detail))
}

// Err writes "ERR <code> <detail>\n"; CR/LF in detail become a space.
func Err(w io.Writer, code, detail string) {
	writeLine(w, "ERR "+code+" "+lineBreaks.Replace(detail))
}

// WatchStdin returns a channel that is closed when r reaches EOF (or a read
// error) or yields a line that is exactly "QUIT" (a trailing CR is allowed).
// Other lines are ignored.
func WatchStdin(r io.Reader) <-chan struct{} {
	done := make(chan struct{})
	go func() {
		defer close(done)
		sc := bufio.NewScanner(r)
		for sc.Scan() {
			if strings.TrimSuffix(sc.Text(), "\r") == "QUIT" {
				return
			}
		}
	}()
	return done
}
