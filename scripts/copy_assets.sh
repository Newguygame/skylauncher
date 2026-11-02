#!/usr/bin/env bash
set -euo pipefail
DEST="${1:?Usage: bash scripts/copy_assets.sh DEST_DIR}"
mkdir -p "$DEST"
for f in background-1.jpg background-2.jpg background-3.jpg background-4.jpg bridge-cert.pem; do
  test -s "assets/$f" || { echo "Missing assets/$f" >&2; exit 1; }
  cp "assets/$f" "$DEST/$f"
done
