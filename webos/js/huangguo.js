/* 黄果漫剧（huangguoai.com 官方站）只读源：官方 API 已全开 CORS，浏览器直连。
 * 剧集/分集编号统一加 "hg:" 前缀，与红果官网编号空间隔离。
 * 官方对个别内容只下发预览片段：清单实际时长不足声明八成即拒绝下发。 */
(function () {
  'use strict';
  var ORIGIN = 'https://huangguoai.com';

  function get(path) {
    return fetch(ORIGIN + path, { headers: { 'Accept': 'application/json' } }).then(function (response) {
      if (!response.ok) throw new Error('黄果来源暂不可用，请稍后重试');
      return response.json();
    }).then(function (body) {
      if (!body || body.status !== 1 || !body.data || typeof body.data !== 'object') {
        throw new Error((body && body.msg) || '黄果返回数据无效');
      }
      return body.data;
    });
  }
  function text(value, limit) {
    value = String(value == null ? '' : value).replace(/<[^>]*>/g, '').trim();
    return value.length > limit ? value.slice(0, limit) : value;
  }
  function item(row) {
    var id = Number(row.id), title = text(row.title, 200);
    if (!(id > 0) || !title) return null;
    var tags = [];
    (Array.isArray(row.tags) ? row.tags : []).some(function (tag, index) {
      if (index >= 4) return true;
      var label = typeof tag === 'object' && tag ? text(tag.name, 80) : typeof tag === 'string' ? tag.trim() : '';
      if (label && label.length <= 80) tags.push(label);
      return false;
    });
    return { seriesId: 'hg:' + id, title: title, cover: /^https:\/\//.test(String(row.cover || '')) ? row.cover : '',
      episodeCount: Math.max(0, Number(row.episode_count) || 0), tags: tags };
  }
  function toPage(data, pageNumber) {
    var seen = {}, results = [];
    (Array.isArray(data.items) ? data.items : []).forEach(function (row) {
      var clean = row && typeof row === 'object' ? item(row) : null;
      if (!clean || seen[clean.seriesId]) return;
      seen[clean.seriesId] = true; results.push(clean);
    });
    var pages = data.pagination ? Number(data.pagination.pages) || 0 : 0;
    return { results: results, page: pageNumber, hasMore: pages > pageNumber && results.length > 0 };
  }

  window.HuangguoSource = {
    ORIGIN: ORIGIN,
    CATEGORIES: [
      { slug: 'hg:ai-manju', label: '黄果·漫剧' },
      { slug: 'hg:ai-duanju', label: '黄果·短剧' }
    ],
    isSeries: function (seriesId) { return String(seriesId || '').indexOf('hg:') === 0; },
    isCategory: function (slug) { return String(slug || '').indexOf('hg:') === 0; },
    browse: function (slug, page) {
      page = Math.max(1, Number(page) || 1);
      if (!/^(ai-manju|ai-duanju)$/.test(slug)) return Promise.reject(new Error('黄果分类无效'));
      return get('/api/videos/category/' + slug + '?page=' + page + '&size=30&sort=latest').then(function (data) {
        return toPage(data, page);
      });
    },
    search: function (keyword, page) {
      keyword = text(keyword, 100);
      if (!keyword) return Promise.reject(new Error('请输入搜索关键词'));
      page = Math.max(1, Number(page) || 1);
      return get('/api/search?q=' + encodeURIComponent(keyword) + '&page=' + page + '&size=30').then(function (data) {
        return toPage(data, page);
      });
    },
    episodes: function (seriesId) {
      var id = String(seriesId || '').slice(3);
      if (!/^\d{1,8}$/.test(id)) return Promise.reject(new Error('黄果剧集编号无效'));
      return get('/api/videos/' + id).then(function (data) {
        if (String(data.id) !== id || !Array.isArray(data.episodes)) throw new Error('黄果返回的剧集与请求不一致');
        var episodes = [];
        data.episodes.forEach(function (episode) {
          var ep = Number(episode && episode.ep_num);
          if (!(ep > 0) || ep > 9999) return;
          episodes.push({ vid: 'hg:' + id + ':' + ep, index: ep, webAvailable: Number(episode.status) === 1 });
        });
        if (!episodes.length) throw new Error('黄果分集列表为空');
        return { title: text(data.title, 200) || '黄果漫剧', cover: /^https:\/\//.test(String(data.cover || '')) ? data.cover : '',
          description: text(data.description, 4000), total: episodes.length, episodes: episodes,
          webAccessibleEpisodes: episodes.every(function (e) { return e.webAvailable; }) ? episodes.length : episodes.filter(function (e) { return e.webAvailable; }).length };
      });
    },
    playUrl: function (seriesId, vid) {
      var id = String(seriesId || '').slice(3), ep = String(vid || '').split(':')[2];
      if (!/^\d{1,8}$/.test(id) || !/^\d{1,4}$/.test(ep || '')) return Promise.reject(new Error('黄果分集编号无效'));
      return get('/api/videos/' + id + '/play?ep=' + ep).then(function (data) {
        var url = String(data.video_url || '');
        if (!/^https:\/\//.test(url)) throw new Error('黄果未提供有效播放地址');
        var declared = Number(data.duration) || 0;
        if (declared <= 0 || !/\.m3u8/i.test(url)) return { url: url };
        // 预览片段防御：拉清单核对实际时长（清单很小，失败不拦截播放）。
        return fetch(url, { headers: { 'Accept': '*/*' } }).then(function (response) {
          return response.ok ? response.text() : '';
        }).catch(function () { return ''; }).then(function (body) {
          if (body && body.indexOf('#EXTM3U') === 0 && body.indexOf('#EXT-X-STREAM-INF') < 0) {
            var total = 0, match, re = /#EXTINF:([0-9.]+)/g;
            while ((match = re.exec(body))) total += Number(match[1]);
            if (total > 0 && total < declared * 0.8) {
              throw new Error('该集只下发了预览片段（约 ' + Math.round(total) + ' 秒），暂无法完整播放');
            }
          }
          return { url: url };
        });
      });
    }
  };
})();
