import test from 'node:test';
import assert from 'node:assert/strict';
import { parseSeasonTitle, seasonCandidates, createSeasonLookup } from '../src/android/seasons.mjs';
const row = (id, title, episodeCount = 100) => ({ seriesId: String(id), title, episodeCount, coverUrl: '', tags: ['穿越'] });
const first = row('7671966197985856536', '持械入宋：第一季');
const second = row('7676706788724460568', '持械入宋：第二季');

test('explicit season parsing preserves punctuation and never assumes season one', () => {
  assert.equal(parseSeasonTitle(first.title).base, '持械入宋:');
  assert.equal(parseSeasonTitle(first.title).keyword, '持械入宋');
  assert.equal(parseSeasonTitle('持械入宋第四季').base, '持械入宋');
  for (const [name, n] of [['第一季',1],['第十季',10],['第十一季',11],['第二十季',20],['第九十九季',99],['第两季',2],['第０２季',2],['Season 2',2],['第2部',2]]) assert.equal(parseSeasonTitle('剧名：'+name).number,n,name);
  for (const name of ['剧名','剧名第二季幕后花絮','剧名2','剧名第一百季','剧名第零季','剧名第一二季','剧名第999季']) { assert.equal(parseSeasonTitle(name).explicit,false,name); assert.equal(parseSeasonTitle(name).number,null); }
  assert.equal(parseSeasonTitle(null),null);assert.equal(parseSeasonTitle('x'.repeat(201)),null);assert.equal(parseSeasonTitle('剧\u0000名'),null);
});
test('real-site-like separate seasonal IDs, duplicates and misleading names stay distinct', () => {
  const fourth = row('7680845974494776345','持械入宋：第四季');
  const fourthOther = row('9999999999999999999','持械入宋：第四季',80);
  const rows = seasonCandidates(first,[second,fourth,row('7692060436526533694','持械入宋第四季',219),fourthOther,second,row('7','持械入宋：第二部'),row('8','持械入宋：第二季花絮'),row('9','别的剧：第二季'),{seriesId:7676706788724460568,title:second.title}]);
  assert.deepEqual(rows.map(v=>v.seriesId),[first.seriesId,second.seriesId,fourth.seriesId,fourthOther.seriesId]);
  assert.equal(rows[3].episodeCount,80);assert.equal(rows[0].seasonLabel,'第 1 季');
  assert.ok(Object.isFrozen(rows)&&Object.isFrozen(rows[0])&&Object.isFrozen(rows[0].tags));
});
test('current season identity wins over conflicting search data and high current season survives cap', () => {
  const rows = seasonCandidates(first,[{...first,title:'注入不同标题',episodes:[{vid:'1'}],url:'https://invalid.test'}]);
  assert.equal(rows[0].title,first.title);assert.equal(rows[0].episodes,undefined);assert.equal(rows[0].url,undefined);
  const hundred = row('9999','长剧：第九十九季');
  const many = Array.from({length:70},(_,i)=>row(1000+i,'长剧：第'+(i+1)+'季'));
  const capped=seasonCandidates(hundred,many);assert.equal(capped.length,40);assert.ok(capped.some(r=>r.seriesId==='9999'));
});
test('unmarked titles only offer explicit exact-base candidates, never rename current to first season', () => {
  const seed=row('1','故事');const rows=seasonCandidates(seed,[row('2','故事第二季'),row('3','故事：第二季'),row('4','故事'),row('5','故事第二季番外')]);
  assert.deepEqual(rows.map(v=>v.seriesId),['1','2']);assert.equal(rows[0].seasonNumber,null);assert.equal(rows[0].seasonLabel,'当前剧集');
});
test('paged lookup has no implicit crawl or cursor reset and preserves per-ID records',async()=>{
  const calls=[];const lookup=createSeasonLookup(async(...args)=>{calls.push(args);return args[1]===1?{items:[second],hasMore:true}:{items:[second,row('3','持械入宋：第三季')],hasMore:false};});
  const p1=await lookup.load(first);assert.deepEqual(calls,[['持械入宋',1]]);assert.equal(p1.hasMore,true);
  const p2=await lookup.load(first,{page:2,previous:p1.items});assert.deepEqual(calls,[['持械入宋',1],['持械入宋',2]]);assert.equal(p2.items.length,3);assert.equal(p2.hasMore,false);assert.equal(p2.matching,'title');assert.match(p2.notice,/按标题匹配/);assert.equal(p2.items[1].seriesId,second.seriesId);
});
test('search failure stays a failure and retry does not poison previous page',async()=>{
  let fail=true;const previous=seasonCandidates(first,[second]);const lookup=createSeasonLookup(async()=>{if(fail)throw new Error('fixture offline');return{items:[],hasMore:false};});
  await assert.rejects(lookup.load(first,{page:2,previous}),/fixture offline/);assert.equal(previous.length,2);fail=false;
  assert.equal((await lookup.load(first,{page:2,previous})).items.length,2);
});
test('limits and malformed data rejected before requests; pagination unknown is not invented',async()=>{
  let calls=0;const lookup=createSeasonLookup(async()=>{calls++;return{items:[],totalPages:0};});
  for (const page of [0,11,-1,1.1,'1']) await assert.rejects(lookup.load(first,{page}));
  await assert.rejects(lookup.load({...first,seriesId:123}));await assert.rejects(lookup.load({...first,title:'剧'.repeat(101)}));assert.equal(calls,0);
  assert.equal((await lookup.load(first)).hasMore,false);assert.equal(calls,1);
  await assert.rejects(createSeasonLookup(async()=>({items:'bad'})).load(first));
  const limited=await createSeasonLookup(async()=>({items:[],hasMore:true})).load(first,{page:10});assert.equal(limited.hasMore,false);assert.match(limited.notice,/上限/);
});
test('cross-family previous rows excluded and explicit unit remains separate',async()=>{
  const lookup=createSeasonLookup(async()=>({items:[],hasMore:false}));const r=await lookup.load(first,{page:2,previous:[row('1','其他：第二季'),row('2','持械入宋：第二部'),second]});assert.deepEqual(r.items.map(x=>x.seriesId),[first.seriesId,second.seriesId]);
});
test('edge inputs keep metadata bounded and malformed directories fail explicitly', async()=>{
  assert.throws(()=>createSeasonLookup(null),/搜索函数/);
  assert.throws(()=>seasonCandidates(null,[]),/当前剧集/);
  assert.throws(()=>seasonCandidates(first,null),/候选/);
  assert.throws(()=>seasonCandidates(first,Array(401).fill(first)),/候选/);
  for(const title of ['', '  ', 4, '\u007f']) assert.equal(parseSeasonTitle(title),null);
  assert.equal(parseSeasonTitle('第一季').explicit,false);
  assert.equal(parseSeasonTitle('：第一季').explicit,false);
  const clean=seasonCandidates({...first,episodeCount:-1,coverUrl:17,episodeLabel:null,tags:'bad'},[null,{seriesId:'8',title:''}]);
  assert.equal(clean[0].episodeCount,0);assert.equal(clean[0].coverUrl,'');assert.equal(clean[0].episodeLabel,'');assert.deepEqual(clean[0].tags,[]);
  const sameOrdinal=seasonCandidates(first,[row('2',second.title),row('1',second.title)]);assert.deepEqual(sameOrdinal.map(v=>v.seriesId),[first.seriesId,'1','2']);
  const lookup=createSeasonLookup(async()=>({items:[],totalPages:3}));assert.equal((await lookup.load(first)).hasMore,true);
  await assert.rejects(lookup.load(first,{previous:'bad'}));await assert.rejects(lookup.load(first,{previous:Array(41).fill(first)}));
  await assert.rejects(createSeasonLookup(async()=>null).load(first));
  await assert.rejects(createSeasonLookup(async()=>({items:Array(201).fill(first)})).load(first));
  const limit=await createSeasonLookup(async()=>({items:Array.from({length:50},(_,n)=>row(String(9000+n),'持械入宋：第'+(n+1)+'季')),hasMore:false})).load(first);
  assert.equal(limit.items.length,40);assert.equal(limit.hasMore,false);assert.match(limit.notice,/上限/);
});
