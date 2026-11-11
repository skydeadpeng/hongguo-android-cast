#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
TMP="$(mktemp -d -t hongguo-cenc.XXXXXX)"
trap 'rm -rf "$TMP"' EXIT
SRC="$ROOT/app/src/main/java/io/github/jackotom/hongguo/android/CencDecryptor.java"
"$JAVA_HOME/bin/javac" -encoding UTF-8 -d "$TMP/classes" "$SRC" "$ROOT/tests/CencDecryptorTest.java"
"$JAVA_HOME/bin/java" -cp "$TMP/classes" io.github.jackotom.hongguo.android.CencDecryptorTest
"$JAVA_HOME/bin/javac" -encoding UTF-8 -source 17 -target 17 -cp "$ANDROID_HOME/platforms/android-35/android.jar" -d "$TMP/sdk35" "$SRC" "$ROOT/tests/CencDecryptorTest.java"
echo 'PASS CencDecryptor SDK35 compile source=17'
if [[ "$#" -gt 0 ]]; then
  "$JAVA_HOME/bin/java" -cp "$TMP/classes" io.github.jackotom.hongguo.android.CencDecryptorTest "$@"
fi
