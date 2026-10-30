/** Updates have a dedicated, local-page-only native interface, separate from source/account RPC. */
const pending = new Map();
let sequence = 0;
export function updateCall(method) {
  if (!['status', 'check', 'download', 'cancel', 'install', 'later'].includes(method)) return Promise.reject(new Error('更新操作无效'));
  if (!globalThis.window?.AndroidUpdates?.postMessage) return Promise.reject(new Error('请在安卓安装包中检查更新'));
  const id = `update-${++sequence}`;
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => { pending.delete(id); reject(new Error('更新操作未响应，请重试')); }, 10000);
    pending.set(id, { resolve, reject, timer });
    try { window.AndroidUpdates.postMessage(JSON.stringify({ id, method })); }
    catch (error) { clearTimeout(timer); pending.delete(id); reject(error); }
  });
}
export function updateProgress(value) {
  const loaded = Number(value?.downloaded), total = Number(value?.size);
  return Number.isFinite(loaded) && Number.isFinite(total) && total > 0 ? Math.max(0, Math.min(100, Math.floor(loaded * 100 / total))) : 0;
}
export function acceptUpdateResponse(message) {
  if (!message || typeof message.id !== 'string') return;
  const request = pending.get(message.id); if (!request) return;
  clearTimeout(request.timer); pending.delete(message.id);
  if (message.error) request.reject(new Error(String(message.error))); else request.resolve(message.result);
}
if (typeof window !== 'undefined') {
  window.__androidUpdatesResponse = acceptUpdateResponse;
  window.__androidUpdatesChanged = value => window.dispatchEvent(new CustomEvent('androidupdateschanged', { detail: value }));
}
