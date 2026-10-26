#!/usr/bin/env bash
# Self-check for scripts/check-public.sh: builds throwaway trees and asserts which Tailscale key
# strings pass as placeholders and which are hits. Run it after every change to check-public.sh.
#
#   scripts/check-public-test.sh
#
# Pass: the placeholders tskey-auth-EXAMPLE and tskey-client-EXAMPLE (with or without a ?query)
# and the bare prefixes tskey-auth- and tskey-client- (Java constants). Fail: anything else with
# the prefix, a key or a MagicDNS name inside a placeholder's ?query, a key behind a NUL or other
# control byte in that query, and a key, Tailscale address, MagicDNS name or denylist entry behind
# an invalid UTF-8 byte on its line. Each fail case sits in a tree that also holds every pass case,
# so the hit must name exactly the failing file and must not print the key. The failing strings
# are assembled at run time from K, K_UPPER, D and N (and written with printf %b, so \000 is a
# NUL and \0351 a lone byte 0xE9) so that this file itself passes check-public.sh. Every case runs
# twice, with check-public.sh started in a UTF-8 locale and in the C locale, so the result never
# depends on the caller's locale. One more case per locale reaches the top of a git work tree
# through a symlink (as macOS /tmp and /var are): it must still be checked in tracked-files mode,
# so an untracked file holding a denylist entry is not a hit there.
# Exit 0 = every case behaved, 1 = at least one did not.
set -Eeuo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CHECK="$ROOT/scripts/check-public.sh"
K=tskey
K_UPPER=TSKEY
D=.ts # the MagicDNS domain is D followed by ".net"
N=100 # a Tailscale address is N followed by three more parts
DENIED=cattail-check-public-test-denied-entry
LOCALES=(C.UTF-8 C)
WORK="$(mktemp -d -t check-public-test.XXXXXX)"
trap 'rm -rf "$WORK"' EXIT

# One denylist entry that occurs nowhere and one that only the invalid-byte case holds, so
# elsewhere only the built-in patterns can hit.
printf 'cattail-check-public-test-unused-entry\n%s\n' "$DENIED" > "$WORK/deny"

PASS_CASES=(
    'tskey-auth-EXAMPLE'
    'tskey-client-EXAMPLE?ephemeral=true'
    '"tskey-client-"'
    '"tskey-auth-"'
    'tskey-client-EXAMPLE'
    'key=tskey-client-EXAMPLE?ephemeral=true&preauthorized=true'
    '`tskey-auth-` or `tskey-client-` at the end: tskey-auth-'
    'two on a line: tskey-auth-EXAMPLE, tskey-client-EXAMPLE.'
)
# "<content, printf %b escapes>|<secret part the output must not show, empty when none>"
FAIL_CASES=(
    "$K-auth-EXAMPLEX|EXAMPLEX"
    "$K-auth-abc123|abc123"
    "$K-client-EXAMPLE-2|EXAMPLE-2"
    "$K_UPPER-AUTH-EXAMPLE|AUTH-EXAMPLE"
    "\"$K-\"|$K-\""
    "$K-auth-EXAMPLE?x=$K-auth-abc123|abc123"
    "ok $K-auth-EXAMPLE then $K-client-abc123 on the same line|abc123"
    "$K-client-EXAMPLE?url=https://host$D.net/|https://host"
    "$K-auth-EXAMPLE?a\\000$K-auth-abc123|abc123"
    "caf\\0351 then $K-auth-abc123|abc123"
    "\\0200 address $N.90.1.2|"
    "caf\\0351 at host$D.net|"
    "caf\\0351 near $DENIED|"
)

failures=0
bad() { echo "FAIL: $*" >&2; failures=$(( failures + 1 )); }

# new_tree: a directory (not a git work tree) holding pass-<n>.txt for every pass case.
new_tree() {
    local tree i
    tree="$(mktemp -d "$WORK/tree.XXXXXX")"
    for i in "${!PASS_CASES[@]}"; do
        printf '%s\n' "${PASS_CASES[$i]}" > "$tree/pass-$i.txt"
    done
    printf '%s\n' "$tree"
}

# run_check <tree>: sets RC and OUT (stdout and stderr of check-public.sh started with
# LC_ALL=$LOC).
run_check() {
    RC=0
    OUT="$(LC_ALL="$LOC" "$CHECK" "$WORK/deny" "$1" 2>&1)" || RC=$?
}

for LOC in "${LOCALES[@]}"; do
    # Every pass case together: clean.
    tree="$(new_tree)"
    run_check "$tree"
    if [[ "$RC" -ne 0 || "$OUT" != *"check-public.sh: clean:"* ]]; then
        bad "[$LOC] pass set: expected exit 0 and clean, got exit $RC:"$'\n'"$OUT"
    fi

    # Each fail case beside the pass set: exit 1, the hit names fail.txt and nothing else, no key.
    for c in "${FAIL_CASES[@]}"; do
        content="${c%|*}"
        secret="${c##*|}"
        tree="$(new_tree)"
        printf '%b\n' "$content" > "$tree/fail.txt"
        run_check "$tree"
        hits="$(grep -v '^check-public.sh: ' <<<"$OUT" || true)"
        if [[ "$RC" -ne 1 ]]; then
            bad "[$LOC] fail case '$content': expected exit 1, got exit $RC:"$'\n'"$OUT"
        elif ! grep -q '^fail\.txt:1: ' <<<"$hits"; then
            bad "[$LOC] fail case '$content': no hit naming fail.txt:"$'\n'"$OUT"
        elif grep -qv '^fail\.txt:1: ' <<<"$hits"; then
            bad "[$LOC] fail case '$content': a hit names another file:"$'\n'"$OUT"
        elif [[ -n "$secret" && "$OUT" == *"$secret"* ]]; then
            bad "[$LOC] fail case '$content': output shows the key part '$secret':"$'\n'"$OUT"
        fi
    done

    # A key in a file name is a hit too, named by the ":name:" form.
    tree="$(new_tree)"
    printf 'x\n' > "$tree/$K-auth-abc123.txt"
    run_check "$tree"
    if [[ "$RC" -ne 1 || "$OUT" != *"$K-auth-abc123.txt:name: "* ]]; then
        bad "[$LOC] key in a file name: expected exit 1 and a :name: hit, got exit $RC:"$'\n'"$OUT"
    fi

    # The top of a git work tree reached through a symlink: tracked files only, so the untracked
    # file holding a denylist entry is no hit (in all-files mode it would be one).
    tree="$(new_tree)"
    git init -q "$tree"
    git -C "$tree" add pass-0.txt
    printf 'near %s\n' "$DENIED" > "$tree/untracked.txt"
    ln -s "$tree" "$tree.link"
    run_check "$tree.link"
    if [[ "$RC" -ne 0 || "$OUT" != *"(git ls-files, 1 files)"* ]]; then
        bad "[$LOC] git tree through a symlink: expected exit 0 in git ls-files mode, got exit $RC:"$'\n'"$OUT"
    fi
done

per_locale=$(( 1 + ${#FAIL_CASES[@]} + 2 ))
total=$(( per_locale * ${#LOCALES[@]} ))
if (( failures > 0 )); then
    echo "check-public-test.sh: FAIL: $failures of $total case(s)" >&2
    exit 1
fi
echo "check-public-test.sh: ok: $total cases in ${#LOCALES[@]} locales (${LOCALES[*]})," \
    "each: ${#PASS_CASES[@]} placeholders in one clean tree, $(( per_locale - 2 )) hits, a git" \
    "tree through a symlink"
