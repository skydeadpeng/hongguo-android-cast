/**
 * 桌面版重构入口 —— Electron 壳直接承载安卓 0.12.x 的 WebView UI。
 * 播放复刻安卓架构：渲染器 App 链（__androidAppResolve/appResolveResult 协议）→ 官网链。
 * 阶段一：明文源直接流式供给；加密源（contentKey/spadeA）回落官网链，完整解密管线为阶段二。
 */
const { app, BrowserWindow, ipcMain, protocol, shell } = require('electron');
const path = require('path');
const fs = require('fs');
const fsp = require('fs/promises');
const { Readable } = require('stream');
const { createAndroidBridge } = require('./src/native/android-bridge');
const hongguo = require('./src/native/hongguo');
const { downloadEpisodeToFile } = require('./src/native/ffmpeg-run.cjs');

app.setName('hongguo-downloader');

let mainWindow = null, playerWindow = null;
let resolveSequence = 0;
const resolveWaiters = new Map();   // requestId → {resolve, reject, timer}
const playSessions = new Map();     // sessionId → {filePath, size, createdAt}

// 安卓同款：整集下载到本地缓存后播放。缓存目录按 mtime 淘汰。
function cacheDir() {
  const dir = path.join(app.getPath('userData'), 'playcache');
  fs.mkdirSync(dir, { recursive: true });
  return dir;
}
async function pruneCache() {
  try {
    const items = (await fsp.readdir(cacheDir())).map(name => ({ name, path: path.join(cacheDir(), name) }));
    const stats = [];
    for (const item of items) { try { const s = await fsp.stat(item.path); if (s.isFile()) stats.push({ ...item, at: s.mtimeMs, size: s.size }); } catch { } }
    stats.sort((a, b) => a.at - b.at);
    let total = stats.reduce((sum, item) => sum + item.size, 0);
    for (const item of stats) {
      if (total <= 6 * 1024 * 1024 * 1024) break;
      await fsp.rm(item.path, { force: true }); total -= item.size;
    }
  } catch { }
}
async function prepareLocalEpisode(playInfo, vid) {
  const destination = path.join(cacheDir(), `${vid}-${(playInfo.contentKey ? 'c' : playInfo.spadeA ? 's' : 'p')}.mp4`);
  try { const existing = await fsp.stat(destination); if (existing.size > 1024) return { filePath: destination, size: existing.size, cached: true }; } catch { }
  await downloadEpisodeToFile(playInfo, destination);
  const stat = await fsp.stat(destination);
  pruneCache();
  return { filePath: destination, size: stat.size, cached: false };
}

const bridge = createAndroidBridge({
  userDataDir: app.getPath('userData'),
  onPlay: payload => openPlayer(payload),
  onResolveResult: params => {
    const waiter = resolveWaiters.get(String(params.requestId || ''));
    if (!waiter) return;
    clearTimeout(waiter.timer); resolveWaiters.delete(waiter.id);
    if (params.error) waiter.reject(Object.assign(new Error(String(params.error)), { code: params.errorCode }));
    else waiter.resolve(params.playInfo || null);
  },
  log: message => console.log('[AndroidBridge]', message),
});

// ===== 安卓同款解析顺序：渲染器 App 链 → 官网链 =====
function askRendererResolver(seriesId, vid, quality) {
  return new Promise((resolve, reject) => {
    if (!mainWindow || mainWindow.isDestroyed()) return reject(new Error('主窗口未就绪'));
    const requestId = `resolve-${Date.now()}-${++resolveSequence}`;
    const timer = setTimeout(() => { resolveWaiters.delete(requestId); reject(new Error('App 片源解析超时')); }, 32000);
    resolveWaiters.set(requestId, { id: requestId, resolve, reject, timer });
    const script = `window.__androidAppResolve ? (window.__androidAppResolve(${JSON.stringify(requestId)}, ${JSON.stringify(String(seriesId))}, ${JSON.stringify(String(vid))}, ${JSON.stringify(quality)}, undefined), 'DISPATCHED') : 'NO_RESOLVER'`;
    mainWindow.webContents.executeJavaScript(script, true).then(answer => {
      if (answer === 'NO_RESOLVER') { clearTimeout(timer); resolveWaiters.delete(requestId); reject(new Error('渲染器解析器未就绪')); }
    }).catch(error => {
      if (!resolveWaiters.has(requestId)) return;
      clearTimeout(timer); resolveWaiters.delete(requestId);
      reject(error);
    });
  });
}

