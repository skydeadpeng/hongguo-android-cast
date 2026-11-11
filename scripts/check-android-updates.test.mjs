import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { updateCall, updateProgress, acceptUpdateResponse } from '../src/android/update-bridge.mjs';

test('progress is finite, clamped and integer', () => {
  assert.equal(updateProgress(null), 0); assert.equal(updateProgress({size:0,downloaded:5}), 0);
  assert.equal(updateProgress({size:100,downloaded:56.9}), 56); assert.equal(updateProgress({size:100,downloaded:200}), 100);
  assert.equal(updateProgress({size:100,downloaded:-1}), 0); assert.equal(updateProgress({size:NaN,downloaded:2}), 0);
  assert.equal(updateProgress({size:Infinity,downloaded:2}), 0);
});
test('bridge is explicit and reports native status, error and stale replies', async () => {
  await assert.rejects(updateCall('wrong'), /无效/); await assert.rejects(updateCall('status'), /安卓/);
  let request;
  globalThis.window = { AndroidUpdates:{postMessage(source){request=JSON.parse(source);}}};
  const promise = updateCall('check'); assert.equal(request.method,'check');
  acceptUpdateResponse({id:'stale',result:{state:'error'}});
  acceptUpdateResponse({id:request.id,result:{state:'checking'}}); assert.deepEqual(await promise,{state:'checking'});
  acceptUpdateResponse({id:request.id,result:{state:'error'}}); // already resolved
  const rejected = updateCall('install'); acceptUpdateResponse({id:request.id,error:'请先结束播放'}); await assert.rejects(rejected,/结束播放/);
  window.AndroidUpdates.postMessage=()=>{throw new Error('disconnected')}; await assert.rejects(updateCall('check'),/disconnected/);
  delete globalThis.window;
});
test('dedicated bridge and narrow install sharing are wired on both lifecycles', () => {
  const base = 'android/app/src/main/';
  const manifest=fs.readFileSync(base+'AndroidManifest.xml','utf8');
  assert.match(manifest,/REQUEST_INSTALL_PACKAGES/);assert.match(manifest,/android:authorities="\$\{applicationId\}\.updates"/);assert.match(manifest,/android:name="\.UpdateProvider"[^>]+android:exported="false"[^>]+android:grantUriPermissions="true"/);
  const main=fs.readFileSync(base+'java/io/github/jackotom/hongguo/android/MainActivity.java','utf8');
  assert.match(main,/addJavascriptInterface\(updates.new Bridge\(\), "AndroidUpdates"\)/);assert.match(main,/removeJavascriptInterface\("AndroidUpdates"\)/);
  for (const method of ['pageReady','resume','pause','destroy']) assert.ok(main.includes(`updates.${method}()`));
  const provider=fs.readFileSync(base+'java/io/github/jackotom/hongguo/android/UpdateProvider.java','utf8');
  assert.match(provider,/MODE_READ_ONLY/);assert.match(provider,/updates\/verified\.apk/);assert.match(provider,/!"r"\.equals\(mode\)/);assert.match(provider,/!"\/apk"\.equals\(uri.getPath\(\)\)/);
});
test('update panel exposes actions with keyboard-focusable native buttons and honest installation copy', () => {
  const ui=fs.readFileSync('src/android/UpdatePanel.jsx','utf8');
  assert.match(ui,/aria-live="polite"/); assert.match(ui,/ref=\{installButton\}/);assert.match(ui,/安装由系统确认/);assert.match(ui,/内置加速线路/);assert.match(ui,/失败后回退 GitHub/);
  for (const method of ['check','download','cancel','install']) assert.ok(ui.includes(`perform('${method}')`));
});
test('updater installation owns its gate and its single network lane stays bounded', () => {
  const source=fs.readFileSync('android/app/src/main/java/io/github/jackotom/hongguo/android/AppUpdater.java','utf8');
  assert.match(source,/new ArrayBlockingQueue<>\(1\)/); assert.match(source,/worker\.purge\(\)/);
  assert.match(source,/UpdateInstallCheckpoint\.prepare\(host.getApplicationContext\(\), this\)/);
  assert.doesNotMatch(source,/UpdateInstallCheckpoint\.release\(\)/);assert.match(source,/UpdateInstallCheckpoint\.release\(this\)/);
  const ui=fs.readFileSync('src/android/UpdatePanel.jsx','utf8');
  assert.match(ui,/active !== document.body/);assert.match(ui,/actionFocus.current = true/);
  for (const ref of ['installButton','downloadButton','checkButton','cancelButton']) assert.ok(ui.includes(`ref={${ref}}`));
});
