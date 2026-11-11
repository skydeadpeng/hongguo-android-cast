#!/usr/bin/env node
// Actual control/session/media/UI diagnostic boundaries; no real receiver access.
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
const own = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
let root = own;
for (let i = 2; i < process.argv.length; i++) {
  if (process.argv[i] === '--root' && process.argv[i + 1]) root = path.resolve(process.argv[++i]);
  else throw Error('Usage: --root PROJECT');
}
let passed = 0, failed = 0;
for (const [label, file, extra] of [
  ['control action/HTTP/UPnP failure context and command receipts', 'run-dlna-control-diagnostics-tests.sh', []],
  ['failed startup flags, retained causes, timeout versus cancellation', 'run-dlna-session-tests.sh', ['--diagnostics-only']],
  ['actual byte server request/write counters without lease or HTTP changes', 'run-dlna-media-diagnostics-tests.sh', []],
  ['actual phone diagnostics read-only service data and explicit copy', 'run-dlna-ui-tests.sh', []],
]) {
  const result = spawnSync('bash', [path.join(own, 'android/tests', file), ...extra, '--source-root', root], {
    cwd: root, encoding: 'utf8', timeout: 180000, maxBuffer: 4 * 1024 * 1024,
  });
  process.stdout.write(result.stdout || ''); process.stdout.write(result.stderr || '');
  if (result.status === 0 && !result.error) { passed++; console.log('PASS ' + label); }
  else { failed++; console.log('FAIL ' + label + ' exit=' + result.status); }
}
console.log(`ANDROID_CAST_DIAGNOSTICS_${failed ? 'FAILED' : 'OK'} groupsPassed=${passed} groupsFailed=${failed} liveReceiverRequests=0 deviceTests=not_run`);
process.exitCode = failed ? 1 : 0;
