#!/usr/bin/env node
/** Compiles actual source/bridge-case methods with offline Android/service doubles. */
import fs from 'node:fs';import os from 'node:os';import path from 'node:path';import {fileURLToPath} from 'node:url';import {spawnSync} from 'node:child_process';
const own=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');let root=own;
for(let i=2;i<process.argv.length;i++){if(process.argv[i]==='--root'&&process.argv[i+1])root=path.resolve(process.argv[++i]);else throw Error('Usage --root PROJECT');}
const java=path.join(root,'android/app/src/main/java/io/github/jackotom/hongguo/android'),tmp=fs.mkdtempSync(path.join(os.tmpdir(),'hongguo-adaptive-contract-'));
const home=process.env.JAVA_HOME||'/Applications/Android Studio.app/Contents/jbr/Contents/Home',jar=process.env.JSON_JAR||path.join(os.homedir(),'.m2/repository/com/vaadin/external/google/android-json/0.0.20131108.vaadin1/android-json-0.0.20131108.vaadin1.jar');let failed=0;
function run(label,files,klass){const classes=path.join(tmp,klass);fs.mkdirSync(classes);for(const [exe,args]of [[home+'/bin/javac',['-encoding','UTF-8','-cp',jar,'-d',classes,...files]],[home+'/bin/java',['-cp',classes+':'+jar,'io.github.jackotom.hongguo.android.'+klass]]]){const r=spawnSync(exe,args,{encoding:'utf8',timeout:60000});process.stdout.write(r.stdout||'');process.stdout.write(r.stderr||'');if(r.status!==0){failed++;console.log(`FAIL ${label} exit=${r.status}`);return;}}console.log('PASS '+label);}
try{
 const runner=fs.readFileSync(path.join(own,'android/tests/run-http403-tests.sh'),'utf8'),stubs=runner.split("<<'STUB'\n")[1].split('\nSTUB')[0];fs.writeFileSync(path.join(tmp,'Stubs.java'),stubs);
 run('actual PlaybackSources fallback classification and typed resolver callback',[path.join(tmp,'Stubs.java'),path.join(java,'MediaQuality.java'),path.join(java,'PlaybackSources.java'),path.join(own,'android/tests/AdaptiveContractTest.java')],'AdaptiveContractTest');
 const bridge=fs.readFileSync(path.join(java,'NativeBridge.java'),'utf8'),start=bridge.indexOf('case "play": {'),end=bridge.indexOf('case "download": {',start);if(start<0||end<start)throw Error('actual bridge play case missing');
 const template=fs.readFileSync(path.join(own,'android/tests/AdaptiveBridgeTest.java'),'utf8');fs.writeFileSync(path.join(tmp,'AdaptiveBridgeTest.java'),template.replace('/* ACTUAL_PLAY_CASE */',bridge.slice(start,end)));
 run('actual NativeBridge play dispatcher modern/legacy validation',[path.join(tmp,'AdaptiveBridgeTest.java'),path.join(java,'MediaQuality.java'),...(fs.existsSync(path.join(java,'PlaybackPlaylist.java'))?[path.join(java,'PlaybackPlaylist.java')]:[])],'AdaptiveBridgeTest');
}catch(e){failed++;console.log('FAIL adaptive contract: '+e.message);}finally{fs.rmSync(tmp,{recursive:true,force:true});}
console.log(`ADAPTIVE_CONTRACT_${failed?'FAILED':'OK'} groupsPassed=${2-failed} groupsFailed=${failed} networkRequests=0 deviceTests=not_run`);process.exitCode=failed?1:0;
