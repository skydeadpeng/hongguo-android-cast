# Production guest playback acceptance

This test is **player-component acceptance**, not normal browse/navigation acceptance.
It installs alongside the unchanged phone debug App, starts its real `MainActivity`,
waits for the production page, and builds the actual `PlayerActivity` intent through
`PlayLauncher`. The intent has no media URL or playInfo. Source resolution, guest POST,
complete MP4 download/verification and decoding remain production operations.

Run only on a new isolated Google Android API 30 x86_64 QA AVD. Do not run on a personal
device or an AVD with accounts, existing App data, or existing playback evidence.
Build the frontend assets normally before `assemblePhoneDebug assemblePhoneDebugAndroidTest`.
Install both APKs, then run:

```
adb shell am instrument -w -r -e isolatedQaAvd true -e qaCommit "$GITHUB_SHA" \
  io.github.jackotom.hongguo.android.test/io.github.jackotom.hongguo.android.ProductionPlaybackInstrumentation
```

Require BOTH `qa_status=passed` and the final report's `status=passed`. Also pass the exact
40-character lowercase build commit as `qaCommit`; the report records it for the host
verifier to match against the build's `GITHUB_SHA`. A shell command
exit code alone is not an instrumentation pass. Failure/blocked results send test status
`-2`, finish with result code `0`, and never print raw exception messages. A missing report,
App crash, runner timeout or absent screenshot is a failure, not a skipped success.
The internal deadline is 240 seconds; use a host timeout of at most 300 seconds.

Only extract these App-private files with `adb exec-out run-as`:

- `files/playback-qa/report.json`
- `files/playback-qa/first-frame.png`
- `files/playback-qa/after-30s.png`

Failure diagnostics contain only fixed stage/status/reason fields and the boolean
`guest_plan_observed`, never the source model or raw exception text. No failure screenshot
is generated, extracted or published: production error UI could include signed URLs.

`playback_diagnostic` adds booleans for a current HUD dialog, terminal dialog state,
decoder preparation, pending source resolution, current status text, and historical media
error presence. Only an explicit literal/anchored-format allowlist classifies the current
App `status` TextView and historical `lastMediaError` in memory. It emits enum codes for
guest identity/login/HTTP/expiry/format/complete-file failures and recognizable network or
decoder messages. Unrecognized text stays `unknown`; a generic guest-request failure does
not establish DNS, TLS, HTTP status, or any hidden cause. Raw text is never persisted.

The initial frame wait does not fail merely because `lastMediaError` retains an earlier
automatic recovery. A current terminal dialog still fails immediately. After the first
actual frame, the same generation/player and historical-error identity must remain stable;
any new error, dialog, interruption or renderer stall fails. Historical recovery is reported
as a boolean, never hidden or counted as proof of successful playback.

The media stream is set to volume zero and muted before either activity opens, and checked
throughout. The runner temporarily adopts only `MODIFY_AUDIO_SETTINGS` via Android's
instrumentation shell API. It never changes network, DNS, proxy, CA, credentials or accounts.
Emulator host audio should also be disabled. Silence evidence is verified Android output
state, not an acoustic recording.

The one-episode queue disables automatic next-episode playback/prefetch, uses auto quality
and 1x speed, and otherwise retains the production player. No preferences or player fields
are rewritten. Read-only reflection reads existing App state. The source plan's guest
provenance plus cold-cache publication confirms the normal production complete-file gate;
the test does not separately call or replace that gate.

First-frame evidence is a successful `PixelCopy` from the actual decoder-backed `VideoView`
surface with nonblank, nonuniform pixels, combined with real MediaPlayer playing state,
position, duration and video dimensions. It is deliberately not an `onPrepared` assertion
and does not synthesize, install or replace renderer callbacks. The App screenshot is saved
only after those checks. The test then requires at least 30 seconds of both wall time and
media position advancement, the same episode, player instance, view and generation, no
error/dialog/buffering/pause, and no five-second position stall. During that interval it
reads the actual surface about once a second, retaining only a hash and numerical counters.
Five seconds without a changed nonblank buffer fails, even if the audio/media clock advances.
This is intentionally conservative and can reject a real static scene lasting five seconds.
The report includes successful surface observations, frame changes and the longest gap.
A final live surface copy must contain a changed frame before the second screenshot is saved.

Do not archive logcat, raw instrumentation output, signed media URLs, source/account models,
keystores, caches, downloads, or full media artifacts. The report is an explicit allowlist
of fixed stage/status codes, public test identities, booleans and numerical measurements.

## Opt-in ten-episode opening check

The original command above remains a schema-1, episode-1, **30-second** acceptance test.
The additional mode is intentionally only an opening check, not ten full-episode playback
or ten 30-second acceptances. Run one instrumentation invocation for each index 1 through
10, once each, on the same newly created isolated AVD. For example, episode 2 is:

```
adb shell am instrument -w -r -e isolatedQaAvd true -e qaCommit "$GITHUB_SHA" \
  -e batchTenEpisodes true -e episodeIndex 2 -e episodeVid 7703412063502093337 \
  io.github.jackotom.hongguo.android.test/io.github.jackotom.hongguo.android.ProductionPlaybackInstrumentation
```

