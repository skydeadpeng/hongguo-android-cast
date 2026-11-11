package io.github.jackotom.hongguo.android;
import org.json.*;import java.nio.charset.StandardCharsets;
/** The marker is replaced by the actual NativeBridge play switch case, never a rewritten model. */
public final class AdaptiveBridgeTest {
 Object activity=new Object();JSONObject captured;String capturedUrl;int starts,checks;
 void play(Object id,JSONObject series,JSONObject episode,JSONArray queue,String url,boolean local,JSONObject params)throws Exception{starts++;captured=new JSONObject(params.toString()).put("queue",queue);capturedUrl=url;}
 void dispatch(JSONObject params)throws Exception{Object id="fixture";switch("play"){/* ACTUAL_PLAY_CASE */ default:throw new Exception("missing");}}
 static JSONObject params()throws Exception{return new JSONObject().put("resolveInPlayer",true).put("series",new JSONObject().put("seriesId","123")).put("episode",new JSONObject().put("vid","456").put("index",9)).put("episodes",new JSONArray().put(new JSONObject().put("vid","456").put("index",9))).put("quality","1080p").put("speed",1.5);}
 void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
 void rejects(JSONObject p)throws Exception{int before=starts;try{dispatch(p);throw new AssertionError("invalid modern call accepted");}catch(JSONException|IllegalArgumentException expected){}check(starts==before,"invalid call did not launch");}
 public static void main(String[] args)throws Exception{AdaptiveBridgeTest h=new AdaptiveBridgeTest();
  h.dispatch(params());h.check(h.starts==1&&h.capturedUrl==null,"modern bridge starts native with no media URL");h.check(PlaybackSources.registered==0,"modern branch does not request/preflight a media model");h.check(h.captured.getJSONArray("queue").getJSONObject(0).getString("vid").equals("456"),"exact target/queue preserved");h.check(h.captured.getString("quality").equals("1080p")&&h.captured.getDouble("speed")==1.5,"quality/speed preserved");
  for(JSONObject p:new JSONObject[]{params().put("resolveInPlayer","true"),params().put("url","https://fixture.invalid/"),params().put("playInfo",JSONObject.NULL),params().put("quality",1080),params().put("quality","4k"),params().put("speed","1.5"),params().put("speed",4),params().put("episodes","bad")})h.rejects(p);
  JSONObject defaults=params();defaults.remove("quality");defaults.remove("speed");h.dispatch(defaults);h.check(h.captured.getString("quality").equals("720p")&&h.captured.getDouble("speed")==1.25,"missing options use saved defaults");
  JSONObject legacy=params();legacy.remove("resolveInPlayer");legacy.put("url","https://fixture.invalid/media.mp4");h.dispatch(legacy);h.check(PlaybackSources.registered==1&&h.capturedUrl.equals("https://fixture.invalid/media.mp4"),"legacy plan register preserved");
  System.out.println("ADAPTIVE_BRIDGE_OK checks="+h.checks+" modernSourcesBeforeLaunch=0 legacyCompatible=true");
 }
}
final class LibraryStore {
 static JSONObject series(JSONObject x){return x;}static JSONObject episode(JSONObject x){return x;}
 static JSONArray episodes(JSONArray queue,JSONObject ep)throws Exception{return queue==null?new JSONArray().put(ep):queue;}
 static String text(JSONObject p,String k,int max)throws Exception{return p.getString(k);}
}
final class PlayerOptions {
 static JSONObject read(Object activity)throws Exception{return new JSONObject().put("quality","720p").put("speed",1.25);}
 static void validateSpeed(double x){if(!Double.isFinite(x)||x<0.5||x>2)throw new IllegalArgumentException("speed");}
}
final class PlaybackSources {
 static int registered;static class Plan {String url;JSONObject input;Plan(JSONObject p)throws Exception{input=p;url=p.getString("url");}JSONObject json(){return input;}}
 static Plan registerInitial(JSONObject p,String sid,String vid,String q)throws Exception{registered++;return new Plan(p);}
}
