#!/usr/bin/env bash
# Self-check for scripts/env.sh: the Go that builds the helper is the one helper/go.mod names on
# its go line, whatever Go scripts/install-toolchain.sh installed (that one is only the
# bootstrap). Sources env.sh in a fresh bash, and in a fresh zsh when there is one, from the real
# tree and from throwaway trees holding a copy of env.sh and a fixture helper/go.mod.
#
#   scripts/env-test.sh
#
# Cases: real_tree (GOTOOLCHAIN is go<the go line>+auto, from the repository root, from another
# directory and by a relative path), fixture_version (the value follows the file, it is not a
# constant), no_go_line, short_go_line and no_go_mod (sourcing fails, names helper/go.mod and
# sets no GOTOOLCHAIN), follows_newer (a module whose go line is newer than the one env.sh read
# is handed to that toolchain: a shell that sourced env.sh before a bump keeps working),
# runs_that_go (in the real helper module go reports exactly the go line's version).
# Offline but for runs_that_go, which downloads that Go into the module cache the first time the
# go line and the installed Go differ. Needs go (scripts/install-toolchain.sh); sources env.sh
# itself. Exit 0 = every case behaved, 1 = at least one did not.
set -Eeuo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d -t env-test.XXXXXX)"
trap 'rm -rf "$WORK"' EXIT

SHELLS=(bash)
if command -v zsh > /dev/null; then
    SHELLS+=(zsh)
