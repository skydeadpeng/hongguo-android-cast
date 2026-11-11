#!/usr/bin/env node
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';

const args = process.argv.slice(2);
let root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
let apk = null;
let device = "tv";
let compatibleWith = null;
for (let i = 0; i < args.length; i++) {
  if (args[i] === '--root') root = path.resolve(args[++i]);
  else if (args[i] === '--apk') apk = path.resolve(args[++i]);
  else if (args[i] === '--device') device = args[++i];
  else if (args[i] === '--compatible-with') compatibleWith = path.resolve(args[++i]);
  else throw new Error(`Unknown argument: ${args[i]}`);
}
let passed = 0;
let failed = 0;
function check(name, fn) {
  try { fn(); passed++; console.log(`PASS ${name}`); }
  catch (error) { failed++; console.error(`FAIL ${name}: ${error.message}`); }
}
function assert(value, message) { if (!value) throw new Error(message); }
function read(file) { return fs.readFileSync(path.join(root, file), 'utf8'); }
function run(command, argv, options = {}) {
  const result = spawnSync(command, argv, { encoding: 'utf8', ...options });
  if (result.error || result.status !== 0) throw new Error(`${path.basename(command)} exited ${result.status}: ${result.error?.message || result.stderr || result.stdout}`);
  return `${result.stdout}${result.stderr}`;
}
if (!['phone','tv'].includes(device)) throw new Error('Unknown device');
const gradle = read('android/app/build.gradle');
const version = /versionName\s+'([^']+)'/.exec(gradle)?.[1];
const code = /versionCode\s+(\d+)/.exec(gradle)?.[1];
const script = read('scripts/build-android.sh');
const pkg = JSON.parse(read('package.json'));
check(`release version is ${version}/code ${code}`, () => {
  assert(/^\d+\.\d+\.\d+$/.test(version) && Number(code) > 0, 'valid Android release version required');
});
check('release explicitly disables debuggability and shrinking', () => {
  assert(/release\s*\{[^}]*debuggable\s+false[^}]*minifyEnabled\s+false/s.test(gradle), 'release must explicitly use debuggable false and minifyEnabled false');
});
check('release signing is explicit; external credentials come from environment', () => {
  assert(gradle.includes("gradleProperty('hongguoReleaseSigning')") && gradle.includes("orElse('none')"), 'unsigned default is missing');
  for (const token of ['compatible', 'external', 'HONGGUO_RELEASE_KEYSTORE', 'HONGGUO_RELEASE_STORE_PASSWORD', 'HONGGUO_RELEASE_KEY_ALIAS', 'HONGGUO_RELEASE_KEY_PASSWORD']) assert(gradle.includes(token), `missing signing contract ${token}`);
  assert(!/storePassword\s+['"](?!android['"])/.test(gradle), 'non-default signing password literal detected');
});
check('dedicated TV release entries preserve the original debug build entry', () => {
  assert(pkg.scripts['build:android'] === 'bash scripts/build-android.sh', 'original Android debug entry changed');
  assert(pkg.scripts['build:android:tv:release-compatible'] === 'bash scripts/build-android.sh --tv-release-compatible', 'compatible entry missing');
  assert(pkg.scripts['build:android:tv:release'] === 'bash scripts/build-android.sh --tv-release', 'external signed entry missing');
  for (const token of ['assemblePhoneDebug assembleTvDebug', 'assembleTvRelease', '-PhongguoReleaseSigning=compatible', '-PhongguoReleaseSigning=external', 'SUFFIX=tv-release-compatible']) assert(script.includes(token) || token === 'SUFFIX=tv-release-compatible' && script.includes('SUFFIX="$DEVICE-release-compatible"'), `missing build contract ${token}`);
});
check('help is non-building and unknown arguments fail before any build', () => {
  assert(script.includes('--help') && script.includes('Unknown build mode'), 'non-building argument parser missing');
  const help = spawnSync('bash', [path.join(root, 'scripts/build-android.sh'), '--help'], { encoding: 'utf8' });
  assert(help.status === 0 && help.stdout.includes('--tv-release-compatible') && !help.stdout.includes('APK_OUTPUT='), 'help did not return without building');
  const unknown = spawnSync('bash', [path.join(root, 'scripts/build-android.sh'), '--not-a-build-mode'], { encoding: 'utf8' });
  assert(unknown.status === 2 && unknown.stderr.includes('Unknown build mode'), 'unknown argument was not rejected');
});
check('production release without external credentials fails before UI or Gradle build', () => {
  assert(script.includes('HONGGUO_RELEASE_KEYSTORE') && script.includes('--tv-release'), 'external signing preflight missing');
  const env = { ...process.env };
  for (const key of Object.keys(env)) if (key.startsWith('HONGGUO_RELEASE_')) delete env[key];
  const result = spawnSync('bash', [path.join(root, 'scripts/build-android.sh'), '--tv-release'], { encoding: 'utf8', env });
  assert(result.status === 2 && result.stderr.includes('HONGGUO_RELEASE_KEYSTORE') && !result.stdout.includes('build:android:ui'), 'external signing should require explicit credentials');
});
if (apk) {
  const sdk = process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT || path.join(os.homedir(), 'Library/Android/sdk');
  const tools = path.join(sdk, 'build-tools/35.0.0');
  const aapt = path.join(tools, 'aapt');
  const apksigner = path.join(tools, 'apksigner');
  let badging = '';
  let manifest = '';
  let cert = '';
  check('actual APK is signed and its signature verifies', () => {
    cert = run(apksigner, ['verify', '--verbose', '--print-certs', apk]);
    assert(cert.includes('Verifies') && /Signer #1 certificate SHA-256 digest: [a-f0-9]{64}/i.test(cert), 'APK signature evidence missing');
  });
  check(`actual ${device} APK has correct identity, version, launcher, and orientation`, () => {
    badging = run(aapt, ['dump', 'badging', apk]);
    manifest = run(aapt, ['dump', 'xmltree', apk, 'AndroidManifest.xml']);
    const id = 'io.github.jackotom.hongguo.android' + (device === 'tv' ? '.tv' : '');
    assert(badging.includes(`package: name='${id}' versionCode='${code}' versionName='${version}'`), 'unexpected app ID or version');
    if (device === 'tv') {
      assert(badging.includes("uses-feature: name='android.software.leanback'") && badging.includes('leanback-launchable-activity:'), 'TV leanback requirement/launcher missing');
      assert(/android:screenOrientation[^\n]*0x0/.test(manifest), 'TV landscape orientation missing');
    } else assert(badging.includes('launchable-activity:') && !badging.includes('leanback-launchable-activity:'), 'phone launcher changed');
    assert(badging.includes("sdkVersion:'26'"), 'minimum SDK changed unexpectedly');
  });
  check('actual APK manifest is non-debuggable', () => {
    if (!manifest) manifest = run(aapt, ['dump', 'xmltree', apk, 'AndroidManifest.xml']);
    assert(!/android:debuggable[^\n]*(?:0xffffffff|true)/i.test(manifest) && !badging.includes('application-debuggable'), 'release APK is debuggable');
  });
  if (compatibleWith) check('compatible APK preserves the previous install certificate', () => {
    const previous = run(apksigner, ['verify', '--print-certs', compatibleWith]);
    const digest = value => value.match(/Signer #1 certificate SHA-256 digest: ([a-f0-9]{64})/i)?.[1].toLowerCase();
    assert(digest(cert) && digest(cert) === digest(previous), 'certificate differs from the supplied previous APK');
    console.log(`CERTIFICATE_SHA256=${digest(cert)}`);
  });
}
console.log(`ANDROID_RELEASE_${failed ? 'FAILED' : 'OK'} passed=${passed} failed=${failed}${apk ? ' actualApk=verified' : ' actualApk=not-requested'}`);
process.exitCode = failed ? 1 : 0;
