#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
TMP="$(mktemp -d -t official-library-sdk35.XXXXXX)";trap 'rm -rf "$TMP"' EXIT
SRC="$ROOT/android/app/src/main/java"
"$JAVA_HOME/bin/javac" --release 17 -encoding UTF-8 -sourcepath "$SRC" -cp "${ANDROID_HOME:-$HOME/Library/Android/sdk}/platforms/android-35/android.jar" -d "$TMP/classes" "$SRC/io/github/jackotom/hongguo/android/OfficialLibrary.java" "$SRC/io/github/jackotom/hongguo/android/LibraryStore.java"
echo 'PASS OfficialLibrary actual account protocol SDK35 compile release=17'
