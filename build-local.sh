#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
rm -rf build input
mkdir -p build/classes build/launcher input release-ready windows-installer/bundled
javac --release 17 -encoding UTF-8 -d build/classes src/SkyLauncher.java src/SkyLauncherSetup.java
find build/classes -maxdepth 1 -name 'SkyLauncher*.class' ! -name 'SkyLauncherSetup*.class' -exec cp {} build/launcher/ \;
for f in icons/SkyLauncher.{png,ico,icns}; do [[ ! -f "$f" ]] || { mkdir -p build/launcher/icons; cp "$f" build/launcher/icons; }; done
jar --create --file input/SkyLauncher.jar --main-class SkyLauncher -C build/launcher .
cp input/SkyLauncher.jar build/classes/bundled-SkyLauncher.jar
mkdir -p build/classes/bundled
cp input/SkyLauncher.jar build/classes/bundled/SkyLauncher.jar
jar --create --file input/SkyLauncherSetup.jar --main-class SkyLauncherSetup -C build/classes .
cp input/SkyLauncher.jar release-ready/SkyLauncher.jar
cp input/SkyLauncherSetup.jar release-ready/SkyLauncherSetup.jar
cp input/SkyLauncher.jar windows-installer/bundled/SkyLauncher.jar
java -jar input/SkyLauncher.jar --selftest
java -jar input/SkyLauncherSetup.jar --selftest
if [[ "${1:-}" == '--windows' ]]; then
  (cd windows-installer && GOOS=windows GOARCH=amd64 CGO_ENABLED=0 go build -trimpath -ldflags='-s -w -H=windowsgui' -o ../local-windows/SkyLauncher-Windows-Local-Setup.exe .)
fi
