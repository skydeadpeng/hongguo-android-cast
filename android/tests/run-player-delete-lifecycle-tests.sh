#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SOURCE_ROOT="${HONGGUO_TEST_SOURCE_ROOT:-$ROOT}"
JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
python3 "$ROOT/tests/player-delete-lifecycle-harness.py" "$SOURCE_ROOT/app/src/main/java/io/github/jackotom/hongguo/android/PlayerActivity.java" "$TMP/PlayerDeleteLifecycleHarness.java"
"$JAVA_HOME/bin/javac" --release 17 -d "$TMP/classes" "$TMP/PlayerDeleteLifecycleHarness.java"
"$JAVA_HOME/bin/java" -cp "$TMP/classes" PlayerDeleteLifecycleHarness
