#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
JSON_JAR="${JSON_JAR:-$HOME/.m2/repository/com/vaadin/external/google/android-json/0.0.20131108.vaadin1/android-json-0.0.20131108.vaadin1.jar}"
TMP="$(mktemp -d -t hongguo-web-sync.XXXXXX)";trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/stubs/android/content" "$TMP/stubs/android/os" "$TMP/stubs/android/util" "$TMP/stubs/android/security/keystore" "$TMP/io/github/jackotom/hongguo/android"
cat > "$TMP/stubs/android/content/Context.java" <<'JAVA'
package android.content;public abstract class Context {public static final int MODE_PRIVATE=0;public Context getApplicationContext(){return this;}public abstract java.io.File getFilesDir();public abstract java.io.File getExternalFilesDir(String type);public abstract SharedPreferences getSharedPreferences(String name,int mode);}
JAVA
cat > "$TMP/stubs/android/content/SharedPreferences.java" <<'JAVA'
package android.content;public interface SharedPreferences {java.util.Map<String,?>getAll();String getString(String key,String fallback);Editor edit();interface Editor {Editor putString(String k,String v);Editor putBoolean(String k,boolean v);Editor putInt(String k,int v);Editor putLong(String k,long v);Editor putFloat(String k,float v);Editor putStringSet(String k,java.util.Set<String>v);Editor remove(String k);void apply();boolean commit();}}
JAVA
cat > "$TMP/stubs/android/os/Environment.java" <<'JAVA'
package android.os;public final class Environment {public static final String DIRECTORY_DOWNLOADS="Download";}
JAVA
cat > "$TMP/stubs/android/util/Base64.java" <<'JAVA'
package android.util;public final class Base64 {public static final int DEFAULT=0,NO_WRAP=2;private Base64(){}public static byte[] decode(String value,int flags){return java.util.Base64.getMimeDecoder().decode(value);}public static String encodeToString(byte[] value,int flags){return java.util.Base64.getEncoder().encodeToString(value);}}
JAVA
cat > "$TMP/stubs/android/security/keystore/KeyProperties.java" <<'JAVA'
package android.security.keystore;public final class KeyProperties {public static final String KEY_ALGORITHM_AES="AES",BLOCK_MODE_GCM="GCM",ENCRYPTION_PADDING_NONE="NoPadding";public static final int PURPOSE_ENCRYPT=1,PURPOSE_DECRYPT=2;private KeyProperties(){}}
JAVA
cat > "$TMP/stubs/android/security/keystore/KeyGenParameterSpec.java" <<'JAVA'
package android.security.keystore;public final class KeyGenParameterSpec implements java.security.spec.AlgorithmParameterSpec {private KeyGenParameterSpec(){}public static final class Builder {public Builder(String alias,int purposes){}public Builder setBlockModes(String... modes){return this;}public Builder setEncryptionPaddings(String... paddings){return this;}public KeyGenParameterSpec build(){return new KeyGenParameterSpec();}}}
JAVA
cat > "$TMP/io/github/jackotom/hongguo/android/OfficialApiClient.java" <<'STUB'
package io.github.jackotom.hongguo.android;
import org.json.JSONObject;
final class OfficialApiClient implements AutoCloseable {
    static final int MAX_BYTES=4*1024*1024;
    static final class HTTPError extends java.io.IOException { final int status,retryAfter; HTTPError(int status,int retry){this.status=status;this.retryAfter=retry;} }
    interface Cloud { JSONObject call(String path,String method,JSONObject query,JSONObject body) throws Exception; }
    static volatile Cloud global;
    private final Cloud cloud;private final JSONObject state;
    OfficialApiClient(Cloud cloud,JSONObject state){this.cloud=cloud;this.state=state;}
    OfficialApiClient(JSONObject state,Cloud cloud){this(cloud,state);}
    OfficialApiClient(JSONObject state){this(state,global);}
    OfficialApiClient(JSONObject state,String userAgent){this(state,global);}
    static JSONObject newSession() throws Exception { return new JSONObject().put("cookies", new JSONObject()); }
    JSONObject session() throws Exception { return new JSONObject(state.toString()); }
    void registerDevice() throws Exception { }
    void deadlineNanos(long value) { }
    JSONObject sendCode(String phone)throws Exception{throw new java.io.IOException("短信登录已停用");}
    JSONObject smsLogin(String phone,String code,String ticket)throws Exception{throw new java.io.IOException("短信登录已停用");}
    JSONObject verifyMfa(JSONObject challenge,String phone)throws Exception{throw new java.io.IOException("短信登录已停用");}
    JSONObject cloud(String path,String method,JSONObject query,JSONObject body,boolean gzip) throws Exception { return cloud.call(path,method,query,body); }
    public void close(){ }
}
STUB
cat > "$TMP/io/github/jackotom/hongguo/android/OfficialSessionStore.java" <<'STUB'
package io.github.jackotom.hongguo.android;
import android.content.Context;import org.json.*;
final class OfficialSessionStore implements OfficialAccountFlow.Store{interface Vault{String seal(String value)throws Exception;String open(String value)throws Exception;}OfficialSessionStore(Context context){}public JSONObject load(){throw new AssertionError("production store not used");}public void save(JSONObject value){throw new AssertionError("production store not used");}}
STUB
SRC="$ROOT/android/app/src/main/java/io/github/jackotom/hongguo/android"
"$JAVA_HOME/bin/javac" --release 17 -encoding UTF-8 -cp "$JSON_JAR" -d "$TMP/classes" \
  "$TMP/stubs/android/content/Context.java" "$TMP/stubs/android/content/SharedPreferences.java" "$TMP/stubs/android/os/Environment.java" \
  "$TMP/stubs/android/util/Base64.java" "$TMP/stubs/android/security/keystore/KeyProperties.java" "$TMP/stubs/android/security/keystore/KeyGenParameterSpec.java" \
  "$TMP/io/github/jackotom/hongguo/android/OfficialApiClient.java" "$TMP/io/github/jackotom/hongguo/android/OfficialSessionStore.java" \
  "$SRC/AccountLog.java" "$SRC/AccountSigner.java" "$SRC/UpstreamDeviceRegister.java" "$SRC/LibraryStore.java" "$SRC/OfficialLibrary.java" "$SRC/OfficialAccountFlow.java" \
  "$SRC/OfficialWebPassport.java" "$SRC/OfficialWebGateway.java" "$SRC/OfficialWebAccount.java" "$SRC/OfficialWebSessionStore.java" "$SRC/OfficialAccountVault.java" \
  "$SRC/AccountSync.java" "$SRC/AppTransport.java" "$SRC/NetworkGuard.java" "$SRC/DanmakuSigner.java" "$SRC/PassportDevice.java" "$SRC/PassportSigner.java" "$SRC/PassportConstants.java" "$SRC/PassportJson.java" "$SRC/SearchProtocol.java" \
  "$ROOT/android/tests/OfficialWebSyncTest.java"
"$JAVA_HOME/bin/java" -cp "$TMP/classes:$JSON_JAR" io.github.jackotom.hongguo.android.OfficialWebSyncTest
