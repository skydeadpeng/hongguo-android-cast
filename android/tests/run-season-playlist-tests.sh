#!/bin/bash
set -euo pipefail
TEST_ROOT="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$TEST_ROOT/../.." && pwd)"
while [[ $# -gt 0 ]];do case "$1" in --source-root) ROOT="$2";shift 2;; *) echo 'Usage: run-season-playlist-tests.sh [--source-root PROJECT]';exit 64;;esac;done
SRC="$ROOT/android/app/src/main/java/io/github/jackotom/hongguo/android"
if [[ ! -f "$SRC/PlaybackPlaylist.java" ]];then echo 'SEASON_PLAYLIST_UNSUPPORTED currentQueue=singleSeason';exit 3;fi
JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
JSON_JAR="${JSON_JAR:-$HOME/.m2/repository/com/vaadin/external/google/android-json/0.0.20131108.vaadin1/android-json-0.0.20131108.vaadin1.jar}"
TMP="$(mktemp -d)";trap 'rm -rf "$TMP"' EXIT
"$JAVA_HOME/bin/javac" --release 17 -encoding UTF-8 -cp "$JSON_JAR" -d "$TMP" "$SRC/PlaybackPlaylist.java" "$SRC/PlaybackCachePolicy.java" "$TEST_ROOT/PlaybackPlaylistTest.java" "$TEST_ROOT/SeasonQueueCacheTest.java"
"$JAVA_HOME/bin/java" -cp "$TMP:$JSON_JAR" io.github.jackotom.hongguo.android.PlaybackPlaylistTest
"$JAVA_HOME/bin/java" -cp "$TMP:$JSON_JAR" io.github.jackotom.hongguo.android.SeasonQueueCacheTest
