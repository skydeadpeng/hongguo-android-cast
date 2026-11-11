#!/usr/bin/env node
/** Offline real React App + real bridge + real search source regression.
 * Only Android RPC replies (including HTTP HTML/JSON) are synthetic. No device,
 * emulator, real provider or stream is contacted. An isolated Chrome profile
 * and Fetch interception block every non-loopback browser request. */
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
let root = ownRoot, output = path.join(ownRoot, 'verification/android-search-release-20261021/ui-current');
for (let i = 2; i < process.argv.length; i++) {
  if (process.argv[i] === '--root' && process.argv[i + 1]) root = path.resolve(process.argv[++i]);
  else if (process.argv[i] === '--output' && process.argv[i + 1]) output = path.resolve(process.argv[++i]);
  else throw Error('Usage: node scripts/check-android-search-pagination-ui.mjs [--root PROJECT] [--output DIRECTORY]');
}
const { build } = createRequire(import.meta.url)('esbuild');
function installFixture() {
  const fixture = window.__searchFixture = {
    calls: [], requests: [], unexpected: [], searchKeywords: ['历史词'], plans: [],
    activeSearch: 0, maxActiveSearch: 0, nowOffset: 0, webEnabled: false, options: { quality: '1080p', speed: 1 },
  };
  const realNow = Date.now;
  Date.now = () => realNow() + fixture.nowOffset;
  const sid = (keyword, index) => String(1000000 + [...keyword].reduce((sum, c) => sum + c.codePointAt(0), 0) * 100 + index);
  const row = (keyword, index) => ({ series_id: sid(keyword, index), series_name: `${keyword} 第${index}项`, series_title: `${keyword} 第${index}项`, episode_cnt: 4, series_intro: '离线搜索分页测试', tags: ['合成'], category_list: [{ name: '合成' }], series_cover: '' });
  const html = data => `<!doctype html><script>window._ROUTER_DATA=${JSON.stringify(data)}</script>`;
  fixture.protocol = function protocol(url) {
    if (url.pathname.startsWith('/category/')) return { kind: 'catalog', body: html({ loaderData: { category: { categoryRoute: { contentType: 'real-drama' }, isSuccess: true, recommendList: [row('首页', 1)], pagination: { pageNum: 1, totalPages: 1, total: 1 } } } }) };
    if (url.pathname.startsWith('/player/') || url.pathname === '/detail') {
      const seriesId = url.pathname === '/detail' ? url.searchParams.get('series_id') : url.pathname.split('/')[2];
      return { kind: 'detail', body: html({ loaderData: { 'player_(series_id)/page': { isSuccess: true, series_id: seriesId, seriesDetail: { ...row('详情', 1), series_id: seriesId, vid_list: ['20000001','20000002','20000003','20000004'], accessible_episode_cnt: 4 } } } }) };
    }
    // The old 0.11.5 baseline may load its real SSR first page. Query parameters
    // never create invented later SSR pages: the observed site repeats ten rows.
    if (url.pathname.startsWith('/search/')) {
      const keyword = decodeURIComponent(url.pathname.slice('/search/'.length));
      return { kind: 'search', keyword, page: 1, body: html({ loaderData: { 'search_(keyword)/page': { isSuccess: true, totalCount: '211', searchList: Array.from({ length: 10 }, (_, n) => ({ video_data: row(keyword, n + 1) })) } } }) };
    }
    if (url.pathname === '/api/search/result') {
      const keyword=url.searchParams.get('query'),page=Number(url.searchParams.get('page'));
      const start=page===1?1:11,count=page===1?10:2;
      return {kind:'search-web',keyword,page,status:fixture.webEnabled?200:503,body:JSON.stringify({searchList:Array.from({length:count},(_,n)=>({video_data:row(keyword,start+n)})),pageNum:page,totalCount:12,totalPages:2,hasMore:page===1})};
    }
    throw Error('UNEXPECTED_HTTP_PATH ' + url.pathname);
  };
  const appSearch = (params) => {
    const { keyword, offset, searchId, passback } = params;
    if (typeof keyword !== 'string' || keyword.trim() !== keyword || !keyword || ![0, 20, 40].includes(offset)) throw Error('UNEXPECTED_SEARCH_PARAMS ' + JSON.stringify(params));
    const cursorId = 'fixture-search-' + keyword;
    if (offset > 0 && (searchId !== cursorId || passback !== String(offset))) throw Error('UNEXPECTED_SEARCH_CURSOR ' + JSON.stringify(params));
    if (!offset && (searchId || passback)) throw Error('UNEXPECTED_FIRST_PAGE_CURSOR ' + JSON.stringify(params));
    const page = offset / 20 + 1;
    const count = page === 3 ? 5 : page === 2 ? 19 : 20;
    const data = Array.from({ length: count }, (_, n) => ({ video_data: [{ series_id: sid(keyword, offset + n + 1), title: `${keyword} 第${offset + n + 1}项`, cover: '', episode_cnt: 4 }] }));
    // A real full provider page may contain a non-video cell. That yields 19
    // displayable cards, but has_more=true and next_offset=40 still advance it.
    if (page === 2) data.push({ cell_type: 'non_video', cell_name: '合成非视频单元' });
    return { keyword, page, result: { code: 0, search_tabs: [{ tab_type: 11, data, has_more: page < 3, next_offset: offset + 20, search_id: cursorId, passback: String(offset + 20) }] } };
  };
  const planFor = response => {
    const i = fixture.plans.findIndex(plan => (!plan.keyword || plan.keyword === response.keyword) && (!plan.page || plan.page === response.page));
    return i >= 0 ? fixture.plans.splice(i, 1)[0] : {};
  };
  window.AndroidBridge = { postMessage(raw) {
    const message = JSON.parse(raw); fixture.calls.push(message);
    let result, error, delay = 1, isSearch = false;
    try {
      if (message.method === 'searchQuery') {
        const response = appSearch(message.params); isSearch = true;
        fixture.requests.push({ kind: 'search', method: 'searchQuery', keyword: response.keyword, page: response.page, offset: message.params.offset, searchId: message.params.searchId, passback: message.params.passback });
        fixture.activeSearch++; fixture.maxActiveSearch = Math.max(fixture.maxActiveSearch, fixture.activeSearch);
        const plan = planFor(response); delay = plan.delay || 1;
        error = plan.error || (plan.status && plan.status !== 200 ? '搜索接口响应 HTTP ' + plan.status : undefined);
        result = response.result;
      } else if (message.method === 'httpGet') {
        const url = new URL(message.params.url);
        if (url.origin !== 'https://hongguoduanju.com') throw Error('UNEXPECTED_HTTP_ORIGIN ' + url.origin);
        const response = fixture.protocol(url); isSearch = response.kind === 'search';
        fixture.requests.push({ url: url.href, kind: response.kind, method: 'httpGet', keyword: response.keyword, page: response.page });
        if (isSearch) {
          fixture.activeSearch++; fixture.maxActiveSearch = Math.max(fixture.maxActiveSearch, fixture.activeSearch);
          const plan = planFor(response); delay = plan.delay || 1; error = plan.error;
          result = { status: plan.status || 200, body: response.body };
        } else result = { status: response.status || 200, body: response.body };
      } else if (message.method === 'cancelHttp') result = { cancelled: true };
      else if (message.method === 'getInfo') result = { isTv: new URL(location.href).searchParams.get('tv') === '1', version: 'SYNTHETIC-search-pagination' };
      else if (message.method === 'getLibrary') result = { favorites: [], history: [] };
      else if (message.method === 'getPlaybackOptions') result = { ...fixture.options };
      else if (message.method === 'getSearchHistory') result = { keywords: [...fixture.searchKeywords] };
      else if (message.method === 'addSearchHistory') { fixture.searchKeywords = [message.params.keyword, ...fixture.searchKeywords.filter(k => k !== message.params.keyword)].slice(0, 20); result = { keywords: [...fixture.searchKeywords] }; }
      else if (message.method === 'removeSearchHistory') { fixture.searchKeywords = fixture.searchKeywords.filter(k => k !== message.params.keyword); result = { keywords: [...fixture.searchKeywords] }; }
      else if (message.method === 'clearSearchHistory') { fixture.searchKeywords = []; result = { keywords: [] }; }
      else if (message.method === 'getPlaybackCache') result = { files: 0, bytes: 0 };
      else throw Error('UNEXPECTED_RPC ' + message.method);
    } catch (cause) { error = cause.message; fixture.unexpected.push(error); }
    setTimeout(() => { if (isSearch) fixture.activeSearch--; window.__androidResponse({ id: message.id, ...(error ? { error } : { result }) }); }, delay);
  } };
}
fs.mkdirSync(output, { recursive: true });
const temporary = fs.mkdtempSync(path.join(os.tmpdir(), 'hongguo-search-pagination-ui-'));
const report = { kind: 'real React App + real target bridge/source, synthetic native searchQuery/HTTP replies; offline browser regression, not device QA', root, tests: [], screenshots: [], externalRequests: [], pageErrors: [], nativeUnexpected: [], nativeRequests: [], sourceHashes: Object.fromEntries(['src/android/App.jsx','src/android/source.mjs','src/android/bridge.mjs','src/android/search.mjs'].filter(file => fs.existsSync(path.join(root,file))).map(file => [file, createHash('sha256').update(fs.readFileSync(path.join(root,file))).digest('hex')])) };
let chrome, server, ws, chromeLog;
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
let passed = 0, failed = 0, checks = 0;
const check = (value, message) => { checks++; assert.ok(value, message); };
const eq = (actual, expected, message) => { checks++; assert.deepEqual(actual, expected, message); };
try {
  const adapter = path.join(root, 'src/android/mac-source');
  const bundled = await build({
    stdin: { contents: `import React from 'react';import {createRoot} from 'react-dom/client';import App from ${JSON.stringify(path.join(root, 'src/android/App.jsx'))};createRoot(document.getElementById('root')).render(React.createElement(App));`, resolveDir: ownRoot, sourcefile: 'search-pagination-fixture-entry.jsx', loader: 'jsx' },
    bundle: true, write: false, format: 'iife', platform: 'browser', target: 'chrome100', nodePaths: [path.join(ownRoot, 'node_modules')],
    inject: [path.join(adapter, 'buffer-runtime.mjs')],
    alias: { 'node:crypto': path.join(adapter, 'crypto-shim.mjs'), axios: path.join(adapter, 'axios-common.cjs') },
    define: { 'AbortSignal.timeout': 'androidAppTimeout' },
    plugins: [{ name: 'real-adapter-and-license', setup(builder) {
      builder.onResolve({ filter: /generated\/mac-app-source\.mjs$/ }, () => ({ path: path.join(adapter, 'entry.mjs') }));
      builder.onResolve({ filter: /LICENSE\?raw$/ }, () => ({ path: 'license', namespace: 'literal' }));
      builder.onLoad({ filter: /.*/, namespace: 'literal' }, () => ({ contents: `export default ${JSON.stringify(fs.readFileSync(path.join(root, 'LICENSE'), 'utf8'))}`, loader: 'js' }));
    } }],
  });
  const js = bundled.outputFiles[0].text, css = fs.readFileSync(path.join(root, 'src/android/style.css'), 'utf8');
  const fixtureHtml = fs.readFileSync(path.join(root, 'src/android/index.html'), 'utf8')
    .replace(/<script\b[^>]*\bdata-hongguo-boot\b[^>]*><\/script>/, '')
    .replace(/<link\b[^>]*href="\/startup\.css"[^>]*>/, '')
    .replace(/<script[^>]+src="\/main\.jsx"[^>]*><\/script>/, '<script src="/fixture.js"></script>')
    .replace('</head>', '<link rel="stylesheet" href="/fixture.css"></head>');
  server = http.createServer((request, response) => {
    if (request.url === '/fixture.js') { response.setHeader('content-type', 'text/javascript'); response.end(js); }
    else if (request.url === '/fixture.css') { response.setHeader('content-type', 'text/css'); response.end(css); }
    else { response.setHeader('content-type', 'text/html'); response.end(fixtureHtml); }
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const base = `http://127.0.0.1:${server.address().port}/`, profile = path.join(temporary, 'profile');
  chromeLog = fs.openSync(path.join(output, 'chrome.log'), 'w');
  chrome = spawn(process.env.CHROME_BIN || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome', ['--headless=new', '--remote-debugging-port=0', `--user-data-dir=${profile}`, '--no-first-run', '--disable-background-networking', '--disable-default-apps', '--disable-sync', '--disable-component-update', '--no-default-browser-check', '--host-resolver-rules=MAP * ~NOTFOUND, EXCLUDE 127.0.0.1', 'about:blank'], { stdio: ['ignore', 'ignore', chromeLog] });
  let port;
  for (let i = 0; i < 100; i++) { try { port = Number(fs.readFileSync(path.join(profile, 'DevToolsActivePort'), 'utf8').split('\n')[0]); if (port) break; } catch {} await sleep(100); }
  if (!port) throw Error('Chrome CDP did not start');
  const target = await fetch(`http://127.0.0.1:${port}/json/new?about:blank`, { method: 'PUT' }).then(r => r.json());
  ws = new WebSocket(target.webSocketDebuggerUrl);
  await new Promise((resolve, reject) => { ws.addEventListener('open', resolve, { once: true }); ws.addEventListener('error', reject, { once: true }); });
  let sequence = 0; const pending = new Map();
  const call = (method, params = {}) => new Promise((resolve, reject) => { const id = ++sequence; pending.set(id, { resolve, reject }); ws.send(JSON.stringify({ id, method, params })); });
  ws.addEventListener('message', ({ data }) => {
    const message = JSON.parse(data);
    if (message.id) { const p = pending.get(message.id); if (p) { pending.delete(message.id); message.error ? p.reject(Error(JSON.stringify(message.error))) : p.resolve(message.result); } }
    if (message.method === 'Runtime.exceptionThrown') report.pageErrors.push(message.params.exceptionDetails.exception?.description || message.params.exceptionDetails.text);
    if (message.method === 'Fetch.requestPaused') {
      const { requestId, request } = message.params, allowed = request.url.startsWith(base);
      if (!allowed) report.externalRequests.push(request.url);
      call(allowed ? 'Fetch.continueRequest' : 'Fetch.failRequest', allowed ? { requestId } : { requestId, errorReason: 'BlockedByClient' }).catch(error => report.pageErrors.push(error.message));
    }
  });
  const evaluate = async expression => { const result = await call('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true }); if (result.exceptionDetails) throw Error(result.exceptionDetails.exception?.description || result.exceptionDetails.text); return result.result.value; };
  const wait = async (predicate, label = predicate) => { for (let i = 0; i < 120; i++) { if (await evaluate(`Boolean(${predicate})`)) return; await sleep(25); } throw Error('WAIT_FAILED ' + label); };
  const collectNative = async () => { const f = await evaluate('window.__searchFixture?{unexpected:window.__searchFixture.unexpected,requests:window.__searchFixture.requests}:null'); if (f) { report.nativeUnexpected.push(...f.unexpected); report.nativeRequests.push(...f.requests); } };
  const reset = async ({ tv = false, width = 390, height = 844, large = false } = {}) => {
    await collectNative();
    await call('Emulation.setDeviceMetricsOverride', { width: tv ? 1280 : width, height: tv ? 720 : height, deviceScaleFactor: 1, mobile: !tv });
    await call('Emulation.setTouchEmulationEnabled', { enabled: !tv });
    await call('Page.navigate', { url: base + (tv ? '?tv=1' : '') });
    await wait('document.querySelector(".poster")');
    await evaluate('[...document.querySelectorAll(".app-nav button")].find(b=>b.textContent.trim()==="搜索").click()');
    await wait('document.querySelector("#search-keyword")&&document.querySelector(".history-keyword")');
    if (large) await evaluate('document.documentElement.style.fontSize="24px"');
  };
  const submit = async keyword => {
    await evaluate(`(()=>{const input=document.querySelector('#search-keyword');Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set.call(input,${JSON.stringify(keyword)});input.dispatchEvent(new Event('input',{bubbles:true}));})()`);
    await evaluate('document.querySelector(".search-form").requestSubmit()');
  };
  const caption = () => evaluate('document.querySelector(".result-caption")?.textContent||""');
  const titles = () => evaluate('[...document.querySelectorAll(".page-slot .poster strong")].map(e=>e.textContent)');
  const requests = () => evaluate('window.__searchFixture.requests.filter(r=>r.kind==="search").map(r=>({keyword:r.keyword,page:r.page}))');
  const historyAdds = () => evaluate('window.__searchFixture.calls.filter(c=>c.method==="addSearchHistory").map(c=>c.params.keyword)');
  const button = (label, scope = '') => evaluate(`(()=>{const b=[...document.querySelectorAll(${JSON.stringify(scope + ' button')})].find(b=>b.textContent.trim()===${JSON.stringify(label)});if(!b)throw Error('missing button: '+${JSON.stringify(label)});b.click()})()`);
  const pagination = () => evaluate('(()=>{const p=document.querySelector(".search-pagination");return p?{text:p.textContent,buttons:[...p.querySelectorAll("button")].map(b=>({text:b.textContent.trim(),disabled:b.disabled})),status:p.querySelector(".search-page-status")?.textContent}:null})()');
  const waitPage = async (keyword, page, length = page === 3 ? 5 : page === 2 ? 19 : 20) => {
    // Baseline SSR may show its ten first-page cards; later assertions reject
    // the missing pagination without mislabelling baseline as a boot failure.
    const count = page === 1 ? 'document.querySelectorAll(".page-slot .poster strong").length>0' : `document.querySelectorAll('.page-slot .poster strong').length===${length}`;
    await wait(`${count}&&document.querySelector('.page-slot .poster strong')?.textContent===${JSON.stringify(keyword + ' 第' + ((page - 1) * 20 + 1) + '项')}`);
  };
  const noOverflow = async () => {
    const sizes = await evaluate('[document.documentElement,document.querySelector(".main-content"),document.querySelector(".search-pagination")].filter(Boolean).map(e=>({node:e.className||"html",width:e.clientWidth,scroll:e.scrollWidth}))');
    check(sizes.every(s => s.scroll <= s.width + 1), 'no horizontal overflow ' + JSON.stringify(sizes));
  };
  const screenshot = async name => { const file = path.join(output, name); fs.writeFileSync(file, Buffer.from((await call('Page.captureScreenshot', { format: 'png', captureBeyondViewport: false })).data, 'base64')); report.screenshots.push(file); };
  const group = async (name, fn) => { try { await fn(); passed++; report.tests.push({ name, result: 'PASS' }); console.log('PASS ' + name); } catch (error) { failed++; report.tests.push({ name, result: 'FAIL', error: error.message }); console.log('FAIL ' + name + ': ' + error.message); } };
  await call('Page.enable'); await call('Runtime.enable'); await call('Network.enable');
  await call('Fetch.enable', { patterns: [{ urlPattern: '*' }] });
  await call('Page.addScriptToEvaluateOnNewDocument', { source: `(${installFixture.toString()})();` });

  await group('FUNCTION real source retrieves all three pages and disables first/last boundaries', async () => {
    await reset(); await submit('分页'); await waitPage('分页', 1);
    await screenshot('phone-first-page.png');
    const first = await pagination(); check(first, 'search pagination is rendered after real source parse');
    eq(first.buttons, [{ text: '上一页', disabled: true }, { text: '下一页', disabled: false }], 'first-page boundary');
    check((await caption()).includes('本页 20 个结果') && !(await caption()).includes('共 '), 'unknown provider total is not fabricated');
    eq((await titles()).length, 20, 'real source parses twenty first-page videos');
    await button('下一页', '.search-pagination'); await waitPage('分页', 2);
    eq((await pagination()).buttons.map(b => b.disabled), [false, false], 'middle-page controls');
    eq(await titles(), Array.from({ length: 19 }, (_, n) => `分页 第${n + 21}项`), 'nineteen videos plus non-video cell do not falsely terminate next-page availability');
    await button('下一页', '.search-pagination'); await waitPage('分页', 3);
    eq((await pagination()).buttons.map(b => b.disabled), [false, true], 'partial last page has no further request');
    await button('下一页', '.search-pagination');
    eq(await requests(), [{ keyword: '分页', page: 1 }, { keyword: '分页', page: 2 }, { keyword: '分页', page: 3 }], 'all three real source requests, disabled next sends none');
    eq(await evaluate('window.__searchFixture.calls.filter(c=>c.method==="searchQuery").map(c=>({offset:c.params.offset,searchId:c.params.searchId||"",passback:c.params.passback||""}))'), [{offset:0,searchId:'',passback:''},{offset:20,searchId:'fixture-search-分页',passback:'20'},{offset:40,searchId:'fixture-search-分页',passback:'40'}], 'real source forwards exact provider offset/searchId/passback cursors');
    await evaluate('document.querySelector(".search-pagination").scrollIntoView({block:"end"})'); await screenshot('phone-last-page.png');
    await button('上一页', '.search-pagination'); await waitPage('分页', 2);
    await button('上一页', '.search-pagination'); await waitPage('分页', 1);
    eq(await historyAdds(), ['分页'], 'page navigation does not duplicate native search history writes');
    eq((await requests()).length, 3, 'previous-page revisit reuses correctly keyed real source cache');
  });
  await group('FUNCTION new keyword, same-keyword submit and saved-history selection each reset page one', async () => {
    await reset(); await submit('原词'); await waitPage('原词', 1); await button('下一页', '.search-pagination'); await waitPage('原词', 2);
    await submit('原词'); await waitPage('原词', 1); check((await pagination()).buttons[0].disabled, 'same query restarts page one');
    eq((await requests()).filter(r => r.keyword === '原词' && r.page === 1).length, 2, 'explicit same-keyword submit refreshes native results rather than retaining stale cached page');
    await button('下一页', '.search-pagination'); await waitPage('原词', 2); await submit('新词'); await waitPage('新词', 1);
    await button('下一页', '.search-pagination'); await waitPage('新词', 2);
    await evaluate('[...document.querySelectorAll(".history-keyword")].find(b=>b.textContent==="历史词").click()'); await waitPage('历史词', 1);
    eq(await evaluate('document.querySelector("#search-keyword").value'), '历史词', 'history selection populates input');
    eq(await historyAdds(), ['原词', '原词', '新词', '历史词'], 'only explicit submitted searches persist history');
  });
  await group('FUNCTION failing second page retries exact same keyword and page without writing history', async () => {
    await reset(); await submit('重试'); await waitPage('重试', 1);
    await evaluate('window.__searchFixture.plans=[{keyword:"重试",page:2,status:503}]');
    await button('下一页', '.search-pagination'); await wait('document.querySelector(".error-state")');
    check(await evaluate('document.querySelector(".error-state").textContent.includes("503")'), 'HTTP failure shown rather than claiming no results');
    check((await pagination()).status.includes('2'), 'failed target page stays visible');
    await screenshot('phone-page-two-error.png'); await button('重新加载', '.error-state'); await waitPage('重试', 2);
    eq(await requests(), [{ keyword: '重试', page: 1 }, { keyword: '重试', page: 2 }, { keyword: '重试', page: 2 }], 'retry uses exact failed page');
    eq(await historyAdds(), ['重试'], 'retry never re-adds query to history');
  });
  await group('FUNCTION expired cursor offers fresh search and handles synchronous pager rejection', async () => {
    await reset(); await submit('过期'); await waitPage('过期', 1);
    await evaluate('window.__searchFixture.nowOffset=300001');
    await button('下一页', '.search-pagination'); await wait('document.querySelector(".error-state")');
    check(await evaluate('document.querySelector(".error-state").textContent.includes("搜索已过期")'), 'expired page is shown as recoverable search state');
    eq(await requests(), [{ keyword: '过期', page: 1 }], 'expired cursor is rejected locally without stale native request');
    await button('重新搜索', '.error-state'); await waitPage('过期', 1);
    eq(await requests(), [{ keyword: '过期', page: 1 }, { keyword: '过期', page: 1 }], 'restart makes one fresh first-page request');
    await button('下一页', '.search-pagination'); await waitPage('过期', 2);
    eq((await pagination()).buttons.map(b => b.disabled), [false, false], 'refreshed search pages continue after expiration');
    eq(await historyAdds(), ['过期', '过期'], 'only explicit restart is recorded, not expiration or paging');
  });
  await group('RACE rapid next-page confirms single-flight without skipping page', async () => {
    await reset(); await submit('连点'); await waitPage('连点', 1);
    await evaluate('window.__searchFixture.plans=[{keyword:"连点",page:2,delay:350}];const b=[...document.querySelectorAll(".search-pagination button")].find(b=>b.textContent.trim()==="下一页");b.click();b.click();b.click();');
    await wait('window.__searchFixture.activeSearch===1');
    eq((await pagination()).buttons.map(b => b.disabled), [true, true], 'both page controls disabled while loading');
    await waitPage('连点', 2); eq(await requests(), [{ keyword: '连点', page: 1 }, { keyword: '连点', page: 2 }], 'one target page, one native request');
    eq(await evaluate('window.__searchFixture.maxActiveSearch'), 1, 'same query paging requests never overlap');
  });
  await group('RACE delayed old keyword and old page cannot overwrite new results or error state', async () => {
    await reset(); await evaluate('window.__searchFixture.plans=[{keyword:"慢词",page:1,delay:450}]');
    await submit('慢词'); await wait('window.__searchFixture.activeSearch===1'); await submit('快词'); await waitPage('快词', 1); await sleep(500);
    check((await caption()).includes('快词'), 'late first page retains new keyword'); eq((await titles())[0], '快词 第1项', 'late old first page ignored');
    await evaluate('window.__searchFixture.plans=[{keyword:"快词",page:2,delay:450,status:502}]'); await button('下一页', '.search-pagination'); await wait('window.__searchFixture.activeSearch===1');
    await submit('第三词'); await waitPage('第三词', 1); await sleep(500);
    check((await caption()).includes('第三词'), 'late paging error keeps latest keyword'); eq(await evaluate('!!document.querySelector(".error-state")'), false, 'late previous query error is ignored');
    eq((await titles())[0], '第三词 第1项', 'new first page remains rendered');
  });
  await group('FUNCTION opening detail and native back preserves current query page and focus', async () => {
    await reset(); await submit('返回'); await waitPage('返回', 1); await button('下一页', '.search-pagination'); await waitPage('返回', 2);
    await evaluate('const p=document.querySelectorAll(".page-slot .poster")[3];p.focus();p.click()'); await wait('document.querySelector(".detail-open .back-button")&&document.querySelector(".episode-grid button")');
    eq(await evaluate('window.__androidBack()'), true, 'native back consumes detail'); await wait('!document.querySelector(".detail-open")'); await waitPage('返回', 2);
    eq(await evaluate('document.activeElement.getAttribute("aria-label")'), '打开返回 第24项', 'focus restored to exact prior page card');
    eq(await historyAdds(), ['返回'], 'detail visit never adds history'); eq((await requests()).filter(r => r.keyword === '返回').length, 2, 'detail return does not reload first page');
  });
  await group('TV D-pad navigation reaches page controls and Enter advances exact page once', async () => {
    await reset({ tv: true }); await submit('电视'); await waitPage('电视', 1);
    check(await evaluate('document.documentElement.dataset.tv==="true"'), 'real TV layout from native info');
    await evaluate('[...document.querySelectorAll(".search-pagination button")].find(b=>b.textContent.trim()==="下一页").focus()');
    await call('Input.dispatchKeyEvent', { type: 'keyDown', key: 'Enter', code: 'Enter', windowsVirtualKeyCode: 13 });
    await call('Input.dispatchKeyEvent', { type: 'keyUp', key: 'Enter', code: 'Enter', windowsVirtualKeyCode: 13 }); await waitPage('电视', 2);
    eq(await requests(), [{ keyword: '电视', page: 1 }, { keyword: '电视', page: 2 }], 'TV Enter dispatches next page only once');
    await wait('document.activeElement.matches(".poster")'); eq(await evaluate('document.activeElement.getAttribute("aria-label")'), '打开电视 第21项', 'remote focus is restored to first result of the new page');
    await evaluate('[...document.querySelectorAll(".search-pagination button")].find(b=>b.textContent.trim()==="上一页").focus()');
    await call('Input.dispatchKeyEvent', { type: 'keyDown', key: 'ArrowRight', code: 'ArrowRight', windowsVirtualKeyCode: 39 });
    await call('Input.dispatchKeyEvent', { type: 'keyUp', key: 'ArrowRight', code: 'ArrowRight', windowsVirtualKeyCode: 39 });
    eq(await evaluate('document.activeElement.textContent.trim()'), '下一页', 'D-pad right focuses next page without loading it');
    eq((await titles())[0], '电视 第21项', 'focus navigation does not trigger search');
    await call('Input.dispatchKeyEvent', { type: 'keyDown', key: 'Enter', code: 'Enter', windowsVirtualKeyCode: 13 });
    await call('Input.dispatchKeyEvent', { type: 'keyUp', key: 'Enter', code: 'Enter', windowsVirtualKeyCode: 13 }); await waitPage('电视', 3);
    await evaluate('document.querySelector(".search-pagination").scrollIntoView({block:"end"})'); await noOverflow(); await screenshot('tv-search-last-page.png');
  });
  await group('LAYOUT small phones and enlarged text keep pagination reachable without horizontal overflow', async () => {
    for (const [width, height, large] of [[320,640,false],[390,844,false],[320,844,true],[844,390,false]]) {
      await reset({ width, height, large }); await submit('布局'); await waitPage('布局', 1); check(await pagination(), 'pagination present on small screen');
      await noOverflow(); await evaluate('document.querySelector(".search-pagination").scrollIntoView({block:"end"})'); await sleep(40);
      const bounds = await evaluate('({width:innerWidth,nav:document.querySelector(".app-nav").getBoundingClientRect().toJSON(),main:document.querySelector(".main-content").getBoundingClientRect().toJSON(),buttons:[...document.querySelectorAll(".search-pagination button")].map(b=>b.getBoundingClientRect().toJSON())})');
      check(bounds.buttons.every(b => b.width >= 44 && b.height >= 44), 'pagination touch targets at least 44 pixels');
      check(bounds.buttons.every(b => b.left >= 0 && b.right <= bounds.width && b.top >= bounds.main.top - 1 && b.bottom <= bounds.nav.top + 1), 'pagination visible above phone bottom navigation ' + JSON.stringify(bounds));
      if (width === 320 && !large) {
        const b = bounds.buttons[1], point = { x: b.x + b.width / 2, y: b.y + b.height / 2, radiusX: 5, radiusY: 5, force: 1, id: 1 };
        await call('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [point] }); await call('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] }); await waitPage('布局', 2);
        await noOverflow(); await evaluate('document.querySelector(".search-pagination").scrollIntoView({block:"end"})');
      }
      await screenshot(`phone-${width}x${height}${large ? '-large-text' : ''}-search.png`);
    }
  });
  await group('FUNCTION real App fallback uses official JSON and keeps its own pagination channel', async () => {
    await reset();await evaluate('window.__searchFixture.webEnabled=true;window.__searchFixture.plans=[{keyword:"备用",page:1,status:503}]');
    await submit('备用');await wait('document.querySelectorAll(".page-slot .poster strong").length===10&&!document.querySelector("[aria-label=搜索结果]").getAttribute("aria-busy").includes("true")');
    check(await evaluate('[...document.querySelectorAll(".result-caption")].some(n=>n.textContent.includes("官网搜索"))'),'fallback channel is visible');
    await button('下一页','.search-pagination');await wait('document.querySelectorAll(".page-slot .poster strong").length===2');
    eq((await titles())[0],'备用 第11项','official page two rendered');eq((await requests()).filter(x=>x.keyword==='备用').length,1,'only first page tried App');
    eq(await evaluate('window.__searchFixture.requests.filter(x=>x.kind==="search-web").map(x=>x.page)'),[1,2],'official pages use fixed numeric channel');
    await button('上一页','.search-pagination');await wait('document.querySelectorAll(".page-slot .poster strong").length===10');
    await screenshot('phone-official-search-fallback.png');
  });
  await group('ISOLATION real source and bridge made no unexpected RPC or external browser request', async () => {
    await collectNative();
    eq(report.nativeUnexpected, [], 'only supported synthetic protocol routes were requested across every test navigation');
    eq(report.externalRequests, [], 'zero non-loopback browser requests'); eq(report.pageErrors, [], 'zero runtime exceptions');
  });
  await call('Page.close'); ws.close(); ws = null;
} catch (error) {
  failed++; report.tests.push({ name: 'runner', result: 'FAIL', error: error.stack }); console.error(error.stack);
} finally {
  if (ws) try { ws.close(); } catch {}
  if (server) await new Promise(resolve => server.close(resolve));
  if (chrome) { chrome.kill('SIGTERM'); await Promise.race([new Promise(resolve => chrome.once('exit', resolve)), sleep(4000)]); if (chrome.exitCode === null) { chrome.kill('SIGKILL'); await Promise.race([new Promise(resolve => chrome.once('exit', resolve)), sleep(1000)]); } }
  if (chromeLog !== undefined) fs.closeSync(chromeLog);
  fs.rmSync(temporary, { recursive: true, force: true });
  report.summary = { groupsPassed: passed, groupsFailed: failed, assertions: checks, networkRequests: report.externalRequests.length, deviceTests: 'not_run', adb: 'not_used', emulator: 'not_used', cleanup: 'own loopback server and Chrome stopped; temporary profile removed' };
  fs.writeFileSync(path.join(output, 'results.json'), JSON.stringify(report, null, 2) + '\n');
  console.log(`ANDROID_SEARCH_PAGINATION_UI_${failed ? 'FAILED' : 'OK'} groupsPassed=${passed} groupsFailed=${failed} assertions=${checks} networkRequests=${report.externalRequests.length} deviceTests=not_run`);
  console.log('SEARCH_UI_CLEANUP ownServer=stopped ownChrome=stopped ownProfile=removed adb=not_used emulator=not_used');
}
process.exitCode = failed ? 1 : 0;
