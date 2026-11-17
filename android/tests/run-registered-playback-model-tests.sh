#!/bin/bash
set -euo pipefail
OWN="$(cd "$(dirname "$0")/../.." && pwd)";ROOT="$OWN"
if [[ "${1:-}" == --source-root ]];then ROOT="$2";shift 2;fi
[[ $# == 0 ]] || exit 2
JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
JSON_JAR="${JSON_JAR:-$HOME/.m2/repository/com/vaadin/external/google/android-json/0.0.20131108.vaadin1/android-json-0.0.20131108.vaadin1.jar}"
# AppTransport/NetworkGuard reference android.os.Build since the TLS hardening pass.
SDK_JAR="${SDK_JAR:-${ANDROID_HOME:-$HOME/Library/Android/sdk}/platforms/android-35/android.jar}"
# The model transport runs OkHttp (the v5 endpoint hollows plain HttpURLConnection
# clients); locate its jars in the local Gradle cache for the offline javac classpath.
GRADLE_CACHE="${GRADLE_CACHE:-$HOME/.gradle/caches/modules-2/files-2.1}"
OKHTTP_JAR="$(find "$GRADLE_CACHE/com.squareup.okhttp3/okhttp" -name 'okhttp-4.12.0.jar' | head -1)"
OKIO_JAR="$(find "$GRADLE_CACHE/com.squareup.okio/okio-jvm" -name 'okio-jvm-3.6.0.jar' | head -1)"
KOTLIN_JAR="$(find "$GRADLE_CACHE/org.jetbrains.kotlin/kotlin-stdlib" -name 'kotlin-stdlib-2.0.20.jar' | head -1)"
if [[ -z "$KOTLIN_JAR" ]]; then
 KOTLIN_JAR="$(find "$GRADLE_CACHE/org.jetbrains.kotlin/kotlin-stdlib" -type f -name 'kotlin-stdlib-[0-9]*.jar' ! -name '*-sources.jar' ! -name '*-javadoc.jar' ! -name '*-metadata.jar' -print -quit)"
fi
ANNOTATIONS_JAR="$(find "$GRADLE_CACHE/org.jetbrains/annotations" -name 'annotations-13.0.jar' | head -1)"
for jar in "$OKHTTP_JAR" "$OKIO_JAR" "$KOTLIN_JAR" "$ANNOTATIONS_JAR"; do [[ -f "$jar" ]] || { echo "missing okhttp runtime jar: $jar" >&2; exit 1; }; done
OKHTTP_CP="$OKHTTP_JAR:$OKIO_JAR:$KOTLIN_JAR:$ANNOTATIONS_JAR"
TMP="$(mktemp -d -t hongguo-registered-model.XXXXXX)";trap 'rm -rf "$TMP"' EXIT
SRC="$ROOT/android/app/src/main/java/io/github/jackotom/hongguo/android"
python3 - "$SRC/NativeBridge.java" "$TMP/RegisteredRpcAllowlistTest.java" <<'PY'
from pathlib import Path
import sys,re
s=Path(sys.argv[1]).read_text();field=re.search(r'private static final Set<String> METHODS = [^;]+;',s).group()
Path(sys.argv[2]).write_text('import java.util.*; public final class RegisteredRpcAllowlistTest { '+field+' public static void main(String[]args){ if(!METHODS.contains("appRegisteredModel"))throw new AssertionError("REGISTERED_RPC_UNSUPPORTED actualNativeAllowlist=true registrationRequests=0 modelRequests=0");System.out.println("REGISTERED_RPC_ALLOWED actualNativeAllowlist=true");}}')
PY
"$JAVA_HOME/bin/javac" --release 17 -encoding UTF-8 -d "$TMP/allowlist" "$TMP/RegisteredRpcAllowlistTest.java"
"$JAVA_HOME/bin/java" -cp "$TMP/allowlist" RegisteredRpcAllowlistTest
"$JAVA_HOME/bin/javac" --release 17 -encoding UTF-8 -cp "$JSON_JAR:$SDK_JAR:$OKHTTP_CP" -d "$TMP/classes" \
 "$SRC/DanmakuSigner.java" "$SRC/SearchProtocol.java" "$SRC/PassportConstants.java" "$SRC/PassportSigner.java" "$SRC/PassportDevice.java" "$SRC/PassportJson.java" "$SRC/OfficialApiClient.java" "$SRC/OfficialTransport.java" "$SRC/AppTransport.java" "$SRC/NetworkGuard.java" "$SRC/PlayerSigner.java" "$SRC/PlaybackSignerV5.java" "$SRC/AccountSigner.java" "$SRC/UpstreamDeviceRegister.java" "$SRC/RegisteredPlaybackModel.java" "$OWN/android/tests/PlayerSignerTest.java" "$OWN/android/tests/PlaybackSignerV5Test.java" "$OWN/android/tests/RegisteredPlaybackModelTest.java"
"$JAVA_HOME/bin/java" -cp "$TMP/classes:$JSON_JAR:$SDK_JAR:$OKHTTP_CP" io.github.jackotom.hongguo.android.PlayerSignerTest
"$JAVA_HOME/bin/java" -cp "$TMP/classes:$JSON_JAR:$SDK_JAR:$OKHTTP_CP" io.github.jackotom.hongguo.android.PlaybackSignerV5Test
"$JAVA_HOME/bin/java" -cp "$TMP/classes:$JSON_JAR:$SDK_JAR:$OKHTTP_CP" io.github.jackotom.hongguo.android.RegisteredPlaybackModelTest "$TMP/projected-flags.json"
node "$OWN/android/tests/registered-model-projection.test.cjs" "$ROOT" "$TMP/projected-flags.json"
python3 "$OWN/android/tests/registered-model-dispatch-harness.py" "$SRC/NativeBridge.java" "$TMP/RegisteredModelDispatchTest.java"
"$JAVA_HOME/bin/javac" --release 17 -encoding UTF-8 -cp "$JSON_JAR" -d "$TMP/dispatch" "$TMP/RegisteredModelDispatchTest.java"
"$JAVA_HOME/bin/java" -cp "$TMP/dispatch:$JSON_JAR" RegisteredModelDispatchTest
