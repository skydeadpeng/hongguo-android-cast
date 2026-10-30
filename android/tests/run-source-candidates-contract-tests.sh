#!/bin/bash
set -euo pipefail
TEST_ROOT="$(cd "$(dirname "$0")" && pwd)"
SOURCE_ROOT="$(cd "$TEST_ROOT/../.." && pwd)"
INPUT=''
while [ "$#" -gt 0 ]; do
 case "$1" in
  --source-root) SOURCE_ROOT="$2";shift 2;;
  --input) INPUT="$2";shift 2;;
  *) echo 'USAGE run-source-candidates-contract-tests.sh [--source-root PROJECT] --input SYNTHETIC_JSON' >&2;exit 2;;
 esac
done
[ -f "$INPUT" ] || { echo 'Synthetic adapter output JSON is required' >&2;exit 2; }
JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
JSON_JAR="${JSON_JAR:-$HOME/.m2/repository/com/vaadin/external/google/android-json/0.0.20131108.vaadin1/android-json-0.0.20131108.vaadin1.jar}"
TMP="$(mktemp -d -t hongguo-source-candidates-java.XXXXXX)"
trap 'python3 -c '\''import shutil,sys;shutil.rmtree(sys.argv[1])'\'' "$TMP"' EXIT
SRC="$SOURCE_ROOT/android/app/src/main/java/io/github/jackotom/hongguo/android"
cat > "$TMP/MainActivity.java" <<'STUB'
package io.github.jackotom.hongguo.android;
final class MainActivity { boolean isFinishing(){return false;} boolean isDestroyed(){return false;} void script(String value){throw new AssertionError("Unexpected source lookup: offline registration only");} }
STUB
"$JAVA_HOME/bin/javac" -encoding UTF-8 -cp "$JSON_JAR" -d "$TMP/classes" \
 "$TMP/MainActivity.java" "$SRC/MediaQuality.java" "$SRC/SpadeDecoder.java" "$SRC/DanmakuSigner.java" "$SRC/AppMediaSource.java" "$SRC/PlaybackSources.java" "$SRC/NetworkGuard.java" "$SRC/AppTransport.java" "$SRC/EpisodeResolver.java" "$SRC/CencDecryptor.java" "$TEST_ROOT/SourceCandidatesContractTest.java"
"$JAVA_HOME/bin/java" -cp "$TMP/classes:$JSON_JAR" io.github.jackotom.hongguo.android.SourceCandidatesContractTest "$INPUT"
