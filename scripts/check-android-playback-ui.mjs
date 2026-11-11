// Self-contained synthetic UI fixture. No real website or media is requested.
// Start dependencies with: bash scripts/check-android-playback-ui.sh
import * as fs from 'node:fs';
import assert from 'node:assert/strict';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
const baseUrl = process.env.HONGGUO_UI_URL || 'http://127.0.0.1:5191/';
const cdpUrl = process.env.HONGGUO_CDP_URL || 'http://127.0.0.1:9223';
const output = resolve(process.env.HONGGUO_UI_OUTPUT || fileURLToPath(new URL('../verification/android-playback/', import.meta.url)));
fs.mkdirSync(output, { recursive: true });
function installMock() {
  window.__calls = []; window.__downloadTicks = 0;
  let library = { favorites: [], history: [] };
  const sid = '1234567890123456789', vid = '2345678901234567890';
  const card = (title = '测试短剧') => ({ series_id: sid, series_name: title, series_title: title, series_cover: '', series_intro: '这是用于验证手机和电视界面的测试剧集。', episode_cnt: 8, tags: ['都市'], category_list: [{ name: '都市' }], episode_right_text: '全8集' });
  const html = data => '<script>window._ROUTER_DATA=' + JSON.stringify({ loaderData: data }) + ';</script>';
  window.AndroidBridge = {
    postMessage(raw) {
      const m = JSON.parse(raw); window.__calls.push(m); let result; let delay = 0;
      if (m.method === 'getInfo') result = { version: '0.2.0', isTv: location.hash === '#tv', platform: 'android' };
      if (m.method === 'getPlaybackOptions') result = window.__options || { quality: 'auto', speed: 1 };
      if (m.method === 'setPlaybackOptions') { window.__options = { ...(window.__options || { quality: 'auto', speed: 1 }), ...m.params }; result = window.__options; }
      if (m.method === 'getLibrary') result = library;
      if (m.method === 'toggleFavorite') { library = { ...library, favorites: library.favorites.length ? [] : [m.params.series] }; result = library; }
      if (m.method === 'play' && window.__mockPlayError) { setTimeout(() => window.__androidResponse({ id: m.id, error: window.__mockPlayError }), delay); return; }
      if (m.method === 'play' || m.method === 'playDownload') result = { started: true };
      if (m.method === 'download') result = { id: 1 };
      if (m.method === 'getPlaybackCache') result = { files: window.__cleared ? 1 : 3, bytes: window.__cleared ? 1000000 : 3000000, keepPreviousEpisodes: 1 };
      if (m.method === 'clearPlaybackCache') { window.__cleared = true; result = { removed: 2, bytes: 2000000 }; }
      if (m.method === 'openExternal') result = { opened: true };
      if (m.method === 'getDownloads') { window.__downloadTicks++; result = [{ id: 1, title: '测试短剧', index: 1, status: window.__downloadTicks < 2 ? 'running' : 'success', downloadedBytes: window.__downloadTicks < 2 ? 500000 : 1000000, totalBytes: 1000000 }]; }
      if (m.method === 'httpGet') {
        const u = new URL(m.params.url); let body;
        if (u.pathname.startsWith('/category/')) body = html({ cat: { isSuccess: true, categoryRoute: { contentType: u.pathname.split('/').pop() }, recommendList: Array.from({ length: 6 }, (_, i) => ({ ...card('界面测试短剧 ' + (i + 1)), series_id: String(BigInt(sid) + BigInt(i)) })), pagination: { pageNum: Number(u.searchParams.get('page')) || 1, totalPages: 2, total: 12 } } });
        else if (u.pathname.startsWith('/search/')) { const q = decodeURIComponent(u.pathname.split('/').pop()); delay = q === 'old' ? 400 : 20; body = html({ 'search_(keyword)/page': { isSuccess: true, searchList: [{ video_data: card(q) }], total: 1 } }); }
        else if (u.pathname.split('/').length === 3) body = html({ 'player_(series_id)/page': { series_id: sid, seriesDetail: { ...card(), vid_list: [vid, '3456789012345678901', '4567890123456789012', '5678901234567890123', '6789012345678901234', '7890123456789012345', '8901234567890123456', '9012345678901234567'], accessible_episode_cnt: 3 } } });
        else { const requested = u.pathname.split('/').pop(); body = html({ 'player_(series_id)/(vid)/page': { isSuccess: true, series_id: sid, vid: requested, seriesDetail: { series_id: sid }, publicVideoSeo: { available: requested !== '9012345678901234567', seriesId: sid, itemId: requested } } }) + '<script type="application/ld+json">' + JSON.stringify({ '@type': 'VideoObject', url: 'https://hongguoduanju.com/player/' + sid + '/' + requested, contentUrl: 'https://media.example.net/test.mp4' }) + '</script>'; }
        result = { status: u.pathname.endsWith('/8901234567890123456') ? 404 : 200, body, url: m.params.url };
      }
      setTimeout(() => window.__androidResponse({ id: m.id, result }), delay);
    },
  };
}

