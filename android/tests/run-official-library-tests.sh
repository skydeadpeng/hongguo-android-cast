#!/bin/bash
set -euo pipefail
OWN="$(cd "$(dirname "$0")/../.." && pwd)"
ROOT="${1:-$OWN}"
JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
JSON_JAR="${JSON_JAR:-$HOME/.m2/repository/com/vaadin/external/google/android-json/0.0.20131108.vaadin1/android-json-0.0.20131108.vaadin1.jar}"
TMP="$(mktemp -d -t hongguo-official-library.XXXXXX)";trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/stubs/android/content" "$TMP/stubs/android/os"
cat > "$TMP/stubs/android/content/Context.java" <<'JAVA'
package android.content;
public abstract class Context {public static final int MODE_PRIVATE=0; public Context getApplicationContext(){return this;} public abstract java.io.File getFilesDir(); public abstract java.io.File getExternalFilesDir(String type);public abstract SharedPreferences getSharedPreferences(String name,int mode);}
JAVA
cat > "$TMP/stubs/android/content/SharedPreferences.java" <<'JAVA'
package android.content;
public interface SharedPreferences {java.util.Map<String,?> getAll();String getString(String key,String fallback);Editor edit();interface Editor {Editor putString(String k,String v);Editor putBoolean(String k,boolean v);Editor putInt(String k,int v);Editor putLong(String k,long v);Editor putFloat(String k,float v);Editor putStringSet(String k,java.util.Set<String>v);Editor remove(String k);void apply();boolean commit();}}
JAVA
cat > "$TMP/stubs/android/os/Environment.java" <<'JAVA'
package android.os;public final class Environment {public static final String DIRECTORY_DOWNLOADS="Download";}
JAVA
cat > "$TMP/OfficialApiClient.java" <<'JAVA'
package io.github.jackotom.hongguo.android;
import java.io.IOException;import org.json.*;
final class OfficialApiClient {static final class HTTPError extends IOException {final int status,retryAfter;HTTPError(int status,int retryAfter){this.status=status;this.retryAfter=retryAfter;}}void deadlineNanos(long value){}JSONObject session()throws Exception{return new JSONObject().put("cookies",new JSONObject());}JSONObject cloud(String p,String m,JSONObject q,JSONObject b,boolean g)throws Exception{throw new IOException("fixture client: network prohibited");}}
JAVA
SRC="$ROOT/android/app/src/main/java/io/github/jackotom/hongguo/android"
"$JAVA_HOME/bin/javac" --release 17 -encoding UTF-8 -cp "$JSON_JAR" -d "$TMP/classes" "$TMP/stubs/android/content/Context.java" "$TMP/stubs/android/content/SharedPreferences.java" "$TMP/stubs/android/os/Environment.java" "$TMP/OfficialApiClient.java" "$SRC/LibraryStore.java" "$SRC/OfficialLibrary.java" "$OWN/android/tests/OfficialLibraryTest.java"
"$JAVA_HOME/bin/java" -cp "$TMP/classes:$JSON_JAR" io.github.jackotom.hongguo.android.OfficialLibraryTest
