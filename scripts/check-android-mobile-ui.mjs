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
let root = ownRoot, output = path.join(ownRoot, 'verification/upstream-180-mobile/mobile-ui-current');
for (let i = 2; i < process.argv.length; i++) {
  if (process.argv[i] === '--root' && process.argv[i + 1]) root = path.resolve(process.argv[++i]);
  else if (process.argv[i] === '--output' && process.argv[i + 1]) output = path.resolve(process.argv[++i]);
  else throw Error('Usage: node scripts/check-android-episode-mobile-ui.mjs [--root PROJECT] [--output DIRECTORY]');
}
const { build } = createRequire(import.meta.url)('esbuild');
const fixtureSource = `
export const CATEGORIES=[{id:'all',label:'全部'},{id:'drama',label:'真人剧'}];
export const createSource=()=>({
 catalog:async(category='all',page=1)=>{
  const f=window.__mobileFixture;f.catalogPages=f.catalogPages||[];f.catalogPages.push({category,page});
  const batch=((page-1)%3)+1;
  return {items:[{...f.series,title:'MOCK 批次 '+batch}],page,totalPages:3};
 },
 detail:async()=>window.__mobileFixture.series,
 search:async keyword=>{window.__mobileFixture.searches.push(keyword);return {items:[]};},
 playback:async(sid,vid,options)=>{
  const f=window.__mobileFixture;f.sources.push({sid,vid,options});
  const plan=f.sourcePlans.shift()||{}; if(plan.delay)await new Promise(r=>setTimeout(r,plan.delay));
  if(plan.error)throw Error(plan.error);
  return {url:'https://fixture.invalid/never-requested.mp4',vid,source:'app',width:1080,height:1920,requestedQuality:options.quality,variants:[]};
 }
});`;
function installMock() {
  const sid='1234567890123456789';
  const episodes=Array.from({length:137},(_,i)=>({vid:String(2345678901234567890n+BigInt(i)),index:i+1,webAvailable:i<3,availability:i<3?'available':'unknown'}));
  window.__mobileFixture={series:{seriesId:sid,title:'MOCK 长剧移动布局',episodeCount:137,description:"这是一段用于验证简介展开与收起的合成剧情。".repeat(20),episodes},calls:[],sources:[],searches:[],searchKeywords:[],sourcePlans:[],optionPlans:[],playPlans:[],options:{quality:'1080p',speed:1.5},activePlay:0,maxActivePlay:0,notes:[]};
  const f=window.__mobileFixture;
  window.AndroidBridge={postMessage(raw){
    const m=JSON.parse(raw); f.calls.push(m);let result,error,delay=1;
    if(m.method==='getInfo')result={isTv:new URL(location.href).searchParams.get('tv')==='1',version:'MOCK-mobile-ui'};
    else if(m.method==='getLibrary')result={favorites:[],history:[{...f.series,vid:f.series.episodes[91].vid,index:92,positionMs:1000}]};
    else if(m.method==='getPlaybackOptions'){const plan=f.optionPlans.shift()||{};delay=plan.delay||1;error=plan.error;result=plan.options||{...f.options};}
    else if(m.method==='setPlaybackOptions'){f.options={...f.options,...m.params};result={...f.options};}
    else if(m.method==='play'){const plan=f.playPlans.shift()||{};delay=plan.delay||1;error=plan.error;f.activePlay++;f.maxActivePlay=Math.max(f.maxActivePlay,f.activePlay);result={started:true};}
    else if(m.method==='download')result={id:1};
    else if(m.method==='getDownloads')result=[{id:17,status:'success',title:'MOCK 下载名称较长的短剧用于移动布局检查',index:137,downloadedBytes:1234567,totalBytes:1234567}];
    else if(m.method==='playDownload')result={started:true};
    else if(m.method==='getPlaybackCache')result={files:0,bytes:0};
    else if(m.method==='clearPlaybackCache')result={removed:0,bytes:0};
    else if(m.method==='openExternal')result={opened:true};
    else if(m.method==='toggleFavorite')result={favorites:[m.params.series],history:[]};
    else if(m.method==='getSearchHistory')result={keywords:[...f.searchKeywords]};
    else if(m.method==='addSearchHistory'){f.searchKeywords=[m.params.keyword,...f.searchKeywords.filter(x=>x!==m.params.keyword)].slice(0,20);result={keywords:[...f.searchKeywords]};}
    else error='UNEXPECTED_RPC_'+m.method;
    setTimeout(()=>{if(m.method==='play')f.activePlay--;window.__androidResponse({id:m.id,...(error?{error}:{result})});},delay);
  }};
}
fs.mkdirSync(output,{recursive:true});
const temporary=fs.mkdtempSync(path.join(os.tmpdir(),'hongguo-mobile-ui-'));
const report={kind:'real React App + real bridge, MOCK source and native responses; not device or stream QA',root,tests:[],screenshots:[],externalRequests:[],pageErrors:[]};
let chrome, server, ws, cdp;
const sleep=ms=>new Promise(resolve=>setTimeout(resolve,ms));
let passed=0,failed=0,checks=0;
const check=(value,message)=>{checks++;assert.ok(value,message);};
const eq=(a,b,message)=>{checks++;assert.deepEqual(a,b,message);};
try {
  const bundled=await build({stdin:{contents:`import React from 'react';import {createRoot} from 'react-dom/client';import App from ${JSON.stringify(path.join(root,'src/android/App.jsx'))};createRoot(document.getElementById('root')).render(React.createElement(App));`,resolveDir:ownRoot,sourcefile:'mobile-ui-fixture-entry.jsx',loader:'jsx'},bundle:true,write:false,format:'iife',platform:'browser',target:'chrome100',nodePaths:[path.join(ownRoot,'node_modules')],plugins:[{name:'mock-sources-only',setup(build){build.onResolve({filter:/^\.\/source\.mjs$/},()=>({path:'source',namespace:'fixture'}));build.onLoad({filter:/.*/,namespace:'fixture'},()=>({contents:fixtureSource,loader:'js'}));build.onResolve({filter:/generated\/mac-app-source\.mjs$/},()=>({path:'protocol',namespace:'fixture-protocol'}));build.onLoad({filter:/.*/,namespace:'fixture-protocol'},()=>({contents:'export const fetchAppPlayUrl=async()=>{throw Error("NO_REAL_PROVIDER");};',loader:'js'}));build.onLoad({filter:/LICENSE\?raw$/},()=>({contents:'export default "MOCK LICENSE";',loader:'js'}));build.onResolve({filter:/LICENSE\?raw$/},()=>({path:'LICENSE?raw',namespace:'fixture-license'}));build.onLoad({filter:/.*/,namespace:'fixture-license'},()=>({contents:'export default "MOCK LICENSE";',loader:'js'}));}}]});
  const js=bundled.outputFiles[0].text,css=fs.readFileSync(path.join(root,'src/android/style.css'),'utf8');
  // This gate mounts App directly with mocked native/source responses. The separate
  // check-android-tv-webview gate owns the real packaged bootstrap and readiness.
  const fixtureHtml=fs.readFileSync(path.join(root,'src/android/index.html'),'utf8')
    .replace(/<script\b[^>]*\bdata-hongguo-boot\b[^>]*><\/script>/,'')
    .replace(/<link\b[^>]*href="\/startup\.css"[^>]*>/,'')
    .replace(/<script[^>]+src="\/main\.jsx"[^>]*><\/script>/,'<script src="/fixture.js"></script>')
    .replace('</head>','<link rel="stylesheet" href="/fixture.css"></head>');
  server=http.createServer((request,response)=>{if(request.url==='/fixture.js'){response.setHeader('content-type','text/javascript');response.end(js);}else if(request.url==='/fixture.css'){response.setHeader('content-type','text/css');response.end(css);}else {response.setHeader('content-type','text/html');response.end(fixtureHtml);}});
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
  const reset=async(tv=false,width=390,height=844,large=false)=>{await call('Emulation.setDeviceMetricsOverride',{width:tv?1280:width,height:tv?720:height,deviceScaleFactor:1,mobile:!tv});await call('Emulation.setTouchEmulationEnabled',{enabled:!tv});await call('Page.navigate',{url:base+(tv?'?tv=1':'')});await wait('document.querySelector(".poster")');await evaluate('document.querySelector(".poster").click()');await wait('document.querySelector(".episode-grid button")&&document.querySelector("select[aria-label=清晰度]").value==="1080p"');await sleep(30);if(large)await evaluate('document.documentElement.style.fontSize="24px"');await evaluate('window.__mobileFixture.calls=[];window.__mobileFixture.sources=[];');};
  const idle=()=>wait('document.querySelector(".play-actions .primary")&&!document.querySelector(".play-actions .primary").disabled');
  const clickEpisode=index=>evaluate(`(()=>{const b=[...document.querySelectorAll('.episode-grid button')].find(b=>Number(b.querySelector('span').textContent)===${index});if(!b)throw Error('missing episode ${index}');b.click()})()`);
  const plays=()=>evaluate('window.__mobileFixture.calls.filter(x=>x.method==="play").map(x=>x.params)');
  const tapEpisode=async index=>{
    await evaluate(`(()=>{const b=[...document.querySelectorAll('.episode-grid button')].find(b=>Number(b.querySelector('span').textContent)===${index});if(!b)throw Error('missing touch target');b.scrollIntoView({block:'center',inline:'nearest'});})()`);await sleep(30);
    const point=await evaluate(`(()=>{const b=[...document.querySelectorAll('.episode-grid button')].find(b=>Number(b.querySelector('span').textContent)===${index});const r=b.getBoundingClientRect();return {x:r.x+r.width/2,y:r.y+r.height/2};})()`);
    await call('Input.dispatchTouchEvent',{type:'touchStart',touchPoints:[{...point,radiusX:5,radiusY:5,force:1,id:1}]});await call('Input.dispatchTouchEvent',{type:'touchEnd',touchPoints:[]});await sleep(80);
  };
  const button=label=>evaluate(`(()=>{const b=[...document.querySelectorAll('button')].find(x=>x.textContent.trim()===${JSON.stringify(label)});if(!b)throw Error('missing button');b.click()})()`);
  const screenshot=async name=>{const file=path.join(output,name);fs.writeFileSync(file,Buffer.from((await call('Page.captureScreenshot',{format:'png',captureBeyondViewport:false})).data,'base64'));report.screenshots.push(file);};
  const group=async(name,fn)=>{try{await fn();passed++;report.tests.push({name,result:'PASS'});console.log('PASS '+name);}catch(error){failed++;report.tests.push({name,result:'FAIL',error:error.message});console.log('FAIL '+name+': '+error.message);}};
  await call('Page.enable');await call('Runtime.enable');await call('Network.enable');await call('Page.addScriptToEvaluateOnNewDocument',{source:`(${installMock.toString()})();`});
  const chooseRange=async value=>{await evaluate(`(()=>{const s=document.querySelector('#episode-range');if(!s)throw Error('missing grouped range selector');s.value=${JSON.stringify(String(value))};s.dispatchEvent(new Event('change',{bubbles:true}));})()`);await sleep(40);};
  const noOverflow=async()=>{
    const sizes=await evaluate(`(()=>{const nodes=[document.documentElement,document.querySelector('.main-content'),document.querySelector('.detail-scroll')].filter(Boolean);return nodes.map(e=>({class:e.className||'html',width:e.clientWidth,scroll:e.scrollWidth}));})()`);
    if(sizes.some(s=>s.scroll>s.width+1)){
      const wide=await evaluate(`(()=>{const root=document.querySelector('.main-content');const wide=[];root.querySelectorAll('*').forEach(e=>{if(e.scrollWidth>e.clientWidth+1&&e.clientWidth>0||e.getBoundingClientRect().right>document.documentElement.clientWidth+1)wide.push({cls:(e.className||e.tagName).toString().slice(0,40),w:Math.round(e.getBoundingClientRect().width),right:Math.round(e.getBoundingClientRect().right),scroll:e.scrollWidth,client:e.clientWidth});});return wide.slice(0,8);})()`);
      throw Error('no horizontal overflow '+JSON.stringify(sizes)+' wide='+JSON.stringify(wide));
    }
  };
  const dockVisible=async()=>{
    const positions=await evaluate(`(()=>{const r=e=>{const b=e.getBoundingClientRect();return {top:b.top,bottom:b.bottom,left:b.left,right:b.right,height:b.height}};return {dock:r(document.querySelector('.play-actions')),nav:r(document.querySelector('.app-nav')),buttons:[...document.querySelectorAll('.play-actions button')].map(r),height:innerHeight,width:innerWidth};})()`);
    check(positions.dock.top>=0&&positions.dock.bottom<=positions.nav.top+1,'action dock occupies viewport space above bottom navigation '+JSON.stringify(positions));
    check(positions.buttons.every(r=>r.top>=0&&r.bottom<=positions.nav.top+1&&r.left>=0&&r.right<=positions.width),'play and download controls fully visible');
  };
  const touchTargets=async()=>{
    const small=await evaluate(`(()=>{const main=document.querySelector('.detail-scroll')||document.querySelector('.main-content');return [...document.querySelectorAll('button,input,select')].filter(e=>e.getClientRects().length&&getComputedStyle(e).visibility!=='hidden').filter(e=>{const label=e.closest('label');return !(label&&label.getBoundingClientRect().height>=43.5)}).map(e=>({label:e.getAttribute('aria-label')||e.textContent.trim().slice(0,30),w:e.getBoundingClientRect().width,h:e.getBoundingClientRect().height})).filter(r=>r.w<43.5||r.h<43.5)})()`);
    assert.deepEqual(small,[],'all rendered interactive controls have at least 44px touch targets: '+JSON.stringify(small));
  };
  await group('FUNCTION remembered long-series episode opens its 30-item group without playing',async()=>{
    await reset();eq(await evaluate('document.querySelectorAll(".episode-grid button").length'),30,'phone only renders active 30-episode range');eq(await evaluate('document.querySelector("#episode-range").value'),'3','remembered episode 92 reveals 91–120');eq(await evaluate('document.querySelector(".episode-grid button[aria-pressed=true]").querySelector("span").textContent'),'92','remembered selection retained');eq((await plays()).length,0,'opening remembered detail never starts video');await dockVisible();await screenshot('phone-390-initial.png');
    await chooseRange(0);eq((await plays()).length,0,'range selection is browse only');eq(await evaluate('document.querySelector(".play-actions").textContent.includes("已选第 92 集")'),true,'range browse keeps exact playback selection');await button('定位第 92 集');await wait('document.querySelector("#episode-range").value==="3"');await wait('document.activeElement.matches(".episode-grid button[aria-pressed=true]")');eq((await plays()).length,0,'locate changes range and focus, never plays');
    await chooseRange(4);eq(await evaluate('[...document.querySelectorAll(".episode-grid button")].map(e=>Number(e.querySelector("span").textContent))'),Array.from({length:17},(_,i)=>121+i),'last partial range preserves all final episodes');await tapEpisode(137);await idle();const p=(await plays())[0];eq(p.episode.index,137,'single tap starts chosen last episode');eq(p.episode.vid,'2345678901234568026','exact long-series last vid');eq(p.episodes.length,137,'complete native continuous-play queue is not truncated by UI groups');eq(p.resolveInPlayer,true,'native resolver retained');check(!p.playInfo&&!p.url,'no obsolete preflight');eq(await evaluate('window.__mobileFixture.sources.length'),0,'playing does not call JS provider');
  });
  await group('FUNCTION mobile synopsis collapses and expands accessibly while TV copy stays complete',async()=>{
    await reset();check(await evaluate('!!document.querySelector(".description-toggle")'),'mobile synopsis has expansion control');const initial=await evaluate('document.querySelector(".detail-description").getBoundingClientRect().height');eq(await evaluate('document.querySelector(".description-toggle").getAttribute("aria-expanded")'),'false','synopsis starts collapsed');await button('展开简介');await wait('document.querySelector(".description-toggle").getAttribute("aria-expanded")==="true"');check(await evaluate('document.querySelector(".detail-description").getBoundingClientRect().height')>initial*2,'expanded text is actually rendered, not a label-only toggle');await button('收起简介');await wait('document.querySelector(".description-toggle").getAttribute("aria-expanded")==="false"');eq(await evaluate('document.querySelector(".detail-description").getBoundingClientRect().height'),initial,'collapse restores three-line height');eq((await plays()).length,0,'description never starts playback');
  });
  await group('LAYOUT phone 320, 360, 390 and low-height landscape keep actions visible and touch targets usable',async()=>{
    for(const [width,height] of [[320,640],[360,740],[390,844],[844,390]]){
      await reset(false,width,height);check(await evaluate('!!document.querySelector(".detail-scroll")'),'phone detail has independent content scroll area');await noOverflow();await dockVisible();await touchTargets();
      await evaluate('document.querySelector(".detail-scroll").scrollTop=999999');await sleep(30);await dockVisible();await noOverflow();
      const bounds=await evaluate('({last:document.querySelectorAll(".playback-settings .setting-switch")[1].getBoundingClientRect().bottom,dock:document.querySelector(".play-actions").getBoundingClientRect().top})');check(bounds.last<=bounds.dock,'last setting is not hidden behind action dock '+JSON.stringify(bounds));
      await screenshot(`phone-${width}x${height}-settings.png`);
    }
  });
  await group('LAYOUT 160 percent text at 320 and 390 wraps controls without horizontal overflow or overlap',async()=>{
    for(const width of [320,390]){
      await reset(false,width,844,true);eq(await evaluate('getComputedStyle(document.querySelector(".playback-setting h2")).fontSize'),'24px','phone setting text scales with root text size');await noOverflow();await dockVisible();await touchTargets();
      await evaluate('document.querySelector(".detail-scroll").scrollTop=999999');await sleep(20);await noOverflow();await dockVisible();await screenshot(`phone-${width}-large-text-settings.png`);
      await evaluate('document.querySelector(".episode-section").scrollIntoView({block:"start"})');await screenshot(`phone-${width}-large-text-episodes.png`);
    }
  });
  await group('FUNCTION failed play keeps same selected episode, visible error and working retry',async()=>{
    await reset();await evaluate('window.__mobileFixture.playPlans=[{error:"MOCK_START_FAILED_HTTP_403"}]');await clickEpisode(95);await wait('document.querySelector(".operation-error")');check(await evaluate('document.querySelector(".operation-error").textContent.includes("MOCK_START_FAILED_HTTP_403")'),'actual failure reason remains visible');await dockVisible();
    const bounds=await evaluate('({error:document.querySelector(".operation-error").getBoundingClientRect().toJSON(),scroll:document.querySelector(".detail-scroll").getBoundingClientRect().toJSON()})');check(bounds.error.top>=bounds.scroll.top-1&&bounds.error.bottom<=bounds.scroll.bottom+1,'failure automatically scrolls into unobscured content '+JSON.stringify(bounds));await screenshot('phone-play-error.png');await button('重试播放');await idle();eq((await plays()).map(p=>p.episode.index),[95,95],'retry exact same episode');eq(await evaluate('document.querySelector("#episode-range").value'),'3','error retry retains range');
  });
  await group('FUNCTION manual download stays strict with exact selected episode and successful retry',async()=>{
    await reset();await chooseRange(4);await clickEpisode(137);await idle();await evaluate('window.__mobileFixture.sourcePlans=[{error:"MOCK_1080_UNAVAILABLE"}]');await button('下载本集');await wait('document.querySelector(".operation-error")');eq(await evaluate('window.__mobileFixture.sources.map(s=>s.options.quality)'),['1080p'],'manual download never lowers quality');eq(await evaluate('window.__mobileFixture.calls.filter(c=>c.method==="download").length'),0,'failed source not queued');await dockVisible();await button('重试下载');await idle();const d=await evaluate('window.__mobileFixture.calls.find(c=>c.method==="download").params');eq(d.episode.index,137,'retry keeps exact target');eq(d.quality,'1080p','requested preferred quality unchanged');eq(d.playInfo.requestedQuality,'1080p','measured strict playInfo still sent');check(!d.resolveInPlayer&&!d.episodes,'download does not use player fallback or truncate queue');
  });
  await group('FUNCTION rapid episode confirms retain existing synchronous coalescing and latest-wins behavior',async()=>{
    await reset();const repeated=await evaluate('Number(document.querySelectorAll(".episode-grid button")[1].querySelector("span").textContent)');await evaluate('window.__mobileFixture.optionPlans=[{delay:120}];const b=document.querySelectorAll(".episode-grid button")[1];b.click();b.click();b.click()');await sleep(180);eq((await plays()).map(p=>p.episode.index),[repeated],'same-event repeated confirms open only one player');
    await reset();const latest=await evaluate('Number(document.querySelectorAll(".episode-grid button")[5].querySelector("span").textContent)');await evaluate('window.__mobileFixture.optionPlans=[{delay:150},{delay:5}];document.querySelectorAll(".episode-grid button")[1].click();document.querySelectorAll(".episode-grid button")[5].click()');await sleep(200);eq((await plays()).map(p=>p.episode.index),[latest],'late previous options never open wrong episode');
  });
  await group('REGRESSION TV retains all 137 episodes and D-pad focus alone never starts playback',async()=>{
    await reset(true);eq(await evaluate('document.querySelectorAll(".episode-grid button").length'),137,'TV retains complete directory');eq(await evaluate('!document.querySelector(".episode-browser")||document.querySelector(".episode-browser").hidden'),true,'mobile range controls do not participate in TV focus');eq(await evaluate('document.querySelector(".description-toggle")===null'),true,'TV retains full synopsis');await evaluate('document.querySelectorAll(".episode-grid button")[91].focus()');await call('Input.dispatchKeyEvent',{type:'keyDown',key:'ArrowRight',code:'ArrowRight',windowsVirtualKeyCode:39});await call('Input.dispatchKeyEvent',{type:'keyUp',key:'ArrowRight',code:'ArrowRight',windowsVirtualKeyCode:39});await sleep(30);eq((await plays()).length,0,'D-pad focus is browse only');check(await evaluate('document.activeElement.matches(".episode-grid button")'),'D-pad remains within directory');const index=await evaluate('Number(document.activeElement.querySelector("span").textContent)');await call('Input.dispatchKeyEvent',{type:'keyDown',key:'Enter',code:'Enter',windowsVirtualKeyCode:13});await call('Input.dispatchKeyEvent',{type:'keyUp',key:'Enter',code:'Enter',windowsVirtualKeyCode:13});await idle();eq((await plays()).map(p=>p.episode.index),[index],'Enter directly plays focused exact episode once');await screenshot('tv-full-directory.png');
  });
  await group('REGRESSION phone navigation, search and downloaded playback remain usable at enlarged text',async()=>{
    await reset(false,320,844,true);for(const name of ['发现','搜索','我的追剧','下载','关于']){
      await evaluate(`(()=>{const b=[...document.querySelectorAll('.app-nav button')].find(b=>b.textContent.trim()===${JSON.stringify(name)});b.click()})()`);await sleep(50);await noOverflow();await touchTargets();
      if(name==='下载'){await button('播放');await wait('window.__mobileFixture.calls.some(c=>c.method==="playDownload")');eq(await evaluate('window.__mobileFixture.calls.find(c=>c.method==="playDownload").params.id'),17,'downloaded file still opens with exact id');}
    }await screenshot('phone-320-large-text-about.png');
  });
  await group('INTEGRATION same Detail survives portrait landscape portrait without changing range or starting video',async()=>{
    await reset();await chooseRange(4);await tapEpisode(137);await idle();const first=(await plays())[0];eq(first.episodes.length,137,'initial queue contains full series');
    for(const [width,height] of [[844,390],[390,844]]){
      await call('Emulation.setDeviceMetricsOverride',{width,height,deviceScaleFactor:1,mobile:true});await sleep(60);await noOverflow();await dockVisible();await touchTargets();eq(await evaluate('document.querySelector("#episode-range").value'),'4','orientation preserves browsed range');eq(await evaluate('document.querySelector(".play-actions").textContent.includes("已选第 137 集")'),true,'orientation preserves selected episode');eq((await plays()).length,1,'layout change never dispatches play RPC');eq((await plays())[0].episode.vid,first.episode.vid,'exact selected identity survives');
    }
    await tapEpisode(137);await idle();eq((await plays()).length,2,'subsequent tap still starts exactly once');eq((await plays())[1].episodes.length,137,'queue remains complete after reflow');await screenshot('phone-orientation-restored.png');
  });
  await group('INTEGRATION search input submit and navigation remain reachable with simulated IME inset',async()=>{
    await reset(false,320,844);await evaluate('[...document.querySelectorAll(".app-nav button")].find(b=>b.textContent.trim()==="搜索").click()');await wait('document.querySelector("#search-keyword")');await call('Emulation.setDeviceMetricsOverride',{width:320,height:300,deviceScaleFactor:1,mobile:true});await evaluate('document.querySelector("#search-keyword").focus();document.querySelector("#search-keyword").scrollIntoView({block:"center"})');await sleep(40);await noOverflow();await touchTargets();
    const bounds=await evaluate('({input:document.querySelector("#search-keyword").getBoundingClientRect().toJSON(),submit:document.querySelector(".search-controls button").getBoundingClientRect().toJSON(),main:document.querySelector(".main-content").getBoundingClientRect().toJSON(),nav:document.querySelector(".app-nav").getBoundingClientRect().toJSON()})');check(bounds.input.top>=bounds.main.top-1&&bounds.input.bottom<=bounds.nav.top+1,'focused input fits above native IME-sized viewport');check(bounds.submit.top>=bounds.main.top-1&&bounds.submit.bottom<=bounds.nav.top+1,'search submit remains reachable beside input');await call('Input.insertText',{text:'键盘复测'});eq(await evaluate('document.querySelector("#search-keyword").value'),'键盘复测','text entry reaches focused real React input');await call('Input.dispatchTouchEvent',{type:'touchStart',touchPoints:[{x:bounds.submit.x+bounds.submit.width/2,y:bounds.submit.y+bounds.submit.height/2,radiusX:5,radiusY:5,force:1,id:1}]});await call('Input.dispatchTouchEvent',{type:'touchEnd',touchPoints:[]});await wait('window.__mobileFixture.searches.includes("键盘复测")');eq(await evaluate('window.__mobileFixture.searchKeywords'),['键盘复测'],'IME-sized submit keeps native history path');
    const nav=await evaluate('[...document.querySelectorAll(".app-nav button")].map(b=>b.getBoundingClientRect().toJSON())');check(nav.every(r=>r.top>=0&&r.bottom<=300&&r.left>=0&&r.right<=320),'all five navigation targets remain within reduced viewport');await screenshot('phone-320-ime-inset-search.png');await call('Emulation.setDeviceMetricsOverride',{width:320,height:844,deviceScaleFactor:1,mobile:true});await sleep(40);eq(await evaluate('document.querySelector("#search-keyword").value'),'键盘复测','restoring viewport preserves entered query');await noOverflow();await screenshot('phone-320-ime-restored-search.png');
  });
  await group('DISCOVER 换一批 advances the real next page, wraps at the end and remembers each category position',async()=>{
    await reset();await button('发现');await sleep(60);
    const firstTitle=()=>evaluate('document.querySelector(".poster strong").textContent');
    check(await firstTitle()==='MOCK 批次 1','the first batch opens at page 1');
    eq(await evaluate('window.__mobileFixture.catalogPages'),[{category:'all',page:1}],'one catalog request for the first page');
    await button('换一批');await sleep(60);check(await firstTitle()==='MOCK 批次 2','换一批 advances to the real next page');
    await button('换一批');await sleep(60);check(await firstTitle()==='MOCK 批次 3','the third batch follows');
    await button('换一批');await sleep(60);check(await firstTitle()==='MOCK 批次 1','the batch wraps to the start at the last page');
    await button('真人剧');await sleep(60);check(await firstTitle()==='MOCK 批次 1','another category starts at its own first batch');
    await button('换一批');await sleep(60);check(await firstTitle()==='MOCK 批次 2','the second category advances independently');
    await button('全部');await sleep(60);check(await firstTitle()==='MOCK 批次 1','switching back resumes the remembered position');
    await noOverflow();await screenshot('phone-discover-next-batch.png');
  });
  await group('ISOLATION no browser exceptions or external source/media requests',async()=>{eq(report.externalRequests,[],'zero external requests');eq(report.pageErrors,[],'zero runtime exceptions');});
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
  console.log(`ANDROID_MOBILE_UI_${failed?'FAILED':'OK'} groupsPassed=${passed} groupsFailed=${failed} assertions=${checks} networkRequests=${report.externalRequests.length} deviceTests=not_run`);
  console.log('MOCK_UI_CLEANUP ownServer=stopped ownChrome=stopped ownProfile=removed adb=not_used emulator=not_used');
}
process.exitCode=failed?1:0;
