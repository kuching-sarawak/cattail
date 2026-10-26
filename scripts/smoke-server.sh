#!/usr/bin/env bash
# Dedicated-server smoke test of the universal jar: start runServer (gameDirectory mod/run/server,
# eula=true written here) and pass when the log shows "Cattail <version> loaded", the server
# finished starting ("Done ("), the bundled Plasmo Voice started its UDP server ("UDP server is
# started on"), Cattail's voice config check ran clean ("Cattail voice: Plasmo Voice config checked,
# no problems"), no Cattail voice WARN line was logged, and there were no mod-loading errors. Stops
# the server afterwards.
#
#   scripts/smoke-server.sh [--novoice] [extra gradle args...]     e.g. -PskipHelper, in any order
#
# --novoice runs the server without Plasmo Voice on the classpath (-PnoVoice), as a server
# running the no-voice jar does, and then passes when the log shows "Cattail voice: Plasmo Voice
# is not loaded" instead of the UDP and "no problems" lines, no Cattail voice WARN line and no
# Plasmo Voice at all.
#
# The server binds 25565 (TCP for the game, UDP for voice) on this machine only for the duration
# of the test. NeoForge is pinned to 21.1.251, a different 21.1.x than neo_version in
# mod/gradle.properties, to catch version drift.
set -Eeuo pipefail
cd "$(dirname "$0")/.."
source scripts/env.sh

# --novoice may come anywhere among the arguments; it is never passed on to Gradle.
NOVOICE=0
for arg in "$@"; do
    shift
    if [[ "$arg" == --novoice ]]; then
        NOVOICE=1
    else
        set -- "$@" "$arg"
    fi
done
if (( NOVOICE )); then
    set -- -PnoVoice "$@"
fi
TIMEOUT_S="${SMOKE_TIMEOUT_S:-600}"
VERSION="$(sed -n 's/^mod_version=\([^[:space:]]*\).*$/\1/p' mod/gradle.properties)"
LOG="$(mktemp -t cattail-smoke-server.XXXXXX.log)"
GRADLE_PID=""

kill_tree() {
    local child
    # pgrep -P: ps --ppid is GNU-only (on macOS it fails, which would orphan the game JVM).
    for child in $(pgrep -P "$1" 2>/dev/null); do
        kill_tree "$child"
    done
    kill -TERM "$1" 2>/dev/null || true
}
trap '[[ -n "$GRADLE_PID" ]] && kill_tree "$GRADLE_PID"' EXIT

mkdir -p mod/run/server
echo "eula=true" > mod/run/server/eula.txt
(cd mod && ./gradlew --no-daemon runServer -Pneo_version=21.1.251 "$@") >"$LOG" 2>&1 &
GRADLE_PID=$!

VOICE_UDP="UDP server is started on"
VOICE_OK="Cattail voice: Plasmo Voice config checked, no problems"
VOICE_WARN_RE="WARN.*Cattail voice:"
VOICE_NOT_LOADED="Cattail voice: Plasmo Voice is not loaded"
PLASMO_RE="plasmo-?voice"
# A loaded Plasmo Voice names itself in the log; NeoForge's dependency table names the absent
# optional dependency too, as "plasmovoice (version ... -> MISSING)", which is not a load.
plasmo_loaded() { grep -iE "$PLASMO_RE" "$LOG" | grep -v -- '-> MISSING' | grep -q .; }
VOICE_GRACE_S=30
done_at=""
result=""
for (( i = 0; i < TIMEOUT_S; i++ )); do
    if grep -qE "errors found|Failed to start the minecraft server|Crash report saved" "$LOG"; then result="loading errors"; break; fi
    if grep -q "Cattail ${VERSION} loaded" "$LOG" && grep -q "Done (" "$LOG"; then
        # Plasmo starts its UDP server and Cattail checks Plasmo's config on the first server tick
        # after "Done (", so those lines follow it; give them VOICE_GRACE_S seconds.
        [[ -n "$done_at" ]] || done_at=$i
        if grep -qE "$VOICE_WARN_RE" "$LOG"; then result="Cattail voice warnings"; break; fi
        if (( NOVOICE )); then
            if grep -qF "$VOICE_NOT_LOADED" "$LOG"; then
                if plasmo_loaded || grep -qF "$VOICE_UDP" "$LOG"; then
                    result="Plasmo Voice loaded in a --novoice run"
                else
                    result="ok"
                fi
                break
            fi
            if (( i - done_at >= VOICE_GRACE_S )); then
                result="no \"$VOICE_NOT_LOADED\" within ${VOICE_GRACE_S}s of \"Done (\""
                break
            fi
        else
            if grep -qF "$VOICE_UDP" "$LOG" && grep -qF "$VOICE_OK" "$LOG"; then result="ok"; break; fi
            if (( i - done_at >= VOICE_GRACE_S )); then
                result="no \"$VOICE_UDP\" and \"$VOICE_OK\" within ${VOICE_GRACE_S}s of \"Done (\""
                break
            fi
        fi
    fi
    if ! kill -0 "$GRADLE_PID" 2>/dev/null; then result="server exited early"; break; fi
    sleep 1
done
[[ -n "$result" ]] || result="timeout after ${TIMEOUT_S}s"

grep -E "Cattail|errors found|Done \(|UDP server is started|Cattail voice" "$LOG" | head -n 20 || true
if [[ "$result" == "ok" ]]; then
    echo "smoke-server: PASS$( (( NOVOICE )) && echo ' (no Plasmo Voice)') (log $LOG)"
    exit 0
fi
echo "smoke-server: FAIL: $result (log $LOG)" >&2
tail -n 40 "$LOG" >&2
exit 1
