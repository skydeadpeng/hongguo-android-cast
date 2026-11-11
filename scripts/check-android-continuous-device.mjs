// Real natural-completion regression. Does not use MEDIA_NEXT, install APKs or change production source.
// Uses actual website/native bridge/media only; never fakes playback or deletes manual downloads.
import { execFileSync, spawn } from 'node:child_process';
import {createHash} from 'node:crypto';
import { appendFileSync, chmodSync, existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import assert from 'node:assert/strict';
import { createSource } from '../src/android/source.mjs';
const sdk = process.env.ANDROID_HOME || `${process.env.HOME}/Library/Android/sdk`;
const serial = process.env.HONGGUO_DEVICE || 'emulator-5582';
const port = process.env.ADB_PORT || '5038';
const cdpPort = process.env.HONGGUO_DEVICE_CDP_PORT || '9227';
const output = resolve(process.env.HONGGUO_DEVICE_OUTPUT || 'verification/android-continuous/baseline-720');
const selectedQuality=process.env.HONGGUO_QUALITY||'720p';
const expectedVersion=process.env.HONGGUO_EXPECTED_VERSION||'0.4.0';
const continuing=process.argv.includes('--continue-player');
const expectedHash=process.env.HONGGUO_EXPECTED_APK||'6e25220c104b98a8b4587fc525336ad66f0eee4c81dad8f7cc02b5208996c948';
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
function record(name, result) { const event = { time: new Date().toISOString(), name, result }; events.push(event); writeFileSync(`${output}/continuous-results.json`, JSON.stringify(events, null, 2)); console.log(name, JSON.stringify(result)); }
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

const redact=line=>line.replace(/https?:\/\/[^\s"'<>]+/gi,'[URL_REDACTED]').replace(/(?:contentKey|key_seed|authorization|x-gorgon|x-argus)\s*[:=][^,;\s]+/gi,'[SENSITIVE_REDACTED]');
let logcat;
function startLogcat(){
 const pid=adb('shell','pidof',phonePkg).toString().trim().split(' ')[0],file=`${output}/native-logcat-sanitized.log`;writeFileSync(file,'');let pending='';
 logcat=spawn(`${sdk}/platform-tools/adb`,['-P',port,'-s',serial,'logcat','-T','1','-v','threadtime'],{stdio:['ignore','pipe','pipe']});
 logcat.stdout.on('data',bytes=>{pending+=bytes.toString();const rows=pending.split('\n');pending=rows.pop();for(const line of rows){if(new RegExp('\\b'+pid+'\\b').test(line)||/MediaPlayer|NuPlayer|HTTP|PlaybackSources|PlaybackCache|Hongguo|SocketTimeout|SSL|InterruptedIOException|TimeoutException/i.test(line)){const safe=/(contentKey|key_seed|x-gorgon|x-argus|Authorization)/i.test(line)?'[SENSITIVE_LINE_REDACTED]':redact(line);appendFileSync(file,safe+'\n');}}});
 record('CONTINUOUS_LOGCAT',{pid,file,filter:'app pid or MediaPlayer/NuPlayer/HTTP/IO/cache/source tags; URL and sensitive-line redaction before disk; absence is not proof of prefetch success'});
}
async function observe(page){await page.evaluate(`(()=>{const stringify=JSON.stringify,response=window.__androidResponse,pending=new Map();window.__continuousObserved=[];JSON.stringify=function(value,...rest){if(value&&typeof value.id==='string'&&typeof value.method==='string'&&value.params){const m=value,p=m.params,i=p.playInfo||{};if(['play','appResolveResult'].includes(m.method))window.__continuousObserved.push({time:Date.now(),method:m.method,quality:p.quality||p.requestedQuality,speed:p.speed,index:p.episode?.index,vid:p.episode?.vid||p.vid,requestedQuality:i.requestedQuality,width:i.width,height:i.height,source:i.source,hasKey:!!i.contentKey,error:p.error,errorCode:p.errorCode||p.code});if(m.method==='appRequest')pending.set(m.id,{id:m.id,method:p.method,start:Date.now()});}return stringify.call(JSON,value,...rest);};window.__androidResponse=m=>{const p=pending.get(m.id);if(p){pending.delete(m.id);window.__continuousObserved.push({time:Date.now(),method:'appRequest',bridgeRequestId:p.id,verb:p.method,elapsedMs:Date.now()-p.start,status:m.result?.status,error:m.error,errorCode:m.errorCode||m.code||m.result?.errorCode});}response(m);};return true;})()`);}
async function oneBack(){adb('shell','input','keyevent','4');await until(isMain,10000,'single Back returns Main');}
const failed=state=>state.texts.some(t=>/暂未播放/.test(t));
let retryUsed=false;
async function prepareOrRetry(index,initial){
 let state=initial||await until(()=>{const s=nativeState();return failed(s)||(s.index===index&&s.position>0&&s.duration>0)?s:null;},180000,`episode${index} actual decode/error`);
 if(failed(state)){
  record('CONTINUOUS_FIRST_FAILURE',{expectedIndex:index,...summary(state)});screenshot(`failure-episode${index}-first`,state.xml);record('CONTINUOUS_RPC_AT_FAILURE',await page.evaluate(`window.__continuousObserved`));record('CONTINUOUS_CACHE_AT_FAILURE',cacheFiles(phonePkg));
  if(retryUsed)throw Error('Retry budget already used; '+state.texts.join(' / '));retryUsed=true;tapNode(state.nodes.find(x=>x.text==='重试'));record('CONTINUOUS_ONE_REAL_USER_RETRY',{index,attempts:1});await pause(600);
  state=await until(()=>{const s=nativeState();return failed(s)||(s.index===index&&s.position>0&&s.duration>0)?s:null;},180000,`episode${index} single real retry`);
  record('CONTINUOUS_RETRY_RESULT',summary(state));screenshot(`episode${index}-retry-result`,state.xml);if(failed(state))throw Error('Single actual user retry failed: '+state.texts.join(' / '));
 }
 assert.equal(state.index,index);assert.ok(state.duration>0);return state;
}
async function finishNatural(index){
 let state=nativeState();assert.equal(state.index,index);assert.ok(state.duration>0);record('CONTINUOUS_NATURAL_END_START',{index,clock:state.clock,input:'Only KEYCODE_MEDIA_FAST_FORWARD90 (+30s), no MEDIA_NEXT/button or fabricated completion'});
 for(let n=0;n<20;n++){
  if(failed(state)||state.index!==index)return state;
  if(state.duration-state.position<=5){record('CONTINUOUS_REAL_MEDIA_TAIL_WAIT',{index,clock:state.clock});return until(()=>{const s=nativeState();return failed(s)||s.index!==index?s:null;},30000,'real onCompletion to next episode');}
  adb('shell','input','keyevent','90');await pause(700);state=nativeState();record('CONTINUOUS_SEEK_NEAR_TAIL',{index,clock:state.clock,nextIndex:state.index});
 }
 throw Error('Media completion did not occur after bounded seek inputs');
}
let page,before;
try{
 prepareSnapshotHelper();const version=adb('shell','dumpsys','package',phonePkg).toString().match(/versionName=(\S+)/)?.[1],apk=adb('shell','pm','path',phonePkg).toString().trim().replace(/^package:/,'');const hash=adb('shell','sha256sum',apk).toString().trim().split(/\s+/)[0];record('CONTINUOUS_INSTALLED_APK',{version,sha256:hash,selectedQuality,device:serial,adbPort:port});assert.equal(version,expectedVersion);assert.equal(hash,expectedHash);
 before=manualSnapshot(phonePkg);record('CONTINUOUS_MANUAL_BASELINE',{records:before.records.length,hashes:before.hashes});if(!continuing){adb('shell','am','force-stop',phonePkg);adb('shell','am','start','-W','-n',`${phonePkg}/io.github.jackotom.hongguo.android.MainActivity`);}else assert.ok(isPlayer(),'continue requires actual active player');page=await attach(phonePkg);startLogcat();if(!continuing)await observe(page);
 let startIndex=3;if(!continuing){const options=await page.rpc('setPlaybackOptions',{quality:selectedQuality,speed:2});record('CONTINUOUS_NATIVE_OPTIONS',options);await until(()=>page.evaluate(`document.querySelectorAll('.poster').length>0`));await page.evaluate(`(()=>{const b=[...document.querySelectorAll('.poster')].find(x=>x.getAttribute('aria-label')==='打开京夜诱温');if(!b)throw Error('Exact homepage series absent');b.click();})()`);await until(()=>page.evaluate(`document.querySelectorAll('.episode-grid button').length===68`));
 await page.evaluate(`(()=>{const on=document.querySelector('[aria-label="自动连播"]');if(on.getAttribute('aria-checked')!=='true')on.click();document.querySelectorAll('.episode-grid button')[2].click();})()`);await until(()=>page.evaluate(`document.querySelectorAll('.episode-grid button')[2].getAttribute('aria-pressed')==='true'&&document.querySelector('[aria-label="自动连播"]').getAttribute('aria-checked')==='true'`));screenshot('continuous-home-selected3');await page.evaluate(`document.querySelector('.play-actions .primary').click()`);
 await until(async()=>{if(isPlayer())return true;const error=await page.evaluate(`document.querySelector('.operation-error')?.textContent`);if(error)throw Error('Initial UI source error: '+error);return false;},180000,'episode3 real initial player');}else{startIndex=await until(()=>{const x=nativeState();return Number.isInteger(x.index)&&x.index>=3&&x.index<=6?x.index:null;});record('CONTINUOUS_CONTINUE_CURRENT',{startIndex,initial:summary(nativeState())});}let state=await prepareOrRetry(startIndex);record('CONTINUOUS_EPISODE_PLAYING',summary(state));screenshot('episode3-playing',state.xml);
 for(let current=startIndex;current<6;current++){const next=current+1;state=await finishNatural(current);record('CONTINUOUS_OBSERVED_COMPLETION',{from:current,expectedNext:next,...summary(state)});state=await prepareOrRetry(next,state.position>0&&state.duration>0||failed(state)?state:null);record('CONTINUOUS_EPISODE_PLAYING',summary(state));record('CONTINUOUS_CACHE_WINDOW',{at:next,files:cacheFiles(phonePkg)});record('CONTINUOUS_RPC_SNAPSHOT',await page.evaluate(`window.__continuousObserved`));screenshot(`episode${next}-playing`,state.xml);}
 await oneBack();record(continuing?'CONTINUOUS_CONTINUATION_REACHED6':'CONTINUOUS_3_TO_6_PASS',{quality:selectedQuality,startedAt:startIndex,retryUsed});
}catch(error){record('CONTINUOUS_DEVICE_FAIL',{message:redact(error.message),stack:redact(error.stack||'')});try{const s=nativeState();record('CONTINUOUS_FINAL_NATIVE',summary(s));screenshot('continuous-final-failure',s.xml);}catch{}}
finally{
 if(page){try{record('CONTINUOUS_FINAL_RPC',await page.evaluate(`window.__continuousObserved`));}catch{}page.close();}
 if(before){try{const after=manualSnapshot(phonePkg);assert.deepEqual(after.records,before.records);assert.deepEqual(after.hashes,before.hashes);record('CONTINUOUS_MANUAL_UNCHANGED',{records:after.records.length,hashes:after.hashes});}catch(error){record('CONTINUOUS_MANUAL_CHECK_FAIL',{message:error.message});process.exitCode=1;}}
 logcat?.kill('SIGTERM');try{adb('forward','--remove',`tcp:${cdpPort}`);}catch{}try{adb('shell','rm','-f','/data/local/tmp/hongguo-playback-native-snapshot.jar');}catch{}
 if(events.some(x=>x.name==='CONTINUOUS_DEVICE_FAIL'))process.exitCode=1;
}
