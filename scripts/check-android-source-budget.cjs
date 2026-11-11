#!/usr/bin/env node
// Deterministic source-budget regressions. Real browser bundles, no sockets or media.
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const crypto = require('node:crypto');
const assert = require('node:assert/strict');
const { build } = require('esbuild');

let root = path.resolve(__dirname, '..');
for (let i = 2; i < process.argv.length; i++) {
  if (process.argv[i] === '--root' && process.argv[i + 1]) root = path.resolve(process.argv[++i]);
  else throw new Error('Usage: node scripts/check-android-source-budget.cjs [--root PROJECT]');
}
const adapter = path.join(root, 'src/android/mac-source');
const native = path.join(root, 'src/native');
const VID = '7694655440692448281', SID = '7694653385668037657';
const MEDIA = 'https://v3-reading.qznovelvod.com/synthetic-budget-fixture.mp4';
let checks = 0, passed = 0, failed = 0;
const check = (value, label) => { checks++; assert.ok(value, label); };
const equal = (actual, expected, label) => { checks++; assert.deepEqual(actual, expected, label); };
const error = code => Object.assign(new Error('synthetic provider failure'), { code });
const plainModel = () => ({ video_list: [{ main_url: MEDIA, codec_type: 'h264', vwidth: 1080,
  vheight: 1920, definition: '1080P', encrypt_info: { encrypt: false } }] });
const registeredModel = () => ({ vid: VID, video_model: plainModel() });
const legacyPost = () => ({ code: 0, data: { [VID]: { video_model: {
  fallback_api: 'https://vod.snssdk.com/synthetic-budget-fixture',
} } } });
const legacyGet = () => ({ code: 0, video_info: { data: plainModel() } });
async function flush() { for (let i = 0; i < 30; i++) await Promise.resolve(); }
async function rejects(promise, code) {
  let failure;
  try { await promise; } catch (caught) { failure = caught; }
  check(failure, 'operation must reject rather than publish a model');
  if (code !== undefined) equal(failure.code, code);
  return failure;
}
async function group(name, run) {
  try { await run(); passed++; console.log('PASS ' + name); }
  catch (failure) { failed++; console.error('FAIL ' + name + ': ' + failure.stack); }
}

function clock() {
  const start = 1793070000000;
  let now = start, sequence = 0;
  const timers = [];
  class Clock extends Date { static now() { return now; } }
  const setTimeout = (fn, ms) => {
    const timer = { fn, ms, at: now + ms, sequence: ++sequence, cleared: false, fired: false };
    timers.push(timer); return timer;
  };
  const clearTimeout = timer => { if (timer) timer.cleared = true; };
  return { start, timers, Date: Clock, setTimeout, clearTimeout, now: () => now,
    advance(ms) {
      const target = now + ms;
      for (;;) {
        const next = timers.filter(t => !t.cleared && !t.fired && t.at <= target)
          .sort((a, b) => a.at - b.at || a.sequence - b.sequence)[0];
        if (!next) break;
        now = Math.max(now, next.at); next.fired = true; next.fn();
      }
      now = target;
    },
    active: () => timers.filter(t => !t.cleared && !t.fired),
  };
}

