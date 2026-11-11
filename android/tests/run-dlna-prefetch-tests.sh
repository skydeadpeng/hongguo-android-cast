#!/bin/bash
set -euo pipefail
TEST_ROOT="$(cd "$(dirname "$0")" && pwd)"
SOURCE_ROOT="$(cd "$TEST_ROOT/../.." && pwd)"
BASELINE=0
while [[ $# -gt 0 ]];do case "$1" in --source-root) SOURCE_ROOT="$2";shift 2;; --baseline) BASELINE=1;shift;; *) echo 'USAGE run-dlna-prefetch-tests.sh [--source-root PROJECT] [--baseline]' >&2;exit 64;;esac;done
JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
SRC="$SOURCE_ROOT/android/app/src/main/java/io/github/jackotom/hongguo/android"
if [[ "$BASELINE" == 0 && ! -f "$SRC/DlnaPrefetch.java" ]];then echo 'DLNA_PREFETCH_UNSUPPORTED reason=production-coordinator-missing ahead=0';exit 3;fi
TMP="$(mktemp -d -t hongguo-dlna-prefetch.XXXXXX)"
trap 'python3 -c "import shutil,sys;shutil.rmtree(sys.argv[1])" "$TMP"' EXIT
if [[ "$BASELINE" == 1 ]];then
    "$JAVA_HOME/bin/javac" --release 17 -encoding UTF-8 -d "$TMP/classes" "$SRC/MediaQuality.java" "$SRC/DlnaSession.java" "$TEST_ROOT/DlnaSessionTest.java" "$TEST_ROOT/DlnaPrefetchBaselineTest.java"
    "$JAVA_HOME/bin/java" -cp "$TMP/classes" io.github.jackotom.hongguo.android.DlnaPrefetchBaselineTest
    exit 0
fi
"$JAVA_HOME/bin/javac" --release 17 -encoding UTF-8 -d "$TMP/classes" "$SRC/MediaQuality.java" "$SRC/AdaptiveQuality.java" "$SRC/DlnaSession.java" "$SRC/DlnaPrefetch.java" "$SRC/PlaybackCachePolicy.java" "$TEST_ROOT/DlnaSessionTest.java" "$TEST_ROOT/DlnaPrefetchTest.java"
"$JAVA_HOME/bin/java" -cp "$TMP/classes" io.github.jackotom.hongguo.android.DlnaPrefetchTest
