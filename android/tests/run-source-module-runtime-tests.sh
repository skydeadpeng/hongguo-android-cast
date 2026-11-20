#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
if [[ ! -x "$JAVA_HOME/bin/javac" ]]; then JAVA_HOME="$(dirname "$(dirname "$(command -v javac)")")"; fi
SDK_JAR="${SDK_JAR:-${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}/platforms/android-35/android.jar}"
CACHE="${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1"
cached_jar() { find "$CACHE/$1" -name "$2" -type f -print -quit 2>/dev/null || true; }
RHINO_JAR="${RHINO_JAR:-$(cached_jar org.mozilla/rhino/1.8.1 rhino-1.8.1.jar)}"
JSON_JAR="${JSON_JAR:-$(cached_jar org.json/json/20240303 json-20240303.jar)}"
OKHTTP_JAR="${OKHTTP_JAR:-$(cached_jar com.squareup.okhttp3/okhttp/4.12.0 okhttp-4.12.0.jar)}"
OKIO_JAR="${OKIO_JAR:-$(cached_jar com.squareup.okio/okio-jvm/3.6.0 okio-jvm-3.6.0.jar)}"
KOTLIN_JAR="${KOTLIN_JAR:-$(cached_jar org.jetbrains.kotlin/kotlin-stdlib/1.9.10 kotlin-stdlib-1.9.10.jar)}"
JSOUP_JAR="${JSOUP_JAR:-$(cached_jar org.jsoup/jsoup/1.23.2 jsoup-1.23.2.jar)}"
for name in SDK_JAR RHINO_JAR JSON_JAR OKHTTP_JAR OKIO_JAR KOTLIN_JAR JSOUP_JAR; do
  [[ -f "${!name}" ]] || { printf 'Missing test dependency: %s\n' "$name" >&2; exit 2; }
done
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
node "$ROOT/scripts/build-android-source-modules.cjs" "$TMP"
for id in $(node -p 'require("./vendor/source-modules/index.json").modules.map(m => m.id).join(" ")'); do
  for name in manifest.json main.js; do
    cmp "$ROOT/vendor/source-modules/$id/$name" "$TMP/source-modules/$id/$name"
  done
done
[[ "$(find "$TMP/source-modules" -mindepth 1 -maxdepth 1 -type d | wc -l | tr -d ' ')" == 15 ]]
node - "$ROOT" "$TMP" <<'NODE'
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const [repo, dir] = process.argv.slice(2);
const { build, SOURCE_IDS } = require(path.join(repo, 'scripts/build-android-source-modules.cjs'));
const tampered = path.join(dir, 'tampered');
fs.mkdirSync(tampered);
fs.copyFileSync(path.join(repo, 'vendor/source-modules/index.json'), path.join(tampered, 'index.json'));
for (const id of SOURCE_IDS) fs.cpSync(path.join(repo, 'vendor/source-modules', id), path.join(tampered, id), { recursive: true });
fs.appendFileSync(path.join(tampered, 'guipian/main.js'), '\n// Modified fixture\n');
assert.throws(() => build(dir, tampered), /Android source SHA-256 mismatch: guipian\/main\.js/);
assert.equal(fs.existsSync(path.join(dir, 'source-modules/index.json')), true);
NODE
CP="$JSON_JAR:$SDK_JAR:$RHINO_JAR:$OKHTTP_JAR:$OKIO_JAR:$KOTLIN_JAR:$JSOUP_JAR"
SRC="$ROOT/android/app/src/main/java/io/github/jackotom/hongguo/android"
"$JAVA_HOME/bin/javac" -encoding UTF-8 -cp "$CP" -d "$TMP/classes" "$SRC/NetworkGuard.java" "$SRC/SourceModuleRuntime.java" "$SRC/SourceRuntimeSupport.java" "$SRC/SourceRuntimeState.java" "$ROOT/android/tests/SourceModuleRuntimeTest.java"
"$JAVA_HOME/bin/java" -cp "$TMP/classes:$CP" io.github.jackotom.hongguo.android.SourceModuleRuntimeTest "$TMP/source-modules"
