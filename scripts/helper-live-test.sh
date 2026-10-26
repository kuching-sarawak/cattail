#!/usr/bin/env bash
# Live test of the helper binary from this machine: builds the Linux helper,
# joins the tailnet as an ephemeral node with the tag from the deployment file,
# pings the Minecraft server at CATTAIL_TEST_TARGET (ip:port, granted to that
# tag by the tailnet policy) through the loopback port, sends QUIT and expects
# exit 0 within 5 s. Safe to re-run; the helper is killed on any failure.
# Opt-in: prints "skipped: CATTAIL_TEST_TARGET not set" and exits 0 when unset.
#
# Needs .secrets/deployment.properties (authkey and tag). The key is only ever
# passed through the environment; it is never printed or put in argv.
set -Eeuo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
HOSTNAME_ARG="mc-claude-live"

# Opt-in: without a live target there is nothing to test.
TARGET="${CATTAIL_TEST_TARGET:-}"
if [[ -z "$TARGET" ]]; then
	echo "skipped: CATTAIL_TEST_TARGET not set"
	exit 0
fi
[[ "$TARGET" =~ ^[^:]+:[0-9]+$ ]] || { echo "FAIL: CATTAIL_TEST_TARGET must be <ip:port>" >&2; exit 2; }

# authkey and tag from the owner's deployment file (see deployment.properties.example), read
# as text with sed, never sourced. The key only ever reaches the helper through TS_AUTHKEY.
DEPLOY_FILE="$ROOT/.secrets/deployment.properties"
[[ -f "$DEPLOY_FILE" && -r "$DEPLOY_FILE" ]] || { echo "FAIL: $DEPLOY_FILE not found; copy deployment.properties.example there and edit it" >&2; exit 1; }
deploy_authkey() { sed -n 's/^authkey=//p' "$DEPLOY_FILE" | tr -d '[:space:]'; }
TAG="$(sed -n 's/^tag=//p' "$DEPLOY_FILE" | tr -d '[:space:]')"
[[ "$TAG" =~ ^tag:[a-z0-9-]+$ ]] || { echo "FAIL: $DEPLOY_FILE: tag must match ^tag:[a-z0-9-]+\$" >&2; exit 1; }

# shellcheck source=env.sh
source "$ROOT/scripts/env.sh"

work="$(mktemp -d)"
helper_pid=""
cleanup() {
	local rc=$?
	if [[ -n "$helper_pid" ]] && kill -0 "$helper_pid" 2>/dev/null; then
		kill "$helper_pid" 2>/dev/null || true
		sleep 1
		kill -9 "$helper_pid" 2>/dev/null || true
	fi
	exec 3>&- || true
	exec 4<&- || true
	rm -rf "$work"
	if ((rc != 0)); then echo "FAIL (exit $rc)" >&2; fi
	exit "$rc"
}
trap cleanup EXIT
fail() { echo "FAIL: $*" >&2; exit 1; }

bin="$work/cattail-helper"
(cd "$ROOT/helper" && CGO_ENABLED=0 go build -o "$bin" ./cmd/cattail-helper)

# An OAuth client secret gets the node options, as the mod does; an auth key is used as is.
TS_AUTHKEY="$(deploy_authkey)"
[[ -n "$TS_AUTHKEY" ]] || fail "$DEPLOY_FILE has no authkey= value"
if [[ "$TS_AUTHKEY" == tskey-client-* && "$TS_AUTHKEY" != *\?* ]]; then
	TS_AUTHKEY="${TS_AUTHKEY}?ephemeral=true&preauthorized=true"
fi
export TS_AUTHKEY

mkfifo "$work/in" "$work/out"
start=$(date +%s.%N)
"$bin" --target "$TARGET" --tag "$TAG" --hostname "$HOSTNAME_ARG" --log-dir "$work" \
	<"$work/in" >"$work/out" 2>"$work/stderr" &
helper_pid=$!
unset TS_AUTHKEY
exec 3>"$work/in" # helper's stdin stays open until we close or QUIT
exec 4<"$work/out"

line=""
read -r -t 25 -u 4 line || fail "no READY line within 25 s (helper log: $work/cattail/helper.log)"
ready=$(date +%s.%N)
[[ "$line" =~ ^READY\ ([0-9]+)\ (100\.[0-9.]+)$ ]] || fail "unexpected first line: $line"
port="${BASH_REMATCH[1]}"
n=$(printf '%.1f' "$(echo "$ready - $start" | bc)")
echo "READY on 127.0.0.1:$port as ${BASH_REMATCH[2]} after $n s"
awk -v n="$n" 'BEGIN { exit !(n <= 20) }' || fail "READY took $n s (> 20 s)"

python3 -I "$ROOT/scripts/mcping.py" 127.0.0.1 "$port" "${TARGET%:*}" >"$work/ping.json" ||
	fail "status ping through the helper failed"
grep -q '"players"' "$work/ping.json" || fail "status JSON has no \"players\""

echo QUIT >&3
quit_at=$(date +%s.%N)
for _ in $(seq 50); do
	kill -0 "$helper_pid" 2>/dev/null || break
	sleep 0.1
done
if kill -0 "$helper_pid" 2>/dev/null; then fail "helper still running 5 s after QUIT"; fi
set +e
wait "$helper_pid"
code=$?
set -e
helper_pid=""
el=$(printf '%.1f' "$(echo "$(date +%s.%N) - $quit_at" | bc)")
((code == 0)) || fail "helper exited $code after QUIT"

# stdout must hold nothing but protocol lines after READY; stderr nothing at all.
extra="$(cat <&4 || true)"
if [[ -n "$extra" ]] && grep -qvE '^(WARN|ERR) ' <<<"$extra"; then fail "non-protocol stdout: $extra"; fi
[[ ! -s "$work/stderr" ]] || fail "helper wrote to stderr ($(wc -c <"$work/stderr") bytes)"
# The key must not appear in the helper log.
log="$work/cattail/helper.log"
[[ -s "$log" ]] || fail "helper.log is empty"
if grep -aqF -f <(deploy_authkey; echo) "$log"; then fail "key found in helper.log"; fi

echo "exit 0 in $el s after QUIT; helper.log $(wc -c <"$log") bytes"
echo "OK ready in $n s, ping ok, exit 0"
