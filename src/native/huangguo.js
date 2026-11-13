const axios = require('axios');
const fs = require('fs');
const path = require('path');

// 黄果官方站 API（huangguoai.com，author「黄果官方」）。全站分集直发完整签名 m3u8（AES-128，
// key/分片均为绝对地址），无 vip 拦截；配合时长校验防"只下发预览片段"的个别情况。
// 备用域名来自社区项目 HGXZ 的配置；末位为自建 Worker 代理（安卓/webOS 同款线路），
// 兜底部分网络把官方全系域名 DNS 污染到假 IP 的情况。
const ORIGINS = ['https://huangguoai.com', 'https://l5f9m.rxzfszht.cc', 'https://blolhh.rxzfszht.cc', 'https://mxz58.rxzfszht.cc', 'https://huoguo.skydeadpeng.xyz'];
const ID = /^\d{1,30}$/;
const PAGE_SIZE = 30;
// 与官方站点导航一致的内容分类（ranks/topics 等聚合页不作为题材）。
const CATEGORIES = [
  { slug: 'ai-manju', label: 'AI成人漫剧' },
  { slug: 'ai-duanju', label: 'AI成人短剧' },
];
let originIndex = 0;
// 首次使用并发测速、钉住最快可用线路并持久记忆（userData/huangguo-line.json，由主进程注入），
// 避免每次冷启动都从主站开始逐条撞黑洞超时；全线失败时清掉记忆，下次重新测速。
let stateFile = null;
let readyPromise = null;
let probeCooldownUntil = 0;

function plainText(value) {
  return String(value || '').replace(/<[^>]*>/g, '').trim();
}

function httpsUrl(value) {
  try {
    const url = new URL(String(value || ''));
    return url.protocol === 'https:' && !url.username && !url.password ? url.href : '';
  } catch (_) { return ''; }
}

function parseId(value, episode = false) {
  const parts = String(value || '').split(':');
  if (parts[0] !== 'huangguo' || parts.length !== (episode ? 3 : 2) || !parts.slice(1).every(part => ID.test(part))) {
    throw new Error('黄果剧集编号无效');
  }
  return { dramaId: parts[1], ep: Number(parts[2]), seriesId: `huangguo:${parts[1]}` };
}

// 注入持久化文件路径（主进程在 userData 定型后调用；不注入则仅进程内记忆）。
function configureStateFile(file) { stateFile = file; }

function loadPersistedOrigin() {
  try {
    const raw = JSON.parse(fs.readFileSync(stateFile, 'utf8'));
    const index = ORIGINS.indexOf(raw && raw.origin);
    return index >= 0 ? index : null;
  } catch (_) { return null; }
}

function persistOriginIndex(index) {
  originIndex = index;
  if (!stateFile) return;
  try {
    fs.mkdirSync(path.dirname(stateFile), { recursive: true });
    fs.writeFileSync(stateFile, JSON.stringify({ origin: ORIGINS[index], pinnedAt: Date.now() }));
  } catch (_) { /* 状态写盘失败不影响取源，仅退化为进程内记忆 */ }
}

function clearPersistedOrigin() {
  if (!stateFile) return;
  try { fs.unlinkSync(stateFile); } catch (_) { /* 文件不存在即可 */ }
}

// 并发探测全部线路（小分页 category 接口），最先返回有效数据的线路胜出——等价于最快可用线路。
async function probeOrigins() {
  const probe = origin => axios.get(origin + '/api/videos/category/ai-manju', {
    params: { page: 1, size: 1, sort: 'latest' },
    headers: { 'User-Agent': 'Mozilla/5.0', Accept: 'application/json', Referer: origin + '/' },
    timeout: 8000, maxRedirects: 0, maxContentLength: 64 * 1024,
  }).then(({ data }) => {
    if (!data || typeof data !== 'object' || Number(data.status) !== 1 || !data.data) throw new Error('invalid');
  });
  try { return await Promise.any(ORIGINS.map((origin, index) => probe(origin).then(() => index))); }
  catch (_) { return -1; }
}

// 首次请求前完成线路选定：有持久记忆直接沿用；否则测速钉住最快线路（单飞，并发请求只测一次）。
function ensureOriginReady() {
  if (!readyPromise) readyPromise = (async () => {
    const persisted = loadPersistedOrigin();
    if (persisted != null) { originIndex = persisted; return; }
    if (Date.now() < probeCooldownUntil) return; // 刚全线失败：冷却期内不再探测，按当前顺序尝试
    const winner = await probeOrigins();
    if (winner >= 0) persistOriginIndex(winner);
    else probeCooldownUntil = Date.now() + 60 * 1000;
  })().catch(() => {});
  return readyPromise;
}

