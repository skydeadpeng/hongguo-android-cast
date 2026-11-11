#!/bin/bash
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
SOURCE_ROOT="$(cd "$HERE/../.." && pwd)"
while [[ $# -gt 0 ]];do case "$1" in --source-root) SOURCE_ROOT="$2";shift 2;; *) echo 'Usage: run-cast-playlist-source-tests.sh [--source-root PROJECT]' >&2;exit 64;; esac;done
JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
JSON_JAR="${JSON_JAR:-$HOME/.m2/repository/com/vaadin/external/google/android-json/0.0.20131108.vaadin1/android-json-0.0.20131108.vaadin1.jar}"
SRC="$SOURCE_ROOT/android/app/src/main/java/io/github/jackotom/hongguo/android"
TMP="$(mktemp -d -t hongguo-cast-playlist.XXXXXX)"
trap 'rm -rf "$TMP"' EXIT
MERGED=("$SRC/MediaQuality.java"); if [[ -f "$SRC/DlnaMergedLease.java" ]]; then MERGED+=("$SRC/DlnaMergedLease.java"); fi
python3 "$HERE/cast-playlist-source-harness.py" "$SRC" "$TMP/generated"
"$JAVA_HOME/bin/javac" --release 17 -encoding UTF-8 -cp "$JSON_JAR" -d "$TMP/classes" \
 "$TMP/generated/android/content/Context.java" "$TMP/generated/android/content/SharedPreferences.java" "$TMP/generated/android/os/Environment.java" \
 "$SRC/PlaybackPlaylist.java" "$SRC/PlaybackCachePolicy.java" "$SRC/PlaybackCache.java" "$SRC/LibraryStore.java" \
 "${MERGED[@]}" "$SRC/AdaptiveQuality.java" "$SRC/DlnaSession.java" "$SRC/Mp4Concat.java" "$SRC/DlnaSessionFile.java" "$SRC/DlnaPrefetch.java" \
 "$SRC/DlnaNetwork.java" "$SRC/DlnaDevice.java" "$SRC/BrowserCastSession.java" \
 "$TMP/generated/DlnaPlaylistSourceHarness.java" "$TMP/generated/BrowserPlaylistSourceHarness.java" \
 "$HERE/DlnaSessionTest.java" "$HERE/BrowserCastSessionTest.java" "$HERE/CastPlaylistSourceTest.java"
"$JAVA_HOME/bin/java" -cp "$TMP/classes:$JSON_JAR" io.github.jackotom.hongguo.android.CastPlaylistSourceTest
