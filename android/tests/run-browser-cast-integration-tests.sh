#!/bin/bash
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
SOURCE_ROOT="$(cd "$HERE/../.." && pwd)"
SERVE=''
while [[ $# -gt 0 ]]; do case "$1" in --source-root) SOURCE_ROOT="$2"; shift 2 ;; --serve) SERVE="$2"; shift 2 ;; *) echo 'Usage: run-browser-cast-integration-tests.sh [--source-root PROJECT] [--serve FIXTURE_DIRECTORY]' >&2; exit 64 ;; esac; done
JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
JSON_JAR="${JSON_JAR:-$HOME/.m2/repository/com/vaadin/external/google/android-json/0.0.20131108.vaadin1/android-json-0.0.20131108.vaadin1.jar}"
SRC="$SOURCE_ROOT/android/app/src/main/java/io/github/jackotom/hongguo/android"
if [[ ! -f "$SRC/BrowserCastServer.java" || ! -f "$SRC/BrowserCastSession.java" ]]; then echo 'BROWSER_CAST_INTEGRATION_UNSUPPORTED productionBrowserCast=absent'; exit 3; fi
TMP="$(mktemp -d -t hongguo-browser-cast-integration.XXXXXX)"
trap 'rm -rf "$TMP"' EXIT
"$JAVA_HOME/bin/javac" --release 17 -encoding UTF-8 -cp "$JSON_JAR" -d "$TMP/classes" "$SRC/MediaQuality.java" "$SRC/DlnaSession.java" "$SRC/DlnaPrefetch.java" "$SRC/PlaybackCachePolicy.java" "$SRC/BrowserCastServer.java" "$SRC/BrowserCastSession.java" "$HERE/BrowserCastIntegrationTest.java"
if [[ -n "$SERVE" ]]; then
  "$JAVA_HOME/bin/java" -cp "$TMP/classes:$JSON_JAR" io.github.jackotom.hongguo.android.BrowserCastIntegrationTest --serve "$SERVE" "$SOURCE_ROOT/android/app/src/main/assets/receiver"
else
  "$JAVA_HOME/bin/java" -cp "$TMP/classes:$JSON_JAR" io.github.jackotom.hongguo.android.BrowserCastIntegrationTest "$SOURCE_ROOT/android/app/src/main/assets/receiver"
fi
