#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SOURCE_ROOT="${1:-$ROOT}"
JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
JSON_JAR="${JSON_JAR:-$HOME/.m2/repository/com/vaadin/external/google/android-json/0.0.20131108.vaadin1/android-json-0.0.20131108.vaadin1.jar}"
TMP="$(mktemp -d -t hongguo-progress.XXXXXX)"; trap 'rm -rf "$TMP"' EXIT
python3 "$ROOT/android/tests/playback-progress-harness.py" "$SOURCE_ROOT/android/app/src/main/java/io/github/jackotom/hongguo/android/PlayerActivity.java" "$TMP/PlaybackProgressHarness.java"
"$JAVA_HOME/bin/javac" --release 17 -encoding UTF-8 -cp "$JSON_JAR" -d "$TMP/classes" "$TMP/PlaybackProgressHarness.java"
"$JAVA_HOME/bin/java" -cp "$TMP/classes:$JSON_JAR" io.github.jackotom.hongguo.android.PlaybackProgressHarness
