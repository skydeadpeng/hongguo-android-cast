#!/bin/bash
# Explicit single anonymous registration probe. No phone, OTP, account session, cloud action, or retries.
set -euo pipefail
[[ $# == 1 && "$1" == '--anonymous-device-only' ]] || { echo 'USAGE: run-official-anonymous-device.sh --anonymous-device-only' >&2;exit 64; }
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
JSON_JAR="${JSON_JAR:-$HOME/.m2/repository/com/vaadin/external/google/android-json/0.0.20131108.vaadin1/android-json-0.0.20131108.vaadin1.jar}"
TMP="$(mktemp -d -t hongguo-anonymous-device.XXXXXX)"
trap 'python3 -c "import shutil,sys;shutil.rmtree(sys.argv[1])" "$TMP"' EXIT
mkdir -p "$TMP/io/github/jackotom/hongguo/android"
cat > "$TMP/io/github/jackotom/hongguo/android/PassportAnonymousDeviceProbe.java" <<'JAVA'
package io.github.jackotom.hongguo.android;
import org.json.JSONObject;
public final class PassportAnonymousDeviceProbe {
 public static void main(String[]args)throws Exception{
  long before=System.nanoTime();int status=0;
  try(OfficialApiClient client=new OfficialApiClient(OfficialApiClient.newSession())){
   client.registerDevice();JSONObject s=client.session();boolean valid=OfficialApiClient.validID(s.optString("device_id"))&&OfficialApiClient.validID(s.optString("install_id"));
   System.out.println("ANONYMOUS_DEVICE_REGISTER registered="+s.optBoolean("registered")+" deviceIdsValid="+valid+" httpStatus=200 millis="+((System.nanoTime()-before)/1000000)+" attempts=1 phoneOrOtpProvided=false accountCredentialsSent=false privateFieldsLogged=false");if(!valid||!s.optBoolean("registered"))System.exit(1);
  }catch(Exception error){if(error instanceof OfficialApiClient.HTTPError)status=((OfficialApiClient.HTTPError)error).status;System.out.println("ANONYMOUS_DEVICE_REGISTER registered=false httpStatus="+status+" errorType="+error.getClass().getSimpleName()+" millis="+((System.nanoTime()-before)/1000000)+" attempts=1 phoneOrOtpProvided=false accountCredentialsSent=false privateFieldsLogged=false");System.exit(1);}
 }
}
JAVA
SRC="$ROOT/app/src/main/java/io/github/jackotom/hongguo/android"
"$JAVA_HOME/bin/javac" -encoding UTF-8 -source 17 -target 17 -cp "$JSON_JAR" -d "$TMP/classes" \
 "$SRC/DanmakuSigner.java" "$SRC/SearchProtocol.java" "$SRC/PassportConstants.java" "$SRC/PassportSigner.java" "$SRC/PassportDevice.java" "$SRC/PassportJson.java" "$SRC/OfficialApiClient.java" "$SRC/OfficialTransport.java" "$SRC/AppTransport.java" "$SRC/NetworkGuard.java" "$TMP/io/github/jackotom/hongguo/android/PassportAnonymousDeviceProbe.java"
"$JAVA_HOME/bin/java" -cp "$TMP/classes:$JSON_JAR" io.github.jackotom.hongguo.android.PassportAnonymousDeviceProbe
