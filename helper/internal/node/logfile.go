package node

import (
	"fmt"
	"os"
	"path/filepath"
	"sync"
)

// Defaults for the helper log: helper.log plus helper.log.1 and helper.log.2,
// each at most 2 MB.
const (
	MaxLogSize    = 2 << 20
	MaxLogBackups = 2
)

// RotatingFile is an io.Writer appending to a file that is rotated to
// path.1 … path.<backups> once the next write would push it past maxSize.
// A single write is never split, so one larger than maxSize gets a file of its
// own. The file (and its parent directory) is created on the first write.
// Safe for concurrent use.
type RotatingFile struct {
	path    string
	maxSize int64
	backups int

	mu   sync.Mutex
	f    *os.File
	size int64
}

// NewRotatingFile returns a RotatingFile for path. Nothing is opened yet.
func NewRotatingFile(path string, maxSize int64, backups int) *RotatingFile {
	return &RotatingFile{path: path, maxSize: maxSize, backups: backups}
}

// Write appends p, rotating first if needed.
func (w *RotatingFile) Write(p []byte) (int, error) {
	w.mu.Lock()
	defer w.mu.Unlock()
	if w.f == nil {
		if err := w.open(); err != nil {
			return 0, err
		}
	}
	if w.size > 0 && w.size+int64(len(p)) > w.maxSize {
		if err := w.rotate(); err != nil {
			return 0, err
		}
	}
	n, err := w.f.Write(p)
	w.size += int64(n)
	return n, err
}

// Close closes the current file. A later Write reopens it.
func (w *RotatingFile) Close() error {
	w.mu.Lock()
	defer w.mu.Unlock()
	if w.f == nil {
		return nil
	}
	err := w.f.Close()
	w.f = nil
	return err
}

func (w *RotatingFile) open() error {
	if err := os.MkdirAll(filepath.Dir(w.path), 0o700); err != nil {
		return err
	}
	f, err := os.OpenFile(w.path, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o600)
	if err != nil {
		return err
	}
	fi, err := f.Stat()
	if err != nil {
		f.Close()
		return err
	}
	w.f, w.size = f, fi.Size()
	return nil
}

// rotate shifts path.(i) to path.(i+1), dropping the oldest, moves the
// current file to path.1 and opens a fresh one. Rename errors for missing
// files are ignored.
func (w *RotatingFile) rotate() error {
	if err := w.f.Close(); err != nil {
		return err
	}
	w.f = nil
	if w.backups < 1 {
		_ = os.Remove(w.path)
	} else {
		_ = os.Remove(w.backupName(w.backups))
		for i := w.backups - 1; i >= 1; i-- {
			_ = os.Rename(w.backupName(i), w.backupName(i+1))
		}
		if err := os.Rename(w.path, w.backupName(1)); err != nil && !os.IsNotExist(err) {
			return err
		}
	}
	return w.open()
}

func (w *RotatingFile) backupName(i int) string {
	return fmt.Sprintf("%s.%d", w.path, i)
}
