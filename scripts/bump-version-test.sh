#!/usr/bin/env bash
# Self-check for scripts/bump-version.sh: runs it in a throwaway git repository holding a copy of
# the script, a fixture mod/gradle.properties (0.1.0 on 1.21.1), a fixture update.json, a
# fixture docs/listing.md naming the 0.1.0 jar twice and a stub mod/gradlew that logs its
# arguments, and asserts the exit code, the output and the three files. Nothing runs Gradle.
#
#   scripts/bump-version-test.sh
#
# Cases: bump (files rewritten exactly, the entry first, the older one kept, gradlew called with
# the test filter, next steps printed), changelog_text, no_test, same_version, bad_version
# (five shapes), entry_exists, dirty_file (each file), gradle_fails (exit 1, files written),
# missing_lines, bad_json, promos_not_object, listing_absent (skipped with a note),
# listing_without_jar (exit 2, nothing written), listing_dirty, usage.
# Needs git and python3. Exit 0 = every case behaved, 1 = at least one did not.
set -Eeuo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
for t in git python3; do
    command -v "$t" > /dev/null || { echo "bump-version-test.sh: $t not found" >&2; exit 2; }
done
WORK="$(mktemp -d -t bump-version-test.XXXXXX)"
trap 'rm -rf "$WORK"' EXIT
export GIT_CONFIG_GLOBAL="$WORK/gitconfig" GIT_CONFIG_NOSYSTEM=1
: > "$GIT_CONFIG_GLOBAL"
export GRADLE_LOG="$WORK/gradlew.log"

PROPS=$'org.gradle.jvmargs=-Xmx4G\nminecraft_version=1.21.1\nneo_version=21.1.264\nmod_id=cattail\nmod_version=0.1.0\nplasmo_voice_version=neoforge-1.21.1-2.1.17\n'
JSON='{
  "homepage": "https://github.com/kuching-sarawak/cattail/releases",
  "promos": {
    "1.21.1-latest": "0.1.0",
    "1.21.1-recommended": "0.1.0"
  },
  "1.21.1": {
    "0.1.0": "First release."
  }
}
'
LISTING='Download `cattail-0.1.0+mc1.21.1.jar` or `cattail-0.1.0+mc1.21.1-novoice.jar`; 0.1.0 is the first.
'
EXPECT_LISTING='Download `cattail-0.2.0+mc1.21.1.jar` or `cattail-0.2.0+mc1.21.1-novoice.jar`; 0.1.0 is the first.
'
EXPECT_JSON='{
  "homepage": "https://github.com/kuching-sarawak/cattail/releases",
  "promos": {
    "1.21.1-latest": "0.2.0",
    "1.21.1-recommended": "0.2.0"
  },
  "1.21.1": {
    "0.2.0": "https://github.com/kuching-sarawak/cattail/releases/tag/v0.2.0",
    "0.1.0": "First release."
  }
}
'

