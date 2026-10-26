#!/usr/bin/env bash
# Live end-to-end check of the update notice: a dev server stamped with a newer Cattail version
# (SERVER_VERSION, default 9.9.9, via -Pmod_version) and a dev client at the real version that
# joins it at once (-PquickPlay). Passes when the client log shows the notice line
# "Cattail update: the server runs <server version>, this jar is <client version>" and the
# server log shows the client's specs line with that client version. Needs a display (the client
# opens a window) and the dev server's run/server/server.properties with online-mode=false (the
# dev client has no Mojang session). Stops both afterwards.
#
#   scripts/e2e-update-notice.sh [extra gradle args...]     e.g. -PskipHelper
set -Eeuo pipefail
cd "$(dirname "$0")/.."
source scripts/env.sh
SERVER_VERSION="${SERVER_VERSION:-9.9.9}"
TIMEOUT_S="${E2E_TIMEOUT_S:-600}"
CLIENT_VERSION="$(sed -n 's/^mod_version=\([^[:space:]]*\).*$/\1/p' mod/gradle.properties)"
SLOG="$(mktemp -t cattail-e2e-server.XXXXXX.log)"
CLOG="$(mktemp -t cattail-e2e-client.XXXXXX.log)"
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
    echo "e2e-update-notice: FAIL: mod/run/server/server.properties needs online-mode=false (the dev client has no session)" >&2
    exit 1
fi
echo "==> server as Cattail $SERVER_VERSION"
(cd mod && ./gradlew --no-daemon runServer -Pmod_version="$SERVER_VERSION" "$@") >"$SLOG" 2>&1 &
SERVER_PID=$!
for (( i = 0; i < TIMEOUT_S; i++ )); do
    grep -q "Done (" "$SLOG" && break
    if ! kill -0 "$SERVER_PID" 2>/dev/null; then echo "e2e-update-notice: FAIL: server exited early (log $SLOG)" >&2; tail -n 30 "$SLOG" >&2; exit 1; fi
    sleep 1
done
grep -q "Cattail ${SERVER_VERSION} loaded" "$SLOG" || { echo "e2e-update-notice: FAIL: server did not report Cattail $SERVER_VERSION (log $SLOG)" >&2; exit 1; }
echo "==> client as Cattail $CLIENT_VERSION, joining 127.0.0.1:25565"
(cd mod && ./gradlew --no-daemon runClient -PquickPlay=127.0.0.1:25565 "$@") >"$CLOG" 2>&1 &
CLIENT_PID=$!
NOTICE="Cattail update: the server runs ${SERVER_VERSION}, this jar is ${CLIENT_VERSION}"
result=""
for (( i = 0; i < TIMEOUT_S; i++ )); do
    if grep -qE "errors found|Crash report saved" "$CLOG"; then result="client loading errors"; break; fi
    if grep -qF "$NOTICE" "$CLOG"; then result="ok"; break; fi
    if ! kill -0 "$CLIENT_PID" 2>/dev/null; then result="client exited early"; break; fi
    sleep 1
done
[[ -n "$result" ]] || result="timeout after ${TIMEOUT_S}s"
sleep 3 # the specs line follows the login on the server
grep -E "Cattail update|Cattail .* loaded|logged in" "$CLOG" "$SLOG" | head -n 10 || true
if [[ "$result" == "ok" ]]; then
    if grep -q "CATTAILSPECS" "$SLOG" && grep -qF "cattail ${CLIENT_VERSION}" "$SLOG"; then
        echo "e2e-update-notice: PASS (client $CLOG, server $SLOG)"
        exit 0
    fi
    result="the server log has no specs line naming cattail ${CLIENT_VERSION}"
fi
echo "e2e-update-notice: FAIL: $result (client $CLOG, server $SLOG)" >&2
tail -n 30 "$CLOG" >&2
exit 1
