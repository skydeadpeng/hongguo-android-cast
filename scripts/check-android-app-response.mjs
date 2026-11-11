#!/usr/bin/env node
/** Real shared source and Android adapter, synthetic replies only. */
import fs from 'node:fs';import path from 'node:path';import vm from 'node:vm';import crypto from 'node:crypto';import assert from 'node:assert/strict';
import {fileURLToPath,pathToFileURL} from 'node:url';import {createRequire} from 'node:module';
const own=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');let root=own;
for(let i=2;i<process.argv.length;i++){if(process.argv[i]==='--root'&&process.argv[i+1])root=path.resolve(process.argv[++i]);else throw Error('Usage --root PROJECT');}
const require=createRequire(import.meta.url),{build}=require('esbuild'),native=path.join(root,'src/native'),adapter=path.join(root,'src/android/mac-source');
const SID='7690112243203050521',VID='7690609146743245886',url='https://cdn.qznovelvod.com/fixture76.mp4';
const validPost={code:0,data:{[VID]:{video_model:JSON.stringify({fallback_api:'https://vod.snssdk.com/fixture-model'})}}};
const validGet={video_info:{data:{video_list:{one:{main_url:url,codec_type:'h264',width:1080,height:1920,quality_desc:'1080P'}}}}};
let checks=0,passed=0,failed=0;
const check=(ok,label)=>{checks++;assert.ok(ok,label);};const equal=(a,b)=>{checks++;assert.deepEqual(a,b);};
async function group(label,run){try{await run();passed++;console.log('PASS '+label);}catch(e){failed++;console.error('FAIL '+label+': '+e.message);}}
function row(request,stage,body,status=200){const post=request.method==='POST';return{status,headers:{'content-type':'application/json'},data:(post?'model_post':'fallback_get')===stage?body:JSON.stringify(post?validPost:validGet)};}
function desktop(stage,body,status=200){
 const calls=[],context={Buffer,URL,URLSearchParams,AbortSignal,module:{exports:{}},require:name=>name==='axios'?async request=>{calls.push(request);return row(request,stage,body,status);}:name.startsWith('./')?require(path.join(native,name)):require(name)};
 vm.runInNewContext(fs.readFileSync(path.join(native,'app-source.js'),'utf8'),context);return{api:context.module.exports,calls};
}
const bundle=await build({entryPoints:[path.join(adapter,'entry.mjs')],bundle:true,write:false,platform:'browser',format:'iife',globalName:'SharedSource',target:'chrome89',inject:[path.join(adapter,'buffer-runtime.mjs')],alias:{'node:crypto':path.join(adapter,'crypto-shim.mjs'),axios:path.join(adapter,'axios-common.cjs')},define:{'AbortSignal.timeout':'androidAppTimeout'}});
function browser(stage,body,status=200){
 const calls=[],window={AndroidCrypto:{process(raw){const r=JSON.parse(raw);assert.equal(r.operation,'digest');return JSON.stringify({data:crypto.createHash(r.algorithm).update(Buffer.from(r.data,'base64')).digest('base64'),error:null});}}};
 const context=vm.createContext({window,URL,URLSearchParams,AbortSignal,AbortController,setTimeout,clearTimeout,crypto:{getRandomValues:b=>crypto.webcrypto.getRandomValues(b)}});vm.runInContext(bundle.outputFiles[0].text,context);
 context.SharedSource.registerAppResolver(async(method,params)=>{calls.push({method,params});if(method==='appRegisteredModel')throw Object.assign(new Error('App 已登记设备的模型接口仍返回空数据'),{code:'APP_EMPTY_RESPONSE'});if(method!=='appRequest')return{accepted:true};const r=row(params,stage,body,status);return{status:r.status,headers:r.headers,body:r.data,url:params.url};});
 return{api:context.SharedSource,window,calls};
}
async function failure(api){try{await api.fetchAppPlayUrl(VID,{quality:'1080p'});}catch(e){return e;}throw Error('expected lookup failure');}
await group('HTTP200 empty and JSON null have a dedicated locally-created code at both stages',async()=>{
 for(const stage of ['model_post','fallback_get'])for(const body of ['',' \n\t ','null']){
  const f=desktop(stage,body),e=await failure(f.api);equal(e.code,'APP_EMPTY_RESPONSE');equal(e.responseStage,stage);equal(e.responseKind,body.trim()==='null'?'null':'empty');equal(e.status,200);equal(e.responseBytes,Buffer.byteLength(body));
  equal(f.calls.length,stage==='model_post'?1:2);check(!e.message.includes('http'),'no URL/token in user diagnostic');
 }
});
await group('HTML, malformed JSON, primitives and arrays remain terminal format errors',async()=>{
 for(const stage of ['model_post','fallback_get'])for(const body of ['<html>upstream unavailable</html>','{bad','false','17','"text"','[]']){
  const f=desktop(stage,body),e=await failure(f.api);equal(e.code,'APP_INVALID_RESPONSE');equal(e.responseStage,stage);equal(f.calls.length,stage==='model_post'?1:2);check(!['APP_EMPTY_RESPONSE','QUALITY_UNAVAILABLE'].includes(e.code),'not disguised as quality/network failure');
 }
});
await group('valid JSON business errors, exact VID mismatch and invalid fallback retain terminal contracts',async()=>{
 for(const raw of [{code:1,data:{}},{code:0,data:{}},{code:0,data:{'999':validPost.data[VID]}},{code:0,data:{[VID]:{video_model:'{broken'}}},{code:0,data:{[VID]:{video_model:JSON.stringify({fallback_api:'http://127.0.0.1/private'})}}},{errorCode:'APP_EMPTY_RESPONSE',data:{}}]){
  const f=desktop('model_post',JSON.stringify(raw)),e=await failure(f.api);check(!['APP_EMPTY_RESPONSE','APP_INVALID_RESPONSE'].includes(e.code),'remote fields and invalid model cannot acquire response retry classification');equal(f.calls.length,1);
 }
 for(const status of [401,403,404,408,429,503]){const e=await failure(desktop('model_post','',status).api);equal(e.status,status);check(e.code!=='APP_EMPTY_RESPONSE','HTTP failures are not 200-empty');}
});
await group('actual Android resolver forwards empty/format codes without adding another lookup loop',async()=>{
 for(const stage of ['model_post','fallback_get'])for(const body of ['','null','<html>not JSON</html>']){
  const f=browser(stage,body);await f.window.__androidAppResolve('fixture-76',SID,VID,'1080p');
  const result=f.calls.find(c=>c.method==='appResolveResult').params;equal(result.errorCode,body.startsWith('<')?'APP_INVALID_RESPONSE':'APP_EMPTY_RESPONSE');equal(result.vid,VID);equal(result.seriesId,SID);equal(result.requestedQuality,'1080p');check(!result.playInfo,'no fake playable result');equal(f.calls.filter(c=>c.method==='appRequest').length,stage==='model_post'?1:2);equal(f.calls.filter(c=>c.method==='appRegisteredModel').length,stage==='model_post'&&!body.startsWith('<')?1:0);
 }
});
await group('valid same-episode 1080p selection and request signing remain intact',async()=>{
 for(const make of [desktop,browser]){const f=make('unused','');const info=await f.api.fetchAppPlayUrl(VID,{quality:'1080p'});equal(info.vid,VID);equal(info.url,url);equal(info.quality,'1080p');equal(info.width,1080);equal(info.height,1920);
  const calls=f.calls.filter(x=>!x.method||x.method==='appRequest'||x.method==='POST'||x.method==='GET'),request=calls[0].params||calls[0];check(request.headers['x-gorgon']&&request.headers['x-medusa'],'fresh signed POST retained');
 }
});
console.log(`APP_RESPONSE_${failed?'FAILED':'OK'} groupsPassed=${passed} groupsFailed=${failed} checks=${checks} externalNetwork=0`);process.exitCode=failed?1:0;
