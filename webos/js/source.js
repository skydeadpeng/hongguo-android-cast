/**
 * 红果 webOS 客户端取源链 —— 与桌面版 hongguo.js/catalog.js 同源解析逻辑。
 * 纯 Web API（fetch + 字符串解析），零依赖；webOS 与 Node 测试共用本文件。
 *
 * 首发只接官网 web 链路（目录/剧集/明文 MP4 直链），与桌面 2.3.1 恢复的
 * fast path 完全一致；App 加密链路（签名模型 + spade 解密）留待后续版本。
 */
(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory();
  else root.HongguoSource = factory();
})(typeof self !== 'undefined' ? self : this, function () {
  'use strict';

  var SITE = 'https://hongguoduanju.com';
  var WEB_UA = 'Mozilla/5.0 (Web0S; TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0 Safari/537.36 HongguoWebOS/0.1';
  var CATEGORIES = [
    { slug: 'real-drama', label: '真人剧' },
    { slug: 'comic-drama', label: '漫剧' },
    { slug: 'ai-drama', label: 'AI剧' },
  ];

  function fetchHtml(path, timeoutMs) {
    var controller = typeof AbortController === 'function' ? new AbortController() : null;
    var timer = controller ? setTimeout(function () { controller.abort(); }, timeoutMs || 12000) : null;
    return fetch(SITE + path, { headers: { 'Accept': 'text/html,*/*' }, signal: controller && controller.signal })
      .then(function (response) {
        if (timer) clearTimeout(timer);
        if (!response.ok) throw new Error('请求失败（HTTP ' + response.status + '）');
        return response.text();
      }, function (error) {
        if (timer) clearTimeout(timer);
        throw error && error.name === 'AbortError' ? new Error('请求超时') : error;
      });
  }

  /** 与桌面 catalog.js 逐字同源：扫描平衡括号提取 window._ROUTER_DATA。 */
  function readRouterData(html) {
    var match = String(html).match(/(?:window\.)?_ROUTER_DATA\s*=\s*(\{)/);
    if (!match) throw new Error('来源页面未返回目录数据');
    var start = match.index + match[0].length - 1;
    var depth = 0, quoted = false, escaped = false;
    for (var i = start; i < html.length; i++) {
      var char = html[i];
      if (quoted) {
        if (escaped) escaped = false;
        else if (char === '\\') escaped = true;
        else if (char === '"') quoted = false;
      } else if (char === '"') quoted = true;
      else if (char === '{') depth++;
      else if (char === '}' && --depth === 0) return JSON.parse(html.slice(start, i + 1));
    }
    throw new Error('来源目录数据不完整');
  }

  /** 分类目录：/category/{slug} 的 recommendList。 */
  function parseCatalog(html, category) {
    var router = readRouterData(html);
    var data = null;
    var values = router.loaderData || {};
    for (var key in values) {
      if (values[key] && values[key].categoryRoute && values[key].categoryRoute.contentType === category) { data = values[key]; break; }
    }
    if (!data || data.isSuccess === false || !Array.isArray(data.recommendList)) throw new Error('来源目录暂不可用');
    var seen = {}, results = [];
    for (var i = 0; i < data.recommendList.length; i++) {
      var item = data.recommendList[i];
      var id = String(item && item.series_id || '');
      if (!/^\d{6,}$/.test(id) || typeof item.series_name !== 'string' || !item.series_name.trim() || seen[id]) continue;
      seen[id] = true;
      results.push({
        seriesId: id, title: item.series_name.trim(), cover: item.series_cover || '',
        episodeCount: Number(item.episode_cnt) || 0,
        tags: Array.isArray(item.tags) ? item.tags.filter(function (t) { return typeof t === 'string'; }).slice(0, 4) : [],
      });
    }
    return { results: results };
  }

  function browse(category) {
    return fetchHtml('/category/' + category).then(function (html) { return parseCatalog(html, category); });
  }

  /** 剧集列表：/player/{sid} 内嵌 vid_list；accessible_episode_cnt 决定 web 可播范围。 */
  function parseEpisodes(html, seriesId) {
    var match = String(html).match(/"vid_list"\s*:\s*\[([^\]]*)\]/);
    if (!match) throw new Error('来源未返回分集列表');
    var raw = match[1].match(/\d{5,}/g) || [];
    var seen = {}, vids = [];
    for (var i = 0; i < raw.length; i++) if (!seen[raw[i]] && seen[raw[i]] !== 0) { seen[raw[i]] = 1; vids.push(raw[i]); }
    if (!vids.length) throw new Error('来源分集列表为空');
    var titleMatch = String(html).match(/"series_name"\s*:\s*"((?:[^"\\]|\\.)*)"/);
    var title = titleMatch ? titleMatch[1].replace(/\\u([0-9a-fA-F]{4})/g, function (_, h) { return String.fromCharCode(parseInt(h, 16)); }).replace(/\\"/g, '"').replace(/\\\\/g, '\\') : '未命名短剧';
    var accessible = String(html).match(/"accessible_episode_cnt"\s*:\s*(\d+)/);
    var webAccessible = accessible ? Math.min(Number(accessible[1]), vids.length) : null;
    var episodes = [];
    for (var at = 0; at < vids.length; at++) {
      episodes.push({ vid: vids[at], index: at + 1, webAvailable: webAccessible === null ? null : at < webAccessible });
    }
    return { seriesId: String(seriesId), title: title, total: episodes.length, webAccessibleEpisodes: webAccessible, episodes: episodes };
  }

  function episodes(seriesId) {
    if (!/^\d{5,30}$/.test(String(seriesId))) return Promise.reject(new Error('剧集编号无效'));
    return fetchHtml('/player/' + encodeURIComponent(seriesId)).then(function (html) { return parseEpisodes(html, seriesId); });
  }

  /**
   * 单集明文 MP4 直链：/player/{sid}/{vid} 的 ld+json VideoObject.contentUrl。
   * 与桌面 2.3.1 一致：路由数据先钉住本集（vid/itemId/seriesId 三重校验），
   * ld+json url 允许官网新版的系列级 /player/{sid} 形态。
   */
  function parsePlayUrl(html, seriesId, vid) {
    var router = readRouterData(html);
    var data = router.loaderData && router.loaderData['player_(series_id)/(vid)/page'];
    if (!data) throw new Error('来源未返回本集数据');
    var actualSid = String(data.series_id || '');
    if (data.isSuccess === false || String(data.vid) !== String(vid) || !/^\d+$/.test(actualSid) ||
        actualSid !== String(seriesId) ||
        String(data.seriesDetail && data.seriesDetail.series_id) !== actualSid ||
        !(data.publicVideoSeo) || data.publicVideoSeo.available !== true ||
        String(data.publicVideoSeo.itemId) !== String(vid) ||
        String(data.publicVideoSeo.seriesId) !== actualSid) {
      throw new Error('官网未开放本集的网页播放');
    }
    var scripts = String(html).match(/<script\b[^>]*type\s*=\s*["']application\/ld\+json["'][^>]*>([\s\S]*?)<\/script\s*>/gi) || [];
    for (var i = 0; i < scripts.length; i++) {
      var body = scripts[i].replace(/^<script\b[^>]*>/i, '').replace(/<\/script\s*>$/i, '');
      var video;
      try { video = JSON.parse(body); } catch (_) { continue; }
      if (!video || video['@type'] !== 'VideoObject') continue;
      if (!bindsPlayer(video.url) || (video['@id'] && !bindsPlayer(video['@id']))) continue;
      var contentUrl = typeof video.contentUrl === 'string' ? video.contentUrl : '';
      if (!/^https?:\/\//.test(contentUrl)) continue;
      if (!publicMediaUrl(contentUrl)) continue;
      return { vid: String(vid), seriesId: actualSid, url: contentUrl, source: 'web', quality: '360p', items: [] };
    }
    throw new Error('本集未提供网页直链');
    /** 与桌面 web-media-guard 同语义：仅公网 http(s)，拒绝内网/本机/带凭据地址。 */
    function publicMediaUrl(value) {
      try {
        var target = new URL(String(value));
        if (target.protocol !== 'https:' && target.protocol !== 'http:') return false;
        if (target.username || target.password) return false;
        var host = target.hostname.toLowerCase();
        if (/^\d+\.\d+\.\d+\.\d+$/.test(host)) {
          var parts = host.split('.').map(Number);
          if (parts[0] === 10 || parts[0] === 127 || (parts[0] === 192 && parts[1] === 168) ||
              (parts[0] === 172 && parts[1] >= 16 && parts[1] <= 31) || parts[0] === 0) return false;
          return true;
        }
        return host.indexOf('.') >= 0 && !/(^|\.)(localhost|local|internal|lan|home)$/.test(host);
      } catch (_) { return false; }
    }
    function bindsPlayer(value) {
      try {
        var target = new URL(String(value));
        return target.origin === SITE && !target.username && !target.password &&
          (target.pathname === '/player/' + actualSid + '/' + String(vid) || target.pathname === '/player/' + actualSid);
      } catch (_) { return false; }
    }
  }

  /**
   * 单集播放地址：优先官方加密链（配置了中转时可得完整正片），
   * 否则回落官网明文直链（仅前几集开放试看）。
   */
  function playUrl(seriesId, vid, options) {
    var settings = options || {};
    var appChain = settings.appChain || null;
    var chain = appChain && appChain.resolveEpisode
      ? appChain.resolveEpisode(String(vid), { proxy: settings.proxy })
      : Promise.resolve(null);
    return chain.then(function (items) {
      if (items && items.length) {
        var best = items[0];
        return { vid: String(vid), seriesId: String(seriesId), url: best.url, source: 'app', quality: best.quality,
          items: items, userAgent: best.userAgent || '', referer: best.referer || '' };
      }
      return fetchHtml('/player/' + encodeURIComponent(seriesId) + '/' + encodeURIComponent(vid), 10000).then(function (html) {
        return parsePlayUrl(html, seriesId, vid);
      });
    });
  }

  return {
    SITE: SITE, CATEGORIES: CATEGORIES, WEB_UA: WEB_UA,
    readRouterData: readRouterData, parseCatalog: parseCatalog, browse: browse,
    parseEpisodes: parseEpisodes, episodes: episodes,
    parsePlayUrl: parsePlayUrl, playUrl: playUrl,
  };
});
