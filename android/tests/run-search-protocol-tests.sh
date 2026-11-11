#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
if [[ "${1:-}" = '--source-root' ]];then ROOT="$2";shift 2;fi
if [[ $# -ne 0 ]];then echo 'USAGE run-search-protocol-tests.sh [--source-root PROJECT]' >&2;exit 2;fi
JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
JSON_JAR="${JSON_JAR:-$HOME/.m2/repository/com/vaadin/external/google/android-json/0.0.20131108.vaadin1/android-json-0.0.20131108.vaadin1.jar}"
TMP="$(mktemp -d -t hongguo-search-tests.XXXXXX)";trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/android/content"
cat > "$TMP/android/content/SharedPreferences.java" <<'STUB'
package android.content; public interface SharedPreferences{String getString(String key,String fallback);Editor edit();interface Editor{Editor putString(String key,String value);boolean commit();}}
STUB
cat > "$TMP/android/content/Context.java" <<'STUB'
package android.content; public abstract class Context{public static final int MODE_PRIVATE=0;public abstract SharedPreferences getSharedPreferences(String name,int mode);}
STUB
SRC="$ROOT/android/app/src/main/java/io/github/jackotom/hongguo/android"
"$JAVA_HOME/bin/javac" --release 17 -encoding UTF-8 -cp "$JSON_JAR" -d "$TMP/classes" "$TMP"/android/content/*.java "$SRC/SearchProtocol.java" "$SRC/SearchClient.java" "$SRC/AppTransport.java" "$SRC/NetworkGuard.java" "$ROOT/android/tests/SearchProtocolTest.java"
"$JAVA_HOME/bin/java" -cp "$TMP/classes:$JSON_JAR" io.github.jackotom.hongguo.android.SearchProtocolTest "$ROOT/android/tests/search-protocol-vectors.json"
