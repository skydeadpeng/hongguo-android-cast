#!/usr/bin/env node
// Actual receiver assets + real HTMLVideoElement + synthetic H.264/AAC in isolated local Chrome.
// This is not validation of iOS Safari, macOS AirPlay or Apple TV hardware.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import http from 'node:http';
import { spawn, spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
const own=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
let root=own, output=path.join(own,'verification/android-apple-webcast-0120-20261021/receiver-chrome');
for(let i=2;i<process.argv.length;i++){
  if(process.argv[i]==='--root'&&process.argv[i+1])root=path.resolve(process.argv[++i]);
  else if(process.argv[i]==='--output'&&process.argv[i+1])output=path.resolve(process.argv[++i]);
  else throw Error('Usage: --root PROJECT --output DIRECTORY');
}
fs.mkdirSync(output,{recursive:true});
const tmp=fs.mkdtempSync(path.join(os.tmpdir(),'hongguo-browser-receiver-'));
const assets=path.join(root,'android/app/src/main/assets/receiver');
const report={kind:'Actual browser receiver, isolated Chrome, generated H.264/AAC fixtures, mock phone session',tests:[],screenshots:[],pageErrors:[],externalRequests:[],events:[],commands:[],appleHardware:'not_run',androidHardware:'not_run'};
let server,chrome,ws,checks=0,passed=0,failed=0;
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
const check=(value,message)=>{checks++;assert.ok(value,message);};
const eq=(actual,expected,message)=>{checks++;assert.deepEqual(actual,expected,message);};
async function group(name,fn){try{await fn();passed++;report.tests.push({name,result:'PASS'});console.log('PASS '+name);}catch(error){failed++;report.tests.push({name,result:'FAIL',error:error.message});console.log('FAIL '+name+': '+error.message);throw error;}}
try {
  const file=path.join(tmp,'synthetic.mp4');
  const generated=spawnSync(process.env.FFMPEG_BIN||'/opt/homebrew/bin/ffmpeg',['-hide_banner','-loglevel','error','-f','lavfi','-i','testsrc2=size=320x568:rate=24','-f','lavfi','-i','sine=frequency=440:sample_rate=44100','-t','4.5','-c:v','libx264','-pix_fmt','yuv420p','-preset','ultrafast','-c:a','aac','-movflags','+faststart','-y',file],{encoding:'utf8',timeout:30000});
  if(generated.status!==0)throw Error('Synthetic MP4 generation failed: '+generated.stderr);
  const bytes=fs.readFileSync(file), token='c'.repeat(64), events=[], commands=[];
  let base='',paired=false, rangeRequests=0;
  const state={sessionId:'fixture-browser-session',revision:1,active:true,state:'WAITING_GESTURE',autoplay:true,positionMs:0,durationMs:4500,lastEventSeq:0,error:'',media:null,intent:{play:true,seekMs:-1,commandId:1}};
  const select=(position)=>{state.media={id:'fixture-media-'+position,url:base+'media/'+position+'.mp4',title:'接收端测试视频',quality:'1080p',episodeIndex:position+1,queuePosition:position,count:2};state.intent={play:true,seekMs:-1,commandId:state.intent.commandId+1};state.state='WAITING_RECEIVER_PLAY';state.positionMs=0;state.revision++;};
  function json(res,value,status=200){res.writeHead(status,{'Content-Type':'application/json','Cache-Control':'no-store'});res.end(JSON.stringify(value));}
  server=http.createServer(async(req,res)=>{
    const url=new URL(req.url,base||'http://127.0.0.1');
    res.setHeader('Referrer-Policy','no-referrer');res.setHeader('Content-Security-Policy',"default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; media-src 'self'; form-action 'none'; base-uri 'none'");
    if(['/','/receiver.js','/receiver.css'].includes(url.pathname)){
      const name=url.pathname==='/'?'index.html':url.pathname.slice(1);res.setHeader('Content-Type',name.endsWith('.html')?'text/html; charset=utf-8':name.endsWith('.css')?'text/css':'text/javascript');res.end(fs.readFileSync(path.join(assets,name)));return;
    }
    if(/^\/media\/[01]\.mp4$/.test(url.pathname)){
      const match=/^bytes=(\d+)-(\d*)$/.exec(req.headers.range||'');let start=0,end=bytes.length-1;
      if(match){start=Number(match[1]);end=match[2]?Math.min(end,Number(match[2])):end;rangeRequests++;res.statusCode=206;res.setHeader('Content-Range',`bytes ${start}-${end}/${bytes.length}`);}
      res.setHeader('Content-Type','video/mp4');res.setHeader('Accept-Ranges','bytes');res.setHeader('Content-Length',end-start+1);res.end(req.method==='HEAD'?undefined:bytes.subarray(start,end+1));return;
    }
    if(url.pathname==='/favicon.ico'){res.writeHead(204);res.end();return;}
    let body='';for await(const chunk of req){body+=chunk;if(body.length>8192){json(res,{},413);return;}}
    let payload;try{payload=body?JSON.parse(body):null;}catch{json(res,{},400);return;}
    if(req.method==='POST'&&req.headers.origin!==base.slice(0,-1)){json(res,{},403);return;}
    if(url.pathname==='/pair'){if(payload?.code!=='654321'){json(res,{error:'BAD_CODE'},403);return;}paired=true;json(res,{token});return;}
    if(!paired||req.headers['x-cast-token']!==token){json(res,{},401);return;}
    if(url.pathname==='/state'){json(res,state);return;}
    if(url.pathname==='/command'){
      commands.push(payload);report.commands.push({type:payload.type,mediaId:payload.mediaId});
      if(payload.type==='play'||payload.type==='pause'){state.intent.play=payload.type==='play';state.intent.commandId++;state.intent.seekMs=-1;state.state=payload.type==='play'?'WAITING_RECEIVER_PLAY':'PAUSED';}
      if(payload.type==='seek'){state.intent.seekMs=payload.value;state.intent.commandId++;}
      if(payload.type==='stop')state.active=false;
      if(payload.type==='autoplay')state.autoplay=payload.value;
      state.revision++;json(res,{ok:true});return;
    }
    if(url.pathname==='/event'){
      events.push(payload);report.events.push({type:payload.type,mediaId:payload.mediaId,commandId:payload.commandId,positionMs:payload.positionMs,durationMs:payload.durationMs});
      state.lastEventSeq=Math.max(state.lastEventSeq,payload.eventSeq);
      if(payload.mediaId===state.media.id&&payload.commandId===state.intent.commandId){
        state.positionMs=payload.positionMs;state.durationMs=payload.durationMs;
        if(payload.type==='playing')state.state='PLAYING';
        if(payload.type==='blocked')state.state='WAITING_GESTURE';
        if(payload.type==='ended'){if(state.media.queuePosition===0&&state.autoplay)select(1);else state.state='ENDED';}
        state.revision++;
      }
      json(res,{ok:true});return;
    }
    json(res,{},404);
  });
  await new Promise(r=>server.listen(0,'127.0.0.1',r));base=`http://127.0.0.1:${server.address().port}/`;select(0);
  const profile=path.join(tmp,'chrome-profile');
  chrome=spawn(process.env.CHROME_BIN||'/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',['--headless=new','--remote-debugging-port=0',`--user-data-dir=${profile}`,'--no-first-run','--disable-background-networking','--disable-default-apps','--no-default-browser-check','--autoplay-policy=document-user-activation-required','about:blank'],{stdio:['ignore','ignore',fs.openSync(path.join(output,'chrome.log'),'w')]});
  let port;for(let i=0;i<100;i++){try{port=Number(fs.readFileSync(path.join(profile,'DevToolsActivePort'),'utf8').split('\n')[0]);if(port)break;}catch{}await sleep(100);}if(!port)throw Error('Chrome startup failed');
  const target=await fetch(`http://127.0.0.1:${port}/json/new?about:blank`,{method:'PUT'}).then(r=>r.json());ws=new WebSocket(target.webSocketDebuggerUrl);await new Promise((r,j)=>{ws.addEventListener('open',r,{once:true});ws.addEventListener('error',j,{once:true});});
  let sequence=0;const pending=new Map();
  ws.addEventListener('message',({data})=>{const message=JSON.parse(data);if(message.id){const p=pending.get(message.id);if(p){pending.delete(message.id);message.error?p.reject(Error(JSON.stringify(message.error))):p.resolve(message.result);}}if(message.method==='Runtime.exceptionThrown')report.pageErrors.push(message.params.exceptionDetails.exception?.description||message.params.exceptionDetails.text);if(message.method==='Network.requestWillBeSent'){const url=message.params.request.url;if(!url.startsWith(base)&&!url.startsWith('data:'))report.externalRequests.push(url);}});
  const call=(method,params={})=>new Promise((resolve,reject)=>{const id=++sequence;pending.set(id,{resolve,reject});ws.send(JSON.stringify({id,method,params}));});
  const evaluate=async expression=>{const result=await call('Runtime.evaluate',{expression,awaitPromise:true,returnByValue:true});if(result.exceptionDetails)throw Error(result.exceptionDetails.exception?.description||result.exceptionDetails.text);return result.result.value;};
  const wait=async(expression,label=expression,timeout=8000)=>{const end=Date.now()+timeout;while(Date.now()<end){if(await evaluate(`Boolean(${expression})`))return;await sleep(60);}throw Error('WAIT_FAILED '+label);};
  const click=async id=>{const point=await evaluate(`(()=>{const r=document.getElementById(${JSON.stringify(id)}).getBoundingClientRect();return{x:r.x+r.width/2,y:r.y+r.height/2}})()`);await call('Input.dispatchMouseEvent',{type:'mousePressed',...point,button:'left',clickCount:1});await call('Input.dispatchMouseEvent',{type:'mouseReleased',...point,button:'left',clickCount:1});};
  const shot=async name=>{const file=path.join(output,name);fs.writeFileSync(file,Buffer.from((await call('Page.captureScreenshot',{format:'png'})).data,'base64'));report.screenshots.push(file);};
  await call('Page.enable');await call('Runtime.enable');await call('Network.enable');await call('Emulation.setDeviceMetricsOverride',{width:1100,height:900,deviceScaleFactor:1,mobile:false});await call('Page.navigate',{url:base});
  await group('real page pairing, audible media requires explicit activation',async()=>{
    await wait('document.getElementById("pair-code")');await shot('receiver-desktop-pair.png');
    await evaluate('document.getElementById("pair-code").value="654321"');await click('pair-submit');
    await wait('document.getElementById("video").readyState>=1','real MP4 metadata');await wait('!document.getElementById("start-play").hidden','explicit gesture prompt');
    eq(await evaluate('document.getElementById("video").paused'),true,'no pre-gesture autoplay');check(!events.some(e=>e.type==='playing'),'HTTP/media metadata not playing proof');
    check(events.some(e=>e.type==='blocked'),'blocked gesture event delivered');eq(await evaluate('document.getElementById("airplay-section").hidden'),true,'Chrome does not pretend AirPlay');
    await shot('receiver-desktop-ready.png');
  });
  await group('real gesture, video frames and one automatic episode transition',async()=>{
    await click('start-play');await wait('!document.getElementById("video").paused&&document.getElementById("video").currentTime>.4','actual media clock');
    await shot('receiver-desktop-playing.png');
    await wait('document.getElementById("video").currentSrc.endsWith("/media/1.mp4")','second episode source',12000);
    await wait('!document.getElementById("video").paused&&document.getElementById("video").currentTime>.3','automatic second episode',8000);
    eq(events.filter(e=>e.type==='ended'&&e.mediaId==='fixture-media-0').length,1,'one actual end event');
    eq(commands.filter(e=>e.type==='pause').length,0,'no manual pause from natural EOF');
    check(events.some(e=>e.type==='playing'&&e.mediaId==='fixture-media-1'),'real second playing event');
    check(events.every(e=>e.type!=='ended'||e.positionMs>=e.durationMs-1000),'only real tail ended');
    check(rangeRequests>0,'browser requests ranged MP4');
  });
  await group('mobile layout, native playback pause and resume, finished status',async()=>{
    await call('Emulation.setDeviceMetricsOverride',{width:390,height:844,deviceScaleFactor:1,mobile:true});
    await shot('receiver-mobile-playing.png');
    check(await evaluate('document.documentElement.scrollWidth<=window.innerWidth'),'no mobile horizontal overflow');
    await click('toggle');await wait('document.getElementById("video").paused','explicit pause');
    await wait('!document.getElementById("toggle").disabled','pause command acknowledged');check(commands.some(e=>e.type==='pause'),'pause delivered');await click('toggle');
    await wait('document.getElementById("video").ended','real final EOF',9000);
    await sleep(500);eq(events.filter(e=>e.type==='ended'&&e.mediaId==='fixture-media-1').length,1,'second EOF one event');
    check((await evaluate('document.getElementById("status").textContent')).includes('已播完'),'finished status');
    await shot('receiver-mobile-ended.png');
  });
  eq(report.externalRequests,[],'no external page/media requests');eq(report.pageErrors,[],'no uncaught page errors');
} catch(error){failed++;report.tests.push({name:'runner',result:'FAIL',error:error.stack});console.error(error.stack);}
finally{
  report.groupsPassed=passed;report.groupsFailed=failed;report.checks=checks;
  fs.writeFileSync(path.join(output,'report.json'),JSON.stringify(report,null,2)+'\n');
  try{ws?.close();}catch{}try{chrome?.kill('SIGTERM');}catch{}
  if(server){server.closeAllConnections();await new Promise(r=>server.close(r));}
  if(chrome&&chrome.exitCode===null)await Promise.race([new Promise(r=>chrome.once('exit',r)),sleep(3000)]);
  fs.rmSync(tmp,{recursive:true,force:true,maxRetries:8,retryDelay:100});
}
console.log(`ANDROID_BROWSER_RECEIVER_CHROME_${failed?'FAILED':'OK'} groupsPassed=${passed} groupsFailed=${failed} checks=${checks} externalRequests=${report.externalRequests.length} appleHardware=not_run`);
process.exitCode=failed?1:0;
