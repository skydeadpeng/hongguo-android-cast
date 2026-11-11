#!/usr/bin/env node
/** Full 0.10 acceptance on a supplied source tree; no device, upstream API or live video. */
import fs from 'node:fs';import path from 'node:path';import os from 'node:os';import {fileURLToPath} from 'node:url';import {spawnSync} from 'node:child_process';
const own=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');let root=own;
for(let i=2;i<process.argv.length;i++){if(process.argv[i]==='--root'&&process.argv[i+1])root=path.resolve(process.argv[++i]);else{console.error('Usage --root PROJECT');process.exit(64);}}
let passed=0,failed=0;const output=fs.mkdtempSync(path.join(os.tmpdir(),'hongguo-mobile-update-gate-'));
try {for(const [label,exe,args]of [
 ['upstream1.80 exact bytes and actual shared source selection',process.execPath,[path.join(own,'scripts/check-android-upstream.mjs'),'--root',root]],
 ['native retries stay preparing until final outcome without skipping episode','bash',[path.join(own,'android/tests/run-mobile-retry-state-tests.sh'),'--source-root',root]],
 ['phone IME, responsive native controls and non-reloading rotation','bash',[path.join(own,'android/tests/run-mobile-layout-tests.sh'),'--source-root',root]],
 ['real phone touch/layout/long series and retained TV navigation',process.execPath,[path.join(own,'scripts/check-android-mobile-ui.mjs'),'--root',root,'--output',path.join(output,'ui')]],
 ]){const p=spawnSync(exe,args,{cwd:root,encoding:'utf8',timeout:150000,maxBuffer:4*1024*1024});process.stdout.write(p.stdout||'');process.stdout.write(p.stderr||'');if(p.status===0&&!p.error){passed++;console.log('PASS '+label);}else{failed++;console.log(`FAIL ${label} exit=${p.status} ${p.error?.message||''}`);}}}
finally{fs.rmSync(output,{recursive:true,force:true});}
console.log(`ANDROID_MOBILE_UPDATE_${failed?'FAILED':'OK'} groupsPassed=${passed} groupsFailed=${failed} networkRequests=0 deviceTests=not_run`);process.exitCode=failed?1:0;
