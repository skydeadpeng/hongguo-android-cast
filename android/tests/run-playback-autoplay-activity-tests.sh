#!/bin/bash
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
SOURCE_ROOT="$(cd "$HERE/../.." && pwd)"
if [[ ${1:-} == --source-root ]];then SOURCE_ROOT="$2";shift 2;fi
[[ $# == 0 ]] || exit 2
JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
JSON_JAR="${JSON_JAR:-$HOME/.m2/repository/com/vaadin/external/google/android-json/0.0.20131108.vaadin1/android-json-0.0.20131108.vaadin1.jar}"
TMP="$(mktemp -d)";trap 'rm -rf "$TMP"' EXIT
SRC="$SOURCE_ROOT/android/app/src/main/java/io/github/jackotom/hongguo/android"
python3 "$HERE/adaptive-activity-harness.py" "$SRC/PlayerActivity.java" "$TMP/AdaptiveActivityHarness.java"
"$JAVA_HOME/bin/javac" --release 17 -encoding UTF-8 -cp "$JSON_JAR" -d "$TMP/classes" "$TMP/AdaptiveActivityHarness.java" "$HERE/AdaptiveActivityDoubles.java" "$SRC/MediaQuality.java" "$SRC/AdaptiveQuality.java" "$SRC/PlaybackPlaylist.java" "$HERE/PlaybackAutoplayActivityTest.java"
"$JAVA_HOME/bin/java" -cp "$TMP/classes:$JSON_JAR" io.github.jackotom.hongguo.android.PlaybackAutoplayActivityTest
