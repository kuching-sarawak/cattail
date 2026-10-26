package node

import (
	"bytes"
	"os"
	"path/filepath"
	"testing"
)

func TestRotatingFileRotates(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "helper.log")
	const max = 1000
	w := NewRotatingFile(path, max, 2)
	defer w.Close()

	line := bytes.Repeat([]byte("x"), 99)
	line = append(line, '\n') // 100 bytes
	for range 45 {            // 4,500 bytes: rotates several times
		if n, err := w.Write(line); err != nil || n != len(line) {
			t.Fatalf("Write = %d, %v", n, err)
		}
	}

	for _, name := range []string{"helper.log", "helper.log.1", "helper.log.2"} {
		fi, err := os.Stat(filepath.Join(dir, name))
		if err != nil {
			t.Fatalf("%s: %v", name, err)
		}
		if fi.Size() > max {
			t.Errorf("%s is %d bytes, want <= %d", name, fi.Size(), max)
		}
	}
	if _, err := os.Stat(filepath.Join(dir, "helper.log.3")); !os.IsNotExist(err) {
		t.Errorf("helper.log.3 exists (err %v); only 2 old files may be kept", err)
	}
	// 45 lines, 10 per full file: current holds 5, .1 and .2 hold 10 each.
	if got := size(t, path); got != 500 {
		t.Errorf("current size %d, want 500", got)
	}
	if got := size(t, path+".1"); got != 1000 {
		t.Errorf(".1 size %d, want 1000", got)
	}
}

func TestRotatingFileDefaultSizeTwoMB(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "helper.log")
	w := NewRotatingFile(path, MaxLogSize, MaxLogBackups)
	defer w.Close()
	chunk := bytes.Repeat([]byte("y"), 64<<10)
	for range 40 { // 2.5 MB
		if _, err := w.Write(chunk); err != nil {
			t.Fatal(err)
		}
	}
	if got := size(t, path+".1"); got != 2<<20 {
		t.Errorf(".1 size %d, want %d", got, 2<<20)
	}
	if got := size(t, path); got != (40*64<<10)-(2<<20) {
		t.Errorf("current size %d", got)
	}
}

func TestRotatingFileAppendsToExisting(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "sub", "helper.log") // parent created on demand
	w := NewRotatingFile(path, 100, 2)
	if _, err := w.Write([]byte("0123456789\n")); err != nil {
		t.Fatal(err)
	}
	w.Close()
	w = NewRotatingFile(path, 100, 2)
	defer w.Close()
	if _, err := w.Write([]byte("abc\n")); err != nil {
		t.Fatal(err)
	}
	if got := size(t, path); got != 15 {
		t.Errorf("size %d, want 15 (append, not truncate)", got)
	}
}

func size(t *testing.T, p string) int64 {
	t.Helper()
	fi, err := os.Stat(p)
	if err != nil {
		t.Fatal(err)
	}
	return fi.Size()
}
