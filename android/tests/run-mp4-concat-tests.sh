#!/bin/bash
set -euo pipefail
TEST_ROOT="$(cd "$(dirname "$0")" && pwd)"
SOURCE_ROOT="$(cd "$TEST_ROOT/../.." && pwd)"
JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
SRC="$SOURCE_ROOT/android/app/src/main/java/io/github/jackotom/hongguo/android"
TMP="$(mktemp -d -t hongguo-mp4-concat.XXXXXX)"
trap 'python3 -c "import shutil,sys;shutil.rmtree(sys.argv[1])" "$TMP"' EXIT
"$JAVA_HOME/bin/javac" --release 17 -encoding UTF-8 -d "$TMP/classes" \
  "$SRC/Mp4Concat.java" "$SRC/DlnaSessionFile.java" "$TEST_ROOT/Mp4ConcatTest.java"
"$JAVA_HOME/bin/java" -cp "$TMP/classes" io.github.jackotom.hongguo.android.Mp4ConcatTest
