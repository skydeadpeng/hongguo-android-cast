#!/usr/bin/env bash
# Real production App acceptance in an isolated, muted hosted AVD. No URL injection.
set -euo pipefail
: "${ANDROID_HOME:?}" "${ANDROID_AVD_HOME:?}" "${QA_OUT:?}" "${QA_PRIVATE:?}" "${GITHUB_SHA:?}"
mkdir -p "$QA_OUT" "$QA_PRIVATE" "$ANDROID_AVD_HOME"
ADB="$ANDROID_HOME/platform-tools/adb"
EMULATOR="$ANDROID_HOME/emulator/emulator"
AVDMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager"
SERIAL=emulator-5554
PACKAGE=io.github.jackotom.hongguo.android
STAGE=initialization
status() {
  STAGE="$1"
  python3 - "$QA_OUT/workflow-status.json" "$STAGE" "$GITHUB_SHA" <<'PY'
import json,sys
json.dump({'status':'RUNNING','stage':sys.argv[2],'commit':sys.argv[3],
           'test_scope':'production player component, not browse UI',
           'network_settings_changed':False,'media_url_injected':False},open(sys.argv[1],'w'),indent=2)
PY
  printf 'QA_STAGE=%s\n' "$STAGE"
}
failed() {
  result=$?
  if [ "$result" -ne 0 ]; then
    python3 - "$QA_OUT/workflow-status.json" "$STAGE" "$result" "$GITHUB_SHA" <<'PY'
import json,sys
try:
    result=json.load(open(sys.argv[1]))
except Exception:
    result={}
if result.get('status')!='FAILED' or 'reason' not in result:
    result={'status':'FAILED','stage':sys.argv[2],'commit':sys.argv[4],
            'accepted_as_playback_success':False,'details':'See sanitized report if present; no raw logs published'}
result['command_exit']=int(sys.argv[3])
json.dump(result,open(sys.argv[1],'w'),indent=2)
PY
  fi
}
trap failed EXIT
status create_isolated_avd
# The image and emulator were installed from Google by the preceding workflow step.
printf 'no\n' | "$AVDMANAGER" create avd --name hongguo_public_qa --package 'system-images;android-30;default;x86_64' --device pixel_2 > "$QA_PRIVATE/avd-create.log" 2>&1
status boot_official_emulator
"$EMULATOR" -avd hongguo_public_qa -port 5554 -no-window -no-audio -no-snapshot -no-boot-anim \
  -accel on -gpu swiftshader_indirect -cores 2 -memory 2048 -feature -Vulkan \
  -camera-back none -camera-front none > "$QA_PRIVATE/emulator.log" 2>&1 &
printf '%s\n' "$!" > "$QA_PRIVATE/emulator.pid"
timeout 180s "$ADB" -s "$SERIAL" wait-for-device
ready=false
for attempt in $(seq 1 120); do
  if [ "$(timeout 5s "$ADB" -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] && \
     timeout 5s "$ADB" -s "$SERIAL" shell pm path android 2>/dev/null | grep -q '^package:'; then ready=true; break; fi
  if ! kill -0 "$(cat "$QA_PRIVATE/emulator.pid")" 2>/dev/null; then break; fi
  sleep 2
done
[ "$ready" = true ] || { printf 'QA_ENVIRONMENT=ANDROID_BOOT_OR_PACKAGE_SERVICE_UNAVAILABLE\n'; exit 20; }
kill -0 "$(cat "$QA_PRIVATE/emulator.pid")"
[ "$(timeout 5s "$ADB" -s "$SERIAL" emu avd name | tr -d '\r' | head -1)" = hongguo_public_qa ] || { printf 'QA_ENVIRONMENT=WRONG_AVD_IDENTITY\n'; exit 21; }
status mute_before_app_launch
timeout 10s "$ADB" -s "$SERIAL" shell cmd media_session volume --stream 3 --set 0 > "$QA_PRIVATE/mute.log" 2>&1
timeout 5s "$ADB" -s "$SERIAL" shell input keyevent KEYCODE_WAKEUP > /dev/null
timeout 5s "$ADB" -s "$SERIAL" shell input keyevent 82 > /dev/null
status install_exact_debug_build
timeout 60s "$ADB" -s "$SERIAL" install -t android/app/build/outputs/apk/phone/debug/app-phone-debug.apk > "$QA_PRIVATE/install-app.log" 2>&1
timeout 60s "$ADB" -s "$SERIAL" install -t android/app/build/outputs/apk/androidTest/phone/debug/app-phone-debug-androidTest.apk > "$QA_PRIVATE/install-test.log" 2>&1
if [ "${QA_EPISODE_MODE:-single}" = ten ]; then
  status actual_ten_episode_players
  bash scripts/android-playback-qa/run-ten-episodes.sh
  STAGE=completed
  exit 0
fi
[ "${QA_EPISODE_MODE:-single}" = single ] || { printf 'QA_ENVIRONMENT=INVALID_EPISODE_MODE\n'; exit 22; }
status actual_production_player
# The test runner constructs the normal production intent with no media URL.
# Neither instrumentation output nor raw logcat is published to the repository.
set +e
timeout 300s "$ADB" -s "$SERIAL" shell am instrument -w -r -e isolatedQaAvd true -e qaCommit "$GITHUB_SHA" \
  "$PACKAGE.test/io.github.jackotom.hongguo.android.ProductionPlaybackInstrumentation" \
  > "$QA_PRIVATE/instrumentation.log" 2>&1
instrument_exit=$?
set -e
status collect_sanitized_evidence
for name in report.json first-frame.png after-30s.png; do
  if ! timeout 20s "$ADB" -s "$SERIAL" exec-out run-as "$PACKAGE" cat "files/playback-qa/$name" > "$QA_OUT/$name" 2> "$QA_PRIVATE/pull-$name.err"; then
    rm -f "$QA_OUT/$name"
  fi
done
# adb exec-out may report a successful transport for a remote cat error. Never
# publish those error strings under a screenshot filename.
python3 - "$QA_OUT" <<'PYIMAGE'
import importlib.util,sys
from pathlib import Path
spec=importlib.util.spec_from_file_location('qa_contract', 'scripts/android-playback-qa/verify-report.py')
contract=importlib.util.module_from_spec(spec);spec.loader.exec_module(contract)
for name in ('first-frame.png','after-30s.png'):
    path=Path(sys.argv[1])/name
    if path.exists() and not contract.png_exists(path):path.unlink()
PYIMAGE
# Completion is determined by actual evidence, never by am instrument's exit code.
python3 scripts/android-playback-qa/verify-report.py "$QA_OUT" "$GITHUB_SHA" "$instrument_exit" "$QA_PRIVATE/instrumentation.log"
STAGE=completed
printf 'QA_ACCEPTANCE=REAL_APP_FIRST_FRAME_AND_30_SECONDS\n'
