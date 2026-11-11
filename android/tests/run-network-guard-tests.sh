#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
JSON_JAR="${JSON_JAR:-$HOME/.m2/repository/com/vaadin/external/google/android-json/0.0.20131108.vaadin1/android-json-0.0.20131108.vaadin1.jar}"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
"$JAVA_HOME/bin/javac" -encoding UTF-8 -cp "$JSON_JAR" -d "$TMP" "$ROOT/app/src/main/java/io/github/jackotom/hongguo/android/NetworkGuard.java" "$ROOT/tests/NetworkGuardTest.java"
"$JAVA_HOME/bin/java" -cp "$TMP:$JSON_JAR" io.github.jackotom.hongguo.android.NetworkGuardTest
