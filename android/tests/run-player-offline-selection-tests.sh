#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
SOURCE="${1:-$ROOT/app/src/main/java/io/github/jackotom/hongguo/android/PlayerActivity.java}"
TMP="$(mktemp -d -t hongguo-offline-selection.XXXXXX)"
trap 'python3 -c "import shutil,sys;shutil.rmtree(sys.argv[1])" "$TMP"' EXIT
python3 - "$SOURCE" "$TMP/PlayerOfflineSelector.java" <<'PY'
from pathlib import Path
import re,sys
source=Path(sys.argv[1]).read_text();matches=re.findall(r'\bUri local\s*=\s*([^;]+);',source)
if len(matches)!=1:raise SystemExit('Expected the one real PlayerActivity local-selection expression')
Path(sys.argv[2]).write_text('''package io.github.jackotom.hongguo.android;
final class PlayerOfflineSelector {
 static String first; static int calls;
 static final class Uri {final String value;Uri(String value){this.value=value;}static Uri parse(String value){return new Uri(value);}}
 static Uri downloaded(Object target){calls++;return first==null?null:new Uri(first);}
 static Uri select(boolean offline,String initialUrl,String targetQuality){return select(offline,initialUrl,targetQuality,true);}
 static Uri select(boolean offline,String initialUrl,String targetQuality,boolean allowDownloads){Object target=new Object();String stageQuality=targetQuality;return '''+matches[0]+''';}
}
''')
PY
"$JAVA_HOME/bin/javac" -encoding UTF-8 -d "$TMP/classes" "$TMP/PlayerOfflineSelector.java" "$ROOT/tests/PlayerOfflineSelectionTest.java"
"$JAVA_HOME/bin/java" -cp "$TMP/classes" io.github.jackotom.hongguo.android.PlayerOfflineSelectionTest