// 钉住线路后的多域名按序兜底：单线抖动时轮换下一域名，成功后把新线路写回记忆。
async function request(apiPath, params, options = {}) {
  await ensureOriginReady();
  const deadlineRemaining = Number(options.deadline) > 0 ? options.deadline - Date.now() : Infinity;
  if (deadlineRemaining <= 0) throw new Error('黄果加载超时，请重试');
  const timeout = Math.max(1000, Math.min(options.timeout || 12000, deadlineRemaining));
  let lastError = null;
  for (let attempt = 0; attempt < ORIGINS.length; attempt++) {
    const origin = ORIGINS[(originIndex + attempt) % ORIGINS.length];
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeout);
    try {
      // options.method='POST' 时把参数改为 JSON 请求体发送（2026-11 官方取流端点仅收 POST）。
      const post = String(options.method || 'GET').toUpperCase() === 'POST';
      const { data } = await axios.request(origin + apiPath, {
        method: post ? 'post' : 'get',
        params: post ? undefined : params,
        data: post ? {} : undefined,
        headers: { 'User-Agent': 'Mozilla/5.0', Accept: 'application/json', Referer: origin + '/',
          ...(post ? { 'Content-Type': 'application/json' } : {}) },
        timeout, signal: controller.signal, maxRedirects: 0, maxContentLength: 8 * 1024 * 1024,
      });
      if (!data || typeof data !== 'object') throw new Error('invalid result');
      const winner = (originIndex + attempt) % ORIGINS.length;
      if (winner !== originIndex) persistOriginIndex(winner);
      if (Number(data.status) === 1 && data.data && typeof data.data === 'object') return data.data;
      throw new Error(plainText(data.msg).slice(0, 120) || '黄果来源暂不可用，请稍后重试');
    } catch (error) {
      if (error.response && error.response.data?.msg) lastError = new Error(plainText(error.response.data.msg).slice(0, 120));
      else if (/^黄果/.test(error.message)) lastError = error;
      else lastError = new Error(controller.signal.aborted || ['ECONNABORTED', 'ETIMEDOUT'].includes(error.code)
        ? '黄果加载超时，请重试' : '黄果来源暂不可用，请稍后重试');
      // 参数类错误立即失败；超时/网络错误继续轮换下一域名——部分运营商会把单个域名
      // 黑洞化（连接挂着不回包），卡死在一个域等于整源不可用（安卓/webOS 端即此语义）。
      if (/^(请输入|搜索词)/.test(lastError.message)) throw lastError;
    } finally { clearTimeout(timer); }
  }
  // 全线失败：清掉钉住的线路记忆，下次请求重新测速（受冷却期约束，不会连环探测）。
  clearPersistedOrigin();
  readyPromise = null;
  throw lastError || new Error('黄果来源暂不可用，请稍后重试');
}

function normalizeItem(item) {
  const id = Number(item?.id);
  const title = plainText(item?.title);
  if (!Number.isSafeInteger(id) || id <= 0 || !title) return null;
  const tags = (Array.isArray(item?.tags) ? item.tags : [])
    .map(tag => plainText(typeof tag === 'object' ? tag?.name : tag)).filter(Boolean).slice(0, 4);
  return { series_id: `huangguo:${id}`, series_title: title, cover: httpsUrl(item?.cover),
    episode_count: Number(item?.episode_count) || 0, tags, source: 'huangguo', url: '' };
}

function pageData(data, page, pageTitle) {
  const seen = new Set();
  const results = (Array.isArray(data?.items) ? data.items : [])
    .map(normalizeItem)
    .filter(item => { if (!item || seen.has(item.series_id)) return false; seen.add(item.series_id); return true; });
  const pagination = data?.pagination || {};
  const totalPages = Number(pagination.pages) || 0;
  const hasMore = totalPages > page && results.length > 0;
  return { success: true, results, page, total: Number(pagination.total) || 0, totalPages,
    hasMore, nextPage: hasMore ? page + 1 : null, genres: CATEGORIES, pageTitle, sourceUrl: ORIGINS[originIndex] };
}

async function browseList(options = {}) {
  try {
    const { genre = '', page = 1 } = options || {};
    const pg = Number.parseInt(page, 10) || 1;
    if (!Number.isSafeInteger(pg) || pg < 1 || pg > 100000) throw new Error('黄果页码无效，请重新选择');
    const slug = String(genre || CATEGORIES[0].slug);
    if (!CATEGORIES.some(item => item.slug === slug)) throw new Error('黄果题材无效，请重新选择');
    const data = await request('/api/videos/category/' + slug, { page: pg, size: PAGE_SIZE, sort: 'latest' });
    return pageData(data, pg, '黄果漫剧');
  } catch (error) { return { success: false, error: error.message }; }
}

