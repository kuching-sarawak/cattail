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
# go mod tidy) and helper/THIRD_PARTY_LICENSES.md (scripts/gen-helper-notices.sh). Go runs with
# GOTOOLCHAIN=local, so a tailscale.com that needs a newer Go than the pinned toolchain fails
# here instead of fetching an unpinned toolchain. Prints the next steps: the full build (vet,
# staticcheck, every test and the client smokes), the soak over a forced relay
# (docs/acceptance.md) and the commit. A run that finds nothing newer writes nothing (exit 0).
# Refuses (exit 2, nothing written): a malformed version; a version older than the current one;
# uncommitted changes to any of the three files (so the commit holds only the bump). Exit 3: the
# version needs a newer Go than the pinned one (bump GO_VERSION and its checksums in
# scripts/install-toolchain.sh and GOROOT in scripts/env.sh first). Exit 1: go list, go get,
# go mod tidy or the notices script failed. Exits 1 and 3 restore the three files.
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
export GOTOOLCHAIN=local GOFLAGS=-mod=mod

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

say "tailscale.com $CURRENT -> $TARGET"
if ! out="$(go get "tailscale.com@$TARGET" 2>&1)"; then
    printf '%s\n' "$out" >&2
    restore
    needs="$(sed -n 's/.* requires go >= \([^ ]*\) (running go .*/\1/p' <<< "$out" | head -n 1)"
    if [[ -n "$needs" ]]; then
        die 3 "tailscale.com $TARGET needs a newer Go than the pinned one: Go $needs. Bump GO_VERSION and its checksums in scripts/install-toolchain.sh and GOROOT in scripts/env.sh, install it, then run this again"
    fi
    die 1 "go get tailscale.com@$TARGET failed"
fi
if ! go mod tidy; then
    restore
    die 1 "go mod tidy failed"
fi
say "helper/THIRD_PARTY_LICENSES.md"
if ! "$ROOT/scripts/gen-helper-notices.sh"; then
    restore
    die 1 "scripts/gen-helper-notices.sh failed"
fi

say "done: tailscale.com $CURRENT -> $TARGET in helper/go.mod, helper/go.sum and helper/THIRD_PARTY_LICENSES.md; next:"
echo "  scripts/build.sh --allow-dirty   # vet and staticcheck for every target, go test, Gradle tests, client smokes"
echo "  the soak over a forced DERP relay (docs/acceptance.md), then the live join on each OS you can"
echo "  git add ${FILES[*]} && git commit -m \"Bump tsnet to $TARGET\""
echo "  release notes: https://tailscale.com/changelog"
