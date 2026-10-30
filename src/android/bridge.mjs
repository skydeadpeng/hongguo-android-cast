/** Android-only JSON RPC. No Electron or browser networking fallback. */
const METHODS = new Set([
  'getInfo', 'httpGet', 'cancelHttp', 'getLibrary', 'getSeriesHistory', 'toggleFavorite', 'play',
  'getAccount', 'officialSendCode', 'officialLoginSMS', 'officialVerifyMFA', 'officialCancelLogin', 'officialLogout', 'officialSync', 'officialImportGuest',
  'download', 'getDownloads', 'playDownload', 'deleteDownload', 'openExternal',
  'getPlaybackCache', 'clearPlaybackCache',
  'appRequest', 'appRegisteredModel', 'appResolveResult', 'getPlaybackOptions', 'setPlaybackOptions',
  'getSearchHistory', 'addSearchHistory', 'removeSearchHistory', 'clearSearchHistory', 'searchQuery',
]);
const pending = new Map();
let sequence = 0;

const PLAYBACK_PREFERENCES = 'hongguo.playbackPreferences';

/** Defaults apply to both streamed episodes and manually downloaded episodes. */
export function getPlaybackPreferences() {
  let saved = {};
  try { saved = JSON.parse(globalThis.localStorage?.getItem(PLAYBACK_PREFERENCES) || '{}') || {}; } catch { /* Older/invalid preferences use defaults. */ }
  return { autoplay: saved.autoplay !== false, cleanupPlaybackCache: saved.cleanupPlaybackCache !== false, keepPreviousEpisodes: 1 };
}

export function setPlaybackPreference(key, value) {
  const preferences = getPlaybackPreferences();
  if (key !== 'autoplay' && key !== 'cleanupPlaybackCache') return preferences;
  preferences[key] = !!value;
  try { globalThis.localStorage?.setItem(PLAYBACK_PREFERENCES, JSON.stringify(preferences)); } catch { /* Current screen can still use the selected value. */ }
  return preferences;
}

// Initial play can include a bounded web preflight plus a fresh App lookup.
export function nativeCall(method, params = {}, timeoutMs = method === 'download' ? 180000 : method === 'play' ? 75000 : 45000, { signal } = {}) {
  if (!METHODS.has(method)) return Promise.reject(new Error('未支持的设备操作'));
  if (!globalThis.window?.AndroidBridge?.postMessage) {
    return Promise.reject(new Error('请在安卓安装包中打开；网页预览未连接设备功能。'));
  }
  const cancellable = method === 'httpGet' || method === 'appRegisteredModel';
  if (cancellable && signal?.aborted) return Promise.reject(Object.assign(new Error('页面请求已取消'), { code: 'ERR_CANCELED' }));
  const id = `android-${Date.now()}-${++sequence}`;
  return new Promise((resolve, reject) => {
    let timer;
    const cleanup = () => {
      clearTimeout(timer);
      if (cancellable) signal?.removeEventListener('abort', abort);
      pending.delete(id);
    };
    const cancelNative = () => {
      // Cancellation must not wait for a worker or create another pending RPC.
      try { window.AndroidBridge.postMessage(JSON.stringify({ id: `cancel-${Date.now()}-${++sequence}`, method: 'cancelHttp', params: { requestId: id } })); } catch { /* Native deadline still bounds the request. */ }
    };
    const abort = () => {
      if (!pending.has(id)) return;
      cleanup(); cancelNative();
      reject(Object.assign(new Error('页面请求已取消'), { code: 'ERR_CANCELED' }));
    };
    timer = setTimeout(() => {
      if (!pending.has(id)) return;
      cleanup();
      if (cancellable) cancelNative();
      const error = new Error('设备操作超时，请检查网络后重试。');
      if (method === 'appRequest' || cancellable) error.code = 'ETIMEDOUT';
      reject(error);
    }, timeoutMs);
    pending.set(id, { resolve, reject, cleanup });
    if (cancellable) {
      signal?.addEventListener('abort', abort, { once: true });
      if (signal?.aborted) { abort(); return; }
    }
    try {
      window.AndroidBridge.postMessage(JSON.stringify({ id, method, params }));
    } catch (error) {
      cleanup();
      reject(error instanceof Error ? error : new Error(String(error)));
    }
  });
}

if (typeof window !== 'undefined') {
  window.__androidResponse = (message) => {
    if (!message || typeof message !== 'object' || typeof message.id !== 'string') return;
    const request = pending.get(message.id);
    if (!request) return;
    request.cleanup();
    if (message.error != null) {
      const error = new Error(String(message.error));
      if (['ETIMEDOUT', 'ERR_CANCELED', 'INVALID_INPUT', 'APP_EMPTY_RESPONSE', 'APP_INVALID_RESPONSE'].includes(message.errorCode)) error.code = message.errorCode;
      request.reject(error);
    }
    else request.resolve(message.result);
  };
  window.__androidResume = () => window.dispatchEvent(new Event('androidresume'));
  window.__androidAccountChanged = metadata => {
    const owner = metadata && typeof metadata.owner === 'string' && /^(guest|official:[a-f0-9]{64})$/.test(metadata.owner) ? metadata.owner : undefined;
    window.dispatchEvent(new CustomEvent('androidaccountchanged', { detail: { owner } }));
  };
}

export async function requestHtml(url, { timeoutMs = 25000, deadlineMs, signal } = {}) {
  if (!Number.isSafeInteger(timeoutMs) || timeoutMs < 1 || timeoutMs > 25000 ||
      deadlineMs !== undefined && (!Number.isSafeInteger(deadlineMs) || deadlineMs < 1)) {
    throw Object.assign(new Error('页面请求等待期限无效'), { code: 'INVALID_INPUT' });
  }
  if (signal?.aborted) throw Object.assign(new Error('页面请求已取消'), { code: 'ERR_CANCELED' });
  const now = Date.now();
  const deadline = Math.min(deadlineMs ?? now + timeoutMs, now + timeoutMs);
  const remaining = deadline - now;
  if (remaining <= 0) throw Object.assign(new Error('页面请求超时'), { code: 'ETIMEDOUT' });
  const response = await nativeCall('httpGet', { url, timeoutMs, deadlineMs: deadline }, remaining, { signal });
  if (!response || !Number.isSafeInteger(response.status) || response.status < 200 || response.status >= 300) {
    throw Object.assign(new Error(`请求失败（HTTP ${response?.status ?? '未知'}），请稍后重试。`), { code: 'HTTP_ERROR', status: response?.status });
  }
  if (typeof response.body !== 'string') throw Object.assign(new Error('服务器返回了无效页面。'), { code: 'INVALID_RESPONSE' });
  return response.body;
}
