#!/bin/bash
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)";ROOT="$(cd "$HERE/../.." && pwd)"
while [[ $# -gt 0 ]];do case "$1" in --source-root) ROOT="$2";shift 2;;*) echo 'USAGE --source-root PROJECT';exit 64;;esac;done
JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
JSON_JAR="${JSON_JAR:-$HOME/.m2/repository/com/vaadin/external/google/android-json/0.0.20131108.vaadin1/android-json-0.0.20131108.vaadin1.jar}"
SRC="$ROOT/android/app/src/main/java/io/github/jackotom/hongguo/android"
TMP="$(mktemp -d -t hongguo-season-activity.XXXXXX)";trap 'rm -rf "$TMP"' EXIT
python3 "$HERE/adaptive-activity-harness.py" "$SRC/PlayerActivity.java" "$TMP/AdaptiveActivityHarness.java"
"$JAVA_HOME/bin/javac" --release 17 -encoding UTF-8 -cp "$JSON_JAR" -d "$TMP/classes" "$HERE/AdaptiveActivityDoubles.java" "$TMP/AdaptiveActivityHarness.java" "$SRC/AdaptiveQuality.java" "$SRC/MediaQuality.java" "$SRC/PlaybackPlaylist.java" "$HERE/SeasonPlaylistActivityTest.java"
"$JAVA_HOME/bin/java" -cp "$TMP/classes:$JSON_JAR" io.github.jackotom.hongguo.android.SeasonPlaylistActivityTest
