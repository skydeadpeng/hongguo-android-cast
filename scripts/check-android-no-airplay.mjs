import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
let root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
for(let i=2;i<process.argv.length;i++){if(process.argv[i]==='--root'&&process.argv[i+1])root=path.resolve(process.argv[++i]);else throw Error('Usage --root PROJECT');}
let checks=0,failed=0;
function check(ok,label){checks++;if(!ok){failed++;console.error('FAIL '+label);}}
const read=p=>fs.readFileSync(path.join(root,p),'utf8');
check(!fs.existsSync(path.join(root,'android/airplay-receiver')),'independent AirPlay receiver directory removed');
for(const p of ['scripts/build-airplay-receiver.sh','scripts/airplay-receiver-mirror.gradle'])check(!fs.existsSync(path.join(root,p)),p+' removed');
check(!/openAirPlayReceiver|openReceiver|隔空投送接收端/.test(read('src/android/App.jsx')),'TV UI receiver entry removed');
check(!/openAirPlayReceiver|AIRPLAY_PACKAGE|io\.github\.jackotom\.hongguo\.airplay/.test(read('android/app/src/main/java/io/github/jackotom/hongguo/android/NativeBridge.java')),'native receiver launcher removed');
check(!/hongguo\.airplay/.test(read('android/app/src/tv/AndroidManifest.xml')),'receiver package visibility removed');
for(const p of ['README.md','ANDROID.md','THIRD-PARTY-NOTICES.md','android/README.md'])check(!/android-airplay-server|airplay-receiver\/|build-airplay-receiver/.test(read(p)),p+' no longer advertises removed receiver');
check(fs.existsSync(path.join(root,'android/app/src/main/assets/receiver/receiver.js')),'existing browser cast retained');
check(fs.existsSync(path.join(root,'android/app/src/main/java/io/github/jackotom/hongguo/android/DlnaSession.java')),'existing DLNA retained');
console.log(`AIRPLAY_REMOVAL_${failed?'FAILED':'OK'} checks=${checks} failed=${failed} dlna=retained browserCast=retained`);
process.exitCode=failed?1:0;
