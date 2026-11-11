#!/bin/bash
set -euo pipefail
OWN="$(cd "$(dirname "$0")/../.." && pwd)";ROOT="$OWN"
if [[ "${1:-}" == --source-root ]];then ROOT="$2";shift 2;fi
[[ $# == 0 ]] || exit 2
JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
JSON_JAR="${JSON_JAR:-$HOME/.m2/repository/com/vaadin/external/google/android-json/0.0.20131108.vaadin1/android-json-0.0.20131108.vaadin1.jar}"
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
"$JAVA_HOME/bin/javac" --release 17 -encoding UTF-8 -cp "$JSON_JAR" -d "$TMP/classes" \
 "$SRC/DanmakuSigner.java" "$SRC/SearchProtocol.java" "$SRC/PassportConstants.java" "$SRC/PassportSigner.java" "$SRC/PassportDevice.java" "$SRC/PassportJson.java" "$SRC/OfficialApiClient.java" "$SRC/OfficialTransport.java" "$SRC/AppTransport.java" "$SRC/NetworkGuard.java" "$SRC/RegisteredPlaybackModel.java" "$OWN/android/tests/RegisteredPlaybackModelTest.java"
"$JAVA_HOME/bin/java" -cp "$TMP/classes:$JSON_JAR" io.github.jackotom.hongguo.android.RegisteredPlaybackModelTest
python3 "$OWN/android/tests/registered-model-dispatch-harness.py" "$SRC/NativeBridge.java" "$TMP/RegisteredModelDispatchTest.java"
"$JAVA_HOME/bin/javac" --release 17 -encoding UTF-8 -cp "$JSON_JAR" -d "$TMP/dispatch" "$TMP/RegisteredModelDispatchTest.java"
"$JAVA_HOME/bin/java" -cp "$TMP/dispatch:$JSON_JAR" RegisteredModelDispatchTest
