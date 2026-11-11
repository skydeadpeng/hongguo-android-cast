// Development-only smoke test against an already running, isolated Android AVD.
// Requires both debug APKs installed. No fake bridge, source, or media response.
import { execFileSync } from 'node:child_process';
import { mkdirSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import assert from 'node:assert/strict';

const sdk = process.env.ANDROID_HOME || `${process.env.HOME}/Library/Android/sdk`;
const serial = process.env.HONGGUO_DEVICE || 'emulator-5582';
const port = process.env.ADB_PORT || '5037';
const output = resolve('verification/android-port');
mkdirSync(output, { recursive: true });
const events = [];
function record(name, result) { events.push({ name, result }); console.log(name, JSON.stringify(result)); }
function adb(...args) { return execFileSync(`${sdk}/platform-tools/adb`, ['-P', port, '-s', serial, ...args], { timeout: 20000, maxBuffer: 8 * 1024 * 1024 }); }
const pause = ms => new Promise(r => setTimeout(r, ms));
async function until(fn, timeout = 30000) {
  const end = Date.now() + timeout;
  while (Date.now() < end) { const value = await fn(); if (value) return value; await pause(250); }
  throw new Error('Device condition timed out');
}
function screenshot(name) { writeFileSync(`${output}/${name}.png`, adb('exec-out', 'screencap', '-p')); }

async function attach(pkg) {
  const pid = adb('shell', 'pidof', pkg).toString().trim().split(' ')[0];
  assert.ok(pid);
  adb('forward', 'tcp:9227', `localabstract:webview_devtools_remote_${pid}`);
  const targets = await until(async () => { try { return await (await fetch('http://127.0.0.1:9227/json/list')).json(); } catch { return null; } });
  const target = targets.find(t => t.url === 'https://appassets.androidplatform.net/index.html');
  assert.ok(target);
  const socket = new WebSocket(target.webSocketDebuggerUrl);
  await new Promise((resolve, reject) => { socket.onopen = resolve; socket.onerror = reject; });
  let next = 0;
  const pending = new Map();
  socket.onmessage = ({ data }) => { const value = JSON.parse(data); if (pending.has(value.id)) { const callback = pending.get(value.id); pending.delete(value.id); callback(value); } };
  const call = (method, params) => new Promise((resolve, reject) => {
    const id = ++next;
    const timer = setTimeout(() => { pending.delete(id); reject(new Error(`CDP timeout: ${method}`)); }, 10000);
    pending.set(id, value => { clearTimeout(timer); if (value.error) reject(new Error(value.error.message)); else resolve(value.result); });
    socket.send(JSON.stringify({ id, method, params }));
  });
  return {
    async evaluate(expression) { const r = await call('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true }); if (r.exceptionDetails) throw new Error(r.exceptionDetails.text); return r.result.value; },
    close() { socket.close(); },
  };
}

let page;
try {
  adb('shell', 'am', 'force-stop', 'io.github.jackotom.hongguo.android');
  adb('shell', 'am', 'force-stop', 'io.github.jackotom.hongguo.android.tv');
  adb('shell', 'am', 'start', '-W', '-n', 'io.github.jackotom.hongguo.android/.MainActivity');
  page = await attach('io.github.jackotom.hongguo.android');
  await page.evaluate(`document.querySelector('.back-button')?.click()`);
  const count = await until(() => page.evaluate(`document.querySelectorAll('.poster').length || null`));
  await until(() => page.evaluate(`[...document.querySelectorAll('.poster img')].some(i=>i.complete&&i.naturalWidth>0)`));
  record('PHONE_REAL_CATALOG', { cards: count });
  screenshot('phone-final-home');
  await page.evaluate(`document.querySelector('.poster').click()`);
  const detail = await until(() => page.evaluate(`document.querySelector('.detail-copy h1') ? {title:document.querySelector('.detail-copy h1').textContent, publicEpisodes:document.querySelectorAll('.episode-grid button:not(:disabled)').length, allEpisodes:document.querySelectorAll('.episode-grid button').length} : null`));
  assert.ok(detail.publicEpisodes > 0 && detail.allEpisodes >= detail.publicEpisodes);
  record('PHONE_REAL_DETAIL', detail);
  await page.evaluate(`{const b=document.querySelector('.detail-copy .button');if(!b.textContent.includes('已加入追剧')) b.click()}`);
  await until(() => page.evaluate(`document.querySelector('.detail-copy .button').textContent.includes('已加入追剧')`));
  record('PHONE_FAVORITE', 'saved');
  screenshot('phone-final-detail');

  await page.evaluate(`document.querySelector('.play-actions .primary').click()`);
  await until(() => /PlayerActivity/.test(adb('shell', 'dumpsys', 'activity', 'activities').toString().split('\n').filter(l => /ResumedActivity/.test(l)).join('')));
  await pause(4000);
  screenshot('phone-native-playback');
  // Android's media controls may consume the first Back to close their overlay.
  for (let i = 0; i < 3; i++) {
    adb('shell', 'input', 'keyevent', '4');
    await pause(350);
    if (/MainActivity/.test(adb('shell', 'dumpsys', 'activity', 'activities').toString().split('\n').filter(l => /ResumedActivity/.test(l)).join(''))) break;
  }
  await until(() => /MainActivity/.test(adb('shell', 'dumpsys', 'activity', 'activities').toString().split('\n').filter(l => /ResumedActivity/.test(l)).join('')));
  const preferences = adb('shell', 'run-as', 'io.github.jackotom.hongguo.android', 'cat', 'shared_prefs/hongguo_library_v1.xml').toString();
  const historyText = preferences.match(/<string name="history">([\s\S]*?)<\/string>/)?.[1];
  assert.ok(historyText, 'Playback must write actual history');
  const history = JSON.parse(historyText.replaceAll('&quot;', '"').replaceAll('&amp;', '&').replaceAll('&lt;', '<').replaceAll('&gt;', '>').replaceAll('&apos;', "'"));
  assert.ok(history[0].positionMs > 0, 'Actual player progress must advance');
  record('PHONE_NATIVE_PLAYBACK', { index: history[0].index, positionMs: history[0].positionMs });

  await page.evaluate(`[...document.querySelectorAll('.play-actions button')].find(b=>b.textContent.includes('下载本集')).click()`);
  await until(() => page.evaluate(`!!document.querySelector('.toast') || !!document.querySelector('.operation-error')`));
  const downloadNotice = await page.evaluate(`document.querySelector('.toast')?.textContent || document.querySelector('.operation-error')?.textContent`);
  assert.match(downloadNotice, /已加入系统下载/);
  await page.evaluate(`[...document.querySelectorAll('.nav-items button')].find(b=>b.textContent==='下载').click()`);
  await until(() => page.evaluate(`document.querySelectorAll('.download-row').length > 0`));
  record('PHONE_SYSTEM_DOWNLOAD', await page.evaluate(`document.querySelector('.download-row').innerText`));
  await until(() => page.evaluate(`!!document.querySelector('.download-row .button')`), 60000);
  record('PHONE_DOWNLOAD_COMPLETE', await page.evaluate(`[...document.querySelectorAll('.download-row')].find(r=>r.querySelector('.button')).innerText`));
  screenshot('phone-download');
  await page.evaluate(`document.querySelector('.download-row .button').click()`);
  await until(() => /PlayerActivity/.test(adb('shell', 'dumpsys', 'activity', 'activities').toString().split('\n').filter(l => /ResumedActivity/.test(l)).join('')));
  await pause(2000);
  screenshot('phone-downloaded-playback');
  record('PHONE_LOCAL_PLAYBACK', 'Started native player through playDownload for completed DownloadManager file.');
  for (let i = 0; i < 3; i++) {
    adb('shell', 'input', 'keyevent', '4');
    await pause(250);
    if (/MainActivity/.test(adb('shell', 'dumpsys', 'activity', 'activities').toString().split('\n').filter(l => /ResumedActivity/.test(l)).join(''))) break;
  }
  page.close(); page = null;

  adb('shell', 'am', 'start', '-W', '-n', 'io.github.jackotom.hongguo.android.tv/io.github.jackotom.hongguo.android.MainActivity');
  page = await attach('io.github.jackotom.hongguo.android.tv');
  await until(() => page.evaluate(`document.documentElement.dataset.tv==='true' && document.querySelectorAll('.poster').length>0`));
  await until(() => page.evaluate(`[...document.querySelectorAll('.poster img')].some(i=>i.complete&&i.naturalWidth>0)`));
  const tv = await page.evaluate(`({isTv:document.documentElement.dataset.tv, width:innerWidth, height:innerHeight, cards:document.querySelectorAll('.poster').length})`);
  assert.ok(tv.width > tv.height);
  record('TV_FLAVOR_LANDSCAPE', tv);
  screenshot('tv-final-home');
  // Start from a known navigation focus; WebView focus restoration varies by OS.
  await page.evaluate(`document.querySelector('.nav-items button').focus()`);
  for (const key of ['20', '23']) adb('shell', 'input', 'keyevent', key);
  await until(() => page.evaluate(`document.querySelector('h1')?.textContent==='搜索短剧'`));
  record('TV_REAL_DPAD', { heading: await page.evaluate(`document.querySelector('h1').textContent`) });
  screenshot('tv-dpad-search');
  record('TV_SYSTEM', 'Phone AVD API32 running TV flavor; not an Android TV OS or physical TV acceptance test.');
  record('DEVICE_SMOKE_PASS', true);
} catch (error) {
  record('DEVICE_SMOKE_FAIL', error.message);
  try { screenshot('device-failure'); } catch {}
  process.exitCode = 1;
} finally {
  page?.close();
  adb('forward', '--remove', 'tcp:9227');
  writeFileSync(`${output}/device-results.json`, JSON.stringify(events, null, 2));
}
