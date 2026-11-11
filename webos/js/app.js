/* 红果 webOS 客户端：分类 → 剧集 → 播放（连播 + 预取），遥控 D-pad 导航。 */
(function () {
  'use strict';
  var source = window.HongguoSource;
  var huangguo = window.HuangguoSource;
  var appChain = window.HongguoAppChain || null;
  var main = document.getElementById('main');
  var nav = document.getElementById('nav');
  var toastEl = document.getElementById('toast');
  var state = { category: source.CATEGORIES[0].slug, view: { name: 'home' }, settings: loadSettings() };

  function loadSettings() {
    try { return JSON.parse(localStorage.getItem('hongguo.settings') || '{}') || {}; } catch (e) { return {}; }
  }
  function saveSettings(patch) {
    state.settings = Object.assign({}, state.settings, patch);
    try { localStorage.setItem('hongguo.settings', JSON.stringify(state.settings)); } catch (e) { }
  }

  function toast(text) {
    toastEl.textContent = text; toastEl.hidden = false;
    clearTimeout(toast._t); toast._t = setTimeout(function () { toastEl.hidden = true; }, 3000);
  }
  function esc(value) {
    return String(value == null ? '' : value).replace(/[&<>"]/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c];
    });
  }
  function spinner(text) { return '<div class="state"><div class="spinner"></div>' + esc(text || '正在读取…') + '</div>'; }
  function errorState(message) {
    main.innerHTML = '<div class="state err">' + esc(message) + '<br><br><button id="retry" class="retry">重新加载</button></div>';
    var retry = document.getElementById('retry');
    if (retry) { retry.addEventListener('click', render); retry.focus(); }
  }

  // ===== 栏目 =====
  function buildNav() {
    nav.innerHTML = '';
    var categories = source.CATEGORIES.concat(huangguo ? huangguo.CATEGORIES : []);
    categories.forEach(function (entry) {
      var button = document.createElement('button');
      button.type = 'button'; button.textContent = entry.label; button.dataset.slug = entry.slug;
      if (entry.slug === state.category) button.classList.add('active');
      button.addEventListener('click', function () {
        if (state.category === entry.slug && state.view.name === 'home') return;
        state.category = entry.slug; state.view = { name: 'home' };
        Array.prototype.forEach.call(nav.children, function (n) { n.classList.toggle('active', n.dataset.slug === entry.slug); });
        render();
      });
      nav.appendChild(button);
    });
  }

  // ===== 首页 =====
  function render() {
    if (state.view.name === 'home') renderHome();
    else if (state.view.name === 'detail') renderDetail(state.view.seriesId);
  }
  function renderHome() {
    main.innerHTML = '<h1>' + esc(categoryLabel()) + '</h1><div id="body">' + spinner() + '</div>';
    var loader = huangguo && huangguo.isCategory(state.category)
      ? huangguo.browse(state.category.slice(3)) : source.browse(state.category);
    loader.then(function (data) {
      if (state.view.name !== 'home') return;
      var body = document.getElementById('body');
      if (!data.results.length) { body.innerHTML = '<div class="state">这个分类暂时没有内容</div>'; return; }
      body.innerHTML = '<div class="grid">' + data.results.map(function (item) {
        return '<button type="button" class="card" data-sid="' + item.seriesId + '">' +
          '<span class="art">' + (item.cover ? '<img src="' + esc(item.cover) + '" alt="" loading="lazy">' : '') +
          (item.episodeCount ? '<span class="eps">全' + item.episodeCount + '集</span>' : '') + '</span>' +
          '<strong>' + esc(item.title) + '</strong></button>';
      }).join('') + '</div>';
      Array.prototype.forEach.call(body.querySelectorAll('.card'), function (card) {
        card.addEventListener('click', function () {
          state.view = { name: 'detail', seriesId: card.dataset.sid };
          render();
        });
      });
      var first = body.querySelector('.card');
      if (first) first.focus();
    }).catch(function (error) { errorState(error.message || '读取失败'); });
  }
  function categoryLabel() {
    var categories = source.CATEGORIES.concat(huangguo ? huangguo.CATEGORIES : []);
    for (var i = 0; i < categories.length; i++) if (categories[i].slug === state.category) return categories[i].label;
    return '发现';
  }

  // ===== 详情 =====
  function renderDetail(seriesId) {
    main.innerHTML = '<div id="body">' + spinner('正在读取选集…') + '</div>';
    var loader = huangguo && huangguo.isSeries(seriesId) ? huangguo.episodes(seriesId) : source.episodes(seriesId);
    loader.then(function (data) {
      if (state.view.name !== 'detail' || state.view.seriesId !== seriesId) return;
      var body = document.getElementById('body');
      state.view.episodes = data;
      var gated = data.webAccessibleEpisodes !== null && data.webAccessibleEpisodes < data.total;
      body.innerHTML = '<div class="detail-head"><img id="detailCover" src="" alt="" hidden><div class="detail-info">' +
        '<h1>' + esc(data.title) + '</h1>' +
        '<p>全 ' + data.total + ' 集' + (gated ? ' · 官网开放前 ' + data.webAccessibleEpisodes + ' 集试看，其余需官方 App 通道' : '') + '</p>' +
        '</div></div>' +
        '<h2 class="episodes-title">选集</h2><div class="episodes">' +
        data.episodes.map(function (episode) {
          var dim = data.webAccessibleEpisodes !== null && !episode.webAvailable;
          return '<button type="button" data-vid="' + episode.vid + '"' + (dim ? ' class="dim" disabled' : '') + '>' + episode.index + '</button>';
        }).join('') + '</div>';
      Array.prototype.forEach.call(body.querySelectorAll('.episodes button:not([disabled])'), function (button) {
        button.addEventListener('click', function () { play(seriesId, button.dataset.vid, data.title, data.episodes); });
      });
      var first = body.querySelector('.episodes button:not([disabled])');
      if (first) first.focus();
    }).catch(function (error) { errorState(error.message || '读取失败'); });
  }

  // ===== 播放（含连播预取）=====
  var player = null;
  var session = null;
  function ensurePlayer() {
    if (player) return player;
    player = document.createElement('div');
    player.className = 'player';
    player.innerHTML = '<button type="button" class="back">◀ 返回</button><video controls autoplay playsinline></video>' +
      '<div class="player-bar"><button type="button" id="pPrev">上一集</button><button type="button" id="pToggle">暂停</button>' +
      '<button type="button" id="pNext">下一集</button><span id="pTime">00:00 / 00:00</span>' +
      '<span class="spacer"></span><button type="button" id="pAuto">连播 开</button></div>';
    document.body.appendChild(player);
    var video = player.querySelector('video');
    player.querySelector('.back').addEventListener('click', closePlayer);
    player.querySelector('#pPrev').addEventListener('click', function () { stepEpisode(-1); });
    player.querySelector('#pNext').addEventListener('click', function () { stepEpisode(1); });
    player.querySelector('#pToggle').addEventListener('click', function () {
      if (video.paused) { video.play(); player.querySelector('#pToggle').textContent = '暂停'; }
      else { video.pause(); player.querySelector('#pToggle').textContent = '播放'; }
    });
    player.querySelector('#pAuto').addEventListener('click', function () {
      var next = !(state.settings.autoplay !== false);
      saveSettings({ autoplay: next });
      player.querySelector('#pAuto').textContent = '连播 ' + (next ? '开' : '关');
    });
    video.addEventListener('timeupdate', function () {
      if (!video.duration) return;
      player.querySelector('#pTime').textContent = fmt(video.currentTime) + ' / ' + fmt(video.duration);
    });
    video.addEventListener('ended', function () {
      if (state.settings.autoplay === false) return;
      if (stepEpisode(1)) toast('正在播放下一集');
    });
    return player;
  }
  function fmt(seconds) {
    if (!isFinite(seconds) || seconds < 0) seconds = 0;
    var m = Math.floor(seconds / 60), s = Math.floor(seconds % 60);
    return (m < 10 ? '0' : '') + m + ':' + (s < 10 ? '0' : '') + s;
  }
  function play(seriesId, vid, title, episodes) {
    var box = ensurePlayer();
    var video = box.querySelector('video');
    session = { seriesId: seriesId, vid: vid, title: title, episodes: episodes || [], prefetched: null, prefetching: null };
    box.classList.add('open');
    box.querySelector('#pAuto').textContent = '连播 ' + (state.settings.autoplay !== false ? '开' : '关');
    video.removeAttribute('src'); video.load();
    var st = document.createElement('div'); st.className = 'state-overlay'; st.textContent = '正在准备本集…';
    box.appendChild(st);
    resolveEpisode(seriesId, vid).then(function (info) {
      st.remove();
      video.src = info.url;
      if (info.userAgent) video.setAttribute('data-ua', info.userAgent);
      video.play().catch(function () { });
      prefetchNext();
    }).catch(function (error) {
      st.remove();
      st = document.createElement('div'); st.className = 'state-overlay err';
      st.textContent = (error && error.message ? error.message : '本集暂不可播放');
      box.appendChild(st);
    });
  }
  function resolveEpisode(seriesId, vid) {
    if (huangguo && huangguo.isSeries(seriesId)) {
      return huangguo.playUrl(seriesId, vid).then(function (info) {
        return { url: info.url, userAgent: '', referer: '' };
      });
    }
    var options = { appChain: appChain, proxy: state.settings.proxy };
    return source.playUrl(seriesId, vid, options).then(function (info) {
      return { url: info.url, userAgent: info.userAgent || '', referer: info.referer || '' };
    });
  }
  /** 后台预取下一集：连播切换时零等待。 */
  function prefetchNext() {
    if (!session || !session.episodes || session.episodes.length < 2) return;
    var at = indexOfEpisode(session.vid);
    if (at < 0 || at >= session.episodes.length - 1) return;
    var next = session.episodes[at + 1];
    if (session.prefetching && String(session.prefetching) === String(next.vid)) return;
    session.prefetching = next.vid;
    resolveEpisode(session.seriesId, next.vid).then(function (info) {
      if (session && String(session.prefetching) === String(next.vid)) { session.prefetched = { vid: next.vid, info: info }; session.prefetching = null; }
    }).catch(function () { if (session) session.prefetching = null; });
  }
  function indexOfEpisode(vid) {
    for (var i = 0; i < (session.episodes || []).length; i++) if (String(session.episodes[i].vid) === String(vid)) return i;
    return -1;
  }
  function stepEpisode(step) {
    if (!session || !session.episodes) return false;
    var at = indexOfEpisode(session.vid);
    var target = at + step;
    if (target < 0 || target >= session.episodes.length) return false;
    var episode = session.episodes[target];
    session.vid = episode.vid;
    if (session.prefetched && String(session.prefetched.vid) === String(episode.vid)) {
      var info = session.prefetched.info; session.prefetched = null;
      applyPlayInfo(info);
    } else {
      resolveEpisode(session.seriesId, episode.vid).then(applyPlayInfo).catch(function (error) { toast(error.message || '下一集准备失败'); });
    }
    return true;
  }
  function applyPlayInfo(info) {
    if (!player || !info) return;
    var video = player.querySelector('video');
    video.src = info.url;
    video.play().catch(function () { });
    prefetchNext();
  }
  function closePlayer() {
    if (!player) return;
    var video = player.querySelector('video');
    video.pause(); video.removeAttribute('src'); video.load();
    player.classList.remove('open');
    var back = document.querySelector('.episodes button:not([disabled])');
    if (back) back.focus();
  }

  // ===== 遥控键 =====
  document.addEventListener('keydown', function (event) {
    if (event.key === 'GoBack' || event.keyCode === 461 || event.key === 'Escape' || event.key === 'Backspace') {
      if (player && player.classList.contains('open')) { event.preventDefault(); closePlayer(); return; }
      if (state.view.name === 'detail') { event.preventDefault(); state.view = { name: 'home' }; render(); }
    }
  });

  buildNav();
  render();
})();
