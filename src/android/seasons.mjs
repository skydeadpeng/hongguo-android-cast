/** Independent, title-matched season directory. Never joins playback queues or changes stream selection. */
const ID = /^\d{1,30}$/;
const MAX_ITEMS = 40, MAX_PAGES = 10;
const NOTE = '按标题匹配，可能存在不同版本；切季不会自动播放。';
const fail = (message, code = 'SEASON_INVALID') => Object.assign(new Error(message), { code });
const clean = value => typeof value === 'string' && value.length <= 200 && !/[\u0000-\u001f\u007f]/.test(value) ? value.trim() : '';
const baseKey = value => value.normalize('NFKC').replace(/\s+/g, '').toLowerCase();
function seasonNumber(value) {
  if (/^[0-9]{1,2}$/.test(value)) { const n = Number(value); return n >= 1 && n <= 99 ? n : null; }
  const digits = '零一二三四五六七八九';
  value = value.replace(/两/g, '二');
  for (let n = 1; n <= 99; n++) {
    const tens = Math.floor(n / 10), ones = n % 10;
    const name = tens ? (tens === 1 ? '' : digits[tens]) + '十' + (ones ? digits[ones] : '') : digits[ones];
    if (value === name) return n;
  }
  return null;
}

/** Keep base punctuation: “持械入宋：第四季” and “持械入宋第四季” may be different productions. */
export function parseSeasonTitle(title) {
  title = clean(title);
  if (!title) return null;
  const normalized = title.normalize('NFKC').trim();
  const match = /^(.*?)(?:第\s*([零一二三四五六七八九十两0-9\s]+)\s*([季部])|Season\s*([0-9]{1,2}))\s*$/i.exec(normalized);
  const number = match ? seasonNumber((match[2] || match[4]).replace(/\s/g, '')) : null;
  if (!match || !number || !match[1].trim()) return Object.freeze({ base: baseKey(normalized), keyword: title, number: null, label: '当前剧集', explicit: false, unit: '' });
  const rawBase = match[1].trim();
  // Removing a trailing display delimiter is for the search keyword only, never for membership comparison.
  const keyword = rawBase.replace(/[:：·\-—]+$/u, '').trim();
  if (!keyword) return Object.freeze({ base: baseKey(normalized), keyword: title, number: null, label: '当前剧集', explicit: false, unit: '' });
  const unit = match[3] || '季';
  return Object.freeze({ base: baseKey(rawBase), keyword, number, label: `第 ${number} ${unit}`, explicit: true, unit });
}
function metadata(value) {
  if (!value || typeof value.seriesId !== 'string' || !ID.test(value.seriesId)) return null;
  const title = clean(value.title);
  if (!title) return null;
  const parsed = parseSeasonTitle(title);
  return Object.freeze({
    seriesId: value.seriesId, title,
    coverUrl: typeof value.coverUrl === 'string' ? value.coverUrl : '',
    episodeCount: Number.isSafeInteger(value.episodeCount) && value.episodeCount >= 0 && value.episodeCount <= 10000 ? value.episodeCount : 0,
    episodeLabel: typeof value.episodeLabel === 'string' ? value.episodeLabel.slice(0, 80) : '',
    tags: Object.freeze(Array.isArray(value.tags) ? value.tags.filter(t => typeof t === 'string').slice(0, 8) : []),
    seasonNumber: parsed?.number ?? null, seasonLabel: parsed?.label || '当前剧集',
  });
}
function candidates(seed, rows) {
  const current = metadata(seed), basis = parseSeasonTitle(seed?.title);
  if (!current || !basis) throw fail('当前剧集信息无效，请重新打开详情');
  const ids = new Set([current.seriesId]);
  const result = [current];
  for (const raw of rows) {
    const row = metadata(raw), parsed = parseSeasonTitle(raw?.title);
    if (!row || ids.has(row.seriesId) || !parsed?.explicit || parsed.base !== basis.base || basis.explicit && parsed.unit !== basis.unit) continue;
    ids.add(row.seriesId); result.push(row);
  }
  result.sort((a, b) => (a.seasonNumber ?? 0) - (b.seasonNumber ?? 0) || a.seriesId.localeCompare(b.seriesId));
  return result;
}
export function seasonCandidates(seed, rows) {
  if (!Array.isArray(rows) || rows.length > 400) throw fail('分季候选数据无效');
  const result = candidates(seed, rows);
  // Preserve the current season even when a very large group is truncated.
  const selected = result.slice(0, MAX_ITEMS);
  if (!selected.some(row => row.seriesId === seed.seriesId)) selected[MAX_ITEMS - 1] = result.find(row => row.seriesId === seed.seriesId);
  return Object.freeze(selected);
}

/** One explicit page at a time. Uses existing search without resetting its cursor/session. */
export function createSeasonLookup(search) {
  if (typeof search !== 'function') throw fail('需要提供分季搜索函数', 'INVALID_INPUT');
  return Object.freeze({
    async load(seed, { page = 1, previous = [] } = {}) {
      const current = metadata(seed), parsed = parseSeasonTitle(seed?.title);
      if (!current || !parsed || !parsed.keyword || parsed.keyword.length > 100) throw fail('剧名不适合自动查找分季，请使用搜索页', 'INVALID_INPUT');
      if (!Number.isSafeInteger(page) || page < 1 || page > MAX_PAGES || !Array.isArray(previous) || previous.length > MAX_ITEMS) throw fail('分季页码或已加载列表无效', 'INVALID_INPUT');
      const result = await search(parsed.keyword, page);
      if (!result || !Array.isArray(result.items) || result.items.length > 200) throw fail('分季搜索暂未返回有效结果，请重试', 'SEASON_SEARCH_INVALID');
      const rows = candidates(seed, [...previous, ...result.items]);
      const items = seasonCandidates(seed, rows);
      const known = typeof result.hasMore === 'boolean' || Number.isSafeInteger(result.totalPages) && result.totalPages > 0;
      const more = typeof result.hasMore === 'boolean' ? result.hasMore
        : Number.isSafeInteger(result.totalPages) && result.totalPages > page;
      const limit = rows.length >= MAX_ITEMS || page === MAX_PAGES;
      const truncated = rows.length > MAX_ITEMS || !!more && limit;
      const notice = limit && (more || rows.length > MAX_ITEMS)
        ? `${NOTE} 已达到本次查找上限，请用搜索页继续查找。`
        : NOTE;
      return Object.freeze({ items, keyword: parsed.keyword, page, hasMore: !!more && !limit, complete: known && !more && !truncated, truncated, notice, matching: 'title' });
    },
  });
}
