// Offline regression: desktop entry (main-android.js) must prepare every episode —
// including the first one and any web-open episode — as a whole-file download,
// never as a streaming hkplay://s/ session. Regresses the old "streaming-first
// for web-open episodes" mode that played episode 1 from the CDN forwarder while
// later episodes downloaded whole files.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const vm = require('node:vm');
const { EventEmitter } = require('node:events');
const { createRequire } = require('node:module');

const root = fs.mkdtempSync(path.join(os.tmpdir(), 'hongguo-desktop-dl-'));
const source = path.resolve(__dirname, '../main-android.js');
const req = createRequire(source);

let failures = 0;
const fail = message => { console.error(`FAIL ${message}`); failures++; };

async function load({ fetchPlayUrlSingle }) {
  const handlers = {}, ipcEvents = {}, protocolHandlers = {};
  const app = new EventEmitter();
  Object.assign(app, {
    setName() {}, getVersion: () => 'test',
    getPath: () => root,
    commandLine: { appendSwitch() {} },
    requestSingleInstanceLock: () => true,
    whenReady: () => readyPromise,
  });
  const readyPromise = Promise.resolve();
  const shell = { openExternal: async () => {} };
  const nativeTheme = { themeSource: 'light' };

  class FakeWebContents {
    constructor() { this.sent = []; this._loadFinished = null; }
    on(name, handler) { if (name === 'did-finish-load') this._loadFinished = handler; }
    send(channel, payload) { this.sent.push({ channel, payload }); }
    executeJavaScript() { return Promise.resolve('NO_RESOLVER'); }
    isDestroyed() { return false; }
  }
  class FakeWindow {
    constructor() { this.webContents = new FakeWebContents(); this.handlers = {}; this.destroyed = false; }
    loadFile() { }
    loadURL() { }
    on(name, handler) { this.handlers[name] = handler; }
    show() { }
    close() { this.destroyed = true; this.handlers.closed && this.handlers.closed(); }
    isDestroyed() { return this.destroyed; }
  }

  const windows = [];
  const BrowserWindow = function () { const win = new FakeWindow(); windows.push(win); return win; };

  const electron = {
    app, shell, nativeTheme, BrowserWindow,
    ipcMain: { handle: (name, fn) => handlers[name] = fn, on: (name, fn) => (ipcEvents[name] = ipcEvents[name] || []).push(fn) },
    protocol: { registerSchemesAsPrivileged() {}, handle: (name, fn) => protocolHandlers[name] = fn },
  };

  const downloads = [];
  const destination = {};
  const downloadEpisodeToFile = async (playInfo, file, options) => {
    downloads.push({ url: playInfo.url, file });
    fs.writeFileSync(file, Buffer.alloc(4096, 7));
    destination[file] = true;
    if (!options || typeof options.onProgress !== 'function') throw new Error('downloadEpisodeToFile expects progress contract');
  };

  const overrides = {
    electron,
    './src/native/android-bridge': { createAndroidBridge: () => ({ handle: async () => ({}), library: {} }) },
    './src/native/hongguo': { fetchPlayUrlSingle },
    './src/native/ffmpeg-run.cjs': { downloadEpisodeToFile },
  };

  const context = vm.createContext({
    require: name => overrides[name] || req(name),
    __dirname: path.dirname(source),
    console: { log() {}, warn() {}, error() {} },
    process, Buffer, URL, setTimeout, clearTimeout,
  });
  vm.runInContext(fs.readFileSync(source, 'utf8'), context, { filename: source });
  // whenReady().then(...) defers registration to a microtask; let it run first.
  await new Promise(resolve => setImmediate(resolve));
  return { handlers, windows, downloads };
}

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));

