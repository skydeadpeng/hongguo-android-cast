import test from 'node:test';
import assert from 'node:assert/strict';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
const root = process.env.HONGGUO_TEST_ROOT || path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const playback = await import(pathToFileURL(path.join(root, 'src/android/season-playback.mjs')));
const { assertPlaybackRequestSize, latestSeasonHistory, sameSeasonFamily } = playback;
const season = (n, count = 10, extra = {}) => ({
  seriesId: String(1000000 + n), title: `测试：第${n}季`, episodeCount: count,
  episodes: Array.from({ length: count }, (_, i) => ({ vid: String(100000 + n * 10000 + i), index: i + 1, webAvailable: i < 3 })), ...extra,
});
const meta = ({ episodes, ...s }) => s;

test('automatic cross-season planner is removed, not only switched off in the UI', () => {
  assert.equal('prepareSeasonPlaylist' in playback, false);
  assert.equal(typeof assertPlaybackRequestSize, 'function');
});
test('single-season request preserves complete own-season IDs, autoplay and cache preferences', () => {
  const a = season(2, 100);
  const params = { series: meta(a), episode: a.episodes[32], episodes: a.episodes, autoplay: true, cleanupPlaybackCache: true, resolveInPlayer: true, quality: '1080p' };
  const before = structuredClone(params);
  assert.equal(assertPlaybackRequestSize(params), undefined);
  assert.deepEqual(params, before, 'size check never joins seasons or rewrites the selected identity');
  assert.equal(params.episodes.length, 100);
  assert.equal(params.episode.index, 33);
  assert.equal('playlist' in params, false);
});
test('240KiB native request budget remains inclusive and rejects larger UTF8 payloads', () => {
  const limit = 240 * 1024;
  const overhead = new TextEncoder().encode(JSON.stringify({ padding: '' })).length;
  assert.doesNotThrow(() => assertPlaybackRequestSize({ padding: 'x'.repeat(limit - overhead) }));
  assert.throws(() => assertPlaybackRequestSize({ padding: 'x'.repeat(limit - overhead + 1) }), e => e.code === 'PLAY_REQUEST_TOO_LARGE' && /请求容量/.test(e.message));
  assert.doesNotThrow(() => assertPlaybackRequestSize({ padding: '季'.repeat(80000) }));
  assert.throws(() => assertPlaybackRequestSize({ padding: '季'.repeat(82000) }), e => e.code === 'PLAY_REQUEST_TOO_LARGE');
});
test('large own-season queue fails explicitly rather than truncating any episodes', () => {
  const a = season(1, 4000);
  a.episodes = a.episodes.map((row, i) => ({ ...row, vid: String(8000000000000000000n + BigInt(i)), availability: 'unknown' }));
  const params = { series: meta(a), episodes: a.episodes };
  assert.throws(() => assertPlaybackRequestSize(params), e => e.code === 'PLAY_REQUEST_TOO_LARGE');
  assert.equal(params.episodes.length, 4000);
});
test('historical latest uses actual updatedAt, not highest season or largest episode', () => {
  const a = season(1), b = season(2);
  const rows = [{ ...meta(b), vid: b.episodes[9].vid, index: 10, updatedAt: 1000, positionMs: 100 }, { ...meta(a), vid: a.episodes[0].vid, index: 1, updatedAt: 2000, positionMs: 5555 }];
  assert.equal(latestSeasonHistory(b, rows).seriesId, a.seriesId);
  assert.equal(latestSeasonHistory(a, rows).positionMs, 5555);
  assert.equal(latestSeasonHistory(a, [{ ...rows[0], title: '测试第二季' }]), null);
  assert.equal(latestSeasonHistory(a, [{ ...rows[0], title: '测试：第二部' }]), null);
});
test('later-season history remains selectable without creating an automatic playlist', () => {
  const a = season(1), b = season(2);
  const history = [{ ...meta(a), vid: a.episodes[9].vid, index: 10, updatedAt: 1000 }, { ...meta(b), vid: b.episodes[2].vid, index: 3, updatedAt: 2000, positionMs: 45000 }];
  const before = structuredClone(history), selected = latestSeasonHistory(a, history);
  assert.deepEqual([selected.seriesId, selected.vid, selected.index, selected.positionMs], [b.seriesId, b.episodes[2].vid, 3, 45000]);
  assert.deepEqual(history, before, 'reading history does not rewrite any season progress');
});
test('invalid history identities and undated other seasons are not guessed', () => {
  const a = season(1), b = season(2);
  assert.equal(latestSeasonHistory(a, [{ ...meta(b), vid: '7', index: 1 }]), null);
  assert.equal(latestSeasonHistory(a, [{ ...meta(b), vid: 7, index: 1, updatedAt: 100 }]), null);
  assert.equal(latestSeasonHistory(a, [{ ...meta(b), vid: '7', index: 0, updatedAt: 100 }]), null);
  assert.equal(latestSeasonHistory(a, null), null);
  assert.equal(sameSeasonFamily(a, { title: '测试第一季' }), false);
});
test('legacy undated history still resumes only its exact season', () => {
  const a = season(1), b = season(2), own = { ...meta(a), vid: '7', index: 1, positionMs: 6000 };
  assert.equal(latestSeasonHistory(a, [{ ...meta(b), vid: '8', index: 2 }, own]), own);
});
