#!/bin/bash
set -euo pipefail
TEST_ROOT="$(cd "$(dirname "$0")" && pwd)"
SOURCE_ROOT="$(cd "$TEST_ROOT/../.." && pwd)"
while [ "$#" -gt 0 ]; do
 case "$1" in
  --source-root) [ "$#" -ge 2 ] || exit 2; SOURCE_ROOT="$2"; shift 2;;
  *) echo 'USAGE run-media-redirect-recovery-tests.sh [--source-root PROJECT]' >&2; exit 2;;
 esac
done
JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
JSON_JAR="${JSON_JAR:-$HOME/.m2/repository/com/vaadin/external/google/android-json/0.0.20131108.vaadin1/android-json-0.0.20131108.vaadin1.jar}"
TMP="$(mktemp -d -t hongguo-media-redirect.XXXXXX)"
trap 'rm -rf "$TMP"' EXIT
SRC="$SOURCE_ROOT/android/app/src/main/java/io/github/jackotom/hongguo/android"
python3 "$TEST_ROOT/media-redirect-recovery-harness.py" "$SRC/NetworkGuard.java" "$TMP/NetworkGuard.java"
"$JAVA_HOME/bin/javac" -encoding UTF-8 -cp "$JSON_JAR" -d "$TMP/classes" \
 "$TMP/NetworkGuard.java" "$SRC/MediaQuality.java" "$SRC/SpadeDecoder.java" "$SRC/DanmakuSigner.java" "$SRC/AppMediaSource.java" "$SRC/PlaybackSources.java" "$SRC/PlaybackCachePolicy.java" "$SRC/AdaptiveQuality.java" \
 "$TEST_ROOT/MediaRedirectRecoveryTest.java"
"$JAVA_HOME/bin/java" -cp "$TMP/classes:$JSON_JAR" io.github.jackotom.hongguo.android.MediaRedirectRecoveryTest