(async () => {
  // The official web page "opens" every requested episode (typical for episode 1),
  // so the old streaming-first mode would short-circuit to hkplay://s/.
  const webOpenEpisode = async () => ({
    url: 'https://vod.example.com/open/ep.mp4', source: 'web',
    userAgent: 'ua-test', referer: 'https://hongguoduanju.com/',
  });

  // A: episode switching (player-resolve) must download whole files even when
  // the web page offers a plaintext URL.
  {
    const { handlers, downloads } = await load({ fetchPlayUrlSingle: webOpenEpisode });
    const fakeEvent = { sender: { isDestroyed: () => false, send() { } } };
    const result = await handlers['player-resolve'](fakeEvent, { vid: '111', seriesId: '222', quality: 'auto' });
    if (result.streaming === true || String(result.playUrl || '').startsWith('hkplay://s/'))
      fail(`player-resolve still streams web-open episodes: ${JSON.stringify(result)}`);
    else if (!String(result.playUrl || '').startsWith('file://'))
      fail(`player-resolve did not return a downloaded local file: ${JSON.stringify(result)}`);
    else if (!downloads.length)
      fail('player-resolve returned a local file without a whole-file download');
    else console.log('PASS player-resolve downloads web-open episodes');
  }

  // B: first-episode launch (bridge onPlay → openPlayer) must skip the streaming
  // session and hand the player window a downloaded local file.
  {
    let onPlay = null;
    const overridesBridge = { createAndroidBridge: options => { onPlay = options.onPlay; return { handle: async () => ({}), library: {} }; } };
    const fetchPlayUrlSingle = webOpenEpisode;
    const handlers = {}; const downloads = [];
    const app = new EventEmitter();
    Object.assign(app, {
      setName() {}, getVersion: () => 'test', getPath: () => root,
      commandLine: { appendSwitch() {} }, requestSingleInstanceLock: () => true, whenReady: () => Promise.resolve(),
    });
    class FakeWebContents {
      constructor() { this.sent = []; }
      on(name, handler) { if (name === 'did-finish-load') setImmediate(() => handler()); }
      send(channel, payload) { this.sent.push({ channel, payload }); }
      executeJavaScript() { return Promise.resolve('NO_RESOLVER'); }
      isDestroyed() { return false; }
    }
    const windows = [];
    const BrowserWindow = function () {
      const win = {
        webContents: new FakeWebContents(), handlers: {}, destroyed: false,
        loadFile() { }, loadURL() { }, on(name, handler) { this.handlers[name] = handler; }, show() { },
        close() { this.destroyed = true; this.handlers.closed && this.handlers.closed(); },
        isDestroyed() { return this.destroyed; },
      };
      windows.push(win); return win;
    };
    const electron = {
      app, shell: { openExternal: async () => {} }, nativeTheme: { themeSource: 'light' }, BrowserWindow,
      ipcMain: { handle: (name, fn) => handlers[name] = fn, on() { } },
      protocol: { registerSchemesAsPrivileged() {}, handle() { } },
    };
    const downloadEpisodeToFile = async (playInfo, file) => { downloads.push(file); fs.writeFileSync(file, Buffer.alloc(4096, 7)); };
    const req2 = createRequire(source);
    const context = vm.createContext({
      require: name => name === 'electron' ? electron
        : name === './src/native/android-bridge' ? overridesBridge
          : name === './src/native/hongguo' ? { fetchPlayUrlSingle }
            : name === './src/native/ffmpeg-run.cjs' ? { downloadEpisodeToFile }
              : req2(name),
      __dirname: path.dirname(source),
      console: { log() {}, warn() {}, error() {} },
      process, Buffer, URL, setTimeout, clearTimeout,
    });
    vm.runInContext(fs.readFileSync(source, 'utf8'), context, { filename: source });
    await new Promise(resolve => setImmediate(resolve));

    onPlay({ series: { seriesId: '222', title: 'T' }, episodes: [{ vid: '333', index: 1 }], startVid: '333' });
    let session = null;
    for (let i = 0; i < 100 && !session; i++) {
      await sleep(20);
      for (const win of windows) {
        const found = win.webContents.sent.find(item => item.channel === 'player-session');
        if (found) session = found.payload;
      }
    }
    if (!session) { fail('openPlayer never delivered a player-session to the player window'); }
    else if (session.playStreaming === true || String(session.startPlayUrl || '').startsWith('hkplay://s/'))
      fail(`openPlayer still launches the first episode as a streaming session: startPlayUrl=${session.startPlayUrl}`);
    else if (!String(session.startPlayUrl || '').startsWith('file://'))
      fail(`openPlayer did not hand the player a downloaded local file: ${session.startPlayUrl}`);
    else if (!downloads.length)
      fail('openPlayer returned a local file without a whole-file download');
    else console.log('PASS openPlayer launches the first episode from a whole-file download');
  }

  console.log(failures ? `${failures} desktop episode-download regressions` : 'PASS check-desktop-episode-download');
  process.exitCode = failures ? 1 : 0;
})().catch(error => { console.error(error); process.exit(1); });
