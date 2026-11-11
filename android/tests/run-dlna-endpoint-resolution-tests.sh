#!/bin/bash
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
while [[ $# -gt 0 ]];do
 case "$1" in --source-root) ROOT="$2";shift 2;; --) shift;break;; *) echo 'USAGE run-dlna-endpoint-resolution-tests.sh [--source-root PROJECT] [-- DESCRIPTION LOCATION PEER EXPECTED_CONTROL]' >&2;exit 64;;esac
done
JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
TMP="$(mktemp -d -t hongguo-dlna-endpoint.XXXXXX)"
trap 'python3 -c '\''import shutil,sys;shutil.rmtree(sys.argv[1])'\'' "$TMP"' EXIT
SRC="$ROOT/android/app/src/main/java/io/github/jackotom/hongguo/android"
"$JAVA_HOME/bin/javac" -encoding UTF-8 -source 8 -target 8 -d "$TMP/classes" "$SRC/DlnaNetwork.java" "$SRC/DlnaDiscovery.java" "$SRC/DlnaDevice.java" "$SRC/DlnaXml.java" "$HERE/DlnaEndpointResolutionTest.java"
"$JAVA_HOME/bin/java" -cp "$TMP/classes" io.github.jackotom.hongguo.android.DlnaEndpointResolutionTest "$@"
