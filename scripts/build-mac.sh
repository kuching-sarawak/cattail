#!/usr/bin/env bash
# One-command build on macOS (Apple Silicon or Intel): installs the pinned toolchain if it is
# missing, then runs scripts/build.sh end to end (vet, staticcheck, go test, Gradle build with
# tests, jar) with this script's arguments.
#
#   scripts/build-mac.sh [build.sh flags, e.g. --skip-tests, --deploy .secrets/deployment.properties]
#
# See BUILD-MACOS.md. Also works on Linux; it is just build.sh with the setup steps in front.
set -Eeuo pipefail
trap 'echo "build-mac.sh: FAILED at line $LINENO (exit $?)" >&2' ERR

CALLER_DIR="$(pwd)" # build.sh resolves a relative --deploy path from here
cd "$(dirname "$0")/.."
REPO_ROOT="$(pwd)"

echo "==> toolchain"
bash scripts/install-toolchain.sh
# shellcheck source=env.sh
source scripts/env.sh
echo "    go:   $(go version)"
echo "    java: $(java -version 2>&1 | head -n 1)"

echo "==> build"
(cd "$CALLER_DIR" && "$REPO_ROOT/scripts/build.sh" "$@")

echo
echo "Done. Jar(s) in $REPO_ROOT/dist/ (checksums in dist/SHA256SUMS):"
ls -l "$REPO_ROOT"/dist/*.jar
