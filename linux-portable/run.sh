#!/usr/bin/env bash
# Run from anywhere; never store game data inside the package directory.
set -euo pipefail
APP_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
APP_JAR="$APP_DIR/SkyLauncher.jar"

if [[ ! -f "$APP_JAR" ]]; then
  printf 'SkyLauncher.jar is missing next to run.sh\n' >&2
  exit 1
fi

if [[ -x "$APP_DIR/runtime/bin/java" ]]; then
  JAVA="$APP_DIR/runtime/bin/java"
elif [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]]; then
  JAVA="$JAVA_HOME/bin/java"
elif command -v java >/dev/null 2>&1; then
  JAVA="$(command -v java)"
else
  cat >&2 <<'MSG'
SkyLauncher needs Java 17 or newer.
Install Java (for example with your Linux package manager), or download
SkyLauncher-Linux-Portable-x86_64.tar.gz with Java bundled.
MSG
  exit 1
fi

# Some bundled runtimes cannot start on old glibc distributions. If so, fall back
# to a compatible system Java rather than failing with a cryptic native error.
if ! "$JAVA" -version >/dev/null 2>&1; then
  if [[ "$JAVA" == "$APP_DIR/runtime/bin/java" ]] && [[ -n "${JAVA_HOME:-}" ]] && [[ -x "$JAVA_HOME/bin/java" ]]; then
    JAVA="$JAVA_HOME/bin/java"
  elif [[ "$JAVA" == "$APP_DIR/runtime/bin/java" ]] && command -v java >/dev/null 2>&1; then
    JAVA="$(command -v java)"
  else
    printf 'Java could not start on this Linux system. Try the Java-required portable archive with a compatible system Java.\n' >&2
    exit 1
  fi
fi

# Fail clearly for unsupported system Java; bundled runtimes use JDK 21.
VERSION="$("$JAVA" -version 2>&1 | head -n 1 || true)"
if [[ "$VERSION" =~ \"([0-9]+)(\.([0-9]+))? ]]; then
  MAJOR="${BASH_REMATCH[1]}"
  if [[ "$MAJOR" == 1 ]]; then MAJOR="${BASH_REMATCH[3]:-0}"; fi
  if (( MAJOR < 17 )); then
    printf 'SkyLauncher requires Java 17 or newer, but found: %s\n' "$VERSION" >&2
    exit 1
  fi
fi

exec "$JAVA" -jar "$APP_JAR" "$@"
