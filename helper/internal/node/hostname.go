// Package node holds helpers for the helper's tsnet node identity.
package node

import "strings"

// maxUserLen caps the sanitised username part, so a hostname is at most
// len("mc-")+16 = 19 characters.
const maxUserLen = 16

// Hostname turns a Minecraft username into a tailnet hostname: lowercased,
// every run of characters outside [a-z0-9] collapsed to a single '-',
// leading/trailing '-' trimmed, truncated to 16 characters and prefixed with
// "mc-". A username that sanitises to nothing yields "mc-player".
func Hostname(username string) string {
	var b strings.Builder
	pendingDash := false
	for _, r := range strings.ToLower(username) {
		if (r >= 'a' && r <= 'z') || (r >= '0' && r <= '9') {
			if pendingDash && b.Len() > 0 {
				b.WriteByte('-')
			}
			pendingDash = false
			b.WriteRune(r)
			continue
		}
		pendingDash = true
	}
	user := b.String()
	if len(user) > maxUserLen {
		user = strings.TrimRight(user[:maxUserLen], "-")
	}
	if user == "" {
		user = "player"
	}
	return "mc-" + user
}
