/**
 * Electron 桥接层 —— 安卓 NativeBridge 的桌面同构实现。
 * 跟随安卓 0.12.x 体系：官方推荐流（landpage 直签）、官网取源链、本机剧库。
 * 账号/下载合并为阶段二；这里返回 UI 可渲染的空态或明确错误。
 */
const fs = require('fs');
const path = require('path');
const axios = require('axios');
// 安卓线的登记身份签名/取流链独立于桌面 app-source（签名头集与 branch 策略有协议差异），勿混用。
const { fetchAppPlayUrl, signedAppRequest, signedModelRequest, setDeviceIdentity } = require('./app-source-registered');
const passport = require('./passport-register.cjs');
const hongguo = require('./hongguo');

const CATALOG_API = 'https://api5-normal-lq.fqnovel.com/reading/distribution/category/landpage/v/';
const PAGE_LIMIT = 20;
const GENRE = {
  '真人剧': 'short_play', 'short_play': 'short_play',
  '漫剧': 'comic_series', 'comic_series': 'comic_series',
  'AI剧': 'ai_series', 'ai_series': 'ai_series',
};

function cleanText(value, max = 300) {
  const text = String(value == null ? '' : value).replace(/<[^>]*>/g, '').replace(/&amp;/g, '&')
    .replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"').replace(/&#39;/g, "'")
    .replace(/\s+/g, ' ').trim();
  return text.slice(0, max);
}

