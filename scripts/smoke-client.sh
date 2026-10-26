#!/usr/bin/env bash
# Headless client smoke test: start the dev client (runClient) under Xvfb with software GL and
# pass when its log shows "Cattail config applied" (the mod loaded and its client config was
# applied), a line naming the bundled Plasmo Voice ("plasmovoice" or "plasmo-voice", any case),
# the client got as far as the title screen's resources (GUI atlas created), and, after a short
# grace period, still no mod-loading errors ("errors found") or crash report. Stops the client
# afterwards.
#
#   scripts/smoke-client.sh [--novoice] [extra gradle args...]     e.g. -PskipHelper
#
# --novoice runs the client without Plasmo Voice on the classpath (-PnoVoice), as a game running
# the no-voice jar does, and then passes only when the log names no Plasmo Voice at all.
#
# NeoForge is pinned to 21.1.251, a different 21.1.x than neo_version in mod/gradle.properties
# (used for the jar), to catch drift between NeoForge versions.
set -Eeuo pipefail
cd "$(dirname "$0")/.."
source scripts/env.sh

NOVOICE=0
if [[ "${1:-}" == --novoice ]]; then
    NOVOICE=1
    shift
    set -- -PnoVoice "$@"
fi
TIMEOUT_S="${SMOKE_TIMEOUT_S:-600}"
LOG="$(mktemp -t cattail-smoke-client.XXXXXX.log)"
XVFB_PID=""
GRADLE_PID=""

# Kills a process and all its descendants (the game JVM is a grandchild of gradle).
kill_tree() {
    local child
    # pgrep -P: ps --ppid is GNU-only (on macOS it fails, which would orphan the game JVM).
    for child in $(pgrep -P "$1" 2>/dev/null); do
        kill_tree "$child"
    done
    kill -TERM "$1" 2>/dev/null || true
}

cleanup() {
    if [[ -n "$GRADLE_PID" ]]; then
        kill_tree "$GRADLE_PID"
    fi
    if [[ -n "$XVFB_PID" ]]; then
        kill -TERM "$XVFB_PID" 2>/dev/null || true
    fi
}
trap cleanup EXIT

DISPLAY_N=$(( 90 + RANDOM % 9 ))
DISPLAY_NUM=":$DISPLAY_N"
if [[ -e "/tmp/.X11-unix/X$DISPLAY_N" || -e "/tmp/.X$DISPLAY_N-lock" ]]; then
    echo "smoke-client: FAIL: display $DISPLAY_NUM is already in use; re-run" >&2
    exit 1
fi
Xvfb "$DISPLAY_NUM" -screen 0 1280x720x24 >/dev/null 2>&1 &
XVFB_PID=$!
for (( i = 0; i < 20; i++ )); do
    [[ -S "/tmp/.X11-unix/X$DISPLAY_N" ]] && break
    kill -0 "$XVFB_PID" 2>/dev/null || break
    sleep 0.5
done
if ! kill -0 "$XVFB_PID" 2>/dev/null || [[ ! -S "/tmp/.X11-unix/X$DISPLAY_N" ]]; then
    echo "smoke-client: FAIL: Xvfb did not start on $DISPLAY_NUM" >&2
    exit 1
fi

(cd mod && DISPLAY="$DISPLAY_NUM" LIBGL_ALWAYS_SOFTWARE=1 \
    ./gradlew --no-daemon runClient -Pneo_version=21.1.251 "$@") >"$LOG" 2>&1 &
GRADLE_PID=$!

FAILED_RE="errors found|Crash report saved|Reported exception"
TITLE_RE="Created: .*minecraft:textures/atlas/gui.png-atlas"
PLASMO_RE="plasmo-?voice" # NeoForge logs the nested Plasmo Voice mod (case-insensitive match)
# NeoForge's dependency table names the absent optional dependency too, as
# "plasmovoice (version ... -> MISSING)", which is not a load.
plasmo_loaded() { grep -iE "$PLASMO_RE" "$LOG" | grep -v -- '-> MISSING' | grep -q .; }
GRACE_S=10
result=""
for (( i = 0; i < TIMEOUT_S; i++ )); do
    if grep -qE "$FAILED_RE" "$LOG"; then result="loading errors"; break; fi
    if grep -q "Cattail config applied" "$LOG" && { (( NOVOICE )) || plasmo_loaded; } \
        && grep -qE "$TITLE_RE" "$LOG"; then
        sleep "$GRACE_S" # errors reported right after the resource reload still count
        if grep -qE "$FAILED_RE" "$LOG"; then result="loading errors"
        elif ! kill -0 "$GRADLE_PID" 2>/dev/null; then result="client exited"
        elif (( NOVOICE )) && plasmo_loaded; then result="Plasmo Voice loaded in a --novoice run"
        else result="ok"; fi
        break
    fi
    if ! kill -0 "$GRADLE_PID" 2>/dev/null; then result="client exited early"; break; fi
    sleep 1
done
[[ -n "$result" ]] || result="timeout after ${TIMEOUT_S}s"

grep -E "Cattail|errors found|gui.png-atlas|plasmo" "$LOG" | head -n 20 || true
if [[ "$result" == "ok" ]]; then
    echo "smoke-client: PASS$( (( NOVOICE )) && echo ' (no Plasmo Voice)') (log $LOG)"
    exit 0
fi
echo "smoke-client: FAIL: $result (log $LOG)" >&2
tail -n 40 "$LOG" >&2
exit 1
