#!/bin/bash
set -euo pipefail
OWN="$(cd "$(dirname "$0")/../.." && pwd)"; ROOT="$OWN"
if [[ "${1:-}" == --source-root ]]; then ROOT="$2"; shift 2; fi
[[ $# == 0 ]] || exit 2
JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
JSON_JAR="${JSON_JAR:-$HOME/.m2/repository/com/vaadin/external/google/android-json/0.0.20131108.vaadin1/android-json-0.0.20131108.vaadin1.jar}"
TMP="$(mktemp -d -t hongguo-tab-signer.XXXXXX)"; trap 'rm -rf "$TMP"' EXIT
SRC="$ROOT/android/app/src/main/java/io/github/jackotom/hongguo/android"
"$JAVA_HOME/bin/javac" --release 17 -encoding UTF-8 -cp "$JSON_JAR" -d "$TMP/classes" \
  "$SRC/DanmakuSigner.java" "$SRC/AccountSigner.java" "$SRC/PlaybackSignerV5.java" "$SRC/TabPlaybackSigner.java" "$OWN/android/tests/TabPlaybackSignerTest.java"
"$JAVA_HOME/bin/java" -cp "$TMP/classes:$JSON_JAR" io.github.jackotom.hongguo.android.TabPlaybackSignerTest "$OWN/android/tests/tab-playback-signer-vectors.json"
