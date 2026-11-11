#!/bin/bash
set -euo pipefail
TEST_ROOT="$(cd "$(dirname "$0")" && pwd)"
SOURCE_ROOT="$(cd "$TEST_ROOT/../.." && pwd)"
while [ "$#" -gt 0 ]; do
 case "$1" in --source-root) SOURCE_ROOT="$2";shift 2;; *) echo 'USAGE run-dlna-control-diagnostics-tests.sh [--source-root PROJECT]' >&2;exit 64;; esac
done
JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
TMP="$(mktemp -d -t hongguo-dlna-control-diagnostics.XXXXXX)"
trap 'python3 -c '\''import shutil,sys;shutil.rmtree(sys.argv[1])'\'' "$TMP"' EXIT
SRC="$SOURCE_ROOT/android/app/src/main/java/io/github/jackotom/hongguo/android"
"$JAVA_HOME/bin/javac" -encoding UTF-8 -source 17 -target 17 -d "$TMP/classes" "$SRC/DlnaDevice.java" "$SRC/DlnaControl.java" "$SRC/DlnaNetwork.java" "$SRC/DlnaXml.java" "$TEST_ROOT/DlnaControlDiagnosticsTest.java"
"$JAVA_HOME/bin/java" -cp "$TMP/classes" io.github.jackotom.hongguo.android.DlnaControlDiagnosticsTest
