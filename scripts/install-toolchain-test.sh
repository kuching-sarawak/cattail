#!/usr/bin/env bash
# Self-check for the staticcheck step of scripts/install-toolchain.sh: staticcheck is built by
# the Go that builds the helper (scripts/env.sh: the go line of helper/go.mod) and rebuilt when
# that Go is another one, because a staticcheck built by an older Go minor refuses the module
# ("requires newer Go version"). Runs the real script with HOME in a throwaway directory where
# the pinned Go and JDK are already "installed" as stubs, so nothing is downloaded: the stub go
# reports the build Go from STUB_BUILD_GO, tells which Go built a file from the note beside it,
# and for go install writes a stub staticcheck with such a note and logs the call.
#
#   scripts/install-toolchain-test.sh
#
# Cases: absent (installed), current (left alone), other_go (built by another Go: rebuilt),
# other_release (another staticcheck release: rebuilt).
# Offline. Exit 0 = every case behaved, 1 = at least one did not.
set -Eeuo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SCRIPT="$ROOT/scripts/install-toolchain.sh"
WORK="$(mktemp -d -t install-toolchain-test.XXXXXX)"
trap 'rm -rf "$WORK"' EXIT
# pinned <name>: the value the script assigns to that variable.
pinned() { sed -n "s/^$1=\"\\(.*\\)\"\$/\\1/p" "$SCRIPT" | head -n 1; }
GO_VERSION="$(pinned GO_VERSION)"
JDK_RELEASE="$(pinned JDK_RELEASE)"
STATICCHECK_VERSION="$(pinned STATICCHECK_VERSION)"
[[ -n "$GO_VERSION" && -n "$JDK_RELEASE" && -n "$STATICCHECK_VERSION" ]] \
    || { echo "install-toolchain-test.sh: cannot read the pinned versions from $SCRIPT" >&2; exit 2; }

export HOME="$WORK/home"
export INSTALL_LOG="$WORK/go-install.log"
GOBIN_DIR="$HOME/.local/go/bin"
mkdir -p "$HOME/.local/toolchains/go$GO_VERSION/bin" "$HOME/.local/toolchains/$JDK_RELEASE/bin" "$GOBIN_DIR"
printf 'JAVA_RUNTIME_VERSION="%s"\n' "${JDK_RELEASE#jdk-}" > "$HOME/.local/toolchains/$JDK_RELEASE/release"
printf '#!/bin/sh\nexit 0\n' > "$HOME/.local/toolchains/$JDK_RELEASE/bin/java"
cat > "$HOME/.local/toolchains/go$GO_VERSION/bin/go" <<STUB
#!/usr/bin/env bash
# Stub go. version: the pinned Go; version <file>: the Go noted beside the file; env GOVERSION:
# the build Go; install: logs, then writes a staticcheck of the release asked for.
case "\$1" in
    version)
        if (( \$# > 1 )); then
            printf '%s: %s\n' "\$2" "\$(cat "\$2.builtby")"
        else
            echo "go version go$GO_VERSION stub/stub"
        fi
        ;;
    env) echo "\$STUB_BUILD_GO" ;;
    install)
        printf '[%s] by %s\n' "\$*" "\$STUB_BUILD_GO" >> "\$INSTALL_LOG"
        stub_staticcheck "\${2##*@}" "\$STUB_BUILD_GO"
        ;;
    *) echo "stub go: unexpected \$*" >&2; exit 1 ;;
esac
STUB
chmod +x "$HOME/.local/toolchains/go$GO_VERSION/bin/go" "$HOME/.local/toolchains/$JDK_RELEASE/bin/java"
# stub_staticcheck <release> <go>: a staticcheck of that release, built by that Go.
stub_staticcheck() {
    printf '#!/bin/sh\necho "staticcheck %s (v0.0.0)"\n' "$1" > "$GOBIN_DIR/staticcheck"
    chmod +x "$GOBIN_DIR/staticcheck"
    printf '%s' "$2" > "$GOBIN_DIR/staticcheck.builtby"
}
export -f stub_staticcheck
export GOBIN_DIR

cases=0
failures=0
CASE=""
CASE_BAD=0
RC=0
OUT=""
begin() {
    CASE="$1"; CASE_BAD=0; cases=$(( cases + 1 ))
    rm -f "$GOBIN_DIR/staticcheck" "$GOBIN_DIR/staticcheck.builtby"
    : > "$INSTALL_LOG"
}
bad() { echo "FAIL: $CASE: $*" >&2; CASE_BAD=1; }
finish() {
    if (( CASE_BAD )); then
        failures=$(( failures + 1 ))
        printf '%s\n' "--- $CASE: output of the last run (exit $RC):" "$OUT" >&2
    fi
}
# run <build go>: the script, with that Go as the one that builds the helper, on a PATH without
# the caller's own toolchain (the script puts the one under HOME in front through env.sh).
run() {
    RC=0
    OUT="$(STUB_BUILD_GO="$1" PATH=/usr/bin:/bin bash "$SCRIPT" 2>&1)" || RC=$?
    [[ "$RC" -eq 0 ]] || bad "the script failed (exit $RC)"
}
expect_installed_by() {
    [[ "$(cat "$INSTALL_LOG")" == "[install honnef.co/go/tools/cmd/staticcheck@$STATICCHECK_VERSION] by $1" ]] \
        || bad "go install was called as '$(cat "$INSTALL_LOG")', expected once for staticcheck $STATICCHECK_VERSION by $1"
}
expect_left_alone() { [[ ! -s "$INSTALL_LOG" ]] || bad "staticcheck was reinstalled: $(cat "$INSTALL_LOG")"; }

begin absent
run go1.50.2
expect_installed_by go1.50.2
finish

begin current
stub_staticcheck "$STATICCHECK_VERSION" go1.50.2
run go1.50.2
expect_left_alone
finish

begin other_go
stub_staticcheck "$STATICCHECK_VERSION" go1.50.2
run go1.51.0
expect_installed_by go1.51.0
[[ "$OUT" == *'go1.50.2'* && "$OUT" == *'go1.51.0'* ]] || bad "the output does not name the two Go releases"
finish

begin other_release
stub_staticcheck 2001.1.1 go1.50.2
run go1.50.2
expect_installed_by go1.50.2
finish

if (( failures )); then
    echo "install-toolchain-test.sh: $failures of $cases cases failed" >&2
    exit 1
fi
echo "install-toolchain-test.sh: ok: $cases cases"
