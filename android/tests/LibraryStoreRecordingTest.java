package io.github.jackotom.hongguo.android;
import java.lang.reflect.*;import org.json.*;
public final class LibraryStoreRecordingTest {
 static int checks,groups;static void check(boolean ok,String msg){checks++;if(!ok)throw new AssertionError(msg);}
 static JSONObject series()throws Exception{return new JSONObject().put("seriesId","123456789").put("title","原本地剧").put("seasonIndex",3).put("multiSeason",true);}
 static JSONObject episode()throws Exception{return new JSONObject().put("vid","987654321").put("index",2);}
 static void record(LibraryStore store,JSONObject series,JSONObject episode,long position,long duration)throws Exception{
  Method m;try{m=LibraryStore.class.getDeclaredMethod("history",JSONObject.class,JSONObject.class,long.class,long.class);}catch(NoSuchMethodException missing){throw new AssertionError("actual LibraryStore must accept measured duration for official progress");}m.invoke(store,series,episode,position,duration);
 }
 public static void main(String[]args)throws Exception{
  LibraryStore.setHistoryFactory(null);SearchHistoryStoreTest.AppContext ctx=new SearchHistoryStoreTest.AppContext();LibraryStore store=new LibraryStore(ctx);
  record(store,series(),episode(),4321,98765);JSONObject row=store.library().getJSONArray("history").getJSONObject(0);check(row.getLong("positionMs")==4321&&row.getLong("durationMs")==98765,"guest actual position and duration persisted");check(row.getInt("seasonIndex")==3&&row.getBoolean("multiSeason"),"season metadata survives recording");check(new LibraryStore(ctx.reopen()).seriesHistory("123456789").getLong("durationMs")==98765,"duration survives restart");groups++;
  store.history(series(),episode(),5678);row=store.seriesHistory("123456789");check(row.getLong("durationMs")==98765&&row.getLong("positionMs")==5678,"legacy same episode keeps known duration");JSONObject next=new JSONObject(episode().toString()).put("vid","111111111").put("index",3);store.history(series(),next,0);check(store.seriesHistory("123456789").getLong("durationMs")==0,"different episode never inherits foreign duration");groups++;
  store.toggle(LibraryStore.series(series()));row=store.library().getJSONArray("favorites").getJSONObject(0);check(row.getLong("favoriteAt")>0,"new guest favorite records an import revision timestamp");check(store.toggle(LibraryStore.series(series())).getJSONArray("favorites").length()==0,"guest unfavorite unchanged");groups++;
  JSONObject clean=LibraryStore.series(series());check(clean.getInt("seasonIndex")==3&&clean.getBoolean("multiSeason"),"compact native series keeps provided season metadata");JSONObject plain=LibraryStore.series(new JSONObject().put("seriesId","555555555").put("title","无分季"));check(!plain.has("seasonIndex")&&!plain.has("multiSeason"),"unknown season not fabricated");for(Object bad:new Object[]{-1,1.5,10001,"3",true}){boolean denied=false;try{LibraryStore.series(new JSONObject(series().toString()).put("seasonIndex",bad));}catch(JSONException expected){denied=true;}check(denied,"invalid season rejected "+bad);}groups++;
  final JSONObject[] captured={null};LibraryStore.setHistoryFactory(()->new LibraryStore.HistoryAccess(){public JSONArray rows(){return new JSONArray();}public void record(JSONObject value){captured[0]=value;}});
  try{LibraryStore account=new LibraryStore(ctx);record(account,series(),episode(),5000,180000);check(captured[0].getLong("durationMs")==180000&&captured[0].getLong("positionMs")==5000,"official provider receives measured media duration");check(captured[0].getInt("seasonIndex")==3,"official provider receives season metadata");}finally{LibraryStore.setHistoryFactory(null);}groups++;
  record(store,series(),episode(),-10,-20);row=store.seriesHistory("123456789");check(row.getLong("positionMs")==0&&row.getLong("durationMs")==0,"negative decoder transitions clamp safely");groups++;
  System.out.println("LIBRARY_STORE_RECORDING_OK groups="+groups+" checks="+checks+" actualStore=true duration=true guestFavoriteRevision=true seasonMetadata=true networkRequests=0");
 }
}
