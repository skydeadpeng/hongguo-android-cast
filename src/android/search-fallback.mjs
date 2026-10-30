/** Metadata search: choose one channel at the first page, never splice cursor chains. */
import { createSearchPager } from './search.mjs';
const SESSION_MS = 5 * 60_000, MAX_QUERIES = 8, MAX_CACHED_PAGES = 120, TOTAL_MS = 20_000, APP_MS = 12_000;
const fail = (message, code = 'SEARCH_INVALID') => Object.assign(new Error(message), { code });
const cancelled = () => fail('搜索已取消', 'ERR_CANCELED');
const aborted = error => ['ERR_CANCELED', 'ABORT_ERR'].includes(error?.code) || error?.name === 'AbortError';
const cleanText = (value, max = 800) => typeof value === 'string' && !/[\u0000-\u001f\u007f]/.test(value) ? value.trim().slice(0, max) : '';
function integer(value) {
  if (typeof value !== 'number' && (typeof value !== 'string' || !/^\d{1,10}$/.test(value))) return null;
  const n = Number(value); return Number.isSafeInteger(n) && n >= 0 && n <= 1_000_000_000 ? n : null;
}
function cover(value) {
  if (typeof value !== 'string' || value.length > 2048 || /[\s\\]/.test(value)) return '';
  try {
    const u = new URL(value);
    if (u.protocol !== 'https:' || u.username || u.password || u.port && u.port !== '443' ||
        !['byteimg.com', 'fqnovelpic.com', 'fqnovelstatic.com'].some(h => u.hostname === h || u.hostname.endsWith('.' + h))) return '';
    return value;
  } catch { return ''; }
}

export function parseOfficialSearch(body, { page = 1, seen = [] } = {}) {
  if (!Number.isSafeInteger(page) || page < 1 || page > 50) throw fail('官网搜索页码无效', 'INVALID_INPUT');
  if (typeof body !== 'string' || !body.trim() || body.length > 2 * 1024 * 1024) throw fail('官网搜索响应为空或过长');
  let raw;
  try { raw = JSON.parse(body); } catch { throw fail('官网搜索响应不是有效 JSON'); }
  if (!raw || typeof raw !== 'object' || Array.isArray(raw) ||
      raw.code !== undefined && (typeof raw.code !== 'number' || raw.code !== 0) ||
      !Array.isArray(raw.searchList) || raw.searchList.length > 200) throw fail('官网搜索暂未返回有效结果');
  if (raw.pageNum !== undefined && integer(raw.pageNum) !== page) throw fail('官网搜索页码与请求不一致');
  if (raw.hasMore !== undefined && typeof raw.hasMore !== 'boolean') throw fail('官网搜索分页状态无效');
  const total = raw.totalCount === undefined ? null : integer(raw.totalCount);
  const totalPages = raw.totalPages === undefined ? null : integer(raw.totalPages);
  if (raw.totalCount !== undefined && total === null || raw.totalPages !== undefined && totalPages === null) throw fail('官网搜索总数无效');
  const items = [], ids = new Set(seen), recognized = new Set();
  for (const row of raw.searchList) {
    if (!row || typeof row !== 'object' || Array.isArray(row)) continue;
    const nested = row.video_data;
    const video = nested && typeof nested === 'object' && !Array.isArray(nested) && Object.keys(nested).length ? nested : row;
    const seriesId = [video.series_id, row.series_id, row.keyword].find(value => typeof value === 'string' && /^\d{1,30}$/.test(value));
    const title = cleanText(video.series_name || video.series_title || row.name || video.title, 200);
    if (!seriesId || !title) continue;
    recognized.add(seriesId);
    if (ids.has(seriesId)) continue;
    ids.add(seriesId);
    const list = video.tags || video.category_list;
    const tags = Array.isArray(list) ? [...new Set(list.map(value => cleanText(typeof value === 'string' ? value : value?.name || value?.tag_name, 40)).filter(Boolean))].slice(0, 8) : [];
    items.push({ seriesId, title, coverUrl: cover(video.series_cover || video.cover),
      description: cleanText(video.series_intro || video.video_desc), episodeCount: Math.min(integer(video.episode_cnt) || 0, 10000),
      episodeLabel: cleanText(video.episode_right_text, 80), tags });
  }
  if (raw.searchList.length && !recognized.size) throw fail('官网搜索条目格式已变化，请重试');
  if (raw.searchList.length && !items.length && seen.length) throw fail('官网搜索未返回新的短剧，请重试当前页', 'SEARCH_CURSOR_STALLED');
  const reportedMore = raw.hasMore ?? (totalPages !== null ? page < totalPages : false);
  if (reportedMore && !items.length) throw fail('官网搜索分页没有返回新的短剧', 'SEARCH_CURSOR_STALLED');
  const atLimit = page === 50 && reportedMore;
  for (const item of items) { Object.freeze(item.tags); Object.freeze(item); }
  Object.freeze(items);
  return Object.freeze({ items, page, totalKnown: total !== null, total: total ?? items.length,
    totalPages: totalPages ?? (!reportedMore ? page : 0), hasMore: reportedMore && !atLimit,
    ...(atLimit ? { notice: '已显示官网前 50 页，请缩小关键词继续查找。' } : {}) });
}

