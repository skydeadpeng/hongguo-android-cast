#!/bin/bash
# Shell-only app_process tests: actual Android AES provider/libcore, foreground untouched.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ADB="${ADB:-$ANDROID_HOME/platform-tools/adb}"
ADB_PORT="${ADB_PORT:-5038}"
ANDROID_SERIAL="${ANDROID_SERIAL:-emulator-5582}"
SDK_JAR="$ANDROID_HOME/platforms/android-35/android.jar"
D8="${D8:-$ANDROID_HOME/build-tools/35.0.0/d8}"
TMP="$(mktemp -d -t hongguo-cenc-android.XXXXXX)"
REMOTE="/data/local/tmp/$(basename "$TMP").jar"
PUSHED=0
cleanup() {
  if [[ "$PUSHED" == 1 ]]; then "$ADB" -P "$ADB_PORT" -s "$ANDROID_SERIAL" shell "rm -f '$REMOTE'" >/dev/null 2>&1 || true; fi
  /bin/rm -rf "$TMP"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
[[ "$ANDROID_SERIAL" =~ ^[a-zA-Z0-9._:-]+$ && "$ADB_PORT" =~ ^[0-9]+$ ]] || { echo 'ANDROID_RUNTIME_DEVICE_ARGUMENT_INVALID'; exit 2; }
mkdir -p "$TMP/classes" "$TMP/dex"
"$JAVA_HOME/bin/javac" -encoding UTF-8 -source 17 -target 17 -cp "$SDK_JAR" -d "$TMP/classes" \
  "$ROOT/app/src/main/java/io/github/jackotom/hongguo/android/CencDecryptor.java" "$ROOT/tests/CencDecryptorTest.java"
"$JAVA_HOME/bin/jar" --create --file "$TMP/input.jar" -C "$TMP/classes" .
JAVA_HOME="$JAVA_HOME" "$D8" --lib "$SDK_JAR" --min-api 26 --output "$TMP/dex" "$TMP/input.jar"
"$JAVA_HOME/bin/jar" --create --file "$TMP/runtime.jar" -C "$TMP/dex" .
PUSHED=1
"$ADB" -P "$ADB_PORT" -s "$ANDROID_SERIAL" push "$TMP/runtime.jar" "$REMOTE" >/dev/null
"$ADB" -P "$ADB_PORT" -s "$ANDROID_SERIAL" shell "CLASSPATH='$REMOTE' app_process -Xmx96m -Xms8m -Djava.io.tmpdir=/data/local/tmp /system/bin io.github.jackotom.hongguo.android.CencDecryptorTest"
echo "PASS CencDecryptor AndroidRuntime serial=$ANDROID_SERIAL foreground=untouched networkRequests=0"
