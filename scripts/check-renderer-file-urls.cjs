const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const url = require('node:url');
const { createRendererSecurity } = require('../src/native/renderer-security');

// The 8.3-style fixture directory ships with the Windows release material; when it is
// absent the real-file assertions are skipped instead of failing `npm test` forever.
const fixtureDir = path.join(process.cwd(), 'fixture~1', 'dist-react');
if (fs.existsSync(path.join(fixtureDir, 'index.html'))) {
  const entry = path.join(process.cwd(), 'fixture~1', 'dist-react', 'index.html');
  const security = createRendererSecurity(entry);
  const encoded = url.pathToFileURL(entry).href;
  assert.equal(security.isTrustedUrl(encoded.replace(/%7E/gi, '~')), true, 'Chromium and Node encode the same tilde path differently');
  assert.equal(security.isTrustedUrl(encoded.replace(/~|%7E/gi, '%7e')), true);
  assert.equal(security.isTrustedUrl(encoded + '?other=1'), false);
  assert.equal(security.isTrustedUrl(encoded.replace(/~|%7E/gi, '%257E')), false, 'literal percent filename is a different file');
  assert.equal(security.isTrustedUrl(encoded.replace('index.html', 'other.html')), false);
  assert.equal(security.isTrustedUrl(encoded.replace('index.html', '..%2findex.html')), false);
}

const moduleFixture = { exports: {} };
vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../src/native/renderer-security.js'), 'utf8'), {
  module: moduleFixture, URL, process: { platform: 'win32' },
  require: name => name === 'node:url' ? {
    // A faithful stand-in for Windows Node's URL helpers: slash normalization, drive
    // case through the WHATWG parser, and percent decoding on the way back to a path.
    pathToFileURL: value => new URL('file:///' + String(value).replace(/\\/g, '/')),
    fileURLToPath: value => {
      const parsed = value instanceof URL ? value : new URL(String(value));
      return decodeURIComponent(parsed.pathname).replace(/^\//, '').replace(/\//g, '\\');
    },
  } : require(name),
});
const windows = moduleFixture.exports.createRendererSecurity('c:\\Fixtures\\APP~1\\app.asar\\dist-react\\index.html');
assert.equal(windows.isTrustedUrl('file:///C:/Fixtures/APP~1/app.asar/dist-react/index.html'), true);
assert.equal(windows.isTrustedUrl('file:///C:/Fixtures/APP%7E1/app.asar/dist-react/index.html'), true);
assert.equal(windows.isTrustedUrl('file:///D:/Fixtures/APP~1/app.asar/dist-react/index.html'), false);
assert.equal(windows.isTrustedUrl('file:///C:/Fixtures/other/app.asar/dist-react/index.html'), false);
assert.equal(windows.isTrustedUrl('file:///C:/Fixtures/APP~1/app.asar/dist-react/index.html?other=1'), false);
console.log('PASS equivalent file URL encodings and Windows drives; other files, queries and encoded separators remain denied');
