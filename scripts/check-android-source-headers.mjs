#!/usr/bin/env node
/** Actual JS source model + manual-download projection; no external HTTP requests. */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import {createRequire} from 'node:module';
import {fileURLToPath} from 'node:url';
let root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const args=process.argv.slice(2);
if(args.length){if(args.length!==2||args[0]!=='--source-root')throw new Error('USAGE --source-root PROJECT');root=path.resolve(args[1]);}
let passed=0,failed=0;
async function group(name,fn){try{await fn();passed++;console.log('PASS '+name);}catch(error){failed++;console.log('FAIL '+name+': '+error.message);}}
await group('App model and all backup candidates explicitly omit Referer',async()=>{
  const module={exports:{}},requests=[],require=createRequire(path.join(root,'src/native/app-source.js'));
  const vid='980001',primary='https://video.qznovelvod.com/synthetic-primary.mp4',backup='https://video.qznovelvod.com/synthetic-backup.mp4';
  vm.runInNewContext(fs.readFileSync(path.join(root,'src/native/app-source.js'),'utf8'),{module,exports:module.exports,Buffer,URL,AbortSignal,AbortController,setTimeout,clearTimeout,require(name){
    if(name!=='axios')return require(name);
    return async config=>{requests.push(config);return {status:200,headers:{},data:JSON.stringify(config.method==='POST'?{code:0,data:{[vid]:{video_model:{fallback_api:'https://vod.snssdk.com/synthetic-info'}}}}:{video_info:{data:{video_list:{one:{main_url:primary,backup_url:backup,codec_type:'h264',width:1920,height:1080,quality_desc:'1080P'}}}}})};};
  }});
  const result=await module.exports.fetchAppPlayUrl(vid,{quality:'1080p'});
  assert.equal(result.url,primary);assert.equal(result.referer,'');assert.equal(result.vid,vid);assert.equal(result.variants.length,2);
  for(const variant of result.variants){assert.equal(variant.referer,'');assert.equal(variant.source,'app');assert.equal(variant.quality,'1080p');}
  assert.equal(requests.length,2);
});
await group('manual download preserves no-Referer app/api while web keeps website header',()=>{
  const text=fs.readFileSync(path.join(root,'src/android/App.jsx'),'utf8');
  const statement=text.match(/const playInfo = \{ \.\.\.video,[^\n]+\};/);
  assert.ok(statement,'actual manual download projection exists');
  const project=vm.runInNewContext('(video,target)=>{'+statement[0]+';return playInfo;}');
  for(const source of ['app','api'])for(const referer of [undefined,'']){
    const info=project({source,url:'https://video.qznovelvod.com/synthetic.mp4',referer},{vid:'980001'});assert.equal(info.referer,'');assert.equal(info.source,source);assert.equal(info.vid,'980001');
  }
  for(const source of [undefined,'web'])assert.equal(project({source},{vid:'980001'}).referer,'https://hongguoduanju.com/');
  assert.equal(project({source:'app',referer:'https://novelquickapp.com/'},{vid:'980001'}).referer,'https://novelquickapp.com/');
});
await group('production network call uses the wire-tested optional header writer on every redirect',()=>{
  const text=fs.readFileSync(path.join(root,'android/app/src/main/java/io/github/jackotom/hongguo/android/NetworkGuard.java'),'utf8');
  assert.ok(text.includes('writeMediaRequest(socket.getOutputStream(),uri,userAgent,referer);'));
  assert.ok(text.includes('uri = mediaRedirect(uri, headers.get("location"));'));
  assert.ok(text.includes('InetAddress[] addresses = publicAddresses(uri, deadline);'), 'each redirect keeps DNS within the shared request deadline');
});
console.log(`SOURCE_HEADERS_${failed?'FAIL':'PASS'} groups=${passed} failed=${failed} externalRequests=0`);
process.exitCode=failed?1:0;
