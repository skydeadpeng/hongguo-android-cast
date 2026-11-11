import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { parseSearchPage, createSearchPager } from '../src/android/search.mjs';
import { seasonCandidates } from '../src/android/seasons.mjs';

// Synthetic fixtures follow the official tab-11 response shape. No account,
// device registration, captured request IDs, signed URLs or network is needed.
const sid = n => String(7_600_000_000_000_000_000n + BigInt(n));
const video = (n, overrides = {}) => ({ series_id: sid(n), title: `短剧 ${n}`, cover: `https://p3.byteimg.com/test-${n}.jpg`, episode_cnt: 80, sub_title: '都市·全80集', video_detail: { episode_right_text: '共80集', series_intro: `简介 ${n}` }, ...overrides });
const cell = (n, overrides = {}) => ({ book_id: sid(n), search_result_id: sid(n), video_data: [video(n)], ...overrides });
const response = (ids = [1, 2], overrides = {}) => ({ code: 0, search_tabs: [{ tab_type: 11, data: ids.map(n => cell(n)), has_more: true, next_offset: 20, search_id: 'synthetic-search', passback: 'opaque:20', ...overrides }] });
const end = (ids = [1], overrides = {}) => response(ids, { has_more: false, ...overrides });
const rejectsCode = (fn, code) => assert.rejects(Promise.resolve().then(fn), e => e instanceof Error && e.code === code);
const throwsCode = (fn, code) => assert.throws(fn, e => e instanceof Error && (!code || e.code === code));
const deferred = () => { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no; }); return { promise, resolve, reject }; };
const flush = () => new Promise(resolve => setImmediate(resolve));

// Sanitized public metadata from the bounded 2026-10-22 native search check.
// Only IDs, display/full titles and episode counts remain; no session or cursor tokens.
const seasonTitleFixture = JSON.parse(readFileSync(new URL('./fixtures/android-search-season-titles.json', import.meta.url), 'utf8'));

test('native fixture preserves canonical season titles instead of the shared search alias', () => {
  const parsed = parseSearchPage(seasonTitleFixture);
  const seed = { seriesId: '7671966197985856536', title: '持械入宋：第一季' };
  const seasons = seasonCandidates(seed, parsed.items);
  assert.equal(parsed.items.length, 20);
  assert.equal(parsed.hasMore, true); // A complete first page is not a complete catalogue.
  assert.deepEqual(seasons.map(row => row.seasonNumber), [1, 2, 3, 4, 5, 6, 7, 8, 9]);
  assert.deepEqual(seasons.map(row => row.seriesId), [
    '7671966197985856536', '7676706788724460568', '7678620970638134334',
    '7680845974494776345', '7683455900727659545', '7686430794469887001',
    '7690112243203050521', '7691615145977449496', '7694939439503510590',
  ]);
  assert.equal(parsed.items.find(row => row.seriesId === '7692060436526533694').title, '持械入宋第四季');
  assert.equal(seasons.some(row => row.seriesId === '7692060436526533694'), false);
});

test('canonical title requires the exact selected string identity, including cell-ID fallback', () => {
  const details = [
    { series_id_str: sid(1), series_title: '完整剧名：第二季' },
    { series_id_str: sid(2), series_title: '其他剧：第二季' },
    { series_title: '未绑定剧：第二季' },
    { series_id_str: Number(sid(1)), series_title: '浮点身份：第二季' },
    { series_id_str: {}, series_title: '对象身份：第二季' },
  ];
  for (let i = 0; i < details.length; i++) {
    const data = [cell(1, { video_data: [video(1, { video_detail: details[i] })] })];
    assert.equal(parseSearchPage(end([], { data })).items[0].title, i === 0 ? '完整剧名：第二季' : '短剧 1');
  }
  const data = [cell(1, { video_data: [video(1, { series_id: undefined, video_detail: details[0] })] })];
  assert.equal(parseSearchPage(end([], { data })).items[0].title, '完整剧名：第二季');
  // A nested identity must not manufacture an outer result's identity.
  data[0].book_id = undefined; data[0].search_result_id = undefined;
  assert.deepEqual(parseSearchPage(end([], { data })).items, []);
});

