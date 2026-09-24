#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
JAR="$ROOT/gradle/wrapper/gradle-wrapper.jar"
EXPECTED=81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f
if [ ! -f "$JAR" ]; then
  echo "Downloading the official Gradle 8.13 wrapper (SHA-256 verified)..."
  curl --fail --location --connect-timeout 20 --max-time 180 --proto '=https' https://raw.githubusercontent.com/gradle/gradle/v8.13.0/gradle/wrapper/gradle-wrapper.jar -o "$JAR.download"
  if command -v sha256sum >/dev/null 2>&1; then
    ACTUAL=$(sha256sum "$JAR.download" | cut -d ' ' -f 1)
  else
    ACTUAL=$(shasum -a 256 "$JAR.download" | cut -d ' ' -f 1)
  fi
  [ "$ACTUAL" = "$EXPECTED" ] || { rm -f "$JAR.download"; echo "Wrapper checksum mismatch" >&2; exit 1; }
  mv "$JAR.download" "$JAR"
fi
if [ -n "${JAVA_HOME:-}" ]; then JAVA="$JAVA_HOME/bin/java"; else JAVA=java; fi
exec "$JAVA" -Dorg.gradle.appname=gradlew -classpath "$JAR" org.gradle.wrapper.GradleWrapperMain "$@"
