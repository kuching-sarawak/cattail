#!/usr/bin/env bash
# Live end-to-end check of the specs exchange: a dev server and a dev client at the same version;
# the client joins at once (-PquickPlay). Passes when the server log shows the client's specs line
# (prefix CATTAILSPECS, the generic jar's default) logged on the server thread, ending in
# "|ip 127.0.0.1", BEFORE its "joined the game" line (the configuration-phase exchange), and the
# client log says it answered in the configuration phase. Needs a display (the client opens a
# window) and the dev server's run/server/server.properties with online-mode=false (the dev client
# has no Mojang session). Stops both afterwards.
#
#   scripts/e2e-specs.sh [extra gradle args...]     e.g. -PskipHelper
set -Eeuo pipefail
cd "$(dirname "$0")/.."
source scripts/env.sh
TIMEOUT_S="${E2E_TIMEOUT_S:-600}"
SLOG="$(mktemp -t cattail-e2e-specs-server.XXXXXX.log)"
CLOG="$(mktemp -t cattail-e2e-specs-client.XXXXXX.log)"
SERVER_PID=""
CLIENT_PID=""
kill_tree() {
    local child
    for child in $(pgrep -P "$1" 2>/dev/null); do
        kill_tree "$child"
    done
    kill -TERM "$1" 2>/dev/null || true
}
cleanup() {
    [[ -n "$CLIENT_PID" ]] && kill_tree "$CLIENT_PID"
    [[ -n "$SERVER_PID" ]] && kill_tree "$SERVER_PID"
    return 0
}
trap cleanup EXIT
mkdir -p mod/run/server
echo "eula=true" > mod/run/server/eula.txt
if ! grep -qx 'online-mode=false' mod/run/server/server.properties 2>/dev/null; then
    echo "e2e-specs: FAIL: mod/run/server/server.properties needs online-mode=false (the dev client has no session)" >&2
    exit 1
fi
echo "==> server"
(cd mod && ./gradlew --no-daemon runServer "$@") >"$SLOG" 2>&1 &
SERVER_PID=$!
for (( i = 0; i < TIMEOUT_S; i++ )); do
    grep -q "Done (" "$SLOG" && break
    if ! kill -0 "$SERVER_PID" 2>/dev/null; then echo "e2e-specs: FAIL: server exited early (log $SLOG)" >&2; tail -n 30 "$SLOG" >&2; exit 1; fi
    sleep 1
done
grep -q "Done (" "$SLOG" || { echo "e2e-specs: FAIL: server not ready after ${TIMEOUT_S}s (log $SLOG)" >&2; exit 1; }
echo "==> client, joining 127.0.0.1:25565"
(cd mod && ./gradlew --no-daemon runClient -PquickPlay=127.0.0.1:25565 "$@") >"$CLOG" 2>&1 &
CLIENT_PID=$!
result=""
for (( i = 0; i < TIMEOUT_S; i++ )); do
    if grep -qE "errors found|Crash report saved" "$CLOG"; then result="client loading errors"; break; fi
    if grep -q "joined the game" "$SLOG"; then result="joined"; break; fi
    if ! kill -0 "$CLIENT_PID" 2>/dev/null; then result="client exited early"; break; fi
    sleep 1
done
[[ "$result" == "joined" ]] || { echo "e2e-specs: FAIL: ${result:-no join after ${TIMEOUT_S}s} (client $CLOG, server $SLOG)" >&2; exit 1; }
sleep 3 # a play-phase line, if that is what came, lands within a second of the join
specs_line="$(grep -n 'CATTAILSPECS|' "$SLOG" | head -n 1)"
join_line="$(grep -n 'joined the game' "$SLOG" | head -n 1)"
fail() { echo "e2e-specs: FAIL: $1 (client $CLOG, server $SLOG)" >&2; exit 1; }
[[ -n "$specs_line" ]] || fail "no specs line on the server"
[[ "$specs_line" == *"[Server thread/INFO]"* ]] || fail "specs line not logged on the server thread: ${specs_line#*:}"
[[ "$specs_line" == *"SpecsNetworking/]: CATTAILSPECS|"* ]] || fail "specs line not from SpecsNetworking: ${specs_line#*:}"
[[ "$specs_line" == *"|ip 127.0.0.1" ]] || fail "specs line does not end in |ip 127.0.0.1: ${specs_line#*:}"
(( ${specs_line%%:*} < ${join_line%%:*} )) || fail "specs line came after the join (play phase), not before it"
grep -q "Cattail sent PC specs to the server (configuration phase)" "$CLOG" || fail "client did not answer in the configuration phase"
(( $(grep -c 'CATTAILSPECS|' "$SLOG") == 1 )) || fail "more than one specs line for one login"
echo "e2e-specs: PASS: specs logged before the join, on the server thread, with the address (client $CLOG, server $SLOG)"
