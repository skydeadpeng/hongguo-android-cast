# Ten real Android episode opening checks

Tested commit: `ac9911f5aaaab9da9886ce749715008c43e667b2`.
Base main: `f9cd346d6de2fd086fee3c4c7771cf36f80eceaf`. App: **0.12.66 (89)**, phone debug, official API30 x86_64 emulator.

**Result: 8 of 10 passed; not all ten open.** All ten were actually attempted once. This verifies a real first decoder frame and at least five seconds of advancing video, not complete episodes, physical phones, normal browsing, or audible playback. The App was muted throughout.

Series: 《都说了，别惹那个乞丐》 (`7703410222160694296`).

| Episode | Result | Source | Decoded size | Position advance |
|---|---|---|---|---|
| 1 | Passed | Official guest | 360×640 | 5251 ms |
| 2 | Failed before first frame | Website metadata validation | — | — |
| 3 | Failed before first frame | Website metadata validation | — | — |
| 4 | Passed | Production App cache | 1080×1920 | 5286 ms |
| 5 | Passed | Production App cache | 1080×1920 | 5054 ms |
| 6 | Passed | Production App cache | 1080×1920 | 5240 ms |
| 7 | Passed | Production App cache | 1080×1920 | 5055 ms |
| 8 | Passed | Production App cache | 1080×1920 | 5176 ms |
| 9 | Passed | Production App cache | 1080×1920 | 5028 ms |
| 10 | Passed | Production App cache | 1080×1920 | 5174 ms |

## Episodes 2 and 3

The actual player produced a terminal `guest_identity_mismatch` classification. A subsequent ordinary public-page GET for each episode returned HTTP 200 without a redirect. The page series/episode IDs and 87-entry episode list matched, but both pages completely omitted `publicVideoSeo`. The current guest parser groups missing SEO identity/duration/dimension data with identity conflicts. The first-episode control still supplied complete matching metadata.

Therefore the observation is **missing required public-playback metadata**, not evidence of a wrong episode or an explicit server permission denial. Missing metadata also does not prove permission to obtain media. No guest POST or media request was made by this diagnostic follow-up; no login, refusal, identity, DRM or full-file guard was removed. No alternate source was forced to turn these failures into passes.

## Evidence and bounds

- [Actual CI run](https://github.com/skydeadpeng/hongguo-android-cast/actions/runs/43124815552), job `130874091593`.
- Artifact `13902289112`, SHA256 `b001b81b2a91aae57093ce65825d8867e47941168d533a3524c8db3f175acc63`, contains ten reports, ten result records, sixteen successful App screenshots, preflight and summary. CI artifacts have a three-day retention period.
- [Persistent sanitized measurements](android-ten-episode-ac9911f-report.json). Screenshots were visually inspected; they show the correct episode labels and actual changing drama imagery, not test footage.
- Episode 1 retained the production guest complete-MP4, identity, duration and dimension checks. Episodes 4–10 used independently resolved production App cache plans, bound to the selected identity and unchanged protected cache/plan/player, with matching decoded dimensions. Their declared full-episode duration was **not** independently verified.
- Both wall time and media position advanced at least five seconds, with nonblank PixelCopy buffers and changed frame hashes. No source URLs, keys, accounts, raw exception text or failed-screen captures were published.
- The runner granted only its own UID temporary KVM read/write access, then restored the original ACL and verified byte-for-byte equality. The user’s devices and main branch were not modified.

## Other checks and unresolved CI

The dedicated ten-episode workflow built the exact App and instrumentation successfully. Host-only acceptance checks passed: legacy 37, batch 86, stdin/orchestration 128; Java diagnostic/config/source-policy checks passed 513/335/30. Local SDK35 compilation used Java21 source/target17; hosted build used genuine JDK17.

Other CI is not green: Android cast production compilation and network/cache regressions pass, but its older `check-android-signer-offsets.cjs` stub rejects the newly introduced `./playback-signature` dependency. The generic Test workflow has six failed script commands on the latest-main snapshot: Windows-installer configuration, signer-offset stub, entitlement fixtures, download-quality fixtures, release-version check, and source-recovery fixtures. These failures were neither hidden nor waived.

This QA change preserved latest-main playback code. Outside test files, it only repaired a missing closing quote in the merged Gradle instrumentation-runner declaration. It was pushed to `test/hongguo-ten-episode-opening`, not merged or released.

The earlier `08ada178` batch attempt only exercised episode 1 before an adb stdin-consumption bug swallowed the remaining list; its nine NOT_RUN results are not included as attempts here. A negative-control fake-ADB regression reproduces that script bug. This later run materialized the ten fixed identities and supplied EOF to every adb subprocess.
