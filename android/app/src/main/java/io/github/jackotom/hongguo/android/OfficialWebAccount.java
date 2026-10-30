package io.github.jackotom.hongguo.android;

import java.io.IOException;
import java.util.*;
import org.json.*;

/**
 * Website QR login for the Hongguo account.
 *
 * The phone scans the official code and confirms it inside the Hongguo App, so neither an SMS code
 * nor the additional short-message verification of the mobile passport is involved. The session
 * cookie never leaves native code: it is kept in memory during the handshake and persisted only
 * through the encrypted web slot.
 */
final class OfficialWebAccount {
    interface Store { JSONObject load() throws Exception; void save(JSONObject value) throws Exception; void clear() throws Exception; }
    interface Gateway {
        JSONObject start(OfficialWebPassport.CookieJar jar) throws Exception;
        JSONObject poll(OfficialWebPassport.CookieJar jar, String token) throws Exception;
        void confirm(OfficialWebPassport.CookieJar jar, String redirect) throws Exception;
        JSONObject profile(OfficialWebPassport.CookieJar jar) throws Exception;
    }
    interface Clock { long now(); }

    static final String PHASE_IDLE="idle",PHASE_QRCODE="qrcode",PHASE_SCANNED="scanned",PHASE_LOGGED_IN="logged_in";
    static final int MAX_IMAGE_BYTES=512*1024;

    private final Object lock=new Object();
    private final Store store;
    private final Gateway gateway;
    private final Clock clock;
    private OfficialWebPassport.CookieJar jar=new OfficialWebPassport.CookieJar();
    private JSONObject profile;
    private String token="",image="",phase=PHASE_IDLE,error="",diagnostic="",scanState="";
    private long expiresAt;
    private long lastVerified;
    private boolean busy;

    OfficialWebAccount(Store store,Gateway gateway,Clock clock)throws Exception {
        this.store=store;this.gateway=gateway;this.clock=clock;
        try{
            JSONObject loaded=store.load();
            if(loaded!=null){
                String cookie=loaded.optString("cookie",""),cachedUid=loaded.optJSONObject("profile")==null?"":loaded.getJSONObject("profile").optString("uid","");
                if(!cookie.isEmpty()&&!cachedUid.isEmpty()){
                    jar=new OfficialWebPassport.CookieJar();jar.restore(cookie);
                    profile=new JSONObject().put("uid",cachedUid).put("name",loaded.getJSONObject("profile").optString("name","红果用户"));
                    phase=PHASE_LOGGED_IN;
                }
            }
        }catch(Exception failure){error="官网登录状态读取未完成，请重新扫码";diagnostic="session_invalid";AccountLog.error("official_web_session_load_failed:"+failure.getClass().getSimpleName());}
    }

    /** Starts a QR session; the returned image is a data URL the phone can scan directly. */
    JSONObject start()throws Exception{
        synchronized(lock){
            if(busy)throw new IOException("账号操作处理中，请稍候");
            if(profile!=null)throw new IOException("请先退出当前红果账号");
            busy=true;error="";diagnostic="";
            try{
                jar=new OfficialWebPassport.CookieJar();
                JSONObject data=data(gateway.start(jar));
                String sessionToken=data.optString("token","").trim();
                String source=data.optString("qrcode","").trim();
                if(sessionToken.isEmpty()||source.isEmpty())throw new IOException("红果登录二维码格式已变化");
                int at=source.lastIndexOf("base64,");
                String image=source.regionMatches(true,0,"data:image/",0,11)?source:"data:image/png;base64,"+(at>=0?source.substring(at+7):source);
                if(image.length()>MAX_IMAGE_BYTES)throw new IOException("红果登录二维码过大");
                long raw=data.optLong("expire_time",0);
                int expiresIn=raw>1000000000L?(int)Math.max(0,Math.min(600,raw-clock.now()/1000)):(int)Math.max(0,Math.min(600,raw>0?raw:180));
                token=sessionToken;this.image=image;expiresAt=clock.now()+expiresIn*1000L;phase=PHASE_QRCODE;scanState="";
                AccountLog.warn("official_web_qrcode_started");
                return status().put("image",image).put("expiresIn",expiresIn);
            }catch(Exception failure){token="";image="";scanState="";phase=PHASE_IDLE;error=reason(failure);diagnostic="qrcode_failed";AccountLog.warn("official_web_qrcode_failed:"+failure.getClass().getSimpleName());throw failure;}
            finally{busy=false;}
        }
    }

    /** Polls the scan state; a confirmed code persists the session and selects the account. */
    JSONObject poll()throws Exception{
        synchronized(lock){
            if(busy)throw new IOException("账号操作处理中，请稍候");
            if(token.isEmpty())throw new IOException("请先获取二维码");
            if(clock.now()>=expiresAt){token="";image="";phase=PHASE_IDLE;error="二维码已过期，请重新获取";diagnostic="qrcode_expired";throw new IOException(error);}
            busy=true;error="";
            try{
                JSONObject data=data(gateway.poll(jar,token));
                String state=data.optString("status","").trim();
                if(expired(state)){token="";image="";phase=PHASE_IDLE;error="二维码已过期，请重新获取";diagnostic="qrcode_expired";throw new IOException(error);}
                if(confirmed(state)){
                    String redirect=data.optString("redirect_url","").trim();
                    if(!redirect.isEmpty())gateway.confirm(jar,redirect);
                    if(!jar.hasSession())throw new IOException("登录确认未完成，请重新扫码");
                    JSONObject next=readProfile(data(gateway.profile(jar)));
                    store.save(new JSONObject().put("version",1).put("cookie",jar.header()).put("profile",next));
                    profile=next;token="";image="";phase=PHASE_LOGGED_IN;diagnostic="";
                    AccountLog.warn("official_web_login_confirmed");
                    return status();
                }
                phase=PHASE_SCANNED;
                scanState=scanned(state)?"scanned":"waiting";
                return status();
            }catch(Exception failure){error=reason(failure);if(diagnostic.isEmpty())diagnostic="poll_failed";AccountLog.warn("official_web_poll_failed:"+failure.getClass().getSimpleName());throw failure;}
            finally{busy=false;}
        }
    }

