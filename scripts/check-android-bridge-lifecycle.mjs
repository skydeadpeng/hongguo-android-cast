#!/usr/bin/env node
/** Execute the actual respond method across background/UI boundaries; no Android/device/network. */
import fs from 'node:fs';import os from 'node:os';import path from 'node:path';import {fileURLToPath} from 'node:url';import {spawnSync} from 'node:child_process';
const own=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');let root=own;for(let i=2;i<process.argv.length;i++){if(process.argv[i]==='--root'&&process.argv[i+1])root=path.resolve(process.argv[++i]);else throw Error('Usage --root PROJECT');}
const source=fs.readFileSync(path.join(root,'android/app/src/main/java/io/github/jackotom/hongguo/android/NativeBridge.java'),'utf8');const start=source.indexOf('    private void respond('),end=source.indexOf('    void close()',start);if(start<0||end<start)throw Error('actual method absent');
const temp=fs.mkdtempSync(path.join(os.tmpdir(),'hongguo-bridge-lifecycle-')),home=process.env.JAVA_HOME||'/Applications/Android Studio.app/Contents/jbr/Contents/Home',jar=process.env.JSON_JAR||path.join(os.homedir(),'.m2/repository/com/vaadin/external/google/android-json/0.0.20131108.vaadin1/android-json-0.0.20131108.vaadin1.jar');let exit=0;
try{const java=`package io.github.jackotom.hongguo.android;
import org.json.*;import java.util.*;
public final class BridgeLifecycleTest {
 final MainActivity activity;volatile boolean closed;BridgeLifecycleTest(MainActivity a){activity=a;}
 ${source.slice(start,end)}
 static int checks;static void check(boolean b,String m){checks++;if(!b)throw new AssertionError(m);}
 public static void main(String[]args)throws Exception{
 MainActivity a=new MainActivity();BridgeLifecycleTest old=new BridgeLifecycleTest(a);
 old.respond("old-1",new JSONObject().put("alive",true),null);check(a.sent.isEmpty(),"background responses wait for UI dispatch");a.pump();check(a.sent.size()==1&&a.sent.get(0).contains("old-1"),"live bridge dispatches actual structured response");
 old.respond("old-queued",null,"fixture");old.closed=true;BridgeLifecycleTest current=new BridgeLifecycleTest(a);a.pump();check(a.sent.size()==1,"closed queued old response never reaches replacement WebView");
 old.respond("old-after-close",null,null);a.pump();check(a.sent.size()==1,"closed worker cannot enqueue a response after replacement");
 current.respond("current",new JSONObject().put("fixture",17),null);a.pump();check(a.sent.size()==2&&a.sent.get(1).contains("current"),"new bridge still answers normally");
 a.ui=true;old.respond("old-ui",null,null);current.respond("current-ui",null,"fixture error");a.ui=false;check(a.sent.size()==3&&a.sent.get(2).contains("current-ui"),"UI-direct path checks closure and preserves active responses");
 System.out.println("BRIDGE_LIFECYCLE_OK checks="+checks+" actualRespond=true queuedOldCallbackBlocked=true networkRequests=0 deviceTests=not_run");
 }
}
final class MainActivity {boolean ui;final Deque<Runnable> queue=new ArrayDeque<>();final List<String>sent=new ArrayList<>();void runOnUiThread(Runnable r){if(ui)r.run();else queue.addLast(r);}void script(String s){runOnUiThread(()->sent.add(s));}void pump(){ui=true;try{while(!queue.isEmpty())queue.removeFirst().run();}finally{ui=false;}}}
`;
fs.writeFileSync(path.join(temp,'BridgeLifecycleTest.java'),java);for(const[exe,args]of [[home+'/bin/javac',['-encoding','UTF-8','-cp',jar,'-d',temp,path.join(temp,'BridgeLifecycleTest.java')]],[home+'/bin/java',['-cp',temp+':'+jar,'io.github.jackotom.hongguo.android.BridgeLifecycleTest']]]){const r=spawnSync(exe,args,{encoding:'utf8',timeout:60000});process.stdout.write(r.stdout||'');process.stdout.write(r.stderr||'');if(r.status!==0){exit=r.status||1;break;}}
}finally{fs.rmSync(temp,{recursive:true,force:true});}process.exitCode=exit;
