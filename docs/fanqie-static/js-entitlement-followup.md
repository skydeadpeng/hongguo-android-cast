# JS playback-envelope follow-up

Date: 2026-11-11. Baseline: `32316f3f1b37d687d189973bf8526129c1f5ab59`.
Branch: `fix/hongguo-playback-fanqie-analysis`. No main merge or release.

## Confirmed defect and bounded change

The multi-model JS fallback selected `response.data[vid].video_model` before
checking the outer SaaSVideoModelData `is_trial_video` or nested explicit errors.
A usable inline model could consequently hide a trial/denial in its wrapper.
The native registered projection already checks the trial flag, but that does
not protect the independent JS fallback or responses from its fallback GET.

The shared registered-source parser now checks each known response envelope
before discarding it. Explicit `Code`, `code`, and `error_code` fields must each
be numeric zero or string `"0"`; one success field cannot mask another refusal.
A present `is_trial_video` must be boolean. True stops with `BUSINESS_REJECTED`;
other types stop with `APP_INVALID_RESPONSE`. Missing optional trial metadata is
not fabricated. These terminal errors do not trigger another provider request,
legacy POST, or fallback GET. Existing encryption, host, quality, and budget
rules are unchanged. This closes a boundary, not a claim of new entitlement.

Official field corroboration and limits are in
[plaintext-and-entitlement-contract.md](plaintext-and-entitlement-contract.md).
No request-domain rotation, access-control workaround, version impersonation
change, or content-type guessing is part of this change.

## Reproduction and verification

`node scripts/check-app-source-entitlement.cjs` runs the actual shared module and
a fresh in-memory bundle of the Android adapter, with synthetic metadata only.
It does not make network requests, register an identity, or download/play media.

- Before the change: 12 groups failed, two positive-control groups passed.
- After the change: 14 groups / 240 checks passed. Coverage includes object and
  string models, each known envelope, explicit and malformed trial markers,
  nested refusal shadowed by success, registered-provider refusal, and exact
  request counts proving no follow-on request after a terminal result.
- Existing source-budget tests: 22 groups / 193 checks passed.
- Existing registered-model JS suite: 16 groups / 146 checks passed.
- Generated Android source + registered-model Node suite: 53 tests passed,
  including 32 signature-entropy cases.
- Android signer offsets: 64 checks; webOS signer: 4 matches; webOS crypto: 14
  checks passed. Android UI build passed.
- `npm run check:android`: 48 tests and project-structure checks passed.
- `npm run check:android:playback`: seven JS/static groups passed; three Java
  groups could not run in this new checkout because JDK17/javac and the Android
  JSON test dependency are absent. No fresh native pass is claimed locally.
- The new regression runs automatically in `npm test` and the Android CI gate.

Baseline CI at the exact commit above: Android workflow passed (run
43012211287). The generic Test workflow failed (run 43012211315) at
`check-download-quality-integration.cjs`: real Hongguo media lookup failed with
`ERR_ASSERTION`. This pre-existing live-source failure is not waived or replaced
with synthetic footage, and the baseline CI is not a pass for this later patch.

## Remaining work

There is still no successful real-video playback acceptance for this branch.
Native registration transport can erase some transient exception categories;
physical DNS cancellation remains a separate unverified runtime limitation.
Neither is represented as fixed here. Official multi-model callers derive their
map key from VideoContentType. The current app catalog filters its raw
`content_type` but discards it during card projection, and the Android source
interface does not pass a verified per-episode type. Replacing fixed values or
trying several values without a reliable provenance chain is not part of this
patch. These limitations require separate evidence and tests.