test('invalid canonical titles fall back without truncating them into credible season metadata', () => {
  for (const series_title of [undefined, null, '', '   ', 123, {}, [], 'x'.repeat(201), '剧名\n第二季', '剧名\u0000第二季', '剧名\u007f第二季']) {
    const data = [cell(1, { video_data: [video(1, { video_detail: { series_id_str: sid(1), series_title } })] })];
    assert.equal(parseSearchPage(end([], { data })).items[0].title, '短剧 1');
  }
  for (const series_title of ['  完整剧名：第二季  ', '剧'.repeat(200)]) {
    const data = [cell(1, { video_data: [video(1, { title: '', video_detail: { series_id_str: sid(1), series_title } })] })];
    assert.equal(parseSearchPage(end([], { data })).items[0].title, series_title.trim());
  }
});

test('invalid canonical metadata retains video, highlight and cell-name fallback order', () => {
  for (const detail of [null, undefined, 7, [], { series_id_str: sid(1), series_title: '' }, { series_id_str: sid(2), series_title: '错剧' }]) {
    const value = cell(1, { video_data: [video(1, { video_detail: detail })], search_high_light: { title: { text: '高亮剧名' } }, cell_name: '单元剧名' });
    assert.equal(parseSearchPage(end([], { data: [value] })).items[0].title, '短剧 1');
    value.video_data[0].title = '';
    assert.equal(parseSearchPage(end([], { data: [value] })).items[0].title, '高亮剧名');
    delete value.search_high_light;
    assert.equal(parseSearchPage(end([], { data: [value] })).items[0].title, '单元剧名');
    delete value.cell_name;
    assert.deepEqual(parseSearchPage(end([], { data: [value] })).items, []);
  }
});

test('canonical titles work in nested single and array lynx cards without changing cursors', () => {
  const make = n => cell(n, { video_data: [video(n, { title: '别名', video_detail: { series_id_str: sid(n), series_title: `完整剧名：第${n}季` } })] });
  const data = [{ lynx_data: JSON.stringify({ cell_data: make(1) }) }, { lynx_data: JSON.stringify({ cell_data: [make(2), make(3)] }) }];
  const parsed = parseSearchPage(response([], { data, next_offset: 41 }));
  assert.deepEqual(parsed.items.map(row => row.title), ['完整剧名：第1季', '完整剧名：第2季', '完整剧名：第3季']);
  assert.deepEqual(parsed.cursor, { offset: 41, searchId: 'synthetic-search', passback: 'opaque:20' });
});

test('tab-11 parser preserves exact 64-bit string IDs and maps public metadata', () => {
  const result = parseSearchPage(response([1]));
  assert.equal(result.items.length, 1);
  assert.equal(result.items[0].seriesId, '7600000000000000001');
  assert.equal(result.items[0].title, '短剧 1');
  assert.equal(result.items[0].coverUrl, 'https://p3.byteimg.com/test-1.jpg');
  assert.equal(result.items[0].episodeCount, 80);
  assert.equal(result.hasMore, true);
  assert.equal(result.totalKnown, false);
  assert.equal(result.total, 1);
  assert.deepEqual(result.cursor, { offset: 20, searchId: 'synthetic-search', passback: 'opaque:20' });
});

test('parser selects drama tab, supports data envelope and lynx cell_data', () => {
  const raw = response([], { data: [{ lynx_data: JSON.stringify({ cell_data: cell(5) }) }, cell(6)] });
  raw.search_tabs.unshift({ tab_type: 8, data: [cell(900)], has_more: false });
  const parsed = parseSearchPage({ code: 0, data: { search_tabs: raw.search_tabs } });
  assert.deepEqual(parsed.items.map(item => item.seriesId), [sid(5), sid(6)]);
});

test('parser uses title text rather than rich markup and accepts string tab type', () => {
  const parsed = parseSearchPage(response([], { tab_type: '11', data: [cell(3, { video_data: [video(3, { title: '' })], search_high_light: { title: { text: '  正常标题  ', rich_text: '<em>正常标题</em>' } } })] }));
  assert.equal(parsed.items[0].title, '正常标题');
});

