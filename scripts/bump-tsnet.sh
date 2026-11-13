#!/usr/bin/env bash
# Bump the helper's tailscale.com (tsnet) to the latest release, or to a given one, together with
# the files that must follow it.
#
#   scripts/bump-tsnet.sh [<version>]
#
# <version>  the tailscale.com version, v<major>.<minor>.<patch> (no pre-release: Tailscale's
#            odd minors are unstable and tagged -pre only); default: the latest release the Go
#            module proxy reports (go list -m -u)
#
# Edits, and nothing else: helper/go.mod and helper/go.sum (go get tailscale.com@<version>, then
# go mod tidy) and helper/THIRD_PARTY_LICENSES.md (scripts/gen-helper-notices.sh). The helper's
# Go follows tailscale.com: when the new version requires a newer Go, go get raises the go line
# of helper/go.mod to it and go switches to that release for the rest of the run (GOTOOLCHAIN
# from scripts/env.sh allows it; go downloads the release into the module cache and verifies it
# against the Go checksum database), so the bump carries the Go version with it and every later
# build uses it. Prints the next steps: the full build (vet, staticcheck, every test and the
# client smokes), the soak over a forced relay (docs/acceptance.md) and the commit. A run that
# finds nothing newer writes nothing (exit 0). Refuses (exit 2, nothing written): a malformed
# version; a version older than the current one; uncommitted changes to any of the three files
# (so the commit holds only the bump). Exit 1: go list, go get (also when the Go it requires
# cannot be had), go mod tidy or the notices script failed, or the new go line names no Go
# release (go <major>.<minor>, which scripts/env.sh cannot build from); the three files are
# restored.
# Needs the toolchain from scripts/env.sh. Self-check: scripts/bump-tsnet-test.sh.
set -Eeuo pipefail
ME=bump-tsnet.sh
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
HELPER="$ROOT/helper"
FILES=(helper/go.mod helper/go.sum helper/THIRD_PARTY_LICENSES.md)
RE_VERSION='^v[0-9]+\.[0-9]+\.[0-9]+$'

die() {
    local rc="$1"
    shift
    echo "$ME: $*" >&2
    exit "$rc"
}
say() { echo "==> $*"; }

TARGET=""
while (( $# > 0 )); do
    case "$1" in
        -h|--help)
            awk 'NR == 1 { next } /^#/ { print; next } { exit }' "$0"
            exit 0
            ;;
        -*) die 2 "unknown option $1 (see --help)" ;;
        *)
            [[ -z "$TARGET" ]] || die 2 "one version only (got $TARGET and $1)"
            TARGET="$1"
            ;;
    esac
    shift
done
if [[ -n "$TARGET" && ! "$TARGET" =~ $RE_VERSION ]]; then
    die 2 "version $TARGET is not v<major>.<minor>.<patch> (no pre-release, leading v)"
fi
command -v go > /dev/null || die 2 "go not found (source scripts/env.sh)"
export GOFLAGS=-mod=mod

if git -C "$ROOT" rev-parse --is-inside-work-tree > /dev/null 2>&1; then
    changes="$(git -C "$ROOT" status --porcelain -- "${FILES[@]}")" || die 2 "git status failed in $ROOT"
    [[ -z "$changes" ]] \
        || die 2 "${FILES[*]} has uncommitted changes; commit or revert them first, so that the bump is its own commit"
fi

cd "$HELPER"
CURRENT="$(go list -m -f '{{.Version}}' tailscale.com)" || die 1 "go list tailscale.com failed"
if [[ -z "$TARGET" ]]; then
    TARGET="$(go list -m -u -f '{{if .Update}}{{.Update.Version}}{{end}}' tailscale.com)" \
        || die 1 "go list -m -u tailscale.com failed (is the module proxy reachable?)"
    if [[ -z "$TARGET" ]]; then
        say "tailscale.com is already at $CURRENT, the latest release; nothing to do"
        exit 0
    fi
fi
if [[ "$TARGET" == "$CURRENT" ]]; then
    say "tailscale.com is already at $CURRENT; nothing to do"
    exit 0
fi
# version_key <vX.Y.Z[-pre]>: a zero-padded string that sorts like the release triple.
version_key() {
    local v="${1#v}" a b c
    v="${v%%[-+]*}"
    IFS=. read -r a b c <<< "$v"
    printf '%09d%09d%09d' "$((10#$a))" "$((10#$b))" "$((10#$c))"
}
if [[ "$(version_key "$TARGET")" < "$(version_key "$CURRENT")" ]]; then
    die 2 "$TARGET is older than the current $CURRENT; refusing to downgrade"
fi

# From here on a failure puts the three files back as they were.
BACKUP="$(mktemp -d -t bump-tsnet.XXXXXX)"
for f in "${FILES[@]}"; do
    cp -p "$ROOT/$f" "$BACKUP/"
done
restore() {
    local f
    for f in "${FILES[@]}"; do
        cp -p "$BACKUP/$(basename "$f")" "$ROOT/$f"
    done
}
trap 'rm -rf "$BACKUP"' EXIT

# build_go: the Go release scripts/env.sh builds the helper with, for helper/go.mod as it is
# now. env.sh owns that rule; a go line it cannot build from fails here, with its message.
build_go() {
    (
        # shellcheck source=env.sh
        # || exit: set -e is off inside the if below, and GOTOOLCHAIN may be inherited.
        source "$ROOT/scripts/env.sh" > /dev/null || exit 1
        v="${GOTOOLCHAIN%+auto}"
        printf '%s' "${v#go}"
    )
}
GO_BEFORE="$(build_go)" || die 1 "scripts/env.sh cannot tell which Go builds the helper"
say "tailscale.com $CURRENT -> $TARGET"
if ! out="$(go get "tailscale.com@$TARGET" 2>&1)"; then
    printf '%s\n' "$out" >&2
    restore
    die 1 "go get tailscale.com@$TARGET failed"
fi
if ! go mod tidy; then
    restore
    die 1 "go mod tidy failed"
fi
if ! GO_AFTER="$(build_go)"; then
    restore
    die 1 "tailscale.com $TARGET leaves helper/go.mod with a go line that no build can use (above)"
fi
if [[ "$GO_AFTER" != "$GO_BEFORE" ]]; then
    say "Go $GO_BEFORE -> $GO_AFTER: tailscale.com $TARGET requires it; the go line of helper/go.mod carries it to every build"
fi
say "helper/THIRD_PARTY_LICENSES.md"
if ! "$ROOT/scripts/gen-helper-notices.sh"; then
    restore
    die 1 "scripts/gen-helper-notices.sh failed"
fi

say "done: tailscale.com $CURRENT -> $TARGET in helper/go.mod, helper/go.sum and helper/THIRD_PARTY_LICENSES.md; next:"
if [[ "$GO_AFTER" != "$GO_BEFORE" ]]; then
    echo "  bash scripts/install-toolchain.sh   # staticcheck must be built by the Go that builds the helper, now Go $GO_AFTER"
fi
echo "  scripts/build.sh --allow-dirty   # vet and staticcheck for every target, go test, Gradle tests, client smokes"
echo "  the soak over a forced DERP relay (docs/acceptance.md), then the live join on each OS you can"
echo "  git add ${FILES[*]} && git commit -m \"Bump tsnet to $TARGET\""
echo "  release notes: https://tailscale.com/changelog"
