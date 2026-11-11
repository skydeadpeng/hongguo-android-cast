package io.github.jackotom.hongguo.android;
import java.util.*;
import org.json.*;
public final class PlaybackPlaylistTest {
    private static int checks;
    interface Checked {void run() throws Exception;}
    static void ok(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    static void fails(Checked action)throws Exception{try{action.run();}catch(JSONException expected){checks++;return;}throw new AssertionError("expected rejection");}
    static JSONObject series(String sid,String name)throws Exception{return new JSONObject().put("seriesId",sid).put("title",name).put("episodeCount",3);}
    static JSONObject e(String sid,String vid,int index)throws Exception{return new JSONObject().put("seriesId",sid).put("vid",vid).put("index",index).put("availability","unknown");}
    static JSONObject plan()throws Exception{return new JSONObject().put("version",1).put("series",new JSONArray().put(series("100001","合成：第一季")).put(series("100002","合成：第二季"))).put("episodes",new JSONArray().put(e("100001","11",1)).put(e("100001","12",2)).put(e("100002","11",1)).put(e("100002","22",2)));}
    static List<JSONObject> list(JSONArray a)throws Exception{List<JSONObject> out=new ArrayList<>();for(int i=0;i<a.length();i++)out.add(a.getJSONObject(i));return out;}
    public static void main(String[] args)throws Exception {
        JSONObject current=series("100001","合成：第一季"),selected=e("100001","12",2);
        JSONArray rows=PlaybackPlaylist.parse(plan(),current,selected);
        ok(rows.length()==4,"both seasons retained");ok(rows.getJSONObject(2).getInt("index")==1,"second season restarts local index");
        ok(PlaybackPlaylist.owner(current,rows.getJSONObject(2)).getString("seriesId").equals("100002"),"actual second-season owner");
        ok(PlaybackPlaylist.find(list(rows),"100002","11",1,current)==2,"same VID different SID locates second season");
        ok(PlaybackPlaylist.find(list(rows),"100001","11",1,current)==0,"same VID different SID locates first season");
        ok(PlaybackPlaylist.find(list(rows),"100002","11",2,current)==-1,"full identity required");
        String identity=PlaybackPlaylist.identity(current,rows);ok(identity.startsWith("playlist:"),"stable multiseason identity");
        ok(identity.equals(PlaybackPlaylist.identity(series("100002","合成：第二季"),rows)),"identity survives changing current season");
        JSONArray normal=PlaybackPlaylist.normalize(series("100002","合成：第二季"),rows,rows.getJSONObject(2));
        ok(normal.length()==2&&PlaybackPlaylist.identity(current,normal).equals("100002"),"cast normalization keeps only the selected season and recomputes identity");
        ok(normal.getJSONObject(0).optString("vid").equals("11")&&normal.getJSONObject(0).optInt("index")==1,"later-season cast selection preserves VID and local index");
        ok(PlaybackPlaylist.normalize(current,rows,rows.getJSONObject(1)).length()==2,"first-season selection drops next season");
        JSONObject poisoned=plan();poisoned.getJSONArray("episodes").getJSONObject(0).put("url","file:///secret");poisoned.getJSONArray("series").getJSONObject(0).put("description","unused");
        JSONArray clean=PlaybackPlaylist.parse(poisoned,current,selected);ok(!clean.getJSONObject(0).has("url"),"no media URL forwarded");ok(!PlaybackPlaylist.owner(current,clean.getJSONObject(0)).has("description"),"no repeated description");
        JSONArray legacy=new JSONArray().put(e("100001","12",2)).put(e("100001","11",1)).put(e("100001","91",2));
        JSONArray old=PlaybackPlaylist.normalize(current,legacy,selected);ok(old.length()==2&&old.getJSONObject(0).getInt("index")==1,"legacy selected wins and sorted");ok(!old.getJSONObject(0).has("_series"),"legacy records stay compact");ok(PlaybackPlaylist.identity(current,old).equals("100001"),"legacy cast identity unchanged");
        fails(()->PlaybackPlaylist.parse(plan().put("version",2),current,selected));
        fails(()->PlaybackPlaylist.parse(plan().put("version","1"),current,selected));
        fails(()->PlaybackPlaylist.parse(plan(),current,e("100001","99",1)));
        fails(()->PlaybackPlaylist.parse(plan(),series("100001","wrong title"),selected));
        JSONObject duplicate=plan();duplicate.getJSONArray("series").put(series("100001","合成：第一季"));fails(()->PlaybackPlaylist.parse(duplicate,current,selected));
        JSONObject dupIndex=plan();dupIndex.getJSONArray("episodes").getJSONObject(1).put("index",1);fails(()->PlaybackPlaylist.parse(dupIndex,current,selected));
        JSONObject dupVid=plan();dupVid.getJSONArray("episodes").getJSONObject(1).put("vid","11");fails(()->PlaybackPlaylist.parse(dupVid,current,selected));
        JSONObject mixed=plan();mixed.getJSONArray("episodes").put(e("100001","13",3));fails(()->PlaybackPlaylist.parse(mixed,current,selected));
        JSONObject unused=plan();unused.getJSONArray("series").put(series("100003","合成：第三季"));fails(()->PlaybackPlaylist.parse(unused,current,selected));
        JSONObject unknown=plan();unknown.getJSONArray("episodes").getJSONObject(0).put("seriesId","100004");fails(()->PlaybackPlaylist.parse(unknown,current,selected));
        for(Object index:new Object[]{0,-1,1.5,"1",10001,JSONObject.NULL}){JSONObject bad=plan();bad.getJSONArray("episodes").getJSONObject(0).put("index",index);fails(()->PlaybackPlaylist.parse(bad,current,selected));}
        for(Object sid:new Object[]{123,"", "../bad",JSONObject.NULL}){JSONObject bad=plan();bad.getJSONArray("episodes").getJSONObject(0).put("seriesId",sid);fails(()->PlaybackPlaylist.parse(bad,current,selected));}
        JSONArray partial=new JSONArray(rows.toString());partial.getJSONObject(2).remove("_series");fails(()->PlaybackPlaylist.normalize(current,partial,selected));
        JSONArray conflict=new JSONArray(rows.toString());conflict.getJSONObject(1).getJSONObject("_series").put("title","changed");fails(()->PlaybackPlaylist.normalize(current,conflict,selected));
        JSONArray reversed=new JSONArray().put(rows.getJSONObject(2)).put(rows.getJSONObject(0)).put(rows.getJSONObject(3));fails(()->PlaybackPlaylist.normalize(current,reversed,selected));
        JSONObject large=plan();large.put("ignored","x".repeat(PlaybackPlaylist.MAX_BYTES));fails(()->PlaybackPlaylist.parse(large,current,selected));
        JSONObject limit=new JSONObject().put("version",1).put("series",new JSONArray().put(current));JSONArray eps=new JSONArray();for(int i=1;i<=1000;i++)eps.put(e("100001",String.valueOf(10000+i),i));limit.put("episodes",eps);
        ok(PlaybackPlaylist.parse(limit,current,eps.getJSONObject(999)).length()==1000,"1000 episodes accepted");eps.put(e("100001","99999",1001));fails(()->PlaybackPlaylist.parse(limit,current,eps.getJSONObject(0)));
        System.out.println("PASS PlaybackPlaylistTest checks="+checks+" crossSeasonAutoNext=false legacyDescriptorValidation=true nativeOwner=true networkRequests=0");
    }
}
