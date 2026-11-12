#!/usr/bin/env bash
# One already-verified fresh QA AVD, ten fixed public episode identities, no retry.
set -euo pipefail
: "${ANDROID_HOME:?}" "${QA_OUT:?}" "${QA_PRIVATE:?}" "${GITHUB_SHA:?}"
ADB="$ANDROID_HOME/platform-tools/adb"
SERIAL=emulator-5554
PACKAGE=io.github.jackotom.hongguo.android
CHECKER=scripts/android-playback-qa/verify-ten-episodes.py
python3 "$CHECKER" --summary "$QA_OUT" "$GITHUB_SHA"
# Materialize the fixed manifest before spawning adb: adb shell may consume stdin.
mapfile -t episode_rows < <(python3 "$CHECKER" --list)
if [ "${#episode_rows[@]}" -ne 10 ]; then
  printf 'QA_BATCH_STOP=incomplete_fixed_manifest\n'
  python3 "$CHECKER" --finish "$QA_OUT" "$GITHUB_SHA"
  exit 1
fi
for row in "${episode_rows[@]}"; do
  IFS=$'\t' read -r index vid <<< "$row"
  key=$(printf 'episode-%02d' "$index")
  mkdir -p "$QA_OUT/$key" "$QA_PRIVATE/$key"
  # Stop only the disposable QA package. Do not wipe App data, account data or caches.
  if ! timeout 10s "$ADB" -s "$SERIAL" shell am force-stop "$PACKAGE" < /dev/null > "$QA_PRIVATE/$key/stop-before.log" 2>&1; then
    printf 'QA_BATCH_STOP=unable_to_stop_previous_qa_process\n'; break
  fi
  printf 'QA_EPISODE_START index=%s vid=%s\n' "$index" "$vid"
  set +e
  timeout 200s "$ADB" -s "$SERIAL" shell am instrument -w -r \
    -e isolatedQaAvd true -e qaCommit "$GITHUB_SHA" -e batchTenEpisodes true \
    -e episodeIndex "$index" -e episodeVid "$vid" \
    "$PACKAGE.test/io.github.jackotom.hongguo.android.ProductionPlaybackInstrumentation" \
    < /dev/null > "$QA_PRIVATE/$key/instrumentation.log" 2>&1
  instrument_exit=$?
  timeout 10s "$ADB" -s "$SERIAL" shell am force-stop "$PACKAGE" < /dev/null > "$QA_PRIVATE/$key/stop-after.log" 2>&1
  stop_exit=$?
  set -e
  if ! timeout 10s "$ADB" -s "$SERIAL" exec-out run-as "$PACKAGE" cat "files/playback-qa/$key/report.json" \
      < /dev/null > "$QA_OUT/$key/report.json" 2> "$QA_PRIVATE/$key/pull-report.err"; then
    rm -f "$QA_OUT/$key/report.json"
  fi
  if python3 "$CHECKER" --should-pull-images "$QA_OUT/$key" "$GITHUB_SHA" "$index"; then
    for name in first-frame.png after-5s.png; do
      if ! timeout 10s "$ADB" -s "$SERIAL" exec-out run-as "$PACKAGE" cat "files/playback-qa/$key/$name" \
          < /dev/null > "$QA_OUT/$key/$name" 2> "$QA_PRIVATE/$key/pull-$name.err"; then
        rm -f "$QA_OUT/$key/$name"
      fi
    done
  fi
  python3 "$CHECKER" --episode "$QA_OUT/$key" "$GITHUB_SHA" "$index" "$instrument_exit" "$QA_PRIVATE/$key/instrumentation.log"
  python3 "$CHECKER" --summary "$QA_OUT" "$GITHUB_SHA"
  if [ "$stop_exit" -ne 0 ]; then printf 'QA_BATCH_STOP=unable_to_confirm_current_qa_process_stopped\n'; break; fi
  sleep 1
done
python3 "$CHECKER" --finish "$QA_OUT" "$GITHUB_SHA"
printf 'QA_ACCEPTANCE=TEN_EPISODES_REAL_FIRST_FRAME_AND_5_SECONDS_EACH\n'
