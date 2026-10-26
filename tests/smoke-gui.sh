#!/usr/bin/env bash
set -euo pipefail
java -cp build/gui-classes SkyLauncher --ui-preview > build/gui-startup.log 2>&1 &
pid=$!
trap 'kill "$pid" 2>/dev/null || true' EXIT
for i in $(seq 1 12); do
  if ! kill -0 "$pid" 2>/dev/null; then
    cat build/gui-startup.log
    echo 'Launcher exited during graphical startup' >&2
    exit 1
  fi
  if xwininfo -root -tree | grep -q 'SkyLauncher [0-9]'; then
    echo 'SkyLauncher window opened successfully in virtual display'
    exit 0
  fi
  sleep 1
done
cat build/gui-startup.log
echo 'SkyLauncher did not open a window in 12 seconds' >&2
exit 1
