#!/bin/bash
set -euo pipefail
TEST_ROOT="$(cd "$(dirname "$0")" && pwd)"
SOURCE_ROOT="$(cd "$TEST_ROOT/../.." && pwd)"
while [[ $# -gt 0 ]]; do
    case "$1" in
        --source-root) SOURCE_ROOT="$2"; shift 2;;
        *) echo 'USAGE run-startup-native-tests.sh [--source-root PROJECT]' >&2; exit 64;;
    esac
done
JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
# Runtime JSON is the same offline Android-compatible dependency as the other native gates.
JSON_JAR="${JSON_JAR:-$HOME/.m2/repository/com/vaadin/external/google/android-json/0.0.20131108.vaadin1/android-json-0.0.20131108.vaadin1.jar}"
[[ -f "$JSON_JAR" ]] || { echo 'TEST_DEPENDENCY_MISSING set JSON_JAR'; exit 2; }
SRC="$SOURCE_ROOT/android/app/src/main/java/io/github/jackotom/hongguo/android"
TMP="$(mktemp -d -t hongguo-startup-native.XXXXXX)"
trap 'python3 -c "import shutil,sys;shutil.rmtree(sys.argv[1])" "$TMP"' EXIT
mkdir -p "$TMP/sdk35" "$TMP/classes" "$TMP/io/github/jackotom/hongguo/android"
printf '%s\n' 'package io.github.jackotom.hongguo.android; public final class BuildConfig {public static final boolean IS_TV=true,DEBUG=true;public static final String VERSION_NAME="fixture";}' > "$TMP/io/github/jackotom/hongguo/android/BuildConfig.java"
"$JAVA_HOME/bin/javac" -encoding UTF-8 -source 17 -target 17 -cp "$ANDROID_HOME/platforms/android-35/android.jar" -d "$TMP/sdk35" "$TMP/io/github/jackotom/hongguo/android/BuildConfig.java" "$SRC/"*.java
echo 'PASS StartupNative SDK35 allProductionCompile=true deviceUntouched=true'
python3 "$TEST_ROOT/startup-main-harness.py" "$SRC" "$TMP/MainActivity.java"
SOURCES=("$TMP/MainActivity.java" "$TEST_ROOT/StartupNativeDoubles.java" "$SRC/MobileLayoutPolicy.java")
if [[ -f "$SRC/StartupPolicy.java" ]]; then
    SOURCES+=("$SRC/StartupPolicy.java" "$TEST_ROOT/StartupNativeTest.java")
    TEST_CLASS=StartupNativeTest
else
    SOURCES+=("$TEST_ROOT/StartupNativeBaselineTest.java")
    TEST_CLASS=StartupNativeBaselineTest
fi
"$JAVA_HOME/bin/javac" -encoding UTF-8 -source 17 -target 17 -cp "$JSON_JAR" -d "$TMP/classes" "${SOURCES[@]}"
"$JAVA_HOME/bin/java" -cp "$TMP/classes:$JSON_JAR" "io.github.jackotom.hongguo.android.$TEST_CLASS"
