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
    else if (m.method === 'getSeriesHistory') {f.pointReads.push(m.params.seriesId);result={owner:f.library.owner,history:f.pointHistory||null};}
    else if (m.method === 'cancelHttp') result={cancelled:true};
    else if (m.method === 'httpGet') {
      const u = new URL(m.params.url);
      if (u.pathname.startsWith('/category/')) result = { status: 200, body: pageHtml({ catalog: { isSuccess: true, categoryRoute: { contentType: u.pathname.split('/')[2] }, recommendList: [homeFirst, entries[ids[6]], entries[ids[7]]].map(raw), pagination: { pageNum: 1, totalPages: 1, total: 3 } } }) };
      else if (/^\/player\/\d+$/.test(u.pathname) || u.pathname==='/detail') {
        const sid = u.pathname==='/detail' ? (u.searchParams.get('series_id')||u.searchParams.get('book_id')) : u.pathname.split('/')[2], item = entries[sid];
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
  f.officialCalls=[];f.pointReads=[];
  entries[ids[1]].title='官方系列另一个名字';
  const officialItems=()=>[ids[0],ids[1],ids[2]].map((id,index)=>({...compact(entries[id]),seasonLabel:`第${index+1}季`,seasonNumber:index+1,official:true,playable:index!==2,upcoming:index===2,tags:[]}));
  window.AndroidSeries={postMessage(source){const req=JSON.parse(source);f.officialCalls.push(req);if(req.method==='cancel')return;setTimeout(()=>window.__androidSeriesResponse({id:req.id,...(scenario==='official-failed'?{error:'MOCK_OFFICIAL_FAILURE'}:{result:{matching:'official',items:officialItems()}})}),scenario==='official-delayed'?600:25);}};
  if(scenario==='official-recent-history'){Object.assign(f.library.history[0],{seasonIndex:3,multiSeason:true});}
  if(scenario==='official-projected-history'){f.pointHistory={...compact(first),...first.episodes[8],positionMs:9000,favoriteAt:1000,seasonIndex:2,multiSeason:true};f.library.history=[];f.library.favorites=[];f.library.owner='official:'+'a'.repeat(64);f.library.projection={historyHasMore:true,historyTotal:300,historyShown:0,favoritesHasMore:true,favoritesTotal:300,completeSaved:true};}
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

  await group('OFFICIAL phone exact relations replace title guesses without starting playback',async()=>{
    await reset();await wait('document.querySelector(".season-note").textContent.includes("官方系列关系")');
    eq(await evaluate('window.__seasonFixture.searches.length'),0,'no title fallback on official success');
    await choose(SID[1]);await ready(SID[1]);eq(await currentTitle(),'官方系列另一个名字','official differently titled season chosen');eq((await plays()).length,0,'manual season switch does not play');await noOverflow();await screenshot('phone-official-seasons.png');
  });
  await group('OFFICIAL upcoming remains readable but cannot change season or enter playback',async()=>{
    await reset();await wait('document.querySelector(".season-note").textContent.includes("官方系列关系")');await choose(SID[2]);await sleep(50);eq(await currentId(),SID[0],'upcoming did not select');eq((await plays()).length,0,'upcoming never played');check(await evaluate('document.body.textContent.includes("待上线")'),'pending state visible');
  });
  await group('OFFICIAL unmount cancels native request and rejects late response',async()=>{
    await reset({scenario:'official-delayed'});await evaluate('window.__androidBack()');await wait('!document.querySelector(".detail-copy")');await sleep(700);check(!(await evaluate('!!document.querySelector(".season-selector")')),'late response detached');check(await evaluate('window.__seasonFixture.officialCalls.some(r=>r.method==="cancel")'),'native cancel sent');
  });
  await group('OFFICIAL failure keeps title fallback separate and usable',async()=>{
    await reset({scenario:'official-failed'});await wait('document.querySelector(".season-note").textContent.includes("已使用标题匹配")');check((await evaluate('window.__seasonFixture.searches.length'))>0,'title fallback called');eq((await plays()).length,0,'failure did not play');
  });
  await group('OFFICIAL bounded library point read restores older episode and old favorite',async()=>{
    await reset({scenario:'official-projected-history'});eq(await selectedEpisode(),9,'point history used despite bounded UI list');check(await evaluate('window.__seasonFixture.pointReads.length>0'),'point RPC made');check(await evaluate('document.querySelector(".detail-copy .saved")?.textContent.includes("已加入追剧")'),'old favorite remains marked');eq((await plays()).length,0,'remembering never auto plays');
  });
  await group('OFFICIAL explicit point metadata survives source detail into exact-season playback',async()=>{
    await reset({scenario:'official-projected-history'});await clickEpisode(9);await wait('window.__seasonFixture.calls.some(c=>c.method==="play")');
    const params=(await plays())[0];eq(params.series.seasonIndex,2,'explicit cloud seasonIndex reaches native playback');eq(params.series.multiSeason,true,'explicit cloud multiSeason reaches native playback');
    eq(params.series.seriesId,SID[0],'metadata does not switch series');eq(params.episodes.map(e=>e.vid),await evaluate('window.__seasonFixture.entries[window.__seasonFixture.ids[0]].episodes.map(e=>e.vid)'),'only exact selected season episodes passed');
    await reset({scenario:'official-recent-history'});await clickEpisode(32);await wait('window.__seasonFixture.calls.some(c=>c.method==="play")');const recent=(await plays())[0];eq(recent.series.seasonIndex,3,'recent library metadata survives neutral source detail');eq(recent.series.multiSeason,true,'recent multi-season flag remains explicit');
    await reset();await clickEpisode(32);await wait('window.__seasonFixture.calls.some(c=>c.method==="play")');const unknown=(await plays())[0];check(!('seasonIndex' in unknown.series)&&!('multiSeason' in unknown.series),'no season metadata invented from title or directory count');
  });
  await group('OFFICIAL same-owner cloud refresh replaces old point favorite without reloading source or moving selection',async()=>{
    await reset({scenario:'official-projected-history'});eq(await selectedEpisode(),9,'old point selection starts at episode9');
    const before=await evaluate('({points:window.__seasonFixture.pointReads.length,details:window.__seasonFixture.detailReads.length})');
    await evaluate('var f=window.__seasonFixture;f.pointHistory={...f.pointHistory,favoriteAt:0,index:12,vid:f.entries[f.ids[0]].episodes[11].vid};window.__androidAccountChanged({owner:f.library.owner})');
    await wait(`window.__seasonFixture.pointReads.length>${before.points}`,'same-owner snapshot refreshes projected point row');
    await wait('!document.querySelector(".detail-copy .saved")','cloud favorite removal clears cached old favorite');
    eq(await selectedEpisode(),9,'cloud refresh does not override explicit selected episode');eq(await evaluate('window.__seasonFixture.detailReads.length'),before.details,'library refresh does not re-fetch media source detail');eq((await plays()).length,0,'cloud update never auto plays');
    await evaluate('var f=window.__seasonFixture;f.pointHistory={...f.pointHistory,favoriteAt:1000};window.__androidAccountChanged({owner:f.library.owner})');
    await wait('document.querySelector(".detail-copy .saved")','same-owner point row can re-add favorite');
    await evaluate('var f=window.__seasonFixture;f.library.projection={historyHasMore:false,favoritesHasMore:false,completeSaved:true};f.library.favorites=[];f.library.history=[];window.__androidAccountChanged({owner:f.library.owner})');
    await wait('!document.querySelector(".detail-copy .saved")','complete library supersedes cached projected favorite');await screenshot('phone-point-cloud-refresh.png');
  });
  await group('OFFICIAL TV focus activates exact season without auto-playing',async()=>{
    await reset({tv:true});await wait('document.querySelector(".season-note").textContent.includes("官方系列关系")');await evaluate(`document.querySelector(${q(selector(SID[1]))}).focus()`);await key('Enter',13);await ready(SID[1]);eq(await currentId(),SID[1],'TV exact season');eq((await plays()).length,0,'TV season switch is not playback');await noOverflow();await screenshot('tv-official-seasons.png');
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
