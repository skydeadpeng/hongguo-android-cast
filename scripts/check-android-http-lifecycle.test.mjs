#!/usr/bin/env node
/** Actual bridge module with synthetic Android messages only. */
import test from 'node:test';
import assert from 'node:assert/strict';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
let root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
for(let i=2;i<process.argv.length;i++){if(process.argv[i]==='--root'&&process.argv[i+1])root=path.resolve(process.argv[++i]);else throw Error('Usage: --root PROJECT');}
let loaded,handlers;
async function fixture(reply){
 const messages=[];globalThis.window={AndroidBridge:{postMessage(raw){const m=JSON.parse(raw);messages.push(m);reply?.(m,window);}},dispatchEvent(){}};
 if(!loaded){loaded=await import(pathToFileURL(path.join(root,'src/android/bridge.mjs')).href);handlers={response:window.__androidResponse,resume:window.__androidResume,account:window.__androidAccountChanged};}
 else {window.__androidResponse=handlers.response;window.__androidResume=handlers.resume;window.__androidAccountChanged=handlers.account;}
 return {...loaded,messages};
}
const url='https://hongguoduanju.com/player/123';
const ok=(m,w)=>{if(m.method==='httpGet')queueMicrotask(()=>w.__androidResponse({id:m.id,result:{status:200,body:'fixture'}}));};
test('page request preserves URL and forwards a bounded absolute queue deadline',async()=>{
 const f=await fixture(ok),before=Date.now();assert.equal(await f.requestHtml(url,{timeoutMs:1234}),'fixture');
 assert.equal(f.messages.length,1);assert.equal(f.messages[0].method,'httpGet');assert.equal(f.messages[0].params.url,url);
 assert.equal(f.messages[0].params.timeoutMs,1234);assert.ok(f.messages[0].params.deadlineMs>=before+1234&&f.messages[0].params.deadlineMs<=Date.now()+1234);
});
test('default page budget is 25 seconds and legacy native methods still work',async()=>{
 const f=await fixture((m,w)=>queueMicrotask(()=>w.__androidResponse({id:m.id,result:m.method==='getInfo'?{version:'fixture'}:{status:200,body:'default'}})));
 assert.equal(await f.requestHtml(url),'default');assert.equal(f.messages[0].params.timeoutMs,25000);assert.deepEqual(await f.nativeCall('getInfo'),{version:'fixture'});
});
test('invalid budgets reject without posting Android work',async()=>{
 const f=await fixture(ok);
 for(const timeoutMs of [0,-1,2.5,25001,'20',NaN])await assert.rejects(f.requestHtml(url,{timeoutMs}),e=>e.code==='INVALID_INPUT');
 for(const deadlineMs of [0,-1,2.5,'20',NaN])await assert.rejects(f.requestHtml(url,{deadlineMs}),e=>e.code==='INVALID_INPUT');
 assert.equal(f.messages.length,0);
});
test('expired caller deadline starts no Android work',async()=>{
 const f=await fixture(ok);await assert.rejects(f.requestHtml(url,{deadlineMs:Date.now()-1}),e=>e.code==='ETIMEDOUT');assert.equal(f.messages.length,0);
});
test('pre-cancelled signal starts no Android work',async()=>{
 const f=await fixture(ok),c=new AbortController();c.abort();await assert.rejects(f.requestHtml(url,{signal:c.signal}),e=>e.code==='ERR_CANCELED');assert.equal(f.messages.length,0);
});
test('in-flight abort cancels only its original HTTP id and ignores late callback',async()=>{
 const f=await fixture((m,w)=>{if(m.method==='httpGet')setTimeout(()=>w.__androidResponse({id:m.id,result:{status:200,body:'late'}}),25);});
 const c=new AbortController(),p=f.requestHtml(url,{timeoutMs:200,signal:c.signal});c.abort();await assert.rejects(p,e=>e.code==='ERR_CANCELED');
 assert.equal(f.messages.length,2);assert.equal(f.messages[1].method,'cancelHttp');assert.equal(f.messages[1].params.requestId,f.messages[0].id);
 await new Promise(r=>setTimeout(r,35));assert.equal(f.messages.length,2);
});
test('HTTP deadline cancels native work with typed timeout',async()=>{
 const f=await fixture((m,w)=>{if(m.method==='httpGet')setTimeout(()=>w.__androidResponse({id:m.id,result:{status:200,body:'late'}}),60);});
 await assert.rejects(f.requestHtml(url,{timeoutMs:15}),e=>e.code==='ETIMEDOUT');assert.equal(f.messages[1].method,'cancelHttp');
 await new Promise(r=>setTimeout(r,70));assert.equal(f.messages.length,2);
});
test('successful completion removes abort handler and timer',async()=>{
 const f=await fixture(ok),c=new AbortController();assert.equal(await f.requestHtml(url,{timeoutMs:35,signal:c.signal}),'fixture');c.abort();await new Promise(r=>setTimeout(r,45));assert.equal(f.messages.length,1);
});
test('HTTP status is structured rather than only embedded in a string',async()=>{
 const f=await fixture((m,w)=>queueMicrotask(()=>w.__androidResponse({id:m.id,result:{status:503,body:'unavailable'}})));
 await assert.rejects(f.requestHtml(url),e=>e.status===503&&e.code==='HTTP_ERROR');
});
test('native typed timeout and cancel survive the bridge response',async()=>{
 for(const code of ['ETIMEDOUT','ERR_CANCELED']){const f=await fixture((m,w)=>queueMicrotask(()=>w.__androidResponse({id:m.id,error:'fixture native error',errorCode:code})));await assert.rejects(f.requestHtml(url),e=>e.code===code);}
});
test('synchronous post failure clears request timer and listener',async()=>{
 const f=await fixture(()=>{throw Error('post-failed');}),c=new AbortController();await assert.rejects(f.requestHtml(url,{timeoutMs:15,signal:c.signal}),/post-failed/);c.abort();await new Promise(r=>setTimeout(r,25));assert.equal(f.messages.length,1);
});
test('many independently aborted/completed requests do not cross-cancel',async()=>{
 const f=await fixture((m,w)=>{if(m.method==='httpGet')setTimeout(()=>w.__androidResponse({id:m.id,result:{status:200,body:m.params.url}}),5);});
 const work=Array.from({length:25},(_,i)=>{const c=new AbortController(),p=f.requestHtml(url+'/'+i,{timeoutMs:100,signal:c.signal});if(i%2===0)c.abort();return p.then(value=>({value}),error=>({code:error.code}));});
 const result=await Promise.all(work);assert.equal(result.filter(r=>r.code==='ERR_CANCELED').length,13);assert.equal(result.filter(r=>r.value).length,12);
 const cancels=f.messages.filter(m=>m.method==='cancelHttp');assert.equal(new Set(cancels.map(m=>m.params.requestId)).size,13);
 await new Promise(r=>setTimeout(r,110));assert.equal(f.messages.filter(m=>m.method==='cancelHttp').length,13);
});

