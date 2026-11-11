#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SRC="$ROOT/android/app/src/main/java/io/github/jackotom/hongguo/android"
TMP=$(mktemp -d -t hongguo-updater-compile.XXXXXX); trap 'rm -rf "$TMP"' EXIT
cat > "$TMP/Stubs.java" <<'JAVA'
package io.github.jackotom.hongguo.android;
class MainActivity extends android.app.Activity { boolean trusted(){return true;} void script(String source){} }
class BuildConfig { static final boolean IS_TV=false,NATIVE_UI=false,DEBUG=false; static final String VERSION_NAME="0.12.11"; }
class UpdateInstallCheckpoint { static void prepare(android.content.Context c,Object owner) throws Exception {} static void release(Object owner){} }
JAVA
"${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}/bin/javac" --release 17 -encoding UTF-8 -cp "${ANDROID_HOME:-$HOME/Library/Android/sdk}/platforms/android-35/android.jar" -d "$TMP/classes" "$TMP/Stubs.java" "$SRC/UpdatePolicy.java" "$SRC/UpdateIntegrity.java" "$SRC/AppUpdater.java" "$SRC/UpdateProvider.java"
echo 'Updater Android API35 compile PASS'
