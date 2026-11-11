#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
JSON_JAR="${JSON_JAR:-$HOME/.m2/repository/com/vaadin/external/google/android-json/0.0.20131108.vaadin1/android-json-0.0.20131108.vaadin1.jar}"
TMP="$(mktemp -d -t hongguo-prepared-download.XXXXXX)"
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/android/content"
cat > "$TMP/android/content/Context.java" <<'STUB'
package android.content;
public abstract class Context {public static final int MODE_PRIVATE=0;public abstract SharedPreferences getSharedPreferences(String name,int mode);public abstract java.io.File getExternalFilesDir(String type);}
STUB
cat > "$TMP/android/content/SharedPreferences.java" <<'STUB'
package android.content;
public interface SharedPreferences {String getString(String key,String fallback);java.util.Map<String,?> getAll();Editor edit();interface Editor {Editor putString(String key,String value);Editor putBoolean(String key,boolean value);Editor putInt(String key,int value);Editor putLong(String key,long value);Editor putFloat(String key,float value);Editor putStringSet(String key,java.util.Set<String> values);Editor remove(String key);boolean commit();void apply();}}
STUB
SRC="$ROOT/app/src/main/java/io/github/jackotom/hongguo/android/LibraryStore.java"
"$JAVA_HOME/bin/javac" -encoding UTF-8 -cp "$JSON_JAR:$ANDROID_HOME/platforms/android-35/android.jar" -d "$TMP/classes" \
  "$TMP/android/content/Context.java" "$TMP/android/content/SharedPreferences.java" "$SRC" "$ROOT/tests/PreparedDownloadTest.java"
"$JAVA_HOME/bin/java" -cp "$TMP/classes:$JSON_JAR:$ANDROID_HOME/platforms/android-35/android.jar" io.github.jackotom.hongguo.android.PreparedDownloadTest
"$JAVA_HOME/bin/javac" -encoding UTF-8 -source 17 -target 17 -cp "$ANDROID_HOME/platforms/android-35/android.jar" -d "$TMP/sdk35" "$SRC"
echo 'PASS PreparedDownload SDK35 productionCompile=true deviceUntouched=true'
