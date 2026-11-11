// Real-device playback regression. Requires the two v0.2 debug APKs already installed.
// Uses actual website/native bridge/media only; never fakes playback or deletes manual downloads.
import { execFileSync } from 'node:child_process';
import { existsSync, mkdirSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import assert from 'node:assert/strict';
import { createSource } from '../src/android/source.mjs';
const sdk = process.env.ANDROID_HOME || `${process.env.HOME}/Library/Android/sdk`;
const serial = process.env.HONGGUO_DEVICE || 'emulator-5582';
const port = process.env.ADB_PORT || '5038';
const cdpPort = process.env.HONGGUO_DEVICE_CDP_PORT || '9227';
const output = resolve(process.env.HONGGUO_DEVICE_OUTPUT || 'verification/android-playback');
const phonePkg = 'io.github.jackotom.hongguo.android';
const tvPkg = `${phonePkg}.tv`;
const sid = '7686713195246930968';
mkdirSync(output, { recursive: true });
const events = [];
const adb = (...args) => execFileSync(`${sdk}/platform-tools/adb`, ['-P', port, '-s', serial, ...args], { timeout: 25000, maxBuffer: 12 * 1024 * 1024 });
const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
function record(name, result) { const event = { time: new Date().toISOString(), name, result }; events.push(event); writeFileSync(`${output}/playback-device-results.json`, JSON.stringify(events, null, 2)); console.log(name, JSON.stringify(result)); }
async function until(fn, timeout = 45000, label = 'device condition') { const end = Date.now() + timeout; let last; while (Date.now() < end) { last = await fn(); if (last) return last; await pause(300); } throw Error(`Timeout: ${label}; last=${JSON.stringify(last)}`); }
function resumed() { return adb('shell', 'dumpsys', 'activity', 'activities').toString().split('\n').filter(line => /ResumedActivity/.test(line)).join('\n'); }
const isPlayer = () => /PlayerActivity/.test(resumed());
const isMain = () => /MainActivity/.test(resumed());
function screenshot(name, xml) { writeFileSync(`${output}/${name}.png`, adb('exec-out', 'screencap', '-p')); if (xml) writeFileSync(`${output}/${name}.xml`, xml); }
const unescapeXml = text => text.replaceAll('&quot;', '"').replaceAll('&amp;', '&').replaceAll('&lt;', '<').replaceAll('&gt;', '>').replaceAll('&apos;', "'");
function prepareSnapshotHelper() {
  const home = process.env.JAVA_HOME || (existsSync('/Applications/Android Studio.app/Contents/jbr/Contents/Home') ? '/Applications/Android Studio.app/Contents/jbr/Contents/Home' : '');
  const java = executable => home ? `${home}/bin/${executable}` : executable;
  const helper = `${output}/qa-helper`; mkdirSync(`${helper}/classes`,{recursive:true}); mkdirSync(`${helper}/dex`,{recursive:true});
  execFileSync(java('javac'),['-source','8','-target','8','-cp',`${sdk}/platforms/android-35/android.jar`,'-d',`${helper}/classes`,'scripts/android-playback-qa/NativeSnapshot.java'],{stdio:'pipe'});
  execFileSync(process.env.D8_BIN || `${sdk}/build-tools/35.0.0/d8`,['--min-api','26','--lib',`${sdk}/platforms/android-35/android.jar`,'--output',`${helper}/dex`,`${helper}/classes/NativeSnapshot.class`],{env:{...process.env,...(home?{JAVA_HOME:home}:{})},stdio:'pipe'});
  execFileSync(java('jar'),['cf',`${helper}/native-snapshot.jar`,'-C',`${helper}/dex`,'classes.dex'],{stdio:'pipe'});
  adb('push',`${helper}/native-snapshot.jar`,'/data/local/tmp/hongguo-playback-native-snapshot.jar');
  record('REAL_ACCESSIBILITY_READER','Shell QA helper reads actual active window without waitForIdle; does not pause or inject media/UI state.');
}
function nativeState() {
  const xml = adb('shell','CLASSPATH=/data/local/tmp/hongguo-playback-native-snapshot.jar app_process /system/bin NativeSnapshot').toString();
  assert.ok(xml.includes('<hierarchy>'),'Actual accessibility snapshot required');
  const nodes = [...xml.matchAll(/<node\b([^>]*)>/g)].map(match => Object.fromEntries([...match[1].matchAll(/([\w-]+)="([^"]*)"/g)].map(attr => [attr[1], unescapeXml(attr[2])])));
  const texts = nodes.map(n => n.text).filter(Boolean);
  // AlertDialog is a separate active accessibility window; its real title carries the episode.
  const title = texts.find(text => / · 第 \d+ 集$/.test(text)) || texts.find(text => /^第 \d+ 集暂未播放$/.test(text));
  const clock = texts.find(text => /^\d+:\d+ \/ \d+:\d+$/.test(text));
  const seconds = time => time.split(':').reduce((n, v) => n * 60 + Number(v), 0);
  const times = clock?.split(' / ').map(seconds);
  return { xml, nodes, title, index: Number(title?.match(/第 (\d+) 集(?:$|暂未播放)/)?.[1]), clock, position: times?.[0], duration: times?.[1], texts };
}
function summary(state) { return { title: state.title, index: state.index, clock: state.clock, texts: state.texts, focused: state.nodes.find(n => n.focused === 'true')?.text }; }
function tapNode(node) { assert.ok(node, 'native control exists'); const bounds = node.bounds?.match(/\[(\d+),(\d+)\]\[(\d+),(\d+)\]/); assert.ok(bounds); adb('shell', 'input', 'tap', String(Math.round((+bounds[1] + +bounds[3]) / 2)), String(Math.round((+bounds[2] + +bounds[4]) / 2))); }
function manualSnapshot(pkg) {
  const directory = `/sdcard/Android/data/${pkg}/files/Download/`;
  let files = '';
  try { files = adb('shell', 'ls', '-ln', directory).toString(); } catch (error) { if (!/No such file/.test(String(error.stderr))) throw error; }
  const raw = adb('shell', 'run-as', pkg, 'cat', 'shared_prefs/hongguo_library_v1.xml').toString();
  const downloads = raw.match(/<string name="downloads">([\s\S]*?)<\/string>/)?.[1];
  const hashes = files.split('\n').map(line => line.trim().split(/\s+/).at(-1)).filter(name => /^[0-9_]+\.mp4$/.test(name)).map(name => ({ name, sha256: adb('shell', 'sha256sum', directory + name).toString().trim().split(/\s+/)[0] }));
  return { files, hashes, records: downloads ? JSON.parse(unescapeXml(downloads)) : [] };
}
function cacheFiles(pkg) {
  try { return adb('shell', 'run-as', pkg, 'ls', '-ln', 'cache/playback-v2').toString().split('\n').map(line => { const name = line.trim().split(/\s+/).at(-1); return { name, index: Number(name?.match(/_([0-9]+)_[a-f0-9]+\.mp4/)?.[1]), bytes: Number(line.trim().split(/\s+/)[4]) }; }).filter(file => file.name?.endsWith('.mp4')); }
  catch (error) { if (/No such file/.test(String(error.stderr))) return []; throw error; }
}
async function attach(pkg) {
  const pid = adb('shell', 'pidof', pkg).toString().trim().split(' ')[0]; assert.ok(pid);
  adb('forward', `tcp:${cdpPort}`, `localabstract:webview_devtools_remote_${pid}`);
  const target = await until(async () => { try { const targets = await (await fetch(`http://127.0.0.1:${cdpPort}/json/list`)).json(); return targets.find(t => t.url === 'https://appassets.androidplatform.net/index.html'); } catch { return null; } }, 30000, 'WebView CDP');
  const socket = new WebSocket(target.webSocketDebuggerUrl); await new Promise((resolve, reject) => { socket.addEventListener('open', resolve, { once: true }); socket.addEventListener('error', reject, { once: true }); });
  let next = 0; const pending = new Map();
  socket.addEventListener('message', ({ data }) => { const value = JSON.parse(data); const callback = pending.get(value.id); if (callback) { pending.delete(value.id); callback(value); } });
  const call = (method, params = {}) => new Promise((resolve, reject) => { const id = ++next; const timer = setTimeout(() => { pending.delete(id); reject(Error(`CDP timeout ${method}`)); }, 70000); pending.set(id, value => { clearTimeout(timer); value.error ? reject(Error(value.error.message)) : resolve(value.result); }); socket.send(JSON.stringify({ id, method, params })); });
  const page = { async evaluate(expression) { const result = await call('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true }); if (result.exceptionDetails) throw Error(result.exceptionDetails.exception?.description || result.exceptionDetails.text); return result.result.value; }, close() { socket.close(); } };
  await until(() => page.evaluate(`typeof window.__androidResponse==='function' && typeof AndroidBridge!=='undefined'`), 30000, 'Native bridge');
  await page.evaluate(`(()=>{const original=window.__androidResponse; const pending=new Map(); let sequence=0; window.__androidResponse=message=>{const p=pending.get(message?.id);if(p){pending.delete(message.id);clearTimeout(p.timer);message.error?p.reject(Error(message.error)):p.resolve(message.result);}original(message);}; window.__playbackDeviceRpc=(method,params={})=>new Promise((resolve,reject)=>{const id='real-device-qa-'+Date.now()+'-'+(++sequence);const timer=setTimeout(()=>{pending.delete(id);reject(Error('Native RPC timeout '+method));},60000);pending.set(id,{resolve,reject,timer});AndroidBridge.postMessage(JSON.stringify({id,method,params}));});return true;})()`);
  return { ...page, rpc: (method, params = {}) => page.evaluate(`window.__playbackDeviceRpc(${JSON.stringify(method)},${JSON.stringify(params)})`) };
}
async function sourceFor(page) { return createSource(async url => { const response = await page.rpc('httpGet', { url }); if (response.status < 200 || response.status >= 300) throw Error(`HTTP ${response.status}`); return response.body; }); }
async function playReal(page, source, detail, index, options = {}) {
  const episode = detail.episodes.find(e => e.index === index); assert.ok(episode);
  const video = await source.playback(detail.seriesId, episode.vid);
  const { episodes, ...series } = detail;
  await page.rpc('play', { series, episode, episodes, url: video.url, autoplay: true, cleanupPlaybackCache: true, keepPreviousEpisodes: 1, ...options });
  await until(isPlayer, 15000, 'native player starts');
  const state = await until(() => { const state = nativeState(); return state.index === index && state.duration > 0 ? state : null; }, 60000, `episode ${index} prepared`);
  return state;
}
async function finishByMediaEnd(index) {
  let state = nativeState(); assert.equal(state.index, index); assert.ok(state.duration > 0);
  record('NATURAL_END_SEEK_START', { index, clock: state.clock, method: 'KEYCODE_MEDIA_FAST_FORWARD +30s only; no MEDIA_NEXT / injected completion' });
  for (let i = 0; i < 30 && state.index === index && !state.texts.some(text => /本集已播完|本剧已播完/.test(text)); i++) {
    // A key-frame seek can land ~1 second before the requested end. Do not keep
    // seeking to that same key frame: let actual media time reach completion.
    if (state.duration - state.position <= 5) {
      record('NATURAL_MEDIA_END_WAIT', { index, clock: state.clock });
      state = await until(() => { const sample=nativeState(); return sample.index!==index||sample.texts.some(text=>/本集已播完|本剧已播完|暂未播放/.test(text))?sample:null; }, 30000, 'actual media completion after final seek');
      break;
    }
    adb('shell', 'input', 'keyevent', '90'); await pause(1000);
    state = nativeState();
    record('MEDIA_FAST_FORWARD', { requestedEpisode: index, ...summary(state) });
    if (state.texts.some(text => /暂未播放/.test(text))) break;
  }
  if (state.index !== index && !state.duration && !state.texts.some(text => /暂未播放/.test(text))) state = await until(() => {const next=nativeState();return next.duration>0||next.texts.some(text=>/暂未播放/.test(text))?next:null;},60000,'next episode prepared or explicit error');
  return state;
}
async function oneBack() { adb('shell', 'input', 'keyevent', '4'); await until(isMain, 10000, 'one Back returns MainActivity'); }
let page;
try {
  prepareSnapshotHelper();
  for (const pkg of [phonePkg, tvPkg]) { const version = adb('shell', 'dumpsys', 'package', pkg).toString().match(/versionName=(\S+)/)?.[1]; assert.equal(version, '0.2.0'); }
  const installedApks = [phonePkg,tvPkg].map(pkg=>{const path=adb('shell','pm','path',pkg).toString().trim().replace(/^package:/,'');return {pkg,sha256:adb('shell','sha256sum',path).toString().trim().split(/\s+/)[0]};});
  record('INSTALLED_APKS', installedApks);
  record('DEVICE_CONTEXT', { serial, adbPort: port, device: adb('shell', 'getprop', 'ro.product.model').toString().trim(), android: adb('shell', 'getprop', 'ro.build.version.release').toString().trim(), televisionOs: false, realMedia: true });
  adb('shell', 'am', 'force-stop', phonePkg); adb('shell', 'am', 'start', '-W', '-n', `${phonePkg}/.MainActivity`);
  page = await attach(phonePkg); const source = await sourceFor(page); const detail = await source.detail(sid);
  assert.equal(detail.episodes.length, 69); record('REAL_SOURCE_DETAIL', { title: detail.title, seriesId: sid, episodes: detail.episodes.length, directoryHint: detail.accessibleEpisodeCount });
  const beforeManual = manualSnapshot(phonePkg); assert.ok(beforeManual.records.length > 0, 'Existing manual downloads required for preservation acceptance');
  record('MANUAL_DOWNLOADS_BEFORE', beforeManual);
  await page.rpc('clearPlaybackCache');
  await until(() => page.evaluate(`document.querySelectorAll('.poster').length>0`), 45000, 'real home');
  let match = await page.evaluate(`(()=>{const b=[...document.querySelectorAll('.poster')].find(b=>b.getAttribute('aria-label')===${JSON.stringify('打开' + detail.title)});if(b){b.click();return true;}return false;})()`);
  if (!match) {
    await page.evaluate(`[...document.querySelectorAll('.nav-items button')].find(b=>b.textContent==='搜索').click()`);
    await until(() => page.evaluate(`!!document.querySelector('#search-keyword')`));
    await page.evaluate(`(()=>{const i=document.querySelector('#search-keyword');Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set.call(i,${JSON.stringify(detail.title)});i.dispatchEvent(new Event('input',{bubbles:true}));return new Promise(resolve=>setTimeout(()=>{document.querySelector('.search-form').requestSubmit();resolve(true)},0));})()`);
    await until(() => page.evaluate(`document.querySelectorAll('.poster').length>0`), 45000, 'real search result');
    match = await page.evaluate(`(()=>{const b=[...document.querySelectorAll('.poster')].find(b=>b.getAttribute('aria-label')===${JSON.stringify('打开' + detail.title)});if(b){b.click();return true;}return false;})()`); assert.ok(match);
  }
  await until(() => page.evaluate(`document.querySelectorAll('.episode-grid button').length===69`), 45000, '69 episode UI');
  const all = await page.evaluate(`({all:document.querySelectorAll('.episode-grid button').length,enabled:document.querySelectorAll('.episode-grid button:not(:disabled)').length,pending:document.querySelectorAll('.episode-status').length,note:document.querySelector('.episode-note').textContent})`);
  assert.equal(all.enabled, 69); record('PHONE_REAL_ALL_EPISODES_SELECTABLE', all); screenshot('phone-native-real-all-episodes');
  await page.evaluate(`document.querySelectorAll('.episode-grid button')[3].click()`);
  await until(() => page.evaluate(`document.querySelectorAll('.episode-grid button')[3].getAttribute('aria-pressed')==='true'`));
  await page.evaluate(`document.querySelector('.play-actions .primary').click()`);
  await until(() => page.evaluate(`!!document.querySelector('.operation-error')`), 45000, 'real fourth episode rejection');
  const error = await page.evaluate(`document.querySelector('.operation-error').textContent`); assert.match(error, /官网当前未提供本集片源.*HTTP 404/); assert.equal(isPlayer(), false); record('PHONE_REAL_EPISODE4_HTTP404', error); await page.evaluate(`document.querySelector('.operation-error').scrollIntoView({block:'center'})`); screenshot('phone-native-real-episode4-404');
  await page.evaluate(`document.querySelectorAll('.episode-grid button')[0].click()`); await until(() => page.evaluate(`document.querySelectorAll('.episode-grid button')[0].getAttribute('aria-pressed')==='true'`)); await page.evaluate(`document.querySelector('.play-actions .primary').click()`); await until(isPlayer, 15000, 'UI playback opens');
  let state = await until(() => { const state = nativeState(); return state.index === 1 && state.duration > 0 ? state : null; }, 60000, 'real episode one');
  record('PHONE_REAL_EPISODE1', summary(state)); screenshot('phone-native-first-controls', state.xml);
  const back = state.nodes.find(n => n.class === 'android.widget.Button' && n.text?.includes('返回')); assert.ok(back); tapNode(back); await until(isMain, 10000, 'top return'); record('PHONE_TOP_RETURN_ONE_TAP', true);
  await playReal(page, source, detail, 1, { autoplay: false }); await oneBack(); record('PHONE_SYSTEM_BACK_ONE_KEY', true);
  state = await playReal(page, source, detail, 1, { autoplay: false }); assert.ok(state.texts.includes('连播 关')); screenshot('phone-native-autoplay-off', state.xml);
  state = await finishByMediaEnd(1);
  assert.equal(state.index, 1); assert.ok(state.texts.some(text => /本集已播完/.test(text)), 'autoplay false must remain at completed current episode'); record('PHONE_AUTOPLAY_FALSE_COMPLETION', summary(state)); screenshot('phone-native-autoplay-off-completed', state.xml); await oneBack();
  await page.rpc('clearPlaybackCache');
  state = await playReal(page, source, detail, 2); assert.ok(state.texts.includes('在线播放 · 滚动缓存')); record('PHONE_REAL_EPISODE2_STREAM', summary(state)); screenshot('phone-native-stream-episode2', state.xml);
  await until(() => { const rows = cacheFiles(phonePkg); return rows.some(f => f.index === 2) && rows.some(f => f.index === 3) ? rows : null; }, 120000, 'real MP4 current cache and next prefetch');
  record('PHONE_REAL_CACHE_CURRENT_AND_PREFETCH', cacheFiles(phonePkg));
  state = await finishByMediaEnd(2); assert.equal(state.index, 3); assert.ok(state.duration > 0); record('PHONE_AUTOPLAY_COMPLETION_2_TO_3', summary(state)); screenshot('phone-native-autoplay-episode3', state.xml);
  const withPrevious = cacheFiles(phonePkg); assert.ok(withPrevious.some(f => f.index === 2) && withPrevious.some(f => f.index === 3)); record('PHONE_CACHE_PREVIOUS_CURRENT', withPrevious);
  state = await finishByMediaEnd(3); assert.equal(state.index, 4); assert.ok(state.texts.some(text => /官网.*未提供|未向官网提供公开播放页面|HTTP 404/.test(text)), 'real next unavailable error'); record('PHONE_AUTOPLAY_NEXT4_REAL_ERROR', summary(state)); screenshot('phone-native-next4-real-error', state.xml);
  const pruned = await until(() => { const rows = cacheFiles(phonePkg); return !rows.some(f => f.index === 2) && rows.some(f => f.index === 3) ? rows : null; }, 30000, 'old episode two pruned'); record('PHONE_CACHE_OLD_EPISODE_REMOVED', pruned);
  await oneBack(); record('PHONE_ERROR_BACK_ONE_KEY', true); await pause(600);
  const clear = await page.rpc('clearPlaybackCache'); const remaining = await page.rpc('getPlaybackCache'); assert.equal(remaining.files, 0);
  const afterManual = manualSnapshot(phonePkg); assert.deepEqual(afterManual, beforeManual); record('PHONE_CLEAR_CACHE_MANUAL_DOWNLOADS_PRESERVED', { clear, remaining, manualFilesAndRecordsEqual: true, manualRecords: afterManual.records.length });
  await page.evaluate(`window.__androidBack?.();[...document.querySelectorAll('.nav-items button')].find(b=>b.textContent==='关于').click()`); await until(() => page.evaluate(`document.querySelector('.cache-status')?.textContent.includes('0 个文件')`)); screenshot('phone-native-cache-cleared-about');
  page.close(); page = null;
  adb('shell', 'am', 'force-stop', tvPkg); adb('shell', 'am', 'start', '-W', '-n', `${tvPkg}/io.github.jackotom.hongguo.android.MainActivity`);
  page = await attach(tvPkg); const tvSource = await sourceFor(page); await until(() => page.evaluate(`document.documentElement.dataset.tv==='true' && document.querySelectorAll('.poster').length>0`), 45000, 'TV real catalog');
  const tv = await page.evaluate(`({width:innerWidth,height:innerHeight,isTv:document.documentElement.dataset.tv})`); assert.ok(tv.width > tv.height); record('TV_FLAVOR_LANDSCAPE', tv);
  await page.evaluate(`document.querySelector('.nav-items button').focus()`); adb('shell', 'input', 'keyevent', '20'); adb('shell', 'input', 'keyevent', '23'); await until(() => page.evaluate(`document.querySelector('h1')?.textContent==='搜索短剧'`)); record('TV_WEB_DPAD_SEARCH', true); screenshot('tv-native-dpad-search');
  state = await playReal(page, tvSource, detail, 1, { autoplay: false }); record('TV_REAL_PLAYBACK', summary(state)); screenshot('tv-native-playback-controls', state.xml);
  adb('shell', 'input', 'keyevent', '20'); adb('shell', 'input', 'keyevent', '23'); await pause(500); state = nativeState(); assert.ok(state.texts.includes('播放')); record('TV_NATIVE_DPAD_PAUSE', summary(state));
  adb('shell', 'input', 'keyevent', '23'); await pause(500); state = nativeState(); assert.ok(state.texts.includes('暂停')); record('TV_NATIVE_DPAD_RESUME', summary(state)); screenshot('tv-native-dpad-controls', state.xml);
  adb('shell','input','keyevent','23'); await pause(500);
  const beforeSeek=nativeState(); assert.ok(beforeSeek.texts.includes('播放'));
  adb('shell','input','keyevent','19'); const focusedSeek=nativeState(); assert.equal(focusedSeek.nodes.find(n=>n.focused==='true')?.class,'android.widget.SeekBar');
  for(let i=0;i<3;i++) adb('shell','input','keyevent','22'); await pause(1200);
  const afterSeek=nativeState(); assert.ok(afterSeek.position>=beforeSeek.position+10,'TV SeekBar D-pad must actually seek paused video, not only move its thumb');
  record('TV_SEEKBAR_DPAD_ACTUAL_SEEK',{before:beforeSeek.clock,after:afterSeek.clock,focused:afterSeek.nodes.find(n=>n.focused==='true')?.class,key:'DPAD_RIGHT x3 while paused; waited for native tick'}); screenshot('tv-native-seekbar-dpad',afterSeek.xml);
  await oneBack(); record('TV_SYSTEM_BACK_ONE_KEY', true); await page.rpc('clearPlaybackCache');
  record('TV_ACCEPTANCE_BOUNDARY', 'TV flavor tested on phone AVD Android 12L, not Android TV OS or a physical television/remote.');
  record('PLAYBACK_DEVICE_PASS', true);
} catch (error) {
  record('PLAYBACK_DEVICE_FAIL', { message: error.message, stack: error.stack });
  try { const state = nativeState(); screenshot('playback-device-failure', state.xml); record('FAILURE_NATIVE_STATE', summary(state)); } catch {}
  process.exitCode = 1;
} finally {
  page?.close(); try { adb('forward', '--remove', `tcp:${cdpPort}`); } catch {}
  try { adb('shell','rm','-f','/data/local/tmp/hongguo-playback-native-snapshot.jar'); } catch {}
}
