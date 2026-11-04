/**
 * 桌面版重构入口 —— Electron 壳直接承载安卓 0.12.x 的 WebView UI。
 * 播放复刻安卓架构：渲染器 App 链（__androidAppResolve/appResolveResult 协议）→ 官网链。
 * 阶段一：明文源直接流式供给；加密源（contentKey/spadeA）回落官网链，完整解密管线为阶段二。
 */
const { app, BrowserWindow, ipcMain, protocol, shell, nativeTheme } = require('electron');
const path = require('path');
const fs = require('fs');
const fsp = require('fs/promises');
const axios = require('axios');
const { Readable } = require('stream');
const { pathToFileURL } = require('url');
const { createAndroidBridge } = require('./src/native/android-bridge');
const hongguo = require('./src/native/hongguo');
const { downloadEpisodeToFile } = require('./src/native/ffmpeg-run.cjs');

app.setName('hongguo-downloader');
// 界面固定白色主题：不跟随系统深色切换（安卓 UI 的深色适配不完整，避免色彩断裂）。
nativeTheme.themeSource = 'light';
// 桌面播放器本就应随点随播：解除 Chromium 对自动播放的用户手势要求。
app.commandLine.appendSwitch('autoplay-policy', 'no-user-gesture-required');

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
const pendingDownloads = new Map();  // vid → Promise（同一集的并发下载共用一次）
async function prepareLocalEpisode(playInfo, vid, onProgress) {
  const destination = path.join(cacheDir(), `${vid}-${(playInfo.contentKey ? 'c' : playInfo.spadeA ? 's' : 'p')}.mp4`);
  try { const existing = await fsp.stat(destination); if (existing.size > 1024) return { filePath: destination, size: existing.size, cached: true }; } catch { }
  // 预取与手动切集可能同时要同一集：复用同一下载，避免并发写同一临时文件。
  if (pendingDownloads.has(vid)) return pendingDownloads.get(vid);
  const task = downloadEpisodeToFile(playInfo, destination, { onProgress })
    .then(async () => {
      const stat = await fsp.stat(destination);
      pruneCache();
      return { filePath: destination, size: stat.size, cached: false };
    })
    .finally(() => { pendingDownloads.delete(vid); });
  pendingDownloads.set(vid, task);
  return task;
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
    if (!session) return new Response('not ready', { status: 404 });
    session.lastUsed = Date.now();
    // 流式会话：直接转发 CDN（在线播放，不落盘）。
    if (session.playInfo) {
      try {
        const range = request.headers.get('range');
        const upstream = await axios.get(session.playInfo.url, {
          responseType: 'stream', maxRedirects: 5,
          headers: { 'User-Agent': session.playInfo.userAgent || 'Mozilla/5.0', ...(session.playInfo.referer ? { Referer: session.playInfo.referer } : {}), ...(range ? { Range: range } : {}) },
          validateStatus: status => [200, 206, 416].includes(status),
        });
        const headers = { 'Content-Type': 'video/mp4', 'Accept-Ranges': 'bytes' };
        for (const name of ['content-length', 'content-range']) if (upstream.headers[name]) headers[name] = upstream.headers[name];
        if (request.method === 'HEAD' || upstream.status === 416) { upstream.data.destroy(); return new Response(null, { status: upstream.status, headers }); }
        return new Response(Readable.toWeb(upstream.data, { strategy: { highWaterMark: 64 * 1024, size: chunk => chunk.byteLength } }), { status: upstream.status, headers });
      } catch (error) {
        return new Response('media unavailable', { status: 502 });
      }
    }
    // 本地文件会话：Range 供给。
    if (!session.filePath) return new Response('not ready', { status: 404 });
    try {
      const stat = await fsp.stat(session.filePath);
      const rangeHeader = request.headers.get('range');
      const headers = { 'Content-Type': 'video/mp4', 'Accept-Ranges': 'bytes', 'Content-Length': String(stat.size) };
      if (!rangeHeader) {
        if (request.method === 'HEAD') return new Response(null, { status: 200, headers });
        const stream = fs.createReadStream(session.filePath);
        return new Response(Readable.toWeb(stream, { strategy: { highWaterMark: 64 * 1024, size: chunk => chunk.byteLength } }), { status: 200, headers });
      }
      const match = /^bytes=(\d*)-(\d*)$/.exec(rangeHeader);
      if (!match) return new Response(null, { status: 416, headers: { 'Content-Range': `bytes */${stat.size}` } });
      let start = match[1] === '' ? stat.size - Number(match[2]) : Number(match[1]);
      let end = match[2] === '' || match[1] === '' ? stat.size - 1 : Math.min(Number(match[2]), stat.size - 1);
      if (!Number.isSafeInteger(start) || !Number.isSafeInteger(end) || start < 0 || start > end || start >= stat.size) {
        return new Response(null, { status: 416, headers: { 'Content-Range': `bytes */${stat.size}` } });
      }
      const ranged = { ...headers, 'Content-Range': `bytes ${start}-${end}/${stat.size}`, 'Content-Length': String(end - start + 1) };
      // HEAD 必须无正文：Chromium 的 Range 探测会先发 HEAD，带正文会导致解码失败。
      if (request.method === 'HEAD') return new Response(null, { status: 206, headers: ranged });
      const stream = fs.createReadStream(session.filePath, { start, end });
      return new Response(Readable.toWeb(stream, { strategy: { highWaterMark: 64 * 1024, size: chunk => chunk.byteLength } }), { status: 206, headers: ranged });
    } catch (error) {
      console.log('[hkplay] 供给失败', error && error.message);
      return new Response('media unavailable', { status: 502 });
    }
  });
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

