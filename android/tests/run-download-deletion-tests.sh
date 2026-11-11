#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SOURCE_ROOT="$ROOT"
if [ "${1:-}" = '--source-root' ]; then SOURCE_ROOT="$2"; shift 2; fi
if [ "$#" -ne 0 ]; then echo 'USAGE run-download-deletion-tests.sh [--source-root PROJECT]' >&2; exit 2; fi
JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
JSON_JAR="${JSON_JAR:-$HOME/.m2/repository/com/vaadin/external/google/android-json/0.0.20131108.vaadin1/android-json-0.0.20131108.vaadin1.jar}"
TMP="$(mktemp -d -t hongguo-download-delete.XXXXXX)";trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/stubs/android/content"
cat > "$TMP/stubs/android/content/Context.java" <<'STUB'
package android.content;
public abstract class Context {public static final int MODE_PRIVATE=0;public abstract SharedPreferences getSharedPreferences(String name,int mode);public java.io.File getExternalFilesDir(String name){return null;}}
STUB
cat > "$TMP/stubs/android/content/SharedPreferences.java" <<'STUB'
package android.content;
public interface SharedPreferences {java.util.Map<String,?>getAll();String getString(String key,String fallback);Editor edit();interface Editor {Editor putString(String key,String value);Editor putStringSet(String key,java.util.Set<String>value);Editor putInt(String key,int value);Editor putLong(String key,long value);Editor putFloat(String key,float value);Editor putBoolean(String key,boolean value);Editor remove(String key);boolean commit();void apply();}}
STUB
SRC="$SOURCE_ROOT/android/app/src/main/java/io/github/jackotom/hongguo/android"
SDK="$ANDROID_HOME/platforms/android-35/android.jar"
if [ ! -f "$SRC/DownloadDeletion.java" ]; then
 cat > "$TMP/MissingDeletion.java" <<'JAVA'
public class MissingDeletion {public static void main(String[]args){try{Class.forName("io.github.jackotom.hongguo.android.DownloadDeletion");throw new AssertionError("unexpected helper");}catch(ClassNotFoundException missing){System.out.println("FAIL missing native download deletion API: DownloadDeletion / deleteDownload");System.exit(1);}}}
JAVA
 "$JAVA_HOME/bin/javac" --release 17 -encoding UTF-8 -cp "$JSON_JAR:$SDK" -d "$TMP/classes" "$TMP/stubs/android/content/Context.java" "$TMP/stubs/android/content/SharedPreferences.java" "$SRC/LibraryStore.java" "$TMP/MissingDeletion.java"
 "$JAVA_HOME/bin/java" -cp "$TMP/classes:$JSON_JAR:$SDK" MissingDeletion
 exit 1
fi
"$JAVA_HOME/bin/javac" --release 17 -encoding UTF-8 -cp "$JSON_JAR:$SDK" -d "$TMP/classes" "$TMP/stubs/android/content/Context.java" "$TMP/stubs/android/content/SharedPreferences.java" "$SRC/LibraryStore.java" "$SRC/DownloadDeletion.java" "$ROOT/android/tests/SearchHistoryStoreTest.java" "$ROOT/android/tests/DownloadDeletionTest.java"
"$JAVA_HOME/bin/java" -cp "$TMP/classes:$JSON_JAR:$SDK" io.github.jackotom.hongguo.android.DownloadDeletionTest
python3 - "$SRC/NativeBridge.java" <<'PY'
import pathlib,sys
s=pathlib.Path(sys.argv[1]).read_text()
assert '"getDownloads", "deleteDownload", "playDownload"' in s
block=s.split('case "deleteDownload": {',1)[1].split('case "getPlaybackCache"',1)[0]
assert 'DownloadDeletion.confirmedId(params)' in block and 'prepareDownloadDeletion(activity,owner)' in block
assert 'finally {UpdateInstallCheckpoint.release(owner);}' in block
assert '(method.equals("download") || method.equals("playDownload")) ? UpdateInstallCheckpoint.operation() : null' in s
assert '.setDestinationInExternalFilesDir(activity, Environment.DIRECTORY_DOWNLOADS, downloadFilename)' in s
print('PASS NativeBridge deletion RPC worker=ordinary confirmedId=required gate=exclusive release=finally playDownloadReadLease=true futureManagedMapping=true')
PY
"$JAVA_HOME/bin/javac" --release 17 -encoding UTF-8 -cp "$SDK" -d "$TMP/sdk35" "$SRC/LibraryStore.java" "$SRC/DownloadDeletion.java"
echo 'PASS DownloadDeletion SDK35 productionCompile=true files=2 deviceUntouched=true'
