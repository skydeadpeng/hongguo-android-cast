package io.github.jackotom.hongguo.android;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.*;
import java.util.*;
import java.util.function.Consumer;
import org.json.*;

/** Uses its own anonymous registered identity, never account credentials. */
final class OfficialSeriesService {
    private final SharedPreferences prefs;
    private final LinkedHashMap<String,Entry> cache=new LinkedHashMap<String,Entry>(16,.75f,true){@Override protected boolean removeEldestEntry(Map.Entry<String,OfficialSeriesService.Entry> e){return size()>32;}};
    private static final class Entry{final String json;final long at;Entry(JSONObject value){json=value.toString();at=System.nanoTime();}}
    OfficialSeriesService(Context context){prefs=context.getApplicationContext().getSharedPreferences("hongguo_series_device_v1",Context.MODE_PRIVATE);}
    JSONObject load(String sid,Consumer<OfficialApiClient> active) throws Exception {
        OfficialSeasons.query(sid);if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("系列剧读取已取消");
        synchronized(cache){Entry e=cache.get(sid);if(e!=null&&System.nanoTime()-e.at<600_000_000_000L)return new JSONObject(e.json);}
        String saved=prefs.getString("session","");JSONObject session=saved.isEmpty()?OfficialApiClient.newSession():new JSONObject(saved);
        session.remove("token");JSONObject cookies=session.optJSONObject("cookies"),clean=new JSONObject();
        for(String key:new String[]{"install_id","store-region","store-region-src","ttreq","odin_tt"})if(cookies!=null&&cookies.opt(key) instanceof String)clean.put(key,cookies.getString(key));session.put("cookies",clean);
        OfficialApiClient client=new OfficialApiClient(session);client.deadlineNanos(System.nanoTime()+45_000_000_000L);active.accept(client);
        try{
            if(!session.optBoolean("registered",false)){
                client.registerDevice();if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("系列剧读取已取消");
                JSONObject registered=client.session();registered.remove("token");
                if(!prefs.edit().putString("session",registered.toString()).commit()){
                    if(saved.isEmpty())prefs.edit().remove("session").commit();else prefs.edit().putString("session",saved).commit();
                    throw new IOException("系列剧设备状态保存未完成");
                }
            }
            JSONObject result=OfficialSeasons.parse(client.cloud("/reading/bookapi/plan/v","GET",OfficialSeasons.query(sid),null,false),sid);
            if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("系列剧读取已取消");
            synchronized(cache){if(result.getJSONArray("items").length()>0){cache.put(sid,new Entry(result));int chars=0;for(Entry e:cache.values())chars+=e.json.length();while(chars>524288&&!cache.isEmpty()){String key=cache.keySet().iterator().next();chars-=cache.remove(key).json.length();}}}
            return result;
        }finally{client.close();}
    }
}
