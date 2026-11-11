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
const ownRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
let tabsOnly = false;
let root = ownRoot, output = path.join(ownRoot, 'verification/android-seasons-0121-20261021/ui');
for (let i = 2; i < process.argv.length; i++) {
  if (process.argv[i] === '--tabs-only') tabsOnly = true;
  else if (process.argv[i] === '--root' && process.argv[i + 1]) root = path.resolve(process.argv[++i]);
  else if (process.argv[i] === '--output' && process.argv[i + 1]) output = path.resolve(process.argv[++i]);
  else throw Error('Usage: node scripts/check-android-seasons-ui.mjs [--root PROJECT] [--output DIRECTORY] [--tabs-only]');
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
    optionPlans: [], playPlans: [], downloadPlans: [], resolvePlans: [], searchPlans: [], detailPlans: {},
    options: { quality: '1080p', speed: 1 }, searchKeywords: [],
    library: { favorites: [], history: [
      { ...compact(first), ...first.episodes[31], positionMs: 3200 },
      { ...compact(second), ...second.episodes[8], positionMs: 9000 },
    ] },
  };
  const scenario = new URL(location.href).searchParams.get('scenario');
  if (scenario === 'cap-current') entries['7000000000000000099'] = { seriesId: '7000000000000000099', title: '合成：山海：第99季', episodeCount: 6, episodes: Array.from({ length: 6 }, (_, i) => ({ vid: String(8000000000000990000n + BigInt(i)), index: i + 1 })), description: '合成的第99季，用于验证分页不丢失当前季。' };
  if (scenario === 'identical-editions') { entries[ids[4]].episodeCount = 100; entries[ids[4]].episodes = entries[ids[4]].episodes.slice(0, 100); }
  if (scenario === 'invalid-title') { entries[ids[6]].title = '合成超长标题'.repeat(40) + '第一季'; entries[ids[6]].description = '自动分季查找失败仍允许原有详情和播放。'; }
  if (scenario === 'lookup-error') f.searchPlans.push({ error: 'MOCK_SEASON_SEARCH_FAILED' });
  if (scenario === 'lookup-delay') f.searchPlans.push({ delay: 500 });
  if (['unmarked-entry', 'native-title-alias', 'manual-unmarked-race'].includes(scenario)) { f.library.history[0].updatedAt = 1000; f.library.history[1].updatedAt = 2000; }
  if (scenario === 'manual-unmarked-race') f.detailPlans[ids[0]] = [{ delay: 200 }];
  if (scenario === 'canonical-race') f.detailPlans[ids[0]] = [{ delay: 100 }];
  const homeFirst = ['unmarked-entry', 'native-title-alias', 'manual-unmarked-race'].includes(scenario) ? { ...first, title: scenario === 'manual-unmarked-race' ? '合成:山海' : '合成：山海' } : scenario === 'canonical-race' ? { ...first, title: '合成：旧名：第一季' } : first;
  const raw = item => ({ series_id: item.seriesId, series_name: item.title, series_intro: item.description,
    episode_cnt: item.episodeCount, accessible_episode_cnt: 3, vid_list: item.episodes.map(e => e.vid) });
  const pageHtml = loaderData => `<script>window._ROUTER_DATA=${JSON.stringify({ loaderData })};</script>`;
  const searchResponse = (keyword, offset) => {
    let items, more;
    if (scenario === 'canonical-race' && keyword === '合成:旧名') { items = [{ ...first, title: '合成：旧名：第一季' }, { ...second, title: '合成：旧名：第二季' }]; more = false; }
    else if (scenario === 'cap-current') {
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
      data: items.map(item => ({ video_data: [scenario === 'native-title-alias' ? { series_id: item.seriesId, title: '合成：山海', episode_cnt: item.episodeCount, video_detail: { series_id_str: item.seriesId, series_title: item.title, episode_cnt: item.episodeCount } } : { series_id: item.seriesId, title: item.title, episode_cnt: item.episodeCount }] })) }] };
  };
  window.AndroidBridge = { postMessage(rawMessage) {
    const m = JSON.parse(rawMessage); f.calls.push(m);
    let result, error, delay = 1, plan = {};
    if (m.method === 'getInfo') result = { isTv: new URL(location.href).searchParams.get('tv') === '1', version: 'MOCK-seasons-ui' };
    else if (m.method === 'getLibrary') result = JSON.parse(JSON.stringify(f.library));
    else if (m.method === 'httpGet') {
      const u = new URL(m.params.url);
      if (u.pathname.startsWith('/category/')) result = { status: 200, body: pageHtml({ catalog: { isSuccess: true, categoryRoute: { contentType: u.pathname.split('/')[2] }, recommendList: [homeFirst, entries[ids[6]], entries[ids[7]]].map(raw), pagination: { pageNum: 1, totalPages: 1, total: 3 } } }) };
      else if (/^\/player\/\d+$/.test(u.pathname)) {
        const sid = u.pathname.split('/')[2], item = entries[sid];
        f.detailReads.push(sid); plan = f.detailPlans[sid]?.shift() || {};
        result = item ? { status: 200, body: pageHtml({ 'player_(series_id)/page': { series_id: sid, seriesDetail: raw(plan.title ? { ...item, title: plan.title } : item) } }) } : { status: 404, body: '' };
      } else { error = 'UNEXPECTED_HTTP_' + u.pathname; f.notes.push(error); }
    } else if (m.method === 'searchQuery') { f.searches.push(m.params); plan = f.searchPlans.shift() || (scenario === 'canonical-race' && m.params.keyword === '合成:旧名' ? { delay: 500 } : scenario === 'manual-unmarked-race' ? { delay: 500 } : {}); result = searchResponse(m.params.keyword, m.params.offset); }
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
        f.library.history = [{ ...m.params.series, ...m.params.episode, positionMs: 1234 }, ...f.library.history.filter(x => x.seriesId !== m.params.series.seriesId)];
      }
      window.__androidResponse({ id: m.id, ...(error ? { error } : { result }) });
      if (m.method === 'play' && !error) window.dispatchEvent(new Event('androidresume'));
    }, delay);
  } };
}
fs.mkdirSync(output,{recursive:true});
const temporary=fs.mkdtempSync(path.join(os.tmpdir(),'hongguo-seasons-ui-'));
const report={kind:'real React App + real bridge, MOCK native responses with real source/search parsers; not device or stream QA',root,tests:[],screenshots:[],externalRequests:[],pageErrors:[]};
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
  const group=async(name,fn)=>{if(tabsOnly&&!name.startsWith('TABS ')&&!name.startsWith('ISOLATION '))return;try{await fn();passed++;report.tests.push({name,result:'PASS'});console.log('PASS '+name);}catch(error){failed++;report.tests.push({name,result:'FAIL',error:error.message});console.log('FAIL '+name+': '+error.message);}};
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

  await group('TABS semantics expose one horizontal tablist with selected panel and a single roving entry',async()=>{
    await reset();await more();await wait(`document.querySelector(${q(selector(SID[4]))})`);
    await evaluate('document.querySelector(".season-selector").scrollIntoView({block:"start"})');await screenshot('phone-tabs-semantics.png');
    const a=await evaluate(`(()=>{const list=document.querySelector('.season-list');return {role:list.getAttribute('role'),orientation:list.getAttribute('aria-orientation'),tabs:[...list.querySelectorAll('[data-season-id]')].map(e=>{const panel=document.getElementById(e.getAttribute('aria-controls'));return {id:e.id,sid:e.dataset.seasonId,role:e.getAttribute('role'),selected:e.getAttribute('aria-selected'),tabIndex:e.tabIndex,label:e.getAttribute('aria-label'),text:e.innerText,panel:panel&&{role:panel.getAttribute('role'),labelledby:panel.getAttribute('aria-labelledby'),hidden:panel.hidden,episodes:!!panel.querySelector('.episode-grid')}};})};})()`);
    eq(a.role,'tablist','season candidates are a real tablist, not grouped cards');eq(a.orientation,'horizontal','tablist announces horizontal navigation');
    eq(a.tabs.filter(t=>t.selected==='true').map(t=>t.sid),[SID[0]],'only current season is selected');eq(a.tabs.filter(t=>t.tabIndex===0).map(t=>t.sid),[SID[0]],'only one initial tab participates in sequential keyboard order');
    check(a.tabs.every(t=>t.role==='tab'&&t.panel?.role==='tabpanel'&&t.panel.labelledby===t.id),'each tab has its own corresponding labelled panel');
    check(a.tabs.every(t=>t.selected==='true'?!t.panel.hidden&&t.panel.episodes:t.panel.hidden),'current panel owns real episodes and inactive panels stay hidden');
    check(a.tabs.every(t=>!t.text.includes('合成')&&!t.text.includes('看到')&&t.label?.includes('合成：山海：')),'tabs use compact season labels while accessible names retain full titles');
    const summary=await evaluate(`(()=>{const e=document.querySelector('.season-current');return e&&{text:e.innerText,rect:e.getBoundingClientRect().toJSON(),visibility:getComputedStyle(e).visibility,display:getComputedStyle(e).display};})()`);
    check(summary&&summary.text.includes('合成：山海：第一季')&&summary.text.includes('65')&&summary.text.includes('第 32 集')&&summary.rect.height>0&&summary.visibility==='visible'&&summary.display!=='none','current season title count and history stay visibly readable outside compact tabs');
    eq((await plays()).length,0,'semantics and directory loading do not start playback');
  });
  await group('TABS layout phone 320/390 landscape and 160 percent text stays one compact scrollable row',async()=>{
    const layouts=[];
    for(const [width,height,large] of [[320,640,false],[390,844,false],[844,390,false],[320,844,true],[390,844,true]]){
      await reset({width,height,large,scenario:'capacity'});await wait('document.querySelectorAll("[data-season-id]").length===25');
      await evaluate('document.querySelector(".season-selector").scrollIntoView({block:"start"})');await sleep(30);
      const m=await evaluate(`(()=>{const list=document.querySelector('.season-list'),r=list.getBoundingClientRect();return {width:innerWidth,font:getComputedStyle(document.documentElement).fontSize,list:{x:r.x,y:r.y,w:r.width,h:r.height,cw:list.clientWidth,sw:list.scrollWidth,ch:list.clientHeight,sh:list.scrollHeight,overflow:getComputedStyle(list).overflowX},tabs:[...list.querySelectorAll('[data-season-id]')].map(e=>{const r=e.getBoundingClientRect();return {top:r.top,left:r.left,right:r.right,w:r.width,h:r.height,text:e.innerText,cw:e.clientWidth,sw:e.scrollWidth,ch:e.clientHeight,sh:e.scrollHeight};})};})()`);
      layouts.push({width,height,large,...m});await screenshot(`phone-tabs-${width}x${height}${large?'-large-text':''}.png`);
      check(m.tabs.every(t=>Math.abs(t.top-m.tabs[0].top)<1),'all season tabs share a single row '+JSON.stringify({width,height,large,tops:m.tabs.map(t=>t.top)}));
      check(m.tabs.every(t=>t.h<=(large?80:64)&&!t.text.includes('\n')),'compact season labels are not tall cards or multiline text');
      check(m.list.sw>m.list.cw&&['auto','scroll'].includes(m.list.overflow),'long tab catalog overflows inside its own horizontal strip');
      check(m.list.sh<=m.list.ch+1,'tablist has no second vertical row');
      check(m.tabs.every(t=>t.sw<=t.cw+1&&t.sh<=t.ch+1),'compact tab text not clipped');await noOverflow();await seasonTargets(false);await dockVisible();
      await evaluate('const strip=document.querySelector(".season-list");strip.scrollLeft=strip.scrollWidth');await sleep(50);
      const last=await evaluate(`(()=>{const l=document.querySelector('.season-list'),b=l.querySelector('[data-season-id]:last-child'),r=l.getBoundingClientRect(),t=b.getBoundingClientRect();return {left:t.left,right:t.right,stripLeft:r.left,stripRight:r.right,scroll:l.scrollLeft};})()`);
      check(last.scroll>0&&last.left>=last.stripLeft-1&&last.right<=last.stripRight+1,'horizontal scrolling makes the last tab fully reachable');
      eq(await selectedEpisode(),32,'scrolling does not reset remembered episode');eq((await plays()).length,0,'scrolling tab strip never plays');
    }
    report.tabLayouts=layouts;
  });
  await group('TABS TV Left Right Home End move only focus and Enter Space explicitly switch while retaining history',async()=>{
    await reset({tv:true});await more();await wait(`document.querySelector(${q(selector(SID[4]))})`);
    await evaluate(`document.querySelector(${q(selector(SID[0]))}).focus()`);
    await key('ArrowRight',39);eq(await evaluate('document.activeElement.dataset.seasonId'),SID[1],'Right focuses next tab');eq(await currentId(),SID[0],'Right does not select another season');eq(await selectedEpisode(),32,'Right keeps current episode');eq((await plays()).length,0,'Right does not play');
    eq(await evaluate('[...document.querySelectorAll(".season-choice")].filter(b=>b.tabIndex===0).map(b=>b.dataset.seasonId)'),[SID[1]],'roving entry follows keyboard focus');
    const focus=await evaluate(`(()=>{const b=document.activeElement,s=getComputedStyle(b);return {visible:b.matches(':focus-visible'),width:parseFloat(s.outlineWidth),style:s.outlineStyle};})()`);
    check(focus.visible&&focus.width>=2&&focus.style!=='none','keyboard focus has a visible outline');
    await key('ArrowLeft',37);eq(await evaluate('document.activeElement.dataset.seasonId'),SID[0],'Left focuses previous tab');
    await key('End',35);eq(await evaluate('document.activeElement.dataset.seasonId'),SID[4],'End focuses last tab');eq(await currentId(),SID[0],'End does not activate');
    await key('Home',36);eq(await evaluate('document.activeElement.dataset.seasonId'),SID[0],'Home focuses first tab');
    await key('ArrowRight',39);await key('Enter',13);await ready(SID[1]);eq(await currentId(),SID[1],'Enter activates focused tab');eq(await selectedEpisode(),9,'Enter restores second-season own remembered episode');eq((await plays()).length,0,'season confirmation alone never plays');
    const panel=await evaluate(`(()=>{const b=document.querySelector('.season-choice[aria-selected=true]'),p=document.getElementById(b.getAttribute('aria-controls'));return {labelledby:p.getAttribute('aria-labelledby'),tabId:b.id,episodes:p.querySelectorAll('.episode-grid button').length};})()`);
    eq(panel.labelledby,panel.tabId,'new active panel labelled by selected tab');eq(panel.episodes,43,'new panel contains second-season own complete directory');
    await screenshot('tv-horizontal-season-tabs-focused.png');
    await key('Home',36);await key(' ',32);await ready(SID[0]);eq(await selectedEpisode(),32,'Space explicitly restores first-season remembered episode');eq((await plays()).length,0,'Space confirmation does not play');
    await reset({tv:true,scenario:'capacity'});await wait('document.querySelectorAll("[data-season-id]").length===25');await evaluate('document.querySelector(".season-choice").focus()');await key('End',35);
    const end=await evaluate(`(()=>{const l=document.querySelector('.season-list'),b=document.activeElement,r=l.getBoundingClientRect(),t=b.getBoundingClientRect();return {text:b.textContent,scroll:l.scrollLeft,left:t.left,right:t.right,listLeft:r.left,listRight:r.right};})()`);
    check(end.text.includes('25')&&end.scroll>0&&end.left>=end.listLeft-1&&end.right<=end.listRight+1,'TV End scrolls focused last tab fully into view');await noOverflow();await screenshot('tv-horizontal-season-tabs-last.png');
  });
  await group('TABS disabled tabs cannot switch or start a second playback during native dispatch',async()=>{
    await reset({tv:true});await wait(`document.querySelector(${q(selector(SID[1]))})`);
    await evaluate(`document.querySelector(${q(selector(SID[0]))}).focus();window.__seasonFixture.playPlans=[{delay:350}]`);await clickEpisode(33);await wait('window.__seasonFixture.calls.some(c=>c.method==="play")');
    check(await evaluate('[...document.querySelectorAll(".season-choice")].every(b=>b.disabled)'),'all season tabs disabled during native ownership');
    await key('Enter',13);await choose(SID[1]);eq(await currentId(),SID[0],'disabled tab confirmation and click cannot switch season');eq((await plays()).length,1,'disabled navigation cannot dispatch duplicate playback');
    await idle();await choose(SID[1]);await ready(SID[1]);eq(await selectedEpisode(),9,'unlock restores normal tab navigation and history');
  });

  await group('DETAIL SYNC same-ID complete title replaces unmarked homepage basis without resetting episode/history or starting playback',async()=>{
    await reset({scenario:'unmarked-entry'});await wait(`document.querySelector(${q(selector(SID[1]))})`);
    eq(await selectedEpisode(),32,'detail-selected history episode survives metadata synchronization');
    eq(await evaluate(`document.querySelector(${q(selector(SID[0]))}).querySelector('.season-label').textContent`),'第1季','entry season label comes from confirmed same-ID detail');
    check(await evaluate('document.querySelector(".season-history").textContent.includes("第 2 季")'),'latest same-family history uses confirmed entry title');
    eq(await evaluate('window.__seasonFixture.searches.map(x=>x.keyword)'),['合成:山海'],'canonical keyword searched once');
    eq((await plays()).length,0,'title correction does not play');
    await evaluate('document.querySelector(".season-selector").scrollIntoView({block:"start"})');await screenshot('phone-confirmed-detail-seasons.png');
    await choose(SID[1]);await ready(SID[1]);eq(await selectedEpisode(),9,'switch restores selected season own history');
    await choose(SID[0]);await ready(SID[0]);eq(await selectedEpisode(),32,'entry remains reachable and retains own history');
    eq(await evaluate('window.__seasonFixture.searches.length'),1,'switching seasons or rereading same title does not restart lookup');eq((await plays()).length,0,'browsing corrected directory never plays');
  });
  await group('NATIVE SHAPE alias titles plus nested series_title produce real UI season choices on phone and TV',async()=>{
    for(const tv of [false,true]){
      await reset({scenario:'native-title-alias',tv});await wait(`document.querySelector(${q(selector(SID[1]))})`);
      eq(await selectedEpisode(),32,'native-shaped search retains remembered entry episode');
      check(await evaluate(`document.querySelector(${q(selector(SID[1]))}).getAttribute('aria-label').includes('第二季')`),'full nested title reaches visible selector');
      await choose(SID[1]);await ready(SID[1]);eq(await selectedEpisode(),9,'nested source identity selects exact second-season history');
      eq((await plays()).length,0,'search alias repair never starts playback');
      if(tv){await evaluate('document.querySelector(".season-selector").scrollIntoView({block:"start"})');await screenshot('tv-native-title-alias-seasons.png');}
    }
  });
  await group('DETAIL SYNC stale original lookup cannot overwrite corrected family when its response arrives late',async()=>{
    await reset({scenario:'canonical-race'});await wait(`document.querySelector(${q(selector(SID[1]))})`);await sleep(550);
    eq(await evaluate('window.__seasonFixture.searches.map(x=>x.keyword)'),['合成:旧名','合成:山海'],'detail correction starts fresh canonical family lookup');
    check(await evaluate(`document.querySelector(${q(selector(SID[1]))}).getAttribute('aria-label').includes('合成：山海：第二季')`),'late stale family response discarded');
    eq(await selectedEpisode(),32,'stale response does not reset episode');eq((await plays()).length,0,'lookup correction never plays');
  });
  await group('DETAIL SYNC old manual lookup and corrected automatic lookup can share cursor request without stale filtering',async()=>{
    await reset({scenario:'manual-unmarked-race',open:false});await evaluate('document.querySelector(".poster").click()');await wait('document.querySelector("[data-season-action=search]")');
    await evaluate('document.querySelector("[data-season-action=search]").click()');await ready(SID[0]);await wait(`document.querySelector(${q(selector(SID[1]))})`);
    eq(await evaluate('window.__seasonFixture.searches.length'),1,'both lookup generations share the real pager in-flight cursor request');
    eq(await selectedEpisode(),32,'shared/late cursor completion keeps own history');eq((await plays()).length,0,'lookup does not play');
  });
  await group('DETAIL SYNC old switched detail cannot update the stable entry basis or visible season metadata',async()=>{
    await reset();await wait(`document.querySelector(${q(selector(SID[1]))})`);
    await evaluate(`window.__seasonFixture.detailPlans[${q(SID[1])}]=[{delay:300,title:'合成：误入：第二季'}]`);
    await choose(SID[1]);await wait(`window.__seasonFixture.detailReads.includes(${q(SID[1])})`);await choose(SID[0]);await ready(SID[0]);await sleep(350);
    check(await evaluate(`document.querySelector(${q(selector(SID[1]))}).getAttribute('aria-label').includes('合成：山海：第二季')`),'old same-ID detail metadata ignored after selection epoch changed');
    eq(await evaluate('window.__seasonFixture.searches.length'),1,'late switched detail does not change family basis or requery');
    eq(await selectedEpisode(),32,'late detail preserves current remembered episode');eq((await plays()).length,0,'late detail never dispatches player');
  });
  await group('DETAIL SYNC corrected family history continues exact recorded season/episode only on explicit action',async()=>{
    await reset({scenario:'unmarked-entry'});await wait(`document.querySelector(${q(selector(SID[1]))})`);eq((await plays()).length,0,'confirmed history never starts itself');
    await evaluate('document.querySelector("[data-season-action=resume]").click()');await ready(SID[1]);await idle();
    eq((await plays()).map(p=>[p.series.seriesId,p.episode.vid,p.episode.index]),[[SID[1],'8000000000000010008',9]],'explicit continue uses corrected-family exact history');
    check(await evaluate(`!!document.querySelector(${q(selector(SID[0]))})`),'entry season remains reachable after history jump');
  });
  await group('DETAIL SYNC browsing another confirmed edition never changes the entry family or discards current/entry seasons',async()=>{
    await reset();await wait(`document.querySelector(${q(selector(SID[1]))})`);
    await evaluate(`window.__seasonFixture.detailPlans[${q(SID[1])}]=[{title:'另一故事：第二季'}]`);await choose(SID[1]);
    await wait('document.querySelector(".detail-copy h1")?.textContent==="另一故事：第二季"');await sleep(30);
    eq(await evaluate('window.__seasonFixture.searches.length'),1,'confirmed other edition never changes family query');eq(await selectedEpisode(),9,'same-ID correction retains current episode');
    await more();await wait(`document.querySelector(${q(selector(SID[4]))})`);
    eq(await evaluate('window.__seasonFixture.searches.at(-1).keyword'),'合成:山海','later pages use stable confirmed entry family');
    eq(await currentId(),SID[1],'current edition remains selected');check(await evaluate(`!!document.querySelector(${q(selector(SID[0]))})`),'entry remains pinned');
    check(await evaluate(`document.querySelector(${q(selector(SID[1]))}).getAttribute('aria-label').includes('另一故事：第二季')`),'old search metadata does not overwrite current confirmed detail');
    eq((await plays()).length,0,'metadata correction does not play');
  });
  await group('DETAIL SYNC unmounted details and lookup responses stay detached from the next family',async()=>{
    await reset({scenario:'canonical-race',open:false});await evaluate('document.querySelector(".poster").click()');await wait('document.querySelector(".back-button")');
    await evaluate('document.querySelector(".back-button").click()');await wait('document.querySelectorAll(".poster").length===3');await evaluate('document.querySelectorAll(".poster")[2].click()');await ready(SID[7]);await sleep(600);
    eq(await currentId(),SID[7],'next family retains its own selection');eq(await evaluate('document.querySelectorAll("[data-season-id]").length'),1,'old family does not leak into new directory');eq((await plays()).length,0,'unmounted work never starts video');
  });
  await group('PHONE real parsed season candidates preserve punctuation and same-ordinal different-ID editions',async()=>{
    await reset();await wait(`document.querySelector(${q(selector(SID[1]))})`);
    eq(await currentId(),SID[0],'initial season remains selected');
    eq(await selectedEpisode(),32,'initial history targets its own season');
    eq((await plays()).length,0,'opening seasons never plays');
    check(await evaluate('document.querySelector(".season-selector").textContent.includes("按标题匹配")'),'title-match caveat displayed');
    eq(await evaluate(`!!document.querySelector(${q(selector(SID[5]))})`),false,'punctuation-distinct family excluded');
    eq(await evaluate(`!!document.querySelector(${q(selector(SID[7]))})`),false,'unrelated family excluded');
    await more();await wait(`document.querySelector(${q(selector(SID[4]))})`);
    const rows=await evaluate(`[...document.querySelectorAll('.season-selector [data-season-id]')].map(e=>({id:e.dataset.seasonId,text:e.textContent}))`);
    eq(rows.map(r=>r.id),[SID[0],SID[1],SID[2],SID[3],SID[4]],'page append sorts and deduplicates by ID, not season number');
    check(rows.find(r=>r.id===SID[3]).text.includes('100'),'first fourth-season edition has count');
    check(rows.find(r=>r.id===SID[4]).text.includes('219'),'alternate fourth-season edition has count');
    eq((await plays()).length,0,'load more never plays');
    await noOverflow();await screenshot('phone-390-season-editions.png');
  });
  await group('PHONE season switch requests exact detail, isolates full queues and never auto-starts or crosses episode IDs',async()=>{
    await reset();await choose(SID[1]);await ready(SID[1]);
    eq(await selectedEpisode(),9,'second season restores its own history');eq((await plays()).length,0,'season button is browse only');
    await clickEpisode(1);await idle();let p=(await plays())[0];eq(p.series.seriesId,SID[1],'native series identity is second season');eq(p.episode.index,1,'second-season first episode restarts at index one');
    eq(p.episodes.length,43,'all second-season episodes passed, not combined');eq(p.episode.vid,'8000000000000010000','exact second-season first vid');eq(p.episodes.map(e=>e.vid),await evaluate(`window.__seasonFixture.entries[${q(SID[1])}].episodes.map(e=>e.vid)`),'queue is exact selected-season IDs');
    check(p.autoplay&&p.cleanupPlaybackCache&&p.resolveInPlayer,'existing native autoplay/cache/resolver flags retained');
    await choose(SID[0]);await ready(SID[0]);eq(await selectedEpisode(),32,'other season history remains unchanged');
    await evaluate('const s=document.querySelector("#episode-range");s.value="0";s.dispatchEvent(new Event("change",{bubbles:true}))');await sleep(20);await clickEpisode(1);await idle();p=(await plays())[1];eq(p.series.seriesId,SID[0],'return restores first-season identity');eq(p.episodes.length,65,'first-season queue is still complete');eq(p.episode.index,1,'both seasons independently have episode one');
    eq(await evaluate('window.__seasonFixture.resolves.length'),0,'native playback source path not replaced by JS preflight');
  });
  await group('EDITIONS identical titles and episode counts retain separate choices with visible version labels and accessible full IDs',async()=>{
    await reset({scenario:'identical-editions'});await more();await wait(`document.querySelector(${q(selector(SID[4]))})`);
    for(const id of [SID[3],SID[4]]){
      eq(await evaluate(`document.querySelectorAll(${q(selector(id))}).length`),1,'separate native series ID retained');
      check(await evaluate(`document.querySelector(${q(selector(id))}).getAttribute('aria-label').includes(${q(id)})`),'identical metadata versions remain distinguishable');
    }
    const editions=await evaluate(`[...document.querySelectorAll('.season-choice')].filter(e=>[${q(SID[3])},${q(SID[4])}].includes(e.dataset.seasonId)).map(e=>e.innerText)`);
    check(editions.length===2&&editions[0]!==editions[1]&&editions.every(text=>text.includes('版本')),'same-title same-count editions have distinct visible compact labels');
    eq((await plays()).length,0,'alternate editions are not played by merely appearing');
  });
  await group('HISTORY initial poster vid is used only first entry, switching back uses refreshed per-season history',async()=>{
    await reset({open:false});await nav('我的追剧');await wait('document.querySelector(".section-tabs")');
    await evaluate('[...document.querySelectorAll(".section-tabs button")].find(b=>b.textContent.includes("观看历史")).click()');await wait('document.querySelectorAll(".poster").length===2');
    await evaluate('document.querySelector(".poster").click()');await ready(SID[0]);eq(await selectedEpisode(),32,'history poster initial vid honored');
    await clickEpisode(55);await idle();await wait('window.__seasonFixture.library.history[0].index===55');
    await choose(SID[1]);await ready(SID[1]);eq(await selectedEpisode(),9,'initial season vid never leaks to second season');
    await choose(SID[0]);await ready(SID[0]);eq(await selectedEpisode(),55,'back switch uses updated native history, not stale initial vid32');
    eq((await plays()).length,1,'history and season browsing add no playback');
  });
  await group('RACE same-event season switch cancels delayed old playback-options before native dispatch',async()=>{
    await reset();await wait(`document.querySelector(${q(selector(SID[1]))})`);
    await evaluate(`window.__seasonFixture.optionPlans=[{delay:250},{delay:1}];document.querySelectorAll('.episode-grid button')[1].click();document.querySelector(${q(selector(SID[1]))}).click()`);
    await ready(SID[1]);await sleep(300);eq((await plays()).length,0,'late options cannot dispatch previous-season player');eq(await currentId(),SID[1],'season selection wins same-tick intent');
    await clickEpisode(2);await idle();eq((await plays()).map(p=>[p.series.seriesId,p.episode.index]),[[SID[1],2]],'new season play remains available');
  });
  await group('RACE late old detail cannot replace newly selected season or lose selector focus',async()=>{
    await reset();await evaluate(`window.__seasonFixture.detailPlans[${q(SID[1])}]=[{delay:300}]`);
    await choose(SID[1]);await wait(`window.__seasonFixture.detailReads.includes(${q(SID[1])})`);
    await choose(SID[0]);await ready(SID[0]);await sleep(350);
    eq(await currentTitle(),'合成：山海：第一季','late response did not replace first-season details');eq(await selectedEpisode(),32,'old selection retained');
    eq(await evaluate('document.activeElement.dataset.seasonId'),SID[0],'focus remains on chosen season, not play button');eq((await plays()).length,0,'detail race never auto-starts');
  });
  await group('FAILURE detail retry and changing back stay available without mixed queues',async()=>{
    await reset();await evaluate(`window.__seasonFixture.detailPlans[${q(SID[1])}]=[{error:'MOCK_SECOND_DETAIL_FAILED'},{}]`);
    await choose(SID[1]);await wait('document.querySelector(".error-state")');
    check(await evaluate('document.querySelector(".error-state").textContent.includes("MOCK_SECOND_DETAIL_FAILED")'),'detail error is shown');
    check(await evaluate(`!document.querySelector(${q(selector(SID[0]))}).disabled`),'other seasons remain selectable during detail failure');
    eq(await evaluate('document.querySelectorAll(".episode-grid button").length'),0,'old season episodes not shown beneath failed new season');await screenshot('phone-season-detail-failure.png');
    await evaluate('document.querySelector(".error-state button").click()');await ready(SID[1]);eq(await selectedEpisode(),9,'retry restores selected-season history');
    await choose(SID[0]);await ready(SID[0]);eq(await selectedEpisode(),32,'changing back remains valid');eq((await plays()).length,0,'error recovery does not play');
  });
  await group('FAILURE title lookup error is separate from playback and retry does not duplicate candidates',async()=>{
    await reset({scenario:'lookup-error'});await wait('document.querySelector("[data-season-action=retry]")');
    check(await evaluate('document.querySelector(".season-selector").textContent.includes("MOCK_SEASON_SEARCH_FAILED")'),'lookup error displayed near selector');
    await clickEpisode(33);await idle();eq((await plays())[0].series.seriesId,SID[0],'current series still plays while season lookup fails');
    await evaluate('document.querySelector("[data-season-action=retry]").click()');await wait(`document.querySelector(${q(selector(SID[1]))})`);
    eq(await evaluate(`document.querySelectorAll(${q(selector(SID[0]))}).length`),1,'lookup retry keeps one seed');eq((await plays()).length,1,'retry is browse-only');
  });
  await group('DETAIL STATE switch resets operation error, synopsis expansion and group while retaining independent history',async()=>{
    await reset();await button('展开简介');eq(await evaluate('document.querySelector(".description-toggle").getAttribute("aria-expanded")'),'true','first season synopsis expanded');
    await evaluate('window.__seasonFixture.playPlans=[{error:"MOCK_FIRST_PLAY_FAILED"}]');await clickEpisode(33);await wait('document.querySelector(".operation-error")');
    await choose(SID[1]);await ready(SID[1]);eq(await evaluate('document.querySelector(".operation-error")===null'),true,'old season operation error cleared');
    eq(await evaluate('document.querySelector(".description-toggle").getAttribute("aria-expanded")'),'false','new synopsis starts collapsed');eq(await evaluate('document.querySelector("#episode-range").value'),'0','new history reveals its own group');
    await choose(SID[0]);await ready(SID[0]);eq(await evaluate('document.querySelector("#episode-range").value'),'1','previous season restores history group, not group from other season');eq(await selectedEpisode(),32,'failed playback did not overwrite remembered episode');
  });
  await group('RACE delayed title lookup after leaving detail never adds old-family candidates to new detail',async()=>{
    await reset({scenario:'lookup-delay'});
    check(await evaluate('!!document.querySelector(".episode-grid button")'),'slow lookup does not block loaded episodes');
    await evaluate('document.querySelector(".back-button").click()');await wait('document.querySelectorAll(".poster").length===3');
    await evaluate('document.querySelectorAll(".poster")[2].click()');await ready(SID[7]);await sleep(550);
    eq(await currentId(),SID[7],'new detail seed stays current');
    eq(await evaluate('[...document.querySelectorAll("[data-season-id]")].map(e=>e.dataset.seasonId)'),[SID[7]],'unmounted lookup response ignored');eq((await plays()).length,0,'delayed discovery never dispatches player');
  });
  await group('PAGINATION load-more failure preserves current candidates and retries exact failed page',async()=>{
    await reset();await wait(`document.querySelector(${q(selector(SID[1]))})`);
    await evaluate('window.__seasonFixture.searchPlans=[{error:"MOCK_MORE_FAILED"},{delay:150}]');await more();await wait('document.querySelector("[data-season-action=retry]")');
    check(await evaluate(`!!document.querySelector(${q(selector(SID[1]))})`),'previous candidates retained after page error');
    await evaluate('document.querySelector("[data-season-action=retry]").click()');
    check(await evaluate(`!!document.querySelector(${q(selector(SID[1]))})`),'previous candidates retained while loading next page');
    await wait(`document.querySelector(${q(selector(SID[4]))})`);eq(await evaluate('window.__seasonFixture.searches.slice(-2).map(x=>x.offset)'),[20,20],'retry keeps failed cursor page');
    eq(await evaluate('document.querySelectorAll("[data-season-id]").length'),5,'retry appends only distinct IDs');eq((await plays()).length,0,'paging never plays');
  });
  await group('PAGINATION real five-minute cursor expiry restarts lookup at page one without losing current season',async()=>{
    for(const lateSeason of [false,true]) {
      await reset({scenario:'expires'});await wait(`document.querySelector(${q(selector(SID[1]))})`);
      if(lateSeason){await more();await wait(`document.querySelector(${q(selector(SID[2]))})`);}
      const selected=lateSeason?SID[2]:SID[1];await choose(selected);await ready(selected);
      const selectedBefore=await selectedEpisode();
      const callsBefore=await evaluate('window.__seasonFixture.searches.length');
      // Exercise createSearchPager's real SESSION_MS expiry; timers and native
      // response handling stay real. Navigation restores the browser Date API.
      await evaluate('window.__realDateNow=Date.now.bind(Date);Date.now=()=>window.__realDateNow()+301000');
      await more();await wait('document.querySelector("[data-season-action=retry]")');
      check(await evaluate('document.querySelector(".season-error").textContent.includes("过期")'),'real pager expiry is shown');
      eq(await evaluate('window.__seasonFixture.searches.length'),callsBefore,'expired cursor rejected locally, before any native search RPC');
      eq(await currentId(),selected,'expiry does not change selected season');eq(await selectedEpisode(),selectedBefore,'expiry does not change episode or history');
      eq(await evaluate('document.querySelector("[data-season-action=retry]").textContent'),'重新查找分季','expired cursor offers restart rather than the same broken page');
      await evaluate('document.querySelector("[data-season-action=retry]").click()');
      await wait(`!document.querySelector('.season-error')&&window.__seasonFixture.searches.length===${callsBefore+1}`);
      eq(await evaluate('window.__seasonFixture.searches.at(-1).offset'),0,'expired search restarts the first-page cursor');
      eq(await currentId(),selected,'current season remains visible and selected even when not present in fresh first page');
      eq(await selectedEpisode(),selectedBefore,'fresh discovery does not reset selected episode');
      check(await evaluate(`!!document.querySelector(${q(selector(SID[0]))})`),'original seed remains reachable');
      await more();await wait(`document.querySelector(${q(selector(SID[4]))})`);
      eq(await evaluate('window.__seasonFixture.searches.at(-1).offset'),20,'fresh second-page loading recovers normally');
      eq(await currentId(),selected,'successful renewed pagination keeps selection');eq((await plays()).length,0,'expiry and recovery never dispatch playback');
    }
  });
  await group('PAGINATION candidate cap is visible and stops automatic/manual expansion at forty',async()=>{
    await reset({scenario:'capacity'});await wait('document.querySelectorAll("[data-season-id]").length===25');await more();await wait('document.querySelectorAll("[data-season-id]").length===40');
    check(await evaluate('!document.querySelector("[data-season-action=more]")||document.querySelector("[data-season-action=more]").disabled'),'no more action beyond bounded cap');
    check(await evaluate('document.querySelector(".season-note").textContent.includes("上限")'),'cap explained visibly');
    eq(await evaluate('window.__seasonFixture.searches.length'),2,'only requested two pages, no hidden crawling');eq((await plays()).length,0,'cap never triggers media');
  });
  await group('PAGINATION current late-numbered season and original seed remain pinned when cap adds earlier seasons',async()=>{
    const last='7000000000000000099';await reset({scenario:'cap-current'});await choose(last);await ready(last);await more();await wait('document.querySelectorAll("[data-season-id]").length===40');
    eq(await currentId(),last,'currently playing/browsed season remains selected despite candidate truncation');
    eq(await evaluate(`document.querySelectorAll(${q(selector(SID[0]))}).length`),1,'original seed remains available');
    check(await evaluate('document.querySelector(".season-note").textContent.includes("上限")'),'bounded limit explained after pinning');
    eq((await plays()).length,0,'pinning is browse-only');await clickEpisode(1);await idle();eq((await plays())[0].series.seriesId,last,'pinned selection still opens its exact queue');eq((await plays())[0].episodes.length,6,'pinned season has its own complete six-episode queue');
  });
  await group('REGRESSION ordinary unmarked series has no background season search and no invented season-one label',async()=>{
    await reset({index:1});eq(await evaluate('window.__seasonFixture.searches.length'),0,'single unmarked series costs no added search request');
    check(await evaluate('!!document.querySelector("[data-season-action=search]")'),'optional other-season lookup remains available');
    eq(await evaluate('document.querySelector(".season-selector .season-label").textContent.includes("第一季")'),false,'unmarked seed is not mislabeled first season');
    await evaluate('document.querySelector("[data-season-action=search]").click()');await wait('window.__seasonFixture.searches.length===1');eq(await evaluate('window.__seasonFixture.searches[0].keyword'),'独立故事','explicit search uses unchanged keyword');eq((await plays()).length,0,'lookup never starts video');
  });
  await group('REGRESSION title unsuitable for optional season matching still loads original detail and exact native playback',async()=>{
    await reset({scenario:'invalid-title',index:1});check(await evaluate('!!document.querySelector(".season-error")'),'optional lookup problem is localized to selector');
    check(await evaluate(`[...document.querySelectorAll('[aria-controls], [aria-labelledby]')].every(e=>['aria-controls','aria-labelledby'].every(a=>!e.hasAttribute(a)||e.getAttribute(a).split(/\\s+/).every(id=>document.getElementById(id))))`),'invalid optional title leaves no dangling accessibility relationship');
    eq(await evaluate('document.querySelectorAll(".episode-grid button").length'),8,'original detail remains usable');eq(await evaluate('window.__seasonFixture.searches.length'),0,'oversized keyword never reaches search RPC');
    await clickEpisode(1);await idle();eq((await plays())[0].series.seriesId,SID[6],'playback retains original source identity');eq((await plays())[0].episodes.length,8,'original full queue unaffected by optional feature error');
  });
  await group('LOCK native player dispatch and manual download block season changes until completion',async()=>{
    await reset();await wait(`document.querySelector(${q(selector(SID[1]))})`);
    await evaluate('window.__seasonFixture.playPlans=[{delay:250}]');await clickEpisode(33);await wait('window.__seasonFixture.calls.some(c=>c.method==="play")');
    check(await evaluate(`document.querySelector(${q(selector(SID[1]))}).disabled`),'season choice disabled while native player owns session');await choose(SID[1]);eq(await currentId(),SID[0],'disabled choice cannot switch active native play');await idle();
    await evaluate('window.__seasonFixture.resolvePlans=[{delay:250}]');await button('下载本集');await wait('window.__seasonFixture.resolves.length===1');
    check(await evaluate(`document.querySelector(${q(selector(SID[1]))}).disabled`),'season choice disabled during exact manual download');await choose(SID[1]);eq(await currentId(),SID[0],'download ownership not changed');await idle();
    const d=await evaluate('window.__seasonFixture.calls.find(c=>c.method==="download").params');eq(d.series.seriesId,SID[0],'download retains selected season identity');eq(d.episode.index,33,'download retains selected episode');eq(d.quality,'1080p','manual quality remains strict');
    await choose(SID[1]);await ready(SID[1]);eq(await currentId(),SID[1],'unlock permits new season after operation');
  });
  await group('SEARCH original query and page remain intact while browsing other seasons and returning',async()=>{
    await reset({open:false});await nav('搜索');await wait('document.querySelector("#search-keyword")');await evaluate('document.querySelector("#search-keyword").focus()');await call('Input.insertText',{text:'山海'});
    await evaluate('document.querySelector(".search-controls button").click()');await wait('document.querySelector(".search-page-status")?.textContent.includes("第 1 页")&&!document.querySelector("[aria-label=搜索结果]").getAttribute("aria-busy").includes("true")');
    await evaluate('document.querySelector(".search-pagination button:last-child").click()');await wait('document.querySelector(".search-page-status")?.textContent.includes("第 2 页")&&document.querySelectorAll(".poster").length===2');
    await evaluate('document.querySelector(".poster").click()');await ready(SID[2]);await wait(`document.querySelector(${q(selector(SID[1]))})`);await choose(SID[1]);await ready(SID[1]);
    await evaluate('document.querySelector(".back-button").click()');await wait('document.querySelector("#search-keyword")');
    eq(await evaluate('document.querySelector("#search-keyword").value'),'山海','original search input retained');check(await evaluate('document.querySelector(".search-page-status").textContent.includes("第 2 页")'),'original result page retained');
    eq(await evaluate('window.__seasonFixture.searches.filter(x=>x.keyword==="山海").map(x=>x.offset)'),[0,20],'season search never refreshes original query session');
    eq(await evaluate('window.__seasonFixture.searchKeywords'),['山海'],'season lookup does not mutate user search history');eq((await plays()).length,0,'search and season browsing never plays');
  });
  await group('TV D-pad focus and Enter select a season without playing, and Enter episode passes full per-season queue',async()=>{
    await reset({tv:true});await wait(`document.querySelector(${q(selector(SID[1]))})`);
    await evaluate(`document.querySelector(${q(selector(SID[1]))}).focus()`);await key('ArrowDown',40);eq((await plays()).length,0,'D-pad focus never plays');
    await evaluate(`document.querySelector(${q(selector(SID[1]))}).focus()`);await key('Enter',13);await ready(SID[1]);
    eq(await evaluate('document.activeElement.dataset.seasonId'),SID[1],'selected season retains remote focus after keyed detail remount');eq((await plays()).length,0,'Enter on season only browses');eq(await evaluate('document.querySelectorAll(".episode-grid button").length'),43,'TV displays selected-season full directory');
    await seasonTargets(true);await noOverflow();await screenshot('tv-season-selector.png');
    await evaluate('document.querySelector(".episode-grid button").focus()');await key('Enter',13);await idle();
    eq((await plays()).map(p=>[p.series.seriesId,p.episode.index,p.episodes.length]),[[SID[1],1,43]],'Enter episode dispatches exactly once with current-season queue');
  });
  await group('LAYOUT phone 320/390/landscape and 160 percent text retain readable season targets and visible actions',async()=>{
    for(const [width,height,large] of [[320,640,false],[390,844,false],[844,390,false],[320,844,true],[390,844,true]]){
      await reset({width,height,large});await wait(`document.querySelector(${q(selector(SID[1]))})`);await more();await wait(`document.querySelector(${q(selector(SID[4]))})`);
      await noOverflow();await seasonTargets(false);await dockVisible();
      await evaluate('document.querySelector(".season-selector").scrollIntoView({block:"start"})');await sleep(25);await noOverflow();await dockVisible();
      const metrics=await evaluate('[...document.querySelectorAll(".season-selector button[data-season-id]")].map(e=>({h:e.clientHeight,sh:e.scrollHeight,w:e.clientWidth,sw:e.scrollWidth}))');
      check(metrics.every(m=>m.sh<=m.h+1&&m.sw<=m.w+1),'candidate title/count not clipped '+JSON.stringify(metrics));
      await screenshot(`phone-seasons-${width}x${height}${large?'-large-text':''}.png`);
      await choose(SID[1]);await ready(SID[1]);await noOverflow();await dockVisible();eq((await plays()).length,0,'layout/selection does not autoplay');
    }
  });
  await group('ISOLATION no browser exceptions, external network requests, unexpected RPCs or real stream access',async()=>{
    eq(report.externalRequests,[],'zero external browser requests');eq(report.pageErrors,[],'zero runtime exceptions');
    eq(await evaluate('window.__seasonFixture.notes'),[],'no unexpected native RPC or HTTP path');
    eq(await evaluate('window.__seasonFixture.calls.filter(c=>c.method==="appRequest").length'),0,'no real app provider resolution');
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
  console.log(`ANDROID_SEASONS_UI_${failed?'FAILED':'OK'} groupsPassed=${passed} groupsFailed=${failed} assertions=${checks} networkRequests=${report.externalRequests.length} deviceTests=not_run`);
  console.log('MOCK_UI_CLEANUP ownServer=stopped ownChrome=stopped ownProfile=removed adb=not_used emulator=not_used');
}
process.exitCode=failed?1:0;
