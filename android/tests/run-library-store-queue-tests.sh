#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
# Runtime JSON is test-only; Android's built-in org.json is used by the APK.
JSON_JAR="${JSON_JAR:-$HOME/.m2/repository/com/vaadin/external/google/android-json/0.0.20131108.vaadin1/android-json-0.0.20131108.vaadin1.jar}"
if [ ! -f "$JSON_JAR" ]; then
  CANDIDATES="$(find "$HOME/.gradle/caches/modules-2/files-2.1/org.json/json" "$HOME/.m2/repository/org/json/json" -name 'json-*.jar' ! -name '*sources*' ! -name '*javadoc*' -print 2>/dev/null || true)"
  JSON_JAR="$(printf '%s\n' "$CANDIDATES" | head -1)"
fi
if [ ! -f "$JSON_JAR" ]; then echo 'TEST_DEPENDENCY_MISSING set JSON_JAR to an org.json runtime jar'; exit 2; fi
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/stubs/android/content" "$TMP/stubs/android/os"
# Recreate only this test's Android signatures for every run. No shared or retained /tmp fixtures are required.
cat > "$TMP/stubs/android/content/Context.java" <<'STUB'
package android.content;
public abstract class Context {
    public static final int MODE_PRIVATE = 0;
    public abstract SharedPreferences getSharedPreferences(String name, int mode);
    public abstract java.io.File getExternalFilesDir(String type);
}
STUB
cat > "$TMP/stubs/android/content/SharedPreferences.java" <<'STUB'
package android.content;
public interface SharedPreferences {
    java.util.Map<String, ?> getAll();
    String getString(String key, String fallback);
    Editor edit();
    interface Editor {
        Editor putString(String key, String value);
        Editor putBoolean(String key, boolean value);
        Editor putInt(String key, int value);
        Editor putLong(String key, long value);
        Editor putFloat(String key, float value);
        Editor putStringSet(String key, java.util.Set<String> values);
        Editor remove(String key);
        void apply();
        boolean commit();
    }
}
STUB
cat > "$TMP/stubs/android/os/Environment.java" <<'STUB'
package android.os;
public final class Environment {
    public static final String DIRECTORY_DOWNLOADS = "Download";
}
STUB
SOURCE="$ROOT/app/src/main/java/io/github/jackotom/hongguo/android/LibraryStore.java"
"$JAVA_HOME/bin/javac" --release 17 -encoding UTF-8 -cp "$JSON_JAR" -d "$TMP/classes" \
  "$TMP/stubs/android/content/Context.java" "$TMP/stubs/android/content/SharedPreferences.java" \
  "$TMP/stubs/android/os/Environment.java" \
  "$SOURCE" "$ROOT/tests/LibraryStoreQueueTest.java"
"$JAVA_HOME/bin/java" -cp "$TMP/classes:$JSON_JAR" io.github.jackotom.hongguo.android.LibraryStoreQueueTest
"$JAVA_HOME/bin/javac" --release 17 -encoding UTF-8 -cp "$ANDROID_HOME/platforms/android-35/android.jar" -d "$TMP/sdk35" "$SOURCE"
echo 'PASS LibraryStore SDK35 compile release=17'
