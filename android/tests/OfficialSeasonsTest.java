package io.github.jackotom.hongguo.android;
import org.json.*;
public final class OfficialSeasonsTest {
 static int checks;static void check(boolean ok){checks++;if(!ok)throw new AssertionError("check="+checks);}
 interface Run{void run()throws Exception;}static void bad(Run f)throws Exception{checks++;try{f.run();throw new AssertionError("accepted invalid");}catch(java.io.IOException expected){}}
 static final String SID="7390000000000000001";
 static JSONObject row(String id,String title,int count)throws Exception{return new JSONObject().put("series_id_str",id).put("title",title).put("vid","7390000000000000099").put("episode_cnt",count).put("secondary_info_list",new JSONArray().put(new JSONObject().put("content","第2季")));}
 static JSONObject payload(JSONArray rows)throws Exception{return new JSONObject().put("code",0).put("data",new JSONArray().put(new JSONObject().put("video_data",rows)));}
 public static void main(String[]args)throws Exception{
  JSONObject q=OfficialSeasons.query(SID);check(q.length()==12);check(q.getString("book_id").equals(SID)&&q.getString("filter_ids").equals(SID));check(q.getString("from").equals("single_col_same_ip_series")&&q.getString("limit").equals("0"));
  JSONArray input=new JSONArray().put(row(SID,"不同命名也由官方确认",10)).put(row("7390000000000000002","第二季",0).put("subscribe_item",new JSONObject().put("item_id","7399999999999999999"))).put(row("7390000000000000003","禁播季",10).put("disable_play",true));
  JSONObject out=OfficialSeasons.parse(payload(input),SID);JSONArray items=out.getJSONArray("items");check(items.length()==3);check(items.getJSONObject(0).getString("seriesId").equals(SID));check(items.getJSONObject(0).getBoolean("playable"));check(items.getJSONObject(0).getString("seasonLabel").equals("第2季"));check(items.getJSONObject(1).getBoolean("upcoming")&&!items.getJSONObject(1).getBoolean("playable")&&items.getJSONObject(1).getString("vid").isEmpty());check(!items.getJSONObject(1).has("reservationId"));check(!items.getJSONObject(2).getBoolean("playable"));
  JSONArray dup=new JSONArray().put(row(SID,"当前",10)).put(row(SID,"重复",10));check(OfficialSeasons.parse(payload(dup),SID).getJSONArray("items").length()==1);
  bad(()->OfficialSeasons.parse(payload(new JSONArray().put(row("7390000000000000002","错族",1))),SID));bad(()->OfficialSeasons.parse(new JSONObject().put("code",1).put("data",new JSONArray()),SID));bad(()->OfficialSeasons.parse(new JSONObject().put("code",0),SID));bad(()->OfficialSeasons.query("0"));bad(()->OfficialSeasons.query("123/456"));bad(()->OfficialSeasons.parse(payload(new JSONArray()).put("error_code",99),SID));
  bad(()->OfficialSeasons.parse(payload(new JSONArray()).put("status_code",1),SID));
  check(OfficialSeasons.parse(payload(new JSONArray()),SID).getJSONArray("items").length()==0);
  JSONObject hugeTitle=row(SID,"x".repeat(201),10);check(OfficialSeasons.parse(payload(new JSONArray().put(hugeTitle)),SID).getJSONArray("items").length()==0);
  JSONObject unsafe=row(SID,"当前",10).put("cover","https://user:pw@host.example/a");check(OfficialSeasons.parse(payload(new JSONArray().put(unsafe)),SID).getJSONArray("items").getJSONObject(0).getString("coverUrl").isEmpty());
  JSONArray many=new JSONArray();for(int i=0;i<80;i++)many.put(row("739000000000000"+String.format("%04d",100+i),"季"+i,20));many.put(row(SID,"当前",20));JSONObject cap=OfficialSeasons.parse(payload(many),SID);check(cap.getBoolean("truncated")&&cap.getJSONArray("items").length()==40);check(cap.getJSONArray("items").getJSONObject(39).getString("seriesId").equals(SID));
  bad(()->OfficialSeasons.parse(new JSONObject().put("code",0).put("data",new JSONArray().put("bad")),SID));bad(()->OfficialSeasons.parse(new JSONObject().put("code",0).put("data",new JSONArray().put(new JSONObject().put("video_data",new JSONObject()))),SID));
  JSONObject ctrl=row(SID,"错\n标题",1);check(OfficialSeasons.parse(payload(new JSONArray().put(ctrl)),SID).getJSONArray("items").length()==0);
  System.out.println("OFFICIAL_SEASONS_OK checks="+checks+" writes=0 autoCrossSeason=false");
 }
}