`PlaybackProbeConfig` fixes the series and the first ten official VID/index pairs. Batch
mode requires both selectors to match that list exactly, fixes the required interval at
5,000 milliseconds, and rejects unknown options and non-string arguments. It cannot accept
an arbitrary URL, series, playback response, interval, quality or output path. Omitted batch
mode, or `batchTenEpisodes=false`, still means the original episode 1 and 30 seconds.

Each batch invocation has a 180-second internal deadline, with at most 15 seconds allowed
for continued playback after the first verified frame. Use a 200-second host timeout per
episode. The host continues to the next fixed episode on failure or timeout, without retrying
the failed episode, clearing App/account data, or clicking a refusal dialog. It may stop only
this isolated QA App package between invocations. Normal production recovery is unchanged;
the QA code never initiates another source request or changes production state.

Batch output is under `files/playback-qa/episode-01/` through `episode-10/`:

- `report.json`
- `first-frame.png`
- `after-5s.png`

Each target evidence directory must be empty before its invocation. The host must extract
and publish screenshots only for a final pass. Failed/blocked reports remain restricted to
the existing fixed categories, booleans, public IDs and numeric evidence. No failure screenshot,
raw UI error text, URL, model or log is captured for diagnosis.

Batch reports use `schema=2`, `scope=production_player_component_ten_episode_opening`,
`test_scope=ten_episode_opening_5s_production_player_component`,
`acceptance_mode=ten_episode_opening_5s`, `required_advance_ms=5000`, and stage `continued_5s`.
They record the exact selected episode index/VID, build commit, `BuildConfig` App version,
per-episode status, measured media/wall advancement, and the same provenance and decoder
evidence as the original test. Passing requires both clocks to advance at least five seconds,
at least ten state observations, four successful actual-surface observations and two changed
surface hashes. The same player/view/generation, unchanged media metadata and historical-error
identity, zero muted media output, absence of buffering/errors/terminal dialogs, five-second
stall gates remain in force. The complete MP4 and declared-duration guest gate is unchanged
whenever the production source is an official guest Plan. A static
scene can conservatively fail this opening check; the bounds are not relaxed to obtain a pass.

The first invocation requires an empty playback cache and reports
`cache_scope=cold_first_install` in its environment stage. That label relies on the host's
required new isolated installation, not a claim that cache inspection proves install history.
The production queue intentionally retains its current episode on close and prunes older
out-of-window files when another queue opens. Therefore later episodes report
`cache_scope=independent_episode_identity`, not a globally cold cache. Before launching the
next queue, the probe reads cache filenames and rejects any existing complete or partial
entry for the target series/index, across all quality/ceiling variants. The report includes
`target_playback_cache_absent=true` and the actual `global_cache_empty` value. The probe does
not invoke the mutating cache lookup API, delete cache files, change preferences, clear App
data or inspect accounts. With a one-episode queue and autoplay off, earlier episodes cannot
prefetch later targets. A fresh target cache plus its real guest plan and successful production
cache publication establishes that episode's normal complete-file gate independently.

Batch mode additionally accepts the unchanged production App path under a narrower opening
claim: one and only one registered Plan for the exact series/VID, `source=app`, `auto` quality,
zero ceiling, positive plan dimensions matching the real decoded dimensions, and the current
protected cache identity matching the episode and quality. `currentCache` must be true;
manual downloads and remote playback are refused. An `api` Plan or multiple conflicting
registered Plans cannot pass. The exact Plan object and the protected cache identity must
remain unchanged during all subsequent player samples and screenshot checks. This does not
change the product's source requests, permissions, recovery or decryption behavior.

The production player does not retain its adopted `PreparedMedia` Plan in a readable field.
Therefore this check binds the unique existing registry entry to the current protected cache
identity; it is not direct observation of a captured Plan or decoder URI. That conservative
limitation is explicit as `plan_binding=unique_registered_plan_and_protected_cache_identity`.
App Plans do not provide a reliable declared duration. App evidence consequently uses
`source_category=production_app`, `cache_gate=production_app_cache_publication`,
`duration_validation=decoder_reported_only`, `full_episode_verified=false`,
`plan_identity_stable=true`, and `expected_width`/`expected_height`. It never reports the guest
complete-MP4 verifier or a fabricated expected duration. Guest evidence remains
`source_category=official_guest` with its original verified duration and full-file gate.
The legacy one-episode 30-second test remains guest-only.

An actually observed healthy nonblank decoder frame is separately recorded by
`actual_first_frame_observed=true` even when provenance cannot subsequently be established.
Such a partial observation is not a passing episode, and no failed/blocked screenshot is
extracted or published. `PlaybackProbeSourcePolicyChecks` tests this App gate without a device,
network, media fixture or source injection.

`PlaybackProbeConfigChecks` is a pure no-network JVM executable testing all ten fixed pairs,
legacy/schema separation, both clock thresholds, observation minimums, target-cache name
matching and rejected source/path/interval inputs. It complements
`PlaybackFailureCategoryChecks`; neither claims live device playback.
