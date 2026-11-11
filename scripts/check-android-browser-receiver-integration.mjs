#!/usr/bin/env node
// Real BrowserCastServer/Session/Prefetch/CachePolicy + receiver assets + Chrome video.
// Media is locally generated; no external video service or Apple hardware is exercised.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawn, spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
const own=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
let root=own, output=path.join(own,'verification/android-apple-webcast-0120-20261021/receiver-integration');
for(let i=2;i<process.argv.length;i++){
  if(process.argv[i]==='--root'&&process.argv[i+1])root=path.resolve(process.argv[++i]);
  else if(process.argv[i]==='--output'&&process.argv[i+1])output=path.resolve(process.argv[++i]);
  else throw Error('Usage: --root PROJECT --output DIRECTORY');
}
fs.mkdirSync(output,{recursive:true});
const tmp=fs.mkdtempSync(path.join(os.tmpdir(),'hongguo-browser-full-'));
const report={kind:'Production BrowserCastServer/Session/Prefetch/CachePolicy + real receiver and Chrome H.264/AAC',tests:[],events:[],commands:[],screenshots:[],externalRequests:[],pageErrors:[],appleHardware:'not_run',androidHardware:'not_run'};
let java,chrome,ws,checks=0,passed=0,failed=0;
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
const check=(value,message)=>{checks++;assert.ok(value,message);};
const eq=(actual,expected,message)=>{checks++;assert.deepEqual(actual,expected,message);};
async function group(name,fn){try{await fn();passed++;report.tests.push({name,result:'PASS'});console.log('PASS '+name);}catch(error){failed++;report.tests.push({name,result:'FAIL',error:error.message});console.log('FAIL '+name+': '+error.message);throw error;}}
try{
  const generated=spawnSync(process.env.FFMPEG_BIN||'/opt/homebrew/bin/ffmpeg',['-hide_banner','-loglevel','error','-f','lavfi','-i','testsrc2=size=320x568:rate=24','-f','lavfi','-i','sine=frequency=440:sample_rate=44100','-t','5.5','-c:v','libx264','-pix_fmt','yuv420p','-preset','ultrafast','-c:a','aac','-movflags','+faststart','-y',path.join(tmp,'episode.mp4')],{encoding:'utf8',timeout:30000});
  if(generated.status!==0)throw Error('Synthetic MP4 generation failed: '+generated.stderr);
  java=spawn('bash',[path.join(own,'android/tests/run-browser-cast-integration-tests.sh'),'--source-root',root,'--serve',tmp],{cwd:root,stdio:['pipe','pipe','pipe']});
  let javaOutput='',javaError='';java.stdout.on('data',data=>javaOutput+=data);java.stderr.on('data',data=>javaError+=data);
  let ready;for(let i=0;i<300;i++){const line=javaOutput.split('\n').find(line=>line.startsWith('{'));if(line){ready=JSON.parse(line);break;}if(java.exitCode!==null)throw Error('Java fixture exit '+java.exitCode+': '+javaError);await sleep(100);}if(!ready)throw Error('Java fixture startup timeout: '+javaError);
  const base=ready.url.endsWith('/')?ready.url:ready.url+'/';
  const profile=path.join(tmp,'chrome-profile');
  chrome=spawn(process.env.CHROME_BIN||'/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',['--headless=new','--remote-debugging-port=0',`--user-data-dir=${profile}`,'--no-first-run','--disable-background-networking','--disable-default-apps','--no-default-browser-check','--autoplay-policy=document-user-activation-required','about:blank'],{stdio:['ignore','ignore',fs.openSync(path.join(output,'chrome.log'),'w')]});
  let port;for(let i=0;i<100;i++){try{port=Number(fs.readFileSync(path.join(profile,'DevToolsActivePort'),'utf8').split('\n')[0]);if(port)break;}catch{}await sleep(100);}if(!port)throw Error('Chrome startup failed');
  const target=await fetch(`http://127.0.0.1:${port}/json/new?about:blank`,{method:'PUT'}).then(r=>r.json());ws=new WebSocket(target.webSocketDebuggerUrl);await new Promise((r,j)=>{ws.addEventListener('open',r,{once:true});ws.addEventListener('error',j,{once:true});});
  let sequence=0;const pending=new Map();
  ws.addEventListener('message',({data})=>{const m=JSON.parse(data);if(m.id){const p=pending.get(m.id);if(p){pending.delete(m.id);m.error?p.reject(Error(JSON.stringify(m.error))):p.resolve(m.result);}}if(m.method==='Runtime.exceptionThrown')report.pageErrors.push(m.params.exceptionDetails.exception?.description||m.params.exceptionDetails.text);if(m.method==='Network.requestWillBeSent'){
    const request=m.params.request;if(!request.url.startsWith(base)&&!request.url.startsWith('data:'))report.externalRequests.push(request.url);
    if(request.postData&&(request.url===base+'event'||request.url===base+'command')){const body=JSON.parse(request.postData);const clean={type:body.type,mediaId:body.mediaId,commandId:body.commandId,eventSeq:body.eventSeq,positionMs:body.positionMs,durationMs:body.durationMs};(request.url.endsWith('/event')?report.events:report.commands).push(clean);}
  }});
  const call=(method,params={})=>new Promise((resolve,reject)=>{const id=++sequence;pending.set(id,{resolve,reject});ws.send(JSON.stringify({id,method,params}));});
  const evaluate=async expression=>{const result=await call('Runtime.evaluate',{expression,awaitPromise:true,returnByValue:true});if(result.exceptionDetails)throw Error(result.exceptionDetails.exception?.description||result.exceptionDetails.text);return result.result.value;};
  const wait=async(expression,label=expression,timeout=8000)=>{const end=Date.now()+timeout;while(Date.now()<end){if(await evaluate(`Boolean(${expression})`))return;await sleep(60);}throw Error('WAIT_FAILED '+label);};
  const click=async id=>{const point=await evaluate(`(()=>{const r=document.getElementById(${JSON.stringify(id)}).getBoundingClientRect();return{x:r.x+r.width/2,y:r.y+r.height/2}})()`);await call('Input.dispatchMouseEvent',{type:'mousePressed',...point,button:'left',clickCount:1});await call('Input.dispatchMouseEvent',{type:'mouseReleased',...point,button:'left',clickCount:1});};
  const shot=async name=>{const file=path.join(output,name);fs.writeFileSync(file,Buffer.from((await call('Page.captureScreenshot',{format:'png'})).data,'base64'));report.screenshots.push(file);};
  const getState=()=>evaluate(`(async()=>{const saved=JSON.parse(sessionStorage.getItem('hongguo.browser.receiver'));return await fetch('/state',{headers:{'X-Cast-Token':saved.token},cache:'no-store'}).then(r=>r.json())})()`);
  await call('Page.enable');await call('Runtime.enable');await call('Network.enable');await call('Emulation.setDeviceMetricsOverride',{width:1100,height:900,deviceScaleFactor:1,mobile:false});await call('Page.navigate',{url:base});
  let firstId;
  await group('actual server pairs, serves ranged MP4 and requires a real gesture',async()=>{
    await wait('document.getElementById("pair-code")');await evaluate(`document.getElementById('pair-code').value=${JSON.stringify(ready.code)}`);await click('pair-submit');
    await wait('document.getElementById("video").readyState>=1','actual server MP4 metadata');await wait('!document.getElementById("start-play").hidden','tap prompt');
    eq(await evaluate('document.getElementById("video").paused'),true,'no automatic initial audible playback');
    const state=await getState();firstId=state.media.id;check(['WAITING_GESTURE','WAITING_RECEIVER_PLAY'].includes(state.state),'server does not claim playing from download');
    await shot('receiver-integrated-ready.png');
  });
  await group('actual ended event advances production session and next episode starts',async()=>{
    await click('start-play');await wait('!document.getElementById("video").paused&&document.getElementById("video").currentTime>.4','real first media clock');
    await shot('receiver-integrated-desktop.png');
    const firstSource=await evaluate('document.getElementById("video").currentSrc');
    await wait(`document.getElementById('video').currentSrc&&document.getElementById('video').currentSrc!==${JSON.stringify(firstSource)}`,'production automatic second publication',13000);
    await wait('!document.getElementById("video").paused&&document.getElementById("video").currentTime>.3','second episode actual clock');
    const state=await getState();eq(state.media.queuePosition,1,'production state advanced exactly one episode');eq(state.state,'PLAYING','production session accepted actual second playing');
    eq(report.events.filter(e=>e.type==='ended'&&e.mediaId===firstId).length,1,'one first EOF');eq(report.commands.filter(e=>e.type==='pause').length,0,'EOF never became manual pause');
  });
  await group('responsive controls, native pause/resume and three real completed episodes',async()=>{
    await call('Emulation.setDeviceMetricsOverride',{width:390,height:844,deviceScaleFactor:1,mobile:true});await shot('receiver-integrated-mobile.png');
    check(await evaluate('document.documentElement.scrollWidth<=window.innerWidth'),'no mobile overflow');
    check(await evaluate('["previous","toggle","next"].every(id=>document.getElementById(id).getBoundingClientRect().height<50)'),'primary controls remain readable single-line');
    await click('toggle');await wait('document.getElementById("video").paused&&!document.getElementById("toggle").disabled','pause acknowledged');
    eq((await getState()).state,'PAUSED','production paused state');await click('toggle');
    await wait('!document.getElementById("video").paused&&!document.getElementById("toggle").disabled','resumed actual video');
    await evaluate(`(async()=>{const saved=JSON.parse(sessionStorage.getItem('hongguo.browser.receiver'));const headers={'X-Cast-Token':saved.token};const state=await fetch('/state',{headers}).then(r=>r.json());await fetch('/command',{method:'POST',headers:{...headers,'Content-Type':'application/json'},body:JSON.stringify({sessionId:state.sessionId,mediaId:state.media.id,type:'seek',value:2500})});})()`);
    await wait('document.getElementById("video").currentTime>=2.5','remote seek applied while playing');
    const end=Date.now()+18000;let state;while(Date.now()<end){state=await getState();if(state.media?.queuePosition===2&&state.state==='ENDED')break;await sleep(150);}
    eq(state.media.queuePosition,2,'third episode selected by production session');eq(state.state,'ENDED','production final end confirmed');
    const ends=report.events.filter(e=>e.type==='ended');eq(ends.length,3,'three real EOF events');eq(new Set(ends.map(e=>e.mediaId)).size,3,'three distinct media generations');
    check(ends.every(e=>e.positionMs>=e.durationMs-1000),'all ended samples at actual tail');await shot('receiver-integrated-ended.png');
  });
  await group('receiver stop releases local playback after production service closure',async()=>{
    await click('stop');await wait('document.getElementById("player-section").hidden','receiver stop completion');
    eq(await evaluate('sessionStorage.getItem("hongguo.browser.receiver")'),null,'local control credential removed');
    eq(await evaluate('document.getElementById("video").getAttribute("src")'),null,'local media reference removed');
  });
  eq(report.externalRequests,[],'zero external requests');eq(report.pageErrors,[],'no uncaught JavaScript errors');
} catch(error){failed++;report.tests.push({name:'runner',result:'FAIL',error:error.stack});console.error(error.stack);}
finally{
  report.groupsPassed=passed;report.groupsFailed=failed;report.checks=checks;
  fs.writeFileSync(path.join(output,'report.json'),JSON.stringify(report,null,2)+'\n');
  try{ws?.close();}catch{}try{chrome?.kill('SIGTERM');}catch{}try{java?.stdin.end();}catch{}
  if(java&&java.exitCode===null)await Promise.race([new Promise(r=>java.once('exit',r)),sleep(5000)]);
  if(java&&java.exitCode===null)java.kill('SIGTERM');
  if(chrome&&chrome.exitCode===null)await Promise.race([new Promise(r=>chrome.once('exit',r)),sleep(3000)]);
  fs.rmSync(tmp,{recursive:true,force:true,maxRetries:8,retryDelay:100});
}
console.log(`ANDROID_BROWSER_RECEIVER_INTEGRATION_${failed?'FAILED':'OK'} groupsPassed=${passed} groupsFailed=${failed} checks=${checks} externalRequests=${report.externalRequests.length} server=production session=production media=synthetic appleHardware=not_run`);
process.exitCode=failed?1:0;
