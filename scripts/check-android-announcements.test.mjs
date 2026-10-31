import test from 'node:test';
import assert from 'node:assert/strict';
import { resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
const sourceRoot = resolve(process.env.ANDROID_SOURCE_ROOT || fileURLToPath(new URL('..', import.meta.url)));
const { parseAnnouncement, createAnnouncements, announcementLink } =
  await import(pathToFileURL(resolve(sourceRoot, 'src/android/announcements.mjs')).href);

const file = (overrides = {}) => JSON.stringify({ id: 'notice-2026-1', title: ' 版本说明 ', body: ' 第一行\n第二行 ', active: true, ...overrides });
const throws = fn => assert.throws(fn, error => error instanceof Error && /[\u4e00-\u9fff]/.test(error.message));
const store = () => {
  const values = new Map();
  globalThis.localStorage = { getItem: key => values.get(key) ?? null, setItem: (key, value) => values.set(key, value), removeItem: key => values.delete(key) };
  return values;
};
const clock = at => { let now = at; const tick = value => { now = value; }; tick.now = () => now; return tick; };

test('parse accepts the exact four-key active shape and trims text', () => {
  assert.deepEqual(parseAnnouncement(file(), false), { id: 'notice-2026-1', title: '版本说明', body: '第一行\n第二行', urgent: false });
  assert.equal(parseAnnouncement(file(), true).urgent, true);
});
test('parse rejects malformed, oversized, non-active and control-text payloads', () => {
  for (const raw of [null, 42, '', '   ', 'x'.repeat(32 * 1025), '{', '[]', '"text"', file({ active: false }),
    file({ id: '' }), file({ id: '-bad' }), file({ id: 'x'.repeat(129) }), file({ id: 7 }),
    file({ title: '' }), file({ title: 'a\nb' }), file({ title: 'x'.repeat(121) }), file({ title: '<b>bold</b>' }),
    file({ body: ' ' }), file({ body: 'x'.repeat(4001) }), file({ body: 'a\u0007b' }),
    file({ extra: 1 })]) {
    assert.equal(parseAnnouncement(raw, false), null, JSON.stringify(String(raw).slice(0, 40)));
  }
});
test('checks surface an emergency once per id, then never again in the process', async () => {
  store();
  const service = createAnnouncements(async () => ({ emergency: file({ id: 'urgent-1' }), normal: null }), '1.0.0', () => 1000);
  const first = await service.check();
  assert.equal(first.announcement.id, 'urgent-1');
  // Upstream semantics: an unacknowledged urgent notice re-shows on every check;
  // only the dialog acknowledgement (persisted) stops it.
  assert.equal((await service.check()).announcement.id, 'urgent-1', 'an unacknowledged urgent notice re-shows');
  assert.equal(service.acknowledge('urgent-1').success, true);
  assert.deepEqual(await service.check(), { announcement: null }, 'an acknowledged urgent id never auto-shows again');
  const second = createAnnouncements(async () => ({ emergency: file({ id: 'urgent-1' }), normal: null }), '1.0.0');
  assert.equal((await second.check()).announcement, null, 'a persisted seen id survives a restart');
});
test('a failing emergency id write reports honestly and can be retried', async () => {
  store();
  const service = createAnnouncements(async () => ({ emergency: file({ id: 'urgent-2' }), normal: null }), '1.0.0');
  const shown = await service.check();
  const previousWrite = globalThis.localStorage.setItem;
  globalThis.localStorage.setItem = () => { throw new Error('disk full'); };
  const result = service.acknowledge(shown.announcement.id);
  globalThis.localStorage.setItem = previousWrite;
  assert.equal(result.success, false);
  assert.match(result.error, /未能保存/);
  assert.equal(service.acknowledge(shown.announcement.id).success, true, 'a retried acknowledgement persists once storage recovers');
  const again = createAnnouncements(async () => ({ emergency: file({ id: 'urgent-2' }), normal: null }), '1.0.0');
  assert.equal((await again.check()).announcement, null, 'the persisted seen id survives a restart');
});
test('normal announcements honour the day and version suppression modes', async () => {
  const values = store();
  const now = clock(1700000000000);
  const service = createAnnouncements(async () => ({ emergency: null, normal: file({ id: 'normal-1' }) }), '2.0.0', now.now);
  assert.equal((await service.check()).announcement.id, 'normal-1');
  assert.equal((await service.check({ urgentOnly: true })).announcement, null, 'urgent-only checks skip the normal channel');
  assert.equal(service.dismiss('hour').success, false, 'an unknown suppression mode is refused');
  assert.equal(service.dismiss('day').success, true);
  assert.equal((await service.check()).announcement, null, 'the same local day stays silenced');
  now(1700000000000 + 24 * 60 * 60 * 1000);
  assert.equal((await service.check()).announcement.id, 'normal-1', 'the next local day shows again');
  assert.equal(service.dismiss('version').success, true);
  now(1700000000000 + 48 * 60 * 60 * 1000);
  assert.equal((await service.check()).announcement, null, 'version suppression outlives days');
  values.clear();
  const otherVersion = createAnnouncements(async () => ({ emergency: null, normal: file({ id: 'normal-1' }) }), '2.1.0', now.now);
  assert.equal((await otherVersion.check()).announcement.id, 'normal-1', 'a different version clears version suppression');
});
test('channel switches invalidate in-flight checks and persist', async () => {
  store();
  let reply = { emergency: null, normal: file({ id: 'normal-2' }) };
  const service = createAnnouncements(async () => reply, '3.0.0');
  assert.equal(service.setPreferences({ normalEnabled: 'yes' }).success, false, 'a non-boolean switch is refused');
  assert.equal(service.setPreferences({ emergencyEnabled: false }).success, true);
  assert.equal(service.setPreferences({ normalEnabled: false }).success, true);
  assert.deepEqual(service.getPreferences(), { normalEnabled: false, emergencyEnabled: false });
  assert.equal((await service.check()).announcement, null, 'a disabled normal channel shows nothing');
  reply = { emergency: file({ id: 'urgent-3' }), normal: null };
  assert.equal((await service.check()).announcement, null, 'a disabled emergency channel is not auto-shown');
  assert.deepEqual(service.getPreferences(), { normalEnabled: false, emergencyEnabled: false }, 'switches persist across service instances');
});
test('the manual view fetches both channels once and reports emptiness honestly', async () => {
  store();
  let calls = 0;
  const service = createAnnouncements(async () => { calls++; return { emergency: null, normal: null }; }, '4.0.0');
  const empty = await service.manual();
  assert.match(empty.error, /暂无可查看的公告/);
  const another = createAnnouncements(async () => ({ emergency: null, normal: file({ id: 'normal-3' }) }), '4.0.0');
  const shown = await another.manual();
  assert.equal(shown.announcement.id, 'normal-3');
  assert.equal(shown.announcement.urgent, false);
  assert.equal(calls, 2, 'one fetch per channel per manual view');
});
test('acknowledge refuses ids this run never displayed', () => {
  store();
  const service = createAnnouncements(async () => ({ emergency: null, normal: null }), '5.0.0');
  assert.equal(service.acknowledge('urgent-never-shown').success, false);
  assert.equal(service.acknowledge('').success, false);
});
test('announcements need a native caller and a version', () => {
  throws(() => createAnnouncements(null, '1.0.0'));
  throws(() => createAnnouncements(async () => ({}), ' '));
});
test('body links are https only', () => {
  assert.equal(announcementLink('见 https://example.com/a?b=1）了解'), 'https://example.com/a?b=1');
  assert.equal(announcementLink('http://example.com/insecure'), '');
  assert.equal(announcementLink('没有链接'), '');
});
