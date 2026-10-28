#!/usr/bin/env bash
# Self-check for scripts/bump-tsnet.sh: runs it in a throwaway git repository holding a copy of
# the script, a fixture helper module (helper/go.mod requiring tailscale.com, a main package that
# imports tailscale.com/tsnet, a committed go.sum and THIRD_PARTY_LICENSES.md) and a stub
# scripts/gen-helper-notices.sh that logs its arguments. Go resolves tailscale.com from a fixture
# module proxy on disk (GOPROXY=file://...), never from the network: v1.0.0, v1.2.0 and
# v1.3.0-pre are listed (so the latest release is v1.2.0), v1.4.0 is fetchable but unlisted and
# declares go 1.99, newer than any installed Go.
#
#   scripts/bump-tsnet-test.sh
#
# Cases: bump_latest (go.mod and go.sum rewritten, the notices script called with no argument,
# next steps printed), bump_explicit, already_current (nothing written, notices not run),
# downgrade, prerelease, bad_version (four shapes), needs_newer_go (exit 3, files restored),
# unknown_version (exit 1, files restored), notices_fails (exit 1, files restored), dirty_file
# (each of the three files), usage.
# Needs git, python3 and go (source scripts/env.sh). Exit 0 = every case behaved, 1 = at least
# one did not.
set -Eeuo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
for t in git python3 go; do
    command -v "$t" > /dev/null || { echo "bump-tsnet-test.sh: $t not found (source scripts/env.sh)" >&2; exit 2; }
done
WORK="$(mktemp -d -t bump-tsnet-test.XXXXXX)"
# The module cache is read-only by design; make it writable before removing it.
trap 'chmod -R u+w "$WORK" 2> /dev/null; rm -rf "$WORK"' EXIT
export GIT_CONFIG_GLOBAL="$WORK/gitconfig" GIT_CONFIG_NOSYSTEM=1
: > "$GIT_CONFIG_GLOBAL"
# Go: no user go.env, the fixture proxy only, no checksum database, a private module cache.
export GOENV=off GOPROXY="file://$WORK/proxy" GOSUMDB=off GOMODCACHE="$WORK/modcache" GOFLAGS=-mod=mod
unset GOTOOLCHAIN
export NOTICES_LOG="$WORK/notices.log"

# module_version <version> <go directive> <listed: 1 or 0>: one version of the fixture
# tailscale.com in the proxy, with a tsnet package whose Version() returns the version.
module_version() {
    python3 -I - "$WORK/proxy/tailscale.com/@v" "$1" "$2" "$3" <<'EOF'
import json, os, sys, zipfile
d, version, go, listed = sys.argv[1:5]
os.makedirs(d, exist_ok=True)
mod = f"module tailscale.com\n\ngo {go}\n"
with open(os.path.join(d, version + ".mod"), "w") as fh:
    fh.write(mod)
with open(os.path.join(d, version + ".info"), "w") as fh:
    json.dump({"Version": version, "Time": "2026-01-01T00:00:00Z"}, fh)
prefix = f"tailscale.com@{version}/"
with zipfile.ZipFile(os.path.join(d, version + ".zip"), "w") as z:
    z.writestr(prefix + "go.mod", mod)
    z.writestr(prefix + "tsnet/tsnet.go",
               f'package tsnet\n\nfunc Version() string {{ return "{version}" }}\n')
if listed == "1":
    with open(os.path.join(d, "list"), "a") as fh:
        fh.write(version + "\n")
EOF
}
module_version v1.0.0 1.22 1
module_version v1.2.0 1.22 1
module_version v1.3.0-pre 1.22 1
module_version v1.4.0 1.99 0

# The fixture helper module at tailscale.com v1.0.0, tidied once; every case starts from a copy.
TEMPLATE="$WORK/template"
mkdir -p "$TEMPLATE/scripts" "$TEMPLATE/helper/cmd/cattail-helper"
cat > "$TEMPLATE/helper/go.mod" <<'EOF'
module github.com/kuching-sarawak/cattail/helper

go 1.22

require tailscale.com v1.0.0
EOF
cat > "$TEMPLATE/helper/cmd/cattail-helper/main.go" <<'EOF'
package main

import "tailscale.com/tsnet"

func main() { println(tsnet.Version()) }
EOF
(cd "$TEMPLATE/helper" && go mod tidy) > /dev/null 2>&1
printf '# Third-party licences\n\nfixture\n' > "$TEMPLATE/helper/THIRD_PARTY_LICENSES.md"
cat > "$TEMPLATE/scripts/gen-helper-notices.sh" <<'STUB'
#!/usr/bin/env bash
printf '[%s]\n' "$*" >> "$NOTICES_LOG"
exit "${NOTICES_STUB_RC:-0}"
STUB
chmod +x "$TEMPLATE/scripts/gen-helper-notices.sh"
LICENSES="$(cat "$TEMPLATE/helper/THIRD_PARTY_LICENSES.md")"

