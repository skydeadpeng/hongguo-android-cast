package io.github.jackotom.hongguo.android;

import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import org.json.*;

/** Actual login flow + account coordinator + official library, all network responses injected. */
public final class OfficialAccountCoordinatorTest {
    static int groups,checks;
    interface Checked {void run()throws Exception;}
    static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    static void waitFor(BooleanSupplier ready)throws Exception{long stop=System.nanoTime()+4_000_000_000L;while(!ready.getAsBoolean()){if(System.nanoTime()>stop)throw new AssertionError("coordinator wait expired");Thread.sleep(10);}}
    static final class Env implements AutoCloseable {
        final OfficialAccountFlowTest.Env flow=new OfficialAccountFlowTest.Env();
        final OfficialLibraryTest.Memory disk=new OfficialLibraryTest.Memory();
        final JSONArray guest=new JSONArray();final AtomicLong now=new AtomicLong(1700000000000L);
        final OfficialLibrary library=new OfficialLibrary(disk,()->guest,now::get);
        final OfficialLibraryTest.API api=new OfficialLibraryTest.API();
        final AtomicInteger created=new AtomicInteger();boolean unauthorized;
        final AccountSync account=new AccountSync(flow.flow,library,session->{created.incrementAndGet();return new OfficialApiClient(session,(path,method,query,body,gzip)->{if(unauthorized)throw new OfficialApiClient.HTTPError(401,0);return api.call(path,method,query,body,gzip);});},false);
        Env()throws Exception{}
        void login()throws Exception{flow.factory.login.add(OfficialAccountFlowTest.login(OfficialLibraryTest.UID));account.sendCode(new JSONObject().put("phone","13800000000"));account.loginSMS(new JSONObject().put("code","123456"));}
        boolean idle(){try{return !account.getAccount().getJSONObject("sync").getBoolean("syncing");}catch(Exception e){throw new RuntimeException(e);}}
        public void close(){account.close();LibraryStore.setHistoryFactory(null);}
    }
    static void group(String name,Checked test)throws Exception{test.run();groups++;System.out.println("PASS "+name);}
    public static void main(String[]args)throws Exception {
        group("login selects durable official UID then truthful asynchronous sync without guest upload",()->{
            try(Env e=new Env()){
                e.guest.put(OfficialLibraryTest.row(OfficialLibraryTest.SID,e.now.get(),4321));String before=e.guest.toString();
                check(e.created.get()==0&&e.flow.factory.sends==0,"opening account creates no cloud or SMS request");e.login();
                waitFor(()->e.idle()&&e.created.get()>0);
                JSONObject status=e.account.getAccount();check(status.getBoolean("authenticated")&&status.getBoolean("libraryReady"),"saved official profile selected");check(status.getJSONObject("sync").getLong("lastSyncAt")>0,"sync success only after real fixture protocol completes");
                check(e.library.openHistory()!=null&&e.library.openHistory().rows().length()==0,"guest rows not auto merged");check(e.guest.toString().equals(before)&&e.api.uploads.size()==0,"login uploads no guest history");
                check(!status.toString().contains("PRIVATE_SESSION_TOKEN")&&!status.toString().contains("13800000000"),"coordinator does not project private session or phone");
            }
        });
        group("explicit import and two-stage acknowledgements persist before queue clears",()->{
            try(Env e=new Env()){
                e.guest.put(OfficialLibraryTest.row(OfficialLibraryTest.SID,e.now.get(),500));e.login();waitFor(e::idle);
                JSONObject result=e.account.importGuest();check(result.getJSONObject("importResult").getInt("importedCount")==1,"explicit import applied once");waitFor(()->e.idle()&&e.api.uploads.size()>=2);
                check(e.account.getAccount().getJSONObject("sync").getInt("pendingCount")==0,"history and progress both acknowledged");
                check(e.guest.length()==1,"source guest remains");check(e.library.openHistory().rows().getJSONObject(0).getLong("positionMs")==500,"official local progress visible");
            }
        });
        group("logout cancels old pull and retained playback access cannot rewrite guest selection",()->{
            try(Env e=new Env()){
                e.login();waitFor(e::idle);LibraryStore.HistoryAccess old=e.library.openHistory();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
                e.api.onRead=()->{entered.countDown();release.await(2,TimeUnit.SECONDS);};e.account.syncNow();check(entered.await(1,TimeUnit.SECONDS),"old official pull running");e.account.logout();release.countDown();waitFor(e::idle);
                check(!e.account.getAccount().getBoolean("authenticated")&&e.library.openHistory()==null,"logout selects guest only after local session cleared");
                old.record(OfficialLibraryTest.row(OfficialLibraryTest.SID,e.now.incrementAndGet(),2222));
                check(e.disk.values.get(OfficialLibraryTest.UID)!=null&&e.guest.length()==0,"old playback stays within old official library");
                check(!OfficialAccountFlow.credentials(e.flow.store.disk.getJSONObject("session")),"logout durable session has no credentials");
            }
        });
        group("explicit cloud expiry signs out without deleting retained official library",()->{
            try(Env e=new Env()){
                e.login();waitFor(e::idle);e.library.openHistory().record(OfficialLibraryTest.row(OfficialLibraryTest.SID,e.now.get(),3333));e.unauthorized=true;e.account.syncNow();waitFor(()->{try{return !e.account.getAccount().getBoolean("authenticated");}catch(Exception x){return false;}});
                JSONObject status=e.account.getAccount();check(status.getBoolean("loginRequired"),"HTTP401 reports reauthentication");check(e.disk.values.containsKey(OfficialLibraryTest.UID),"expired session does not delete saved records/outbox");
                check(!OfficialAccountFlow.credentials(e.flow.store.disk.getJSONObject("session")),"expiry persisted without token");
            }
        });
        group("new UID before library selection never captures guest or previous-account history",()->{
            try(Env e=new Env()){
                e.flow.factory.login.add(OfficialAccountFlowTest.login(OfficialLibraryTest.UID));e.flow.flow.loginSMS("13800000000","123456");
                java.lang.reflect.Field field=LibraryStore.class.getDeclaredField("historyFactory");field.setAccessible(true);LibraryStore.HistoryFactory factory=(LibraryStore.HistoryFactory)field.get(null);LibraryStore.HistoryAccess blocked=factory.open();
                check(blocked!=null,"selection gap returns explicit failure access, not null guest fallback");
                boolean historyFailed=false,favoriteFailed=false;try{blocked.rows();}catch(JSONException expected){historyFailed=true;}try{blocked.favorites();}catch(JSONException expected){favoriteFailed=true;}
                check(historyFailed&&favoriteFailed&&!e.account.getAccount().getBoolean("libraryReady"),"both history and favorites fail honestly during account selection");
                e.account.foreground();waitFor(e::idle);check(e.account.getAccount().getBoolean("libraryReady"),"coordinator selects persisted UID");
                e.flow.flow.logout();blocked=factory.open();check(blocked!=null,"logout gap cannot capture the previous official library");
                boolean failed=false;try{blocked.toggleFavorite(OfficialLibraryTest.row(OfficialLibraryTest.SID,e.now.get(),100));}catch(JSONException expected){failed=true;}check(failed,"old account cannot be mutated through logout gap");
            }
        });
        group("queued favorite accepted under old guest/account is rejected after identity change",()->{
            try(Env e=new Env()){
                AccountSync.Selection guest=e.account.captureSelection();AtomicInteger guestWrites=new AtomicInteger();e.login();waitFor(e::idle);
                boolean rejected=false;try{e.account.requireCurrent(guest);guestWrites.incrementAndGet();}catch(java.io.IOException expected){rejected=true;}
                check(rejected&&guestWrites.get()==0&&e.library.openHistory().favorites().length()==0,"queued guest favorite does not write newly logged-in UID");
                AccountSync.Selection official=e.account.captureSelection();LibraryStore.HistoryAccess captured=e.library.openHistory();e.account.logout();rejected=false;
                try{e.account.requireCurrent(official);captured.toggleFavorite(OfficialLibraryTest.row(OfficialLibraryTest.SID,e.now.get(),100));}catch(java.io.IOException expected){rejected=true;}
                check(rejected&&e.library.openHistory()==null&&e.guest.length()==0,"queued official favorite does not write guest after logout");
                e.library.select(OfficialLibraryTest.UID);check(e.library.openHistory().favorites().length()==0,"cancelled old favorite also never changes retained account");
            }
        });
        group("nonqueued account lane cannot execute a new SMS operation after cancelling blocked old request",()->{
            OfficialAccountFlowTest.Env e=new OfficialAccountFlowTest.Env();e.factory.entered=new CountDownLatch(1);e.factory.release=new CountDownLatch(1);
            ThreadPoolExecutor lane=new ThreadPoolExecutor(1,1,30,TimeUnit.SECONDS,new SynchronousQueue<>());
            try{java.util.concurrent.Future<?> old=lane.submit(()->{try{e.flow.sendCode("13800000000");}catch(Exception ignored){}});check(e.factory.entered.await(1,TimeUnit.SECONDS),"old SMS is in flight");e.flow.cancelLogin();
                boolean rejected=false;try{lane.execute(()->{try{e.flow.sendCode("13900000000");}catch(Exception ignored){}});}catch(RejectedExecutionException expected){rejected=true;}
                e.flow.cancelLogin();e.factory.release.countDown();old.get(1,TimeUnit.SECONDS);
                check(rejected&&e.factory.sends==1,"no queued SMS revives after either cancel");check(!e.flow.status().getBoolean("authenticated"),"cancelled account remains logged out");
            }finally{e.factory.release.countDown();lane.shutdownNow();}
        });
        System.out.println("OFFICIAL_ACCOUNT_COORDINATOR_OK groups="+groups+" checks="+checks+" networkRequests=0 realSms=not_run deviceTests=not_run");
    }
}
