#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SOURCE_ROOT="$ROOT"
if [[ "${1:-}" = '--source-root' ]]; then SOURCE_ROOT="$2"; shift 2; fi
[[ $# = 0 ]] || { echo 'USAGE run-app-media-source-tests.sh [--source-root PROJECT]' >&2; exit 2; }
JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
JSON_JAR="${JSON_JAR:-$HOME/.m2/repository/com/vaadin/external/google/android-json/0.0.20131108.vaadin1/android-json-0.0.20131108.vaadin1.jar}"
if [ ! -f "$JSON_JAR" ]; then
  JSON_JAR="$(find "$HOME/.gradle/caches" "$HOME/.m2/repository/org/json/json" -name 'json-*.jar' ! -name '*sources*' ! -name '*javadoc*' -print 2>/dev/null | head -1)"
fi
[ -f "$JSON_JAR" ] || { echo 'TEST_DEPENDENCY_MISSING set JSON_JAR'; exit 2; }
TMP="$(mktemp -d -t hongguo-app-media.XXXXXX)"; trap 'rm -rf "$TMP"' EXIT
SRC="$SOURCE_ROOT/android/app/src/main/java/io/github/jackotom/hongguo/android"
"$JAVA_HOME/bin/javac" --release 17 -encoding UTF-8 -cp "$JSON_JAR" -d "$TMP/classes" \
  "$SRC/SpadeDecoder.java" "$SRC/MediaQuality.java" "$SRC/DanmakuSigner.java" "$SRC/AppMediaSource.java" \
  "$ROOT/android/tests/SpadeDecoderTest.java" "$ROOT/android/tests/AppMediaSourceTest.java"
"$JAVA_HOME/bin/java" -cp "$TMP/classes:$JSON_JAR" io.github.jackotom.hongguo.android.SpadeDecoderTest
"$JAVA_HOME/bin/java" -cp "$TMP/classes:$JSON_JAR" io.github.jackotom.hongguo.android.AppMediaSourceTest
