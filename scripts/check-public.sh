#!/usr/bin/env bash
# Public-tree gate: fail when any file in a tree contains a string from a denylist, or a
# Tailscale address, MagicDNS name or auth key that is not a documentation placeholder.
#
#   scripts/check-public.sh <denylist-file> [tree]
#
# <denylist-file>  one fixed string per line, matched ASCII case-insensitively (only A-Z and a-z
#                  fold: grep runs in the C locale, so a non-ASCII letter matches only as the same
#                  bytes); leading/trailing blanks are stripped; blank lines and lines starting
#                  with '#' are ignored (see scripts/check-public.example.txt). Keep the real list
#                  outside the public tree: it names what must not be published.
# [tree]           directory to check (default: the repository this script belongs to). When it
#                  is the top of a git work tree, only tracked files (git ls-files) are checked;
#                  otherwise every file under it, skipping .git/ directories. The path is resolved
#                  physically first, so a tree reached through a symlink (macOS /tmp, /var)
#                  still counts as the top of its work tree.
#
# Always checked, besides the denylist:
#   - 100.x.y.z addresses (Tailscale range), except the documentation placeholders in ALLOW_IPS
#   - MagicDNS names (the tailnet domain under ts[.]net)
#   - the Tailscale auth key / OAuth secret prefix (tskey followed by a dash), except the
#     placeholders in ALLOW_KEYS. A key is the prefix plus every letter, digit and dash after it,
#     optionally followed by a ?query; it passes only when that key is exactly one of ALLOW_KEYS
#     (the query is ignored unless it holds the prefix again), so a placeholder with one more
#     character is a hit. A key hit prints only the prefix, never the key.
# (Both are spelled with [.] / [-] in this file so that the script passes its own check; the
# placeholders pass by the rule above. Self-check: scripts/check-public-test.sh.)
# Every file is read in full as text (NUL bytes included). File names and symlink targets are
# checked too. Hits print as "file:line: match" ("file:name: match" for a name or link target).
# Exit 0 = clean, 1 = hits, 2 = usage or read error (an unreadable file never passes).
set -Eeuo pipefail
# Byte semantics for grep, sort and bash: in a UTF-8 locale BSD grep stops matching a line at an
# invalid UTF-8 byte, so one stray byte would hide every later hit on that line.
export LC_ALL=C

ALLOW_IPS=(100.64.0.0 100.101.102.103 100.1.2.3 100.100.100.100 100.127.255.255)
IP_RE='100\.[0-9]+\.[0-9]+\.[0-9]+'
DNS_RE='[.]ts[.]net'
# A key: the prefix, its letters, digits and dashes, then an optional ?query that stops at a
# quote, blank or control byte: emit's read cuts a match at a NUL, so a query running through one
# would hide a key behind it. It has its own grep, so a MagicDNS name inside a key's ?query is
# still reported.
KEY_RE='tskey[-][A-Za-z0-9-]*([?][^"'"'"'[:space:][:cntrl:]]*)?'
# The only keys the tree may hold: the documentation placeholders and the bare prefixes (Java
# constants).
ALLOW_KEYS=(tskey-auth-EXAMPLE tskey-client-EXAMPLE tskey-auth- tskey-client-)

