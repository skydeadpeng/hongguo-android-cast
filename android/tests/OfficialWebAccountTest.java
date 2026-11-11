package io.github.jackotom.hongguo.android;

import java.io.*;
import java.util.*;
import org.json.*;

public final class OfficialWebAccountTest {
    static int groups,checks;
    interface Checked {void run()throws Exception;}
    static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    static void group(String name,Checked test)throws Exception{test.run();groups++;System.out.println("PASS "+name);}
    static JSONObject confirmed()throws JSONException{return new JSONObject().put("message","success").put("data",new JSONObject().put("status","3confirmed").put("redirect_url","https://hongguoduanju.com/confirmed"));}
    static JSONObject scanned()throws JSONException{return new JSONObject().put("message","success").put("data",new JSONObject().put("status","2scanned"));}
    static JSONObject expiredCode()throws JSONException{return new JSONObject().put("message","success").put("data",new JSONObject().put("status","5expired"));}

    static final class Store implements OfficialWebAccount.Store {
        JSONObject disk;boolean failSave,failLoad;
        public JSONObject load()throws Exception{if(failLoad)throw new IOException("corrupt");return disk==null?null:new JSONObject(disk.toString());}
        public void save(JSONObject value)throws Exception{if(failSave)throw new IOException("synthetic disk full");disk=new JSONObject(value.toString());}
        public void clear(){disk=null;}
    }
    static final class Clock implements OfficialWebAccount.Clock {long now=1_700_000_000_000L;public long now(){return now;}}
    static final class Gateway implements OfficialWebAccount.Gateway {
        final Queue<Object> polls=new ArrayDeque<>();String cookieOnConfirm="";boolean profileInvalid;int starts,pollCalls,confirms,profiles;
        public JSONObject start(OfficialWebPassport.CookieJar jar)throws Exception{starts++;return new JSONObject().put("message","success").put("data",new JSONObject().put("token","PRIVATE_QR_TOKEN").put("qrcode","data:image/png;base64,AAAA").put("expire_time",(System.currentTimeMillis()/1000)+180));}
        public JSONObject poll(OfficialWebPassport.CookieJar jar,String token)throws Exception{pollCalls++;Object next=polls.isEmpty()?scanned():polls.remove();return(JSONObject)next;}
        public void confirm(OfficialWebPassport.CookieJar jar,String redirect)throws Exception{confirms++;if(!cookieOnConfirm.isEmpty())jar.absorb(Collections.singletonList(cookieOnConfirm));}
        public JSONObject profile(OfficialWebPassport.CookieJar jar)throws Exception{profiles++;if(profileInvalid)throw new IOException("官网会话已失效");return new JSONObject().put("message","success").put("data",new JSONObject().put("user_id_str","7123456789012345678").put("name","扫码账号"));}
    }
    static final class Env {
        Store store;final Clock clock=new Clock();final Gateway gateway=new Gateway();final OfficialWebAccount web;
        Env()throws Exception{this(new Store());}
        Env(Store store)throws Exception{this.store=store;web=new OfficialWebAccount(store,gateway,clock);}
    }

