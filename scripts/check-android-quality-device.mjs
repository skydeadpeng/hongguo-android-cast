// Real quality/rate device acceptance. Target v0.4 (or --baseline v0.3). No mock media, manual downloads, or root requirement in production.
// Uses actual website/native bridge/media only; never fakes playback or deletes manual downloads.
import { execFileSync } from 'node:child_process';
import {createHash} from 'node:crypto';
import {performance} from 'node:perf_hooks';
import { chmodSync, existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import assert from 'node:assert/strict';
import { createSource } from '../src/android/source.mjs';
const sdk = process.env.ANDROID_HOME || `${process.env.HOME}/Library/Android/sdk`;
const serial = process.env.HONGGUO_DEVICE || 'emulator-5582';
const port = process.env.ADB_PORT || '5038';
const cdpPort = process.env.HONGGUO_DEVICE_CDP_PORT || '9227';
const output = resolve(process.env.HONGGUO_DEVICE_OUTPUT || 'verification/android-quality/device-'+(process.argv.includes('--tv')?'tv':'phone'));
const tvMode=process.argv.includes('--tv');
const manualOnly=process.argv.includes('--manual-only');
const controlsOnly=process.argv.includes('--controls-only');
const coreOnly=process.argv.includes('--core-only');
const retryCurrent=process.argv.includes('--retry-current');
const phonePkg = 'io.github.jackotom.hongguo.android'+(tvMode?'.tv':'');
const tvPkg = `${phonePkg}.tv`;
const sid = '7693823855940357144';
const baseline = process.argv.includes('--baseline');
const continuing=process.argv.includes('--continue-player');
const returnOnly=process.argv.includes('--return-only');
const tvControls=process.argv.includes('--tv-controls');
const episodeIndex=Number(process.env.HONGGUO_EPISODE_INDEX||1);
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
function record(name, result) { const event = { time: new Date().toISOString(), name, result }; events.push(event); writeFileSync(`${output}/quality-device-results.json`, JSON.stringify(events, null, 2)); console.log(name, JSON.stringify(result)); }
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

async function oneBack(){adb('shell','input','keyevent','4');await until(isMain,10000,'one Back to MainActivity');}
function bounds(node){return node?.bounds?.match(/\[(\d+),(\d+)\]\[(\d+),(\d+)\]/)?.slice(1).map(Number);}
async function focusDpad(predicate){
 for(let n=0;n<15;n++){
  const state=nativeState(),target=state.nodes.find(predicate),focused=state.nodes.find(x=>x.focused==='true');assert.ok(target,'real target exists');if(focused&&predicate(focused))return state;
  const a=bounds(focused),b=bounds(target);let key=20;
  if(a&&b){const dx=(b[0]+b[2]-a[0]-a[2])/2,dy=(b[1]+b[3]-a[1]-a[3])/2;key=Math.abs(dy)>25?(dy>0?20:19):(dx>0?22:21);}
  adb('shell','input','keyevent',String(key));await pause(80);
 }
 throw Error('DPAD did not focus actual target');
}
async function activate(predicate){if(tvMode){const s=await focusDpad(predicate);record('QUALITY_DPAD_FOCUS',{focused:s.nodes.find(x=>x.focused==='true')?.text});adb('shell','input','keyevent','23');}else tapNode(nativeState().nodes.find(predicate));await pause(180);}
async function chooseQuality(value){await activate(n=>/^清晰度 /.test(n.text));await activate(n=>value==='auto'?n.text.startsWith('自动 ·'):n.text.toLowerCase().startsWith(value.toLowerCase()+' ·'));}
async function chooseSpeed(value){await activate(n=>/^倍速 /.test(n.text));if(tvMode){const menu=nativeState(),rows=menu.nodes.filter(n=>n.class==='android.widget.CheckedTextView').map(n=>n.text),index=rows.indexOf(value+'×');assert.ok(index>=0);record('QUALITY_TV_REAL_LIST_SELECTION',{rows,target:value+'×',keySequence:'MOVE_HOME then DPAD_DOWN x'+index+' then DPAD_CENTER'});adb('shell','input','keyevent','122');for(let n=0;n<index;n++)adb('shell','input','keyevent','20');adb('shell','input','keyevent','23');await pause(200);}else await activate(n=>n.text===value+'×');}
function dims(state){const match=state.texts.join(' / ').match(/实际 (\d+)×(\d+)/);return match?match.slice(1).map(Number):null;}
async function observeRpc(page){await page.evaluate(`(()=>{const stringify=JSON.stringify,response=window.__androidResponse,pending=new Map();window.__qualityObservations=[];JSON.stringify=function(value,...rest){if(value&&typeof value.id==='string'&&typeof value.method==='string'&&value.params){const m=value,p=m.params,i=p.playInfo||{};if(['play','download','appResolveResult','getPlaybackOptions','setPlaybackOptions'].includes(m.method))window.__qualityObservations.push({method:m.method,quality:p.quality||p.requestedQuality,speed:p.speed,index:p.episode?.index,requestedQuality:i.requestedQuality,width:i.width,height:i.height,source:i.source,hasKey:!!i.contentKey,error:p.error});if(m.method==='appRequest')pending.set(m.id,{method:p.method,started:Date.now()});}return stringify.call(JSON,value,...rest);};window.__androidResponse=m=>{const p=pending.get(m.id);if(p){pending.delete(m.id);window.__qualityObservations.push({method:'appRequest',verb:p.method,elapsedMs:Date.now()-p.started,status:m.result?.status,error:m.error});}response(m);};return true;})()`);}
async function actualPrepared(index,{retry=true}={}){
 let attempted=false;
 return until(async()=>{
  const state=nativeState();if(state.texts.some(t=>/暂未播放/.test(t))){
   record('QUALITY_ACTUAL_INITIAL_ERROR',summary(state));screenshot('quality-initial-error',state.xml);
   if(retry&&!attempted&&state.texts.some(t=>/HTTP\s*403/.test(t))){attempted=true;record('QUALITY_SINGLE_USER_RETRY',{attempts:1});await activate(n=>n.text==='重试');return null;}
   throw Error(state.texts.join(' / '));
  }
  return state.index===index&&state.position>0&&dims(state)?state:null;
 },180000,'actual decoded video and progress');
}
async function setUiOption(page,label,value){
 const current=await page.evaluate(`document.querySelector('select[aria-label="${label}"]').value`);if(current===String(value))return;
 const rect=await page.evaluate(`(()=>{const x=document.querySelector('select[aria-label="${label}"]');x.scrollIntoView({block:'center'});const r=x.getBoundingClientRect();return {x:r.x+r.width/2,y:r.y+r.height/2,width:innerWidth};})()`);await pause(150);
 const web=bounds(nativeState().nodes.find(x=>x.class==='android.webkit.WebView'));assert.ok(web);const scale=(web[2]-web[0])/rect.width;adb('shell','input','tap',String(Math.round(web[0]+rect.x*scale)),String(Math.round(web[1]+rect.y*scale)));await pause(200);
 const text=label==='清晰度'?(value==='auto'?'自动':value+' 及以上'):value+'×';const popup=nativeState();record('QUALITY_ACTUAL_SELECT_POPUP',{label,texts:popup.texts});tapNode(popup.nodes.find(x=>x.text===text));
 await until(()=>page.evaluate(`document.querySelector('select[aria-label="${label}"]').value===${JSON.stringify(String(value))}&&!document.querySelector('select[aria-label="${label}"]').disabled`));
 record('QUALITY_UI_OPTION_STORED',await page.rpc('getPlaybackOptions'));
}
let page;
try{
 prepareSnapshotHelper();const version=adb('shell','dumpsys','package',phonePkg).toString().match(/versionName=(\S+)/)?.[1],apk=adb('shell','pm','path',phonePkg).toString().trim().replace(/^package:/,'');record('QUALITY_INSTALLED_APK',{package:phonePkg,version,sha256:adb('shell','sha256sum',apk).toString().trim().split(/\s+/)[0],tvFlavorOnPhoneAvd:tvMode});assert.equal(version,baseline?'0.3.0':'0.4.0');
 const before=manualSnapshot(phonePkg);if(returnOnly){assert.ok(isPlayer());page=await attach(phonePkg);const options=await page.rpc('getPlaybackOptions');record('QUALITY_RETURN_OPTIONS',options);await oneBack();await until(()=>page.evaluate(`document.querySelector('select[aria-label="清晰度"]')?.value===${JSON.stringify(options.quality)}&&document.querySelector('select[aria-label="播放倍速"]')?.value===${JSON.stringify(String(options.speed))}`));record('QUALITY_DETAIL_ONRESUME_MATCH',options);screenshot('quality-return-options');assertExistingManualFiles(before,manualSnapshot(phonePkg));record('QUALITY_RETURN_MANUAL_PRESERVED',true);record('QUALITY_RETURN_ONLY_PASS',true);}else{if(!continuing){adb('shell','am','force-stop',phonePkg);adb('shell','am','start','-W','-n',`${phonePkg}/io.github.jackotom.hongguo.android.MainActivity`);page=await attach(phonePkg);record('QUALITY_INITIAL_NATIVE_OPTIONS',await page.rpc('getPlaybackOptions'));await observeRpc(page);
 await until(()=>page.evaluate(`document.querySelectorAll('.poster').length>0`));await page.evaluate(`(()=>{const b=[...document.querySelectorAll('.poster')].find(x=>x.getAttribute('aria-label')==='打开京夜诱温');if(!b)throw Error('Exact homepage title absent');b.click();})()`);await until(()=>page.evaluate(`document.querySelectorAll('.episode-grid button').length===68`));
 }else{assert.ok(isPlayer(),'continue requires actual current player');const popup=nativeState();if(popup.texts.some(t=>t==='播放倍速 · 设备实际确认')){adb('shell','input','keyevent','4');record('QUALITY_DISMISSED_EXISTING_QA_SPEED_POPUP',true);}page=await attach(phonePkg);record('QUALITY_CONTINUED_EXISTING_PLAYER',summary(nativeState()));}
 if(baseline){assert.equal(await page.evaluate(`document.querySelectorAll('select[aria-label="清晰度"],select[aria-label="播放倍速"]').length`),0);screenshot('quality-baseline-no-controls');record('QUALITY_BASELINE_NO_CONTROLS',true);}
 else{
  if(!continuing){await setUiOption(page,'清晰度',tvControls?'auto':'1080p');await setUiOption(page,'播放倍速',1);await page.evaluate(`document.querySelectorAll('.episode-grid button')[${episodeIndex-1}].click()`);screenshot('quality-ui-1080-selection');await page.evaluate(`document.querySelector('.play-actions .primary').click()`);
  await until(async()=>{if(isPlayer())return true;const error=await page.evaluate(`document.querySelector('.operation-error')?.textContent`);if(error)throw Error('Actual UI source: '+error);return false;},180000,'quality actual player');}
  let state=await actualPrepared(continuing?(await until(()=>{const x=nativeState();return Number.isInteger(x.index)&&x.index>0?x.index:null;})):episodeIndex);const actual=dims(state);if(!tvControls){assert.ok(Math.min(...actual)>=1080);assert.ok(state.texts.some(t=>/^清晰度 1080p$/i.test(t)));}record(tvControls?'QUALITY_TV_ACTUAL_DECODED':'QUALITY_1080_ACTUAL_DECODED',summary(state));const observations=await page.evaluate(`window.__qualityObservations`);if(observations){assert.ok(observations.some(x=>x.method==='play'&&x.quality==='1080p'&&x.width>=1080)||tvControls);assert.ok(observations.some(x=>x.method==='appRequest'&&x.status===200)||tvControls);}record('QUALITY_TRUE_API_AND_RPC',observations);screenshot('quality-1080-actual',state.xml);
  await chooseSpeed(2);state=await until(()=>{const s=nativeState();return s.texts.includes('倍速 2×')?s:null;});record('QUALITY_RATE_DEVICE_ACCEPTED',summary(state));
  const start=performance.now(),beforeTick=nativeState();await pause(5000);const afterTick=nativeState(),elapsed=(performance.now()-start)/1000,advance=afterTick.position-beforeTick.position;assert.ok(advance/elapsed>=1.4&&advance/elapsed<=2.6,'2x actual media tick ratio');record('QUALITY_RATE_ACTUAL_TICKS',{before:beforeTick.clock,after:afterTick.clock,elapsedSeconds:elapsed,mediaAdvanceSeconds:advance,ratio:advance/elapsed});screenshot('quality-2x-actual',afterTick.xml);
  if(tvControls){await activate(n=>/^清晰度 /.test(n.text));const menu=nativeState();assert.ok(menu.texts.some(t=>t.includes('先准备')));record('QUALITY_TV_DPAD_QUALITY_MENU',summary(menu));screenshot('quality-tv-dpad-menu',menu.xml);adb('shell','input','keyevent','4');await until(()=>{const s=nativeState();return s.index>0?s:null;});record('QUALITY_TV_DIALOG_BACK_CLOSED',true);}else{
  await activate(n=>n.text==='暂停');state=nativeState();const pausedAt=state.position;assert.ok(state.texts.includes('播放'));await chooseQuality('720p');
  let outcome=await until(()=>{const s=nativeState();return s.texts.some(t=>/^清晰度 /.test(t)&&!t.includes('准备中'))&&!s.texts.some(t=>t.includes('后台准备'))?s:null;},180000,'candidate switch success or preserved current');
  screenshot('quality-paused-switch-outcome',outcome.xml);assert.ok(outcome.texts.includes('播放'),'quality switch preserves paused transport');assert.ok(Math.abs(outcome.position-pausedAt)<=3,'quality switch preserves seek position');
  const switched=outcome.texts.some(t=>/^清晰度 720p$/i.test(t));if(switched){assert.ok(Math.min(...dims(outcome))>=720);record('QUALITY_SWITCH_SUCCESS_PRESERVED_PAUSE',{before:pausedAt,...summary(outcome)});}else{assert.ok(outcome.texts.some(t=>/^清晰度 1080p$/i.test(t)));assert.deepEqual(dims(outcome),actual);record('QUALITY_SWITCH_REAL_FAILURE_OLD_PICTURE_PRESERVED',{before:pausedAt,...summary(outcome)});}
  await pause(2500);const still=nativeState();assert.ok(Math.abs(still.position-outcome.position)<=1);record('QUALITY_PAUSE_STILL',{before:outcome.clock,after:still.clock});assert.ok(still.texts.includes('倍速 2×'),'paused player retains confirmed2x after quality switch');
  await activate(n=>n.text==='播放');await until(()=>{const x=nativeState();return x.texts.includes('暂停')&&x.texts.includes('倍速 2×')?x:null;});const resumeStart=performance.now(),resumeBefore=nativeState();await pause(4500);const resumeAfter=nativeState(),resumeElapsed=(performance.now()-resumeStart)/1000,resumeAdvance=resumeAfter.position-resumeBefore.position;assert.ok(resumeAdvance/resumeElapsed>=1.4&&resumeAdvance/resumeElapsed<=2.6,'quality-switched player really resumes at2x');record('QUALITY_SWITCH_RESUMED_2X_TICKS',{before:resumeBefore.clock,after:resumeAfter.clock,elapsedSeconds:resumeElapsed,mediaAdvanceSeconds:resumeAdvance,ratio:resumeAdvance/resumeElapsed,texts:resumeAfter.texts});screenshot('quality-switched-resume-2x',resumeAfter.xml);
  }
  const options=await page.rpc('getPlaybackOptions');if(!tvControls)assert.equal(options.quality,nativeState().texts.some(t=>/^清晰度 720p$/i.test(t))?'720p':'1080p');assert.equal(options.speed,2);record('QUALITY_NATIVE_PREFERENCES',options);
  const names=cacheFiles(phonePkg);record('QUALITY_CACHE_FILES',names);
  await oneBack();await until(()=>page.evaluate(`document.querySelector('select[aria-label="清晰度"]')?.value===${JSON.stringify(options.quality)}&&document.querySelector('select[aria-label="播放倍速"]')?.value==='2'`));record('QUALITY_DETAIL_ONRESUME_MATCH',await page.evaluate(`({quality:document.querySelector('select[aria-label="清晰度"]').value,speed:document.querySelector('select[aria-label="播放倍速"]').value})`));screenshot('quality-ui-resume-prefs');
  assertExistingManualFiles(before,manualSnapshot(phonePkg));record('QUALITY_MANUAL_FILES_AND_RECORDS_UNCHANGED',true);record('QUALITY_DEVICE_CORE_PASS',true);
 }
 }
}catch(error){record('QUALITY_DEVICE_FAIL',{message:error.message,stack:error.stack});try{const state=nativeState();record('QUALITY_FAILURE_NATIVE',summary(state));screenshot('quality-device-failure',state.xml);}catch{}if(page){try{record('QUALITY_OBSERVED_RPC_AT_FAILURE',await page.evaluate(`window.__qualityObservations`));}catch{}}process.exitCode=1;}
finally{page?.close();try{adb('forward','--remove',`tcp:${cdpPort}`);}catch{}try{adb('shell','rm','-f','/data/local/tmp/hongguo-playback-native-snapshot.jar');}catch{}}
