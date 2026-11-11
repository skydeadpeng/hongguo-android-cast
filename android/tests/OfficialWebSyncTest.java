package io.github.jackotom.hongguo.android;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Offline integration: the website session's cloud lane runs on the upstream 72232 transport. */
public final class OfficialWebSyncTest {
    static int groups,checks;
    interface Checked {void run()throws Exception;}
    static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    static void group(String name,Checked test)throws Exception{test.run();groups++;System.out.println("PASS "+name);}
    static JSONObject copy(JSONObject value)throws JSONException{return new JSONObject(value.toString());}

    /** Fake upstream transport: canned registration and per-path replies. */
    static final class FakeUpstream implements OfficialWebAccount.UpstreamTransport {
        final String uid;final List<String> paths=new ArrayList<>();final List<String> cookies=new ArrayList<>();
        boolean refuseWrites;
        FakeUpstream(String uid){this.uid=uid;}
        public UpstreamDeviceRegister.Identity register(){return new UpstreamDeviceRegister.Identity("7000000000000000001","7000000000000000002","00000000-0000-4000-8000-000000000003");}
        public String call(String url,String method,java.util.Map<String,String> headers,String body,long deadlineNanos)throws Exception{
            String path=url.contains("?")?url.substring(0,url.indexOf('?')):url;
            paths.add(method+" "+path);
            cookies.add(headers.getOrDefault("Cookie",""));
            if(path.endsWith("/reading/user/info/v1/"))return new JSONObject().put("data",new JSONObject().put("user_id_str",uid)).toString();
            if(path.contains("read_history/list"))return new JSONObject().put("code",0).put("data",new JSONObject().put("data_list",new JSONArray().put(new JSONObject().put("book_id_str","7000000000000000009").put("vid","8000000000000000001").put("vid_index",2).put("current_play_position",45000).put("duration",120000).put("episode_cnt",4).put("series_title","云端剧"))).put("total",1)).toString();
            if(path.contains("bookshelf/video/list"))return new JSONObject().put("code",0).put("data",new JSONObject().put("video_shelf_info",new JSONArray()).put("has_more",false)).toString();
            if(path.contains("update")||path.contains("upload")){if(refuseWrites)return new JSONObject().put("code",401).toString();return new JSONObject().put("code",0).put("data",new JSONObject()).toString();}
            return new JSONObject().put("code",0).put("data",new JSONObject()).toString();
        }
    }

    static final class Gateway implements OfficialWebAccount.Gateway {
        final java.util.concurrent.atomic.AtomicReference<String> cookie = new java.util.concurrent.atomic.AtomicReference<>("");
        final String uid;
        Gateway(String uid){this.uid=uid;}
        public JSONObject start(OfficialWebPassport.CookieJar jar)throws JSONException{return new JSONObject().put("message","success").put("data",new JSONObject().put("token","t").put("qrcode","data:image/png;base64,AAAA").put("expire_time",180));}
        public JSONObject poll(OfficialWebPassport.CookieJar jar,String token)throws JSONException{jar.absorb(java.util.Collections.singletonList(cookie.get()));return new JSONObject().put("message","success").put("data",new JSONObject().put("status","3confirmed").put("redirect_url","https://hongguoduanju.com/ok"));}
        public void confirm(OfficialWebPassport.CookieJar jar,String redirect){ }
        public JSONObject profile(OfficialWebPassport.CookieJar jar)throws JSONException{return new JSONObject().put("message","success").put("data",new JSONObject().put("user_id_str",uid).put("name","扫码账号"));}
    }

    static final class FlowClients implements OfficialAccountFlow.Clients {
        public JSONObject fresh()throws Exception{return new JSONObject().put("registered",false).put("device_id","").put("install_id","").put("cookies",new JSONObject()).put("token","");}
        public OfficialAccountFlow.Client create(JSONObject session){return new OfficialAccountFlow.Client(){
            public void registerDevice(){ } public JSONObject session()throws Exception{return copy(session);}
            public JSONObject sendCode(String phone)throws Exception{throw new java.io.IOException("短信登录已停用");}
            public JSONObject smsLogin(String p,String c,String t)throws Exception{throw new java.io.IOException("短信登录已停用");}
            public JSONObject verifyMfa(JSONObject c,String p)throws Exception{throw new java.io.IOException("短信登录已停用");}
            public void close(){ }
        };}
    }
    static final class FlowStore implements OfficialAccountFlow.Store {
        JSONObject disk;
        public JSONObject load()throws Exception{return disk==null?null:copy(disk);}
        public void save(JSONObject value)throws Exception{disk=copy(value);}
    }
    static final class WebStore implements OfficialWebAccount.Store {
        JSONObject disk;
        public JSONObject load()throws Exception{return disk==null?null:copy(disk);}
        public void save(JSONObject value)throws Exception{disk=copy(value);}
        public void clear(){disk=null;}
    }
    static final class StubContext extends android.content.Context {
        private final android.content.SharedPreferences prefs=new android.content.SharedPreferences(){
            final java.util.Map<String,String> values=new java.util.HashMap<>();
            public java.util.Map<String,?>getAll(){return values;}
            public String getString(String key,String fallback){return values.getOrDefault(key,fallback);}
            public android.content.SharedPreferences.Editor edit(){return new android.content.SharedPreferences.Editor(){
                public android.content.SharedPreferences.Editor putString(String k,String v){values.put(k,v);return this;}
                public android.content.SharedPreferences.Editor putBoolean(String k,boolean v){return this;}
                public android.content.SharedPreferences.Editor putInt(String k,int v){return this;}
                public android.content.SharedPreferences.Editor putLong(String k,long v){return this;}
                public android.content.SharedPreferences.Editor putFloat(String k,float v){return this;}
                public android.content.SharedPreferences.Editor putStringSet(String k,java.util.Set<String>v){return this;}
                public android.content.SharedPreferences.Editor remove(String k){values.remove(k);return this;}
                public void apply(){ } public boolean commit(){return true;}
            };}
        };
        public android.content.SharedPreferences getSharedPreferences(String name,int mode){return prefs;}
        public java.io.File getFilesDir(){return new java.io.File(".");}
        public java.io.File getExternalFilesDir(String type){return null;}
    }

