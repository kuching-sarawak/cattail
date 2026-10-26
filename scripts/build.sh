#!/usr/bin/env bash
# Build the shippable Cattail jars: cross-compile the Windows helper, bundle it with its sha256
# into the mod resources, build the mod, copy the two generic jars to dist/: the full jar
# (cattail-<ver>+mc<mc>.jar, Plasmo Voice nested) and the no-voice jar (the same with the
# -novoice suffix and no nested Plasmo Voice). A generic jar carries no key, tag or other
# deployment value and is not secret; it needs no build.conf and nothing in .secrets/. With
# --deploy each generic jar's own inject command writes a copy that carries the deployment file:
# those copies hold the key, so they are owner-only and must never be shared.
#
#   scripts/build.sh [--deploy <file>] [--probe] [--skip-tests] [--allow-dirty]
#
#   --deploy <file>  then run `java -jar <generic jar> inject <file>` for both jars, writing
#                    dist/cattail-<ver>+mc<mc>-<tag>.jar and
#                    dist/cattail-<ver>+mc<mc>-novoice-<tag>.jar (<tag> from the file's last
#                    tag line, tag=tag:<tag>); the owner's file is
#                    .secrets/deployment.properties, see deployment.properties.example. The jar
#                    validates the file; a file with probe.targets is refused unless --probe is
#                    given too
#   --probe          build the helper with -tags probe and the jar with -Pprobe (manifest
#                    Cattail-Probe: true); needs --deploy, and the injected copy also gets
#                    probe.targets from build.conf CATTAIL_PROBE_TARGETS (required with
#                    --probe); both jar names get a "-probe" suffix; a dirty tree only adds
#                    "-dirty" to the helper version
#   --skip-tests     skip `go test`, the Gradle test task and the headless client smokes
#                    (scripts/smoke-client.sh, with and without Plasmo Voice); vet + staticcheck
#                    always run
#   --allow-dirty    build a normal jar from uncommitted changes (helper version gets "-dirty");
#                    without it a normal build refuses a dirty tree (untracked files ignored)
#
# Outside a git work tree (e.g. an unpacked source zip) the helper version is
# "<mod_version>+nogit" and the dirty-tree check does not apply.
#
# The key reaches a jar only through inject. This script takes only the tag= line from the
# deployment file; with --probe it copies the file by redirection into an owner-only temporary
# file. The key is never printed, logged or passed on a command line.
set -Eeuo pipefail
trap 'echo "build.sh: FAILED at line $LINENO (exit $?)" >&2' ERR

CALLER_DIR="$(pwd)" # a relative --deploy path is relative to where the script was started
cd "$(dirname "$0")/.."
REPO_ROOT="$(pwd)"
HOST_OS="$(uname -s)"

# sha256_list <file>...: "<hex>  <name>" per file, like sha256sum (shasum on macOS).
if command -v sha256sum >/dev/null; then
    sha256_list() { sha256sum "$@"; }
else
    sha256_list() { shasum -a 256 "$@"; }
fi

PROBE=0
SKIP_TESTS=0
ALLOW_DIRTY=0
DEPLOY_FILE=""
while (( $# )); do
    case "$1" in
        --deploy)
            [[ $# -ge 2 && -n "$2" ]] || { echo "build.sh: --deploy needs a file" >&2; exit 2; }
            DEPLOY_FILE="$2"
            shift
            ;;
        --probe) PROBE=1 ;;
        --skip-tests) SKIP_TESTS=1 ;;
        --allow-dirty) ALLOW_DIRTY=1 ;;
        -h|--help) sed -n '2,30p' "$0"; exit 0 ;;
        *) echo "build.sh: unknown argument: $1" >&2; exit 2 ;;
    esac
    shift
done
if (( PROBE )) && [[ -z "$DEPLOY_FILE" ]]; then
    echo "build.sh: --probe needs --deploy" >&2
    exit 2
