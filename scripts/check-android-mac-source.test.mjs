import test from 'node:test';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import { transform } from 'esbuild';
import { Buffer as BrowserBuffer } from '../src/android/mac-source/buffer-runtime.mjs';
import cryptoShim from '../src/android/mac-source/crypto-shim.mjs';
import axiosNative, { setNativeCall } from '../src/android/mac-source/axios-native.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const require = createRequire(import.meta.url);
const sid = '1234567890123456789', vid = '2345678901234567890';
const media = 'https://v3-reading.qznovelvod.com/fixture.mp4?token=fixture%20X';
const keyHex = '0123456789abcdef0123456789abcdef';
const generated = fs.readFileSync(path.join(root, 'src/android/generated/mac-app-source.mjs'), 'utf8');
const iife = (await transform(generated, { format: 'iife', globalName: 'AndroidMacSource', target: 'chrome89' })).code;
const appText = fs.readFileSync(path.join(root, 'src/native/app-source.js'), 'utf8');
const appHash = crypto.createHash('sha256').update(appText).digest('hex');

// All network responses are explicit synthetic fixtures. Nothing in this suite makes HTTP requests.
function nativeCrypto(input) {
  const request = JSON.parse(input), bytes = Buffer.from(request.data, 'base64');
  try {
    let result;
    if (request.operation === 'digest') result = crypto.createHash(request.algorithm).update(bytes).digest();
    else {
      assert.equal(request.operation, 'decrypt'); assert.equal(request.algorithm, 'AES-128-CBC');
      const decipher = crypto.createDecipheriv('aes-128-cbc', Buffer.from(request.key, 'base64'), Buffer.from(request.iv, 'base64'));
      decipher.setAutoPadding(request.autoPadding);
      result = Buffer.concat([decipher.update(bytes), decipher.final()]);
    }
    return JSON.stringify({ data: result.toString('base64'), error: null });
  } catch (error) { return JSON.stringify({ data: null, error: error.message }); }
}
function installCrypto() { globalThis.window = { AndroidCrypto: { process: nativeCrypto } }; }
function encodedKey() {
  const plain = Buffer.from(`0${keyHex}`), raw = Buffer.alloc(34), previous = [246, 85];
  for (let i = 0; i < plain.length; i++) {
    raw[i + 1] = ((plain[i] + 21 + i.toString(2).split('1').length - 1) & 255) ^ previous[i & 1];
    previous[i & 1] = raw[i + 1];
  }
  raw[0] = 48 ^ raw[1] ^ raw[2]; return raw.toString('base64');
}
function encryptedUrl() {
  const seed = Buffer.alloc(16, 7);
  const constants = Buffer.from('4dd4c2e6b83162090e52b3c7a6733ba41cb2462b829ab58a196b39db57177524f49baf7f08e8d68d26a72e37c1a95a2f1f05a51892aef2949732b62a38aadd58', 'hex');
  const material = crypto.createHash('sha512').update(Buffer.concat([crypto.createHash('sha512').update(seed).digest(), constants])).digest();
  const cipher = crypto.createCipheriv('aes-128-cbc', material.subarray(0, 16), material.subarray(16, 32));
  return { seed: seed.toString('base64'), url: Buffer.concat([Buffer.from([0xa8, 0, 1, 0]), cipher.update(media), cipher.final()]).toString('base64') };
}
function responses({ wrongVid = false, fallback = 'https://vod.snssdk.com/fixture-info', available = true, videoList } = {}) {
  const encrypted = encryptedUrl();
  return (request) => {
    if (request.method === 'POST') return { status: 200, body: JSON.stringify({ code: available ? 0 : 1,
      data: { [wrongVid ? '999' : vid]: { video_model: JSON.stringify({ fallback_api: fallback }) } } }), url: request.url };
    if (videoList) return { status: 200, body: JSON.stringify({ video_info: { data: { video_list: videoList } } }), url: request.url };
    return { status: 200, body: JSON.stringify({ video_info: { data: { key_seed: encrypted.seed, video_list: {
      good: { main_url: encrypted.url, codec_type: 'h264', quality_desc: '720P', spade_a: encodedKey() },
    } } } }), url: request.url };
  };
}
const requestedModel = (info, requestedQuality = 'auto') => {
  // Only the Android transport projection differs; signed requests and source fields do not.
  const seen = new Set([info.url]);
  const variants = Array.isArray(info.variants) ? info.variants.filter(row => {
    if (seen.has(row.url)) return false;
    seen.add(row.url); return true;
  }).slice(0, 7).map(row => ({ ...row, requestedQuality })) : null;
  return { ...info, requestedQuality, ...(variants ? { variants } : {}) };
};
// Strip only local JNI scheduling fields when comparing signed HTTP bytes.
function signedRequest({ timeoutMs, deadlineMs, ...request }) {
  assert.ok(Number.isSafeInteger(timeoutMs) && timeoutMs > 0 && timeoutMs <= 12000);
  assert.ok(Number.isSafeInteger(deadlineMs) && deadlineMs > 0);
  return request;
}
function deterministic(seed) {
  if (seed !== undefined) {
    let call = 0, state = seed + 1;
    return {
      randomBytes(size) {
        const out = Buffer.alloc(size);
        for (let offset = 0; offset < size;) {
          const block = crypto.createHash('sha256').update(`${seed}:${call++}`).digest();
          const length = Math.min(block.length, size - offset);
          block.copy(out, offset, 0, length); offset += length;
        }
        return out;
      },
      random() { state = (Math.imul(state, 1664525) + 1013904223) >>> 0; return state / 0x1_0000_0000; },
    };
  }
  let bytes = 0, state = 1234567;
  return {
    randomBytes(size) { const out = Buffer.alloc(size); for (let i = 0; i < size; i++) out[i] = (bytes++ * 17 + 31) & 255; return out; },
    random() { state = (Math.imul(state, 1664525) + 1013904223) >>> 0; return state / 0x1_0000_0000; },
  };
}
function globals(random, timestamp = 1_792_074_400_000) {
  const math = Object.create(Math); math.random = random.random;
  class FixedDate extends Date { static now() { return timestamp; } }
  return { Math: math, Date: FixedDate, URL, URLSearchParams, AbortSignal, AbortController, setTimeout, clearTimeout, console };
}
function browserFixture(options = {}) {
  const random = deterministic(options.seed), calls = [], reply = responses(options);
  const window = { AndroidCrypto: { process: nativeCrypto } };
  const timers = [];
  const compatibility = options.withoutTimeoutApi ? { AbortSignal: {},
    setTimeout(callback, milliseconds) { timers.push({ callback, milliseconds }); return timers.length; }, clearTimeout() {} } : {};
  const context = vm.createContext({ ...globals(random, options.timestamp), ...compatibility, window, crypto: { getRandomValues(array) { array.set(random.randomBytes(array.length)); return array; } } });
  vm.runInContext(iife, context);
  const rpc = async (method, params) => {
    calls.push({ method, params: structuredClone(params) });
    if (method === 'appRequest' && options.callNative) return options.callNative(method, params);
    if (method === 'appRequest' && options.nativeFailure) return { status: 0, body: '', url: params.url, errorCode: options.nativeFailure };
    return method === 'appRequest' ? reply(params) : { received: true };
  };
  const unregister = context.AndroidMacSource.registerAppResolver(rpc);
  return { source: context.AndroidMacSource, context, window, calls, unregister, timers };
}
function desktopFixture(options = {}) {
  const random = deterministic(options.seed), calls = [], reply = responses(options);
  const context = vm.createContext({ ...globals(random, options.timestamp), Buffer, module: { exports: {} }, require(name) {
    if (name === 'node:crypto') return { ...crypto, randomBytes: random.randomBytes };
    if (name === './sm3' || name === './download-quality') return require(path.join(root, 'src/native', name));
    if (name === 'axios') return async config => {
      const request = { url: config.url, method: config.method || 'GET', headers: config.headers,
        body: config.data == null ? '' : Buffer.from(config.data).toString('utf8') };
      calls.push(request); const result = reply(request); return { status: result.status, data: result.body, headers: {}, url: result.url };
    };
    throw new Error(`Unexpected desktop dependency ${name}`);
  } });
  vm.runInContext(appText, context);
  return { source: context.module.exports, calls, branch(request) {
    // Call the original helper without modifying or instrumenting the Mac source.
    context.__fixtureRequest = structuredClone(request);
    return vm.runInContext('branchOf(__fixtureRequest.url, Buffer.from(__fixtureRequest.body), Number(__fixtureRequest.headers["x-khronos"]))', context);
  } };
}

