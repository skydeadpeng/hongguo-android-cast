#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
JSON_JAR="${JSON_JAR:-$HOME/.m2/repository/com/vaadin/external/google/android-json/0.0.20131108.vaadin1/android-json-0.0.20131108.vaadin1.jar}"
TMP="$(mktemp -d -t hongguo-official-web.XXXXXX)"
trap 'rm -rf "$TMP"' EXIT
SOURCE="$ROOT/android/app/src/main/java/io/github/jackotom/hongguo/android"
"$JAVA_HOME/bin/javac" --release 17 -encoding UTF-8 -cp "$JSON_JAR" -sourcepath "$ROOT/android/app/src/main/java" -d "$TMP/classes" \
  "$SOURCE/AccountLog.java" "$SOURCE/OfficialWebPassport.java" "$SOURCE/OfficialWebAccount.java" \
  "$ROOT/android/tests/OfficialWebAccountTest.java"
"$JAVA_HOME/bin/java" -cp "$TMP/classes:$JSON_JAR" io.github.jackotom.hongguo.android.OfficialWebAccountTest
