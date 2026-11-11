#!/usr/bin/env node
/** Offline protocol checks. The offset fault is an explicit emulator-runtime test double. */
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { build } = require('esbuild');

const root = path.resolve(__dirname, '..');
const adapter = path.join(root, 'src/android/mac-source');
const source = fs.readFileSync(path.join(root, 'src/native/app-source-registered.js'), 'utf8');
const api = 'https://api5-normal-sinfonlineb.fqnovel.com/novel/player/multi_video_model/v1/';
const payload = JSON.stringify({ biz_param: { need_all_video_definition: true, video_platform: 1024 }, mixed_video_id_map: { '1004': ['1234567890123456789'] } });

function fixture(seed, timestamp, browser) {
  let call = 0, state = seed + 1;
  const randomBytes = size => {
    const bytes = Buffer.alloc(size);
    for (let offset = 0; offset < size;) {
      const block = crypto.createHash('sha256').update(`${seed}:${call++}`).digest();
      const length = Math.min(block.length, size - offset);
      block.copy(bytes, offset, 0, length); offset += length;
    }
    return bytes;
  };
  const math = Object.create(Math);
  math.random = () => { state = (Math.imul(state, 1664525) + 1013904223) >>> 0; return state / 0x100000000; };
  class Clock extends Date { static now() { return timestamp; } }
  const globals = { Date: Clock, Math: math, URL, AbortSignal, AbortController, setTimeout, clearTimeout };
  if (browser) {
    globals.window = { AndroidCrypto: { process(raw) {
      const request = JSON.parse(raw);
      assert.equal(request.operation, 'digest');
      return JSON.stringify({ data: crypto.createHash(request.algorithm).update(Buffer.from(request.data, 'base64')).digest('base64'), error: null });
    } } };
    globals.crypto = { getRandomValues(bytes) { bytes.set(randomBytes(bytes.length)); return bytes; } };
  } else {
    globals.Buffer = Buffer;
    globals.module = { exports: {} };
    globals.require = name => {
      if (name === 'node:crypto') return { ...crypto, randomBytes };
      if (name === './sm3' || name === './download-quality') return require(path.join(root, 'src/native', name));
      throw new Error('Unexpected dependency: ' + name);
    };
  }
  return vm.createContext(globals);
}
function comparable(result) {
  return { url: result.url, headers: JSON.parse(JSON.stringify(result.headers)), body: result.body.toString('hex') };
}

(async () => {
  const bundle = await build({
    stdin: { resolveDir: root, contents: `
      import { Buffer } from './src/android/mac-source/buffer-runtime.mjs';
      export { signedAppRequest } from './src/native/app-source-registered.js';
      export function emulateMissingOffsetFault() {
        for (const name of ['writeUInt32LE', 'writeUInt32BE', 'writeFloatLE']) {
          const original = Buffer.prototype[name];
          Buffer.prototype[name] = function(value, offset, ...rest) {
            // API30 x86_64 TCG/WebView83 was observed converting undefined >>> 0
            // to 2147483647. This deliberately reproduces only that missing-offset fault.
            return original.call(this, value, offset === undefined ? 2147483647 : offset, ...rest);
          };
        }
      }
    ` },
    bundle: true, write: false, platform: 'browser', format: 'iife', globalName: 'Signer',
    target: 'chrome67', minify: true, inject: [path.join(adapter, 'buffer-runtime.mjs')],
    alias: { 'node:crypto': path.join(adapter, 'crypto-shim.mjs'), axios: path.join(adapter, 'axios-common.cjs') },
    define: { 'AbortSignal.timeout': 'androidAppTimeout' },
  });
  let checks = 0;
  for (let seed = 0; seed < 8; seed++) for (let second = 0; second < 4; second++) {
    const timestamp = 1794393600000 + second * 1000;
    const desktop = fixture(seed, timestamp, false);
    vm.runInContext(source, desktop);
    const expected = comparable(desktop.module.exports.signedAppRequest(api, payload));
    for (const emulateFault of [false, true]) {
      const browser = fixture(seed, timestamp, true);
      vm.runInContext(bundle.outputFiles[0].text, browser);
      if (emulateFault) browser.Signer.emulateMissingOffsetFault();
      assert.deepEqual(comparable(browser.Signer.signedAppRequest(api, payload)), expected,
        `request bytes unchanged seed=${seed} second=${second} missingOffsetFault=${emulateFault}`);
      checks++;
    }
  }
  console.log(`ANDROID_SIGNER_OFFSETS_OK checks=${checks} networkRequests=0 browserAndNodeBytesEqual=true offsetFault=explicitTestDouble`);
})().catch(error => { console.error(error.stack); process.exitCode = 1; });
