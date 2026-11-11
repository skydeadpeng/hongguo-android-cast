#!/usr/bin/env node
// Run the actual target source through four independent offline startup gates.
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';

const own = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
let root = own;
for (let i = 2; i < process.argv.length; i++) {
  if (process.argv[i] === '--root' && process.argv[i + 1]) root = path.resolve(process.argv[++i]);
  else throw new Error('Usage: node scripts/check-android-tv-startup.mjs [--root PROJECT]');
}
const temp = fs.mkdtempSync(path.join(os.tmpdir(), 'hongguo-tv-startup-'));
let passed = 0, failed = 0;
try {
  const groups = [
    ['release configuration', process.execPath, [path.join(own, 'scripts/check-android-release.mjs'), '--root', root]],
    ['actual native startup and MENU lifecycle', 'bash', [path.join(own, 'android/tests/run-startup-native-tests.sh'), '--source-root', root]],
    ['built legacy WebView and committed React startup', process.execPath, [path.join(own, 'scripts/check-android-tv-webview.mjs'), '--root', root, '--output', path.join(temp, 'webview')]],
    ['actual old bridge response lifecycle', process.execPath, [path.join(own, 'scripts/check-android-bridge-lifecycle.mjs'), '--root', root]],
  ];
  for (const [name, command, args] of groups) {
    console.log(`STARTUP_GROUP ${name}`);
    const result = spawnSync(command, args, { cwd: own, encoding: 'utf8', timeout: 240000, maxBuffer: 20 * 1024 * 1024 });
    process.stdout.write(result.stdout || '');
    process.stdout.write(result.stderr || '');
    if (result.error) console.log(`GROUP_ERROR ${result.error.code || result.error.message}`);
    console.log(`GROUP_EXIT ${result.status ?? 1}`);
    if (result.status === 0) passed++; else failed++;
  }
} finally {
  fs.rmSync(temp, { recursive: true, force: true });
}
console.log(`ANDROID_TV_STARTUP_${failed ? 'FAILED' : 'OK'} groupsPassed=${passed} groupsFailed=${failed} networkRequests=0 deviceTests=not_run`);
process.exitCode = failed ? 1 : 0;
