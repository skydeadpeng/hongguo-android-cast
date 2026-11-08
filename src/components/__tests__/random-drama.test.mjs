import test from 'node:test';
import assert from 'node:assert/strict';
import * as model from '../randomDramaModel.mjs';

const drama = (id, title = id) => ({ series_id: id, series_title: title });

test('pool keeps available sources, validates IDs and deduplicates', async () => {
  assert.equal(typeof model.loadRandomPool, 'function');
  const requests = [];
  const result = await model.loadRandomPool(async options => {
    requests.push(options);
    if (options.source === 'xifan') throw new Error('offline');
    return { success: true, results: options.source === 'hongguo'
      ? [drama('123456'), drama('123456'), drama('hema:1'), drama('bad'), drama('654321', ' ')]
      : options.source === 'huangguo' ? [drama('huangguo:787')]
      : [drama('hema:1'), drama('hema:2')] };
  });
  assert.deepEqual(result.items.map(item => item.series_id), ['123456', 'hema:1', 'hema:2', 'huangguo:787']);
  assert.deepEqual(result.unavailable, ['xifan']);
  assert.equal(requests.length, 4);
  assert.ok(requests.every(request => request.page === 1 && request.category === 'real-drama'));
});

test('single-source pool never requests other sources and reports empty directories', async () => {
  const requests = [];
  const result = await model.loadRandomPool(async request => {
    requests.push(request.source);
    return { success: true, results: [] };
  }, { source: 'xifan' });
  assert.deepEqual(requests, ['xifan']);
  assert.deepEqual(result.items, []);
  assert.deepEqual(result.unavailable, ['xifan']);
});

test('draw excludes the previous ID and the same title across sources', () => {
  const previous = drama('123456', '同一部');
  const next = drama('hema:2', '另一部');
  assert.equal(model.pickRandomDrama([previous, drama('hema:1', '同一部'), next], previous, () => 0), next);
  assert.equal(model.pickRandomDrama([previous], previous), null);
  assert.equal(model.pickRandomDrama([]), null);
});

test('slow source times out without discarding a working source', async () => {
  const result = await model.loadRandomPool(({ source }) => source === 'hongguo'
    ? Promise.resolve({ success: true, results: [drama('123456')] })
    : new Promise(() => {}), { timeoutMs: 10 });
  assert.equal(result.items.length, 1);
  assert.deepEqual(result.unavailable, ['xifan', 'hema', 'huangguo']);
});

test('cancelling a pending pool rejects promptly', async () => {
  const controller = new AbortController();
  const task = model.loadRandomPool(() => new Promise(() => {}), { signal: controller.signal });
  controller.abort();
  await assert.rejects(task, { name: 'AbortError' });
});

test('playback uses the chosen source ID and skips locked episodes', async () => {
  const calls = [];
  await model.playRecommendedDrama({ searchResolve: async id => ({ success: true, data: { series_id: id,
    episodes: [{ vid_index: 1, locked: true }, { vid_index: 2, locked: false }] } }),
    playSeries: async options => { calls.push(options); return { success: true }; } }, drama('hema:1'));
  assert.deepEqual(calls, [{ seriesId: 'hema:1', vidIndex: 2, resume: false }]);
});

test('mismatched, locked-only and cancelled resolutions never open the player', async () => {
  for (const kind of ['mismatch', 'locked', 'cancel']) {
    const controller = new AbortController();
    let played = false;
    const api = { searchResolve: async () => {
      if (kind === 'cancel') controller.abort();
      return { success: true, data: { series_id: kind === 'mismatch' ? 'hema:9' : 'hema:1',
        episodes: [{ vid_index: 1, locked: kind === 'locked' }] } };
    }, playSeries: async () => { played = true; } };
    await assert.rejects(model.playRecommendedDrama(api, drama('hema:1'), { signal: controller.signal }));
    assert.equal(played, false);
  }
});


test('all-source draw includes enabled custom registries and rejects a different custom source ID', async () => {
  const id = 'custom:550e8400-e29b-41d4-a716-446655440000';
  const calls = [];
  const result = await model.loadRandomPool(async request => {
    calls.push(request.source);
    return { success: true, results: [drama(`${id}:12`), drama('custom:650e8400-e29b-41d4-a716-446655440000:13')] };
  }, { sources: [{ id, label: '测试源', custom: true }] });
  assert.deepEqual(calls, [id]);
  assert.deepEqual(result.items.map(item => item.series_id), [`${id}:12`]);
});

const recommendedDetail = {
  success: true,
  data: { series_id: '123', episodes: [
    { vid_index: 1, locked: false }, { vid_index: 2, locked: false }, { vid_index: 3, locked: true },
  ] },
};

test('recommendation resumes the saved playable episode', async () => {
  const calls = [];
  await model.playRecommendedDrama({
    searchResolve: async () => recommendedDetail,
    getPlaybackPosition: async () => ({ vid_index: 2, currentTime: 30 }),
    playSeries: async payload => { calls.push(payload); return { success: true }; },
  }, { series_id: '123', series_title: '测试剧' });
  assert.deepEqual(calls[0], { seriesId: '123', vidIndex: 2, resume: true });
});

test('recommendation starts at the first playable episode without a saved record', async () => {
  const calls = [];
  await model.playRecommendedDrama({
    searchResolve: async () => recommendedDetail,
    getPlaybackPosition: async () => null,
    playSeries: async payload => { calls.push(payload); return { success: true }; },
  }, { series_id: '123', series_title: '测试剧' });
  assert.deepEqual(calls[0], { seriesId: '123', vidIndex: 1, resume: false });
});

test('a locked or missing saved episode falls back to the first playable one', async () => {
  for (const saved of [{ vid_index: 3 }, { vid_index: 99 }, { vid_index: 0 }]) {
    const calls = [];
    await model.playRecommendedDrama({
      searchResolve: async () => recommendedDetail,
      getPlaybackPosition: async () => saved,
      playSeries: async payload => { calls.push(payload); return { success: true }; },
    }, { series_id: '123', series_title: '测试剧' });
    assert.deepEqual(calls[0], { seriesId: '123', vidIndex: 1, resume: false });
  }
});