function playbackOptions() {
  try {
    const saved = JSON.parse(fs.readFileSync(path.join(app.getPath('userData'), 'android-prefs.json'), 'utf8')) || {};
    return { autoplay: saved.autoplay !== false, speed: Number(saved.speed) || 1, quality: String(saved.quality || 'auto') };
  } catch { return { autoplay: true, speed: 1, quality: 'auto' }; }
}

/** 在线流式会话：注册 CDN 转发会话并返回流式播放地址（即点即播，不落盘）。 */
function startStreamingSession(playInfo) {
  const sessionId = `s-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
  for (const [key, value] of playSessions) if (Date.now() - value.createdAt > 6 * 3600_000) playSessions.delete(key);
  playSessions.set(sessionId, { playInfo: { url: playInfo.url, userAgent: playInfo.userAgent || '', referer: playInfo.referer || '' }, createdAt: Date.now() });
  return `hkplay://s/${sessionId}`;
}

function openPlayer(payload) {
  const seriesId = String(payload.series && payload.series.seriesId);
  const episodes = Array.isArray(payload.episodes) ? payload.episodes : [];
  console.log('[Play] request series=', seriesId, 'vid=', payload.startVid, 'episodes=', episodes.length, 'title=', payload.series && payload.series.title);
  // 在线流式优先：官网明文源即点即播（不落盘）。
  const web = hongguo.fetchPlayUrlSingle(String(payload.startVid || episodes[0] && episodes[0].vid), seriesId, { quality: 'auto' });
  web.then(info => {
    if (!info.url || info.source !== 'web') throw new Error('官网流式源未就绪');
    const startPlayUrl = startStreamingSession(info);
    const sessionPayload = {
      ...payload, seriesId, startPlayUrl, playCached: false,
      qualityLabel: '流式 360p', playStreaming: true,
      autoplay: playbackOptions().autoplay !== false,
      speed: playbackOptions().speed || 1,
    };
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
    console.log('[Play] 流式失败，回落下载模式:', error.message);
    resolveForPlayback(seriesId, String(payload.startVid || episodes[0] && episodes[0].vid), payload.quality || 'auto')
      .then(playInfo => prepareLocalEpisode(playInfo, String(payload.startVid || episodes[0] && episodes[0].vid)).then(local => ({ local, source: playInfo.source })))
      .then(({ local, source }) => {
        const sessionId = `f-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
        playSessions.set(sessionId, { filePath: local.filePath, size: local.size, createdAt: Date.now() });
        const sessionPayload = { ...payload, seriesId, startPlayUrl: pathToFileURL(local.filePath).href, qualityLabel: source === 'web' ? '网页 360p' : 'App 高清' };
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
      }).catch(err2 => console.log('[Play] 下载模式也失败:', err2.message));
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
// 播放器逐集解析：
//  - auto 清晰度 → 官网明文源纯流式（即点即播，不落盘；CDN Range 可拖动）
//  - 指定清晰度（1080p/720p/480p/360p）→ App 加密链整集下载解密（本地缓存秒开复用）
//  - 已有本地缓存的集数 → 直接复用（连播切集秒开）
// 播放器逐集解析：
//  - auto 清晰度 → 官网明文源纯流式（即点即播，不落盘；CDN Range 可拖动）
//  - 指定清晰度（1080p/720p/480p/360p）→ App 加密链整集下载解密（本地缓存秒开复用）
//  - 已有本地缓存的集数 → 直接复用（连播切集秒开）
ipcMain.handle('player-resolve', async (event, payload) => {
  const vid = String(payload.vid || '');
  const quality = String(payload.quality || 'auto');
  const cached = cachedEpisode(vid);
  if (cached) return { playUrl: cached.playUrl, qualityLabel: cached.qualityLabel, cached: true };
  if (quality !== 'auto') {
    const seriesId = String(payload.seriesId || '');
    const playInfo = await resolveForPlayback(seriesId, vid, quality);
    const local = await prepareLocalEpisode(playInfo, vid, (receivedBytes, totalBytes) => {
      if (!event.sender.isDestroyed()) event.sender.send('player-download-progress', { vid, receivedBytes, totalBytes });
    });
    const sessionId = `f-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
    for (const [key, value] of playSessions) if (Date.now() - value.createdAt > 6 * 3600_000) playSessions.delete(key);
    playSessions.set(sessionId, { filePath: local.filePath, size: local.size, createdAt: Date.now() });
    return { playUrl: pathToFileURL(local.filePath).href, qualityLabel: playInfo.source === 'web' ? '网页 360p' : 'App ' + quality };
  }
  // 流式优先：官网明文直链即点即播；官网未开放的集回落 App 链整集下载解密（下载后播放）。
  const seriesId = String(payload.seriesId || '');
  let web = null;
  try { web = await hongguo.fetchPlayUrlSingle(vid, seriesId, { quality: 'auto' }); } catch { /* 该集官网未开放 */ }
  if (web && web.url && web.source === 'web') {
    const sessionId = `s-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
    for (const [key, value] of playSessions) if (Date.now() - value.createdAt > 6 * 3600_000) playSessions.delete(key);
    playSessions.set(sessionId, { playInfo: { url: web.url, userAgent: web.userAgent || '', referer: web.referer || '' }, createdAt: Date.now() });
    return { playUrl: `hkplay://s/${sessionId}`, qualityLabel: '流式 360p', streaming: true };
  }
  // 回落：App 加密链解析 → 整集下载解密 → 本地播放（带进度反馈）。
  const playInfo = await resolveForPlayback(seriesId, vid, 'auto');
  const local = await prepareLocalEpisode(playInfo, vid, (receivedBytes, totalBytes) => {
    if (!event.sender.isDestroyed()) event.sender.send('player-download-progress', { vid, receivedBytes, totalBytes });
  });
  const sessionId = `f-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
  for (const [key, value] of playSessions) if (Date.now() - value.createdAt > 6 * 3600_000) playSessions.delete(key);
  playSessions.set(sessionId, { filePath: local.filePath, size: local.size, createdAt: Date.now() });
  return { playUrl: pathToFileURL(local.filePath).href, qualityLabel: playInfo.source === 'web' ? '网页 360p' : 'App 高清', cached: local.cached };
});
/** 已在本地缓存的整集：连播切集直接复用，不再走解析与下载。 */
function cachedEpisode(vid) {
  for (const [suffix, label] of [['c', 'App 高清'], ['s', 'App 高清'], ['p', '网页 360p']]) {
    const filePath = path.join(cacheDir(), `${vid}-${suffix}.mp4`);
    let stat;
    try { stat = fs.statSync(filePath); } catch { continue; }
    if (stat.size <= 1024) continue;
    return { playUrl: pathToFileURL(filePath).href, qualityLabel: label };
  }
  return null;
}
// 渲染器 App 链的加密组件由 src/native/app-crypto.cjs 提供实现（同步契约）。
ipcMain.on('android-crypto-sync', (event, request) => {
  event.returnValue = require('./src/native/app-crypto.cjs').appCryptoProcess(request);
});
ipcMain.on('player-progress', (_event, payload) => { bridge.handle('playerProgress', payload).catch(() => { }); });
ipcMain.on('player-option', (_event, payload) => {
  if (!payload || typeof payload.autoplay !== 'boolean') return;
  try {
    const file = path.join(app.getPath('userData'), 'android-prefs.json');
    const saved = JSON.parse(fs.readFileSync(file, 'utf8')) || {};
    saved.autoplay = payload.autoplay;
    fs.writeFileSync(file, JSON.stringify(saved));
  } catch { }
});
ipcMain.on('player-close', () => { if (playerWindow && !playerWindow.isDestroyed()) playerWindow.close(); });

// ===== 版本更新（桌面版）：检查本仓最新 release；下载/安装引导到发布页 =====
ipcMain.on('android-updates-rpc', async (event, payload) => {
  const reply = detail => {
    if (event.sender.isDestroyed()) return;
    event.sender.send('android-updates-event', detail);
    event.sender.executeJavaScript(`window.__androidUpdatesChanged && window.__androidUpdatesChanged(${JSON.stringify(detail)})`, true).catch(() => { });
  };
  const method = String(payload && payload.method || 'status');
  if (!['status', 'check', 'download', 'install', 'later', 'cancel'].includes(method)) return;
  if (['download', 'install'].includes(method)) {
    await shell.openExternal('https://github.com/skydeadpeng/hongguo-android-cast/releases/latest').catch(() => { });
    reply({ state: 'idle', busy: false, message: '已在浏览器打开下载页' });
    return;
  }
  const installed = { versionName: app.getVersion(), code: Number(app.getVersion().split('.').reduce((acc, part) => acc * 100 + Number(part), 0)) };
  try {
    const response = await axios.get('https://api.github.com/repos/skydeadpeng/hongguo-android-cast/releases/latest', {
      timeout: 10000, headers: { Accept: 'application/vnd.github+json', 'User-Agent': 'Hongguo-Desktop-Update' },
    });
    const tag = String(response.data && response.data.tag_name || '').replace(/^v/, '');
    const code = tag ? Number(tag.split('.').reduce((acc, part) => acc * 100 + Number(part || 0), 0)) : 0;
    const notes = String(response.data && response.data.body || '').split('\n').find(Boolean) || '';
    if (!tag || code <= installed.code) {
      reply({ state: 'idle', installedVersion: installed.versionName, installedCode: installed.code, versionCode: installed.code, versionName: installed.versionName, flavor: 'desktop', busy: false, message: `当前已是最新版本 ${installed.versionName}` });
    } else {
      reply({ state: 'available', installedVersion: installed.versionName, installedCode: installed.code, versionCode: code, versionName: tag, flavor: 'desktop', busy: false, message: `发现新版本 ${tag}，点击「下载更新」前往发布页`, notes });
    }
  } catch (error) {
    reply({ state: 'error', installedVersion: installed.versionName, installedCode: installed.code, versionCode: installed.code, versionName: installed.versionName, flavor: 'desktop', busy: false, message: '暂时无法检查更新，请稍后重试' });
  }
});

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
