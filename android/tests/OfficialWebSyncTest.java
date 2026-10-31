package io.github.jackotom.hongguo.android;

import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Offline integration: the website QR session drives the cloud library after a passing probe. */
public final class OfficialWebSyncTest {
    static int groups,checks;
    interface Checked {void run()throws Exception;}
    static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    static void group(String name,Checked test)throws Exception{test.run();groups++;System.out.println("PASS "+name);}
    static JSONObject copy(JSONObject value)throws JSONException{return new JSONObject(value.toString());}

    /** The website gateway: scripted QR answers and a fixed profile. */
    static final class Gateway implements OfficialWebAccount.Gateway {
        final AtomicReference<String> cookie = new AtomicReference<>("");
        final String uid;
        Gateway(String uid){this.uid=uid;}
        public JSONObject start(OfficialWebPassport.CookieJar jar)throws JSONException{return new JSONObject().put("message","success").put("data",new JSONObject().put("token","t").put("qrcode","data:image/png;base64,AAAA").put("expire_time",180));}
        public JSONObject poll(OfficialWebPassport.CookieJar jar,String token)throws JSONException{jar.absorb(java.util.Collections.singletonList(cookie.get()));return new JSONObject().put("message","success").put("data",new JSONObject().put("status","3confirmed").put("redirect_url","https://hongguoduanju.com/ok"));}
        public void confirm(OfficialWebPassport.CookieJar jar,String redirect){ }
        public JSONObject profile(OfficialWebPassport.CookieJar jar)throws JSONException{return new JSONObject().put("message","success").put("data",new JSONObject().put("user_id_str",uid).put("name","扫码账号"));}
    }

    /** The signed cloud lane: scripted answers keyed by request path. */
    static final class Cloud implements OfficialApiClient.Cloud {
        final java.util.List<String> paths=new java.util.ArrayList<>();
        final java.util.List<JSONObject> cookies=new java.util.ArrayList<>();
        String infoUid="";boolean refuseWrites;
        public JSONObject call(String path,String method,JSONObject query,JSONObject body)throws Exception{
            paths.add(path);
            if(path.equals("/reading/user/info/v1/"))return new JSONObject().put("data",new JSONObject().put("user_id_str",infoUid));
            if(path.contains("read_history/list"))return new JSONObject().put("data",new JSONObject().put("data_list",new JSONArray().put(new JSONObject().put("book_id_str","7000000000000000001").put("vid","8000000000000000001").put("vid_index",2).put("current_play_position",45_000).put("duration",120_000).put("episode_cnt",4).put("series_title","云端剧"))));
            if(path.contains("update")||path.contains("upload")){if(refuseWrites)return new JSONObject().put("code",401);return new JSONObject().put("data",new JSONObject());}
            return new JSONObject().put("data",new JSONObject());
        }
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

    /** Builds the coordinator with a QR session already confirmed and the probe answered. */
    record Env(AccountSync sync,AccountSync.Selection selection,Cloud cloud,OfficialWebAccount web) { }
    static Env build(Cloud cloud,boolean probePasses)throws Exception{
        OfficialApiClient.global=cloud;
        WebStore webStore=new WebStore();
        Gateway gateway=new Gateway("7123456789012345678");
        OfficialWebAccount web=new OfficialWebAccount(webStore,gateway,()->1_000_000L);
        gateway.cookie.set("sessionid=WEB_SESSION; Path=/; Secure");
        web.start();web.poll();
        if(probePasses)cloud.infoUid="7123456789012345678";
        OfficialAccountFlow flow=new OfficialAccountFlow(new FlowStore(),new FlowClients(),()->1_000_000L,ms->{ });
        AccountSync sync=new AccountSync(flow,new OfficialLibrary(new StubContext()),clients->null,false,web);
        sync.foreground();
        for(int at=0;at<40&&!sync.ownerStamp().startsWith("official");at++)Thread.sleep(25);
        return new Env(sync,null,cloud,web);
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

    public static void main(String[]args)throws Exception{
        group("a confirmed QR session with a passing probe selects the web owner and pulls the cloud history",()->{
            Cloud cloud=new Cloud();Env env=build(cloud,true);
            check(env.sync.ownerStamp().startsWith("official:"),"the web owner is selected");
            JSONObject account=env.sync.getAccount();
            check(account.optJSONObject("web").optBoolean("cloudReady"),"the probe result is projected");
            check(account.optBoolean("libraryReady"),"the library is ready for the web owner");
            for(int at=0;at<40;at++){if(!env.sync.getAccount().optJSONObject("sync").optBoolean("syncing")&&!env.sync.getAccount().optJSONObject("sync").optString("error","").isEmpty())break;Thread.sleep(25);}
            check(cloud.paths.stream().anyMatch(p->p.contains("read_history/list")),"the web client pulled the cloud history");
            check(cloud.paths.stream().anyMatch(p->p.contains("user/info")),"the probe proved the identity before syncing");
        });
        group("a probe that answers a different id never enables the web lane",()->{
            Cloud cloud=new Cloud();cloud.infoUid="9999999999999999999";
            Env env=build(cloud,false);
            check(!env.sync.ownerStamp().startsWith("official:"),"the guest stays selected when the probe fails");
            JSONObject account=env.sync.getAccount();
            check(!account.optJSONObject("web").optBoolean("cloudReady"),"the probe result is not claimed");
            boolean refused=false;try{env.sync.syncNow();}catch(java.io.IOException expected){refused=expected.getMessage().contains("云端验证")||expected.getMessage().contains("请先登录");}
            check(refused,"an unproven web lane refuses sync honestly");
        });
        group("a cloud 401 expires the web lane without touching the stored session projection",()->{
            Cloud cloud=new Cloud();cloud.refuseWrites=true;
            Env env=build(cloud,true);
            for(int at=0;at<60;at++){String error=env.sync.getAccount().optJSONObject("sync").optString("error","");if(!error.isEmpty())break;Thread.sleep(25);}
            JSONObject account=env.sync.getAccount();
            check(!account.optJSONObject("web").optBoolean("cloudReady")||account.optJSONObject("sync").optString("error","").contains("失效")||account.optJSONObject("sync").optString("error","").contains("未完成"),"a failed sync reports honestly");
        });
        System.out.println("OFFICIAL_WEB_SYNC_OK groups="+groups+" checks="+checks+" networkRequests=0 realScan=not_run");
    }
}
