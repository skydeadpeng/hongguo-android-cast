#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
"$JAVA_HOME/bin/javac" --release 17 -d "$TMP" "$ROOT/app/src/main/java/io/github/jackotom/hongguo/android/UpdateOperationGate.java" "$ROOT/tests/UpdateOperationGateTest.java"
"$JAVA_HOME/bin/java" -cp "$TMP" io.github.jackotom.hongguo.android.UpdateOperationGateTest
