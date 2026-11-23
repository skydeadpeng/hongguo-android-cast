#!/bin/bash
# Fault handling of the SDK playback adapter (android/app/src/main/cpp/sdk_digest.c) with real signals.
# It needs no SDK image and no device: SdkFaultForwardTest.c builds the adapter's own source in and
# replaces the image entry with a stand-in.
#
#   run-sdk-fault-forward-test.sh             arm64 host (macOS arm64 or Linux aarch64)
#   run-sdk-fault-forward-test.sh --android   an arm64 device or emulator through adb; ANDROID_NDK or
#                                             the SDK's ndk/28.2.13676358 cross-compiles the same test.
#                                             An optional second argument is the adb serial.
set -euo pipefail
OWN="$(cd "$(dirname "$0")" && pwd)"
CPP="$OWN/../app/src/main/cpp"
TMP="$(mktemp -d -t hongguo-sdk-fault.XXXXXX)"
trap 'rm -rf "$TMP"' EXIT

# The process without a handler of its own must die of a fault on another thread during a call, as it
# would without the adapter: 139 = terminated by SIGSEGV (11).
check_child() {
  local status=0
  "$@" child || status=$?
  if [[ "$status" -ne 139 ]]; then
    echo "没有自有处理器的进程本应死于 SIGSEGV(139)，实际退出码 $status" >&2
    return 1
  fi
  echo "SDK_FAULT_FORWARD_CHILD_DIED_BY_SIGSEGV signal=11"
}

DEVICE=
if [[ "${1:-}" == --android ]]; then
  DEVICE="${2:-}"
  SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
  NDK="${ANDROID_NDK:-$SDK/ndk/28.2.13676358}"
  CLANG="$(ls -d "$NDK"/toolchains/llvm/prebuilt/*/bin/aarch64-linux-android30-clang 2>/dev/null | head -1)"
  [[ -x "$CLANG" ]] || { echo "找不到 NDK 28.2 的 arm64 编译器：$NDK" >&2; exit 1; }
  ADB="$SDK/platform-tools/adb"
  # Android spells the trampoline symbols the C way, so the macOS names are not needed here.
  "$CLANG" -O1 -Wall -Wextra -Wno-unused-function -Wno-unused-parameter \
    -I "$CPP" "$OWN/SdkFaultForwardTest.c" "$CPP/sdk_tramp.S" -o "$TMP/fault-forward"
  REMOTE=/data/local/tmp/hongguo-sdk-fault/fault-forward
  adb() { if [[ -n "$DEVICE" ]]; then "$ADB" -s "$DEVICE" "$@"; else "$ADB" "$@"; fi; }
  adb shell "rm -rf /data/local/tmp/hongguo-sdk-fault && mkdir -p /data/local/tmp/hongguo-sdk-fault" >/dev/null
  adb push "$TMP/fault-forward" "$REMOTE" >/dev/null
  adb shell "chmod 755 $REMOTE"
  run() { adb shell "$REMOTE $*"; }
else
  case "$(uname -s):$(uname -m)" in
    Darwin:arm64|Linux:aarch64) ;;
    *) echo "需要 arm64 宿主机（macOS arm64 或 Linux aarch64），或加 --android 在设备上运行。" >&2; exit 1 ;;
  esac
  "${CC:-cc}" -O1 -Wall -Wextra -Wno-unused-function -Wno-unused-parameter -D_XOPEN_SOURCE -Wno-deprecated-declarations \
    -I "$CPP" "$OWN/SdkFaultForwardTest.c" "$CPP/sdk_tramp.S" "$OWN/sdk_tramp_darwin.S" -o "$TMP/fault-forward"
  run() { "$TMP/fault-forward" "$*"; }
fi

run
check_child run

if [[ "${1:-}" == --android ]]; then
  adb shell "rm -rf /data/local/tmp/hongguo-sdk-fault" >/dev/null
fi