test('19 video rows plus a non-video cell still has a following page', () => {
  const parsed = parseSearchPage(response([], { data: [...Array.from({ length: 19 }, (_, n) => cell(n + 1)), { cell_name: '非视频内容', book_id: sid(999) }], next_offset: 40, passback: 'opaque:40' }), { offset: 20 });
  assert.equal(parsed.items.length, 19);
  assert.equal(parsed.hasMore, true);
  assert.equal(parsed.cursor.offset, 40);
});

test('parser deduplicates within a page and excludes prior-page identities', () => {
  const parsed = parseSearchPage(response([1, 1, 2, 3]), { seen: [sid(1), sid(99)] });
  assert.deepEqual(parsed.items.map(item => item.seriesId), [sid(2), sid(3)]);
});

test('parser ignores nonvideo cells and rejects rounded numeric and malformed identities', () => {
  const parsed = parseSearchPage(end([], { data: [null, {}, { lynx_data: 'broken json' }, cell(1, { book_id: undefined, search_result_id: undefined, video_data: [video(1, { series_id: Number(sid(1)) })] }), cell(2, { book_id: undefined, search_result_id: undefined, video_data: [video(2, { series_id: '../invalid' })] }), cell(3)] }));
  assert.deepEqual(parsed.items.map(item => item.seriesId), [sid(3)]);
});

test('has_more recognizes true, one and explicit true strings without truthy-zero errors', () => {
  for (const value of [true, 1, '1', 'true']) assert.equal(parseSearchPage(response([1], { has_more: value })).hasMore, true);
  for (const value of [false, 0, '0', 'false']) assert.equal(parseSearchPage(response([1], { has_more: value })).hasMore, false);
});

test('empty terminal page is valid but an empty claimed continuation is stalled', () => {
  const empty = parseSearchPage(end([], { next_offset: 40 }), { offset: 20 });
  assert.deepEqual(empty.items, []);
  assert.equal(empty.hasMore, false);
  throwsCode(() => parseSearchPage(response([], { next_offset: 40 }), { offset: 20 }), 'SEARCH_CURSOR_STALLED');
});

test('parser rejects empty body, malformed envelope, rejected business code and missing drama tab', () => {
  for (const value of [null, undefined, '', ' ', {}, { code: 500, message: '业务失败', search_tabs: response().search_tabs }, { code: 0 }, { code: 0, search_tabs: [] }, { code: 0, search_tabs: [{ tab_type: 8, data: [cell(1)] }] }, response([], { data: null })]) throwsCode(() => parseSearchPage(value));
});

test('continuation requires a forward safe next_offset, never a guessed row-count offset', () => {
  for (const next_offset of [undefined, null, 0, 20, 19, -1, 'invalid', 20.5, Number.MAX_SAFE_INTEGER + 1]) {
    throwsCode(() => parseSearchPage(response([1], { next_offset }), { offset: 20 }), 'SEARCH_CURSOR_STALLED');
  }
  assert.equal(parseSearchPage(response([1], { next_offset: 67, passback: 'opaque-token' }), { offset: 20 }).cursor.offset, 67);
});

test('all repeated video identities produce a stalled error instead of a fake page', () => {
  throwsCode(() => parseSearchPage(response([1, 2]), { seen: [sid(1), sid(2)] }), 'SEARCH_CURSOR_STALLED');
});

