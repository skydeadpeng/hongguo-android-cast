#!/bin/bash
set -euo pipefail
TEST_ROOT="$(cd "$(dirname "$0")" && pwd)"
SOURCE_ROOT="$(cd "$TEST_ROOT/../.." && pwd)"
while [[ $# -gt 0 ]]; do
  case "$1" in --source-root) SOURCE_ROOT="$2"; shift 2;; *) echo 'USAGE run-dlna-protocol-tests.sh [--source-root PROJECT]' >&2; exit 64;; esac
done
JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
SRC="$SOURCE_ROOT/android/app/src/main/java/io/github/jackotom/hongguo/android"
TMP="$(mktemp -d -t hongguo-dlna-protocol.XXXXXX)"
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/sdk35" "$TMP/classes"
SOURCES=("$SRC/DlnaDevice.java" "$SRC/DlnaControl.java" "$SRC/DlnaDiscovery.java" "$SRC/DlnaNetwork.java" "$SRC/DlnaXml.java")
for source in "${SOURCES[@]}"; do
  if [[ ! -f "$source" ]]; then echo 'DLNA_PROTOCOL_FAILED missingProductionDlnaModule=true'; exit 1; fi
done
"$JAVA_HOME/bin/javac" -encoding UTF-8 -source 17 -target 17 -cp "$ANDROID_HOME/platforms/android-35/android.jar" -d "$TMP/sdk35" "${SOURCES[@]}"
echo 'PASS DLNA protocol SDK35 productionCompile=true'
"$JAVA_HOME/bin/javac" -encoding UTF-8 -source 17 -target 17 -d "$TMP/classes" "${SOURCES[@]}" "$TEST_ROOT/DlnaProtocolTest.java"
"$JAVA_HOME/bin/java" -cp "$TMP/classes" io.github.jackotom.hongguo.android.DlnaProtocolTest
