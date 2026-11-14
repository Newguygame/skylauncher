#!/usr/bin/env bash
set -euo pipefail
mkdir -p build/ci/classes build/ci/launcher build/ci/setup/bundled
javac --release 17 -encoding UTF-8 -d build/ci/classes src/SkyLauncher.java src/SkyLauncherSetup.java
javac --release 17 -encoding UTF-8 -cp build/ci/classes -d build/ci/classes tests/TestLocalModImport.java
java -ea -cp build/ci/classes TestLocalModImport
find build/ci/classes -maxdepth 1 -name 'SkyLauncher*.class' ! -name 'SkyLauncherSetup*.class' -exec cp {} build/ci/launcher/ \;
bash scripts/copy_assets.sh build/ci/launcher
jar --create --file build/SkyLauncher-ci.jar --main-class SkyLauncher -C build/ci/launcher .
find build/ci/classes -maxdepth 1 -name 'SkyLauncherSetup*.class' -exec cp {} build/ci/setup/ \;
cp build/ci/launcher/*.class build/ci/setup/
bash scripts/copy_assets.sh build/ci/setup
cp build/SkyLauncher-ci.jar build/ci/setup/bundled/SkyLauncher.jar
jar --create --file build/SkyLauncherSetup-ci.jar --main-class SkyLauncherSetup -C build/ci/setup .
java -jar build/SkyLauncher-ci.jar --selftest
java -jar build/SkyLauncherSetup-ci.jar --selftest