test('pager follows opaque cursor and caches previous pages without guessing offset', async () => {
  const calls = [];
  const search = createSearchPager(async request => {
    calls.push({ ...request });
    if (request.offset === 0) return response([1, 2], { next_offset: 37, passback: 'opaque:37' });
    if (request.offset === 37) return response([2, 3], { next_offset: 59, passback: 'opaque:59' });
    if (request.offset === 59) return end([4], { next_offset: 59 });
    throw new Error('unexpected guessed offset');
  });
  const first = await search(' 总裁 ', 1);
  const second = await search('总裁', 2);
  const third = await search('总裁', 3);
  assert.equal(first.page, 1); assert.equal(second.page, 2); assert.equal(third.page, 3);
  assert.equal(first.totalKnown, false); assert.equal(first.totalPages, 0);
  assert.deepEqual(second.items.map(item => item.seriesId), [sid(3)]);
  assert.equal(third.hasMore, false); assert.equal(third.totalPages, 3);
  assert.deepEqual(calls.map(call => call.offset), [0, 37, 59]);
  assert.equal(calls[0].keyword, '总裁');
  assert.equal(calls[1].searchId, 'synthetic-search');
  assert.equal(calls[1].passback, 'opaque:37');
  assert.equal(calls[2].passback, 'opaque:59');
  assert.deepEqual((await search('总裁', 1)).items, first.items);
  assert.equal(calls.length, 3);
});

test('pager keeps one-row nonterminal pages pageable and ends on explicit empty terminal', async () => {
  const calls = [];
  const search = createSearchPager(async request => { calls.push(request.offset); return request.offset === 0 ? response([1], { next_offset: 43 }) : end([], { next_offset: 43 }); });
  assert.equal((await search('京夜', 1)).hasMore, true);
  const last = await search('京夜', 2);
  assert.equal(last.page, 2); assert.equal(last.totalPages, 2); assert.equal(last.hasMore, false); assert.equal(last.items.length, 0);
  assert.deepEqual(calls, [0, 43]);
});

test('pager validates keyword and page before any transport call', async () => {
  let calls = 0;
  const search = createSearchPager(async () => { calls++; return end(); });
  for (const keyword of ['', '  ', 'x'.repeat(101), null, 123, {}]) await rejectsCode(() => search(keyword, 1), 'INVALID_INPUT');
  for (const page of [0, -1, 101, 1.2, NaN, '1', null]) await rejectsCode(() => search('剧', page), 'INVALID_INPUT');
  assert.equal(calls, 0);
  await search('x'.repeat(100), 1);
  assert.equal(calls, 1);
});

test('pager rejects missing predecessor instead of recursively requesting all earlier pages', async () => {
  let calls = 0;
  const search = createSearchPager(async () => { calls++; return response([1]); });
  await rejectsCode(() => search('总裁', 2), 'SEARCH_EXPIRED');
  assert.equal(calls, 0);
  await search('总裁', 1);
  await rejectsCode(() => search('总裁', 3), 'SEARCH_EXPIRED');
  assert.equal(calls, 1);
});

test('pager coalesces simultaneous same-query same-page requests', async () => {
  const gate = deferred(); let calls = 0;
  const search = createSearchPager(() => { calls++; return gate.promise; });
  const a = search('总裁', 1), b = search(' 总裁 ', 1);
  await flush(); assert.equal(calls, 1);
  gate.resolve(response([1]));
  assert.deepEqual(await a, await b);
});

test('failed first page is not cached and an explicit retry can succeed', async () => {
  let calls = 0;
  const search = createSearchPager(async () => ++calls === 1 ? { code: 500 } : response([1]));
  await assert.rejects(() => search('剧', 1));
  assert.equal((await search('剧', 1)).items[0].seriesId, sid(1));
  assert.equal(calls, 2);
});

test('failed next page keeps cursor for same-page retry without disturbing earlier page', async () => {
  let attempts = 0; const calls = [];
  const search = createSearchPager(async request => {
    calls.push(request.offset);
    if (request.offset === 0) return response([1], { next_offset: 71 });
    if (++attempts === 1) throw Object.assign(new Error('网络暂不可用'), { code: 'NETWORK_ERROR' });
    return end([2], { next_offset: 71 });
  });
  await search('剧', 1);
  await rejectsCode(() => search('剧', 2), 'NETWORK_ERROR');
  assert.equal((await search('剧', 1)).items[0].seriesId, sid(1));
  assert.equal((await search('剧', 2)).items[0].seriesId, sid(2));
  assert.deepEqual(calls, [0, 71, 71]);
});

