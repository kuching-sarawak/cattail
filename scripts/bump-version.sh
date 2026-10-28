#!/usr/bin/env bash
# Bump Cattail's version in the one place it is set by hand and in the file that must follow it.
#
#   scripts/bump-version.sh <version> [--changelog <text>] [--no-test]
#
# <version>          the new mod_version: <major>.<minor>.<patch>, with an optional -<pre-release>
#                    or +<build> suffix (0.2.0, 0.2.0-rc.1); no leading v
# --changelog <text> the changelog line update.json gets for this version (default: the public
#                    release page, https://github.com/kuching-sarawak/cattail/releases/tag/v<version>,
#                    which NeoForge's update screen shows as the changelog)
# --no-test          skip the Gradle check at the end (the next build runs it anyway)
#
# Edits, and nothing else:
#   mod/gradle.properties   the mod_version line (every other line is kept as it is)
#   docs/listing.md         every cattail-<current>+mc<mc> jar name becomes cattail-<version>+mc<mc>
#                           (the mod-site page names the jar; maintainers only, so a checkout
#                           without the file skips this with a note)
#   update.json             the promos <mc>-latest and <mc>-recommended become <version> (<mc> is
#                           minecraft_version from mod/gradle.properties) and the <mc> section
#                           gets the entry "<version>": <changelog> first, before the older
#                           entries, which are kept; the file is written with two-space indent
#                           and a final newline
# then runs UpdateJsonTest (cd mod && ./gradlew test -PskipHelper --tests '*UpdateJsonTest*'),
# the test that pins update.json to mod_version, and prints the next steps: commit, tag.
# Refuses (exit 2, nothing written): a malformed version; a version equal to the current one or
# already in the update.json section; uncommitted changes to any of the files (so the commit
# holds only the bump); a missing minecraft_version or mod_version line; an update.json whose
# promos or section are not objects; a docs/listing.md that names no cattail-<current>+mc<mc> jar. Exit 1: the Gradle check failed after the files were written (they
# stay written; fix and run the test again).
# Self-check: scripts/bump-version-test.sh.
set -Eeuo pipefail
ME=bump-version.sh
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PROPS="$ROOT/mod/gradle.properties"
JSON="$ROOT/update.json"
LISTING="$ROOT/docs/listing.md"
RE_VERSION='^[0-9]+\.[0-9]+\.[0-9]+([-+][0-9A-Za-z.-]+)?$'
RELEASES=https://github.com/kuching-sarawak/cattail/releases/tag

die() {
    local rc="$1"
    shift
    echo "$ME: $*" >&2
    exit "$rc"
}
say() { echo "==> $*"; }

VERSION=""
CHANGELOG=""
RUN_TEST=1
while (( $# > 0 )); do
    case "$1" in
        -h|--help)
            awk 'NR == 1 { next } /^#/ { print; next } { exit }' "$0"
            exit 0
            ;;
        --changelog)
            [[ $# -ge 2 && -n "$2" ]] || die 2 "--changelog needs a text"
            CHANGELOG="$2"
            shift
            ;;
        --no-test) RUN_TEST=0 ;;
        -*) die 2 "unknown option $1 (see --help)" ;;
        *)
            [[ -z "$VERSION" ]] || die 2 "one version only (got $VERSION and $1)"
            VERSION="$1"
            ;;
    esac
    shift
done
[[ -n "$VERSION" ]] || die 2 "usage: bump-version.sh <version> [--changelog <text>] [--no-test]"
[[ "$VERSION" =~ $RE_VERSION ]] \
    || die 2 "version $VERSION is not <major>.<minor>.<patch> with an optional -<pre-release> or +<build> suffix (no leading v)"
command -v python3 > /dev/null || die 2 "python3 not found"
[[ -f "$PROPS" ]] || die 2 "$PROPS not found"
[[ -f "$JSON" ]] || die 2 "$JSON not found"

CURRENT="$(sed -n 's/^mod_version=\([^[:space:]]*\).*$/\1/p' "$PROPS")"
MC="$(sed -n 's/^minecraft_version=\([^[:space:]]*\).*$/\1/p' "$PROPS")"
[[ -n "$CURRENT" ]] || die 2 "$PROPS has no mod_version line"
[[ -n "$MC" ]] || die 2 "$PROPS has no minecraft_version line"
[[ "$VERSION" != "$CURRENT" ]] || die 2 "mod_version is already $VERSION; nothing to do"
if git -C "$ROOT" rev-parse --is-inside-work-tree > /dev/null 2>&1; then
    changes="$(git -C "$ROOT" status --porcelain -- mod/gradle.properties update.json docs/listing.md)" \
        || die 2 "git status failed in $ROOT"
    [[ -z "$changes" ]] \
        || die 2 "mod/gradle.properties, update.json or docs/listing.md has uncommitted changes; commit or revert them first, so that the bump is its own commit"
