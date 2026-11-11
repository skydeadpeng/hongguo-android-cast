#!/bin/bash
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
SOURCE_ROOT="$(cd "$HERE/../.." && pwd)"
while [[ $# -gt 0 ]];do case "$1" in --source-root) SOURCE_ROOT="$2";shift 2;; *) echo 'Usage: run-dlna-merged-source-tests.sh [--source-root PROJECT]' >&2;exit 64;; esac;done
JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
JSON_JAR="${JSON_JAR:-$HOME/.m2/repository/com/vaadin/external/google/android-json/0.0.20131108.vaadin1/android-json-0.0.20131108.vaadin1.jar}"
SRC="$SOURCE_ROOT/android/app/src/main/java/io/github/jackotom/hongguo/android"
TMP="$(mktemp -d -t hongguo-dlna-merged-source.XXXXXX)"
trap 'rm -rf "$TMP"' EXIT
MERGED=("$SRC/MediaQuality.java"); if [[ -f "$SRC/DlnaMergedLease.java" ]]; then MERGED+=("$SRC/DlnaMergedLease.java"); fi
python3 "$HERE/dlna-merged-source-harness.py" "$SRC" "$TMP/generated"
"$JAVA_HOME/bin/javac" --release 17 -encoding UTF-8 -cp "$JSON_JAR" -d "$TMP/classes" \
 "${MERGED[@]}" "$SRC/AdaptiveQuality.java" "$SRC/DlnaSession.java" "$SRC/Mp4Concat.java" "$SRC/DlnaSessionFile.java" \
 "$SRC/PlaybackPlaylist.java" "$SRC/DlnaMediaServer.java" "$TMP/generated/DlnaMergedSourceHarness.java" \
 "$HERE/DlnaMediaServerTest.java" "$HERE/Mp4ConcatTest.java" "$HERE/DlnaMergedSourceTest.java"
"$JAVA_HOME/bin/java" -cp "$TMP/classes:$JSON_JAR" io.github.jackotom.hongguo.android.DlnaMergedSourceTest
