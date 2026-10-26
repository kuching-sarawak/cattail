#!/usr/bin/env bash
# Generate helper/THIRD_PARTY_LICENSES.md: the licence text of every Go module statically
# linked into any of the helper binaries, plus the Go runtime's own licence. The jar ships this
# file next to the helpers (META-INF/licenses/), which is what the BSD, MIT, ISC and Apache-2.0
# licences of those modules require of a binary distribution.
#
#   scripts/gen-helper-notices.sh            rewrite helper/THIRD_PARTY_LICENSES.md
#   scripts/gen-helper-notices.sh --check    exit 1 when the committed file is stale
#
# The module list is the union of what `go list -deps` reports for the helper's main package
# built for each target build.sh produces (windows/amd64, linux/amd64, darwin/arm64), with
# CGO_ENABLED=0 as build.sh builds them: each binary links its own set of modules, and a module
# in any of them needs its licence here. The targets are read from build.sh's HELPER_TARGETS
# line, not copied, so a target added there is covered here too (and --check fails until this
# script is rerun); a build.sh that names HELPER_TARGETS anywhere else, other than in a comment
# line or a read-only expansion, is refused, since a target added that way would be missed.
# Run after any change to helper/go.mod.
# Needs the toolchain from scripts/env.sh (go) and the module cache populated (go mod download).
set -Eeuo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/helper/THIRD_PARTY_LICENSES.md"
CHECK=0
case "${1:-}" in
    "") ;;
    --check) CHECK=1 ;;
    # The usage lines of the header comment ("#   scripts/..."), which ends at the first other line.
    *) awk 'NR > 1 && !/^#/ { exit } /^#   scripts\// { print }' "$ROOT/scripts/gen-helper-notices.sh" >&2; exit 2 ;;
esac

cd "$ROOT/helper"
export GOFLAGS=-mod=mod
WORK="$(mktemp -d -t helper-notices.XXXXXX)"
trap 'rm -rf "$WORK"' EXIT
TMP="$WORK/THIRD_PARTY_LICENSES.md"

# Exactly one line HELPER_TARGETS=(<goos>/<goarch> ...) in build.sh, entries one space apart; a
# second match or any other shape is refused rather than guessed at.
TARGETS_LINE="$(sed -n 's/^HELPER_TARGETS=(\(.*\))$/\1/p' "$ROOT/scripts/build.sh")"
[[ "$TARGETS_LINE" =~ ^[a-z0-9]+/[a-z0-9]+( [a-z0-9]+/[a-z0-9]+)*$ ]] \
    || { echo "gen-helper-notices.sh: scripts/build.sh needs one line HELPER_TARGETS=(<goos>/<goarch> ...)" >&2; exit 2; }
