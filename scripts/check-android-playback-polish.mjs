#!/usr/bin/env node
/** Offline production regressions; does not start an emulator or make network requests. */
import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {spawnSync} from 'node:child_process';
import vm from 'node:vm';
const ownRoot=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
let root=ownRoot;
const args=process.argv.slice(2);
for(let i=0;i<args.length;i++){
  if(args[i]==='--root'&&args[i+1])root=path.resolve(args[++i]);
  else{console.error('USAGE check-android-playback-polish.mjs [--root PROJECT]');process.exit(64);}
}
let passed=0,failed=0;
for(const [name,runner] of [
  ['initial preflight and explicit-1080p preparation recovery','run-initial-playback-tests.sh'],
  ['bounded checked-CDN-address connection fallback','run-public-socket-tests.sh'],
  ['real player HUD timer, touch, TV and lifecycle','run-player-hud-tests.sh'],
]){
  const result=spawnSync('bash',[path.join(ownRoot,'android/tests',runner),'--source-root',root],{cwd:root,encoding:'utf8',timeout:150000,maxBuffer:2*1024*1024});
  if(result.stdout)process.stdout.write(result.stdout);
  if(result.stderr)process.stdout.write(result.stderr);
  if(result.status===0&&!result.error){passed++;console.log(`PASS FUNCTION ${name}`);}
  else{failed++;console.log(`FAIL FUNCTION ${name} exit=${result.status} ${result.error?.message||''}`);}
}
try{
  const delays=[],cleared=[],exports={},window={};
  window.AndroidBridge={postMessage(text){const request=JSON.parse(text);window.__androidResponse({id:request.id,result:{ok:true}});}};
  const source=fs.readFileSync(path.join(root,'src/android/bridge.mjs'),'utf8').replace(/^export\s+/gm,'');
  vm.runInNewContext(source+'\nexports.nativeCall=nativeCall;',{
    exports,window,setTimeout(_action,delay){delays.push(delay);return delays.length;},clearTimeout(id){cleared.push(id);},
  });
  for(const [method,expected] of [['play',75000],['appRequest',45000],['download',180000]]){
    const result=await exports.nativeCall(method,{});
    if(!result.ok||delays.at(-1)!==expected)throw Error(`${method} RPC timeout/response mismatch`);
  }
  await exports.nativeCall('play',{},1234);
  if(delays.at(-1)!==1234||cleared.length!==4)throw Error('explicit timeout or completed timer cleanup changed');
  passed++;console.log('PASS FUNCTION real JS RPC gives initial fallback bounded wait and clears completed timers');
}catch(error){failed++;console.log('FAIL FUNCTION real JS RPC wait: '+error.message);}
try{
  const dir=path.join(root,'android/app/src/main/java/io/github/jackotom/hongguo/android');
  const bridge=fs.readFileSync(path.join(dir,'NativeBridge.java'),'utf8');
  const network=fs.readFileSync(path.join(dir,'NetworkGuard.java'),'utf8');
  const plans=fs.readFileSync(path.join(dir,'PlaybackSources.java'),'utf8');
  const player=fs.readFileSync(path.join(dir,'PlayerActivity.java'),'utf8');
  const play=bridge.slice(bridge.indexOf('case "play":'),bridge.indexOf('case "download":'));
  if(!play.includes('PlaybackSources.registerInitial(info, series.getString("seriesId"), episode.getString("vid"), requestedQuality)'))throw Error('initial real play RPC does not use guarded fallback');
  const download=network.slice(network.indexOf('static void download(String value, File destination, long maxBytes, String userAgent'),network.indexOf('private static final class DeadlineInputStream'));
  if(!download.includes('connectPublic(addresses,deadline)')||!download.includes('setEndpointIdentificationAlgorithm("HTTPS")')||!download.includes('uri.getHost(), 443, true'))throw Error('media connection must use checked address list and retain domain TLS');
  const boundedRounds=plans.includes('round<2')||plans.includes('round<3')&&plans.includes('forbiddenFailureSeen?2:1')&&plans.includes('MAX_MEDIA_TRANSFERS = 16');
  if(!plans.includes('dnsTransferFailure?2100:500')||!boundedRounds||!plans.includes('variants.size()<7'))throw Error('same-quality DNS recovery must preserve existing bounds');
  if(!player.includes('PlayerHud')||!player.includes('setOnInfoListener'))throw Error('actual player must wire HUD and buffering');
  passed++;console.log('PASS STATIC actual play/download/HUD wiring with HTTPS and quality bounds retained');
}catch(error){failed++;console.log('FAIL STATIC actual production wiring: '+error.message);}
console.log(`ANDROID_PLAYBACK_POLISH_${failed?'FAILED':'OK'} groupsPassed=${passed} groupsFailed=${failed} networkRequests=0 deviceTests=not_run`);
process.exitCode=failed?1:0;
