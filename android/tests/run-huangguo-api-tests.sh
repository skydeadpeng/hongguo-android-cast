#!/usr/bin/env bash
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
SRC="$HERE/../app/src/main/java/io/github/jackotom/hongguo/android"
SDK_JAR="${SDK_JAR:-${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}/platforms/android-35/android.jar}"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
python3 "$HERE/huangguo-api-harness.py" "$SRC" "$TMP/generated"
javac -encoding UTF-8 -cp "$SDK_JAR" -d "$TMP/classes" "$TMP/generated/HuangguoApi.java" \
 "$SRC/LegacyFiles.java" "$SRC/HuangguoHls.java" "$SRC/DlnaSessionFile.java" "$HERE/HuangguoApiTest.java"
java -cp "$TMP/classes:$SDK_JAR" io.github.jackotom.hongguo.android.HuangguoApiTest
