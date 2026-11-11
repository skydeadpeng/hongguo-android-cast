#!/usr/bin/env node
/** Offline playback regression gate. UI/native source checks are explicitly not device evidence. */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { createRequire } from 'node:module';
import vm from 'node:vm';
import { spawnSync } from 'node:child_process';

const args = process.argv.slice(2);
let root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
for (let i = 0; i < args.length; i++) {
  if (args[i] === '--root' && args[i + 1]) root = path.resolve(args[++i]);
  else { console.error('USAGE node scripts/check-android-playback.mjs [--root PROJECT_DIRECTORY]'); process.exit(2); }
}
const read = relative => fs.readFileSync(path.join(root, relative), 'utf8');
const java = 'android/app/src/main/java/io/github/jackotom/hongguo/android/';
let assertions = 0, passed = 0, failed = 0;
const verify = (condition, label) => { assertions++; assert.ok(condition, label); };
async function group(label, action) {
  try { await action(); passed++; console.log(`PASS ${label}`); }
  catch (error) { failed++; console.log(`FAIL ${label}: ${error.message}`); }
}
const sid = '1234567890123456789', vids = ['100001', '100002', '100003', '100004', '100005'];
const html = loaderData => `<script>window._ROUTER_DATA=${JSON.stringify({ loaderData })};</script>`;
const detailHtml = html({ 'player_(series_id)/page': {
  seriesDetail: { series_id: sid, series_name: '验收短剧', episode_cnt: 5, vid_list: vids, accessible_episode_cnt: 3 },
} });
const playerHtml = available => html({ 'player_(series_id)/(vid)/page': {
  isSuccess: true, series_id: sid, vid: vids[3],
  seriesDetail: { series_id: sid, vid_list: vids, accessible_episode_cnt: 3 },
  publicVideoSeo: { available, seriesId: sid, itemId: vids[3] },
} }) + `<script type="application/ld+json">${JSON.stringify({ '@type': 'VideoObject', url: `https://hongguoduanju.com/player/${sid}/${vids[3]}`, contentUrl: 'https://cdn.example.net/episode4.mp4?Range=0-4&token=synthetic%20only' })}</script>`;
let sourceModule, detail;
const seasonsModulePath = path.join(root, 'src/android/seasons.mjs');
const seasonsModule = fs.existsSync(seasonsModulePath) ? await import(pathToFileURL(seasonsModulePath).href) : null;
const seasonPlaybackModulePath = path.join(root, 'src/android/season-playback.mjs');
const seasonPlaybackModule = fs.existsSync(seasonPlaybackModulePath) ? await import(pathToFileURL(seasonPlaybackModulePath).href) : null;
const officialSeriesModulePath = path.join(root, 'src/android/official-series.mjs');
const officialSeriesModule = fs.existsSync(officialSeriesModulePath) ? await import(pathToFileURL(officialSeriesModulePath).href) : null;
await group('FUNCTION source preview=3 leaves episode 4 unknown in full 5-episode directory', async () => {
  sourceModule = await import(`${pathToFileURL(path.join(root, 'src/android/source.mjs')).href}?gate=${Date.now()}`);
  detail = sourceModule.parseDetail(detailHtml, sid);
  verify(detail.episodes.length === 5, 'full directory has five episodes');
  verify(detail.episodes[3].webAvailable === false, 'preview hint remains false');
  verify(detail.episodes[3].availability === 'unknown', 'episode 4 must be unknown, not locked');
  verify(detail.episodes[4].availability === 'unknown', 'episode 5 must be unknown');
});
await group('FUNCTION exact fourth-episode on-demand resolution and unavailable response', async () => {
  const requests = [];
  const source = sourceModule.createSource(async url => { requests.push(url); return url.endsWith(`/${vids[3]}`) ? playerHtml(true) : detailHtml; });
  const directory = await source.detail(sid);
  const video = await source.playback(sid, directory.episodes[3].vid);
  verify(video.vid === vids[3] && video.seriesId === sid, 'response bound to requested identity');
  verify(video.url === 'https://cdn.example.net/episode4.mp4?token=synthetic%20only', 'signed query preserved while Range removed');
  assert.deepEqual(requests, [`https://hongguoduanju.com/player/${sid}`, `https://hongguoduanju.com/detail?series_id=${sid}`, `https://hongguoduanju.com/player/${sid}/${vids[3]}`]); assertions++;
  let code;
  try { sourceModule.parsePlayback(playerHtml(false), sid, vids[3]); } catch (error) { code = error.code; }
  verify(code === 'EPISODE_UNAVAILABLE', 'explicit unavailable response stops playback');
});