async function resolveForPlayback(seriesId, vid, quality) {
  // 1) 渲染器 App 链（与安卓 Mac 适配层同一套签名/解密前置逻辑）；加密源由本地解密管线处理
  try {
    const playInfo = await askRendererResolver(seriesId, vid, quality);
    if (playInfo && playInfo.url) {
      console.log('[Play] 渲染器 App 链成功（' + (playInfo.contentKey || playInfo.spadeA ? '加密' : '明文') + '）');
      return playInfo;
    }
    throw new Error(playInfo ? '解析结果无效' : '渲染器未返回片源');
  } catch (error) {
    console.log('[Play] 渲染器 App 链失败:', error.message);
  }
  // 2) 官网快速链（明文 360p）
  const web = await hongguo.fetchPlayUrlSingle(String(vid), String(seriesId), { quality: 'auto' });
  if (web && web.url && web.source === 'web') {
    console.log('[Play] 官网链成功');
    return { url: web.url, userAgent: web.userAgent, referer: web.referer, source: 'web', vid: String(vid) };
  }
  throw new Error('本集暂未开放网页播放，可稍后重试或换一集');
}

// ===== 本地文件供给（hkplay 协议，支持 Range 拖动）=====
function registerPlayProtocol() {
  protocol.handle('hkplay', async request => {
    const url = new URL(request.url);
    const id = url.pathname.replace(/^\/+/, '');
    const session = playSessions.get(id);
    if (!session || !session.filePath) return new Response('not ready', { status: 404 });
    session.lastUsed = Date.now();
    try {
      const stat = await fsp.stat(session.filePath);
      const rangeHeader = request.headers.get('range');
      const headers = { 'Content-Type': 'video/mp4', 'Accept-Ranges': 'bytes', 'Content-Length': String(stat.size) };
      if (!rangeHeader) {
        const stream = fs.createReadStream(session.filePath);
        return new Response(Readable.toWeb(stream, { strategy: { highWaterMark: 64 * 1024, size: chunk => chunk.byteLength } }), { status: 200, headers });
      }
      const match = /^bytes=(\d*)-(\d*)$/.exec(rangeHeader);
      if (!match) return new Response(stream0(), { status: 200, headers });
      let start = match[1] === '' ? stat.size - Number(match[2]) : Number(match[1]);
      let end = match[2] === '' || match[1] === '' ? stat.size - 1 : Math.min(Number(match[2]), stat.size - 1);
      if (!Number.isSafeInteger(start) || !Number.isSafeInteger(end) || start < 0 || start > end || start >= stat.size) {
        return new Response(null, { status: 416, headers: { 'Content-Range': `bytes */${stat.size}` } });
      }
      const stream = fs.createReadStream(session.filePath, { start, end });
      return new Response(Readable.toWeb(stream, { strategy: { highWaterMark: 64 * 1024, size: chunk => chunk.byteLength } }), { status: 206, headers: {
        ...headers, 'Content-Range': `bytes ${start}-${end}/${stat.size}`, 'Content-Length': String(end - start + 1) } });
    } catch (error) {
      return new Response('media unavailable', { status: 502 });
    }
  });
  function stream0() { return new Response(null, { status: 200 }); }
}

// ===== 窗口 =====
function createMainWindow() {
  mainWindow = new BrowserWindow({
    width: 1180, height: 780, minWidth: 940, minHeight: 640,
    backgroundColor: '#131516', autoHideMenuBar: true,
    webPreferences: {
      preload: path.join(__dirname, 'preload-android.js'),
      contextIsolation: true, nodeIntegration: false, sandbox: false,
    },
  });
  mainWindow.loadFile(path.join(__dirname, 'dist-react-android', 'index.html'));
  mainWindow.on('closed', () => { mainWindow = null; });
}

