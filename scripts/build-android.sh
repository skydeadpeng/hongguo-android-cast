#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

MODE="${1:---debug}"
if [[ "$#" -gt 1 ]]; then
  echo 'Unknown build mode: pass exactly one mode.' >&2
  exit 2
fi
case "$MODE" in
--debug) ;;
--tv-release-compatible|--phone-release-compatible|--tvnative-release-compatible) ;;
--tv-release|--phone-release|--tvnative-release)
    for name in HONGGUO_RELEASE_KEYSTORE HONGGUO_RELEASE_STORE_PASSWORD HONGGUO_RELEASE_KEY_ALIAS; do
      [[ -n "${!name:-}" ]] || { printf 'Missing %s for external release signing.\n' "$name" >&2; exit 2; }
    done
    [[ -f "$HONGGUO_RELEASE_KEYSTORE" ]] || { echo 'External signing keystore file is missing.' >&2; exit 2; }
    ;;
  --help|-h)
    cat <<'HELP'
Usage: bash scripts/build-android.sh [mode]
  --debug                   Build phone and TV debug APKs (default).
  --tv-release-compatible   Build non-debuggable TV release using the existing debug install certificate.
  --tv-release              Build TV release using external HONGGUO_RELEASE_* signing credentials.
  --phone-release-compatible Build phone release using the existing debug install certificate.
  --phone-release           Build phone release using external signing credentials.
  --tvnative-release-compatible Build the WebView-free native TV release with the debug install certificate.
  --tvnative-release        Build the WebView-free native TV release using external signing credentials.

External signing environment: HONGGUO_RELEASE_KEYSTORE,
HONGGUO_RELEASE_STORE_PASSWORD, HONGGUO_RELEASE_KEY_ALIAS,
and optional HONGGUO_RELEASE_KEY_PASSWORD (defaults to the store password).
Compatible mode uses ~/.android/debug.keystore or HONGGUO_COMPAT_KEYSTORE.
Private keys are read in place, never copied to the repository or distribution.
HELP
    exit 0
    ;;
  *) echo 'Unknown build mode. Use --help.' >&2; exit 2 ;;
esac

compatible_node() {
  "$1" -e 'const [major, minor] = process.versions.node.split(".").map(Number); process.exit(major > 22 || (major === 22 && minor >= 12) ? 0 : 1)' >/dev/null 2>&1
}
if ! command -v node >/dev/null 2>&1 || ! compatible_node "$(command -v node)"; then
  for candidate in "$HOME"/.nvm/versions/node/v*/bin/node /opt/homebrew/opt/node@22/bin/node; do
    if [[ -x "$candidate" ]] && compatible_node "$candidate"; then
      export PATH="$(dirname "$candidate"):$PATH"
      break
    fi
  done
fi
compatible_node "$(command -v node)" || { echo '构建需要 Node >=22.12。' >&2; exit 1; }

if [[ -z "${JAVA_HOME:-}" && -d '/Applications/Android Studio.app/Contents/jbr/Contents/Home' ]]; then
  export JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home'
fi
export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
[[ -f "$ANDROID_HOME/platforms/android-35/android.jar" ]] || { echo '请安装 Android SDK Platform 35。' >&2; exit 1; }
[[ -d node_modules ]] || { echo '请先运行 npm ci。' >&2; exit 1; }

npm run build:android:ui
GRADLE_ARGS=(--no-daemon assemblePhoneDebug assembleTvDebug assembleTvNativeDebug)
if [[ "$MODE" == --tv-release-compatible ]]; then
  GRADLE_ARGS=(--no-daemon assembleTvRelease -PhongguoReleaseSigning=compatible)
elif [[ "$MODE" == --tv-release ]]; then
  GRADLE_ARGS=(--no-daemon assembleTvRelease -PhongguoReleaseSigning=external)
elif [[ "$MODE" == --phone-release-compatible ]]; then
  GRADLE_ARGS=(--no-daemon assemblePhoneRelease -PhongguoReleaseSigning=compatible)
elif [[ "$MODE" == --phone-release ]]; then
  GRADLE_ARGS=(--no-daemon assemblePhoneRelease -PhongguoReleaseSigning=external)
elif [[ "$MODE" == --tvnative-release-compatible ]]; then
  GRADLE_ARGS=(--no-daemon assembleTvNativeRelease -PhongguoReleaseSigning=compatible)
