#!/usr/bin/env node
/** Offline real-production regression gate; live playback is a separate device record. */
import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {spawnSync} from 'node:child_process';
const ownRoot=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
let root=ownRoot;const args=process.argv.slice(2);
for(let i=0;i<args.length;i++){if(args[i]==='--root'&&args[i+1])root=path.resolve(args[++i]);else{console.error('USAGE check-android-continuous.mjs [--root PROJECT]');process.exit(64);}}
const java=path.join(root,'android/app/src/main/java/io/github/jackotom/hongguo/android');
const tests=[
 ['media HTTP200 error-body rejection', ['bash',path.join(ownRoot,'android/tests/run-media-response-tests.sh'),'--source-root',root]],
 ['source fresh recovery, quality and bounded transfers', ['bash',path.join(ownRoot,'android/tests/run-native-media-contract-tests.sh'),'--source-root',root,'--recovery-only']],
 ['typed App DNS/timeout transport classification', ['bash',path.join(ownRoot,'android/tests/run-app-lookup-transport-tests.sh'),'--source-root',root]],
 ['one fresh signed lookup, deadline and late-callback isolation', ['bash',path.join(ownRoot,'android/tests/run-native-media-contract-tests.sh'),'--source-root',root,'--lookup-only']],
 ['failed prefetch handoff and same-episode player recovery', ['bash',path.join(ownRoot,'android/tests/run-cache-foreground-recovery-tests.sh'),java]],
];
let passed=0,failed=0;
for(const [name,command]of tests){
 const result=spawnSync(command[0],command.slice(1),{cwd:root,encoding:'utf8',timeout:150000,maxBuffer:2*1024*1024});
 if(result.stdout)process.stdout.write(result.stdout);if(result.stderr)process.stdout.write(result.stderr);
 if(result.status===0&&!result.error){passed++;console.log(`PASS FUNCTION ${name}`);}else{failed++;console.log(`FAIL FUNCTION ${name} exit=${result.status} ${result.error?.message||''}`);}
}
// Wiring is labelled static; it does not replace the compiled production-function tests above.
try{
 const player=fs.readFileSync(path.join(java,'PlayerActivity.java'),'utf8'),net=fs.readFileSync(path.join(java,'NetworkGuard.java'),'utf8'),transport=fs.readFileSync(path.join(java,'AppTransport.java'),'utf8');
 if(!player.includes('cache.fetchForeground(')||!player.includes('recoveryBudget.take(')||!net.includes('streamMediaBody(input, output, headers, maxBytes, deadline)')||!transport.includes('NetworkGuard.streamBody('))throw Error('missing foreground/recovery or separated media/JSON consumer wiring');
 passed++;console.log('PASS STATIC real foreground path, bounded recovery and JSON/media separation');
}catch(error){failed++;console.log('FAIL STATIC '+error.message);}
console.log(`ANDROID_CONTINUOUS_${failed?'FAILED':'OK'} groupsPassed=${passed} groupsFailed=${failed} networkRequests=0 deviceTests=not_run`);
process.exitCode=failed?1:0;
