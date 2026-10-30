package io.github.jackotom.hongguo.android;

import android.content.Context;
import java.io.*;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import org.json.*;

/** Official-account library and durable outbox. Guest and retired server data never become this namespace. */
final class OfficialLibrary {
    interface Api { JSONObject call(String path,String method,JSONObject query,JSONObject body,boolean gzip)throws Exception; }
    interface Persistence { JSONObject read(String uid)throws Exception; void write(String uid,JSONObject value)throws Exception; }
    interface GuestSource { JSONArray rows()throws Exception; default JSONArray favorites()throws Exception{return new JSONArray();} }
    static final class AuthExpiredException extends IOException { AuthExpiredException(String message){super(message);} }
    private static final int MAX_PENDING=2000, MAX_ENTRIES=100_000, MAX_FILE_BYTES=24*1024*1024;
    private static final Set<OfficialLibrary> INSTANCES=Collections.newSetFromMap(new WeakHashMap<OfficialLibrary,Boolean>());
    private final Object lock=new Object();
    private final Persistence disk;
    private final GuestSource guest;
    private final LongSupplier clock;
    private final Map<String,State> states=new LinkedHashMap<String,State>(4,.75f,true);
    private final Map<String,Integer> stateBytes=new HashMap<>();
    private final Map<String,String> errors=new HashMap<>();
    private final Set<String> running=new HashSet<>(),expired=new HashSet<>();
    private String selected="";private long selectionEpoch;
    private volatile Runnable onDirty;

