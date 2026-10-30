#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
TMP="$(mktemp -d -t hongguo-update-policy.XXXXXX)"
trap 'rm -rf "$TMP"' EXIT
"$JAVA_HOME/bin/javac" --release 17 -encoding UTF-8 -d "$TMP" "$ROOT/android/app/src/main/java/io/github/jackotom/hongguo/android/UpdatePolicy.java" "$ROOT/android/tests/UpdatePolicyTest.java" "$ROOT/android/app/src/main/java/io/github/jackotom/hongguo/android/UpdateIntegrity.java" "$ROOT/android/tests/UpdateIntegrityTest.java"
"$JAVA_HOME/bin/java" -cp "$TMP" io.github.jackotom.hongguo.android.UpdatePolicyTest

"$JAVA_HOME/bin/java" -cp "$TMP" io.github.jackotom.hongguo.android.UpdateIntegrityTest
