#!/bin/bash
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
SRC="$HERE/../app/src/main/java/io/github/jackotom/hongguo/android"
JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
SDK_JAR="${SDK_JAR:-${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}/platforms/android-35/android.jar}"
TMP="$(mktemp -d -t hongguo-hls.XXXXXX)"
trap 'rm -rf "$TMP"' EXIT
"$JAVA_HOME/bin/javac" -encoding UTF-8 -source 17 -target 17 -cp "$SDK_JAR" -d "$TMP" "$SRC/LegacyFiles.java" "$SRC/HuangguoHls.java" "$SRC/DlnaSessionFile.java" "$HERE/HuangguoHlsTest.java"
"$JAVA_HOME/bin/java" -cp "$TMP:$SDK_JAR" io.github.jackotom.hongguo.android.HuangguoHlsTest