fi
DEPLOY_TAG=""
if [[ -n "$DEPLOY_FILE" ]]; then
    [[ "$DEPLOY_FILE" == /* ]] || DEPLOY_FILE="$CALLER_DIR/$DEPLOY_FILE"
    if [[ ! -f "$DEPLOY_FILE" || ! -r "$DEPLOY_FILE" ]]; then
        echo "build.sh: --deploy: $DEPLOY_FILE is not a readable file" >&2
        exit 2
    fi
    # The tag only names the output jar and is not secret; inject validates the whole file. The
    # last tag line wins, as in Properties: key and value may be separated by "=", ":" or blanks,
    # with blanks around, and a byte order mark before the first line is skipped.
    DEPLOY_TAG="$(LC_ALL=C sed -nE -e $'1s/^\xef\xbb\xbf//' \
        -e 's/^[[:space:]]*tag([[:space:]]*[=:]|[[:space:]])[[:space:]]*tag:([a-z0-9-]+)[[:space:]]*$/\2/p' \
        "$DEPLOY_FILE" | tail -n 1)"
    if [[ ! "$DEPLOY_TAG" =~ ^[a-z0-9-]+$ ]]; then
        echo "build.sh: --deploy: $DEPLOY_FILE has no line tag=tag:<name> (a-z, 0-9, -) to name the jar" >&2
        exit 2
    fi
fi

# shellcheck source=scripts/env.sh
source "$REPO_ROOT/scripts/env.sh"

RES_DIR="$REPO_ROOT/mod/src/main/resources/helper"
DIST_DIR="$REPO_ROOT/dist"

# build.conf (read only with --probe): KEY=value lines (no quotes, no spaces, no export). Only
# lines of that exact form are read, and they are parsed as text, never sourced or evaluated.
BUILD_CONF="$REPO_ROOT/build.conf"
conf_fail() {
    echo "build.sh: build.conf: $1; copy build.conf.example to build.conf and edit it" >&2
    exit 1
}
conf_get() { # last value of key $1, empty when absent
    { grep -E '^[A-Z_]+=[^[:space:]]*$' "$BUILD_CONF" || true; } | sed -n "s/^$1=//p" | tail -n 1
}
# Probe targets (only used with --probe): comma-separated IPv4:port, e.g. 100.101.102.103:22.
CATTAIL_PROBE_TARGETS=""
valid_probe_target() { # $1 = one "a.b.c.d:port"
    local ip port o
    [[ "$1" =~ ^([0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}):([0-9]{1,5})$ ]] || return 1
    ip="${BASH_REMATCH[1]}"
    port="${BASH_REMATCH[2]}"
    for o in ${ip//./ }; do
        [[ "$o" =~ ^(0|[1-9][0-9]{0,2})$ ]] && (( o <= 255 )) || return 1
    done
    [[ "$port" =~ ^[1-9][0-9]*$ ]] && (( port <= 65535 ))
}
if (( PROBE )); then
    [[ -f "$BUILD_CONF" ]] || conf_fail "file not found (--probe reads CATTAIL_PROBE_TARGETS from it)"
    CATTAIL_PROBE_TARGETS="$(conf_get CATTAIL_PROBE_TARGETS)"
    [[ -n "$CATTAIL_PROBE_TARGETS" ]] || conf_fail "CATTAIL_PROBE_TARGETS is required with --probe"
    IFS=',' read -r -a probe_targets <<<"$CATTAIL_PROBE_TARGETS"
    [[ "$CATTAIL_PROBE_TARGETS" != *, && ${#probe_targets[@]} -gt 0 ]] \
        || conf_fail "CATTAIL_PROBE_TARGETS must be comma-separated IPv4:port entries"
    for t in "${probe_targets[@]}"; do
        valid_probe_target "$t" || conf_fail "CATTAIL_PROBE_TARGETS entry '$t' is not IPv4:port"
    done
fi

MOD_VERSION="$(sed -n 's/^mod_version=\([^[:space:]]*\).*$/\1/p' mod/gradle.properties)"
MC_VERSION="$(sed -n 's/^minecraft_version=\([^[:space:]]*\).*$/\1/p' mod/gradle.properties)"
if [[ -z "$MOD_VERSION" || -z "$MC_VERSION" ]]; then
    echo "build.sh: could not read mod_version/minecraft_version from mod/gradle.properties" >&2
    exit 1
fi
# A jar for players must be built from a commit; untracked files do not count. Only a git work
# tree rooted HERE counts (not a parent directory's repository).
IN_GIT=0
if [[ -e "$REPO_ROOT/.git" ]] \
        && [[ "$(git -C "$REPO_ROOT" rev-parse --show-toplevel 2>/dev/null || true)" == "$REPO_ROOT" ]]; then
    IN_GIT=1
fi
if (( IN_GIT )); then
    VER="${MOD_VERSION}+$(git rev-parse --short HEAD)"
else
    VER="${MOD_VERSION}+nogit"
    echo "build.sh: not a git work tree; helper version is $VER" >&2
fi
if (( IN_GIT )) && { ! git diff --quiet || ! git diff --cached --quiet; }; then
    if (( PROBE || ALLOW_DIRTY )); then
        VER="${VER}-dirty"
        echo "build.sh: WARNING: uncommitted changes; helper version is $VER" >&2
    else
        echo "build.sh: refusing to build a release jar from a dirty git tree (commit first, or pass --allow-dirty)" >&2
        git status --short --untracked-files=no >&2
        exit 1
    fi
fi

# Empty-array expansions use ${a[@]+"${a[@]}"}: macOS bash 3.2 + set -u treats a bare
# "${a[@]}" of an empty array as unbound.
GO_TAGS=()
JAR_SUFFIX=""
if (( PROBE )); then
    GO_TAGS=(-tags probe)
    JAR_SUFFIX="-probe"
fi
OUT_JAR="$DIST_DIR/cattail-${MOD_VERSION}+mc${MC_VERSION}${JAR_SUFFIX}.jar"
OUT_NOVOICE_JAR="$DIST_DIR/cattail-${MOD_VERSION}+mc${MC_VERSION}-novoice${JAR_SUFFIX}.jar"
INJECTED_JAR=""
INJECTED_NOVOICE_JAR=""
if [[ -n "$DEPLOY_FILE" ]]; then
    INJECTED_JAR="$DIST_DIR/cattail-${MOD_VERSION}+mc${MC_VERSION}-${DEPLOY_TAG}${JAR_SUFFIX}.jar"
    INJECTED_NOVOICE_JAR="$DIST_DIR/cattail-${MOD_VERSION}+mc${MC_VERSION}-novoice-${DEPLOY_TAG}${JAR_SUFFIX}.jar"
fi

# The helper resources hold only what this build writes (the exe and its sha256). Older builds
# also wrote the key, the player tag and the probe targets there, and the specs prefix in
# cattail/build.properties next to it; the mod no longer reads them, and a left-over copy would
# ship inside the generic jar (the key with it), so they go before every build.
rm -rf "$RES_DIR"
LEGACY_PROPS_DIR="$REPO_ROOT/mod/src/main/resources/cattail"
rm -f "$LEGACY_PROPS_DIR/build.properties"
rmdir "$LEGACY_PROPS_DIR" 2>/dev/null || true

echo "==> helper $VER (probe=$PROBE, skip-tests=$SKIP_TESTS)"
(
    cd helper
    echo "--> go vet"
    go vet ./...
    GOOS=windows GOARCH=amd64 CGO_ENABLED=0 go vet ./...
    echo "--> staticcheck"
    staticcheck ./...
    echo "--> third-party licence file"
    ../scripts/gen-helper-notices.sh --check
    if (( PROBE )); then
        echo "--> go vet / staticcheck (-tags probe)"
        go vet -tags probe ./...
        staticcheck -tags probe ./...
    fi
    if (( ! SKIP_TESTS )); then
        echo "--> go test"
        go test ${GO_TAGS[@]+"${GO_TAGS[@]}"} ./...
    fi
    echo "--> cross-compile windows/amd64"
    mkdir -p "$RES_DIR"
    # Tailscale reports version.Long() to the control plane (shown in the admin console). Without
    # stamps it is derived from git metadata: "x.y.z-devYYYYMMDD-g<commit>" in a git tree,
    # "x.y.z-ERR-BuildInfo" from an unpacked zip. Stamp it like Tailscale's build_dist.sh does,
    # and drop the VCS metadata (-buildvcs=false) so a git tree and a zip build the same exe.
    TS_VER="$(go list -m -f '{{.Version}}' tailscale.com)"
    TS_VER="${TS_VER#v}"
    [[ "$TS_VER" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "build.sh: unexpected tailscale.com version '$TS_VER'" >&2; exit 1; }
    TS_LONG="${TS_VER}-cattail-${VER//+/-}"
    GOOS=windows GOARCH=amd64 CGO_ENABLED=0 go build ${GO_TAGS[@]+"${GO_TAGS[@]}"} -trimpath -buildvcs=false \
        -ldflags "-s -w -H windowsgui -X main.version=$VER \
            -X tailscale.com/version.longStamp=$TS_LONG -X tailscale.com/version.shortStamp=$TS_VER" \
        -o "$RES_DIR/cattail-helper.exe" ./cmd/cattail-helper
    echo "    tailscale version reported by the helper: $TS_LONG"
)

(cd "$RES_DIR" && sha256_list cattail-helper.exe > cattail-helper.sha256)

echo "==> mod"
GRADLE_ARGS=(clean build)
if (( SKIP_TESTS )); then
    GRADLE_ARGS+=(-x test)
fi
# -Pprobe sets the manifest's Cattail-Probe: true, the only jar inject lets carry probe.targets.
if (( PROBE )); then
    GRADLE_ARGS+=(-Pprobe)
fi
(cd mod && ./gradlew --no-daemon --warning-mode=fail "${GRADLE_ARGS[@]}")

if (( ! SKIP_TESTS )); then
    if [[ "$HOST_OS" == "Linux" ]]; then
        echo "==> headless client smoke (Xvfb)"
        scripts/smoke-client.sh
        echo "==> headless client smoke without Plasmo Voice (Xvfb)"
        scripts/smoke-client.sh --novoice
    else
        echo "==> headless client smokes skipped: need Xvfb (Linux only); go test and the Gradle tests ran"
    fi
fi

BUILT_JAR="mod/build/libs/cattail-${MOD_VERSION}.jar"
BUILT_NOVOICE_JAR="mod/build/libs/cattail-${MOD_VERSION}-novoice.jar"
for built in "$BUILT_JAR" "$BUILT_NOVOICE_JAR"; do
    if [[ ! -f "$built" ]]; then
        echo "build.sh: expected $built was not produced" >&2
        exit 1
    fi
done

# Checksums for every jar in dist/ (generic, injected, normal and probe builds can sit side by
# side); written again after inject, so dist/ is consistent even when inject refuses the file.
write_sums() { (cd "$DIST_DIR" && sha256_list cattail-*.jar > SHA256SUMS); }
mkdir -p "$DIST_DIR"
# The generic jars hold no key, so unlike the injected copies they are not made owner-only.
cp "$BUILT_JAR" "$OUT_JAR"
cp "$BUILT_NOVOICE_JAR" "$OUT_NOVOICE_JAR"
write_sums

if [[ -n "$DEPLOY_FILE" ]]; then
    DEPLOY_INPUT="$DEPLOY_FILE"
    if (( PROBE )); then
        # Probe targets join a private copy of the deployment file (owner-only directory and
        # file, removed on exit), copied by redirection only. If the file has its own
        # probe.targets, this later line wins.
        DEPLOY_TMP_DIR="$(umask 077 && mktemp -d -t cattail-deploy.XXXXXX)"
        trap 'rm -rf "$DEPLOY_TMP_DIR"' EXIT
        DEPLOY_INPUT="$DEPLOY_TMP_DIR/deployment.properties"
        (
            umask 077
            { cat < "$DEPLOY_FILE"; printf '\nprobe.targets=%s\n' "$CATTAIL_PROBE_TARGETS"; } > "$DEPLOY_INPUT"
        )
        echo "==> inject $DEPLOY_FILE + build.conf CATTAIL_PROBE_TARGETS"
    else
        echo "==> inject $DEPLOY_FILE"
    fi
    # Each jar's own inject validates the file and writes the copy owner-only; it never prints
    # the key. --out names the output, so a jar left by an earlier run is replaced.
    rc=0
    java -jar "$OUT_JAR" inject "$DEPLOY_INPUT" --out "$INJECTED_JAR" || rc=$?
    if (( rc != 0 )); then
        echo "build.sh: inject refused the deployment file (exit $rc, reason above); the generic jar is ${OUT_JAR#"$REPO_ROOT"/}" >&2
        exit "$rc"
    fi
    java -jar "$OUT_NOVOICE_JAR" inject "$DEPLOY_INPUT" --out "$INJECTED_NOVOICE_JAR" || rc=$?
    if (( rc != 0 )); then
        echo "build.sh: inject (no-voice jar) refused the deployment file (exit $rc, reason above)" >&2
        exit "$rc"
    fi
    # The injected jars carry the key: owner-only.
    chmod 600 "$INJECTED_JAR" "$INJECTED_NOVOICE_JAR"
    write_sums
fi

SIZE_BYTES="$(wc -c < "$OUT_JAR" | tr -d '[:space:]')"   # portable (BSD stat has no -c)
SIZE_MB="$(awk -v b="$SIZE_BYTES" 'BEGIN { printf "%.2f", b / 1048576 }')"
echo
echo "==> done"
echo "jar:    ${OUT_JAR#"$REPO_ROOT"/} (generic, no deployment)"
echo "jar:    ${OUT_NOVOICE_JAR#"$REPO_ROOT"/} (generic, no deployment, no Plasmo Voice)"
if [[ -n "$INJECTED_JAR" ]]; then
    echo "deploy: ${INJECTED_JAR#"$REPO_ROOT"/} (carries the key: owner-only, never commit or share)"
    echo "deploy: ${INJECTED_NOVOICE_JAR#"$REPO_ROOT"/} (carries the key: owner-only, never commit or share)"
fi
echo "size:   $SIZE_BYTES bytes ($SIZE_MB MiB), no-voice $(wc -c < "$OUT_NOVOICE_JAR" | tr -d '[:space:]') bytes"
echo "helper: $(cat "$RES_DIR/cattail-helper.sha256")"
(cd "$DIST_DIR" && cat SHA256SUMS)
if (( SIZE_BYTES < 18 * 1048576 || SIZE_BYTES > 22 * 1048576 )); then
    echo "WARNING: jar size $SIZE_MB MiB is outside the expected 18-22 MiB range" >&2
fi
if [[ -z "$DEPLOY_FILE" && -e "$REPO_ROOT/.secrets/deployment.properties" ]]; then
    echo "build.sh: NOTE: generic jar built; pass --deploy .secrets/deployment.properties to inject" >&2
fi
