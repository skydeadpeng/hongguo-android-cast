#!/bin/bash
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)";ROOT="$(cd "$HERE/../.." && pwd)"
while [[ $# -gt 0 ]];do case "$1" in --source-root) ROOT="$2";shift 2;;*) echo 'USAGE --source-root PROJECT';exit 64;;esac;done
JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
JSON_JAR="${JSON_JAR:-$HOME/.m2/repository/com/vaadin/external/google/android-json/0.0.20131108.vaadin1/android-json-0.0.20131108.vaadin1.jar}"
SRC="$ROOT/android/app/src/main/java/io/github/jackotom/hongguo/android";TMP="$(mktemp -d -t hongguo-season-bridge.XXXXXX)";trap 'rm -rf "$TMP"' EXIT
python3 - "$SRC/NativeBridge.java" "$HERE/SeasonPlaylistBridgeTest.java" "$TMP/SeasonPlaylistBridgeTest.java" <<'PY'
from pathlib import Path
import sys
source=Path(sys.argv[1]).read_text();template=Path(sys.argv[2]).read_text()
start=source.index('case "play": {');end=source.index('case "download": {',start)
launch=source.index('    private void play(');launch_end=source.index('    /** Encrypted App media',launch)
Path(sys.argv[3]).write_text(template.replace('/* ACTUAL_PLAY_CASE */',source[start:end]).replace('/* ACTUAL_PLAY_METHOD */',source[launch:launch_end]))
print('SEASON_PLAYLIST_BRIDGE_BINDING dispatch=actual launch=actual')
PY
"$JAVA_HOME/bin/javac" --release 17 -encoding UTF-8 -cp "$JSON_JAR" -d "$TMP/classes" "$TMP/SeasonPlaylistBridgeTest.java" "$SRC/PlaybackPlaylist.java" "$SRC/MediaQuality.java" "$SRC/UpdateOperationGate.java"
"$JAVA_HOME/bin/java" -cp "$TMP/classes:$JSON_JAR" io.github.jackotom.hongguo.android.SeasonPlaylistBridgeTest
