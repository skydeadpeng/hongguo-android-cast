#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
python3 "$ROOT/tests/update-checkpoint-harness.py" "$TMP/stubs"
SRC="$ROOT/app/src/main/java/io/github/jackotom/hongguo/android"
"$JAVA_HOME/bin/javac" --release 17 -d "$TMP/classes" $(find "$TMP/stubs" -name '*.java') "$SRC/UpdateOperationGate.java" "$SRC/UpdateInstallCheckpoint.java" "$ROOT/tests/UpdateInstallCheckpointTest.java"
"$JAVA_HOME/bin/java" -cp "$TMP/classes" io.github.jackotom.hongguo.android.UpdateInstallCheckpointTest
