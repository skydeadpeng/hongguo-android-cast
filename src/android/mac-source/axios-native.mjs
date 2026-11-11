import { Buffer } from './buffer-runtime.mjs';

let callNative;
const NATIVE_TRANSIENT_CODES = new Set(['ENOTFOUND', 'EAI_AGAIN', 'ETIMEDOUT', 'ECONNABORTED', 'ECONNRESET', 'ECONNREFUSED', 'EHOSTUNREACH', 'ENETUNREACH', 'EPIPE']);
export function setNativeCall(call) {
  if (typeof call !== 'function') throw new TypeError('需要提供安卓请求函数');
  callNative = call;
}
function cancelled() { return Object.assign(new Error('App 片源请求已取消'), { code: 'ERR_CANCELED' }); }

/** Only adapt the desktop module's bounded requests; never use browser fetch or execute a response. */
export default async function axiosNative(config = {}) {
  if (!callNative) throw Object.assign(new Error('设备网络组件未连接'), { code: 'ERR_NETWORK' });
  if (config.signal?.aborted) throw cancelled();
  const method = String(config.method || 'GET').toUpperCase();
  if (method !== 'GET' && method !== 'POST') throw new Error('App 片源请求方式无效');
  const headers = {};
  for (const [key, value] of Object.entries(config.headers || {})) headers[key] = String(value);
  const body = config.data == null ? '' : Buffer.isBuffer(config.data) || config.data instanceof Uint8Array
    ? Buffer.from(config.data).toString('utf8') : typeof config.data === 'string' ? config.data : JSON.stringify(config.data);
  const timeout = Math.max(1, Math.min(12000, Math.floor(Number(config.timeout) || 12000)));
  let abort;
  const aborted = new Promise((_, reject) => {
    abort = () => reject(cancelled());
    config.signal?.addEventListener('abort', abort, { once: true });
  });
  try {
    // Expire queued native requests too; this metadata is not part of the signed HTTP request.
    const result = await Promise.race([callNative('appRequest', { url: config.url, method, headers, body, timeoutMs: timeout, deadlineMs: Date.now() + timeout }, timeout), aborted]);
    // This field belongs to the trusted JNI envelope, never to the remote JSON body.
    if (result?.status === 0 && NATIVE_TRANSIENT_CODES.has(result.errorCode)) {
      throw Object.assign(new Error('App 片源网络请求失败，请稍后重试'), { code: result.errorCode });
    }
    if (!result || !Number.isInteger(result.status) || typeof result.body !== 'string') throw new Error('设备请求返回了无效响应');
    const bytes = Buffer.byteLength(result.body, 'utf8');
    if (bytes > Math.min(config.maxContentLength || 2 * 1024 * 1024, 2 * 1024 * 1024)) throw new Error('App 接口响应超过大小限制');
    return { status: result.status, data: result.body, headers: result.headers || {}, url: result.url || config.url };
  } finally { config.signal?.removeEventListener('abort', abort); }
}
