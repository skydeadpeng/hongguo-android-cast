# Android official public guest playback follow-up

## Confirmed route and scope

On 2026-11-11, the official web player's current public fallback used a normal
POST to `/api/video/protected/fallback-play-info`, with only `item_id` in JSON.
The official PC flow selects it when login is not required and encrypted browser
playback is not selected. A page's `protectedPlayback` flag alone does not mean
that it only provides a preview. The loader's `videoList` describes recommended
items, not a media source map.

This change handles that explicit guest route for Android's native `auto` source
resolution. It does not change App signatures, claim App content types from web
category numbers, supply credentials, extract keys, or enable protected decoding.
Fixed-quality App lookups and their existing refusal rules remain unchanged.
Desktop and the WebView's separate manual source resolver are not changed here.

## Gates before playback

- Bind current page, series detail, episode membership, and public SEO metadata
  to the exact requested series and episode. Require explicit public availability,
  finite full-episode duration, and integer dimensions.
- Require explicit no-login/no-authenticated-PC authorization flags. The browser
  capability field must be a boolean, but it is not an entitlement: the native
  public-MP4 path does not host the official encrypted browser player, so it uses
  the normal public fallback for either capability value. Unknown capability
  metadata still stops this route. Server refusal remains final.
- Select one page after existing bounded GET/hedge attempts, then issue at most
  one fixed-destination POST. Do not use an SEO content URL as the guest source.
- Do not follow POST redirects. Non-200 replies are terminal before parsing any
  error body. Require numeric zero business status, valid HTTPS media service,
  unexpired URL, and no contradictory identity, explicit trial, or encryption.
- Only native resolution can attach guest provenance. Bridge/saved JSON cannot
  assert this marker or overwrite its declared duration and expiry.
- Download once into the existing private partial-file cache. No guest header
  changes, App-model retry, key processing, or automatic quality descent after
  refusal or failed media verification.
- Before atomic cache publication, read the complete ordinary MP4, reject DRM,
  encrypted tracks/samples and truncation, verify the actual video sample time
  span and continuity against the episode duration, and require matching actual
  dimensions. Container duration or long audio cannot validate a short preview.
- Guest native TV/manual downloads use the same preparation gate, not a direct
  DownloadManager URL. Casting cannot use a format error to switch a guest source
  to another App route. Old automatic cache identity `complete=1` is not reused;
  the new namespace is `complete=2`. Existing user downloads are not deleted.

## Real public-media observation

A series was selected randomly from the live Hongguo catalog. The first attempted
third-episode page did not expose usable, correctly bound public metadata, so it
was not downloaded. The same series' first episode qualified normally:

- Series `7703410222160694296`, 《都说了，别惹那个乞丐》, 87 episodes.
- Episode 1: `7703412149342719001`.
- Public page: https://hongguoduanju.com/player/7703410222160694296/7703412149342719001
- The ordinary guest POST, without cookies or authorization/signature headers,
  returned HTTP 200 and `base_resp.StatusCode = 0`.
- Complete MP4: 11,570,693 bytes. Declared duration 139.034 seconds; observed
  media duration 139.033333 seconds; H.264 video 360×640.
- A real first frame was extracted and inspected. Thirty-five seconds decoded
  without errors, keys, or audio output. No signed URL or credentials are stored
  in this repository.
- The production container verifier also read this real file using 10,160 actual
  packet byte ranges and timestamps supplied by a host demux boundary double.
  That exercise does not claim Android MediaExtractor execution.

## Verification limits

The cloud computer has no KVM. An official API 30 emulator repeatedly suffered
system_server watchdog restarts; a single-core/no-Vulkan retry still ended with
`package: Broken pipe (32)` during APK installation. The project did not launch.
This is an emulator/system-service blocker, not evidence of an application crash.

