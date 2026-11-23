// 2026-11-23: the platform answers only the V5-signed bodyless video_tab GET; the v5 POST
// and both legacy forms stay as later rungs of the ladder.
const assert = require('node:assert/strict');
const { test } = require('node:test');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const crypto = require('node:crypto');
const { PLAYER_V1_UA, branchClass } = require('./playback-signature-v5');

const vid = '7694655440692448281';
const identity = { device_id: '9007199254740993', iid: '9007199254741993', cdid: '00000000-0000-4000-8000-000000000003' };
const media = 'https://qznovelvod.com/synthetic.mp4';
const model = () => ({ video_id: 'synthetic-video', video_duration: 60, video_list: {
    hd: { codec_type: 'h264', vwidth: 1080, vheight: 1920, quality_desc: '1080p',
        main_url: media, encrypt_info: { encrypt: false } },
} });
const response = data => ({ status: 200, headers: {}, data: JSON.stringify(data) });
const tabEnvelope = value => ({ code: 0, data: { video_model_data: { [vid]: { video_model: value } } } });
const playerEnvelope = value => ({ code: 0, data: { 1004: { [vid]: { video_model: value } } } });
const EMPTY = { status: 200, headers: {}, data: '' };
const TAB = '/reading/bookapi/video_tab/multi_video_model/v1/', PLAYER = '/novel/player/multi_video_model/player/v1', LEGACY = '/novel/player/multi_video_model/v1/';
/** Route name of a captured request: V5 and Argus share the tab path, the signature headers tell them apart. */
const route = config => {
    const pathname = new URL(config.url).pathname;
    if (pathname === PLAYER) return 'v5-post';
    if (pathname === TAB) return config.headers['x-argus'] ? 'argus-tab' : 'v5-tab';
    if (pathname === LEGACY) return 'legacy-post';
    return pathname;
};

/** withZlib=false is the WebView bundle: no gzip, so the v5 POST rung is skipped without HTTP. */
function fixture(answer, { withZlib = true } = {}) {
    const calls = [], module = { exports: {} };
    const context = { module, Buffer, URL, AbortSignal, setTimeout, clearTimeout, console,
        require(name) {
            if (name === 'axios') return async config => { calls.push(config); return answer(config, calls.length); };
            if (name === 'node:zlib' && !withZlib) return {};
            return require(name.startsWith('.') ? path.join(__dirname, name) : name);
        } };
    vm.runInNewContext(fs.readFileSync(path.join(__dirname, 'app-source-registered.js'), 'utf8'), context);
    module.exports.setDeviceIdentity(identity);
    return { calls, source: module.exports, routes: () => calls.map(route),
        fetch: options => module.exports.fetchAppPlayUrl(vid, { quality: 'auto', ...options }) };
}

test('V5 video_tab GET is a bodyless request signed by the v5 player family over the empty-content digest', () => {
    const { source } = fixture(() => { throw new Error('no network expected'); });
    const signed = source.signedVideoTabV5Request(vid);
    const url = new URL(signed.url), query = url.searchParams;
    assert.equal(signed.method, 'GET');
    assert.equal(url.origin + url.pathname, 'https://api5-normal-sinfonlineb.fqnovel.com' + TAB);
    for (const [key, value] of Object.entries({ video_id: vid, video_id_type: '0', device_level: '1',
        need_all_video_definition: '1', need_mp4_align: '0', use_os_player: '0',
        video_platform: '1024', source: '0', from_video_id: '', device_id: identity.device_id, iid: identity.iid,
        cdid: identity.cdid, version_code: '74332', version_name: '7.4.3.32', update_version_code: '74332',
        channel: 'vivo_8662_64', device_type: 'PGT-AN10', aid: '8662', app_name: 'novelread' })) assert.equal(query.get(key), value, key);
    assert.equal(query.get('ts'), null);
    assert.deepEqual(Object.keys(signed.headers).sort(), ['Accept', 'Content-Type', 'User-Agent', 'x-helios', 'x-khronos', 'x-medusa', 'x-ss-stub']);
    for (const name of ['x-khronos', 'x-helios', 'x-medusa']) assert.ok(signed.headers[name], name);
    // The bodyless GET digests MD5(""): jackotom 7.7 and hongguoTV 0.1.37 agree.
    assert.equal(signed.headers['x-ss-stub'], crypto.createHash('md5').update('').digest('hex').toUpperCase());
    assert.equal(signed.headers['Content-Type'], 'application/json; charset=utf-8');
    assert.equal(signed.headers['User-Agent'], PLAYER_V1_UA);
    const ts = Number(signed.headers['x-khronos']), ticket = Number(query.get('_rticket'));
    assert.ok(ticket >= ts * 1000 && ticket < ts * 1000 + 1024, 'ticket search stays within the signed second');
    assert.equal(branchClass(url.search.slice(1), Buffer.alloc(0), ts), 12, 'ticket search lands on the v5 branch');
    assert.equal(signed.body.length, 0);
});

test('an empty v5 POST answer recovers on the V5 video_tab GET without touching the legacy forms', async () => {
    const f = fixture(config => route(config) === 'v5-post' ? EMPTY : response(tabEnvelope(model())));
    assert.equal((await f.fetch()).url, media);
    assert.deepEqual(f.routes(), ['v5-post', 'v5-tab']);
    const tab = f.calls[1];
    assert.equal(tab.method, 'GET');
    assert.equal(tab.data, undefined, 'the GET carries no body');
    assert.equal(tab.headers['x-argus'], undefined);
    assert.equal(tab.headers['x-ss-stub'], crypto.createHash('md5').update('').digest('hex').toUpperCase());
    assert.equal(tab.headers.Cookie, undefined);
    assert.equal(tab.headers['Content-Encoding'], undefined);
});

test('the legacy POST still serves after both V5 forms answer hollow', async () => {
    const f = fixture(config => route(config) === 'legacy-post' ? response({ code: 0, data: { [vid]: { video_model: model() } } }) : EMPTY);
    assert.equal((await f.fetch()).url, media);
    assert.deepEqual(f.routes(), ['v5-post', 'v5-tab', 'legacy-post']);
    assert.ok(f.calls[2].headers['x-argus'], 'the legacy rung keeps its own signature family');
});

test('four hollow rungs surface the empty model answer after the whole ladder', async () => {
    const f = fixture(() => EMPTY);
    await assert.rejects(f.fetch(), error => error.code === 'APP_EMPTY_RESPONSE' && error.responseStage === 'model_post');
    assert.deepEqual(f.routes(), ['v5-post', 'v5-tab', 'legacy-post', 'argus-tab']);
});

test('a refused V5 GET stays terminal and never reaches the legacy forms', async () => {
    const f = fixture(config => route(config) === 'v5-post' ? EMPTY : { status: 403, headers: {}, data: '' });
    await assert.rejects(f.fetch(), error => error.status === 403);
    assert.deepEqual(f.routes(), ['v5-post', 'v5-tab']);
});

for (const served of ['v5-tab', 'legacy-post']) {
    test(`the WebView bundle has no gzip: the v5 POST is skipped and ${served} serves`, async () => {
        const f = fixture(config => route(config) === served
            ? response(route(config) === 'v5-tab' ? tabEnvelope(model()) : { code: 0, data: { [vid]: { video_model: model() } } }) : EMPTY,
            { withZlib: false });
        assert.equal((await f.fetch()).url, media);
        assert.deepEqual(f.routes(), served === 'v5-tab' ? ['v5-tab'] : ['v5-tab', 'legacy-post']);
    });
}
