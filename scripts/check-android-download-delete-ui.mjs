#!/usr/bin/env node
/** Real App/React and JSON bridge, isolated fake native RPC; never touches device files. */
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import http from 'node:http';
import { spawn } from 'node:child_process';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import assert from 'node:assert/strict';
const own = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
let root = own, output = path.join(own, 'verification/download-delete-ui'), baseline = false;
for (let i = 2; i < process.argv.length; i++) {
  if (process.argv[i] === '--root') root = path.resolve(process.argv[++i]);
  else if (process.argv[i] === '--output') output = path.resolve(process.argv[++i]);
  else if (process.argv[i] === '--baseline') baseline = true;
  else throw Error('Usage: --root PROJECT --output DIRECTORY [--baseline]');
}
fs.mkdirSync(output, { recursive: true });
const { build } = createRequire(import.meta.url)('esbuild');
function install() {
  const f = window.__downloads = { calls: [], plans: [], held: [], pollers: new Set(), concurrentReads: 0, maxReads: 0,
    downloads: [
      { id: 101, title: '下载甲', index: 1, status: 'success', downloadedBytes: 1024, totalBytes: 1024 },
      { id: 102, title: '下载乙', index: 2, status: 'running', downloadedBytes: 512, totalBytes: 1024 },
      { id: 103, title: '下载丙', index: 3, status: 'failed', reason: '文件缺失', downloadedBytes: 0, totalBytes: 0 },
    ] };
  const params = new URL(location.href).searchParams;
  const set = window.setTimeout.bind(window);
  window.setTimeout = (fn, ms, ...args) => set(fn, params.has('timeout') && ms === 45000 ? 90 : ms, ...args);
  const interval = window.setInterval.bind(window), clear = window.clearInterval.bind(window);
  window.setInterval = (fn, ms, ...args) => { if (ms !== 2500) return interval(fn, ms, ...args); f.pollers.add(fn); return fn; };
  window.clearInterval = id => typeof id === 'function' ? f.pollers.delete(id) : clear(id);
  f.poll = () => f.pollers.forEach(fn => fn());
  window.AndroidBridge = { postMessage(raw) {
    const call = JSON.parse(raw); f.calls.push(call);
    const at = f.plans.findIndex(p => p.method === call.method);
    const plan = at < 0 ? {} : f.plans.splice(at, 1)[0];
    let result = call.method === 'getInfo' ? { isTv: params.has('tv'), version: 'OFFLINE' }
      : call.method === 'getLibrary' ? { favorites: [{ seriesId: '111111', title: '保留收藏' }], history: [{ seriesId: '222222', title: '保留历史' }] }
      : call.method === 'getDownloads' ? JSON.parse(JSON.stringify(f.downloads))
      : call.method === 'deleteDownload' ? { id: call.params.id, deleted: true }
      : call.method === 'getSearchHistory' ? { keywords: [] } : {};
    if (Object.hasOwn(plan, 'result')) result = plan.result;
    if (call.method === 'getDownloads') { f.concurrentReads++; f.maxReads = Math.max(f.maxReads, f.concurrentReads); }
    const respond = () => {
      if (call.method === 'getDownloads') f.concurrentReads--;
      if (call.method === 'deleteDownload' && !plan.error && result?.deleted === true && result.id === call.params.id) f.downloads = f.downloads.filter(row => row.id !== call.params.id);
      window.__androidResponse({ id: call.id, ...(plan.error ? { error: plan.error } : { result }) });
    };
    if (plan.hold || plan.noResponse) f.held.push({ method: call.method, respond }); else set(respond, plan.delay || 5);
  } };
}
const appPath = path.join(root, 'src/android/App.jsx');
const bundled = await build({ stdin: { contents: `import React from 'react';import {createRoot} from 'react-dom/client';import App from ${JSON.stringify(appPath)};const root=createRoot(document.getElementById('root'));window.__unmount=()=>root.unmount();root.render(React.createElement(App));`, resolveDir: own, loader: 'jsx' }, bundle: true, write: false, platform: 'browser', format: 'iife', target: 'chrome100', nodePaths: [path.join(own, 'node_modules')], plugins: [{ name: 'offline-source-only', setup(b) {
  b.onResolve({ filter: /source\.mjs$/ }, a => a.importer === appPath && a.path === './source.mjs' ? { path: 'source', namespace: 'fixture' } : null);
  b.onResolve({ filter: /generated\/mac-app-source\.mjs$/ }, () => ({ path: 'generated', namespace: 'fixture' }));
  b.onResolve({ filter: /LICENSE\?raw$/ }, () => ({ path: 'license', namespace: 'fixture' }));
  b.onLoad({ filter: /.*/, namespace: 'fixture' }, a => ({ loader: 'js', contents: a.path === 'source' ? `export const CATEGORIES=[{id:'real-drama',label:'真人剧'}];export function createSource(){return {catalog:async()=>({items:[{seriesId:'100001',title:'测试目录'}],page:1,totalPages:1}),search:async()=>({items:[]}),detail:()=>Promise.reject(Error('not exercised'))}}` : a.path === 'generated' ? `export const fetchAppPlayUrl=()=>Promise.reject(Error('not exercised'));` : `export default 'offline license';` }));
} }] });
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'hongguo-delete-ui-'));
const report = { root, boundary: 'actual React App and actual nativeCall; fake AndroidBridge only', tests: [], screenshots: [], pageErrors: [], externalRequests: [] };
let chrome, server, ws, log, passed = 0, failed = 0, assertions = 0;
const sleep = ms => new Promise(r => setTimeout(r, ms));
const check = (b, label) => { assertions++; assert.ok(b, label); };
try {
  const js = bundled.outputFiles[0].text, css = fs.readFileSync(path.join(root, 'src/android/style.css'), 'utf8');
  server = http.createServer((q, r) => { r.setHeader('content-type', q.url === '/app.js' ? 'text/javascript' : q.url === '/style.css' ? 'text/css' : 'text/html'); r.end(q.url === '/app.js' ? js : q.url === '/style.css' ? css : '<!doctype html><meta name="viewport" content="width=device-width,initial-scale=1"><link rel="stylesheet" href="/style.css"><div id="root"></div><script src="/app.js"></script>'); });
  await new Promise(r => server.listen(0, '127.0.0.1', r));
  const base = 'http://127.0.0.1:' + server.address().port + '/', profile = path.join(tmp, 'profile');
  log = fs.openSync(path.join(output, 'chrome.log'), 'w');
  chrome = spawn('/Applications/Google Chrome.app/Contents/MacOS/Google Chrome', ['--headless=new', '--remote-debugging-port=0', '--user-data-dir=' + profile, '--no-first-run', '--disable-background-networking', '--disable-sync', '--disable-component-update', '--host-resolver-rules=MAP * ~NOTFOUND, EXCLUDE 127.0.0.1', 'about:blank'], { stdio: ['ignore', 'ignore', log] });
  let port;
  for (let i = 0; i < 100; i++) { try { port = Number(fs.readFileSync(path.join(profile, 'DevToolsActivePort'), 'utf8').split('\n')[0]); if (port) break; } catch {} await sleep(100); }
  assert.ok(port, 'Chrome starts');
  const target = await fetch('http://127.0.0.1:' + port + '/json/new?about:blank', { method: 'PUT' }).then(r => r.json());
  ws = new WebSocket(target.webSocketDebuggerUrl);
  await new Promise((r, j) => { ws.addEventListener('open', r, { once: true }); ws.addEventListener('error', j, { once: true }); });
  let seq = 0; const pending = new Map();
  const call = (method, params = {}) => new Promise((resolve, reject) => { const id = ++seq; pending.set(id, { resolve, reject }); ws.send(JSON.stringify({ id, method, params })); });
  ws.addEventListener('message', ({ data }) => { const m = JSON.parse(data); if (m.id) { const p = pending.get(m.id); if (p) { pending.delete(m.id); m.error ? p.reject(Error(JSON.stringify(m.error))) : p.resolve(m.result); } } if (m.method === 'Runtime.exceptionThrown') report.pageErrors.push(m.params.exceptionDetails.exception?.description || m.params.exceptionDetails.text); if (m.method === 'Fetch.requestPaused') { const ok = m.params.request.url.startsWith(base); if (!ok) report.externalRequests.push(m.params.request.url); call(ok ? 'Fetch.continueRequest' : 'Fetch.failRequest', { requestId: m.params.requestId, ...(ok ? {} : { errorReason: 'BlockedByClient' }) }).catch(() => {}); } });
  const evaluate = async expression => { const r = await call('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true }); if (r.exceptionDetails) throw Error(r.exceptionDetails.exception?.description || r.exceptionDetails.text); return r.result.value; };
  const wait = async predicate => { for (let i = 0; i < 120; i++) { if (await evaluate('Boolean(' + predicate + ')')) return; await sleep(25); } throw Error('WAIT ' + predicate); };
  await call('Page.enable'); await call('Runtime.enable'); await call('Fetch.enable', { patterns: [{ urlPattern: '*' }] });
  await call('Page.addScriptToEvaluateOnNewDocument', { source: '(' + install.toString() + ')()' });
  const button = (label, scope = '') => evaluate(`(()=>{const b=[...document.querySelectorAll(${JSON.stringify(scope + ' button')})].find(x=>x.textContent.trim()===${JSON.stringify(label)});if(!b)throw Error('missing button '+${JSON.stringify(label)});b.click()})()`);
  const nav = label => button(label, '.app-nav');
  const reset = async (tv = false, query = '') => { await call('Emulation.setDeviceMetricsOverride', { width: tv ? 1280 : 390, height: tv ? 720 : 844, deviceScaleFactor: 1, mobile: !tv }); await call('Page.navigate', { url: base + '?v=' + Date.now() + (tv ? '&tv=1' : '') + query }); await wait('document.querySelector(".poster strong")?.textContent==="测试目录"'); await nav('下载'); await wait('document.querySelectorAll(".download-row").length===3'); };
  const plan = p => evaluate('window.__downloads.plans.push(' + JSON.stringify(p) + ')');
  const release = method => evaluate(`(()=>{const at=window.__downloads.held.findIndex(x=>x.method===${JSON.stringify(method)});if(at<0)throw Error('no held response');window.__downloads.held.splice(at,1)[0].respond()})()`);
  const count = method => evaluate('window.__downloads.calls.filter(x=>x.method===' + JSON.stringify(method) + ').length');
  const rows = () => evaluate('[...document.querySelectorAll(".download-info h2")].map(x=>x.textContent)');
  const selectDelete = (index = 0) => evaluate(`(()=>{const b=document.querySelectorAll('.download-row')[${index}]?.querySelector('.download-delete');if(!b)throw Error('missing per-row delete control');b.click()})()`);
  const confirm = () => button('确认删除', '.download-confirm');
  const cancel = () => button('取消', '.download-confirm');
  const key = async key => { await call('Input.dispatchKeyEvent', { type: 'keyDown', key, code: key === 'Enter' ? 'Enter' : key }); await call('Input.dispatchKeyEvent', { type: 'keyUp', key, code: key === 'Enter' ? 'Enter' : key }); };
  const screenshot = async name => { const p = path.join(output, name + '.png'); fs.writeFileSync(p, Buffer.from((await call('Page.captureScreenshot', { format: 'png', captureBeyondViewport: false })).data, 'base64')); report.screenshots.push(p); };
  const group = async (name, run) => { try { await run(); passed++; report.tests.push({ name, result: 'PASS' }); console.log('PASS ' + name); } catch (e) { failed++; report.tests.push({ name, result: 'FAIL', error: e.message }); console.log('FAIL ' + name + ': ' + e.message); } };
  await group('phone: every row has deletion and destructive RPC requires explicit confirmation', async () => {
    await reset();
    check(await evaluate('document.querySelectorAll(".download-delete").length===3'), 'three rows have per-row delete controls');
    await selectDelete(); await wait('document.querySelector(".download-confirm")');
    check((await count('deleteDownload')) === 0, 'opening confirmation makes no delete RPC');
    check(await evaluate('document.querySelector(".download-confirm").textContent.includes("下载甲")&&document.querySelector(".download-confirm").textContent.includes("第 1 集")'), 'confirmation identifies title and episode');
    check(await evaluate('document.querySelector(".download-confirm").textContent.includes("文件")&&document.querySelector(".download-confirm").textContent.includes("记录")&&document.querySelector(".download-confirm").textContent.includes("收藏")&&document.querySelector(".download-confirm").textContent.includes("观看历史")'), 'confirmation explains file/record only, favorites/history retained');
    check(await evaluate('document.activeElement.textContent==="取消"'), 'default focus is cancel');
    await screenshot('phone-delete-confirm');
    await cancel(); check((await rows()).length === 3 && (await count('deleteDownload')) === 0, 'cancel preserves rows without RPC');
    check(await evaluate('document.activeElement.matches(".download-delete")'), 'cancel restores row focus');
  });
  if (!baseline) {
    await group('phone: success removes only confirmed id and retains other rows', async () => {
      await reset(); await selectDelete(); await confirm(); await wait('document.querySelectorAll(".download-row").length===2&&!document.querySelector(".download-confirm")');
      check((await rows()).every(x => !x.includes('下载甲')) && (await rows()).some(x => x.includes('下载乙')), 'only confirmed first id removed');
      check(await evaluate('JSON.stringify(window.__downloads.calls.find(x=>x.method==="deleteDownload").params)==="{\\"id\\":101,\\"confirmed\\":true}"'), 'RPC carries only exact id plus confirmed');
      check((await count('getDownloads')) >= 2, 'success refreshes native list');
      check(await evaluate('document.activeElement.closest(".download-row")?.textContent.includes("下载乙")'), 'focus moves to next row');
      check(await evaluate('!window.__downloads.calls.some(x=>/Favorite|History/.test(x.method))'), 'deletion never changes favorites/history');
    });
    await group('running, paused and pending show cancel-and-delete; failed/missing show delete', async () => {
      await reset(); check(await evaluate('document.querySelectorAll(".download-delete")[1].textContent==="取消并删除"'), 'running label says cancellation');
      await evaluate('window.__downloads.downloads[0].status="pending";window.__downloads.downloads[1].status="paused";window.__downloads.poll()'); await wait('document.querySelectorAll(".download-delete")[0].textContent==="取消并删除"');
      check(await evaluate('document.querySelectorAll(".download-delete")[1].textContent==="取消并删除"&&document.querySelectorAll(".download-delete")[2].textContent==="删除"'), 'paused and failed labels are precise');
      await selectDelete(); check(await evaluate('document.querySelector(".download-confirm").textContent.includes("取消下载")'), 'active task confirmation explains cancellation'); await cancel();
    });
    await group('failure retains rows, explicit retry remains id-scoped', async () => {
      await reset(); await plan({ method: 'deleteDownload', error: '文件正在使用，请先停止播放。' }); await selectDelete(); await confirm(); await wait('document.querySelector(".download-delete-error")');
      check((await rows()).length === 3, 'native failure keeps list');
      check(await evaluate('document.querySelector(".download-delete-error").textContent.includes("文件正在使用")'), 'native reason is shown');
      await confirm(); await wait('document.querySelectorAll(".download-row").length===2');
      check((await count('deleteDownload')) === 2, 'explicit confirm retries failed id once');
    });
    await group('actual bridge timeout retains item; late response cannot remove it silently', async () => {
      await reset(false, '&timeout=1'); await plan({ method: 'deleteDownload', noResponse: true }); await selectDelete(); await confirm(); await wait('document.querySelector(".download-delete-error")');
      check((await rows()).length === 3, 'timeout retains row');
      check(await evaluate('document.querySelector(".download-delete-error").textContent.includes("超时")'), 'actual nativeCall timeout appears');
      await release('deleteDownload'); await sleep(40); check((await rows()).length === 3, 'late timed-out response does not alter confirmed UI state');
      await cancel(); await selectDelete(); await confirm(); await wait('document.querySelectorAll(".download-row").length===2');
      check((await count('deleteDownload')) === 2, 'idempotent explicit retry recovers');
    });
    await group('duplicate confirm, play and refresh blocked during deletion', async () => {
      await reset(); await plan({ method: 'deleteDownload', hold: true }); await selectDelete();
      await evaluate('const b=[...document.querySelectorAll(".download-confirm button")].find(x=>x.textContent==="确认删除");b.click();b.click();b.click()');
      await sleep(30); check((await count('deleteDownload')) === 1, 'same tick repeated confirmation sends one delete');
      check(await evaluate('[...document.querySelectorAll(".download-row button,.heading-actions button")].every(x=>x.disabled)'), 'row actions and refresh disabled');
      await evaluate('document.querySelector(".download-actions button")?.click();window.__downloads.poll();window.__downloads.poll();window.dispatchEvent(new Event("androidresume"))'); await sleep(30);
      check((await count('playDownload')) === 0 && (await count('getDownloads')) === 1, 'delete blocks play and periodic/resume refresh');
      await release('deleteDownload'); await wait('document.querySelectorAll(".download-row").length===2');
    });
    await group('stale pre-delete poll is discarded and remains single-flight', async () => {
      await reset(); await plan({ method: 'getDownloads', hold: true }); await evaluate('window.__downloads.poll();window.__downloads.poll();window.dispatchEvent(new Event("androidresume"))'); await sleep(20);
      check((await count('getDownloads')) === 2, 'poll/resume have one in-flight list call');
      await selectDelete(); await confirm(); await wait('document.querySelectorAll(".download-row").length===2');
      await release('getDownloads'); await wait('window.__downloads.calls.filter(x=>x.method==="getDownloads").length===3'); await sleep(50);
      check((await rows()).length === 2 && (await rows()).every(x => !x.includes('下载甲')), 'old list snapshot does not resurrect deleted row');
      check(await evaluate('window.__downloads.maxReads===1'), 'list requests stay single-flight across mutation');
      await evaluate('window.__downloads.plans.push({method:"getDownloads",result:[{id:101,title:"迟到缓存",index:1,status:"success"},...window.__downloads.downloads]});window.__downloads.poll()'); await sleep(40);
      check((await rows()).length === 2, 'confirmed deleted id is filtered from stale backend snapshot');
    });
    await group('Escape, native Back and Backspace cancel confirmation before navigation', async () => {
      await reset(); await selectDelete(); await key('Escape'); await wait('!document.querySelector(".download-confirm")');
      check(await evaluate('document.querySelector(".page-heading h1").textContent==="下载"'), 'Escape does not navigate away');
      await selectDelete(); check(await evaluate('window.__androidBack()===true'), 'native Back is consumed'); await wait('!document.querySelector(".download-confirm")');
      check(await evaluate('document.querySelector(".page-heading h1").textContent==="下载"'), 'native Back preserves downloads page');
      await selectDelete(); await key('Backspace'); await wait('!document.querySelector(".download-confirm")');
      check((await count('deleteDownload')) === 0, 'back gestures never delete');
    });
    await group('TV: directional keys and Enter can cancel and confirm, focus/layout preserved', async () => {
      await reset(true); await selectDelete(); await key('Enter'); await wait('!document.querySelector(".download-confirm")');
      check((await count('deleteDownload')) === 0, 'TV default Enter cancels');
      await selectDelete(); await key('ArrowRight'); check(await evaluate('document.activeElement.textContent==="确认删除"'), 'TV right reaches confirmation');
      await screenshot('tv-delete-confirm'); await key('Enter'); await wait('document.querySelectorAll(".download-row").length===2');
      check((await count('deleteDownload')) === 1, 'TV deliberate confirm deletes once');
      check(await evaluate('document.querySelector(".main-content").scrollWidth<=document.querySelector(".main-content").clientWidth+1'), 'TV has no horizontal overflow');
    });
    await group('last row becomes empty state and refresh receives focus', async () => {
      await reset(); await evaluate('window.__downloads.downloads=window.__downloads.downloads.slice(0,1);window.__downloads.poll()'); await wait('document.querySelectorAll(".download-row").length===1');
      await selectDelete(); await confirm(); await wait('document.querySelector(".state-panel")?.textContent.includes("还没有下载任务")');
      check((await rows()).length === 0, 'last row is gone'); check(await evaluate('document.activeElement.getAttribute("aria-label")==="刷新下载列表"'), 'focus moves to refresh after last row');
      await screenshot('phone-downloads-empty');
    });
    await group('unmount drops stale polling and deletion completions', async () => {
      await reset(); await plan({ method: 'getDownloads', hold: true }); await evaluate('window.__downloads.poll()'); await plan({ method: 'deleteDownload', hold: true }); await selectDelete(); await confirm();
      await nav('发现'); await wait('document.querySelector(".poster strong")?.textContent==="测试目录"'); const before = await count('getDownloads');
      await release('getDownloads'); await release('deleteDownload'); await sleep(60);
      check((await count('getDownloads')) === before, 'unmounted mutation does not refresh');
      check(await evaluate('window.__downloads.pollers.size===0&&!document.querySelector(".download-row")&&!document.querySelector(".download-confirm")'), 'poll cleaned and no stale DOM');
      await nav('下载'); await wait('document.querySelectorAll(".download-row").length===2');
      check((await rows()).every(x => !x.includes('下载甲')), 'remount reads real remaining fixture');
    });
    await group('failed old poll settles queued post-delete refresh without reviving error or item', async () => {
      await reset(); await plan({ method: 'getDownloads', hold: true, error: '旧轮询失败' }); await evaluate('window.__downloads.poll()');
      await selectDelete(); await confirm(); await wait('document.querySelectorAll(".download-row").length===2');
      await release('getDownloads'); await wait('window.__downloads.calls.filter(x=>x.method==="getDownloads").length===3'); await sleep(40);
      check((await rows()).length === 2 && (await rows()).every(x => !x.includes('下载甲')), 'old failed read preserves deletion');
      check(await evaluate('!document.querySelector(".error-state")&&window.__downloads.maxReads===1'), 'stale failure does not display or overlap refresh');
      check(await evaluate('document.activeElement.closest(".download-row")?.textContent.includes("下载乙")'), 'remaining row receives focus');
    });
    await group('pending offline playback blocks deletion until the open operation completes', async () => {
      await reset(); await plan({ method: 'playDownload', hold: true }); await button('播放', '.download-actions'); await sleep(25);
      await selectDelete(); check((await count('deleteDownload')) === 0 && await evaluate('!document.querySelector(".download-confirm")'), 'play operation and delete cannot race');
      await release('playDownload'); await wait('!document.querySelector(".download-delete").disabled'); await selectDelete(); await wait('document.querySelector(".download-confirm")'); await cancel();
    });
    await group('320px phone genuine touch opens confirmation without deleting; long title wraps', async () => {
      await reset(); await call('Emulation.setDeviceMetricsOverride', { width: 320, height: 740, deviceScaleFactor: 1, mobile: true });
      await evaluate('window.__downloads.downloads[0].title="很长的测试短剧标题用于验证窄屏逐集删除不会遮挡其他操作";window.__downloads.poll()'); await wait('document.querySelector(".download-info h2").textContent.startsWith("很长")');
      const point = await evaluate('(()=>{const r=document.querySelector(".download-delete").getBoundingClientRect();return {x:r.left+r.width/2,y:r.top+r.height/2}})()');
      await call('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ ...point, radiusX: 2, radiusY: 2 }] }); await call('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
      await wait('document.querySelector(".download-confirm")'); check((await count('deleteDownload')) === 0, 'touch only opens confirmation');
      check(await evaluate('document.querySelector(".main-content").scrollWidth<=document.querySelector(".main-content").clientWidth+1'), 'long confirmation and rows fit 320px');
      await screenshot('phone-320-long-title-confirm'); await cancel();
    });
    await group('invalid acknowledgement preserves item and phone narrow layout is touch usable', async () => {
      await reset(); await plan({ method: 'deleteDownload', result: { id: 999, deleted: true } }); await selectDelete(); await confirm(); await wait('document.querySelector(".download-delete-error")');
      check((await rows()).length === 3, 'wrong id acknowledgement never removes row'); await cancel();
      check(await evaluate('document.querySelector(".main-content").scrollWidth<=document.querySelector(".main-content").clientWidth+1'), 'phone has no horizontal overflow');
      check(await evaluate('[...document.querySelectorAll(".download-row button")].every(x=>x.getBoundingClientRect().height>=44)'), 'all row touch targets reach 44 CSS px');
      await screenshot('phone-downloads-actions');
    });
    check(report.externalRequests.length === 0, 'no external browser request'); check(report.pageErrors.length === 0, 'no runtime exception');
  }
  await call('Page.close');
} catch (e) { failed++; report.tests.push({ name: 'runner', result: 'FAIL', error: e.stack }); console.error(e.stack); }
finally {
  if (ws) try { ws.close(); } catch {}
  if (server) await new Promise(r => server.close(r));
  if (chrome) { chrome.kill('SIGTERM'); await Promise.race([new Promise(r => chrome.once('exit', r)), sleep(3000)]); if (chrome.exitCode === null) chrome.kill('SIGKILL'); }
  if (log !== undefined) fs.closeSync(log);
  fs.rmSync(tmp, { recursive: true, force: true });
  report.summary = { groupsPassed: passed, groupsFailed: failed, assertions, externalRequests: report.externalRequests.length, deviceTests: 'not_run' };
  fs.writeFileSync(path.join(output, 'results.json'), JSON.stringify(report, null, 2) + '\n');
  console.log(`ANDROID_DOWNLOAD_DELETE_UI_${failed ? 'FAILED' : 'OK'} groupsPassed=${passed} groupsFailed=${failed} assertions=${assertions} networkRequests=${report.externalRequests.length} deviceTests=not_run`);
}
process.exitCode = failed ? 1 : 0;
