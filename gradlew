#!/bin/sh
# Gradle start up script (minimal wrapper: delegates to system gradle if wrapper jar missing)
APP_HOME=$(cd "$(dirname "$0")/.." && pwd)
if [ -f "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" ]; then
  exec java -jar "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" "$@"
else
  exec gradle "$@"
fi
