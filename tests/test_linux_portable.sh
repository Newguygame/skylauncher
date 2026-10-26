#!/usr/bin/env bash
# Offline portable-package test, safe to run on a GitHub runner.
set -euo pipefail
TARBALL="${1:?Usage: bash tests/test_linux_portable.sh path/to/archive.tar.gz}"
TARBALL="$(realpath "$TARBALL")"
WORK="$(mktemp -d)"
trap 'rm -rf -- "$WORK"' EXIT
mkdir -p "$WORK/unpacked" "$WORK/home/Desktop" "$WORK/home/.ghmodlauncher"
echo preserve > "$WORK/home/.ghmodlauncher/data.txt"
tar -xzf "$TARBALL" -C "$WORK/unpacked"
for file in run.sh install.sh uninstall.sh SkyLauncher.jar README.txt; do
  [[ -f "$WORK/unpacked/$file" ]] || { echo "Missing $file" >&2; exit 1; }
done
[[ -x "$WORK/unpacked/run.sh" ]] || { echo 'run.sh not executable' >&2; exit 1; }
[[ -x "$WORK/unpacked/install.sh" ]] || { echo 'install.sh not executable' >&2; exit 1; }
if [[ -d "$WORK/unpacked/runtime" ]]; then
  "$WORK/unpacked/runtime/bin/java" -version
fi
HOME="$WORK/home" XDG_DATA_HOME="$WORK/home/.local/share" \
  "$WORK/unpacked/run.sh" --selftest
HOME="$WORK/home" XDG_DATA_HOME="$WORK/home/.local/share" \
  "$WORK/unpacked/install.sh" --desktop
[[ -f "$WORK/home/.local/share/applications/skylauncher.desktop" ]]
[[ -f "$WORK/home/Desktop/SkyLauncher.desktop" ]]
HOME="$WORK/home" XDG_DATA_HOME="$WORK/home/.local/share" \
  "$WORK/home/.local/share/skylauncher/run.sh" --selftest
HOME="$WORK/home" XDG_DATA_HOME="$WORK/home/.local/share" \
  "$WORK/home/.local/share/skylauncher/uninstall.sh"
[[ ! -e "$WORK/home/.local/share/applications/skylauncher.desktop" ]]
[[ ! -e "$WORK/home/Desktop/SkyLauncher.desktop" ]]
[[ ! -e "$WORK/home/.local/share/skylauncher/SkyLauncher.jar" ]]
[[ "$(cat "$WORK/home/.ghmodlauncher/data.txt")" == preserve ]]
echo 'Portable Linux package test passed.'
