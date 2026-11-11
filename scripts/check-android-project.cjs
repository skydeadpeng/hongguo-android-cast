const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');

const root = path.resolve(process.argv[2] || path.join(__dirname, '..'));
const read = relative => fs.readFileSync(path.join(root, relative), 'utf8');
const required = [
  'android/app/src/main/AndroidManifest.xml',
  'android/app/src/phone/AndroidManifest.xml',
  'android/app/src/tv/AndroidManifest.xml',
  'android/app/build.gradle',
  'src/android/App.jsx',
  'src/android/bridge.mjs',
  'src/android/source.mjs',
  'src/android/index.html',
  'vite.android.config.mjs',
];
for (const file of required) {
  if (!fs.existsSync(path.join(root, file))) {
    console.error(`ANDROID_PROJECT_MISSING ${file}`);
    process.exit(1);
  }
}
try {
  const gradle = read('android/app/build.gradle');
  assert.match(gradle, /phone\s*\{/);
  assert.match(gradle, /tv\s*\{/);
  assert.match(gradle, /minSdk\s+26/);
  const manifest = read('android/app/src/main/AndroidManifest.xml');
  assert.match(manifest, /android\.permission\.INTERNET/);
  assert.match(manifest, /usesCleartextTraffic="false"/);
  assert.match(read('android/app/src/tv/AndroidManifest.xml'), /LEANBACK_LAUNCHER/);
  assert.match(read('android/app/src/tv/AndroidManifest.xml'), /android\.hardware\.touchscreen/);
  assert.match(read('android/app/src/phone/AndroidManifest.xml'), /android\.intent\.category\.LAUNCHER/);
  assert.match(read('src/android/bridge.mjs'), /AndroidBridge/);
  for (const file of ['src/android/App.jsx', 'src/android/source.mjs', 'src/android/bridge.mjs']) {
    assert.doesNotMatch(read(file), /window\.electronAPI|require\(['"]electron['"]\)/, file);
  }
  const scripts = JSON.parse(read('package.json')).scripts;
  assert.ok(scripts['build:android'] && scripts['build:android:ui']);
  console.log('ANDROID_PROJECT_OK phone+tv native_bridge independent_source');
} catch (error) {
  console.error('ANDROID_PROJECT_INVALID ' + error.message);
  process.exit(1);
}
