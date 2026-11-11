#!/bin/bash
set -euo pipefail
TEST_ROOT="$(cd "$(dirname "$0")" && pwd)"
SOURCE_ROOT="$(cd "$TEST_ROOT/../.." && pwd)"
while [ "$#" -gt 0 ];do
 case "$1" in --source-root) SOURCE_ROOT="$2";shift 2;; *) echo 'USAGE run-dlna-control-endpoint-diagnostics-tests.sh [--source-root PROJECT]' >&2;exit 64;;esac
done
JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
TMP="$(mktemp -d -t hongguo-control-endpoint-diagnostics.XXXXXX)"
trap 'python3 -c '\''import shutil,sys;shutil.rmtree(sys.argv[1])'\'' "$TMP"' EXIT
SRC="$SOURCE_ROOT/android/app/src/main/java/io/github/jackotom/hongguo/android"
"$JAVA_HOME/bin/javac" --release 17 -encoding UTF-8 -d "$TMP/classes" "$SRC/DlnaDevice.java" "$SRC/DlnaControl.java" "$SRC/DlnaNetwork.java" "$SRC/DlnaXml.java" "$TEST_ROOT/DlnaControlEndpointDiagnosticsTest.java"
"$JAVA_HOME/bin/java" -cp "$TMP/classes" io.github.jackotom.hongguo.android.DlnaControlEndpointDiagnosticsTest