# reset [<version>]: a fresh repository at $WORK/repo with everything committed, the helper at
# tailscale.com <version> (default v1.0.0).
reset() {
    local r="$WORK/repo"
    rm -rf "$r"
    cp -R "$TEMPLATE" "$r"
    cp -p "$ROOT/scripts/bump-tsnet.sh" "$r/scripts/"
    if [[ -n "${1:-}" ]]; then
        (cd "$r/helper" && go get "tailscale.com@$1" && go mod tidy) > /dev/null 2>&1
    fi
    git -C "$r" init -q -b main
    git -C "$r" add -A
    git -C "$r" -c user.name=Fixture -c user.email=fixture@example.com commit -q -m init
    : > "$NOTICES_LOG"
    unset NOTICES_STUB_RC
    BEFORE_MOD="$(cat "$r/helper/go.mod")"
    BEFORE_SUM="$(cat "$r/helper/go.sum")"
}
run() {
    RC=0
    OUT="$(cd "$WORK/repo" && scripts/bump-tsnet.sh "$@" 2>&1)" || RC=$?
}
cases=0
failures=0
CASE=""
CASE_BAD=0
begin() { CASE="$1"; CASE_BAD=0; cases=$(( cases + 1 )); reset "${2:-}"; }
bad() { echo "FAIL: $CASE: $*" >&2; CASE_BAD=1; }
finish() {
    if (( CASE_BAD )); then
        failures=$(( failures + 1 ))
        printf '%s\n' "--- $CASE: output of the last run (exit $RC):" "$OUT" >&2
    fi
}
expect_rc() { [[ "$RC" -eq "$1" ]] || bad "expected exit $1, got $RC"; }
expect_out() { local s; for s in "$@"; do [[ "$OUT" == *"$s"* ]] || bad "output lacks '$s'"; done; }
# required: the tailscale.com version helper/go.mod requires.
required() { sed -n 's/^require tailscale\.com \(v[^[:space:]]*\)$/\1/p' "$WORK/repo/helper/go.mod"; }
expect_unchanged() {
    [[ "$(cat "$WORK/repo/helper/go.mod")" == "$BEFORE_MOD" ]] || bad "go.mod changed:"$'\n'"$(cat "$WORK/repo/helper/go.mod")"
    [[ "$(cat "$WORK/repo/helper/go.sum")" == "$BEFORE_SUM" ]] || bad "go.sum changed"
    [[ "$(cat "$WORK/repo/helper/THIRD_PARTY_LICENSES.md")" == "$LICENSES" ]] || bad "THIRD_PARTY_LICENSES.md changed"
}
expect_bumped_to() {
    [[ "$(required)" == "$1" ]] || bad "go.mod requires '$(required)', expected $1"
    grep -q "^tailscale.com $1 " "$WORK/repo/helper/go.sum" || bad "go.sum has no $1 line"
    ! grep -q "^tailscale.com v1.0.0 " "$WORK/repo/helper/go.sum" || bad "go.sum still has the v1.0.0 line (not tidied)"
    grep -q '^go 1.22$' "$WORK/repo/helper/go.mod" || bad "the go directive changed"
    [[ "$(cat "$NOTICES_LOG")" == "[]" ]] || bad "the notices script was called as '$(cat "$NOTICES_LOG")', expected once with no argument"
}

begin bump_latest
run
expect_rc 0
expect_bumped_to v1.2.0
expect_out 'tailscale.com v1.0.0 -> v1.2.0' 'scripts/build.sh' 'docs/acceptance.md' 'Bump tsnet to v1.2.0'
finish

begin bump_explicit
run v1.2.0
expect_rc 0
expect_bumped_to v1.2.0
finish

begin already_current v1.2.0
run
expect_rc 0
expect_out 'already at v1.2.0'
expect_unchanged
[[ ! -s "$NOTICES_LOG" ]] || bad "the notices script ran with nothing to do"
run v1.2.0
expect_rc 0
expect_out 'already at v1.2.0'
expect_unchanged
finish

begin downgrade v1.2.0
run v1.0.0
expect_rc 2
expect_out 'older than the current v1.2.0'
expect_unchanged
finish

begin prerelease
run v1.3.0-pre
expect_rc 2
expect_out 'is not v<major>.<minor>.<patch>'
expect_unchanged
finish

begin bad_version
for v in 1.2.0 v1.2 latest 'v1.2.0 '; do
    run "$v"
    expect_rc 2
    expect_out 'is not v<major>.<minor>.<patch>'
    expect_unchanged
done
finish

begin needs_newer_go
run v1.4.0
expect_rc 3
expect_out 'needs a newer Go' 'Go 1.99' 'scripts/install-toolchain.sh' 'scripts/env.sh'
# Go's own message: with GOTOOLCHAIN=local it names that setting; with the default (auto) it says
# "switching to go >= 1.99" and, given a real proxy, downloads that toolchain and succeeds.
expect_out 'GOTOOLCHAIN=local'
[[ "$OUT" != *'switching to go'* ]] || bad "go tried to switch toolchains (GOTOOLCHAIN is not local)"
expect_unchanged
[[ ! -s "$NOTICES_LOG" ]] || bad "the notices script ran"
finish

begin unknown_version
run v1.9.9
expect_rc 1
expect_out 'go get tailscale.com@v1.9.9 failed'
expect_unchanged
finish

begin notices_fails
export NOTICES_STUB_RC=1
run
expect_rc 1
expect_out 'gen-helper-notices.sh failed'
expect_unchanged
unset NOTICES_STUB_RC
finish

begin dirty_file
for f in helper/go.mod helper/go.sum helper/THIRD_PARTY_LICENSES.md; do
    reset
    printf '\n' >> "$WORK/repo/$f"
    BEFORE_MOD="$(cat "$WORK/repo/helper/go.mod")"
    BEFORE_SUM="$(cat "$WORK/repo/helper/go.sum")"
    run
    expect_rc 2
    expect_out 'uncommitted changes'
    [[ "$(required)" == v1.0.0 ]] || bad "go.mod was bumped with $f dirty"
done
finish

begin usage
run v1.2.0 v1.4.0
expect_rc 2
expect_out 'one version only'
run --bogus
expect_rc 2
expect_out 'unknown option'
run --help
expect_rc 0
expect_out 'scripts/bump-tsnet.sh [<version>]'
expect_unchanged
finish

if (( failures )); then
    echo "bump-tsnet-test.sh: $failures of $cases cases failed" >&2
    exit 1
fi
echo "bump-tsnet-test.sh: ok: $cases cases"
