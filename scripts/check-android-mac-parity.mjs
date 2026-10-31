#!/usr/bin/env node
/** Offline Mac-source integration gate. STATIC groups are not device/media-playback evidence. */
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import { createRequire } from 'node:module';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { spawnSync } from 'node:child_process';

let root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const args = process.argv.slice(2);
for (let i = 0; i < args.length; i++) {
  if (args[i] === '--root' && args[i + 1]) root = path.resolve(args[++i]);
  else { console.error('USAGE node scripts/check-android-mac-parity.mjs [--root PROJECT_DIRECTORY]'); process.exit(2); }
}
// Source archives intentionally omit generated assets. Build only the selected
// target tree; never silently borrow a bundle from the gate's own checkout.
const generatedPath = path.join(root, 'src/android/generated/mac-app-source.mjs');
const targetBuilder = path.join(root, 'scripts/build-android-mac-source.mjs');
let preparationError;
if (!fs.existsSync(generatedPath) && fs.existsSync(targetBuilder)) {
  const built = spawnSync(process.execPath, [targetBuilder], { cwd: root, encoding: 'utf8', timeout: 60_000 });
  if (!built.error && built.status === 0 && fs.existsSync(generatedPath)) {
    console.log(`PREPARE target generated Mac-source bundle built from ${root}`);
  } else {
    preparationError = new Error('目标项目尚未生成 Mac-source bundle；请在 --root 指定目录先执行 npm ci && node scripts/build-android-mac-source.mjs，然后重跑门禁。');
    console.log(`PREPARE_FAILED target build exit=${built.status} ${built.error?.message || ''}`);
  }
}
const read = relative => {
  if (relative === 'src/android/generated/mac-app-source.mjs' && preparationError) throw preparationError;
  return fs.readFileSync(path.join(root, relative), 'utf8');
};
const serialized = value => JSON.parse(JSON.stringify(value));
const java = 'android/app/src/main/java/io/github/jackotom/hongguo/android/';
const { transformSync } = createRequire(import.meta.url)('esbuild');
let passed = 0, failed = 0, assertions = 0;
const verify = (condition, label) => { assertions++; assert.ok(condition, label); };
async function group(label, run) {
  try { await run(); passed++; console.log(`PASS ${label}`); }
  catch (error) { failed++; console.log(`FAIL ${label}: ${error.message}`); }
}
const sid = '1234567890123456789', vid = '2345678901234567890';
const playInfo = {
  url: 'https://v3-reading.qznovelvod.com/fixture-only.mp4?token=synthetic%20query',
  vid, source: 'app', contentKey: '0123456789abcdef0123456789abcdef',
  userAgent: 'Fixture Mac App Agent', referer: 'https://novelquickapp.com/', codec: 'h264', quality: '720P',
  variants: [{ url: 'https://v3-reading.qznovelvod.com/fixture-backup.mp4', contentKey: '0123456789abcdef0123456789abcdef' }],
};
const autoModel = value => ({ ...value, requestedQuality: 'auto', ...(Array.isArray(value.variants)
  ? { variants: value.variants.map(row => ({ ...row, requestedQuality: 'auto' })) } : {}) });
let sourceModule;
await group('FUNCTION webpage HTTP404 really invokes the App fallback and retains the complete model', async () => {
  sourceModule = await import(`${pathToFileURL(path.join(root, 'src/android/source.mjs')).href}?macGate=${Date.now()}`);
  const requested = [], fallback = [];
  const source = sourceModule.createSource(async url => { requested.push(url); throw Object.assign(new Error('HTTP 404'), { status: 404 }); }, {
    fallback: async (requestedVid, options) => { fallback.push({ requestedVid, options }); return playInfo; },
  });
  const info = await source.playback(sid, vid);
  assert.deepEqual(requested, [`https://hongguoduanju.com/player/${sid}/${vid}`, `https://hongguoduanju.com/player/_/${vid}`]); assertions++;
  verify(fallback.length === 1 && fallback[0].requestedVid === vid && fallback[0].options.seriesId === sid, 'App fallback truly called after HTTP404');
  assert.deepEqual(serialized(info), serialized({ ...autoModel(playInfo), seriesId: sid })); assertions++;
  verify(info.contentKey === playInfo.contentKey && info.variants[0].contentKey === playInfo.variants[0].contentKey, 'key, media headers and alternatives remain intact');
});