read -r -a TARGETS <<< "$TARGETS_LINE"
# Anywhere else, build.sh may only read the array, and only through closed expansions that
# cannot assign: ${HELPER_TARGETS...} with no "=" between the name and the first "}" (so
# "${HELPER_TARGETS[@]}", ${HELPER_TARGETS[*]}, ${#HELPER_TARGETS[@]} and ${!HELPER_TARGETS[@]}
# pass, ${HELPER_TARGETS[3]:=x} does not), and $HELPER_TARGETS not followed by a letter, digit
# or underscore. Those are removed from each line; a line that still names HELPER_TARGETS
# (HELPER_TARGETS+=(...), an element assignment, read -a, mapfile, declare, unset, an indented
# second HELPER_TARGETS=, an assigning expansion) could add a target this list misses, so it is
# refused with its line number. Only full-line comments are skipped: build.sh names the array
# in no trailing comment, and a "#" inside a string must not hide the rest of its line.
OTHER_USES="$(awk '/^HELPER_TARGETS=[(].*[)]$/ || /^[[:space:]]*#/ { next }
    {
        # The trailing blank stands in for the end of the line after a bare $HELPER_TARGETS.
        line = $0 " "
        gsub(/[$][{][#!]?HELPER_TARGETS[^}=]*[}]/, "", line)
        while (match(line, /[$]HELPER_TARGETS[^A-Za-z0-9_]/)) {
            line = substr(line, 1, RSTART - 1) substr(line, RSTART + 15)
        }
    }
    line ~ /(^|[^A-Za-z0-9_])HELPER_TARGETS([^A-Za-z0-9_]|$)/ { print "  line " NR ": " $0 }' \
    "$ROOT/scripts/build.sh")"
if [[ -n "$OTHER_USES" ]]; then
    echo "gen-helper-notices.sh: scripts/build.sh uses HELPER_TARGETS outside its one HELPER_TARGETS=(...) line other than in a read-only expansion, so a target could be missed here; list every target on that line:" >&2
    printf '%s\n' "$OTHER_USES" >&2
    exit 2
fi

MAIN_MODULE="$(go list -m)"
# CGO_ENABLED=0 as in the build: with cgo, darwin/arm64 would list a module the helper does not
# link, and whether cgo is on by default depends on the host.
for t in "${TARGETS[@]}"; do
    CGO_ENABLED=0 GOOS="${t%/*}" GOARCH="${t#*/}" go list -deps \
        -f '{{if .Module}}{{.Module.Path}} {{.Module.Version}}{{end}}' ./cmd/cattail-helper
done | grep -v "^$MAIN_MODULE " | LC_ALL=C sort -u > "$WORK/modules.txt"
[[ -s "$WORK/modules.txt" ]] || { echo "gen-helper-notices.sh: no modules listed" >&2; exit 2; }

licence_files() {
    # Every licence-like file at the module root, sorted; empty when there is none.
    find "$1" -maxdepth 1 -type f \( -iname 'LICENSE' -o -iname 'LICENSE.*' -o -iname 'LICENCE' \
        -o -iname 'LICENCE.*' -o -iname 'COPYING' -o -iname 'COPYING.*' -o -iname 'NOTICE' \
        -o -iname 'NOTICE.*' \) | LC_ALL=C sort
}

emit_file() {
    # $1 heading, $2 path: heading, then the file verbatim inside a fenced block.
    printf '### %s\n\n```text\n' "$1"
    # Strip carriage returns so the output is identical on every platform; end with a newline.
    tr -d '\r' < "$2"
    [[ "$(tail -c1 "$2" | od -An -c | tr -d ' ')" == '\n' ]] || printf '\n'
    printf '```\n\n'
}

{
    printf '# Third-party licences of the Cattail helper\n\n'
    printf 'The helpers (`cattail-helper.exe` for Windows, `cattail-helper` for Linux and macOS, nested\n'
    printf 'in the Cattail jar) are Go binaries that statically link the Go runtime and their Go\n'
    printf 'modules. This file reproduces the licence of the Go runtime and of every Go module linked\n'
    printf 'into any of them, as required by those licences. It is generated by\n'
    printf '`scripts/gen-helper-notices.sh` from `helper/go.mod`; edit that script, not this file.\n\n'
    printf 'Targets: %s. Modules (%s):\n\n' "$(printf '%s, ' "${TARGETS[@]}" | sed 's/, $//')" \
        "$(wc -l < "$WORK/modules.txt" | tr -d ' ')"
    while read -r mod ver; do printf -- '- `%s` %s\n' "$mod" "$ver"; done < "$WORK/modules.txt"
    printf '\n## Go runtime and standard library\n\n'
    GOROOT="$(go env GOROOT)"
    emit_file "Go $(go env GOVERSION): LICENSE" "$GOROOT/LICENSE"
    [[ -f "$GOROOT/PATENTS" ]] && emit_file "Go $(go env GOVERSION): PATENTS" "$GOROOT/PATENTS"
    printf '## Modules\n\n'
    while read -r mod ver; do
        dir="$(go list -m -f '{{.Dir}}' "$mod@$ver")"
        [[ -n "$dir" && -d "$dir" ]] || { echo "gen-helper-notices.sh: $mod@$ver not in module cache (run go mod download)" >&2; exit 2; }
        files="$(licence_files "$dir")"
        [[ -n "$files" ]] || { echo "gen-helper-notices.sh: $mod@$ver has no licence file; add it by hand" >&2; exit 2; }
        while IFS= read -r f; do
            emit_file "$mod $ver: $(basename "$f")" "$f"
        done <<< "$files"
    done < "$WORK/modules.txt"
} > "$TMP"

if (( CHECK )); then
    if [[ -f "$OUT" ]] && cmp -s "$TMP" "$OUT"; then
        echo "gen-helper-notices.sh: $OUT is up to date"
    else
        echo "gen-helper-notices.sh: $OUT is stale; run scripts/gen-helper-notices.sh" >&2
        exit 1
    fi
else
    mv "$TMP" "$OUT"
    echo "gen-helper-notices.sh: wrote $OUT ($(wc -l < "$WORK/modules.txt" | tr -d ' ') modules)"
fi
