/** Android directory/playback source, with an optional native cursor-search adapter. */
import { createFallbackSearch } from './search-fallback.mjs';
import { loadDetail } from './detail-loader.mjs';
export const SITE_ORIGIN = 'https://hongguoduanju.com';
export const CATEGORIES = Object.freeze([
  Object.freeze({ id: 'real-drama', label: '真人剧' }),
  Object.freeze({ id: 'comic-drama', label: '漫剧' }),
  Object.freeze({ id: 'ai-drama', label: 'AI剧' }),
]);
const ID = /^\d{1,30}$/;
const CACHE_MS = 30_000;
const MAX_HTML = 8 * 1024 * 1024;
const error = (message, code = 'SOURCE_INVALID') => Object.assign(new Error(message), { code });
const isId = value => typeof value === 'string' && ID.test(value);
const text = value => typeof value === 'string' ? value.trim() : '';
const count = value => Number.isSafeInteger(Number(value)) && Number(value) >= 0 ? Number(value) : 0;
function requireId(value, label) {
  if (!isId(value)) throw error(`${label}必须是 1 至 30 位数字字符串`, 'INVALID_INPUT');
  return value;
}
function requirePage(value) {
  if (!Number.isSafeInteger(value) || value < 1) throw error('页码必须为正整数', 'INVALID_INPUT');
  return value;
}
function requireCategory(value) {
  if (!CATEGORIES.some(item => item.id === value)) throw error('目录分类无效', 'INVALID_INPUT');
  return value;
}
function requireQuality(value = 'auto') {
  if (!['auto', '720p', '1080p'].includes(value)) throw error('清晰度无效，请选择自动、720p 或 1080p', 'INVALID_INPUT');
  return value;
}
function appQuality(info, quality) {
  const rows = [info, ...(Array.isArray(info?.variants) ? info.variants : [])];
  if (rows.some(row => !row || row.requestedQuality !== undefined && row.requestedQuality !== quality)) {
    throw error('App 片源清晰度与请求不一致', 'QUALITY_MISMATCH');
  }
  if (quality !== 'auto' && rows.some(row =>
    !['app', 'api'].includes(row.source ?? info.source) ||
    !Number.isSafeInteger(row.width) || !Number.isSafeInteger(row.height) ||
    row.width <= 0 || row.height <= 0 || Math.min(row.width, row.height) < Number.parseInt(quality, 10))) {
    throw error(`本集暂无尺寸可确认且满足 ${quality} 的片源，未降级。`, 'QUALITY_UNAVAILABLE');
  }
  return { ...info, requestedQuality: quality, ...(Array.isArray(info.variants)
    ? { variants: info.variants.map(row => ({ ...row, requestedQuality: quality })) } : {}) };
}

