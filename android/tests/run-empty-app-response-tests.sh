#!/bin/bash
set -euo pipefail
OWN="$(cd "$(dirname "$0")/../.." && pwd)"
ROOT="$OWN"
if [[ "${1:-}" == --source-root ]]; then ROOT="$2"; shift 2; fi
[[ $# == 0 ]] || { echo 'Usage: run-empty-app-response-tests.sh [--source-root PROJECT]' >&2; exit 2; }
JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
JSON_JAR="${JSON_JAR:-$HOME/.m2/repository/com/vaadin/external/google/android-json/0.0.20131108.vaadin1/android-json-0.0.20131108.vaadin1.jar}"
TMP="$(mktemp -d -t hongguo-empty-response.XXXXXX)"
trap 'rm -rf "$TMP"' EXIT
python3 - "$OWN/android/tests/run-native-media-contract-tests.sh" "$TMP/OfflineMediaStubs.java" <<'PY'
from pathlib import Path
import sys
text=Path(sys.argv[1]).read_text()
marker='cat > "$TMP/OfflineMediaStubs.java" <<\'STUB\'\n'
start=text.index(marker)+len(marker)
end=text.index('\nSTUB',start)
Path(sys.argv[2]).write_text(text[start:end]+'\n')
PY
SRC="$ROOT/android/app/src/main/java/io/github/jackotom/hongguo/android"
"$JAVA_HOME/bin/javac" --release 17 -encoding UTF-8 -cp "$JSON_JAR" -d "$TMP/classes" \
 "$TMP/OfflineMediaStubs.java" "$SRC/MediaQuality.java" "$SRC/SpadeDecoder.java" "$SRC/DanmakuSigner.java" "$SRC/AppMediaSource.java" "$SRC/PlaybackSources.java" "$SRC/AdaptiveQuality.java" "$OWN/android/tests/EmptyAppResponseTest.java"
"$JAVA_HOME/bin/java" -cp "$TMP/classes:$JSON_JAR" io.github.jackotom.hongguo.android.EmptyAppResponseTest
