#!/usr/bin/env bash
# Lint for the Markdown pages that leave this repository (README.md, PLAYERS.md,
# policy/README.md and the like): fails on the first broken Contents anchor, missing relative
# link target, image without alt text, misplaced GitHub alert, em dash or public-tree gate hit.
#
#   scripts/readme-lint.sh [--denylist <file>] <markdown file>...
#
# --denylist <file>  denylist for scripts/check-public.sh (default: check-public.example.txt next
#                    to this script; the release script passes the real one).
# <markdown file>    checked in the order given; per file the rules below run in that order, and
#                    the first finding ends the run.
#
# Rules (all but em_dash and gate_hit skip fenced code blocks and inline code spans):
#   anchor_missing        a ](#target) that is not the anchor of a heading in the same file
#   anchor_punctuation    the same, when the target holds a character outside [a-z0-9_-]
#   link_missing          a ](path) or ](path#fragment) with no scheme, or the src="path" of an
#                         <img> tag, whose file does not exist; a path starting with / is looked
#                         up from the repository root (the directory above this script), any
#                         other from the file's directory. A #fragment, a ?query and a "title"
#                         are not part of the path.
#   alt_missing           an image with empty alt text: ![ directly followed by ](, or an <img>
#                         tag with no alt attribute or an empty one (a tag is read up to the
#                         first > on its line)
#   alert_count           more than two GitHub alerts ("> [!TYPE]" lines) in a file named
#                         README.md, or any alert in another file
#   alert_inside_details  an alert between a <details line and its </details>
#   alert_in_list         an alert line indented by a space or tab (a quote inside a list item)
#   alert_adjacent        an alert with no line since the previous alert that is neither blank
#                         nor a quote line (starting with >)
#   em_dash               the UTF-8 bytes of U+2014 anywhere in the file
#   gate_hit              a hit of scripts/check-public.sh <denylist> on a copy of the file; every
#                         hit of that run is printed, <line> is "name" for a hit in the file name
# A heading's anchor follows GitHub: the ATX heading text with inline code markers and link
# syntax stripped, lowercased, every character outside [a-z0-9 _-] removed, each space turned
# into -; repeats get -1, -2 and so on. Setext (underlined) headings are not recognised.
# A finding prints as "<file>:<line>: <rule>: <detail>" on stdout, lines counted from 1.
# Self-check: scripts/readme-lint-test.sh.
# Exit 0 = clean, 1 = a finding, 2 = usage error or unreadable file.
set -Eeuo pipefail
# Byte semantics for grep, sed, tr and bash patterns: the em dash is matched as three bytes, and
# [a-z] means the ASCII letters only.
export LC_ALL=C

SCRIPTS="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$SCRIPTS/.." && pwd)"
GATE="$SCRIPTS/check-public.sh"
DENYLIST="$SCRIPTS/check-public.example.txt"
EM=$'\342\200\224' # U+2014 as UTF-8, spelled in octal so that this file holds none

RE_FENCE='^[[:blank:]]*(`{3,}|~{3,})'
RE_HEADING='^ {0,3}#{1,6}([[:blank:]]+(.*))?$'
RE_ONLY_HASHES='^#+$'
RE_CLOSING_HASHES='^(.*[^[:blank:]])[[:blank:]]+#+$'
RE_CODE_SPAN='`[^`]*`'
RE_TARGET='[]][(]([^)]*)[)]'
RE_SCHEME='^[A-Za-z][A-Za-z0-9+.-]*:'
RE_IMG='<[Ii][Mm][Gg]([[:blank:]/][^>]*)?(>|$)'
RE_IMG_ALT="[[:blank:]/][Aa][Ll][Tt][[:blank:]]*=[[:blank:]]*(\"[^\"]*\"|'[^']*'|[^[:blank:]\"'>]+)"
RE_IMG_SRC="[[:blank:]/][Ss][Rr][Cc][[:blank:]]*=[[:blank:]]*(\"[^\"]*\"|'[^']*'|[^[:blank:]\"'>]+)"
RE_ALERT='^([[:blank:]]*)>[[:blank:]]*[[]![A-Za-z]+[]]'
RE_BLANK_OR_QUOTE='^[[:blank:]]*(>|$)'