/** Read a balanced JSON object only; quoted braces and escapes never execute. */
export function extractRouterData(html) {
  if (typeof html !== 'string' || !html.trim() || html.length > MAX_HTML) {
    throw error('来源页面为空或超过大小限制');
  }
  const match = /(?:window\.)?_ROUTER_DATA\s*=\s*(\{)/.exec(html);
  if (!match) throw error('来源页面未返回目录数据');
  const start = match.index + match[0].length - 1;
  let depth = 0, quoted = false, escaped = false;
  for (let i = start; i < html.length; i++) {
    const char = html[i];
    if (quoted) {
      if (escaped) escaped = false;
      else if (char === '\\') escaped = true;
      else if (char === '"') quoted = false;
    } else if (char === '"') quoted = true;
    else if (char === '{') depth++;
    else if (char === '}' && --depth === 0) {
      try { return JSON.parse(html.slice(start, i + 1)); }
      catch (_) { throw error('来源目录数据不是有效 JSON'); }
    }
  }
  throw error('来源目录数据不完整');
}

/** Lexical URL guard; Android's transport also guards resolved addresses/redirects. */
function publicHttpsUrl(value) {
  if (typeof value !== 'string' || value.length > 16_384 || /[\s\\]/.test(value)) return '';
  try {
    const url = new URL(value);
    const host = url.hostname.toLowerCase().replace(/\.$/, '');
    if (url.protocol !== 'https:' || url.username || url.password || url.port && url.port !== '443' ||
        !host.includes('.') || /^[\d.]+$/.test(host) || host.includes(':') ||
        /(^|\.)(localhost|local|internal|lan|home|test|invalid|example|onion)$/.test(host) ||
        !host.split('.').every(label => /^[a-z\d](?:[a-z\d-]{0,61}[a-z\d])?$/i.test(label))) return '';
    // Preserve signed CDN query encoding while dropping only the Range parameter.
    url.search = url.search.slice(1).split('&').filter(part => decodeURIComponent(part.split('=')[0]).toLowerCase() !== 'range').join('&');
    url.hash = '';
    return url.href;
  } catch (_) { return ''; }
}
function series(item, search = false) {
  if (!item || !isId(item.series_id)) return null;
  const title = text(search ? item.series_title : item.series_name);
  if (!title) return null;
  const rawTags = search ? item.category_list : item.tags;
  const tags = Array.isArray(rawTags) ? [...new Set(rawTags.map(tag => text(search ? tag?.name : tag)).filter(Boolean))].slice(0, 8) : [];
  return {
    seriesId: item.series_id, title,
    coverUrl: publicHttpsUrl(item.series_cover),
    description: text(item.series_intro), episodeCount: count(item.episode_cnt),
    tags, episodeLabel: text(item.episode_right_text).slice(0, 80),
  };
}
function uniqueSeries(items, search = false) {
  const seen = new Set();
  return items.map(item => series(search ? item?.video_data : item, search)).filter(item => {
    if (!item || seen.has(item.seriesId)) return false;
    seen.add(item.seriesId);
    return true;
  });
}

export function parseCatalog(html, { category = 'real-drama', page = 1 } = {}) {
  requireCategory(category);
  requirePage(page);
  const router = extractRouterData(html);
  const data = Object.values(router.loaderData || {}).find(value => value?.categoryRoute?.contentType === category);
  if (!data || data.isSuccess === false || !Array.isArray(data.recommendList)) throw error('来源目录暂不可用');
  return {
    items: uniqueSeries(data.recommendList),
    page: count(data.pagination?.pageNum) || page,
    totalPages: count(data.pagination?.totalPages), total: count(data.pagination?.total),
  };
}
export function parseSearch(html) {
  const data = extractRouterData(html).loaderData?.['search_(keyword)/page'];
  if (!data || data.isSuccess === false || !Array.isArray(data.searchList)) throw error('来源搜索暂不可用');
  const items = uniqueSeries(data.searchList, true);
  return { items, total: count(data.totalCount ?? data.total ?? data.pagination?.total) || items.length };
}
export function parseDetail(html, seriesId) {
  requireId(seriesId, '剧集编号');
  const loaders = extractRouterData(html).loaderData;
  const data = loaders?.['player_(series_id)/page'] ?? loaders?.['player_(series_id)/(vid)/page'] ?? loaders?.detail_page;
  const detail = data?.seriesDetail;
  if (data?.isSuccess === false || detail?.series_id !== seriesId ||
      data.series_id !== undefined && data.series_id !== seriesId) throw error('剧集详情与请求编号不一致', 'IDENTITY_MISMATCH');
  const item = series(detail);
  if (!item || !Array.isArray(detail.vid_list)) throw error('来源页面缺少剧集详情或分集列表');
  const seen = new Set();
  const accessible = count(detail.accessible_episode_cnt);
  // The directory count is a preview hint, not an authoritative per-item lock.
  // Items outside it remain selectable and are checked by parsePlayback on use.
  const episodes = detail.vid_list.map((vid, i) => ({
    vid, index: i + 1, webAvailable: i < accessible,
    availability: i < accessible ? 'available' : 'unknown',
  }))
    .filter(episode => {
      if (!isId(episode.vid) || seen.has(episode.vid)) return false;
      seen.add(episode.vid);
      return true;
    });
  return { ...item, episodes, accessibleEpisodeCount: episodes.filter(episode => episode.webAvailable).length };
}
function matchesPlayer(value, seriesId, vid, allowCanonical = false) {
  try {
    const url = new URL(value);
    return url.origin === SITE_ORIGIN && !url.username && !url.password &&
      (url.pathname === `/player/${seriesId}/${vid}` ||
       allowCanonical && !url.search && url.pathname === `/player/${seriesId}`);
  } catch (_) { return false; }
}
function videoObjects(value) {
  if (Array.isArray(value)) return value.flatMap(videoObjects);
  if (!value || typeof value !== 'object') return [];
  return [...(value['@type'] === 'VideoObject' ? [value] : []), ...videoObjects(value['@graph'])];
}
export function parsePlayback(html, seriesId, vid) {
  requireId(seriesId, '剧集编号');
  requireId(vid, '分集编号');
  const loaders = extractRouterData(html).loaderData;
  const data = loaders?.['player_(series_id)/(vid)/page'] ?? loaders?.['player_(series_id)/page'];
  const seo = data?.publicVideoSeo;
  if (!data || data.isSuccess === false || data.series_id !== seriesId || data.vid !== vid ||
      data.seriesDetail?.series_id !== seriesId || seo?.seriesId !== seriesId || seo.itemId !== vid) {
    throw error('播放页面与请求剧集或分集不一致', 'IDENTITY_MISMATCH');
  }
  if (seo.available !== true) throw error('该分集未向官网公开播放，请在官方 App 查看', 'EPISODE_UNAVAILABLE');
  // Protected pages expose SEO previews even when metadata claims the full duration.
  if (data.protectedPlayback === true) throw error('官网仅提供本集预览，正在尝试完整片源', 'PREVIEW_ONLY');
  // Keep the verified first-episode CDN form. New canonical pages also omit
  // /{vid} for later episodes, whose official media path must bind that VID.
  const allowCanonicalFirst = Array.isArray(data.seriesDetail.vid_list) && data.seriesDetail.vid_list[0] === vid;
  let invalidUrl = false;
  for (const script of html.matchAll(/<script\b([^>]*)>([\s\S]*?)<\/script\s*>/gi)) {
    if (!/\btype\s*=\s*["']application\/ld\+json["']/i.test(script[1])) continue;
    let json;
    try { json = JSON.parse(script[2]); } catch (_) { continue; }
    for (const video of videoObjects(json)) {
      const url = publicHttpsUrl(typeof video.contentUrl === 'string' ? video.contentUrl.replace(/&amp;/gi, '&') : video.contentUrl);
      const media = url ? new URL(url) : null;
      const officialMedia = media?.origin === SITE_ORIGIN && media.pathname.startsWith('/video/public/');
      const boundMedia = officialMedia && media.pathname.startsWith(`/video/public/${vid}/`);
      // Explicit contradictory media identity defeats even legacy first-episode evidence.
      if (officialMedia && !boundMedia) continue;
      const allowCanonical = allowCanonicalFirst || boundMedia;
      if (!matchesPlayer(video.url, seriesId, vid, allowCanonical) ||
          video['@id'] && !matchesPlayer(video['@id'], seriesId, vid, allowCanonical)) continue;
      if (url) return { url, seriesId, vid };
      invalidUrl = true;
    }
  }
  throw error(invalidUrl ? '来源播放地址未通过 HTTPS 公网地址校验' : '该分集暂无官网公开播放地址', invalidUrl ? 'INVALID_MEDIA_URL' : 'NO_PLAYABLE_URL');
}

/** Inject Android's native HTML request; never requires a Mac/local server. */
export function createSource(request, { fallback, webFallback = !!fallback, searchPage } = {}) {
  if (typeof request !== 'function') throw error('需要提供页面请求函数', 'INVALID_INPUT');
  if (fallback !== undefined && typeof fallback !== 'function') throw error('片源回退必须是请求函数', 'INVALID_INPUT');
  const pagedSearch = searchPage === undefined ? null : createFallbackSearch(searchPage, request);
  const cache = new Map(), pending = new Map();
  const cachedLoad = (key, execute, { refresh = false } = {}) => {
    if (refresh) cache.delete(key);
    const cached = cache.get(key);
    if (!refresh && cached?.expires > Date.now()) return Promise.resolve(cached.value);
    if (!refresh && pending.has(key)) return pending.get(key);
    const task = Promise.resolve().then(execute).then(value => {
      if (pending.get(key) === task) {
        cache.set(key, { value, expires: Date.now() + CACHE_MS });
        if (cache.size > 64) cache.delete(cache.keys().next().value);
      }
      return value;
    }).finally(() => { if (pending.get(key) === task) pending.delete(key); });
    pending.set(key, task);
    return task;
  };
  const load = (path, parse, recover, quality, options) => {
    const url = SITE_ORIGIN + path;
    return cachedLoad(quality === undefined ? url : `${url}#quality=${quality}`, () =>
      Promise.resolve().then(() => request(url)).then(parse).catch(cause => {
        if (!recover) throw cause;
        return recover(cause);
      }), options);
  };
  return Object.freeze({
    catalog(category = 'real-drama', page = 1, options = {}) {
      requireCategory(category); requirePage(page);
      return load(`/category/${category}${page > 1 ? `?page=${page}` : ''}`, html => parseCatalog(html, { category, page }), undefined, undefined, options);
    },
    search(keyword, page = 1, options = {}) {
      if (typeof keyword !== 'string' || !keyword.trim() || keyword.trim().length > 100) throw error('请输入 1 至 100 字的搜索词', 'INVALID_INPUT');
      requirePage(page);
      if (pagedSearch) return pagedSearch(keyword, page, options);
      if (page !== 1) throw error('网页搜索只提供首屏结果，请使用安卓分页搜索', 'SEARCH_UNAVAILABLE');
      return load(`/search/${encodeURIComponent(keyword.trim())}`, parseSearch);
    },
    detail(seriesId) {
      requireId(seriesId, '剧集编号');
      return cachedLoad(`${SITE_ORIGIN}/detail:${seriesId}`, () => loadDetail(request, seriesId, html => parseDetail(html, seriesId)));
    },
    playback(seriesId, vid, { quality = 'auto' } = {}) {
      requireId(seriesId, '剧集编号'); requireId(vid, '分集编号');
      requireQuality(quality);
      const app = async () => {
        if (!fallback) throw error(`本集 ${quality} 片源尚未取得，未降级。`, 'QUALITY_UNAVAILABLE');
        let info;
        try { info = await fallback(vid, { seriesId, quality }); }
        catch (cause) {
          if (cause?.code === 'QUALITY_UNAVAILABLE') throw error(`本集暂无尺寸可确认且满足 ${quality} 的片源，未降级。`, 'QUALITY_UNAVAILABLE');
          throw error(quality === 'auto' ? '网页片源未成功，App 回退也未完成，请重试。' : `${quality} 片源尚未取得，请重试；未降级。`, 'SOURCE_FALLBACK_FAILED');
        }
        if (!info || info.vid !== vid || info.seriesId !== undefined && info.seriesId !== seriesId || !publicHttpsUrl(info.url)) throw error('App 回退未返回所选分集的有效片源。', 'SOURCE_FALLBACK_INVALID');
        // Preserve keys/headers/labels, and bind only the independently requested quality.
        return { ...appQuality(info, quality), seriesId, vid };
      };
      if (quality !== 'auto') return cachedLoad(`${SITE_ORIGIN}/player/${seriesId}/${vid}#quality=${quality}`, app);
      return load(`/player/${seriesId}/${vid}`, html => parsePlayback(html, seriesId, vid), (webFallback || fallback) && (async cause => {
        // Mac's second web route can serve a valid, exactly-bound episode even
        // when the canonical series route returns 404. '_' is never its identity.
        if (webFallback) {
          try { return parsePlayback(await request(`${SITE_ORIGIN}/player/_/${vid}`), seriesId, vid); }
          catch { /* Only the same requested series/episode may succeed. */ }
        }
        if (!fallback) throw cause;
        return app();
      }), quality);
    },
  });
}
