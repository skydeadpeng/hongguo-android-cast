package io.github.jackotom.hongguo.android;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.*;

/** Coordinator contracts with production flow/library and an in-memory transport. */
public final class OfficialCoordinatorReviewTest {
    static int checks,groups;
    static void check(boolean yes,String name){checks++;if(!yes)throw new AssertionError(name);}
    static boolean workerRunning(AccountSync account)throws Exception{Field f=AccountSync.class.getDeclaredField("syncing");f.setAccessible(true);return f.getBoolean(account);}
    static void waitFor(java.util.function.BooleanSupplier ready)throws Exception{OfficialAccountCoordinatorTest.waitFor(ready);}
    static void expiryWithQueuedDirty()throws Exception {
        try(OfficialAccountCoordinatorTest.Env e=new OfficialAccountCoordinatorTest.Env()){
            e.login();waitFor(e::idle);int prior=e.created.get();AtomicBoolean once=new AtomicBoolean();
            e.api.onRead=()->{if(once.compareAndSet(false,true)){e.library.openHistory().record(OfficialLibraryTest.row(OfficialLibraryTest.SID,e.now.get(),3456));e.unauthorized=true;}};
            e.account.syncNow();
            waitFor(()->{try{return e.created.get()>prior&&!workerRunning(e.account)&&!e.account.getAccount().getBoolean("authenticated");}catch(Exception error){throw new RuntimeException(error);}});
            JSONObject result=e.account.getAccount();
            check(result.getBoolean("loginRequired"),"explicit expiry requires login");
            check(!result.getJSONObject("sync").getBoolean("syncing"),"expiry to guest must clear queued dirty sync intent");
            check(e.disk.values.containsKey(OfficialLibraryTest.UID),"expiry preserves old UID durable history/outbox");groups++;
        }
    }
    static final class RefreshEnv implements AutoCloseable {
        final OfficialAccountFlowTest.Env f=new OfficialAccountFlowTest.Env();
        final OfficialLibraryTest.Memory disk=new OfficialLibraryTest.Memory();
        final OfficialLibrary library=new OfficialLibrary(disk,JSONArray::new,()->1700000000000L);
        final OfficialLibraryTest.API api=new OfficialLibraryTest.API();
        final AtomicReference<OfficialApiClient> current=new AtomicReference<>();
        final AtomicInteger created=new AtomicInteger();volatile boolean partialFailure,unauthorizedFailure;volatile CountDownLatch blocked,release;
        final AccountSync account=new AccountSync(f.flow,library,session->{
            created.incrementAndGet();
            OfficialApiClient client=new OfficialApiClient(session,(path,method,query,body,gzip)->{
                if(partialFailure){
                    if(path.equals("/reading/bookapi/read_history/list/v"))current.get().state.put("token","ROTATED_CURRENT_UID_SESSION");
                    else {
                        if(blocked!=null){blocked.countDown();while(release.getCount()>0)try{release.await(10,TimeUnit.MILLISECONDS);}catch(InterruptedException ignored){}}
                        if(unauthorizedFailure)throw new OfficialApiClient.HTTPError(401,0);
                        throw new IOException("synthetic later network failure");
                    }
                }
                return api.call(path,method,query,body,gzip);
            });current.set(client);return client;
        },false);
        RefreshEnv()throws Exception{}
        boolean idle(){try{return !account.getAccount().getJSONObject("sync").getBoolean("syncing");}catch(Exception error){throw new RuntimeException(error);}}
        void login()throws Exception{f.factory.login.add(OfficialAccountFlowTest.login(OfficialLibraryTest.UID));account.loginSMS(new JSONObject().put("phone","13800000000").put("code","123456"));waitFor(()->created.get()>0&&idle());}
        public void close(){account.close();LibraryStore.setHistoryFactory(null);}
    }
    static void partialRefresh()throws Exception {
        try(RefreshEnv e=new RefreshEnv()){
            e.login();int prior=e.created.get();e.partialFailure=true;e.account.syncNow();
            waitFor(()->e.created.get()>prior&&e.idle());
            check(e.account.getAccount().getBoolean("authenticated"),"transient partial pull failure leaves login intact");
            check(e.f.store.disk.getJSONObject("session").optString("token").equals("ROTATED_CURRENT_UID_SESSION"),"new verified session must persist even when a later cloud request fails");
            check(!e.account.getAccount().getJSONObject("sync").optString("error").isEmpty(),"partial sync still reports failure, not false completion");groups++;
        }
    }
    static void cancelledRefresh()throws Exception {
        try(RefreshEnv e=new RefreshEnv()){
            e.login();int prior=e.created.get();e.partialFailure=true;e.blocked=new CountDownLatch(1);e.release=new CountDownLatch(1);
            try{
                e.account.syncNow();check(e.blocked.await(2,TimeUnit.SECONDS),"partial batch reached blocked second request");
                e.account.flush();e.release.countDown();waitFor(()->e.created.get()>prior&&e.idle());
                check(e.f.store.disk.getJSONObject("session").optString("token").equals("PRIVATE_SESSION_TOKEN"),"cancelled batch never persists rotated session after cancellation");
                check(e.account.getAccount().getBoolean("authenticated"),"background cancellation preserves selected login");groups++;
            }finally{e.release.countDown();}
        }
    }
    static void switchedRefresh()throws Exception {
        try(RefreshEnv e=new RefreshEnv()){
            e.login();e.partialFailure=true;e.blocked=new CountDownLatch(1);e.release=new CountDownLatch(1);
            String other="7123456789012345679";
            try{
                e.account.syncNow();check(e.blocked.await(2,TimeUnit.SECONDS),"old UID batch reached token rotation");
                e.account.logout();e.f.factory.login.add(OfficialAccountFlowTest.login(other));
                e.account.loginSMS(new JSONObject().put("phone","13900000000").put("code","123456"));
                e.partialFailure=false;e.release.countDown();waitFor(()->e.created.get()>=3&&e.idle());
                check(e.f.flow.uid().equals(other),"new UID remains selected after old sync cleanup");
                check(e.f.store.disk.getJSONObject("session").optString("token").equals("PRIVATE_SESSION_TOKEN"),"old rotated token cannot overwrite new UID session");
                check(!e.account.getAccount().getJSONObject("sync").getBoolean("syncing"),"new UID queued sync is not lost by old epoch cleanup");groups++;
            }finally{e.release.countDown();}
        }
    }
    static void expiredRefresh()throws Exception {
        try(RefreshEnv e=new RefreshEnv()){
            e.login();int prior=e.created.get();e.partialFailure=true;e.unauthorizedFailure=true;e.account.syncNow();
            waitFor(()->{try{return e.created.get()>prior&&!workerRunning(e.account)&&!e.account.getAccount().getBoolean("authenticated");}catch(Exception error){throw new RuntimeException(error);}});
            check(e.account.getAccount().getBoolean("loginRequired"),"rotated response followed by auth expiry requests fresh login");
            check(!OfficialAccountFlow.credentials(e.f.store.disk.getJSONObject("session")),"expired batch cleanup never revives earlier rotated credentials");
            check(!e.f.store.disk.has("profile")&&e.f.flow.uid().isEmpty(),"expired rotated batch stays guest on disk and in memory");groups++;
        }
    }
    static void successfulSaveOnce()throws Exception {
        try(RefreshEnv e=new RefreshEnv()){
            e.login();int previous=e.f.store.saves,created=e.created.get();e.account.syncNow();
            waitFor(()->e.created.get()>created&&e.idle());
            check(e.f.store.saves==previous+1,"successful complete sync persists session only once");
            check(e.account.getAccount().getJSONObject("sync").optString("error").isEmpty(),"complete sync still reports success");groups++;
        }
    }
    static void failedRefreshSave()throws Exception {
        try(RefreshEnv e=new RefreshEnv()){
            e.login();int prior=e.created.get(),saves=e.f.store.saves;e.partialFailure=true;e.f.store.failProfile=true;e.account.syncNow();
            waitFor(()->e.created.get()>prior&&e.idle());
            check(e.f.store.saves==saves,"failed session commit is not counted as durable success");
            check(e.f.store.disk.getJSONObject("session").optString("token").equals("PRIVATE_SESSION_TOKEN"),"disk commit failure preserves old durable session");
            check(e.account.getAccount().getJSONObject("sync").optString("error").contains("会话刷新保存未完成"),"session commit failure reports actionable error");groups++;
        }
    }
    public static void main(String[]args)throws Exception {
        String mode=args.length==0?"all":args[0];
        if(mode.equals("all")||mode.equals("expiry"))expiryWithQueuedDirty();
        if(mode.equals("all")||mode.equals("session"))partialRefresh();
        if(mode.equals("all")){cancelledRefresh();switchedRefresh();expiredRefresh();successfulSaveOnce();failedRefreshSave();}
        if(groups==0)throw new IllegalArgumentException("unknown selection");
        System.out.println("OFFICIAL_COORDINATOR_REVIEW_OK groups="+groups+" checks="+checks+" actualCoordinator=true actualLibrary=true networkRequests=0 realSms=0");
    }
}
