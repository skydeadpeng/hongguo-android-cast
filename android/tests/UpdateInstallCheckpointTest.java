package io.github.jackotom.hongguo.android;
import android.app.*;import android.content.*;import android.database.Cursor;import java.io.*;import java.lang.reflect.Field;
public final class UpdateInstallCheckpointTest {
 static int checks;static final Object OWNER=new Object(),OTHER=new Object();
 static final class Row implements Cursor {boolean active,closed;Row(boolean a){active=a;}public boolean moveToFirst(){return active;}public void close(){closed=true;}}
 static Context fresh()throws Exception{UpdateInstallCheckpoint.release(OWNER);PlayerActivity.live=false;LibraryStore.fail=false;LibraryStore.commits=0;OfficialLibrary.fail=false;OfficialLibrary.commits=0;Field f=UpdateInstallCheckpoint.class.getDeclaredField("pendingPlayerUntil");f.setAccessible(true);f.setLong(null,0);Context c=new Context();c.services.put(Context.ACTIVITY_SERVICE,new ActivityManager());DownloadManager dm=new DownloadManager();dm.cursor=new Row(false);c.services.put(Context.DOWNLOAD_SERVICE,dm);return c;}
 static void check(boolean b){checks++;if(!b)throw new AssertionError("check "+checks);}
 interface Run{void run()throws Exception;}
 static void reject(Run r)throws Exception{checks++;try{r.run();throw new AssertionError("expected failure");}catch(IOException expected){}}
 static void released()throws Exception{try(UpdateOperationGate.Lease lease=UpdateInstallCheckpoint.operation()){check(lease!=null);}}
 public static void main(String[]args)throws Exception{
  Context c=fresh();UpdateInstallCheckpoint.prepare(c,OWNER);check(LibraryStore.commits==1);check(OfficialLibrary.commits==1);reject(UpdateInstallCheckpoint::operation);final Context first=c;reject(()->UpdateInstallCheckpoint.prepare(first,OTHER));UpdateInstallCheckpoint.release(OTHER);reject(UpdateInstallCheckpoint::operation);UpdateInstallCheckpoint.release(OWNER);released();check(((DownloadManager)c.getSystemService(Context.DOWNLOAD_SERVICE)).filter==7);
  c=fresh();try(UpdateOperationGate.Lease lease=UpdateInstallCheckpoint.operation()){final Context x=c;reject(()->UpdateInstallCheckpoint.prepare(x,OWNER));check(LibraryStore.commits==0);}released();
  c=fresh();PlayerActivity.live=true;final Context player=c;reject(()->UpdateInstallCheckpoint.prepare(player,OWNER));released();
  for(String service:new String[]{DlnaCastService.class.getName(),BrowserCastService.class.getName()}){c=fresh();((ActivityManager)c.getSystemService(Context.ACTIVITY_SERVICE)).running.add(new ActivityManager.RunningServiceInfo(service));final Context x=c;reject(()->UpdateInstallCheckpoint.prepare(x,OWNER));check(LibraryStore.commits==0);released();}
  c=fresh();((ActivityManager)c.getSystemService(Context.ACTIVITY_SERVICE)).running.add(new ActivityManager.RunningServiceInfo("OtherService"));UpdateInstallCheckpoint.prepare(c,OWNER);check(LibraryStore.commits==1);UpdateInstallCheckpoint.release(OWNER);
  c=fresh();c.services.remove(Context.ACTIVITY_SERVICE);final Context noManager=c;reject(()->UpdateInstallCheckpoint.prepare(noManager,OWNER));released();
  c=fresh();((ActivityManager)c.getSystemService(Context.ACTIVITY_SERVICE)).running=null;final Context noList=c;reject(()->UpdateInstallCheckpoint.prepare(noList,OWNER));released();
  c=fresh();c.services.remove(Context.DOWNLOAD_SERVICE);final Context noDownloads=c;reject(()->UpdateInstallCheckpoint.prepare(noDownloads,OWNER));released();
  c=fresh();((DownloadManager)c.getSystemService(Context.DOWNLOAD_SERVICE)).cursor=null;final Context noCursor=c;reject(()->UpdateInstallCheckpoint.prepare(noCursor,OWNER));released();
  c=fresh();Row active=new Row(true);((DownloadManager)c.getSystemService(Context.DOWNLOAD_SERVICE)).cursor=active;final Context downloading=c;reject(()->UpdateInstallCheckpoint.prepare(downloading,OWNER));check(active.closed);released();
  c=fresh();LibraryStore.fail=true;final Context disk=c;reject(()->UpdateInstallCheckpoint.prepare(disk,OWNER));check(LibraryStore.commits==1);released();
  c=fresh();OfficialLibrary.fail=true;final Context cloudDisk=c;reject(()->UpdateInstallCheckpoint.prepare(cloudDisk,OWNER));check(OfficialLibrary.commits==1);released();
  c=fresh();UpdateInstallCheckpoint.playerLaunching();final Context launch=c;reject(()->UpdateInstallCheckpoint.prepare(launch,OWNER));released();
  fresh();System.out.println("UPDATE_INSTALL_CHECKPOINT_OK checks="+checks+" actualPrepare=true deviceUntouched=true");
 }
}
