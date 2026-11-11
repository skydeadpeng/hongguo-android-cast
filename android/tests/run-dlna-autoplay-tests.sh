#!/bin/bash
set -euo pipefail
TEST_ROOT="$(cd "$(dirname "$0")" && pwd)"
SOURCE_ROOT="$(cd "$TEST_ROOT/../.." && pwd)"
while [[ $# -gt 0 ]]; do case "$1" in --source-root) SOURCE_ROOT="$2"; shift 2;; *) echo 'USAGE run-dlna-autoplay-tests.sh [--source-root PROJECT]' >&2;exit 64;; esac;done
JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
SRC="$SOURCE_ROOT/android/app/src/main/java/io/github/jackotom/hongguo/android"
TMP="$(mktemp -d -t hongguo-dlna-autoplay.XXXXXX)"
trap 'python3 -c "import shutil,sys;shutil.rmtree(sys.argv[1])" "$TMP"' EXIT
mkdir -p "$TMP/classes"
"$JAVA_HOME/bin/javac" -encoding UTF-8 -source 17 -target 17 -d "$TMP/classes" "$SRC/MediaQuality.java" "$SRC/DlnaSession.java" "$SRC/DlnaMediaServer.java" "$SRC/PlaybackCachePolicy.java" "$TEST_ROOT/DlnaSessionTest.java" "$TEST_ROOT/DlnaIntegrationTest.java" "$TEST_ROOT/DlnaAutoplayCompatibilityTest.java"
"$JAVA_HOME/bin/java" -cp "$TMP/classes" io.github.jackotom.hongguo.android.DlnaAutoplayCompatibilityTest