    OfficialLibrary(Context context){
        this(new FilePersistence(new File(context.getApplicationContext().getFilesDir(),"official-library-v1")),
            new GuestSource(){
                public JSONArray rows()throws Exception{return new JSONArray(context.getApplicationContext().getSharedPreferences("hongguo_library_v1",Context.MODE_PRIVATE).getString("history","[]"));}
                public JSONArray favorites()throws Exception{return new JSONArray(context.getApplicationContext().getSharedPreferences("hongguo_library_v1",Context.MODE_PRIVATE).getString("favorites","[]"));}
            },System::currentTimeMillis);
    }
    OfficialLibrary(File directory,GuestSource guest,LongSupplier clock){this(new FilePersistence(directory),guest,clock);}
    OfficialLibrary(Persistence disk,GuestSource guest,LongSupplier clock){this.disk=disk;this.guest=guest;this.clock=clock;synchronized(INSTANCES){INSTANCES.add(this);}}
    void setOnDirty(Runnable listener){onDirty=listener;}
    void select(String uid)throws Exception{
        if(uid==null||!uid.isEmpty()&&!validId(uid))throw new IOException("官方账号身份无效");
        synchronized(lock){if(!selected.equals(uid))selectionEpoch++;selected=uid;if(!uid.isEmpty()){try{state(uid);errors.remove(uid);expired.remove(uid);}catch(Exception error){errors.put(uid,"官方账号本机记录读取未完成");throw error;}}}
    }
    LibraryStore.HistoryAccess openHistory(){
        final String owner;synchronized(lock){owner=selected;}if(owner.isEmpty())return null;
        return new LibraryStore.HistoryAccess(){
            public JSONArray rows()throws JSONException{return rowsFor(owner,false);}
            public void record(JSONObject row)throws JSONException{try{recordFor(owner,row);}catch(Exception error){remember(owner,error);throw jsonError("官方观看记录保存未成功");}}
            @Override public JSONArray favorites()throws JSONException{return rowsFor(owner,true);}
            @Override public void toggleFavorite(JSONObject row)throws JSONException{try{favoriteFor(owner,row);}catch(Exception error){remember(owner,error);throw jsonError("官方收藏保存未成功");}}
            @Override public int position(JSONObject series,JSONObject episode)throws JSONException{try{synchronized(lock){JSONObject row=state(owner).entries.get(series.optString("seriesId"));return row!=null&&row.optString("vid").equals(episode.optString("vid"))?(int)Math.min(Integer.MAX_VALUE,Math.max(0,row.optLong("positionMs"))):0;}}catch(Exception error){throw jsonError("官方播放进度读取未完成");}}
            @Override public JSONObject projection()throws JSONException{return projectionFor(owner);}
            @Override public JSONObject seriesHistory(String id)throws JSONException{try{synchronized(lock){JSONObject row=state(owner).entries.get(id);return row==null?null:projectRow(row);}}catch(Exception error){throw jsonError("官方剧集观看记录读取未完成");}}
        };
    }
    JSONObject status()throws JSONException{
        synchronized(lock){State value=states.get(selected);int count=value==null?0:value.pending.size();String error=errors.getOrDefault(selected,"");boolean syncing=running.contains(selected);
            return new JSONObject().put("syncing",syncing).put("pendingCount",count).put("pending",count).put("lastSyncAt",value==null?0:value.lastSync).put("error",error).put("authExpired",expired.contains(selected)).put("loadedAccounts",states.size()).put("loadedBytes",loadedBytes()).put("state",syncing?"syncing":!error.isEmpty()?"error":count>0?"pending":"idle");}
    }
    void checkpoint()throws Exception{synchronized(lock){for(Map.Entry<String,State> entry:states.entrySet())disk.write(entry.getKey(),entry.getValue().json());}}
    static void checkpointAll(Context ignored)throws Exception{List<OfficialLibrary> values;synchronized(INSTANCES){values=new ArrayList<>(INSTANCES);}for(OfficialLibrary value:values)value.checkpoint();}
    void flush()throws Exception{checkpoint();}
    private void remember(String uid,Exception error){synchronized(lock){if(selected.equals(uid)){errors.put(uid,privateError(error));if(error instanceof AuthExpiredException)expired.add(uid);}}}
    private static String privateError(Exception error){
        if(error instanceof AuthExpiredException)return "官方账号会话需要重新登录";
        if(error instanceof java.net.SocketTimeoutException)return "官方云同步超时，本机记录与待同步队列已保留";
        // Only this module's stable messages reach UI; never emit remote JSON/URLs/UIDs/credentials.
        String value=error.getMessage();return value!=null&&value.startsWith("云端")&&!value.contains("://")&&value.length()<=100?value:"官方云同步未完成，本机记录与待同步队列已保留";
    }
    private static JSONException jsonError(String message){return new JSONException(message);}
    private State state(String uid)throws Exception{
        State existing=states.get(uid);if(existing!=null)return existing;JSONObject saved=disk.read(uid);State result=saved==null?new State(uid):State.parse(saved,uid);JSONObject encoded=result.json();int bytes=encoded.toString().getBytes(StandardCharsets.UTF_8).length;if(bytes>MAX_FILE_BYTES)throw new IOException("官方本机剧库超过存储上限");if(saved==null)disk.write(uid,encoded);states.put(uid,result);stateBytes.put(uid,bytes);trimStates();return result;
    }
    private long loadedBytes(){long bytes=0;for(int value:stateBytes.values())bytes+=value;return bytes;}
    private void trimStates(){
        while(states.size()>2||loadedBytes()>MAX_FILE_BYTES){String victim=null;for(String uid:states.keySet())if(!uid.equals(selected)){victim=uid;break;}if(victim==null)return;states.remove(victim);stateBytes.remove(victim);errors.remove(victim);expired.remove(victim);}
    }
    private void commit(String uid,State value)throws Exception{JSONObject encoded=value.json();int bytes=encoded.toString().getBytes(StandardCharsets.UTF_8).length;if(bytes>MAX_FILE_BYTES)throw new IOException("官方本机剧库超过存储上限");disk.write(uid,encoded);states.put(uid,value);stateBytes.put(uid,bytes);errors.remove(uid);expired.remove(uid);trimStates();}
    private void dirty(){Runnable callback=onDirty;if(callback!=null)try{callback.run();}catch(RuntimeException ignored){/* Durable data survives a scheduler failure. */}}
    private static final class Projection {final JSONArray rows;final int total;Projection(JSONArray rows,int total){this.rows=rows;this.total=total;}}
    private Projection project(State state,boolean favorites)throws Exception{
        String sort=favorites?"favoriteAt":"updatedAt";Comparator<JSONObject> order=Comparator.comparingLong(row->row.optLong(sort));PriorityQueue<JSONObject> newest=new PriorityQueue<>(200,order);int total=0;
        for(JSONObject row:state.entries.values())if(favorites?row.optLong("favoriteAt")>0:row.optLong("updatedAt")>0&&row.optInt("index")>0){total++;if(newest.size()<200)newest.add(row);else if(order.compare(row,newest.peek())>0){newest.poll();newest.add(row);}}
        List<JSONObject> sorted=new ArrayList<>(newest);sorted.sort(order.reversed());JSONArray result=new JSONArray();int bytes=2;
        for(JSONObject row:sorted){JSONObject projected=projectRow(row);int length=projected.toString().getBytes(StandardCharsets.UTF_8).length+1;if(bytes+length>96*1024)break;result.put(projected);bytes+=length;}
        return new Projection(result,total);
    }
    private static JSONObject projectRow(JSONObject row)throws JSONException{
        JSONObject result=new JSONObject();for(String key:new String[]{"seriesId","vid"})result.put(key,row.optString(key,""));result.put("title",clip(row.optString("title",""),200)).put("coverUrl",clip(row.optString("coverUrl",""),4096)).put("description",clip(row.optString("description",""),240)).put("episodeLabel",clip(row.optString("episodeLabel",""),100));
        for(String key:new String[]{"index","positionMs","updatedAt","favoriteAt","episodeCount","durationMs"})result.put(key,row.optLong(key));Object season=row.opt("seasonIndex"),multi=row.opt("multiSeason");if(season instanceof Number){double n=((Number)season).doubleValue();if(Double.isFinite(n)&&n==Math.rint(n)&&n>=0&&n<=10000)result.put("seasonIndex",(int)n);}if(multi instanceof Boolean)result.put("multiSeason",multi);result.put("metadataPending",row.optBoolean("metadataPending"));JSONArray tags=row.optJSONArray("tags"),clean=new JSONArray();if(tags!=null)for(int i=0;i<Math.min(4,tags.length());i++)clean.put(clip(tags.optString(i,""),40));return result.put("tags",clean);
    }
    private JSONArray rowsFor(String uid,boolean favorites)throws JSONException{synchronized(lock){try{return project(state(uid),favorites).rows;}catch(Exception error){remember(uid,error);throw jsonError("官方账号本机记录读取未完成");}}}
    private JSONObject projectionFor(String uid)throws JSONException{synchronized(lock){try{State value=state(uid);Projection history=project(value,false),favorites=project(value,true);return new JSONObject().put("provider","hongguo").put("historyTotal",history.total).put("historyShown",history.rows.length()).put("historyHasMore",history.rows.length()<history.total).put("favoritesTotal",favorites.total).put("favoritesShown",favorites.rows.length()).put("favoritesHasMore",favorites.rows.length()<favorites.total).put("completeSaved",true);}catch(Exception error){throw jsonError("官方账号剧库投影读取未完成");}}}
    private void recordFor(String uid,JSONObject input)throws Exception{
        JSONObject row=localProgress(input);
        synchronized(lock){State before=state(uid),next=before.copy();String id=row.getString("seriesId");JSONObject old=next.entries.get(id);if(old!=null){
                row.put("favoriteAt",old.optLong("favoriteAt"));
                if(row.optLong("durationMs")==0&&row.optString("vid").equals(old.optString("vid")))row.put("durationMs",old.optLong("durationMs"));
                if(!input.has("seasonIndex"))row.put("seasonIndex",old.optInt("seasonIndex"));if(!input.has("multiSeason"))row.put("multiSeason",old.optBoolean("multiSeason"));
            }next.entries.put(id,row);next.enqueue("progress",id,true,row);commit(uid,next);}dirty();
    }
    private void favoriteFor(String uid,JSONObject input)throws Exception{
        JSONObject card=LibraryStore.series(input);String id=card.getString("seriesId");
        synchronized(lock){State next=state(uid).copy();JSONObject row=next.entries.containsKey(id)?copy(next.entries.get(id)):card;boolean value=row.optLong("favoriteAt")==0;mergeMetadata(row,card);row.put("favoriteAt",value?Math.max(1,clock.getAsLong()):0);next.entries.put(id,row);next.enqueue("favorite",id,value,row);commit(uid,next);}dirty();
    }
    JSONObject importGuest()throws Exception{
        // The caller's explicit confirmation is the only entry into this method; guest storage is never changed.
        final String owner;final long ownerEpoch;synchronized(lock){owner=selected;ownerEpoch=selectionEpoch;if(owner.isEmpty())throw new IOException("请先登录红果官方账号");}
        JSONArray source=guest.rows(),favoriteSource=guest.favorites();if(source==null||favoriteSource==null)throw new IOException("本机游客记录读取未完成");
        Map<String,JSONObject> candidates=new LinkedHashMap<>(),favoriteCandidates=new LinkedHashMap<>();int historySkipped=0,favoritesSkipped=0;
        for(int i=0;i<source.length();i++){try{JSONObject row=localProgress(source.getJSONObject(i));String id=row.getString("seriesId");JSONObject old=candidates.get(id);if(old==null||old.getLong("updatedAt")<row.getLong("updatedAt"))candidates.put(id,row);}catch(Exception invalid){historySkipped++;}}
        for(int i=0;i<favoriteSource.length();i++){try{JSONObject raw=favoriteSource.getJSONObject(i),row=LibraryStore.series(raw);String id=requiredId(row.opt("seriesId"));long stamp=raw.has("favoriteAt")&&!raw.isNull("favoriteAt")?integer(raw.opt("favoriteAt"),0,9_007_199_254_740_991L):1;row.put("favoriteAt",Math.max(1,stamp));JSONObject old=favoriteCandidates.get(id);if(old==null||old.optLong("favoriteAt")<row.optLong("favoriteAt"))favoriteCandidates.put(id,row);}catch(Exception invalid){favoritesSkipped++;}}
        int historyImported=0,favoritesImported=0,historyRemaining=0,favoritesRemaining=0;boolean watermarksChanged=false;
        synchronized(lock){if(!selected.equals(owner)||selectionEpoch!=ownerEpoch)throw new InterruptedIOException("账号已切换，请重新确认导入");State next=state(owner).copy();for(Map.Entry<String,JSONObject> candidate:candidates.entrySet()){
                String id=candidate.getKey();JSONObject row=copy(candidate.getValue()),old=next.entries.get(id);long stamp=row.getLong("updatedAt");
                if(stamp<=next.imported.getOrDefault(id,0L)||old!=null&&stamp<=old.optLong("updatedAt")){historySkipped++;continue;}
                if(!next.pending.containsKey("progress:"+id)&&next.pending.size()>=MAX_PENDING){historyRemaining++;continue;}
                if(old!=null)row.put("favoriteAt",old.optLong("favoriteAt"));next.entries.put(id,row);next.enqueue("progress",id,true,row);next.imported.put(id,stamp);historyImported++;
            }
            for(Map.Entry<String,JSONObject> candidate:favoriteCandidates.entrySet()){
                String id=candidate.getKey();JSONObject card=candidate.getValue(),old=next.entries.get(id);long stamp=card.getLong("favoriteAt");
                if(stamp<=next.favoriteImported.getOrDefault(id,0L)){favoritesSkipped++;continue;}
                // A pending explicit official favorite/cancel takes priority over an older guest membership.
                // Remember already-present/cancelled imports too, otherwise a later retry could resurrect them.
                if(next.pending.containsKey("favorite:"+id)||old!=null&&old.optLong("favoriteAt")>0){next.favoriteImported.put(id,stamp);watermarksChanged=true;favoritesSkipped++;continue;}
                if(next.pending.size()>=MAX_PENDING){favoritesRemaining++;continue;}
                JSONObject row=old==null?copy(card):copy(old);mergeMetadata(row,card);row.put("favoriteAt",stamp==1?Math.max(1,clock.getAsLong()):stamp);next.entries.put(id,row);next.enqueue("favorite",id,true,row);next.favoriteImported.put(id,stamp);favoritesImported++;
            }
            if(historyImported+favoritesImported>0||watermarksChanged)commit(owner,next);
        }
        int imported=historyImported+favoritesImported,skipped=historySkipped+favoritesSkipped,remaining=historyRemaining+favoritesRemaining;
        if(imported>0)dirty();return new JSONObject().put("importedCount",imported).put("skippedCount",skipped).put("remainingCount",remaining).put("imported",imported).put("remaining",remaining)
            .put("historyImportedCount",historyImported).put("favoritesImportedCount",favoritesImported).put("historySkippedCount",historySkipped).put("favoritesSkippedCount",favoritesSkipped).put("historyRemainingCount",historyRemaining).put("favoritesRemainingCount",favoritesRemaining);
    }
    void sync(OfficialApiClient client,String uid,BooleanSupplier current)throws Exception{
        JSONObject session=client.session(),cookies=session.optJSONObject("cookies");
        if(session.optString("token","").isEmpty()&&(cookies==null||cookies.optString("sessionid","").isEmpty()&&cookies.optString("sessionid_ss","").isEmpty())){AuthExpiredException error=new AuthExpiredException("官方账号会话凭证缺失");remember(uid,error);throw error;}
        client.deadlineNanos(System.nanoTime()+180_000_000_000L);
        sync((path,method,query,body,gzip)->client.cloud(path,method,query,body,gzip),uid,current);
    }
    void sync(Api api,String uid,BooleanSupplier current)throws Exception{
        synchronized(lock){checkCurrent(uid,current);state(uid);if(!running.add(uid))return;}
        final long deadline=System.nanoTime()+180_000_000_000L;
        try{
            Map<String,JSONObject> remote=pull(api,uid,current,deadline);
            synchronized(lock){checkCurrent(uid,current);merge(uid,remote);}
            int sent=0;
            for(;sent<MAX_PENDING;sent++){
                final Mutation mutation;synchronized(lock){checkCurrent(uid,current);State value=state(uid);if(value.pending.isEmpty())break;mutation=value.pending.values().iterator().next();}
                upload(api,uid,current,deadline,mutation);
                synchronized(lock){checkCurrent(uid,current);State before=state(uid);Mutation latest=before.pending.get(mutation.key());if(latest!=null&&latest.revision==mutation.revision){State next=before.copy();next.pending.remove(mutation.key());commit(uid,next);}}
            }
            synchronized(lock){checkCurrent(uid,current);State next=state(uid).copy();if(sent==MAX_PENDING&&!next.pending.isEmpty())throw new IOException("云端同步批次达到上限，将在下一轮继续");next.lastSync=Math.max(1,clock.getAsLong());commit(uid,next);}
        }catch(Exception error){remember(uid,error);throw error;}
        finally{synchronized(lock){running.remove(uid);}}
    }
    private void checkCurrent(String uid,BooleanSupplier current)throws IOException{if(!validId(uid)||!selected.equals(uid)||!current.getAsBoolean()||Thread.currentThread().isInterrupted())throw new InterruptedIOException("账号已切换或同步已取消");}
    private JSONObject call(Api api,String uid,BooleanSupplier current,long deadline,String path,String method,JSONObject query,JSONObject body)throws Exception{
        synchronized(lock){checkCurrent(uid,current);}if(System.nanoTime()>=deadline)throw new java.net.SocketTimeoutException("官方云同步超时");
        JSONObject response=api.call(path,method,query,body,false);
        synchronized(lock){checkCurrent(uid,current);}if(System.nanoTime()>=deadline)throw new java.net.SocketTimeoutException("官方云同步超时");
        envelope(response,uid);return response;
    }
    private static void envelope(JSONObject response,String uid)throws Exception{
        if(response==null||!response.has("code")||!zero(response.opt("code"))) {if(response!=null&&"401".equals(String.valueOf(response.opt("code"))))throw new AuthExpiredException("官方账号会话已失效");throw new IOException("云端未确认请求成功");}
        for(JSONObject object:new JSONObject[]{response,response.optJSONObject("data")})if(object!=null){
            for(String key:new String[]{"error_code","status_code"})if(object.has(key)&&!zero(object.opt(key))){if("401".equals(String.valueOf(object.opt(key))))throw new AuthExpiredException("官方账号会话已失效");throw new IOException("云端返回业务错误，本次同步未完成");}
            for(String key:new String[]{"owner_uid","owner_id","target_user_id"})if(object.has(key)&&!uid.equals(exactId(object.opt(key))))throw new AuthExpiredException("官方账号响应身份不一致");
        }
    }
    private static boolean zero(Object value){return value instanceof Number?((Number)value).doubleValue()==0:"0".equals(value);}
    private Map<String,JSONObject> pull(Api api,String uid,BooleanSupplier current,long deadline)throws Exception{
        BoundedSnapshot snapshot=new BoundedSnapshot();Map<String,JSONObject> remote=snapshot.rows,local;
        synchronized(lock){local=new LinkedHashMap<>(state(uid).entries);}
        Set<String> fingerprints=new HashSet<>();long offset=0;
        for(int page=0;page<200;page++){
            JSONObject query=new JSONObject().put("limit","0").put("offset",String.valueOf(offset)).put("book_type","2").put("full_field","false").put("query_soft_deleted","false").put("last_min_read_timestamp_ms","0").put("is_first_load",offset==0?"true":"false");
            JSONObject data=data(call(api,uid,current,deadline,"/reading/bookapi/read_history/list/v","GET",query,null));JSONArray rows=array(data,"data_list");if(rows.length()>500)throw new IOException("云端历史单页超过上限");
            if(rows.length()>0&&!fingerprints.add(digest(rows.toString())))throw new IOException("云端历史返回重复分页");
            for(int i=0;i<rows.length();i++){
                JSONObject raw=rows.optJSONObject(i);if(raw==null)throw new IOException("云端历史条目无效");String id=requiredId(raw.opt("book_id_str"));if(flag(raw,"is_delete",false))continue;
                long index=field(raw,"vid_index",10000),stamp=field(raw,"read_timestamp_ms",9_007_199_254_740_991L),position=millis(raw,"current_play_position"),duration=millis(raw,"duration");
                if(index<1||stamp<=0)continue;
                JSONObject row=card(id,raw.optString("book_name",""),raw.optString("thumb_url",""),Math.min(10000,raw.optInt("episode_cnt")));
                row.put("index",index).put("vid",optionalId(raw.opt("vid"))).put("positionMs",position).put("durationMs",duration).put("updatedAt",stamp).put("seasonIndex",Math.max(0,raw.optInt("season_index"))).put("multiSeason",flag(raw,"is_multi_season",false));
                fillMissingMetadata(row,local.get(id));JSONObject old=remote.get(id);if(old==null||old.optLong("updatedAt")<stamp)snapshot.put(id,row);
            }
            boolean more=flag(data,"has_more",true);if(!more)break;if(rows.length()==0||page==199)throw new IOException("云端历史分页未完整读取");
            long next=data.has("next_offset")?integer(data.opt("next_offset"),0,Integer.MAX_VALUE):0;offset=next>offset?next:offset+500;
        }
        JSONObject favorites=data(call(api,uid,current,deadline,"/reading/bookapi/bookshelf/video/list/v","GET",new JSONObject().put("target_user_id",uid),null));JSONArray rows=array(favorites,"video_shelf_info");if(rows.length()>MAX_ENTRIES||favorites.has("has_more")&&flag(favorites,"has_more",true))throw new IOException("云端收藏分页未完整读取");
        for(int i=0;i<rows.length();i++){
            JSONObject raw=rows.optJSONObject(i);if(raw==null)throw new IOException("云端收藏条目无效");String id=requiredId(raw.opt("series_id"));JSONObject row=remote.containsKey(id)?remote.get(id):local.containsKey(id)?copy(local.get(id)):card(id,"","",0);
            // Favorite-only cards must not inherit a stale favorite from a previous incomplete read.
            long stamp=raw.has("collect_time")?integer(raw.opt("collect_time"),0,9_007_199_254_740_991L):0;if(stamp>0&&stamp<100_000_000_000L)stamp*=1000;row.put("favoriteAt",stamp>0?stamp:Math.max(1,clock.getAsLong()-i));snapshot.put(id,row);
        }
        if(remote.size()>MAX_ENTRIES)throw new IOException("云端剧库条目超过读取上限");
        List<String> missing=new ArrayList<>();for(Map.Entry<String,JSONObject> entry:remote.entrySet())if(entry.getValue().optString("title").isEmpty()||entry.getValue().optBoolean("metadataPending"))missing.add(entry.getKey());
        for(int start=0;start<missing.size();start+=20){List<String> batch=missing.subList(start,Math.min(start+20,missing.size()));JSONObject metadata=null;
            try{JSONObject body=new JSONObject().put("dr_scene","preload").put("series_id",String.join(",",batch)).put("biz_param",new JSONObject().put("detail_page_version",0).put("disable_digg_stat",false).put("disable_video_relate_book",false).put("need_all_video_definition",false).put("need_mp4_align",false).put("source",7).put("use_os_player",false).put("use_server_dns",false));metadata=data(call(api,uid,current,deadline,"/novel/player/multi_video_detail/preload/v1","POST",null,body));}
            catch(Exception error){synchronized(lock){checkCurrent(uid,current);}if(error instanceof AuthExpiredException||error instanceof InterruptedIOException||error instanceof OfficialApiClient.HTTPError&&(((OfficialApiClient.HTTPError)error).status==401||((OfficialApiClient.HTTPError)error).status==403))throw error;}
            for(String id:batch){JSONObject row=remote.get(id),holder=metadata==null?null:metadata.optJSONObject(id),raw=holder==null?null:holder.optJSONObject("video_data");
                if(raw!=null&&raw.length()>0){String actual=requiredId(raw.opt("series_id"));if(!id.equals(actual))throw new IOException("云端详情身份不一致");String title=raw.optString("series_title","");if(!title.isEmpty()){mergeMetadata(row,card(id,title,first(raw,"series_cover","cover","cover_url"),Math.max(raw.optInt("episode_cnt"),raw.optInt("episode_total_cnt"))));row.put("metadataPending",false);}}
                if(row.optString("title").isEmpty()){row.put("title","剧集信息暂未加载").put("metadataPending",true);}else if(metadata==null)row.put("metadataPending",true);snapshot.put(id,row);
            }
        }
        List<String> ids=new ArrayList<>(remote.keySet());Collections.sort(ids);
        for(int start=0;start<ids.size();start+=200){List<String> batch=ids.subList(start,Math.min(start+200,ids.size()));JSONArray numeric=new JSONArray();for(String id:batch)numeric.put(new BigInteger(id));JSONObject response=call(api,uid,current,deadline,"/reading/bookapi/read_progress/get/v","POST",null,new JSONObject().put("book_ids",new JSONObject().put("2",numeric)));JSONArray progress=response.optJSONArray("video_data");if(progress==null)progress=array(data(response),"video_data");if(progress.length()>200)throw new IOException("云端进度批次超过上限");
            Set<String> seen=new HashSet<>();for(int i=0;i<progress.length();i++){JSONObject raw=progress.optJSONObject(i);if(raw==null)throw new IOException("云端进度条目无效");String id=requiredId(raw.opt("series_id"));if(!batch.contains(id)||!seen.add(id))throw new IOException("云端进度条目身份不一致");JSONObject row=remote.get(id);long index=field(raw,"vid_index",10000),stamp=field(raw,"last_watch_time",9_007_199_254_740_991L);if(stamp>0&&stamp<100_000_000_000L)stamp*=1000;
                row.put("episodeCount",Math.min(10000,Math.max(row.optInt("episodeCount"),raw.optInt("episode_count"))));
                if(index>0&&(row.optInt("index")==0||stamp>row.optLong("updatedAt"))){if(row.optInt("index")!=index)row.put("vid","").put("positionMs",0).put("durationMs",0);row.put("index",index);if(stamp>row.optLong("updatedAt"))row.put("updatedAt",stamp);}snapshot.put(id,row);
            }
        }
        return remote;
    }
    private void merge(String uid,Map<String,JSONObject> remote)throws Exception{
        State next=state(uid).copy();Iterator<Map.Entry<String,Mutation>> queued=next.pending.entrySet().iterator();while(queued.hasNext()){Mutation mutation=queued.next().getValue();JSONObject cloud=remote.get(mutation.id);if(mutation.kind.equals("progress")&&cloud!=null&&cloud.optLong("updatedAt")>mutation.row.optLong("updatedAt"))queued.remove();}
        for(Map.Entry<String,JSONObject> entry:remote.entrySet()){
            String id=entry.getKey();JSONObject cloud=entry.getValue(),existing=next.entries.get(id),row=existing==null?copy(cloud):copy(existing);
            if(existing==null||(!next.pending.containsKey("progress:"+id)&&cloud.optLong("updatedAt")>row.optLong("updatedAt"))){long favorite=row.optLong("favoriteAt");row=copy(cloud);row.put("favoriteAt",favorite);}
            if(row.optString("title").isEmpty()||row.optBoolean("metadataPending")&&!cloud.optBoolean("metadataPending"))mergeMetadata(row,cloud);
            if(!next.pending.containsKey("favorite:"+id))row.put("favoriteAt",cloud.optLong("favoriteAt"));next.entries.put(id,row);
        }
        for(Map.Entry<String,JSONObject> entry:new ArrayList<>(next.entries.entrySet()))if(!remote.containsKey(entry.getKey())&&!next.pending.containsKey("favorite:"+entry.getKey())){JSONObject row=copy(entry.getValue());row.put("favoriteAt",0);next.entries.put(entry.getKey(),row);}
        commit(uid,next);
    }
    private void upload(Api api,String uid,BooleanSupplier current,long deadline,Mutation mutation)throws Exception{
        JSONObject e=mutation.row;
        if(mutation.kind.equals("favorite")){
            JSONObject row=new JSONObject().put("book_id",mutation.id).put("book_type",2).put("modify_time",Math.max(1,clock.getAsLong())).put("video_shelf_operate_type",mutation.value?0:1);
            call(api,uid,current,deadline,"/reading/bookapi/bookshelf/video/update/v","POST",null,new JSONObject().put("is_cancelled",false).put("shark_extra",new JSONObject().put("enter_from","0").put("previous_page","0").put("page_list","0").put("is_active_behavior","1").put("inactive_type","0")).put("update_bookshelf_video_list",new JSONArray().put(row)));return;
        }
        String vid=requiredId(e.opt("vid"));long stamp=e.getLong("updatedAt"),position=e.getLong("positionMs");int index=e.getInt("index");
        JSONObject history=new JSONObject().put("book_id",new BigInteger(mutation.id)).put("book_type",2).put("vid",new BigInteger(vid)).put("vid_index",index).put("chapter_index",0).put("current_play_position",position).put("player_accumulate_total_time",position).put("read_timestamp_ms",stamp).put("update_timestamp_ms",stamp).put("is_delete",false).put("is_interactive_game",false).put("is_listen_mode",false).put("is_multi_season",e.optBoolean("multiSeason")?1:0).put("season_index",e.optInt("seasonIndex")).put("duration",e.optLong("durationMs")).put("episode_cnt",e.optInt("episodeCount")).put("digged_count",0).put("user_digg",false).put("origin_novel_book_id",0).put("recent_reads",0).put("retain_video_play_time",0).put("series_play_cnt",0).put("tone_id",0).put("use_soft_delete",false).put("meet_guide_comment_tag",false).put("user_playlet_comment_flag",false);
        call(api,uid,current,deadline,"/reading/bookapi/read_history/update/v","POST",null,new JSONObject().put("update_datas",new JSONArray().put(history)));
        JSONObject progress=new JSONObject().put("book_id",mutation.id).put("book_type",2).put("item_id",vid).put("vid_index",index).put("current_play_time",position).put("player_cumulative_total_duration",position).put("read_timestamp_ms",stamp).put("progress_type",0).put("channel_id",0).put("cur_channel_id",0).put("check_timestamp",false).put("is_listen_mode",false).put("is_local_book",false).put("listen_and_read",false).put("page_index",0).put("page_progress_rate",0).put("paragraph_offset",0).put("tone_id",0);
        call(api,uid,current,deadline,"/reading/bookapi/read_progress/upload/v","POST",null,new JSONObject().put("books",new JSONArray().put(progress)));
    }
    private static JSONObject localProgress(JSONObject value)throws Exception{
        JSONObject row=LibraryStore.series(value);String vid=requiredId(value.opt("vid"));long index=integer(value.opt("index"),1,10000),position=integer(value.opt("positionMs"),0,86_400_000),stamp=integer(value.opt("updatedAt"),1,9_007_199_254_740_991L),duration=value.has("durationMs")?integer(value.opt("durationMs"),0,86_400_000):0;
        return row.put("vid",vid).put("index",index).put("positionMs",position).put("updatedAt",stamp).put("durationMs",duration).put("favoriteAt",0).put("seasonIndex",Math.max(0,value.optInt("seasonIndex"))).put("multiSeason",value.optBoolean("multiSeason"));
    }
    private static JSONObject card(String id,String title,String cover,int episodes)throws Exception{return new JSONObject().put("seriesId",id).put("title",clip(title,200)).put("coverUrl",clip(cover,4096)).put("description","").put("episodeCount",Math.max(0,Math.min(10000,episodes))).put("episodeLabel","").put("tags",new JSONArray()).put("favoriteAt",0).put("updatedAt",0).put("metadataPending",title.isEmpty());}
    private static void mergeMetadata(JSONObject target,JSONObject source)throws JSONException{for(String key:new String[]{"title","coverUrl","description","episodeCount","episodeLabel","tags","metadataPending"})if(source.has(key))target.put(key,source.get(key));}
    private static void fillMissingMetadata(JSONObject row,JSONObject local)throws JSONException{if(local==null)return;if(row.optString("title").isEmpty()){mergeMetadata(row,local);}if(row.optInt("seasonIndex")==0)row.put("seasonIndex",local.optInt("seasonIndex"));if(local.optBoolean("multiSeason"))row.put("multiSeason",true);}
    private static String first(JSONObject value,String...keys){for(String key:keys){String v=value.optString(key,"");if(!v.isEmpty())return v;}return"";}
    private static String clip(String value,int max){return value.length()>max?value.substring(0,max):value;}
    private static boolean validId(String id){return id!=null&&id.matches("[1-9][0-9]{0,29}");}
    private static String exactId(Object value){if(value instanceof String)return validId((String)value)?(String)value:"";if(value instanceof Byte||value instanceof Short||value instanceof Integer||value instanceof Long||value instanceof BigInteger){String id=value.toString();return validId(id)?id:"";}return"";}
    private static String requiredId(Object value)throws IOException{String id=exactId(value);if(id.isEmpty())throw new IOException("云端记录身份无效");return id;}
    private static String optionalId(Object value){return exactId(value);}
    private static long integer(Object value,long min,long max)throws IOException{try{if(!(value instanceof Number||value instanceof String))throw new NumberFormatException();String raw=String.valueOf(value);if(!raw.matches("[0-9]{1,16}(?:\\.0+)?"))throw new NumberFormatException();double n=Double.parseDouble(raw);if(!Double.isFinite(n)||n!=Math.rint(n)||n<min||n>max)throw new NumberFormatException();return(long)n;}catch(RuntimeException invalid){throw new IOException("云端记录数值无效");}}
    private static long field(JSONObject value,String key,long max)throws IOException{return !value.has(key)||value.isNull(key)?0:integer(value.opt(key),0,max);}
    private static long millis(JSONObject value,String key)throws IOException{if(!value.has(key)||value.isNull(key))return 0;Object raw=value.opt(key);try{if(!(raw instanceof Number||raw instanceof String))throw new NumberFormatException();double n=Double.parseDouble(String.valueOf(raw));if(!Double.isFinite(n)||n<0||n>86_400_000)throw new NumberFormatException();return(long)n;}catch(RuntimeException invalid){throw new IOException("云端进度毫秒值无效");}}
    private static boolean flag(JSONObject value,String key,boolean required)throws IOException{if(!value.has(key)||!required&&value.isNull(key)){if(required)throw new IOException("云端分页状态缺失");return false;}Object raw=value.opt(key);if(raw instanceof Boolean)return(Boolean)raw;if("1".equals(String.valueOf(raw))||"true".equals(raw))return true;if("0".equals(String.valueOf(raw))||"false".equals(raw))return false;throw new IOException("云端分页状态无效");}
    private static JSONObject data(JSONObject response)throws IOException{JSONObject result=response.optJSONObject("data");if(result==null)throw new IOException("云端响应缺少数据");return result;}
    private static JSONArray array(JSONObject value,String key)throws IOException{Object result=value.opt(key);if(!(result instanceof JSONArray))throw new IOException("云端响应缺少完整列表");return(JSONArray)result;}
    private static JSONObject copy(JSONObject object)throws JSONException{return new JSONObject(object.toString());}
    private static String digest(String value)throws Exception{byte[] result=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));StringBuilder s=new StringBuilder();for(byte b:result)s.append(String.format(Locale.ROOT,"%02x",b&255));return s.toString();}
    private static final class BoundedSnapshot {
        final Map<String,JSONObject> rows=new LinkedHashMap<>();final Map<String,Integer> sizes=new HashMap<>();long bytes;
        void put(String id,JSONObject row)throws IOException{int length=row.toString().getBytes(StandardCharsets.UTF_8).length;long total=bytes-sizes.getOrDefault(id,0)+length;if(total>12L*1024*1024||!rows.containsKey(id)&&rows.size()>=MAX_ENTRIES)throw new IOException("云端剧库快照超过单轮大小上限，原记录保留");rows.put(id,row);sizes.put(id,length);bytes=total;}
    }
    private static final class Mutation {
        final String kind,id;final long revision;final boolean value;final JSONObject row;
        Mutation(String kind,String id,long revision,boolean value,JSONObject row){this.kind=kind;this.id=id;this.revision=revision;this.value=value;this.row=row;}
        String key(){return kind+":"+id;}
        JSONObject json()throws JSONException{return new JSONObject().put("kind",kind).put("seriesId",id).put("revision",revision).put("value",value).put("row",row);}
    }
    private static final class State {
        final String uid;final LinkedHashMap<String,JSONObject> entries=new LinkedHashMap<>();final LinkedHashMap<String,Mutation> pending=new LinkedHashMap<>();final LinkedHashMap<String,Long> imported=new LinkedHashMap<>(),favoriteImported=new LinkedHashMap<>();long sequence,lastSync;
        State(String uid){this.uid=uid;}
        State copy(){State s=new State(uid);s.entries.putAll(entries);s.pending.putAll(pending);s.imported.putAll(imported);s.favoriteImported.putAll(favoriteImported);s.sequence=sequence;s.lastSync=lastSync;return s;}
        void enqueue(String kind,String id,boolean value,JSONObject row)throws Exception{String key=kind+":"+id;if(!pending.containsKey(key)&&pending.size()>=MAX_PENDING)throw new IOException("待同步记录已满，请先完成同步");if(sequence>=9_007_199_254_740_990L)throw new IOException("本机同步序列达到上限");pending.put(key,new Mutation(kind,id,++sequence,value,OfficialLibrary.copy(row)));}
        JSONObject json()throws Exception{JSONObject rows=new JSONObject(),imports=new JSONObject(),favoriteImports=new JSONObject();for(Map.Entry<String,JSONObject> e:entries.entrySet())rows.put(e.getKey(),e.getValue());for(Map.Entry<String,Long> e:imported.entrySet())imports.put(e.getKey(),e.getValue());for(Map.Entry<String,Long> e:favoriteImported.entrySet())favoriteImports.put(e.getKey(),e.getValue());JSONArray queue=new JSONArray();for(Mutation m:pending.values())queue.put(m.json());return new JSONObject().put("schema",1).put("ownerUid",uid).put("sequence",sequence).put("lastSyncAt",lastSync).put("entries",rows).put("pending",queue).put("guestImported",imports).put("guestFavoritesImported",favoriteImports);}
        static State parse(JSONObject value,String uid)throws Exception{
            if(integer(value.opt("schema"),1,1)!=1||!uid.equals(value.optString("ownerUid")))throw new IOException("官方本机剧库身份不匹配");State s=new State(uid);s.sequence=integer(value.opt("sequence"),0,9_007_199_254_740_991L);s.lastSync=integer(value.opt("lastSyncAt"),0,9_007_199_254_740_991L);JSONObject rows=value.optJSONObject("entries"),imports=value.optJSONObject("guestImported");JSONArray queue=value.optJSONArray("pending");if(rows==null||imports==null||queue==null||rows.length()>MAX_ENTRIES||queue.length()>MAX_PENDING)throw new IOException("官方本机剧库结构无效");
            for(Iterator<String> it=rows.keys();it.hasNext();){String id=requiredId(it.next());JSONObject row=rows.getJSONObject(id);if(!id.equals(row.optString("seriesId")))throw new IOException("官方本机剧库条目身份不匹配");s.entries.put(id,OfficialLibrary.copy(row));}
            for(int i=0;i<queue.length();i++){JSONObject raw=queue.getJSONObject(i);String kind=raw.getString("kind"),id=requiredId(raw.opt("seriesId"));long revision=integer(raw.opt("revision"),1,s.sequence);if(!kind.equals("favorite")&&!kind.equals("progress"))throw new IOException("官方同步队列类型无效");JSONObject row=raw.getJSONObject("row");if(!id.equals(row.optString("seriesId"))||s.pending.containsKey(kind+":"+id))throw new IOException("官方同步队列身份无效");if(kind.equals("progress"))row=localProgress(row);s.pending.put(kind+":"+id,new Mutation(kind,id,revision,raw.getBoolean("value"),OfficialLibrary.copy(row)));}
            JSONObject favoriteImports=value.has("guestFavoritesImported")?value.optJSONObject("guestFavoritesImported"):new JSONObject();if(favoriteImports==null||imports.length()>MAX_ENTRIES||favoriteImports.length()>MAX_ENTRIES)throw new IOException("官方导入记录结构无效");
            for(Iterator<String> it=imports.keys();it.hasNext();){String id=requiredId(it.next());s.imported.put(id,integer(imports.opt(id),1,9_007_199_254_740_991L));}for(Iterator<String> it=favoriteImports.keys();it.hasNext();){String id=requiredId(it.next());s.favoriteImported.put(id,integer(favoriteImports.opt(id),1,9_007_199_254_740_991L));}return s;
        }
    }
    private static final class FilePersistence implements Persistence {
        final File directory;FilePersistence(File directory){this.directory=directory;}
        private File file(String uid)throws Exception{if(!validId(uid))throw new IOException("官方账号身份无效");return new File(directory,digest(uid)+".json");}
        public JSONObject read(String uid)throws Exception{File file=file(uid);if(!file.exists())return null;if(!file.isFile()||file.length()>MAX_FILE_BYTES)throw new IOException("官方本机剧库文件无效");ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(InputStream in=new FileInputStream(file)){byte[] b=new byte[32768];int n;while((n=in.read(b))!=-1){if(bytes.size()+n>MAX_FILE_BYTES)throw new IOException("官方本机剧库文件过大");bytes.write(b,0,n);}}return new JSONObject(bytes.toString("UTF-8"));}
        public void write(String uid,JSONObject value)throws Exception{if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("官方本机剧库目录创建失败");byte[] bytes=value.toString().getBytes(StandardCharsets.UTF_8);if(bytes.length>MAX_FILE_BYTES)throw new IOException("官方本机剧库超过存储上限");File target=file(uid),part=new File(directory,target.getName()+"."+UUID.randomUUID()+".part");try{try(FileOutputStream out=new FileOutputStream(part)){out.write(bytes);out.getFD().sync();}Files.move(part.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}finally{part.delete();}}
    }
}
