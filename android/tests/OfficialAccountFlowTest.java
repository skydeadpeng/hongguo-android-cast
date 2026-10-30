package io.github.jackotom.hongguo.android;

import java.io.*;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

public final class OfficialAccountFlowTest {
    static int groups,checks;
    interface Checked {void run()throws Exception;}
    static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    static void group(String name,Checked test)throws Exception{test.run();groups++;System.out.println("PASS "+name);}
    static JSONObject copyJSON(JSONObject value)throws JSONException{return new JSONObject(value.toString());}
    static JSONObject ok(JSONObject data)throws JSONException{return new JSONObject().put("message","success").put("data",data);}
    static JSONObject login(String uid)throws JSONException{return ok(new JSONObject().put("user_id_str",uid).put("user_id",uid).put("name","合成账号"));}
    static JSONObject mfa()throws JSONException{return new JSONObject().put("message","error").put("data",new JSONObject().put("error_code",2002).put("encrypt_uid","PRIVATE_ENCRYPT_UID").put("sms_code_key","PRIVATE_CODE_KEY").put("event_params",new JSONObject().put("log_id","PRIVATE_LOG")).put("verify_ways",new JSONArray().put(new JSONObject().put("verify_way","upsms").put("channel_mobile","106900000001").put("sms_content","SYNTHETIC MESSAGE"))));}
    static final class Memory implements OfficialAccountFlow.Store {
        JSONObject disk;int saves;boolean failProfile,failLoad;
        public JSONObject load()throws Exception{if(failLoad)throw new IOException("corrupt");return disk==null?null:copyJSON(disk);}
        public void save(JSONObject value)throws Exception{if(failProfile&&value.has("profile"))throw new IOException("synthetic disk full");disk=copyJSON(value);saves++;}
    }
    static final class Clock implements OfficialAccountFlow.Clock {long now=100000,nanos=1_000_000_000L;public long now(){return now;}public long nanos(){return nanos;}}
    static final class Factory implements OfficialAccountFlow.Clients {
        final Queue<Object>send=new ArrayDeque<>(),login=new ArrayDeque<>(),verify=new ArrayDeque<>();final List<String>tickets=new ArrayList<>();
        final Memory store;int registrations,sends,logins,verifications,closes,sleeps;boolean failRegister,noCredentials,forceCredentials;CountDownLatch entered,release;
        Runnable onVerify;Factory(Memory store){this.store=store;}
        public JSONObject fresh()throws Exception{return new JSONObject().put("registered",false).put("device_id","").put("install_id","").put("cookies",new JSONObject()).put("token","");}
        Object next(Queue<Object>values,Object fallback)throws Exception{Object value=values.isEmpty()?fallback:values.remove();if(value instanceof Exception)throw(Exception)value;return value;}
        public OfficialAccountFlow.Client create(JSONObject input)throws Exception{return new OfficialAccountFlow.Client(){
            JSONObject state=copyJSON(input);boolean closed;
            public void registerDevice()throws Exception{if(!state.optBoolean("registered")){registrations++;if(failRegister)throw new IOException("synthetic register failure");state.put("registered",true).put("device_id","7000000000000000001").put("install_id","7000000000000000002");}}
            public JSONObject session()throws Exception{return copyJSON(state);}
            public JSONObject sendCode(String phone)throws Exception{sends++;check(store.disk!=null&&store.disk.getJSONObject("session").getBoolean("registered"),"registration persisted before SMS");if(entered!=null){entered.countDown();long stop=System.nanoTime()+2_000_000_000L;while(release.getCount()>0&&System.nanoTime()<stop)try{release.await(20,TimeUnit.MILLISECONDS);}catch(InterruptedException ignored){} }return(JSONObject)next(send,ok(new JSONObject()));}
            public JSONObject smsLogin(String phone,String code,String ticket)throws Exception{logins++;tickets.add(ticket);JSONObject value=(JSONObject)next(login,OfficialAccountFlowTest.login("7123456789012345678"));if(forceCredentials||OfficialAccountFlow.success(value)&&value.getJSONObject("data").has("user_id_str")&&!noCredentials)state.put("token","PRIVATE_SESSION_TOKEN");return value;}
            public JSONObject verifyMfa(JSONObject challenge,String phone)throws Exception{verifications++;if(onVerify!=null)onVerify.run();return(JSONObject)next(verify,new JSONObject().put("message","error").put("data",new JSONObject().put("error_code",1045)));}
            public void close(){if(!closed){closes++;closed=true;}}
        };}
    }
    static final class Env {
        final Memory store=new Memory();final Clock clock=new Clock();final Factory factory=new Factory(store);final OfficialAccountFlow flow;
        Env()throws Exception{flow=new OfficialAccountFlow(store,factory,clock,ms->{factory.sleeps++;clock.now+=ms;clock.nanos+=TimeUnit.MILLISECONDS.toNanos(ms);});}
    }
    static String otp(OfficialAccountFlow flow)throws Exception{Field field=OfficialAccountFlow.class.getDeclaredField("otp");field.setAccessible(true);return(String)field.get(flow);}
    static void rejects(Checked action,String text)throws Exception{boolean failed=false;try{action.run();}catch(Exception expected){failed=true;}check(failed,text);}
    public static void main(String[]args)throws Exception {
        group("user-only registration persistence, masking and cooldown survive a new flow",()->{
            Env e=new Env();check(e.factory.sends==0&&e.store.saves==0,"startup sends/writes nothing");JSONObject status=e.flow.sendCode("+86 13800000000");check(status.getString("phase").equals("code")&&status.getString("smsState").equals("sent"),"explicit send confirmed");check(status.getString("maskedPhone").equals("138****0000")&&!status.toString().contains("13800000000"),"full phone not projected");check(e.factory.registrations==1&&e.factory.sends==1,"one device registration and one SMS");rejects(()->e.flow.sendCode("13800000000"),"cooldown blocks repeated sends");check(e.factory.sends==1,"no second SMS");new OfficialAccountFlow(e.store,e.factory,e.clock,ms->e.clock.now+=ms);check(e.factory.sends==1&&e.factory.registrations==1,"restart only loads registered state");check(!e.store.disk.toString().contains("13800000000"),"phone not persisted");
        });
        group("registration failure, rejection, rate limit and uncertain delivery differ",()->{
            Env reg=new Env();reg.factory.failRegister=true;JSONObject s=reg.flow.sendCode("13800000000");check(reg.factory.sends==0&&s.getString("smsState").equals("rejected")&&s.getInt("retryAfter")==0,"registration failure prevents SMS");
            Env reject=new Env();reject.factory.send.add(new OfficialApiClient.HTTPError(403,0));s=reject.flow.sendCode("13800000000");check(s.getString("smsState").equals("rejected")&&s.getInt("retryAfter")==0,"403 no fake countdown");
            Env limited=new Env();limited.factory.send.add(new OfficialApiClient.HTTPError(429,123));s=limited.flow.sendCode("13800000000");check(s.getString("smsState").equals("limited")&&s.getInt("retryAfter")==123,"429 respects waiting");
            Env unknown=new Env();unknown.factory.send.add(new java.net.SocketTimeoutException());s=unknown.flow.sendCode("13800000000");check(s.getString("smsState").equals("uncertain")&&s.getInt("retryAfter")==60,"timeout retains delivery guard");check(s.getString("error").contains("超时")&&!s.getString("error").contains("取消"),"network timeout is not reported as user cancellation");
        });
        group("ambiguous JSON and explicit server retry times retain resend guard",()->{
            Env ambiguous=new Env();ambiguous.factory.send.add(new JSONObject().put("message","error").put("data",new JSONObject()));JSONObject s=ambiguous.flow.sendCode("13800000000");check(s.getString("smsState").equals("uncertain")&&s.getInt("retryAfter")==60,"unconfirmed JSON is not a definite rejection");rejects(()->ambiguous.flow.sendCode("13800000000"),"ambiguous delivery cannot immediately resend");
            Env limited=new Env();limited.factory.send.add(new JSONObject().put("message","error").put("data",new JSONObject().put("error_code",429).put("retry_time",120)));s=limited.flow.sendCode("13800000000");check(s.getString("smsState").equals("limited")&&s.getInt("retryAfter")==120,"business limit keeps retry_time");
            Env rejected=new Env();rejected.factory.send.add(new JSONObject().put("message","error").put("data",new JSONObject().put("error_code",1009).put("retry_time",30)));s=rejected.flow.sendCode("13800000000");check(s.getString("smsState").equals("rejected")&&s.getInt("retryAfter")==30,"explicit failure still respects server retry_time");
        });
        group("malformed business code and private remote descriptions never clear resend guard or leak credentials",()->{
            Env e=new Env();e.factory.send.add(new JSONObject().put("message","error").put("data",new JSONObject().put("error_code",new JSONObject().put("PRIVATE_TOKEN","private")).put("description","bad token PRIVATE_SESSION")));
            JSONObject s=e.flow.sendCode("13800000000");check(s.getString("smsState").equals("uncertain")&&s.getInt("retryAfter")==60,"invalid code remains uncertain");check(!s.toString().contains("PRIVATE")&&!s.toString().contains("private"),"private remote code and description are never echoed");
        });
        group("wrong OTP clears only OTP and retains current phone for retry",()->{
            Env e=new Env();e.flow.sendCode("13800000000");e.factory.login.add(new JSONObject().put("message","error").put("data",new JSONObject().put("error_code",1009).put("description","验证码错误")));JSONObject s=e.flow.loginSMS("13800000000","012345");check(!s.getBoolean("authenticated")&&s.getString("phase").equals("code"),"wrong OTP not login");check(otp(e.flow).isEmpty()&&e.flow.phoneForLogin().equals("13800000000"),"OTP cleared, phone retained native");check(!e.store.disk.toString().contains("012345"),"OTP never stored");s=e.flow.loginSMS("13800000000","123456");check(s.getBoolean("authenticated")&&s.getString("phase").equals("logged_in")&&!s.getBoolean("busy"),"retry confirms login");check(e.store.disk.has("profile")&&e.store.disk.getJSONObject("session").getString("token").equals("PRIVATE_SESSION_TOKEN"),"session saved");check(!s.toString().contains("PRIVATE_SESSION_TOKEN")&&!s.toString().contains("7123456789012345678"),"public status contains no session/raw uid");
        });
        group("identity consistency and credentials, not HTTP200 or code0 alone",()->{
            for(JSONObject response:new JSONObject[]{ok(new JSONObject().put("user_id_str","7123456789012345678").put("user_id","7123456789012345679")),ok(new JSONObject().put("user_id_str","0")),new JSONObject().put("code",0).put("data",new JSONObject())}){Env e=new Env();e.factory.login.add(response);JSONObject s=e.flow.loginSMS("13800000000","123456");check(!s.getBoolean("authenticated")&&!e.store.disk.has("profile"),"bad identity/success rejected");}
            Env missing=new Env();missing.factory.noCredentials=true;check(!missing.flow.loginSMS("13800000000","123456").getBoolean("authenticated"),"missing session rejected");
        });
        group("contradictory top-level/nested codes cannot declare SMS login or MFA success",()->{
            for(String location:new String[]{"top:error_code","top:code","top:status_code","data:code","data:status_code","data:error_code"}){
                String[] parts=location.split(":");JSONObject response=login("7123456789012345678");response.put("error_code",0);response.getJSONObject("data").put("error_code",0);(parts[0].equals("top")?response:response.getJSONObject("data")).put(parts[1],999);
                check(!OfficialAccountFlow.success(response),"strict all-code success rejects "+location);
                Env login=new Env();login.factory.forceCredentials=true;login.factory.login.add(response);check(!login.flow.loginSMS("13800000000","123456").getBoolean("authenticated"),"captured cookie/token does not override contradictory login "+location);
                Env send=new Env();send.factory.send.add(response);check(send.flow.sendCode("13800000000").getString("smsState").equals("rejected"),"contradictory send is not delivered success "+location);
                Env m=new Env();m.factory.login.add(mfa());m.flow.loginSMS("13800000000","123456");JSONObject ticket=ok(new JSONObject().put("ticket","PRIVATE_TICKET"));ticket.put("status_code",999);m.factory.verify.add(ticket);check(!m.flow.verifyMfa().getBoolean("authenticated")&&m.factory.logins==1,"contradictory MFA ticket never triggers authenticated re-login");
            }
        });
        group("encrypted persistence failure cannot announce authentication",()->{
            Env e=new Env();e.store.failProfile=true;JSONObject s=e.flow.loginSMS("13800000000","123456");check(!s.getBoolean("authenticated")&&!e.store.disk.has("profile"),"disk failure does not select account");check(otp(e.flow).isEmpty(),"failed save clears OTP");
        });
        group("MFA waits for explicit user, polls then retries login with returned ticket",()->{
            Env e=new Env();e.factory.login.add(mfa());JSONObject s=e.flow.loginSMS("13800000000","123456");check(s.getString("phase").equals("mfa")&&s.getString("smsNumber").equals("106900000001"),"upstream challenge displayed");check(e.factory.verifications==0,"no automatic MFA query before user confirms");check(!s.toString().contains("PRIVATE_CODE_KEY")&&!s.toString().contains("PRIVATE_ENCRYPT_UID")&&!s.toString().contains("123456"),"no private MFA fields/OTP in public state");check(!e.store.disk.toString().contains("PRIVATE_CODE_KEY")&&!e.store.disk.toString().contains("123456"),"MFA/OTP never persisted");e.factory.verify.add(new JSONObject().put("message","error").put("data",new JSONObject().put("error_code",1045)));e.factory.verify.add(ok(new JSONObject().put("ticket","PRIVATE_TICKET")));s=e.flow.verifyMfa();check(s.getBoolean("authenticated")&&!s.getBoolean("busy")&&e.factory.verifications==2&&e.factory.sleeps==1,"one 3-second wait and confirmed login");check(e.factory.tickets.get(1).equals("PRIVATE_TICKET")&&otp(e.flow).isEmpty(),"ticket used only in native re-login, OTP cleared");
        });
        group("switching to SMS app preserves code and MFA across suspend/resume",()->{
            Env e=new Env();e.flow.sendCode("13800000000");e.flow.suspendLogin();check(e.flow.status().getString("phase").equals("code")&&e.flow.phoneForLogin().equals("13800000000"),"read incoming SMS and return to same phone");e.factory.login.add(mfa());e.flow.loginSMS("13800000000","123456");e.flow.suspendLogin();check(e.flow.status().getString("phase").equals("mfa")&&otp(e.flow).equals("123456"),"MFA native OTP/challenge retained while sending outgoing SMS");e.factory.verify.add(ok(new JSONObject().put("ticket","ticket")));check(e.flow.verifyMfa().getBoolean("authenticated"),"MFA continues after SMS app return");
        });
        group("MFA has 90-second round and ten-minute memory-only expiry",()->{
            Env e=new Env();e.factory.login.add(mfa());e.flow.loginSMS("13800000000","123456");JSONObject s=e.flow.verifyMfa();check(!s.getBoolean("authenticated")&&s.getString("phase").equals("mfa")&&e.factory.verifications==30,"90-second round bounded");e.clock.now+=600000;s=e.flow.status();check(s.getString("phase").equals("phone")&&!s.has("smsContent")&&otp(e.flow).isEmpty(),"expired challenge cleared");
        });
        group("MFA monotonic limit caps sleep and late success when wall clock moves backwards",()->{
            Env e=new Env();e.factory.login.add(mfa());e.flow.loginSMS("13800000000","123456");long begin=e.clock.nanos;
            e.factory.onVerify=()->{e.clock.now-=120000;e.clock.nanos+=TimeUnit.SECONDS.toNanos(89);};
            JSONObject s=e.flow.verifyMfa();check(!s.getBoolean("authenticated")&&e.factory.verifications==1&&e.factory.sleeps==1,"only one second sleep remains after an 89-second read, despite wall clock rollback");
            check(e.clock.nanos-begin==TimeUnit.SECONDS.toNanos(90),"poll round never sleeps beyond monotonic 90 seconds");
            Env late=new Env();late.factory.login.add(mfa());late.flow.loginSMS("13800000000","123456");late.factory.verify.add(ok(new JSONObject().put("ticket","late-private-ticket")));late.factory.onVerify=()->late.clock.nanos+=TimeUnit.SECONDS.toNanos(91);
            s=late.flow.verifyMfa();check(!s.getBoolean("authenticated")&&late.factory.logins==1,"late verify success cannot issue second SMS login or authenticate after round deadline");
        });
        group("explicit server-ended MFA clears challenge while transport errors retain retryable memory",()->{
            Env expired=new Env();expired.factory.login.add(mfa());expired.flow.loginSMS("13800000000","123456");expired.factory.verify.add(new JSONObject().put("message","error").put("data",new JSONObject().put("error_code",1010).put("description","本次验证已过期")));JSONObject s=expired.flow.verifyMfa();check(s.getString("phase").equals("phone")&&!s.has("smsContent")&&otp(expired.flow).isEmpty(),"server expiry clears dead challenge immediately");
            Env offline=new Env();offline.factory.login.add(mfa());offline.flow.loginSMS("13800000000","123456");offline.factory.verify.add(new java.net.SocketTimeoutException());s=offline.flow.verifyMfa();check(s.getString("phase").equals("mfa")&&s.has("smsContent")&&!otp(offline.flow).isEmpty(),"transport timeout does not falsely expire a valid challenge");
        });
        group("explicit cancellation ignores a late send response without blocking on network",()->{
            Env e=new Env();e.factory.entered=new CountDownLatch(1);e.factory.release=new CountDownLatch(1);Thread request=new Thread(()->{try{e.flow.sendCode("13800000000");}catch(Exception ignored){}});request.start();check(e.factory.entered.await(1,TimeUnit.SECONDS),"SMS in flight");long start=System.nanoTime();e.flow.cancelLogin();check(System.nanoTime()-start<200_000_000L,"cancel returns promptly");e.factory.release.countDown();request.join(1000);check(!request.isAlive()&&!e.flow.status().getBoolean("authenticated")&&e.flow.status().getString("phase").equals("phone"),"late response cannot revive cancelled attempt");check(e.flow.phoneForLogin().isEmpty()&&otp(e.flow).isEmpty(),"explicit cancel clears native phone/OTP");
        });
        group("logout and reloaded sessions preserve guest boundaries and invalidate sync epochs",()->{
            Env e=new Env();e.flow.loginSMS("13800000000","123456");long epoch=e.flow.epoch();String uid=e.flow.uid();e.flow.suspendLogin();check(e.flow.current(epoch,uid),"background preserves logged session epoch");e.flow.logout();check(!e.flow.current(epoch,uid)&&!e.flow.status().getBoolean("authenticated"),"logout invalidates old cloud callback");check(!OfficialAccountFlow.credentials(e.store.disk.getJSONObject("session")),"logout commits no credentials");check(!e.store.disk.has("profile")&&e.store.disk.getJSONObject("session").getBoolean("registered"),"registered device retained without user selection");
        });
        System.out.println("OFFICIAL_ACCOUNT_FLOW_OK groups="+groups+" checks="+checks+" networkRequests=0 realSms=not_run deviceTests=not_run");
    }
}
