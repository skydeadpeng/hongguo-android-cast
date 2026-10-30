#!/bin/bash
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
SOURCE_ROOT="$(cd "$HERE/../.." && pwd)"
while [[ $# -gt 0 ]]; do case "$1" in --source-root) SOURCE_ROOT="$2";shift 2;; *) echo 'USAGE run-player-cast-cache-guards-tests.sh [--source-root PROJECT]' >&2;exit 64;;esac;done
JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
JSON_JAR="${JSON_JAR:-$HOME/.m2/repository/com/vaadin/external/google/android-json/0.0.20131108.vaadin1/android-json-0.0.20131108.vaadin1.jar}"
TMP="$(mktemp -d -t hongguo-player-cast-cache.XXXXXX)"
trap 'python3 -c "import shutil,sys;shutil.rmtree(sys.argv[1])" "$TMP"' EXIT
python3 "$HERE/player-cast-cache-guards-harness.py" "$SOURCE_ROOT/android/app/src/main/java/io/github/jackotom/hongguo/android/PlayerActivity.java" "$TMP/PlayerCastCacheGuardsHarness.java"
EXTRA=();if [[ -f "$SOURCE_ROOT/android/app/src/main/java/io/github/jackotom/hongguo/android/PlaybackPlaylist.java" ]];then EXTRA+=("$SOURCE_ROOT/android/app/src/main/java/io/github/jackotom/hongguo/android/PlaybackPlaylist.java");fi
if [[ -f "$SOURCE_ROOT/android/app/src/main/java/io/github/jackotom/hongguo/android/AdaptiveQuality.java" ]];then EXTRA+=("$SOURCE_ROOT/android/app/src/main/java/io/github/jackotom/hongguo/android/AdaptiveQuality.java" "$SOURCE_ROOT/android/app/src/main/java/io/github/jackotom/hongguo/android/MediaQuality.java");fi
"$JAVA_HOME/bin/javac" --release 17 -encoding UTF-8 -cp "$JSON_JAR" -d "$TMP/classes" "$TMP/PlayerCastCacheGuardsHarness.java" "$HERE/PlayerCastCacheGuardsTest.java" "${EXTRA[@]}"
"$JAVA_HOME/bin/java" -cp "$TMP/classes:$JSON_JAR" io.github.jackotom.hongguo.android.PlayerCastCacheGuardsTest