elif [[ "$MODE" == --tvnative-release ]]; then
  GRADLE_ARGS=(--no-daemon assembleTvNativeRelease -PhongguoReleaseSigning=external)
fi
if [[ "${GRADLE_OFFLINE:-0}" == 1 ]]; then GRADLE_ARGS=(--offline "${GRADLE_ARGS[@]}"); fi
if command -v gradle >/dev/null 2>&1; then
  gradle -p "$ROOT/android" "${GRADLE_ARGS[@]}"
elif [[ -x android/gradlew ]]; then
  (cd android && ./gradlew "${GRADLE_ARGS[@]}")
else
  echo '请安装 Gradle 8.13，或使用 Android Studio 打开 android 工程。' >&2
  exit 1
fi

VERSION="$(sed -n "s/.*versionName '\([^']*\)'.*/\1/p" android/app/build.gradle | head -1)"
[[ -n "$VERSION" ]] || VERSION=dev
mkdir -p dist/android
if [[ "$MODE" == --debug ]]; then
  for flavor in phone tv; do
    cp "android/app/build/outputs/apk/$flavor/debug/app-$flavor-debug.apk" "dist/android/Hongguo-$VERSION-$flavor-debug.apk"
  done
  # tvNative is an independent version line; name it with its own versionName.
  NATIVE_VERSION="$(sed -n '/tvNative {/,/^        }/s/.*versionName '\''\([^'\'']*\)'\''.*/\1/p' android/app/build.gradle | head -1)"
  cp "android/app/build/outputs/apk/tvNative/debug/app-tvNative-debug.apk" \
     "dist/android/HongguoNative-${NATIVE_VERSION:-dev}-tvNative-debug.apk"
  shasum -a 256 dist/android/Hongguo-*-*-debug.apk > dist/android/SHA256SUMS.txt
  SUMS=dist/android/SHA256SUMS.txt
else
  DEVICE=tv
  [[ "$MODE" != --phone-release && "$MODE" != --phone-release-compatible ]] || DEVICE=phone
  if [[ "$MODE" == --tvnative-release || "$MODE" == --tvnative-release-compatible ]]; then
    # tvNative is an independent version line; the shared release checker only
    # knows the phone/tv WebView flavors, so it is not run for this APK yet.
    NATIVE_VERSION="$(sed -n '/tvNative {/,/^        }/s/.*versionName '\''\([^'\'']*\)'\''.*/\1/p' android/app/build.gradle | head -1)"
    SUFFIX="tvNative-release"
    [[ "$MODE" != *-release-compatible ]] || SUFFIX="tvNative-release-compatible"
    OUTPUT="dist/android/HongguoNative-${NATIVE_VERSION:-dev}-$SUFFIX.apk"
    cp "android/app/build/outputs/apk/tvNative/release/app-tvNative-release.apk" "$OUTPUT"
    SUMS="dist/android/SHA256SUMS-native-$SUFFIX.txt"
    shasum -a 256 "$OUTPUT" > "$SUMS"
    printf '\nAPK_OUTPUT=%s/dist/android\n' "$ROOT"
    cat "$SUMS"
    exit 0
  fi
  SUFFIX="$DEVICE-release"
  [[ "$MODE" != *-release-compatible ]] || SUFFIX="$DEVICE-release-compatible"
  OUTPUT="dist/android/Hongguo-$VERSION-$SUFFIX.apk"
  cp "android/app/build/outputs/apk/$DEVICE/release/app-$DEVICE-release.apk" "$OUTPUT"
  CHECK_ARGS=(--apk "$OUTPUT" --device "$DEVICE")
  if [[ "$MODE" == *-release-compatible && -n "${HONGGUO_COMPAT_REFERENCE_APK:-}" ]]; then
    CHECK_ARGS+=(--compatible-with "$HONGGUO_COMPAT_REFERENCE_APK")
  fi
  node scripts/check-android-release.mjs "${CHECK_ARGS[@]}"
  SUMS="dist/android/SHA256SUMS-$VERSION-$SUFFIX.txt"
  shasum -a 256 "$OUTPUT" > "$SUMS"
fi
printf '\nAPK_OUTPUT=%s/dist/android\n' "$ROOT"
cat "$SUMS"
