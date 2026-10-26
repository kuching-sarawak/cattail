#!/usr/bin/env bash
# Self-check for scripts/readme-lint.sh: builds throwaway Markdown files and asserts which pass
# and which fail with which rule. Run it after every change to readme-lint.sh.
#
#   scripts/readme-lint-test.sh
#
# Pass (one README.md that must be clean): Contents entries to a heading with a colon and to one
# with a comma, a relative link with an anchor to a file that exists, an image and an <img> tag
# with alt text whose file exists, an IMPORTANT and a CAUTION alert separated by a paragraph, the
# documentation placeholders for a key, a Tailscale address and a domain, and a missing link and
# an alert inside a fenced code block plus an anchor inside inline code (none of them counts).
# Fail (one small README.md each; exit 1 and exactly one finding, "README.md:<line>: <rule>: "):
# anchor_missing behind a fenced "# Does not exist" (so a fenced heading must not count and the
# fence must close), anchor_punctuation, link_missing, link_missing for a missing file behind an
# anchor, link_missing for an <img> src, alt_missing, alt_missing for an <img> without alt and
# for one with alt="", alert_count, alert_inside_details, alert_in_list, alert_adjacent,
# em_dash, and gate_hit with the default denylist. Usage: no arguments, exit 2.
# The em dash and the denylist entry are assembled at run time (EM, HOST) so that this file
# holds no em dash and passes check-public.sh with scripts/check-public.example.txt.
# Exit 0 = every case behaved, 1 = at least one did not.
set -Eeuo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LINT="$ROOT/scripts/readme-lint.sh"
EM="$(printf '\342\200\224')" # U+2014 as UTF-8
HOST=my-server
HOST="$HOST-hostname" # an entry of scripts/check-public.example.txt
WORK="$(mktemp -d -t readme-lint-test.XXXXXX)"
trap 'rm -rf "$WORK"' EXIT

cases=0
failures=0
bad() { echo "FAIL: $*" >&2; failures=$(( failures + 1 )); }

# fixture <case> <line>...: writes the lines to $WORK/<case>/README.md.
fixture() {
    local dir="$WORK/$1"
    shift
    mkdir -p "$dir"
    printf '%s\n' "$@" > "$dir/README.md"
}

# run_lint <case> [arg...]: sets RC and OUT (stdout and stderr) of readme-lint.sh started in
# $WORK/<case>.
run_lint() {
    local dir="$WORK/$1"
    shift
    RC=0
    OUT="$(cd "$dir" && "$LINT" "$@" 2>&1)" || RC=$?
}

# expect_fail <case> <line> <rule>: lints README.md in $WORK/<case> and expects exit 1 and
# exactly one finding, "README.md:<line>: <rule>: ...".
expect_fail() {
    local name="$1" line="$2" rule="$3" findings
    cases=$(( cases + 1 ))
    run_lint "$name" README.md
    findings="$(grep -cE '^README\.md:[^:]+: [a-z_]+: ' <<<"$OUT" || true)"
    if [[ "$RC" -ne 1 ]]; then
        bad "$name: expected exit 1, got exit $RC:"$'\n'"$OUT"
    elif [[ $'\n'"$OUT" != *$'\n'"README.md:$line: $rule: "* ]]; then
        bad "$name: expected the finding 'README.md:$line: $rule: ':"$'\n'"$OUT"
    elif [[ "$findings" -ne 1 ]]; then
        bad "$name: expected exactly one finding, got $findings:"$'\n'"$OUT"
    fi
}

# Pass: every construct the README needs, in one file that must be clean.
fixture pass \
    '# Fixture' \
    '' \
    '## Contents' \
    '' \
    '- [Which key: auth key or OAuth client](#which-key-auth-key-or-oauth-client)' \
    '- [Set up, step by step](#set-up-step-by-step)' \
    '' \
    '## Which key: auth key or OAuth client' \
    '' \
    'The voice grant is in the [policy](policy/README.md#voice).' \
    '' \
    '```markdown' \
    '[x](nope.md)' \
    '> [!NOTE]' \
    '```' \
    '' \
    'Inline code such as `](#nope)` is not a link.' \
    '' \
    '> [!IMPORTANT]' \
    '> An auth key lasts up to 90 days.' \
    '' \
    'Placeholders: tskey-auth-EXAMPLE, 100.101.102.103 and mc.example.com.' \
    '' \
    '> [!CAUTION]' \
    '> The injected jar holds your key.' \
    '' \
    '## Set up, step by step' \
    '' \
    '![Cattail logo](.github/cattail-logo.png)' \
    '' \
    '<p align="center"><img src=".github/cattail-logo.png" width="100" alt="Cattail logo"></p>'
