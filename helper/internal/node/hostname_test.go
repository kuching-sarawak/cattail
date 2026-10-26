package node

import (
	"regexp"
	"strings"
	"testing"
)

var validHostname = regexp.MustCompile(`^[a-z0-9-]{1,63}$`)

func TestHostname(t *testing.T) {
	cases := map[string]string{
		"Alice":                 "mc-alice",
		"Alice_Smith":           "mc-alice-smith",
		"a":                     "mc-a",
		"Bob123":                "mc-bob123",
		"bad name!":             "mc-bad-name",
		strings.Repeat("x", 40): "mc-" + strings.Repeat("x", 40)[:16],
		"":                      "mc-player",
		"___":                   "mc-player",
		"__a__b__":              "mc-a-b",
		"abcdefghijklmno_pq":    "mc-abcdefghijklmno",
	}
	for in, want := range cases {
		got := Hostname(in)
		if got != want {
			t.Errorf("Hostname(%q) = %q, want %q", in, got, want)
		}
		if !validHostname.MatchString(got) {
			t.Errorf("Hostname(%q) = %q, does not match %s", in, got, validHostname)
		}
		if strings.HasSuffix(got, "-") {
			t.Errorf("Hostname(%q) = %q ends with '-'", in, got)
		}
		if len(got) > 19 {
			t.Errorf("Hostname(%q) = %q longer than 19 chars", in, got)
		}
	}
}
