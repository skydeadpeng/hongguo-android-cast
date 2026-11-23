#!/bin/bash
# Replays the native SDK adapter's digest golden vectors (android/tests/sdk-digest-vectors.json) on an
# arm64 device or emulator: the adapter and its device harness are cross-compiled with the NDK, and the
# pinned SDK image is loaded through both memory carriers. The exit status is 0 only when the image has
# the pinned hash and every vector matches byte for byte on both carriers.
#
#   run-sdk-digest-vectors-device.sh <libmetasec_ml.so> [adb serial]
#
# The image is not in this repository. It is the verified SDK image the app caches at run time (see
# SdkAssets.java: a byte range of the official APK, inflated), which the vectors file pins by SHA-256.
set -euo pipefail
OWN="$(cd "$(dirname "$0")" && pwd)"
CPP="$OWN/../app/src/main/cpp"
IMAGE="${1:-}"
[[ -f "$IMAGE" ]] || { echo "用法：run-sdk-digest-vectors-device.sh <libmetasec_ml.so> [adb 序列号]" >&2; exit 2; }
DEVICE="${2:-}"

PINNED="$(node -e 'process.stdout.write(JSON.parse(require("fs").readFileSync(process.argv[1],"utf8")).imageSha256)' "$OWN/sdk-digest-vectors.json")"
ACTUAL="$(shasum -a 256 "$IMAGE" | cut -c1-64)"
if [[ "$ACTUAL" != "$PINNED" ]]; then
  echo "镜像摘要不符：期望 ${PINNED:0:16}，实际 ${ACTUAL:0:16}" >&2
  exit 1
fi

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
NDK="${ANDROID_NDK:-$SDK/ndk/28.2.13676358}"
CLANG="$(ls -d "$NDK"/toolchains/llvm/prebuilt/*/bin/aarch64-linux-android30-clang 2>/dev/null | head -1)"
[[ -x "$CLANG" ]] || { echo "找不到 NDK 28.2 的 arm64 编译器：$NDK" >&2; exit 1; }
TMP="$(mktemp -d -t hongguo-sdk-digest.XXXXXX)"
trap 'rm -rf "$TMP"' EXIT
"$CLANG" -O2 -Wall -Wextra -I "$CPP" "$OWN/SdkDigestVectorsDevice.c" "$CPP/sdk_digest.c" "$CPP/sdk_tramp.S" -o "$TMP/digest-vectors"

ADB="$SDK/platform-tools/adb"
adb() { if [[ -n "$DEVICE" ]]; then "$ADB" -s "$DEVICE" "$@"; else "$ADB" "$@"; fi; }
REMOTE=/data/local/tmp/hongguo-sdk-digest
adb shell "rm -rf $REMOTE && mkdir -p $REMOTE" >/dev/null
adb push "$TMP/digest-vectors" "$REMOTE/digest-vectors" >/dev/null
adb push "$IMAGE" "$REMOTE/libmetasec_ml.so" >/dev/null
adb push "$OWN/sdk-digest-vectors.json" "$REMOTE/sdk-digest-vectors.json" >/dev/null
adb shell "chmod 755 $REMOTE/digest-vectors"
STATUS=0
for carrier in 1 0; do
  echo "=== carrier=$carrier ($([[ "$carrier" == 1 ]] && echo memfd || echo ashmem)) ==="
  adb shell "$REMOTE/digest-vectors $REMOTE/libmetasec_ml.so $REMOTE/sdk-digest-vectors.json $carrier" | tr -d '\r' || STATUS=1
done
adb shell "rm -rf $REMOTE" >/dev/null
if [[ "$STATUS" -ne 0 ]]; then echo "SDK_DIGEST_VECTORS_FAILED" >&2; exit 1; fi
echo "SDK_DIGEST_VECTORS_OK"
