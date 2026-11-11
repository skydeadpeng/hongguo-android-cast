package io.github.jackotom.hongguo.android;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Offline contract for the near-seven-day new-release catalog. */
public final class NewReleaseCatalogTest {
    static int groups,checks;
    interface Checked {void run()throws Exception;}
    static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    static void group(String name,Checked test)throws Exception{test.run();groups++;System.out.println("PASS "+name);}
    static JSONObject ok(JSONObject data)throws JSONException{return new JSONObject().put("code",0).put("data",data);}
    static JSONObject row(String id,String title,String tagText,String subtitle,boolean today)throws JSONException{
        JSONObject item=new JSONObject().put("series_id",id).put("title",title).put("episode_cnt",12)
            .put("video_desc"," 简介 ").put("cover","https://cdn.example.net/"+id+".jpg");
        if(tagText!=null)item.put("tag_info",new JSONObject().put("text",tagText));
        JSONArray subs=new JSONArray();subs.put(new JSONObject().put("content",today?"今日上新":subtitle==null?"爱情":subtitle));
        item.put("sub_title_list",subs);return item;
    }
    static JSONObject page(Object... rows)throws JSONException{
        JSONArray list=new JSONArray();for(Object item:rows)list.put(item);
        return ok(new JSONObject().put("video_data",list).put("has_more",false).put("next_offset",0).put("session_id","s1"));
    }
    static final class Replies implements OfficialApiClient.Cloud {
        final Queue<Object> replies=new ArrayDeque<>();final AtomicInteger posts=new AtomicInteger();
        final java.util.List<String> bodies=new java.util.ArrayList<>();String lastPath=null;
        public JSONObject call(String path,String method,JSONObject body)throws Exception{
            posts.incrementAndGet();lastPath=path;bodies.add(body==null?"":body.toString());
            Object next=replies.isEmpty()?page():replies.remove();
            if(next instanceof Exception)throw(Exception)next;
            return(JSONObject)next;
        }
    }
    public static void main(String[]args)throws Exception{
        group("the catalog posts the online-time seven-day body to the fixed endpoint",()->{
            Replies replies=new Replies();replies.replies.add(page(row("7702453391682702360","合成新剧",null,null,true)));
            try(OfficialApiClient client=new OfficialApiClient(replies)){NewReleaseCatalog.fetch(client,1,System.nanoTime()+1_000_000_000L);}
            check(replies.posts.get()==2,"one request per genre");
            check("/reading/distribution/category/landpage/v/".equals(replies.lastPath),"the fixed category endpoint");
            JSONObject body=new JSONObject(replies.bodies.get(0));
            check(body.optInt("limit")==50&&body.optInt("offset")==0&&"default".equals(body.optString("req_scene")),"the page body bounds and scene");
            check("online_time".equals(body.optJSONObject("select_items").optJSONArray("sort").opt(0)),"online-time order");
            check("days_7".equals(body.optJSONObject("select_items").optJSONArray("online_time").opt(0)),"the seven-day window");
            check("short_play".equals(body.optJSONObject("select_items").optJSONArray("genre").opt(0)),"the real-drama genre first");
        });
        group("rows are strictly projected and unknown shapes are skipped",()->{
            Replies replies=new Replies();replies.replies.add(page(
                row("7702453391682702360"," 合成新剧 ","红果首发","爱情",true),
                new JSONObject().put("series_id","bad").put("title","非数字编号"),
                new JSONObject().put("title","缺编号"),
                new JSONObject().put("series_id","7702453391682702361"),
                null));
            JSONArray items;
            try(OfficialApiClient client=new OfficialApiClient(replies)){items=NewReleaseCatalog.fetch(client,1,System.nanoTime()+1_000_000_000L);}
            check(items.length()==1,"only the valid row survives");
            JSONObject item=items.optJSONObject(0);
            check("7702453391682702360".equals(item.optString("series_id"))&&"合成新剧".equals(item.optString("title")),"identity and cleaned title");
            check(item.optBoolean("released_today"),"今日上新 is promoted out of the subtitles");
            check(!item.optJSONArray("tags").toString().contains("今日上新")&&!item.optJSONArray("tags").toString().contains("新剧"),"marker labels never become tags");
            check("https://cdn.example.net/7702453391682702360.jpg".equals(item.optString("cover")),"the https cover");
        });
        group("both genres are read, deduped and capped",()->{
            Replies replies=new Replies();
            replies.replies.add(page(row("7001","真人新剧",null,null,false)));
            replies.replies.add(new JSONObject(page(row("7002","AI新剧",null,null,true)).toString().replace("\"has_more\":false","\"has_more\":true").replace("\"next_offset\":0","\"next_offset\":50")));
            replies.replies.add(page(row("7001","真人新剧",null,null,true)));
            JSONArray items;
            try(OfficialApiClient client=new OfficialApiClient(replies)){items=NewReleaseCatalog.fetch(client,2,System.nanoTime()+1_000_000_000L);}
            check(replies.posts.get()==3,"two genres plus the AI second page");
            check(items.length()==2,"deduped across genres");
            check(items.optJSONObject(0).optString("series_id").equals("7001")&&items.optJSONObject(0).optBoolean("released_today"),"the today mark merges across genres");
            check(items.optJSONObject(1).optString("series_id").equals("7002"),"the AI row follows");
        });
        group("cursor stalls and business failures end a genre without killing the other",()->{
            Replies replies=new Replies();
            replies.replies.add(new JSONObject().put("code",7).put("message","error"));
            replies.replies.add(page(row("7003","AI 恢复剧",null,null,false)));
            JSONArray items;
            try(OfficialApiClient client=new OfficialApiClient(replies)){items=NewReleaseCatalog.fetch(client,1,System.nanoTime()+1_000_000_000L);}
            check(items.length()==1&&"7003".equals(items.optJSONObject(0).optString("series_id")),"a real-drama failure keeps the AI results");
            Replies stalled=new Replies();
            stalled.replies.add(new JSONObject(ok(new JSONObject().put("video_data",new JSONArray()).put("has_more",true).put("next_offset",0).put("session_id","")).toString()));
            try(OfficialApiClient client=new OfficialApiClient(stalled)){NewReleaseCatalog.fetch(client,1,System.nanoTime()+1_000_000_000L);}
            check(stalled.posts.get()==2,"the stalled genre stops and the other genre still runs");
        });
        System.out.println("NEW_RELEASE_CATALOG_OK groups="+groups+" checks="+checks+" networkRequests=0");
    }
}
