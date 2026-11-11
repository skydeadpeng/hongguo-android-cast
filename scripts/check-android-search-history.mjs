#!/usr/bin/env node
/** Offline production-store regression gate; browser interaction has its own UI report. */
import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {spawnSync} from 'node:child_process';
const ownRoot=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
let root=ownRoot;const args=process.argv.slice(2);
for(let i=0;i<args.length;i++){if(args[i]==='--root'&&args[i+1])root=path.resolve(args[++i]);else{console.error('USAGE check-android-search-history.mjs [--root PROJECT]');process.exit(64);}}
let passed=0,failed=0;
const methods=['getSearchHistory','addSearchHistory','removeSearchHistory','clearSearchHistory'];
const result=spawnSync('bash',[path.join(ownRoot,'android/tests/run-search-history-tests.sh'),'--source-root',root],{cwd:root,encoding:'utf8',timeout:120000,maxBuffer:2*1024*1024});
if(result.stdout)process.stdout.write(result.stdout);if(result.stderr)process.stdout.write(result.stderr);
if(result.status===0&&!result.error){passed++;console.log('PASS FUNCTION persistent search store, bounded ordering and commit failure');}else{failed++;console.log(`FAIL FUNCTION persistent search store exit=${result.status} ${result.error?.message||''}`);}
function group(name,run){try{run();passed++;console.log('PASS STATIC '+name);}catch(error){failed++;console.log('FAIL STATIC '+name+': '+error.message);}}
const read=name=>fs.readFileSync(path.join(root,name),'utf8');
group('Java and JavaScript RPC allowlists plus real native dispatcher',()=>{
 const native=read('android/app/src/main/java/io/github/jackotom/hongguo/android/NativeBridge.java'),bridge=read('src/android/bridge.mjs');
 const allowedJava=native.match(/Set<String> METHODS\s*=\s*new HashSet<>\(Arrays\.asList\(([^;]+)\)\);/)?.[1]||'';
 const allowedJs=bridge.match(/const METHODS\s*=\s*new Set\(\[([\s\S]*?)\]\)/)?.[1]||'';
 for(const method of methods)if(!allowedJava.includes(`"${method}"`)||!new RegExp(`['"]${method}['"]`).test(allowedJs)||!native.includes(`case "${method}"`))throw Error('missing actual RPC exposure: '+method);
});
group('real SearchPage exposes saved keywords and separate deletion controls',()=>{
 const app=read('src/android/App.jsx');
 for(const method of methods)if(!app.includes(method))throw Error('missing real SearchPage operation: '+method);
 if(!/最近搜索|搜索历史/.test(app)||!app.includes('runSearch'))throw Error('missing shared submitted-search/history UI path');
});
console.log(`ANDROID_SEARCH_HISTORY_${failed?'FAILED':'OK'} groupsPassed=${passed} groupsFailed=${failed} networkRequests=0 deviceTests=not_run`);
process.exitCode=failed?1:0;
