#!/usr/bin/env bash
# Installs the pinned Cattail build toolchain per-user into ~/.local/toolchains:
# Go, Temurin JDK and staticcheck. Idempotent: anything already present at the
# pinned version is left alone. Every download is SHA-256 verified against the
# checksum pinned below before it is unpacked.
#
# Supported hosts: Linux x86-64, macOS arm64 (Apple Silicon), macOS x86-64.
# Needs only curl and tar (and sha256sum or shasum, which every host has).
#
# The pinned checksums come from the vendors: Go from https://go.dev/dl/?mode=json
# (field sha256), Temurin from the <file>.sha256.txt next to each release asset on
# https://github.com/adoptium/temurin21-binaries/releases. Bumping a version means
# updating the file names AND the checksums here.
set -Eeuo pipefail

GO_VERSION="1.27.3"
JDK_RELEASE="jdk-21.0.12.1+1"
JDK_NUM="${JDK_RELEASE#jdk-}"          # 21.0.12.1+1
JDK_FILE_VER="${JDK_NUM/+/_}"          # 21.0.12.1_1
STATICCHECK_VERSION="2026.2.2"

case "$(uname -s)/$(uname -m)" in
  Linux/x86_64)
    GO_FILE="go${GO_VERSION}.linux-amd64.tar.gz"
    GO_SUM="65efbc80fdc1fa2cd2631cad5b481777a68e8725f34e89f50fc6ac714e379b40"
    JDK_FILE="OpenJDK21U-jdk_x64_linux_hotspot_${JDK_FILE_VER}.tar.gz"
    JDK_SUM="ce79869e1307ed8ee1e2baa86a412b1eb5b75d10a01006d788a6f968bcfaee94"
    ;;
  Darwin/arm64)
    GO_FILE="go${GO_VERSION}.darwin-arm64.tar.gz"
    GO_SUM="dd3908cf20959d798c1e06ebe5b72213f0505ce01373b40ce3110975a6cd99c2"
    JDK_FILE="OpenJDK21U-jdk_aarch64_mac_hotspot_${JDK_FILE_VER}.tar.gz"
    JDK_SUM="3623232f33a9c3baadf304480b2535f9a3cba8a58d42ecbb438ba267315d9998"
    ;;
  Darwin/x86_64)
    GO_FILE="go${GO_VERSION}.darwin-amd64.tar.gz"
    GO_SUM="b6f8e931e4ab497fcde6da8ae4499a58f9d258e55c8a80824a516eae5950a750"
    JDK_FILE="OpenJDK21U-jdk_x64_mac_hotspot_${JDK_FILE_VER}.tar.gz"
    JDK_SUM="44db0f08196daf19a47f90d13388b0c943b67663cb537f998fe29e836fa842ce"
    ;;
  *)
    echo "unsupported host: $(uname -s) $(uname -m) (supported: Linux x86_64, macOS arm64/x86_64)" >&2
    exit 1
    ;;
esac
GO_URL="https://go.dev/dl/${GO_FILE}"
JDK_URL="https://github.com/adoptium/temurin21-binaries/releases/download/${JDK_RELEASE/+/%2B}/${JDK_FILE}"

TOOLCHAINS="${HOME}/.local/toolchains"
GO_DIR="${TOOLCHAINS}/go${GO_VERSION}"
JDK_DIR="${TOOLCHAINS}/${JDK_RELEASE}"

for cmd in curl tar; do
  command -v "$cmd" >/dev/null || { echo "missing required command: $cmd" >&2; exit 1; }
done
# sha256_of <file>: prints the hex digest; sha256sum (coreutils) or shasum (macOS, perl).
if command -v sha256sum >/dev/null; then
  sha256_of() { sha256sum "$1" | cut -d' ' -f1; }
elif command -v shasum >/dev/null; then
  sha256_of() { shasum -a 256 "$1" | cut -d' ' -f1; }
else
  echo "missing required command: sha256sum or shasum" >&2; exit 1
fi

mkdir -p "$TOOLCHAINS"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# download_verified <url> <expected sha256> <dest>
download_verified() {
  local url="$1" want="$2" dest="$3" got
  if [[ ! "$want" =~ ^[0-9a-f]{64}$ ]]; then
    echo "no valid pinned checksum for $url (got '$want')" >&2
    exit 1
  fi
  curl -fsSL --retry 3 -o "$dest" "$url"
  got="$(sha256_of "$dest")"
  if [[ "$got" != "$want" ]]; then
    echo "checksum mismatch for $url: expected $want, got $got" >&2
    exit 1
  fi
}

# install_tarball <url> <sha256> <target dir>: unpacks the archive's single
# top-level directory into <target dir> (atomic rename, no partial installs).
# macOS JDK tarballs are app bundles (<top>/Contents/Home holds the JDK); that
# inner directory is what gets installed, so JAVA_HOME is the same on every host.
install_tarball() {
  local url="$1" sum="$2" target="$3" archive="${WORK}/archive.tar.gz"
  download_verified "$url" "$sum" "$archive"
  local unpack="${WORK}/unpack"
  rm -rf "$unpack" && mkdir -p "$unpack"
  tar -xzf "$archive" -C "$unpack"
  local top=("$unpack"/*)
  [[ ${#top[@]} -eq 1 && -d "${top[0]}" ]] || { echo "unexpected layout in $url" >&2; exit 1; }
  local src="${top[0]}"
  [[ -d "$src/Contents/Home" ]] && src="$src/Contents/Home"
  rm -rf "$target"
  mv "$src" "$target"
}

# --- Go -----------------------------------------------------------------------
if [[ -x "${GO_DIR}/bin/go" ]] && "${GO_DIR}/bin/go" version 2>/dev/null | grep -q "go${GO_VERSION} "; then
  echo "Go ${GO_VERSION}: already installed"
else
  echo "Go ${GO_VERSION}: installing (${GO_FILE})"
  install_tarball "$GO_URL" "$GO_SUM" "$GO_DIR"
fi

# --- Temurin JDK --------------------------------------------------------------
if [[ -x "${JDK_DIR}/bin/java" && -f "${JDK_DIR}/release" ]] \
  && grep -qF -e "JAVA_RUNTIME_VERSION=\"${JDK_NUM}\"" -e "JAVA_RUNTIME_VERSION=\"${JDK_NUM}-" "${JDK_DIR}/release"; then
  echo "JDK ${JDK_RELEASE}: already installed"
else
  echo "JDK ${JDK_RELEASE}: installing (${JDK_FILE})"
  install_tarball "$JDK_URL" "$JDK_SUM" "$JDK_DIR"
fi

# --- staticcheck --------------------------------------------------------------
# shellcheck source=env.sh
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"
if command -v staticcheck >/dev/null && staticcheck -version | grep -q "^staticcheck ${STATICCHECK_VERSION} "; then
  echo "staticcheck ${STATICCHECK_VERSION}: already installed"
else
  echo "staticcheck ${STATICCHECK_VERSION}: installing"
  go install "honnef.co/go/tools/cmd/staticcheck@${STATICCHECK_VERSION}"
fi

echo "Toolchain ready. Run: source scripts/env.sh"
