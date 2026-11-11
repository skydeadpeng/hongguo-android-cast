#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)";TMP="$(mktemp -d)";trap 'rm -rf "$TMP"' EXIT
J="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}";JSON_JAR="${JSON_JAR:-$HOME/.m2/repository/com/vaadin/external/google/android-json/0.0.20131108.vaadin1/android-json-0.0.20131108.vaadin1.jar}"
mkdir -p "$TMP/android/webkit";printf '%s\n' 'package android.webkit;public @interface JavascriptInterface {}' > "$TMP/android/webkit/JavascriptInterface.java"
SRC="$ROOT/app/src/main/java/io/github/jackotom/hongguo/android"
"$J/bin/javac" --release 17 -cp "$JSON_JAR" -d "$TMP/classes" "$TMP/android/webkit/JavascriptInterface.java" "$SRC/OfficialSeriesBridge.java" "$SRC/OfficialSeasons.java" "$ROOT/tests/OfficialSeriesBridgeTest.java"
"$J/bin/java" -cp "$TMP/classes:$JSON_JAR" io.github.jackotom.hongguo.android.OfficialSeriesBridgeTest
