#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SOURCE_ROOT="${HONGGUO_SOURCE_ROOT:-$ROOT}"
if [[ $# -gt 0 ]]; then
  [[ $# -eq 2 && "$1" = --source-root ]] || { echo 'usage: run-media-response-tests.sh [--source-root DIRECTORY]' >&2; exit 64; }
  SOURCE_ROOT="$2"
fi
JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
TMP="$(mktemp -d -t hongguo-media-response.XXXXXX)"
trap 'rm -rf "$TMP"' EXIT
"$JAVA_HOME/bin/javac" -encoding UTF-8 -d "$TMP" "$SOURCE_ROOT/android/app/src/main/java/io/github/jackotom/hongguo/android/NetworkGuard.java" "$ROOT/android/tests/MediaResponseTest.java"
"$JAVA_HOME/bin/java" -cp "$TMP" io.github.jackotom.hongguo.android.MediaResponseTest