test('stalled repeated page is not cached and can recover on retry', async () => {
  let attempts = 0;
  const search = createSearchPager(async request => request.offset === 0 ? response([1], { next_offset: 37 }) : ++attempts === 1 ? response([1], { next_offset: 74 }) : end([2], { next_offset: 74 }));
  await search('剧', 1);
  await rejectsCode(() => search('剧', 2), 'SEARCH_CURSOR_STALLED');
  assert.equal((await search('剧', 2)).items[0].seriesId, sid(2));
});

test('refresh starts a fresh session and stale completion cannot overwrite it', async () => {
  const old = deferred(); let calls = 0;
  const search = createSearchPager(async request => {
    calls++;
    if (calls === 1) return old.promise;
    if (request.offset === 0) return response([10], { next_offset: 63, search_id: 'new-session', passback: 'new:63' });
    assert.equal(request.offset, 63); assert.equal(request.searchId, 'new-session');
    return end([11]);
  });
  const stale = search('剧', 1).then(value => ({ value }), error => ({ error }));
  await flush();
  const fresh = await search('剧', 1, { refresh: true });
  assert.equal(fresh.items[0].seriesId, sid(10));
  old.resolve(response([1], { next_offset: 20, search_id: 'old-session' }));
  await stale;
  assert.equal((await search('剧', 1)).items[0].seriesId, sid(10));
  assert.equal((await search('剧', 2)).items[0].seriesId, sid(11));
  assert.equal(calls, 3);
});

test('refresh invalidates later cached pages while independent query sessions remain isolated', async () => {
  let version = 0;
  const search = createSearchPager(async request => {
    const base = request.keyword === '甲' ? 100 : 200;
    if (request.offset === 0) return response([base + version], { next_offset: 20 });
    return end([base + 1 + version]);
  });
  await search('甲', 1); await search('甲', 2); await search('乙', 1);
  version = 10;
  await search('甲', 1, { refresh: true });
  assert.equal((await search('甲', 2)).items[0].seriesId, sid(111));
  assert.equal((await search('乙', 1)).items[0].seriesId, sid(200));
});

test('five minutes of inactivity expires a cursor while cached reads renew idle lifetime', async () => {
  const realNow = Date.now; let now = 10_000_000;
  Date.now = () => now;
  try {
    const search = createSearchPager(async request => request.offset === 0 ? response([1]) : end([2]));
    await search('剧', 1);
    now += 4 * 60_000;
    await search('剧', 1);
    now += 4 * 60_000;
    await search('剧', 2);
    now += 5 * 60_000 + 1;
    await rejectsCode(() => search('剧', 2), 'SEARCH_EXPIRED');
    assert.equal((await search('剧', 1)).page, 1);
  } finally { Date.now = realNow; }
});

test('session cache holds at most eight query sessions and evicts least recently used', async () => {
  const realNow = Date.now; let now = 20_000_000;
  Date.now = () => ++now;
  try {
    const search = createSearchPager(async () => response([1]));
    for (let n = 0; n < 8; n++) await search(`词${n}`, 1);
    await search('词0', 1); // Touch the oldest so query 1 becomes the LRU.
    await search('词8', 1);
    await rejectsCode(() => search('词1', 2), 'SEARCH_EXPIRED');
    assert.equal((await search('词0', 1)).items[0].seriesId, sid(1));
  } finally { Date.now = realNow; }
});

test('bounded 100-page search uses returned cursors and refuses page 101', async () => {
  let calls = 0;
  const search = createSearchPager(async request => {
    calls++;
    return response([request.offset + 1], { next_offset: request.offset + 7, passback: `token:${request.offset + 7}` });
  });
  for (let page = 1; page <= 100; page++) assert.equal((await search('剧', page)).page, page);
  await rejectsCode(() => search('剧', 101), 'INVALID_INPUT');
  assert.equal(calls, 100);
});

test('parser accepts array-valued lynx cells without treating arbitrary JSON as executable', () => {
  const parsed = parseSearchPage(end([], { data: [{ lynx_data: JSON.stringify({ cell_data: [cell(5), cell(6)] }) }, { lynx_data: '{ cell_data: globalThis.__searchExecuted = true }' }] }));
  assert.deepEqual(parsed.items.map(item => item.seriesId), [sid(5), sid(6)]);
  assert.equal(globalThis.__searchExecuted, undefined);
});

