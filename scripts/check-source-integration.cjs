// New-source permission checks through the real main IPC, with no network or user data.
const assert=require('node:assert/strict'),fs=require('node:fs'),os=require('node:os'),path=require('node:path'),vm=require('node:vm');
const {EventEmitter}=require('node:events'),{createRequire}=require('node:module');
const file=path.resolve(__dirname,'../main.js'),req=createRequire(file),root=fs.mkdtempSync(path.join(os.tmpdir(),'hongguo-source-'));
const app=new EventEmitter(),handlers={};
Object.assign(app,{getVersion:()=> 'test',getPath:()=>root,commandLine:{appendSwitch(){}},requestSingleInstanceLock: () => true, whenReady:()=>({then(){}})});
let permissionCalls=0;
const native={fetchEpisodeList:async sid=>({series_id:sid,episodes:[{vid:sid+':2',vid_index:2,locked:true}]}),fetchPlayUrlSingle:async()=>{permissionCalls++;return {url:null,locked:true,error:'该集已锁定'};}};
const hema={search:async()=>({success:true,results:[{series_id:'hema:1',series_title:'剧名'}]})};
const electron={app,ipcMain:{handle:(name,fn)=>handlers[name]=fn},protocol:{registerSchemesAsPrivileged(){}}};
const context=vm.createContext({require:n=>n==='electron'?electron:n==='./src/native/hongguo'?native:n==='./src/native/hema'?hema:n==='./src/store'?{getSettings:()=>({root}),saveTasks(){},saveSeries(){}}:req(n),__dirname:path.dirname(file),process,Buffer,URL,Response,Request,Headers,AbortController,setTimeout,clearTimeout,console:{log(){},warn(){},error(){}}});
vm.runInContext(fs.readFileSync(file,'utf8'),context,{filename:file});
require('./fixtures/trusted-ipc.cjs')(context, handlers);
context.sendToRenderer=()=>{};context.pumpQueue=()=>{};
let failures=0;async function check(name,fn){if(process.argv[2]&&!name.includes(process.argv[2]))return;try{await fn();console.log('PASS '+name);}catch(e){failures++;console.error('FAIL '+name+': '+e.message);}}
(async()=>{
 await check('Hema search reaches its own adapter',async()=>{
  context.runSearchSniff=async()=>({success:false,error:'wrong source'});
  const result=await handlers['search-series']({},'剧名',{source:'hema'});assert.equal(result.results?.[0]?.series_id,'hema:1');
 });
 await check('Hema queue rechecks locked state and rejects cross-source IDs',async()=>{
  await assert.rejects(context.enqueueEpisodes({seriesId:'hema:1',seriesTitle:'同名剧',episodes:[{vid:'hema:1:2',vid_index:2,locked:false}]}),/锁定/);
  await assert.rejects(context.enqueueEpisodes({seriesId:'123',seriesTitle:'同名剧',episodes:[{vid:'hema:1:2',vid_index:2,locked:false}]}),/来源不匹配/);
 });
 await check('Hema cached playback and compatibility still enforce current access',async()=>{
  vm.runInContext("onlineCache.set('hema:1:2',{size:123,playInfo:{url:'https://example.invalid/media.mp4'}})",context);
  const online=await handlers['prepare-online-play']({},{vid:'hema:1:2',seriesId:'hema:1',vidIndex:2});assert.equal(online.success,false);assert.match(online.error,/锁定/);assert.equal(permissionCalls,1);
  fs.writeFileSync(context.compatPathFor('hema:1',2),Buffer.alloc(120000));
  const compat=await handlers['transcode-for-playback']({},{seriesId:'hema:1',vid:'hema:1:2',vidIndex:2,requestId:'hema-locked'});assert.equal(compat.success,false);assert.match(compat.error,/锁定/);
 });
 await check('Hema queued download rechecks access before trusting existing files',async()=>{
  const saved=path.join(root,'saved.mp4');fs.writeFileSync(saved,Buffer.alloc(120000));
  const task={id:'hema-test',type:'hongguo',status:'pending',filename:'saved.mp4',savePath:saved,customDir:root,hongguoInfo:{vid:'hema:1:2',series_id:'hema:1',series_title:'同名剧'}};
  context.testTask=task;vm.runInContext('downloadTasks=[testTask]',context);
  await context.executeHongguoDownload(task);assert.equal(task.status,'failed');assert.match(task.error,/锁定/);assert.equal(fs.statSync(saved).size,120000);
 });
 await check('Custom source detail and play routes never fall through to Hongguo',async()=>{
  const route={fetchEpisodeList:async id=>({series_id:id,source:'custom'}),fetchPlayUrlSingle:async(vid,sid)=>({vid,sid,source:'custom'})};
  const m={exports:{}};const sandbox={module:m,exports:m.exports,require:n=>n==='./custom-source'?route:n==='axios'?{get:()=>{throw Error('wrong source')}}:req(n.startsWith('.')?path.join(__dirname,'../src/native',n):n),Buffer,URL,console,process};
  vm.runInNewContext(fs.readFileSync(path.join(__dirname,'../src/native/hongguo.js'),'utf8'),sandbox);
  assert.equal((await m.exports.fetchEpisodeList('custom:550e8400-e29b-41d4-a716-446655440000:1')).source,'custom');
  assert.equal((await m.exports.fetchPlayUrlSingle('custom:550e8400-e29b-41d4-a716-446655440000:1:2','custom:550e8400-e29b-41d4-a716-446655440000:1')).source,'custom');
 });
 await check('Hema media uses its own directory',()=>{assert.equal(path.basename(path.dirname(context.seriesDownloadDir(root,'hema:1','同名剧'))),'河马短剧');});
 if(failures)process.exitCode=1;
})().finally(()=>fs.rmSync(root,{recursive:true,force:true}));