fi
# in_shell <shell> <directory> <script> [<argument>...]: runs the script in a fresh shell of that
# kind (no startup files) in that directory, without an inherited GOTOOLCHAIN.
in_shell() {
    local shell="$1" dir="$2" script="$3"
    shift 3
    local -a flags=(--noprofile --norc)
    [[ "$shell" == bash ]] || flags=(-f)
    (cd "$dir" && env -u GOTOOLCHAIN "$shell" "${flags[@]}" -c "$script" _ "$@")
}
# toolchain_after <shell> <directory> <path to env.sh>: sets RC and OUT (stdout and stderr) of
# sourcing env.sh and printing GOTOOLCHAIN, "<unset>" when it has none.
toolchain_after() {
    RC=0
    # shellcheck disable=SC2016
    OUT="$(in_shell "$1" "$2" 'source "$1" && printf "%s" "${GOTOOLCHAIN-<unset>}"' "$3" 2>&1)" || RC=$?
}
# go_line <go.mod>: the version on its go line.
go_line() { awk '$1 == "go" { print $2; exit }' "$1"; }
# tree <name> [<go line>]: a throwaway tree with a copy of env.sh and, given a go line (may be
# empty for a go.mod without one), a helper/go.mod; prints its path.
tree() {
    local t="$WORK/$1"
    mkdir -p "$t/scripts" "$t/helper"
    cp -p "$ROOT/scripts/env.sh" "$t/scripts/"
    if (( $# > 1 )); then
        printf 'module example.com/fixture\n\n%s\n' "${2:+go $2}" > "$t/helper/go.mod"
    fi
    printf '%s' "$t"
}

cases=0
failures=0
CASE=""
CASE_BAD=0
RC=0
OUT=""
begin() { CASE="$1"; CASE_BAD=0; cases=$(( cases + 1 )); }
bad() { echo "FAIL: $CASE: $*" >&2; CASE_BAD=1; }
finish() {
    if (( CASE_BAD )); then
        failures=$(( failures + 1 ))
        printf '%s\n' "--- $CASE: output of the last run (exit $RC):" "$OUT" >&2
    fi
}
expect_toolchain() {
    [[ "$RC" -eq 0 ]] || bad "$1: sourcing env.sh failed (exit $RC)"
    [[ "$OUT" == "$2" ]] || bad "$1: GOTOOLCHAIN is '$OUT', expected '$2'"
}
expect_refused() {
    [[ "$RC" -ne 0 ]] || bad "$1: sourcing env.sh succeeded"
    [[ "$OUT" == *'helper/go.mod'* ]] || bad "$1: the message does not name helper/go.mod"
    [[ "$OUT" != *'<unset>'* ]] || bad "$1: the script went on after the failed source"
}

REAL="$(go_line "$ROOT/helper/go.mod")"
[[ "$REAL" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "env-test.sh: helper/go.mod has the go line '$REAL'" >&2; exit 2; }

begin real_tree
for sh in "${SHELLS[@]}"; do
    toolchain_after "$sh" "$ROOT" scripts/env.sh
    expect_toolchain "$sh, repository root" "go$REAL+auto"
    toolchain_after "$sh" "$WORK" "$ROOT/scripts/env.sh"
    expect_toolchain "$sh, another directory" "go$REAL+auto"
    toolchain_after "$sh" "$ROOT/scripts" env.sh
    expect_toolchain "$sh, relative path" "go$REAL+auto"
done
finish

begin fixture_version
T="$(tree fixture_version 1.99.7)"
for sh in "${SHELLS[@]}"; do
    toolchain_after "$sh" "$T" scripts/env.sh
    expect_toolchain "$sh" 'go1.99.7+auto'
done
finish

begin no_go_line
T="$(tree no_go_line '')"
for sh in "${SHELLS[@]}"; do
    toolchain_after "$sh" "$T" scripts/env.sh
    expect_refused "$sh"
done
finish

# go 1.99 is a language version, not a toolchain: go1.99+auto would name no release.
begin short_go_line
T="$(tree short_go_line 1.99)"
for sh in "${SHELLS[@]}"; do
    toolchain_after "$sh" "$T" scripts/env.sh
    expect_refused "$sh"
done
finish

begin no_go_mod
T="$(tree no_go_mod)"
for sh in "${SHELLS[@]}"; do
    toolchain_after "$sh" "$T" scripts/env.sh
    expect_refused "$sh"
done
finish

# The rest runs go: put the installed one on PATH, as a caller of env.sh has it.
# shellcheck source=env.sh
source "$ROOT/scripts/env.sh"
command -v go > /dev/null || { echo "env-test.sh: go not found (scripts/install-toolchain.sh)" >&2; exit 2; }
INSTALLED="$(GOTOOLCHAIN=local go env GOVERSION)"

# A tree whose go line is the installed Go, so that env.sh selects it with nothing to download,
# and beside it a module that needs go 1.99.0, which is a stub on PATH that logs its arguments.
begin follows_newer
T="$(tree follows_newer "${INSTALLED#go}")"
mkdir -p "$WORK/bin" "$WORK/newer"
printf 'module example.com/newer\n\ngo 1.99.0\n' > "$WORK/newer/go.mod"
cat > "$WORK/bin/go1.99.0" <<'STUB'
#!/usr/bin/env bash
printf '[%s]\n' "$*" >> "$STUB_LOG"
STUB
chmod +x "$WORK/bin/go1.99.0"
export STUB_LOG="$WORK/stub.log"
for sh in "${SHELLS[@]}"; do
    : > "$STUB_LOG"
    RC=0
    # shellcheck disable=SC2016
    OUT="$(in_shell "$sh" "$T" 'source scripts/env.sh && PATH="$1:$PATH" && cd "$2" && GOENV=off GOPROXY=off go version' \
        "$WORK/bin" "$WORK/newer" 2>&1)" || RC=$?
    [[ "$RC" -eq 0 ]] || bad "$sh: go version failed in a module with a newer go line (exit $RC)"
    [[ "$(cat "$STUB_LOG")" == '[version]' ]] \
        || bad "$sh: go 1.99.0 was called as '$(cat "$STUB_LOG")', expected once with 'version'"
done
finish

begin runs_that_go
for sh in "${SHELLS[@]}"; do
    RC=0
    # shellcheck disable=SC2016
    OUT="$(in_shell "$sh" "$ROOT" 'source scripts/env.sh && cd helper && go env GOVERSION' 2>&1)" || RC=$?
    [[ "$RC" -eq 0 ]] || bad "$sh: go env GOVERSION failed (exit $RC)"
    [[ "$(tail -n 1 <<< "$OUT")" == "go$REAL" ]] \
        || bad "$sh: go reports '$(tail -n 1 <<< "$OUT")' in helper/, expected go$REAL (the installed one is $INSTALLED)"
done
finish

if (( failures )); then
    echo "env-test.sh: $failures of $cases cases failed" >&2
    exit 1
fi
echo "env-test.sh: ok: $cases cases"
