#!/usr/bin/env node
/** webOS 取源链离线解析测试：夹具按官网现行页面结构建模（含 2026-11 改版后的系列级 ld+json url）。 */
const assert = require('assert');
const source = require('../webos/js/source.js');
let checks = 0;
const check = (value, message) => { checks++; if (!value) throw new Error(message); };

const CATEGORY_HTML = `<html><script>window._ROUTER_DATA = ${JSON.stringify({
  loaderData: { 'category_(slug)/page': {
    isSuccess: true, categoryRoute: { contentType: 'real-drama' },
    recommendList: [
      { series_id: '7686713195246930968', series_name: '测试剧甲', series_cover: 'https://p3.byteimg.com/a.webp', episode_cnt: 24, tags: ['逆袭', '都市'] },
      { series_id: '7688199338417196056', series_name: '测试剧乙', series_cover: '', episode_cnt: '12', tags: [] },
      { series_id: '7686713195246930968', series_name: '重复剧被去重', series_cover: '', episode_cnt: 1, tags: [] },
      { series_id: 'bad-id', series_name: '非法编号被丢弃', series_cover: '', episode_cnt: 1, tags: [] },
    ],
    selectorList: [], pagination: { pageNum: 1, total: 2, totalPages: 1 },
  } },
})};</script></html>`;

function playerPage(seriesId, vid, options = {}) {
  const seo = options.seo === undefined ? { available: true, itemId: vid, seriesId } : options.seo;
  const ldUrl = options.ldUrl === undefined ? `https://hongguoduanju.com/player/${seriesId}` : options.ldUrl;
  const contentUrl = options.contentUrl === undefined ? `https://hongguoduanju.com/video/public/${vid}/360p.mp4` : options.contentUrl;
  return `<html><script>window._ROUTER_DATA = ${JSON.stringify({
    loaderData: { 'player_(series_id)/(vid)/page': {
      isSuccess: options.isSuccess === undefined ? true : options.isSuccess,
      vid: options.routeVid === undefined ? String(vid) : options.routeVid,
      series_id: seriesId, seriesDetail: { series_id: seriesId }, publicVideoSeo: seo,
    } },
  })};</script><script type="application/ld+json">${JSON.stringify({ '@type': 'VideoObject', url: ldUrl, contentUrl })}</script>` +
    (options.extra ? options.extra : '') + `</html>`;
}

// ===== readRouterData =====
const router = source.readRouterData(CATEGORY_HTML);
check(router.loaderData['category_(slug)/page'].recommendList.length === 4, 'router data parsed');

// ===== parseCatalog =====
const catalog = source.parseCatalog(CATEGORY_HTML, 'real-drama');
check(catalog.results.length === 2, 'catalog keeps valid unique rows, got ' + catalog.results.length);
check(catalog.results[0].seriesId === '7686713195246930968' && catalog.results[0].title === '测试剧甲', 'catalog row fields');
check(catalog.results[1].episodeCount === 12, 'numeric episode count coerced');

// ===== parseEpisodes =====
const episodeHtml = `<html><script>window._ROUTER_DATA={"loaderData":{}};</script>` +
  `<script>var _=_ROUTER_DATA</script><script>window.__INITIAL_STATE__={"series_name":"测试剧甲","series_cover":"https://p3.byteimg.com/a.webp","vid_list":["7686715217652223038","7686715217652223038","7686715221565508670","7686715222000000912"],"accessible_episode_cnt":2}</script></html>`;
const episodes = source.parseEpisodes(episodeHtml, '7686713195246930968');
check(episodes.total === 3 && episodes.episodes[0].vid === '7686715217652223038', 'vid list deduped in order');
check(episodes.episodes[0].webAvailable === true && episodes.episodes[2].webAvailable === false, 'accessible count gates web availability');
check(episodes.title === '测试剧甲', 'series title decoded');

// ===== parsePlayUrl：现行系列级 ld+json url（2026-11 官网形态）=====
const fixed = source.parsePlayUrl(playerPage('7686713195246930968', '7686715217652223038'), '7686713195246930968', '7686715217652223038');
check(fixed.url === 'https://hongguoduanju.com/video/public/7686715217652223038/360p.mp4', 'series-level ld+json url accepted');
// 旧版逐集 url 仍接受
const legacy = source.parsePlayUrl(playerPage('7686713195246930968', '7686715217652223038', { ldUrl: 'https://hongguoduanju.com/player/7686713195246930968/7686715217652223038' }), '7686713195246930968', '7686715217652223038');
check(legacy.url.endsWith('/360p.mp4'), 'legacy per-episode ld+json url accepted');
// 其他剧的 VideoObject 必须被拒绝（series 级与逐集级都试）
for (const foreign of ['https://hongguoduanju.com/player/7699999999999999999', 'https://hongguoduanju.com/player/7699999999999999999/7686715217652223038']) {
  let rejected = false;
  try { source.parsePlayUrl(playerPage('7686713195246930968', '7686715217652223038', { ldUrl: foreign }), '7686713195246930968', '7686715217652223038'); }
  catch (_) { rejected = true; }
  check(rejected, 'foreign ld+json binding rejected: ' + foreign);
}
// 路由数据与本集不一致必须拒绝
for (const broken of [
  playerPage('7686713195246930968', '7686715217652223038', { routeVid: '7686715221565508670' }),
  playerPage('7686713195246930968', '7686715217652223038', { seo: { available: false, itemId: '7686715217652223038', seriesId: '7686713195246930968' } }),
  playerPage('7686713195246930968', '7686715217652223038', { seo: { available: true, itemId: '7686715221565508670', seriesId: '7686713195246930968' } }),
  playerPage('7686713195246930968', '7686715217652223038', { isSuccess: false }),
]) {
  let rejected = false;
  try { source.parsePlayUrl(broken, '7686713195246930968', '7686715217652223038'); }
  catch (_) { rejected = true; }
  check(rejected, 'mismatched route data rejected: ' + broken.slice(60, 130));
}
// 非 https/越权 contentUrl 拒绝；公网域放行（与桌面 web-media-guard 同语义）
for (const url of ['http://localhost/video.mp4', 'https://192.168.1.5/video.mp4', 'https://user:pass@hongguoduanju.com/video.mp4', 'ftp://hongguoduanju.com/video.mp4']) {
  let rejected = false;
  try { source.parsePlayUrl(playerPage('7686713195246930968', '7686715217652223038', { contentUrl: url }), '7686713195246930968', '7686715217652223038'); }
  catch (_) { rejected = true; }
  check(rejected, 'unsafe contentUrl rejected: ' + url);
}

console.log(`WEBOS_SOURCE_OK checks=${checks}`);
