#!/bin/bash
set -euo pipefail
OWN="$(cd "$(dirname "$0")/../.." && pwd)";ROOT="$OWN"
if [[ "${1:-}" == --source-root ]];then ROOT="$2";shift 2;fi
[[ $# == 0 ]] || exit 2
JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
JSON_JAR="${JSON_JAR:-$HOME/.m2/repository/com/vaadin/external/google/android-json/0.0.20131108.vaadin1/android-json-0.0.20131108.vaadin1.jar}"
TMP="$(mktemp -d -t hongguo-playback-resolution.XXXXXX)";trap 'rm -rf "$TMP"' EXIT
python3 - "$OWN/android/tests/run-native-media-contract-tests.sh" "$TMP/OfflineMediaStubs.java" <<'PY'
from pathlib import Path
import sys
s=Path(sys.argv[1]).read_text();m='cat > "$TMP/OfflineMediaStubs.java" <<\'STUB\'\n';i=s.index(m)+len(m);Path(sys.argv[2]).write_text(s[i:s.index('\nSTUB',i)]+'\n')
PY
SRC="$ROOT/android/app/src/main/java/io/github/jackotom/hongguo/android"
"$JAVA_HOME/bin/javac" --release 17 -encoding UTF-8 -cp "$JSON_JAR" -d "$TMP/classes" "$TMP/OfflineMediaStubs.java" "$SRC/MediaQuality.java" "$SRC/SpadeDecoder.java" "$SRC/DanmakuSigner.java" "$SRC/AppMediaSource.java" "$SRC/PlaybackSources.java" "$OWN/android/tests/PlaybackResolutionSourceTest.java"
"$JAVA_HOME/bin/java" -cp "$TMP/classes:$JSON_JAR" io.github.jackotom.hongguo.android.PlaybackResolutionSourceTest
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platforms/android-35/android.jar"
"$JAVA_HOME/bin/javac" --release 17 -encoding UTF-8 -cp "$SDK" -d "$TMP/sdk35" "$TMP/OfflineMediaStubs.java" "$SRC/MediaQuality.java" "$SRC/SpadeDecoder.java" "$SRC/DanmakuSigner.java" "$SRC/AppMediaSource.java" "$SRC/PlaybackSources.java"
echo 'PASS PLAYBACK_RESOLUTION_SDK35 productionSourceClasses=2 androidJsonContract=true transportBoundary=stubs'
