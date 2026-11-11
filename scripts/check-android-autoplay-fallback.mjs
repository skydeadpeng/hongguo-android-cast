#!/usr/bin/env node
/** 0.9 offline acceptance against the supplied production tree; no Android device or live media. */
import fs from 'node:fs';import os from 'node:os';import path from 'node:path';import {fileURLToPath} from 'node:url';import {spawnSync} from 'node:child_process';
const own=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');let root=own;
for(let i=2;i<process.argv.length;i++){if(process.argv[i]==='--root'&&process.argv[i+1])root=path.resolve(process.argv[++i]);else{console.error('USAGE node scripts/check-android-autoplay-fallback.mjs [--root PROJECT]');process.exit(64);}}
const output=fs.mkdtempSync(path.join(os.tmpdir(),'hongguo-autoplay-gate-'));let passed=0,failed=0;
try{for(const [label,exe,args]of [
 ['native bounded quality coordinator and actual player methods','bash',[path.join(own,'android/tests/run-adaptive-quality-tests.sh'),'--source-root',root]],
 ['strict typed source errors and actual native play dispatcher',process.execPath,[path.join(own,'scripts/check-android-adaptive-contract.mjs'),'--root',root]],
 ['phone click / TV confirm directly play exact episode',process.execPath,[path.join(own,'scripts/check-android-episode-oneclick.mjs'),'--root',root,'--output',path.join(output,'episode-ui')]],
 ]){const r=spawnSync(exe,args,{cwd:root,encoding:'utf8',timeout:120000,maxBuffer:3*1024*1024});process.stdout.write(r.stdout||'');process.stdout.write(r.stderr||'');if(r.status===0&&!r.error){passed++;console.log('PASS '+label);}else{failed++;console.log(`FAIL ${label} exit=${r.status} ${r.error?.message||''}`);}}
}finally{fs.rmSync(output,{recursive:true,force:true});}
console.log(`ANDROID_AUTOPLAY_FALLBACK_${failed?'FAILED':'OK'} groupsPassed=${passed} groupsFailed=${failed} networkRequests=0 deviceTests=not_run`);process.exitCode=failed?1:0;
