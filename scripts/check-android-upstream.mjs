#!/usr/bin/env node
/** Verify imported upstream bytes and execute actual shared selectors with synthetic protocol replies. */
import fs from 'node:fs';import path from 'node:path';import crypto from 'node:crypto';import vm from 'node:vm';import {fileURLToPath} from 'node:url';import {spawnSync} from 'node:child_process';import {createRequire} from 'node:module';import assert from 'node:assert/strict';
const own=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');let root=own;
for(let i=2;i<process.argv.length;i++){if(process.argv[i]==='--root'&&process.argv[i+1])root=path.resolve(process.argv[++i]);else throw Error('Usage --root PROJECT');}
const expected=JSON.parse(fs.readFileSync(path.join(own,'scripts/android-playback-qa/upstream-1.80.json'),'utf8')),read=f=>fs.readFileSync(path.join(root,f),'utf8');let passed=0,failed=0,checks=0;
const check=(value,label)=>{checks++;assert.ok(value,label);};async function group(label,fn){try{await fn();passed++;console.log('PASS '+label);}catch(e){failed++;console.log('FAIL '+label+': '+e.message);}}
await group('complete upstream1.80 source bytes retained outside Android metadata overlay',()=>{
 const pkg=JSON.parse(read('package.json'));check(pkg.version===expected.version,'package1.80.0 imported');
 for(const [name,sha]of Object.entries(expected.files))check(crypto.createHash('sha256').update(fs.readFileSync(path.join(root,name))).digest('hex')===sha,'upstream byte mismatch: '+name);
});
await group('Android scripts and buffer coexist with upstream updater dependencies and lock',()=>{
 const pkg=JSON.parse(read('package.json')),lock=JSON.parse(read('package-lock.json'));for(const [name,value]of Object.entries(expected.upstreamScripts))check(pkg.scripts[name]===value,'upstream script preserved: '+name);
 for(const [name,value]of Object.entries(expected.upstreamDependencies))check(pkg.dependencies[name]===value,'upstream dependency preserved: '+name);
 for(const name of ['build:android:ui','build:android','check:android','check:android:playback'])check(typeof pkg.scripts[name]==='string','Android integration kept: '+name);
 check(pkg.dependencies.buffer==='6.0.3'&&lock.packages[''].dependencies.buffer==='6.0.3','buffer bridge remains pinned');check(lock.version===pkg.version&&lock.packages[''].version===pkg.version,'package and lock version equal');
 for(const [name,value]of Object.entries(pkg.dependencies))check(lock.packages[''].dependencies[name]===value&&lock.packages['node_modules/'+name],'lock includes exact root declaration for '+name);
});
const {transformSync}=createRequire(import.meta.url)('esbuild');
function protocolFixture(rows){
 const calls=[],writes=[],window={AndroidCrypto:{process(raw){const r=JSON.parse(raw);assert.equal(r.operation,'digest');return JSON.stringify({data:crypto.createHash(r.algorithm).update(Buffer.from(r.data,'base64')).digest('base64'),error:null});}}};
 const context=vm.createContext({window,URL,URLSearchParams,AbortSignal,AbortController,setTimeout,clearTimeout,crypto:{getRandomValues:b=>crypto.webcrypto.getRandomValues(b)},localStorage:{getItem:()=>null,setItem(...args){writes.push(args);}}});
 vm.runInContext(transformSync(read('src/android/generated/mac-app-source.mjs'),{format:'iife',globalName:'UpstreamProtocol',target:'chrome89'}).code,context);
 context.UpstreamProtocol.registerAppResolver(async(method,params)=>{calls.push({method,params:structuredClone(params)});if(method!=='appRequest')return {accepted:true};return {status:200,url:params.url,body:JSON.stringify(params.method==='POST'?{code:0,data:{'456':{video_model:JSON.stringify({fallback_api:'https://vod.snssdk.com/synthetic-upstream'})}}}:{video_info:{data:{video_list:rows}}})};});
 return {api:context.UpstreamProtocol,window,calls,writes};
}
await group('Android rebuild really selects measured pixels rather than misleading quality labels',async()=>{
 const generated=path.join(root,'src/android/generated/mac-app-source.mjs');
 // Rebuild each target, never silently test an old bundle produced before the upstream update.
 const build=spawnSync(process.execPath,[path.join(root,'scripts/build-android-mac-source.mjs')],{cwd:root,encoding:'utf8',timeout:60000});check(build.status===0&&fs.existsSync(generated),'target shared source bundle builds');
 const rows={misleading:{main_url:'https://cdn.qznovelvod.com/fixture-low.mp4',codec_type:'h264',quality_desc:'9000P',width:360,height:640},measured:{main_url:'https://cdn.qznovelvod.com/fixture-high.mp4',codec_type:'h264',quality_desc:'LD',width:1080,height:1920}};
 const auto=protocolFixture(rows),video=await auto.api.fetchAppPlayUrl('456',{quality:'auto'});check(video.url===rows.measured.main_url&&video.quality==='LD','auto same-codec ranks true1080 over cosmetic9000 label');
 const explicit=protocolFixture(rows),high=await explicit.api.fetchAppPlayUrl('456',{quality:'1080p'});check(high.requestedQuality==='1080p'&&high.url===rows.measured.main_url,'strict selected1080 contract retained');
 const missing=protocolFixture({only:rows.misleading});await missing.window.__androidAppResolve('fixture-missing','123','456','1080p');const error=missing.calls.find(c=>c.method==='appResolveResult');check(error?.params.errorCode==='QUALITY_UNAVAILABLE'&&!error.params.playInfo,'missing explicit quality stays typed for Android coordinator');
 for(const f of [auto,explicit,missing])check(f.writes.length===0,'media models never persisted in localStorage');
 check(auto.calls.filter(x=>x.method==='appRequest').length===2,'synthetic signed model request executed; not a static label check');
});
await group('actual desktop model selector also prioritizes decoded dimensions',()=>{
 const source=read('src/native/hongguo.js'),start=source.indexOf('function streamScore('),end=source.indexOf('\n/**\n * 获取单集播放直链',start);check(start>=0&&end>start,'actual selector methods found');const out={};
 vm.runInNewContext(source.slice(start,end)+'\nexports.parseModelVideo=parseModelVideo;',{URL,exports:out,normalizeVideoUrl:value=>value});
 const video=out.parseModelVideo(JSON.stringify({video_list:{low:{main_url:'https://fixture.invalid/low.mp4',video_meta:{codec_type:'h264',definition:'9000p',vwidth:360,vheight:640}},high:{main_url:'https://fixture.invalid/high.mp4',video_meta:{codec_type:'h264',definition:'LD',vwidth:1920,vheight:1080}}}}));check(video[0]==='https://fixture.invalid/high.mp4','real desktop selector no longer trusts false quality label above dimensions');
});
console.log(`ANDROID_UPSTREAM_${failed?'FAILED':'OK'} groupsPassed=${passed} groupsFailed=${failed} checks=${checks} importedVersion=${expected.version} networkRequests=0 deviceTests=not_run`);process.exitCode=failed?1:0;
