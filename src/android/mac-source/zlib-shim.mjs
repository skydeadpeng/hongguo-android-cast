// Browser adapter for the bundled playback chain: real node:zlib gzip stays a
// Node-only convenience. The model endpoint accepts a plaintext JSON body with
// a matching x-ss-stub (verified 2026-11-11), so the WebView build signs and
// sends the body as-is and skips Content-Encoding.
module.exports = { gzipSync: null };
