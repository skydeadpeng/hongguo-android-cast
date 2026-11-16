#!/usr/bin/env node
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import crypto from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { pathToFileURL } from 'node:url';

const REPO = 'skydeadpeng/hongguo-android-cast';
const COMPAT_SIGNER = '3a883cc41a4ce34b462a8c12c0d289e6c985d3e2c0d6ecf2b516b5cc75e353ab';
export function createUpdateManifest({ device, badging, signature, bytes, sha256, filename, notes = '', abis = null }) {
  if (!['phone', 'tv', 'tvnative'].includes(device)) throw new Error('device must be phone, tv or tvnative');
  const identity = /package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'/.exec(badging);
  const expected = 'io.github.jackotom.hongguo.android' + (device === 'tv' ? '.tv' : device === 'tvnative' ? '.tvnative' : '');
  if (!identity || identity[1] !== expected || !Number.isSafeInteger(Number(identity[2])) || Number(identity[2]) <= 0 || !/^\d+\.\d+\.\d+$/.test(identity[3])) throw new Error('APK identity or version is invalid');
  if (/application-debuggable/.test(badging)) throw new Error('debug APK cannot be published as an update');
  const min = Number(/sdkVersion:'(\d+)'/.exec(badging)?.[1]);
  // tvNative keeps its own Android 6.0 envelope; the WebView flavors ship API26+.
  if (min !== (device === 'tvnative' ? 23 : 26)) throw new Error('unexpected minimum SDK');
  const signers = [...signature.matchAll(/Signer #\d+ certificate SHA-256 digest: ([a-f\d]{64})/ig)].map(match => match[1].toLowerCase());
  if (signers.length !== 1 || signers[0] !== COMPAT_SIGNER) throw new Error('APK is not signed by the existing compatible certificate');
  if (!Number.isSafeInteger(bytes) || bytes <= 0 || bytes > 100 * 1024 * 1024 || !/^[a-f\d]{64}$/.test(sha256)) throw new Error('invalid APK size or SHA256');
  const name = identity[3];
  const assetPrefix = device === 'tvnative' ? `HongguoNative-${name}-tvNative-release` : `Hongguo-${name}-${device}-release`;
  // tvNative 0.1.22+ ships one APK per ABI; the canonical name carries the ABI suffix.
  const abiNames = device === 'tvnative' ? ['', '-arm64-v8a', '-armeabi-v7a'] : [''];
  const canonical = [];
  for (const abi of abiNames) for (const quality of ['-compatible', '']) canonical.push(`${assetPrefix}${quality}${abi}.apk`);
  if (!canonical.includes(filename)) throw new Error('APK filename does not match its immutable release identity');
  if (typeof notes !== 'string' || notes.length > 8000) throw new Error('release notes are too long');
  const manifest = { schemaVersion: 1, channel: 'android-compatible', flavor: device, packageName: expected,
    versionCode: Number(identity[2]), versionName: name, minSdk: min,
    apkUrl: `https://github.com/${REPO}/releases/download/v${name}/${filename}`, size: bytes, sha256 };
  if (abis && Object.keys(abis).length) manifest.abis = abis;
  manifest.notes = notes;
  manifest.publishedAt = new Date().toISOString();
  return manifest;
}
function tool(executable, args) {
  const result = spawnSync(executable, args, { encoding: 'utf8', maxBuffer: 4 * 1024 * 1024 });
  if (result.error || result.status !== 0) throw new Error(`${path.basename(executable)} failed: ${result.error?.message || result.stderr || result.stdout}`);
  return result.stdout + result.stderr;
}
export function main(args = process.argv.slice(2)) {
  const opts = {};
  for (let i = 0; i < args.length; i++) {
    if (args[i] === '--help') { console.log('Usage: node scripts/generate-android-update.mjs --apk PATH --device phone|tv|tvnative --output PATH [--notes-file PATH] [--apk-armeabi PATH]'); return; }
    if (!['--apk', '--device', '--output', '--notes-file', '--apk-armeabi'].includes(args[i]) || !args[i + 1] || args[i + 1].startsWith('--')) throw new Error('invalid arguments; use --help');
    if (opts[args[i]]) throw new Error('duplicate argument');
    opts[args[i]] = args[++i];
  }
  if (!opts['--apk'] || !opts['--device'] || !opts['--output']) throw new Error('apk, device and output are required');
  if (opts['--apk-armeabi'] && opts['--device'] !== 'tvnative') throw new Error('--apk-armeabi only applies to the tvnative channel');
  const apk = path.resolve(opts['--apk']), output = path.resolve(opts['--output']);
  if (apk === output || fs.lstatSync(apk).isSymbolicLink() || !fs.statSync(apk).isFile()) throw new Error('APK must be a regular file and output must be separate');
  const sdk = process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT || path.join(os.homedir(), 'Library/Android/sdk');
  const tools = path.join(sdk, 'build-tools', '35.0.0');
  const inspect = file => {
    const before = fs.statSync(file);
    const badging = tool(path.join(tools, 'aapt'), ['dump', 'badging', file]);
    const signature = tool(path.join(tools, 'apksigner'), ['verify', '--verbose', '--print-certs', file]);
    const data = fs.readFileSync(file), after = fs.statSync(file);
    if (before.size !== after.size || before.mtimeMs !== after.mtimeMs || data.length !== after.size) throw new Error('APK changed during verification');
    return { badging, signature, bytes: data.length, sha256: crypto.createHash('sha256').update(data).digest('hex'), filename: path.basename(file), data };
  };
  const primary = inspect(apk);
  // tvNative 0.1.22+: --apk must be the arm64-v8a APK and --apk-armeabi its armeabi-v7a
  // twin; both must carry the same package identity so one manifest covers both ABIs.
  let abis = null;
  if (opts['--apk-armeabi']) {
    if (!primary.filename.endsWith('-arm64-v8a.apk')) throw new Error('tvnative multi-ABI manifests need --apk to be the arm64-v8a build');
    const secondary = inspect(path.resolve(opts['--apk-armeabi']));
    const identityOf = text => /package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'/.exec(text)?.slice(1, 3).join('/');
    if (identityOf(primary.badging) !== identityOf(secondary.badging)) throw new Error('ABI APKs do not share one package identity');
    const urlOf = name => `https://github.com/${REPO}/releases/download/v${/versionName='([^']+)'/.exec(primary.badging)?.[1]}/${name}`;
    abis = {
      'arm64-v8a': { apkUrl: urlOf(primary.filename), size: primary.bytes, sha256: primary.sha256 },
      'armeabi-v7a': { apkUrl: urlOf(secondary.filename), size: secondary.bytes, sha256: secondary.sha256 },
    };
  }
  const manifest = createUpdateManifest({ device: opts['--device'], badging: primary.badging, signature: primary.signature, bytes: primary.bytes,
    sha256: primary.sha256, filename: primary.filename,
    notes: opts['--notes-file'] ? fs.readFileSync(opts['--notes-file'], 'utf8') : '', abis });
  fs.mkdirSync(path.dirname(output), { recursive: true });
  fs.writeFileSync(output + '.tmp', JSON.stringify(manifest, null, 2) + '\n', { flag: 'w' });
  fs.renameSync(output + '.tmp', output);
  const reopened = JSON.parse(fs.readFileSync(output, 'utf8'));
  if (reopened.sha256 !== manifest.sha256) throw new Error('update manifest verification failed');
  console.log(`ANDROID_UPDATE_MANIFEST_OK device=${manifest.flavor} version=${manifest.versionName} code=${manifest.versionCode} output=${output}`);
}
if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  try { main(); } catch (error) { console.error(error.message); process.exitCode = 1; }
}
