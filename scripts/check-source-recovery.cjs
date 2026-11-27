const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const http = require('node:http');
const nativeDir = path.join(__dirname, '../src/native');
const playbackDevice = Object.freeze({ device_id: '7000000000000000001', iid: '7000000000000000002', cdid: '00000000-0000-4000-8000-000000000003' });
const media = name => `https://v3-reading.qznovelvod.com/${name}.mp4`;
const webPage = (vid = '11', sid = '1', extra = {}) => `<script>window._ROUTER_DATA=${JSON.stringify({ loaderData: {
  'player_(series_id)/(vid)/page': { isSuccess: true, vid, series_id: sid, seriesDetail: { series_id: sid, vid_list: ['11'] },
    publicVideoSeo: { available: true, itemId: vid, seriesId: sid }, ...extra },
} })}</script><script type="application/ld+json">${JSON.stringify({ '@type': 'VideoObject', url: `https://hongguoduanju.com/player/${sid}/${vid}`, contentUrl: media('web') })}</script>`;
function load(file, dependencies) {
  dependencies = { './playback-device': { registerPlaybackDevice: async () => playbackDevice }, ...dependencies };
  if (file === 'hongguo.js' && !Object.hasOwn(dependencies, './app-source')) {
    // 2026-11-27: the list chain asks the App detail first; without a caller stub it stays offline.
    dependencies['./app-source'] = { ...require(path.join(nativeDir, 'app-source.js')),
      fetchAppEpisodeList: async () => { throw Object.assign(new Error('app detail unavailable'), { code: 'EMPTY_RESPONSE' }); } };
  }
  const context = { Buffer, URL, URLSearchParams, AbortSignal, AbortController, process: { env: {} }, module: { exports: {} },
    console: { warn() {} },
    setTimeout: (fn, ms) => { const timer = setTimeout(fn, ms === 600 ? 0 : ms); return { _timer: timer, unref() { timer.unref?.(); } }; },
    clearTimeout: (timer) => clearTimeout(timer?._timer ?? timer),
    require: name => Object.hasOwn(dependencies, name) ? dependencies[name] : require(name.startsWith('.') ? path.join(nativeDir, name) : name) };
  vm.runInNewContext(fs.readFileSync(path.join(nativeDir, file), 'utf8'), context);
  return context.module.exports;
}
function encodedKey(hex) {
  const plain = Buffer.from(`0${hex}`), raw = Buffer.alloc(34), previous = [246, 85];
  for (let i = 0; i < plain.length; i++) {
    raw[i + 1] = ((plain[i] + 21 + (i.toString(2).split('1').length - 1)) & 255) ^ previous[i & 1];
    previous[i & 1] = raw[i + 1];
  }
  raw[0] = 48 ^ raw[1] ^ raw[2];
  return raw.toString('base64');
}
function appFixture(rows) {
  const calls = [];
  const app = load('app-source.js', { axios: async config => {
    calls.push(config);
    return { status: 200, headers: {}, data: JSON.stringify(config.method === 'POST'
      ? { data: { '11': { video_model: { fallback_api: 'https://vod.snssdk.com/info' } } } }
      : { video_info: { data: { video_list: rows } } }) };
  } });
  return { ...app, calls };
}
function routeFixture() {
  const state = { web: true, app: false, calls: [] };
  const native = load('hongguo.js', {
    axios: {
      get: async url => { state.calls.push('web'); return { data: state.web && url.endsWith('/11') ? webPage() : '' }; },
      post: async () => { state.calls.push('api'); return { status: 200, headers: {}, data: Buffer.alloc(0) }; },
    },
    './app-source': { fetchAppPlayUrl: async () => { state.calls.push('app'); if (!state.app) throw new Error('secret https://signed.invalid/?token=private'); return { url: media('app'), source: 'app' }; } },
  });
  return { ...native, state };
}
const checks = [
  ['Missing explicit quality remains distinct from incompatible automatic sources without leaking raw messages', async () => {
    for (const quality of ['720p', '1080p']) {
      const app = appFixture({ low: { main_url: media('low'), codec_type: 'h264', width: 360, height: 640 } });
      const f = load('hongguo.js', { './app-source': app });
      const result = await f.fetchPlayUrlSingle('11', '1', { quality });
      assert.equal(result.sources[0].code, 'QUALITY_UNAVAILABLE');
      assert(result.error.includes(`满足 ${quality}`));
      assert(result.error.includes('未降级'));
    }
    const app = appFixture({ incompatible: { main_url: media('av1'), codec_type: 'av1', width: 1080, height: 1920 } });
    await assert.rejects(app.fetchAppPlayUrl('11'), error => error.code === 'NO_COMPATIBLE_SOURCE');
    for (const quality of ['720p', '1080p']) {
      const f = load('hongguo.js', { './app-source': app });
      const result = await f.fetchPlayUrlSingle('11', '1', { quality });
      assert.equal(result.sources[0].code, 'NO_COMPATIBLE_SOURCE', 'incompatible codecs must not be misreported as missing resolution');
    }
    for (const code of ['QUALITY_UNAVAILABLE', 'NO_COMPATIBLE_SOURCE']) {
      const f = load('hongguo.js', { './app-source': { fetchAppPlayUrl: async () => {
        throw Object.assign(new Error('secret https://signed.invalid/?token=private'), { code, quality: 'secret' });
      } } });
      const result = await f.fetchPlayUrlSingle('11', '1', { quality: '1080p' });
      assert.equal(result.sources[0].code, code);
      assert.doesNotMatch(JSON.stringify(result), /secret|signed\.invalid|private/);
    }
  }],
  ['Registration failures retain safe details and remain retryable only for transient causes', async () => {
    for (const [failure, expected, detail] of [
      [{ code: 'ECONNRESET' }, 'NETWORK_ERROR', '网络连接失败'],
      [{ code: 'ETIMEDOUT' }, 'TIMEOUT', '请求超时'],
      [{ status: 503 }, 'HTTP_503', 'HTTP 503'],
      [{ status: 403 }, 'HTTP_403', 'HTTP 403'],
      [{ code: 'EMPTY_RESPONSE' }, 'DEVICE_REGISTRATION_FAILED', '空响应'],
      [{ code: 'INVALID_RESPONSE' }, 'DEVICE_REGISTRATION_FAILED', '有效设备编号'],
      [{ code: 'BUSINESS_REJECTED' }, 'DEVICE_REGISTRATION_FAILED', '拒绝注册'],
      [{ code: 'SECURITY_BLOCKED' }, 'DEVICE_REGISTRATION_FAILED', '安全校验'],
      [{ code: 'secret' }, 'DEVICE_REGISTRATION_FAILED', '10 秒后重试'],
    ]) {
      const device = load('playback-device.js', {});
      const registerPlaybackDevice = async request => device.registerPlaybackDevice(async (...args) => {
        if (['EMPTY_RESPONSE', 'INVALID_RESPONSE', 'BUSINESS_REJECTED', 'SECURITY_BLOCKED'].includes(failure.code)) {
          throw Object.assign(new Error('secret'), failure);
        }
        return request(...args);
      });
      const routedApp = load('app-source.js', { './playback-device': { registerPlaybackDevice }, axios: async config => {
        if (failure.status) return { status: failure.status, headers: {}, data: 'secret' };
        throw Object.assign(new Error('secret https://signed.invalid/?token=private'), failure);
      } });
      const f = load('hongguo.js', { './app-source': routedApp });
      const result = await f.fetchPlayUrlSingle('11', '1', { quality: '720p' });
      assert.equal(result.sources[0].code, failure.code === 'secret' ? 'NETWORK_ERROR' : expected);
      assert.match(result.error, /匿名播放设备注册失败/);
      assert.ok(result.error.includes(detail));
      assert.doesNotMatch(JSON.stringify(result), /secret|signed\.invalid|private/);
    }
    for (const registrationFailure of ['secret', 'constructor', 'toString', '__proto__']) {
      const f = load('hongguo.js', { './app-source': { fetchAppPlayUrl: async () => {
        throw Object.assign(new Error('secret'), { code: 'DEVICE_REGISTRATION_FAILED', registrationFailure });
      } } });
      const result = await f.fetchPlayUrlSingle('11', '1', { quality: '720p' });
      assert.equal(result.sources[0].message, '匿名播放设备注册失败；本机冷却 10 秒后可手动重试，上游何时恢复暂无法确认');
      assert.doesNotMatch(JSON.stringify(result), /secret|function|\[object/);
    }
  }],
  ['App network failures retain only safe codes and aggregate into distinct reasons', async () => {
    for (const [code, expected] of [['ECONNABORTED', 'TIMEOUT'], ['ETIMEDOUT', 'TIMEOUT'],
      ['ENOTFOUND', 'NETWORK_ERROR'], ['ECONNRESET', 'NETWORK_ERROR'], ['ERR_NETWORK', 'NETWORK_ERROR'],
      ['secret-token-code', 'NETWORK_ERROR']]) {
      const app = load('app-source.js', { axios: async () => {
        throw Object.assign(new Error('secret https://signed.invalid/?token=private'), { code, config: { secret: true } });
      } });
      await assert.rejects(app.fetchAppPlayUrl('11'), error => {
        assert.equal(error.code, code === 'secret-token-code' ? 'ERR_NETWORK' : code);
        assert.doesNotMatch(error.message + JSON.stringify(error), /secret|signed\.invalid|private/);
        return true;
      });
      const f = load('hongguo.js', { './app-source': app });
      const result = await f.fetchPlayUrlSingle('11', '1', { quality: '720p' });
      assert.equal(result.sources[0].code, expected);
      assert.doesNotMatch(JSON.stringify(result), /secret|signed\.invalid|private/);
    }
  }],
  ['App distinguishes HTTP, business refusal, absent model, parsing and unsafe media', async () => {
    const first = { data: { '11': { video_model: { fallback_api: 'https://vod.snssdk.com/info' } } } };
    const cases = [
      [{ status: 403 }, null, 'HTTP_403'],
      [{ data: { code: 8, message: 'secret' } }, null, 'BUSINESS_REJECTED'],
      [{ data: { data: {} } }, null, 'MISSING_MODEL'],
      [{ data: 'not json secret' }, null, 'INVALID_RESPONSE'],
      [{ data: { data: { '11': { video_model: 'not json secret' } } } }, null, 'INVALID_RESPONSE'],
      [{ data: first }, { code: 8, message: 'secret' }, 'BUSINESS_REJECTED'],
      [{ data: first }, { video_info: { data: { video_list: { one: { codec_type: 'h264', width: 720, height: 1280, main_url: 'https://127.0.0.1/private' } } } } }, 'SECURITY_BLOCKED'],
    ];
    for (const [response, second, expected] of cases) {
      const app = load('app-source.js', { axios: async config => {
        const result = { status: 200, headers: {}, ...(config.method === 'POST' ? response : { data: second }) };
        if (typeof result.data !== 'string') result.data = JSON.stringify(result.data);
        return result;
      } });
      const f = load('hongguo.js', { './app-source': app });
      const result = await f.fetchPlayUrlSingle('11', '1', { quality: '720p' });
      assert.equal(result.sources[0].code, expected);
      assert.doesNotMatch(JSON.stringify(result), /secret|127\.0\.0\.1|private/);
    }
  }],
  ['Web keeps null compatibility but reports actual failures on first and final fallback', async () => {
    for (const [failure, expected] of [
      [Object.assign(new Error('secret URL'), { response: { status: 404 } }), 'HTTP_404'],
      [Object.assign(new Error('secret URL'), { code: 'ETIMEDOUT' }), 'TIMEOUT'],
      [Object.assign(new Error('secret URL'), { code: 'ERR_WEB_MEDIA_ADDRESS' }), 'SECURITY_BLOCKED'],
      [webPage().replace(media('web'), 'https://127.0.0.1/private'), 'SECURITY_BLOCKED'],
      ['', 'MISSING_MODEL'],
    ]) {
      const f = load('hongguo.js', {
        axios: { get: async () => { if (typeof failure !== 'string') throw failure; return { data: failure }; },
          post: async () => ({ status: 200, headers: {}, data: Buffer.alloc(0) }) },
        './app-source': { fetchAppPlayUrl: async () => { throw new Error('unavailable'); } },
      });
      assert.equal(await f.fetchPlayUrlFromWeb('11', '1'), null);
      for (const skipWeb of [false, true]) {
        if (skipWeb) f.markWebMediaFailure({ source: 'web', vid: '11' });
        const result = await f.fetchPlayUrlSingle('11', '1', { force: true });
        assert.equal(result.sources.find(item => item.source === 'web').code, expected);
        assert.doesNotMatch(JSON.stringify(result), /secret|127\.0\.0\.1|private/);
      }
    }
  }],
  ['Playback route budgets reach App after Web timeout and bound legacy API to one attempt', async () => {
    const webTimeouts = [], apiTimeouts = [];
    let appAvailable = true, webAvailable = false;
    const f = load('hongguo.js', {
      axios: {
        get: async (_url, options) => {
          webTimeouts.push(options.timeout);
          if (webAvailable) return { data: webPage() };
          throw Object.assign(new Error('timeout'), { code: 'ECONNABORTED' });
        },
        post: async (_url, _body, options) => {
          apiTimeouts.push(options.timeout);
          throw Object.assign(new Error('timeout'), { code: 'ECONNABORTED' });
        },
      },
      './app-source': { fetchAppPlayUrl: async () => {
        if (!appAvailable) throw new Error('App 片源请求超时');
        return { url: media('app'), source: 'app' };
      } },
    });
    assert.equal((await f.fetchPlayUrlSingle('11', '1')).source, 'app');
    assert.deepEqual(webTimeouts, [4000, 4000], 'Web cannot consume the whole lookup deadline');
    assert.deepEqual(apiTimeouts, []);
    appAvailable = false; webTimeouts.length = 0;
    assert.equal((await f.fetchPlayUrlSingle('11', '1', { force: true })).code, 'SOURCE_UNAVAILABLE');
    assert.deepEqual(webTimeouts, [4000, 4000]);
    assert.deepEqual(apiTimeouts, [6000], 'legacy playback lookup must not repeat after timeout');
    webAvailable = true; webTimeouts.length = 0;
    assert.equal((await f.fetchEpisodeListFromWeb('1')).series_id, '1');
    assert.deepEqual(webTimeouts, [1500, 1500], 'episode page first race bounds each web route');
  }],
  ['Web rejects wrong or missing current episode identity even when vid_list contains the request', async () => {
    for (const data of [
      { vid: '10', seriesDetail: { series_id: '1', vid_list: ['10', '11'] } },
      { seriesDetail: { series_id: '1', vid_list: ['11'] } },
      { vid: '11', seriesDetail: { series_id: '2', vid_list: ['11'] } },
      { vid: '11' },
    ]) {
      const html = `window._ROUTER_DATA=${JSON.stringify({ loaderData: { player: data }, recommendation: { main_url: media('wrong') } })}`;
      const f = load('hongguo.js', { axios: { get: async () => ({ data: html }) } });
      assert.equal(await f.fetchPlayUrlFromWeb('11', '1'), null, 'unverified webpage must not be labelled as the requested episode');
    }
  }],
  ['Episode list retries both web routes, then retries a transient network failure', async () => {
    const webCalls = [], apiCalls = [], sid = '918273645';
    const f = load('hongguo.js', {
      axios: {
        get: async url => {
          webCalls.push(url);
          throw Object.assign(new Error('aborted'), { code: 'ECONNRESET' });
        },
        post: async () => {
          apiCalls.push(1);
          if (apiCalls.length < 3) throw Object.assign(new Error('connection reset'), { code: 'ECONNRESET' });
          return { status: 200, headers: {}, data: Buffer.from(JSON.stringify({ code: 0, data: {
            [sid]: { video_data: { series_title: '重试成功', video_list: [
              { vid: '101', vid_index: 1 }, { vid: '102', vid_index: 2 },
            ] } },
          } })) };
        },
      },
    });
    const data = await f.fetchEpisodeList(sid);
    assert.equal(data.total, 2);
    assert.equal(apiCalls.length, 3, 'transient network failures must still be retried');
    assert.equal(webCalls.filter(url => url.includes(`/player/${sid}`)).length, 3, 'the retry must try the player route with both resolvers');
    assert.equal(webCalls.filter(url => url.includes(`series_id=${sid}`)).length, 3, 'the retry must try the detail route with both resolvers');
  }],
  ['Episode list turns repeated aborts and empty responses into one readable failure', async () => {
    let apiCalls = 0;
    const f = load('hongguo.js', {
      axios: {
        get: async () => { throw Object.assign(new Error('aborted'), { code: 'ECONNRESET' }); },
        post: async () => { apiCalls++; return { status: 200, headers: {}, data: Buffer.alloc(0) }; },
      },
    });
    await assert.rejects(f.fetchEpisodeList('918273646'), error => {
      assert.match(error.message, /加载超时或连接中断/);
      assert.match(error.message, /0 字节/);
      assert.doesNotMatch(error.message, /aborted/);
      return true;
    });
    assert.equal(apiCalls, 1, 'empty 200 responses must stop without retrying');
  }],
  // 2026-11-27: the official page lists only the leading episodes, by obfuscated token (upstream #217).
  ['Episode list takes the App detail first, rejects an incomplete web preview and bounds its own timeout', async () => {
    const sid = '918273647';
    let appCalls = 0, webCalls = 0, apiCalls = 0;
    const appFirst = load('hongguo.js', {
      './app-source': { fetchAppEpisodeList: async (id, opts) => {
        appCalls++;
        assert.equal(id, sid);
        assert.ok(opts?.signal instanceof AbortSignal, 'the App list must receive a bounded abort signal');
        return { series_id: sid, series_title: '完整剧', total: 6, episodes: Array.from({ length: 6 }, (_, i) => ({ vid: String(100 + i), vid_index: i + 1 })), source: 'app' };
      } },
      axios: {
        get: async () => { webCalls++; return { data: '' }; },
        post: async () => { apiCalls++; return { status: 200, headers: {}, data: Buffer.alloc(0) }; },
      },
    });
    const full = await appFirst.fetchEpisodeList(sid);
    assert.equal(full.source, 'app'); assert.equal(full.total, 6);
    assert.equal(appCalls, 1); assert.equal(webCalls, 0); assert.equal(apiCalls, 0, 'a served App list reads no web page and no preload API');

    // A page that lists only the accessible prefix of a longer series is a preview, never the list.
    const previewHtml = `<script>window._ROUTER_DATA=${JSON.stringify({ loaderData: {
      'player_(series_id)/page': { isSuccess: true, series_id: sid, seriesDetail: {
        series_id: sid, series_name: '预览剧', episode_cnt: 71, accessible_episode_cnt: 3, vid_list: ['101', '102', '103'],
      } },
    } })}</script>`;
    let detailCalls = 0;
    const previewFallback = load('hongguo.js', {
      axios: {
        get: async () => ({ data: previewHtml }),
        post: async () => {
          detailCalls++;
          return { status: 200, headers: {}, data: Buffer.from(JSON.stringify({ code: 0, data: {
            [sid]: { video_data: { series_title: '完整回退', video_list: Array.from({ length: 71 }, (_, i) => ({ vid: String(200 + i), vid_index: i + 1 })) } },
          } })) };
        },
      },
    });
    const parsedPreview = await previewFallback.fetchEpisodeListFromWeb(sid);
    assert.equal(parsedPreview.episode_count, 71); assert.equal(parsedPreview.complete, false);
    const recovered = await previewFallback.fetchEpisodeList(sid);
    assert.equal(recovered.total, 71);
    assert.equal(detailCalls, 1, 'an incomplete web preview must not be accepted as a full series');

    // Token pages (the current site format) stay a typed web failure behind the App failure.
    let tokenApiCalls = 0;
    const tokens = load('hongguo.js', { axios: {
      get: async () => ({ data: previewHtml.replace('"vid_list":["101","102","103"]', '"vid_list":["v1_5DC8VBWf14_jqPcnhiSPfJ6fiGI","v1_2VMvyadqPpt6G-ZZqkmCa18eFHw"]') }),
      post: async () => { tokenApiCalls++; return { status: 200, headers: {}, data: Buffer.alloc(0) }; },
    } });
    await assert.rejects(tokens.fetchEpisodeList(sid), error => {
      assert.match(error.message, /App详情（接口返回空响应）/); assert.match(error.message, /官网分集数据异常/); assert.match(error.message, /0 字节/);
      assert.doesNotMatch(error.message, /aborted|secret|signed\.invalid/);
      return true;
    });
    assert.equal(tokenApiCalls, 1);

    // A stalled App list is abandoned inside its budget; the web page keeps the remaining deadline.
    let timedOutSignal = false, webBudgetOk = false;
    const deadline = Date.now() + 300;
    const bounded = load('hongguo.js', {
      './app-source': { fetchAppEpisodeList: async (_id, opts) => new Promise((_resolve, reject) => {
        opts.signal.addEventListener('abort', () => { timedOutSignal = true; reject(new Error('aborted')); }, { once: true });
      }) },
      axios: {
        get: async () => {
          webBudgetOk = deadline - Date.now() > 0;
          return { data: `<script>window._ROUTER_DATA=${JSON.stringify({ loaderData: {
            'player_(series_id)/page': { isSuccess: true, series_id: sid, seriesDetail: {
              series_id: sid, series_name: '完整网页剧', episode_cnt: 2, vid_list: ['301', '302'],
            } },
          } })}</script>` };
        },
        post: async () => ({ status: 200, headers: {}, data: Buffer.alloc(0) }),
      },
    });
    const keepAlive = setInterval(() => {}, 50);
    try {
      const fromWeb = await bounded.fetchEpisodeList(sid, { deadline, force: true });
      assert.equal(timedOutSignal, true, 'a stalled App list must abort before the outer deadline');
      assert.equal(webBudgetOk, true, 'the web fallback must keep a positive remaining deadline');
      assert.equal(fromWeb.total, 2);
    } finally { clearInterval(keepAlive); }
  }],
  ['Episode list reads the signed App video_detail/v1 with 64-bit series ids and full numeric vids', async () => {
    const sid = '7706908220253555737';
    let detailCalled = 0, webCalled = 0;
    const app = load('app-source.js', {
      axios: async config => {
        detailCalled++;
        const url = new URL(config.url);
        assert.equal(url.pathname, '/novel/player/video_detail/v1/');
        assert.equal(config.method, 'POST');
        assert.equal(url.searchParams.get('version_code'), '74532');
        assert.equal(url.searchParams.get('iid'), playbackDevice.iid);
        assert.equal(url.searchParams.get('device_id'), playbackDevice.device_id);
        assert.equal(url.searchParams.get('cdid'), playbackDevice.cdid);
        assert.equal(config.headers['User-Agent'], 'okhttp/4.12.0');
        assert.equal(config.headers['Content-Encoding'], 'gzip');
        assert.ok(config.headers['x-medusa'] && config.headers['x-helios'] && config.headers['x-khronos'] && config.headers['x-ss-stub']);
        const raw = `{"code":0,"data":{"video_data":{"series_id":${sid},"series_title":"完整真剧","series_cover":"https://img.example/c.jpg","series_intro":"简介","episode_cnt":3,"content_type":1001,"video_list":[{"vid":7706910230088518718,"vid_index":1,"title":"第1集"},{"vid":7706910230088518719,"vid_index":2,"title":"第2集"},{"vid":7706910230088518720,"vid_index":3,"title":"第3集","disable_play":true}]}}}`;
        return { status: 200, headers: {}, data: raw };
      },
    });
    const f = load('hongguo.js', {
      './app-source': app,
      axios: { get: async () => { webCalled++; return { data: '' }; }, post: async () => ({ status: 200, headers: {}, data: Buffer.alloc(0) }) },
    });
    const result = await f.fetchEpisodeList(sid);
    assert.equal(detailCalled, 1); assert.equal(webCalled, 0);
    assert.equal(result.source, 'app'); assert.equal(result.series_id, sid); assert.equal(result.content_type, '1001'); assert.equal(result.total, 3);
    assert.deepEqual(Array.from(result.episodes, e => e.vid), ['7706910230088518718', '7706910230088518719', '7706910230088518720']);
    assert.deepEqual(Array.from(result.episodes, e => e.locked), [false, false, true]);
    assert.equal(result.episodes[0].series_title, '完整真剧');
    // A refusal or a short list is typed, and a canceled caller is never answered from the App.
    const refused = load('app-source.js', { axios: async () => ({ status: 200, headers: {}, data: `{"code":0,"data":{"video_data":{"series_id":${sid},"series_title":"短","video_list":[{"vid":1,"vid_index":1},{"vid":1,"vid_index":2}],"episode_cnt":3}}}` }) });
    await assert.rejects(refused.fetchAppEpisodeList(sid), error => error.code === 'INVALID_RESPONSE');
    await assert.rejects(refused.fetchAppEpisodeList('12', { signal: AbortSignal.abort() }), error => error.code === 'ERR_CANCELED');
    await assert.rejects(refused.fetchAppEpisodeList('../1'), error => error.code === 'INVALID_OPTIONS');
    const other = load('app-source.js', { axios: async () => ({ status: 200, headers: {}, data: `{"code":0,"data":{"video_data":{"series_id":100001,"video_list":[{"vid":1,"vid_index":1}]}}}` }) });
    await assert.rejects(other.fetchAppEpisodeList(sid), error => error.code === 'INVALID_RESPONSE');
  }],
  ['Protected website SEO preview never replaces the complete App episode', async () => {
    const calls = [];
    let protectedPlayback = true, appAvailable = true;
    const f = load('hongguo.js', {
      axios: {
        get: async () => { calls.push('web'); return { data: webPage('11', '1', { protectedPlayback,
          publicVideoSeo: { available: true, itemId: '11', seriesId: '1', durationSeconds: 166.488 } }) }; },
        post: async () => { calls.push('api'); return { status: 200, headers: {}, data: Buffer.alloc(0) }; },
      },
      './app-source': { fetchAppPlayUrl: async (_vid, options) => {
        calls.push(options?.quality || 'app');
        if (!appAvailable) throw new Error('unavailable');
        return { url: media('complete'), source: 'app' };
      } },
    });
    const result = await f.fetchPlayUrlSingle('11', '1');
    assert.equal(result.source, 'app', 'SEO duration describes the episode, not the public preview file');
    assert.deepEqual(calls, ['web', 'app'], 'known preview must skip the redundant alternate page');
    appAvailable = false; calls.length = 0;
    const unavailable = await f.fetchPlayUrlSingle('11', '1', { force: true });
    assert.equal(unavailable.code, 'SOURCE_UNAVAILABLE');
    assert.equal(unavailable.sources.find(item => item.source === 'web').code, 'PREVIEW_ONLY');
    f.markWebMediaFailure({ source: 'web', vid: '11' }); calls.length = 0;
    assert.equal((await f.fetchPlayUrlSingle('11', '1', { force: true })).code, 'SOURCE_UNAVAILABLE',
      'final web recovery must not reintroduce the preview');
    protectedPlayback = false;
    assert.equal((await f.fetchPlayUrlSingle('11', '1', { force: true })).source, 'web', 'complete public web remains a valid recovery');
    appAvailable = true; calls.length = 0;
    assert.equal((await f.fetchPlayUrlSingle('11', '1', { quality: '1080p' })).source, 'app');
    assert.deepEqual(calls, ['1080p'], 'explicit quality remains App-only');
  }],
  ['Web accepts only an identity-bound VideoObject and ignores earlier recommended media', async () => {
    const unrelated = `<script type="application/ld+json">${JSON.stringify({ '@type': 'VideoObject', url: 'https://hongguoduanju.com/player/2/22', contentUrl: media('recommendation') })}</script>`;
    let html = unrelated + webPage();
    const f = load('hongguo.js', { axios: { get: async () => ({ data: html }) } });
    assert.equal((await f.fetchPlayUrlFromWeb('11', '1')).url, media('web'));
    for (const bad of [webPage('10'), webPage('11', '2'), webPage('11', '1', { vid: undefined }),
      webPage('11', '1', { publicVideoSeo: { available: true, itemId: '10', seriesId: '1' } }),
      webPage().replace('/player/1/11', '/player/1/10'), webPage().replace('https://hongguoduanju.com/player/', 'https://other.invalid/player/')]) {
      html = bad; assert.equal(await f.fetchPlayUrlFromWeb('11', '1'), null);
    }
  }],
  ['Web accepts the current series canonical URL only with episode-bound official media', async () => {
    const url = 'https://hongguoduanju.com/video/public/11/360p.mp4';
    const page = webPage().replace('/player/1/11', '/player/1').replace(media('web'), url);
    let html = page;
    const f = load('hongguo.js', { axios: { get: async () => ({ data: html }) } });
    assert.equal((await f.fetchPlayUrlFromWeb('11', '1'))?.url, url);
    assert.equal((await f.fetchPlayUrlFromWeb('11'))?.url, url);
    for (const bad of [
      page.replace('/public/11/', '/public/12/'),
      page.replace(url, media('recommended')),
      page.replace('/player/1', '/player/2'),
      page.replace('"itemId":"11"', '"itemId":"12"'),
      page.replace('"available":true', '"available":false'),
      page.replace('"isSuccess":true', '"isSuccess":false'),
      page.replace('"@type":"VideoObject"', '"@type":"VideoObject","@id":"https://hongguoduanju.com/player/2#video"'),
    ]) {
      html = bad; assert.equal(await f.fetchPlayUrlFromWeb('11', '1'), null);
    }
    html = page.replace('"@type":"VideoObject"', '"@type":"VideoObject","@id":"https://hongguoduanju.com/player/1#video"');
    assert.equal((await f.fetchPlayUrlFromWeb('11', '1'))?.url, url);
    html = page.replace('/public/11/', '/public/12/') + page;
    assert.equal((await f.fetchPlayUrlFromWeb('11', '1'))?.url, url, 'skip earlier recommendation from the same series');
  }],
  ['Media 403 switches App/API Referer once and preserves ranges, cancellation and other sources', async () => {
    const requests = [];
    const server = http.createServer((request, response) => {
      requests.push({ path: request.url, referer: request.headers.referer, range: request.headers.range, ua: request.headers['user-agent'] });
      const accepted = request.url === '/needs-referer' ? !!request.headers.referer : !request.headers.referer;
      if (!accepted || request.url === '/denied') { response.writeHead(403); response.end(); return; }
      response.writeHead(206, { 'content-type': 'video/mp4', 'content-range': 'bytes 2-4/10' }); response.end('234');
    });
    await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
    try {
      const main = fs.readFileSync(path.join(__dirname, '../main.js'), 'utf8');
      const context = vm.createContext({ URL, axios: require('axios'), hongguo: { UA: 'test', WEB_UA: 'browser', VIDEO_REFERER: 'https://novelquickapp.com/', WEB_REFERER: 'https://hongguoduanju.com/' },
        checkCustomAccess() {}, require: () => ({ webMediaRequestOptions: () => ({}) }) });
      vm.runInContext(main.slice(main.indexOf('async function requestMedia('), main.indexOf('function awaitMediaOperation(')), context);
      const base = `http://127.0.0.1:${server.address().port}`;
      for (const source of ['app', 'api']) {
        const before = requests.length;
        const result = await context.requestMedia({ url: base + '/no-referer', source, userAgent: 'app-agent', referer: 'https://novelquickapp.com/' }, { headers: { Range: 'bytes=2-4' } });
        let body = ''; for await (const chunk of result.data) body += chunk;
        assert.equal(body, '234'); assert.equal(requests.length - before, 2);
        assert.equal(requests.at(-1).referer, undefined);
        assert.equal(requests.at(-1).ua, 'app-agent'); assert.equal(requests.at(-1).range, 'bytes=2-4');
      }
      const added = await context.requestMedia({ url: base + '/needs-referer', source: 'app' }, { headers: { Range: 'bytes=2-4' } });
      added.data.destroy(); assert.equal(requests.at(-1).referer, 'https://novelquickapp.com/');
      for (const source of ['web', 'custom']) {
        const before = requests.length;
        await assert.rejects(context.requestMedia({ url: base + '/no-referer', source, referer: 'https://provider.example/' }));
        assert.ok(requests.slice(before).every(request => request.referer === 'https://provider.example/'));
      }
      const before = requests.length;
      await assert.rejects(context.requestMedia({ url: base + '/denied', source: 'app' }));
      assert.equal(requests.length - before, 2, 'persistent 403 remains bounded');
      const controller = new AbortController(); controller.abort();
      await assert.rejects(context.requestMedia({ url: base + '/denied', source: 'app' }, { signal: controller.signal }));
      assert.equal(requests.length - before, 2, 'cancelled requests must not retry');
    } finally { server.closeAllConnections(); await new Promise(resolve => server.close(resolve)); }
  }],
  ['App preserves safe alternates and binds keys and request identity to each candidate', async () => {
    const key1 = '01'.repeat(16), key2 = '02'.repeat(16);
    const f = appFixture({ first: { main_url: media('main'), backup_url: media('backup'), backup_url_list: [media('extra'), media('backup'), 'https://127.0.0.1/private'], codec_type: 'h264', width: 1080, height: 1920, spade_a: encodedKey(key1) },
      second: { main_url: media('hevc'), codec_type: 'hevc', width: 1080, height: 1920, spade_a: encodedKey(key2) },
      listOnly: { backup_url_list: [media('list')], codec_type: 'hevc', width: 1080, height: 1920 } });
    const result = await f.fetchAppPlayUrl('11');
    assert.ok(Array.isArray(result.variants), 'media candidates must survive address lookup');
    assert.deepEqual(Array.from(result.variants, v => v.url), ['main', 'backup', 'extra', 'hevc', 'list'].map(media));
    for (const candidate of result.variants) {
      assert.equal(candidate.source, 'app'); assert.equal(candidate.vid, '11');
      assert.equal(candidate.userAgent, f.calls[1].headers['User-Agent']);
      assert.equal(candidate.referer, undefined, 'App media must not start with the rejected website Referer');
      assert.equal(candidate.variants, undefined);
    }
    assert.equal(result.variants[2].contentKey, key1); assert.equal(result.variants[3].contentKey, key2);
    assert.equal(result.variants[4].contentKey, null); assert.equal(f.calls.length, 2, 'no media preflight');
  }],
  ['Strict quality alternatives never include smaller or unknown-size streams', async () => {
    const f = appFixture({ low: { main_url: media('low'), codec_type: 'h264', width: 720, height: 1280 },
      unknown: { main_url: media('unknown'), codec_type: 'h264' },
      high: { main_url: media('high'), backup_url_list: [media('high2')], codec_type: 'hevc', width: 1080, height: 1920 } });
    const result = await f.fetchAppPlayUrl('11', { quality: '1080p' });
    assert.ok(result.variants, 'strict-quality candidate list missing');
    assert.deepEqual(Array.from(result.variants, v => v.url), ['high', 'high2'].map(media));
    assert.ok(result.variants.every(v => v.width === 1080 && v.quality === '1080p'));
  }],
  ['Recent web failure prefers App but allows fresh web as final recovery', async () => {
    const f = routeFixture(), first = await f.fetchPlayUrlSingle('11', '1');
    f.markWebMediaFailure(first); f.state.calls.length = 0;
    const recovered = await f.fetchPlayUrlSingle('11', '1', { force: true });
    assert.equal(recovered.source, 'web', 'five-minute penalty must not hide the only working source');
    assert.deepEqual(f.state.calls, ['app', 'api', 'web']);
    f.state.app = true; f.state.calls.length = 0;
    assert.equal((await f.fetchPlayUrlSingle('11', '1')).source, 'app');
    assert.deepEqual(f.state.calls, ['app'], 'web penalty remains after last-resort recovery');
  }],
  ['Unavailable source reports safe per-route reasons without declaring web metadata a global block', async () => {
    const f = routeFixture(); f.state.web = false;
    const unavailable = await f.fetchPlayUrlSingle('11', '1');
    assert.equal(unavailable.code, 'SOURCE_UNAVAILABLE');
    assert.deepEqual(Array.from(unavailable.sources, item => item.source), ['web', 'app', 'api']);
    assert.doesNotMatch(JSON.stringify(unavailable), /secret|signed\.invalid|private|前.*集|付费|会员/);
    assert.ok(unavailable.sources.every(item => item.code && item.message));
    f.state.app = true;
    assert.equal((await f.fetchPlayUrlSingle('11', '1')).source, 'app', 'failures must not be cached');
  }],
];
(async () => {
  let failed = 0;
  for (const [name, run] of checks) { try { await run(); console.log(`PASS ${name}`); } catch (error) { failed++; console.error(`FAIL ${name}: ${error.message}`); } }
  console.log(`${checks.length - failed}/${checks.length} source recovery checks passed`);
  process.exitCode = failed ? 1 : 0;
})();
