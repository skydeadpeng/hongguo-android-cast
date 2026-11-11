# Registration transport timeout classification

Date: 2026-11-11. Baseline: `67b460833bfdc75aca72a8af1c02c128126fe89b`.
Branch: `fix/hongguo-playback-fanqie-analysis`; no main merge or release.

## Reproduced defect

The actual `OfficialTransport` erased two timeout cases into generic IOException:

1. The last permitted address's connect attempt threw SocketTimeoutException
   while total time remained. The address loop wrapped it as IOException(last),
   and the outer catch checked only the top-level exception type.
2. A SocketException observed after the total deadline was not recognized as a
   timeout. Closing a blocked socket at a deadline can produce this category.

`NativeBridge.startRegisteredModel` classifies generic failures as
APP_INVALID_RESPONSE, so these timeout cases could not use the already-defined,
bounded transient fallback. They also lost the correct timeout explanation.

The small fix preserves the final connect exception until the existing safe
outer classification and recognizes SocketException only after the deadline.
Explicit close or thread interruption still wins as cancellation. A late
ordinary IOException or SSLHandshakeException is not promoted to a retryable
failure. Error details remain sanitized. No extra attempt, longer budget,
changed URL, certificate handling, DNS policy, or access override was added.
UnknownHostException and other non-timeout categories retain existing behavior.

## Offline red/green evidence

An isolated test compiled the actual baseline OfficialTransport with DNS and
SocketImpl doubles. Both timeout assertions failed; direct timeout, pre-expiry,
and cancellation-priority controls passed. The doubles never performed real
DNS, connection, TLS, registration, or HTTP work.

`OfficialTransportTimeoutTest` formalizes this harness and adds negative controls
for early socket failure, late format/IO failure, late TLS failure, unknown host,
closed operations, and simultaneous cancellation/deadline. The patched actual
transport passes 23 checks. The earlier two-failure harness also passes.

Local compilation used OpenJDK 21's compiler module with `-source 17 -target 17`;
it is not represented as a full JDK17 or Android runtime pass. The CI runner
`run-official-transport-timeout-tests.sh` requires JDK17's `--release 17` and is
included in the Android workflow. Existing shared source-budget tests continue
to cover the single permitted fallback, deadline, and terminal refusal rules.

## Other evidence and limits

The previous entitlement commit's exact Android CI succeeded:
https://github.com/skydeadpeng/hongguo-android-cast/actions/runs/43030364731
Its generic Test CI passed the new 240-check entitlement suite but still failed
only the pre-existing real Hongguo media lookup in
`check-download-quality-integration.cjs`:
https://github.com/skydeadpeng/hongguo-android-cast/actions/runs/43030364628
Those results are not treated as CI for this later timeout patch.

This does not verify Android watchdog scheduling, physical DNS cancellation,
HttpURLConnection.disconnect behavior, user-device behavior, or successful real
playback. The after-deadline test injects its failure at the DNS test seam to
exercise outer exception classification; it is not an Android network simulator.
There is no successful real-media acceptance for this branch yet.