# reset: a fresh repository at $WORK/repo with everything committed.
reset() {
    local r="$WORK/repo"
    rm -rf "$r"
    mkdir -p "$r/scripts" "$r/mod" "$r/docs"
    cp -p "$ROOT/scripts/bump-version.sh" "$r/scripts/"
    printf '%s' "$PROPS" > "$r/mod/gradle.properties"
    printf '%s' "$JSON" > "$r/update.json"
    printf '%s' "$LISTING" > "$r/docs/listing.md"
    cat > "$r/mod/gradlew" <<'STUB'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "$GRADLE_LOG"
exit "${GRADLE_STUB_RC:-0}"
STUB
    chmod +x "$r/mod/gradlew"
    git -C "$r" init -q -b main
    git -C "$r" add -A
    git -C "$r" -c user.name=Fixture -c user.email=fixture@example.com commit -q -m init
    : > "$GRADLE_LOG"
    unset GRADLE_STUB_RC
}
run() {
    RC=0
    OUT="$(cd "$WORK/repo" && scripts/bump-version.sh "$@" 2>&1)" || RC=$?
}
cases=0
failures=0
CASE=""
CASE_BAD=0
begin() { CASE="$1"; CASE_BAD=0; cases=$(( cases + 1 )); reset; }
bad() { echo "FAIL: $CASE: $*" >&2; CASE_BAD=1; }
finish() {
    if (( CASE_BAD )); then
        failures=$(( failures + 1 ))
        printf '%s\n' "--- $CASE: output of the last run (exit $RC):" "$OUT" >&2
    fi
}
expect_rc() { [[ "$RC" -eq "$1" ]] || bad "expected exit $1, got $RC"; }
expect_out() { local s; for s in "$@"; do [[ "$OUT" == *"$s"* ]] || bad "output lacks '$s'"; done; }
expect_unchanged() {
    [[ "$(cat "$WORK/repo/mod/gradle.properties")" == "$(printf '%s' "$PROPS")" ]] || bad "gradle.properties changed"
    [[ "$(cat "$WORK/repo/update.json")" == "$(printf '%s' "$JSON")" ]] || bad "update.json changed"
    [[ ! -f "$WORK/repo/docs/listing.md" || "$(cat "$WORK/repo/docs/listing.md")" == "$(printf '%s' "$LISTING")" ]] \
        || bad "docs/listing.md changed"
}
expect_bumped() {
    [[ "$(cat "$WORK/repo/update.json")" == "$(printf '%s' "$EXPECT_JSON")" ]] \
        || bad "update.json differs from the expected text:"$'\n'"$(cat "$WORK/repo/update.json")"
    [[ "$(cat "$WORK/repo/mod/gradle.properties")" == "$(printf '%s' "${PROPS/mod_version=0.1.0/mod_version=0.2.0}")" ]] \
        || bad "gradle.properties is not the fixture with mod_version=0.2.0 only"
    [[ ! -f "$WORK/repo/docs/listing.md" || "$(cat "$WORK/repo/docs/listing.md")" == "$(printf '%s' "$EXPECT_LISTING")" ]] \
        || bad "docs/listing.md is not the fixture with the 0.2.0 jar names only:"$'\n'"$(cat "$WORK/repo/docs/listing.md")"
}

begin bump
run 0.2.0
expect_rc 0
expect_bumped
expect_out '0.1.0 -> 0.2.0' 'git tag v0.2.0' 'Bump to 0.2.0'
[[ "$(cat "$GRADLE_LOG")" == "-q test -PskipHelper --tests *UpdateJsonTest*" ]] \
    || bad "gradlew was called with '$(cat "$GRADLE_LOG")'"
finish

begin changelog_text
run 0.2.0 --changelog 'Voice fixes.'
expect_rc 0
grep -q '"0.2.0": "Voice fixes.",' "$WORK/repo/update.json" || bad "the changelog text is not the entry"
grep -q '"0.1.0": "First release."' "$WORK/repo/update.json" || bad "the older entry was lost"
finish

begin no_test
run 0.2.0 --no-test
expect_rc 0
expect_bumped
[[ ! -s "$GRADLE_LOG" ]] || bad "gradlew ran with --no-test"
finish

begin same_version
run 0.1.0
expect_rc 2
expect_out 'already 0.1.0'
expect_unchanged
finish

begin bad_version
for v in v0.2.0 0.2 0.2.0.1 '0.2.0 beta' 0.2.0_1; do
    run "$v"
    expect_rc 2
    expect_out 'is not <major>.<minor>.<patch>'
    expect_unchanged
done
finish

begin entry_exists
python3 - "$WORK/repo/update.json" <<'EOF'
import json, sys
p = sys.argv[1]
d = json.load(open(p))
d["1.21.1"]["0.2.0"] = "Already here."
json.dump(d, open(p, "w"), indent=2)
EOF
git -C "$WORK/repo" -c user.name=Fixture -c user.email=fixture@example.com commit -q -am entry
run 0.2.0
expect_rc 2
expect_out 'already has a "1.21.1" entry for 0.2.0'
grep -q 'mod_version=0.1.0' "$WORK/repo/mod/gradle.properties" || bad "gradle.properties was changed"
finish

