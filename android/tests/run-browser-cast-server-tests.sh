#!/bin/bash
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
SOURCE_ROOT="$(cd "$HERE/../.." && pwd)"
while [[ $# -gt 0 ]]; do case "$1" in --source-root) SOURCE_ROOT="$2"; shift 2 ;; *) echo 'Usage: run-browser-cast-server-tests.sh [--source-root PROJECT]' >&2; exit 64 ;; esac; done
JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
JSON_JAR="${JSON_JAR:-$HOME/.m2/repository/com/vaadin/external/google/android-json/0.0.20131108.vaadin1/android-json-0.0.20131108.vaadin1.jar}"
SRC="$SOURCE_ROOT/android/app/src/main/java/io/github/jackotom/hongguo/android"
if [[ ! -f "$SRC/BrowserCastServer.java" ]]; then echo 'BROWSER_CAST_SERVER_UNSUPPORTED productionServer=absent'; exit 3; fi
TMP="$(mktemp -d -t hongguo-browser-cast-server.XXXXXX)"
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/classes" "$TMP/sdk35"
SOURCES=("$SRC/MediaQuality.java" "$SRC/DlnaSession.java" "$SRC/BrowserCastServer.java")
"$JAVA_HOME/bin/javac" -encoding UTF-8 -source 17 -target 17 -cp "$ANDROID_HOME/platforms/android-35/android.jar" -d "$TMP/sdk35" "${SOURCES[@]}"
echo 'PASS BrowserCastServer SDK35 actualProductionCompile=true'
"$JAVA_HOME/bin/javac" --release 17 -encoding UTF-8 -cp "$JSON_JAR" -d "$TMP/classes" "${SOURCES[@]}" "$HERE/BrowserCastServerTest.java"
"$JAVA_HOME/bin/java" -cp "$TMP/classes:$JSON_JAR" io.github.jackotom.hongguo.android.BrowserCastServerTest
