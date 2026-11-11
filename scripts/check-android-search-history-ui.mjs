// Actual React App + mocked AndroidBridge and mocked source HTML. NOT device QA.
// The isolated runner owns only its own Chrome profile/Vite ports; no adb is used.
import fs from 'node:fs';
import assert from 'node:assert/strict';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const baseUrl = process.env.HONGGUO_SEARCH_UI_URL || 'http://127.0.0.1:5193/';
const cdpUrl = process.env.HONGGUO_SEARCH_CDP_URL || 'http://127.0.0.1:9225';
const output = resolve(process.env.HONGGUO_SEARCH_UI_OUTPUT || fileURLToPath(new URL('../verification/android-search-history/', import.meta.url)));
fs.mkdirSync(output, { recursive: true });
const evidence = { kind: 'MOCK: real local React App, source HTML and native storage RPC simulated', phone: { width: 390, height: 844 }, tv: { width: 1280, height: 720 }, deviceTests: 'not_run', tests: [], screenshots: [] };

function installMock() {
  const storageKey = '__hongguo_search_history_mock_only';
  const historyMethods = new Set(['getSearchHistory', 'addSearchHistory', 'removeSearchHistory', 'clearSearchHistory']);
  const stored = () => JSON.parse(localStorage.getItem(storageKey) || '[]');
  const store = values => localStorage.setItem(storageKey, JSON.stringify(values));
  const normalize = raw => {
    if (typeof raw !== 'string' || !raw.trim() || raw.trim().length > 100) throw Error('MOCK_HISTORY_INVALID_KEYWORD');
    return raw.trim();
  };
  window.__searchFixture = { calls: [], events: [], activeHistory: 0, maxActiveHistory: 0, delayNextGet: 0, delayNextAdd: 0, delayNextRemove: 0, delayNextClear: 0, failNextGet: false, failNextAdd: false, failNextRemove: false, failNextClear: false, failSourceKeywords: [], sourceDelay: 15, storageKey, read: stored, seed: store };
  const f = window.__searchFixture;
  const card = title => ({ series_id: '1234567890123456789', series_name: title, series_title: title, series_cover: '', series_intro: 'MOCK source result for UI verification.', episode_cnt: 8, category_list: [{ name: 'MOCK' }], tags: ['MOCK'], episode_right_text: '全8集' });
  const html = loaders => '<script>window._ROUTER_DATA=' + JSON.stringify({ loaderData: loaders }) + ';</script>';
  window.AndroidBridge = {
    postMessage(raw) {
      const message = JSON.parse(raw), { id, method, params } = message;
      f.calls.push(message); f.events.push({ phase: 'request', method, keyword: params.keyword, id });
      let result, error, delay = 5, commit;
      try {
        if (historyMethods.has(method)) {
          f.activeHistory++; f.maxActiveHistory = Math.max(f.maxActiveHistory, f.activeHistory);
          const name = { getSearchHistory: 'Get', addSearchHistory: 'Add', removeSearchHistory: 'Remove', clearSearchHistory: 'Clear' }[method];
          delay = Number(f['delayNext' + name]) || 15; f['delayNext' + name] = 0;
          const fail = !!f['failNext' + name]; f['failNext' + name] = false;
          if (fail) error = 'MOCK_STORAGE_' + name.toUpperCase() + '_FAILED';
          else {
            const before = stored();
            if (method === 'getSearchHistory') result = { keywords: before };
            if (method === 'addSearchHistory') { const keyword = normalize(params.keyword); const keywords = [keyword, ...before.filter(item => item !== keyword)].slice(0, 20); result = { keywords }; commit = () => store(keywords); }
            if (method === 'removeSearchHistory') { const keyword = normalize(params.keyword); const keywords = before.filter(item => item !== keyword); result = { keywords }; commit = () => store(keywords); }
            if (method === 'clearSearchHistory') { result = { keywords: [] }; commit = () => store([]); }
          }
        } else if (method === 'getInfo') result = { version: 'MOCK-search-history', platform: 'android', isTv: new URL(location.href).searchParams.get('tv') === '1' };
        else if (method === 'getLibrary') result = { favorites: [], history: [] };
        else if (method === 'getDownloads') result = [];
        else if (method === 'getPlaybackOptions') result = { quality: 'auto', speed: 1 };
        else if (method === 'searchQuery') {
          // Native cursor search replaced the old web /search/ route in 0.11.6.
          delay = f.sourceDelay;
          if (f.failSourceKeywords.includes(params.keyword)) error = '搜索接口请求失败（HTTP 503）';
          else result = { code: 0, search_tabs: [{ tab_type: 11, has_more: false, next_offset: 0, search_id: 'mock', passback: '', data: [{ video_data: [{ series_id: '2222222222222222222', title: params.keyword, video_detail: { series_title: params.keyword, series_intro: 'MOCK', episode_cnt: 8 } }] }] }] };
        }
        else if (method === 'httpGet') {
          const url = new URL(params.url);
          if (url.pathname.startsWith('/category/')) result = { status: 200, url: params.url, body: html({ catalog: { isSuccess: true, categoryRoute: { contentType: url.pathname.split('/').pop() }, recommendList: [card('MOCK 首页')], pagination: { pageNum: 1, totalPages: 1, total: 1 } } }) };
          else if (url.pathname.startsWith('/search/')) {
            const keyword = decodeURIComponent(url.pathname.split('/').pop()); delay = f.sourceDelay;
            result = { status: f.failSourceKeywords.includes(keyword) ? 503 : 200, url: params.url, body: html({ 'search_(keyword)/page': { isSuccess: true, searchList: [{ video_data: card(keyword) }], total: 1 } }) };
          } else error = 'MOCK_UNEXPECTED_SOURCE_ROUTE';
        } else error = 'MOCK_UNEXPECTED_RPC_' + method;
      } catch (cause) { error = cause.message; }
      setTimeout(() => {
        try { if (!error) commit?.(); } catch (cause) { error = cause.message; }
        if (historyMethods.has(method)) f.activeHistory--;
        f.events.push({ phase: 'response', method, keyword: params.keyword, id, error: error || null, ...(result?.keywords ? { keywords: result.keywords } : {}) });
        window.__androidResponse({ id, ...(error ? { error } : { result }) });
      }, delay);
    },
  };
}

