#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
JSON_JAR="${JSON_JAR:-$HOME/.m2/repository/com/vaadin/external/google/android-json/0.0.20131108.vaadin1/android-json-0.0.20131108.vaadin1.jar}"
TMP="$(mktemp -d -t hongguo-official-web.XXXXXX)"
trap 'rm -rf "$TMP"' EXIT
SOURCE="$ROOT/android/app/src/main/java/io/github/jackotom/hongguo/android"
mkdir -p "$TMP/io/github/jackotom/hongguo/android"
cat > "$TMP/io/github/jackotom/hongguo/android/OfficialLibrary.java" <<'STUB'
package io.github.jackotom.hongguo.android;
import org.json.JSONObject;
final class OfficialLibrary {
    interface Api { JSONObject call(String path,String method,JSONObject query,JSONObject body,boolean gzip)throws Exception; }
}
STUB
cat > "$TMP/io/github/jackotom/hongguo/android/AppTransport.java" <<'STUB'
package io.github.jackotom.hongguo.android;
import java.util.Map;
final class AppTransport {
    static String accountCall(String url, String method, Map<String, String> headers, String body, long deadlineNanos) throws Exception { throw new java.io.IOException("网络在离线测试中不可用"); }
}
STUB
cat > "$TMP/io/github/jackotom/hongguo/android/UpstreamDeviceRegister.java" <<'STUB'
package io.github.jackotom.hongguo.android;
final class UpstreamDeviceRegister {
    static final class Identity { final String deviceId,iid,cdid; Identity(String a,String b,String c){deviceId=a;iid=b;cdid=c;} }
    static Identity register() throws Exception { throw new java.io.IOException("登记在离线测试中不可用"); }
}
STUB
cat > "$TMP/io/github/jackotom/hongguo/android/OfficialApiClient.java" <<'STUB'
package io.github.jackotom.hongguo.android;
import org.json.JSONObject;
final class OfficialApiClient implements AutoCloseable {
    static final int MAX_BYTES=4*1024*1024;
    static final class HTTPError extends java.io.IOException { final int status,retryAfter; HTTPError(int status,int retry){this.status=status;this.retryAfter=retry;} }
    static JSONObject newSession() throws Exception { return new JSONObject().put("cookies", new JSONObject()); }
    OfficialApiClient(JSONObject session) throws Exception { }
    OfficialApiClient(JSONObject session, String userAgent) throws Exception { }
    JSONObject session() throws Exception { return new JSONObject().put("cookies", new JSONObject()); }
    void registerDevice() throws Exception { }
    void deadlineNanos(long value) { }
    JSONObject cloud(String path, String method, JSONObject query, JSONObject body, boolean gzip) throws Exception { throw new java.io.IOException("网络在离线测试中不可用"); }
    public void close() { }
}
STUB
cat > "$TMP/io/github/jackotom/hongguo/android/AccountSigner.java" <<'STUB'
package io.github.jackotom.hongguo.android;
import java.util.Map;
final class AccountSigner {
    static final String USER_AGENT = "com.phoenix.read/72232 (stub)";
    static final class Signed { final String url; final Map<String, String> headers; Signed(String url, Map<String, String> headers) { this.url = url; this.headers = headers; } }
    static Signed request(String apiPath, Map<String, String> values, String deviceId, String iid, String cdid, long now, String processNonce, long processStarted, int[] randoms, byte[] body) throws Exception { throw new java.io.IOException("签名在离线测试中不可用"); }
}
STUB
"$JAVA_HOME/bin/javac" --release 17 -encoding UTF-8 -cp "$JSON_JAR" -d "$TMP/classes" \
  "$TMP/io/github/jackotom/hongguo/android/OfficialLibrary.java" "$TMP/io/github/jackotom/hongguo/android/AppTransport.java" "$TMP/io/github/jackotom/hongguo/android/UpstreamDeviceRegister.java" "$TMP/io/github/jackotom/hongguo/android/OfficialApiClient.java" "$TMP/io/github/jackotom/hongguo/android/AccountSigner.java" \
  "$SOURCE/AccountLog.java" "$SOURCE/OfficialWebPassport.java" "$SOURCE/OfficialWebAccount.java" "$SOURCE/NetworkGuard.java" "$SOURCE/DanmakuSigner.java" \
  "$ROOT/android/tests/OfficialWebAccountTest.java"
"$JAVA_HOME/bin/java" -cp "$TMP/classes:$JSON_JAR" io.github.jackotom.hongguo.android.OfficialWebAccountTest
