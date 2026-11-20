import assert from 'node:assert/strict';
import { test } from 'node:test';

const calls = [];
let response = { result: { ok: true } };
globalThis.window = { AndroidBridge: { postMessage(raw) {
  const request = JSON.parse(raw);
  calls.push(request);
  queueMicrotask(() => window.__androidResponse({ id: request.id, ...response }));
} } };

const { getAndroidSourcesApi } = await import('../src/android/android-sources.mjs');
const { nativeCall } = await import('../src/android/bridge.mjs');

test('Android sources require explicit native capability and a connected bridge', () => {
  assert.equal(getAndroidSourcesApi({ platform: 'android' }), undefined);
  assert.equal(getAndroidSourcesApi({ sourceService: 'true' }), undefined);
  assert.equal(getAndroidSourcesApi({ sourceService: true }, {}), undefined);
  const active = getAndroidSourcesApi({ sourceService: true });
  assert.ok(Object.isFrozen(active));
  assert.equal(getAndroidSourcesApi({ sourceService: true }), active);
  assert.equal(calls.length, 0);
});

test('only the supported source arguments cross the native RPC boundary', async () => {
  const api = getAndroidSourcesApi({ sourceService: true });
  const sourceId = 'guipian', seriesId = 'source:string:9007199254740993', episodeId = 'part-1';
  const extra = { url: 'https://fixture.invalid', credentials: 'must-not-cross', operation: 'account' };
  await api.list(extra);
  await api.settings({ ...extra, sourceId, enabled: false });
  await api.categories({ ...extra, sourceId });
  await api.catalog({ ...extra, sourceId, category: 'films', page: 2 });
  await api.search({ ...extra, sourceId, keyword: '精确搜索', page: 1 });
  await api.detail({ ...extra, sourceId, seriesId });
  await api.play({ ...extra, sourceId, seriesId, episodeId, variant: '0' });
  await api.account({ ...extra, sourceId: 'sorani', command: 'savedStatus', payload: extra });
  const track = { trackList: [] };
  await api.account({ ...extra, sourceId: 'sorani', command: 'login',
    payload: { identifier: 'fixture-account', password: 'fixture-not-real', captchaId: 'fixture-challenge', track,
      ...extra, rememberCredentials: true, useSavedCredentials: true } });
  await api.cancel({ ...extra, sourceId });
  assert.ok(calls.every(call => call.method === 'sourceService'));
  assert.deepEqual(calls.map(call => call.params), [
    { operation: 'list' }, { operation: 'settings', sourceId, enabled: false }, { operation: 'categories', sourceId },
    { operation: 'catalog', sourceId, category: 'films', page: 2 },
    { operation: 'search', sourceId, keyword: '精确搜索', page: 1 }, { operation: 'detail', sourceId, seriesId },
    { operation: 'play', sourceId, seriesId, episodeId, variant: '0' },
    { operation: 'account', sourceId: 'sorani', command: 'savedStatus' },
    { operation: 'account', sourceId: 'sorani', command: 'login',
      payload: { identifier: 'fixture-account', password: 'fixture-not-real', captchaId: 'fixture-challenge', track } },
    { operation: 'cancel', sourceId },
  ]);
});

test('native source error codes are preserved without broadening normal RPC codes', async () => {
  const api = getAndroidSourcesApi({ sourceService: true });
  try {
    response = { error: '来源返回 HTTP 403', errorCode: 'HTTP_403' };
    await assert.rejects(api.play({ sourceId: 'hanxiaoquan', seriesId: 'id', episodeId: 'e' }), { code: 'HTTP_403', message: response.error });
    await assert.rejects(nativeCall('getInfo'), error => error.code === undefined && error.message === response.error);
    response = { error: '[SOURCE_CANCELLED] 已取消', errorCode: 'unsafe code' };
    await assert.rejects(api.categories({ sourceId: 'guipian' }), error => error.code === undefined && error.message === response.error);
  } finally { response = { result: { ok: true } }; }
});
