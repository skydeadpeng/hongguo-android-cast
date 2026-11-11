#!/usr/bin/env node
// Offline DOM/media-event fixtures for the actual browser receiver module; no Apple hardware claim.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
const own = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
let root = own;
for (let i=2;i<process.argv.length;i++) {
  if (process.argv[i] === '--root' && process.argv[i+1]) root = path.resolve(process.argv[++i]);
  else throw Error('Usage: --root PROJECT');
}
const source = fs.readFileSync(path.join(root,'android/app/src/main/assets/receiver/receiver.js'),'utf8');
const { createReceiver } = await import('data:text/javascript;base64,'+Buffer.from(source).toString('base64'));
let checks=0, groups=0;
function eq(a,b,message) { checks++; assert.deepEqual(a,b,message); }
function ok(value,message) { checks++; assert.ok(value,message); }
const flush = async () => { for(let i=0;i<40;i++) await Promise.resolve(); };
class Element extends EventTarget {
  constructor(){super();this.hidden=false;this.disabled=false;this.value='';this.checked=false;this.textContent='';this.dataset={};}
  emit(type){this.dispatchEvent(new Event(type,{cancelable:true}));}
}
class Video extends Element {
  constructor(){super();this.paused=true;this.ended=false;this.readyState=0;this.currentTime=0;this.duration=10;this.currentSrc='';this.src='';this.playCalls=0;this.failPlay=null;this.error=null;}
  load(){this.currentSrc=this.src;this.readyState=0;this.currentTime=0;this.ended=false;}
  metadata(){this.readyState=4;this.emit('loadedmetadata');}
  play(){this.playCalls++; if(this.failPlay)return Promise.reject(this.failPlay);const was=this.paused;this.paused=false;this.ended=false;if(was){this.emit('play');this.emit('playing');}return Promise.resolve();}
  pause(){const was=this.paused;this.paused=true;if(!was)this.emit('pause');}
  removeAttribute(key){if(key==='src')this.src='';}
  end(){this.currentTime=this.duration;this.ended=true;this.paused=true;this.emit('timeupdate');this.emit('pause');this.emit('ended');}
}
function harness(options={}) {
  const ids=['pair-section','pair-form','pair-code','pair-submit','player-section','connection','status','video','title','quality','previous','toggle','next','autoplay','stop','start-play','airplay','airplay-section'];
  const ui=Object.fromEntries(ids.map(id=>[id,id==='video'?new Video():new Element()]));
  const document=new Element();document.getElementById=id=>ui[id];document.hidden=false;
  const window=new Element();window.location={href:'http://192.168.1.2:34567/'};window.navigator={userAgent:'Macintosh',maxTouchPoints:0};
  const storage=new Map();window.sessionStorage={setItem:(k,v)=>storage.set(k,v),getItem:k=>storage.get(k)||null,removeItem:k=>storage.delete(k)};
  let timer=0;const timers=new Map(), sent=[], requests=[];
  const state={sessionId:'session-1',revision:1,active:true,state:'WAITING_GESTURE',autoplay:true,positionMs:0,durationMs:10000,lastEventSeq:0,error:'',media:{id:'media-1',url:'http://192.168.1.2:34567/media/first.mp4',title:'测试短剧',quality:'1080p',episodeIndex:1,queuePosition:0,count:3},intent:{play:true,seekMs:-1,commandId:1}};
  let eventFailures=0, commandGate=null, oldCommandState=null, oldReads=0;
  const fetcher=async(url,init)=>{
    requests.push({url,init});
    const payload=init.body?JSON.parse(init.body):null;
    if(url==='/pair')return{ok:true,json:async()=>({token:'a'.repeat(64)})};
    if(url==='/state')return{ok:true,json:async()=>structuredClone(oldReads-->0?oldCommandState:state)};
    if(url==='/command'){
      if(commandGate)await commandGate;
      oldCommandState=structuredClone(state);oldReads=options.staleCommandStateReads||0;sent.push({...payload,kind:'command'});state.revision++;
      if(['play','pause','seek'].includes(payload.type)){state.intent.commandId++;state.intent.play=payload.type==='pause'?false:payload.type==='play'?true:state.intent.play;state.intent.seekMs=payload.type==='seek'?payload.value:-1;}
      if(payload.type==='autoplay')state.autoplay=payload.value;
      if(payload.type==='stop')state.active=false;
      if(['next','previous','select'].includes(payload.type)){
        state.media={...state.media,id:'media-'+(state.revision+1),url:'http://192.168.1.2:34567/media/'+state.revision+'.mp4',queuePosition:state.media.queuePosition+1,episodeIndex:state.media.episodeIndex+1};state.intent.commandId++;state.intent.play=true;
      }
      return{ok:true,json:async()=>({ok:true})};
    }
    if(url==='/event'){
      if(eventFailures>0){eventFailures--;throw Error('OFFLINE');}
      sent.push({...payload,kind:'event'});state.lastEventSeq=Math.max(state.lastEventSeq,payload.eventSeq);return{ok:true,json:async()=>({ok:true})};
    }
    throw Error('UNEXPECTED '+url);
  };
  if(options.airplay)ui.video.webkitShowPlaybackTargetPicker=()=>{ui.video.airplayCalls=(ui.video.airplayCalls||0)+1;};
  const receiver=createReceiver({document,window,fetch:fetcher,setTimeout:(fn,ms)=>{const id=++timer;timers.set(id,{fn,ms});return id;},clearTimeout:id=>timers.delete(id)}).start();
  return {ui,video:ui.video,receiver,state,sent,requests,storage,timers,window,document,setEventFailures:n=>eventFailures=n,gateCommands:p=>commandGate=p,
    async pair(){ui['pair-code'].value='123456';ui['pair-form'].emit('submit');await flush();ui.video.metadata();await flush();},
    async poll(){window.emit('online');const id=[...timers].find(([,v])=>v.ms===0)?.[0];if(id){const t=timers.get(id);timers.delete(id);await t.fn();}await flush();},
    async applyTick(){const entry=[...timers].find(([,v])=>v.ms===50);if(entry){timers.delete(entry[0]);entry[1].fn();}await flush();},
    async retry(){const entry=[...timers].find(([,v])=>v.ms===1000);if(entry){timers.delete(entry[0]);await entry[1].fn();}await flush();}
  };
}
async function group(name,fn){await fn();groups++;console.log('PASS '+name);}
await group('pairing, autoplay gesture, genuine playing and no token in URL',async()=>{
  const h=harness();await h.pair();
  eq(h.video.playCalls,0,'no autoplay before real activation');ok(!h.ui['start-play'].hidden,'tap overlay displayed');
  ok(h.sent.some(e=>e.type==='blocked'),'blocked state sent');ok(!h.sent.some(e=>e.type==='playing'),'no false playing from HTTP');
  h.ui['start-play'].emit('click');await flush();
  eq(h.video.playCalls,1,'gesture synchronously starts video');
  const play=h.sent.find(e=>e.type==='playing');ok(play,'real playing event delivered after command');eq(play.commandId,h.state.intent.commandId,'playing uses acknowledged command generation');
  ok(h.sent.findIndex(e=>e.kind==='command'&&e.type==='play')<h.sent.findIndex(e=>e.type==='playing'),'command precedes genuine buffered event');
  ok(h.requests.every(r=>!r.url.includes('a'.repeat(32))),'token only in header');
  ok(h.ui['airplay-section'].hidden,'non Safari no AirPlay promise');h.receiver.destroy();
});
await group('HTTP acceptance waits for asynchronously applied command generation',async()=>{
  const h=harness({staleCommandStateReads:1});await h.pair();h.ui['start-play'].emit('click');await flush();
  eq(h.receiver.inspect().pendingCommands,1,'command remains pending for old state response');
  ok(!h.sent.some(e=>e.type==='playing'),'genuine play retained until new command generation visible');
  await h.applyTick();await flush();eq(h.receiver.inspect().pendingCommands,0,'new command state acknowledged');
  const event=h.sent.find(e=>e.type==='playing');ok(event,'held real playing eventually sent');eq(event.commandId,h.state.intent.commandId,'uses applied generation, never stale ACK generation');h.receiver.destroy();
});
await group('NotAllowed rejection remains visible and does not claim playing',async()=>{
  const h=harness();await h.pair();h.video.failPlay=Object.assign(Error('gesture rejected'),{name:'NotAllowedError'});h.ui['start-play'].emit('click');await flush();
  ok(!h.ui['start-play'].hidden,'tap remains visible');ok(!h.receiver.inspect().activated,'activation reset on rejection');ok(!h.sent.some(e=>e.type==='playing'),'no fabricated playing');h.receiver.destroy();
});
await group('natural EOF deduplicated, pause at tail is not manual pause, stale end ignored',async()=>{
  const h=harness();await h.pair();h.ui['start-play'].emit('click');await flush();h.video.currentTime=6;h.video.emit('timeupdate');await flush();h.video.end();h.video.emit('ended');await flush();
  eq(h.sent.filter(e=>e.kind==='event'&&e.type==='ended').length,1,'one ended');eq(h.sent.filter(e=>e.kind==='command'&&e.type==='pause').length,0,'native pause at EOF not manual pause');
  h.ui.next.emit('click');await flush();const endCount=h.sent.filter(e=>e.type==='ended').length;h.video.emit('ended');await flush();eq(h.sent.filter(e=>e.type==='ended').length,endCount,'old ended after new source ignored');
  h.video.metadata();await flush();ok(h.video.playCalls>=2,'same element continues after actual activation');h.receiver.destroy();
});
await group('real native pause/play and seeking propagate once without remote feedback',async()=>{
  const h=harness();await h.pair();h.ui['start-play'].emit('click');await flush();h.video.pause();await flush();
  eq(h.sent.filter(e=>e.kind==='command'&&e.type==='pause').length,1,'native pause one command');eq(h.state.intent.play,false,'pause intent updated');
  await h.video.play();await flush();eq(h.sent.filter(e=>e.kind==='command'&&e.type==='play').length,2,'native play one new command');
  h.video.currentTime=4;h.video.emit('seeking');h.video.emit('seeked');await flush();eq(h.sent.filter(e=>e.kind==='command'&&e.type==='seek').length,1,'native seek once');
  h.state.intent={play:false,seekMs:-1,commandId:99};h.state.revision++;await h.poll();await flush();eq(h.sent.filter(e=>e.kind==='command'&&e.type==='pause').length,1,'remote pause no command echo');
  h.receiver.destroy();
});
await group('native controls activate continuing playback; seek recovery requires real advancing clock',async()=>{
  const h=harness();await h.pair();await h.video.play();await flush();
  ok(h.receiver.inspect().activated,'native play activation retained for next episode');
  h.video.currentTime=4;h.video.emit('seeking');h.video.emit('seeked');await flush();
  const before=h.sent.filter(e=>e.type==='playing').length;
  h.video.emit('timeupdate');await flush();eq(h.sent.filter(e=>e.type==='playing').length,before,'stalled clock is not playing proof');
  h.video.currentTime=4.3;h.video.emit('timeupdate');await flush();eq(h.sent.filter(e=>e.type==='playing').length,before+1,'advancing active video reconfirms seek playback');
  const confirmed=h.sent.filter(e=>e.type==='playing').at(-1);eq(confirmed.commandId,h.state.intent.commandId,'seek observation uses current acknowledged intent');
  h.state.state='DISCONNECTED';h.state.revision++;await h.poll();const count=h.sent.filter(e=>e.type==='playing').length;
  h.video.paused=true;h.video.currentTime=4.8;h.video.emit('timeupdate');await flush();eq(h.sent.filter(e=>e.type==='playing').length,count,'paused clock changes never recover playback');h.receiver.destroy();
});
await group('bounded command/event queues and stale command isolation',async()=>{
  const h=harness();await h.pair();h.ui['start-play'].emit('click');await flush();let release;h.gateCommands(new Promise(r=>release=r));
  h.ui.next.emit('click');for(let i=0;i<50;i++)h.ui.next.emit('click');for(let i=0;i<100;i++)h.video.emit('waiting');
  ok(h.receiver.inspect().pendingCommands<=8,'bounded commands');ok(h.receiver.inspect().queuedEvents<=24,'bounded media events');release();await flush();
  eq(h.sent.filter(e=>e.kind==='command'&&e.type==='next').length,1,'stale queued next commands discarded after media selection');h.receiver.destroy();
});
await group('failed EOF delivery retries same sequence; real Safari capability button and stop',async()=>{
  const h=harness({airplay:true});await h.pair();h.ui['start-play'].emit('click');await flush();h.setEventFailures(1);h.video.end();await flush();
  ok(h.receiver.inspect().queuedEvents>0,'failed event retained');await h.retry();await h.retry();await flush();ok(h.sent.some(e=>e.type==='ended'),'EOF retries instead of silently disappearing');
  ok(!h.ui['airplay-section'].hidden,'AirPlay shown only on supported API');h.ui.airplay.emit('click');eq(h.video.airplayCalls,1,'system picker called');
  h.ui.stop.emit('click');await flush();eq(h.receiver.inspect().connected,false,'stop clears connection');eq(h.storage.size,0,'stop clears stored token');ok(h.ui['player-section'].hidden,'stopped video hidden');h.receiver.destroy();
});
console.log(`ANDROID_BROWSER_RECEIVER_OK groups=${groups} checks=${checks} syntheticDOM=true appleHardware=not_run`);
