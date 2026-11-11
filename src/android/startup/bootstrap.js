// This entry and all selected standard polyfills must parse before the modern
// application. Never use eval/Function or relax the packaged self-only CSP.
import 'core-js/modules/es.global-this.js';
import 'core-js/modules/es.array.flat.js';
import 'core-js/modules/es.array.flat-map.js';
import 'core-js/modules/es.string.match-all.js';

(function () {
  'use strict';
  var phase = 'loading';
  var application = null;
  var tokenMatch = /(?:^|[?&])startup=([0-9a-f-]{36})(?:&|$)/i.exec(window.location.search);
  var token = tokenMatch ? tokenMatch[1] : '';
  var boot = document.querySelector('script[data-hongguo-boot]');
  var messages = {
    bootstrap_error: '启动组件准备失败，请重新打开应用。',
    script_error: '应用页面加载失败，请重新打开应用。',
    unsupported_webview: '这台设备的 Android System WebView 版本较旧。请更新系统 WebView（需要支持 BigInt 的 Chromium 67 或更新版本）后重试。'
  };
  function notify(method, code) {
    try {
      if (token && window.AndroidStartup && typeof window.AndroidStartup[method] === 'function') {
        if (method === 'ready') window.AndroidStartup.ready(token);
        else window.AndroidStartup.failed(token, code);
      }
    } catch (ignored) { /* The visible page remains actionable without native. */ }
  }
  function failed(code) {
    if (phase === 'failed') return;
    if (!Object.prototype.hasOwnProperty.call(messages, code)) code = 'script_error';
    phase = 'failed';
    var root = document.getElementById('root');
    if (root) {
      while (root.firstChild) root.removeChild(root.firstChild);
      var panel = document.createElement('main');
      panel.className = 'startup-panel'; panel.setAttribute('role', 'alert');
      var title = document.createElement('h1'); title.textContent = '红果短剧启动未完成';
      var message = document.createElement('p'); message.textContent = messages[code];
      var retry = document.createElement('button'); retry.type = 'button'; retry.textContent = '重新打开';
      retry.addEventListener('click', function () { window.location.reload(); });
      panel.appendChild(title); panel.appendChild(message); panel.appendChild(retry); root.appendChild(panel);
      retry.focus();
    }
    notify('failed', code);
  }
  window.HongguoStartup = {
    ready: function () {
      if (phase !== 'loading') return;
      phase = 'ready'; notify('ready');
    },
    failed: failed
  };
  window.addEventListener('error', function (event) {
    if (phase !== 'loading') return;
    if (event.target === application || event.target === window) failed('script_error');
  }, true);
  window.addEventListener('unhandledrejection', function () { if (phase === 'loading') failed('script_error'); });
  try {
    if (typeof BigInt !== 'function' || typeof Promise !== 'function' || typeof Map !== 'function' || typeof Set !== 'function' || typeof Symbol !== 'function' || typeof Symbol.iterator === 'undefined' || !window.crypto || typeof window.crypto.getRandomValues !== 'function') {
      failed('unsupported_webview'); return;
    }
    var source = boot && boot.getAttribute('data-app-src');
    // Source comes solely from Vite's packaged entry, never URL/query input.
    if (!source || /^(?:[a-z][a-z0-9+.-]*:|\/\/)/i.test(source)) { failed('bootstrap_error'); return; }
    application = document.createElement('script'); application.type = 'module'; application.src = source;
    application.addEventListener('error', function () { failed('script_error'); });
    document.head.appendChild(application);
  } catch (ignored) { failed('bootstrap_error'); }
}());
