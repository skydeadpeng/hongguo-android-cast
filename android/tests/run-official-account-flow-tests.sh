#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
JSON_JAR="${JSON_JAR:-$HOME/.m2/repository/com/vaadin/external/google/android-json/0.0.20131108.vaadin1/android-json-0.0.20131108.vaadin1.jar}"
TMP="$(mktemp -d -t hongguo-official-flow.XXXXXX)"
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/io/github/jackotom/hongguo/android"
cat > "$TMP/io/github/jackotom/hongguo/android/OfficialApiClient.java" <<'STUB'
package io.github.jackotom.hongguo.android;
final class OfficialApiClient { static final class HTTPError extends java.io.IOException { final int status,retryAfter; HTTPError(int status,int retry){this.status=status;this.retryAfter=retry;} } }
STUB
SOURCE="$ROOT/android/app/src/main/java/io/github/jackotom/hongguo/android"
"$JAVA_HOME/bin/javac" --release 17 -encoding UTF-8 -cp "$JSON_JAR" -d "$TMP/classes" "$TMP/io/github/jackotom/hongguo/android/OfficialApiClient.java" "$SOURCE/OfficialAccountFlow.java" "$ROOT/android/tests/OfficialAccountFlowTest.java"
"$JAVA_HOME/bin/java" -cp "$TMP/classes:$JSON_JAR" io.github.jackotom.hongguo.android.OfficialAccountFlowTest

SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platforms/android-35/android.jar"
"$JAVA_HOME/bin/javac" --release 17 -encoding UTF-8 -cp "$TMP/classes:$JSON_JAR:$SDK" -d "$TMP/classes" "$SOURCE/OfficialSessionStore.java" "$SOURCE/OfficialAccountVault.java" "$ROOT/android/tests/OfficialSessionStoreTest.java"
"$JAVA_HOME/bin/java" -cp "$TMP/classes:$JSON_JAR:$SDK" io.github.jackotom.hongguo.android.OfficialSessionStoreTest
