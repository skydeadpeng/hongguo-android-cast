#!/usr/bin/env node
/** Real React App + real target bridge, isolated local Chrome, synthetic source/native RPC.
 * No adb, simulator, external API, real stream or device is used. */
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import http from 'node:http';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
const ownRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
let root = ownRoot, output = path.join(ownRoot, 'verification/android-autoplay-fallback/episode-ui');
for (let i = 2; i < process.argv.length; i++) {
  if (process.argv[i] === '--root' && process.argv[i + 1]) root = path.resolve(process.argv[++i]);
  else if (process.argv[i] === '--output' && process.argv[i + 1]) output = path.resolve(process.argv[++i]);
  else throw Error('Usage: node scripts/check-android-episode-oneclick.mjs [--root PROJECT] [--output DIRECTORY]');
}
const { build } = createRequire(import.meta.url)('esbuild');
const fixtureSource = `
export const CATEGORIES=[{id:'all',label:'全部'}];
export const createSource=()=>({
 catalog:async()=>({items:[window.__episodeFixture.series],totalPages:1}),
 detail:async()=>window.__episodeFixture.series,
 search:async()=>({items:[]}),
 playback:async(sid,vid,options)=>{
  const f=window.__episodeFixture;f.sources.push({sid,vid,options});
  const plan=f.sourcePlans.shift()||{}; if(plan.delay)await new Promise(r=>setTimeout(r,plan.delay));
  if(plan.error)throw Error(plan.error);
  return {url:'https://fixture.invalid/never-requested.mp4',vid,source:'app',width:1080,height:1920,requestedQuality:options.quality,variants:[]};
 }
});`;
function installMock() {
  const sid='1234567890123456789';
  const episodes=Array.from({length:8},(_,i)=>({vid:String(2345678901234567890n+BigInt(i)),index:i+1,webAvailable:i<3,availability:i<3?'available':'unknown'}));
  window.__episodeFixture={series:{seriesId:sid,title:'MOCK 直接选集剧',episodeCount:8,episodes},calls:[],sources:[],sourcePlans:[],optionPlans:[],playPlans:[],options:{quality:'1080p',speed:1.5},activePlay:0,maxActivePlay:0,notes:[]};
  const f=window.__episodeFixture;
  window.AndroidBridge={postMessage(raw){
    const m=JSON.parse(raw); f.calls.push(m);let result,error,delay=1;
    if(m.method==='getInfo')result={isTv:new URL(location.href).searchParams.get('tv')==='1',version:'MOCK-oneclick'};
    else if(m.method==='getLibrary')result={favorites:[],history:[]};
    else if(m.method==='getPlaybackOptions'){const plan=f.optionPlans.shift()||{};delay=plan.delay||1;error=plan.error;result=plan.options||{...f.options};}
    else if(m.method==='setPlaybackOptions'){f.options={...f.options,...m.params};result={...f.options};}
    else if(m.method==='play'){const plan=f.playPlans.shift()||{};delay=plan.delay||1;error=plan.error;f.activePlay++;f.maxActivePlay=Math.max(f.maxActivePlay,f.activePlay);result={started:true};}
    else if(m.method==='download')result={id:1};
    else if(m.method==='getDownloads')result=[];
    else if(m.method==='getSearchHistory')result={keywords:[]};
    else error='UNEXPECTED_RPC_'+m.method;
    setTimeout(()=>{if(m.method==='play')f.activePlay--;window.__androidResponse({id:m.id,...(error?{error}:{result})});},delay);
  }};
}
fs.mkdirSync(output,{recursive:true});
const temporary=fs.mkdtempSync(path.join(os.tmpdir(),'hongguo-oneclick-'));
const report={kind:'real React App + real bridge, MOCK source and native responses; not device or stream QA',root,tests:[],screenshots:[],externalRequests:[],pageErrors:[]};
let chrome, server, ws, cdp;
const sleep=ms=>new Promise(resolve=>setTimeout(resolve,ms));
let passed=0,failed=0,checks=0;
const check=(value,message)=>{checks++;assert.ok(value,message);};
const eq=(a,b,message)=>{checks++;assert.deepEqual(a,b,message);};
try {
  const bundled=await build({stdin:{contents:`import React from 'react';import {createRoot} from 'react-dom/client';import App from ${JSON.stringify(path.join(root,'src/android/App.jsx'))};createRoot(document.getElementById('root')).render(React.createElement(App));`,resolveDir:ownRoot,sourcefile:'oneclick-fixture-entry.jsx',loader:'jsx'},bundle:true,write:false,format:'iife',platform:'browser',target:'chrome100',nodePaths:[path.join(ownRoot,'node_modules')],plugins:[{name:'mock-sources-only',setup(build){build.onResolve({filter:/^\.\/source\.mjs$/},()=>({path:'source',namespace:'fixture'}));build.onLoad({filter:/.*/,namespace:'fixture'},()=>({contents:fixtureSource,loader:'js'}));build.onResolve({filter:/generated\/mac-app-source\.mjs$/},()=>({path:'protocol',namespace:'fixture-protocol'}));build.onLoad({filter:/.*/,namespace:'fixture-protocol'},()=>({contents:'export const fetchAppPlayUrl=async()=>{throw Error("NO_REAL_PROVIDER");};',loader:'js'}));build.onLoad({filter:/LICENSE\?raw$/},()=>({contents:'export default "MOCK LICENSE";',loader:'js'}));build.onResolve({filter:/LICENSE\?raw$/},()=>({path:'LICENSE?raw',namespace:'fixture-license'}));build.onLoad({filter:/.*/,namespace:'fixture-license'},()=>({contents:'export default "MOCK LICENSE";',loader:'js'}));}}]});
  const js=bundled.outputFiles[0].text,css=fs.readFileSync(path.join(root,'src/android/style.css'),'utf8');
  server=http.createServer((request,response)=>{if(request.url==='/fixture.js'){response.setHeader('content-type','text/javascript');response.end(js);}else if(request.url==='/fixture.css'){response.setHeader('content-type','text/css');response.end(css);}else {response.setHeader('content-type','text/html');response.end('<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><div id="root"></div><link rel="stylesheet" href="/fixture.css"><script src="/fixture.js"></script>');}});
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  const base=`http://127.0.0.1:${server.address().port}/`;
  const chromeBin=process.env.CHROME_BIN||'/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
  const profile=path.join(temporary,'profile');
  chrome=spawn(chromeBin,['--headless=new','--remote-debugging-port=0',`--user-data-dir=${profile}`,'--no-first-run','--disable-background-networking','--disable-default-apps','--no-default-browser-check','about:blank'],{stdio:['ignore','ignore',fs.openSync(path.join(output,'chrome.log'),'w')]});
  let port;
  for(let i=0;i<100;i++){try{port=Number(fs.readFileSync(path.join(profile,'DevToolsActivePort'),'utf8').split('\n')[0]);if(port)break;}catch{}await sleep(100);}
  if(!port)throw Error('Chrome CDP did not start');
  cdp=`http://127.0.0.1:${port}`;
  const target=await fetch(`${cdp}/json/new?about:blank`,{method:'PUT'}).then(r=>r.json());
  ws=new WebSocket(target.webSocketDebuggerUrl);await new Promise((resolve,reject)=>{ws.addEventListener('open',resolve,{once:true});ws.addEventListener('error',reject,{once:true});});
  let seq=0;const pending=new Map();
  ws.addEventListener('message',({data})=>{const m=JSON.parse(data);if(m.id){const p=pending.get(m.id);if(p){pending.delete(m.id);m.error?p.reject(Error(JSON.stringify(m.error))):p.resolve(m.result);}}if(m.method==='Runtime.exceptionThrown')report.pageErrors.push(m.params.exceptionDetails.exception?.description||m.params.exceptionDetails.text);if(m.method==='Network.requestWillBeSent'){const url=m.params.request.url;if(!url.startsWith(base)&&!url.startsWith('data:'))report.externalRequests.push(url);}});
  const call=(method,params={})=>new Promise((resolve,reject)=>{const id=++seq;pending.set(id,{resolve,reject});ws.send(JSON.stringify({id,method,params}));});
  const evaluate=async expression=>{const r=await call('Runtime.evaluate',{expression,awaitPromise:true,returnByValue:true});if(r.exceptionDetails)throw Error(r.exceptionDetails.exception?.description||r.exceptionDetails.text);return r.result.value;};
  const wait=async(predicate,label=predicate)=>{for(let i=0;i<100;i++){if(await evaluate(`Boolean(${predicate})`))return;await sleep(25);}throw Error('WAIT_FAILED '+label);};
  const reset=async(tv=false)=>{await call('Emulation.setDeviceMetricsOverride',{width:tv?1280:390,height:tv?720:844,deviceScaleFactor:1,mobile:!tv});await call('Page.navigate',{url:base+(tv?'?tv=1':'')});await wait('document.querySelector(".poster")');await evaluate('document.querySelector(".poster").click()');await wait('document.querySelectorAll(".episode-grid button").length===8&&document.querySelector("select[aria-label=清晰度]").value==="1080p"');await sleep(10);await evaluate('window.__episodeFixture.calls=[];window.__episodeFixture.sources=[];');};
  const idle=()=>wait('document.querySelector(".play-actions .primary")&&!document.querySelector(".play-actions .primary").disabled');
  const clickEpisode=index=>evaluate(`document.querySelectorAll('.episode-grid button')[${index-1}].click()`);
  const plays=()=>evaluate('window.__episodeFixture.calls.filter(x=>x.method==="play").map(x=>x.params)');
  const button=label=>evaluate(`(()=>{const b=[...document.querySelectorAll('button')].find(x=>x.textContent.trim()===${JSON.stringify(label)});if(!b)throw Error('missing button');b.click()})()`);
  const screenshot=async name=>{const file=path.join(output,name);fs.writeFileSync(file,Buffer.from((await call('Page.captureScreenshot',{format:'png',captureBeyondViewport:false})).data,'base64'));report.screenshots.push(file);};
  const group=async(name,fn)=>{try{await fn();passed++;report.tests.push({name,result:'PASS'});console.log('PASS '+name);}catch(error){failed++;report.tests.push({name,result:'FAIL',error:error.message});console.log('FAIL '+name+': '+error.message);}};
  await call('Page.enable');await call('Runtime.enable');await call('Network.enable');await call('Page.addScriptToEvaluateOnNewDocument',{source:`(${installMock.toString()})();`});
  await group('FUNCTION phone episode click directly dispatches exact modern play and compact queue',async()=>{
    await reset();eq((await plays()).length,0,'opening detail does not start video');await clickEpisode(4);await sleep(100);
    const calls=await plays();eq(calls.length,1,'one episode click must start play without second button');const p=calls[0];
    eq(p.episode.index,4,'target index is event episode, not previous React selection');eq(p.episode.vid,'2345678901234567893','target vid is exact');eq(p.episodes.length,8,'complete 8-item continuous queue');eq(p.episodes.map(x=>x.index),[1,2,3,4,5,6,7,8],'queue order preserved');eq(p.quality,'1080p','current preferred quality');eq(p.speed,1.5,'current speed');eq(p.resolveInPlayer,true,'native resolver starts inside player');eq(p.autoplay,true,'autoplay preference');eq(p.cleanupPlaybackCache,true,'cleanup preference');eq(p.keepPreviousEpisodes,1,'cache retention');check(!p.url&&!p.playInfo,'no preflight URL or stale model');check(!p.series.episodes,'compact series');eq(await evaluate('window.__episodeFixture.sources.length'),0,'JS playback provider never blocks modern play');
  });
  if(failed)throw Error('required one-click baseline behavior missing');
  await group('FUNCTION same-event repeated episode activation coalesces while preparing',async()=>{await reset();await evaluate('window.__episodeFixture.optionPlans=[{delay:100}];const b=document.querySelectorAll(".episode-grid button")[4];b.click();b.click();b.click();');await wait('window.__episodeFixture.calls.some(x=>x.method==="play")');await idle();eq((await plays()).length,1,'one native player');eq(await evaluate('window.__episodeFixture.calls.filter(x=>x.method==="getPlaybackOptions").length'),1,'one options read');});
  await group('FUNCTION rapid different episodes latest wins even when old options return later',async()=>{await reset();await evaluate('window.__episodeFixture.optionPlans=[{delay:180,options:{quality:"720p",speed:1}},{delay:10}];document.querySelectorAll(".episode-grid button")[1].click();document.querySelectorAll(".episode-grid button")[5].click();');await sleep(240);const p=await plays();eq(p.length,1,'stale request never opens player');eq(p[0].episode.index,6,'last chosen episode wins');eq(await evaluate('document.querySelector("select[aria-label=清晰度]").value'),'1080p','late stale preference cannot overwrite');});
  await group('FUNCTION superseded options error never replaces newer success',async()=>{await reset();await evaluate('window.__episodeFixture.optionPlans=[{delay:130,error:"MOCK_OLD_READ_FAILED"},{delay:5}];document.querySelectorAll(".episode-grid button")[0].click();document.querySelectorAll(".episode-grid button")[6].click();');await sleep(180);eq((await plays()).map(x=>x.episode.index),[7],'new request only');eq(await evaluate('!!document.querySelector(".operation-error")'),false,'old rejection suppressed');});
  await group('FUNCTION delayed resume option read never overwrites new quality choice',async()=>{await reset();await evaluate('window.__episodeFixture.optionPlans=[{delay:150,options:{quality:"1080p",speed:1.5}}];window.dispatchEvent(new Event("androidresume"));const q=document.querySelector("select[aria-label=清晰度]");q.value="720p";q.dispatchEvent(new Event("change",{bubbles:true}));');await sleep(200);eq(await evaluate('document.querySelector("select[aria-label=清晰度]").value'),'720p','late resume read superseded by explicit choice');await clickEpisode(3);await idle();eq((await plays())[0].quality,'720p','new preferred quality sent to native');});
  await group('FUNCTION dispatched native play cannot overlap a second native player',async()=>{await reset();await evaluate('window.__episodeFixture.playPlans=[{delay:180}];');await clickEpisode(3);await wait('window.__episodeFixture.activePlay===1');await clickEpisode(7);await evaluate('document.querySelector(".play-actions .primary").click()');await sleep(220);eq((await plays()).length,1,'buttons locked after dispatch');eq(await evaluate('window.__episodeFixture.maxActivePlay'),1,'no parallel native play RPC');});
  await group('FUNCTION explicit play remains and same-episode later activation resumes via native history',async()=>{await reset();await clickEpisode(5);await idle();await button('播放本集');await idle();await clickEpisode(5);await idle();const p=await plays();eq(p.map(x=>x.episode.index),[5,5,5],'later confirmed playback deliberately opens same chosen episode');check(p.every(x=>x.resolveInPlayer===true&&!('positionMs'in x)),'native stored history chooses resume, no forced replay seek');});
  await group('FUNCTION option-read failure shows error and retry starts originally selected episode',async()=>{await reset();await evaluate('window.__episodeFixture.optionPlans=[{error:"MOCK_OPTIONS_FAILED"}]');await clickEpisode(7);await wait('document.querySelector(".operation-error")');eq((await plays()).length,0,'failed options do not start');check(await evaluate('document.querySelector(".operation-error").textContent.includes("MOCK_OPTIONS_FAILED")'),'actual reason visible');await button('重试播放');await idle();eq((await plays())[0].episode.index,7,'retry target remains seventh');});
  await group('FUNCTION native start failure releases lock and permits retry',async()=>{await reset();await evaluate('window.__episodeFixture.playPlans=[{error:"MOCK_START_FAILED"}]');await clickEpisode(2);await wait('document.querySelector(".operation-error")');await button('重试播放');await idle();eq((await plays()).map(x=>x.episode.index),[2,2],'retry starts same episode once more');eq(await evaluate('document.querySelectorAll(".episode-grid button:disabled").length'),0,'lock released');});
  await group('FUNCTION leaving detail cancels late options before native dispatch',async()=>{await reset();await evaluate('window.__episodeFixture.optionPlans=[{delay:150}];document.querySelectorAll(".episode-grid button")[2].click();window.__androidBack();');await sleep(200);eq((await plays()).length,0,'unmounted page never opens stale player');eq(await evaluate('!!document.querySelector(".detail-page")'),false,'detail remains closed');});
  await group('FUNCTION download keeps current selection, strict quality and full playInfo',async()=>{await reset();await clickEpisode(4);await idle();await button('下载本集');await idle();const d=await evaluate('window.__episodeFixture.calls.find(x=>x.method==="download").params');eq(d.episode.index,4,'download uses selected episode');eq(d.quality,'1080p','download preferred quality stays strict');eq(d.playInfo.requestedQuality,'1080p','real mock provider model bound to quality');eq(d.url,'https://fixture.invalid/never-requested.mp4','original download path');check(!d.resolveInPlayer&&!d.episodes,'no player resolver or autoplay queue on manual download');eq(await evaluate('window.__episodeFixture.sources'),[{sid:'1234567890123456789',vid:'2345678901234567893',options:{quality:'1080p'}}],'source used only for download');});
  await group('FUNCTION failed manual download does not auto-lower and explicit retry works',async()=>{await reset();await clickEpisode(8);await idle();await evaluate('window.__episodeFixture.sourcePlans=[{error:"MOCK_1080_NOT_AVAILABLE"}]');await button('下载本集');await wait('document.querySelector(".operation-error")');eq(await evaluate('window.__episodeFixture.calls.filter(x=>x.method==="download").length'),0,'no file queued after source failure');eq(await evaluate('window.__episodeFixture.sources.map(x=>x.options.quality)'),['1080p'],'download never auto-lowers');await button('重试下载');await idle();eq(await evaluate('window.__episodeFixture.calls.find(x=>x.method==="download").params.episode.index'),8,'retry downloads selected episode');});
  await group('FUNCTION leaving during download resolution does not queue stale manual file',async()=>{await reset();await evaluate('window.__episodeFixture.sourcePlans=[{delay:150}];');await button('下载本集');await wait('window.__episodeFixture.sources.length===1');await evaluate('window.__androidBack()');await sleep(200);eq(await evaluate('window.__episodeFixture.calls.filter(x=>x.method==="download").length'),0,'late source never queues download after leave');});
  await group('FUNCTION chosen quality speed and autoplay preferences accompany one-click play',async()=>{await reset();await evaluate('const q=document.querySelector("select[aria-label=清晰度]");q.value="720p";q.dispatchEvent(new Event("change",{bubbles:true}));');await wait('!document.querySelector("select[aria-label=清晰度]").disabled');await evaluate('const s=document.querySelector("select[aria-label=播放倍速]");s.value="2";s.dispatchEvent(new Event("change",{bubbles:true}));');await wait('!document.querySelector("select[aria-label=播放倍速]").disabled');await evaluate('document.querySelectorAll("[role=switch]").forEach(x=>x.click())');await clickEpisode(6);await idle();const p=(await plays())[0];eq([p.quality,p.speed,p.autoplay,p.cleanupPlaybackCache],['720p',2,false,false],'exact selected preferences');eq(await evaluate('window.__episodeFixture.options.quality'),'720p','preferred quality not auto-mutated');});
  await group('FUNCTION TV focus and D-pad arrows never autoplay, Enter plays exactly focused episode',async()=>{await reset(true);await evaluate('document.querySelectorAll(".episode-grid button")[3].focus()');await call('Input.dispatchKeyEvent',{type:'keyDown',key:'ArrowRight',code:'ArrowRight',windowsVirtualKeyCode:39});await call('Input.dispatchKeyEvent',{type:'keyUp',key:'ArrowRight',code:'ArrowRight',windowsVirtualKeyCode:39});await sleep(25);eq((await plays()).length,0,'focus movement is selection-free');check(await evaluate('document.activeElement.matches(".episode-grid button")'),'D-pad stays on episode control');const label=await evaluate('document.activeElement.querySelector("span").textContent');await call('Input.dispatchKeyEvent',{type:'keyDown',key:'Enter',code:'Enter',windowsVirtualKeyCode:13});await call('Input.dispatchKeyEvent',{type:'keyUp',key:'Enter',code:'Enter',windowsVirtualKeyCode:13});await idle();eq((await plays()).length,1,'TV Enter one native play, no second action');eq((await plays())[0].episode.index,Number(label),'focused episode target');await screenshot('mock-tv-oneclick.png');});
  await group('FUNCTION real updated copy describes fallback without changing download semantics',async()=>{await reset();check(await evaluate('document.querySelector(".episode-note").textContent.includes("点击集数直接播放")'),'one-click instruction');const copy=await evaluate('document.querySelector("section[aria-label=清晰度与倍速]").textContent');check(copy.includes('1080p → 720p → 自动')&&copy.includes('实际档位')&&copy.includes('手动下载严格'),'truthful playback/download distinction');eq(await evaluate('document.documentElement.scrollWidth<=innerWidth'),true,'phone has no horizontal overflow');await evaluate('document.querySelector(".episode-section").scrollIntoView({block:"start"})');await screenshot('mock-phone-oneclick.png');eq(report.externalRequests,[],'no external network request');eq(report.pageErrors,[],'no browser runtime errors');});
  await call('Page.close');ws.close();ws=null;
} catch(error) {
  if(!failed){failed++;report.tests.push({name:'runner',result:'FAIL',error:error.stack});console.error(error.stack);}
} finally {
  if(ws){try{ws.close();}catch{}}
  if(server)await new Promise(resolve=>server.close(resolve));
  if(chrome){chrome.kill('SIGTERM');await Promise.race([new Promise(resolve=>chrome.once('exit',resolve)),sleep(4000)]);if(chrome.exitCode===null)chrome.kill('SIGKILL');}
  fs.rmSync(temporary,{recursive:true,force:true});
  report.summary={groupsPassed:passed,groupsFailed:failed,assertions:checks,networkRequests:report.externalRequests.length,deviceTests:'not_run',adb:'not_used',emulator:'not_used',cleanup:'own local server and Chrome stopped; temporary profile removed'};
  fs.writeFileSync(path.join(output,'results.json'),JSON.stringify(report,null,2)+'\n');
  console.log(`ANDROID_EPISODE_ONECLICK_${failed?'FAILED':'OK'} groupsPassed=${passed} groupsFailed=${failed} assertions=${checks} networkRequests=${report.externalRequests.length} deviceTests=not_run`);
  console.log('MOCK_UI_CLEANUP ownServer=stopped ownChrome=stopped ownProfile=removed adb=not_used emulator=not_used');
}
process.exitCode=failed?1:0;