usage() { sed -n '6,11p' "$0" >&2; exit 2; }
fail() { echo "readme-lint.sh: ERROR: $*" >&2; exit 2; }

while (( $# > 0 )); do
    case "$1" in
        -h|--help) usage ;;
        --denylist)
            (( $# >= 2 )) || usage
            DENYLIST="$2"
            shift 2
            ;;
        --) shift; break ;;
        -?*) usage ;;
        *) break ;;
    esac
done
(( $# >= 1 )) || usage
[[ -f "$DENYLIST" && -r "$DENYLIST" ]] || fail "denylist not readable: $DENYLIST"
[[ -f "$GATE" && -x "$GATE" ]] || fail "public-tree gate missing or not executable: $GATE"
for f in "$@"; do
    [[ -f "$f" && -r "$f" ]] || fail "not a readable file: $f"
done

WORK="$(mktemp -d -t readme-lint.XXXXXX)"
trap 'rm -rf "$WORK"' EXIT

# finding <file> <line> <rule> <detail>: prints the finding and ends the run with exit 1.
finding() {
    printf '%s:%s: %s: %s\n' "$1" "$2" "$3" "$4"
    echo "readme-lint.sh: FAIL: $3 in $1" >&2
    exit 1
}

# trim <text>: TRIMMED = <text> without leading and trailing blanks.
trim() {
    local s="$1"
    s="${s#"${s%%[![:blank:]]*}"}"
    s="${s%"${s##*[![:blank:]]}"}"
    TRIMMED="$s"
}

# attr <tag> <regex>: ATTR = the value, without quotes, of the attribute <regex> matches in
# <tag>; status 1 (ATTR untouched) when the tag has no such attribute with a value.
attr() {
    local v
    [[ "$1" =~ $2 ]] || return 1
    v="${BASH_REMATCH[1]}"
    case "$v" in
        \"*) v="${v#\"}"; v="${v%\"}" ;;
        \'*) v="${v#\'}"; v="${v%\'}" ;;
    esac
    ATTR="$v"
}

# parse <file>: reads the file once and collects what the rules check:
#   $WORK/heads            the text of every heading, one per line, in order
#   ANC_LINE, ANC_TGT      every ](#target): its line and the target without the #
#   LNK_LINE, LNK_TGT      every other ](target) and <img> src with no scheme: line and target
#   ALT_LINE, ALT_DETAIL   the first line holding ![]( or an <img> tag without alt text, and what
#                          it holds; ALT_LINE is empty when no line does
#   AL_LINE, AL_INDENT, AL_DETAILS, AL_PREV
#                          every alert: its line, 1 when indented, 1 when inside <details>, and
#                          the line of the alert it directly follows (0 when there is none)
parse() {
    local file="$1" line scan rest m t n=0 fence="" depth=0 last_alert=0
    ANC_LINE=(); ANC_TGT=(); LNK_LINE=(); LNK_TGT=(); ALT_LINE=""; ALT_DETAIL=""
    AL_LINE=(); AL_INDENT=(); AL_DETAILS=(); AL_PREV=()
    : > "$WORK/heads"
    while IFS= read -r line || [[ -n "$line" ]]; do
        n=$(( n + 1 ))
        line="${line%$'\r'}"
        # Fenced code block: skip up to the closing fence (same character, at least as long).
        if [[ -n "$fence" ]]; then
            if [[ "$line" =~ $fence ]]; then
                fence=""
            fi
            continue
        fi
        if [[ "$line" =~ $RE_FENCE ]]; then
            m="${BASH_REMATCH[1]}"
            fence="^[[:blank:]]*${m:0:1}{${#m},}[[:blank:]]*\$"
            last_alert=0
            continue
        fi

        if [[ "$line" =~ $RE_HEADING ]]; then
            trim "${BASH_REMATCH[2]}"
            t="$TRIMMED"
            if [[ "$t" =~ $RE_ONLY_HASHES ]]; then
                t=""
            elif [[ "$t" =~ $RE_CLOSING_HASHES ]]; then
                t="${BASH_REMATCH[1]}"
            fi
            printf '%s\n' "$t" >> "$WORK/heads"
        fi

        # Inline code spans are not Markdown: drop them before looking for links and tags.
        scan="$line"
        while [[ "$scan" =~ $RE_CODE_SPAN ]]; do
            m="${BASH_REMATCH[0]}"
            scan="${scan%%"$m"*}${scan#*"$m"}"
        done

        rest="$scan"
        while [[ "$rest" =~ $RE_TARGET ]]; do
            m="${BASH_REMATCH[0]}"
            rest="${rest#*"$m"}"
            trim "${m:2:${#m}-3}"
            t="$TRIMMED"
            if [[ "$t" == '<'* ]]; then
                t="${t#<}"
                t="${t%%>*}"
            else
                t="${t%%[[:blank:]]*}" # drop a "title"
            fi
            if [[ "$t" == '#'* ]]; then
                ANC_LINE+=("$n")
                ANC_TGT+=("${t#\#}")
            elif [[ ! "$t" =~ $RE_SCHEME ]]; then
                LNK_LINE+=("$n")
                LNK_TGT+=("$t")
            fi
        done

        if [[ -z "$ALT_LINE" && "$scan" == *'![]('* ]]; then
            ALT_LINE="$n"
            ALT_DETAIL="image with empty alt text"
        fi

        rest="$scan"
        while [[ "$rest" =~ $RE_IMG ]]; do
            m="${BASH_REMATCH[0]}"
            rest="${rest#*"$m"}"
            if [[ -z "$ALT_LINE" ]]; then
                ATTR=""
                attr "$m" "$RE_IMG_ALT" || true
                if [[ -z "$ATTR" ]]; then
                    ALT_LINE="$n"
                    ALT_DETAIL="<img> tag with no alt text"
                fi
            fi
            if attr "$m" "$RE_IMG_SRC"; then
                trim "$ATTR"
                t="$TRIMMED"
                if [[ "$t" != '#'* && ! "$t" =~ $RE_SCHEME ]]; then
                    LNK_LINE+=("$n")
                    LNK_TGT+=("$t")
                fi
            fi
        done

        if [[ "$line" =~ $RE_ALERT ]]; then
            AL_LINE+=("$n")
            if [[ -n "${BASH_REMATCH[1]}" ]]; then AL_INDENT+=(1); else AL_INDENT+=(0); fi
            if (( depth > 0 )); then AL_DETAILS+=(1); else AL_DETAILS+=(0); fi
            AL_PREV+=("$last_alert")
            last_alert="$n"
        elif [[ ! "$line" =~ $RE_BLANK_OR_QUOTE ]]; then
            last_alert=0
        fi

        rest="$scan"
        while [[ "$rest" == *'<details'* ]]; do
            depth=$(( depth + 1 ))
            rest="${rest#*<details}"
        done
        rest="$scan"
        while [[ "$rest" == *'</details>'* ]]; do
            if (( depth > 0 )); then
                depth=$(( depth - 1 ))
            fi
            rest="${rest#*</details>}"
        done
    done < "$file"
}

# slugs: SLUGS = the anchor of every heading in $WORK/heads, in order (rules in the header).
slugs() {
    local s n j
    local -a bases=()
    SLUGS=()
    sed -e 's/\[\([^]]*\)]([^)]*)/\1/g' -e 's/`//g' "$WORK/heads" | tr 'A-Z' 'a-z' \
        | sed -e 's/[^a-z0-9 _-]//g' -e 's/ /-/g' > "$WORK/slugs"
    while IFS= read -r s || [[ -n "$s" ]]; do
        n=0
        j=0
        while (( j < ${#bases[@]} )); do
            if [[ "${bases[j]}" == "$s" ]]; then
                n=$(( n + 1 ))
            fi
            j=$(( j + 1 ))
        done
        bases+=("$s")
        if (( n == 0 )); then SLUGS+=("$s"); else SLUGS+=("$s-$n"); fi
    done < "$WORK/slugs"
}

# lint <file> <index>: runs every rule on <file>, in order; the first finding exits.
lint() {
    local file="$1" i j t path full dir=. max=0 m rc out l gdir base="${1##*/}"
    parse "$file"
    slugs

    i=0
    while (( i < ${#ANC_TGT[@]} )); do
        t="${ANC_TGT[i]}"
        j=0
        while (( j < ${#SLUGS[@]} )); do
            [[ "${SLUGS[j]}" != "$t" ]] || break
            j=$(( j + 1 ))
        done
        if (( j == ${#SLUGS[@]} )); then
            if [[ "$t" == *[!a-z0-9_-]* ]]; then
                finding "$file" "${ANC_LINE[i]}" anchor_punctuation \
                    "#$t has a character outside [a-z0-9_-]; heading anchors drop punctuation"
            fi
            finding "$file" "${ANC_LINE[i]}" anchor_missing \
                "#$t is the anchor of no heading in this file"
        fi
        i=$(( i + 1 ))
    done

    if [[ "$file" == */* ]]; then
        dir="${file%/*}"
    fi
    i=0
    while (( i < ${#LNK_TGT[@]} )); do
        t="${LNK_TGT[i]}"
        path="${t%%#*}"
        path="${path%%\?*}"
        if [[ -z "$path" ]]; then
            finding "$file" "${LNK_LINE[i]}" link_missing "empty link target '$t'"
        fi
        if [[ "$path" == /* ]]; then full="$ROOT$path"; else full="$dir/$path"; fi
        if [[ ! -e "$full" ]]; then
            finding "$file" "${LNK_LINE[i]}" link_missing "$t: no such file ($full)"
        fi
        i=$(( i + 1 ))
    done

    if [[ -n "$ALT_LINE" ]]; then
        finding "$file" "$ALT_LINE" alt_missing "$ALT_DETAIL"
    fi

    if [[ "$base" == README.md ]]; then
        max=2
    fi
    if (( ${#AL_LINE[@]} > max )); then
        if (( max > 0 )); then
            finding "$file" "${AL_LINE[max]}" alert_count \
                "alert $(( max + 1 )) in a README.md, at most $max allowed"
        fi
        finding "$file" "${AL_LINE[0]}" alert_count \
            "GitHub alert in a file not named README.md, none allowed"
    fi
    i=0
    while (( i < ${#AL_LINE[@]} )); do
        if (( AL_DETAILS[i] )); then
            finding "$file" "${AL_LINE[i]}" alert_inside_details \
                "GitHub alert inside a <details> block"
        fi
        i=$(( i + 1 ))
    done
    i=0
    while (( i < ${#AL_LINE[@]} )); do
        if (( AL_INDENT[i] )); then
            finding "$file" "${AL_LINE[i]}" alert_in_list \
                "indented GitHub alert (inside a list item); start it at column 1"
        fi
        i=$(( i + 1 ))
    done
    i=0
    while (( i < ${#AL_LINE[@]} )); do
        if (( AL_PREV[i] )); then
            finding "$file" "${AL_LINE[i]}" alert_adjacent \
                "GitHub alert after the alert on line ${AL_PREV[i]} with no paragraph between them"
        fi
        i=$(( i + 1 ))
    done

    rc=0
    m="$(grep -n -a -F -m 1 -e "$EM" -- "$file")" || rc=$?
    if (( rc > 1 )); then
        fail "grep failed (status $rc) on $file"
    fi
    if (( rc == 0 )); then
        finding "$file" "${m%%:*}" em_dash "em dash (U+2014); use a comma, a colon or parentheses"
    fi

    # The gate checks a tree, so give it a directory holding only a copy of this file.
    gdir="$WORK/gate.$2"
    mkdir "$gdir"
    cp -- "$file" "$gdir/$base"
    rc=0
    out="$("$GATE" "$DENYLIST" "$gdir" 2> "$WORK/gate.err")" || rc=$?
    if (( rc == 1 )); then
        # check-public.sh prints "<name>:<line>: <match>" per hit (<line> is "name" for a hit in
        # the file name, and a key hit shows only the key prefix).
        while IFS= read -r l; do
            l="${l#"$base":}"
            printf '%s:%s: gate_hit: %s\n' "$file" "${l%%: *}" "${l#*: }"
        done <<< "$out"
        echo "readme-lint.sh: FAIL: gate_hit in $file (check-public.sh with $DENYLIST)" >&2
        exit 1
    fi
    if (( rc != 0 )); then
        cat "$WORK/gate.err" >&2
        fail "check-public.sh failed (status $rc) on $file"
    fi
}

k=0
for f in "$@"; do
    k=$(( k + 1 ))
    lint "$f" "$k"
done
echo "readme-lint.sh: clean: $# file(s)"