await group('FUNCTION real JS bridge preferences, cache RPC and queued play payload', async () => {
  const descriptors = new Map(['window', 'localStorage'].map(key => [key, Object.getOwnPropertyDescriptor(globalThis, key)]));
  const saved = new Map(), messages = [];
  const fakeWindow = { dispatchEvent() {}, AndroidBridge: { postMessage(raw) {
    const message = JSON.parse(raw); messages.push(message);
    queueMicrotask(() => fakeWindow.__androidResponse({ id: message.id, result: message.method === 'getPlaybackCache' ? { files: 2, bytes: 4096 } : message.method === 'clearPlaybackCache' ? { removed: 1, bytes: 1024 } : { started: true }, error: null }));
  } } };
  Object.defineProperty(globalThis, 'window', { value: fakeWindow, configurable: true, writable: true });
  Object.defineProperty(globalThis, 'localStorage', { value: { getItem: key => saved.get(key) ?? null, setItem: (key, value) => saved.set(key, value) }, configurable: true, writable: true });
  try {
    const bridge = await import(`${pathToFileURL(path.join(root, 'src/android/bridge.mjs')).href}?gate=${Date.now()}`);
    assert.deepEqual(bridge.getPlaybackPreferences(), { autoplay: true, cleanupPlaybackCache: true, keepPreviousEpisodes: 1 }); assertions++;
    bridge.setPlaybackPreference('autoplay', false); verify(bridge.getPlaybackPreferences().autoplay === false, 'autoplay preference persists');
    bridge.setPlaybackPreference('cleanupPlaybackCache', false); verify(bridge.getPlaybackPreferences().cleanupPlaybackCache === false, 'cleanup preference persists independently');
    verify(bridge.getPlaybackPreferences().keepPreviousEpisodes === 1, 'retention remains bounded');
    bridge.setPlaybackPreference('keepPreviousEpisodes', 999); verify(bridge.getPlaybackPreferences().keepPreviousEpisodes === 1, 'arbitrary retention preference ignored');
    const cache = await bridge.nativeCall('getPlaybackCache'); verify(cache.files === 2 && cache.bytes === 4096, 'cache status RPC resolves');
    const cleared = await bridge.nativeCall('clearPlaybackCache'); verify(cleared.removed === 1 && cleared.bytes === 1024, 'cache clear RPC resolves');
    const queue = detail.episodes;
    await bridge.nativeCall('play', { series: { seriesId: sid }, episode: queue[3], episodes: queue, autoplay: true, cleanupPlaybackCache: true, keepPreviousEpisodes: 1 });
    const play = messages.find(message => message.method === 'play');
    verify(play.params.episodes.length === 5 && play.params.episode.vid === vids[3], 'unknown episode crosses bridge with complete queue');
    verify(play.params.autoplay === true && play.params.cleanupPlaybackCache === true, 'both playback preferences cross bridge');
    const count = messages.length;
    await assert.rejects(bridge.nativeCall('unrecognized-operation')); assertions++;
    verify(messages.length === count, 'unsupported RPC never reaches native layer');
    saved.set('hongguo.playbackPreferences', '{invalid');
    verify(bridge.getPlaybackPreferences().autoplay === true && bridge.getPlaybackPreferences().cleanupPlaybackCache === true, 'corrupt stored preferences restore defaults');
  } finally {
    for (const [key, descriptor] of descriptors) { if (descriptor) Object.defineProperty(globalThis, key, descriptor); else delete globalThis[key]; }
  }
});

