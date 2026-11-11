// Real-device playback regression. Requires the target v0.3 debug APKs installed (v0.2 for --baseline).
// Uses actual website/native bridge/media only; never fakes playback or deletes manual downloads.
import { execFileSync } from 'node:child_process';
import {createHash} from 'node:crypto';
import { chmodSync, existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import assert from 'node:assert/strict';
import { createSource } from '../src/android/source.mjs';
const sdk = process.env.ANDROID_HOME || `${process.env.HOME}/Library/Android/sdk`;
const serial = process.env.HONGGUO_DEVICE || 'emulator-5582';
const port = process.env.ADB_PORT || '5038';
const cdpPort = process.env.HONGGUO_DEVICE_CDP_PORT || '9227';
const output = resolve(process.env.HONGGUO_DEVICE_OUTPUT || 'verification/android-mac-parity');
const tvMode=process.argv.includes('--tv');
const manualOnly=process.argv.includes('--manual-only');
const controlsOnly=process.argv.includes('--controls-only');
const coreOnly=process.argv.includes('--core-only');
const retryCurrent=process.argv.includes('--retry-current');
const phonePkg = 'io.github.jackotom.hongguo.android'+(tvMode?'.tv':'');
const tvPkg = `${phonePkg}.tv`;
const sid = '7693823855940357144';
const baseline = process.argv.includes('--baseline');
const appOnly = process.argv.includes('--app-only');
const skipDownload = process.argv.includes('--no-download');
const forcedIndex = process.argv.includes('--app-10') ? 10 : 4;
const forcedVid = forcedIndex === 10 ? '7693825815523363902' : '7693825686737259582';
const suffix = process.argv.includes('--suffix') || appOnly;
const traceOnly = process.argv.includes('--trace-app');
const diagnose = process.argv.includes('--diagnose-app') || traceOnly;
mkdirSync(output, { recursive: true });
const events = [];
const adb = (...args) => execFileSync(`${sdk}/platform-tools/adb`, ['-P', port, '-s', serial, ...args], { timeout: 25000, maxBuffer: 12 * 1024 * 1024 });
const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
function record(name, result) { const event = { time: new Date().toISOString(), name, result }; events.push(event); writeFileSync(`${output}/jingye-device-${baseline?'baseline':suffix?'suffix':'results'}.json`, JSON.stringify(events, null, 2)); console.log(name, JSON.stringify(result)); }
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
  const hashes = files.split('\n').map(line => line.trim().split(/\s+/).at(-1)).filter(name => /^[0-9a-f_-]+\.mp4$/.test(name)).map(name => ({ name, sha256: (()=>{ try { return adb('shell', 'sha256sum', directory + name).toString().trim().split(/\s+/)[0]; } catch { try { return adb('shell', 'run-as', pkg, 'sha256sum', directory + name).toString().trim().split(/\s+/)[0]; } catch { /* userdebug AVD FUSE can reject run-as; read actual backing file, without permission changes. */ return adb('shell','su','0','sha256sum',directory.replace('/sdcard/','/data/media/0/')+name).toString().trim().split(/\s+/)[0]; } } })() }));
  return { files, hashes, records: downloads ? JSON.parse(unescapeXml(downloads)) : [] };
}
function assertExistingManualFiles(before,after) {
  assert.equal(after.records.length,before.records.length);
  for(const hash of before.hashes)assert.deepEqual(after.hashes.find(x=>x.name===hash.name),hash);
  for(const row of before.records){const next=after.records.find(x=>x.id===row.id);assert.ok(next);if(row.id===12){const {preparedFile,preparedBytes,...old}=row;const {preparedFile:newFile,preparedBytes:newBytes,...current}=next;assert.deepEqual(current,old);}else assert.deepEqual(next,row);}
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
  const call = (method, params = {}) => new Promise((resolve, reject) => { const id = ++next; const timer = setTimeout(() => { pending.delete(id); reject(Error(`CDP timeout ${method}`)); }, 200000); pending.set(id, value => { clearTimeout(timer); value.error ? reject(Error(value.error.message)) : resolve(value.result); }); socket.send(JSON.stringify({ id, method, params })); });
  const page = { async evaluate(expression) { const result = await call('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true }); if (result.exceptionDetails) throw Error(result.exceptionDetails.exception?.description || result.exceptionDetails.text); return result.result.value; }, close() { socket.close(); } };
  await until(() => page.evaluate(`typeof window.__androidResponse==='function' && typeof AndroidBridge!=='undefined'`), 30000, 'Native bridge');
  await page.evaluate(`(()=>{const original=window.__androidResponse; const pending=new Map(); let sequence=0; window.__androidResponse=message=>{const p=pending.get(message?.id);if(p){pending.delete(message.id);clearTimeout(p.timer);message.error?p.reject(Error(message.error)):p.resolve(message.result);}original(message);}; window.__playbackDeviceRpc=(method,params={})=>new Promise((resolve,reject)=>{const id='real-device-qa-'+Date.now()+'-'+(++sequence);const timer=setTimeout(()=>{pending.delete(id);reject(Error('Native RPC timeout '+method));},method==='download'?180000:60000);pending.set(id,{resolve,reject,timer});AndroidBridge.postMessage(JSON.stringify({id,method,params}));});return true;})()`);
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


async function checkTvControls(page) {
  const viewport=await page.evaluate(`({width:innerWidth,height:innerHeight,isTv:document.documentElement.dataset.tv})`);assert.ok(viewport.width>viewport.height);record('JINGYE_TV_LANDSCAPE',viewport);
  adb('shell','input','keyevent','20');adb('shell','input','keyevent','23');await pause(500);let state=nativeState();assert.ok(state.texts.includes('播放'));record('JINGYE_TV_DPAD_PAUSE',summary(state));
  adb('shell','input','keyevent','23');await pause(500);state=nativeState();assert.ok(state.texts.includes('暂停'));record('JINGYE_TV_DPAD_RESUME',summary(state));screenshot('jingye-tv-native-dpad',state.xml);
  adb('shell','input','keyevent','23');await pause(500);const before=nativeState();assert.ok(before.texts.includes('播放'));
  adb('shell','input','keyevent','19');assert.equal(nativeState().nodes.find(n=>n.focused==='true')?.class,'android.widget.SeekBar');
  for(let i=0;i<3;i++)adb('shell','input','keyevent','22');await pause(1200);state=nativeState();assert.ok(state.position>=before.position+10);
  record('JINGYE_TV_SEEKBAR_ACTUAL_SEEK',{before:before.clock,after:state.clock,key:'DPAD_RIGHT x3 while paused'});screenshot('jingye-tv-native-seekbar',state.xml);
  record('JINGYE_TV_ACCEPTANCE_BOUNDARY','TV flavor on API32 Android12 phone AVD; not Android TV OS or a physical television/remote.');
}

async function diagnoseApp(page) {
  await page.rpc('clearPlaybackCache');
  const generated=readFileSync('src/android/generated/mac-app-source.mjs','utf8').replace(/\nexport\s*\{[\s\S]*?\};\s*$/,'');
  const vid='7693825815523363902';
  const model=await page.evaluate(`(async()=>{${generated};const calls=[];const original=(method,params)=>window.__playbackDeviceRpc(method,params);registerAppResolver(async(method,params)=>{try{const response=await original(method,params);if(method==='appRequest')calls.push({request:params,response});return response;}catch(error){if(method==='appRequest')calls.push({request:params,error:error.message});throw error;}});try{const info=await fetchAppPlayUrl('${vid}');window.__jingyeQaPlayInfo=info;window.__jingyeQaTrace={calls,model:info};return {vid:info.vid,source:info.source,codec:info.codec,hasContentKey:!!info.contentKey,variants:info.variants?.length};}finally{registerAppResolver(original);}})()`);
  record('JINGYE_REAL_BROWSER_MODEL_DIAG',model);
  const privateDir=`${output}/private`;mkdirSync(privateDir,{recursive:true,mode:0o700});chmodSync(privateDir,0o700);
  if(traceOnly){const tracePath=`${privateDir}/browser-app-trace.json`;writeFileSync(tracePath,await page.evaluate(`JSON.stringify(window.__jingyeQaTrace)`),{mode:0o600});chmodSync(tracePath,0o600);record('JINGYE_PRIVATE_TRACE_SAVED',{path:tracePath,mode:'0600',contentsNotLogged:true,originalNetworkRestored:true});await page.evaluate(`delete window.__jingyeQaTrace`);return;}
  const privatePath=`${privateDir}/browser-app-model.json`;
  writeFileSync(privatePath,await page.evaluate(`JSON.stringify(window.__jingyeQaPlayInfo)`),{mode:0o600});chmodSync(privatePath,0o600);
  record('JINGYE_PRIVATE_MODEL_SAVED',{path:privatePath,mode:'0600',contentsNotLogged:true});
  const source=await sourceFor(page);const detail=await source.detail(sid);const {episodes,...series}=detail;const episode=episodes.find(e=>e.vid===vid);
  const outcomes=[];
  for(let index=0;index<(model.variants||1);index++){
    try{
      await page.evaluate(`(()=>{const full=window.__jingyeQaPlayInfo;const candidate=full.variants?.[${index}]||full;return window.__playbackDeviceRpc('play',{series:${JSON.stringify(series)},episode:${JSON.stringify(episode)},episodes:${JSON.stringify(episodes)},url:candidate.url,playInfo:{...full,...candidate},autoplay:false,cleanupPlaybackCache:true,keepPreviousEpisodes:1});})()`);
      await until(isPlayer,15000,'diagnostic real variant player');
      const state=await until(()=>{const x=nativeState();if(x.texts.some(t=>/暂未播放/.test(t)))throw Error(x.texts.join(' / '));return x.index===10&&x.position>0&&x.duration>0?x:null;},180000,'diagnostic real variant preparation');
      outcomes.push({index,ok:true,state:summary(state)});record('JINGYE_REAL_VARIANT',outcomes.at(-1));screenshot(`jingye-app-variant-${index}`,state.xml);await oneBack();break;
    }catch(error){outcomes.push({index,ok:false,error:error.message.split('\n')[0]});record('JINGYE_REAL_VARIANT',outcomes.at(-1));if(isPlayer())await oneBack();}
  }
  record('JINGYE_VARIANTS_DIAGNOSTIC_DONE',{attempted:outcomes.length,succeeded:outcomes.some(x=>x.ok)});
}
let page;
try {
  prepareSnapshotHelper();
  const version=adb('shell','dumpsys','package',phonePkg).toString().match(/versionName=(\S+)/)?.[1];
  const apkPath=adb('shell','pm','path',phonePkg).toString().trim().replace(/^package:/,'');
  record('JINGYE_INSTALLED_APK',{package:phonePkg,tvFlavorOnPhoneAvd:tvMode,mode:baseline?'baseline':suffix?'suffix':'modified',version,sha256:adb('shell','sha256sum',apkPath).toString().trim().split(/\s+/)[0]});
  if(baseline) assert.equal(version,'0.2.0'); else assert.equal(version,'0.3.0');
  if(retryCurrent){
    const initial=nativeState();assert.equal(initial.index,10);assert.ok(initial.texts.some(t=>t.includes('HTTP 403')));
    record('JINGYE_EXPLICIT_USER_RETRY',{reason:'HTTP403',attempts:1,initial:summary(initial)});
    tapNode(initial.nodes.find(n=>n.text==='重试'));await pause(600);
    const result=await until(()=>{const x=nativeState();if(x.texts.some(t=>/暂未播放/.test(t)))throw Error(x.texts.join(' / '));return x.index===10&&x.position>0&&x.duration>0?x:null;},180000,'single explicit native retry App10');
    record('JINGYE_EXPLICIT_USER_RETRY_PLAYING',summary(result));screenshot('jingye-app10-explicit-user-retry',result.xml);if(tvMode){page=await attach(phonePkg);await checkTvControls(page);}await oneBack();if(tvMode)record('JINGYE_TV_ONE_BACK',true);record('JINGYE_EXPLICIT_RETRY_PASS',true);
  }else{
  const before=manualSnapshot(phonePkg);
  adb('shell','am','force-stop',phonePkg);adb('shell','am','start','-W','-n',`${phonePkg}/io.github.jackotom.hongguo.android.MainActivity`);page=await attach(phonePkg);
  await until(()=>page.evaluate(`document.querySelectorAll('.poster').length>0`),45000,'real homepage');
  screenshot(baseline?'jingye-baseline-home':'jingye-modified-home');
  const title='京夜诱温';
  const opened=await page.evaluate(`(()=>{const b=[...document.querySelectorAll('.poster')].find(b=>b.getAttribute('aria-label')===${JSON.stringify('打开'+title)});if(b)b.click();return !!b;})()`);assert.ok(opened,'Exact user-homepage series must be present; do not substitute a search result');
  await until(()=>page.evaluate(`document.querySelectorAll('.episode-grid button').length===68`),45000,'exact 68-episode series');
  record('JINGYE_EXACT_DETAIL',await page.evaluate(`({title:document.querySelector('.detail-copy h1').textContent,all:document.querySelectorAll('.episode-grid button').length,selectable:document.querySelectorAll('.episode-grid button:not(:disabled)').length,note:document.querySelector('.episode-note').textContent})`));
  if(manualOnly){
    const cleared=await page.rpc('clearPlaybackCache');await pause(500);assert.equal((await page.rpc('getPlaybackCache')).files,0);record('JINGYE_MANUAL12_CACHE_ZERO',cleared);
    const row=(await page.rpc('getDownloads')).find(x=>x.id===12);assert.equal(row?.status,'success');assert.ok(row.downloadedBytes===7703531||row.totalBytes===7703531);record('JINGYE_MANUAL12_MIGRATED',row);
    await page.rpc('playDownload',{id:12,autoplay:false,cleanupPlaybackCache:true,keepPreviousEpisodes:1});await until(isPlayer,15000,'existing QA manual id12 native player');
    const state=await until(()=>{const x=nativeState();if(x.texts.some(t=>/暂未播放/.test(t)))throw Error(x.texts.join(' / '));return x.index===4&&x.position>0&&x.duration>0?x:null;},60000,'existing QA manual id12 actual media');
    assert.ok(state.texts.some(x=>x.includes('手动下载')));record('JINGYE_MANUAL12_LOCAL_PLAYING',summary(state));screenshot('jingye-manual12-cache-zero-local',state.xml);await oneBack();
    const after=manualSnapshot(phonePkg);for(const hash of before.hashes)assert.deepEqual(after.hashes.find(x=>x.name===hash.name),hash);for(const row of before.records.filter(x=>x.id!==12))assert.deepEqual(after.records.find(x=>x.id===row.id),row);assert.equal(after.records.length,before.records.length);record('JINGYE_MANUAL12_OLD3_PRESERVED',{records:after.records.length,oldHashesEqual:true,oldRecordsEqual:true});record('JINGYE_MANUAL12_PASS',true);
  }else if(controlsOnly){
    assert.ok(tvMode,'--controls-only is the TV public-web control check, not App-source validation');const source=await sourceFor(page);const detail=await source.detail(sid);await playReal(page,source,detail,1,{autoplay:false});
    const state=await until(()=>{const x=nativeState();if(x.texts.some(t=>/暂未播放/.test(t)))throw Error(x.texts.join(' / '));return x.index===1&&x.position>0&&x.duration>0?x:null;},60000,'TV actual public-web first episode');
    record('JINGYE_TV_PUBLIC_WEB_CONTROLS_PLAYBACK',summary(state));screenshot('jingye-tv-native-web1',state.xml);await checkTvControls(page);await oneBack();record('JINGYE_TV_ONE_BACK',true);assertExistingManualFiles(before,manualSnapshot(phonePkg));record('JINGYE_TV_PUBLIC_WEB_CONTROLS_PASS',true);
  }else if(diagnose){await diagnoseApp(page);}else{
  const select=async index=>{await page.evaluate(`document.querySelectorAll('.episode-grid button')[${index-1}].click()`);await until(()=>page.evaluate(`document.querySelectorAll('.episode-grid button')[${index-1}].getAttribute('aria-pressed')==='true'`));};
  const open=async index=>{await select(index);await page.evaluate(`document.querySelector('.play-actions .primary').click()`);};
  if(!suffix){await open(baseline?4:3);await until(async()=>isPlayer()||await page.evaluate(`!!document.querySelector('.operation-error')`),180000,'actual selected episode UI outcome');}
  if(baseline){
    const player=isPlayer();
    const error=await page.evaluate(`document.querySelector('.operation-error')?.textContent||''`);
    record('JINGYE_BASELINE_EPISODE4',{playerOpened:player,error});
    if(player){const state=nativeState();record('JINGYE_BASELINE_ACTUAL_PLAYER',summary(state));screenshot('jingye-baseline-player',state.xml);await oneBack();}
    else{await page.evaluate(`document.querySelector('.operation-error').scrollIntoView({block:'center'})`);screenshot('jingye-baseline-episode4-error');}
    record('JINGYE_BASELINE_REPRODUCED',!player&&/404/.test(error));
  }else{
    let state;
    if(!suffix){
    assert.equal(isPlayer(),true,'Actual native player must open, not merely resolve a model');
    state=await until(()=>{const x=nativeState();if(x.texts.some(t=>/暂未播放/.test(t)))throw Error(x.texts.join(' / '));return x.index===3&&x.position>0&&x.duration>0?x:null;},180000,'real episode three playback');
    record('JINGYE_MODIFIED_EPISODE3_PLAYING',summary(state));screenshot('jingye-phone-native-episode3',state.xml);
    state=await finishByMediaEnd(3);assert.equal(state.index,4);assert.ok(state.duration>0);record('JINGYE_COMPLETION_3_TO_4',summary(state));
    state=await until(()=>{const x=nativeState();if(x.texts.some(t=>/暂未播放/.test(t)))throw Error(x.texts.join(' / '));return x.index===4&&x.position>0&&x.duration>0?x:null;},180000,'real episode four frame/progress');
    record('JINGYE_MODIFIED_EPISODE4_PLAYING',summary(state));screenshot('jingye-phone-native-episode4',state.xml);
    state=await finishByMediaEnd(4);assert.equal(state.index,5);assert.ok(state.duration>0);record('JINGYE_COMPLETION_4_TO_5',summary(state));screenshot('jingye-phone-native-episode5',state.xml);
    const cachePrefix=createHash('sha256').update(sid).digest('hex')+'_';const cacheWindow=await until(()=>{const rows=cacheFiles(phonePkg).filter(f=>f.name.startsWith(cachePrefix));return rows.some(f=>f.index===5)&&rows.every(f=>f.index>=4&&f.index<=6)?rows:null;},30000,'rolling current5/previous4/next6 cache window');record('JINGYE_CACHE_WINDOW_AFTER_5',cacheWindow);
    await oneBack();
    }
    if(!appOnly){
    await open(10);await until(async()=>{if(isPlayer())return true;const error=await page.evaluate(`document.querySelector('.operation-error')?.textContent||''`);if(error)throw Error('Episode10 UI: '+error);return false;},180000,'actual episode ten opens');
    const selected10=await page.evaluate(`document.querySelector('.play-actions>p')?.textContent`);assert.match(selected10,/第 10 集/);record('JINGYE_EPISODE10_SELECTION_STABLE',selected10);
    state=await until(()=>{const x=nativeState();if(x.texts.some(t=>/暂未播放/.test(t)))throw Error(x.texts.join(' / '));return x.index===10&&x.position>0&&x.duration>0?x:null;},180000,'real episode ten frame/progress');
    record('JINGYE_MODIFIED_EPISODE10_PLAYING',summary(state));screenshot('jingye-phone-native-episode10',state.xml);if(coreOnly&&tvMode)await checkTvControls(page);await oneBack();if(coreOnly&&tvMode)record('JINGYE_TV_ONE_BACK',true);
    }
    assertExistingManualFiles(before,manualSnapshot(phonePkg));record('JINGYE_MANUAL_DOWNLOADS_UNCHANGED',true);
    if(coreOnly){record('JINGYE_CORE_DEVICE_PASS',true);}else{
    // Run the exact generated Mac module inside the real WebView, using actual
    // AndroidCrypto and appRequest; retain the private media model in memory only.
    await page.rpc('clearPlaybackCache');await pause(500);assert.equal((await page.rpc('getPlaybackCache')).files,0);
    const generated=readFileSync('src/android/generated/mac-app-source.mjs','utf8').replace(/\nexport\s*\{[\s\S]*?\};\s*$/,'');
    const model=await page.evaluate(`(async()=>{${generated};registerAppResolver((method,params)=>window.__playbackDeviceRpc(method,params));const info=await fetchAppPlayUrl('${forcedVid}');window.__jingyeQaPlayInfo=info;return {vid:info.vid,source:info.source,codec:info.codec,hasContentKey:!!info.contentKey};})()`);
    assert.equal(model.source,'app');assert.ok(model.hasContentKey);record('JINGYE_REAL_ANDROID_APP_MODEL',model);
    const source=await sourceFor(page);const detail=await source.detail(sid);const {episodes,...series}=detail;const episode=episodes.find(e=>e.index===forcedIndex);
    await page.evaluate(`window.__playbackDeviceRpc('play',{series:${JSON.stringify(series)},episode:${JSON.stringify(episode)},episodes:${JSON.stringify(episodes)},url:window.__jingyeQaPlayInfo.url,playInfo:window.__jingyeQaPlayInfo,autoplay:false,cleanupPlaybackCache:true,keepPreviousEpisodes:1})`);
    await until(isPlayer,180000,'real CENC App player opens');
    state=await until(()=>{const x=nativeState();if(x.texts.some(t=>/暂未播放/.test(t)))throw Error(x.texts.join(' / '));return x.index===forcedIndex&&x.position>0&&x.duration>0?x:null;},180000,'actual App CENC picture/progress');
    assert.ok(state.texts.some(t=>t.includes('App片源')));record('JINGYE_FORCED_APP_CENC_PLAYBACK',summary(state));screenshot(`jingye-phone-native-forced-app-${forcedIndex}-cenc`,state.xml);record('JINGYE_FORCED_APP_CACHE',cacheFiles(phonePkg));
    if(tvMode)await checkTvControls(page);
    await oneBack();if(tvMode)record('JINGYE_TV_ONE_BACK',true);
    if(!skipDownload){
    const downloaded=await page.evaluate(`window.__playbackDeviceRpc('download',{series:${JSON.stringify(series)},episode:${JSON.stringify(episode)},url:window.__jingyeQaPlayInfo.url,playInfo:window.__jingyeQaPlayInfo})`);
    const rows=await page.rpc('getDownloads');const added=rows.find(r=>r.id===downloaded.id);assert.equal(added?.status,'success');record('JINGYE_APP_MANUAL_DOWNLOAD',{id:downloaded.id,status:added.status,bytes:added.downloadedBytes,prepared:downloaded.prepared});
    await page.rpc('playDownload',{id:downloaded.id,autoplay:false,cleanupPlaybackCache:true,keepPreviousEpisodes:1});await until(isPlayer,15000,'prepared manual App file playback');
    state=await until(()=>{const x=nativeState();return x.index===forcedIndex&&x.position>0&&x.duration>0?x:null;},60000,'actual manual App video playback');record('JINGYE_APP_MANUAL_LOCAL_PLAYBACK',summary(state));screenshot('jingye-phone-native-app-manual-local',state.xml);await oneBack();
    const after=manualSnapshot(phonePkg);for(const hash of before.hashes)assert.deepEqual(after.hashes.find(x=>x.name===hash.name),hash);for(const row of before.records)assert.deepEqual(after.records.find(x=>x.id===row.id),row);
    assert.equal(after.records.length,before.records.length+1);record('JINGYE_OLD_MANUAL_DOWNLOADS_PRESERVED',{oldRecords:before.records.length,newQaRecords:1,oldHashesEqual:true});
    }else{assertExistingManualFiles(before,manualSnapshot(phonePkg));record('JINGYE_MANUAL_DOWNLOADS_UNCHANGED',true);}
    await page.evaluate(`delete window.__jingyeQaPlayInfo`);
    record(appOnly?'JINGYE_APP_ONLY_PASS':suffix?'JINGYE_SUFFIX_PASS':'JINGYE_DEVICE_PASS',true);
    }
  }
  }
  }
}catch(error){record('JINGYE_DEVICE_FAIL',{message:error.message,stack:error.stack});try{const state=nativeState();record('JINGYE_FAILURE_NATIVE_STATE',summary(state));screenshot('jingye-device-failure',state.xml);}catch{}process.exitCode=1;}
finally{try{if(page)await page.evaluate(`delete window.__jingyeQaPlayInfo`);}catch{}page?.close();try{adb('forward','--remove',`tcp:${cdpPort}`);}catch{}try{adb('shell','rm','-f','/data/local/tmp/hongguo-playback-native-snapshot.jar');}catch{}}
