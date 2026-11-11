#!/usr/bin/env node
/** Real Android source adapter -> production Java plan contracts, entirely synthetic/offline. */
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import vm from 'node:vm';
import crypto from 'node:crypto';
import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';
import { spawnSync } from 'node:child_process';
const own = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
let root = own;
for (let i = 2; i < process.argv.length; i++) {
  if (process.argv[i] === '--root' && process.argv[i + 1]) root = path.resolve(process.argv[++i]);
  else throw Error('Usage: check-android-source-candidates.mjs [--root PROJECT]');
}
const { build, transformSync } = createRequire(import.meta.url)('esbuild');
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'hongguo-source-candidates-'));
const sid = '1234567890123456789', vid = '2345678901234567890';
const javaCases = [];
let passed = 0, failed = 0, checks = 0;
const check = (ok, label) => { checks++; assert.ok(ok, label); };
async function group(label, fn) {
  try { await fn(); passed++; console.log('PASS ' + label); }
  catch (error) { failed++; console.log('FAIL ' + label + ': ' + error.message); }
}
const read = name => fs.readFileSync(path.join(root, name), 'utf8');
const shared = ['src/native/app-source.js', 'src/native/sm3.js', 'src/native/download-quality.js', 'src/native/hongguo.js'];
const beforeHashes = Object.fromEntries(shared.map(name => [name, crypto.createHash('sha256').update(read(name)).digest('hex')]));
const key1 = '0123456789abcdef0123456789abcdef', key2 = 'fedcba9876543210fedcba9876543210';
function encodedKey(key) {
  const plain = Buffer.from('0' + key), raw = Buffer.alloc(34), previous = [246, 85];
  for (let i = 0; i < plain.length; i++) { raw[i + 1] = ((plain[i] + 21 + i.toString(2).split('1').length - 1) & 255) ^ previous[i & 1]; previous[i & 1] = raw[i + 1]; }
  raw[0] = 48 ^ raw[1] ^ raw[2]; return raw.toString('base64');
}
const url = n => `https://cdn.qznovelvod.com/candidate-${n}.mp4?signature=fixture%2B${n}`;
function row(n, count = 1, extras = {}) {
  return { main_url: url(n), backup_url_list: Array.from({ length: count - 1 }, (_, i) => url(n + i + 1)), codec_type: 'h264', width: 1080, height: 1920, quality_desc: '1080P', spade_a: encodedKey(key1), ...extras };
}
async function bundle(mockInfo) {
  const adapter = path.join(root, 'src/android/mac-source');
  const result = await build({ entryPoints: [path.join(adapter, 'entry.mjs')], bundle: true, format: 'esm', platform: 'browser', target: 'chrome67', write: false,
    inject: [path.join(adapter, 'buffer-runtime.mjs')], alias: { 'node:crypto': path.join(adapter, 'crypto-shim.mjs'), axios: path.join(adapter, 'axios-common.cjs') }, define: { 'AbortSignal.timeout': 'androidAppTimeout' },
    ...(mockInfo ? { plugins: [{ name: 'synthetic-shared-result', setup(b) { b.onLoad({ filter: /\/src\/native\/app-source\.js$/ }, () => ({ contents: `export default {fetchAppPlayUrl:async()=>(${JSON.stringify(mockInfo)})};`, loader: 'js' })); } }] } : {}),
  });
  return transformSync(result.outputFiles[0].text, { format: 'iife', globalName: 'SourceAdapter', target: 'chrome89' }).code;
}
function replies(rows) {
  return p => ({ status: 200, headers: {}, url: p.url, body: JSON.stringify(p.method === 'POST'
    ? { code: 0, data: { [vid]: { video_model: JSON.stringify({ fallback_api: 'https://vod.snssdk.com/offline-candidates' }) } } }
    : { video_info: { data: { video_list: rows } } }) });
}
function actualAdapter(code, rows) {
  const calls = [], reply = replies(rows);
  const window = { AndroidCrypto: { process(raw) { const r = JSON.parse(raw); return JSON.stringify({ data: crypto.createHash(r.algorithm).update(Buffer.from(r.data, 'base64')).digest('base64'), error: null }); } } };
  const context = vm.createContext({ window, URL, URLSearchParams, AbortSignal, AbortController, setTimeout, clearTimeout, crypto: { getRandomValues: b => crypto.webcrypto.getRandomValues(b) } });
  vm.runInContext(code, context);
  context.SourceAdapter.registerAppResolver(async (method, params) => { calls.push({ method, params: structuredClone(params) }); return method === 'appRequest' ? reply(params) : { accepted: true }; });
  return { api: context.SourceAdapter, calls, window };
}
function desktop(rows) {
  const reply = replies(rows), module = { exports: {} }, require = createRequire(path.join(root, 'src/native/app-source.js'));
  vm.runInNewContext(read('src/native/app-source.js'), { module, Buffer, URL, URLSearchParams, AbortSignal, require(name) {
    if (name === 'axios') return async p => { const r = reply(p); return { ...r, data: r.body }; };
    return name === './sm3' || name === './download-quality' ? require(name) : require(name);
  } });
  return module.exports;
}
function expectedProjection(info, quality) {
  const seen = new Set([info.url]), variants = [];
  for (const candidate of info.variants || []) {
    if (seen.has(candidate.url)) continue;
    seen.add(candidate.url);
    if (variants.length < 7) variants.push({ ...candidate, requestedQuality: quality });
  }
  return { ...info, requestedQuality: quality, ...(Array.isArray(info.variants) ? { variants } : {}) };
}
async function acceptance(code, label, rows, quality = 'auto') {
  const adapter = actualAdapter(code, rows), actual = JSON.parse(JSON.stringify(await adapter.api.fetchAppPlayUrl(vid, { quality })));
  const original = JSON.parse(JSON.stringify(await desktop(rows).fetchAppPlayUrl(vid, { quality })));
  const expected = expectedProjection(original, quality);
  javaCases.push({ name: label, plan: { ...actual, seriesId: sid }, expected: { ...expected, seriesId: sid } });
  assert.deepEqual(actual, expected, 'only duplicate/beyond-budget candidates may change; every field and priority must be preserved'); checks++;
  check(actual.variants.length <= 7, 'bounded seven backups');
  check(!actual.variants.some(candidate => candidate.url === actual.url), 'primary not duplicated');
  check(new Set([actual.url, ...actual.variants.map(candidate => candidate.url)]).size === actual.variants.length + 1, 'native URL identity is unique');
  check(adapter.calls.filter(c => c.method === 'appRequest').length === 2, 'same two signed model requests; no new source retries');
}
try {
  const code = await bundle();
  for (const count of [1, 8, 9, 12, 300]) await group(`actual App model with ${count} distinct candidates`, () => acceptance(code, `count-${count}`, { one: row(1, count) }));
  await group('multiple valid renditions and CDN backups retain source priority', () => acceptance(code, 'three-renditions', { a: row(1, 3), b: row(4, 3), c: row(7, 3) }));
  for (const quality of ['720p', '1080p']) await group(`explicit ${quality} candidates preserve dimensions, keys and requested quality`, () => acceptance(code, quality, {
    low: row(1000, 3, { width: 360, height: 640 }), target: row(1, 12, { width: quality === '720p' ? 720 : 1080, height: quality === '720p' ? 1280 : 1920 }), high: row(100, 3, { codec_type: 'hevc', width: 2160, height: 3840 }),
  }, quality));
  await group('duplicate URL uses first complete candidate, including its own content key', () => acceptance(code, 'url-duplicate-different-key', { a: row(1, 3), b: row(1, 3, { spade_a: encodedKey(key2) }), c: row(10, 3) }, '1080p'));
  await group('empty source list remains a real failure, never a playable empty plan', async () => {
    await assert.rejects(actualAdapter(code, {}).api.fetchAppPlayUrl(vid), /未提供兼容/); checks++;
  });
  await group('invalid or foreign candidates never enter the bounded output', () => acceptance(code, 'blocked-source-URLs', {
    invalid: row(100, 1, { main_url: 'https://127.0.0.1/private' }), foreign: row(101, 1, { main_url: 'https://cdn.qznovelvod.com.attacker.test/a.mp4' }), wrongScheme: row(102, 1, { main_url: 'http://cdn.qznovelvod.com/a.mp4' }), valid: row(1, 12),
  }, '1080p'));
  const base = { url: url(1), vid, source: 'app', contentKey: key1, userAgent: 'fixture-primary-agent', referer: 'https://novelquickapp.com/', width: 1080, height: 1920, quality: '1080p' };
  await group('all candidate dimensions are validated before truncation, even the last omitted candidate', async () => {
    const variants = Array.from({ length: 12 }, (_, i) => ({ ...base, url: url(i + 2) })); variants[11].width = 360;
    const mock = actualAdapter(await bundle({ ...base, variants }), {});
    await assert.rejects(mock.api.fetchAppPlayUrl(vid, { quality: '1080p' }), error => error.code === 'QUALITY_UNAVAILABLE'); checks++;
    variants[11].width = 1080.5;
    const fractional = actualAdapter(await bundle({ ...base, variants }), {});
    await assert.rejects(fractional.api.fetchAppPlayUrl(vid, { quality: '1080p' }), error => error.code === 'QUALITY_UNAVAILABLE'); checks++;
  });
  await group('normalization never merges metadata from duplicate URLs or changes caller identity', async () => {
    const variants = [{ ...base, contentKey: key2, userAgent: 'wrong-duplicate-agent' }, { ...base, url: url(2), contentKey: key2, userAgent: 'fixture-backup-agent' }];
    const mock = actualAdapter(await bundle({ ...base, variants }), {}), actual = JSON.parse(JSON.stringify(await mock.api.fetchAppPlayUrl(vid, { quality: '1080p' })));
    assert.deepEqual(actual, expectedProjection({ ...base, variants }, '1080p')); checks++;
    check(actual.vid === vid && actual.contentKey === key1 && actual.userAgent === 'fixture-primary-agent', 'primary identity/key/header unmodified');
    check(actual.variants[0].contentKey === key2 && actual.variants[0].userAgent === 'fixture-backup-agent', 'backup metadata comes from its own first row');
  });
  await group('real resolver callback preserves request, series and episode identities with bounded media', async () => {
    const f = actualAdapter(code, { one: row(1, 12) }); await f.window.__androidAppResolve('source-candidate-fixture', sid, vid, '1080p');
    const result = f.calls.find(c => c.method === 'appResolveResult')?.params;
    check(result.requestId === 'source-candidate-fixture' && result.seriesId === sid && result.vid === vid && result.requestedQuality === '1080p', 'callback binding unchanged');
    check(result.playInfo.variants.length === 7 && !result.error, 'callback publishes bounded candidates without hiding failure');
  });
  fs.writeFileSync(path.join(tmp, 'plans.json'), JSON.stringify(javaCases));
  await group('actual adapter plans are accepted by production Java register; native rejection contracts stay strict', async () => {
    const run = spawnSync('bash', [path.join(own, 'android/tests/run-source-candidates-contract-tests.sh'), '--source-root', root, '--input', path.join(tmp, 'plans.json')], { cwd: root, encoding: 'utf8', timeout: 60000, maxBuffer: 2 * 1024 * 1024 });
    process.stdout.write(run.stdout || ''); process.stdout.write(run.stderr || ''); check(run.status === 0 && !run.error, `production Java contract exit=${run.status}`);
  });
  await group('all four shared upstream source modules remain unchanged by acceptance', () => {
    for (const name of shared) check(crypto.createHash('sha256').update(read(name)).digest('hex') === beforeHashes[name], 'upstream source bytes retained: ' + name);
  });
} finally { fs.rmSync(tmp, { recursive: true, force: true }); }
console.log(`ANDROID_SOURCE_CANDIDATES_${failed ? 'FAILED' : 'OK'} groupsPassed=${passed} groupsFailed=${failed} checks=${checks} upstreamVersion=1.80.0 networkRequests=0 deviceTests=not_run`);
process.exitCode = failed ? 1 : 0;
