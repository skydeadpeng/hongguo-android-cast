import test from 'node:test';
import assert from 'node:assert/strict';
const calls = [];
let mode = 'success';
globalThis.window = { dispatchEvent() {}, AndroidBridge: { postMessage(raw) {
  const message = JSON.parse(raw); calls.push(message);
  if (mode === 'hold') return;
  queueMicrotask(() => window.__androidResponse({ id: message.id, ...(mode === 'fail' ? { error: '文件正在使用' } : { result: { id: message.params.id, deleted: true } }) }));
} } };
const { nativeCall } = await import('../src/android/bridge.mjs?download-delete-test');
test('exact deleteDownload method dispatches only the selected id and explicit confirmation', async () => {
  const result = await nativeCall('deleteDownload', { id: 81, confirmed: true });
  assert.deepEqual(result, { id: 81, deleted: true });
  assert.deepEqual(calls.at(-1).params, { id: 81, confirmed: true });
  assert.equal(calls.at(-1).method, 'deleteDownload');
});
test('unrelated bulk/path deletion methods remain unsupported without dispatch', async () => {
  const before = calls.length;
  for (const method of ['deleteManualDownloads', 'deleteAllDownloads', 'removeFile', 'deletePath']) await assert.rejects(nativeCall(method, { path: '/fake' }), /未支持/);
  assert.equal(calls.length, before);
});
test('native deletion failure rejects without manufacturing successful removal', async () => {
  mode = 'fail'; await assert.rejects(nativeCall('deleteDownload', { id: 82, confirmed: true }), /文件正在使用/); mode = 'success';
});
test('timeout rejects and ignores a late native acknowledgement; explicit retry remains available', async () => {
  mode = 'hold'; const request = nativeCall('deleteDownload', { id: 83, confirmed: true }, 10); const id = calls.at(-1).id;
  await assert.rejects(request, /超时/);
  window.__androidResponse({ id, result: { id: 83, deleted: true } });
  mode = 'success'; assert.deepEqual(await nativeCall('deleteDownload', { id: 83, confirmed: true }), { id: 83, deleted: true });
});
