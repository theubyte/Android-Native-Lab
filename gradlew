#!/bin/sh
# Wrapper bootstrap: download official Gradle 8.9 JAR when missing; verify against gradle.org release checksum.
set -eu
APP_HOME=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
JAR="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"
if [ ! -f "$JAR" ]; then
  echo 'Downloading verified official Gradle 8.9 wrapper JAR...'
  if command -v curl >/dev/null 2>&1; then
    curl --fail --location --silent --show-error 'https://services.gradle.org/distributions/gradle-8.9-wrapper.jar' -o "$JAR"
  else
    echo 'Install curl or generate the wrapper with Gradle 8.9.' >&2; exit 1
  fi
fi
ACTUAL=$(sha256sum "$JAR" | awk '{print $1}')
EXPECTED='498495120a03b9a6ab5d155f5de3c8f0d986a449153702fb80fc80e134484f17'
if [ "$ACTUAL" != "$EXPECTED" ]; then
  echo 'ERROR: Invalid Gradle wrapper JAR checksum.' >&2; exit 1
fi
if [ -n "${JAVA_HOME:-}" ]; then JAVA_CMD="$JAVA_HOME/bin/java"; else JAVA_CMD=java; fi
exec "$JAVA_CMD" -cp "$JAR" org.gradle.wrapper.GradleWrapperMain "$@"
