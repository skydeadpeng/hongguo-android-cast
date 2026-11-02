/* 红果 webOS 客户端 UI：分类 → 剧集 → 播放。遥控 D-pad（方向键 + Enter）+ 返回键。 */
(function () {
  'use strict';
  var source = window.HongguoSource;
  var main = document.getElementById('main');
  var nav = document.getElementById('nav');
  var toastEl = document.getElementById('toast');
  var state = { category: source.CATEGORIES[0].slug, view: { name: 'home' } };

  function toast(text) {
    toastEl.textContent = text; toastEl.hidden = false;
    clearTimeout(toast._t); toast._t = setTimeout(function () { toastEl.hidden = true; }, 3200);
  }
  function esc(value) {
    return String(value == null ? '' : value).replace(/[&<>"]/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c];
    });
  }
  function spinner(text) {
    return '<div class="state"><div class="spinner"></div>' + esc(text || '正在读取…') + '</div>';
  }
  function errorState(message) {
    main.innerHTML = '<div class="state err">' + esc(message) + '<br><br><button id="retry" class="chips"><span>重试</span></button></div>';
    var retry = document.getElementById('retry');
    if (retry) { retry.className = ''; retry.style.cssText = 'padding:10px 26px;border:1px solid #ef5a45;border-radius:999px;color:#ef5a45'; retry.addEventListener('click', render); retry.focus(); }
  }

  // ===== 栏目 =====
  function buildNav() {
    nav.innerHTML = '';
    source.CATEGORIES.forEach(function (entry) {
      var button = document.createElement('button');
      button.type = 'button'; button.textContent = entry.label; button.dataset.slug = entry.slug;
      if (entry.slug === state.category) button.classList.add('active');
      button.addEventListener('click', function () {
        if (state.category === entry.slug && state.view.name === 'home') return;
        state.category = entry.slug; state.view = { name: 'home' };
        [].forEach.call(nav.children, function (n) { n.classList.toggle('active', n.dataset.slug === entry.slug); });
        render();
      });
      nav.appendChild(button);
    });
  }

  // ===== 首页网格 =====
  function render() {
    if (state.view.name === 'home') renderHome();
    else if (state.view.name === 'detail') renderDetail(state.view.seriesId);
  }
  function renderHome() {
    main.innerHTML = '<h1>' + esc(navLabel()) + '</h1><div id="body">' + spinner() + '</div>';
    source.browse(state.category).then(function (data) {
      if (state.view.name !== 'home') return;
      var body = document.getElementById('body');
      if (!data.results.length) { body.innerHTML = '<div class="state">这个分类暂时没有内容</div>'; return; }
      body.innerHTML = '<div class="chips" id="cats"></div><div class="grid">' +
        data.results.map(function (item) {
          return '<button type="button" class="card" data-sid="' + item.seriesId + '">' +
            '<span class="art">' + (item.cover ? '<img src="' + esc(item.cover) + '" alt="" loading="lazy">' : '') +
            (item.episodeCount ? '<span class="eps">全' + item.episodeCount + '集</span>' : '') + '</span>' +
            '<strong>' + esc(item.title) + '</strong></button>';
        }).join('') + '</div>';
      [].forEach.call(body.querySelectorAll('.card'), function (card) {
        card.addEventListener('click', function () {
          state.view = { name: 'detail', seriesId: card.dataset.sid };
          render();
        });
      });
      var first = body.querySelector('.card');
      if (first) first.focus();
    }).catch(function (error) { errorState(error.message || '读取失败'); });
  }
  function navLabel() {
    for (var i = 0; i < source.CATEGORIES.length; i++) if (source.CATEGORIES[i].slug === state.category) return source.CATEGORIES[i].label;
    return '发现';
  }

  // ===== 详情 =====
  function renderDetail(seriesId) {
    main.innerHTML = '<div id="body">' + spinner() + '</div>';
    source.episodes(seriesId).then(function (data) {
      if (state.view.name !== 'detail' || state.view.seriesId !== seriesId) return;
      var body = document.getElementById('body');
      state.view.episodes = data;
      var gated = data.webAccessibleEpisodes !== null && data.webAccessibleEpisodes < data.total;
      body.innerHTML = '<div class="detail-head"><img src="" alt="" hidden><div class="detail-info">' +
        '<h1>' + esc(data.title) + '</h1>' +
        '<p>全 ' + data.total + ' 集' + (gated ? ' · 官网开放前 ' + data.webAccessibleEpisodes + ' 集，其余请在官方 App 观看' : '') + '</p></div></div>' +
        '<h1 style="font-size:20px">选集</h1><div class="episodes">' +
        data.episodes.map(function (episode) {
          var dim = data.webAccessibleEpisodes !== null && !episode.webAvailable;
          return '<button type="button" data-vid="' + episode.vid + '"' + (dim ? ' class="dim" disabled' : '') + '>' + episode.index + '</button>';
        }).join('') + '</div>';
      [].forEach.call(body.querySelectorAll('.episodes button:not([disabled])'), function (button) {
        button.addEventListener('click', function () { play(seriesId, button.dataset.vid, data.title); });
      });
      var first = body.querySelector('.episodes button:not([disabled])');
      if (first) first.focus();
    }).catch(function (error) { errorState(error.message || '读取失败'); });
  }

  // ===== 播放 =====
  var player = null;
  function play(seriesId, vid, title) {
    toast('正在解析本集片源…');
    source.playUrl(seriesId, vid).then(function (info) {
      if (!player) {
        player = document.createElement('div');
        player.className = 'player';
        player.innerHTML = '<button type="button" class="back">◀ 返回</button><video controls autoplay></video>';
        document.body.appendChild(player);
        player.querySelector('.back').addEventListener('click', closePlayer);
        player.querySelector('video').addEventListener('error', function () {
          toast('本集播放失败，请稍后重试'); closePlayer();
        });
      }
      player.querySelector('video').src = info.url;
      player.classList.add('open');
      player.dataset.vid = vid;
      player.querySelector('video').focus();
    }).catch(function (error) { toast(error.message || '解析失败'); });
  }
  function closePlayer() {
    if (!player) return;
    var video = player.querySelector('video');
    video.pause(); video.removeAttribute('src'); video.load();
    player.classList.remove('open');
    var target = main.querySelector('.episodes button[data-vid="' + player.dataset.vid + '"]') || main.querySelector('.card');
    if (target) target.focus();
  }

  // ===== 遥控键 =====
  document.addEventListener('keydown', function (event) {
    if (event.key === 'GoBack' || event.keyCode === 461 || event.key === 'Escape' || event.key === 'Backspace') {
      if (player && player.classList.contains('open')) { event.preventDefault(); closePlayer(); return; }
      if (state.view.name === 'detail') { event.preventDefault(); state.view = { name: 'home' }; render(); }
    }
  });
  // webOS 平台返回键（webOSDev/LunaSystemFont 环境注入的 palm back）
  if (window.webOS && window.webOS.event && typeof window.webOS.event.addEventListener === 'function') {
    try { window.webOS.event.addEventListener('application', function (event) { if (event.key === 'back') { closePlayer(); if (state.view.name === 'detail') { state.view = { name: 'home' }; render(); } } }); } catch (_) { }
  }

  buildNav();
  render();
})();