The first-frame and 35-second results above prove the sampled public media is
usable with an ordinary decoder; they do not prove this patched Android APK has
rendered a frame. Android native extraction, hardware decoder behavior, seeking,
continuous playback and next-episode playback still require a stable Android
phone or accelerated emulator. CI compilation/offline regression results cannot
replace that device acceptance. No main merge or release is part of this change.

## Offline regression results before publication

- Production resolver: 606 assertions. Production NetworkGuard guest HTTP bytes:
  223 assertions, 28 fake connections, no actual network.
- Guest provenance/cache/download/cast integration: 26 cases, 502 assertions.
  Nine temporary-copy mutations were rejected by those tests.
- Complete-file verifier: 67 synthetic boundary cases; 68 with the separately
  downloaded real MP4 packet replay. Test fixtures are structural bytes only,
  never a substitute test video for playback acceptance.
- Existing initial playback/recovery: 98 + 104 assertions. Native media contracts:
  41 crypto/transport, 213 plan/cache, 50 recovery, 34 lookup, 10 native lookup.
- Existing fixed-quality HTTP recovery: 473 assertions; quality-cache isolation:
  24; parser/transport: 305 + 28; NetworkGuard baseline: 46, resolver race: 56.
- Existing shared App entitlement and request budget: 240 + 193 assertions.
- All production Android Java compiled against official SDK35. Local host checks
  used the available JDK21 compiler in source/target17 mode, not a complete JDK17.
  The repository CI retains its genuine JDK17/SDK35 compilation and APK build.

## Accelerated Android acceptance follow-up

The later GitHub-hosted tests used a fresh Google API30 x86_64 AVD and the actual
phone App plus opt-in instrumentation. Only the explicitly approved temporary
runner user received KVM access; cleanup restored the original device ACL. No
personal device, DNS, proxy, certificate, account or production network setting
was changed. The production intent contained no media URL or playInfo.

