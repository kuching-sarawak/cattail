#!/bin/sh
# Stand-in for cattail-helper.exe in unit tests (Linux only). Speaks the same
# stdout protocol: READY first, then WARN/ERR lines; exits on QUIT or EOF.
#   FAKE_DELAY_MS  sleep this many milliseconds before anything else
#   FAKE_MODE=err  print "ERR auth bad key" and exit 3
#   FAKE_MODE=die  print READY, exit 1 after 200 ms
#   FAKE_MODE=bad  print a malformed READY line, then wait for stdin
#   FAKE_MODE=probe       print three PROBE verdict lines and exit 0 (no READY, stdin ignored)
#   FAKE_MODE=probe-hang  print one PROBE line, then never exit
if [ -n "${FAKE_DELAY_MS:-}" ]; then
    sleep "$(awk "BEGIN { print ${FAKE_DELAY_MS} / 1000 }")"
fi
echo "fake-helper stderr line" >&2
case "${FAKE_MODE:-}" in
    err)
        echo "ERR auth bad key"
        exit 3
        ;;
    die)
        echo "READY 51234 100.1.2.3"
        sleep 0.2
        exit 1
        ;;
    bad)
        echo "READY port 100.1.2.3"
        ;;
    probe)
        echo "fake probe args: $*"
        echo "PROBE 100.101.102.103:22 denied"
        echo "PROBE 100.1.2.3:22 denied"
        echo "PROBE 100.101.102.103:25565 allowed"
        exit 0
        ;;
    probe-hang)
        echo "PROBE 100.101.102.103:22 denied"
        while :; do sleep 1; done
        ;;
    *)
        echo "READY 51234 100.1.2.3"
        echo "WARN voice-failed "
        ;;
esac
while IFS= read -r line; do
    if [ "$line" = "QUIT" ]; then
        exit 0
    fi
done
exit 0