const target = await fetch(`${cdpUrl}/json/new?about:blank`, { method: 'PUT' }).then(response => response.json());
const ws = new WebSocket(target.webSocketDebuggerUrl);
await new Promise((resolve, reject) => { ws.addEventListener('open', resolve, { once: true }); ws.addEventListener('error', reject, { once: true }); });
let sequence = 0;
const requests = new Map();
const pageErrors = [];
ws.addEventListener('message', ({ data }) => {
  const message = JSON.parse(data);
  if (message.method === 'Runtime.exceptionThrown') pageErrors.push(message.params.exceptionDetails.exception?.description || message.params.exceptionDetails.text);
  if (message.id) { const request = requests.get(message.id); requests.delete(message.id); if (request) message.error ? request.reject(Error(JSON.stringify(message.error))) : request.resolve(message.result); }
});
const call = (method, params = {}) => new Promise((resolve, reject) => { const id = ++sequence; requests.set(id, { resolve, reject }); ws.send(JSON.stringify({ id, method, params })); });
const evaluate = async expression => {
  const result = await call('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true });
  if (result.exceptionDetails) throw Error(result.exceptionDetails.exception?.description || result.exceptionDetails.text);
  return result.result.value;
};
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const wait = async (predicate, label = predicate) => { for (let count = 0; count < 120; count++) { if (await evaluate(predicate)) return; await sleep(40); } throw Error('WAIT_FAILED ' + label); };
const js = value => JSON.stringify(value);
const history = () => evaluate(`[...document.querySelectorAll('.history-keyword')].map(button=>button.textContent)`);
const historyIdle = () => wait(`document.querySelector('.search-history')?.getAttribute('aria-busy')==='false'`, 'history idle');
const configure = values => evaluate(`Object.assign(window.__searchFixture,${js(values)})`);
const countMethod = method => evaluate(`window.__searchFixture.calls.filter(call=>call.method===${js(method)}).length`);
const clickText = (selector, text) => evaluate(`(()=>{const button=[...document.querySelectorAll(${js(selector)})].find(button=>button.textContent.trim()===${js(text)});if(!button||button.disabled)throw Error('missing enabled button '+${js(text)});button.click();return true})()`);
const nav = text => clickText('.app-nav button', text);
const input = async value => { await evaluate(`(()=>{const input=document.querySelector('#search-keyword');Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set.call(input,${js(value)});input.dispatchEvent(new Event('input',{bubbles:true}));return true})()`); await wait(`document.querySelector('#search-keyword').value===${js(value)}`); };
const submit = async value => { await input(value); await evaluate(`document.querySelector('.search-form').dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}))`); };
const result = keyword => wait(`document.querySelector('.poster strong')?.textContent===${js(keyword)}`, 'source result ' + keyword);
const pass = (name, details) => { evidence.tests.push({ name, result: 'PASS', details }); console.log('PASS MOCK_UI ' + name + ' ' + details); };
const screenshot = async name => {
  await evaluate(`document.querySelector('.main-content').scrollTop=0`);
  await sleep(80);
  const path = resolve(output, name);
  fs.writeFileSync(path, Buffer.from((await call('Page.captureScreenshot', { format: 'png' })).data, 'base64'));
  evidence.screenshots.push(path);
};
const assertLayout = async () => {
  assert.equal(await evaluate(`document.querySelector('button button')===null`), true, 'buttons must not nest');
  assert.equal(await evaluate(`document.documentElement.scrollWidth<=innerWidth&&document.querySelector('.main-content').scrollWidth<=document.querySelector('.main-content').clientWidth`), true, 'horizontal overflow');
};
const openSearch = async () => { await nav('搜索'); await wait(`document.querySelector('.search-form input#search-keyword')!==null`); await historyIdle(); };
const reload = async tv => { await call('Page.navigate', { url: baseUrl + (tv ? '?tv=1' : '') }); await wait(`document.querySelector('.poster')!==null&&document.documentElement.dataset.tv===${js(String(tv))}`); await openSearch(); };
const key = async name => { const code = { ArrowLeft: 37, ArrowRight: 39, ArrowDown: 40, ArrowUp: 38, Enter: 13 }[name]; await call('Input.dispatchKeyEvent', { type: 'keyDown', key: name, code: name, windowsVirtualKeyCode: code, nativeVirtualKeyCode: code }); await call('Input.dispatchKeyEvent', { type: 'keyUp', key: name, code: name, windowsVirtualKeyCode: code, nativeVirtualKeyCode: code }); };

try {
  console.log('MOCK_UI_CONTEXT realReact=true sourceHttpGet=mock nativeHistoryRPC=mock localStorageCommit=mock deviceTests=not_run adb=not_used emulator=not_used');
  await call('Page.enable'); await call('Runtime.enable');
  await call('Page.addScriptToEvaluateOnNewDocument', { source: `(${installMock.toString()})();` });
  await call('Emulation.setDeviceMetricsOverride', { width: 390, height: 844, deviceScaleFactor: 1, mobile: true });
  await reload(false);
  assert.deepEqual(await history(), []);
  const initialCalls = await countMethod('addSearchHistory');
  const initialSearchCalls = await evaluate(`window.__searchFixture.calls.filter(call=>call.method==='httpGet'&&call.params.url.includes('/search/')).length`);
  await input('只输入不提交'); await sleep(80);
  assert.equal(await countMethod('addSearchHistory'), initialCalls);
  assert.equal(await evaluate(`window.__searchFixture.calls.filter(call=>call.method==='httpGet'&&call.params.url.includes('/search/')).length`), initialSearchCalls);
  await submit('   '); await historyIdle();
  assert.equal(await countMethod('addSearchHistory'), initialCalls);
  await submit('x'.repeat(101)); await historyIdle();
  assert.equal(await countMethod('addSearchHistory'), initialCalls);
  pass('input_only_and_invalid', 'typing/blank/101 chars cause no history write or source search');

  await submit('  京夜诱温  '); await historyIdle(); await result('京夜诱温');
  assert.deepEqual(await history(), ['京夜诱温']);
  assert.equal(await evaluate(`document.querySelector('#search-keyword').value`), '京夜诱温');
  const normalized = await evaluate(`window.__searchFixture.calls.filter(call=>call.method==='addSearchHistory').at(-1).params`);
  assert.deepEqual(normalized, { keyword: '京夜诱温' });
  await submit('首辅娇娘'); await historyIdle();
  await submit('京夜诱温'); await historyIdle();
  assert.deepEqual(await history(), ['京夜诱温', '首辅娇娘']);
  pass('trim_and_dedup', 'submit sends {keyword}; exact duplicate moves to front');

  for (const keyword of ['A', 'a', '甲 乙', '甲  乙']) { await submit(keyword); await historyIdle(); }
  assert.deepEqual((await history()).slice(0, 4), ['甲  乙', '甲 乙', 'a', 'A']);
  await clickText('.history-keyword', '首辅娇娘'); await historyIdle(); await result('首辅娇娘');
  assert.equal(await evaluate(`document.querySelector('#search-keyword').value`), '首辅娇娘');
  assert.equal((await history())[0], '首辅娇娘');
  pass('exact_identity_and_tag_search', 'case/internal spaces remain distinct; tag fills input and searches via shared path');

  await evaluate(`document.querySelector('button[aria-label="删除搜索历史：a"]').click()`); await historyIdle();
  assert.equal((await history()).includes('a'), false); assert.equal((await history()).includes('A'), true);
  assert.deepEqual(await evaluate(`window.__searchFixture.calls.filter(call=>call.method==='removeSearchHistory').at(-1).params`), { keyword: 'a' });
  pass('single_remove', 'independent delete removes exact keyword only');

  const beforeClear = await history(); const clears = await countMethod('clearSearchHistory');
  await clickText('.search-history>header .history-clear', '清空');
  await wait(`document.querySelector('[role="group"][aria-label="确认清空搜索历史"]')!==null`);
  assert.equal(await countMethod('clearSearchHistory'), clears);
  await clickText('#search-history-confirm button', '取消');
  assert.equal(await evaluate(`document.querySelector('#search-history-confirm')===null`), true);
  assert.equal(await countMethod('clearSearchHistory'), clears);
  await configure({ failNextClear: true, delayNextClear: 250 });
  await clickText('.search-history>header .history-clear', '清空');
  await clickText('#search-history-confirm button', '确认清空');
  await wait(`window.__searchFixture.activeHistory===1`);
  assert.deepEqual(await history(), beforeClear, 'clear must not optimistically erase history');
  await historyIdle(); await wait(`document.querySelector('.search-history-error[role="alert"]')?.textContent.includes('MOCK_STORAGE_CLEAR_FAILED')`);
  assert.deepEqual(await history(), beforeClear); assert.deepEqual(await evaluate(`window.__searchFixture.read()`), beforeClear);
  await screenshot('ui-mock-clear-error-phone.png');
  await clickText('#search-history-confirm button', '确认清空'); await historyIdle();
  assert.deepEqual(await history(), []); assert.deepEqual(await evaluate(`window.__searchFixture.read()`), []);
  pass('confirmed_clear_commit', 'cancel sends no RPC; pending/failed commit retains list; successful confirmation clears');

  await submit('重开保留'); await historyIdle();
  await nav('发现'); await openSearch(); assert.deepEqual(await history(), ['重开保留']);
  await reload(false); assert.deepEqual(await history(), ['重开保留']);
  pass('remount_and_reload_persistence', 'mock native store commits localStorage, reopened tab and browser reload retain history');

  await nav('发现'); await evaluate(`window.__searchFixture.seed(['旧记录'])`); await configure({ delayNextGet: 450, delayNextAdd: 350 });
  await nav('搜索'); await wait(`document.querySelector('.search-history')?.getAttribute('aria-busy')==='true'&&window.__searchFixture.activeHistory===1`);
  await submit('新提交');
  await wait(`window.__searchFixture.calls.some(call=>call.method==='addSearchHistory'&&call.params.keyword==='新提交')&&window.__searchFixture.activeHistory===1`);
  assert.deepEqual(await history(), [], 'stale initial get must not flash old history while add is pending');
  await historyIdle(); await result('新提交'); assert.deepEqual(await history(), ['新提交', '旧记录']);
  pass('slow_get_submit_race', 'old snapshot ignored; serialized add result wins without optimistic update');

  await configure({ delayNextAdd: 450 }); const eventStart = await evaluate(`window.__searchFixture.events.length`);
  await submit('卸载待写'); await wait(`window.__searchFixture.calls.some(call=>call.method==='addSearchHistory'&&call.params.keyword==='卸载待写')&&window.__searchFixture.activeHistory===1`);
  await nav('发现'); await nav('搜索'); await wait(`document.querySelector('.search-form')!==null`);
  const pendingEvents = await evaluate(`window.__searchFixture.events.slice(${eventStart})`);
  assert.equal(pendingEvents.some(event=>event.method==='getSearchHistory'&&event.phase==='request'), false, 'new get must wait behind old mounted write');
  await historyIdle(); assert.equal((await history())[0], '卸载待写');
  const events = await evaluate(`window.__searchFixture.events.slice(${eventStart})`);
  assert.ok(events.findIndex(event=>event.method==='addSearchHistory'&&event.phase==='response') < events.findIndex(event=>event.method==='getSearchHistory'&&event.phase==='request'));
  assert.equal(await evaluate(`window.__searchFixture.maxActiveHistory`), 1);
  pass('pending_add_remount_order', 'old write response precedes remounted get; maximum concurrent history RPC=1');

  await configure({ delayNextAdd: 300 });
  await submit('已确认写入A');
  await wait(`window.__searchFixture.calls.some(call=>call.method==='addSearchHistory'&&call.params.keyword==='已确认写入A')&&window.__searchFixture.activeHistory===1`);
  await configure({ failNextAdd: true, delayNextAdd: 300 });
  await submit('随后失败B'); await result('随后失败B');
  await wait(`window.__searchFixture.calls.some(call=>call.method==='addSearchHistory'&&call.params.keyword==='随后失败B')&&window.__searchFixture.activeHistory===1`);
  assert.equal((await history())[0], '已确认写入A', 'earlier committed mutation must remain visible while latest write is pending');
  await historyIdle();
  assert.equal((await history())[0], '已确认写入A'); assert.equal((await history()).includes('随后失败B'), false);
  assert.equal(await evaluate(`document.querySelector('.search-history-error')?.textContent.includes('MOCK_STORAGE_ADD_FAILED')`), true);
  pass('earlier_add_commit_latest_add_failure', 'confirmed A survives newer B failure; B source result remains visible');

  await clickText('.search-history-error button', '重新读取'); await historyIdle();
  const clearingTag = (await history())[0];
  await configure({ delayNextClear: 300, failNextAdd: true, delayNextAdd: 300 });
  await clickText('.search-history>header .history-clear', '清空');
  await clickText('#search-history-confirm button', '确认清空');
  await wait(`window.__searchFixture.activeHistory===1`);
  await clickText('.history-keyword', clearingTag); await result(clearingTag);
  await wait(`window.__searchFixture.calls.filter(call=>call.method==='addSearchHistory'&&call.params.keyword===${js(clearingTag)}).length===2&&window.__searchFixture.activeHistory===1`);
  assert.deepEqual(await history(), [], 'clear success must update list even while newer tag add is pending');
  await historyIdle(); assert.deepEqual(await history(), []); assert.deepEqual(await evaluate(`window.__searchFixture.read()`), []);
  assert.equal(await evaluate(`document.querySelector('.search-history-error')?.textContent.includes('MOCK_STORAGE_ADD_FAILED')`), true);
  pass('earlier_clear_commit_latest_add_failure', 'confirmed clear remains empty after queued tag-save fails; no stale history revival');

  const beforeAddFailure = await history();
  await configure({ failNextAdd: true }); await submit('存储失败仍搜索'); await historyIdle(); await result('存储失败仍搜索');
  await wait(`document.querySelector('.search-history-error[role="alert"]')?.textContent.includes('MOCK_STORAGE_ADD_FAILED')`);
  assert.deepEqual(await history(), beforeAddFailure);
  pass('storage_failure_source_success', 'storage commit failure preserves history while real React renders mocked source result');

  await configure({ failNextAdd: true, failSourceKeywords: ['双重失败'] }); await submit('双重失败'); await historyIdle();
  await wait(`document.querySelector('.error-state[role="alert"]')?.textContent.includes('HTTP 503')&&document.querySelector('.search-history-error[role="alert"]')?.textContent.includes('MOCK_STORAGE_ADD_FAILED')`);
  assert.deepEqual(await history(), beforeAddFailure);
  await assertLayout(); await screenshot('ui-mock-separated-errors-phone.png');
  await clickText('.search-history-error button', '重新读取'); await historyIdle();
  assert.equal(await evaluate(`document.querySelector('.search-history-error')===null`), true);
  assert.equal(await evaluate(`document.querySelector('.error-state')?.textContent.includes('HTTP 503')`), true);
  pass('separate_errors_and_reread', 'source HTTP503 and native storage error coexist; reread clears only storage error');

  await nav('发现'); await configure({ failNextGet: true }); await openSearch();
  await wait(`document.querySelector('.search-history-error')?.textContent.includes('MOCK_STORAGE_GET_FAILED')`);
  await clickText('.search-history-error button', '重新读取'); await historyIdle(); assert.deepEqual(await history(), beforeAddFailure);
  pass('mount_read_failure_retry', 'initial read failure offers retry and recovers committed history');

  for (let index = 1; index <= 22; index++) { await submit('顺序词' + index); await historyIdle(); }
  const bounded = await history(); assert.equal(bounded.length, 20); assert.equal(bounded[0], '顺序词22'); assert.equal(bounded.at(-1), '顺序词3');
  await submit('长'.repeat(100)); await historyIdle(); await result('长'.repeat(100)); assert.equal((await history())[0].length, 100); assert.equal((await history()).length, 20);
  await assertLayout(); await screenshot('ui-mock-history-phone.png');
  pass('limit_and_long_word_phone_layout', 'most recent20; 100-char keyword wraps at390x844; no nested buttons or horizontal overflow');

  await call('Emulation.setDeviceMetricsOverride', { width: 1280, height: 720, deviceScaleFactor: 1, mobile: false });
  await reload(true); assert.deepEqual(await history(), await evaluate(`window.__searchFixture.read()`));
  // Start at an existing tag, then exercise production spatial navigation both ways.
  await evaluate(`document.querySelectorAll('.history-keyword')[1].focus()`);
  await key('ArrowRight'); assert.equal(await evaluate(`document.activeElement.classList.contains('history-remove')`), true);
  await key('ArrowLeft'); assert.equal(await evaluate(`document.activeElement.classList.contains('history-keyword')`), true);
  const selected = await evaluate(`document.activeElement.textContent`);
  const adds = await countMethod('addSearchHistory'); await key('Enter'); await historyIdle(); await result(selected);
  assert.equal(await countMethod('addSearchHistory'), adds + 1); assert.equal((await history())[0], selected);
  await evaluate(`document.querySelector('.history-keyword').focus()`); await key('ArrowRight');
  assert.equal(await evaluate(`document.activeElement.getAttribute('aria-label')`), '删除搜索历史：' + selected);
  await screenshot('ui-mock-history-tv.png');
  await key('Enter'); await historyIdle(); assert.equal((await history()).includes(selected), false);
  await assertLayout();
  pass('tv_dpad_tag_and_delete', '1280x720 D-padRight reaches delete, Left reaches tag; Enter searches/deletes; no overflow');

  assert.deepEqual(pageErrors, [], 'uncaught browser exceptions');
  evidence.historyRpcEvents = await evaluate(`window.__searchFixture.events.filter(event=>event.method.includes('SearchHistory'))`);
  evidence.finalKeywords = await history(); evidence.result = 'PASS';
  console.log('ANDROID_SEARCH_HISTORY_MOCK_UI_OK tests=' + evidence.tests.length + ' phone=390x844 tv=1280x720 realDevice=not_run');
  console.log('MOCK_UI_SCREENSHOTS ' + evidence.screenshots.join(' | '));
} catch (error) {
  evidence.result = 'FAIL'; evidence.error = error.stack || String(error); evidence.browserErrors = pageErrors;
  try { evidence.dom = await evaluate(`document.body.innerText`); await screenshot('ui-mock-failure.png'); } catch {}
  console.error(error); process.exitCode = 1;
} finally {
  fs.writeFileSync(resolve(output, 'ui-mock-results.json'), JSON.stringify(evidence, null, 2) + '\n');
  await call('Page.close').catch(() => {}); ws.close();
}
