/**
 * Same-day new-release backfill for search, ported from the desktop's near-seven-day
 * catalog: when the normal channels return nothing relevant, the App's online-time
 * ordered directory fills the gap. The native side owns the fetch; this module owns
 * the strict row shape, the title matching and the bounded merge.
 */
const MAX_RESULTS = 20;
const fail = (message, code = 'INVALID_INPUT') => Object.assign(new Error(message), { code });

export function normalizeTitle(value) {
  return String(value ?? '')
    .normalize('NFKC')
    .toLowerCase()
    .replace(/[\s\p{P}\p{S}]+/gu, '');
}

/** One native row; unknown shapes are skipped, never guessed. */
export function parseNewRelease(row) {
  if (!row || typeof row !== 'object' || Array.isArray(row)) return null;
  const seriesId = String(row.series_id ?? '');
  if (!/^\d{1,30}$/.test(seriesId)) return null;
  const title = typeof row.title === 'string' ? row.title.trim() : '';
  if (!title || title.length > 200) return null;
  const count = Number(row.episode_count);
  return {
    seriesId,
    title,
    coverUrl: typeof row.cover === 'string' && row.cover.startsWith('https://') && row.cover.length <= 2048 ? row.cover : '',
    description: typeof row.description === 'string' ? row.description.slice(0, 800) : '',
    episodeCount: Number.isSafeInteger(count) && count >= 0 && count <= 10000 ? count : 0,
    episodeLabel: row.released_today === true ? '今日上新' : '',
    tags: Array.isArray(row.tags) ? [...new Set(row.tags.filter(tag => typeof tag === 'string' && tag.length <= 40))].slice(0, 4) : [],
    newRelease: true,
  };
}

export function parseNewReleases(rows) {
  if (!Array.isArray(rows) || rows.length > 400) throw fail('上新目录数据无效');
  const seen = new Set(), items = [];
  for (const row of rows) {
    const item = parseNewRelease(row);
    if (!item || seen.has(item.seriesId)) continue;
    seen.add(item.seriesId);
    items.push(item);
  }
  return items;
}

/**
 * Matches the query against the catalog and returns ranked results: exact title,
 * title containing the query, then query containing the title. A row whose title
 * shares no normalized characters with the query is never claimed as a match.
 */
export function matchNewReleases(items, query, { limit = MAX_RESULTS } = {}) {
  if (!Array.isArray(items) || typeof query !== 'string' || !Number.isSafeInteger(limit) || limit < 1) throw fail('上新匹配参数无效');
  const needle = normalizeTitle(query);
  if (!needle) return [];
  const ranked = [];
  for (const item of items) {
    const title = normalizeTitle(item.title);
    if (!title) continue;
    const rank = title === needle ? 0 : title.includes(needle) ? 1 : needle.includes(title) && title.length >= 2 ? 2 : -1;
    if (rank >= 0) ranked.push({ rank, item });
  }
  ranked.sort((left, right) => left.rank - right.rank
    || right.item.episodeCount - left.item.episodeCount
    || left.item.title.localeCompare(right.item.title, 'zh-Hans-CN'));
  return ranked.slice(0, limit).map(entry => entry.item);
}

/** True when the first page of a channel needs the backfill: empty or nothing relevant. */
export function needsBackfill(items, query) {
  if (!Array.isArray(items)) return true;
  if (!items.length) return true;
  const needle = normalizeTitle(query);
  if (!needle) return false;
  return !items.some(item => normalizeTitle(item?.title).includes(needle) || needle.includes(normalizeTitle(item?.title)) && normalizeTitle(item?.title).length >= 2);
}

/** Merges backfill results into the page result with an honest notice. */
export function mergeNewReleases(result, backfill, query) {
  if (!result || typeof result !== 'object' || !Array.isArray(result.items)) throw fail('搜索结果无效');
  const matches = matchNewReleases(backfill || [], query);
  if (!matches.length) return result;
  const existing = new Set(result.items.map(item => item.seriesId));
  const added = matches.filter(item => !existing.has(item.seriesId));
  if (!added.length) return result;
  const merged = [...result.items, ...added];
  return { ...result, items: merged, total: merged.length,
    notice: `${result.notice ? result.notice + ' ' : ''}官网与 App 搜索尚未收录时，已从近 7 天上新中补入 ${added.length} 部。` };
}
