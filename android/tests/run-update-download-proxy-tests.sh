#!/bin/bash
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"; ROOT="$(cd "$HERE/../.." && pwd)"
SOURCE_ROOT="$ROOT"
MODE=download
while [[ $# -gt 0 ]];do case "$1" in --source-root) SOURCE_ROOT="$2";shift 2;; --manifest) MODE=manifest;shift;; *) exit 2;; esac;done
JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
JSON_JAR="${JSON_JAR:-$HOME/.m2/repository/com/vaadin/external/google/android-json/0.0.20131108.vaadin1/android-json-0.0.20131108.vaadin1.jar}"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
SRC="$SOURCE_ROOT/android/app/src/main/java/io/github/jackotom/hongguo/android"
python3 "$HERE/update-download-harness.py" "$SRC/AppUpdater.java" "$TMP/UpdateDownloadHarness.java"
"$JAVA_HOME/bin/javac" --release 17 -encoding UTF-8 -cp "$JSON_JAR" -d "$TMP/classes" "$SRC/UpdatePolicy.java" "$SRC/UpdateIntegrity.java" "$TMP/UpdateDownloadHarness.java"
"$JAVA_HOME/bin/java" -cp "$TMP/classes:$JSON_JAR" io.github.jackotom.hongguo.android.UpdateDownloadHarness "$MODE"
