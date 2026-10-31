import test from 'node:test';
import assert from 'node:assert/strict';
import { resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
const sourceRoot = resolve(process.env.ANDROID_SOURCE_ROOT || fileURLToPath(new URL('..', import.meta.url)));
const { normalizeTitle, parseNewReleases, matchNewReleases, needsBackfill, mergeNewReleases } =
  await import(pathToFileURL(resolve(sourceRoot, 'src/android/new-releases.mjs')).href);
const { createFallbackSearch } = await import(pathToFileURL(resolve(sourceRoot, 'src/android/search-fallback.mjs')).href);

const row = (overrides = {}) => ({ series_id: '7702453391682702360', title: '合成新剧', cover: 'https://cdn.example.net/a.jpg',
  episode_count: 12, description: '简介', tags: ['爱情'], released_today: true, ...overrides });
const item = (overrides = {}) => ({ seriesId: '1001', title: '合成剧', coverUrl: '', description: '', episodeCount: 6, episodeLabel: '', tags: [], ...overrides });
// The App channel's RAW page shape (what parseSearchPage consumes inside the pager).
const rawAppPage = (overrides = {}) => ({ code: 0, search_tabs: [{ tab_type: 11, has_more: false, next_offset: 0,
  search_id: '', passback: '', data: [], ...overrides }] });

test('titles normalize across full-width forms and punctuation', () => {
  assert.equal(normalizeTitle(' ＡＩ女友！'), 'ai女友');
  assert.equal(normalizeTitle('我　的-冒泡“剧”'), '我的冒泡剧');
  assert.equal(normalizeTitle(null), '');
});
test('rows project strictly; unknown shapes and duplicates are dropped', () => {
  const items = parseNewReleases([row(), row(), row({ series_id: 'bad' }), row({ title: '' }), row({ series_id: '7702453391682702361', title: '另一部' }), null, 'x']);
  assert.equal(items.length, 2);
  assert.deepEqual(items[0], { seriesId: '7702453391682702360', title: '合成新剧', coverUrl: 'https://cdn.example.net/a.jpg',
    description: '简介', episodeCount: 12, episodeLabel: '今日上新', tags: ['爱情'], newRelease: true });
  assert.throws(() => parseNewReleases('x'), undefined, 'a non-array catalog is refused');
  assert.throws(() => parseNewReleases(new Array(401).fill(row())));
});
test('matching ranks exact, containment and reverse containment', () => {
  const catalog = parseNewReleases([row({ series_id: '1', title: '假孕成真后，总裁宠我上天2' }),
    row({ series_id: '2', title: 'AI女友诞生记' }), row({ series_id: '3', title: '合成新剧' }),
    row({ series_id: '4', title: '西游记' })]);
  assert.deepEqual(matchNewReleases(catalog, '合成新剧').map(i => i.seriesId), ['3']);
  assert.deepEqual(matchNewReleases(catalog, '假孕成真后总裁宠我上天2').map(i => i.seriesId), ['1'], 'punctuation in the query is normalized');
  assert.deepEqual(matchNewReleases(catalog, '我发现了AI女友诞生记全剧').map(i => i.seriesId), ['2']);
  assert.deepEqual(matchNewReleases(catalog, '西游记后传').map(i => i.seriesId), ['4'], 'a query containing a real title still matches');
  assert.deepEqual(matchNewReleases(catalog, '完全无关的标题'), []);
  assert.deepEqual(matchNewReleases(catalog, '合成').map(i => i.seriesId), ['3'], 'a short query still matches a containing title');
  assert.deepEqual(matchNewReleases(catalog, '我看到了剧'), [], 'a fragment inside the query never claims a one-character title');
});
test('the backfill triggers on an empty or irrelevant first page only', () => {
  assert.equal(needsBackfill([], '合成新剧'), true);
  assert.equal(needsBackfill([item()], '合成新剧'), true);
  assert.equal(needsBackfill([item({ title: '合成新剧第二季' })], '合成新剧'), false);
  assert.equal(needsBackfill([item({ title: '合成剧' })], '我的合成剧全集'), false);
  assert.equal(needsBackfill([item({ title: '无关剧' })], '合成新剧'), true);
});
test('merge appends ranked matches with an honest notice and dedupe', () => {
  const result = { items: [item()], page: 1, hasMore: false, total: 1, notice: '' };
  const backfill = parseNewReleases([row({ series_id: '1001' }), row({ series_id: '7702453391682702360', title: '合成新剧' }), row({ series_id: '2002', title: '完全无关' })]);
  const merged = mergeNewReleases(result, backfill, '合成新剧');
  assert.equal(merged.items.length, 2, 'the existing row is kept, the irrelevant row is not claimed');
  assert.equal(merged.items[1].seriesId, '7702453391682702360');
  assert.equal(merged.items[1].episodeLabel, '今日上新');
  assert.match(merged.notice, /补入 1 部/);
  assert.deepEqual(mergeNewReleases(result, backfill, '紫禁城风雪夜'), result, 'no match returns the result untouched');
});
test('the fallback search merges same-day releases when both channels miss', async () => {
  const calls = [];
  const search = createFallbackSearch(async params => {
    calls.push(['app', params.keyword, params.page]);
    return rawAppPage();
  }, async () => { calls.push(['web']); throw new Error('官网不可用'); }, {
    newReleases: async params => { calls.push(['new', params.pages]); return [row({ series_id: '3001', title: '今日上新的合成新剧' })]; },
  });
  const result = await search('合成新剧', 1);
  assert.deepEqual(calls.map(entry => entry[0]), ['app', 'new'], 'the App channel misses once, then the catalog answers');
  assert.deepEqual(calls[1], ['new', 2], 'the backfill reads both genres');
  assert.equal(result.channel, 'app');
  assert.equal(result.items.length, 1);
  assert.equal(result.items[0].seriesId, '3001');
  assert.match(result.notice, /近 7 天上新/);
});
test('a failing catalog never fails the search', async () => {
  const search = createFallbackSearch(async () => rawAppPage(),
    async () => { throw new Error('官网不可用'); }, { newReleases: async () => { throw new Error('目录不可用'); } });
  const result = await search('合成新剧', 1);
  assert.equal(result.items.length, 0, 'the empty normal channel answer stands');
  assert.equal(result.notice || '', '');
});
test('relevant first pages never spend a catalog request', async () => {
  let requested = 0;
  const appRow = { code: 0, search_tabs: [{ tab_type: 11, has_more: false, next_offset: 0, search_id: '', passback: '',
    data: [{ video_data: [{ series_id: '5001', title: '合成新剧正传', episode_cnt: 6 }] }] }] };
  const search = createFallbackSearch(async () => appRow,
    async () => { throw new Error('官网不可用'); }, { newReleases: async () => { requested++; return []; } });
  const result = await search('合成新剧', 1);
  assert.equal(requested, 0, 'a relevant hit skips the backfill');
  assert.equal(result.items.length, 1);
});
