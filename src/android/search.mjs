/** Cursor-based search. Metadata only: no stream URLs or device identities are cached here. */
const fail = (message, code = 'SEARCH_INVALID') => Object.assign(new Error(message), { code });
const ID = /^\d{1,30}$/;
const SESSION_MS = 5 * 60_000;
const MAX_QUERIES = 8;
const MAX_PAGES = 100;
const MAX_CACHED_PAGES = 120;
const text = (value, max = 800) => typeof value === 'string' ? value.trim().slice(0, max) : '';
function integer(value) {
  if (typeof value === 'string' && !/^\d{1,8}$/.test(value)) return null;
  if (typeof value !== 'string' && typeof value !== 'number') return null;
  const n = Number(value);
  return Number.isSafeInteger(n) && n >= 0 ? n : null;
}
function more(value) {
  if ([true, 1, '1', 'true'].includes(value)) return true;
  if ([false, 0, '0', 'false'].includes(value)) return false;
  throw fail('搜索服务未返回有效的分页状态');
}
function cursorText(value, max) {
  if (value == null || value === '') return '';
  if (typeof value !== 'string' || value.length > max || /[\u0000-\u001f\u007f]/.test(value)) throw fail('搜索服务返回了无效游标');
  return value;
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
function item(video, cell) {
  if (!video || typeof video !== 'object') return null;
  // Never stringify a JSON number: the provider's large numeric IDs lose precision in JS.
  const seriesId = [video.series_id, cell.book_id, cell.search_result_id].find(v => typeof v === 'string' && ID.test(v));
  const detail = video.video_detail || {};
  // video.title can be a search alias shared by every season. Trust the full title
  // only when the nested metadata is bound to this exact string identity.
  const fullTitle = detail.series_title;
  const canonicalTitle = detail.series_id_str === seriesId && typeof fullTitle === 'string' &&
    fullTitle.length <= 200 && !/[\u0000-\u001f\u007f]/.test(fullTitle) ? fullTitle.trim() : '';
  const title = canonicalTitle || text(video.title || cell.search_high_light?.title?.text || cell.cell_name, 200);
  if (!seriesId || !title) return null;
  let categories = [];
  try { categories = JSON.parse(video.category_schema || detail.category_schema || '[]'); } catch { /* Optional metadata. */ }
  return {
    seriesId, title, coverUrl: cover(video.cover || video.cover_url || detail.series_cover),
    description: text(detail.series_intro || video.video_desc || video.sub_title),
    episodeCount: Math.min(integer(video.episode_cnt ?? detail.episode_cnt) || 0, 10000),
    episodeLabel: text(detail.episode_right_text, 80),
    tags: Array.isArray(categories) ? [...new Set(categories.map(c => text(c?.name, 40)).filter(Boolean))].slice(0, 8) : [],
  };
}

export function parseSearchPage(raw, { offset = 0, seen = [] } = {}) {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw) || ![0, '0'].includes(raw.code)) throw fail('搜索接口暂未返回有效结果，请重试');
  const tabs = Array.isArray(raw.search_tabs) ? raw.search_tabs : raw.data?.search_tabs;
  const tab = Array.isArray(tabs) && tabs.find(t => [11, '11'].includes(t?.tab_type));
  if (!tab || !Array.isArray(tab.data) || tab.data.length > 200) throw fail('搜索接口缺少短剧结果，请重试');
  const hasMore = more(tab.has_more), next = integer(tab.next_offset);
  if (hasMore && (next === null || next <= offset || next > 10000)) throw fail('搜索分页未前进，请重试当前页', 'SEARCH_CURSOR_STALLED');
  const searchId = cursorText(tab.search_id, 512), passback = cursorText(tab.passback, 512);
  const ids = new Set(seen), items = [];
  for (const outer of tab.data) {
    const cells = [outer];
    if (typeof outer?.lynx_data === 'string' && outer.lynx_data.length <= 256 * 1024) {
      try {
        const nested = JSON.parse(outer.lynx_data)?.cell_data;
        if (Array.isArray(nested)) cells.push(...nested.slice(0, 200));
        else if (nested && typeof nested === 'object') cells.push(nested);
      } catch { /* Ignore a malformed optional card. */ }
    }
    for (const cell of cells) {
      if (!Array.isArray(cell?.video_data)) continue;
      for (const video of cell.video_data.slice(0, 20)) {
        const row = item(video, cell);
        if (!row || ids.has(row.seriesId)) continue;
        ids.add(row.seriesId); items.push(row);
        if (items.length > 40) throw fail('搜索结果超过单页限制');
      }
    }
  }
  // A repeated first page must not look like a working "next" button.
  if (hasMore && !items.length) throw fail('搜索分页没有返回新的短剧，请重试当前页', 'SEARCH_CURSOR_STALLED');
  return { items, hasMore, cursor: { offset: next ?? offset, searchId, passback }, totalKnown: false, total: items.length };
}

