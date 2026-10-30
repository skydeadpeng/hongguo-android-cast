package io.github.jackotom.hongguo.android;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.concurrent.atomic.AtomicLong;
import org.json.*;

/** Native-only login state. OTP/challenges never enter a preference or public account projection. */
final class OfficialAccountFlow {
    interface Store { JSONObject load() throws Exception; void save(JSONObject value) throws Exception; }
    interface Client extends AutoCloseable {
        void registerDevice() throws Exception;
        JSONObject session() throws Exception;
        JSONObject sendCode(String phone) throws Exception;
        JSONObject smsLogin(String phone,String code,String ticket) throws Exception;
        JSONObject verifyMfa(JSONObject challenge,String phone) throws Exception;
        void close();
        default void deadlineNanos(long value) { }
    }
    interface Clients { Client create(JSONObject session) throws Exception; JSONObject fresh() throws Exception; }
    interface Clock { long now(); default long nanos(){return System.nanoTime();} }
    interface Sleeper { void sleep(long millis) throws InterruptedException; }
    private final Object lock=new Object();
    private final Store store;
    private final Clients clients;
    private final Clock clock;
    private final Sleeper sleeper;
    private final AtomicLong generation=new AtomicLong();
    private JSONObject disk,challenge;
    private String phone="",otp="",phase="phone",smsState="",error="";
    private long retryAt,mfaExpires,mfaExpiresNanos;
    private boolean busy,loginRequired;
    private volatile Client active;
    private volatile Thread activeThread;
    private volatile Runnable changed;

