const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { createHash } = require('node:crypto');
const { test } = require('node:test');
const { build, SOURCE_IDS } = require('./build-android-source-modules.cjs');

const hash = data => createHash('sha256').update(data).digest('hex');
const original = path.resolve(__dirname, '../vendor/source-modules');

test('all Android source assets retain pinned bytes, grading, and runtime hashes', () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'android-source-bundle-'));
  try {
    const bundle = path.join(directory, 'vendor');
    const output = path.join(directory, 'assets');
    fs.cpSync(original, bundle, { recursive: true });
    const modules = build(output, bundle);
    const assets = path.join(output, 'source-modules');
    assert.deepEqual(modules.map(row => row.id), SOURCE_IDS);
    assert.equal(modules.filter(row => row.adult).length, 0);
    assert.deepEqual(JSON.parse(fs.readFileSync(path.join(assets, 'index.json'), 'utf8')).modules, modules);
    for (const entry of modules) {
      for (const name of ['main.js', 'manifest.json', 'runtime.js']) {
        const bytes = fs.readFileSync(path.join(assets, entry.id, name));
        assert.equal(hash(bytes), entry.sha256[name], `${entry.id}/${name}`);
        if (name !== 'runtime.js') assert.deepEqual(bytes, fs.readFileSync(path.join(original, entry.id, name)));
      }
    }
    const savedIndex = fs.readFileSync(path.join(assets, 'index.json'));
    const pinnedMain = fs.readFileSync(path.join(original, SOURCE_IDS.at(-1), 'main.js'));
    fs.appendFileSync(path.join(bundle, SOURCE_IDS.at(-1), 'main.js'), '\n// tampered');
    assert.throws(() => build(output, bundle), /SHA-256 mismatch/);
    fs.writeFileSync(path.join(bundle, SOURCE_IDS.at(-1), 'main.js'), pinnedMain);
    assert.deepEqual(fs.readFileSync(path.join(assets, 'index.json')), savedIndex);
    const indexFile = path.join(bundle, 'index.json');
    const index = JSON.parse(fs.readFileSync(indexFile, 'utf8'));
    index.modules[0].adult = !index.modules[0].adult;
    fs.writeFileSync(indexFile, JSON.stringify(index));
    assert.throws(() => build(output, bundle), /source manifest is invalid/);
    index.modules[0].id = '../unknown';
    fs.writeFileSync(indexFile, JSON.stringify(index));
    assert.throws(() => build(output, bundle), /source identity is invalid/);
    assert.deepEqual(fs.readFileSync(path.join(assets, 'index.json')), savedIndex);
  } finally {
    fs.rmSync(directory, { recursive: true, force: true });
  }
});
