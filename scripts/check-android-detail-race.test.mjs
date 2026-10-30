import test from 'node:test';
import assert from 'node:assert/strict';
import { resolve } from 'node:path';
import { pathToFileURL, fileURLToPath } from 'node:url';
const root=resolve(process.env.ANDROID_SOURCE_ROOT||fileURLToPath(new URL('..',import.meta.url)));
const {createSource,parseDetail}=await import(pathToFileURL(resolve(root,'src/android/source.mjs')).href);
let loadDetail,importError;
try {({loadDetail}=await import(pathToFileURL(resolve(root,'src/android/detail-loader.mjs')).href));}catch(e){importError=e;}
const sid='123456',card={series_id:sid,series_name:'离线详情',vid_list:['1001','1002'],accessible_episode_cnt:1};
const html=(id=sid,key='detail_page')=>'<script>window._ROUTER_DATA='+JSON.stringify({loaderData:{[key]:{series_id:id,seriesDetail:{...card,series_id:id}}}})+'</script>';
const need=()=>{assert.ifError(importError);return loadDetail;};
const parse=value=>parseDetail(value,sid);
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
test('detail_page is parsed with exact series identity',()=>{assert.equal(parseDetail(html(),sid).episodes[1].vid,'1002');assert.throws(()=>parseDetail(html('9999'),sid),e=>e.code==='IDENTITY_MISMATCH');});
test('source detail uses player and detail without changing playback',async()=>{
 const calls=[];const source=createSource(async(url,options)=>{calls.push({url,options});if(url.includes('/player/'))throw Error('one bad edge');return html();});
 const result=await source.detail(sid);assert.equal(result.seriesId,sid);assert.equal(calls.length,2);
 assert.ok(calls.some(x=>x.url.endsWith('/detail?series_id='+sid)));assert.ok(calls.every(x=>x.options.signal&&x.options.timeoutMs<=8000));
 assert.strictEqual(await source.detail(sid),result);assert.equal(calls.length,2);
});
test('first valid response wins and immediately aborts the hanging sibling',async()=>{
 const seen=[];const result=await need()(async(url,options)=>{seen.push(options);if(url.includes('/player/'))return new Promise(()=>{});return html();},sid,parse);
 assert.equal(result.episodes.length,2);assert.equal(seen.length,2);assert.equal(seen[0].signal.aborted,true);
});
test('fast wrong identity cannot defeat a slower valid route',async()=>{
 const result=await need()(async url=>{if(url.includes('/player/'))return html('7777');await sleep(3);return html();},sid,parse);
 assert.equal(result.seriesId,sid);
});
test('empty list does not win over complete identity-bound episodes',async()=>{
 const result=await need()(async url=>url.includes('/player/')?'empty':'complete',sid,v=>v==='empty'?{episodes:[]}:{seriesId:sid,episodes:[{vid:'1001'}]});
 assert.equal(result.episodes.length,1);
});
test('both failures settle once with a retryable detail error',async()=>{
 await assert.rejects(need()(async()=>{throw Error('fixture down');},sid,parse),e=>e.code==='DETAIL_UNAVAILABLE'&&/重新|重试/.test(e.message));
});
test('deadline bounds even an uncooperative transport and cancels both requests',async()=>{
 const seen=[];const start=Date.now();await assert.rejects(need()((_url,o)=>{seen.push(o);return new Promise(()=>{});},sid,parse,{timeoutMs:15}),e=>e.code==='ETIMEDOUT');
 assert.ok(Date.now()-start<300);assert.ok(seen.every(x=>x.signal.aborted));
});
test('pre-aborted caller starts no requests',async()=>{
 const controller=new AbortController();controller.abort();let calls=0;
 await assert.rejects(need()(async()=>{calls++;return html();},sid,parse,{signal:controller.signal}),e=>e.code==='ERR_CANCELED');assert.equal(calls,0);
});
test('caller cancellation settles and cancels both active requests',async()=>{
 const controller=new AbortController(),seen=[];
 const request=need()((_url,o)=>{seen.push(o);return new Promise(()=>{});},sid,parse,{signal:controller.signal});
 await sleep(1);controller.abort();await assert.rejects(request,e=>e.code==='ERR_CANCELED');assert.ok(seen.every(x=>x.signal.aborted));
});
test('shared absolute deadline is propagated unchanged to both routes',async()=>{
 const seen=[];await need()(async(_url,o)=>{seen.push(o);return html();},sid,parse,{timeoutMs:45});
 assert.equal(seen[0].deadlineMs,seen[1].deadlineMs);assert.equal(seen[0].timeoutMs,45);
});
test('invalid identity, transport and budgets reject before I/O',async()=>{
 let calls=0;const r=async()=>{calls++;return html();};
 for(const id of ['',12,'123x','1'.repeat(31)])await assert.rejects(need()(r,id,parse),e=>e.code==='INVALID_INPUT');
 for(const timeoutMs of [0,-1,1.2,12001,'5',NaN])await assert.rejects(need()(r,sid,parse,{timeoutMs}),e=>e.code==='INVALID_INPUT');
 await assert.rejects(need()(null,sid,parse),e=>e.code==='INVALID_INPUT');await assert.rejects(need()(r,sid,null),e=>e.code==='INVALID_INPUT');assert.equal(calls,0);
});
test('late success after timeout does not revive a failed lookup',async()=>{
 let release;const request=need()(()=>new Promise(r=>{release=r;}),sid,parse,{timeoutMs:10});
 await assert.rejects(request,e=>e.code==='ETIMEDOUT');release(html());await sleep(1);
});
test('rejected request is not cached and can recover on retry',async()=>{
 let bad=true,calls=0;const source=createSource(async()=>{calls++;if(bad)throw Error('fixture');return html();});
 await assert.rejects(source.detail(sid));bad=false;assert.equal((await source.detail(sid)).episodes.length,2);assert.equal(calls,4);
});
const catalog=(title,page=1)=>'<script>window._ROUTER_DATA='+JSON.stringify({loaderData:{category:{categoryRoute:{contentType:'real-drama'},recommendList:[{series_id:sid,series_name:title}],pagination:{pageNum:page,totalPages:2}}}})+'</script>';
test('manual catalog refresh bypasses the 30-second cache',async()=>{
 let calls=0;const source=createSource(async()=>catalog('版本'+(++calls)));
 assert.equal((await source.catalog()).items[0].title,'版本1');assert.equal((await source.catalog()).items[0].title,'版本1');
 assert.equal((await source.catalog('real-drama',1,{refresh:true})).items[0].title,'版本2');assert.equal(calls,2);
});
test('late pre-refresh catalog response cannot overwrite the fresh cache or pending request',async()=>{
 const requests=[];const source=createSource(()=>new Promise(resolve=>requests.push(resolve)));
 const old=source.catalog();await sleep(0);const fresh=source.catalog('real-drama',1,{refresh:true});await sleep(0);
 requests[0](catalog('旧片单'));await old;
 const joined=source.catalog();await sleep(0);assert.equal(requests.length,2);
 requests[1](catalog('新片单'));await fresh;assert.equal((await joined).items[0].title,'新片单');assert.equal((await source.catalog()).items[0].title,'新片单');
});
