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
