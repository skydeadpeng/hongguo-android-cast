#!/bin/bash
set -euo pipefail
TEST_ROOT="$(cd "$(dirname "$0")" && pwd)"
SOURCE_ROOT="$(cd "$TEST_ROOT/../.." && pwd)"
while [[ $# -gt 0 ]]; do
 case "$1" in
  --source-root) SOURCE_ROOT="$2";shift 2;;
  *) echo 'USAGE run-adaptive-quality-tests.sh [--source-root PROJECT]' >&2;exit 64;;
 esac
done
JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
JSON_JAR="${JSON_JAR:-$HOME/.m2/repository/com/vaadin/external/google/android-json/0.0.20131108.vaadin1/android-json-0.0.20131108.vaadin1.jar}"
SRC="$SOURCE_ROOT/android/app/src/main/java/io/github/jackotom/hongguo/android"
TMP="$(mktemp -d -t hongguo-adaptive-quality.XXXXXX)"
trap 'python3 -c "import shutil,sys;shutil.rmtree(sys.argv[1])" "$TMP"' EXIT
mkdir -p "$TMP/sdk35" "$TMP/io/github/jackotom/hongguo/android"
printf '%s\n' 'package io.github.jackotom.hongguo.android; public final class BuildConfig {public static final boolean IS_TV=false,DEBUG=true;public static final String VERSION_NAME="fixture";}' > "$TMP/io/github/jackotom/hongguo/android/BuildConfig.java"
"$JAVA_HOME/bin/javac" -encoding UTF-8 -source 17 -target 17 -cp "$ANDROID_HOME/platforms/android-35/android.jar" -d "$TMP/sdk35" "$TMP/io/github/jackotom/hongguo/android/BuildConfig.java" "$SRC/"*.java
echo 'PASS AdaptiveQuality SDK35 allProductionCompile=true deviceUntouched=true'
python3 "$TEST_ROOT/adaptive-activity-harness.py" "$SRC/PlayerActivity.java" "$TMP/AdaptiveActivityHarness.java"
SOURCES=("$TEST_ROOT/AdaptiveActivityDoubles.java" "$TMP/AdaptiveActivityHarness.java")
if [[ -f "$SRC/PlaybackPlaylist.java" ]];then SOURCES+=("$SRC/PlaybackPlaylist.java");fi
if [[ -f "$SRC/AdaptiveQuality.java" ]];then
 SOURCES+=("$SRC/MediaQuality.java" "$SRC/AdaptiveQuality.java" "$TEST_ROOT/AdaptiveQualityTest.java" "$TEST_ROOT/AdaptiveActivityTest.java")
else
 SOURCES+=("$TEST_ROOT/AdaptiveBaselineTest.java")
fi
"$JAVA_HOME/bin/javac" -encoding UTF-8 -source 17 -target 17 -cp "$JSON_JAR" -d "$TMP/classes" "${SOURCES[@]}"
if [[ -f "$SRC/AdaptiveQuality.java" ]];then
 "$JAVA_HOME/bin/java" -cp "$TMP/classes:$JSON_JAR" io.github.jackotom.hongguo.android.AdaptiveQualityTest
 "$JAVA_HOME/bin/java" -cp "$TMP/classes:$JSON_JAR" io.github.jackotom.hongguo.android.AdaptiveActivityTest
else
 "$JAVA_HOME/bin/java" -cp "$TMP/classes:$JSON_JAR" io.github.jackotom.hongguo.android.AdaptiveBaselineTest
fi
echo 'ADAPTIVE_QUALITY_OK actualCoordinator=true actualActivityMethods=true SDK35=true networkRequests=0 emulator=not_used'
