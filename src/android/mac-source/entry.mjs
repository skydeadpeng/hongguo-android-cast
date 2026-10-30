import desktopAppSource from '../../native/app-source.js';
import desktopQuality from '../../native/download-quality.js';
import { setNativeCall } from './axios-native.mjs';
const TRANSIENT_LOOKUP_CODES = new Set(['ENOTFOUND', 'EAI_AGAIN', 'ETIMEDOUT', 'ECONNABORTED', 'ECONNRESET', 'ECONNREFUSED', 'EHOSTUNREACH', 'ENETUNREACH', 'EPIPE']);
const RESPONSE_LOOKUP_CODES = new Set(['APP_EMPTY_RESPONSE', 'APP_INVALID_RESPONSE']);
let registeredModelCall;
const cancelledLookup = () => Object.assign(new Error('App 分集取源已取消'), { code: 'ERR_CANCELED' });

/** Protocol, signatures, Spade parsing and quality selection use the shared Mac module. */
export async function fetchAppPlayUrl(vid, options = {}) {
  const bounded = options.playbackCeiling !== undefined || options.playbackFloor !== undefined;
  const quality = bounded ? options.quality ?? 'auto' : desktopQuality.normalizeDownloadQuality(options.quality);
  if (bounded && !['auto', '1080p', '720p', '480p', '360p'].includes(quality)) throw new Error('播放清晰度无效');
  const ceiling = bounded && options.playbackCeiling !== undefined ? options.playbackCeiling : 0;
  const floor = bounded ? options.playbackFloor === undefined ? (quality === 'auto' ? 0 : Number.parseInt(quality, 10)) : options.playbackFloor : 0;
  if (bounded && (!Number.isSafeInteger(ceiling) || !Number.isSafeInteger(floor) || ceiling < 0 || ceiling > 16384 || floor < (quality === 'auto' ? 0 : Number.parseInt(quality, 10)) || floor > 16384 || ceiling > 0 && floor > ceiling)) throw new Error('播放清晰度边界无效');
  const deadline = Date.now() + 28000, controller = new AbortController();
  const abort = () => controller.abort();
  if (options.signal?.aborted) throw cancelledLookup();
  options.signal?.addEventListener('abort', abort, { once: true });
  const timer = setTimeout(abort, 28000), call = registeredModelCall;
  let info;
  try {
    const registeredModelProvider = call ? async selectedVid => {
      if (controller.signal.aborted) throw Date.now() >= deadline ? Object.assign(new Error('App 分集取源超时'), { code: 'ETIMEDOUT' }) : cancelledLookup();
      const timeout = Math.min(20000, deadline - Date.now());
      if (timeout <= 0) throw Object.assign(new Error('App 分集取源超时'), { code: 'ETIMEDOUT' });
      let rejectAbort;
      const cancelled = new Promise((_, reject) => { rejectAbort = () => reject(cancelledLookup()); controller.signal.addEventListener('abort', rejectAbort, { once: true }); });
      try {
        return await Promise.race([call('appRegisteredModel', { vid: selectedVid }, timeout, { signal: controller.signal }), cancelled]);
      } finally { controller.signal.removeEventListener('abort', rejectAbort); }
    } : undefined;
    const shared = { ...options, signal: controller.signal, lookupTimeoutMs: 6000, registeredModelProvider };
    info = await desktopAppSource.fetchAppPlayUrl(vid, bounded ? { ...shared, quality: 'auto', playbackFloor: floor, playbackCeiling: ceiling } : { ...shared, quality });
    if (controller.signal.aborted) throw cancelledLookup();
  } catch (error) {
    if (Date.now() >= deadline) throw Object.assign(new Error('App 分集取源超时'), { code: 'ETIMEDOUT' });
    if (controller.signal.aborted) throw cancelledLookup();
    if (quality !== 'auto' && /暂无尺寸可确认且满足/.test(error?.message || '')) error.code = 'QUALITY_UNAVAILABLE';
    throw error;
  } finally { clearTimeout(timer); options.signal?.removeEventListener('abort', abort); }
  const rows = [info, ...(Array.isArray(info.variants) ? info.variants : [])];
  if (bounded && rows.some(row => !Number.isSafeInteger(row.width) || !Number.isSafeInteger(row.height) || row.width <= 0 || row.height <= 0 || Math.min(row.width, row.height) < floor || ceiling > 0 && Math.min(row.width, row.height) > ceiling)) throw Object.assign(new Error('播放片源尺寸未满足所请求边界'), { code: 'QUALITY_UNAVAILABLE' });
  if (!bounded && quality !== 'auto' && rows.some(row => !Number.isSafeInteger(row.width) || !Number.isSafeInteger(row.height) || !desktopQuality.meetsDownloadQuality(row, quality))) {
    throw Object.assign(new Error(`本集暂无尺寸可确认且满足 ${quality} 的片源，未降级。`), { code: 'QUALITY_UNAVAILABLE' });
  }
  // The desktop list includes the primary; Android accepts eight distinct URLs total.
  // Validate every candidate's quality above before removing duplicates or trimming.
  const seen = new Set([info.url]);
  const variants = Array.isArray(info.variants) ? info.variants.filter(row => {
    if (seen.has(row.url)) return false;
    seen.add(row.url); return true;
  }).slice(0, 7).map(row => ({ ...row, requestedQuality: quality, ...(bounded ? { playbackCeiling: ceiling } : {}) })) : null;
  return { ...info, requestedQuality: quality, ...(bounded ? { playbackCeiling: ceiling } : {}), ...(variants ? { variants } : {}) };
}
export function registerAppResolver(nativeCall) {
  setNativeCall(nativeCall); registeredModelCall = nativeCall;
  if (typeof globalThis.window === 'undefined') throw new Error('需要安卓应用页面环境');
  const operations = new Map();
  const cancel = requestId => { const controller = operations.get(requestId); if (controller) { operations.delete(requestId); controller.abort(); } };
  const handler = (requestId, seriesId, vid, quality = 'auto', playbackCeiling) => {
    cancel(requestId);
    const controller = new AbortController(); operations.set(requestId, controller);
    return Promise.resolve().then(() => {
      if (typeof requestId !== 'string' || !requestId || requestId.length > 128 ||
          typeof seriesId !== 'string' || !/^\d{1,30}$/.test(seriesId) || typeof vid !== 'string' || !/^\d{1,30}$/.test(vid)) throw new Error('App 分集请求信息无效');
      if (playbackCeiling === undefined) desktopQuality.normalizeDownloadQuality(quality);
      if (controller.signal.aborted) throw cancelledLookup();
      return fetchAppPlayUrl(vid, { quality, signal: controller.signal, ...(playbackCeiling !== undefined ? { playbackCeiling } : {}) });
    }).then(
      playInfo => { if (operations.get(requestId) !== controller || controller.signal.aborted) return; return nativeCall('appResolveResult', { requestId, seriesId, vid, requestedQuality: quality, ...(playbackCeiling !== undefined ? { playbackCeiling } : {}), playInfo }); },
      error => { if (operations.get(requestId) !== controller || controller.signal.aborted) return; return nativeCall('appResolveResult', { requestId, seriesId, vid, requestedQuality: quality, ...(playbackCeiling !== undefined ? { playbackCeiling } : {}), error: error?.message || 'App 片源暂不可用',
        ...(TRANSIENT_LOOKUP_CODES.has(error?.code) || RESPONSE_LOOKUP_CODES.has(error?.code) || error?.code === 'QUALITY_UNAVAILABLE' ? { errorCode: error.code } : {}) }); },
    ).catch(() => undefined).finally(() => { if (operations.get(requestId) === controller) operations.delete(requestId); });
  };
  window.__androidAppResolve = handler; window.__androidAppCancelResolve = cancel;
  return () => {
    for (const controller of operations.values()) controller.abort(); operations.clear();
    if (window.__androidAppResolve === handler) delete window.__androidAppResolve;
    if (window.__androidAppCancelResolve === cancel) delete window.__androidAppCancelResolve;
    if (registeredModelCall === nativeCall) registeredModelCall = undefined;
  };
}
