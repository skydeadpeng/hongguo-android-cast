#!/bin/bash
set -euo pipefail
TEST_ROOT="$(cd "$(dirname "$0")" && pwd)"
SOURCE_ROOT="$(cd "$TEST_ROOT/../.." && pwd)"
DIAGNOSTICS_ONLY=false
while [[ $# -gt 0 ]]; do case "$1" in --source-root) SOURCE_ROOT="$2"; shift 2;; --diagnostics-only) DIAGNOSTICS_ONLY=true;shift;; *) echo 'USAGE run-dlna-session-tests.sh [--source-root PROJECT] [--diagnostics-only]' >&2;exit 64;; esac;done
JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
SRC="$SOURCE_ROOT/android/app/src/main/java/io/github/jackotom/hongguo/android"
TMP="$(mktemp -d -t hongguo-dlna-session.XXXXXX)"
trap 'python3 -c "import shutil,sys;shutil.rmtree(sys.argv[1])" "$TMP"' EXIT
mkdir -p "$TMP/sdk35" "$TMP/classes" "$TMP/io/github/jackotom/hongguo/android"
printf '%s\n' 'package io.github.jackotom.hongguo.android; public final class BuildConfig {public static final boolean IS_TV=false,DEBUG=true;public static final String VERSION_NAME="fixture";}' > "$TMP/io/github/jackotom/hongguo/android/BuildConfig.java"
if [[ "$DIAGNOSTICS_ONLY" = false ]];then
 "$JAVA_HOME/bin/javac" -encoding UTF-8 -source 17 -target 17 -cp "$ANDROID_HOME/platforms/android-35/android.jar" -d "$TMP/sdk35" "$TMP/io/github/jackotom/hongguo/android/BuildConfig.java" "$SRC/"*.java
 echo 'PASS DlnaCastService SDK35 allProductionCompile=true deviceUntouched=true'
fi
if [[ ! -f "$SRC/DlnaSession.java" ]];then echo 'DLNA_SESSION_FAILED baselineHasNoCastingSession=true';exit 1;fi
"$JAVA_HOME/bin/javac" -encoding UTF-8 -source 17 -target 17 -d "$TMP/classes" "$SRC/MediaQuality.java" "$SRC/DlnaSession.java" "$TEST_ROOT/DlnaSessionTest.java" "$SRC/DlnaSessionFile.java" "$TEST_ROOT/DlnaSessionFileTest.java" "$TEST_ROOT/DlnaSessionDiagnosticsTest.java"
if [[ "$DIAGNOSTICS_ONLY" = true ]];then
 "$JAVA_HOME/bin/java" -cp "$TMP/classes" io.github.jackotom.hongguo.android.DlnaSessionDiagnosticsTest
 exit 0
fi
"$JAVA_HOME/bin/java" -cp "$TMP/classes" io.github.jackotom.hongguo.android.DlnaSessionTest
"$JAVA_HOME/bin/java" -cp "$TMP/classes" io.github.jackotom.hongguo.android.DlnaSessionDiagnosticsTest

"$JAVA_HOME/bin/java" -cp "$TMP/classes" io.github.jackotom.hongguo.android.DlnaSessionFileTest

JSON_JAR="$HOME/.m2/repository/com/vaadin/external/google/android-json/0.0.20131108.vaadin1/android-json-0.0.20131108.vaadin1.jar"
python3 "$TEST_ROOT/dlna-service-harness.py" "$SRC" "$TMP/DlnaServiceHarness.java"
"$JAVA_HOME/bin/javac" -encoding UTF-8 -source 17 -target 17 -cp "$TMP/classes:$JSON_JAR" -d "$TMP/classes" "$SRC/DlnaNetwork.java" "$SRC/DlnaDevice.java" "$TMP/DlnaServiceHarness.java" "$TEST_ROOT/DlnaServiceLifecycleTest.java"
"$JAVA_HOME/bin/java" -cp "$TMP/classes:$JSON_JAR" io.github.jackotom.hongguo.android.DlnaServiceLifecycleTest
