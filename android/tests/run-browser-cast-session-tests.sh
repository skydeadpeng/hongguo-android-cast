#!/bin/bash
set -euo pipefail
TEST_ROOT="$(cd "$(dirname "$0")" && pwd)"
SOURCE_ROOT="$(cd "$TEST_ROOT/../.." && pwd)"
while [[ $# -gt 0 ]]; do case "$1" in --source-root) SOURCE_ROOT="$2";shift 2;; *) echo 'USAGE run-browser-cast-session-tests.sh [--source-root PROJECT]' >&2;exit 64;;esac;done
JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
JSON_JAR="${JSON_JAR:-$HOME/.m2/repository/com/vaadin/external/google/android-json/0.0.20131108.vaadin1/android-json-0.0.20131108.vaadin1.jar}"
SRC="$SOURCE_ROOT/android/app/src/main/java/io/github/jackotom/hongguo/android"
if [[ ! -f "$SRC/BrowserCastSession.java" ]]; then echo 'BROWSER_CAST_SESSION_BASELINE feature=absent existingDLNA=unchanged';exit 0;fi
TMP="$(mktemp -d -t hongguo-browser-cast-session.XXXXXX)"
trap 'rm -rf "$TMP"' EXIT
"$JAVA_HOME/bin/javac" --release 17 -encoding UTF-8 -cp "$JSON_JAR" -d "$TMP/classes" "$SRC/MediaQuality.java" "$SRC/DlnaSession.java" "$SRC/BrowserCastSession.java" "$TEST_ROOT/BrowserCastSessionTest.java"
"$JAVA_HOME/bin/java" -cp "$TMP/classes:$JSON_JAR" io.github.jackotom.hongguo.android.BrowserCastSessionTest