    public static void main(String[]args)throws Exception {
        group("a QR session returns a scannable image and no credential",()->{
            Env e=new Env();JSONObject s=e.web.start();
            check(e.gateway.starts==1,"one QR session requested");
            check(s.getString("phase").equals("qrcode"),"the QR phase is reported");
            check(s.getString("image").startsWith("data:image/png;base64,"),"the image is a data URL the phone can scan");
            check(s.getInt("expiresIn")>0&&s.getInt("expiresIn")<=600,"the expiry is bounded");
            check(!s.toString().contains("PRIVATE_QR_TOKEN"),"the QR token never reaches the UI projection");
            check(!s.toString().contains("sessionid"),"no session cookie reaches the UI projection");
        });
        group("a scanned code waits for the phone confirmation",()->{
            Env e=new Env();e.web.start();JSONObject s=e.web.poll();
            check(s.getString("phase").equals("scanned"),"a scanned code waits");
            check(!s.getBoolean("authenticated"),"a scan alone never authenticates");
            check(s.getString("scanState").equals("scanned"),"the scan state is reported");
            check(e.store.disk==null,"nothing is persisted before the confirmation");
        });
        group("a confirmed code persists the session and selects the account",()->{
            Env e=new Env();e.web.start();e.gateway.polls.add(confirmed());e.gateway.cookieOnConfirm="sessionid=PRIVATE_SESSION; Path=/; Secure";
            JSONObject s=e.web.poll();
            check(s.getBoolean("authenticated")&&s.getString("phase").equals("logged_in"),"the confirmed scan authenticates");
            check(e.gateway.confirms==1,"the confirmation redirect is followed");
            check(e.store.disk!=null&&e.store.disk.getString("cookie").contains("PRIVATE_SESSION"),"the session cookie is persisted");
            check(!s.toString().contains("PRIVATE_SESSION"),"the cookie never reaches the UI projection");
            check(s.getJSONObject("user").getString("name").equals("扫码账号"),"the account name is projected");
            check(!s.toString().contains("7123456789012345678"),"the raw account id never reaches the UI projection");
        });
        group("a confirmation without a session cookie never authenticates",()->{
            Env e=new Env();e.web.start();e.gateway.polls.add(confirmed());
            boolean failed=false;try{e.web.poll();}catch(Exception expected){failed=true;}
            check(failed,"a missing session cookie fails the login");
            check(!e.web.status().getBoolean("authenticated"),"no account is selected");
            check(e.store.disk==null,"nothing is persisted");
        });
        group("an expired code is refused and the session is cleared",()->{
            Env e=new Env();e.web.start();e.clock.now+=700000;
            boolean failed=false;try{e.web.poll();}catch(Exception expected){failed=true;}
            check(failed,"an expired QR fails");
            check(e.web.status().getString("phase").equals("idle"),"the expired session is cleared");
        });
        group("an expired scan status is refused",()->{
            Env e=new Env();e.web.start();e.gateway.polls.add(expiredCode());
            boolean failed=false;try{e.web.poll();}catch(Exception expected){failed=true;}
            check(failed,"an expired scan status fails");
            check(e.web.status().getString("phase").equals("idle"),"the expired session is cleared");
        });
        group("a rejected profile never authenticates",()->{
            Env e=new Env();e.web.start();e.gateway.polls.add(confirmed());e.gateway.cookieOnConfirm="sessionid=PRIVATE_SESSION";
            e.gateway.profileInvalid=true;
            boolean failed=false;try{e.web.poll();}catch(Exception expected){failed=true;}
            check(failed,"a rejected profile fails the login");
            check(!e.web.status().getBoolean("authenticated"),"no account is selected");
            check(e.store.disk==null,"nothing is persisted");
        });
        group("a store failure never announces a login",()->{
            Env e=new Env();e.store.failSave=true;e.web.start();e.gateway.polls.add(confirmed());e.gateway.cookieOnConfirm="sessionid=PRIVATE_SESSION";
            boolean failed=false;try{e.web.poll();}catch(Exception expected){failed=true;}
            check(failed,"a store failure fails the login");
            check(!e.web.status().getBoolean("authenticated"),"no account is selected");
        });
        group("a stored session is restored and dropped when the site rejects it",()->{
            Store store=new Store();Env first=new Env(store);first.web.start();first.gateway.polls.add(confirmed());first.gateway.cookieOnConfirm="sessionid=PRIVATE_SESSION";first.web.poll();
            Env again=new Env(store);
            check(again.web.status().getBoolean("authenticated"),"a stored session is restored");
            again.gateway.profileInvalid=true;again.web.restore();
            check(!again.web.status().getBoolean("authenticated"),"a rejected session is dropped");
            check(store.disk==null,"the rejected session is erased");
        });
        group("logout clears the stored session",()->{
            Env e=new Env();e.web.start();e.gateway.polls.add(confirmed());e.gateway.cookieOnConfirm="sessionid=PRIVATE_SESSION";e.web.poll();
            JSONObject s=e.web.logout();
            check(!s.getBoolean("authenticated")&&e.store.disk==null,"logout clears the session");
            check(s.getString("phase").equals("idle"),"the phase returns to idle");
        });
        group("the official payload shape is enforced",()->{
            boolean refused=false;try{OfficialWebAccount.data(new JSONObject().put("message","error").put("data",new JSONObject().put("description","手机号错误")));}catch(IOException expected){refused=true;}
            check(refused,"an error payload is refused");
            refused=false;try{OfficialWebAccount.data(new JSONObject().put("message","success").put("code",7));}catch(IOException expected){refused=true;}
            check(refused,"a non-zero code is refused");
            refused=false;try{OfficialWebAccount.data(new JSONObject().put("message","success"));}catch(IOException expected){refused=true;}
            check(refused,"a missing data object is refused");
            refused=false;try{OfficialWebAccount.readProfile(new JSONObject().put("user_id_str","0"));}catch(Exception expected){refused=true;}
            check(refused,"a zero account id is refused");
            refused=false;try{OfficialWebAccount.readProfile(new JSONObject().put("user_id_str","abc"));}catch(Exception expected){refused=true;}
            check(refused,"a non numeric account id is refused");
        });
        group("the cookie jar keeps the session and drops expired entries",()->{
            OfficialWebPassport.CookieJar jar=new OfficialWebPassport.CookieJar();
            jar.absorb(Arrays.asList("passport_csrf_token=PRIVATE_CSRF; Path=/; Secure","reg-store-region=; Path=/; Max-Age=0","sessionid=PRIVATE_SESSION; Path=/; Secure"));
            check(jar.hasSession(),"the session cookie is kept");
            check(jar.header().contains("passport_csrf_token=PRIVATE_CSRF"),"the csrf cookie is kept");
            check(!jar.header().contains("reg-store-region"),"a Max-Age=0 cookie is dropped");
            OfficialWebPassport.CookieJar restored=new OfficialWebPassport.CookieJar();
            restored.restore("sessionid=PRIVATE_SESSION; passport_csrf_token=PRIVATE_CSRF");
            check(restored.hasSession()&&restored.header().contains("passport_csrf_token=PRIVATE_CSRF"),"a stored cookie header is restored completely");
        });
        System.out.println("OFFICIAL_WEB_ACCOUNT_OK groups="+groups+" checks="+checks+" networkRequests=0 realScan=not_run");
    }
}
