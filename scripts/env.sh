# Source this file (do not execute it) to put the pinned Cattail toolchain on
# PATH: `source scripts/env.sh`. Install it first with scripts/install-toolchain.sh.
export GOROOT="${HOME}/.local/toolchains/go1.27.3"
export JAVA_HOME="${HOME}/.local/toolchains/jdk-21.0.12.1+1"
export GOPATH="${HOME}/.local/go"
export PATH="${GOROOT}/bin:${JAVA_HOME}/bin:${GOPATH}/bin:${PATH}"
# macOS: build without cgo so no Xcode Command Line Tools are needed. The helper is always
# built with CGO_ENABLED=0 anyway; this makes the host-side vet/test match.
if [[ "$(uname -s)" == "Darwin" ]]; then
    export CGO_ENABLED=0
fi
