#!/usr/bin/env bash
# Live end-to-end check of the server-list info: the dev server, started with a written
# config/cattail-server.toml, answers a status ping with the cattail object (v is mod_version, the
# lines sanitised, an &a code arriving as §a); then three edits of the file, each seen on a later ping
# within RELOAD_S seconds with no restart: new lines, lines = [] (the object with no lines) and
# showIcon = false (no cattail object). No client and no display. Needs the dev server's port free (it
# pings 127.0.0.1 there). Puts back the dev server's own cattail-server.toml (or its absence) and
# stops the server afterwards.
#
# CATTAIL_E2E_EXTRA_MODS=<dir>: every *.jar in that directory is copied into the dev server's mods
# folder for the run (and removed afterwards), to prove the object survives other mods that touch
# the status codec; Better Compatibility Checker replaces ServerStatus.CODEC outright from its
# own mixin, which once hid the object on a live server, and No Chat Reports adds its own field.
#
#   scripts/e2e-server-list.sh [extra gradle args...]     e.g. -PskipHelper
#   CATTAIL_E2E_EXTRA_MODS=/path/to/jars scripts/e2e-server-list.sh -PskipHelper
set -Eeuo pipefail
cd "$(dirname "$0")/.."
source scripts/env.sh
TIMEOUT_S="${E2E_TIMEOUT_S:-600}"
RELOAD_S=20
CONF_DIR=mod/run/server/config
CONF="$CONF_DIR/cattail-server.toml"
STAGED="$CONF_DIR/.cattail-server.toml.e2e"
VERSION="$(sed -n 's/^mod_version=//p' mod/gradle.properties)"
PORT="$(sed -n 's/^server-port=//p' mod/run/server/server.properties 2>/dev/null)"
PORT="${PORT:-25565}"
SLOG="$(mktemp -t cattail-e2e-server-list.XXXXXX.log)"
BACKUP=""
HAD_CONF=0
SERVER_PID=""
EXTRA_MODS="${CATTAIL_E2E_EXTRA_MODS:-}"
COPIED_MODS=()
kill_tree() {
    local child
    for child in $(pgrep -P "$1" 2>/dev/null); do
        kill_tree "$child"
    done
    kill -TERM "$1" 2>/dev/null || true
}
cleanup() {
    [[ -n "$SERVER_PID" ]] && kill_tree "$SERVER_PID"
    rm -f "$STAGED"
    local m
    for m in ${COPIED_MODS[@]+"${COPIED_MODS[@]}"}; do
        rm -f "$m"
    done
    if (( HAD_CONF )); then
        cp -p "$BACKUP" "$CONF" && rm -f "$BACKUP"
    else
        rm -f "$CONF"
    fi
    return 0
}
trap cleanup EXIT
fail() { echo "e2e-server-list: FAIL: $1 (server $SLOG)" >&2; exit 1; }
# Replaces the file in one rename, so NeoForge's watcher never reads it half written (it backs up
# a file it cannot parse and recreates it with the defaults, and fills in the missing keys of an
# incomplete one). The layout and comments are what NeoForge writes for ServerListConfig, so the
# load needs no correction.
write_conf() { # $1 = showIcon, $2 = lines (TOML)
    printf '[serverList]\n\t#%s\n\tshowIcon = %s\n\t#%s\n\t#%s\n\tlines = %s\n' \
        "Show the Cattail icon with these lines on players' server lists." "$1" \
        "One tooltip line each, in order. & plus a Minecraft formatting code styles the rest of" \
        "the line (&a green, &7 grey, &l bold, &r reset). At most 6 lines of 64 characters." \
        "$2" >"$STAGED"
    mv -f "$STAGED" "$CONF"
}
ping_status() {
    python3 -I scripts/mcping.py 127.0.0.1 "$PORT" 2>/dev/null
}
# Prints the status's cattail object (or "absent"); succeeds when it is exactly
# {"v": mod_version, "lines": $2}, or when $2 is "absent" and the status has no cattail key.
check_cattail() {
    python3 -I -c '
import json, sys
status, version, want = json.loads(sys.argv[1]), sys.argv[2], sys.argv[3]
present = "cattail" in status
print(json.dumps(status["cattail"], ensure_ascii=False) if present else "absent")
ok = not present if want == "absent" else present and status["cattail"] == {"v": version, "lines": json.loads(want)}
sys.exit(0 if ok else 1)
' "$1" "$VERSION" "$2"
}
# Rewrites the file ($1 showIcon, $2 lines), then pings every 2 s until check_cattail $3 passes.
edit_and_expect() {
    local start=$SECONDS status seen=""
    write_conf "$1" "$2"
    while (( SECONDS - start < RELOAD_S )); do
        sleep 2
        status="$(ping_status)" || continue
        if seen="$(check_cattail "$status" "$3")"; then
            echo "    showIcon = $1, lines = $2: $seen (after $(( SECONDS - start ))s)"
            return 0
        fi
    done
    fail "${RELOAD_S}s after the edit to showIcon = $1, lines = $2 the cattail object is ${seen:-unknown}, want lines $3"
}
[[ -n "$VERSION" ]] || fail "no mod_version in mod/gradle.properties"
if (exec 3<>"/dev/tcp/127.0.0.1/$PORT") 2>/dev/null; then
    fail "something already listens on 127.0.0.1:$PORT; stop it first"
