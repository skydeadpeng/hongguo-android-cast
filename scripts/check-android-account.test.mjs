import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import { accountStatus, accountView, phoneFields, otpFields, accountImportSummary } from '../src/android/account.mjs';
const state = { provider: 'hongguo', authenticated: false, officialLoginAvailable: true, phase: 'phone', revision: 1 };

test('public projection contains only masked official state and excludes native session', () => {
  const value = accountView({ ...state, token: 'PRIVATE_TOKEN', cookies: { sessionid: 'PRIVATE_COOKIE' }, session: { private: 'PRIVATE_SESSION' }, phone: '13800000000', maskedPhone: '138****0000', smsState: 'sent', retryAfter: 60, sync: { pendingCount: 3 } });
  assert.equal(value.maskedPhone, '138****0000'); assert.equal(value.sync.pendingCount, 3);
  for (const secret of ['PRIVATE_TOKEN', 'PRIVATE_COOKIE', 'PRIVATE_SESSION', '13800000000']) assert.equal(JSON.stringify(value).includes(secret), false);
});
test('stale custom provider or malformed readiness cannot become official login', () => {
  for (const value of [null, undefined, {}, { ...state, provider: 'custom' }, { ...state, authenticated: 'true' }, { ...state, officialLoginAvailable: false }, { ...state, phase: 'fake' }]) assert.throws(() => accountView(value));
});
test('phone and OTP validation preserve leading zeros and reject non-mainland or non-numeric input', () => {
  assert.deepEqual(phoneFields('+86 13800000000'), { phone: '13800000000' }); assert.deepEqual(phoneFields('8613800000000'), { phone: '13800000000' });
  for (const phone of ['', '1380000000', '+85261234567', '1380000000a', '12800000000']) assert.throws(() => phoneFields(phone));
  assert.deepEqual(otpFields('001234'), { code: '001234' }); for (const code of ['', '123', '123456789', 123456, '12 3456', 'abcdef']) assert.throws(() => otpFields(code));
});
test('status never declares successful send/login/sync for uncertainty, MFA waiting, or pending outbox', () => {
  assert.match(accountStatus(accountView({ ...state, smsState: 'uncertain' })), /尚未确认/);
  assert.match(accountStatus(accountView({ ...state, phase: 'mfa_waiting', busy: true })), /90秒/);
  assert.match(accountStatus(accountView({ ...state, authenticated: true, phase: 'logged_in', sync: { pendingCount: 2 } })), /2 条/);
  assert.match(accountStatus(accountView({ ...state, authenticated: true, phase: 'logged_in', sync: { syncing: true } })), /正在同步/);
});
test('native cancellation runs on UI fast path before account executor and routes SMS-app suspend separately', async () => {
  const source = await fs.readFile(new URL('../android/app/src/main/java/io/github/jackotom/hongguo/android/NativeBridge.java', import.meta.url), 'utf8');
  assert.ok(source.indexOf('method.equals("officialCancelLogin")') < source.indexOf('ThreadPoolExecutor executor ='));
  assert.match(source, /"background"\.equals\(params\.optString\("reason"\)\).*suspendLogin/);
  assert.match(source, /method\.startsWith\("official"\) \? accountWorker/);
  assert.match(source, /accountWorker = new ThreadPoolExecutor\(1, 1, 30, TimeUnit.SECONDS, new SynchronousQueue<>\(\)\)/);
  assert.match(source, /favoriteStore = new LibraryStore\(activity\)/);
  assert.match(source, /AccountSync\.get\(\)\.requireCurrent\(favoriteSelection\)/);
  assert.match(source, /favoriteStore\.toggle/);
  for (const legacy of ['"accountLogin"', '"accountLogout"', '"syncHistory"', '"importGuestHistory"']) assert.equal(source.includes(legacy), false);
});
test('UI keeps OTP only in React memory and distinguishes background suspend from explicit cancel', async () => {
  const code = await fs.readFile(new URL('../src/android/AccountPanel.jsx', import.meta.url), 'utf8');
  assert.match(code, /setCode\(''\); perform\('officialLoginSMS'/);
  assert.match(code, /reason: 'background'/); assert.match(code, /我已发送短信，开始验证/);
  for (const legacy of ['serverUrl', 'type="password"', 'localStorage', 'sessionStorage']) assert.equal(code.includes(legacy), false);
});
test('web bridge permits exact official actions but rejects retired methods before dispatch', async () => {
  const calls = [];
  globalThis.window = { dispatchEvent: () => {}, AndroidBridge: { postMessage: raw => { const request = JSON.parse(raw); calls.push(request); queueMicrotask(() => window.__androidResponse({ id: request.id, result: state })); } } };
  try { const { nativeCall } = await import('../src/android/bridge.mjs?official-account');
    for (const method of ['getAccount', 'officialSendCode', 'officialLoginSMS', 'officialVerifyMFA', 'officialCancelLogin', 'officialLogout', 'officialSync', 'officialImportGuest']) await nativeCall(method, {});
    for (const method of ['accountLogin', 'accountLogout', 'syncHistory', 'importGuestHistory', 'getToken']) await assert.rejects(nativeCall(method), /未支持/);
    assert.equal(calls.length, 8);
  } finally { delete globalThis.window; }
});


test('owner CustomEvent keeps only guest or opaque hash metadata and point reads preserve requested series', async () => {
  const events = [], calls = []; const Custom = globalThis.CustomEvent;
  if (typeof Custom !== 'function') globalThis.CustomEvent = class { constructor(type, options) { this.type = type; this.detail = options.detail; } };
  globalThis.window = { dispatchEvent: event => { events.push(event); }, AndroidBridge: { postMessage: raw => { const request = JSON.parse(raw); calls.push(request); queueMicrotask(() => window.__androidResponse({ id: request.id, result: { owner: 'guest', history: { seriesId: request.params.seriesId, positionMs: 1234 } } })); } } };
  try {
    const { nativeCall } = await import('../src/android/bridge.mjs?official-owner-point-read');
    window.__androidAccountChanged({ owner: 'guest', token: 'PRIVATE' });
    window.__androidAccountChanged({ owner: 'official:' + 'a'.repeat(64), uid: '7123456789012345678' });
    window.__androidAccountChanged({ owner: '7123456789012345678' });
    assert.deepEqual(events.map(event => [event.type, event.detail.owner]), [['androidaccountchanged', 'guest'], ['androidaccountchanged', 'official:' + 'a'.repeat(64)], ['androidaccountchanged', undefined]]);
    assert.equal(JSON.stringify(events).includes('7123456789012345678'), false); assert.equal(JSON.stringify(events).includes('PRIVATE'), false);
    const value = await nativeCall('getSeriesHistory', { seriesId: '7000000000000000001' });
    assert.deepEqual(calls.map(call => ({ method: call.method, params: call.params })), [{ method: 'getSeriesHistory', params: { seriesId: '7000000000000000001' } }]);
    assert.deepEqual(value, { owner: 'guest', history: { seriesId: '7000000000000000001', positionMs: 1234 } });
  } finally { delete globalThis.window; if (Custom === undefined) delete globalThis.CustomEvent; else globalThis.CustomEvent = Custom; }
});

test('native library and point reads capture owner before and after a fresh store', async () => {
  const source = await fs.readFile(new URL('../android/app/src/main/java/io/github/jackotom/hongguo/android/NativeBridge.java', import.meta.url), 'utf8');
  assert.match(source, /case "getSeriesHistory": respond\(id, currentSeriesHistory\(params\), null\)/);
  for (const name of ['currentSeriesHistory', 'currentLibrary']) {
    const body = source.slice(source.indexOf(`private JSONObject ${name}(`), source.indexOf('\n    }', source.indexOf(`private JSONObject ${name}(`)));
    assert.ok(body.indexOf('ownerStamp()') < body.indexOf('new LibraryStore(activity)'));
    assert.match(body, /before.equals\(AccountSync.get\(\).ownerStamp\(\)\)/);
    assert.match(body, /put\("owner", before\)/);
  }
});

test('uncertain OTP state and import counts never advertise false send or complete import', () => {
  assert.match(accountStatus(accountView({ ...state, phase: 'code', smsState: 'uncertain', maskedPhone: '138****0000' })), /尚未确认/);
  assert.doesNotMatch(accountStatus(accountView({ ...state, phase: 'code', smsState: 'uncertain' })), /验证码已发送/);
  const text = accountImportSummary({ historyImportedCount: 2, favoritesImportedCount: 1, skippedCount: 3, remainingCount: 4 });
  assert.match(text, /收藏 1 条/); assert.match(text, /观看记录 2 条/); assert.match(text, /还有 4 条/);
  assert.doesNotMatch(text, /已处理完毕/);
  for (const value of [null, {}, { historyImportedCount: -1 }, { historyImportedCount: 1, favoritesImportedCount: 1, skippedCount: NaN, remainingCount: 0 }]) assert.match(accountImportSummary(value), /尚未确认/);
});
