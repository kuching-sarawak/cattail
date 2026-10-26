#!/usr/bin/env bash
# Live UDP (voice) relay smoke test of the helper from this machine: builds the
# Linux helper, joins the tailnet as an ephemeral node with the tag from the
# deployment file and --voice 25566, sends 5 datagrams (four ~1 KB, one 60 KB) to
# 127.0.0.1:25566 and expects each to come back byte-identical within 2 s from
# a UDP echo on the host of CATTAIL_TEST_TARGET (ip:port), then QUIT -> exit 0.
# Opt-in: prints "skipped: CATTAIL_TEST_TARGET not set" and exits 0 when unset.
#
# Needs: a UDP echo listening on udp:25566 on the target host and a tailnet
# grant <tag> -> <target host> udp:25566.
# Needs .secrets/deployment.properties (authkey and tag); the key only ever
# travels in the environment, never in argv or output.
set -Eeuo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VOICE=25566
HOSTNAME_ARG="mc-claude-udp"

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

if ss -Huln "sport = :$VOICE" | grep -q .; then fail "udp port $VOICE is already in use here"; fi

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
"$bin" --target "$TARGET" --tag "$TAG" --voice "$VOICE" --hostname "$HOSTNAME_ARG" --log-dir "$work" \
	<"$work/in" >"$work/out" 2>"$work/stderr" &
helper_pid=$!
unset TS_AUTHKEY
exec 3>"$work/in"
exec 4<"$work/out"

line=""
read -r -t 25 -u 4 line || fail "no READY line within 25 s"
[[ "$line" =~ ^READY\ ([0-9]+)\ (100\.[0-9.]+)$ ]] || fail "unexpected first line: $line"
echo "READY as ${BASH_REMATCH[2]} (game loopback port ${BASH_REMATCH[1]}, voice 127.0.0.1:$VOICE)"

python3 -I - "$VOICE" <<'PY' || fail "UDP echo check failed"
import os, socket, sys, time
port = int(sys.argv[1])
sizes = [1000, 1024, 1100, 61440, 900]  # one 60 KB datagram
s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
s.bind(("127.0.0.1", 0))
ok = 0
for i, n in enumerate(sizes):
    prefix = b"cattail-udp-%d|" % i
    payload = prefix + os.urandom(n - len(prefix))
    t0 = time.monotonic()
    s.sendto(payload, ("127.0.0.1", port))
    s.settimeout(2.0)
    try:
        while True:
            data, src = s.recvfrom(70000)
            if src == ("127.0.0.1", port):
                break
    except socket.timeout:
        print(f"datagram {i} ({n} B): no echo within 2 s", file=sys.stderr)
        sys.exit(1)
    ms = (time.monotonic() - t0) * 1000
    if data != payload:
        print(f"datagram {i} ({n} B): echo differs ({len(data)} B back)", file=sys.stderr)
        sys.exit(1)
    print(f"datagram {i}: {n} B echoed identically in {ms:.0f} ms")
    ok += 1
print(f"{ok}/{len(sizes)} echoed")
PY

echo QUIT >&3
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
((code == 0)) || fail "helper exited $code after QUIT"

extra="$(cat <&4 || true)"
[[ -z "$extra" ]] || fail "unexpected stdout after READY (e.g. a voice WARN): $extra"
[[ ! -s "$work/stderr" ]] || fail "helper wrote to stderr ($(wc -c <"$work/stderr") bytes)"
if grep -aqF -f <(deploy_authkey; echo) "$work/cattail/helper.log"; then fail "key found in helper.log"; fi

echo "OK udp 5/5 echoed, exit 0"
