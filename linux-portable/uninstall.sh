#!/usr/bin/env bash
# Uninstalls only the per-user portable application. Never deletes game data.
set -euo pipefail
DATA_HOME="${XDG_DATA_HOME:-$HOME/.local/share}"
DEST="$DATA_HOME/skylauncher"
APP_MENU="$DATA_HOME/applications/skylauncher.desktop"
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
if [[ "$ROOT" != "$DEST" ]]; then
  printf 'Run this uninstall.sh from the installed SkyLauncher folder: %s\n' "$DEST" >&2
  exit 1
fi
if [[ -f "$APP_MENU" ]] && grep -Fq -- "$DEST/run.sh" "$APP_MENU"; then rm -f -- "$APP_MENU"; fi
for desk in "$HOME/Desktop/SkyLauncher.desktop"; do
  if [[ -f "$desk" ]] && grep -Fq -- "$DEST/run.sh" "$desk"; then rm -f -- "$desk"; fi
done
rm -f -- "$DEST/SkyLauncher.jar" "$DEST/run.sh" "$DEST/install.sh" "$DEST/uninstall.sh" "$DEST/README.txt"
if [[ -d "$DEST/runtime" ]]; then rm -rf -- "$DEST/runtime"; fi
rmdir -- "$DEST" 2>/dev/null || true
printf 'SkyLauncher application removed. ~/.ghmodlauncher (profiles, mods, settings) was not changed.\n'