    record Env(AccountSync sync,FakeUpstream upstream,OfficialWebAccount web) { }
    static Env build(FakeUpstream upstream,boolean probePasses)throws Exception{
        OfficialWebAccount.setUpstreamTransport(probePasses?upstream:new FakeUpstream("9999999999999999999"));
        WebStore webStore=new WebStore();
        Gateway gateway=new Gateway("7123456789012345678");
        OfficialWebAccount web=new OfficialWebAccount(webStore,gateway,()->1_000_000L);
        gateway.cookie.set("sessionid=WEB_SESSION; Path=/; Secure");
        web.start();web.poll();
        if(probePasses)web.probeCloud();
        OfficialAccountFlow flow=new OfficialAccountFlow(new FlowStore(),new FlowClients(),()->1_000_000L,ms->{ });
        AccountSync sync=new AccountSync(flow,new OfficialLibrary(new StubContext()),clients->null,false,web);
        sync.foreground();
        for(int at=0;at<40&&!sync.ownerStamp().startsWith("official");at++)Thread.sleep(25);
        return new Env(sync,upstream,web);
    }

    public static void main(String[]args)throws Exception{
        group("a passing probe selects the web owner and the sync runs on the upstream lane",()->{
            FakeUpstream upstream=new FakeUpstream("7123456789012345678");Env env=build(upstream,true);
            check(env.sync.ownerStamp().startsWith("official:"),"the web owner is selected");
            JSONObject account=env.sync.getAccount();
            check(account.optJSONObject("web").optBoolean("cloudReady"),"the probe result is projected");
            check(account.optBoolean("libraryReady"),"the library is ready for the web owner");
            env.sync.syncNow();
            for(int at=0;at<80;at++){JSONObject sync=env.sync.getAccount().optJSONObject("sync");if(!sync.optBoolean("syncing")&&!sync.optString("error","").isEmpty())break;Thread.sleep(25);}
            check(upstream.paths.stream().anyMatch(p->p.contains("user/info")),"the probe proved the identity through the lane");
            check(upstream.paths.stream().anyMatch(p->p.contains("read_history/list")),"the cloud history was pulled through the lane");
            check(upstream.cookies.stream().noneMatch(String::isEmpty)&&upstream.cookies.stream().allMatch(c->c.contains("sessionid=WEB_SESSION")),"every lane call carries the web session cookie");
        });
        group("a probe that answers a different id never enables the web lane",()->{
            FakeUpstream upstream=new FakeUpstream("9999999999999999999");Env env=build(upstream,false);
            check(!env.sync.ownerStamp().startsWith("official:"),"the guest stays selected when the probe fails");
            JSONObject account=env.sync.getAccount();
            check(!account.optJSONObject("web").optBoolean("cloudReady"),"the probe result is not claimed");
            boolean refused=false;try{env.sync.syncNow();}catch(java.io.IOException expected){refused=expected.getMessage().contains("云端验证")||expected.getMessage().contains("请先登录");}
            check(refused,"an unproven web lane refuses sync honestly");
        });
        group("a cloud 401 expires the web lane without touching the stored session projection",()->{
            FakeUpstream upstream=new FakeUpstream("7123456789012345678");upstream.refuseWrites=true;
            Env env=build(upstream,true);
            for(int at=0;at<80;at++){String error=env.sync.getAccount().optJSONObject("sync").optString("error","");if(!error.isEmpty())break;Thread.sleep(25);}
            JSONObject account=env.sync.getAccount();
            check(!account.optJSONObject("web").optBoolean("cloudReady")||account.optJSONObject("sync").optString("error","").contains("失效")||account.optJSONObject("sync").optString("error","").contains("未完成"),"a failed sync reports honestly");
        });
        OfficialWebAccount.setUpstreamTransport(null);
        System.out.println("OFFICIAL_WEB_SYNC_OK groups="+groups+" checks="+checks+" networkRequests=0 realScan=not_run");
    }
}
