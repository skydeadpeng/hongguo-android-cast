#!/usr/bin/env node
/** Offline HTTP403 regressions. Live, bounded Mac evidence is recorded separately. */
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import assert from 'node:assert/strict';
import {fileURLToPath} from 'node:url';
import {spawnSync} from 'node:child_process';
const ownRoot=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
let root=ownRoot;const args=process.argv.slice(2);
for(let i=0;i<args.length;i++){if(args[i]==='--root'&&args[i+1])root=path.resolve(args[++i]);else{console.error('USAGE check-android-http403.mjs [--root PROJECT]');process.exit(64);}}
let passed=0,failed=0;
const result=spawnSync('bash',[path.join(ownRoot,'android/tests/run-http403-tests.sh'),'--source-root',root],{cwd:root,encoding:'utf8',timeout:150000,maxBuffer:2*1024*1024});
if(result.stdout)process.stdout.write(result.stdout);if(result.stderr)process.stdout.write(result.stderr);
if(result.status===0&&!result.error){passed++;console.log('PASS FUNCTION actual Android same-quality HTTP403 retries, refresh, bounds and atomic cache');}
else{failed++;console.log(`FAIL FUNCTION actual Android HTTP403 recovery exit=${result.status} ${result.error?.message||''}`);}
try{
  const main=fs.readFileSync(path.join(root,'main.js'),'utf8');
  const start=main.indexOf('async function requestMedia(playInfo, options = {}) {');
  const end=main.indexOf('\nfunction mediaTransferError',start);
  assert.ok(start>=0&&end>start,'actual original Mac requestMedia method exists');
  const url='https://cdn.qznovelvod.com/fixture%2Fpath.mp4?sig=A%2FB+C%2BD&token=x%26y%3Dz';
  for(const statuses of [[403,200],[403,403],[200]]){
    const calls=[],destroyed=[],failures=[];let cursor=0;
    const axios=Object.assign(async config=>{
      calls.push({url:config.url,headers:{...config.headers},method:config.method});
      const status=statuses[Math.min(cursor++,statuses.length-1)];
      if(status!==200){const error=Object.assign(new Error('fixture HTTP '+status),{response:{status,data:{destroy(){destroyed.push(status);}}}});throw error;}
      return {status:200,headers:{'content-type':'video/mp4'},data:{}};
    },{isCancel:()=>false});
    const exports={};
    vm.runInNewContext(main.slice(start,end)+'\nexports.requestMedia=requestMedia;',{
      URL,exports,axios,checkCustomAccess(){},hongguo:{VIDEO_REFERER:'https://novelquickapp.com/',markWebMediaFailure(info){failures.push(info.vid);}},
      require(){throw Error('App-only parity case must not use web/custom guard');},
      mediaTransferError(message){return Object.assign(new Error(message),{mediaRetryable:true});},
    });
    const info={url,vid:'980009',source:'app',userAgent:'Fixture Original Agent',referer:'https://novelquickapp.com/'};
    if(statuses.at(-1)===200){const reply=await exports.requestMedia(info);assert.equal(reply.status,200);}
    else{await assert.rejects(exports.requestMedia(info),error=>error.mediaStatus===403&&error.mediaRetryable===true);}
    assert.equal(calls.length,statuses.length);assert.equal(destroyed.length,statuses.filter(x=>x===403).length);
    for(const call of calls){assert.equal(call.url,url);assert.equal(call.method,'GET');assert.equal(call.headers['User-Agent'],info.userAgent);assert.equal(call.headers.Referer,info.referer);assert.equal(call.headers['Accept-Encoding'],'identity');assert.equal(call.headers.Range,undefined);}
    assert.equal(failures.length,statuses.at(-1)===403?1:0);
  }
  passed++;console.log('PASS FUNCTION unmodified Mac requestMedia retries App403 once with exact URL/headers and preserves final403');
}catch(error){failed++;console.log('FAIL FUNCTION original Mac parity: '+error.message);}
try{
  const source=fs.readFileSync(path.join(root,'android/app/src/main/java/io/github/jackotom/hongguo/android/PlaybackSources.java'),'utf8');
  for(const required of ['anyRefreshableTransfer|=refreshable','forbiddenRetryUsed','forbiddenFailureSeen?2:1','MAX_MEDIA_TRANSFERS = 16','transfers<MAX_MEDIA_TRANSFERS','plan.requestedQuality,false','媒体尝试','主地址未变化'])assert.ok(source.includes(required),'actual production branch missing: '+required);
  assert.ok(!source.includes('transientTransfersOnly&='),'candidate-local permanent failure must not globally veto recoverable403');
  passed++;console.log('PASS STATIC real retry/fresh-model path keeps global16, exact quality, cancellation and diagnostic counters');
}catch(error){failed++;console.log('FAIL STATIC actual production HTTP403 wiring: '+error.message);}
console.log(`ANDROID_HTTP403_${failed?'FAILED':'OK'} groupsPassed=${passed} groupsFailed=${failed} networkRequests=0 deviceTests=not_run`);
process.exitCode=failed?1:0;
