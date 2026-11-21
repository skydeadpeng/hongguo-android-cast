/** Real Android React page and RPC bridge, synthetic native responses only. */
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import fs from 'node:fs/promises';
import http from 'node:http';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { build } from 'esbuild';

const root = fileURLToPath(new URL('..', import.meta.url));
const temporary = await fs.mkdtemp(path.join(os.tmpdir(), 'android-sources-ui-'));
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
let server, chrome, ws;

function installFixture() {
  const mode = new URL(location.href).searchParams.get('mode');
  const restoredSource = mode === 'restore-old-source';
  const retiredRecord = { seriesId: '9900000001', title: '旧黄果观看记录', coverUrl: 'https://old-source.example.test/cover.jpg', episodeCount: 3, index: 1, positionMs: 5000 };
  if (restoredSource) localStorage.setItem('hongguo.session.v1', JSON.stringify({ tab: 'huangguo', detail: retiredRecord }));
  const rows = [
    { id: 'hongguo', name: '红果', enabled: true, nativePlayback: true, capabilities: ['search'] },
  ];
  const native = window.sourceFixture = { calls: [], desktopCalls: 0, delayed: null, delayGui: false };
  if (mode === 'desktop') window.DesktopSources = { list: async () => { native.desktopCalls++; return []; } };
  window.AndroidBridge = { postMessage(raw) {
    const request = JSON.parse(raw);
    const { params: p } = request;
    native.calls.push(request);
    let result = {}, error, errorCode;
    const respond = () => window.__androidResponse({ id: request.id, result, error, errorCode });
    if (request.method === 'getInfo') result = { sourceService: mode !== 'unsupported', isTv: mode === 'tv', platform: 'android', version: '0.0.1' };
    if (request.method === 'getLibrary') result = { history: restoredSource ? [retiredRecord] : [], favorites: restoredSource ? [retiredRecord] : [] };
    if (request.method === 'homeFeed') result = { items: [{ series_id: '123456', title: '现有红果发现页' }] };
    if (request.method === 'sourceService') {
      if (p.operation === 'list' || p.operation === 'settings') {
        if (p.operation === 'settings') rows.find(row => row.id === p.sourceId).enabled = p.enabled === true;
        result = rows.map(row => ({ ...row }));
      }
    }
    queueMicrotask(respond);
  } };
}

