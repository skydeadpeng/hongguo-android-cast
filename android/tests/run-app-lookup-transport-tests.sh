#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SOURCE_ROOT="$ROOT"
if [ "${1:-}" = '--source-root' ]; then SOURCE_ROOT="$2"; shift 2; fi
if [ "$#" -ne 0 ]; then echo 'USAGE run-app-lookup-transport-tests.sh [--source-root PROJECT]' >&2; exit 2; fi
JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
JSON_JAR="${JSON_JAR:-$HOME/.m2/repository/com/vaadin/external/google/android-json/0.0.20131108.vaadin1/android-json-0.0.20131108.vaadin1.jar}"
TMP="$(mktemp -d -t hongguo-app-lookup.XXXXXX)";trap 'rm -rf "$TMP"' EXIT
cat > "$TMP/NetworkGuard.java" <<'STUB'
package io.github.jackotom.hongguo.android;
import java.io.*;import java.net.*;import java.util.*;
final class NetworkGuard {
 static Exception failure;static int lookups;
 static URI validate(String value,boolean official)throws Exception{return new URI(value);}
 static InetAddress[]publicAddresses(URI uri)throws IOException{lookups++;if(failure instanceof IOException)throw(IOException)failure;throw new IOException("synthetic unavailable",failure);}
 static long streamBody(InputStream input,OutputStream output,Map<String,String>headers,long max,long deadline)throws IOException{throw new AssertionError("no socket or response expected");}
}
STUB
"$JAVA_HOME/bin/javac" -encoding UTF-8 -cp "$JSON_JAR" -d "$TMP/classes" "$TMP/NetworkGuard.java" "$SOURCE_ROOT/android/app/src/main/java/io/github/jackotom/hongguo/android/AppTransport.java" "$ROOT/android/tests/AppTransportLookupTest.java"
"$JAVA_HOME/bin/java" -cp "$TMP/classes:$JSON_JAR" io.github.jackotom.hongguo.android.AppTransportLookupTest