fi
mkdir -p "$CONF_DIR"
echo "eula=true" > mod/run/server/eula.txt
if [[ -f "$CONF" ]]; then
    BACKUP="$(mktemp -t cattail-e2e-server-list-toml.XXXXXX)"
    cp -p "$CONF" "$BACKUP"
    HAD_CONF=1
fi
write_conf true '["&aVoice chat on", "Restarts daily"]'
if [[ -n "$EXTRA_MODS" ]]; then
    [[ -d "$EXTRA_MODS" ]] || fail "CATTAIL_E2E_EXTRA_MODS is not a directory: $EXTRA_MODS"
    mkdir -p mod/run/server/mods
    for jar in "$EXTRA_MODS"/*.jar; do
        [[ -f "$jar" ]] || fail "no *.jar in $EXTRA_MODS"
        cp "$jar" "mod/run/server/mods/$(basename "$jar")"
        COPIED_MODS+=("mod/run/server/mods/$(basename "$jar")")
    done
    echo "==> extra mods: ${#COPIED_MODS[@]} jar(s) from $EXTRA_MODS"
fi
echo "==> server"
(cd mod && ./gradlew --no-daemon runServer "$@") >"$SLOG" 2>&1 &
SERVER_PID=$!
for (( i = 0; i < TIMEOUT_S; i++ )); do
    grep -q "Done (" "$SLOG" && break
    grep -q "FAILED TO BIND TO PORT" "$SLOG" && fail "the server could not bind its port $PORT"
    kill -0 "$SERVER_PID" 2>/dev/null || { tail -n 30 "$SLOG" >&2; fail "server exited early"; }
    sleep 1
done
grep -q "Done (" "$SLOG" || fail "server not ready after ${TIMEOUT_S}s"
echo "==> status ping at start"
status="$(ping_status)" || fail "status ping to 127.0.0.1:$PORT failed"
before="$(check_cattail "$status" '["§aVoice chat on", "Restarts daily"]')" \
    || fail "cattail object at start is ${before:-absent}, want {\"v\": \"$VERSION\", \"lines\": [\"§aVoice chat on\", \"Restarts daily\"]}"
echo "    $before"
echo "==> edits of the file, no restart"
edit_and_expect true '["Changed"]' '["Changed"]'
edit_and_expect true '[]' '[]'
edit_and_expect false '["Changed"]' absent
echo "e2e-server-list: PASS: cattail object at start and after each edit with no restart (server $SLOG)"
