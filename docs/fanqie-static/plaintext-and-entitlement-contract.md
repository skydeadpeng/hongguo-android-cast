# Plaintext signaling and entitlement metadata: static corroboration

Scope: official Fanqie 7.4.3.32 APK; model classes only. Relevant to repairing the user's existing Hongguo playback bridge, not adding a Fanqie entrance. No runtime API request or key extraction was performed.

## Independent encryption-state field

classes14.dex `com.ss.ttvideoengine.model.VideoInfo.extractFields(JSONObject)` begins at DEX address 0x76427c. Its static JSON parser contains these distinct assignments:

- 0x7644f4–0x764500: reads top-level `encrypt` with `JSONObject.optBoolean(String)` and assigns `mEncrypt:Z`.
- 0x764a32–0x764a4e: in a branch parsing a JSON string from `sub_info`, reads `encrypt_info` using optJSONObject; when non-null, reads its `encrypt` boolean into mEncrypt.
- 0x765534–0x765550: another branch reads `encrypt_info` using optJSONObject; when non-null, reads its `encrypt` boolean into mEncrypt.
- The following instructions read the separate `spade_a` string into a separate field. Therefore `encrypt` is independent metadata, not implied by preserving the sibling string.

This supports preserving explicitly supplied `encrypt_info.encrypt` through a bridge projection. It does NOT authorize fabricating `encrypt=false`, interpreting missing state as known plaintext, removing encryption, discarding entitlement metadata, or bypassing a server denial. Native optBoolean is not proof that arbitrary numeric/string variants have exactly the same semantics as a JavaScript parser; retain the application's existing explicit-state rules unless separately tested.

Full static source evidence: `VideoInfo-tt-evidence.txt`. `VideoRef-tt-evidence.txt` addresses 0x769788 onward prove version 1 video_list is read as JSONObject and enumerates video_1 through video_8, passing child JSON into _generateVideoInfo. Other format/version branches exist; this report is not a universal format claim.

## Trial, expiry, and permission metadata

Both main and Series SDK `SaaSVideoModelData` have:

- `video_model`: String
- `is_trial_video`: boolean
- `expire_time`: long
- `play_auth_token`: String (schema name only; no value retrieved)
- `main_url`, `backup_url`: String

The `is_trial_video` flag is distinct from encryption state. Plaintext signaling does not establish full-content entitlement or supersede trial/expiry information.

Series SDK `GetVideoModelResponse.code` has type VideoErr. Direct enum constructor values:

- SUCCESS=0
- IllegalAccess=110, AppReject=112
- VideoNoPermission=101003
- AgeIllegal=101004
- VideoLocked=101005
- VideoPaidLocked=101007
- VideoUnPaidBlock=101008
- I18nVideoModelRiskControlError=300001

These values belong to VideoErr, not the main flat endpoint's BookApiERR; do not substitute between response families. Error-name meanings indicate denial categories but no entitlement override branch was inferred or implemented.

`SaaSVideoDetailData.lock_data` is EpisodeLockData; exact wire fields: once_unlock_num, lock_threshold_order, auto_unlock, ad_duration, auto_unlock_list, enable_insert_ad, ad_repeat_info. Types are in model-contract-data.json. `VideoDetailInfo` independently includes status, pay_info, age_gate_info, audit_status, filter_reason. Their presence does not imply every response populates them.

## Reproduction

Extract classes14.dex from the APK, run Android SDK dexdump -a -d into classes14-dump.txt, and run extract_tt_evidence.py. For model/enum evidence rerun extract_model_contract.py and extract_enum_contract.py. enum-contract-data.json records exact constructor instruction addresses and values; model-contract-data.json records serializer fields and typed JVM fields. Keep plaintext bridge regression tests separate from claims of live authorized playback.