test('browser Buffer supports the exact Mac base64url and BigInt APIs', () => {
  assert.equal(BrowserBuffer.from([255, 238, 221]).toString('base64url'), Buffer.from([255, 238, 221]).toString('base64url'));
  const bytes = BrowserBuffer.alloc(8); bytes.writeBigUInt64BE(0x1234567890abcdefn); assert.equal(bytes.readBigUInt64BE(), 0x1234567890abcdefn);
});
test('synchronous native MD5/SHA512 preserve bytes, chained updates and digest encodings', () => {
  installCrypto();
  for (const name of ['md5', 'sha512']) {
    const bytes = BrowserBuffer.from([0, 255, 42]);
    const hash = cryptoShim.createHash(name).update('中文').update(bytes); bytes[0] = 7;
    assert.equal(hash.digest('hex'), crypto.createHash(name).update('中文').update(Buffer.from([0, 255, 42])).digest('hex'));
    assert.throws(() => hash.digest(), /完成/);
  }
  assert.throws(() => cryptoShim.createHash('sha1'), /未支持/);
});
test('native AES-CBC matches Node with and without automatic padding', () => {
  installCrypto();
  const key = Buffer.alloc(16, 2), iv = Buffer.alloc(16, 3);
  for (const autoPadding of [false, true]) {
    const plain = Buffer.alloc(autoPadding ? 29 : 32, 13);
    const cipher = crypto.createCipheriv('aes-128-cbc', key, iv); cipher.setAutoPadding(autoPadding);
    const encrypted = Buffer.concat([cipher.update(plain), cipher.final()]);
    const decipher = cryptoShim.createDecipheriv('aes-128-cbc', key, iv).setAutoPadding(autoPadding);
    const output = BrowserBuffer.concat([decipher.update(encrypted.subarray(0, 16)), decipher.update(encrypted.subarray(16)), decipher.final()]);
    assert.deepEqual(Buffer.from(output), plain);
  }
  assert.throws(() => cryptoShim.createDecipheriv('aes-128-cbc', Buffer.alloc(15), iv), /长度/);
});
test('random bytes use browser crypto and bounded chunk sizes', () => {
  const descriptor = Object.getOwnPropertyDescriptor(globalThis, 'crypto');
  const sizes = [];
  Object.defineProperty(globalThis, 'crypto', { configurable: true, value: { getRandomValues(bytes) { sizes.push(bytes.length); bytes.fill(7); return bytes; } } });
  try {
    assert.equal(cryptoShim.randomBytes(65540).length, 65540); assert.deepEqual(sizes, [65536, 4]);
    assert.throws(() => cryptoShim.randomBytes(-1), /无效/);
  } finally { if (descriptor) Object.defineProperty(globalThis, 'crypto', descriptor); else delete globalThis.crypto; }
});
test('native request adapter preserves signed request bytes and headers', async () => {
  const calls = [];
  setNativeCall(async (...args) => { calls.push(args); return { status: 200, body: '{"ok":true}', headers: { test: '1' }, url: 'https://vod.snssdk.com/result' }; });
  const reply = await axiosNative({ url: 'https://vod.snssdk.com/info?x=a%20b', method: 'POST', headers: { signed: 'A+/=' }, data: BrowserBuffer.from('{"中文":1}'), timeout: 1234 });
  assert.equal(calls[0][1].timeoutMs, 1234);
  assert.ok(Number.isSafeInteger(calls[0][1].deadlineMs));
  assert.deepEqual([calls[0][0], signedRequest(calls[0][1]), calls[0][2]], ['appRequest', { url: 'https://vod.snssdk.com/info?x=a%20b', method: 'POST', headers: { signed: 'A+/=' }, body: '{"中文":1}' }, 1234]);
  assert.equal(reply.data, '{"ok":true}'); assert.equal(reply.headers.test, '1');
});
test('native request adapter rejects invalid/oversize responses and observes abort', async () => {
  setNativeCall(async () => ({ status: 200, body: 'x'.repeat(11) }));
  await assert.rejects(axiosNative({ url: 'https://vod.snssdk.com/info', maxContentLength: 10 }), /大小限制/);
  setNativeCall(async () => ({ status: '200', body: '{}' }));
  await assert.rejects(axiosNative({}), /无效响应/);
  const controller = new AbortController(); controller.abort();
  await assert.rejects(axiosNative({ signal: controller.signal }), error => error.code === 'ERR_CANCELED');
  const live = new AbortController(); setNativeCall(() => new Promise(() => {}));
  const request = axiosNative({ signal: live.signal }); live.abort();
  await assert.rejects(request, error => error.code === 'ERR_CANCELED');
});
test('browser bundle reproduces the Mac signed request, decryption and complete playInfo byte-for-byte', async () => {
  const browser = browserFixture(), desktop = desktopFixture();
  const actual = await browser.source.fetchAppPlayUrl(vid), expected = await desktop.source.fetchAppPlayUrl(vid);
  assert.deepEqual(JSON.parse(JSON.stringify(actual)), JSON.parse(JSON.stringify(requestedModel(expected))));
  assert.equal(actual.url, media); assert.equal(actual.contentKey, keyHex); assert.equal(actual.source, 'app');
  assert.equal(actual.referer, ''); for (const candidate of actual.variants) assert.equal(candidate.referer, '');
  const requests = browser.calls.filter(call => call.method === 'appRequest').map(call => signedRequest(call.params));
  assert.deepEqual(requests, JSON.parse(JSON.stringify(desktop.calls)));
  assert.equal(requests.length, 2); assert.ok(requests[0].headers['x-medusa']); assert.ok(requests[0].headers['x-gorgon']);
  assert.equal(JSON.parse(requests[0].body).mixed_video_id_map['1004'][0], vid);
});
test('32 entropy seeds preserve all Mac request bytes and models across signature branches offline', async t => {
  const branches = new Map(), headerLengths = new Set(), signatures = new Set(), urls = new Set();
  for (let seed = 0; seed < 32; seed++) {
    await t.test(`entropy seed ${seed}`, async () => {
      const options = { seed, timestamp: 1_792_074_432_123 };
      const browser = browserFixture(options), desktop = desktopFixture(options);
      try {
        const actual = await browser.source.fetchAppPlayUrl(vid), expected = await desktop.source.fetchAppPlayUrl(vid);
        assert.deepEqual(JSON.parse(JSON.stringify(actual)), JSON.parse(JSON.stringify(requestedModel(expected))));
        assert.equal(actual.url, media); assert.equal(actual.contentKey, keyHex); assert.equal(actual.vid, vid);
        const requests = browser.calls.filter(call => call.method === 'appRequest').map(call => signedRequest(call.params));
        const desktopRequests = JSON.parse(JSON.stringify(desktop.calls));
        assert.equal(requests.length, 2); assert.equal(desktopRequests.length, 2);
        assert.equal(requests[0].method, 'POST');
        assert.equal(requests[0].url, desktopRequests[0].url);
        assert.deepEqual(requests[0].headers, desktopRequests[0].headers);
        assert.deepEqual(Buffer.from(requests[0].body, 'utf8'), Buffer.from(desktopRequests[0].body, 'utf8'));
        // Include the fallback GET, not just the signature-bearing POST.
        assert.deepEqual(requests, desktopRequests);
        const branch = desktop.branch(desktopRequests[0]);
        assert.ok(branch === 0 || branch === 2);
        branches.set(branch, (branches.get(branch) || 0) + 1);
        headerLengths.add(requests[0].headers['x-medusa'].length);
        signatures.add(requests[0].headers['x-medusa']);
        urls.add(requests[0].url);
      } finally { browser.unregister(); }
    });
  }
  assert.equal(urls.size, 32, 'all seeds exercise distinct device/nonces instead of repeating one request');
  assert.ok(branches.get(0) > 0 && branches.get(2) > 0, 'both accepted signature branches execute');
  assert.equal(signatures.size, 32, 'all seeds exercise distinct signed header bytes');
  t.diagnostic(`entropyPairs=32 branch0=${branches.get(0)} branch2=${branches.get(2)} signatureLengths=${headerLengths.size} networkRequests=0`);
});
test('old WebView without AbortSignal.timeout uses the whole-operation controller and bounded native 6s requests', async () => {
  const browser = browserFixture({ withoutTimeoutApi: true });
  assert.equal((await browser.source.fetchAppPlayUrl(vid)).url, media);
  assert.equal(browser.timers.length, 1); assert.equal(browser.timers[0].milliseconds, 28000);
  const requests = browser.calls.filter(call => call.method === 'appRequest');
  assert.ok(requests.length > 0 && requests.every(call => call.params.timeoutMs <= 6000));
  browser.timers[0].callback();
});
test('browser wrapper keeps exact episode matching and server rejection behavior', async () => {
  for (const options of [{ wrongVid: true }, { available: false }]) {
    const browser = browserFixture(options);
    await assert.rejects(browser.source.fetchAppPlayUrl(vid), /未返回所选分集|未提供本集/);
    assert.equal(browser.calls.filter(call => call.method === 'appRequest').length, 1);
  }
});
test('original destination validation rejects foreign fallback before making another request', async () => {
  for (const fallback of ['https://vod.snssdk.com.attacker.test/info', 'http://vod.snssdk.com/info', 'https://127.0.0.1/info']) {
    const browser = browserFixture({ fallback });
    await assert.rejects(browser.source.fetchAppPlayUrl(vid), /不受支持/);
    assert.equal(browser.calls.filter(call => call.method === 'appRequest').length, 1);
  }
});
test('registered native callback returns playInfo or a real error with the original request identity', async () => {
  const browser = browserFixture();
  await browser.window.__androidAppResolve('request-1', sid, vid);
  const success = browser.calls.find(call => call.method === 'appResolveResult');
  assert.equal(success.params.requestId, 'request-1'); assert.equal(success.params.seriesId, sid); assert.equal(success.params.vid, vid);
  assert.equal(success.params.playInfo.url, media); assert.equal(success.params.playInfo.contentKey, keyHex);
  const unavailable = browserFixture({ available: false });
  await unavailable.window.__androidAppResolve('request-2', sid, vid);
  const failed = unavailable.calls.find(call => call.method === 'appResolveResult');
  assert.equal(failed.params.requestId, 'request-2'); assert.equal(typeof failed.params.error, 'string'); assert.equal('playInfo' in failed.params, false);
  browser.unregister(); assert.equal(browser.window.__androidAppResolve, undefined);
});
test('invalid resolve identities never issue an HTTP request', async () => {
  const browser = browserFixture(); await browser.window.__androidAppResolve('bad-request', '../wrong', vid);
  assert.equal(browser.calls.some(call => call.method === 'appRequest'), false);
  assert.match(browser.calls[0].params.error, /信息无效/);
});
test('explicit quality reuses the actual Mac dimension filter rather than advertised quality labels', async () => {
  const videoList = {
    misleading: { main_url: media + '&row=low', codec_type: 'h264', quality_desc: '1080P', width: 360, height: 640 },
    portrait: { main_url: media + '&row=720', codec_type: 'h264', quality_desc: 'fixture portrait', vwidth: 720, vheight: 1280 },
    landscape: { main_url: media + '&row=1080', codec_type: 'hevc', quality_desc: 'fixture landscape', video_meta: { width: 1920, height: 1080 } },
  };
  for (const quality of ['720p', '1080p']) {
    const browser = browserFixture({ videoList }), desktop = desktopFixture({ videoList });
    const actual = await browser.source.fetchAppPlayUrl(vid, { quality }), expected = await desktop.source.fetchAppPlayUrl(vid, { quality });
    assert.deepEqual(JSON.parse(JSON.stringify(actual)), JSON.parse(JSON.stringify(requestedModel(expected, quality))));
    assert.equal(Math.min(actual.width, actual.height), Number.parseInt(quality, 10));
    assert.equal(actual.requestedQuality, quality);
    assert.ok(actual.variants.every(row => row.requestedQuality === quality && Math.min(row.width, row.height) >= Number.parseInt(quality, 10)));
    assert.equal(actual.variants.some(row => row.url.includes('row=low')), false);
  }
});
test('missing, fractional or insufficient dimensions really fail explicit quality without a downgraded model', async () => {
  for (const dimensions of [{}, { width: 719, height: 1920 }, { width: 1080, height: 0 }, { width: 720.5, height: 1280 }]) {
    const browser = browserFixture({ videoList: { one: { main_url: media, codec_type: 'h264', quality_desc: '1080P', ...dimensions } } });
    await assert.rejects(browser.source.fetchAppPlayUrl(vid, { quality: '720p' }), error => error.code === 'QUALITY_UNAVAILABLE');
    assert.equal(browser.calls.filter(call => call.method === 'appRequest').length, 2);
  }
});
test('quality callback binds the requested value, preserves three-argument auto, and rejects invalid values before network', async () => {
  const videoList = { one: { main_url: media, codec_type: 'h264', quality_desc: '720P', width: 1080, height: 1920 } };
  const browser = browserFixture({ videoList });
  await browser.window.__androidAppResolve('quality-1080', sid, vid, '1080p');
  let result = browser.calls.filter(call => call.method === 'appResolveResult').at(-1).params;
  assert.equal(result.requestedQuality, '1080p'); assert.equal(result.playInfo.requestedQuality, '1080p');
  assert.equal(result.playInfo.width, 1080); assert.equal(result.playInfo.height, 1920);
  await browser.window.__androidAppResolve('legacy-auto', sid, vid);
  result = browser.calls.filter(call => call.method === 'appResolveResult').at(-1).params;
  assert.equal(result.requestedQuality, 'auto'); assert.equal(result.playInfo.requestedQuality, 'auto');
  for (const quality of ['4k', '1080P', '', 1080, null, {}]) {
    const invalid = browserFixture({ videoList });
    await invalid.window.__androidAppResolve('invalid-quality', sid, vid, quality);
    assert.equal(invalid.calls.some(call => call.method === 'appRequest'), false);
    assert.equal(typeof invalid.calls[0].params.error, 'string');
    await assert.rejects(invalid.source.fetchAppPlayUrl(vid, { quality }));
    assert.equal(invalid.calls.some(call => call.method === 'appRequest'), false);
  }
});
test('trusted JNI DNS/timeout codes survive the actual unmodified Mac requestJson wrapper and callback', async () => {
  for (const code of ['ENOTFOUND', 'EAI_AGAIN', 'ETIMEDOUT', 'ECONNABORTED', 'ECONNRESET', 'ECONNREFUSED', 'EHOSTUNREACH', 'ENETUNREACH', 'EPIPE']) {
    const browser = browserFixture({ nativeFailure: code });
    await assert.rejects(browser.source.fetchAppPlayUrl(vid), error => error.code === code);
    browser.calls.length = 0;
    await browser.window.__androidAppResolve('typed-network', sid, vid, '1080p');
    const result = browser.calls.find(call => call.method === 'appResolveResult').params;
    assert.equal(result.errorCode, code); assert.equal(result.requestedQuality, '1080p');
    assert.equal(typeof result.error, 'string'); assert.equal('playInfo' in result, false);
    assert.equal(browser.calls.filter(call => call.method === 'appRequest').length, 1, 'JS does not independently multiply the native retry budget');
  }
});
test('untrusted remote JSON fields, business and generic native errors do not acquire retry codes', async () => {
  for (const options of [{ nativeFailure: 'ERR_NETWORK' }, { nativeFailure: 'BUSINESS_REJECTED' }, { available: false }, { fallback: 'https://foreign.example.net/no-source' }]) {
    const browser = browserFixture(options);
    await browser.window.__androidAppResolve('not-retryable', sid, vid);
    const result = browser.calls.find(call => call.method === 'appResolveResult').params;
    assert.equal('errorCode' in result, false); assert.equal('playInfo' in result, false);
  }
  setNativeCall(async () => ({ status: 200, body: '{"errorCode":"ENOTFOUND"}', url: 'https://vod.snssdk.com/fixture' }));
  assert.equal((await axiosNative({})).data, '{"errorCode":"ENOTFOUND"}', 'remote body is data, never JNI transport classification');
});
test('real appRequest RPC-owned deadline stays typed through the actual Mac wrapper, without typing other methods', async () => {
  const prior = globalThis.window;
  globalThis.window = { dispatchEvent() {}, AndroidBridge: { postMessage() {} } };
  try {
    const bridge = await import(`../src/android/bridge.mjs?lookupTimeout=${Date.now()}`);
    await assert.rejects(bridge.nativeCall('getInfo', {}, 1), error => error.code === undefined && /设备操作超时/.test(error.message));
    const browser = browserFixture({ callNative: (method, params) => bridge.nativeCall(method, params, 1) });
    await browser.window.__androidAppResolve('actual-rpc-timeout', sid, vid, '720p');
    const reply = browser.calls.find(call => call.method === 'appResolveResult').params;
    assert.equal(reply.errorCode, 'ETIMEDOUT'); assert.equal(reply.requestedQuality, '720p');
    assert.equal('playInfo' in reply, false); assert.equal(browser.calls.filter(call => call.method === 'appRequest').length, 1);
  } finally { globalThis.window = prior; }
});
test('generated module has no external code execution, browser fetch fallback or remaining Node crypto dependency', () => {
  assert.doesNotMatch(generated, /\beval\s*\(|new Function\s*\(|\bfetch\s*\(|require\(["'](?:node:crypto|axios)["']\)/);
  assert.equal(crypto.createHash('sha256').update(fs.readFileSync(path.join(root, 'src/native/app-source.js'))).digest('hex'), appHash);
});
