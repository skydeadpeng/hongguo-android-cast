package io.github.jackotom.hongguo.android;

import android.content.Context;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import org.json.*;

/** Official native account + bounded independent cloud worker. Retired server preferences are never opened. */
final class AccountSync {
    private static AccountSync instance;
    static synchronized AccountSync init(Context context){if(instance==null)try{instance=new AccountSync(context.getApplicationContext());}catch(Exception failure){throw new IllegalStateException("红果账号初始化未完成",failure);}return instance;}
    static synchronized AccountSync get(){if(instance==null)throw new IllegalStateException("红果账号尚未初始化");return instance;}
    private final OfficialAccountFlow flow;
    private final OfficialLibrary library;
    private final OfficialWebAccount web;
    interface CloudClients { OfficialApiClient create(JSONObject session)throws Exception; }
    private final CloudClients cloudClients;
    private final ScheduledThreadPoolExecutor syncWorker=new ScheduledThreadPoolExecutor(1,r->{Thread t=new Thread(r,"hongguo-official-sync");t.setDaemon(true);return t;});
    private final Object syncLock=new Object(),selectionLock=new Object();
    private ScheduledFuture<?> scheduled;
    private long requestedEpoch;
    private volatile long syncGeneration;
    private volatile boolean syncing,syncRequested,libraryReady;
    private volatile String selectedUid="",selectionError="",syncError="";
    private volatile Runnable listener;
    private volatile OfficialApiClient syncClient;
    private static OfficialAccountFlow createFlow(Context context)throws Exception {
        return new OfficialAccountFlow(new OfficialSessionStore(context),new OfficialAccountFlow.Clients(){
            public JSONObject fresh()throws Exception{return OfficialApiClient.newSession();}
            public OfficialAccountFlow.Client create(JSONObject session)throws Exception {final OfficialApiClient client=new OfficialApiClient(session);return new OfficialAccountFlow.Client(){
                public void registerDevice()throws Exception{client.registerDevice();}public JSONObject session()throws Exception{return client.session();}
                public JSONObject sendCode(String phone)throws Exception{return client.sendCode(phone);}public JSONObject smsLogin(String phone,String code,String ticket)throws Exception{return client.smsLogin(phone,code,ticket);}
                public JSONObject verifyMfa(JSONObject challenge,String phone)throws Exception{return client.verifyMfa(challenge,phone);}public void close(){client.close();}public void deadlineNanos(long value){client.deadlineNanos(value);}
            };}
        },System::currentTimeMillis,Thread::sleep);
    }
    private static OfficialWebAccount createWeb(Context context)throws Exception {
        return new OfficialWebAccount(new OfficialWebSessionStore(context),new OfficialWebGateway(),System::currentTimeMillis);
    }
    private AccountSync(Context context)throws Exception {this(createFlow(context),new OfficialLibrary(context),OfficialApiClient::new,true,createWeb(context));}
    AccountSync(OfficialAccountFlow flow,OfficialLibrary library,CloudClients clients,boolean periodic)throws Exception {this(flow,library,clients,periodic,null);}
    AccountSync(OfficialAccountFlow flow,OfficialLibrary library,CloudClients clients,boolean periodic,OfficialWebAccount web)throws Exception {
        this.flow=flow;this.library=library;this.cloudClients=clients;this.web=web;
        syncWorker.setRemoveOnCancelPolicy(true);
        flow.setListener(this::notifyChanged);library.setOnDirty(()->schedule(2000));
        selectLibrary();
        LibraryStore.setHistoryFactory(()->{
            String owner=flow.uid();
            if(!libraryReady||!owner.equals(selectedUid))return unavailableHistory();
            LibraryStore.HistoryAccess captured=library.openHistory();
            // UID can change while a library handle is captured. Never substitute
            // guest/old-account access for the account that the caller observed.
            return libraryReady&&owner.equals(selectedUid)&&owner.equals(flow.uid())?captured:unavailableHistory();
        });
        if(periodic)syncWorker.scheduleWithFixedDelay(()->schedule(0),90,90,TimeUnit.SECONDS);
    }
    synchronized void setListener(Runnable next){listener=next;}
    synchronized void clearListener(Runnable expected){if(listener==expected)listener=null;}
    private void notifyChanged(){Runnable current=listener;if(current!=null)try{current.run();}catch(RuntimeException ignored){}}
    private static LibraryStore.HistoryAccess unavailableHistory(){
        return new LibraryStore.HistoryAccess(){
            public JSONArray rows()throws JSONException{throw new JSONException("红果账号本机记录尚未就绪，请重试");}
            public void record(JSONObject row)throws JSONException{throw new JSONException("红果账号本机记录尚未就绪，请重试");}
            public JSONArray favorites()throws JSONException{throw new JSONException("红果账号本机收藏尚未就绪，请重试");}
            public JSONObject seriesHistory(String sid)throws JSONException{throw new JSONException("红果账号本机进度尚未就绪，请重试");}
            public void toggleFavorite(JSONObject series)throws JSONException{throw new JSONException("红果账号本机收藏尚未就绪，请重试");}
        };
    }
    /** The library owner: the passport session when present, else the proven web session. */
    private String cloudUid(){
        String uid=flow.uid();
        if(!uid.isEmpty())return uid;
        return web!=null&&web.cloudReady()?web.uid():"";
    }
    /** Both epochs only ever increase, so their sum is a stable invalidation token. */
    private long cloudEpoch(){return flow.epoch()+(web==null?0:web.cloudGeneration()*1_000_000L);}
    private boolean webLane(){return flow.uid().isEmpty()&&web!=null&&web.cloudReady();}
    private void selectLibrary(){
        synchronized(selectionLock){
            for(int attempt=0;attempt<3;attempt++){
                String uid=cloudUid();if(libraryReady&&uid.equals(selectedUid))return;libraryReady=false;
                try{library.select(uid);if(!uid.equals(cloudUid()))continue;if(!uid.equals(selectedUid))syncError="";selectedUid=uid;selectionError="";libraryReady=true;return;}
                catch(Exception failure){selectionError="账号已保存，但本机账号记录读取未完成，请重试同步";return;}
            }
            selectionError="账号正在切换，请重新读取本机记录";
        }
    }
    String ownerStamp(){
        String uid=cloudUid();if(uid.isEmpty())return "guest";
        try{byte[] digest=java.security.MessageDigest.getInstance("SHA-256").digest(("hongguo-official-owner-v1\n"+uid).getBytes(java.nio.charset.StandardCharsets.UTF_8));StringBuilder value=new StringBuilder("official:");for(byte b:digest)value.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return value.toString();}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    static final class Selection {
        final long epoch;final String uid;
        private Selection(long epoch,String uid){this.epoch=epoch;this.uid=uid;}
    }
    Selection captureSelection()throws java.io.IOException {
        long epoch=cloudEpoch();String uid=cloudUid();
        if(!libraryReady||!uid.equals(selectedUid)||epoch!=cloudEpoch())throw new java.io.IOException("账号正在切换，请稍后重试");
        return new Selection(epoch,uid);
    }
    void requireCurrent(Selection selected)throws java.io.IOException {
        if(selected==null||!libraryReady||!selected.uid.equals(selectedUid)||selected.epoch!=cloudEpoch()||!selected.uid.equals(cloudUid()))throw new java.io.IOException("账号已切换，原操作已取消");
    }
    JSONObject getAccount()throws JSONException {
        JSONObject value=flow.status(),status=library.status();boolean ready=libraryReady&&cloudUid().equals(selectedUid);String localError=!selectionError.isEmpty()?selectionError:!ready?"账号本机记录正在切换，请稍候":"";value.put("sync",status.put("syncing",syncing||syncRequested||status.optBoolean("syncing")).put("error",!localError.isEmpty()?localError:!syncError.isEmpty()?syncError:status.optString("error","")));
        value.put("localOnly",!value.optBoolean("authenticated")&&!webLaneReady()).put("libraryReady",ready);value.put("web",webStatus());return value;
    }
    private boolean webLaneReady(){return web!=null&&web.cloudReady();}
    /** The website session is reported on its own: it is not the mobile passport session that drives the library. */
    JSONObject webStatus()throws JSONException {
        if(web==null)return new JSONObject().put("available",false);
        JSONObject value=web.status();value.put("available",true);return value;
    }
    JSONObject webQrcode()throws Exception {if(web==null)throw new java.io.IOException("红果官网扫码登录不可用");JSONObject value=web.start();notifyChanged();probeWeb();return value;}
    JSONObject webPoll()throws Exception {if(web==null)throw new java.io.IOException("红果官网扫码登录不可用");JSONObject value=web.poll();probeWeb();notifyChanged();return value;}
    JSONObject webLogout()throws Exception {if(web==null)throw new java.io.IOException("红果官网扫码登录不可用");JSONObject value=web.logout();selectLibrary();notifyChanged();return value;}
    JSONObject webCancel()throws Exception {if(web==null)throw new java.io.IOException("红果官网扫码登录不可用");JSONObject value=web.cancel();notifyChanged();return value;}
    /** Proves the web cookie on the signed cloud APIs, then re-selects the library when it passes. */
    private void probeWeb(){
        if(web==null)return;
        syncWorker.execute(()->{
            try{
                if(!web.probeCloud())return;
                selectLibrary();schedule(0);notifyChanged();
            }catch(RuntimeException ignored){ }
        });
    }
    JSONObject sendCode(JSONObject params)throws Exception{flow.sendCode(params.optString("phone",flow.phoneForLogin()));return getAccount();}
    JSONObject loginSMS(JSONObject params)throws Exception {
        flow.loginSMS(params.optString("phone",flow.phoneForLogin()),params.optString("code",""));selectLibrary();if(!cloudUid().isEmpty())schedule(0);notifyChanged();return getAccount();
    }
    JSONObject verifyMfa()throws Exception {flow.verifyMfa();selectLibrary();if(!cloudUid().isEmpty())schedule(0);notifyChanged();return getAccount();}
    JSONObject cancelLogin()throws JSONException {flow.cancelLogin();notifyChanged();return getAccount();}
    JSONObject suspendLogin()throws JSONException {flow.suspendLogin();notifyChanged();return getAccount();}
    JSONObject logout()throws Exception {cancelSync();flow.logout();selectLibrary();notifyChanged();return getAccount();}
    JSONObject importGuest()throws Exception {selectLibrary();if(cloudUid().isEmpty()||!libraryReady)throw new java.io.IOException("请先登录红果账号并读取本机记录");JSONObject result=library.importGuest();schedule(0);notifyChanged();return getAccount().put("importResult",result);}
    JSONObject syncNow()throws Exception {selectLibrary();if(cloudUid().isEmpty()||!libraryReady)throw new java.io.IOException(web!=null&&!web.uid().isEmpty()?"官网会话云端验证未完成，请稍后重试同步":"请先登录红果账号并读取本机记录");schedule(0);notifyChanged();return getAccount();}
    private void schedule(long delay){
        if(cloudUid().isEmpty()||!libraryReady)return;
        synchronized(syncLock){syncRequested=true;requestedEpoch=cloudEpoch();if(syncing)return;if(scheduled!=null){if(scheduled.getDelay(TimeUnit.MILLISECONDS)<=delay)return;scheduled.cancel(false);}scheduled=syncWorker.schedule(this::sync,delay,TimeUnit.MILLISECONDS);}
    }
    private void sync(){
        final String uid=cloudUid();final long epoch=cloudEpoch();final boolean webLane=webLane();
        final long operation;
        synchronized(syncLock){scheduled=null;syncRequested=false;if(uid.isEmpty()||!libraryReady||syncing)return;syncing=true;operation=syncGeneration;}
        final BooleanSupplier current=()->operation==syncGeneration&&epoch==cloudEpoch()&&uid.equals(cloudUid())&&libraryReady&&uid.equals(selectedUid);
        notifyChanged();OfficialApiClient client=null;boolean sessionSaveAttempted=false;
        try {
            client=webLane?web.cloudClient():cloudClients.create(flow.session());syncClient=client;
            library.sync(client,uid,current);if(current.getAsBoolean()&&!webLane){sessionSaveAttempted=true;flow.refreshedSession(client.session(),epoch,uid);}
            if(current.getAsBoolean())syncError="";
        }catch(Exception failure){if(current.getAsBoolean()){
            boolean expired=failure instanceof OfficialLibrary.AuthExpiredException||failure instanceof OfficialApiClient.HTTPError&&((OfficialApiClient.HTTPError)failure).status==401;
            if(expired){
                if(webLane){web.cloudExpired();syncError="官网会话云端验证已失效，请重新扫码";}
                else try{flow.expired(epoch,uid);}catch(Exception disk){syncError="会话失效状态保存未完成，请重试退出";}
                selectLibrary();
            }
            else syncError="云同步未完成，本机记录和待同步队列已保留";
        }}finally{
            // A later network/page failure must not discard an earlier validated token rotation.
            // Epoch and cancellation ownership prevent old sessions from reviving logout/expiry.
            if(client!=null){
                if(!sessionSaveAttempted&&!webLane&&current.getAsBoolean()&&!Thread.currentThread().isInterrupted())try{flow.refreshedSession(client.session(),epoch,uid);}
                catch(Exception failure){if(current.getAsBoolean())syncError="账号会话刷新保存未完成，请稍后重试同步";}
                client.close();
            }
            syncClient=null;boolean again;synchronized(syncLock){syncing=false;if(syncRequested&&requestedEpoch!=cloudEpoch())syncRequested=false;again=syncRequested;}if(again)schedule(2000);notifyChanged();
        }
    }
    private void cancelSync(){synchronized(syncLock){syncGeneration++;syncRequested=false;if(scheduled!=null){scheduled.cancel(true);scheduled=null;}}OfficialApiClient current=syncClient;if(current!=null)current.close();}
    void foreground(){
        selectLibrary();schedule(0);notifyChanged();
        if(web==null)return;
        syncWorker.execute(()->{
            try{web.restore();}catch(RuntimeException ignored){ }
            // A restored session is not proven until the cloud probe answers with the same id.
            if(!web.cloudReady())probeWeb();
        });
    }
    void flush(){try{flow.suspendLogin();}catch(JSONException ignored){}cancelSync();syncWorker.execute(()->{try{library.checkpoint();}catch(Exception failure){syncError="本机账号记录保存未完成，请检查存储空间";notifyChanged();}});}
    void checkpoint()throws Exception{library.checkpoint();}
    void close(){cancelSync();syncWorker.shutdownNow();try{flow.cancelLogin();}catch(JSONException ignored){}}
}
