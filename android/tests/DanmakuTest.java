package io.github.jackotom.hongguo.android;

import java.nio.file.*;
import java.util.*;
import org.json.*;

/** Offline golden signatures and real parser/media-clock policy; no remote services or Android device. */
public final class DanmakuTest {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    interface Action{void run()throws Exception;}
    private static void rejects(Action action,String message)throws Exception{try{action.run();}catch(Exception expected){checks++;return;}throw new AssertionError(message);}
    private static JSONObject comment(String vid,Object time,Object text,String id)throws Exception{return new JSONObject().put("comment",new JSONObject().put("comment_id",id).put("common",new JSONObject().put("group_id",vid).put("status",1).put("content",new JSONObject().put("text",text))).put("expand",new JSONObject().put("offset_time",time)));}
    private static JSONObject page(JSONArray rows,boolean more,Object cursor)throws Exception{return new JSONObject().put("code",0).put("data",new JSONObject().put("data_list",rows).put("common_list_info",new JSONObject().put("has_more",more).put("cursor",cursor)));}
    public static void main(String[] args)throws Exception{
        JSONArray vectors=new JSONObject(Files.readString(Path.of(args[0]))).getJSONArray("vectors");
        for(int n=0;n<vectors.length();n++){
            JSONObject v=vectors.getJSONObject(n);String q=DanmakuSigner.query(v.getLong("stamp_ms"),v.getString("device_id"),v.getString("install_id"));check(q.equals(v.getString("raw_query")),"canonical query vector "+n);byte[]nonce=DanmakuSigner.unhex(v.getString("nonce_hex"));Map<String,String>h=DanmakuSigner.headers(q,v.getLong("stamp"),nonce);
            check(h.get("X-Gorgon").equals(v.getString("x_gorgon")),"Gorgon vector "+n);check(h.get("X-Ladon").equals(v.getString("x_ladon")),"Ladon vector "+n);check(h.get("X-Argus").equals(v.getString("x_argus")),"Argus vector "+n);check(h.get("X-Khronos").equals(v.getString("x_khronos")),"Khronos vector "+n);check(h.get("X-SS-Req-Ticket").equals(v.getString("x_ss_req_ticket")),"ticket vector "+n);check(h.get("X-SS-STUB").isEmpty(),"POST client intentionally empty STUB");
        }
        check(DanmakuSigner.hex(DanmakuSigner.sm3(DanmakuSigner.bytes("abc"))).equals("66c7f0f462eeedd9d1f2d46bdc10e4e24167c4875cf2f7a2297da02b8f4ba8e0"),"SM3 standard abc");
        check(DanmakuSigner.hex(DanmakuSigner.sm3(DanmakuSigner.bytes("abcd".repeat(16)))).equals("debe9ff92275b8a138604889c18e5a4d6fdb70e5387e5765293dcba39c0c5732"),"SM3 multi block");
        check(DanmakuSigner.hex(DanmakuSigner.sm3(new byte[0])).equals("1ab21d8355cfa17f8e61194831e81a8f22bec8c728fefb747ed035eb5082aa2b"),"SM3 empty");
        rejects(()->DanmakuSigner.headers("a=1",1,new byte[10]),"short nonce accepted");rejects(()->DanmakuSigner.query(1,"0","1"),"invalid identity accepted");
        JSONObject request=DanmakuClient.body("123","456",30000,120000);check(request.getInt("comment_type")==20&&request.getInt("comment_source")==601&&request.getInt("server_channel")==1000,"complete POST protocol constants");check(request.getJSONObject("business_param").getLong("start_offset_time")==30000,"media milliseconds request");check(request.getJSONObject("business_param").getJSONArray("need_danmaku_guide_type").length()==4,"POST client guides not GET scalar");rejects(()->DanmakuClient.body("0","456",0,1000),"zero vid accepted");rejects(()->DanmakuClient.body("1","2",-1,1000),"negative start accepted");rejects(()->DanmakuClient.body("1","2",0,86400001),"unbounded duration accepted");
        JSONArray rows=new JSONArray().put(comment("123",1000," hello\n世界 ","a")).put(comment("999",1100,"wrong episode","b")).put(comment("123",-1,"bad time","c")).put(comment("123","2000","later","d")).put(comment("123",1000," hello\n世界 ","a"));
        DanmakuClient.Page parsed=DanmakuClient.parse(page(rows,true,"{\"end_offset_time\":30000}"),"123");check(parsed.items.size()==2,"parse filters wrong video, bad time, duplicates");check(parsed.items.get(0).text.equals("hello 世界"),"clean controls");check(parsed.items.get(1).timeMs==2000,"string milliseconds preserved");check(parsed.more&&parsed.next==30000,"JSON cursor time boundary");
        JSONObject noMore=page(rows,false,new JSONObject().put("end_offset_time",60000));noMore.getJSONObject("data").put("extra",new JSONObject().put("next_query_danmaku_list_time",90000));check(!DanmakuClient.parse(noMore,"123").more&&DanmakuClient.parse(noMore,"123").next==0,"explicit no-more overrides cursors");
        rejects(()->DanmakuClient.parse(new JSONObject().put("code",0),"123"),"invalid empty response silently accepted");rejects(()->DanmakuClient.parse(page(rows,false,"").put("code",1),"123"),"business error accepted");
        JSONObject hidden=comment("123",0,"hidden","x");hidden.getJSONObject("comment").getJSONObject("common").put("status",0);check(DanmakuClient.parse(page(new JSONArray().put(hidden),false,""),"123").items.isEmpty(),"non-public status omitted");
        check(DanmakuClient.clean("x".repeat(201)).isEmpty(),"oversize text omitted");check(DanmakuClient.clean(123).isEmpty(),"nonstring text omitted");check(DanmakuClient.clean("a\u2028b\u0000c").equals("a b c"),"line separators cleaned");check(DanmakuClient.color("#abc")==0xffaabbcc,"short hex color");check(DanmakuClient.color("#112233")==0xff112233,"hex color");check(DanmakuClient.color("0xff00ff")==0xffff00ff,"numeric hex color");check(DanmakuClient.color(-1)==-1&&DanmakuClient.color("bad")==-1,"invalid color white");
        check(DanmakuClient.number("1000",-1)==1000&&DanmakuClient.number(true,-1)==-1&&DanmakuClient.number("1e3",-1)==-1,"strict number types");check(DanmakuClient.validDevice("1234567890123456789")&&!DanmakuClient.validDevice("1"),"persistent identity shape");
        List<DanmakuClient.Item> items=Arrays.asList(new DanmakuClient.Item("1","one",1000,-1),new DanmakuClient.Item("2","two",1100,-1),new DanmakuClient.Item("3","three",1200,-1));DanmakuTimeline clock=new DanmakuTimeline();clock.replace(items,0);
        check(clock.advance(1000,false,800,t->80).isEmpty(),"pause never emits");List<DanmakuTimeline.Visible> visible=clock.advance(1000,true,800,t->80);check(visible.size()==1,"media timeline emits");float x=visible.get(0).x(1000,800);visible=clock.advance(1000,false,800,t->80);check(visible.size()==1&&visible.get(0).x(1000,800)==x,"paused clock freezes position");
        check(clock.advance(1200,true,800,t->80).size()==3,"distinct lanes admit dense comments");check(clock.advance(1300,true,800,t->80).size()==3,"no duplicate repeated tick");check(clock.advance(1500,true,800,t->80).get(0).x(1500,800)<x,"motion follows media time");clock.seek(1000);check(clock.advance(1000,true,800,t->80).size()==1,"seek replays correct current timeline");check(clock.advance(50000,true,800,t->80).isEmpty(),"forward seek never dumps skipped comments");clock.clear();check(clock.activeCount()==0,"episode/destroy clear");
        List<DanmakuClient.Item> dense=new ArrayList<>();for(int i=0;i<1000;i++)dense.add(new DanmakuClient.Item("d"+i,"text",0,-1));clock.replace(dense,0);check(clock.advance(0,true,800,t->80).size()==4,"burst density limited to four lanes");check(clock.advance(1,true,800,t->80).size()==4,"suppressed burst not backlogged");
        System.out.println("PASS DanmakuTest checks="+checks+" goldenVectors="+vectors.length()+" parser=true mediaClock=true readOnly=true networkRequests=0 deviceOperations=0");
    }
}
