#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SRC="$ROOT/android/app/src/main/java/io/github/jackotom/hongguo/android"
TMP="$(mktemp -d -t hongguo-download-delete-compile.XXXXXX)";trap 'rm -rf "$TMP"' EXIT
cat > "$TMP/BuildConfig.java" <<'JAVA'
package io.github.jackotom.hongguo.android;
final class BuildConfig {static final boolean IS_TV=false,DEBUG=false;static final String VERSION_NAME="0.12.14";}
JAVA
find "$SRC" -name '*.java' -print0 | xargs -0 "${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}/bin/javac" --release 17 -encoding UTF-8 -cp "${ANDROID_HOME:-$HOME/Library/Android/sdk}/platforms/android-35/android.jar" -d "$TMP/classes" "$TMP/BuildConfig.java"
echo "PASS DownloadDeletion all Android production sources SDK35 files=$(find "$SRC" -name '*.java' | wc -l | tr -d ' ') release=17 BuildConfigOnlyStub=true"
