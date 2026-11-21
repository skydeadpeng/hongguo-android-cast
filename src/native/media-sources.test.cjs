const test = require('node:test');
const assert = require('node:assert/strict');
const { getMediaSources, setBuiltinSourceEnabled } = require('./media-sources');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const vm = require('node:vm');

test('hongguo is the only builtin source; toggles are rejected without touching custom sources', () => {
  const settings = { root: '/downloads', builtin_sources: { xifan: false }, custom_sources: [{ id: 'test', name: '片库', enabled: true }] };
  assert.deepEqual(getMediaSources(settings).map(s => s.id), ['hongguo', 'custom:test']);
  assert.equal(getMediaSources({})[0].id, 'hongguo');
  assert.deepEqual(getMediaSources({ custom_sources: [{ id: 'off', name: '停用', enabled: false }] }).map(s => s.id), ['hongguo']);
  assert.throws(() => setBuiltinSourceEnabled(settings, 'hongguo', false), /红果/);
  assert.throws(() => setBuiltinSourceEnabled(settings, 'xifan', false));
  assert.throws(() => setBuiltinSourceEnabled(settings, 'custom:test', false));
  assert.equal(settings.custom_sources.length, 1, 'rejected toggles never mutate custom sources');
});

test('IPC lists the single builtin source and rejects every toggle', async () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'hongguo-sources-'));
  const store = require('../store');
  const file = path.join(directory, 'settings.json');
  try {
    store.init(file);
    const handlers = {}, events = [];
    const source = fs.readFileSync(path.join(__dirname, '../../main.js'), 'utf8');
    const context = {
      require: name => { assert.equal(name, './src/native/media-sources'); return { getMediaSources, setBuiltinSourceEnabled }; },
      store, mainWindow: null, getCurrentSettings: () => store.getSettings(),
      BrowserWindow: { getAllWindows: () => [1, 2].map(id => ({ webContents: { isDestroyed: () => false, send: event => events.push([id, event]) } })) },
      ipcMain: { handle: (name, fn) => handlers[name] = fn }, console,
    };
    for (const name of ['get-media-sources', 'set-builtin-source-enabled']) {
      const start = source.indexOf(`ipcMain.handle('${name}'`);
      const end = name === 'get-media-sources' ? source.indexOf('\n', start) : source.indexOf('\n});', start) + 4;
      vm.runInNewContext(source.slice(start, end), context);
    }
    assert.equal((await handlers['set-builtin-source-enabled']({}, 'hongguo', false)).success, false);
    assert.equal((await handlers['set-builtin-source-enabled']({}, 'xifan', false)).success, false);
    store.init(file);
    assert.deepEqual((await handlers['get-media-sources']()).sources.map(s => s.id), ['hongguo']);
    assert.deepEqual(events, [], 'failed toggles notify no window');
  } finally { fs.rmSync(directory, { recursive: true, force: true }); }
});