export function createSearchPager(requestPage) {
  if (typeof requestPage !== 'function') throw fail('需要提供分页搜索请求函数', 'INVALID_INPUT');
  const sessions = new Map();
  return function search(keyword, page = 1, { refresh = false } = {}) {
    if (typeof keyword !== 'string' || !keyword.trim() || keyword.trim().length > 100 ||
        !Number.isSafeInteger(page) || page < 1 || page > MAX_PAGES) throw fail('搜索词或页码无效', 'INVALID_INPUT');
    keyword = keyword.trim();
    const now = Date.now();
    for (const [key, session] of sessions) if (now - session.used >= SESSION_MS) sessions.delete(key);
    if (refresh && page !== 1) throw fail('请从第 1 页重新搜索', 'INVALID_INPUT');
    if (refresh) sessions.delete(keyword);
    let session = sessions.get(keyword);
    if (!session) {
      if (page !== 1) throw fail('搜索已过期，请重新搜索后再翻页', 'SEARCH_EXPIRED');
      session = { used: now, pages: new Map(), pending: new Map() };
    }
    session.used = now;
    sessions.delete(keyword); sessions.set(keyword, session);
    while (sessions.size > MAX_QUERIES) sessions.delete(sessions.keys().next().value);
    if (session.pages.has(page)) return Promise.resolve(session.pages.get(page).result);
    if (session.pending.has(page)) return session.pending.get(page);
    const previous = session.pages.get(page - 1);
    if (page > 1 && !previous) throw fail('请先读取上一页，或重新搜索', 'SEARCH_EXPIRED');
    if (previous && !previous.result.hasMore) throw fail('已经到搜索结果末页', 'SEARCH_END');
    const cursor = previous?.cursor || { offset: 0, searchId: '', passback: '' };
    const seen = [];
    for (const [n, value] of session.pages) if (n < page) seen.push(...value.result.items.map(v => v.seriesId));
    const params = { keyword, offset: cursor.offset,
      ...(cursor.searchId ? { searchId: cursor.searchId } : {}), ...(cursor.passback ? { passback: cursor.passback } : {}) };
    const pending = Promise.resolve().then(() => requestPage(params)).then(raw => {
      const parsed = parseSearchPage(raw, { offset: cursor.offset, seen });
      parsed.items.forEach(row => { Object.freeze(row.tags); Object.freeze(row); });
      Object.freeze(parsed.items);
      Object.freeze(parsed.cursor);
      const atLimit = page === MAX_PAGES && parsed.hasMore;
      const result = Object.freeze({ items: parsed.items, page, totalPages: !parsed.hasMore ? page : 0,
        totalKnown: false, total: parsed.items.length, hasMore: parsed.hasMore && !atLimit,
        ...(atLimit ? { notice: '已显示前 100 页，请缩小关键词继续查找。' } : {}) });
      session.pages.set(page, { result, cursor: parsed.cursor });
      // Evict whole older queries, preserving the most recently requested query's
      // previous-page cursor chain. A late response must not evict the active query.
      let cachedPages = [...sessions.values()].reduce((sum, entry) => sum + entry.pages.size, 0);
      while (cachedPages > MAX_CACHED_PAGES && sessions.size > 1) {
        const oldest = sessions.keys().next().value;
        cachedPages -= sessions.get(oldest).pages.size;
        sessions.delete(oldest);
      }
      return result;
    }).finally(() => session.pending.delete(page));
    session.pending.set(page, pending);
    return pending;
  };
}
