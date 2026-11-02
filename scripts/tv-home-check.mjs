import fs from 'node:fs';
import path from 'node:path';
import http from 'node:http';
import { spawn } from 'node:child_process';

// One-off TV visual check: load the built web UI in TV mode and screenshot home + detail.
const root = path.resolve(process.cwd(), 'android/app/src/main/assets/web');
const types = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml' };
const server = http.createServer((request, response) => {
  const urlPath = decodeURIComponent(new URL(request.url, 'http://x').pathname);
  const file = path.join(root, urlPath === '/' ? 'index.html' : urlPath);
  fs.readFile(file, (error, data) => {
    if (error) { response.writeHead(404); response.end('nf'); return; }
    response.writeHead(200, { 'content-type': types[path.extname(file)] || 'application/octet-stream' });
    response.end(data);
  });
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const base = `http://127.0.0.1:${server.address().port}`;

// Minimal Android bridge stub good enough for the home/detail pages.
const chrome = spawn('/Applications/Google Chrome.app/Contents/MacOS/Google Chrome', [
  '--headless=new', '--remote-debugging-port=0', '--no-first-run', '--no-default-browser-check',
  '--user-data-dir=' + fs.mkdtempSync(path.join('/tmp', 'tvcheck-')), 'about:blank',
]);
const wsEndpoint = await new Promise((resolve, reject) => {
  const timer = setTimeout(() => reject(new Error('chrome start timeout')), 20000);
  chrome.stderr.on('data', chunk => {
    const match = /ws:\/\/[^\s]+/.exec(String(chunk));
    if (match) { clearTimeout(timer); resolve(match[0].split('?')[0].replace('ws://', 'http://')); }
  });
});
const { WebSocket } = await import('ws');
const ws = new WebSocket(wsEndpoint);
await new Promise(resolve => ws.on('open', resolve));
let seq = 0;
const pending = new Map();
ws.on('message', raw => { const m = JSON.parse(raw); if (m.id && pending.has(m.id)) { pending.get(m.id)(m); pending.delete(m.id); } });
const call = (method, params = {}) => new Promise(resolve => { const id = ++seq; pending.set(id, resolve); ws.send(JSON.stringify({ id, method, params })); });
const targets = await call('Target.getTargets');
let page = targets.targetInfos.find(t => t.type === 'page');
const { targetId } = await call('Target.createTarget', { url: 'about:blank' });
const { sessionId } = await call('Target.attachToTarget', { targetId, flatten: true });
const send = (method, params = {}) => call('Target.sendMessageToTarget', { targetId, sessionId, message: JSON.stringify({ id: ++seq, method, params }) });
// route session messages
ws.on('message', raw => {
  const m = JSON.parse(raw);
  if (m.method && m.sessionId) {
    if (m.method === 'Page.javascriptDialogOpening') return;
    if (m.method === 'Runtime.consoleAPICalled' && m.params.type === 'error') console.log('PAGE ERROR:', JSON.stringify(m.params.args?.map(a => a.value || a.description || '')));
  }
});
const evalAsync = async expression => {
  const response = await send('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true });
  const message = JSON.parse(response.message);
  if (message.error) throw new Error(message.error.message);
  if (message.result.exceptionDetails) throw new Error(message.result.exceptionDetails.exception?.description || 'page exception');
  return message.result.result?.value;
};
await send('Page.enable');
await send('Runtime.enable');
await send('Emulation.setDeviceMetricsOverride', { width: 1280, height: 720, deviceScaleFactor: 1, mobile: false });
await send('Page.addScriptToEvaluateOnNewDocument', { source: `
  window.__tvStub = { calls: [] };
  window.AndroidBridge = { postMessage(raw) {
    const m = JSON.parse(raw); window.__tvStub.calls.push(m.method);
    const done = result => window.__androidResponse({ id: m.id, result });
    if (m.method === 'getInfo') done({ isTv: true, version: 'TVCHECK' });
    else if (m.method === 'getLibrary') done({ favorites: [], history: [] });
    else if (m.method === 'homeFeed') done({ items: [
      { series_id: '7000000000000000001', title: 'TV 检查剧一', cover: '', episode_count: 80, description: '', tags: ['都市'] },
      { series_id: '7000000000000000002', title: 'TV 检查剧二', cover: '', episode_count: 64, description: '', tags: ['爱情'] },
      { series_id: '7000000000000000003', title: 'TV 检查剧三', cover: '', episode_count: 52, description: '', tags: ['家庭'] }],
      hasMore: false, offset: 20, sessionId: 'tv' });
    else if (m.method === 'getPlaybackOptions') done({ quality: 'auto', speed: 1 });
    else if (m.method === 'getPlaybackCache') done({ files: 0, bytes: 0 });
    else if (m.method === 'getDownloads') done([]);
    else if (m.method === 'getAccount') done({ provider: 'none', authenticated: false, sync: { pendingCount: 0, syncing: false, error: '' }, web: { available: true, phase: 'idle', authenticated: false, expiresIn: 0, scanState: '', scanHint: '', diagnostic: '' }, error: '' });
    else if (m.method === 'getAnnouncements') done({ normal: null, emergency: null });
    else if (m.method === 'getSearchHistory') done({ keywords: [] });
    else done({ ok: true });
  } };
`});
const shot = async name => {
  const response = await send('Page.captureScreenshot', { format: 'png' });
  const message = JSON.parse(response.message);
  fs.writeFileSync(`/tmp/hg-probe/${name}.png`, Buffer.from(message.data, 'base64'));
  console.log('shot', name);
};
await send('Page.navigate', { url: base + '/?tv=1' });
await evalAsync(`new Promise(r => { const t = setInterval(() => { if (document.querySelector('.poster')) { clearInterval(t); r(true); } }, 100); })`);
await new Promise(r => setTimeout(r, 800));
await shot('tv-home');
// focus walk: does D-pad-like tab traversal reach the feed cards and 搜索?
const focusInfo = await evalAsync(`(() => {
  const focusables = [...document.querySelectorAll('button, [tabindex]')].filter(el => !el.disabled && el.offsetParent);
  return { count: focusables.length, labels: focusables.slice(0, 10).map(el => (el.getAttribute('aria-label') || el.textContent).trim().slice(0, 18)) };
})()`);
console.log('focusables:', JSON.stringify(focusInfo));
await evalAsync(`document.querySelector('.poster').click()`);
await new Promise(r => setTimeout(r, 2500));
await shot('tv-detail');
const homeBack = await evalAsync(`!!document.querySelector('.back-button')`);
console.log('detail back-button:', homeBack);
chrome.kill('SIGKILL');
server.close();
console.log('TV_CHECK_DONE');
process.exit(0);
