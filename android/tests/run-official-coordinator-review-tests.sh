#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
JSON_JAR="${JSON_JAR:-$HOME/.m2/repository/com/vaadin/external/google/android-json/0.0.20131108.vaadin1/android-json-0.0.20131108.vaadin1.jar}"
TMP="$(mktemp -d -t hongguo-official-coordinator.XXXXXX)";trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/stubs/android/content" "$TMP/stubs/android/os"
cat > "$TMP/stubs/android/content/Context.java" <<'JAVA'
package android.content;public abstract class Context {public static final int MODE_PRIVATE=0;public Context getApplicationContext(){return this;}public abstract java.io.File getFilesDir();public abstract java.io.File getExternalFilesDir(String type);public abstract SharedPreferences getSharedPreferences(String name,int mode);}
JAVA
cat > "$TMP/stubs/android/content/SharedPreferences.java" <<'JAVA'
package android.content;public interface SharedPreferences {java.util.Map<String,?>getAll();String getString(String key,String fallback);Editor edit();interface Editor {Editor putString(String k,String v);Editor putBoolean(String k,boolean v);Editor putInt(String k,int v);Editor putLong(String k,long v);Editor putFloat(String k,float v);Editor putStringSet(String k,java.util.Set<String>v);Editor remove(String k);void apply();boolean commit();}}
JAVA
cat > "$TMP/stubs/android/os/Environment.java" <<'JAVA'
package android.os;public final class Environment {public static final String DIRECTORY_DOWNLOADS="Download";}
JAVA
cat > "$TMP/OfficialApiClient.java" <<'JAVA'
package io.github.jackotom.hongguo.android;
import java.io.*;import org.json.*;
final class OfficialApiClient implements AutoCloseable {
 interface Api{JSONObject call(String p,String m,JSONObject q,JSONObject b,boolean g)throws Exception;}
 static final class HTTPError extends IOException{final int status,retryAfter;HTTPError(int status,int retry){this.status=status;this.retryAfter=retry;}}
 final JSONObject state;final Api api;volatile Thread caller;
 OfficialApiClient(JSONObject state)throws Exception{this(state,(p,m,q,b,g)->{throw new IOException("network prohibited");});}
 OfficialApiClient(JSONObject state,Api api)throws Exception{this.state=new JSONObject(state.toString());this.api=api;}
 static JSONObject newSession()throws Exception{return new JSONObject().put("cookies",new JSONObject());}
 JSONObject session()throws JSONException{return new JSONObject(state.toString());}
 void registerDevice()throws Exception{throw new IOException("not used");}
 JSONObject sendCode(String phone)throws Exception{throw new IOException("not used");}
 JSONObject smsLogin(String p,String c,String t)throws Exception{throw new IOException("not used");}
 JSONObject verifyMfa(JSONObject c,String p)throws Exception{throw new IOException("not used");}
 void deadlineNanos(long value){}
 JSONObject cloud(String p,String m,JSONObject q,JSONObject b,boolean g)throws Exception{caller=Thread.currentThread();try{return api.call(p,m,q,b,g);}finally{caller=null;}}
 public void close(){Thread t=caller;if(t!=null&&t!=Thread.currentThread())t.interrupt();}
}
JAVA
cat > "$TMP/OfficialSessionStore.java" <<'JAVA'
package io.github.jackotom.hongguo.android;
import android.content.Context;import org.json.*;
final class OfficialSessionStore implements OfficialAccountFlow.Store{OfficialSessionStore(Context context){}public JSONObject load(){throw new AssertionError("production keystore constructor not used by injected test");}public void save(JSONObject value){throw new AssertionError("production store not used");}}
JAVA
SRC="$ROOT/android/app/src/main/java/io/github/jackotom/hongguo/android"
"$JAVA_HOME/bin/javac" --release 17 -encoding UTF-8 -cp "$JSON_JAR" -d "$TMP/classes" "$TMP/stubs/android/content/Context.java" "$TMP/stubs/android/content/SharedPreferences.java" "$TMP/stubs/android/os/Environment.java" "$TMP/OfficialApiClient.java" "$TMP/OfficialSessionStore.java" "$SRC/LibraryStore.java" "$SRC/OfficialLibrary.java" "$SRC/OfficialAccountFlow.java" "$SRC/AccountSync.java" "$ROOT/android/tests/OfficialAccountFlowTest.java" "$ROOT/android/tests/OfficialLibraryTest.java" "$ROOT/android/tests/OfficialAccountCoordinatorTest.java" "$ROOT/android/tests/OfficialCoordinatorReviewTest.java"
"$JAVA_HOME/bin/java" -cp "$TMP/classes:$JSON_JAR" io.github.jackotom.hongguo.android.OfficialCoordinatorReviewTest "${1:-all}"