try {
  const bundle = await build({ stdin: { contents: `import React from 'react';import {createRoot} from 'react-dom/client';import App from './src/android/App.jsx';import './src/android/style.css';createRoot(document.getElementById('root')).render(React.createElement(App));`, resolveDir: root, loader: 'jsx' },
    bundle: true, write: false, outdir: temporary, format: 'iife', platform: 'browser', target: 'chrome67', plugins: [{ name: 'offline-app-fixtures', setup(builder) {
      builder.onResolve({ filter: /(?:mac-app-source\.mjs|LICENSE\?raw)$/ }, args => ({ path: args.path, namespace: 'fixture' }));
      builder.onLoad({ filter: /.*/, namespace: 'fixture' }, args => ({ contents: args.path.includes('LICENSE') ? 'export default "Offline license fixture";' : 'export async function fetchAppPlayUrl() { throw Error("unexpected playback route"); }', loader: 'js' }));
    } }], logLevel: 'silent' });
  const content = Object.fromEntries(bundle.outputFiles.map(file => [`/${path.basename(file.path)}`, file.contents]));
  const js = Object.keys(content).find(name => name.endsWith('.js'));
  const css = Object.keys(content).find(name => name.endsWith('.css'));
  assert.ok(js && css);
  const html = `<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><link rel="stylesheet" href="${css}"><div id="root"></div><script src="${js}"></script>`;
  server = http.createServer(({ url }, response) => {
    if (url in content) { response.setHeader('Content-Type', url === js ? 'application/javascript' : 'text/css'); response.end(content[url]); }
    else { response.setHeader('Content-Type', 'text/html; charset=utf-8'); response.end(html); }
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const origin = `http://127.0.0.1:${server.address().port}`;
  const profile = path.join(temporary, 'profile');
  chrome = spawn(process.env.CHROME_BIN || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome', ['--headless=new', '--remote-debugging-port=0', `--user-data-dir=${profile}`, '--no-first-run', '--disable-background-networking', '--mute-audio', 'about:blank'], { stdio: 'ignore' });
  chrome.on('error', () => {});
  let port;
  for (let index = 0; index < 100 && !port; index++) {
    try { port = Number((await fs.readFile(path.join(profile, 'DevToolsActivePort'), 'utf8')).split('\n')[0]); }
    catch { await sleep(40); }
  }
  assert.ok(port, 'local Chrome CDP starts');
  const pages = await fetch(`http://127.0.0.1:${port}/json/list`).then(reply => reply.json());
  ws = new WebSocket(pages.find(page => page.type === 'page').webSocketDebuggerUrl);
  await new Promise((resolve, reject) => { ws.addEventListener('open', resolve, { once: true }); ws.addEventListener('error', reject, { once: true }); });
  let callId = 0;
  const pending = new Map(), unexpected = [], errors = [];
  const call = (method, params = {}) => new Promise((resolve, reject) => {
    const id = ++callId; pending.set(id, { resolve, reject }); ws.send(JSON.stringify({ id, method, params }));
  });
  ws.addEventListener('message', ({ data }) => {
    const packet = JSON.parse(data), current = pending.get(packet.id);
    if (current) { pending.delete(packet.id); if (packet.error) current.reject(new Error(packet.error.message)); else current.resolve(packet.result); }
    if (packet.method === 'Runtime.exceptionThrown') errors.push(packet.params.exceptionDetails.text);
    if (packet.method === 'Fetch.requestPaused') {
      const { request, requestId } = packet.params;
      if (new URL(request.url).origin === origin) void call('Fetch.continueRequest', { requestId });
      else { unexpected.push(request.url); void call('Fetch.failRequest', { requestId, errorReason: 'BlockedByClient' }); }
    }
  });
  const evaluate = async expression => {
    const evaluation = await call('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true });
    if (evaluation.exceptionDetails) throw new Error(evaluation.exceptionDetails.exception?.description || evaluation.exceptionDetails.text);
    return evaluation.result.value;
  };
  const wait = async expression => {
    for (let i = 0; i < 140; i++) { if (await evaluate(`Boolean(${expression})`)) return; await sleep(30); }
    throw new Error(`UI waiting for: ${expression}`);
  };
  const click = selector => evaluate(`document.querySelector(${JSON.stringify(selector)}).click()`);
  const useNav = (label, method) => evaluate(`Array.from(document.querySelectorAll('.nav-items > button')).find(button => button.textContent === ${JSON.stringify(label)}).${method}()`);
  const selectSource = name => evaluate(`Array.from(document.querySelectorAll('.sources-choices button')).find(button => button.textContent.startsWith(${JSON.stringify(name)})).click()`);
  const navLabels = () => evaluate('Array.from(document.querySelectorAll(".nav-items > button"), button => button.textContent)');
  const legacyOps = () => evaluate('sourceFixture.calls.filter(request => /^huangguo/i.test(request.method))');
  const key = name => call('Input.dispatchKeyEvent', { type: 'keyDown', key: name, code: name });
  const ops = () => evaluate('sourceFixture.calls.filter(request => request.method === "sourceService").map(request => request.params)');
  const visit = async mode => {
    const tv = mode === 'tv';
    await call('Emulation.setDeviceMetricsOverride', { width: tv ? 1280 : 320, height: tv ? 720 : 740, deviceScaleFactor: 1, mobile: !tv });
    await call('Page.navigate', { url: `${origin}/?mode=${mode}${mode === 'restore-old-source' ? '&restore=1' : ''}` });
    await wait('document.querySelector(".home-search") && document.documentElement.dataset.tv !== undefined && sourceFixture.calls.some(request => request.method === "getInfo")');
  };
  await call('Page.enable'); await call('Runtime.enable'); await call('Fetch.enable', { patterns: [{ urlPattern: '*' }] });
  await call('Page.addScriptToEvaluateOnNewDocument', { source: `(${installFixture})();` });
  await visit('phone');
  await wait('document.querySelector(".android-source-shortcuts .button")');
  assert.deepEqual(await navLabels(), ['发现', '搜索', '我的追剧', '下载', '关于']);
  assert.ok(await evaluate('document.querySelector(".android-source-shortcuts .button").getBoundingClientRect().height >= 44 && document.querySelector(".main-content").scrollWidth === document.querySelector(".main-content").clientWidth'));
  await click('.android-source-shortcuts .button');
  await wait('document.querySelectorAll(".sources-choices button").length === 1');
  assert.deepEqual(await ops(), [{ operation: 'list' }]);
  assert.equal(await evaluate('Array.from(document.querySelectorAll(".sources-choices button")).filter(button => button.textContent.endsWith("限制级（未启用）")).length'), 0);
  await selectSource('红果');
  await wait('document.querySelector(".desktop-sources .state-panel button")?.textContent === "打开红果发现页"');
  assert.deepEqual(await ops(), [{ operation: 'list' }], 'native Hongguo does not invoke archived module');
  await click('.desktop-sources .state-panel button');
  await wait('document.querySelector(".android-source-shortcuts .button")');
  await click('.android-source-shortcuts .button');
  await wait('document.querySelectorAll(".sources-choices button").length === 1');
  await useNav('关于', 'click');
  await wait('document.querySelector(".page-heading h1")?.textContent === "关于红果短剧"');
  assert.equal(await evaluate('document.body.textContent.includes("黄果")'), false);
  assert.deepEqual(await legacyOps(), []);
  console.log('PASS phone single native source entry, native Hongguo handoff and focus restoration');

  await visit('tv');
  await wait('Array.from(document.querySelectorAll(".tv-layout .nav-items button")).some(button => button.textContent === "影视来源")');
  assert.deepEqual(await navLabels(), ['发现', '影视来源', '搜索', '我的追剧', '下载', '关于']);
  await useNav('影视来源', 'focus');
  await key('Enter');
  await wait('document.querySelectorAll(".sources-choices button").length === 1');
  await key('ArrowRight');
  assert.equal(await evaluate('document.activeElement.closest(".sources-choices") !== null'), true);
  await key('Enter');
  await wait('document.querySelector(".sources-choices [aria-pressed=true]")');
  assert.ok((await evaluate('document.activeElement.textContent')).startsWith('红果'), 'remote keeps focus on the only native source');
  assert.equal(await evaluate('document.querySelector(".android-source-shortcuts")'), null);
  assert.deepEqual(await legacyOps(), []);
  console.log('PASS TV sidebar and remote focus select the native source');

  await visit('restore-old-source');
  await wait('document.querySelector(".nav-items button[aria-current=page]")?.textContent === "发现"');
  assert.equal(await evaluate('JSON.parse(localStorage.getItem("hongguo.session.v1")).detail'), null);
  await useNav('我的追剧', 'click');
  await wait('document.querySelector(\'.poster[aria-label="打开旧黄果观看记录"]\')');
  await click('.poster[aria-label="打开旧黄果观看记录"]');
  await wait('document.querySelector(".toast")?.textContent.includes("原黄果来源已移除")');
  assert.ok(await evaluate('document.querySelector(\'.poster[aria-label="打开旧黄果观看记录"]\') && !document.querySelector(".detail-open")'));
  assert.deepEqual(await evaluate('sourceFixture.calls.filter(request => JSON.stringify(request.params).includes("9900000001"))'), []);
  assert.deepEqual(await legacyOps(), []);
  console.log('PASS legacy page and settings removed; old saved tab returns to Discover and old records remain without legacy RPC');

  await visit('unsupported');
  assert.equal(await evaluate('document.body.textContent.includes("影视来源")'), false);
  assert.deepEqual(await ops(), []);
  await visit('desktop');
  await useNav('影视来源', 'click');
  await wait('sourceFixture.desktopCalls === 1');
  assert.deepEqual(await ops(), []);
  assert.deepEqual(unexpected, []);
  assert.deepEqual(errors, []);
  console.log('PASS native capability gate and Electron precedence; no external requests');
} finally {
  ws?.close();
  if (chrome && chrome.exitCode === null) { chrome.kill(); await new Promise(resolve => { chrome.once('exit', resolve); setTimeout(resolve, 1000).unref(); }); }
  if (server) await new Promise(resolve => server.close(resolve));
  await fs.rm(temporary, { recursive: true, force: true });
}
