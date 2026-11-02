#!/bin/sh
# SkyLauncher updater for macOS/Linux. Runs independently after SkyLauncher exits.
set -eu
MODE="$1"; PACKAGE="$2"; TARGET="$3"; APP="$4"; JAVA="$5"; PARENT_PID="$6"
wait_for_exit() {
  i=0
  while kill -0 "$PARENT_PID" 2>/dev/null; do
    i=$((i+1))
    [ "$i" -lt 120 ] || { echo 'SkyLauncher did not exit' >&2; exit 1; }
    sleep 1
  done
}
wait_for_exit
case "$MODE" in
  portable-jar)
    BACKUP="${PACKAGE}.old"
    mv -- "$TARGET" "$BACKUP"
    if mv -- "$PACKAGE" "$TARGET"; then
      rm -f -- "$BACKUP"
      nohup "$JAVA" -jar "$TARGET" >/dev/null 2>&1 </dev/null &
    else
      mv -- "$BACKUP" "$TARGET" || true
      exit 1
    fi
    ;;
  mac-app)
    # Replace only the application bundle; app data is stored outside the bundle.
    WORK="$(dirname "$PACKAGE")"
    mkdir -p "$WORK/mount"
    hdiutil attach -quiet -readonly -nobrowse -mountpoint "$WORK/mount" "$PACKAGE"
    NEWAPP="$(find "$WORK/mount" -type d -name 'SkyLauncher.app' -print -quit)"
    [ -n "$NEWAPP" ] || { hdiutil detach "$WORK/mount" || true; exit 1; }
    # Stage and back up on the SAME filesystem as the installed app.
    # This makes replacement atomic and prevents cross-filesystem copy failures.
    APPDIR="$(dirname "$APP")"
    STAGE_DIR="$(mktemp -d "$APPDIR/.skylauncher-update.XXXXXXXX")"
    STAGED="$STAGE_DIR/SkyLauncher.app"
    if ! ditto "$NEWAPP" "$STAGED"; then
      hdiutil detach -quiet "$WORK/mount" || true
      rm -rf -- "$STAGE_DIR"
      exit 1
    fi
    hdiutil detach -quiet "$WORK/mount" || true
    BACKUP="$STAGE_DIR/previous.app"
    mv -- "$APP" "$BACKUP"
    if mv -- "$STAGED" "$APP"; then
      rm -rf -- "$STAGE_DIR"
      open "$APP"
    else
      mv -- "$BACKUP" "$APP" || true
      exit 1
    fi
    ;;
  linux-deb)
    # pkexec shows the desktop authorization dialog; no unsupervised root access.
    /usr/bin/pkexec /usr/bin/dpkg -i "$PACKAGE"
    if [ -x /opt/skylauncher/bin/SkyLauncher ]; then
      nohup /opt/skylauncher/bin/SkyLauncher >/dev/null 2>&1 </dev/null &
    fi
    ;;
  *) echo 'Unknown update mode' >&2; exit 2 ;;
esac
