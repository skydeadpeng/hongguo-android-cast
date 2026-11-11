#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SOURCE_ROOT="${1:-$ROOT}"
JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
TMP="$(mktemp -d)";trap 'rm -rf "$TMP"' EXIT
SRC="$SOURCE_ROOT/android/app/src/main/java/io/github/jackotom/hongguo/android"
"$JAVA_HOME/bin/javac" --release 17 -d "$TMP/classes" "$SRC/MediaQuality.java" "$SRC/AdaptiveQuality.java" "$ROOT/android/tests/AdaptiveRecoveryQualityTest.java"
"$JAVA_HOME/bin/java" -cp "$TMP/classes" io.github.jackotom.hongguo.android.AdaptiveRecoveryQualityTest
