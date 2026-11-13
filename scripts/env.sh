# Source this file (do not execute it) to put the pinned Cattail toolchain on
# PATH: `source scripts/env.sh`. Install it first with scripts/install-toolchain.sh.
export GOROOT="${HOME}/.local/toolchains/go1.27.4"
export JAVA_HOME="${HOME}/.local/toolchains/jdk-21.0.12.1+1"
export GOPATH="${HOME}/.local/go"
export PATH="${GOROOT}/bin:${JAVA_HOME}/bin:${GOPATH}/bin:${PATH}"
# macOS: build without cgo so no Xcode Command Line Tools are needed. The helper is always
# built with CGO_ENABLED=0 anyway; this makes the host-side vet/test match.
if [[ "$(uname -s)" == "Darwin" ]]; then
    export CGO_ENABLED=0
fi
# The Go above is only the bootstrap. The Go that builds the helper is the one on the go line
# of helper/go.mod, which scripts/bump-tsnet.sh raises to what tailscale.com requires:
# GOTOOLCHAIN=go<that version>+auto makes the bootstrap run exactly that release (downloaded
# once into the module cache and verified against the Go checksum database), also when the
# bootstrap is newer, and follow a go line raised after this file was sourced. Self-check:
# scripts/env-test.sh.
if [ -n "${ZSH_VERSION:-}" ]; then
    eval '_cattail_env="${(%):-%x}"'
else
    _cattail_env="${BASH_SOURCE[0]}"
fi
_cattail_gomod="$(cd "$(dirname "$_cattail_env")/.." && pwd)/helper/go.mod"
_cattail_go="$(awk '$1 == "go" && NF == 2 && $2 ~ /^[0-9]+\.[0-9]+\.[0-9]+$/ { print $2; exit }' "$_cattail_gomod" 2> /dev/null || true)"
if [ -z "$_cattail_go" ]; then
    echo "env.sh: $_cattail_gomod has no go line naming a release (go <major>.<minor>.<patch>)" >&2
    unset _cattail_env _cattail_gomod _cattail_go
    return 1
fi
export GOTOOLCHAIN="go${_cattail_go}+auto"
unset _cattail_env _cattail_gomod _cattail_go