test('parser rejects omitted or ambiguous has_more rather than inferring from result count', () => {
  for (const has_more of [undefined, null, '', 'FALSE', 2, 'yes', {}, []]) throwsCode(() => parseSearchPage(response([1], { has_more })));
});

test('parser bounds cursor strings, result rows and next offsets', () => {
  for (const patch of [{ search_id: 'x'.repeat(513) }, { search_id: 123 }, { search_id: 'bad\nline' }, { passback: 'x'.repeat(513) }, { passback: {} }, { passback: 'bad\u0000value' }, { next_offset: 10001 }, { data: Array.from({ length: 201 }, (_, i) => cell(i)) }]) {
    throwsCode(() => parseSearchPage(response([1], patch)));
  }
  const page = parseSearchPage(response([1], { next_offset: '37', search_id: null, passback: undefined }));
  assert.deepEqual(page.cursor, { offset: 37, searchId: '', passback: '' });
});

test('parser limits aggregate video cards even when each cell count is small', () => {
  const data = Array.from({ length: 11 }, (_, i) => cell(i, { video_data: Array.from({ length: 20 }, (_, j) => video(i * 20 + j)) }));
  throwsCode(() => parseSearchPage(end([], { data })));
});

test('optional metadata is bounded, category names deduplicated and untrusted covers omitted', () => {
  const values = ['', null, 'not a url', 'http://p3.byteimg.com/a.jpg', 'https://bad.example/a.jpg', 'https://p3.byteimg.com:444/a.jpg', 'https://user:pass@p3.byteimg.com/a.jpg', 'https://p3.byteimg.com/a b.jpg', 'https://p3.byteimg.com/' + 'a'.repeat(2100)];
  for (const cover of values) {
    const item = parseSearchPage(end([], { data: [cell(1, { video_data: [video(1, { cover, title: 'x'.repeat(250), episode_cnt: -1, category_schema: '{invalid' })] })] })).items[0];
    assert.equal(item.coverUrl, ''); assert.equal(item.title.length, 200); assert.equal(item.episodeCount, 0); assert.deepEqual(item.tags, []);
  }
  const item = parseSearchPage(end([], { data: [cell(1, { video_data: [video(1, { episode_cnt: '90000', category_schema: JSON.stringify([{ name: ' 都市 ' }, { name: '都市' }, null, { name: '恋爱' }]) })] })] })).items[0];
  assert.equal(item.episodeCount, 10000); assert.deepEqual(item.tags, ['都市', '恋爱']);
});

test('parser uses exact string cell IDs when optional video identity is absent and never fabricates a title', () => {
  const parsed = parseSearchPage(end([], { data: [cell(1, { video_data: [video(1, { series_id: undefined })] }), cell(2, { video_data: [video(2, { title: '' })] }), cell(3, { book_id: undefined, video_data: [video(3, { series_id: undefined })] })] }));
  assert.deepEqual(parsed.items.map(item => item.seriesId), [sid(1), sid(3)]);
});

test('pager validates requester, forbids refreshing a later page and does not request beyond EOF', async () => {
  for (const requester of [undefined, null, {}, 123]) throwsCode(() => createSearchPager(requester), 'INVALID_INPUT');
  let calls = 0;
  const search = createSearchPager(async () => { calls++; return end([1]); });
  await rejectsCode(() => search('剧', 2, { refresh: true }), 'INVALID_INPUT');
  await search('剧', 1);
  await rejectsCode(() => search('剧', 2), 'SEARCH_END');
  assert.equal(calls, 1);
});

test('page 100 explicitly stops pagination and reports the bounded search limit', async () => {
  const search = createSearchPager(async request => response([request.offset + 1], { next_offset: request.offset + 1 }));
  let last;
  for (let page = 1; page <= 100; page++) last = await search('剧', page);
  assert.equal(last.page, 100); assert.equal(last.hasMore, false); assert.match(last.notice, /100/);
});

