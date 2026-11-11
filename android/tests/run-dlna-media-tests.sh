#!/bin/bash
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
SOURCE_ROOT="$(cd "$HERE/../.." && pwd)"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --source-root) SOURCE_ROOT="$2"; shift 2 ;;
    *) echo 'Usage: run-dlna-media-tests.sh [--source-root PROJECT]' >&2; exit 64 ;;
  esac
done
JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
SOURCE="$SOURCE_ROOT/android/app/src/main/java/io/github/jackotom/hongguo/android/DlnaMediaServer.java"
TMP="$(mktemp -d -t hongguo-dlna-media.XXXXXX)"
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/classes" "$TMP/sdk35"
"$JAVA_HOME/bin/javac" -encoding UTF-8 -source 17 -target 17 -cp "$ANDROID_HOME/platforms/android-35/android.jar" -d "$TMP/sdk35" "$SOURCE"
echo 'PASS DlnaMediaServer SDK35 actualProductionCompile=true'
"$JAVA_HOME/bin/javac" -encoding UTF-8 -source 17 -target 17 -d "$TMP/classes" "$SOURCE" "$HERE/DlnaMediaServerTest.java"
"$JAVA_HOME/bin/java" -cp "$TMP/classes" io.github.jackotom.hongguo.android.DlnaMediaServerTest