await group('FUNCTION normal webpage404 resolves through the same Mac underscore route before App fallback', async () => {
  const requested = []; let appCalls = 0;
  const html = '<script>window._ROUTER_DATA=' + JSON.stringify({ loaderData: { 'player_(series_id)/(vid)/page': {
    isSuccess: true, series_id: sid, vid, seriesDetail: { series_id: sid }, publicVideoSeo: { available: true, seriesId: sid, itemId: vid },
  } } }) + '</script><script type="application/ld+json">' + JSON.stringify({ '@type': 'VideoObject',
    url: `https://hongguoduanju.com/player/${sid}/${vid}`, contentUrl: playInfo.url }) + '</script>';
  const source = sourceModule.createSource(async url => {
    requested.push(url); if (!url.includes('/player/_/')) throw new Error('HTTP 404'); return html;
  }, { fallback: async () => { appCalls++; return playInfo; } });
  const info = await source.playback(sid, vid);
  assert.deepEqual(requested, [`https://hongguoduanju.com/player/${sid}/${vid}`, `https://hongguoduanju.com/player/_/${vid}`]); assertions++;
  verify(info.url === playInfo.url && info.seriesId === sid && info.vid === vid, 'backup HTML still binds the expected real sid and vid');
  verify(appCalls === 0, 'successful underscore webpage never invokes App');
});

await group('FUNCTION real Detail uses native play preparation and complete strict download model', async () => {
  const calls = [], fallbackCalls = [];
  const detail = { seriesId: sid, title: '合成验收剧', episodeCount: 4, accessibleEpisodeCount: 3,
    episodes: [{ vid: '100001', index: 1, webAvailable: true, availability: 'available' },
      { vid, index: 4, webAvailable: false, availability: 'unknown' }] };
  let stateIndex = 0;
  const react = { createElement: (type, props, ...children) => ({ type, props: props || {}, children }),
    useState: initial => [stateIndex++ === 0 ? { busy: false, item: detail } : typeof initial === 'function' ? initial() : initial, () => {}],
    useEffect() {}, useRef: current => ({ current }), useCallback: fn => fn };
  const exports = {}, code = transformSync(read('src/android/App.jsx') + '\nexports.__Detail = Detail;\n', { loader: 'jsx', format: 'cjs', target: 'node22' }).code;
  vm.runInNewContext(code, { exports, module: { exports }, console, require(name) {
    if (name === 'react') return { __esModule: true, default: react, ...react };
    if (name.includes('icons.jsx')) return new Proxy({ __esModule: true }, { get: (target, key) => key in target ? target[key] : () => null });
    if (name === './source.mjs') return sourceModule;
    if (name === './generated/mac-app-source.mjs') return { fetchAppPlayUrl: async requestedVid => { fallbackCalls.push(requestedVid); return playInfo; } };
    // Season/series helpers load at module scope; the exported Detail flow under test
    // only needs them to be callable. Panels render nothing without their effects.
    if (name === './seasons.mjs') return { createSeasonLookup: () => ({}), parseSeasonTitle: () => ({ base: '', season: 0 }), seasonCandidates: seed => [seed] };
    if (name === './announcements.mjs') return { announcementLink: () => '', createAnnouncements: () => ({}) };
    if (name === './history-view.mjs') return { completedEpisode: () => false, filterHistory: rows => rows, withHistoryHints: () => ({ hints: [], decorate: row => row }) };
    if (name === './season-playback.mjs') return { latestSeasonHistory: () => [], assertPlaybackRequestSize() {} };
    if (name === './official-series.mjs') return { createOfficialSeasonLookup: () => ({}), requestOfficialSeries: async () => [] };
    if (name === './AccountPanel.jsx' || name === './UpdatePanel.jsx') return { __esModule: true, default: () => null };
    if (name === './bridge.mjs') return { requestHtml: async () => { throw new Error('HTTP 404'); },
      nativeCall: async (method, params) => { calls.push({ method, params }); return method === 'getPlaybackOptions' ? { quality: 'auto', speed: 1 } : { started: true }; },
      getPlaybackPreferences: () => ({ autoplay: true, cleanupPlaybackCache: true, keepPreviousEpisodes: 1 }), setPlaybackPreference() {} };
    if (name.includes('LICENSE')) return '';
    throw new Error(`unexpected UI import ${name}`);
  } }, { filename: 'App.mac-parity-gate.cjs' });
  const tree = exports.__Detail({ seed: detail, initialVid: vid, library: { favorites: [], history: [] }, onBack() {}, onLibrary() {}, notify() {} });
  const elements = [], walk = value => { if (Array.isArray(value)) value.forEach(walk); else if (value && typeof value === 'object') { if ('type' in value) elements.push(value); walk(value.children); } };
  const text = value => Array.isArray(value) ? value.map(text).join('') : value && typeof value === 'object' ? text(value.children) : typeof value === 'string' ? value : '';
  walk(tree);
  for (const [method, title] of [['play', '播放本集'], ['download', '下载本集']]) {
    const button = elements.find(element => element.type === 'button' && text(element.children).includes(title));
    verify(button && button.props.disabled !== true, `${method} action is enabled`); await button.props.onClick();
    const request = calls.find(call => call.method === method);
    verify(request?.params.episode.vid === vid, `${method} identity matches`);
    if (method === 'play') {
      verify(request.params.resolveInPlayer === true && !('url' in request.params) && !('playInfo' in request.params), 'play enters unified native preparation without an early JS failure');
      verify(fallbackCalls.length === 0, 'play has no premature App model lookup');
    } else {
      verify(request.params.url === playInfo.url, 'download URL matches');
      assert.deepEqual(serialized(request.params.playInfo), serialized({ ...autoModel(playInfo), seriesId: sid })); assertions++;
    }
  }
  verify(fallbackCalls.length === 1 && fallbackCalls[0] === vid, 'only strict manual download resolves the complete App model');
  verify(calls.find(call => call.method === 'play').params.episodes.length === 2, 'continuous queue is still included');
});

