#!/bin/bash
set -euo pipefail
TEST_ROOT="$(cd "$(dirname "$0")" && pwd)"
SOURCE_ROOT="$(cd "$TEST_ROOT/../.." && pwd)"
while [[ $# -gt 0 ]]; do
    case "$1" in
        --source-root) SOURCE_ROOT="$2"; shift 2;;
        *) echo 'USAGE run-mobile-layout-tests.sh [--source-root PROJECT]' >&2; exit 64;;
    esac
done
JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
SRC="$SOURCE_ROOT/android/app/src/main/java/io/github/jackotom/hongguo/android"
TMP="$(mktemp -d -t hongguo-mobile-layout.XXXXXX)"
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/sdk35" "$TMP/classes" "$TMP/io/github/jackotom/hongguo/android" "$TMP/android/text"
printf '%s\n' 'package io.github.jackotom.hongguo.android; public final class BuildConfig {public static final boolean IS_TV=false,DEBUG=true;public static final String VERSION_NAME="fixture";}' > "$TMP/io/github/jackotom/hongguo/android/BuildConfig.java"
"$JAVA_HOME/bin/javac" -encoding UTF-8 -source 17 -target 17 -cp "$ANDROID_HOME/platforms/android-35/android.jar" -d "$TMP/sdk35" "$TMP/io/github/jackotom/hongguo/android/BuildConfig.java" "$SRC/"*.java
echo 'PASS MobileLayout SDK35 allProductionCompile=true deviceUntouched=true'
python3 "$TEST_ROOT/mobile-layout-harness.py" "$SRC" "$TMP/MobileLayoutHarness.java"
printf '%s\n' 'package android.text; public final class TextUtils {public enum TruncateAt {END}}' > "$TMP/android/text/TextUtils.java"
SOURCES=("$TMP/MobileLayoutHarness.java" "$TEST_ROOT/MobileLayoutDoubles.java" "$TMP/android/text/TextUtils.java")
if [[ -f "$SRC/MobileLayoutPolicy.java" ]]; then
    SOURCES+=("$SRC/MobileLayoutPolicy.java" "$TEST_ROOT/MobileLayoutTest.java")
    TEST_CLASS=MobileLayoutTest
else
    SOURCES+=("$TEST_ROOT/MobileLayoutBaselineTest.java")
    TEST_CLASS=MobileLayoutBaselineTest
fi
"$JAVA_HOME/bin/javac" -encoding UTF-8 -source 17 -target 17 -d "$TMP/classes" "${SOURCES[@]}"
"$JAVA_HOME/bin/java" -cp "$TMP/classes" "io.github.jackotom.hongguo.android.$TEST_CLASS"
python3 - "$SOURCE_ROOT" <<'PY'
from pathlib import Path
import sys, xml.etree.ElementTree as ET
root=Path(sys.argv[1]);src=root/'android/app/src/main/java/io/github/jackotom/hongguo/android'
ns='{http://schemas.android.com/apk/res/android}'
phone=ET.parse(root/'android/app/src/phone/AndroidManifest.xml').getroot()
main=next(item for item in phone.findall('./application/activity') if item.get(ns+'name')=='.MainActivity')
assert main.get(ns+'windowSoftInputMode')=='adjustResize', 'phone API26–29 must resize when IME opens'
tv=ET.parse(root/'android/app/src/tv/AndroidManifest.xml').getroot()
assert all(item.get(ns+'windowSoftInputMode') is None for item in tv.findall('./application/activity')), 'TV soft input policy changed'
main=(src/'MainActivity.java').read_text();player=(src/'PlayerActivity.java').read_text()
assert 'int imeBottom = BuildConfig.IS_TV ? 0 : insets.getInsets(WindowInsets.Type.ime()).bottom;' in main
assert 'applyWebInsets(container, bars.left, bars.top, bars.right, bars.bottom, imeBottom);' in main
assert 'if (!BuildConfig.IS_TV) getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);' in main
assert 'if (!BuildConfig.IS_TV) installPhoneLayout();' in player
assert 'WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout()' in player
assert 'DisplayCutout cutout = insets.getDisplayCutout();' in player
assert 'if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) applyPhoneLayout();' in player
print('PASS STATIC MobileLayout phoneIME_API26to35=true listenerWiring=true cutout_API28plus=true TVmanifestUntouched=true')
PY