mkdir -p "$WORK/pass/policy" "$WORK/pass/.github"
printf '%s\n' '# Policy' '' '## Voice' > "$WORK/pass/policy/README.md"
printf 'not a real image\n' > "$WORK/pass/.github/cattail-logo.png"
cases=$(( cases + 1 ))
run_lint pass README.md
if [[ "$RC" -ne 0 || "$OUT" != *"readme-lint.sh: clean"* ]]; then
    bad "pass: expected exit 0 and clean, got exit $RC:"$'\n'"$OUT"
fi

# The fenced "# Does not exist" is code, not a heading, and the fence closes before the entry.
fixture anchor_missing \
    '# Fixture' '' '```bash' '# Does not exist' '```' '' '- [Nope](#does-not-exist)'
expect_fail anchor_missing 7 anchor_missing

fixture anchor_punctuation \
    '# Fixture' '' '- [Which key](#which-key:-auth-key)' '' '## Which key: auth key or OAuth client'
expect_fail anchor_punctuation 3 anchor_punctuation

fixture link_missing '# Fixture' '' 'See [x](docs/nope.md).'
expect_fail link_missing 3 link_missing

# The file behind the anchor is missing (no policy/ here): reported as link_missing.
fixture link_with_anchor '# Fixture' '' 'See [x](policy/README.md#voice).'
expect_fail link_with_anchor 3 link_missing

fixture img_link_missing '# Fixture' '' '<img src="nope.png" alt="x">'
expect_fail img_link_missing 3 link_missing

# The image file exists, so only the empty alt text is wrong.
fixture alt_missing '# Fixture' '' '![](.github/cattail-logo.png)'
mkdir -p "$WORK/alt_missing/.github"
printf 'not a real image\n' > "$WORK/alt_missing/.github/cattail-logo.png"
expect_fail alt_missing 3 alt_missing

# The <img> file exists, so only the missing alt attribute is wrong.
fixture img_alt_missing '# Fixture' '' '<img src="x.png">'
printf 'not a real image\n' > "$WORK/img_alt_missing/x.png"
expect_fail img_alt_missing 3 alt_missing

fixture img_alt_empty '# Fixture' '' '<img src="x.png" alt="">'
printf 'not a real image\n' > "$WORK/img_alt_empty/x.png"
expect_fail img_alt_empty 3 alt_missing

fixture alert_count \
    '# Fixture' '' '> [!NOTE]' '> One.' '' 'Text.' '' '> [!TIP]' '> Two.' '' 'Text.' '' \
    '> [!WARNING]' '> Three.'
expect_fail alert_count 13 alert_count

fixture alert_inside_details \
    '# Fixture' '' '<details>' '<summary>More</summary>' '' '> [!NOTE]' '> Inside.' '' '</details>'
expect_fail alert_inside_details 6 alert_inside_details

fixture alert_in_list '# Fixture' '' '1. step' '   > [!NOTE]' '   > Indented.'
expect_fail alert_in_list 4 alert_in_list

# Only a blank line between the two alerts.
fixture alert_adjacent '# Fixture' '' '> [!NOTE]' '> One.' '' '> [!TIP]' '> Two.'
expect_fail alert_adjacent 6 alert_adjacent

fixture em_dash '# Fixture' '' "Cattail $EM the helper relays."
expect_fail em_dash 3 em_dash

fixture gate_hit '# Fixture' '' "Connect to $HOST."
expect_fail gate_hit 3 gate_hit

cases=$(( cases + 1 ))
run_lint pass
if [[ "$RC" -ne 2 || "$OUT" != *'<markdown file>'* ]]; then
    bad "usage: expected exit 2 and the usage text, got exit $RC:"$'\n'"$OUT"
fi

if (( failures > 0 )); then
    echo "readme-lint-test.sh: FAIL: $failures of $cases case(s)" >&2
    exit 1
fi
echo "readme-lint-test.sh: ok: $cases cases"