test('legacy preferences, method checks, response validation and events remain compatible',async()=>{
 const f=await fixture(ok);let saved='{}',writes=0,events=[];
 globalThis.localStorage={getItem(){return saved;},setItem(key,value){saved=value;writes++;}};
 assert.deepEqual(f.getPlaybackPreferences(),{autoplay:true,cleanupPlaybackCache:true,keepPreviousEpisodes:1});
 assert.equal(f.setPlaybackPreference('autoplay',false).autoplay,false);assert.equal(writes,1);
 assert.equal(f.setPlaybackPreference('unknown',true).autoplay,false);assert.equal(writes,1);
 assert.equal(f.setPlaybackPreference('cleanupPlaybackCache',false).cleanupPlaybackCache,false);
 saved='invalid';assert.equal(f.getPlaybackPreferences().autoplay,true);
 saved='null';assert.equal(f.getPlaybackPreferences().autoplay,true);
 globalThis.localStorage={getItem(){throw Error('storage');},setItem(){throw Error('storage');}};
 assert.equal(f.setPlaybackPreference('autoplay',false).autoplay,false);delete globalThis.localStorage;
 await assert.rejects(f.nativeCall('not-supported'),/未支持/);const bridge=window.AndroidBridge;delete window.AndroidBridge;await assert.rejects(f.nativeCall('getInfo'),/安卓/);window.AndroidBridge=bridge;
 for(const invalid of [null,{},1,{id:3},{id:'unknown'}])window.__androidResponse(invalid);
 window.dispatchEvent=e=>events.push(e.type);window.__androidResume();window.__androidAccountChanged();assert.deepEqual(events,['androidresume','androidaccountchanged']);
});
test('legacy timeout, native errors and non-Error post failures stay bounded',async()=>{
 let f=await fixture(()=>{});await assert.rejects(f.nativeCall('appRequest',{},5),e=>e.code==='ETIMEDOUT');
 await assert.rejects(f.nativeCall('getInfo',{},5),e=>!e.code);
 f=await fixture((m,w)=>queueMicrotask(()=>w.__androidResponse({id:m.id,error:'native rejected',errorCode:'unrecognised'})));await assert.rejects(f.nativeCall('getInfo'),e=>e.message==='native rejected'&&!e.code);
 f=await fixture(()=>{throw 'plain-post-error';});await assert.rejects(f.nativeCall('getInfo'),/plain-post-error/);
});
test('invalid page result and direct pre-cancel are rejected without dangling work',async()=>{
 const f=await fixture((m,w)=>queueMicrotask(()=>w.__androidResponse({id:m.id,result:{status:200,body:42}})));
 await assert.rejects(f.requestHtml(url),e=>e.code==='INVALID_RESPONSE');
 const c=new AbortController();c.abort();const before=f.messages.length;await assert.rejects(f.nativeCall('httpGet',{url},5,{signal:c.signal}),e=>e.code==='ERR_CANCELED');assert.equal(f.messages.length,before);
});
test('cancellation post failure and abort during listener setup do not leave a timer',async()=>{
 let f=await fixture((m,w)=>{if(m.method==='cancelHttp')throw Error('cancel-post-failed');if(m.method==='httpGet')setTimeout(()=>w.__androidResponse({id:m.id,result:{status:200,body:'late'}}),30);});
 const c=new AbortController(),p=f.requestHtml(url,{timeoutMs:20,signal:c.signal});c.abort();await assert.rejects(p,e=>e.code==='ERR_CANCELED');
 let aborted=false;const signal={get aborted(){return aborted;},addEventListener(){aborted=true;},removeEventListener(){}};
 const before=f.messages.length;await assert.rejects(f.nativeCall('httpGet',{url},20,{signal}),e=>e.code==='ERR_CANCELED');assert.equal(f.messages.length,before+1);
 await new Promise(r=>setTimeout(r,25));assert.equal(f.messages.length,before+1);
});
