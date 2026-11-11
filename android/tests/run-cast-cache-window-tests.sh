#!/bin/bash
set -euo pipefail
TEST_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
ROOT="${1:-$TEST_ROOT}"
JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
"$JAVA_HOME/bin/javac" -encoding UTF-8 -d "$TMP" \
  "$ROOT/android/app/src/main/java/io/github/jackotom/hongguo/android/PlaybackCachePolicy.java" \
  "$TEST_ROOT/android/tests/CastCacheWindowTest.java"
"$JAVA_HOME/bin/java" -cp "$TMP" io.github.jackotom.hongguo.android.CastCacheWindowTest
