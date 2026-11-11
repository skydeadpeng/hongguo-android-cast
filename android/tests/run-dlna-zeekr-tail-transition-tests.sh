#!/bin/bash
set -euo pipefail
TEST_ROOT="$(cd "$(dirname "$0")" && pwd)"
SOURCE_ROOT="$(cd "$TEST_ROOT/../.." && pwd)"
while [[ $# -gt 0 ]]; do
    case "$1" in
        --source-root) SOURCE_ROOT="$2"; shift 2 ;;
        *) echo 'USAGE run-dlna-zeekr-tail-transition-tests.sh [--source-root PROJECT]' >&2; exit 64 ;;
    esac
done
JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
SRC="$SOURCE_ROOT/android/app/src/main/java/io/github/jackotom/hongguo/android"
TMP="$(mktemp -d -t hongguo-dlna-zeekr-tail.XXXXXX)"
trap 'python3 -c "import shutil,sys;shutil.rmtree(sys.argv[1])" "$TMP"' EXIT
"$JAVA_HOME/bin/javac" --release 17 -encoding UTF-8 -d "$TMP/classes" \
    "$SRC/MediaQuality.java" "$SRC/DlnaSession.java" \
    "$TEST_ROOT/DlnaSessionTest.java" "$TEST_ROOT/DlnaZeekrTailTransitionTest.java"
"$JAVA_HOME/bin/java" -cp "$TMP/classes" io.github.jackotom.hongguo.android.DlnaZeekrTailTransitionTest
