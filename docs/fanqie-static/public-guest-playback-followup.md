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
- Require all guest eligibility flags explicitly: no login required, no
  authenticated PC route, and no encrypted browser route. Unknown or conflicting
  flags stop this route rather than guessing.
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
