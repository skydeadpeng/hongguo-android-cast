import test from 'node:test';
import assert from 'node:assert/strict';
import { resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
const sourceRoot = resolve(process.env.ANDROID_SOURCE_ROOT || fileURLToPath(new URL('..', import.meta.url)));
const { CATEGORIES, SITE_ORIGIN, extractRouterData, parseCatalog, parseSearch, parseDetail, parsePlayback, createSource } =
  await import(pathToFileURL(resolve(sourceRoot, 'src/android/source.mjs')).href);

const sid = '1234567890123456789', vid = '2345678901234567890';
const card = (overrides = {}) => ({ series_id: sid, series_name: ' 测试短剧 ', series_cover: 'https://cdn.example.net/cover.jpg', series_intro: ' 简介 ', episode_cnt: 80, tags: ['都市', '都市', 1, ' 逆袭 '], episode_right_text: ' 全80集 ', ...overrides });
const html = loaderData => `<script>window._ROUTER_DATA = ${JSON.stringify({ loaderData })};</script>`;
const catalog = (overrides = {}) => html({ category: { isSuccess: true, categoryRoute: { contentType: 'real-drama' }, recommendList: [card()], pagination: { pageNum: 1, totalPages: 8, total: 80 }, ...overrides } });
const search = (overrides = {}) => html({ 'search_(keyword)/page': { searchList: [{ video_data: { ...card(), series_title: ' 搜索结果 ', category_list: [{ name: ' 都市 ' }, { name: '都市' }, {}, null], vid_list: ['999'] } }], total: 22, ...overrides } });
const detail = (overrides = {}, data = {}, key = 'player_(series_id)/page') => html({ [key]: { seriesDetail: card({ vid_list: [vid, '3456789012345678901'], accessible_episode_cnt: 1, ...overrides }), ...data } });
const playerData = (overrides = {}) => ({ isSuccess: true, series_id: sid, vid, seriesDetail: { series_id: sid }, publicVideoSeo: { available: true, seriesId: sid, itemId: vid }, ...overrides });
const video = (overrides = {}) => ({ '@type': 'VideoObject', url: `${SITE_ORIGIN}/player/${sid}/${vid}`, contentUrl: 'https://media.example.net/v.mp4?Range=0-100&token=a%20b&x=1', ...overrides });
const playback = (data = playerData(), objects = [video()], key = 'player_(series_id)/(vid)/page') => html({ [key]: data }) + objects.map(object => `<script type="application/ld+json">${JSON.stringify(object)}</script>`).join('');
const throwsCode = (fn, code) => assert.throws(fn, error => error instanceof Error && error.code === code && /[\u4e00-\u9fff]/.test(error.message));

// All page fixtures are generated here; no real page, media or credentials are stored.
test('exports three immutable phone/TV catalog categories', () => {
  assert.deepEqual(CATEGORIES.map(item => item.id), ['real-drama', 'comic-drama', 'ai-drama']);
  assert.deepEqual(CATEGORIES.map(item => item.label), ['真人剧', '漫剧', 'AI剧']);
  assert(Object.isFrozen(CATEGORIES)); assert(Object.isFrozen(CATEGORIES[0]));
});
test('balanced JSON scanner handles braces, nested arrays and escaped quotes/backslashes', () => {
  const object = { nested: [{ title: 'a } { \\ " 嵌套', inner: { x: 1 } }] };
  assert.deepEqual(extractRouterData(`before<script>_ROUTER_DATA = ${JSON.stringify(object)}; globalThis.__executed=true;</script>`), object);
  assert.equal(globalThis.__executed, undefined);
});
test('scanner rejects empty, non-string, oversized, missing, truncated and JavaScript object payloads', () => {
  for (const value of ['', ' ', null, {}, 'x'.repeat(8 * 1024 * 1024 + 1), '<html/>', '_ROUTER_DATA={"x":{', '_ROUTER_DATA={"x":"unfinished', '_ROUTER_DATA={x:1}', '_ROUTER_DATA={"x":undefined}']) {
    throwsCode(() => extractRouterData(value), 'SOURCE_INVALID');
  }
});
test('catalog maps public metadata and pagination', () => {
  assert.deepEqual(parseCatalog(catalog()), { items: [{ seriesId: sid, title: '测试短剧', coverUrl: 'https://cdn.example.net/cover.jpg', description: '简介', episodeCount: 80, tags: ['都市', '逆袭'], episodeLabel: '全80集' }], page: 1, totalPages: 8, total: 80 });
});
test('catalog selects requested category rather than first unrelated model', () => {
  const page = html({ wrong: { categoryRoute: { contentType: 'real-drama' }, recommendList: [card()] }, correct: { categoryRoute: { contentType: 'ai-drama' }, recommendList: [], pagination: { pageNum: 3 } } });
  assert.deepEqual(parseCatalog(page, { category: 'ai-drama', page: 2 }), { items: [], page: 3, totalPages: 0, total: 0 });
});
test('catalog deduplicates IDs, rejects incomplete cards and defaults optional fields', () => {
  const page = catalog({ recommendList: [card(), card(), null, {}, card({ series_id: 123 }), card({ series_name: '' }), card({ series_id: '../x' }), card({ series_id: '345678', series_cover: 1, series_intro: null, episode_cnt: -1, tags: undefined, episode_right_text: null })], pagination: undefined });
  const result = parseCatalog(page, { page: 2 });
  assert.equal(result.items.length, 2); assert.equal(result.items[1].coverUrl, ''); assert.deepEqual(result.items[1].tags, []);
  assert.equal(result.items[1].description, ''); assert.equal(result.items[1].episodeCount, 0); assert.equal(result.items[1].episodeLabel, ''); assert.equal(result.page, 2);
});
test('catalog rejects missing/rejected category models and invalid options', () => {
  for (const value of [html({}), catalog({ isSuccess: false }), catalog({ recommendList: null })]) throwsCode(() => parseCatalog(value), 'SOURCE_INVALID');
  for (const options of [{ category: 'comic' }, { category: '../x' }, { page: 0 }, { page: '1' }, { page: NaN }]) throwsCode(() => parseCatalog(catalog(), options), 'INVALID_INPUT');
});
test('search preserves total episode count and does not turn preview vids into a full episode list', () => {
  const result = parseSearch(search()); assert.equal(result.total, 22);
  assert.equal(result.items[0].episodeCount, 80); assert.equal(result.items[0].title, '搜索结果');
  assert.deepEqual(result.items[0].tags, ['都市']); assert.equal('episodes' in result.items[0], false);
});
test('search reads the official totalCount field before legacy pagination totals', () => {
  assert.equal(parseSearch(search({ totalCount: 51 })).total, 51);
  assert.equal(parseSearch(search({ totalCount: '52', total: undefined })).total, 52);
  assert.equal(parseSearch(search({ totalCount: 0, searchList: [] })).total, 0);
});
test('search rejects invalid model and deduplicates/defaults optional data', () => {
  for (const value of [html({}), search({ isSuccess: false }), search({ searchList: {} })]) throwsCode(() => parseSearch(value), 'SOURCE_INVALID');
  const result = parseSearch(search({ total: undefined, searchList: [{ video_data: { series_id: sid, series_title: '剧' } }, { video_data: { series_id: sid, series_title: '重复' } }, {}, null, { video_data: { series_id: '333333', series_title: '' } }] }));
  assert.equal(result.total, 1); assert.deepEqual(result.items[0].tags, []);
  assert.equal(parseSearch(search({ total: undefined, pagination: { total: 5 } })).total, 5);
});
test('detail returns full ordered list with directory availability hints', () => {
  const result = parseDetail(detail(), sid);
  assert.equal(result.seriesId, sid); assert.equal(result.episodeCount, 80);
  assert.equal(result.accessibleEpisodeCount, 1);
  assert.deepEqual(result.episodes, [{ vid, index: 1, webAvailable: true, availability: 'available' }, { vid: '3456789012345678901', index: 2, webAvailable: false, availability: 'unknown' }]);
});
test('a three-episode preview count leaves later full-directory episodes unknown, not locked', () => {
  const result = parseDetail(detail({ vid_list: ['1001', '1002', '1003', '1004', '1005'], accessible_episode_cnt: 3 }), sid);
  assert.equal(result.episodes.length, 5);
  assert.deepEqual(result.episodes.map(episode => episode.availability), ['available', 'available', 'available', 'unknown', 'unknown']);
  assert.equal(result.episodes[3].webAvailable, false);
  assert.equal(result.episodes.some(episode => 'locked' in episode || episode.availability === 'unavailable'), false);
});
test('detail accepts either official player loader key without changing identity checks', () => {
  assert.equal(parseDetail(detail({}, { series_id: sid, vid }, 'player_(series_id)/(vid)/page'), sid).seriesId, sid);
  throwsCode(() => parseDetail(detail({}, { series_id: '333333' }, 'player_(series_id)/(vid)/page'), sid), 'IDENTITY_MISMATCH');
});
test('detail keeps original episode indexes while deduplicating/filtering invalid vids', () => {
  const result = parseDetail(detail({ vid_list: [vid, vid, null, 123, 'bad', '333333'], accessible_episode_cnt: 8 }), sid);
  assert.deepEqual(result.episodes.map(item => item.index), [1, 6]); assert.equal(result.accessibleEpisodeCount, 2);
  assert.equal(parseDetail(detail({ accessible_episode_cnt: undefined }), sid).accessibleEpisodeCount, 0);
});
test('detail rejects wrong/missing identities, rejected business status or missing required fields', () => {
  for (const value of [html({}), detail({ series_id: '333333' }), detail({}, { series_id: '333333' }), detail({}, { isSuccess: false })]) throwsCode(() => parseDetail(value, sid), 'IDENTITY_MISMATCH');
  for (const value of [detail({ series_name: '' }), detail({ vid_list: null })]) throwsCode(() => parseDetail(value, sid), 'SOURCE_INVALID');
  for (const id of ['', 'a', '1'.repeat(31), 123, null]) throwsCode(() => parseDetail(detail(), id), 'INVALID_INPUT');
});
test('playback binds current identity and removes only Range without rewriting signed query', () => {
  assert.deepEqual(parsePlayback(playback(), sid, vid), { url: 'https://media.example.net/v.mp4?token=a%20b&x=1', seriesId: sid, vid });
  assert.equal(parsePlayback(playback(playerData(), [video({ '@id': `${SITE_ORIGIN}/player/${sid}/${vid}`, contentUrl: 'https://cdn.example.net/a?x=1&amp;range=x&amp;Y=two#part' })]), sid, vid).url, 'https://cdn.example.net/a?x=1&Y=two');
});
test('playback accepts either official loader key only with exact per-item evidence', () => {
  assert.equal(parsePlayback(playback(playerData(), [video()], 'player_(series_id)/page'), sid, vid).vid, vid);
  throwsCode(() => parsePlayback(playback(playerData({ vid: '333333' }), [video()], 'player_(series_id)/page'), sid, vid), 'IDENTITY_MISMATCH');
});
test('a fourth episode can be publicly verified despite a directory preview count of three', () => {
  const fourthVid = '1004';
  const data = playerData({ vid: fourthVid, seriesDetail: { series_id: sid, accessible_episode_cnt: 3, vid_list: ['1001', '1002', '1003', fourthVid] }, publicVideoSeo: { available: true, seriesId: sid, itemId: fourthVid } });
  const object = video({ url: `${SITE_ORIGIN}/player/${sid}/${fourthVid}` });
  assert.equal(parsePlayback(playback(data, [object]), sid, fourthVid).vid, fourthVid);
  throwsCode(() => parsePlayback(playback({ ...data, publicVideoSeo: { available: false, seriesId: sid, itemId: fourthVid } }, [object]), sid, fourthVid), 'EPISODE_UNAVAILABLE');
});
test('playback rejects each independent identity mismatch', () => {
  for (const data of [null, playerData({ isSuccess: false }), playerData({ series_id: '333333' }), playerData({ vid: '333333' }), playerData({ seriesDetail: {} }), playerData({ publicVideoSeo: undefined }), playerData({ publicVideoSeo: { available: true, seriesId: '333333', itemId: vid } }), playerData({ publicVideoSeo: { available: true, seriesId: sid, itemId: '333333' } })]) throwsCode(() => parsePlayback(playback(data), sid, vid), 'IDENTITY_MISMATCH');
});
test('unavailable public episodes return a distinct state, never an app-interface fallback', () => {
  for (const available of [false, null, undefined]) throwsCode(() => parsePlayback(playback(playerData({ publicVideoSeo: { available, seriesId: sid, itemId: vid } })), sid, vid), 'EPISODE_UNAVAILABLE');
});
test('recommended video, foreign/relative player URL and wrong @id never become current playback', () => {
  for (const overrides of [{ url: `${SITE_ORIGIN}/player/333333/${vid}` }, { url: 'https://foreign.example.net/player/' + sid + '/' + vid }, { url: '/player/' + sid + '/' + vid }, { url: 'https://a:b@hongguoduanju.com/player/' + sid + '/' + vid }, { '@id': `${SITE_ORIGIN}/player/${sid}/333333` }, { '@type': 'Article' }, { url: null }]) throwsCode(() => parsePlayback(playback(playerData(), [video(overrides)]), sid, vid), 'NO_PLAYABLE_URL');
});
test('legacy canonical CDN playback still requires independent first-episode evidence', () => {
  const canonical = video({ url: `${SITE_ORIGIN}/player/${sid}`, '@id': `${SITE_ORIGIN}/player/${sid}#video` });
  const data = playerData({ seriesDetail: { series_id: sid, vid_list: [vid, '333333'] } });
  assert.equal(parsePlayback(playback(data, [canonical]), sid, vid).vid, vid);
  for (const seriesDetail of [{ series_id: sid }, { series_id: sid, vid_list: [] }, { series_id: sid, vid_list: ['333333', vid] }, { series_id: sid, vid_list: [Number(vid)] }]) {
    throwsCode(() => parsePlayback(playback(playerData({ seriesDetail }), [canonical]), sid, vid), 'NO_PLAYABLE_URL');
  }
  throwsCode(() => parsePlayback(playback(playerData({ seriesDetail: { series_id: '333333', vid_list: [vid] } }), [canonical]), sid, vid), 'IDENTITY_MISMATCH');
  for (const overrides of [{ url: `${SITE_ORIGIN}/player/333333` }, { '@id': `${SITE_ORIGIN}/player/333333#video` }, { url: `${SITE_ORIGIN}/player/${sid}?vid=333333` }]) {
    throwsCode(() => parsePlayback(playback(data, [{ ...canonical, ...overrides }]), sid, vid), 'NO_PLAYABLE_URL');
  }
});
const canonicalMedia = (overrides = {}) => video({
  url: `${SITE_ORIGIN}/player/${sid}`, '@id': `${SITE_ORIGIN}/player/${sid}#video`,
  contentUrl: `${SITE_ORIGIN}/video/public/${vid}/play.mp4?token=a%2Fb&Range=0-100&x=1`, ...overrides,
});
const laterPlayer = () => playerData({ seriesDetail: { series_id: sid, vid_list: ['111111', vid], accessible_episode_cnt: 1 } });
test('canonical later episode binds official media VID without a preview-count gate', () => {
  for (const key of ['player_(series_id)/(vid)/page', 'player_(series_id)/page']) {
    assert.deepEqual(parsePlayback(playback(laterPlayer(), [canonicalMedia()], key), sid, vid), {
      seriesId: sid, vid, url: `${SITE_ORIGIN}/video/public/${vid}/play.mp4?token=a%2Fb&x=1`,
    });
  }
});
test('canonical media identity works for first, later and missing vid_list with exact router/SEO evidence', () => {
  for (const vid_list of [[vid, '111111'], ['111111', vid], [], undefined]) {
    const data = playerData({ seriesDetail: { series_id: sid, vid_list } });
    assert.equal(parsePlayback(playback(data, [canonicalMedia()]), sid, vid).vid, vid);
  }
});
test('canonical media permits exact @id with fragment and preserves signed media query', () => {
  for (const id of [undefined, `${SITE_ORIGIN}/player/${sid}#video`, `${SITE_ORIGIN}/player/${sid}/${vid}#video`]) {
    assert.equal(parsePlayback(playback(laterPlayer(), [canonicalMedia({ '@id': id,
      contentUrl: `${SITE_ORIGIN}:443/video/public/${vid}/play.mp4?token=a%2fb+Z&amp;%52ange=0&amp;x=%2520#media`,
    })]), sid, vid).url, `${SITE_ORIGIN}/video/public/${vid}/play.mp4?token=a%2fb+Z&x=%2520`);
  }
});
test('canonical media rejects each changed router/SEO identity and unavailable episode', () => {
  for (const data of [playerData({ series_id: '111111' }), playerData({ vid: '111111' }),
    playerData({ seriesDetail: { series_id: '111111' } }),
    playerData({ publicVideoSeo: { seriesId: '111111', itemId: vid, available: true } }),
    playerData({ publicVideoSeo: { seriesId: sid, itemId: '111111', available: true } })]) {
    throwsCode(() => parsePlayback(playback(data, [canonicalMedia()]), sid, vid), 'IDENTITY_MISMATCH');
  }
  for (const available of [false, undefined, null, 1, 'true']) {
    throwsCode(() => parsePlayback(playback(playerData({ publicVideoSeo: { seriesId: sid, itemId: vid, available } }), [canonicalMedia()]), sid, vid), 'EPISODE_UNAVAILABLE');
  }
});
test('canonical later media rejects foreign, malformed and non-matching public episode paths', () => {
  for (const contentUrl of [`${SITE_ORIGIN}/video/public/111111/play.mp4`, `${SITE_ORIGIN}/video/public/${vid}0/play.mp4`,
    `${SITE_ORIGIN}/video/public/${vid}`, `${SITE_ORIGIN}/video/public/${vid}%2Fplay.mp4`, `${SITE_ORIGIN}/video/public/${vid}/../111111/play.mp4`,
    `${SITE_ORIGIN}/other/${vid}/play.mp4`, `https://cdn.example.net/video/public/${vid}/play.mp4`,
    `http://hongguoduanju.com/video/public/${vid}/play.mp4`, `https://user:pass@hongguoduanju.com/video/public/${vid}/play.mp4`,
    `${SITE_ORIGIN}:8443/video/public/${vid}/play.mp4`, `${SITE_ORIGIN}/video/public/${vid}/a b`,
    `${SITE_ORIGIN}/video/public/${vid}/a\\b`, `${SITE_ORIGIN}/video/public/${vid}/a?%zz=1`, null, 123]) {
    throwsCode(() => parsePlayback(playback(laterPlayer(), [canonicalMedia({ contentUrl })]), sid, vid), 'NO_PLAYABLE_URL');
  }
});
test('canonical later media rejects wrong SID, wrong @id and ambiguous canonical query', () => {
  for (const overrides of [{ url: `${SITE_ORIGIN}/player/111111` }, { url: `${SITE_ORIGIN}/player/${sid}?vid=111111` },
    { url: `/player/${sid}` }, { url: `http://hongguoduanju.com/player/${sid}` }, { url: `https://user:pass@hongguoduanju.com/player/${sid}` },
    { '@id': `${SITE_ORIGIN}/player/111111#video` }, { '@id': `${SITE_ORIGIN}/player/${sid}/111111#video` },
    { '@id': `${SITE_ORIGIN}/player/${sid}?vid=111111#video` }]) {
    throwsCode(() => parsePlayback(playback(laterPlayer(), [canonicalMedia(overrides)]), sid, vid), 'NO_PLAYABLE_URL');
  }
});
test('same-series recommended canonical media never replaces requested current episode', () => {
  const recommended = canonicalMedia({ contentUrl: `${SITE_ORIGIN}/video/public/111111/play.mp4` });
  throwsCode(() => parsePlayback(playback(laterPlayer(), [recommended]), sid, vid), 'NO_PLAYABLE_URL');
  assert.equal(parsePlayback(playback(laterPlayer(), [{ '@graph': [recommended, canonicalMedia()] }]), sid, vid).url,
    `${SITE_ORIGIN}/video/public/${vid}/play.mp4?token=a%2Fb&x=1`);
});
test('explicit wrong official media VID cannot use legacy first-episode compatibility', () => {
  const first = playerData({ seriesDetail: { series_id: sid, vid_list: [vid, '111111'] } });
  for (const url of [`${SITE_ORIGIN}/player/${sid}`, `${SITE_ORIGIN}/player/${sid}/${vid}`]) {
    throwsCode(() => parsePlayback(playback(first, [canonicalMedia({ url, contentUrl: `${SITE_ORIGIN}/video/public/111111/play.mp4` })]), sid, vid), 'NO_PLAYABLE_URL');
  }
  assert.equal(parsePlayback(playback(first, [video({ url: `${SITE_ORIGIN}/player/${sid}` })]), sid, vid).url, 'https://media.example.net/v.mp4?token=a%20b&x=1');
});
test('canonical webpage resolution does not trigger App fallback when new matching media is present', async () => {
  const requested = []; let fallbackCalls = 0;
  const source = createSource(async url => { requested.push(url); return playback(laterPlayer(), [canonicalMedia()]); },
    { fallback: async () => { fallbackCalls++; throw new Error('unexpected App fallback'); } });
  assert.equal((await source.playback(sid, vid)).vid, vid);
  assert.equal(fallbackCalls, 0);
  assert.deepEqual(requested, [`${SITE_ORIGIN}/player/${sid}/${vid}`]);
});
test('playback accepts matching VideoObjects in JSON-LD arrays and @graph only', () => {
  const page = playback(playerData(), [{ '@graph': [null, { '@type': 'Article' }, [video()]] }]);
  assert.equal(parsePlayback(page, sid, vid).vid, vid);
});
test('playback skips invalid JSON-LD/non-LD scripts and reports absent media clearly', () => {
  const page = playback(playerData(), []) + '<script type="text/javascript">ignored</script><script type="application/ld+json">bad JSON</script>';
  throwsCode(() => parsePlayback(page, sid, vid), 'NO_PLAYABLE_URL');
  throwsCode(() => parsePlayback(playback(playerData(), [video({ contentUrl: null })]), sid, vid), 'INVALID_MEDIA_URL');
  throwsCode(() => parsePlayback(playback(), sid, 'invalid'), 'INVALID_INPUT');
});
test('media rejects non-HTTPS, local/reserved names, literals, credentials, malformed or unbounded URLs', () => {
  for (const contentUrl of ['http://cdn.example.net/a', 'file:///x', 'javascript:alert(1)', 'https://localhost/a', 'https://a.local/a', 'https://a.internal/a', 'https://a.lan/a', 'https://a.home/a', 'https://a.test/a', 'https://a.invalid/a', 'https://a.example/a', 'https://a.onion/a', 'https://127.0.0.1/a', 'https://0x7f000001/a', 'https://[::1]/a', 'https://8.8.8.8/a', 'https://user:pass@cdn.example.net/a', 'https://cdn.example.net:1234/a', 'https://-bad.example.net/a', 'https://cdn.example.net/a b', 'https://cdn.example.net/a\\b', 'https://cdn.example.net/a?%zz=1', 'https://cdn.example.net/' + 'x'.repeat(16_384), 'not-a-url']) {
    throwsCode(() => parsePlayback(playback(playerData(), [video({ contentUrl })]), sid, vid), 'INVALID_MEDIA_URL');
  }
});
test('invalid cover links are omitted, not requested', () => {
  assert.equal(parseCatalog(catalog({ recommendList: [card({ series_cover: 'http://localhost/x' })] })).items[0].coverUrl, '');
});
test('createSource validates adapter and paths before sending any HTTP request', () => {
  for (const request of [undefined, {}, 'fetch']) throwsCode(() => createSource(request), 'INVALID_INPUT');
  let calls = 0; const source = createSource(() => { calls++; return catalog(); });
  for (const action of [() => source.catalog('comic'), () => source.catalog('real-drama', -1), () => source.search(' '), () => source.search(null), () => source.search('x'.repeat(101)), () => source.detail('../'), () => source.playback(sid, 123)]) throwsCode(action, 'INVALID_INPUT');
  assert.equal(calls, 0); assert(Object.isFrozen(source));
});
test('createSource uses independent official HTTPS paths and encodes Unicode/search delimiters', async () => {
  const urls = [];
  const source = createSource(async url => { urls.push(url); return url.includes('/category/') ? catalog() : url.includes('/search/') ? search() : url.endsWith(`/${vid}`) ? playback() : detail(); });
  await source.catalog(); await source.catalog('real-drama', 2); await source.search(' 短剧 /?& '); await source.detail(sid); await source.playback(sid, vid);
  assert.deepEqual(urls, [`${SITE_ORIGIN}/category/real-drama`, `${SITE_ORIGIN}/category/real-drama?page=2`, `${SITE_ORIGIN}/search/${encodeURIComponent('短剧 /?&')}`, `${SITE_ORIGIN}/player/${sid}`, `${SITE_ORIGIN}/detail?series_id=${sid}`, `${SITE_ORIGIN}/player/${sid}/${vid}`]);
});
test('createSource resolves an unknown directory episode on demand without a count gate', async () => {
  const fourthVid = '1004', urls = [];
  const source = createSource(async url => {
    urls.push(url);
    if (url.endsWith(`/${fourthVid}`)) return playback(playerData({ vid: fourthVid, publicVideoSeo: { available: true, seriesId: sid, itemId: fourthVid } }), [video({ url: `${SITE_ORIGIN}/player/${sid}/${fourthVid}` })]);
    return detail({ vid_list: ['1001', '1002', '1003', fourthVid], accessible_episode_cnt: 3 });
  });
  assert.equal((await source.detail(sid)).episodes[3].availability, 'unknown');
  assert.equal((await source.playback(sid, fourthVid)).vid, fourthVid);
  assert.deepEqual(urls, [`${SITE_ORIGIN}/player/${sid}`, `${SITE_ORIGIN}/detail?series_id=${sid}`, `${SITE_ORIGIN}/player/${sid}/${fourthVid}`]);
});
test('concurrent identical requests share one operation, success is cached and expires', async () => {
  let calls = 0; const source = createSource(async () => { calls++; return catalog(); });
  const [a, b] = await Promise.all([source.catalog(), source.catalog()]);
  assert.strictEqual(a, b); assert.strictEqual(await source.catalog(), a); assert.equal(calls, 1);
  const now = Date.now;
  try { Date.now = () => now() + 31_000; await source.catalog(); } finally { Date.now = now; }
  assert.equal(calls, 2);
});
test('request errors and invalid HTML are not cached, immediate retry succeeds', async () => {
  let calls = 0;
  const source = createSource(async () => { calls++; if (calls === 1) throw new Error('网络失败'); return calls === 2 ? '' : catalog(); });
  await assert.rejects(source.catalog(), /网络失败/); await assert.rejects(source.catalog(), /来源页面为空/);
  assert.equal((await source.catalog()).items.length, 1); assert.equal(calls, 3);
});
test('cache is bounded to 64 successful pages', async () => {
  let calls = 0; const source = createSource(async () => { calls++; return catalog(); });
  for (let page = 1; page <= 65; page++) await source.catalog('real-drama', page);
  await source.catalog(); assert.equal(calls, 66);
});
