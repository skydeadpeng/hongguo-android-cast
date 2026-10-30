#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
JSON_JAR="${JSON_JAR:-$HOME/.m2/repository/com/vaadin/external/google/android-json/0.0.20131108.vaadin1/android-json-0.0.20131108.vaadin1.jar}"
TMP="$(mktemp -d -t hongguo-passport.XXXXXX)"
trap 'python3 -c "import shutil,sys;shutil.rmtree(sys.argv[1])" "$TMP"' EXIT
SRC="${OFFICIAL_API_SRC:-$ROOT/app/src/main/java/io/github/jackotom/hongguo/android}"
"$JAVA_HOME/bin/javac" -encoding UTF-8 -source 17 -target 17 -cp "$JSON_JAR" -d "$TMP/classes" \
 "$SRC/DanmakuSigner.java" "$SRC/SearchProtocol.java" "$SRC/PassportConstants.java" "$SRC/PassportSigner.java" "$SRC/PassportDevice.java" "$SRC/PassportJson.java" "$SRC/OfficialApiClient.java" "$SRC/OfficialTransport.java" "$SRC/AppTransport.java" "$SRC/NetworkGuard.java" \
 "$ROOT/tests/PassportSignerTest.java" "$ROOT/tests/PassportDeviceTest.java" "$ROOT/tests/OfficialApiClientTest.java"
"$JAVA_HOME/bin/java" -cp "$TMP/classes:$JSON_JAR" io.github.jackotom.hongguo.android.PassportSignerTest "$ROOT/tests/fixtures/passport-vectors.json"
"$JAVA_HOME/bin/java" -cp "$TMP/classes:$JSON_JAR" io.github.jackotom.hongguo.android.PassportDeviceTest "$ROOT/tests/fixtures/passport-device-vector.json"
"$JAVA_HOME/bin/java" -cp "$TMP/classes:$JSON_JAR" io.github.jackotom.hongguo.android.OfficialApiClientTest
