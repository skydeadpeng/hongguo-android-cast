#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SOURCE_ROOT="$ROOT"
if [[ "${1:-}" = '--source-root' ]];then SOURCE_ROOT="$2";shift 2;fi
[[ $# = 0 ]] || { echo 'USAGE run-source-deadline-tests.sh [--source-root PROJECT]' >&2;exit 2; }
JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
JSON_JAR="${JSON_JAR:-$HOME/.m2/repository/com/vaadin/external/google/android-json/0.0.20131108.vaadin1/android-json-0.0.20131108.vaadin1.jar}"
TMP="$(mktemp -d -t hongguo-deadline.XXXXXX)";trap 'rm -rf "$TMP"' EXIT
SRC="$SOURCE_ROOT/android/app/src/main/java/io/github/jackotom/hongguo/android"
"$JAVA_HOME/bin/javac" --release 17 -encoding UTF-8 -cp "$JSON_JAR" -d "$TMP/classes" "$SRC/NetworkGuard.java" "$SRC/AppTransport.java" "$ROOT/android/tests/SourceDeadlineTest.java"
"$JAVA_HOME/bin/java" -cp "$TMP/classes:$JSON_JAR" io.github.jackotom.hongguo.android.SourceDeadlineTest
