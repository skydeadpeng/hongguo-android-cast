package io.github.jackotom.hongguo.android;

import java.io.*;
import java.net.SocketTimeoutException;
import java.util.*;

/** Strict resolver control-flow tests. Registered object tokens are NOT a JSON parser. */
final class HuangguoApiTest {
    static int checks;
    static final String SID="9900000042", VID=SID+"0003";
    static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    static JSONObject object(Object... pairs){JSONObject result=new JSONObject();for(int i=0;i<pairs.length;i+=2)result.put((String)pairs[i],pairs[i+1]);return result;}
    static JSONObject data(){return object("streams",object("main","https://cdn.example.test/full.m3u8?fixture=opaque%2Btoken&v=2"),"duration",10);}
    static NetworkGuard.Reply response(int status,JSONObject body){return new NetworkGuard.Reply(status,JSONObject.register(body));}
    static NetworkGuard.Reply ok(){return response(200,object("status",1,"data",data()));}
    static void reset(){NetworkGuard.events.clear();NetworkGuard.urls.clear();NetworkGuard.posts.clear();NetworkGuard.deadlines.clear();HuangguoApi.originIndex=0;HuangguoApi.preferences.line="auto";HuangguoApi.preferences.writes=0;Thread.interrupted();}
    static HuangguoApi.CastStream resolve()throws Exception{return HuangguoApi.episodeForCast(SID,VID,3);}
    static Exception rejected()throws Exception {
        try{resolve();throw new AssertionError("Unexpected acceptance");}catch(IOException|JSONException expected){return expected;}
    }
    static void deny(NetworkGuard.Reply reply)throws Exception {
        reset();NetworkGuard.events.add(reply);NetworkGuard.events.add(ok());
        Exception failure=rejected();check(failure.getClass().getSimpleName().equals("SourceDeniedException"),"explicit denial type");
        check(NetworkGuard.urls.size()==1,"denial stops before mirror");check(NetworkGuard.events.size()==1,"mirror success left unused");
    }
    public static void main(String[] args)throws Exception {
        reset();NetworkGuard.events.add(ok());HuangguoApi.CastStream stream=resolve();
        check(stream.url.equals("https://cdn.example.test/full.m3u8?fixture=opaque%2Btoken&v=2")&&stream.seconds==10,"authorized stream returned unchanged");
        check(NetworkGuard.posts.equals(Arrays.asList(true)),"cast playback uses POST");
        check(NetworkGuard.urls.get(0).equals("https://huangguoai.com/api/videos/42/episodes/3/playback"),"fixed API path and exact IDs");
        check(HuangguoApi.encodeEpisode(42,3).equals(VID),"episode encoding");
        check(HuangguoApi.decodeSeries(SID)==42&&HuangguoApi.decodeEpisodeIndex(VID)==3,"ID decoding");
        for(int status=400;status<500;status++)deny(new NetworkGuard.Reply(status,"unused"));
        for(int status:new int[]{0,-1,2})deny(response(200,object("status",status,"data",data())));
        deny(response(200,object("status",1,"data","not an object")));
        deny(response(200,object("data",data())));
        for(Object problem:new Object[]{new IOException("network"),new SocketTimeoutException("timeout"),new NetworkGuard.Reply(500,"unparsed"),new NetworkGuard.Reply(200,"unregistered token")}) {
            reset();NetworkGuard.events.add(problem);NetworkGuard.events.add(ok());resolve();
            check(NetworkGuard.urls.size()==2,"recoverable failure tries one mirror");
            check(NetworkGuard.urls.get(1).startsWith("https://l5f9m.rxzfszht.cc/"),"next fixed origin used");
            check(HuangguoApi.originIndex==1,"successful origin retained");
        }
        reset();NetworkGuard.events.add(new IOException("temporary"));NetworkGuard.events.add(new NetworkGuard.Reply(403,"denied"));NetworkGuard.events.add(ok());
        check(rejected().getClass().getSimpleName().equals("SourceDeniedException"),"mirror denial remains terminal");
        check(NetworkGuard.urls.size()==2&&NetworkGuard.events.size()==1,"denial after recovery stops remaining mirrors");
        reset();for(int i=0;i<6;i++)NetworkGuard.events.add(new NetworkGuard.Reply(500,"unparsed"));rejected();
        check(NetworkGuard.urls.size()==5&&NetworkGuard.events.size()==1,"fallback bounded to five configured origins");
        reset();NetworkGuard.events.add(new InterruptedIOException("cancelled"));NetworkGuard.events.add(ok());
        check(rejected() instanceof InterruptedIOException,"interrupted IO propagated");check(NetworkGuard.urls.size()==1,"interrupted IO never retries");
        reset();Thread.currentThread().interrupt();try {check(rejected() instanceof InterruptedIOException,"thread cancellation propagated");check(NetworkGuard.urls.isEmpty(),"already interrupted makes no request");}finally{Thread.interrupted();}
        reset();NetworkGuard.events.add(new NetworkGuard.InterruptingTimeout());NetworkGuard.events.add(ok());
        try{check(rejected() instanceof SocketTimeoutException,"interrupted socket timeout propagated");check(NetworkGuard.urls.size()==1,"interrupted timeout never retries");}finally{Thread.interrupted();}
        for(String url:new String[]{"", "http://cdn.example.test/x.m3u8", "file:///tmp/x", "https://user:pass@cdn.example.test/x", "https://cdn.example.test:8443/x", "https://cdn.example.test/x#fragment"}) {
            reset();NetworkGuard.events.add(response(200,object("status",1,"data",object("streams",object("main",url)))));NetworkGuard.events.add(ok());
            rejected();check(NetworkGuard.urls.size()==1,"bad stream rejected without resolving elsewhere");
        }
        reset();NetworkGuard.events.add(response(200,object("status",1,"data",object("duration",10))));rejected();check(NetworkGuard.urls.size()==1,"missing streams rejected");
        for(double seconds:new double[]{-1,Double.NaN,Double.POSITIVE_INFINITY,7201}) {
            reset();JSONObject bad=data().put("duration",seconds);NetworkGuard.events.add(response(200,object("status",1,"data",bad)));rejected();check(NetworkGuard.urls.size()==1,"invalid duration rejected");
        }
        for(String[] identity:new String[][]{{"123456",VID},{SID,SID+"0004"},{SID,""},{SID,null}}) {
            reset();try{HuangguoApi.episodeForCast(identity[0],identity[1],3);throw new AssertionError("wrong identity accepted");}catch(JSONException expected){}
            check(NetworkGuard.urls.isEmpty(),"mismatched IDs never resolve");
        }
        for(int index:new int[]{0,10000}) {
            reset();try{HuangguoApi.episodeForCast(SID,VID,index);throw new AssertionError("bad index");}catch(JSONException expected){}
            check(NetworkGuard.urls.isEmpty(),"invalid episode index never resolves");
        }
        for(Object event:new Object[]{new IOException("offline"),new NetworkGuard.Reply(500,"bad"),new NetworkGuard.Reply(403,"denied")}) {
            reset();HuangguoApi.preferences.line="2";NetworkGuard.events.add(event);NetworkGuard.events.add(ok());rejected();
            check(NetworkGuard.urls.size()==1&&NetworkGuard.urls.get(0).startsWith("https://blolhh.rxzfszht.cc/"),"fixed line never switches");
            check(HuangguoApi.preferences.line.equals("2")&&HuangguoApi.preferences.writes==0,"cast failure never resets fixed setting");
        }
        reset();HuangguoApi.preferences.line="3";NetworkGuard.events.add(ok());resolve();
        check(NetworkGuard.urls.get(0).startsWith("https://mxz58.rxzfszht.cc/")&&HuangguoApi.preferences.line.equals("3"),"fixed-line success remains fixed");
        // Exercise the same extracted fetchFrom GET branch without changing cast's POST semantics.
        reset();NetworkGuard.events.add(ok());java.lang.reflect.Method fetch=HuangguoApi.class.getDeclaredMethod("fetch",String.class,boolean.class,boolean.class);fetch.setAccessible(true);fetch.invoke(null,"/fixture",false,true);
        check(NetworkGuard.posts.equals(Arrays.asList(false)),"fetch GET branch honored");
        reset();NetworkGuard.events.add(ok());
        try{HuangguoApi.episodeForCast(SID,VID,3,System.nanoTime()-1);throw new AssertionError("expired caller deadline accepted");}catch(SocketTimeoutException expected){}
        check(NetworkGuard.urls.isEmpty(),"global expiry prevents even first origin request");
        reset();NetworkGuard.events.add(new IOException("temporary"));NetworkGuard.events.add(ok());
        long callerDeadline=System.nanoTime()+2_000_000_000L;HuangguoApi.episodeForCast(SID,VID,3,callerDeadline);
        check(NetworkGuard.deadlines.size()==2,"caller-deadline path retries recoverable error");
        for(long deadline:NetworkGuard.deadlines)check(deadline<=callerDeadline,"origin deadline never exceeds caller deadline");
        reset();NetworkGuard.events.add(new NetworkGuard.ExpiringFailure());NetworkGuard.events.add(ok());
        try{HuangguoApi.episodeForCast(SID,VID,3,System.nanoTime()+50_000_000L);throw new AssertionError("global expiry ignored during retries");}catch(SocketTimeoutException expected){}
        check(NetworkGuard.urls.size()==1&&NetworkGuard.events.size()==1,"global expiry stops remaining origins");
        // The non-strict browsing path intentionally retains its previous behavior.
        reset();NetworkGuard.events.add(new NetworkGuard.Reply(403,"denied"));NetworkGuard.events.add(ok());fetch.invoke(null,"/fixture",false,false);
        check(NetworkGuard.urls.size()==2,"ordinary non-strict browsing still retries HTTP 4xx");
        reset();HuangguoApi.preferences.line="2";NetworkGuard.events.add(new NetworkGuard.Reply(500,"offline"));
        try{fetch.invoke(null,"/fixture",false,false);throw new AssertionError("non-strict fixed failure accepted");}catch(java.lang.reflect.InvocationTargetException expected){check(expected.getCause() instanceof IOException,"non-strict fixed failure propagated");}
        check(HuangguoApi.preferences.line.equals("auto")&&HuangguoApi.preferences.writes==1,"ordinary browsing retains legacy fixed-line reset");
        System.out.println("Huangguo strict resolver boundary checks passed: "+checks+" (JSON parser is a registered-token double)");
    }
}
class JSONException extends Exception {JSONException(String message){super(message);}}
class JSONObject {
    private static final Map<String,JSONObject> TOKENS=new HashMap<>();
    private final Map<String,Object> values=new HashMap<>();
    JSONObject(){}
    JSONObject(String token)throws JSONException {JSONObject fixture=TOKENS.get(token);if(fixture==null)throw new JSONException("Unregistered fixture token");values.putAll(fixture.values);}
    static String register(JSONObject value){String token="fixture:"+TOKENS.size();TOKENS.put(token,value);return token;}
    JSONObject put(String key,Object value){values.put(key,value);return this;}
    Object opt(String key){return values.get(key);}
    int optInt(String key,int fallback){Object value=opt(key);return value instanceof Number?((Number)value).intValue():fallback;}
    double optDouble(String key,double fallback){Object value=opt(key);return value instanceof Number?((Number)value).doubleValue():fallback;}
    String optString(String key,String fallback){Object value=opt(key);return value instanceof String?(String)value:fallback;}
    JSONObject optJSONObject(String key){Object value=opt(key);return value instanceof JSONObject?(JSONObject)value:null;}
    JSONObject getJSONObject(String key)throws JSONException {JSONObject value=optJSONObject(key);if(value==null)throw new JSONException("Not an object");return value;}
}
class TestPrefs {
    String line="auto";int writes;
    String getString(String key,String fallback){return key.equals("line")?line:fallback;}
    TestPrefs edit(){return this;}
    TestPrefs putString(String key,String value){if(key.equals("line")){line=value;writes++;}return this;}
    void apply(){}
}
class NetworkGuard {
    static final Deque<Object> events=new ArrayDeque<>();
    static final List<String> urls=new ArrayList<>();static final List<Boolean> posts=new ArrayList<>();
    static final List<Long> deadlines=new ArrayList<>();
    static class Reply {final int status;final String body;Reply(int status,String body){this.status=status;this.body=body;}}
    static final class InterruptingTimeout extends SocketTimeoutException {}
    static final class ExpiringFailure {}
    static Reply publicPostJson(String url,long deadline)throws IOException{return request(url,deadline,true);}
    static Reply publicGet(String url,long deadline)throws IOException{return request(url,deadline,false);}
    static Reply request(String url,long deadline,boolean post)throws IOException {
        urls.add(url);posts.add(post);deadlines.add(deadline);long remaining=deadline-System.nanoTime();HuangguoApiTest.check(remaining>0&&remaining<=6_000_000_000L,"request deadline is positive and at most six seconds");
        Object next=events.poll();if(next==null)throw new AssertionError("Unplanned request");
        if(next instanceof ExpiringFailure){while(System.nanoTime()<deadline)Thread.yield();throw new SocketTimeoutException("caller budget expired");}
        if(next instanceof InterruptingTimeout)Thread.currentThread().interrupt();
        if(next instanceof IOException)throw (IOException)next;
        return (Reply)next;
    }
}
