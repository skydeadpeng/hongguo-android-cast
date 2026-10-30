#!/usr/bin/env node
/** Real React App, bridge and directory/search parsers in isolated local Chrome.
 * All native RPC responses are synthetic. No provider, media or device is used. */
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import http from 'node:http';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { createHash } from 'node:crypto';
const ownRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
let root = ownRoot, output = path.join(ownRoot, 'verification/android-account-sync/ui');
for (let i = 2; i < process.argv.length; i++) {
  if (process.argv[i] === '--root' && process.argv[i + 1]) root = path.resolve(process.argv[++i]);
  else if (process.argv[i] === '--output' && process.argv[i + 1]) output = path.resolve(process.argv[++i]);
  else throw Error('Usage: node scripts/check-android-account-ui.mjs [--root PROJECT] [--output DIRECTORY]');
}
const { build } = createRequire(import.meta.url)('esbuild');
const SID = ['7000000000000000001', '7000000000000000002', '7000000000000000003', '7000000000000000004', '7000000000000000005', '7000000000000000006', '7000000000000000007', '7000000000000000008'];
function installMock() {
  const ids = ['7000000000000000001', '7000000000000000002', '7000000000000000003', '7000000000000000004', '7000000000000000005', '7000000000000000006', '7000000000000000007', '7000000000000000008'];
  const specs = [
    ['合成：山海：第一季', 65], ['合成：山海：第二季', 43], ['合成：山海：第三季', 3],
    ['合成：山海：第四季', 100], ['合成：山海：第四季', 219],
    ['合成山海：第三季', 12], ['独立故事', 8], ['另一故事：第一季', 9],
  ];
  const entries = Object.fromEntries(specs.map(([title, count], index) => {
    const seriesId = ids[index];
    const episodes = Array.from({ length: count }, (_, i) => ({ vid: String(8000000000000000000n + BigInt(index) * 10000n + BigInt(i)), index: i + 1, webAvailable: i < 3, availability: i < 3 ? 'available' : 'unknown' }));
    return [seriesId, { seriesId, title, episodeCount: count, episodes, description: `这是${title}的合成简介，用于验证切季后简介与分集独立。`.repeat(8) }];
  }));
  const first = entries[ids[0]], second = entries[ids[1]];
  const compact = item => ({ seriesId: item.seriesId, title: item.title, episodeCount: item.episodeCount });
  const f = window.__seasonFixture = {
    ids, entries, calls: [], resolves: [], searches: [], detailReads: [], notes: [],
    optionPlans: [], playPlans: [], downloadPlans: [], resolvePlans: [], searchPlans: [], detailPlans: {}, libraryPlans: [],
    options: { quality: '1080p', speed: 1 }, searchKeywords: [],
    library: { favorites: [], history: [
      { ...compact(first), ...first.episodes[31], positionMs: 3200, updatedAt: 1000 },
      { ...compact(second), ...second.episodes[8], positionMs: 9000, updatedAt: 2000 },
    ] },
  };
  f.guest = JSON.parse(JSON.stringify(f.library.history));
  const accountBase = () => ({provider:'hongguo',officialLoginAvailable:true,authenticated:false,phase:'phone',revision:0,busy:false,maskedPhone:'',retryAfter:0,smsState:'',error:'',libraryReady:true,sync:{syncing:false,pendingCount:0,lastSyncAt:0,error:''}});
  f.owner = () => f.account.authenticated ? 'official:'+(f.account.fixtureOwner||'a').repeat(64) : 'guest';
  f.account=accountBase(); f.nativePhone=''; f.sendMode='sent';f.loginMode='success';f.verifyDelay=1;
  f.accountRpc = m => {
    const plan=m.method==='getAccount'?(f.accountPlans.shift()||{}):{};let result,error=plan.error,delay=plan.delay||1;
    const fresh=patch=>({...f.account,...patch,revision:f.account.revision+1});
    const signed=()=>fresh({authenticated:true,phase:'logged_in',busy:false,maskedPhone:'',error:'',user:{name:'合成官方账号',maskedPhone:'138****0000',idSuffix:'尾号 5678'},sync:{syncing:false,pendingCount:0,lastSyncAt:0,error:''}});
    if(m.method==='getAccount')result=JSON.parse(JSON.stringify(f.account));
    else if(m.method==='officialSendCode'){
      if(m.params.phone)f.nativePhone=m.params.phone;
      if(!f.nativePhone){error='请输入手机号';result=f.account;}
      else {f.account=fresh({phase:'sending',busy:true,error:''});delay=f.sendDelay||30;result=fresh({phase:f.sendMode==='sent'?'code':'phone',busy:false,maskedPhone:'138****0000',smsState:f.sendMode,retryAfter:f.sendMode==='rejected'?0:60,error:f.sendMode==='rejected'?'合成发码被拒绝':f.sendMode==='uncertain'?'合成发码结果不确定':''});}
    } else if(m.method==='officialLoginSMS'){
      f.account=fresh({phase:'submitting',busy:true,error:''});delay=f.loginDelay||40;
      result=f.loginMode==='wrong'?fresh({phase:'code',busy:false,error:'验证码错误'}):f.loginMode==='mfa'?fresh({phase:'mfa',busy:false,error:'',smsNumber:'106900000001',smsContent:'SYNTHETIC MESSAGE',mfaExpiresAt:Date.now()+600000}):signed();
    } else if(m.method==='officialVerifyMFA'){
      f.account=fresh({phase:'mfa_waiting',busy:true,error:''});delay=f.verifyDelay;result=signed();
    } else if(m.method==='officialCancelLogin'){
      const background=m.params.reason==='background';
      f.account=fresh(background?{phase:f.account.phase==='mfa_waiting'?'mfa':f.account.phase==='submitting'?'code':f.account.phase,busy:false,error:''}:{...accountBase(),revision:f.account.revision+1});
      if(!background)f.nativePhone='';result=f.account;
    } else if(m.method==='officialLogout'){
      f.account={...accountBase(),revision:f.account.revision+1};f.nativePhone='';f.library.history=JSON.parse(JSON.stringify(f.guest));result=f.account;
    } else if(m.method==='officialImportGuest'){
      f.account=fresh({sync:{...f.account.sync,pendingCount:3}});result={...f.account,importResult:{importedCount:3,historyImportedCount:2,favoritesImportedCount:1,skippedCount:3,historySkippedCount:2,favoritesSkippedCount:1,remainingCount:4,historyRemainingCount:3,favoritesRemainingCount:1}};
    } else if(m.method==='officialSync'){
      f.account=fresh({sync:{...f.account.sync,syncing:true}});result=f.account;
      setTimeout(()=>{f.account=fresh({sync:{syncing:false,pendingCount:0,lastSyncAt:Date.now(),error:''}});window.__androidAccountChanged?.();},100);
    }
    const beginRevision=f.account.revision;
    setTimeout(()=>{
      if(m.method!=='getAccount'&&m.method!=='officialCancelLogin'&&m.method!=='officialSync'&&!error&&f.account.revision===beginRevision)f.account=JSON.parse(JSON.stringify(result));
      window.__androidResponse({id:m.id,...(error?{error}:{result:JSON.parse(JSON.stringify(result))})});
      if(!error&&['officialLoginSMS','officialVerifyMFA','officialLogout'].includes(m.method)&&f.account.revision===result.revision)window.__androidAccountChanged({owner:f.owner()});
    },delay);
  };
  f.accountPlans = []; f.loginPlans = []; f.logoutPlans = []; f.syncPlans = []; f.importPlans = [];
  const scenario = new URL(location.href).searchParams.get('scenario');
  if (scenario === 'cap-current') entries['7000000000000000099'] = { seriesId: '7000000000000000099', title: '合成：山海：第99季', episodeCount: 6, episodes: Array.from({ length: 6 }, (_, i) => ({ vid: String(8000000000000990000n + BigInt(i)), index: i + 1 })), description: '合成的第99季，用于验证分页不丢失当前季。' };
  if (scenario === 'identical-editions') { entries[ids[4]].episodeCount = 100; entries[ids[4]].episodes = entries[ids[4]].episodes.slice(0, 100); }
  if (scenario === 'invalid-title') { entries[ids[6]].title = '合成超长标题'.repeat(40) + '第一季'; entries[ids[6]].description = '自动分季查找失败仍允许原有详情和播放。'; }
  if (scenario === 'lookup-error') f.searchPlans.push({ error: 'MOCK_SEASON_SEARCH_FAILED' });
  if (scenario === 'lookup-delay') f.searchPlans.push({ delay: 500 });
  if (scenario === 'lookup-hang') f.searchPlans.push({ delay: 10000 });
  if (scenario === 'oversized') { first.episodeCount=4000;first.episodes=Array.from({length:4000},(_,i)=>({vid:String(8000000000000000000n+BigInt(i)),index:i+1})); }
  if (scenario === 'nine') { f.nineItems = Array.from({length:9},(_,n)=>{const id=ids[n]||'7000000000000000009';const value={seriesId:id,title:`合成：山海：第${n+1}季`,episodeCount:100,episodes:Array.from({length:100},(_,i)=>({vid:String(8000000000000000000n+BigInt(n)*10000n+BigInt(i)),index:i+1})),description:'九季目录测试'}; if(entries[id])Object.assign(entries[id],value);else entries[id]=value;return entries[id];}); }
  const raw = item => ({ series_id: item.seriesId, series_name: item.title, series_intro: item.description,
    episode_cnt: item.episodeCount, accessible_episode_cnt: 3, vid_list: item.episodes.map(e => e.vid) });
  const pageHtml = loaderData => `<script>window._ROUTER_DATA=${JSON.stringify({ loaderData })};</script>`;
  const searchResponse = (keyword, offset) => {
    let items, more;
    if (scenario === 'nine') { items=f.nineItems;more=false; } else if (scenario === 'cap-current') {
      items = offset === 0 ? [first, entries['7000000000000000099']] : Array.from({ length: 39 }, (_, n) => ({ seriesId: String(7200000000000000000n + BigInt(n)), title: `合成：山海：第${n + 2}季`, episodeCount: n + 2 }));
      more = offset === 0;
    } else if (scenario === 'capacity') {
      items = Array.from({ length: offset === 0 ? 25 : 20 }, (_, n) => {
        const number = (offset === 0 ? 1 : 26) + n;
        return { seriesId: number === 1 ? ids[0] : String(7100000000000000000n + BigInt(number)), title: `合成：山海：第${number}季`, episodeCount: 10 + number };
      });
      more = true;
    } else if (keyword === '另一故事') { items = [entries[ids[7]]]; more = false; }
    else if (keyword === '独立故事') { items = [entries[ids[6]]]; more = false; }
    else if (offset === 0) { items = [entries[ids[0]], entries[ids[1]], entries[ids[3]], entries[ids[5]], entries[ids[7]]]; more = true; }
    else { items = [entries[ids[0]], entries[ids[2]], entries[ids[4]]]; more = scenario === 'expires' && offset === 20; }
    return { code: 0, search_tabs: [{ tab_type: 11, has_more: more, next_offset: offset + 20,
      search_id: 'synthetic-search', passback: `synthetic-${offset + 20}`,
      data: items.map(item => ({ video_data: [{ series_id: item.seriesId, title: item.title, episode_cnt: item.episodeCount }] })) }] };
  };
  f.updateCalls = []; f.updateError = '';
  f.update = {state:'idle',message:'尚未检查更新',busy:false,installedCode:33,installedVersion:'FIXTURE-0.12.10',flavor:new URL(location.href).searchParams.get('tv')==='1'?'tv':'phone',versionCode:0};
  window.AndroidUpdates = {postMessage(rawMessage) {
    const m=JSON.parse(rawMessage);f.updateCalls.push(m.method);
    let error=f.updateError;f.updateError='';
    if(!error&&m.method==='check')Object.assign(f.update,{state:'available',message:'发现新版本',versionCode:34,versionName:'FIXTURE-0.12.11',notes:'合成更新说明',busy:false});
    if(!error&&m.method==='download')Object.assign(f.update,{state:'downloading',message:'正在下载',downloaded:40,size:100,busy:true});
    if(!error&&m.method==='cancel')Object.assign(f.update,{state:'available',message:'下载已取消',busy:false});
    if(!error&&m.method==='install')Object.assign(f.update,{state:'installing',message:'等待系统确认安装',busy:false});
    setTimeout(()=>window.__androidUpdatesResponse({id:m.id,...(error?{error}:{result:JSON.parse(JSON.stringify(f.update))})}),1);
  }};
  window.AndroidBridge = { postMessage(rawMessage) {
    const m = JSON.parse(rawMessage); f.calls.push(m);
    if(m.method==='getAccount'||m.method.startsWith('official')) { f.accountRpc(m); return; }
    let result, error, delay = 1, plan = {};
    if (m.method === 'getInfo') result = { isTv: new URL(location.href).searchParams.get('tv') === '1', version: 'MOCK-seasons-ui' };
    else if (m.method === 'getAccount') { plan = f.accountPlans.shift() || {}; result = JSON.parse(JSON.stringify(f.account)); }
    else if (m.method === 'accountLogin') { plan = f.loginPlans.shift() || {}; result = null; }
    else if (m.method === 'accountLogout') { plan = f.logoutPlans.shift() || {}; result = null; }
    else if (m.method === 'importGuestHistory') { plan = f.importPlans.shift() || {}; result = null; }
    else if (m.method === 'syncHistory') { plan = f.syncPlans.shift() || {}; result = null; }
    else if (m.method === 'getLibrary') { plan = f.libraryPlans.shift() || {}; result = JSON.parse(JSON.stringify({...f.library,owner:f.owner()})); }
    else if (m.method === 'getSeriesHistory') { result = {owner:f.owner(),history:JSON.parse(JSON.stringify(f.library.history.find(row=>row.seriesId===m.params.seriesId)||null))}; }
    else if (m.method === 'httpGet') {
      const u = new URL(m.params.url);
      if (u.pathname.startsWith('/category/')) result = { status: 200, body: pageHtml({ catalog: { isSuccess: true, categoryRoute: { contentType: u.pathname.split('/')[2] }, recommendList: [first, entries[ids[6]], entries[ids[7]]].map(raw), pagination: { pageNum: 1, totalPages: 1, total: 3 } } }) };
      else if (/^\/player\/\d+$/.test(u.pathname)) {
        const sid = u.pathname.split('/')[2], item = entries[sid];
        f.detailReads.push(sid); plan = f.detailPlans[sid]?.shift() || {};
        result = item ? { status: 200, body: pageHtml({ 'player_(series_id)/page': { series_id: sid, seriesDetail: raw(item) } }) } : { status: 404, body: '' };
      } else { error = 'UNEXPECTED_HTTP_' + u.pathname; f.notes.push(error); }
    } else if (m.method === 'searchQuery') { f.searches.push(m.params); plan = f.searchPlans.shift() || {}; result = searchResponse(m.params.keyword, m.params.offset); }
    else if (m.method === 'getPlaybackOptions') { plan = f.optionPlans.shift() || {}; result = plan.options || { ...f.options }; }
    else if (m.method === 'setPlaybackOptions') { f.options = { ...f.options, ...m.params }; result = { ...f.options }; }
    else if (m.method === 'play') { plan = f.playPlans.shift() || {}; result = { started: true }; }
    else if (m.method === 'download') { plan = f.downloadPlans.shift() || {}; result = { id: 1 }; }
    else if (m.method === 'getDownloads') result = [];
    else if (m.method === 'getPlaybackCache') result = { files: 0, bytes: 0 };
    else if (m.method === 'getSearchHistory') result = { keywords: [...f.searchKeywords] };
    else if (m.method === 'addSearchHistory') { f.searchKeywords = [m.params.keyword, ...f.searchKeywords.filter(x => x !== m.params.keyword)].slice(0, 20); result = { keywords: [...f.searchKeywords] }; }
    else if (m.method === 'toggleFavorite') { f.library.favorites = [m.params.series]; result = f.library; }
    else { error = 'UNEXPECTED_RPC_' + m.method; f.notes.push(error); }
    delay = plan.delay || delay; error = plan.error || error;
    setTimeout(() => {
      if (m.method === 'play' && !error) {
        f.library.history = [{ ...m.params.series, ...m.params.episode, positionMs: 1234, updatedAt: Date.now() }, ...f.library.history.filter(x => x.seriesId !== m.params.series.seriesId)];
      }
      if (!error && m.method === 'accountLogin') {
        f.account = { ...f.account, serverUrl: m.params.serverUrl, authenticated: true, user: { id: m.params.username, username: m.params.username }, syncing: false, lastSyncAt: 1000, pendingCount: 0, error: '', loginRequired: false };
        f.library.history = [{ ...compact(first), ...first.episodes[3], title: m.params.username + ' 账号记录', positionMs: 42000, updatedAt: 1000 }];
        result = JSON.parse(JSON.stringify(f.account)); window.__androidAccountChanged?.();
      }
      if (!error && m.method === 'accountLogout') { f.account = { ...f.account, authenticated: false, user: null, lastSyncAt: 0 }; f.library.history = JSON.parse(JSON.stringify(f.guest)); result = JSON.parse(JSON.stringify(f.account)); window.__androidAccountChanged?.(); }
      if (!error && m.method === 'importGuestHistory') {
        const imported = f.guest.filter(row => !f.library.history.some(existing => existing.seriesId === row.seriesId));
        f.library.history.push(...JSON.parse(JSON.stringify(imported)));
        result = { ...f.account, importedCount: imported.length, skippedCount: f.guest.length - imported.length };
        window.__androidAccountChanged?.();
      }
      if (!error && m.method === 'syncHistory') { Object.assign(f.account, { ...plan.account, lastSyncAt: Date.now() }); if (plan.positionMs) f.library.history[0].positionMs = plan.positionMs; result = JSON.parse(JSON.stringify(f.account)); window.__androidAccountChanged?.(); }
      window.__androidResponse({ id: m.id, ...(error ? { error } : { result }) });
      if (m.method === 'play' && !error) window.dispatchEvent(new Event('androidresume'));
    }, delay);
  } };
}
fs.mkdirSync(output,{recursive:true});
const temporary=fs.mkdtempSync(path.join(os.tmpdir(),'hongguo-seasons-ui-'));
const report={kind:'real React App + real bridge, MOCK native responses with real source/search parsers; not device or stream QA',root,tests:[],screenshots:[],externalRequests:[],pageErrors:[]};
report.sourceSha256=Object.fromEntries(['src/android/App.jsx','src/android/AccountPanel.jsx','src/android/account.mjs','src/android/bridge.mjs','src/android/style.css'].map(file=>[file,createHash('sha256').update(fs.readFileSync(path.join(root,file))).digest('hex')]));
let chrome, server, ws, cdp;
const sleep=ms=>new Promise(resolve=>setTimeout(resolve,ms));
let passed=0,failed=0,checks=0;
const check=(value,message)=>{checks++;assert.ok(value,message);};
const eq=(a,b,message)=>{checks++;assert.deepEqual(a,b,message);};
try {
  const bundled=await build({stdin:{contents:`import React from 'react';import {createRoot} from 'react-dom/client';import App from ${JSON.stringify(path.join(root,'src/android/App.jsx'))};createRoot(document.getElementById('root')).render(React.createElement(App));`,resolveDir:ownRoot,sourcefile:'mobile-ui-fixture-entry.jsx',loader:'jsx'},bundle:true,write:false,format:'iife',platform:'browser',target:'chrome100',nodePaths:[path.join(ownRoot,'node_modules')],plugins:[{name:'mock-sources-only',setup(build){build.onResolve({filter:/generated\/mac-app-source\.mjs$/},()=>({path:'protocol',namespace:'fixture-protocol'}));build.onLoad({filter:/.*/,namespace:'fixture-protocol'},()=>({contents:`export const fetchAppPlayUrl=async(vid,options)=>{const f=window.__seasonFixture;f.resolves.push({vid,...options});const plan=f.resolvePlans.shift()||{};if(plan.delay)await new Promise(r=>setTimeout(r,plan.delay));if(plan.error)throw Error(plan.error);return {seriesId:options.seriesId,vid,url:'https://media.cdnfixture.net/never-requested.mp4',source:'app',width:1080,height:1920,requestedQuality:options.quality};};`,loader:'js'}));build.onLoad({filter:/LICENSE\?raw$/},()=>({contents:'export default "MOCK LICENSE";',loader:'js'}));build.onResolve({filter:/LICENSE\?raw$/},()=>({path:'LICENSE?raw',namespace:'fixture-license'}));build.onLoad({filter:/.*/,namespace:'fixture-license'},()=>({contents:'export default "MOCK LICENSE";',loader:'js'}));}}]});
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
  const group=async(name,fn)=>{try{await fn();passed++;report.tests.push({name,result:'PASS'});console.log('PASS '+name);}catch(error){failed++;report.tests.push({name,result:'FAIL',error:error.message});console.log('FAIL '+name+': '+error.message);}};
  const q = value => JSON.stringify(value);
  const selector = id => `.season-selector button[data-season-id="${id}"]`;
  const plays = () => evaluate('window.__seasonFixture.calls.filter(x=>x.method==="play").map(x=>x.params)');
  const currentId = () => evaluate('document.querySelector(".season-selector button[aria-selected=true]")?.dataset.seasonId');
  const currentTitle = () => evaluate('document.querySelector(".detail-copy h1")?.textContent');
  const selectedEpisode = () => evaluate('Number(document.querySelector(".episode-grid button[aria-pressed=true] span")?.textContent)');
  const idle = () => wait('document.querySelector(".play-actions .primary")&&!document.querySelector(".play-actions .primary").disabled');
  const ready = async id => {
    await wait(`document.querySelector('.detail-copy h1')?.textContent===window.__seasonFixture.entries[${q(id)}].title&&document.querySelector('.episode-grid button')&&document.querySelector('select[aria-label="清晰度"]').value==='1080p'`, `loaded season ${id}`);
    await sleep(25);
  };
  const choose = async id => { await wait(`document.querySelector(${q(selector(id))})`); await evaluate(`document.querySelector(${q(selector(id))}).click()`); };
  const clickEpisode = index => evaluate(`(()=>{const b=[...document.querySelectorAll('.episode-grid button')].find(b=>Number(b.querySelector('span').textContent)===${index});if(!b)throw Error('missing episode ${index}');b.click()})()`);
  const button = (label, scope='document') => evaluate(`(()=>{const b=[...${scope}.querySelectorAll('button')].find(x=>x.textContent.trim()===${q(label)});if(!b)throw Error('missing button '+${q(label)});b.click()})()`);
  const nav = label => evaluate(`[...document.querySelectorAll('.app-nav button')].find(b=>b.textContent.replace(/\\s/g,'')===${q(label)}).click()`);
  const key = async (name, code) => { await call('Input.dispatchKeyEvent',{type:'keyDown',key:name,code:name,windowsVirtualKeyCode:code}); await call('Input.dispatchKeyEvent',{type:'keyUp',key:name,code:name,windowsVirtualKeyCode:code}); };
  const reset = async ({tv=false,width=390,height=844,large=false,scenario='',open=true,index=0}={}) => {
    await call('Emulation.setDeviceMetricsOverride',{width:tv?1280:width,height:tv?720:height,deviceScaleFactor:1,mobile:!tv});
    await call('Emulation.setTouchEmulationEnabled',{enabled:!tv});
    await call('Page.navigate',{url:base+`?tv=${tv?1:0}&scenario=${encodeURIComponent(scenario)}`});
    await wait('document.querySelectorAll(".poster").length===3&&window.__seasonFixture.calls.some(c=>c.method==="getLibrary")');
    await sleep(20);
    if(large)await evaluate('document.documentElement.style.fontSize="24px"');
    if(open){await evaluate(`document.querySelectorAll('.poster')[${index}].click()`);await ready(SID[index===0?0:index===1?6:7]);}
    await evaluate('window.__seasonFixture.calls=[];window.__seasonFixture.resolves=[];');
  };
  const more = async () => { await wait('document.querySelector("[data-season-action=more]")&&!document.querySelector("[data-season-action=more]").disabled');await evaluate('document.querySelector("[data-season-action=more]").click()'); };
  const screenshot = async name => { const file=path.join(output,name);fs.writeFileSync(file,Buffer.from((await call('Page.captureScreenshot',{format:'png',captureBeyondViewport:false})).data,'base64'));report.screenshots.push(file); };
  const noOverflow = async () => {
    const sizes=await evaluate(`(()=>{const nodes=[document.documentElement,document.querySelector('.main-content'),document.querySelector('.detail-scroll'),document.querySelector('.season-selector')].filter(Boolean);return nodes.map(e=>({class:e.className||'html',width:e.clientWidth,scroll:e.scrollWidth}));})()`);
    check(sizes.every(s=>s.scroll<=s.width+1),'no horizontal overflow '+JSON.stringify(sizes));
  };
  const seasonTargets = async tv => {
    const sizes=await evaluate(`[...document.querySelectorAll('.season-selector button')].filter(e=>e.getClientRects().length).map(e=>({title:e.textContent,w:e.getBoundingClientRect().width,h:e.getBoundingClientRect().height}))`);
    check(sizes.length>0,'season controls rendered');
    check(sizes.every(s=>s.w>=(tv?53.5:43.5)&&s.h>=(tv?53.5:43.5)),`season targets minimum ${tv?54:44}px `+JSON.stringify(sizes));
  };
  const dockVisible = async () => {
    const p=await evaluate(`(()=>{const r=e=>e.getBoundingClientRect().toJSON();return {dock:r(document.querySelector('.play-actions')),nav:r(document.querySelector('.app-nav')),buttons:[...document.querySelectorAll('.play-actions button')].map(r),height:innerHeight,width:innerWidth};})()`);
    check(p.dock.top>=0&&p.dock.bottom<=p.nav.top+1,'action dock above mobile navigation');
    check(p.buttons.every(r=>r.top>=0&&r.bottom<=p.nav.top+1&&r.left>=0&&r.right<=p.width),'play/download actions remain reachable');
  };
  await call('Page.enable');await call('Runtime.enable');await call('Network.enable');
  // Block anything outside this temporary server before Chrome can load it.
  await call('Fetch.enable',{patterns:[{urlPattern:'*'}]});
  ws.addEventListener('message',({data})=>{const m=JSON.parse(data);if(m.method!=='Fetch.requestPaused')return;const url=m.params.request.url;const allowed=url.startsWith(base)||url.startsWith('data:');call(allowed?'Fetch.continueRequest':'Fetch.failRequest',{requestId:m.params.requestId,...(allowed?{}:{errorReason:'BlockedByClient'})}).catch(error=>report.pageErrors.push(error.message));});
  await call('Page.addScriptToEvaluateOnNewDocument',{source:`(${installMock.toString()})();`});


  const accountReady = () => wait('document.querySelector(".account-status")&&!document.querySelector(".account-status").textContent.includes("正在读取")');
  const about = async () => { await nav('关于'); await accountReady(); };
  const field = (id, value) => evaluate(`(()=>{const e=document.getElementById(${q(id)});if(!e)throw Error('missing '+${q(id)});Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set.call(e,${q(value)});e.dispatchEvent(new Event('input',{bubbles:true}));})()`);
  const accountCalls = method => evaluate(`window.__seasonFixture.calls.filter(c=>c.method===${q(method)}).map(c=>c.params)`);
  const retiredCalls = () => evaluate('window.__seasonFixture.calls.filter(c=>["accountLogin","accountLogout","syncHistory","importGuestHistory"].includes(c.method))');
  const sendCode = async () => {await field('official-phone','13800000000');await button('获取短信验证码');await wait('document.querySelector("#official-code")&&!document.querySelector("#official-code").disabled');};
  const login = async () => {await field('official-code','001234');await button('登录红果');await wait('document.querySelector(".account-details")&&document.querySelector(".account-status").textContent.includes("已登录红果")');};
  const visibility = state => evaluate(`Object.defineProperty(document,'visibilityState',{configurable:true,value:${q(state)}});document.dispatchEvent(new Event('visibilitychange'))`);
  await group('PHONE official SMS form has labels, no retired server controls, and no automatic send', async () => {
    for(const width of [320,390]){
      await reset({width,large:true,open:false});await about();
      eq(await accountCalls('officialSendCode'),[],'opening page never sends SMS');
      eq(await evaluate('document.querySelectorAll("#account-server,#account-password,input[type=password]").length'),0,'retired credentials absent');
      check(await evaluate('document.querySelector("label[for=official-phone]").textContent.includes("+86")'),'mainland phone labelled');
      const sizes=await evaluate('[...document.querySelectorAll(".account-section input,.account-section button")].map(e=>({w:e.getBoundingClientRect().width,h:e.getBoundingClientRect().height}))');check(sizes.every(r=>r.w>=44&&r.h>=44),'touch targets at least44px');
      await noOverflow();await screenshot(`phone-${width}-official-phone.png`);
    }
  });
  await group('SEND confirms masked phone and cooldown; OTP clears immediately, errors retain native phone', async () => {
    await reset({open:false});await about();await sendCode();
    eq(await accountCalls('officialSendCode'),[{phone:'13800000000'}],'one explicit SMS request');
    check(await evaluate('document.querySelector(".account-status").textContent.includes("138****0000")'),'only masked phone returned');
    check(await evaluate('!document.querySelector("#official-phone")'),'full phone field dropped after sending');
    await evaluate('window.__seasonFixture.loginMode="wrong";window.__seasonFixture.loginDelay=150');await field('official-code','001234');await button('登录红果');
    eq(await evaluate('document.querySelector("#official-code").value'),'','submitted OTP cleared before response');
    await wait('document.querySelector(".account-error")?.textContent.includes("验证码错误")');
    eq(await evaluate('document.querySelector("#official-code").value'),'','wrong OTP never restored');
    eq(await accountCalls('officialLoginSMS'),[{code:'001234'}],'OTP exact including leading zeros, no repeated full phone');
    await evaluate('window.__seasonFixture.loginMode="success";window.__seasonFixture.loginDelay=1');await login();
    check(await evaluate('!document.querySelector("#official-code")'),'OTP form destroyed on success');
  });
  await group('UNCERTAIN send accepts a received OTP without another SMS request or false sent claim', async () => {
    await reset({open:false});await about();await evaluate('window.__seasonFixture.sendMode="uncertain"');
    await field('official-phone','13800000000');await button('获取短信验证码');
    await wait('document.querySelector("#official-code")&&!document.querySelector("#official-code").disabled','uncertain delivery exposes received-code entry');
    check(await evaluate('document.querySelector(".account-status").textContent.includes("尚未确认")&&!document.querySelector(".account-status").textContent.includes("验证码已发送")'),'uncertainty never announced as successful send');
    await wait('document.activeElement.id==="official-code"','uncertain recovery focuses received-code entry');
    check(await evaluate('document.querySelector(".account-section").textContent.includes("已收到短信")'),'recovery instruction explains received SMS');
    check(await evaluate('[...document.querySelectorAll(".account-form button")].some(b=>b.disabled&&b.textContent.includes("秒后可重发"))'),'uncertain request preserves resend cooldown');
    eq((await accountCalls('officialSendCode')).length,1,'received code entry sends no second SMS');await screenshot('phone-uncertain-received-code.png');
    await field('official-code','001234');await button('登录红果');await wait('document.querySelector(".account-status").textContent.includes("已登录红果")');
    eq(await accountCalls('officialLoginSMS'),[{code:'001234'}],'uncertain recovery submits exact code using native phone');
    eq((await accountCalls('officialSendCode')).length,1,'successful recovery still has only one SMS send');
    await reset({open:false});await about();await evaluate('var f=window.__seasonFixture;f.nativePhone="13800000000";f.account={...f.account,phase:"phone",smsState:"uncertain",maskedPhone:"138****0000",retryAfter:0,revision:f.account.revision+1};window.__androidAccountChanged()');
    await wait('document.querySelector("#official-code")','uncertain recovery still available after cooldown');
    check(await evaluate('[...document.querySelectorAll(".account-section button")].some(b=>b.textContent==="换手机号 / 取消")'),'expired uncertain cooldown still permits changing phone');
    await button('换手机号 / 取消');await wait('document.querySelector("#official-phone")&&!document.querySelector("#official-code")');eq(await accountCalls('officialSendCode'),[],'changing phone never silently resends SMS');
  });
  await group('MFA displays upstream SMS instructions, waits for user, survives SMS-app background round trip', async () => {
    await reset({open:false});await about();await sendCode();await evaluate('window.__seasonFixture.loginMode="mfa"');await field('official-code','123456');await button('登录红果');
    await wait('document.querySelector("[aria-label=红果追加短信验证]")');
    eq(await accountCalls('officialVerifyMFA'),[],'challenge does not auto query before user sends');
    check(await evaluate('document.querySelector("[aria-label=红果追加短信验证]").textContent.includes("106900000001")&&document.querySelector("[aria-label=红果追加短信验证]").textContent.includes("SYNTHETIC MESSAGE")'),'server number and content displayed');
    await visibility('hidden');await sleep(30);await visibility('visible');await sleep(50);
    check(await evaluate('!!document.querySelector("[aria-label=红果追加短信验证]")'),'challenge survives switching to SMS app');
    check((await accountCalls('officialCancelLogin')).some(x=>x.reason==='background'),'background uses suspend reason');
    await button('我已发送短信，开始验证');await wait('document.querySelector(".account-status").textContent.includes("已登录红果")');
    eq((await accountCalls('officialVerifyMFA')).length,1,'one user-confirmed verification');await screenshot('phone-official-signed-in.png');
  });
  await group('MFA in-flight cancellation bypasses busy UI and late login reply cannot revive account', async () => {
    await reset({open:false});await about();await sendCode();await evaluate('window.__seasonFixture.loginMode="mfa"');await field('official-code','123456');await button('登录红果');await wait('document.querySelector("[aria-label=红果追加短信验证]")');
    await evaluate('window.__seasonFixture.verifyDelay=350');await button('我已发送短信，开始验证');
    await wait('document.querySelector(".account-status").textContent.includes("90秒")');await button('取消登录');
    await wait('document.querySelector("#official-phone")&&!document.querySelector("#official-phone").disabled');await sleep(400);
    check(await evaluate('!!document.querySelector("#official-phone")&&!document.querySelector(".account-section").textContent.includes("合成官方账号")'),'late authenticated reply ignored');
    eq(await evaluate('document.querySelector("#official-phone").value'),'','explicit cancellation clears phone');
  });
  await group('SYNC and guest import require explicit action; logout keeps original local library', async () => {
    await reset({open:false});await about();await sendCode();await login();
    eq(await accountCalls('officialImportGuest'),[],'login does not auto import');
    await button('导入本机记录');await wait('document.querySelector("[aria-label=确认导入本机记录]")');await button('取消');eq(await accountCalls('officialImportGuest'),[],'cancel import writes nothing');
    await button('导入本机记录');await wait('document.querySelector("[aria-label=确认导入本机记录]")');await button('确认导入');await sleep(40);eq((await accountCalls('officialImportGuest')).length,1,'one confirmed import');
    check(await evaluate('document.querySelector(".account-import-result")?.textContent.includes("收藏 1 条")&&document.querySelector(".account-import-result").textContent.includes("观看记录 2 条")&&document.querySelector(".account-import-result").textContent.includes("4 条")'),'import reports actual favorite/history counts and remaining retryable records');
    await sleep(1050);check(await evaluate('document.querySelector(".account-import-result")?.textContent.includes("收藏 1 条")'),'status polling does not erase acknowledged import result');await screenshot('phone-guest-import-counts.png');
    await button('立即同步');await wait('document.querySelector(".account-status").textContent.includes("正在同步")');await wait('document.querySelector(".account-status").textContent.includes("最近同步")');
    await button('退出红果账号');await wait('document.querySelector("[aria-label=确认退出红果账号]")');await button('确认退出');await wait('document.querySelector("#official-phone")');
    eq(await evaluate('JSON.stringify(window.__seasonFixture.library.history)'),await evaluate('JSON.stringify(window.__seasonFixture.guest)'),'original guest history not deleted');
  });
  await group('TV official phone/code/confirm focus and remote actions remain accessible', async () => {
    await reset({tv:true,open:false});await about();await field('official-phone','13800000000');
    await evaluate('document.getElementById("official-phone").focus()');await key('ArrowDown',40);eq(await evaluate('document.activeElement.textContent'),'获取短信验证码','D-pad reaches send');await key('Enter',13);
    await wait('document.activeElement.id==="official-code"');await field('official-code','123456');await key('ArrowDown',40);eq(await evaluate('document.activeElement.textContent'),'登录红果','D-pad reaches login');await key('Enter',13);
    await wait('document.querySelector(".account-status").textContent.includes("已登录红果")');await noOverflow();await screenshot('tv-official-signed-in.png');
  });
  await group('AUTOMATIC owner change clears old favorites and history even when new library read fails', async () => {
    await reset({open:false});
    await evaluate('var f=window.__seasonFixture;f.account={...f.account,authenticated:true,phase:"logged_in",fixtureOwner:"a",revision:f.account.revision+1,user:{name:"旧账号"}};f.library.favorites=[{...f.entries[f.ids[0]],title:"旧账号私人收藏"}];f.library.history=[{...f.entries[f.ids[1]],...f.entries[f.ids[1]].episodes[1],positionMs:1234,title:"旧账号私人历史"}];window.__androidAccountChanged({owner:f.owner()})');
    await nav('我的追剧');await wait('document.querySelector(".poster strong")?.textContent==="旧账号私人收藏"');
    await evaluate('window.__seasonFixture.libraryPlans=[{error:"SAME_OWNER_OFFLINE"}];window.__androidAccountChanged({owner:window.__seasonFixture.owner()})');await wait('document.querySelector(".error-state")?.textContent.includes("SAME_OWNER_OFFLINE")');
    check(await evaluate('document.querySelector(".poster strong").textContent==="旧账号私人收藏"'),'same-owner failure retains visible favorites');
    await evaluate('[...document.querySelectorAll(".section-tabs button")].find(e=>e.textContent.includes("观看历史")).click()');
    check(await evaluate('document.querySelector(".poster strong").textContent==="旧账号私人历史"'),'same-owner failure retains visible history');
    await evaluate('var f=window.__seasonFixture;f.account={...f.account,authenticated:true,phase:"logged_in",fixtureOwner:"b",revision:f.account.revision+1,user:{name:"新账号"}};f.libraryPlans=[{error:"NEW_OWNER_OFFLINE"}];window.__androidAccountChanged({owner:f.owner()})');
    await wait('document.querySelector(".error-state")?.textContent.includes("NEW_OWNER_OFFLINE")');
    eq(await evaluate('document.querySelectorAll(".poster").length'),0,'new-owner read failure never displays previous history');
    check(await evaluate('!document.querySelector(".main-content").textContent.includes("旧账号私人历史")&&!document.querySelector(".main-content").textContent.includes("旧账号私人收藏")'),'both prior owner texts removed without panel changing event');
    check(await evaluate('[...document.querySelectorAll(".section-tabs button")].every(e=>e.textContent.includes("(0)"))'),'favorites and history counts both cleared');
    await evaluate('[...document.querySelectorAll(".section-tabs button")].find(e=>e.textContent.includes("追剧")).click()');
    eq(await evaluate('document.querySelectorAll(".poster").length'),0,'old favorites stay cleared after tab switch');
  });
  await group('POLL coalesces slow account reads and clears a recovered read error', async () => {
    await reset({open:false});await about();
    await evaluate('var f=window.__seasonFixture;f.calls=[];f.accountPlans=[{delay:1400,error:"TEMPORARY_ACCOUNT_READ"}];for(let i=0;i<6;i++)window.__androidResume()');
    await sleep(1100);eq((await accountCalls('getAccount')).length,1,'only one status RPC remains in flight across events and periodic tick');
    await wait('document.querySelector(".account-error")?.textContent.includes("TEMPORARY_ACCOUNT_READ")','read failure is explicit');
    await wait('!document.querySelector(".account-error")','successful retry clears only recovered read error');
    check(await evaluate('!!document.querySelector("#official-phone")'),'recovery keeps usable login form');
  });
  await group('NO tokens, OTP, phone or old server credential persisted to browser storage', async () => {
    await reset({open:false});await about();await sendCode();await login();
    const storage=await evaluate('JSON.stringify({local:localStorage,session:sessionStorage})');for(const secret of ['13800000000','001234','token','sessionid','PRIVATE'])check(!storage.includes(secret),'browser storage excludes '+secret);
    eq(await retiredCalls(),[],'no self-hosted operation remains');
  });
  await group('COMBINED update download/cancel/install focus and system return do not steal other-section focus', async () => {
    for(const tv of [false,true]) {
      await reset({tv,open:false});await about();
      await wait('document.querySelector("[aria-labelledby=updates-title]")?.textContent.includes("FIXTURE-0.12.10")');
      await evaluate('document.querySelector("[aria-labelledby=updates-title] button").focus()');
      await button('检查更新');await wait('document.activeElement.textContent==="下载更新"');
      check(await evaluate('document.activeElement.closest("[aria-labelledby=updates-title]")!==null'),'check result keeps focus in updates');
      await button('下载更新');await wait('document.querySelector("progress")?.value===40');
      eq(await evaluate('document.querySelector("progress").getAttribute("aria-label")'),'更新下载进度','download progress named');
      await wait('document.activeElement.textContent==="取消"');
      check(await evaluate('!document.activeElement.disabled'),'download moves focus to enabled cancel after removed download button');
      await button('取消');await wait('!document.querySelector("progress")');
      await wait('document.activeElement.textContent==="下载更新"');
      check(await evaluate('!document.activeElement.disabled'),'cancel moves focus back to enabled download');
      await evaluate('Object.assign(window.__seasonFixture.update,{state:"ready",message:"安装包已校验",busy:false});window.__androidUpdatesChanged({...window.__seasonFixture.update})');
      await wait('document.activeElement.textContent==="安装更新"');
      if(tv) await key('Enter',13); else await button('安装更新');
      await wait('window.__seasonFixture.update.state==="installing"&&document.querySelector("[aria-labelledby=updates-title]").textContent.includes("等待系统确认安装")');
      eq(await evaluate('[...document.querySelectorAll("[aria-labelledby=updates-title] button")].filter(e=>e.textContent==="安装更新").length'),0,'install button is unmounted during real native installing state');
      await wait('document.activeElement.textContent==="检查更新"');
      check(await evaluate('!document.activeElement.disabled'),'install handoff restores enabled check focus, not body');
      check(await evaluate('!document.querySelector("[aria-labelledby=updates-title]").textContent.includes("安装成功")'),'installer handoff not called success');
      // A system installer may temporarily leave WebView with body focus. Its
      // cancellation response must restore the originating installation action.
      eq(await evaluate('document.activeElement.blur();document.activeElement===document.body'),true,'fixture simulates system installer returning with body focus');
      await evaluate('Object.assign(window.__seasonFixture.update,{state:"ready",message:"安装未完成，可以重试",busy:false});window.__androidUpdatesChanged({...window.__seasonFixture.update})');
      await wait('document.activeElement.textContent==="安装更新"');
      check(await evaluate('document.activeElement.closest("[aria-labelledby=updates-title]")!==null&&!document.activeElement.disabled'),'system cancellation returns focus to enabled install');
      // After a deliberate user move to another section, asynchronous installer
      // state changes must not pull focus back to the update controls.
      await evaluate('document.querySelector(".account-section input,.account-section button").focus();Object.assign(window.__seasonFixture.update,{state:"installing",message:"等待系统确认安装",busy:false});window.__androidUpdatesChanged({...window.__seasonFixture.update})');
      await sleep(50);
      check(await evaluate('document.activeElement.closest(".account-section")!==null'),'installing notification respects user focus in account section');
      await evaluate('Object.assign(window.__seasonFixture.update,{state:"ready",message:"安装未完成，可以重试",busy:false});window.__androidUpdatesChanged({...window.__seasonFixture.update})');
      await sleep(50);
      check(await evaluate('document.activeElement.closest(".account-section")!==null'),'ready notification never steals explicit other-section focus');
      eq(await retiredCalls(),[],'updates do not invoke retired account');
      await noOverflow();await screenshot(tv?'tv-combined-account-update.png':'phone-combined-account-update.png');
    }
  });
  await group('ISOLATION no browser network, exceptions or unexpected native calls', async () => {
    eq(report.externalRequests,[],'zero external requests');eq(report.pageErrors,[],'zero browser exceptions');eq(await evaluate('window.__seasonFixture.notes'),[],'zero unexpected RPCs');
  });
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
  console.log(`ANDROID_OFFICIAL_ACCOUNT_UI_${failed?'FAILED':'OK'} groupsPassed=${passed} groupsFailed=${failed} assertions=${checks} networkRequests=${report.externalRequests.length} deviceTests=not_run`);
  console.log('MOCK_UI_CLEANUP ownServer=stopped ownChrome=stopped ownProfile=removed adb=not_used emulator=not_used');
}
process.exitCode=failed?1:0;