    /** Re-checks a stored session in the background and drops it when the official site rejects it. */
    void restore(){
        synchronized(lock){
            if(profile==null||busy)return;
            if(clock.now()-lastVerified<300_000L)return;
            lastVerified=clock.now();
            busy=true;
        }
        try{
            JSONObject next=readProfile(data(gateway.profile(jar)));
            synchronized(lock){profile=next;phase=PHASE_LOGGED_IN;diagnostic="";}
        }catch(Exception failure){
            synchronized(lock){profile=null;jar=new OfficialWebPassport.CookieJar();phase=PHASE_IDLE;diagnostic="session_expired";}
            try{store.clear();}catch(Exception ignored){}
            AccountLog.warn("official_web_session_expired:"+failure.getClass().getSimpleName());
        }finally{synchronized(lock){busy=false;}}
    }

    /** Drops a pending QR session; an already confirmed session is left untouched. */
    JSONObject cancel()throws JSONException{
        synchronized(lock){
            if(profile==null){token="";image="";scanState="";phase=PHASE_IDLE;error="";diagnostic="";jar=new OfficialWebPassport.CookieJar();}
            return status();
        }
    }

    JSONObject logout()throws Exception{
        synchronized(lock){
            profile=null;token="";image="";scanState="";phase=PHASE_IDLE;error="";diagnostic="";
            jar=new OfficialWebPassport.CookieJar();
            try{store.clear();}catch(Exception failure){AccountLog.error("official_web_session_clear_failed:"+failure.getClass().getSimpleName());}
            return status();
        }
    }

    /** Public projection: the cookie, the token and the raw account id never appear here. */
    JSONObject status()throws JSONException{
        synchronized(lock){
            JSONObject result=new JSONObject().put("provider","hongguo_web").put("phase",phase).put("busy",busy).put("authenticated",profile!=null)
                .put("error",error).put("diagnostic",diagnostic).put("scanState",scanState).put("expiresIn",Math.max(0,(expiresAt-clock.now()+999)/1000));
            if(profile!=null)result.put("user",new JSONObject().put("name",profile.optString("name","红果用户")).put("idSuffix",profile.optString("uid","").length()>4?"尾号 "+profile.optString("uid","").substring(profile.optString("uid","").length()-4):""));
            return result;
        }
    }

    private static boolean confirmed(String state){
        return "3".equals(state)||"3confirmed".equalsIgnoreCase(state)||"confirmed".equalsIgnoreCase(state);
    }
    private static boolean scanned(String state){return "2".equals(state)||"2scanned".equalsIgnoreCase(state)||"scanned".equalsIgnoreCase(state);}
    private static boolean expired(String state){return "5".equals(state)||"5expired".equalsIgnoreCase(state)||"expired".equalsIgnoreCase(state)||"4".equals(state)||"4change".equalsIgnoreCase(state);}

    /** The official payloads answer with message=success and a data object; anything else is refused. */
    static JSONObject data(JSONObject payload)throws IOException{
        if(payload==null)throw new IOException("红果官网接口返回格式异常");
        String message=payload.optString("message","").trim();
        if(!message.isEmpty()&&!"success".equalsIgnoreCase(message)){
            JSONObject nested=payload.optJSONObject("data");
            String description=nested==null?"":clean(nested.optString("description",""));
            throw new IOException(description.isEmpty()?clean(message):description);
        }
        if(payload.has("code")&&payload.optInt("code",0)!=0)throw new IOException("红果官网接口返回错误（"+payload.optInt("code",0)+"）");
        JSONObject data=payload.optJSONObject("data");
        if(data==null)throw new IOException("红果官网接口返回内容为空");
        return data;
    }

    static JSONObject readProfile(JSONObject data)throws Exception{
        String id=data.optString("user_id_str",data.optString("user_id","")).trim();
        if(id.isEmpty())throw new IOException("登录态未通过红果账号验证，请重新扫码");
        if(!id.matches("\\d{1,30}")||!id.matches(".*[1-9].*"))throw new IOException("红果账号编号无效，请重新扫码");
        String name=clean(data.optString("name",data.optString("screen_name",data.optString("username",""))));
        if(name.isEmpty())name="红果用户";
        if(name.length()>80)name=name.substring(0,80);
        return new JSONObject().put("uid",id).put("name",name);
    }

    private static String clean(String value){
        String text=String.valueOf(value==null?"":value).replaceAll("<[^>]*>","").replace("&amp;","&").replace("&lt;","<").replace("&gt;",">").replace("&quot;","\"").replace("&#39;","'");
        return text.replaceAll("\\s+"," ").trim();
    }

    private static String reason(Exception failure){
        if(failure instanceof java.net.SocketTimeoutException)return "红果官网请求超时，请稍后重试";
        if(failure instanceof java.io.InterruptedIOException)return "登录已取消";
        String value=failure.getMessage();
        if(value==null||value.length()>160||value.contains("http")||value.indexOf('\n')>=0||value.indexOf('\r')>=0)return "红果官网请求未完成，请重试";
        String lower=value.toLowerCase(Locale.ROOT);
        for(String secret:new String[]{"cookie","sessionid","token","csrf","passport"})if(lower.contains(secret))return "红果官网请求未完成，请重试";
        return value.replaceAll("[0-9]{4,}","***");
    }
}