    OfficialAccountFlow(Store store,Clients clients,Clock clock,Sleeper sleeper)throws Exception {
        this.store=store;this.clients=clients;this.clock=clock;this.sleeper=sleeper;
        try {
            JSONObject loaded=store.load();
            disk=loaded==null?empty(clients.fresh()):validateDisk(loaded);
            phase=profile()!=null?"logged_in":"phone";
        }catch(Exception failure){disk=empty(clients.fresh());error="账号凭据读取未完成，请重新登录；原本机记录保留";loginRequired=true;}
    }
    void setListener(Runnable value){changed=value;}
    private void notifyChanged(){Runnable value=changed;if(value!=null)try{value.run();}catch(RuntimeException ignored){}}
    private static JSONObject copy(JSONObject value)throws JSONException{return new JSONObject(value.toString());}
    private static JSONObject empty(JSONObject session)throws JSONException{return new JSONObject().put("version",1).put("session",session);}
    private static JSONObject validateDisk(JSONObject value)throws Exception {
        JSONObject data=copy(value);if(data.optInt("version",0)!=1||data.optJSONObject("session")==null)throw new IOException("账号存储格式无效");
        JSONObject p=data.optJSONObject("profile");if(p!=null&&(!validID(p.optString("uid"))||!credentials(data.getJSONObject("session"))))throw new IOException("账号会话不完整");
        return data;
    }
    private JSONObject profile(){return disk.optJSONObject("profile");}
    String phoneForLogin(){synchronized(lock){return phone;}}
    String uid(){synchronized(lock){JSONObject p=profile();return p==null?"":p.optString("uid");}}
    long epoch(){return generation.get();}
    boolean current(long epoch,String uid){return generation.get()==epoch&&uid.equals(uid());}
    JSONObject session()throws Exception{synchronized(lock){return copy(disk.getJSONObject("session"));}}
    void refreshedSession(JSONObject session,long epoch,String uid)throws Exception {
        synchronized(lock){if(!current(epoch,uid))return;JSONObject next=copy(disk).put("session",copy(session));store.save(next);if(current(epoch,uid))disk=next;}
    }
    JSONObject status()throws JSONException {
        synchronized(lock){expireLocked();JSONObject p=profile();JSONObject result=new JSONObject().put("provider","hongguo").put("revision",generation.get()).put("officialLoginAvailable",true).put("authenticated",p!=null).put("phase",phase).put("busy",busy).put("maskedPhone",mask(phone)).put("retryAfter",Math.max(0,(retryAt-clock.now()+999)/1000)).put("smsState",smsState).put("error",error).put("loginRequired",loginRequired).put("selfHostedRemoved",true).put("legacyRecordsPreserved",true);
            if(p!=null)result.put("user",new JSONObject().put("name",p.optString("name","红果用户")).put("maskedPhone",p.optString("maskedPhone","")).put("idSuffix",suffix(p.optString("uid"))));
            if(challenge!=null){JSONObject safe=mfaDisplay(challenge);result.put("smsNumber",safe.optString("number")).put("smsContent",safe.optString("content")).put("mfaExpiresAt",mfaExpires);}
            return result;
        }
    }
    static String canonicalPhone(String value)throws IOException {
        if(value==null)throw new IOException("请输入手机号");String phone=value.trim().replace(" ","");if(phone.startsWith("+86"))phone=phone.substring(3);else if(phone.startsWith("86")&&phone.length()==13)phone=phone.substring(2);
        if(!phone.matches("1[3-9][0-9]{9}"))throw new IOException("请输入有效的中国大陆手机号（+86）");return phone;
    }
    private static String mask(String value){return value.matches("[0-9]{11}")?value.substring(0,3)+"****"+value.substring(7):"";}
    private static String suffix(String uid){return uid.length()>4?"尾号 "+uid.substring(uid.length()-4):"";}
    static boolean validID(String value){return value!=null&&value.matches("[1-9][0-9]{0,29}");}
    static boolean credentials(JSONObject session){JSONObject cookies=session.optJSONObject("cookies");return !session.optString("token","").isEmpty()||cookies!=null&&(!cookies.optString("sessionid","").isEmpty()||!cookies.optString("sessionid_ss","").isEmpty());}
    private static boolean zero(Object value){return value instanceof Number?Double.isFinite(((Number)value).doubleValue())&&((Number)value).doubleValue()==0:"0".equals(value);}
    static String businessCode(JSONObject payload){
        boolean present=false;for(JSONObject object:new JSONObject[]{payload,payload.optJSONObject("data")})if(object!=null)for(String key:new String[]{"error_code","status_code","code"})if(object.has(key)){present=true;Object value=object.opt(key);if(!zero(value)){if(value instanceof Number){double n=((Number)value).doubleValue();return Double.isFinite(n)&&n==Math.rint(n)&&Math.abs(n)<=999999999?Long.toString(((Number)value).longValue()):"invalid";}String code=value instanceof String?(String)value:"";return code.matches("-?[1-9][0-9]{0,8}")?code:"invalid";}}
        return present?"0":"";
    }
    static boolean success(JSONObject payload){
        if(!"success".equals(payload.optString("message"))||payload.optJSONObject("data")==null)return false;
        for(JSONObject object:new JSONObject[]{payload,payload.optJSONObject("data")})for(String key:new String[]{"code","status_code","error_code"})if(object.has(key)&&!zero(object.opt(key)))return false;
        return true;
    }
    static String loginUID(JSONObject payload)throws IOException {
        if(!success(payload))throw new IOException("红果未确认登录成功");JSONObject data=payload.optJSONObject("data");String text=data.optString("user_id_str","");Object raw=data.opt("user_id");String numeric=raw==null||raw==JSONObject.NULL?"":String.valueOf(raw);
        if(text.isEmpty())text=numeric;if(!validID(text)||!numeric.isEmpty()&&(!validID(numeric)||!text.equals(numeric)))throw new IOException("登录响应中的账号身份无效或不一致");return text;
    }
    private static String reason(Exception failure){
        if(failure instanceof java.net.SocketTimeoutException)return "红果账号请求超时，请稍后重试";
        if(failure instanceof InterruptedException||failure instanceof InterruptedIOException)return "登录已取消";
        if(failure instanceof OfficialApiClient.HTTPError){OfficialApiClient.HTTPError http=(OfficialApiClient.HTTPError)failure;return http.status==429?"请求较频繁，请等待后重试":"红果账号服务返回 HTTP "+http.status+"，请重试";}
        String value=failure.getMessage();if(value==null||value.length()>160||value.contains("http")||value.indexOf('\n')>=0||value.indexOf('\r')>=0)return "账号请求未完成，请重试";String lower=value.toLowerCase(java.util.Locale.ROOT);for(String privateKey:new String[]{"token","cookie","sessionid","sms_code_key","encrypt_uid","ticket"})if(lower.contains(privateKey))return "账号请求未完成，请重试";return value.replaceAll("[0-9]{4,}","***");
    }
    private static IOException rejected(JSONObject payload){JSONObject data=payload.optJSONObject("data");String value=data==null?"":data.optString("description","");if(value.isEmpty())value=payload.optString("description","");if(value.isEmpty())value="红果未确认本次操作成功";return new IOException(reason(new IOException(value))+"（"+(businessCode(payload).matches("-?[0-9]{1,9}")?businessCode(payload):"未知")+"）");}
    private static final class ExpiredMfa extends IOException {ExpiredMfa(){super("红果已结束本次短信验证，请重新获取验证码");}}
    private static IOException mfaFailure(JSONObject response){
        JSONObject data=response.optJSONObject("data");String text=data==null?"":data.optString("description","");if(text.isEmpty())text=response.optString("description","");
        return text.contains("验证已过期")||text.contains("验证过期")||text.contains("验证已失效")||text.contains("长时间未操作")?new ExpiredMfa():rejected(response);
    }
    private static JSONObject mfaDisplay(JSONObject data)throws JSONException {
        JSONArray ways=data.optJSONArray("verify_ways");if(ways!=null)for(int i=0;i<ways.length();i++){JSONObject way=ways.optJSONObject(i);if(way==null)continue;String number=way.optString("channel_mobile",""),content=way.optString("sms_content","");if(number.matches("\\+?[0-9]{5,20}")&&!content.isEmpty()&&content.length()<=256&&!content.matches("(?s).*[\\p{Cntrl}].*"))return new JSONObject().put("number",number).put("content",content);}
        return new JSONObject();
    }
    private static boolean isMfa(JSONObject payload)throws JSONException {JSONObject data=payload.optJSONObject("data");return data!=null&&!data.optString("encrypt_uid","").isEmpty()&&!data.optString("sms_code_key","").isEmpty()&&mfaDisplay(data).has("number");}
    private final class Operation {
        final long epoch;final Client client;
        Operation(long epoch,Client client){this.epoch=epoch;this.client=client;}
    }
    private Operation begin(String number,boolean sending)throws Exception {
        synchronized(lock){expireLocked();if(profile()!=null)throw new IOException("请先退出当前红果账号");if(busy)throw new IOException("账号操作处理中，请稍候");if(sending&&clock.now()<retryAt)throw new IOException("请等待倒计时结束后再获取验证码");
            if(!sending&&!phone.isEmpty()&&!phone.equals(number))throw new IOException("手机号已改变，请重新获取验证码");
            long epoch=generation.incrementAndGet();Client client=clients.create(copy(disk.getJSONObject("session")));active=client;activeThread=Thread.currentThread();busy=true;phone=number;phase=sending?"sending":"submitting";error="";challenge=null;mfaExpires=0;mfaExpiresNanos=0;otp="";return new Operation(epoch,client);}
    }
    private void live(Operation op)throws IOException {if(generation.get()!=op.epoch||active!=op.client||Thread.currentThread().isInterrupted())throw new InterruptedIOException("登录已取消");}
    private void saveAnonymous(Operation op)throws Exception {synchronized(lock){live(op);JSONObject next=copy(disk).put("session",op.client.session());store.save(next);live(op);disk=next;}}
    private void end(Operation op){synchronized(lock){if(active==op.client){active=null;activeThread=null;busy=false;}}op.client.close();notifyChanged();}
    JSONObject sendCode(String candidate)throws Exception {
        String number=canonicalPhone(candidate);Operation op=begin(number,true);boolean deliveryPossible=false;notifyChanged();
        try {op.client.registerDevice();saveAnonymous(op);synchronized(lock){live(op);retryAt=clock.now()+60000;smsState="uncertain";deliveryPossible=true;}JSONObject response=op.client.sendCode(number);live(op);saveAnonymous(op);
            synchronized(lock){live(op);JSONObject data=response.optJSONObject("data");long retry=data==null?0:data.optLong("retry_time",0);if(retry>0&&retry<=3600)retryAt=clock.now()+retry*1000;
                if(success(response)){smsState="sent";phase="code";}else{String business=businessCode(response);boolean explicit=!business.isEmpty()&&!business.equals("0")&&!business.equals("invalid");smsState=explicit?(business.equals("429")?"limited":"rejected"):"uncertain";if(explicit&&!(retry>0&&retry<=3600))retryAt=business.equals("429")?clock.now()+60000:0;phase="phone";error=reason(rejected(response));}}
        }catch(Exception failure){synchronized(lock){if(generation.get()==op.epoch){int status=failure instanceof OfficialApiClient.HTTPError?((OfficialApiClient.HTTPError)failure).status:0;long retry=failure instanceof OfficialApiClient.HTTPError?((OfficialApiClient.HTTPError)failure).retryAfter:0;
                    if(status==429){smsState="limited";retryAt=clock.now()+Math.max(1,Math.min(3600,retry>0?retry:60))*1000;}else if(status>=400&&status<500&&status!=408){smsState="rejected";retryAt=retry>0?clock.now()+Math.min(3600,retry)*1000:0;}else if(!deliveryPossible){smsState="rejected";retryAt=0;}else smsState="uncertain";
                    phase="phone";error=reason(failure);}}}
        finally{end(op);}return status();
    }
    JSONObject loginSMS(String candidate,String code)throws Exception {
        String number=canonicalPhone(candidate);if(code==null||!code.matches("[0-9]{4,8}"))throw new IOException("请输入4至8位短信验证码");Operation op=begin(number,false);notifyChanged();
        try {op.client.registerDevice();saveAnonymous(op);JSONObject response=op.client.smsLogin(number,code,"");live(op);
            if(success(response))finish(op,response);else if(isMfa(response)){saveAnonymous(op);synchronized(lock){live(op);challenge=copy(response.getJSONObject("data"));otp=code;mfaExpires=clock.now()+600000;mfaExpiresNanos=clock.nanos()+java.util.concurrent.TimeUnit.MINUTES.toNanos(10);phase="mfa";}}
            else throw rejected(response);
        }catch(Exception failure){synchronized(lock){if(generation.get()==op.epoch){error=reason(failure);phase="code";clearChallenge();}}}
        finally{end(op);}return status();
    }
    JSONObject verifyMfa()throws Exception {
        final Operation op;final JSONObject selected;final String number,code;final long expires,expiresNanos;
        synchronized(lock){expireLocked();if(busy)throw new IOException("账号操作处理中，请稍候");if(challenge==null||otp.isEmpty())throw new IOException("短信验证已结束，请重新登录");
            long epoch=generation.incrementAndGet();Client client=clients.create(copy(disk.getJSONObject("session")));active=client;activeThread=Thread.currentThread();busy=true;phase="mfa_waiting";error="";selected=copy(challenge);number=phone;code=otp;expires=mfaExpires;expiresNanos=mfaExpiresNanos;op=new Operation(epoch,client);}
        notifyChanged();long stop=Math.min(expires,clock.now()+90000);
        final long stopNanos=Math.min(expiresNanos,clock.nanos()+java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(Math.max(0,Math.min(90000,stop-clock.now()))));
        op.client.deadlineNanos(stopNanos);boolean complete=false;
        try {for(int count=0;count<31&&clock.now()<stop&&clock.nanos()<stopNanos;count++){live(op);JSONObject response=op.client.verifyMfa(selected,number);live(op);
                // A clock correction or late injected/client reply cannot extend the 90-second round.
                if(clock.nanos()>=stopNanos)break;
                if("1045".equals(businessCode(response))){long wait=Math.min(3000,Math.min(stop-clock.now(),java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(stopNanos-clock.nanos())));if(wait<=0)break;sleeper.sleep(wait);continue;}
                if(!success(response))throw mfaFailure(response);JSONObject data=response.getJSONObject("data");String ticket=data.optString("ticket","");if(ticket.isEmpty())ticket=data.optString("verify_ticket","");if(ticket.isEmpty())throw new IOException("短信验证没有返回登录票据，请重新登录");
                response=op.client.smsLogin(number,code,ticket);live(op);if(clock.nanos()>=stopNanos)break;if(!success(response))throw mfaFailure(response);finish(op,response);complete=true;break;}
            if(!complete)synchronized(lock){live(op);if(clock.now()>=expires||clock.nanos()>=expiresNanos){clearChallenge();phase="phone";error="短信验证等待已过期，请重新获取验证码";}else{phase="mfa";error="本轮短信验证等待已结束，可重试或取消";}}
        }catch(Exception failure){synchronized(lock){if(generation.get()==op.epoch){error=reason(failure);if(failure instanceof ExpiredMfa||clock.now()>=expires||clock.nanos()>=expiresNanos){clearChallenge();phase="phone";}else phase="mfa";}}}
        finally{end(op);}return status();
    }
    private void finish(Operation op,JSONObject response)throws Exception {
        String uid=loginUID(response);JSONObject session=op.client.session();if(!credentials(session))throw new IOException("登录响应缺少会话凭据，请重试");JSONObject data=response.getJSONObject("data");String name=data.optString("name",data.optString("screen_name","红果用户"));if(name.length()>80||name.matches("(?s).*[\\p{Cntrl}].*"))name="红果用户";
        JSONObject next=empty(session).put("profile",new JSONObject().put("uid",uid).put("name",name).put("maskedPhone",mask(phone)));
        synchronized(lock){live(op);store.save(next);live(op);disk=next;clearChallenge();phase="logged_in";error="";loginRequired=false;phone="";smsState="";retryAt=0;}
    }
    private void clearChallenge(){challenge=null;otp="";mfaExpires=0;mfaExpiresNanos=0;}
    private void expireLocked(){if(challenge!=null&&(clock.now()>=mfaExpires||clock.nanos()>=mfaExpiresNanos)){generation.incrementAndGet();Client client=active;if(client!=null)client.close();active=null;Thread thread=activeThread;if(thread!=null&&thread!=Thread.currentThread())thread.interrupt();activeThread=null;busy=false;clearChallenge();phase="phone";error="短信验证等待已过期，请重新获取验证码";}}
    JSONObject suspendLogin()throws JSONException {
        synchronized(lock){expireLocked();if(!busy&&active==null)return status();}
        generation.incrementAndGet();Client client=active;if(client!=null)client.close();Thread thread=activeThread;if(thread!=null&&thread!=Thread.currentThread())thread.interrupt();
        synchronized(lock){active=null;activeThread=null;busy=false;if(profile()!=null)phase="logged_in";else if(challenge!=null)phase="mfa";else if(phase.equals("submitting"))phase="code";else phase="phone";error="";}notifyChanged();return status();
    }
    JSONObject cancelLogin()throws JSONException {
        synchronized(lock){if(profile()!=null&&!busy&&challenge==null)return status();}
        generation.incrementAndGet();Client client=active;if(client!=null)client.close();Thread thread=activeThread;if(thread!=null&&thread!=Thread.currentThread())thread.interrupt();
        synchronized(lock){active=null;activeThread=null;busy=false;clearChallenge();phone="";phase=profile()!=null?"logged_in":"phone";error="";}notifyChanged();return status();
    }
    JSONObject logout()throws Exception {
        cancelLogin();synchronized(lock){generation.incrementAndGet();JSONObject session=copy(disk.getJSONObject("session"));session.put("cookies",new JSONObject());session.remove("token");JSONObject next=empty(session);store.save(next);disk=next;phase="phone";error="";loginRequired=false;smsState="";retryAt=0;}notifyChanged();return status();
    }
    void expired(long epoch,String expectedUid)throws Exception {synchronized(lock){if(!current(epoch,expectedUid))return;logout();loginRequired=true;error="红果会话已失效，本机账号记录保留，请重新登录";}notifyChanged();}
}
