#!/bin/sh
#
# Gradle wrapper bootstrap script
# Скачивает gradle-wrapper.jar автоматически если его нет
#

set -e

# Путь к wrapper jar
WRAPPER_JAR="$(dirname "$0")/gradle/wrapper/gradle-wrapper.jar"

# Скачать jar если отсутствует
if [ ! -f "$WRAPPER_JAR" ]; then
  echo "Downloading gradle-wrapper.jar..."
  if command -v curl > /dev/null; then
    curl -sL "https://github.com/gradle/gradle/raw/v8.0.0/gradle/wrapper/gradle-wrapper.jar" \
         -o "$WRAPPER_JAR"
  elif command -v wget > /dev/null; then
    wget -q "https://github.com/gradle/gradle/raw/v8.0.0/gradle/wrapper/gradle-wrapper.jar" \
         -O "$WRAPPER_JAR"
  else
    echo "ERROR: curl or wget required" >&2
    exit 1
  fi
fi

exec java -jar "$WRAPPER_JAR" "$@"