// ===== 官方推荐流（NewReleaseCatalog.feed 的 JS 同构移植）=====
function parseFeedRows(videoData) {
  const rows = [];
  for (const raw of videoData || []) {
    if (!raw) continue;
    const id = String(raw.series_id == null ? '' : raw.series_id);
    const title = cleanText(raw.title || raw.series_title, 200);
    if (!/^\d{1,30}$/.test(id) || !title) continue;
    let cover = String(raw.cover || raw.series_cover || '');
    if (cover.startsWith('http://')) cover = 'https://' + cover.slice(7);
    if (!/^https:\/\//.test(cover) || cover.length > 2048 || /[\s\\]/.test(cover)) cover = '';
    const tags = [];
    for (const tag of Array.isArray(raw.tags) ? raw.tags : []) {
      const label = cleanText(tag, 20);
      if (label && !tags.includes(label) && tags.length < 4) tags.push(label);
    }
    rows.push({ seriesId: id, title, cover, description: cleanText(raw.description || raw.video_desc, 3000),
      episodeCount: Math.min(Number(raw.episode_cnt) || 0, 10000), tags });
  }
  return rows;
}

async function catalogPost(body, deadlineMs) {
  const signed = signedAppRequest(CATALOG_API, JSON.stringify(body));
  const response = await axios.post(signed.url, signed.body, {
    headers: signed.headers, timeout: Math.max(2000, Math.min(20000, deadlineMs - Date.now())), responseType: 'json',
  });
  const payload = typeof response.data === 'string' ? JSON.parse(response.data) : response.data;
  if (!payload || Number(payload.code ?? payload.Code ?? -1) !== 0) throw new Error('红果首页推荐未就绪，请重试');
  return payload.data || {};
}

async function genreChips() {
  const data = await catalogPost({
    filter_ids: '', req_scene: 'default', offset: 0, need_selector_panel: true, limit: 1,
    select_items: { genre: ['short_play'], sort: [], category_dim_theme: [], category_dim_role: [], category_dim_epoch: [], gender: [], online_time: [], creation_status: [] },
    session_id: '', req_type: 'default', client_req_type: 1,
  }, Date.now() + 15000);
  const chips = [];
  for (const row of data.selector_rows || []) {
    if (!row || row.type !== 'genre' || !Array.isArray(row.items)) continue;
    for (const item of row.items) {
      const id = String(item && item.selector_item_id || '');
      const label = cleanText(item && item.show_name, 12);
      if (/^[a-z_]{1,30}$/.test(id) && label && !chips.some(chip => chip.id === id)) chips.push({ id, label });
    }
  }
  return chips.length >= 2 ? chips : null;
}

async function homeFeed(params) {
  const offset = Math.max(0, Number(params && params.offset) || 0);
  const sessionId = String(params && params.sessionId || '').slice(0, 128);
  const requested = String(params && params.genre || 'short_play');
  const genre = GENRE[requested] && /^[a-z_]{1,30}$/.test(GENRE[requested]) ? GENRE[requested] : 'short_play';
  const deadline = Date.now() + 20000;
  const data = await catalogPost({
    filter_ids: '', req_scene: 'default', offset, need_selector_panel: false, limit: PAGE_LIMIT,
    select_items: { genre: [genre], sort: [], category_dim_theme: [], category_dim_role: [], category_dim_epoch: [], gender: [], online_time: [], creation_status: [] },
    session_id: sessionId, req_type: 'default', client_req_type: 1,
  }, deadline);
  const rows = parseFeedRows(data.video_data);
  if (!rows.length) throw new Error('红果首页推荐暂无内容，请重试');
  const result = { items: rows, hasMore: data.has_more === true, offset: Math.max(offset, Number(data.next_offset) || offset), sessionId: String(data.session_id || '') };
  if (offset === 0) {
    try { const chips = await genreChips(); if (chips) result.genres = chips; } catch { /* 兜底芯片由前端补 */ }
  }
  return result;
}

// ===== 页面抓取（requestHtml 的 native 语义）=====
const pageFetches = new Map();
let pageSequence = 0;
async function httpGet(params) {
  const url = String(params && params.url || '');
  if (!/^https:\/\//.test(url) || url.length > 4096) throw new Error('页面请求地址无效');
  const deadline = Number(params && params.deadlineMs) || Date.now() + (Number(params && params.timeoutMs) || 25000);
  const remaining = Math.max(1000, deadline - Date.now());
  const key = `http-${Date.now()}-${++pageSequence}`;
  pageFetches.set(key, { url });
  try {
    const response = await axios.get(url, {
      timeout: Math.min(remaining, 25000), maxRedirects: 5, responseType: 'text',
      headers: { 'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36', 'Accept': 'text/html,application/xhtml+xml,*/*;q=0.8', 'Accept-Language': 'zh-CN,zh;q=0.9', 'Referer': 'https://hongguoduanju.com/' },
      validateStatus: () => true,
    });
    const body = typeof response.data === 'string' ? response.data : JSON.stringify(response.data);
    return { status: Number(response.status), body, url: response.request && response.request.res ? url : url };
  } catch (error) {
    if (error.code === 'ECONNABORTED') throw Object.assign(new Error('页面请求超时'), { code: 'ETIMEDOUT' });
    throw Object.assign(new Error('页面请求未成功，请重试'), { });
  } finally { pageFetches.delete(key); }
}
function cancelHttp(params) {
  const requestId = String(params && params.requestId || '');
  const entry = pageFetches.get(requestId);
  if (entry) pageFetches.delete(requestId);
  return { ok: true };
}

// ===== App 家族网络（渲染进程 axios shim 的 native 语义）=====
async function appRequest(params) {
  const url = String(params && params.url || '');
  const method = String(params && params.method || 'GET').toUpperCase();
  if (!/^https:\/\//.test(url) || !['GET', 'POST'].includes(method)) return { status: 0, errorCode: 'ERR_NETWORK' };
  const timeout = Math.max(1, Math.min(12000, Number(params && params.timeoutMs) || 12000));
  const headers = {};
  for (const [key, value] of Object.entries((params && params.headers) || {})) headers[key] = String(value);
  try {
    const response = await axios({ url, method, headers, data: method === 'POST' ? String(params && params.body || '') : undefined, timeout, responseType: 'text', validateStatus: () => true });
    const body = typeof response.data === 'string' ? response.data : JSON.stringify(response.data);
    if (body.length > 2 * 1024 * 1024) throw new Error('oversize');
    return { status: Number(response.status), body, headers: response.headers || {}, url };
  } catch (error) {
    const transient = ['ENOTFOUND', 'EAI_AGAIN', 'ETIMEDOUT', 'ECONNABORTED', 'ECONNRESET', 'ECONNREFUSED', 'EHOSTUNREACH', 'ENETUNREACH', 'EPIPE'].includes(error.code);
    return { status: 0, errorCode: transient ? error.code : 'ERR_NETWORK' };
  }
}

// ===== 本机剧库（LibraryStore 同构；owner 固定 local）=====
class Library {
  constructor(file) {
    this.file = file; this.data = { favorites: [], history: [] };
    try { this.data = JSON.parse(fs.readFileSync(file, 'utf8')) || this.data; } catch { /* 首次创建 */ }
  }
  save() { try { fs.mkdirSync(path.dirname(this.file), { recursive: true }); fs.writeFileSync(this.file, JSON.stringify(this.data)); } catch { /* 存储失败不阻塞播放 */ } }
  owner() { return 'local'; }
  view() { return { owner: this.owner(), favorites: this.data.favorites, history: this.data.history }; }
  upsertHistory(row) {
    if (!row || !/^\d{1,30}$/.test(String(row.seriesId || ''))) return;
    const clean = { seriesId: String(row.seriesId), title: String(row.title || '').slice(0, 200), cover: String(row.cover || '').slice(0, 2048),
      episodeCount: Number(row.episodeCount) || 0, index: Math.max(1, Number(row.index) || 1), vid: String(row.vid || '').slice(0, 30),
      positionMs: Math.max(0, Number(row.positionMs) || 0), durationMs: Math.max(0, Number(row.durationMs) || 0),
      updatedAt: Date.now() };
    const rest = this.data.history.filter(item => item.seriesId !== clean.seriesId);
    rest.unshift(clean); this.data.history = rest.slice(0, 200); this.save();
  }
  history(seriesId) { return this.data.history.find(item => item.seriesId === String(seriesId)) || null; }
  toggleFavorite(series) {
    const id = String(series && series.seriesId || '');
    if (!/^\d{1,30}$/.test(id)) throw new Error('剧集编号无效');
    const existing = this.data.favorites.find(item => item.seriesId === id);
    if (existing) { this.data.favorites = this.data.favorites.filter(item => item.seriesId !== id); }
    else { this.data.favorites.unshift({ seriesId: id, title: String(series.title || '').slice(0, 200), cover: String(series.cover || '').slice(0, 2048),
      episodeCount: Number(series.episodeCount) || 0, updatedAt: Date.now() }); this.data.favorites = this.data.favorites.slice(0, 200); }
    this.save(); return this.view();
  }
}

// ===== 简单键值存储（播放偏好/搜索历史）=====
class KeyValue {
  constructor(file) { this.file = file; this.data = {}; try { this.data = JSON.parse(fs.readFileSync(file, 'utf8')) || {}; } catch { } }
  save() { try { fs.mkdirSync(path.dirname(this.file), { recursive: true }); fs.writeFileSync(this.file, JSON.stringify(this.data)); } catch { } }
  get(key, fallback) { return this.data[key] === undefined ? fallback : this.data[key]; }
  set(key, value) { this.data[key] = value; this.save(); return value; }
}

function createAndroidBridge({ userDataDir, onPlay, onResolveResult, log }) {
  const library = new Library(path.join(userDataDir, 'android-library.json'));
  const prefs = new KeyValue(path.join(userDataDir, 'android-prefs.json'));
  const say = message => { if (log) log(message); };
  const UNSUPPORTED = name => { throw new Error(`${name}将在桌面版后续版本开放`); };
  const identityFile = path.join(userDataDir, 'passport-device.json');
  let identityPromise = null;
  // 设备注册一次并持久化；失败不落盘，下次重试。
  function ensureRegisteredDevice() {
    if (!identityPromise) {
      identityPromise = (async () => {
        try {
          const saved = JSON.parse(fs.readFileSync(identityFile, 'utf8'));
          if (saved && /^[1-9][0-9]{0,29}$/.test(saved.deviceId) && /^[1-9][0-9]{0,29}$/.test(saved.installId)) return saved;
        } catch { }
        const identity = await passport.registerDevice({ axios });
        try { fs.writeFileSync(identityFile, JSON.stringify(identity)); } catch { }
        say('设备注册成功 device_id=' + identity.deviceId);
        return identity;
      })();
      identityPromise.catch(() => { }).finally(() => { identityPromise = null; });
    }
    return identityPromise;
  }
  // 强制重新注册（会话失效/服务端拒绝后的恢复路径）。
  async function reRegisterDevice() {
    try { fs.rmSync(identityFile, { force: true }); } catch { }
    const identity = await passport.registerDevice({ axios });
    try { fs.writeFileSync(identityFile, JSON.stringify(identity)); } catch { }
    say('设备重新注册 device_id=' + identity.deviceId);
    return identity;
  }
  // 已登记身份（尽力而为：注册未完成时返回 null，调用方保持合成身份）。
  function registeredIdentityOrNull() {
    try {
      const saved = JSON.parse(fs.readFileSync(identityFile, 'utf8'));
      if (saved && /^[1-9][0-9]{0,29}$/.test(saved.deviceId) && /^[1-9][0-9]{0,29}$/.test(saved.installId)) return saved;
    } catch { }
    return null;
  }
  // App 家族请求统一改写为已登记设备身份（安卓同构：原生层所有请求共用登记身份）。
  function withRegisteredIdentity(url) {
    const identity = registeredIdentityOrNull();
    if (!identity) return url;
    try {
      const target = new URL(url);
      if (!target.hostname.endsWith('fqnovel.com')) return url;
      target.searchParams.set('device_id', identity.deviceId);
      target.searchParams.set('iid', identity.installId);
      return target.toString();
    } catch { return url; }
  }

  return {
    async handle(method, params) {
      switch (method) {
        case 'getInfo': return { version: '3.0.0', isTv: false, platform: 'android' };
        case 'httpGet': return httpGet(params);
        case 'cancelHttp': return cancelHttp(params);
        case 'homeFeed': return homeFeed(params);
        // 搜索：桥接侧拒绝，UI 的兜底链自动改用官网 JSON 搜索（经 httpGet）。
        case 'searchQuery': throw new Error('App 搜索通道未接入，已使用官网搜索');
        case 'searchNewReleases': throw new Error('App 上新通道未接入');
        case 'getLibrary': return library.view();
        case 'getSeriesHistory': return { owner: library.owner(), history: library.history(params && params.seriesId) };
        case 'toggleFavorite': return library.toggleFavorite(params && params.series);
        case 'play': {
          const series = params && params.series, episodes = Array.isArray(params && params.episodes) ? params.episodes : [];
          if (!series || !series.seriesId || !episodes.length) throw new Error('播放请求无效');
          onPlay({ series, episodes, startVid: params && params.episode && params.episode.vid, quality: String(params && params.quality || 'auto'), speed: Number(params && params.speed) || 1 });
          return { opened: true };
        }
        case 'getPlaybackOptions': return { quality: prefs.get('quality', 'auto'), speed: prefs.get('speed', 1) };
        case 'setPlaybackOptions': {
          const quality = String(params && params.quality || 'auto');
          const speed = Number(params && params.speed);
          if (!['auto', '1080p', '720p', '480p', '360p'].includes(quality)) throw new Error('清晰度无效');
          if (![0.5, 0.75, 1, 1.25, 1.5, 2, 3].includes(speed)) throw new Error('倍速无效');
          prefs.set('quality', quality); prefs.set('speed', speed);
          return { quality, speed };
        }
        case 'getSearchHistory': return { keywords: prefs.get('searchKeywords', []) };
        case 'addSearchHistory': {
          const keyword = String(params && params.keyword || '').trim().slice(0, 100);
          if (!keyword) return { keywords: prefs.get('searchKeywords', []) };
          const keywords = [keyword, ...prefs.get('searchKeywords', []).filter(item => item !== keyword)].slice(0, 20);
          prefs.set('searchKeywords', keywords); return { keywords };
        }
        case 'removeSearchHistory': {
          const keyword = String(params && params.keyword || '');
          prefs.set('searchKeywords', prefs.get('searchKeywords', []).filter(item => item !== keyword));
          return { keywords: prefs.get('searchKeywords', []) };
        }
        case 'clearSearchHistory': prefs.set('searchKeywords', []); return { keywords: [] };
        case 'getAnnouncements': return { items: [] };
        case 'openExternal': return { opened: true, url: String(params && params.url || '') };
        case 'getAccount': {
          // 桌面版未接账号同步：返回未登录的合法投影，UI 显示未登录状态（扫码等动作在后续版本开放）。
          return { provider: 'hongguo', authenticated: false, officialLoginAvailable: true, phase: 'logged_in', revision: 1, busy: false, maskedPhone: '', sync: {}, web: {}, counts: {} };
        }
        case 'officialSendCode': case 'officialLoginSMS': case 'officialVerifyMFA': case 'officialCancelLogin':
        case 'officialLogout': case 'officialSync': case 'officialImportGuest':
        case 'officialWebQrcode': case 'officialWebPoll': case 'officialWebLogout': case 'officialWebCancel':
          UNSUPPORTED('账号登录'); break;
        case 'getDownloads': return [];
        case 'download': UNSUPPORTED('离线下载'); break;
        case 'playDownload': UNSUPPORTED('离线播放'); break;
        case 'deleteDownload': return { deleted: false };
        case 'getPlaybackCache': return { items: [], totalBytes: 0 };
        case 'clearPlaybackCache': return { cleared: true };
        case 'getMergedVideos': return [];
        case 'mergeDownloads': UNSUPPORTED('合并导出'); break;
        case 'deleteMergedVideo': return { deleted: false };
        case 'playMergedVideo': UNSUPPORTED('合并版播放'); break;
        case 'appRegisteredModel': {
          // 已登记设备模型（安卓 RegisteredPlaybackModel 同构）。模型接口间歇返回空体
          // （安卓同样带重试语义）：最多 4 次，命中即回。
          const vid = String(params && params.vid || '');
          if (!/^\d{1,30}$/.test(vid)) throw new Error('App 分集请求参数无效');
          let identity = await ensureRegisteredDevice();
          // 已登记身份：只覆盖 device_id/iid；档案版本由 signedModelRequest 按接口形态决定
          // （POST 7.4.x / video_tab GET 7.3.9——2026-11 起服务端按此校验）。
          setDeviceIdentity({ device_id: identity.deviceId, iid: identity.installId });
          try {            const payload = JSON.stringify({
              biz_param: { detail_page_version: 0, device_level: 3, disable_digg_stat: false, need_all_video_definition: true, need_mp4_align: false, use_os_player: false, use_server_dns: false, video_platform: 1024 },
              mixed_video_id_map: { '1004': [vid] },
            });
            let lastError = null;
            for (let attempt = 0; attempt < 3; attempt++) {
              if (attempt > 0) {
                // 空响应＝会话失效或短期拒绝：重新注册一个新身份再试（与服务端的新鲜会话语义一致）。
                await new Promise(resolve => setTimeout(resolve, 800 + attempt * 700));
                await reRegisterDevice();
                const fresh = registeredIdentityOrNull();
                if (fresh) { identity.deviceId = fresh.deviceId; identity.installId = fresh.installId; identity.cookie = fresh.cookie; identity.deviceToken = fresh.deviceToken; }
                setDeviceIdentity({ device_id: identity.deviceId, iid: identity.installId });
              }
              const signed = signedModelRequest(payload);
              const cookieParts = [];
              if (identity.cookie) cookieParts.push(identity.cookie);
              if (identity.deviceToken) cookieParts.push('device_token=' + identity.deviceToken);
              cookieParts.push('install_id=' + identity.installId);
              const response = await axios.post(signed.url, signed.body, {
                headers: { ...signed.headers, Cookie: cookieParts.join('; ') },
                timeout: 15000, responseType: 'text', validateStatus: () => true,
                maxContentLength: 8 * 1024 * 1024,
              });
              const text = typeof response.data === 'string' ? response.data : JSON.stringify(response.data);
              if (!text || text.length < 8) { lastError = Object.assign(new Error('App 已登记模型空响应'), { code: 'APP_EMPTY_RESPONSE' }); continue; }
              let body;
              try { body = JSON.parse(text); } catch { throw Object.assign(new Error('App 已登记模型响应无效'), { code: 'APP_INVALID_RESPONSE' }); }
              if (Number(body.Code ?? body.code ?? -1) !== 0) throw Object.assign(new Error('App 已登记模型请求未通过'), { code: 'BUSINESS_REJECTED' });
              const entry = body.data && body.data[vid];
              const model = entry ? (typeof entry.video_model === 'string' ? JSON.parse(entry.video_model) : entry.video_model) : null;
              if (!model || typeof model !== 'object') { lastError = Object.assign(new Error('App 已登记模型未返回本集'), { code: 'APP_EMPTY_RESPONSE' }); continue; }
              return { vid, video_model: model };
            }
            throw lastError || new Error('App 已登记模型未成功');
          } finally { setDeviceIdentity(null); }
        }
        case 'appRequest': return appRequest(params);
        case 'appResolveResult': if (onResolveResult) onResolveResult(params || {}); return { accepted: true };
        // 播放器窗口回写进度
        case 'playerProgress': library.upsertHistory(params && params.row); return { ok: true };
        default: throw new Error('未支持的设备操作');
      }
    },
    library,
    resolveEpisode: async (seriesId, vid, quality) => {
      // 与安卓播放器同序：App 高清链优先（明文源直接播），失败落官网快速链。
      try {
        const info = await fetchAppPlayUrl(String(vid), { quality });
        if (!info.contentKey) return { url: info.url, userAgent: info.userAgent || '', referer: info.referer || '', source: info.source || 'app', qualityLabel: info.quality || quality, width: info.width, height: info.height };
        say('app 源为加密内容，回退官网链');
      } catch (error) { say('app 链失败，回退官网链: ' + (error.message || error)); }
      const web = await hongguo.fetchPlayUrlSingle(String(vid), String(seriesId), { quality: 'auto' });
      if (web && web.url && web.source === 'web') return { url: web.url, userAgent: web.userAgent || '', referer: web.referer || '', source: 'web', qualityLabel: '360p' };
      say('官网链也未成功: ' + JSON.stringify({ source: web && web.source, error: web && web.error }));
      throw new Error('本集暂未开放网页播放；高清加密源将在后续版本支持');
    },
  };
}

module.exports = { createAndroidBridge };