fi
OLD_JAR="cattail-$CURRENT+mc$MC"
NEW_JAR="cattail-$VERSION+mc$MC"
if [[ -f "$LISTING" ]]; then
    grep -qF "$OLD_JAR" "$LISTING" \
        || die 2 "docs/listing.md names no $OLD_JAR jar; it must tell owners the jar name of the release"
fi
[[ -n "$CHANGELOG" ]] || CHANGELOG="$RELEASES/v$VERSION"

# update.json first: it can refuse (an entry for the version exists); then the properties.
say "update.json: promos $MC-latest and $MC-recommended = $VERSION; changelog entry for $VERSION"
python3 -I - "$JSON" "$MC" "$VERSION" "$CHANGELOG" <<'EOF' || exit 2
import json, os, sys
path, mc, version, changelog = sys.argv[1:5]
try:
    with open(path, encoding="utf-8") as fh:
        doc = json.load(fh, object_pairs_hook=lambda pairs: dict(pairs))
except ValueError as exc:
    sys.exit(f"bump-version.sh: {path} is not valid JSON: {exc}")
promos = doc.get("promos")
section = doc.get(mc)
if not isinstance(promos, dict):
    sys.exit(f"bump-version.sh: {path} has no \"promos\" object")
if section is None:
    section = {}
elif not isinstance(section, dict):
    sys.exit(f"bump-version.sh: {path}: \"{mc}\" is not an object")
if version in section:
    sys.exit(f"bump-version.sh: {path} already has a \"{mc}\" entry for {version}; nothing written")
promos[f"{mc}-latest"] = version
promos[f"{mc}-recommended"] = version
doc[mc] = {version: changelog, **section}
tmp = path + ".tmp"
with open(tmp, "w", encoding="utf-8") as fh:
    json.dump(doc, fh, indent=2, ensure_ascii=False)
    fh.write("\n")
os.replace(tmp, path)
EOF
say "mod/gradle.properties: mod_version=$CURRENT -> $VERSION"
python3 -I - "$PROPS" "$VERSION" <<'EOF' || exit 2
import os, re, sys
path, version = sys.argv[1:3]
with open(path, encoding="utf-8") as fh:
    text = fh.read()
new, n = re.subn(r"(?m)^mod_version=[^\r\n]*$", f"mod_version={version}", text)
if n != 1:
    sys.exit(f"bump-version.sh: {path} has {n} mod_version lines")
tmp = path + ".tmp"
with open(tmp, "w", encoding="utf-8") as fh:
    fh.write(new)
os.replace(tmp, path)
EOF

if [[ -f "$LISTING" ]]; then
    say "docs/listing.md: $OLD_JAR -> $NEW_JAR"
    python3 -I - "$LISTING" "$OLD_JAR" "$NEW_JAR" <<'EOF' || exit 2
import os, sys
path, old, new = sys.argv[1:4]
with open(path, encoding="utf-8") as fh:
    text = fh.read()
tmp = path + ".tmp"
with open(tmp, "w", encoding="utf-8") as fh:
    fh.write(text.replace(old, new))
os.replace(tmp, path)
EOF
else
    say "docs/listing.md not in this checkout (maintainers only); nothing to rewrite there"
fi

if (( RUN_TEST )); then
    say "check (UpdateJsonTest)"
    (cd "$ROOT/mod" && ./gradlew -q test -PskipHelper --tests '*UpdateJsonTest*') \
        || die 1 "UpdateJsonTest failed; the files are written, fix them and run: cd mod && ./gradlew test -PskipHelper --tests '*UpdateJsonTest*'"
fi
say "done: $CURRENT -> $VERSION in mod/gradle.properties, update.json and docs/listing.md (when present); next:"
# git commit -- <paths>, not git add: .gitignore lists /docs/, and git add exits 1 on a path under it.
echo "  git commit -m \"Bump to $VERSION\" -- mod/gradle.properties update.json docs/listing.md"
echo "  git tag v$VERSION && git push origin main v$VERSION   # after the merge to main"
echo "  then draft and publish the internal release v$VERSION (docs/build.md, Publish a release)"