begin dirty_file
printf '# touched\n' >> "$WORK/repo/update.json"
run 0.2.0
expect_rc 2
expect_out 'uncommitted changes'
grep -q 'mod_version=0.1.0' "$WORK/repo/mod/gradle.properties" || bad "gradle.properties was changed"
reset
printf '# touched\n' >> "$WORK/repo/mod/gradle.properties"
run 0.2.0
expect_rc 2
expect_out 'uncommitted changes'
[[ "$(cat "$WORK/repo/update.json")" == "$(printf '%s' "$JSON")" ]] || bad "update.json was changed"
finish

begin gradle_fails
export GRADLE_STUB_RC=1
run 0.2.0
expect_rc 1
expect_out 'UpdateJsonTest failed'
expect_bumped
unset GRADLE_STUB_RC
finish

begin missing_lines
printf 'mod_version=0.1.0\n' > "$WORK/repo/mod/gradle.properties"
git -C "$WORK/repo" -c user.name=Fixture -c user.email=fixture@example.com commit -q -am nomc
run 0.2.0
expect_rc 2
expect_out 'no minecraft_version line'
reset
printf 'minecraft_version=1.21.1\n' > "$WORK/repo/mod/gradle.properties"
git -C "$WORK/repo" -c user.name=Fixture -c user.email=fixture@example.com commit -q -am noversion
run 0.2.0
expect_rc 2
expect_out 'no mod_version line'
finish

begin bad_json
printf '{\n' > "$WORK/repo/update.json"
git -C "$WORK/repo" -c user.name=Fixture -c user.email=fixture@example.com commit -q -am badjson
run 0.2.0
expect_rc 2
expect_out 'not valid JSON'
grep -q 'mod_version=0.1.0' "$WORK/repo/mod/gradle.properties" || bad "gradle.properties was changed"
finish

begin promos_not_object
printf '{"promos": "x", "1.21.1": {}}\n' > "$WORK/repo/update.json"
git -C "$WORK/repo" -c user.name=Fixture -c user.email=fixture@example.com commit -q -am promos
run 0.2.0
expect_rc 2
expect_out 'no "promos" object'
finish

begin listing_absent
rm "$WORK/repo/docs/listing.md"
git -C "$WORK/repo" -c user.name=Fixture -c user.email=fixture@example.com commit -q -am nolisting
run 0.2.0
expect_rc 0
expect_bumped
expect_out 'docs/listing.md not in this checkout'
finish

begin listing_without_jar
printf 'No jar named here.\n' > "$WORK/repo/docs/listing.md"
git -C "$WORK/repo" -c user.name=Fixture -c user.email=fixture@example.com commit -q -am nojar
run 0.2.0
expect_rc 2
expect_out 'names no cattail-0.1.0+mc1.21.1 jar'
grep -q 'mod_version=0.1.0' "$WORK/repo/mod/gradle.properties" || bad "gradle.properties was changed"
[[ "$(cat "$WORK/repo/update.json")" == "$(printf '%s' "$JSON")" ]] || bad "update.json was changed"
finish

begin listing_dirty
printf 'edited\n' >> "$WORK/repo/docs/listing.md"
run 0.2.0
expect_rc 2
expect_out 'uncommitted changes'
grep -q 'mod_version=0.1.0' "$WORK/repo/mod/gradle.properties" || bad "gradle.properties was changed"
finish

begin usage
run
expect_rc 2
expect_out 'usage:'
run 0.2.0 0.3.0
expect_rc 2
expect_out 'one version only'
run 0.2.0 --bogus
expect_rc 2
expect_out 'unknown option'
run --help
expect_rc 0
expect_out 'scripts/bump-version.sh <version>'
expect_unchanged
finish

if (( failures )); then
    echo "bump-version-test.sh: $failures of $cases cases failed" >&2
    exit 1
fi
echo "bump-version-test.sh: ok: $cases cases"
