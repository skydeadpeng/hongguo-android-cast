package io.github.jackotom.hongguo.android;
import android.app.*;import android.content.*;import android.database.Cursor;import java.io.*;import java.lang.reflect.Field;
/** Uses the production exclusive barrier; no account, files or device downloads are changed. */
public final class DownloadDeleteCheckpointTest {
 static int checks;static final Object OWNER=new Object(),OTHER=new Object();
 static final class Row implements Cursor {boolean closed;public boolean moveToFirst(){throw new AssertionError("delete must allow active DownloadManager jobs");}public void close(){closed=true;}}
 interface Run{void run()throws Exception;}
 static void check(boolean value){checks++;if(!value)throw new AssertionError("check "+checks);}
 static void reject(Run action)throws Exception{checks++;try{action.run();throw new AssertionError("expected failure");}catch(IOException expected){}}
 static Context fresh()throws Exception{UpdateInstallCheckpoint.release(OWNER);PlayerActivity.live=false;LibraryStore.commits=0;OfficialLibrary.commits=0;Field field=UpdateInstallCheckpoint.class.getDeclaredField("pendingPlayerUntil");field.setAccessible(true);field.setLong(null,0);Context c=new Context();c.services.put(Context.ACTIVITY_SERVICE,new ActivityManager());DownloadManager dm=new DownloadManager();dm.cursor=new Row();c.services.put(Context.DOWNLOAD_SERVICE,dm);return c;}
 static void released()throws Exception{try(UpdateOperationGate.Lease lease=UpdateInstallCheckpoint.operation()){check(lease!=null);}}
 public static void main(String[]args)throws Exception{
  Context c=fresh();UpdateInstallCheckpoint.prepareDownloadDeletion(c,OWNER);reject(UpdateInstallCheckpoint::operation);final Context first=c;reject(()->UpdateInstallCheckpoint.prepareDownloadDeletion(first,OTHER));UpdateInstallCheckpoint.release(OTHER);reject(UpdateInstallCheckpoint::operation);check(LibraryStore.commits==0&&OfficialLibrary.commits==0);check(((DownloadManager)c.getSystemService(Context.DOWNLOAD_SERVICE)).filter==0);UpdateInstallCheckpoint.release(OWNER);released();
  c=fresh();c.services.remove(Context.DOWNLOAD_SERVICE);UpdateInstallCheckpoint.prepareDownloadDeletion(c,OWNER);UpdateInstallCheckpoint.release(OWNER);released();
  c=fresh();try(UpdateOperationGate.Lease lease=UpdateInstallCheckpoint.operation()){final Context busy=c;reject(()->UpdateInstallCheckpoint.prepareDownloadDeletion(busy,OWNER));}released();
  c=fresh();PlayerActivity.live=true;final Context player=c;reject(()->UpdateInstallCheckpoint.prepareDownloadDeletion(player,OWNER));released();
  for(String name:new String[]{DlnaCastService.class.getName(),BrowserCastService.class.getName()}){c=fresh();((ActivityManager)c.getSystemService(Context.ACTIVITY_SERVICE)).running.add(new ActivityManager.RunningServiceInfo(name));final Context casting=c;reject(()->UpdateInstallCheckpoint.prepareDownloadDeletion(casting,OWNER));released();}
  c=fresh();c.services.remove(Context.ACTIVITY_SERVICE);final Context absent=c;reject(()->UpdateInstallCheckpoint.prepareDownloadDeletion(absent,OWNER));released();
  c=fresh();((ActivityManager)c.getSystemService(Context.ACTIVITY_SERVICE)).running=null;final Context unknown=c;reject(()->UpdateInstallCheckpoint.prepareDownloadDeletion(unknown,OWNER));released();
  c=fresh();UpdateInstallCheckpoint.playerLaunching();final Context launch=c;reject(()->UpdateInstallCheckpoint.prepareDownloadDeletion(launch,OWNER));released();
  c=fresh();((ActivityManager)c.getSystemService(Context.ACTIVITY_SERVICE)).running.add(new ActivityManager.RunningServiceInfo("Unrelated"));UpdateInstallCheckpoint.prepareDownloadDeletion(c,OWNER);UpdateInstallCheckpoint.release(OWNER);released();
  fresh();System.out.println("DOWNLOAD_DELETE_CHECKPOINT_OK checks="+checks+" activeDownloadsAllowed=true playbackProtected=true accountUntouched=true");
 }
}