await group('FUNCTION rendered Detail controls allow episode 4 and submit queue/preferences', async () => {
  // Real App component code executes after JSX transformation; hooks/transport are test doubles, not a browser.
  const { transformSync } = createRequire(import.meta.url)('esbuild');
  const calls = [], requested = [];
  const fixtureSource = sourceModule.createSource(async url => { requested.push(url); return playerHtml(true); });
  const preferences = { autoplay: true, cleanupPlaybackCache: true, keepPreviousEpisodes: 1 };
  let stateIndex = 0, accountPanelRenders = 0, updatePanelRenders = 0;
  const react = {
    createElement: (type, props, ...children) => ({ type, props: props || {}, children }),
    useState: initial => [stateIndex++ === 0 ? { busy: false, item: detail } : typeof initial === 'function' ? initial() : initial, () => {}],
    useEffect() {}, useRef: current => ({ current }), useCallback: fn => fn,
  };
  const exports = {};
  const code = transformSync(read('src/android/App.jsx') + '\nexports.__gateDetail = Detail;\n', { loader: 'jsx', format: 'cjs', target: 'node22' }).code;
  vm.runInNewContext(code, { exports, module: { exports }, console, require(name) {
    if (name === 'react') return { __esModule: true, default: react, ...react };
    if (name.includes('icons.jsx')) return new Proxy({ __esModule: true }, { get: (target, key) => key in target ? target[key] : () => null });
    if (name === './generated/mac-app-source.mjs') return { fetchAppPlayUrl() { throw new Error('App fallback unexpectedly called for valid web fixture'); } };
    if (name === './seasons.mjs' && seasonsModule) return seasonsModule;
    if (name === './season-playback.mjs' && seasonPlaybackModule) return seasonPlaybackModule;
    if (name === './official-series.mjs' && officialSeriesModule) return officialSeriesModule;
    // This gate invokes Detail only. AccountPanel and UpdatePanel belong to About;
    // register its import without executing or weakening any playback assertions.
    if (name === './AccountPanel.jsx') return { __esModule: true, default() { accountPanelRenders++; throw new Error('AccountPanel must not render in the Detail playback gate'); } };
    if (name === './UpdatePanel.jsx') return { __esModule: true, default() { updatePanelRenders++; throw new Error('UpdatePanel must not render in the Detail playback gate'); } };
    if (name === './source.mjs') return { ...sourceModule, createSource: () => fixtureSource };
    if (name === './bridge.mjs') return { getPlaybackPreferences: () => preferences, setPlaybackPreference() {}, requestHtml() {}, nativeCall: async (method, params) => { if (method === 'getPlaybackOptions') return { quality: 'auto', speed: 1 }; calls.push({ method, params }); return { started: true }; } };
    if (name.includes('LICENSE')) return '';
    throw new Error(`unexpected component import ${name}`);
  } }, { filename: 'App.playback-gate.cjs' });
  const tree = exports.__gateDetail({ seed: detail, initialVid: vids[3], library: { favorites: [], history: [] }, onBack() {}, onLibrary() {}, notify() {} });
  const elements = [];
  const walk = value => { if (Array.isArray(value)) value.forEach(walk); else if (value && typeof value === 'object') { if ('type' in value) elements.push(value); walk(value.children); } };
  walk(tree);
  const text = value => Array.isArray(value) ? value.map(text).join('') : value && typeof value === 'object' ? text(value.children) : typeof value === 'string' ? value : '';
  const buttons = elements.filter(element => element.type === 'button');
  const fourth = buttons.find(button => button.props['aria-label']?.includes('第 4 集'));
  verify(fourth && fourth.props.disabled !== true, 'fourth unknown episode selection button enabled');
  const playButton = buttons.find(button => text(button.children).includes('播放本集'));
  verify(playButton && playButton.props.disabled !== true, 'selected unknown episode play action enabled');
  await playButton.props.onClick();
  verify(requested.length === 0, 'play delegates exact on-demand resolution to native without an early JS preflight');
  verify(calls.length === 1 && calls[0].method === 'play' && calls[0].params.resolveInPlayer === true && !('url' in calls[0].params) && !('playInfo' in calls[0].params), 'click starts unified native preparation without an unverified initial URL');
  const params = calls[0].params;
  verify(params.episodes.length === 5 && params.episode.vid === vids[3] && params.episode.availability === 'unknown', 'full ordered queue, selection and unknown hint preserved');
  verify(params.autoplay && params.cleanupPlaybackCache && params.keepPreviousEpisodes === 1, 'native player receives actual UI playback defaults');
  verify(accountPanelRenders === 0, 'Detail playback does not execute About account UI');
  verify(updatePanelRenders === 0, 'Detail playback does not execute About update UI');
});

