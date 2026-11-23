// 2026-11-23: the complete V5 medusa must match the Java twin (PlaybackSignerV5), not only its
// f13/carrier parts. The JS twin carried the v3-era SDK constant in field 9 (67503104 instead
// of 0x040a0300), so every JS-signed v5 request was silently refused with an empty 200 while
// the Kotlin/Java signers were served. The vectors in playback-signature-v5.medusa-vectors.json
// were produced by the Java helpers for fixed randomness, one per carrier word (khronos & 3).
const assert = require('node:assert/strict');
const { test } = require('node:test');
const crypto = require('node:crypto');

const vectors = require('./playback-signature-v5.medusa-vectors.json');

// The module captures its process nonce and start time at load: pin both before loading it.
// randomBytes(18) of zeros is the 24-character base64url nonce the vectors were built with.
const realRandomBytes = crypto.randomBytes, realNow = Date.now, realRandom = Math.random;
crypto.randomBytes = size => Buffer.alloc(size);
Date.now = () => vectors[0].processStarted;
let signer;
try { signer = require('./playback-signature-v5'); }
finally { crypto.randomBytes = realRandomBytes; Date.now = realNow; }

for (const vector of vectors) {
    test(`complete V5 medusa matches the Java twin (carrier word ${vector.ts & 3})`, () => {
        // medusaV5 draws Math.random exactly three times: message field 3, the key nonce, the packed nonce.
        const sequence = vector.randomness.map(value => (value + 0.5) / 2 ** 32);
        Math.random = () => sequence.shift();
        try {
            assert.equal(signer.medusaV5(vector.url, Buffer.alloc(0), vector.ts, vector.who), vector.medusa);
            assert.equal(sequence.length, 0, 'medusa consumes exactly three random words');
        } finally { Math.random = realRandom; }
    });
}
