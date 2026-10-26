#!/usr/bin/env bash
# Optional per-user installation; never needs sudo or modifies ~/.ghmodlauncher.
set -euo pipefail
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
DATA_HOME="${XDG_DATA_HOME:-$HOME/.local/share}"
DEST="$DATA_HOME/skylauncher"
APP_MENU="$DATA_HOME/applications/skylauncher.desktop"

want_desktop=ask
for arg in "$@"; do
  case "$arg" in
    --desktop) want_desktop=yes ;;
    --no-desktop) want_desktop=no ;;
    --yes) : ;; # Non-interactive use, does not imply a desktop shortcut.
    *) printf 'Usage: ./install.sh [--desktop|--no-desktop]\n' >&2; exit 2 ;;
  esac
done

if [[ ! -f "$ROOT/SkyLauncher.jar" ]]; then
  printf 'SkyLauncher.jar is missing. Extract all files into one directory first.\n' >&2
  exit 1
fi
if [[ "$ROOT" == "$DEST" ]]; then
  printf 'SkyLauncher is already installed in %s\n' "$DEST"
  exit 0
fi

# Install only package-managed files. Existing Minecraft data lives elsewhere.
mkdir -p "$DEST" "$(dirname "$APP_MENU")"
for file in SkyLauncher.jar run.sh install.sh uninstall.sh README.txt; do
  if [[ -f "$ROOT/$file" ]]; then cp -f -- "$ROOT/$file" "$DEST/$file"; fi
done
if [[ -d "$ROOT/runtime" ]]; then
  rm -rf -- "$DEST/runtime"
  cp -a -- "$ROOT/runtime" "$DEST/runtime"
else
  # Switching to the Java-required edition removes an older bundled runtime.
  if [[ -d "$DEST/runtime" ]]; then rm -rf -- "$DEST/runtime"; fi
fi
chmod +x "$DEST/run.sh" "$DEST/install.sh" "$DEST/uninstall.sh"

# FreeDesktop Exec argument: escape quoted string correctly; no third-party utilities.
exec_path="$DEST/run.sh"
exec_path="${exec_path//\\/\\\\}"
exec_path="${exec_path//\"/\\\"}"
exec_path="${exec_path//\$/\\\$}"
exec_path="${exec_path//\`/\\\`}"
exec_path="${exec_path//%/%%}"
cat > "$APP_MENU" <<ENTRY
[Desktop Entry]
Type=Application
Version=1.0
Name=SkyLauncher
GenericName=Minecraft launcher
Comment=Manage Minecraft instances and mods
Exec="$exec_path"
Icon=applications-games
Terminal=false
Categories=Game;
StartupNotify=true
ENTRY
chmod 644 "$APP_MENU"
printf 'Installed SkyLauncher in %s\nAdded application-menu entry: %s\n' "$DEST" "$APP_MENU"

if [[ "$want_desktop" == ask && -t 0 ]]; then
  read -r -p 'Also create a desktop shortcut? [y/N] ' answer
  case "$answer" in y|Y|yes|YES) want_desktop=yes;; *) want_desktop=no;; esac
fi
if [[ "$want_desktop" == yes ]]; then
  DESKTOP=""
  if command -v xdg-user-dir >/dev/null 2>&1; then DESKTOP="$(xdg-user-dir DESKTOP 2>/dev/null || true)"; fi
  if [[ -z "$DESKTOP" || ! -d "$DESKTOP" ]]; then DESKTOP="$HOME/Desktop"; fi
  if [[ -d "$DESKTOP" ]]; then
    cp -f -- "$APP_MENU" "$DESKTOP/SkyLauncher.desktop"
    chmod +x "$DESKTOP/SkyLauncher.desktop"
    if command -v gio >/dev/null 2>&1; then gio set "$DESKTOP/SkyLauncher.desktop" metadata::trusted true 2>/dev/null || true; fi
    printf 'Desktop shortcut: %s\n' "$DESKTOP/SkyLauncher.desktop"
  else
    printf 'No desktop folder found, so only the application-menu shortcut was created.\n'
  fi
fi
printf 'Minecraft settings and instances in ~/.ghmodlauncher are untouched.\n'
