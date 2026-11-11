#!/usr/bin/env node
'use strict';
// Consume actual Java fetch/project results, not hand-authored JS model copies.
// Built-ins and the production parser's local pure helpers only: no npm install or network.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const native = path.join(path.resolve(process.argv[2]), 'src/native');
const cases = JSON.parse(fs.readFileSync(process.argv[3], 'utf8'));
let networkCalls = 0;
const context = {
  Buffer, URL, AbortSignal, module: { exports: {} },
  require(name) {
    if (name === 'axios') return async () => { networkCalls++; throw new Error('Unexpected HTTP request'); };
    return require(name.startsWith('./') ? path.join(native, name) : name);
  },
};
vm.runInNewContext(fs.readFileSync(path.join(native, 'app-source-registered.js'), 'utf8'), context);
(async () => {
  assert.equal(cases.length, 14, 'every scalar and missing-flag fixture must cross runtimes');
  let clear = 0, rejected = 0, providerCalls = 0;
  for (const fixture of cases) {
    const request = () => context.module.exports.fetchAppPlayUrl(fixture.reply.vid, {
      registeredModelProvider: async vid => {
        providerCalls++;
        assert.equal(vid, fixture.reply.vid);
        return fixture.reply;
      },
    });
    if (fixture.clear) {
      const result = await request();
      assert.equal(result.url, 'https://cdn.qznovelvod.com/fixture.mp4');
      assert.equal(result.contentKey, null);
      assert.equal(result.vid, fixture.reply.vid);
      clear++;
    } else {
      await assert.rejects(request, error => error.code === 'INVALID_RESPONSE');
      rejected++;
    }
  }
  assert.equal(clear, 4);
  assert.equal(rejected, 10);
  assert.equal(providerCalls, cases.length);
  assert.equal(networkCalls, 0);
  console.log(`REGISTERED_PROJECTION_JS_OK clear=${clear} rejected=${rejected} actualJavaProjection=true actualJsParser=true networkRequests=0`);
})().catch(error => { console.error(error); process.exitCode = 1; });
