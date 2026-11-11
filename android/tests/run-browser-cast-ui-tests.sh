#!/bin/bash
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
if [[ $# == 2 && "$1" == --source-root ]]; then ROOT="$2"; elif [[ $# != 0 ]]; then exit 64; fi
JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
JSON_JAR="${JSON_JAR:-$HOME/.m2/repository/com/vaadin/external/google/android-json/0.0.20131108.vaadin1/android-json-0.0.20131108.vaadin1.jar}"
TMP="$(mktemp -d -t hongguo-browser-cast-ui.XXXXXX)";trap 'rm -rf "$TMP"' EXIT
python3 "$HERE/dlna-ui-harness.py" "$ROOT/android/app/src/main/java/io/github/jackotom/hongguo/android/PlayerActivity.java" "$TMP/DlnaUiHarness.java"
PLAYLIST=(); if [[ -f "$ROOT/android/app/src/main/java/io/github/jackotom/hongguo/android/PlaybackPlaylist.java" ]]; then PLAYLIST+=("$ROOT/android/app/src/main/java/io/github/jackotom/hongguo/android/PlaybackPlaylist.java"); fi
"$JAVA_HOME/bin/javac" -encoding UTF-8 -cp "$JSON_JAR" -d "$TMP" "$TMP/DlnaUiHarness.java" "${PLAYLIST[@]}" "$HERE/BrowserCastUiTest.java" "$TMP"/android/net/*.java "$ROOT/android/app/src/main/java/io/github/jackotom/hongguo/android/DlnaNetwork.java"
"$JAVA_HOME/bin/java" -cp "$TMP:$JSON_JAR" io.github.jackotom.hongguo.android.BrowserCastUiTest
