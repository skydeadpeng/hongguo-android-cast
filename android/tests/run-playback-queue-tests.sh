#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
JSON_JAR="${JSON_JAR:-$HOME/.m2/repository/com/vaadin/external/google/android-json/0.0.20131108.vaadin1/android-json-0.0.20131108.vaadin1.jar}"
if [[ ! -f "$JSON_JAR" ]]; then echo 'TEST_DEPENDENCY_MISSING set JSON_JAR to the Android-compatible org.json runtime'; exit 2; fi
SDK_JAR="$ANDROID_HOME/platforms/android-35/android.jar"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
"$JAVA_HOME/bin/javac" -encoding UTF-8 -cp "$JSON_JAR:$SDK_JAR" -d "$TMP" \
  "$ROOT/app/src/main/java/io/github/jackotom/hongguo/android/LibraryStore.java" "$ROOT/tests/PlaybackQueueTest.java"
"$JAVA_HOME/bin/java" -cp "$TMP:$JSON_JAR:$SDK_JAR" io.github.jackotom.hongguo.android.PlaybackQueueTest