- `336c7db4ba665de09ce6f59f984e9803efb425d8`, [run 43109222599](https://github.com/skydeadpeng/hongguo-android-cast/actions/runs/43109222599):
  official SDK install, genuine JDK17 APK/test build, KVM, muted cold-cache App,
  main page and PlayerActivity launch passed. A terminal error appeared before
  a verified frame. This did not pass playback acceptance.
- `2a3bfa932dab63842649203b06d95e2050606837`, [run 43110678334](https://github.com/skydeadpeng/hongguo-android-cast/actions/runs/43110678334):
  repeated those setup stages, then failed at 6,910 ms with fixed diagnostic
  `guest_permission_not_explicit`. The current terminal dialog was present,
  source resolution had finished, no historical media error or guest plan was
  observed. No frame or 30-second playback was accepted. ACL restoration was
  followed by an exact comparison, which passed.
- The second run passed 37 evidence-contract fixtures and 58 diagnostic allowlist
  checks. These are metadata tests, not substitutes for rendering.

A separate same-page read-only comparison found that the project's existing
Android Chrome/131 User-Agent received `encryptedBrowserAllowed=true`, whereas
an ordinary HTTP client received `false`. Both had `pcLoginRequired=false`,
`pcAuthenticatedPlayback=false`, public availability and matching episode IDs.
This identifies the eligibility predicate that stops the native path. It does
not by itself authorize changing that predicate, changing identity to bypass a
restriction, or claiming a successful alternative source. The official client's
capability/authorization distinction still requires verification before a fix.

### Confirmed browser-capability correction

The current official scripts distinguish permission from local browser support:

- [async/d.21e8cce5d0.js](https://lf-fe.fqnovelstatic.com/obj/novel-fanqie-fe/growth/incentive-h5-monorepo/apps/hongguo/static/js/async/d.21e8cce5d0.js),
  SHA256 `3bd4e55fc4bb511c5ef4fb43dd89cfa9cc7a30d40dcd1ebd75f902a08fd41261`,
  function exported as `xm.H` around character 9056: local encryption support AND
  the explicit browser flag (or browser allowlist when that flag is absent).
- [async/w.31741d2ebf.js](https://lf-fe.fqnovelstatic.com/obj/novel-fanqie-fe/growth/incentive-h5-monorepo/apps/hongguo/static/js/async/w.31741d2ebf.js),
  SHA256 `491c7585b106e359fabe21d4812537dcda6794370f5ea4f6e7e52872e01a7963`,
  PC selection around character 71400 passes `Boolean(isEncryptSupported())`.
  A false result selects the normal public-player factory and fallback-play-info.

The native path does not implement that encrypted browser player. Requiring the
server's browser capability flag to be false was therefore an implementation
mistake. The correction accepts either explicit boolean while retaining login,
authentication, episode identity, public availability, server response/refusal,
expiry, media host, trial/encryption and full-file validation. It does not change
User-Agent, spoof a different browser, extract keys or bypass a server denial.
Android first-frame and continuous playback must still pass after this fix.

Before the corrected device run, offline checks passed: resolver 633, real
NetworkGuard doubles 223, guest integration 502, verifier 67, evidence contract
37. All production and instrumentation Java compiled against SDK35 using the
local Java21 compiler with source/target17. Genuine JDK17 remains the CI check.

### Real Android playback accepted at 55d0bd2

[Android playback QA run 43112033629](https://github.com/skydeadpeng/hongguo-android-cast/actions/runs/43112033629)
passed on commit `55d0bd2ac50f008de170e1e49ce5625b4e3ea7a2`.
The same previously failing episode ran through the unchanged production intent,
normal source resolution, server-approved guest POST, complete private download,
real Android MediaExtractor/full-file verification and Android MediaPlayer.

- A real decoder-backed surface produced a nonblank first frame. The first
  screenshot shows the correct title, episode, native controls and actual video.
- Actual duration: 139,034 ms. Actual video size: 360 × 640.
- Continuous wall interval: 30,235 ms; playback position: 199 → 30,202 ms,
  advancing 30,003 ms. The same player, episode and generation were retained.
- 103 state observations, 28 surface samples and 27 frame changes. No pause,
  buffering, dialog, media error, source replacement or five-second stall occurred.
- The second screenshot shows a different real scene after the interval.
  Both screenshots were opened and visually inspected after artifact extraction.
- Android output remained muted. No media URL/playInfo was injected, no User-Agent
  or network setting changed, and no keys or protected decoding were used.
- Temporary KVM ACL restoration passed an exact comparison.

The six-file sanitized [artifact](https://github.com/skydeadpeng/hongguo-android-cast/actions/runs/43112033629/artifacts/13895869468)
has ZIP SHA256 `7ba0dddaf47f3fe321d78d94e76796197b6a0b1366444eeb48ac9836e0bc2bfc`.
It includes two screenshots and numerical reports, not raw logs or signed media
addresses. The compact report is also retained next to this document.

The same commit's [Android compilation, regression and APK workflow](https://github.com/skydeadpeng/hongguo-android-cast/actions/runs/43112033644)
also passed with genuine JDK17/SDK35. The broader desktop `npm test` workflow
[43112033640](https://github.com/skydeadpeng/hongguo-android-cast/actions/runs/43112033640)
did not pass: its separate random real-media download test could not obtain the
selected episode (`Real Hongguo media lookup failed (ERR_ASSERTION)`). The same
case already failed in parent commit 2a3bfa9, [run 43110678322](https://github.com/skydeadpeng/hongguo-android-cast/actions/runs/43110678322).
No desktop source/test files changed in the capability fix. This is disclosed,
not treated as a passing global suite or silently replaced with test footage.

Acceptance is limited to this real phone-player sample and interval. It does not
claim every episode, complete browse-to-play interaction, seeking, completion,
next-episode playback, TV, casting, fixed high resolutions or personal devices
were verified. No main merge or release was performed.