async function search(keyword, page = 1, options = {}) {
  try {
    const kw = plainText(keyword);
    if (!kw) throw new Error('请输入搜索关键词');
    if (kw.length > 200) throw new Error('搜索词过长，请精简后重试');
    const pg = Math.max(1, Number.parseInt(page, 10) || 1);
    // 官方搜索只认 q 参数（keyword/wd 均返回"请输入搜索关键词"）。
    const data = await request('/api/search', { q: kw, page: pg, size: PAGE_SIZE }, options);
    return pageData(data, pg, '黄果漫剧');
  } catch (error) { return { success: false, error: error.message }; }
}

async function detail(seriesId) {
  const { dramaId } = parseId(seriesId);
  const data = await request(`/api/videos/${dramaId}`);
  if (String(data?.id) !== dramaId || !Array.isArray(data?.episodes)) {
    throw new Error('黄果返回的剧集与请求不一致');
  }
  return data;
}

async function fetchEpisodeList(seriesId) {
  const data = await detail(seriesId);
  const series_title = plainText(data.title) || '未命名漫剧';
  const cover = httpsUrl(data.cover);
  const seen = new Set();
  const episodes = data.episodes.filter(episode => episode && Number.isSafeInteger(Number(episode.ep_num)) && Number(episode.ep_num) > 0)
    .map(episode => ({ vid: `${seriesId}:${Number(episode.ep_num)}`,
      vid_index: Number(episode.ep_num), title: `第${Number(episode.ep_num)}集`,
      series_id: seriesId, series_title, cover,
      // status!==1 视为未开放（实测全站 status:1，保留语义防御）。
      locked: Number(episode.status) !== 1, source: 'huangguo' }))
    .filter(episode => { if (seen.has(episode.vid)) return false; seen.add(episode.vid); return true; })
    .sort((a, b) => a.vid_index - b.vid_index);
  if (!episodes.length) throw new Error('黄果分集列表为空');
  return { series_id: seriesId, series_title, cover, total: episodes.length, episodes, source: 'huangguo' };
}

// 拉取 m3u8 文本并汇总 EXTINF 时长；拉取失败返回 0（网络抖动不应拦截播放本身）。
async function playlistDuration(url, referer) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 12000);
  try {
    const { data } = await axios.get(url, { responseType: 'text', transformResponse: [text => text], timeout: 12000,
      signal: controller.signal, maxRedirects: 0, maxContentLength: 2 * 1024 * 1024,
      headers: { 'User-Agent': 'Mozilla/5.0', Referer: referer } });
    if (typeof data !== 'string' || !data.startsWith('#EXTM3U')) return 0;
    if (/#EXT-X-STREAM-INF:/.test(data)) return 0; // 多码率主清单不含分片时长，跳过校验
    return data.split(/\r?\n/).reduce((sum, line) => {
      const match = line.match(/^#EXTINF:([\d.]+)/);
      return match ? sum + Number(match[1]) : sum;
    }, 0);
  } catch (_) { return 0; }
  finally { clearTimeout(timer); }
}

async function fetchPlayUrlSingle(vid, sid) {
  try {
    const id = parseId(vid, true);
    if (sid != null && sid !== '' && parseId(sid).seriesId !== id.seriesId) throw new Error('黄果分集不属于当前剧集');
    await ensureOriginReady();
    const referer = ORIGINS[originIndex] + '/';
    // 2026-11-09 官方改版：旧 GET /api/videos/:id/play 仅回页面元数据（video_url 恒空），
    // 取流改为 POST /api/videos/:id/episodes/:ep/playback（空 JSON 体），地址在 data.streams.main。
    const data = await request(`/api/videos/${id.dramaId}/episodes/${id.ep}/playback`, null, { method: 'POST', timeout: 15000 });
    const url = httpsUrl(data.streams && data.streams.main);
    if (!url) throw new Error('黄果未提供有效 HTTPS 播放地址');
    // 官方对个别内容只下发预览片段：实际时长不足声明八成即视为不完整，如实报错（参照 huangguo-web 的做法）。
    const declared = Number(data.duration) || 0;
    if (declared > 0 && /\.m3u8/i.test(url)) {
      const actual = await playlistDuration(url, referer);
      if (actual > 0 && actual < declared * 0.8) {
        throw new Error(`该集只下发了预览片段（约 ${Math.round(actual)} 秒），暂无法完整播放`);
      }
    }
    return { url, spadeA: null, codec: '', source: 'huangguo', format: 'hls',
      userAgent: 'Mozilla/5.0', referer: referer };
  } catch (error) { return { url: null, spadeA: null, codec: null, source: 'huangguo', error: error.message }; }
}

module.exports = { browseList, search, fetchEpisodeList, fetchPlayUrlSingle, configureStateFile };
