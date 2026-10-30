/** Official relations are independent of title search and never join playback queues. */
const ID=/^[1-9][0-9]{0,29}$/;
const pending=new Map();let sequence=0;
const cancelled=()=>Object.assign(new Error('分季读取已取消'),{code:'ERR_CANCELED'});
export function requestOfficialSeries(seriesId,{signal,timeoutMs=55000}={}){
  if(typeof seriesId!=='string'||!ID.test(seriesId)||!Number.isSafeInteger(timeoutMs)||timeoutMs<1||timeoutMs>60000)return Promise.reject(new Error('分季请求参数无效'));
  if(signal?.aborted)return Promise.reject(cancelled());
  if(!globalThis.window?.AndroidSeries?.postMessage)return Promise.reject(new Error('官方分季未连接设备接口'));
  if(pending.size>=4)return Promise.reject(new Error('分季请求较多，请稍后重试'));
  const id=`seasons-${Date.now()}-${++sequence}`;
  return new Promise((resolve,reject)=>{
    let timer;const stop=()=>{try{window.AndroidSeries.postMessage(JSON.stringify({id:`cancel-${id}`,method:'cancel',requestId:id}));}catch{}};
    const clean=()=>{clearTimeout(timer);signal?.removeEventListener('abort',abort);pending.delete(id);};
    const abort=()=>{if(!pending.has(id))return;clean();stop();reject(cancelled());};
    timer=setTimeout(()=>{if(!pending.has(id))return;clean();stop();reject(Object.assign(new Error('官方分季读取超时'),{code:'ETIMEDOUT'}));},timeoutMs);
    pending.set(id,{resolve,reject,clean});signal?.addEventListener('abort',abort,{once:true});
    if(signal?.aborted){abort();return;}
    try{window.AndroidSeries.postMessage(JSON.stringify({id,method:'load',seriesId}));}catch(error){clean();reject(error);}
  });
}
if(typeof window!=='undefined')window.__androidSeriesResponse=message=>{
  if(!message||typeof message.id!=='string')return;const request=pending.get(message.id);if(!request)return;request.clean();
  if(message.error!=null)request.reject(message.errorCode==='ERR_CANCELED'?cancelled():new Error(String(message.error)));else request.resolve(message.result);
};
function project(raw){
  if(!raw||typeof raw.seriesId!=='string'||!ID.test(raw.seriesId)||typeof raw.title!=='string'||!raw.title.trim()||raw.title.length>200||typeof raw.playable!=='boolean'||typeof raw.upcoming!=='boolean')throw new Error('官方分季条目无效');
  if(raw.upcoming&&raw.playable)throw new Error('待上线分季状态无效');
  const text=(v,max)=>typeof v==='string'?v.slice(0,max):'';
  return Object.freeze({seriesId:raw.seriesId,title:raw.title,coverUrl:text(raw.coverUrl,4096),description:text(raw.description,8000),
    episodeCount:Number.isSafeInteger(raw.episodeCount)&&raw.episodeCount>=0&&raw.episodeCount<=10000?raw.episodeCount:0,
    episodeLabel:text(raw.episodeLabel,80),seasonNumber:Number.isSafeInteger(raw.seasonNumber)&&raw.seasonNumber>0?raw.seasonNumber:null,
    seasonLabel:text(raw.seasonLabel,80)||'系列剧',subtitle:text(raw.subtitle,200),official:true,playable:raw.playable,upcoming:raw.upcoming,tags:[]});
}
export function createOfficialSeasonLookup(official,fallback){
  if(typeof official!=='function'||typeof fallback?.load!=='function')throw new Error('分季读取函数无效');
  const channels=new Map();
  const select=(key,value)=>{channels.delete(key);channels.set(key,value);while(channels.size>32)channels.delete(channels.keys().next().value);};
  return Object.freeze({async load(seed,{page=1,previous=[],signal}={}){
    if(!seed||typeof seed.seriesId!=='string'||!ID.test(seed.seriesId)||!Number.isSafeInteger(page)||page<1||page>10)throw new Error('分季请求参数无效');
    if(signal?.aborted)throw cancelled();
    if(page>1){if(channels.get(seed.seriesId)!=='title')throw new Error('官方分季不使用标题搜索分页');return fallback.load(seed,{page,previous});}
    let reason='';
    try{
      const result=await official(seed.seriesId,{signal});if(signal?.aborted)throw cancelled();
      if(!result||result.matching!=='official'||!Array.isArray(result.items)||result.items.length>1000)throw new Error('官方分季响应无效');
      const seen=new Set();const items=result.items.map(project).filter(item=>!seen.has(item.seriesId)&&seen.add(item.seriesId));
      if(items.length&&!items.some(item=>item.seriesId===seed.seriesId))throw new Error('官方分季未确认当前剧集');
      if(items.length){const chosen=items.slice(0,40);if(!chosen.some(i=>i.seriesId===seed.seriesId))chosen[chosen.length-1]=items.find(i=>i.seriesId===seed.seriesId);select(seed.seriesId,'official');return Object.freeze({items:chosen,page:1,hasMore:false,complete:items.length<=40&&result.truncated!==true,truncated:items.length>40||result.truncated===true,matching:'official',notice:items.length>40||result.truncated===true?'官方系列关系；已显示40季，请用搜索查找其余季。':'官方系列关系；待上线或禁播季仅展示状态，切季不会自动播放。'});}
      reason='官方暂未返回系列列表';
    }catch(error){if(signal?.aborted||error?.code==='ERR_CANCELED')throw cancelled();reason='官方分季暂未读取成功';}
    select(seed.seriesId,'title');const result=await fallback.load(seed,{page:1,previous:[]});if(signal?.aborted)throw cancelled();
    return Object.freeze({...result,matching:'title',notice:`${reason}，已使用标题匹配。${result.notice||''}`});
  }});
}