usage() { sed -n '5,17p' "$0" >&2; exit 2; }
fail() { echo "check-public.sh: ERROR: $*" >&2; exit 2; }
[[ $# -ge 1 && $# -le 2 ]] || usage
case "$1" in -h|--help) usage ;; esac
DENYLIST="$1"
TREE="${2:-$(cd "$(dirname "$0")/.." && pwd)}"
[[ -f "$DENYLIST" && -r "$DENYLIST" ]] || fail "denylist not readable: $DENYLIST"
[[ -d "$TREE" ]] || fail "not a directory: $TREE"
# Physical, as git rev-parse --show-toplevel prints it, so the comparison below holds for a
# path through a symlink.
TREE="$(cd "$TREE" && pwd -P)"

WORK="$(mktemp -d -t check-public.XXXXXX)"
trap 'rm -rf "$WORK"' EXIT

# Denylist: strip CRs and surrounding blanks, drop comments and blank lines.
while IFS= read -r line || [[ -n "$line" ]]; do
    line="${line%$'\r'}"
    line="${line#"${line%%[![:space:]]*}"}"
    line="${line%"${line##*[![:space:]]}"}"
    [[ -z "$line" || "$line" == \#* ]] && continue
    printf '%s\n' "$line"
done < "$DENYLIST" > "$WORK/deny"
[[ -s "$WORK/deny" ]] || fail "denylist $DENYLIST has no entries"

# File list (NUL-separated, paths relative to the tree).
if [[ "$(git -C "$TREE" rev-parse --show-toplevel 2>/dev/null || true)" == "$TREE" ]]; then
    MODE="git ls-files"
    git -C "$TREE" ls-files -z > "$WORK/all" || fail "git ls-files failed in $TREE"
else
    MODE="all files"
    (cd "$TREE" && find . -name .git -prune -o \( -type f -o -type l \) -print0) > "$WORK/all" \
        || fail "find failed in $TREE"
fi

cd "$TREE"
: > "$WORK/hits"

# grep wrapper: exit status 0 = matches, 1 = none, anything else = error -> exit 2.
# Output (matches only) goes to $WORK/out.
grep_into_out() {
    local rc=0
    grep "$@" > "$WORK/out" || rc=$?
    (( rc <= 1 )) || fail "grep failed (status $rc) on: ${*: -1}"
}
# allowed_key <match>: 0 when a KEY_RE match is one of ALLOW_KEYS (plus any ?query that does not
# hold the prefix again), 1 otherwise.
allowed_key() {
    local key="${1%%\?*}" query="" a
    if [[ "$1" == *\?* ]]; then query="${1#*\?}"; fi
    [[ "$query" != *[Tt][Ss][Kk][Ee][Yy]-* ]] || return 1
    for a in "${ALLOW_KEYS[@]}"; do [[ "$key" == "$a" ]] && return 0; done
    return 1
}
# Appends "<prefix> <match>" per output line, dropping allowlisted addresses when $2 = ip and
# allowlisted keys when $2 = key (a key hit shows only its first six characters, the prefix).
emit() { # $1 = prefix, $2 = "ip", "key" or "text"; reads $WORK/out lines "N:match" (when $3 = n) or "match"
    local prefix="$1" kind="$2" numbered="$3" l n m a
    while IFS= read -r l || [[ -n "$l" ]]; do
        if [[ "$numbered" == n ]]; then n="${l%%:*}"; m="${l#*:}"; else n=""; m="$l"; fi
        if [[ "$kind" == ip ]]; then
            for a in "${ALLOW_IPS[@]}"; do [[ "$m" == "$a" ]] && continue 2; done
        fi
        if [[ "$kind" == key ]]; then
            allowed_key "$m" && continue
            m="${m:0:6}"
        fi
        if [[ -n "$n" ]]; then
            printf '%s:%s: %s\n' "$prefix" "$n" "$m"
        else
            printf '%s:name: %s\n' "$prefix" "$m"
        fi
    done < "$WORK/out" >> "$WORK/hits"
}
check_name() { # $1 = path (relative), $2 = text to check (the name, or a link target)
    printf '%s\n' "$2" > "$WORK/name"
    grep_into_out -oiF -f "$WORK/deny" -- "$WORK/name"; emit "$1" text x
    grep_into_out -oiE "$DNS_RE" -- "$WORK/name";        emit "$1" text x
    grep_into_out -oiE "$KEY_RE" -- "$WORK/name";        emit "$1" key x
    grep_into_out -oE "$IP_RE" -- "$WORK/name";          emit "$1" ip x
}

count=0
while IFS= read -r -d '' f; do
    f="${f#./}"
    if [[ -L "$f" ]]; then
        count=$(( count + 1 ))
        check_name "$f" "$f"
        target="$(readlink -- "$f")" || fail "cannot read symlink: $f"
        check_name "$f" "$target"
        continue
    fi
    [[ -e "$f" ]] || continue # tracked but deleted from the work tree: nothing to publish
    [[ -f "$f" ]] || fail "not a regular file: $f"
    [[ -r "$f" ]] || fail "unreadable file: $f"
    count=$(( count + 1 ))
    check_name "$f" "$f"
    # -a: read every byte as text (a NUL never ends the scan early, and no match spans one).
    grep_into_out -anoiF -f "$WORK/deny" -- "$f"; emit "$f" text n
    grep_into_out -anoiE "$DNS_RE" -- "$f";        emit "$f" text n
    grep_into_out -anoiE "$KEY_RE" -- "$f";        emit "$f" key n
    grep_into_out -anoE "$IP_RE" -- "$f";          emit "$f" ip n
done < "$WORK/all"

sort -u "$WORK/hits" > "$WORK/hits.sorted"
n="$(wc -l < "$WORK/hits.sorted")"
if (( n > 0 )); then
    cat "$WORK/hits.sorted"
    echo "check-public.sh: FAIL: $n hit(s) in $TREE ($MODE, $count files)" >&2
    exit 1
fi
echo "check-public.sh: clean: $TREE ($MODE, $count files)"