await group('FUNCTION registered App resolver echoes identity and real result/error without localStorage', async () => {
  const writes = [], calls = [];
  const window = { AndroidCrypto: { process(raw) {
    const request = JSON.parse(raw); assert.equal(request.operation, 'digest', 'fixture uses only the real native digest primitive');
    return JSON.stringify({ data: crypto.createHash(request.algorithm).update(Buffer.from(request.data, 'base64')).digest('base64'), error: null });
  } } };
  const context = vm.createContext({ window, console, URL, URLSearchParams, AbortSignal, AbortController, setTimeout, clearTimeout,
    crypto: { getRandomValues(bytes) { return crypto.webcrypto.getRandomValues(bytes); } },
    localStorage: { getItem() { return null; }, setItem(...args) { writes.push(args); } } });
  const module = transformSync(read('src/android/generated/mac-app-source.mjs'), { format: 'iife', globalName: 'AppSource', target: 'chrome89' }).code;
  vm.runInContext(module, context);
  let unavailable = false;
  context.AppSource.registerAppResolver(async (method, params) => {
    calls.push({ method, params: structuredClone(params) });
    if (method !== 'appRequest') return { accepted: true };
    const body = params.method === 'POST' ? { code: unavailable ? 1 : 0, data: { [vid]: { video_model: JSON.stringify({ fallback_api: 'https://vod.snssdk.com/fixture' }) } } } :
      { video_info: { data: { video_list: { one: { main_url: playInfo.url, codec_type: 'h264' } } } } };
    return { status: 200, body: JSON.stringify(body), url: params.url };
  });
  await window.__androidAppResolve('gate-success', sid, vid);
  const success = calls.find(call => call.method === 'appResolveResult');
  verify(success?.params.requestId === 'gate-success' && success.params.seriesId === sid && success.params.vid === vid, 'success returns exact native request identity');
  verify(success.params.playInfo?.source === 'app' && success.params.playInfo.url === playInfo.url, 'result comes from the bundled desktop protocol');
  calls.length = 0; unavailable = true; await window.__androidAppResolve('gate-failure', sid, vid);
  const failure = calls.find(call => call.method === 'appResolveResult');
  verify(failure?.params.requestId === 'gate-failure' && typeof failure.params.error === 'string' && !failure.params.playInfo, 'failure never invents a playable result');
  verify(writes.length === 0, 'resolved URLs, keys and identities are never written to localStorage');
});

await group('STATIC native App transport, plan validation, callback identities and player/cache CENC wiring', () => {
  const native = read(java + 'NativeBridge.java'), main = read(java + 'MainActivity.java');
  const plans = read(java + 'PlaybackSources.java'), player = read(java + 'PlayerActivity.java'), cache = read(java + 'PlaybackCache.java');
  verify(native.includes('case "appRequest"') && native.includes('AppTransport.request(params)'), 'App RPC reaches native transport');
  verify(native.includes('case "appResolveResult"') && native.includes('PlaybackSources.result(params)'), 'App result reaches pending native resolver');
  verify(main.includes('new CryptoBridge()') && main.includes('"AndroidCrypto"') && main.includes('PlaybackSources.attach(this)'), 'native primitives and background callback host attached');
  verify(plans.includes('request.sid.equals(params.optString("seriesId"))') && plans.includes('request.vid.equals(params.optString("vid"))'), 'native pending result verifies both identities');
  verify(plans.includes('CencDecryptor.decryptInPlace') && plans.includes('candidate.userAgent') && plans.includes('candidate.referer'), 'native download path decrypts CENC with App media headers');
  verify(cache.includes('PlaybackSources::download'), 'isolated playback cache uses the shared media-plan downloader');
  verify(player.includes('PlaybackSources.resolve') && player.includes('!media.plan.source.equals("web")') && player.includes('cache.fetchForeground('), 'continuous player handles encrypted App plans');
  verify(native.includes('if (!plan.source.equals("web"))') && native.includes('downloadPrepared(') && native.includes('addCompletedDownload('), 'manual encrypted downloads are prepared before completion');
  const transport = read(java + 'AppTransport.java');
  verify(transport.includes('fqnovel.com') && transport.includes('snssdk.com') && transport.includes('method.equals("POST")'), 'native fixed-domain request contract present');
  verify(/registerAppResolver\(nativeCall\)/.test(read('src/android/main.jsx')), 'App callback registered before user interaction');
});
console.log(`ANDROID_MAC_PARITY_${failed ? 'FAILED' : 'OK'} groupsPassed=${passed} groupsFailed=${failed} assertions=${assertions} networkRequests=0 deviceTests=not_run`);
process.exitCode = failed ? 1 : 0;