function openPlayer(payload) {
  const seriesId = String(payload.series && payload.series.seriesId);
  const episodes = Array.isArray(payload.episodes) ? payload.episodes : [];
  console.log('[Play] request series=', seriesId, 'vid=', payload.startVid, 'episodes=', episodes.length, 'title=', payload.series && payload.series.title);
  resolveForPlayback(seriesId, String(payload.startVid || episodes[0] && episodes[0].vid), payload.quality || 'auto')
    .then(playInfo => prepareLocalEpisode(playInfo, String(payload.startVid || episodes[0] && episodes[0].vid)).then(local => ({ local, source: playInfo.source })))
    .then(({ local, source }) => {
      const sessionId = `f-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
      for (const [key, value] of playSessions) if (Date.now() - value.createdAt > 6 * 3600_000) playSessions.delete(key);
      playSessions.set(sessionId, { filePath: local.filePath, size: local.size, createdAt: Date.now() });
      const sessionPayload = { ...payload, seriesId, startPlayUrl: `hkplay://f/${sessionId}`, playCached: local.cached, qualityLabel: source === 'web' ? '网页 360p' : 'App 高清' };
      if (playerWindow && !playerWindow.isDestroyed()) { playerWindow.webContents.send('player-session', sessionPayload); playerWindow.show(); return; }
      playerWindow = new BrowserWindow({
        fullscreen: true, backgroundColor: '#000', autoHideMenuBar: true,
        webPreferences: { preload: path.join(__dirname, 'preload-android.js'), contextIsolation: true, nodeIntegration: false, sandbox: false },
      });
      playerWindow.loadFile(path.join(__dirname, 'desktop-android-player', 'player.html'));
      playerWindow.webContents.on('did-finish-load', () => playerWindow && !playerWindow.isDestroyed() && playerWindow.webContents.send('player-session', sessionPayload));
      playerWindow.on('closed', () => {
        playerWindow = null;
        if (mainWindow && !mainWindow.isDestroyed()) { mainWindow.webContents.send('android-resume'); mainWindow.show(); }
      });
    }).catch(error => {
      console.log('[Play] 失败:', error.message);
    });
}

// 安卓桥走 send/回调 语义：bridge.mjs 靠 window.__androidResponse 收结果。
ipcMain.on('android-rpc', async (event, payload) => {
  try {
    const result = await bridge.handle(String(payload.method), payload.params || {});
    if (!event.sender.isDestroyed()) event.sender.send('android-rpc-response', { id: payload.id, result });
  } catch (error) {
    if (!event.sender.isDestroyed()) event.sender.send('android-rpc-response', { id: payload.id, error: error && error.message || String(error), errorCode: error && error.code });
  }
});
ipcMain.on('android-series-rpc', (event, payload) => {
  // 官方分季（bookapi/plan）为阶段二；拒绝后 UI 自动落标题匹配。
  event.sender.send('android-series-response', { id: payload.id, error: '官方分季通道未接入，已使用标题匹配', errorCode: 'UNAVAILABLE' });
});
// 播放器逐集解析：主进程解析 + 整集下载落盘，返回本地播放地址。
ipcMain.handle('player-resolve', async (_event, payload) => {
  const seriesId = String(payload.seriesId || '');
  const vid = String(payload.vid || '');
  const playInfo = await resolveForPlayback(seriesId, vid, String(payload.quality || 'auto'));
  const local = await prepareLocalEpisode(playInfo, vid);
  const sessionId = `f-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
  for (const [key, value] of playSessions) if (Date.now() - value.createdAt > 6 * 3600_000) playSessions.delete(key);
  playSessions.set(sessionId, { filePath: local.filePath, size: local.size, createdAt: Date.now() });
  return { playUrl: `hkplay://f/${sessionId}`, qualityLabel: playInfo.source === 'web' ? '网页 360p' : 'App 高清' };
});
// 渲染器 App 链的加密组件由 src/native/app-crypto.cjs 提供实现（同步契约）。
ipcMain.on('android-crypto-sync', (event, request) => {
  event.returnValue = require('./src/native/app-crypto.cjs').appCryptoProcess(request);
});
ipcMain.on('player-progress', (_event, payload) => { bridge.handle('playerProgress', payload).catch(() => { }); });
ipcMain.on('player-close', () => { if (playerWindow && !playerWindow.isDestroyed()) playerWindow.close(); });

app.whenReady().then(() => {
  registerPlayProtocol();
  createMainWindow();
  app.on('activate', () => { if (BrowserWindow.getAllWindows().length === 0) createMainWindow(); });
});
app.on('window-all-closed', () => { app.quit(); });
app.on('web-contents-created', (_event, contents) => {
  contents.setWindowOpenHandler(({ url }) => {
    if (/^https:\/\//.test(url)) shell.openExternal(url);
    return { action: 'deny' };
  });
  contents.on('will-navigate', event => event.preventDefault());
});
