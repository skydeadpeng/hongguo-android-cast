// 2026-11-23: the desktop registered-model provider (NativeBridge appRegisteredModel twin)
// leads with the V5 video_tab GET; the v5 POST stays as the second shape of each attempt.
const assert = require('node:assert/strict');
const { test } = require('node:test');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const crypto = require('node:crypto');
const { PLAYER_V1_UA } = require('./playback-signature-v5');

const vid = '7694655440692448281';
const identity = { deviceId: '9007199254740993', installId: '9007199254741993' };
const media = 'https://qznovelvod.com/synthetic.mp4';
const model = () => ({ video_list: { hd: { codec_type: 'h264', vwidth: 1080, vheight: 1920, main_url: media, encrypt_info: { encrypt: false } } } });
const tabEnvelope = () => ({ code: 0, data: { video_model_data: { [vid]: { video_model: model() } } } });
const playerEnvelope = () => ({ code: 0, data: { 1004: { [vid]: { video_model: model() } } } });
const EMPTY = { status: 200, headers: {}, data: '' };

/** A persisted registration keeps the fixture offline; only the model HTTP is captured.
 *  The bridge module is re-evaluated per fixture so each one owns its fake axios. */
function fixture(answer) {
    const axiosPath = require.resolve('axios'), bridgePath = require.resolve('./android-bridge'), calls = [];
    const axios = async () => { throw new Error('unexpected axios call'); };
    axios.get = async (url, config) => { const call = { method: 'GET', url, config }; calls.push(call); return answer(call); };
    axios.post = async (url, body, config) => { const call = { method: 'POST', url, body, config }; calls.push(call); return answer(call); };
    const previous = require.cache[axiosPath];
    require.cache[axiosPath] = { id: axiosPath, filename: axiosPath, loaded: true, exports: axios, children: [], paths: [] };
    delete require.cache[bridgePath];
    let createAndroidBridge;
    try { ({ createAndroidBridge } = require('./android-bridge')); }
    finally { if (previous) require.cache[axiosPath] = previous; else delete require.cache[axiosPath]; }
    const userDataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'hongguo-bridge-'));
    fs.writeFileSync(path.join(userDataDir, 'passport-device.json'), JSON.stringify(identity));
    const bridge = createAndroidBridge({ userDataDir, onPlay() {}, log() {} });
    return { calls, run: () => bridge.handle('appRegisteredModel', { vid }), shapes: () => calls.map(call => call.method + ' ' + new URL(call.url).pathname) };
}

test('appRegisteredModel leads with the V5 video_tab GET and returns its model', async () => {
    const f = fixture(() => ({ status: 200, headers: {}, data: JSON.stringify(tabEnvelope()) }));
    const reply = await f.run();
    assert.equal(reply.vid, vid);
    assert.deepEqual(Object.keys(reply), ['vid', 'video_model']);
    assert.equal(reply.video_model.video_list.hd.main_url, media);
    assert.deepEqual(f.shapes(), ['GET /reading/bookapi/video_tab/multi_video_model/v1/']);
    const request = f.calls[0], query = new URL(request.url).searchParams, headers = request.config.headers;
    for (const [key, value] of Object.entries({ video_id: vid, device_id: identity.deviceId, iid: identity.installId,
        version_code: '74332', version_name: '7.4.3.32' })) assert.equal(query.get(key), value, key);
    // The bridge hands the signer no cdid: both V5 shapes carry a local UUID, as the v5 POST always did.
    assert.match(query.get('cdid'), /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
    assert.deepEqual(Object.keys(headers).sort(), ['Accept', 'Content-Type', 'User-Agent', 'x-helios', 'x-khronos', 'x-medusa', 'x-ss-stub']);
    for (const name of ['x-khronos', 'x-helios', 'x-medusa']) assert.ok(headers[name], name);
    assert.equal(headers['x-ss-stub'], crypto.createHash('md5').update('').digest('hex').toUpperCase());
    assert.equal(headers['User-Agent'], PLAYER_V1_UA);
    assert.equal(headers.Cookie, undefined);
    assert.ok(request.config.timeout > 0 && request.config.validateStatus(200) && request.config.validateStatus(500));
});

test('an empty V5 GET falls through to the v5 POST within the same attempt', async () => {
    const f = fixture(call => call.method === 'GET' ? EMPTY : { status: 200, headers: {}, data: JSON.stringify(playerEnvelope()) });
    const reply = await f.run();
    assert.equal(reply.video_model.video_list.hd.main_url, media);
    assert.deepEqual(f.shapes(), ['GET /reading/bookapi/video_tab/multi_video_model/v1/', 'POST /novel/player/multi_video_model/player/v1']);
    const post = f.calls[1];
    assert.equal(post.config.headers['Content-Encoding'], 'gzip');
    assert.ok(Buffer.isBuffer(post.body) && post.body.length > 18);
    assert.equal(post.config.headers['x-argus'], undefined);
});

test('a business refusal on the leading V5 GET stays terminal without a v5 POST or retry', async () => {
    const f = fixture(() => ({ status: 200, headers: {}, data: JSON.stringify({ code: 401 }) }));
    await assert.rejects(f.run(), error => error.code === 'BUSINESS_REJECTED');
    assert.deepEqual(f.shapes(), ['GET /reading/bookapi/video_tab/multi_video_model/v1/']);
});

test('both shapes hollow end as APP_EMPTY_RESPONSE after the bounded same-identity attempts', async t => {
    // Same-identity backoff waits 1s/3s/5s; the cadence itself is covered by check-android-registered-model.
    const immediate = globalThis.setTimeout;
    t.after(() => { globalThis.setTimeout = immediate; });
    globalThis.setTimeout = (fn, _ms, ...args) => immediate(fn, 0, ...args);
    const f = fixture(() => EMPTY);
    await assert.rejects(f.run(), error => error.code === 'APP_EMPTY_RESPONSE');
    assert.deepEqual(f.shapes(), ['GET /reading/bookapi/video_tab/multi_video_model/v1/', 'POST /novel/player/multi_video_model/player/v1',
        'GET /reading/bookapi/video_tab/multi_video_model/v1/', 'POST /novel/player/multi_video_model/player/v1',
        'GET /reading/bookapi/video_tab/multi_video_model/v1/', 'POST /novel/player/multi_video_model/player/v1']);
});