for (const test of ['run-episode-resolver-tests.sh', 'run-playback-cache-tests.sh', 'run-playback-queue-tests.sh']) {
  await group(`FUNCTION native offline ${test}`, () => {
    const filename = path.join(root, 'android/tests', test);
    verify(fs.existsSync(filename), `${test} exists`);
    const result = spawnSync('/bin/bash', [filename], { encoding: 'utf8', timeout: 60_000 });
    if (result.stdout?.trim()) console.log(result.stdout.trim());
    if (result.stderr?.trim()) console.log(result.stderr.trim());
    verify(!result.error && result.status === 0, `${test} exit=${result.status} ${result.error?.message || ''}`);
  });
}

await group('STATIC UI source has no preview-count/webAvailable disabling gate', () => {
  const app = read('src/android/App.jsx');
  verify(!/disabled=\{[^}]*webAvailable/.test(app), 'webAvailable never disables an action');
  verify(!/if\s*\([^)]*!episode\??\.webAvailable/.test(app), 'operation has no directory hint guard');
  verify(/getPlaybackCache/.test(app) && /clearPlaybackCache/.test(app), 'cache status and clear controls wired');
});
await group('STATIC native bridge transmits queue/preferences and exposes isolated cache', () => {
  const bridge = read(java + 'NativeBridge.java');
  for (const field of ['episodes', 'autoplay', 'cleanupPlaybackCache', 'getPlaybackCache', 'clearPlaybackCache']) verify(bridge.includes(`"${field}"`), `native bridge carries ${field}`);
  verify(!/if\s*\([^)]*!episode\.optBoolean\("webAvailable"\)/.test(bridge), 'native play/download does not reject unknown directory hint');
  verify(/PlaybackCache\.get\(activity\)\.status\(/.test(bridge) && /PlaybackCache\.get\(activity\)\.clear\(/.test(bridge), 'cache RPC uses isolated cache facade');
});
await group('STATIC native player revealable return, custom controls, next and cache integration', () => {
  const player = read(java + 'PlayerActivity.java');
  verify(!/\bMediaController\b/.test(player), 'framework controller cannot intercept the first back press');
  verify(/(?:Button )?back = button\("[^"\n]*返回[^"\n]*", this::exitPlayer\)/.test(player) && /top\.addView\(back/.test(player) && /exitPlayer\(\)\s*\{[^}]*finish\(/.test(player), 'top return action finishes the player when controls are revealed');
  verify(/KEYCODE_BACK/.test(player), 'TV/phone back has explicit player route');
  verify(/setOnCompletionListener/.test(player) && /autoplay/.test(player) && /next/i.test(player), 'completion has autoplay/next branch');
  for (const method of ['protect', 'release', 'fetch', 'prune']) verify(new RegExp(`\\.${method}\\(`).test(player), `player integrates cache.${method}`);
  verify(/cleanupPlaybackCache/.test(player), 'per-session cleanup switch respected');
  verify(/EpisodeResolver\.resolve/.test(player) || (/PlaybackSources\.resolve/.test(player) && /EpisodeResolver\.resolve/.test(read(java + 'PlaybackSources.java'))), 'next episode resolves exact official identity through the shared web/App resolver');
});
console.log(`ANDROID_PLAYBACK_${failed ? 'FAILED' : 'OK'} groupsPassed=${passed} groupsFailed=${failed} assertions=${assertions} networkRequests=0 deviceTests=not_run`);
process.exitCode = failed ? 1 : 0;
