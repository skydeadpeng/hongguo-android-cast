#!/bin/bash
set -euo pipefail
TEST_ROOT="$(cd "$(dirname "$0")" && pwd)"
SOURCE_ROOT="$(cd "$TEST_ROOT/../.." && pwd)"
if [ "${1:-}" = '--source-root' ]; then SOURCE_ROOT="$2"; shift 2; fi
[ "$#" -eq 0 ] || exit 2
JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
TMP="$(mktemp -d -t hongguo-media-headers.XXXXXX)"
trap 'rm -rf "$TMP"' EXIT
# The pre-fix client writes inline. Lift those exact unchanged statements only in
# the test copy so the same wire expectations exercise its actual old formatter.
python3 - "$SOURCE_ROOT/android/app/src/main/java/io/github/jackotom/hongguo/android/NetworkGuard.java" "$TMP/NetworkGuard.java" <<'PYTHON'
import pathlib,sys
source=pathlib.Path(sys.argv[1]).read_text()
if 'static void writeMediaRequest(' not in source:
    start=source.index('                        String path = uri.getRawPath();',source.index('static void download(String value, File destination, long maxBytes, String userAgent, String referer)'))
    end=source.index('                        socket.getOutputStream().flush();',start)+len('                        socket.getOutputStream().flush();')
    body=source[start:end].replace('socket.getOutputStream()','output')
    method='\n    static void writeMediaRequest(OutputStream output, URI uri, String userAgent, String referer) throws IOException {\n'+body+'\n    }\n'
    at=source.rfind('}')
    source=source[:at]+method+source[at:]
pathlib.Path(sys.argv[2]).write_text(source)
PYTHON
"$JAVA_HOME/bin/javac" -encoding UTF-8 -d "$TMP/classes" "$TMP/NetworkGuard.java" "$TEST_ROOT/NetworkGuardRequestHeadersTest.java"
"$JAVA_HOME/bin/java" -cp "$TMP/classes" io.github.jackotom.hongguo.android.NetworkGuardRequestHeadersTest
