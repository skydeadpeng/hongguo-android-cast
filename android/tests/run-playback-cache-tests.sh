#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
"$JAVA_HOME/bin/javac" -encoding UTF-8 -d "$TMP" \
  "$ROOT/app/src/main/java/io/github/jackotom/hongguo/android/NetworkGuard.java" \
  "$ROOT/app/src/main/java/io/github/jackotom/hongguo/android/PlaybackCachePolicy.java" \
  "$ROOT/tests/PlaybackCachePolicyTest.java"
"$JAVA_HOME/bin/java" -cp "$TMP" io.github.jackotom.hongguo.android.PlaybackCachePolicyTest
