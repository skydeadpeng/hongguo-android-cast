package io.github.jackotom.hongguo.android;
import java.nio.file.*;import java.util.*;import java.util.concurrent.*;import android.content.SharedPreferences;import org.json.*;
public final class SearchProtocolTest {
    static int checks;
    static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    interface Task{void run()throws Exception;}
    static void fails(Task task,String label)throws Exception{boolean failed=false;try{task.run();}catch(Exception expected){failed=true;}check(failed,label);}
    static final class Store implements SharedPreferences {
        final Map<String,String> values=new ConcurrentHashMap<>();int writes;boolean writable=true;
        public String getString(String key,String fallback){return values.getOrDefault(key,fallback);}
        public Editor edit(){return new Editor(){final Map<String,String> pending=new HashMap<>();public Editor putString(String key,String value){pending.put(key,value);return this;}public boolean commit(){if(!writable)return false;values.putAll(pending);writes++;return true;}};}
    }
    static JSONObject request()throws Exception{return new JSONObject().put("keyword","总裁").put("offset",0);}
    static final class Backend implements SearchClient.Transport {
        int registrations,searches;boolean failRegistration,failSearch;String lastQuery;byte[] registrationBody;
        public JSONObject request(String url,String method,Map<String,String> headers,byte[] body,long deadline)throws Exception{
            check(deadline-System.nanoTime()>0&&deadline-System.nanoTime()<=38_000_000_000L,"bounded deadline");
            check(!headers.containsKey("Cookie")&&!headers.containsKey("Authorization"),"anonymous request");
            if(method.equals("POST")){registrations++;registrationBody=body;check(url.startsWith("https://log.snssdk.com/service/2/device_register/?"),"fixed registration route");check(body.length>100&&SearchProtocol.hex(Arrays.copyOf(body,6)).equals("746305100000"),"encrypted binary registration");return new JSONObject().put("status",200).put("body",failRegistration?"{}":"{\"device_id_str\":\"1234567890123456\",\"install_id_str\":\"2234567890123456\"}");}
            searches++;lastQuery=url.substring(url.indexOf('?')+1);check(url.startsWith("https://api5-normal-sinfonlineb.fqnovel.com/reading/bookapi/search/tab/v?"),"fixed search route");check(body.length==0&&headers.get("x-argus").length()>100,"GET signed without body");return new JSONObject().put("status",failSearch?500:200).put("body","{\"code\":0,\"search_tabs\":[]}");
        }
    }
    public static void main(String[] args)throws Exception{
        JSONObject v=new JSONObject(Files.readString(Path.of(args[0])));String q=v.getString("query");long t=v.getLong("timestamp");
        check(SearchProtocol.ladon(t,SearchProtocol.unhex(v.getString("nonce"))).equals(v.getString("ladon")),"Ladon cross-language vector");
        check(SearchProtocol.argus(q,t,v.getInt("randomField")).equals(v.getString("argus")),"Argus cross-language vector");
        check(SearchProtocol.hex(SearchProtocol.ttEncrypt(SearchProtocol.unhex(v.getString("compressed")),SearchProtocol.unhex(v.getString("ttNonce")))).equals(v.getString("ttEncrypted")),"TTEncrypt cross-language vector");
        check(SearchProtocol.hex(SearchProtocol.sm3(SearchProtocol.utf8("abc"))).equals("66c7f0f462eeedd9d1f2d46bdc10e4e24167c4875cf2f7a2297da02b8f4ba8e0"),"SM3 official abc vector");
        check(SearchProtocol.encode("中 文+*()'!").equals("%E4%B8%AD+%E6%96%87%2B%2A%28%29%27%21"),"exact query encoding");
        Store store=new Store();Backend backend=new Backend();SearchClient.search(store,request(),backend);
        check(backend.registrations==1&&backend.searches==1&&store.writes==1,"lazy first registration");
        check(store.values.size()==2&&!store.values.containsKey("secret_key"),"only local server-issued identifiers persisted");
        SearchClient.search(store,request().put("offset",20).put("searchId","####11@fixture").put("passback","20"),backend);
        check(backend.registrations==1&&backend.searches==2&&store.writes==1,"reuse session across pages");
        check(backend.lastQuery.contains("user_is_login=0")&&backend.lastQuery.contains("search_id=%23%23%23%2311%40fixture")&&backend.lastQuery.contains("offset=20")&&backend.lastQuery.contains("passback=20")&&backend.lastQuery.contains("tab_type=11")&&backend.lastQuery.contains("count=20"),"cursor and anonymous short-drama parameters");
        backend.failSearch=true;fails(()->SearchClient.search(store,request(),backend),"failed search rejected");backend.failSearch=false;SearchClient.search(store,request(),backend);check(backend.registrations==1,"transport failure does not create new devices");
        Store empty=new Store();Backend fail=new Backend();fail.failRegistration=true;fails(()->SearchClient.search(empty,request(),fail),"malformed registration rejected");check(empty.values.isEmpty()&&fail.searches==0,"failed registration not cached nor searched");
        Store notWritable=new Store();notWritable.writable=false;Backend save=new Backend();fails(()->SearchClient.search(notWritable,request(),save),"persistence failure reported");check(save.searches==0&&notWritable.values.isEmpty(),"no search after unpersisted registration");
        for(Object value:new Object[]{-1,1.5,10001,"20",JSONObject.NULL})fails(()->SearchClient.search(store,request().put("offset",value),backend),"invalid offset rejected");
        for(Object value:new Object[]{"", "x".repeat(101),"x\n",20})fails(()->SearchClient.search(store,request().put("keyword",value),backend),"invalid keyword rejected");
        fails(()->SearchClient.search(store,request().put("passback","x".repeat(513)),backend),"unbounded cursor rejected");
        Thread.currentThread().interrupt();fails(()->SearchClient.search(store,request(),backend),"interrupted request rejected");check(Thread.interrupted(),"cancellation flag preserved");
        check(!SearchClient.validId("0")&&!SearchClient.validId("1e18")&&!SearchClient.validId("123")&&SearchClient.validId("1234567890123456789"),"identifier strings validated without numeric rounding");
        Store concurrent=new Store();Backend shared=new Backend();ExecutorService pool=Executors.newFixedThreadPool(2);try{Future<?> a=pool.submit(()->{try{SearchClient.search(concurrent,request(),shared);}catch(Exception e){throw new RuntimeException(e);}});Future<?> b=pool.submit(()->{try{SearchClient.search(concurrent,request(),shared);}catch(Exception e){throw new RuntimeException(e);}});a.get();b.get();}finally{pool.shutdownNow();}check(shared.registrations==1&&shared.searches==2,"concurrent first searches register once");
        check(AppTransport.searchEndpoint(new java.net.URI("https://log.snssdk.com/service/2/device_register/?x=1"),"POST"),"registered endpoint allowed");
        for(String url:new String[]{"https://evil.example/service/2/device_register/","https://log.snssdk.com/service/2/device_register/other","https://api5-normal-sinfonlineb.fqnovel.com/novel/player/multi_video_model/v1/"})check(!AppTransport.searchEndpoint(new java.net.URI(url),"POST"),"unrelated endpoint rejected");
        fails(()->AppTransport.searchRequest("http://log.snssdk.com/service/2/device_register/","POST",Map.of(),new byte[0],System.nanoTime()+1_000_000_000L),"non-TLS rejected before socket");
        fails(()->AppTransport.searchRequest("https://log.snssdk.com/service/2/device_register/","POST",Map.of("host","evil.example"),new byte[0],System.nanoTime()+1_000_000_000L),"host override rejected before socket");
        System.out.println("SEARCH_PROTOCOL_TESTS_OK checks="+checks+" goldenVectors=4 networkRequests=0 sessionRegistration=lazy_once mediaSigner=unchanged");
    }
}
