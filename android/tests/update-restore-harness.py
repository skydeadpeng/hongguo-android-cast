#!/usr/bin/env python3
"""Bind exact production start/resume/finish bodies to deterministic file/platform boundaries.

The suite is not a device installation or signature test. Candidate policy and verifier
boundaries simulate success/failure; real UpdatePolicy/UpdateIntegrity/SDK tests run separately.
"""
from pathlib import Path
import sys
source=Path(sys.argv[1]).read_text()
def method(signature):
 start=source.index(signature);i=source.index('{',start);depth=0;quote=None;escape=False;comment=None
 while i<len(source):
  c=source[i];pair=source[i:i+2]
  if comment=='line':
   if c=='\n':comment=None
  elif comment=='block':
   if pair=='*/':comment=None;i+=1
  elif quote:
   if escape:escape=False
   elif c=='\\':escape=True
   elif c==quote:quote=None
  elif pair=='//':comment='line';i+=1
  elif pair=='/*':comment='block';i+=1
  elif c in ('"',"'"):quote=c
  elif c=='{':depth+=1
  elif c=='}':
   depth-=1
   if depth==0:return source[start:i+1]
  i+=1
 raise AssertionError('Unclosed production method '+signature)
methods='\n'.join(method(s) for s in ['private void start()','void resume()','boolean automaticChecksEnabled()','void automaticCheck()','private void finish(long operation, Runnable action)'])
template=r'''
import java.io.*;import java.nio.file.*;import java.util.*;import org.json.*;
public final class UpdateRestoreHarness implements AutoCloseable {
 static int groups,checks;
 static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
 static final class BuildConfig{static boolean DEBUG=false;}
 static final class UpdatePolicy{static final long CHECK_INTERVAL=86400000;static boolean automaticCheckDue(long now,long attempted){return attempted<=0||attempted>now||now-attempted>=CHECK_INTERVAL;}static boolean automaticCheckDue(long now,long attempted,long succeeded){long stamp=Math.max(attempted,succeeded);long interval=succeeded>0&&succeeded>=attempted?CHECK_INTERVAL:900000;return stamp<=0||stamp>now||now-stamp>=interval;}}
 final File directory,apk,partial;final FakePrefs prefs=new FakePrefs();final Host host=new Host();
 final Deque<Runnable> worker=new ArrayDeque<>(),ui=new ArrayDeque<>();
 int installedCode=36;String installedName="0.12.13";boolean started,busy,destroyed,foreground,pageReady=true;
 long epoch,downloaded=98765;String state="idle",message="";Candidate candidate,verifiedCandidate;int verifies,publishes,autoChecks,installs;
 UpdateRestoreHarness()throws Exception{directory=Files.createTempDirectory("hongguo-restore-fixture-").toFile();apk=new File(directory,"verified.apk");partial=new File(directory,"download.part");prefs.values.put("last_auto_attempt",System.currentTimeMillis());}
 public void close()throws Exception{try(java.util.stream.Stream<Path> files=Files.walk(directory.toPath())){files.sorted(Comparator.reverseOrder()).forEach(p->{try{Files.deleteIfExists(p);}catch(IOException ex){throw new UncheckedIOException(ex);}});}}
 void cache(int code,String failure)throws Exception{Files.write(apk.toPath(),new byte[]{1,2,3});apk.setLastModified(System.currentTimeMillis());prefs.values.put("manifest",new JSONObject().put("code",code).put("bytes",3).put("failure",failure).toString());prefs.values.put("waiting_permission",true);Files.write(partial.toPath(),new byte[]{0});}
 boolean pageAlive(){return true;}void publish(){publishes++;}void maybePrompt(){}void submit(long op,Runnable job){worker.addLast(job);}
 void pump(){while(!worker.isEmpty())worker.removeFirst().run();while(!ui.isEmpty())ui.removeFirst().run();}
 void check(boolean manual){check(!manual,"automatic restore check is not manual");autoChecks++;state="checking";}
 void install(){check(candidate!=null&&verifiedCandidate==candidate&&apk.isFile(),"permission return only uses verified future APK");installs++;state="installing";}
 void verify(Candidate c,File f)throws Exception{verifies++;if(c.code<=installedCode||!c.failure.isEmpty()||!f.isFile())throw new IOException("injected identity/hash/signature failure");}
 static final class Candidate{final int code;final long bytes;final String failure;Candidate(int c,long b,String f){code=c;bytes=b;failure=f;}static Candidate parse(JSONObject j,int installed,String name)throws Exception{int code=j.getInt("code");if(code<installed)throw new IOException("downgrade");return new Candidate(code,j.getLong("bytes"),j.optString("failure",""));}}
 static final class FakePrefs{final Map<String,Object>values=new HashMap<>();String getString(String key,String fallback){Object v=values.get(key);return v instanceof String?(String)v:fallback;}long getLong(String key,long fallback){Object v=values.get(key);return v instanceof Number?((Number)v).longValue():fallback;}boolean getBoolean(String key,boolean fallback){Object v=values.get(key);return v instanceof Boolean?(Boolean)v:fallback;}FakePrefs edit(){return this;}FakePrefs remove(String key){values.remove(key);return this;}FakePrefs putBoolean(String key,boolean value){values.put(key,value);return this;}void apply(){}}
 final class Host{final PackageManager pm=new PackageManager();void runOnUiThread(Runnable r){ui.addLast(r);}PackageManager getPackageManager(){return pm;}}
 static final class PackageManager{boolean permission=true;boolean canRequestPackageInstalls(){return permission;}}
 // Exact production method bodies; no test-side replacement of restore transitions.
 METHODS
 static void cleared(UpdateRestoreHarness h,String reason){check(h.candidate==null&&h.verifiedCandidate==null,reason+": invalid cached candidate must be cleared, actual="+h.state);check(!h.state.equals("ready")&&!h.state.equals("installing"),reason+": invalid restore must not announce ready");check(!h.apk.exists()&&!h.partial.exists(),reason+": cached/partial APK deleted");check(!h.prefs.values.containsKey("manifest")&&!h.prefs.values.containsKey("waiting_permission"),reason+": manifest and permission marker cleared");check(h.downloaded==0,reason+": phantom download bytes cleared");check(!h.busy&&h.installs==0,reason+": no installer handoff");}
 public static void main(String[]args)throws Exception{
  try(UpdateRestoreHarness h=new UpdateRestoreHarness()){h.cache(h.installedCode,"");h.start();h.pump();cleared(h,"already installed same version");check(h.verifies==1&&h.autoChecks==0,"same version cleanup preserves automatic check throttle");groups++;}
  for(String failure:new String[]{"corrupt-hash","wrong-signature","wrong-package","wrong-size"})try(UpdateRestoreHarness h=new UpdateRestoreHarness()){h.cache(h.installedCode+1,failure);h.start();h.pump();cleared(h,failure);check(h.verifies==1,"failed archive was verified exactly once");groups++;}
  try(UpdateRestoreHarness h=new UpdateRestoreHarness()){h.cache(h.installedCode-1,"");h.start();h.pump();cleared(h,"downgrade");check(h.verifies==0,"downgrade rejected before verifier");groups++;}
  try(UpdateRestoreHarness h=new UpdateRestoreHarness()){h.cache(h.installedCode+1,"");h.apk.setLastModified(System.currentTimeMillis()-UpdatePolicy.CHECK_INTERVAL-1000);h.start();h.pump();cleared(h,"expired archive");check(h.verifies==0,"expired file is not verified/installed");groups++;}
  try(UpdateRestoreHarness h=new UpdateRestoreHarness()){h.cache(h.installedCode+1,"");h.apk.delete();h.start();h.pump();cleared(h,"missing archive");groups++;}
  try(UpdateRestoreHarness h=new UpdateRestoreHarness()){h.cache(h.installedCode+1,"");h.prefs.values.put("manifest","{broken");h.start();h.pump();cleared(h,"malformed saved manifest");groups++;}
  try(UpdateRestoreHarness h=new UpdateRestoreHarness()){h.prefs.values.put("waiting_permission",true);Files.write(h.partial.toPath(),new byte[]{0});h.start();h.pump();cleared(h,"no manifest or archive");groups++;}
  try(UpdateRestoreHarness h=new UpdateRestoreHarness()){h.cache(h.installedCode+1,"");h.start();h.pump();check(h.state.equals("ready")&&h.candidate==h.verifiedCandidate&&h.candidate!=null,"verified newer archive remains ready");check(h.apk.isFile()&&!h.partial.exists()&&h.downloaded==3,"valid cached bytes retained and partial cleared");check(h.prefs.values.containsKey("manifest")&&h.prefs.getBoolean("waiting_permission",false),"background restore preserves marker for real foreground");check(h.verifies==1&&h.autoChecks==0,"valid restore does not issue duplicate checks");groups++;}
  try(UpdateRestoreHarness h=new UpdateRestoreHarness()){h.cache(h.installedCode+1,"");h.foreground=true;h.start();h.pump();check(h.state.equals("installing")&&h.installs==1,"valid cold permission return continues once");check(!h.prefs.getBoolean("waiting_permission",true),"real resume method consumes marker");groups++;}
  try(UpdateRestoreHarness h=new UpdateRestoreHarness()){h.cache(h.installedCode+1,"");h.foreground=true;h.host.pm.permission=false;h.start();h.pump();check(h.state.equals("ready")&&h.installs==0&&h.message.contains("权限尚未开启"),"permission denial keeps verified future APK retryable");groups++;}
  try(UpdateRestoreHarness h=new UpdateRestoreHarness()){h.prefs.values.put("last_auto_attempt",0L);h.foreground=true;h.start();h.pump();check(h.autoChecks==1&&h.state.equals("checking")&&h.candidate==null,"due auto-check runs once after genuine empty restore");groups++;}
  try(UpdateRestoreHarness h=new UpdateRestoreHarness()){h.cache(h.installedCode+1,"");h.start();h.worker.removeFirst().run();h.epoch++;h.state="newer-operation";h.pump();check(h.state.equals("newer-operation")&&h.candidate==null&&h.publishes==1,"late restore UI completion cannot replace newer epoch");groups++;}
  try(UpdateRestoreHarness h=new UpdateRestoreHarness()){h.cache(h.installedCode+1,"");h.start();h.destroyed=true;h.state="destroyed";h.pump();check(h.state.equals("destroyed")&&h.candidate==null&&h.publishes==1,"destroyed activity gets no late restored ready");groups++;}
  try(UpdateRestoreHarness h=new UpdateRestoreHarness()){BuildConfig.DEBUG=true;h.start();h.pump();check(h.state.equals("unsupported")&&h.worker.isEmpty(),"debug build does not restore/install");BuildConfig.DEBUG=false;groups++;}
  try(UpdateRestoreHarness h=new UpdateRestoreHarness()){h.installedCode=0;h.start();h.pump();check(h.state.equals("unsupported")&&h.worker.isEmpty(),"unreadable installed identity cannot restore");groups++;}
  System.out.println("UPDATE_RESTORE_OK groups="+groups+" checks="+checks+" actualStartResumeFinish=true invalidReady=false validFutureReady=true staleEpoch=blocked networkRequests=0");
 }
}
'''
Path(sys.argv[2]).write_text(template.replace('METHODS',methods))
print('UPDATE_RESTORE_BINDING actualStartResumeFinish=true source='+sys.argv[1])
