# Mac App-source adapter for Android

The build reuses these files without editing their protocol implementation:

- `src/native/app-source.js`
- `src/native/sm3.js`
- `src/native/download-quality.js`

Generate `src/android/generated/mac-app-source.mjs` with:

```sh
node scripts/build-android-mac-source.mjs
node --test scripts/check-android-mac-source.test.mjs
node scripts/check-android-mac-parity.mjs
# Optionally compare an earlier project tree:
node scripts/check-android-mac-parity.mjs --root /absolute/path/to/baseline
```

The generated browser ESM exports:

```js
registerAppResolver(nativeCall); // Call once before rendering the Android app.
const playInfo = await fetchAppPlayUrl(vid, { quality: 'auto' });
```

`playInfo` retains the Mac module's complete result, including `contentKey`,
`userAgent`, `referer`, `codec`, and `variants`. The native player must handle
encrypted media rather than treating every returned URL as a plain MP4.

`registerAppResolver` installs `window.__androidAppResolve(requestId, seriesId,
vid)`. It returns success through `appResolveResult` with
`{requestId, seriesId, vid, playInfo}`, or failure with the same identity and
an `error` string. Its return value unregisters that callback.

## Native interfaces

- `nativeCall('appRequest', {url, method, headers, body}, timeoutMs)` returns
  `{status, body, url, headers?}`. `body` is the exact UTF-8 JSON request text.
  The adapter never performs browser networking or executes server responses.
- Synchronous `window.AndroidCrypto.process(json)` accepts `digest` with `MD5`
  or `SHA-512`, and `decrypt` with `AES-128-CBC`. Byte fields use base64.
  It returns JSON `{data: base64, error: null}`, or an error string.
- Random bytes use `crypto.getRandomValues`. The Buffer injection adds the
  `base64url` encoding missing from browser `buffer@6.0.3` and supplies the Mac
  module's BigInt byte methods.

The fixture tests compare the bundled module against the actual desktop module
using the same deterministic clock and random stream. They verify exact request
URLs, headers, body bytes, response decoding and complete results. Tests use only
synthetic responses and make no network requests.

GPL-3.0-only. Protocol attribution remains in `src/native/licenses/NOTICE.txt`.