(async () => {
  const target = await fetch(`${cdpUrl}/json/new?about:blank`, { method: 'PUT' }).then(r => r.json());
  const ws = new WebSocket(target.webSocketDebuggerUrl); await new Promise((resolve, reject) => { ws.addEventListener('open', resolve, { once: true }); ws.addEventListener('error', reject, { once: true }); });
  let seq = 0; const requests = new Map();
  ws.addEventListener('message', ({ data }) => { const m = JSON.parse(data); if (m.id) { const p = requests.get(m.id); requests.delete(m.id); m.error ? p.reject(m.error) : p.resolve(m.result); } });
  const call = (method, params = {}) => new Promise((resolve, reject) => { const id = ++seq; requests.set(id, { resolve, reject }); ws.send(JSON.stringify({ id, method, params })); });
  const evaluate = async expression => { const r = await call('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true }); if (r.exceptionDetails) throw Error(r.exceptionDetails.exception?.description || r.exceptionDetails.text); return r.result.value; };
  const sleep = ms => new Promise(r => setTimeout(r, ms));
  const wait = async predicate => { for (let i = 0; i < 80; i++) { if (await evaluate(predicate)) return; await sleep(75); } throw Error('WAIT_FAILED ' + predicate); };
  const click = async text => evaluate(`(()=>{const b=[...document.querySelectorAll('button')].find(b=>b.textContent.trim()===${JSON.stringify(text)});if(!b)throw Error('button missing: '+${JSON.stringify(text)});b.click();return true})()`);
  const screenshot = async path => fs.writeFileSync(path, Buffer.from((await call('Page.captureScreenshot', { format: 'png' })).data, 'base64'));
  try {
    await call('Page.enable'); await call('Page.addScriptToEvaluateOnNewDocument', { source: `(${installMock.toString()})();` });
    await call('Emulation.setDeviceMetricsOverride', { width: 390, height: 844, deviceScaleFactor: 1, mobile: true });
    await call('Page.navigate', { url: baseUrl }); await wait(`document.querySelectorAll('.poster').length===6`);
    await evaluate(`localStorage.clear();document.querySelector('.poster').click()`); await wait(`document.querySelectorAll('.episode-grid button').length===8`);
    if (process.argv.includes('--baseline')) {
      assert.equal(await evaluate(`document.querySelectorAll('.episode-grid button:disabled').length`), 5);
      assert.equal(await evaluate(`document.querySelectorAll('[role="switch"]').length`), 0);
      console.log('BASELINE_UI 8 episodes, 5 disabled by count, no autoplay/cache controls');
      return;
    }
    assert.equal(await evaluate(`document.querySelectorAll('.episode-grid button:disabled').length`), 0);
    assert.equal(await evaluate(`document.querySelectorAll('.episode-status').length`), 5);
    assert.equal(await evaluate(`document.querySelector('.episode-note').textContent.includes('点击时核验')`), true);
    assert.equal(await evaluate(`document.querySelectorAll('[role="switch"][aria-checked="true"]').length`), 2);
    await evaluate(`document.querySelectorAll('.episode-grid button')[3].click()`); await wait(`window.__calls.some(x=>x.method==='play')`);
    let play = await evaluate(`window.__calls.filter(x=>x.method==='play').at(-1).params`);
    assert.equal(play.episode.index, 4); assert.equal(play.episodes.length, 8); assert.equal(play.autoplay, true); assert.equal(play.cleanupPlaybackCache, true); assert.equal(play.keepPreviousEpisodes, 1); assert.equal(play.series.episodes, undefined); assert.equal(play.episode.webAvailable, false);
    assert.equal(await evaluate(`document.querySelectorAll('.episode-grid button:disabled').length`), 0);
    await click('下载本集'); await wait(`window.__calls.some(x=>x.method==='download')`);
    assert.equal(await evaluate(`window.__calls.find(x=>x.method==='download').params.episode.index`), 4);
    await evaluate(`window.__mockPlayError='MOCK_PLAY_HTTP_404'`);
    await evaluate(`document.querySelectorAll('.episode-grid button')[6].click()`); await wait(`document.querySelector('.operation-error')!==null`);
    assert.match(await evaluate(`document.querySelector('.operation-error').textContent`), /MOCK_PLAY_HTTP_404/);
    console.log('PLAY_FAIL_UI_OK native failure reason stays explicit with a retry entry');
    const plays = await evaluate(`window.__calls.filter(x=>x.method==='play').length`);
    await evaluate(`document.querySelectorAll('.episode-grid button')[6].click();document.querySelectorAll('.episode-grid button')[6].click()`); await wait(`window.__calls.filter(x=>x.method==='play').length===${plays + 1}`);
    assert.equal(await evaluate(`document.querySelectorAll('.episode-grid button:disabled').length`), 0);
    await click('重试播放'); await wait(`document.querySelector('.operation-error')===null&&window.__calls.filter(x=>x.method==='play').length===${plays + 2}`);
    await evaluate(`window.__mockPlayError=''`);
    await evaluate(`document.querySelectorAll('[role="switch"]').forEach(b=>b.click())`); await evaluate(`document.querySelectorAll('.episode-grid button')[4].click()`); await wait(`window.__calls.filter(x=>x.method==='play').length===${plays + 3}`);
    play = await evaluate(`window.__calls.filter(x=>x.method==='play').at(-1).params`); assert.equal(play.autoplay, false); assert.equal(play.cleanupPlaybackCache, false);
    console.log('DETAIL_UI_OK all 8 selectable, 5 pending verification, episode 4 playback/download, explicit native failure with retry, compact playlist, default/persisted preferences');
    await screenshot(resolve(output, 'ui-mock-detail-phone.png'));
    await evaluate(`document.querySelectorAll('[role="switch"]')[1].scrollIntoView({block:'nearest'});`);
    assert.equal(await evaluate(`document.querySelectorAll('[role="switch"]')[1].getBoundingClientRect().bottom<=document.querySelector('.play-actions').getBoundingClientRect().top`), true);
    await screenshot(resolve(output, 'ui-mock-settings-phone.png'));
    await evaluate(`window.__androidBack()`); await wait(`!document.querySelector('.detail-page')`); await click('关于'); await wait(`document.querySelector('.cache-status')?.textContent.includes('3')`);
    await click('清理播放缓存'); await wait(`document.querySelector('.cache-status')?.textContent.includes('1 个文件')`);
    assert.equal(await evaluate(`window.__calls.filter(x=>x.method==='clearPlaybackCache').length`), 1);
    assert.equal(await evaluate(`document.querySelector('.cache-note').textContent.includes('手动下载')`), true);
    await screenshot(resolve(output, 'ui-mock-cache-phone.png'));
    console.log('CACHE_UI_OK 3 files -> clear removes 2 -> refreshed 1 active protected file, manual downloads retained');
    await click('下载'); await wait(`document.querySelector('.download-row .button')!==null`); await click('播放'); await wait(`window.__calls.some(x=>x.method==='playDownload')`);
    const offline = await evaluate(`window.__calls.find(x=>x.method==='playDownload').params`); assert.equal(offline.autoplay, false); assert.equal(offline.cleanupPlaybackCache, false);
    await call('Emulation.setDeviceMetricsOverride', { width: 1280, height: 720, deviceScaleFactor: 1, mobile: false }); await call('Page.navigate', { url: `${baseUrl.replace(/\/$/, '')}/?tv=1#tv` }); await wait(`document.documentElement.dataset.tv==='true'&&document.querySelectorAll('.poster').length===6`);
    await evaluate(`document.querySelector('.poster').click()`); await wait(`document.querySelectorAll('[role="switch"]').length===2`);
    assert.equal(await evaluate(`document.querySelectorAll('[role="switch"][aria-checked="false"]').length`), 2);
    await evaluate(`document.querySelector('[role="switch"]').focus();document.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',bubbles:true}))`); await wait(`document.querySelector('[role="switch"]').getAttribute('aria-checked')==='true'`);
    assert.equal(await evaluate(`document.documentElement.scrollWidth<=innerWidth`), true);
    await evaluate(`document.querySelector('[role="switch"]').scrollIntoView({block:'nearest'});`);
    assert.equal(await evaluate(`document.querySelector('[role="switch"]').getBoundingClientRect().bottom<=document.querySelector('.play-actions').getBoundingClientRect().top`), true);
    await evaluate(`document.querySelectorAll('[role="switch"]')[1].scrollIntoView({block:'nearest'});`);
    await screenshot(resolve(output, 'ui-mock-detail-tv.png')); console.log('TV_UI_OK persisted controls focusable/Enter toggles, no horizontal overflow, offline play respects preferences');
    console.log('MODIFIED_UI all playback options and cache controls passed');
    console.log('MOCK_UI_SCREENSHOTS ' + output);
  } finally { await call('Page.close').catch(()=>{}); ws.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
