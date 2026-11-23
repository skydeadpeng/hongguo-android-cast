#!/bin/bash
# Fault handling of the SDK playback adapter (android/app/src/main/cpp/sdk_digest.c) with real signals,
# on an arm64 host. It needs no SDK image and no device: SdkFaultForwardTest.c builds the adapter's own
# source in and replaces the image entry with a stand-in.
set -euo pipefail
OWN="$(cd "$(dirname "$0")" && pwd)"
CPP="$OWN/../app/src/main/cpp"
case "$(uname -s):$(uname -m)" in
  Darwin:arm64|Linux:aarch64) ;;
  *) echo "需要 arm64 宿主机（macOS arm64 或 Linux aarch64）。" >&2; exit 1 ;;
esac
CC="${CC:-cc}"
TMP="$(mktemp -d -t hongguo-sdk-fault.XXXXXX)"
trap 'rm -rf "$TMP"' EXIT
"$CC" -O1 -Wall -Wextra -Wno-unused-function -Wno-unused-parameter -D_XOPEN_SOURCE -Wno-deprecated-declarations \
  -I "$CPP" "$OWN/SdkFaultForwardTest.c" "$CPP/sdk_tramp.S" "$OWN/sdk_tramp_darwin.S" -o "$TMP/fault-forward"
"$TMP/fault-forward"
# A process that has no handler of its own must still die of a fault on another thread during a call,
# as it would without the adapter: 139 = terminated by SIGSEGV (11).
STATUS=0
"$TMP/fault-forward" child || STATUS=$?
if [[ "$STATUS" -ne 139 ]]; then
  echo "没有自有处理器的进程本应死于 SIGSEGV(139)，实际退出码 $STATUS" >&2
  exit 1
fi
echo "SDK_FAULT_FORWARD_CHILD_DIED_BY_SIGSEGV signal=11"
