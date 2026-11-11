#!/usr/bin/env node
/** Offline quality gate; STATIC wiring checks are not device or live-source evidence. */
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import { createRequire } from 'node:module';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { spawnSync } from 'node:child_process';
let root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const args=process.argv.slice(2);
for(let i=0;i<args.length;i++){if(args[i]==='--root'&&args[i+1])root=path.resolve(args[++i]);else{console.error('USAGE node scripts/check-android-quality.mjs [--root PROJECT_DIRECTORY]');process.exit(2);}}
const read=relative=>fs.readFileSync(path.join(root,relative),'utf8');
const serialized=value=>JSON.parse(JSON.stringify(value));
const {transformSync}=createRequire(import.meta.url)('esbuild');
const java='android/app/src/main/java/io/github/jackotom/hongguo/android/';
const bundle=path.join(root,'src/android/generated/mac-app-source.mjs'),builder=path.join(root,'scripts/build-android-mac-source.mjs');
if(!fs.existsSync(bundle)&&fs.existsSync(builder)){const result=spawnSync(process.execPath,[builder],{cwd:root,encoding:'utf8',timeout:60000});console.log(`PREPARE targetBundleExit=${result.status}`);}
const sid='1234567890123456789',vid='2345678901234567890',media='https://v3-reading.qznovelvod.com/fixture-quality.mp4';
const model=(quality='1080p')=>({url:media,vid,source:'app',contentKey:null,codec:'h264',userAgent:'Fixture Mac Agent',referer:'https://novelquickapp.com/',width:quality==='720p'?720:1080,height:quality==='720p'?1280:1920,quality:'server label only',variants:[]});
let passed=0,failed=0,assertions=0;
const check=(condition,message)=>{assertions++;assert.ok(condition,message);};
async function group(name,run){try{await run();passed++;console.log(`PASS ${name}`);}catch(error){failed++;console.log(`FAIL ${name}: ${error.message}`);}}
let source;
await group('FUNCTION quality-specific source paths and coalesced caches',async()=>{
 source=await import(`${pathToFileURL(path.join(root,'src/android/source.mjs')).href}?qualityGate=${Date.now()}`);
 let pages=0;const requests=[];
 const client=source.createSource(async()=>{pages++;throw Error('synthetic HTTP404');},{fallback:async(requestedVid,options)=>{requests.push({requestedVid,...options});return model(options.quality);}});
 const [a,b,c]=await Promise.all([client.playback(sid,vid,{quality:'720p'}),client.playback(sid,vid,{quality:'720p'}),client.playback(sid,vid,{quality:'1080p'})]);
 check(pages===0,'explicit resolutions never use unmeasured web pages');
 check(a===b&&a!==c,'same requested quality coalesces; other quality stays isolated');
 check(requests.length===2&&requests.some(x=>x.quality==='720p')&&requests.some(x=>x.quality==='1080p'),'actual provider receives both quality values');
 check(a.requestedQuality==='720p'&&c.requestedQuality==='1080p','requested quality remains bound');
 await client.playback(sid,vid);check(pages===2&&requests.length===3,'auto still follows both web routes before App');
 await client.playback(sid,vid,{quality:'720p'});check(requests.length===3,'explicit cache never gets replaced by auto');
});
await group('FUNCTION explicit quality rejects unknown, low and cosmetic-label metadata',async()=>{
 for(const dimensions of [{},{width:719,height:1920},{width:1080,height:0},{width:720.5,height:1280}]){
  const client=source.createSource(async()=>{throw Error('web must not execute');},{fallback:async()=>({...model(),width:undefined,height:undefined,quality:'1080p',...dimensions})});
  let failure;try{await client.playback(sid,vid,{quality:'720p'});}catch(error){failure=error;}
  check(failure?.code==='QUALITY_UNAVAILABLE','real dimensions, not a quality label, determine availability');
 }
 let calls=0;const client=source.createSource(async()=>{calls++;return '';},{fallback:async()=>{calls++;return model();}});
 for(const quality of ['4k','1080P',1080,null]){let error;try{await client.playback(sid,vid,{quality});}catch(value){error=value;}check(error?.code==='INVALID_INPUT','quality enumeration is exact');}
 check(calls===0,'invalid values fail before any provider call');
});
function protocolFixture(videoList){
 const calls=[],writes=[];const window={AndroidCrypto:{process(input){const request=JSON.parse(input);assert.equal(request.operation,'digest');return JSON.stringify({data:crypto.createHash(request.algorithm).update(Buffer.from(request.data,'base64')).digest('base64'),error:null});}}};
 const context=vm.createContext({window,URL,URLSearchParams,AbortSignal,AbortController,setTimeout,clearTimeout,crypto:{getRandomValues(bytes){return crypto.webcrypto.getRandomValues(bytes);}},localStorage:{getItem(){return null;},setItem(...input){writes.push(input);}}});
 vm.runInContext(transformSync(read('src/android/generated/mac-app-source.mjs'),{format:'iife',globalName:'QualityProtocol',target:'chrome89'}).code,context);
 context.QualityProtocol.registerAppResolver(async(method,params)=>{calls.push({method,params:structuredClone(params)});if(method!=='appRequest')return {received:true};const body=params.method==='POST'?{code:0,data:{[vid]:{video_model:JSON.stringify({fallback_api:'https://vod.snssdk.com/fixture-quality'})}}}:{video_info:{data:{video_list:videoList}}};return {status:200,body:JSON.stringify(body),url:params.url};});
 return {source:context.QualityProtocol,window,calls,writes};
}
await group('FUNCTION bundled unmodified Mac protocol selects measured 720p and 1080p',async()=>{
 const rows={low:{main_url:media+'?row=low',codec_type:'h264',quality_desc:'1080P',width:360,height:640},portrait:{main_url:media+'?row=720',codec_type:'h264',quality_desc:'fixture',vwidth:720,vheight:1280},landscape:{main_url:media+'?row=1080',codec_type:'hevc',video_meta:{width:1920,height:1080}}};
 for(const quality of ['720p','1080p']){const fixture=protocolFixture(rows),info=await fixture.source.fetchAppPlayUrl(vid,{quality});check(info.requestedQuality===quality,'wrapper binds requested quality');check(Math.min(info.width,info.height)===parseInt(quality),'real Mac selection chooses a measured matching row');check(info.variants.every(row=>row.requestedQuality===quality&&Math.min(row.width,row.height)>=parseInt(quality)),'all candidates retain minimum dimensions');check(fixture.calls.filter(x=>x.method==='appRequest').length===2,'real signed POST and model GET execute against synthetic replies');}
});
await group('FUNCTION native callback binds quality, rejects unavailable/invalid and keeps legacy auto',async()=>{
 const fixture=protocolFixture({one:{main_url:media,codec_type:'h264',width:1080,height:1920}});
 await fixture.window.__androidAppResolve('quality-request',sid,vid,'1080p');let result=fixture.calls.filter(x=>x.method==='appResolveResult').at(-1)?.params;
 check(result?.requestedQuality==='1080p'&&result.playInfo?.requestedQuality==='1080p'&&result.vid===vid&&result.seriesId===sid,'callback binds all requested identities and quality');
 await fixture.window.__androidAppResolve('legacy-request',sid,vid);result=fixture.calls.filter(x=>x.method==='appResolveResult').at(-1)?.params;check(result?.requestedQuality==='auto'&&result.playInfo?.requestedQuality==='auto','old three-argument callback remains auto');
 const unavailable=protocolFixture({one:{main_url:media,codec_type:'h264',quality_desc:'1080P'}});await unavailable.window.__androidAppResolve('no-dimensions',sid,vid,'1080p');result=unavailable.calls.filter(x=>x.method==='appResolveResult').at(-1)?.params;check(typeof result?.error==='string'&&!result.playInfo,'unconfirmed resolution returns an actual error');check(result.errorCode==='QUALITY_UNAVAILABLE','only the explicit typed quality-missing result reaches native fallback');
 const invalid=protocolFixture({});await invalid.window.__androidAppResolve('invalid-quality',sid,vid,'4k');check(!invalid.calls.some(x=>x.method==='appRequest'),'invalid callback quality never sends API requests');check(fixture.writes.length===0&&unavailable.writes.length===0,'signed models and keys remain outside localStorage');
});
await group('FUNCTION Detail delegates play resolution to native and keeps downloads strictly measured',async()=>{
 const calls=[],providerCalls=[];let stateIndex=0;const detail={seriesId:sid,title:'合成清晰度剧',episodeCount:1,episodes:[{vid,index:1,webAvailable:false,availability:'unknown'}]};
 const react={createElement:(type,props,...children)=>({type,props:props||{},children}),useState:initial=>[stateIndex++===0?{busy:false,item:detail}:typeof initial==='function'?initial():initial,()=>{}],useEffect(){},useRef:current=>({current}),useCallback:fn=>fn};
 const support = {}; for (const name of ['./seasons.mjs', './season-playback.mjs', './official-series.mjs']) support[name] = await import(pathToFileURL(path.join(root, 'src/android', name.slice(2))).href);
 const exports={},code=transformSync(read('src/android/App.jsx')+'\nexports.__Detail = Detail;\n',{loader:'jsx',format:'cjs',target:'node22'}).code;
 vm.runInNewContext(code,{exports,module:{exports},console,require(name){if(support[name])return support[name];if(['./AccountPanel.jsx','./UpdatePanel.jsx'].includes(name))return {__esModule:true,default:()=>null};if(name==='react')return {__esModule:true,default:react,...react};if(name.includes('icons.jsx'))return new Proxy({__esModule:true},{get:(target,key)=>key in target?target[key]:()=>null});if(name==='./source.mjs')return source;if(name==='./generated/mac-app-source.mjs')return {fetchAppPlayUrl:async(requestedVid,options)=>{providerCalls.push({requestedVid,options});return {...model('1080p'),requestedQuality:'1080p'};}};if(name==='./bridge.mjs')return {requestHtml:async()=>{throw Error('unmeasured webpage must not run');},nativeCall:async(method,params)=>{calls.push({method,params});return method==='getPlaybackOptions'?{quality:'1080p',speed:1.5}:{started:true};},getPlaybackPreferences:()=>({autoplay:true,cleanupPlaybackCache:true,keepPreviousEpisodes:1}),setPlaybackPreference(){},getPlaybackOptions:()=>({quality:'1080p',speed:1.5}),setPlaybackOptions:value=>value};if(name.includes('LICENSE'))return '';throw Error(`unexpected UI import ${name}`);}});
 const tree=exports.__Detail({seed:detail,initialVid:vid,library:{favorites:[],history:[]},onBack(){},onLibrary(){},notify(){}}),elements=[];
 const walk=value=>{if(Array.isArray(value))value.forEach(walk);else if(value&&typeof value==='object'){if('type'in value)elements.push(value);walk(value.children);}};const text=value=>Array.isArray(value)?value.map(text).join(''):value&&typeof value==='object'?text(value.children):typeof value==='string'?value:'';walk(tree);
 for(const [method,label]of [['play','播放本集'],['download','下载本集']]){
  const button=elements.find(x=>x.type==='button'&&text(x.children).includes(label));check(button&&button.props.disabled!==true,`${method} control is enabled`);
  await button.props.onClick();const request=calls.find(x=>x.method===method);check(request?.params.quality==='1080p',`${method} RPC carries requested quality`);
  check(request.params.episode.vid===vid,`${method} identity remains exact`);
  if(method==='play'){
   check(request.params.resolveInPlayer===true&&!('url'in request.params)&&!('playInfo'in request.params),'play starts native resolution before any JS provider/preflight');
   check(request.params.speed===1.5&&request.params.episodes.length===1&&request.params.episodes[0].vid===vid,'play RPC preserves speed and full queue');
   check(providerCalls.length===0,'initial play does not depend on the JS playback provider');
  }else{
   check(request.params.playInfo?.requestedQuality==='1080p'&&request.params.playInfo.width===1080&&request.params.playInfo.height===1920,'download retains independently measured complete model');
   check(request.params.url===media&&!('resolveInPlayer'in request.params),'download remains the strict source contract');
  }
 }
 check(providerCalls.length===1&&providerCalls[0].options.quality==='1080p','only manual download resolves an exact-quality JS model');
});
await group('STATIC native plans, next-episode, refresh and cache identities preserve quality',()=>{
 const plans=read(java+'PlaybackSources.java'),quality=read(java+'MediaQuality.java'),native=read(java+'NativeBridge.java'),player=read(java+'PlayerActivity.java'),cache=read(java+'PlaybackCache.java');
 check(plans.includes('requestedQuality')&&plans.includes('width')&&plans.includes('height')&&plans.includes('MediaQuality.meets'),'plan validates measured per-candidate dimensions');
 check(/resolve\(String\s+sid\s*,\s*String\s+vid\s*,\s*String/.test(plans),'native next-episode resolver accepts quality');
 check(plans.includes('request.requestedQuality')&&plans.includes('plan.requestedQuality'),'pending callback and bounded model refresh retain quality');
 check(quality.includes('Math.min(width,height)')&&quality.includes('|quality='),'short edge and quality-specific cache identity are explicit');
 check(native.includes('requestedQuality')&&native.includes('MediaQuality.normalize'),'bridge validates quality independently');
 const allowedMethods=new Set([...native.match(/Set<String> METHODS\s*=\s*new HashSet<>\(Arrays\.asList\(([^;]+)\)\);/)?.[1]?.matchAll(/"([^"]+)"/g)||[]].map(match=>match[1]));
 for(const method of ['getPlaybackOptions','setPlaybackOptions'])check(allowedMethods.has(method)&&native.includes(`case "${method}"`),`real native RPC allowlist and dispatcher expose ${method}`);
 check(player.includes('quality')&&player.includes('PlaybackSources.resolve'),'player forwards its actual quality to continuous resolution');
 check(cache.includes('MediaQuality.cacheVid'),'playback cache differentiates requested qualities');
});
console.log(`ANDROID_QUALITY_${failed?'FAILED':'OK'} groupsPassed=${passed} groupsFailed=${failed} assertions=${assertions} networkRequests=0 deviceTests=not_run`);process.exitCode=failed?1:0;
