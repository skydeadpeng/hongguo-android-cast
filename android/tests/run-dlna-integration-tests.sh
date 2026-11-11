#!/bin/bash
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
SOURCE_ROOT="$(cd "$HERE/../.." && pwd)"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --source-root) SOURCE_ROOT="$2"; shift 2 ;;
    *) echo 'Usage: run-dlna-integration-tests.sh [--source-root PROJECT]' >&2; exit 64 ;;
  esac
done
JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
SRC="$SOURCE_ROOT/android/app/src/main/java/io/github/jackotom/hongguo/android"
for file in DlnaSession.java DlnaMediaServer.java PlaybackCachePolicy.java MediaQuality.java; do
  [[ -f "$SRC/$file" ]] || { echo "DLNA_INTEGRATION_FAILED missingSource=$file"; exit 1; }
done
TMP="$(mktemp -d -t hongguo-dlna-integration.XXXXXX)"
trap 'rm -rf "$TMP"' EXIT
"$JAVA_HOME/bin/javac" -encoding UTF-8 -source 17 -target 17 -d "$TMP" "$SRC/DlnaSession.java" "$SRC/DlnaMediaServer.java" "$SRC/PlaybackCachePolicy.java" "$SRC/MediaQuality.java" "$HERE/DlnaIntegrationTest.java"
"$JAVA_HOME/bin/java" -cp "$TMP" io.github.jackotom.hongguo.android.DlnaIntegrationTest
