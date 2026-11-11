#!/bin/bash
# Execute production/parser/cache/queue tests on real Android libcore and org.json.
# This uses independent shell app_process instances, never launches or stops the app.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ADB="${ADB:-$ANDROID_HOME/platform-tools/adb}"
ADB_PORT="${ADB_PORT:-5038}"
ANDROID_SERIAL="${ANDROID_SERIAL:-emulator-5582}"
SDK_JAR="$ANDROID_HOME/platforms/android-35/android.jar"
D8="${D8:-$ANDROID_HOME/build-tools/35.0.0/d8}"
SRC="$ROOT/app/src/main/java/io/github/jackotom/hongguo/android"
TEST="$ROOT/tests"
TMP="$(mktemp -d -t hongguo-android-runtime.XXXXXX)"
REMOTE="/data/local/tmp/$(basename "$TMP").jar"
PUSHED=0
cleanup() {
  if [[ "$PUSHED" == 1 ]]; then "$ADB" -P "$ADB_PORT" -s "$ANDROID_SERIAL" shell "rm -f '$REMOTE'" >/dev/null 2>&1 || true; fi
  /bin/rm -rf "$TMP"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
[[ -x "$JAVA_HOME/bin/javac" && -x "$D8" && -x "$ADB" && -f "$SDK_JAR" ]] || { echo 'ANDROID_RUNTIME_DEPENDENCY_MISSING require JDK, SDK35, d8 and adb'; exit 2; }
[[ "$ANDROID_SERIAL" =~ ^[a-zA-Z0-9._:-]+$ && "$ADB_PORT" =~ ^[0-9]+$ ]] || { echo 'ANDROID_RUNTIME_DEVICE_ARGUMENT_INVALID'; exit 2; }
[[ "$("$ADB" -P "$ADB_PORT" -s "$ANDROID_SERIAL" get-state)" == device ]] || { echo 'ANDROID_RUNTIME_DEVICE_NOT_READY'; exit 2; }
API="$("$ADB" -P "$ADB_PORT" -s "$ANDROID_SERIAL" shell getprop ro.build.version.sdk | tr -d '\r')"
echo "ANDROID_RUNTIME_START serial=$ANDROID_SERIAL port=$ADB_PORT api=$API foreground=untouched networkRequests=0"
mkdir -p "$TMP/classes" "$TMP/dex"
"$JAVA_HOME/bin/javac" -encoding UTF-8 -source 17 -target 17 -cp "$SDK_JAR" -d "$TMP/classes" \
  "$SRC/EpisodeResolver.java" "$SRC/NetworkGuard.java" "$SRC/PlaybackCachePolicy.java" "$SRC/LibraryStore.java" \
  "$TEST/EpisodeResolverTest.java" "$TEST/NetworkGuardTest.java" "$TEST/PlaybackCachePolicyTest.java" "$TEST/PlaybackQueueTest.java"
"$JAVA_HOME/bin/jar" --create --file "$TMP/input.jar" -C "$TMP/classes" .
JAVA_HOME="$JAVA_HOME" "$D8" --lib "$SDK_JAR" --min-api 26 --output "$TMP/dex" "$TMP/input.jar"
"$JAVA_HOME/bin/jar" --create --file "$TMP/runtime-tests.jar" -C "$TMP/dex" .
PUSHED=1
"$ADB" -P "$ADB_PORT" -s "$ANDROID_SERIAL" push "$TMP/runtime-tests.jar" "$REMOTE" >/dev/null
PASSED=0
FAILED=0
for TEST_CLASS in EpisodeResolverTest NetworkGuardTest PlaybackCachePolicyTest PlaybackQueueTest; do
  echo "ANDROID_RUNTIME_TEST class=$TEST_CLASS"
  if "$ADB" -P "$ADB_PORT" -s "$ANDROID_SERIAL" shell "CLASSPATH='$REMOTE' app_process -Djava.io.tmpdir=/data/local/tmp /system/bin io.github.jackotom.hongguo.android.$TEST_CLASS"; then
    PASSED=$((PASSED + 1))
  else
    STATUS=$?
    FAILED=$((FAILED + 1))
    echo "ANDROID_RUNTIME_TEST_FAILED class=$TEST_CLASS exit=$STATUS"
  fi
done
echo "ANDROID_RUNTIME_RESULT passed=$PASSED failed=$FAILED serial=$ANDROID_SERIAL api=$API foreground=untouched networkRequests=0"
[[ "$FAILED" == 0 ]]
