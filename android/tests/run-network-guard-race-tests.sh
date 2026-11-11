#!/usr/bin/env bash
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
SOURCE_ROOT="$(cd "$HERE/../.." && pwd)"
if [[ "${1:-}" == --source-root ]]; then SOURCE_ROOT="$2"; shift 2; fi
[[ $# -eq 0 ]] || { echo 'Usage: run-network-guard-race-tests.sh [--source-root PROJECT]' >&2; exit 64; }
JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
JSON_JAR="${JSON_JAR:-$HOME/.m2/repository/com/vaadin/external/google/android-json/0.0.20131108.vaadin1/android-json-0.0.20131108.vaadin1.jar}"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
"$JAVA_HOME/bin/javac" --release 17 -encoding UTF-8 -cp "$JSON_JAR" -d "$TMP" \
 "$SOURCE_ROOT/android/app/src/main/java/io/github/jackotom/hongguo/android/NetworkGuard.java" "$HERE/NetworkGuardRaceTest.java"
"$JAVA_HOME/bin/java" -cp "$TMP:$JSON_JAR" io.github.jackotom.hongguo.android.NetworkGuardRaceTest
