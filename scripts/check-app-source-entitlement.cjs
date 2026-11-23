#!/usr/bin/env node
// Synthetic metadata only: actual shared module + freshly bundled Android adapter.
// No network, media, identity registration, or claim of authorized live playback.
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const crypto = require('node:crypto');
const assert = require('node:assert/strict');
const { build } = require('esbuild');
let root = path.resolve(__dirname, '..');
for (let i = 2; i < process.argv.length; i++) {
  if (process.argv[i] === '--root' && process.argv[i + 1]) root = path.resolve(process.argv[++i]);
  else throw new Error('Usage: node scripts/check-app-source-entitlement.cjs [--root PROJECT]');
}
const VID = '7694655440692448281';
const MEDIA = 'https://v3-reading.qznovelvod.com/synthetic-entitlement-fixture.mp4';
const FALLBACK = 'https://vod.snssdk.com/synthetic-entitlement-fixture';
const model = () => ({ video_list: [{ main_url: MEDIA, codec_type: 'h264', vwidth: 1080,
  vheight: 1920, encrypt_info: { encrypt: false } }] });
const post = (inline = true) => ({ code: 0, data: { [VID]: { video_model: inline ? model() : { fallback_api: FALLBACK } } } });
const get = () => ({ code: 0, video_info: { code: 0, data: model() } });
let checks = 0, passed = 0, failed = 0;
let completed = false;
process.once('beforeExit', () => {
  if (completed) return;
  console.error(`APP_ENTITLEMENT_INCOMPLETE groupsPassed=${passed} groupsFailed=${failed} checks=${checks} mainPending=true`);
  process.exitCode = 1;
});
const equal = (a, b) => { checks++; assert.deepEqual(a, b); };
async function group(label, fn) {
  try { await fn(); passed++; console.log('PASS ' + label); }
  catch (error) { failed++; console.error('FAIL ' + label + ': ' + error.message); }
}
async function main() {
  const adapter = path.join(root, 'src/android/mac-source');
  const bundle = await build({ entryPoints: [path.join(adapter, 'entry.mjs')], bundle: true,
    write: false, platform: 'browser', format: 'iife', globalName: 'Source', target: 'chrome89',
    inject: [path.join(adapter, 'buffer-runtime.mjs')],
    alias: { 'node:crypto': path.join(adapter, 'crypto-shim.mjs'), axios: path.join(adapter, 'axios-common.cjs') },
    define: { 'AbortSignal.timeout': 'androidAppTimeout' } });
  // 2026-11-23: before the legacy forms the ladder walks the V5 rungs, answered hollow here so
  // every plan below still exercises the legacy form it names. The browser bundle has no gzip
  // and sends only the V5 video_tab GET; the shared module (real zlib in this harness) also
  // sends the v5 player POST first.
  const HOLLOW = Symbol('hollow');
  const isV5Rung = (headers, url) => {
    const pathname = new URL(url).pathname;
    return pathname === '/novel/player/multi_video_model/player/v1'
      || pathname === '/reading/bookapi/video_tab/multi_video_model/v1/' && !headers['x-argus'];
  };
  function fixture(platform, plan) {
    const calls = [], timers = new Set();
    const provider = async () => {
      calls.push('provider');
      if (plan.provider) return plan.provider;
      throw Object.assign(new Error('synthetic provider timeout'), { code: 'ETIMEDOUT' });
    };
    const http = (method, url, headers) => {
      calls.push(method);
      // A plan may answer the leading V5 video_tab GET itself to test that rung's own verdict.
      if (new URL(url).pathname === '/reading/bookapi/video_tab/multi_video_model/v1/' && !(headers || {})['x-argus'] && plan.tab !== undefined) return plan.tab;
      return isV5Rung(headers || {}, url) ? HOLLOW : method === 'POST' ? plan.post : plan.get;
    };
    const common = { Buffer, URL, URLSearchParams, AbortController, AbortSignal,
      setTimeout(fn, ms) { const token = { fn, ms }; timers.add(token); return token; },
      clearTimeout(token) { timers.delete(token); } };
    let fetch;
    if (platform === 'shared') {
      const context = vm.createContext({ ...common, module: { exports: {} }, require(name) {
        if (name === 'axios') return async options => {
          const data = http(options.method || 'GET', options.url, options.headers);
          return data === HOLLOW ? { status: 200, headers: {}, data: '' } : { status: 200, headers: {}, data };
        };
        return name.startsWith('./') ? require(path.join(root, 'src/native', name)) : require(name);
      } });
      vm.runInContext(fs.readFileSync(path.join(root, 'src/native/app-source-registered.js'), 'utf8'), context);
      fetch = () => context.module.exports.fetchAppPlayUrl(VID, { registeredModelProvider: provider, signal: new AbortController().signal });
    } else {
      const window = { AndroidCrypto: { process(raw) {
        const request = JSON.parse(raw), bytes = Buffer.from(request.data, 'base64');
        let result;
        if (request.operation === 'digest') result = crypto.createHash(request.algorithm).update(bytes).digest();
        else {
          assert.ok(request.operation === 'encrypt' || request.operation === 'decrypt');
          assert.equal(request.algorithm, 'AES-128-CBC');
          const cipher = (request.operation === 'encrypt' ? crypto.createCipheriv : crypto.createDecipheriv)
            ('aes-128-cbc', Buffer.from(request.key, 'base64'), Buffer.from(request.iv, 'base64'));
          cipher.setAutoPadding(request.autoPadding);
          result = Buffer.concat([cipher.update(bytes), cipher.final()]);
        }
        return JSON.stringify({ data: result.toString('base64'), error: null });
      } } };
      const context = vm.createContext({ ...common, window, crypto: { getRandomValues: bytes => crypto.webcrypto.getRandomValues(bytes) } });
      vm.runInContext(bundle.outputFiles[0].text, context);
      context.Source.registerAppResolver(async (method, params) => {
        if (method === 'appRegisteredModel') return provider();
        assert.equal(method, 'appRequest');
        const body = http(params.method || 'GET', params.url, params.headers);
        return { status: 200, body: body === HOLLOW ? '' : JSON.stringify(body), headers: {}, url: params.url };
      });
      fetch = () => context.Source.fetchAppPlayUrl(VID);
    }
    return { calls, timers, fetch };
  }
  async function run(platform, plan, expectedCode, expectedCalls) {
    const f = fixture(platform, plan);
    if (expectedCode) {
      await assert.rejects(f.fetch(), error => error.code === expectedCode); checks++;
    } else equal((await f.fetch()).url, MEDIA);
    equal(f.calls, expectedCalls);
    equal(f.timers.size, 0);
  }
  for (const platform of ['shared', 'android']) {
    // Each legacy form is preceded by the V5 rungs this platform sends (see isV5Rung).
    const toLegacyPost = ['provider', ...(platform === 'shared' ? ['POST', 'GET'] : ['GET']), 'POST'];
    const toFallbackGet = [...toLegacyPost, 'GET'];
    await group(platform + ' multi response checks trial before discarding each envelope (object and string models)', async () => {
      for (const stringModel of [false, true]) for (const layer of ['response', 'entry', 'model']) {
        const response = post(), entry = response.data[VID];
        const target = layer === 'response' ? response : layer === 'entry' ? entry : entry.video_model;
        target.is_trial_video = true;
        if (stringModel) entry.video_model = JSON.stringify(entry.video_model);
        await run(platform, { post: response }, 'BUSINESS_REJECTED', toLegacyPost);
      }
    });
    await group(platform + ' explicit nested refusal cannot be shadowed by a zero success code', async () => {
      for (const layer of ['response', 'entry', 'model']) for (const key of ['Code', 'code', 'error_code']) {
        const response = post(), entry = response.data[VID];
        const target = layer === 'response' ? response : layer === 'entry' ? entry : entry.video_model;
        target.Code = 0; target.code = 0; target[key] = 101003;
        await run(platform, { post: response }, 'BUSINESS_REJECTED', toLegacyPost);
      }
    });
    await group(platform + ' trial and refusal block the next fallback GET', async () => {
      for (const marker of [{ is_trial_video: true }, { error_code: 101005 }]) {
        const response = post(false); Object.assign(response.data[VID], marker);
        await run(platform, { post: response, get: get() }, 'BUSINESS_REJECTED', toLegacyPost);
      }
    });
    await group(platform + ' fallback response envelopes retain trial and explicit refusal', async () => {
      for (const layer of ['outer', 'video_info', 'data']) for (const marker of [{ is_trial_video: true }, { error_code: 101007 }]) {
        const outer = get(); Object.assign(layer === 'outer' ? outer : layer === 'video_info' ? outer.video_info : outer.video_info.data, marker);
        await run(platform, { post: post(false), get: outer }, 'BUSINESS_REJECTED', toFallbackGet);
      }
    });
    await group(platform + ' registered wrapper and inner model refusal are terminal with no new HTTP', async () => {
      for (const layer of ['wrapper', 'model']) for (const marker of [{ is_trial_video: true }, { error_code: 101003 }]) {
        const reply = { vid: VID, video_model: model() }; Object.assign(layer === 'wrapper' ? reply : reply.video_model, marker);
        await run(platform, { provider: reply, post: post() }, 'BUSINESS_REJECTED', ['provider']);
      }
      const outer = get(); outer.video_info.data.is_trial_video = true;
      await run(platform, { provider: { vid: VID, video_model: { fallback_api: FALLBACK } }, get: outer }, 'BUSINESS_REJECTED', ['provider', 'GET']);
    });
    await group(platform + ' leading V5 video_tab GET keeps its own trial and refusal terminal', async () => {
      const tab = () => ({ code: 0, data: { video_model_data: { [VID]: { video_model: model() } } } });
      const toV5Tab = ['provider', ...(platform === 'shared' ? ['POST'] : []), 'GET'];
      for (const layer of ['response', 'entry', 'model']) for (const marker of [{ is_trial_video: true }, { error_code: 101013 }]) {
        const response = tab(), entry = response.data.video_model_data[VID];
        Object.assign(layer === 'response' ? response : layer === 'entry' ? entry : entry.video_model, marker);
        await run(platform, { tab: response, post: post() }, 'BUSINESS_REJECTED', toV5Tab);
      }
      // A clean V5 video_tab model is served as is; no legacy form is touched.
      await run(platform, { tab: tab(), post: post() }, null, toV5Tab);
    });
    await group(platform + ' a present nonboolean trial flag is invalid, never an entitlement grant', async () => {
      for (const value of [null, 0, 1, 'false', 'true', {}, []]) {
        const response = post(); response.data[VID].is_trial_video = value;
        await run(platform, { post: response }, 'APP_INVALID_RESPONSE', toLegacyPost);
      }
    });
    await group(platform + ' absent or explicit false trial and numeric/string zero codes preserve full-model paths', async () => {
      for (const inline of [true, false]) for (const explicit of [false, true]) {
        const response = post(inline), outer = get();
        if (explicit) for (const value of [response, response.data[VID], response.data[VID].video_model, outer, outer.video_info, outer.video_info.data])
          Object.assign(value, { is_trial_video: false, Code: 0, code: '0', error_code: 0 });
        await run(platform, { post: response, get: outer }, null, inline ? toLegacyPost : toFallbackGet);
      }
      await run(platform, { provider: { vid: VID, is_trial_video: false, video_model: { ...model(), is_trial_video: false } } }, null, ['provider']);
    });
  }
  console.log(`APP_ENTITLEMENT_${failed ? 'FAILED' : 'OK'} groupsPassed=${passed} groupsFailed=${failed} checks=${checks} actualSharedAndAndroid=true syntheticMetadataOnly=true networkRequests=0`);
  process.exitCode = failed ? 1 : 0;
  completed = true;
}
main().catch(error => { console.error(error); process.exitCode = 1; });