test('normalized search pages allow at most 40 distinct drama cards', () => {
  const ids = Array.from({ length: 40 }, (_, n) => n + 1);
  assert.equal(parseSearchPage(end(ids)).items.length, 40);
  throwsCode(() => parseSearchPage(end([...ids, 41])));
  // Duplicate cells do not consume the normalized-card budget twice.
  assert.equal(parseSearchPage(end([...ids, ...ids])).items.length, 40);
});

test('native-compatible passback bound is 512 characters, with exact content preserved', () => {
  const passback = 'opaque%2F&='.repeat(60).slice(0, 512);
  assert.equal(parseSearchPage(response([1], { passback })).cursor.passback, passback);
  throwsCode(() => parseSearchPage(response([1], { passback: 'x'.repeat(513) })));
});

test('cached page objects are deeply read-only and mutations cannot corrupt future deduplication', async () => {
  const search = createSearchPager(async request => request.offset === 0 ? response([1]) : end([1, 2]));
  const first = await search('剧', 1);
  assert.equal(Object.isFrozen(first), true);
  assert.equal(Object.isFrozen(first.items), true);
  assert.equal(Object.isFrozen(first.items[0]), true);
  assert.equal(Object.isFrozen(first.items[0].tags), true);
  assert.throws(() => { first.hasMore = false; }, TypeError);
  assert.throws(() => { first.items[0].seriesId = sid(99); }, TypeError);
  assert.throws(() => { first.items.push({ seriesId: sid(99) }); }, TypeError);
  assert.throws(() => { first.items[0].tags.push('意外修改'); }, TypeError);
  assert.equal((await search('剧', 1)).hasMore, true);
  assert.deepEqual((await search('剧', 2)).items.map(item => item.seriesId), [sid(2)]);
});

test('global 120-page budget evicts the least recent noncurrent query as a whole session', async () => {
  const calls = [];
  const search = createSearchPager(async request => {
    calls.push({ ...request });
    return response([request.offset + 1], { next_offset: request.offset + 20 });
  });
  for (let page = 1; page <= 60; page++) await search('甲', page);
  for (let page = 1; page <= 60; page++) await search('乙', page);
  assert.equal(calls.length, 120);
  await search('甲', 1); // A read makes 甲 newer than 乙.
  assert.equal(calls.length, 120);
  await search('丙', 1);
  await rejectsCode(() => search('乙', 2), 'SEARCH_EXPIRED');
  assert.equal((await search('甲', 60)).page, 60);
  assert.equal((await search('丙', 1)).page, 1);
  assert.equal(calls.length, 121);
  // Restarting an evicted query is explicit page 1, never reconstructed page 2.
  assert.equal((await search('乙', 1)).page, 1);
  assert.equal(calls.length, 122);
});

test('page budget preserves all pages of the active query instead of breaking its back button', async () => {
  const search = createSearchPager(async request => response([request.offset + 1], { next_offset: request.offset + 20 }));
  for (let page = 1; page <= 30; page++) await search('较早', page);
  for (let page = 1; page <= 100; page++) await search('当前', page);
  await rejectsCode(() => search('较早', 2), 'SEARCH_EXPIRED');
  assert.equal((await search('当前', 1)).page, 1);
  assert.equal((await search('当前', 50)).page, 50);
  assert.equal((await search('当前', 100)).page, 100);
});

test('late completion over the page budget never evicts the newest active query', async () => {
  const late = deferred(); let calls = 0;
  const search = createSearchPager(request => {
    calls++;
    if (request.keyword === '旧查询' && request.offset === 1200) return late.promise;
    return response([request.offset + 1], { next_offset: request.offset + 20 });
  });
  for (let page = 1; page <= 60; page++) await search('旧查询', page);
  const oldPage = search('旧查询', 61);
  await flush();
  for (let page = 1; page <= 60; page++) await search('新查询', page);
  assert.equal(calls, 121);
  late.resolve(response([1201], { next_offset: 1220 }));
  await oldPage;
  // Finishing the older request must not revive or promote its old session.
  assert.equal((await search('新查询', 60)).page, 60);
  await rejectsCode(() => search('旧查询', 2), 'SEARCH_EXPIRED');
  assert.equal(calls, 121);
});
