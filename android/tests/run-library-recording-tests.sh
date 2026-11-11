#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SOURCE_ROOT="$ROOT"
if [ "${1:-}" = '--source-root' ]; then SOURCE_ROOT="$2"; shift 2; fi
if [ "$#" -ne 0 ]; then echo 'USAGE run-search-history-tests.sh [--source-root PROJECT]' >&2; exit 2; fi
JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
JSON_JAR="${JSON_JAR:-$HOME/.m2/repository/com/vaadin/external/google/android-json/0.0.20131108.vaadin1/android-json-0.0.20131108.vaadin1.jar}"
if [ ! -f "$JSON_JAR" ]; then echo 'TEST_DEPENDENCY_MISSING set JSON_JAR to an Android-compatible org.json runtime jar'; exit 2; fi
TMP="$(mktemp -d -t hongguo-search-history.XXXXXX)";trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/stubs/android/content"
cat > "$TMP/stubs/android/content/Context.java" <<'STUB'
package android.content;
public abstract class Context {
 public static final int MODE_PRIVATE=0;
 public abstract SharedPreferences getSharedPreferences(String name,int mode);
 public java.io.File getExternalFilesDir(String name){return null;}
}
STUB
cat > "$TMP/stubs/android/content/SharedPreferences.java" <<'STUB'
package android.content;
public interface SharedPreferences {
 java.util.Map<String,?> getAll();
 String getString(String key,String fallback);
 Editor edit();
 interface Editor {
  Editor putString(String key,String value);
  Editor putStringSet(String key,java.util.Set<String>value);
  Editor putInt(String key,int value);
  Editor putLong(String key,long value);
  Editor putFloat(String key,float value);
  Editor putBoolean(String key,boolean value);
  Editor remove(String key);
  boolean commit();void apply();
 }
}
STUB
SOURCE="$SOURCE_ROOT/android/app/src/main/java/io/github/jackotom/hongguo/android/LibraryStore.java"
SDK="$ANDROID_HOME/platforms/android-35/android.jar"
"$JAVA_HOME/bin/javac" --release 17 -encoding UTF-8 -cp "$JSON_JAR:$SDK" -d "$TMP/classes" "$TMP/stubs/android/content/Context.java" "$TMP/stubs/android/content/SharedPreferences.java" "$SOURCE" "$ROOT/android/tests/SearchHistoryStoreTest.java" "$ROOT/android/tests/LibraryStoreRecordingTest.java"
"$JAVA_HOME/bin/java" -cp "$TMP/classes:$JSON_JAR:$SDK" io.github.jackotom.hongguo.android.LibraryStoreRecordingTest
"$JAVA_HOME/bin/javac" --release 17 -encoding UTF-8 -cp "$SDK" -d "$TMP/sdk35" "$SOURCE"
echo 'PASS LibraryStore recording SDK35 compile release=17'
