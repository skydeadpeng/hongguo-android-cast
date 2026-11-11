#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
TMP="$(mktemp -d -t hongguo-update-resume.XXXXXX)"
trap 'rm -rf "$TMP"' EXIT
python3 "$ROOT/android/tests/update-resume-harness.py" "$ROOT/android/app/src/main/java/io/github/jackotom/hongguo/android/AppUpdater.java" "$TMP/UpdateResumeHarness.java"
"$JAVA_HOME/bin/javac" --release 17 -encoding UTF-8 -d "$TMP" "$TMP/UpdateResumeHarness.java"
"$JAVA_HOME/bin/java" -cp "$TMP" UpdateResumeHarness