/** Bounded wait; late callbacks cannot publish channel or cached result state. */
function waitFor(value, deadline, signal) {
  if (signal?.aborted) return Promise.reject(cancelled());
  const remaining = deadline - Date.now();
  if (remaining <= 0) return Promise.reject(fail('搜索请求超时，请重试', 'ETIMEDOUT'));
  return new Promise((resolve, reject) => {
    let finished = false;
    const finish = (fn, result) => { if (finished) return; finished = true; clearTimeout(timer); signal?.removeEventListener('abort', abort); fn(result); };
    const abort = () => finish(reject, cancelled());
    const timer = setTimeout(() => finish(reject, fail('搜索请求超时，请重试', 'ETIMEDOUT')), remaining);
    signal?.addEventListener('abort', abort, { once: true });
    Promise.resolve(value).then(result => finish(resolve, result), error => finish(reject, error));
  });
}

export function createFallbackSearch(requestPage, requestHtml) {
  if (typeof requestPage !== 'function' || typeof requestHtml !== 'function') throw fail('需要提供 App 与官网搜索请求函数', 'INVALID_INPUT');
  const sessions = new Map();
  const discard = (key, session) => { session.controller.abort(); if (sessions.get(key) === session) sessions.delete(key); };
  function createSession() {
    const session = { used: Date.now(), channel: '', controller: new AbortController(), pages: new Map(), pending: new Map(), appBudget: null };
    session.app = createSearchPager(params => {
      const deadlineMs = session.appBudget;
      const timeoutMs = Math.max(1, Math.min(APP_MS, deadlineMs - Date.now()));
      return requestPage({ ...params, timeoutMs, deadlineMs });
    });
    return session;
  }
  return function search(keyword, page = 1, { refresh = false, signal } = {}) {
    if (typeof keyword !== 'string' || !keyword.trim() || keyword.trim().length > 100 || /[\u0000-\u001f\u007f]/.test(keyword) ||
        !Number.isSafeInteger(page) || page < 1 || page > 100 || refresh && page !== 1) throw fail('搜索词或页码无效', 'INVALID_INPUT');
    if (signal?.aborted) return Promise.reject(cancelled());
    keyword = keyword.trim();
    const now = Date.now(), deadline = now + TOTAL_MS;
    for (const [key, value] of sessions) if (now - value.used >= SESSION_MS) discard(key, value);
    if (refresh && sessions.has(keyword)) discard(keyword, sessions.get(keyword));
    let session = sessions.get(keyword);
    if (!session) {
      if (page !== 1) throw fail('搜索已过期，请重新搜索后再翻页', 'SEARCH_EXPIRED');
      session = createSession();
    }
    session.used = now; sessions.delete(keyword); sessions.set(keyword, session);
    while (sessions.size > MAX_QUERIES) { const key = sessions.keys().next().value; discard(key, sessions.get(key)); }
    if (session.pages.has(page)) return Promise.resolve(session.pages.get(page));
    if (session.pending.has(page)) return waitFor(session.pending.get(page), deadline, signal);
    const previous = session.pages.get(page - 1);
    if (page > 1 && !previous) throw fail('请先读取上一页，或重新搜索', 'SEARCH_EXPIRED');
    if (previous && !previous.hasMore) throw fail('已经到搜索结果末页', 'SEARCH_END');
    if (session.channel === 'web' && page > 50) throw fail('官网搜索最多支持 50 页', 'SEARCH_END');
    const controller = new AbortController();
    const abort = () => controller.abort();
    session.controller.signal.addEventListener('abort', abort, { once: true });
    signal?.addEventListener('abort', abort, { once: true });
    const live = () => { if (controller.signal.aborted || sessions.get(keyword) !== session) throw cancelled(); };
    const task = Promise.resolve().then(async () => {
      live();
      let result;
      if (session.channel !== 'web') {
        session.appBudget = Math.min(deadline, Date.now() + APP_MS);
        try {
          result = await waitFor(Promise.resolve().then(() => session.app(keyword, page)), session.appBudget, controller.signal);
          live(); session.channel = 'app';
          result = { ...result, channel: 'app', notice: result.notice || '' };
        } catch (error) {
          live();
          if (page !== 1 || session.channel === 'app' || aborted(error) || error?.code === 'INVALID_INPUT') throw error;
          session.channel = 'web';
        }
      }
      if (session.channel === 'web') {
        live();
        const timeoutMs = deadline - Date.now();
        if (timeoutMs <= 0) throw fail('搜索请求超时，请重试', 'ETIMEDOUT');
        const url = new URL('https://hongguoduanju.com/api/search/result');
        url.searchParams.set('aid', '8662'); url.searchParams.set('query', keyword); url.searchParams.set('page', String(page));
        const body = await waitFor(Promise.resolve().then(() => requestHtml(url.href, { timeoutMs, deadlineMs: deadline, signal: controller.signal })), deadline, controller.signal);
        live();
        const seen = [];
        for (const [number, value] of session.pages) if (number < page) seen.push(...value.items.map(item => item.seriesId));
        result = parseOfficialSearch(body, { page, seen });
        result = { ...result, channel: 'web', notice: `App 搜索未成功，当前使用官网搜索。${result.notice || ''}` };
      }
      live();
      const frozen = Object.freeze(result);
      session.pages.set(page, frozen);
      // Keep the current query's cursor chain, evicting complete older sessions.
      // A late older completion may not evict a more recently requested query.
      const keys = [...sessions.keys()];
      if (keys[keys.length - 1] === keyword) {
        let cached = [...sessions.values()].reduce((n, value) => n + value.pages.size, 0);
        while (cached > MAX_CACHED_PAGES && sessions.size > 1) {
          const key = sessions.keys().next().value, oldest = sessions.get(key);
          cached -= oldest.pages.size; discard(key, oldest);
        }
      }
      return frozen;
    }).finally(() => {
      controller.abort(); session.controller.signal.removeEventListener('abort', abort); signal?.removeEventListener('abort', abort);
      if (session.pending.get(page) === task) session.pending.delete(page);
    });
    session.pending.set(page, task);
    return task;
  };
}
