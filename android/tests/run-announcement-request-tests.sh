#!/usr/bin/env bash
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
SOURCE_ROOT="$(cd "$HERE/../.." && pwd)"
if [[ "${1:-}" == --source-root ]]; then SOURCE_ROOT="$2"; shift 2; fi
[[ $# -eq 0 ]] || { echo 'Usage: run-announcement-request-tests.sh [--source-root PROJECT]' >&2; exit 64; }
JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
JSON_JAR="${JSON_JAR:-$HOME/.m2/repository/com/vaadin/external/google/android-json/0.0.20131108.vaadin1/android-json-0.0.20131108.vaadin1.jar}"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
SOURCE="$SOURCE_ROOT/android/app/src/main/java/io/github/jackotom/hongguo/android"
"$JAVA_HOME/bin/javac" --release 17 -encoding UTF-8 -cp "$JSON_JAR" -d "$TMP" \
 "$SOURCE/AnnouncementRequests.java" "$SOURCE/AnnouncementClient.java" "$SOURCE/UpdatePolicy.java" "$HERE/AnnouncementRequestsTest.java"
"$JAVA_HOME/bin/java" -cp "$TMP:$JSON_JAR" io.github.jackotom.hongguo.android.AnnouncementRequestsTest
python3 - "$SOURCE/NativeBridge.java" <<'PY'
import pathlib, sys
source = pathlib.Path(sys.argv[1]).read_text()
dispatch = source[source.index('@JavascriptInterface public void postMessage'):source.index('private void startRegisteredModel')]
assert dispatch.index('if (closed || !activity.trusted()) return;') < dispatch.index('if (method.equals("getAnnouncements"))') < dispatch.index('ThreadPoolExecutor executor =')
assert 'AnnouncementRequests.fetch(result -> respond(id, result, null));' in dispatch
assert 'case "getAnnouncements"' not in source
close = source[source.index('void close()'):]
assert 'AnnouncementRequests' not in close
respond = source[source.index('private void respond('):source.index('void close()')]
assert 'if (closed) return;' in respond and 'if (!closed) activity.script(source)' in respond
print('ANNOUNCEMENT_DISPATCH_OK checks=6 playbackLaneBypassed=true lifetimeChecksRetained=true')
PY
