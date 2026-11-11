#!/usr/bin/env node
/** Actual source, offline SOAP/SSDP fixtures, loopback HTTP and actual Activity methods; never scans LAN. */
import fs from 'node:fs';import os from 'node:os';import path from 'node:path';import {fileURLToPath} from 'node:url';import {spawnSync} from 'node:child_process';
const own=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');let root=own;
for(let i=2;i<process.argv.length;i++){if(process.argv[i]==='--root'&&process.argv[i+1])root=path.resolve(process.argv[++i]);else throw Error('Usage --root PROJECT');}
const src=path.join(root,'android/app/src/main/java/io/github/jackotom/hongguo/android');let passed=0,failed=0;
function group(name,fn){try{fn();passed++;console.log('PASS '+name);}catch(e){failed++;console.log('FAIL '+name+': '+e.message);}}
function run(exe,args){const r=spawnSync(exe,args,{cwd:root,encoding:'utf8',timeout:180000,maxBuffer:8*1024*1024});process.stdout.write(r.stdout||'');process.stdout.write(r.stderr||'');if(r.status!==0||r.error)throw Error('exit='+r.status+' '+(r.error?.message||''));}
const temporary=fs.mkdtempSync(path.join(os.tmpdir(),'hongguo-dlna-sdk-'));
try{
 group('all Android production Java SDK35 compilation',()=>{
  const java=process.env.JAVA_HOME||'/Applications/Android Studio.app/Contents/jbr/Contents/Home',sdk=process.env.ANDROID_HOME||path.join(os.homedir(),'Library/Android/sdk');
  const config=path.join(temporary,'BuildConfig.java');fs.writeFileSync(config,'package io.github.jackotom.hongguo.android;public final class BuildConfig {public static final boolean IS_TV=false,DEBUG=true;public static final String VERSION_NAME="fixture";}');
  run(path.join(java,'bin/javac'),['-encoding','UTF-8','-source','17','-target','17','-cp',path.join(sdk,'platforms/android-35/android.jar'),'-d',path.join(temporary,'classes'),config,...fs.readdirSync(src).filter(x=>x.endsWith('.java')).map(x=>path.join(src,x))]);
 });
 for(const [label,file] of [['SSDP discovery, SOAP control and checked local endpoints','run-dlna-protocol-tests.sh'],['receiver response headers and bounded discovery recovery','run-dlna-receiver-compat-tests.sh'],['real bounded HTTP Range supply and file leases','run-dlna-media-tests.sh'],['receiver-owned queue, end detection, cancellation and cache cleanup','run-dlna-session-tests.sh'],['renderer EOF compatibility and false-end guards','run-dlna-autoplay-tests.sh'],['Zeekr exact paused EOF and false-end guards','run-dlna-paused-eof-tests.sh'],['actual phone PlayerActivity cast controls and lifecycle','run-dlna-ui-tests.sh'],['session plus real byte server and real rolling cache','run-dlna-integration-tests.sh']])group(label,()=>run('bash',[path.join(own,'android/tests',file),'--source-root',root]));
 group('phone-only controls, foreground declaration and compatible release entry',()=>{
  const read=p=>fs.readFileSync(path.join(root,p),'utf8'),player=read('android/app/src/main/java/io/github/jackotom/hongguo/android/PlayerActivity.java'),phone=read('android/app/src/phone/AndroidManifest.xml'),main=read('android/app/src/main/AndroidManifest.xml'),tv=read('android/app/src/tv/AndroidManifest.xml'),service=read('android/app/src/main/java/io/github/jackotom/hongguo/android/DlnaCastService.java'),pkg=JSON.parse(read('package.json'));
  for(const text of ['BuildConfig.IS_TV ? null : button("投屏"','castCommand("select", position)','castCommand("toggle", 0)','castCommand("seek",','castService.connect(device, series, episodes, current','castService.removeListener(castListener)','castSnapshot.active','video.stopPlayback()','DlnaDiscovery.discover(castWifiAddress(), 6000','TRANSPORT_WIFI','TRANSPORT_VPN'])if(!player.includes(text))throw Error('missing actual player binding '+text);
  if(!phone.includes('android:name=".DlnaCastService" android:exported="false" android:foregroundServiceType="connectedDevice"'))throw Error('private connectedDevice declaration absent');
  for(const p of ['FOREGROUND_SERVICE_CONNECTED_DEVICE','CHANGE_WIFI_MULTICAST_STATE','WAKE_LOCK'])if(!phone.includes('android.permission.'+p))throw Error('missing phone permission '+p);
  if(!main.includes('android:usesCleartextTraffic="false"')||tv.includes('DlnaCastService'))throw Error('global network or TV scope changed');
  for(const text of ['cache.protect','cache.release','openQueueWindow','window.prune','cache.fetchForeground','START_NOT_STICKY','startForeground','stopForeground','releaseLocks'])if(!service.includes(text))throw Error('missing service/cache wiring '+text);
  if(pkg.scripts['build:android:phone:release-compatible']!=='bash scripts/build-android.sh --phone-release-compatible')throw Error('phone release entry absent');
 });
}finally{fs.rmSync(temporary,{recursive:true,force:true});}
console.log(`ANDROID_DLNA_${failed?'FAILED':'OK'} groupsPassed=${passed} groupsFailed=${failed} externalRequests=0 realDeviceTests=not_run`);process.exitCode=failed?1:0;