async function main() {
  const sourceBundle = await build({ entryPoints: [path.join(adapter, 'entry.mjs')], bundle: true,
    write: false, platform: 'browser', format: 'iife', globalName: 'BudgetSource', target: 'chrome89',
    inject: [path.join(adapter, 'buffer-runtime.mjs')],
    alias: { 'node:crypto': path.join(adapter, 'crypto-shim.mjs'), axios: path.join(adapter, 'axios-common.cjs') },
    define: { 'AbortSignal.timeout': 'androidAppTimeout' } });
  const bridgeBundle = await build({ entryPoints: [path.join(root, 'src/android/bridge.mjs')], bundle: true,
    write: false, platform: 'browser', format: 'iife', globalName: 'BudgetBridge', target: 'chrome89' });

  function browser(handle) {
    const time = clock(), calls = [];
    let fixture;
    const window = {
      AndroidCrypto: { process(raw) {
        const request = JSON.parse(raw);
        assert.equal(request.operation, 'digest');
        return JSON.stringify({ data: crypto.createHash(request.algorithm)
          .update(Buffer.from(request.data, 'base64')).digest('base64'), error: null });
      } },
      AndroidBridge: { postMessage(raw) {
        const request = { ...JSON.parse(raw), at: time.now() }; calls.push(request);
        if (request.method === 'cancelHttp') return;
        if (request.method === 'appResolveResult') { fixture.reply(request, { accepted: true }); return; }
        if (handle) handle(request, fixture);
        else fixture.defaultReply(request);
      } },
      dispatchEvent() {},
    };
    const context = vm.createContext({ window, URL, URLSearchParams, AbortController, AbortSignal,
      Date: time.Date, setTimeout: time.setTimeout, clearTimeout: time.clearTimeout,
      Event: class {}, CustomEvent: class {}, crypto: { getRandomValues: bytes => crypto.webcrypto.getRandomValues(bytes) } });
    vm.runInContext(bridgeBundle.outputFiles[0].text, context);
    vm.runInContext(sourceBundle.outputFiles[0].text, context);
    fixture = { time, calls, window, source: context.BudgetSource, bridge: context.BudgetBridge,
      reply(request, result) { window.__androidResponse({ id: request.id, result }); },
      fail(request, code) { window.__androidResponse({ id: request.id, error: 'synthetic provider failure', errorCode: code }); },
      http(request, body, status = 200) {
        fixture.reply(request, { status, body: typeof body === 'string' ? body : JSON.stringify(body),
          headers: {}, url: request.params.url });
      },
      defaultReply(request) {
        if (request.method === 'appRegisteredModel') fixture.reply(request, registeredModel());
        else if (request.method === 'appRequest') fixture.http(request, request.params.method === 'POST' ? legacyPost() : legacyGet());
        else throw new Error('Unexpected RPC: ' + request.method);
      },
      models: () => calls.filter(c => c.method === 'appRegisteredModel'),
      httpCalls: () => calls.filter(c => c.method === 'appRequest'),
      results: () => calls.filter(c => c.method === 'appResolveResult'),
    };
    fixture.unregister = fixture.source.registerAppResolver(fixture.bridge.nativeCall);
    return fixture;
  }

  function shared(handle) {
    const time = clock(), calls = [], signals = [];
    let fixture;
    const context = { Buffer, URL, Date: time.Date, module: { exports: {} },
      AbortSignal: { timeout(ms) {
        const controller = new AbortController();
        signals.push({ at: time.now(), ms, signal: controller.signal });
        time.setTimeout(() => controller.abort(), ms); return controller.signal;
      } },
      require(name) {
        if (name === 'axios') return async config => {
          calls.push({ config, at: time.now() });
          if (config.signal.aborted) throw error('ERR_CANCELED');
          return handle ? handle(config, fixture) : fixture.response(config.method === 'POST' ? legacyPost() : legacyGet());
        };
        if (name === 'node:crypto') return crypto;
        if (name === './sm3' || name === './download-quality') return require(path.join(native, name));
        throw new Error('Unexpected dependency; networking prohibited: ' + name);
      },
    };
    vm.runInNewContext(fs.readFileSync(path.join(native, 'app-source-registered.js'), 'utf8'), context);
    fixture = { time, calls, signals, source: context.module.exports,
      response: data => ({ status: 200, data: JSON.stringify(data), headers: {} }) };
    return fixture;
  }

  await group('registered empty response after 7s leaves a fresh 6s HTTP fallback', async () => {
    const f = browser((request, self) => { if (request.method !== 'appRegisteredModel') self.defaultReply(request); });
    const pending = f.source.fetchAppPlayUrl(VID); const native = f.models()[0];
    equal(f.time.timers.map(t => t.ms).sort((a, b) => a - b), [20000, 28000]);
    f.time.advance(7000); f.fail(native, 'APP_EMPTY_RESPONSE');
    const result = await pending;
    equal(result.url, MEDIA); equal(result.contentKey, null);
    equal(f.httpCalls().map(c => c.params.timeoutMs), [6000, 6000]);
    equal(f.httpCalls().map(c => c.at - f.time.start), [7000, 7000]);
    equal(f.models().length, 1); equal(f.time.active().length, 0);
  });

  await group('real registered RPC timeout at 20s leaves the reserved 6s fallback', async () => {
    const f = browser((request, self) => { if (request.method !== 'appRegisteredModel') self.defaultReply(request); });
    const pending = f.source.fetchAppPlayUrl(VID); const native = f.models()[0];
    f.time.advance(20000);
    equal((await pending).url, MEDIA);
    equal(f.httpCalls().map(c => c.params.timeoutMs), [6000, 6000]);
    equal(f.httpCalls()[0].at - f.time.start, 20000);
    equal(f.calls.filter(c => c.method === 'cancelHttp').map(c => c.params.requestId), [native.id]);
    equal(f.models().length, 1); equal(f.time.active().length, 0);
  });

  await group('legacy POST and GET share one deadline instead of resetting 6s', async () => {
    const f = browser((request, self) => {
      if (request.method === 'appRegisteredModel') self.fail(request, 'APP_EMPTY_RESPONSE');
      else { if (request.params.method === 'POST') self.time.advance(2300); self.defaultReply(request); }
    });
    equal((await f.source.fetchAppPlayUrl(VID)).url, MEDIA);
    equal(f.httpCalls().map(c => c.params.timeoutMs), [6000, 3700]);
    equal(new Set(f.httpCalls().map(c => c.params.deadlineMs)).size, 1);
    equal(f.models().length, 1);
  });

  await group('exhausted legacy POST does not start a GET or another provider', async () => {
    const f = browser((request, self) => { if (request.method === 'appRegisteredModel') self.fail(request, 'APP_EMPTY_RESPONSE'); });
    const pending = f.source.fetchAppPlayUrl(VID); const result = pending.catch(e => e);
    await flush(); equal(f.httpCalls().length, 1); f.time.advance(6000);
    equal((await result).code, 'ETIMEDOUT'); equal(f.httpCalls().length, 1); equal(f.models().length, 1);
    equal(f.time.active().length, 0);
  });

  await group('empty legacy response never invokes an already attempted provider twice', async () => {
    const f = browser((request, self) => {
      if (request.method === 'appRegisteredModel') self.fail(request, 'APP_EMPTY_RESPONSE');
      else self.http(request, '');
    });
    await rejects(f.source.fetchAppPlayUrl(VID), 'APP_EMPTY_RESPONSE');
    equal(f.models().length, 1); equal(f.httpCalls().length, 1);
  });

  await group('business refusal HTTP denial cancellation invalid and unknown errors never fall back', async () => {
    for (const code of ['BUSINESS_REJECTED', 'HTTP_ERROR', 'INVALID_INPUT', 'ERR_CANCELED',
      'SECURITY_BLOCKED', 'APP_INVALID_RESPONSE', 'UNRECOGNIZED_FAILURE', undefined]) {
      const f = browser((request, self) => {
        if (request.method === 'appRegisteredModel') self.fail(request, code); else self.defaultReply(request);
      });
      const failure = await rejects(f.source.fetchAppPlayUrl(VID));
      equal(failure.code, code === 'UNRECOGNIZED_FAILURE' ? undefined : code);
      equal(f.models().length, 1); equal(f.httpCalls().length, 0);
    }
  });

  await group('wrong episode and malformed native model wrappers are terminal', async () => {
    for (const reply of [null, { ...registeredModel(), vid: '999' }, plainModel(),
      { vid: VID, video_model: [] }, { vid: VID, video_model: '{}' }, { vid: VID, video_model: {} }]) {
      const f = browser((request, self) => {
        if (request.method === 'appRegisteredModel') self.reply(request, reply); else self.defaultReply(request);
      });
      const failure = await rejects(f.source.fetchAppPlayUrl(VID));
      check(['APP_INVALID_RESPONSE', 'MISSING_MODEL'].includes(failure.code), 'typed invalid model');
      equal(f.models().length, 1); equal(f.httpCalls().length, 0);
    }
  });

  await group('legacy HTTP 401 and 403 remain terminal after a transient provider failure', async () => {
    for (const status of [401, 403]) {
      const f = browser((request, self) => {
        if (request.method === 'appRegisteredModel') self.fail(request, 'APP_EMPTY_RESPONSE');
        else self.http(request, { code: status }, status);
      });
      equal((await rejects(f.source.fetchAppPlayUrl(VID))).status, status);
      equal(f.models().length, 1); equal(f.httpCalls().length, 1);
    }
  });

  await group('successful 7s registered inline model does not make an HTTP request', async () => {
    const f = browser(() => {}); const pending = f.source.fetchAppPlayUrl(VID, { quality: '1080p' });
    f.time.advance(7000); f.reply(f.models()[0], registeredModel());
    const result = await pending; equal(result.url, MEDIA); equal(result.width, 1080);
    equal(f.httpCalls().length, 0); equal(f.time.active().length, 0);
  });

  await group('registered fallback GET gets its own first HTTP budget after a 7s provider wait', async () => {
    const f = browser((request, self) => { if (request.method !== 'appRegisteredModel') self.defaultReply(request); });
    const pending = f.source.fetchAppPlayUrl(VID); f.time.advance(7000);
    f.reply(f.models()[0], { vid: VID, video_model: { fallback_api: 'https://vod.snssdk.com/synthetic-budget-fixture' } });
    equal((await pending).url, MEDIA);
    equal(f.httpCalls().map(c => c.params.method), ['GET']); equal(f.httpCalls()[0].params.timeoutMs, 6000);
  });

  await group('28s total deadline during suspended callbacks prevents all fallback requests', async () => {
    const f = browser(() => {});
    const pending = f.window.__androidAppResolve('total-deadline', SID, VID);
    await flush(); equal(f.models().length, 1);
    // Process timers before microtasks, as when a suspended WebView resumes late.
    f.time.advance(28000); await pending;
    equal(f.httpCalls().length, 0); equal(f.results().length, 1);
    equal(f.results()[0].params.errorCode, 'ETIMEDOUT'); check(!f.results()[0].params.playInfo, 'no expired success');
    f.reply(f.models()[0], registeredModel()); await flush(); equal(f.results().length, 1);
    equal(f.time.active().length, 0);
  });

  await group('cancellation during registration closes exact RPC and ignores late success', async () => {
    const f = browser(() => {}); const controller = new AbortController();
    const pending = f.source.fetchAppPlayUrl(VID, { signal: controller.signal });
    const outcome = pending.catch(e => e), native = f.models()[0];
    controller.abort(); equal((await outcome).code, 'ERR_CANCELED');
    equal(f.calls.filter(c => c.method === 'cancelHttp').map(c => c.params.requestId), [native.id]);
    equal(f.httpCalls().length, 0); f.reply(native, registeredModel()); await flush();
    equal(f.httpCalls().length, 0); equal(f.time.active().length, 0);
  });

  await group('cancellation during fallback prevents GET and any result publication', async () => {
    const f = browser((request, self) => { if (request.method === 'appRegisteredModel') self.fail(request, 'APP_EMPTY_RESPONSE'); });
    const pending = f.window.__androidAppResolve('cancel-fallback', SID, VID);
    await flush(); equal(f.httpCalls().length, 1);
    f.window.__androidAppCancelResolve('cancel-fallback'); await pending;
    f.http(f.httpCalls()[0], legacyPost()); await flush();
    equal(f.httpCalls().length, 1); equal(f.results().length, 0); equal(f.models().length, 1);
  });

  await group('unregister cancels the model and removes only its own handlers', async () => {
    const f = browser(() => {}); const pending = f.window.__androidAppResolve('unregister', SID, VID);
    await flush(); const request = f.models()[0]; f.unregister(); await pending;
    equal(f.window.__androidAppResolve, undefined); equal(f.window.__androidAppCancelResolve, undefined);
    equal(f.calls.filter(c => c.method === 'cancelHttp').map(c => c.params.requestId), [request.id]);
    f.reply(request, registeredModel()); await flush(); equal(f.httpCalls().length, 0); equal(f.results().length, 0);
  });

  await group('real bridge preserves terminal codes and does not trust unknown codes', async () => {
    for (const code of ['BUSINESS_REJECTED', 'HTTP_ERROR', 'SECURITY_BLOCKED', 'INVALID_INPUT',
      'APP_INVALID_RESPONSE', 'APP_EMPTY_RESPONSE', 'ETIMEDOUT', 'ERR_CANCELED', 'REMOTE_SAYS_RETRY']) {
      const f = browser(() => {}); const pending = f.bridge.nativeCall('appRegisteredModel', { vid: VID }, 20000);
      const outcome = pending.catch(e => e); f.fail(f.models()[0], code);
      equal((await outcome).code, code === 'REMOTE_SAYS_RETRY' ? undefined : code);
      equal(f.time.active().length, 0);
    }
  });

  await group('no-provider desktop lookup retains its default 12s shared HTTP budget', async () => {
    const f = shared((config, self) => {
      if (config.method === 'POST') self.time.advance(3000);
      return self.response(config.method === 'POST' ? legacyPost() : legacyGet());
    });
    equal((await f.source.fetchAppPlayUrl(VID)).url, MEDIA);
    equal(f.calls.map(c => c.config.timeout), [12000, 9000]); equal(f.signals.length, 1);
    equal(f.signals[0].at, f.time.start); equal(f.signals[0].ms, 12000);
    equal(f.calls[0].config.signal, f.calls[1].config.signal);
  });

  await group('shared default signal is created only after provider wait and shares HTTP deadline', async () => {
    const f = shared((config, self) => {
      if (config.method === 'POST') self.time.advance(3000);
      return self.response(config.method === 'POST' ? legacyPost() : legacyGet());
    });
    let providerCalls = 0;
    const result = await f.source.fetchAppPlayUrl(VID, { registeredModelProvider: async () => {
      providerCalls++; equal(f.signals.length, 0); f.time.advance(7000); throw error('APP_EMPTY_RESPONSE');
    } });
    equal(result.url, MEDIA); equal(providerCalls, 1); equal(f.signals.length, 1);
    equal(f.signals[0].at - f.time.start, 7000); equal(f.signals[0].ms, 12000);
    equal(f.calls.map(c => c.config.timeout), [12000, 9000]);
    equal(f.calls[0].config.signal, f.calls[1].config.signal);
  });

  await group('registered inline shared response needs neither HTTP nor a timeout signal', async () => {
    const f = shared(); let providerCalls = 0;
    const result = await f.source.fetchAppPlayUrl(VID, { registeredModelProvider: async () => {
      providerCalls++; f.time.advance(7000); return registeredModel();
    } });
    equal(result.url, MEDIA); equal(providerCalls, 1); equal(f.calls.length, 0); equal(f.signals.length, 0);
  });

  await group('shared external signal is reused without creating another timeout signal', async () => {
    const f = shared(), controller = new AbortController();
    equal((await f.source.fetchAppPlayUrl(VID, { signal: controller.signal, lookupTimeoutMs: 6000 })).url, MEDIA);
    equal(f.calls.map(c => c.config.timeout), [6000, 6000]); equal(f.signals.length, 0);
    check(f.calls.every(c => c.config.signal === controller.signal), 'same caller signal across HTTP stages');
  });

  await group('shared own timeout still cancels an unresponsive no-provider request', async () => {
    const f = shared(config => new Promise((_, reject) => config.signal.addEventListener('abort', () => reject(error('ERR_CANCELED')), { once: true })));
    const pending = f.source.fetchAppPlayUrl(VID); const outcome = pending.catch(e => e);
    f.time.advance(12000); equal((await outcome).code, 'ETIMEDOUT'); equal(f.calls.length, 1);
    check(f.calls[0].config.signal.aborted, 'own HTTP timeout aborts the request');
  });

  await group('no-provider empty and business responses retain their original error contracts', async () => {
    for (const [body, code] of [['', 'APP_EMPTY_RESPONSE'], [JSON.stringify({ code: 401 }), 'BUSINESS_REJECTED']]) {
      const f = shared(() => ({ status: 200, data: body, headers: {} }));
      const failure = await rejects(f.source.fetchAppPlayUrl(VID), code);
      if (code === 'APP_EMPTY_RESPONSE') equal(failure.responseStage, 'model_post');
      equal(f.calls.length, 1);
    }
  });

  await group('invalid HTTP budget rejects before provider requests or timeout creation', async () => {
    for (const lookupTimeoutMs of [0, -1, 12001, 1.5, '6000']) {
      const f = shared(); let providerCalls = 0;
      await rejects(f.source.fetchAppPlayUrl(VID, { lookupTimeoutMs,
        registeredModelProvider: async () => { providerCalls++; return registeredModel(); } }), 'INVALID_INPUT');
      equal(providerCalls, 0); equal(f.calls.length, 0); equal(f.signals.length, 0);
    }
  });

  console.log(`ANDROID_SOURCE_BUDGET_${failed ? 'FAILED' : 'OK'} groupsPassed=${passed} groupsFailed=${failed} checks=${checks} actualBrowserEntryAndBridge=true fakeClock=true networkRequests=0`);
  process.exitCode = failed ? 1 : 0;
}
main().catch(failure => { console.error(failure.stack); process.exitCode = 1; });
