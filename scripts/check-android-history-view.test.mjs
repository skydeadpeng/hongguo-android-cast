import test from 'node:test';
import assert from 'node:assert/strict';
import { resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
const sourceRoot = resolve(process.env.ANDROID_SOURCE_ROOT || fileURLToPath(new URL('..', import.meta.url)));
const { completedEpisode, filterHistory, historyHints, withHistoryHints } =
  await import(pathToFileURL(resolve(sourceRoot, 'src/android/history-view.mjs')).href);

const row = (overrides = {}) => ({ seriesId: '1001', title: '合成剧', vid: '9001', index: 1, episodeCount: 12,
  positionMs: 60_000, durationMs: 120_000, updatedAt: 100, ...overrides });
const finished = (overrides = {}) => row({ index: 12, positionMs: 120_000, ...overrides });

test('completed means the last episode played near its end', () => {
  assert.equal(completedEpisode(finished()), true, 'the last episode at the very end');
  assert.equal(completedEpisode(finished({ positionMs: 95_000 })), true, 'the 30-second tail still counts');
  assert.equal(completedEpisode(finished({ positionMs: 89_000 })), false, 'mid-episode does not count');
  assert.equal(completedEpisode(row({ index: 11, positionMs: 120_000 })), false, 'a non-final episode never counts');
  assert.equal(completedEpisode(row({ episodeCount: 0 })), false, 'an unknown count never counts');
  assert.equal(completedEpisode(row({ durationMs: 0 })), false, 'an unknown duration never counts');
  assert.equal(completedEpisode(row({ durationMs: 30_000, positionMs: 30_000 })), false, 'a too-short duration never counts');
  assert.equal(completedEpisode(null), false);
});
test('filters keep the provider order inside each group', () => {
  const rows = [finished({ seriesId: '1' }), row({ seriesId: '2' }), finished({ seriesId: '3' })];
  assert.deepEqual(filterHistory(rows, 'all').map(r => r.seriesId), ['1', '2', '3']);
  assert.deepEqual(filterHistory(rows, 'completed').map(r => r.seriesId), ['1', '3']);
  assert.deepEqual(filterHistory(rows, 'watching').map(r => r.seriesId), ['2']);
  assert.throws(() => filterHistory(rows, 'ended'));
});
test('an explicit lower season is hinted toward the higher one in the same family', () => {
  const rows = [row({ seriesId: 'a', title: '山海情', updatedAt: 300 }), row({ seriesId: 'b', title: '山海情 第二季', updatedAt: 100 })];
  const hints = historyHints(rows);
  assert.equal(hints.length, 1);
  assert.equal(hints[0].seriesId, 'a');
  assert.match(hints[0].text, /第 2 季/);
  assert.deepEqual(historyHints([row({ seriesId: 'c', title: '独行月球' })]), [], 'a single row is never hinted');
  assert.deepEqual(historyHints([row({ seriesId: 'd', title: '山海情' }), row({ seriesId: 'e', title: '山海情 第二季' }), row({ seriesId: 'f', title: '山海情 第三季' })])
    .map(hint => hint.seriesId), ['d', 'e'], 'both lower seasons are hinted');
});
test('near-identical bases group via bare season numbers or hint across families', () => {
  // A bare trailing number is the same family; the un-numbered row is the one hinted.
  const bare = historyHints([row({ seriesId: 'old', title: '我的逆袭人生 第二季' }), row({ seriesId: 'new', title: '我的逆袭人生3' })]);
  assert.equal(bare.length, 1);
  assert.equal(bare[0].seriesId, 'old');
  assert.match(bare[0].text, /第 3 季/);
  // Genuinely different bases within the edit cap hint across families.
  const across = historyHints([row({ seriesId: 'a1', title: '星辰大海 第二季' }), row({ seriesId: 'b1', title: '星尘大海 第三季' })]);
  assert.equal(across.length, 1);
  assert.equal(across[0].seriesId, 'a1');
  assert.match(across[0].text, /同一部剧/);
  assert.equal(historyHints([row({ seriesId: 'x', title: '完全不同的一部剧' }), row({ seriesId: 'y', title: '风马牛不相及的另一部' })]).length, 0, 'unrelated titles stay silent');
  assert.equal(historyHints([row({ seriesId: 'y2019', title: '逆袭2019' }), row({ seriesId: 'y2', title: '逆袭 第二季' })]).length, 0, 'a year never becomes a season');
  const many = [];
  for (let at = 0; at < 10; at++) many.push(row({ seriesId: String(at), title: `剧名示例 第${at}季` }));
  assert.ok(historyHints(many).length <= 5, 'hints are capped at five');
});
test('the decorator attaches hints without mutating rows or breaking clean lists', () => {
  const rows = [row({ seriesId: 'a', title: '山海情' }), row({ seriesId: 'b', title: '山海情 第二季' })];
  const { hints, decorate } = withHistoryHints(rows);
  assert.equal(hints.length, 1);
  const decorated = rows.map(decorate);
  assert.equal(decorated[0].hint, hints[0].text);
  assert.equal(decorated[1].hint, undefined);
  assert.equal(decorate(rows[0]).title, '山海情', 'the original row is not mutated');
  assert.deepEqual(withHistoryHints([row({ seriesId: 'solo', title: '孤剧' })]).hints, []);
});
