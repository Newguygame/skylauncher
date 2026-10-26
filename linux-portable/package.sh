#!/usr/bin/env bash
# Package a root-level Linux tarball, optionally bundling a Java runtime.
# Usage: bash linux-portable/package.sh build/SkyLauncher.jar dist [--bundle-java] [--arch x86_64|arm64]
set -euo pipefail
SRC="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
JAR="${1:?Specify built JAR}"; OUT="${2:?Specify destination directory}"; shift 2
BUNDLE=false
ARCH="$(uname -m)"
while (($#)); do
  case "$1" in
    --bundle-java) BUNDLE=true; shift;;
    --arch) ARCH="$2"; shift 2;;
    *) echo "Unexpected argument: $1" >&2; exit 2;;
  esac
done
case "$ARCH" in
 x86_64|amd64) ARCH=x86_64;;
 arm64|aarch64) ARCH=arm64;;
 *) echo "Unsupported architecture: $ARCH" >&2; exit 2;;
esac
JAR="$(realpath "$JAR")"
mkdir -p "$OUT"; OUT="$(cd "$OUT" && pwd -P)"
WORK="$(mktemp -d)"; trap 'rm -rf -- "$WORK"' EXIT
cp "$JAR" "$WORK/SkyLauncher.jar"
cp "$SRC/run.sh" "$SRC/install.sh" "$SRC/uninstall.sh" "$SRC/README.txt" "$WORK/"
chmod +x "$WORK/run.sh" "$WORK/install.sh" "$WORK/uninstall.sh"

if [[ "$BUNDLE" == true ]]; then
  command -v jlink >/dev/null || { echo 'Please install JDK 21+ with jlink' >&2; exit 1; }
  echo 'Creating embedded Java runtime...'
  jlink --add-modules java.se,jdk.crypto.ec,jdk.unsupported,jdk.zipfs --strip-debug --no-header-files --no-man-pages --output "$WORK/runtime"
  # Deliberately build on matching Linux runner architecture (never cross-build a runtime).
  FILE="SkyLauncher-Linux-Portable-${ARCH}.tar.gz"
else
  FILE='SkyLauncher-Linux-Portable-Java-Required.tar.gz'
fi
# Create a root-level archive, not a wrapper SkyLauncher/ directory.
tar -czf "$OUT/$FILE" -C "$WORK" .
echo "Built: $OUT/$FILE"
