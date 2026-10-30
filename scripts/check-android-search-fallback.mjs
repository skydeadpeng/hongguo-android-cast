#!/usr/bin/env node
import assert from 'node:assert/strict';
import path from 'node:path';
import fs from 'node:fs';
import {pathToFileURL,fileURLToPath} from 'node:url';
const own=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');let root=own;
for(let i=2;i<process.argv.length;i++){if(process.argv[i]==='--root'&&process.argv[i+1])root=path.resolve(process.argv[++i]);else throw Error('Usage --root PROJECT');}
const modulePath=path.join(root,'src/android/search-fallback.mjs');
if(!fs.existsSync(modulePath)){console.error('SEARCH_FALLBACK_FAILED module=missing checks=0');process.exit(1);}
const {createFallbackSearch,parseOfficialSearch}=await import(pathToFileURL(modulePath));
let checks=0,groups=0,failed=0;const eq=(a,b)=>{checks++;assert.deepEqual(a,b);};const ok=x=>{checks++;assert.ok(x);};
const test=async(name,run)=>{try{await run();groups++;console.log('PASS '+name);}catch(e){failed++;console.error('FAIL '+name+': '+e.stack);}};
const app=(id='11',more=false,offset=20)=>({code:0,search_tabs:[{tab_type:11,data:[{video_data:[{series_id:id,title:'离线剧'+id}]}],has_more:more,next_offset:offset,search_id:'s',passback:String(offset)}]});
const web=(id='12',page=1,more=false)=>JSON.stringify({searchList:[{video_data:{series_id:id,series_title:'官网剧'+id,episode_cnt:80,series_cover:'https://p3.byteimg.com/test.jpg'}}],pageNum:page,totalPages:more?3:page,totalCount:more?30:1,hasMore:more});
const rejectCode=async(fn,code)=>{checks++;await assert.rejects(Promise.resolve().then(fn),e=>e.code===code);};
const deferred=()=>{let resolve,reject;const promise=new Promise((a,b)=>{resolve=a;reject=b;});return{promise,resolve,reject};};
await test('first App failure falls back with exact bounded official URL and data shape',async()=>{
 let params,options,calls=0;const search=createFallbackSearch(async p=>{params=p;throw Error('App offline');},async(url,o)=>{calls++;options=o;eq(new URL(url).origin,'https://hongguoduanju.com');eq(new URL(url).pathname,'/api/search/result');eq(new URL(url).searchParams.get('aid'),'8662');eq(new URL(url).searchParams.get('query'),'中文 & 剧');eq(new URL(url).searchParams.get('page'),'1');return web();});
 const start=Date.now(),result=await search(' 中文 & 剧 ');eq(calls,1);eq(result.channel,'web');eq(result.items[0].seriesId,'12');eq(result.items[0].title,'官网剧12');ok(result.notice.includes('官网'));eq(result.totalKnown,true);eq(result.total,1);ok(params.timeoutMs<=12000&&params.timeoutMs>0);ok(params.deadlineMs>=start&&params.deadlineMs<=start+12050);ok(options.timeoutMs<=20000&&options.deadlineMs<=start+20050);ok(options.signal instanceof AbortSignal);
});
await test('App success and valid empty result do not issue official requests',async()=>{
 let webCalls=0;const search=createFallbackSearch(async()=>app(),()=>{webCalls++;throw Error('unexpected');});eq((await search('成功')).channel,'app');eq((await search('成功')).items[0].seriesId,'11');eq(webCalls,0);
 const empty=createFallbackSearch(async()=>({code:0,search_tabs:[{tab_type:11,data:[],has_more:false,next_offset:0}]}),()=>{webCalls++;});eq((await empty('空')).items,[]);eq(webCalls,0);
});
await test('App later-page failure never joins official page sequence',async()=>{
 let appCalls=0,webCalls=0;const search=createFallbackSearch(async()=>{appCalls++;if(appCalls===1)return app('11',true);throw Error('second page fail');},()=>{webCalls++;});await search('链');await assert.rejects(search('链',2),/second page fail/);eq(webCalls,0);eq((await search('链',1)).channel,'app');
});
await test('web pages retain fixed channel, deduplicate, cache and retry failed page',async()=>{
 let a=0,w=0,fail=true;const search=createFallbackSearch(async()=>{a++;throw Error('fail');},async url=>{w++;const p=Number(new URL(url).searchParams.get('page'));if(p===2&&fail){fail=false;throw Error('web2');}return p===1?web('12',1,true):JSON.stringify({searchList:[{keyword:'12',name:'duplicate',video_data:{}},{keyword:'13',name:'next',video_data:{}}],pageNum:2,hasMore:false,totalCount:2,totalPages:2});});
 eq((await search('网页')).hasMore,true);await assert.rejects(search('网页',2),/web2/);const next=await search('网页',2);eq(next.channel,'web');eq(next.items.map(x=>x.seriesId),['13']);eq(a,1);eq(w,3);eq((await search('网页',2)).items[0].seriesId,'13');eq(w,3);await rejectCode(()=>search('网页',3),'SEARCH_END');
});
await test('refresh resets channel and old late completion cannot replace new session',async()=>{
 const d=deferred();let calls=0;const search=createFallbackSearch(async()=>{calls++;return calls===1?d.promise:calls===2?app('22',true):app('23',false,40);},()=>web());const old=search('刷新');await new Promise(r=>setImmediate(r));const fresh=await search('刷新',1,{refresh:true});eq(fresh.items[0].seriesId,'22');d.resolve(app('11',true));await old.catch(()=>{});eq((await search('刷新')).items[0].seriesId,'22');eq((await search('刷新',2)).items[0].seriesId,'23');
});
await test('overlapping first requests are merged and query state is independent',async()=>{
 const d=deferred();let count=0;const search=createFallbackSearch(async()=>{count++;return d.promise;},()=>web());const x=search('甲'),y=search('甲');await new Promise(r=>setImmediate(r));eq(count,1);d.resolve(app());eq((await x).items,(await y).items);await search('乙');eq(count,2);
});
await test('session expiry, LRU bound and input failures are explicit without fallback',async()=>{
 let webCalls=0;const search=createFallbackSearch(async()=>app('11',true),()=>{webCalls++;return web();});await search('过期');const now=Date.now;try{Date.now=()=>now()+300001;await rejectCode(()=>search('过期',2),'SEARCH_EXPIRED');}finally{Date.now=now;}
 for(let i=0;i<9;i++)await search('q'+i);await rejectCode(()=>search('q0',2),'SEARCH_EXPIRED');
 for(const k of ['',null,'x'.repeat(101),'a\n'])await rejectCode(()=>search(k),'INVALID_INPUT');for(const p of [0,1.1,101,'1'])await rejectCode(()=>search('q',p),'INVALID_INPUT');await rejectCode(()=>search('q',2,{refresh:true}),'INVALID_INPUT');eq(webCalls,0);
});
await test('cancelled request never falls back and a cancelled follower does not cancel owner',async()=>{
 let calls=0;const pre=new AbortController();pre.abort();const search=createFallbackSearch(async()=>{calls++;return app();},()=>{throw Error('unexpected web');});await rejectCode(()=>search('取消',1,{signal:pre.signal}),'ERR_CANCELED');eq(calls,0);
 const d=deferred(),live=new AbortController();let webCalls=0;const pending=createFallbackSearch(()=>d.promise,()=>{webCalls++;return web();});const first=pending('同词');const second=pending('同词',1,{signal:live.signal});live.abort();await rejectCode(()=>second,'ERR_CANCELED');d.resolve(app());eq((await first).channel,'app');eq(webCalls,0);
 const c=new AbortController(),d2=deferred();const inFlight=createFallbackSearch(()=>d2.promise,()=>{webCalls++;return web();});const p=inFlight('取消中',1,{signal:c.signal});await new Promise(r=>setImmediate(r));c.abort();await rejectCode(()=>p,'ERR_CANCELED');d2.resolve(app());eq(webCalls,0);
});
await test('App invalid input/cancel errors do not trigger fallback',async()=>{
 for(const code of ['INVALID_INPUT','ERR_CANCELED']){let calls=0;const search=createFallbackSearch(async()=>{throw Object.assign(Error(code),{code});},()=>{calls++;return web();});await rejectCode(()=>search('类型'),code);eq(calls,0);}
});
await test('strict official schema rejects errors, bad pages, unsafe IDs and malformed lists',async()=>{
 const parse=(raw,p=1)=>parseOfficialSearch(raw,{page:p});
 for(const raw of ['', '<html>','null','[]','{}','{"code":"0","searchList":[]}','{"code":1,"searchList":[]}','{"searchList":{}}','{"searchList":[{"keyword":12,"name":"x"}]}','{"searchList":[{"keyword":"12","name":""}]}','{"searchList":[],"hasMore":"false"}','{"searchList":[],"pageNum":2}','{"searchList":[],"totalCount":null}']){checks++;assert.throws(()=>parse(raw));}
 const data=JSON.parse(web());data.searchList.push(data.searchList[0]);eq(parse(JSON.stringify(data)).items.length,1);
 const fallback=parse(JSON.stringify({searchList:[{keyword:'123456789012345678901234567890',name:'反向字段',video_data:{}}],hasMore:false}));eq(fallback.items[0].seriesId,'123456789012345678901234567890');
 for(const cover of ['http://p3.byteimg.com/x','https://127.0.0.1/x','https://u:p@p3.byteimg.com/x','https://p3.byteimg.com.attacker/x','https://evil.test/x']){const data=JSON.parse(web());data.searchList[0].video_data.series_cover=cover;eq(parse(JSON.stringify(data)).items[0].coverUrl,'');}
 const limit=parseOfficialSearch(JSON.stringify({searchList:[{keyword:'12',name:'末页'}],pageNum:50,hasMore:true,totalCount:1000,totalPages:100}),{page:50});eq(limit.hasMore,false);ok(limit.notice.includes('50'));eq(parse('{"searchList":[],"hasMore":false}').items,[]);
});
await test('web failures remain retryable and nonempty repeated page is rejected',async()=>{
 let webCalls=0;const search=createFallbackSearch(async()=>{throw Error('app');},async(url)=>{webCalls++;return web('12',Number(new URL(url).searchParams.get('page')),true);});await search('重复');await rejectCode(()=>search('重复',2),'SEARCH_CURSOR_STALLED');eq(webCalls,2);
 const schema=createFallbackSearch(async()=>{throw Error('app');},async()=>'{');await assert.rejects(schema('坏格式'));await assert.rejects(schema('坏格式'));
});
await test('real bounded wait falls back at App sub-budget and enforces shared total deadline',async()=>{
 const oldNow=Date.now,oldTimeout=globalThis.setTimeout;let offset=0;const waits=[];
 Date.now=()=>oldNow()+offset;globalThis.setTimeout=(fn,ms,...args)=>{waits.push(ms);return oldTimeout(()=>{offset+=ms;fn(...args);},0);};
 try {
  let webBudget;
  const search=createFallbackSearch(()=>new Promise(()=>{}),async(url,options)=>{webBudget=options.timeoutMs;return web();});
  eq((await search('等待备用')).channel,'web');ok(waits[0]<=12000);ok(webBudget>0&&webBudget<=8000);
  const fail=createFallbackSearch(()=>new Promise(()=>{}),()=>new Promise(()=>{}));await rejectCode(()=>fail('总超时'),'ETIMEDOUT');ok(waits.includes(8000)||waits.some(ms=>ms<=8000));
 }finally{Date.now=oldNow;globalThis.setTimeout=oldTimeout;}
});
await test('web cache page bound evicts whole older query chains, never newest',async()=>{
 const search=createFallbackSearch(async()=>{throw Error('offline');},async url=>{const u=new URL(url),p=Number(u.searchParams.get('page')),k=u.searchParams.get('query');return web(String((k==='A'?1000:k==='B'?2000:3000)+p),p,true);});
 for(const keyword of ['A','B','C'])for(let page=1;page<=50;page++)await search(keyword,page);
 await rejectCode(()=>search('A',2),'SEARCH_EXPIRED');eq((await search('C',50)).page,50);eq((await search('B',50)).page,50);
});
console.log(`SEARCH_FALLBACK_${failed?'FAILED':'OK'} groups=${groups} failed=${failed} checks=${checks} externalNetwork=0`);process.exitCode=failed?1:0;
