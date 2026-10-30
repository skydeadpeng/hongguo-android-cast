package io.github.jackotom.hongguo.android;
import java.util.*;import java.nio.charset.StandardCharsets;import org.json.*;
/** Actual dispatch case and actual launch helper are inserted; only Android/library boundaries are doubles. */
public final class SeasonPlaylistBridgeTest {
 final MainActivity activity=new MainActivity();boolean closed;JSONObject response;String error;int checks;
 void respond(Object id,JSONObject result,String failure){response=result;error=failure;}
 void dispatch(JSONObject params)throws Exception{Object id="fixture";switch("play"){/* ACTUAL_PLAY_CASE */default:throw new Exception("missing");}}
 /* ACTUAL_PLAY_METHOD */
 static JSONObject season(String sid,String title)throws Exception{return new JSONObject().put("seriesId",sid).put("title",title).put("episodeCount",2);}
 static JSONObject row(String sid,String vid,int index)throws Exception{return new JSONObject().put("seriesId",sid).put("vid",vid).put("index",index);}
 static JSONObject params()throws Exception{
  JSONObject a=season("111111","示例 第一季"),b=season("222222","示例 第二季"),first=row("111111","1001",1),second=row("111111","1002",2);
  return new JSONObject().put("resolveInPlayer",true).put("series",a).put("episode",second).put("episodes",new JSONArray().put(first).put(second)).put("quality","1080p").put("speed",1.25).put("autoplay",true).put("cleanupPlaybackCache",true)
   .put("playlist",new JSONObject().put("version",1).put("series",new JSONArray().put(a).put(b)).put("episodes",new JSONArray().put(first).put(second).put(row("222222","1001",1)).put(row("222222","2002",2))));
 }
 void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
 void rejects(JSONObject value)throws Exception{int started=activity.starts;try{dispatch(value);throw new AssertionError("invalid playlist dispatched");}catch(JSONException|IllegalArgumentException expected){}activity.pump();check(activity.starts==started,"invalid metadata rejected before Activity launch");}
 public static void main(String[]args)throws Exception{
  SeasonPlaylistBridgeTest h=new SeasonPlaylistBridgeTest();JSONObject valid=params();h.dispatch(valid);h.check(h.activity.starts==0,"launch queued on UI thread");h.activity.pump();Intent intent=h.activity.last;
  h.check(h.activity.starts==1&&h.response.optBoolean("started")&&h.error==null,"actual bridge launch responds only after Activity start");
  h.check(intent.get("playlist").equals(valid.getJSONObject("playlist").toString()),"one original compact playlist descriptor crosses Intent boundary unchanged");
  h.check(new JSONArray((String)intent.get("episodes")).length()==2&&!intent.extras.toString().contains("_series"),"legacy current-season queue stays separate without repeated owner metadata in Binder");
  h.check(intent.get("quality").equals("1080p")&&intent.get("speed").equals(1.25)&&intent.get("autoplay").equals(true)&&intent.get("cleanupPlaybackCache").equals(true),"play options including autoplay/cache remain unchanged");
  h.check(PlaybackSources.registered==0&&intent.get("url")==null,"cross-season bridge prepares metadata only; no source/network resolution before launch");
  JSONObject descriptor=new JSONObject((String)intent.get("playlist"));JSONArray parsed=PlaybackPlaylist.parse(descriptor,new JSONObject((String)intent.get("series")),new JSONObject((String)intent.get("episode")));
  h.check(parsed.length()==4&&parsed.getJSONObject(2).optInt("index")==1&&PlaybackPlaylist.owner(null,parsed.getJSONObject(2)).optString("seriesId").equals("222222"),"launched descriptor reparses correct next-season owner/local index");
  h.rejects(params().put("playlist",new JSONArray()));h.rejects(params().put("resolveInPlayer",false));h.rejects(params().put("playlist",JSONObject.NULL));h.rejects(params().put("url","https://fixture.invalid/a.mp4"));
  JSONObject absent=new JSONObject(params().toString());absent.getJSONObject("episode").put("vid","9999");h.rejects(absent);
  JSONObject huge=params();huge.getJSONObject("playlist").put("padding","x".repeat(160*1024));h.rejects(huge);
  h.rejects(params().put("padding","x".repeat(240*1024)));
  JSONObject legacy=params();legacy.remove("playlist");h.dispatch(legacy);h.activity.pump();h.check(!h.activity.last.extras.containsKey("playlist"),"legacy single-season launch does not introduce a playlist");
  JSONObject offline=params();h.play("offline",offline.getJSONObject("series"),offline.getJSONObject("episode"),offline.getJSONArray("episodes"),"content://fixture/offline",true,offline);h.activity.pump();h.check(!h.activity.last.extras.containsKey("playlist")&&h.activity.last.get("download").equals(true),"offline launch never includes online cross-season descriptor");
  int before=h.activity.starts;h.dispatch(params());h.closed=true;h.activity.pump();h.check(h.activity.starts==before,"queued cross-season launch respects closed bridge lifecycle");
  System.out.println("SEASON_PLAYLIST_BRIDGE_OK checks="+h.checks+" actualDispatch=true actualLaunch=true actualPlaylistParser=true compactIntent=true networkRequests=0 deviceTests=not_run");
 }
}
final class MainActivity {final Deque<Runnable>pending=new ArrayDeque<>();int starts;Intent last;boolean isFinishing(){return false;}boolean isDestroyed(){return false;}void runOnUiThread(Runnable action){pending.add(action);}void startActivity(Intent value){starts++;last=value;}void pump(){while(!pending.isEmpty())pending.removeFirst().run();}}
final class Intent {final Map<String,Object>extras=new LinkedHashMap<>();Intent(Object activity,Class<?>target){}Intent putExtra(String key,Object value){extras.put(key,value);return this;}Object get(String key){return extras.get(key);}}
final class PlayerActivity {}
final class LibraryStore {static JSONObject series(JSONObject value){return value;}static JSONObject episode(JSONObject value){return value;}static JSONArray episodes(JSONArray rows,JSONObject selected){return rows==null?new JSONArray().put(selected):rows;}static String text(JSONObject value,String key,int max)throws Exception{return value.getString(key);}}
final class PlayerOptions {static JSONObject read(Object activity)throws Exception{return new JSONObject().put("quality","auto").put("speed",1.0);}static void validateSpeed(double speed){if(!Double.isFinite(speed)||speed<0.5||speed>2)throw new IllegalArgumentException("speed");}}
final class PlaybackSources {static int registered;static class Plan {final JSONObject value;final String url;Plan(JSONObject v){value=v;url=v.optString("url");}JSONObject json(){return value;}}static Plan registerInitial(JSONObject input,String sid,String vid,String q){registered++;return new Plan(input);}}

// Installation coordination is tested separately with actual checkpoint production code.
class UpdateInstallCheckpoint { private static final UpdateOperationGate gate = new UpdateOperationGate(); static UpdateOperationGate.Lease operation() throws java.io.IOException { return gate.operation(); } static void playerLaunching() { } }
