import { Buffer } from './buffer-runtime.mjs';

function binary(value, encoding) {
  if (typeof value === 'string') return Buffer.from(value, encoding || 'utf8');
  if (value instanceof ArrayBuffer) return Buffer.from(new Uint8Array(value));
  if (ArrayBuffer.isView(value)) return Buffer.from(new Uint8Array(value.buffer, value.byteOffset, value.byteLength));
  throw new TypeError('加密输入必须为文本或字节数组');
}
function nativeProcess(request) {
  const bridge = globalThis.window?.AndroidCrypto || globalThis.AndroidCrypto;
  if (!bridge || typeof bridge.process !== 'function') throw new Error('设备加密组件未连接');
  let response;
  try {
    const value = bridge.process(JSON.stringify(request));
    response = typeof value === 'string' ? JSON.parse(value) : value;
  } catch { throw new Error('设备加密组件返回了无效响应'); }
  if (!response || response.error != null) throw new Error(typeof response?.error === 'string' ? response.error : '设备加密操作未成功');
  if (typeof response.data !== 'string' || response.data.length % 4 !== 0 || !/^[A-Za-z0-9+/]*={0,2}$/.test(response.data)) throw new Error('设备加密结果不是有效字节数据');
  return Buffer.from(response.data, 'base64');
}
export function randomBytes(size) {
  if (!Number.isSafeInteger(size) || size < 0 || size > 1024 * 1024) throw new RangeError('随机字节数无效');
  const crypto = globalThis.crypto || globalThis.window?.crypto;
  if (typeof crypto?.getRandomValues !== 'function') throw new Error('设备随机数组件未连接');
  const output = Buffer.alloc(size);
  for (let offset = 0; offset < size; offset += 65536) crypto.getRandomValues(output.subarray(offset, Math.min(size, offset + 65536)));
  return output;
}
export function createHash(name) {
  const normalized = String(name).toLowerCase().replace(/-/g, '');
  const algorithm = normalized === 'md5' ? 'MD5' : normalized === 'sha512' ? 'SHA-512' : null;
  if (!algorithm) throw new Error('设备未支持该摘要算法');
  const chunks = []; let finalized = false;
  const hash = {
    update(value, encoding) {
      if (finalized) throw new Error('摘要已经完成');
      chunks.push(binary(value, encoding)); return hash;
    },
    digest(encoding) {
      if (finalized) throw new Error('摘要已经完成');
      finalized = true;
      const result = nativeProcess({ operation: 'digest', algorithm, data: Buffer.concat(chunks).toString('base64') });
      if (result.length !== (algorithm === 'MD5' ? 16 : 64)) throw new Error('设备摘要长度无效');
      return encoding == null ? result : result.toString(encoding);
    },
  };
  return hash;
}
export function createDecipheriv(name, key, iv) {
  if (String(name).toLowerCase().replace(/-/g, '') !== 'aes128cbc') throw new Error('设备未支持该解密算法');
  const keyBytes = binary(key), ivBytes = binary(iv);
  if (keyBytes.length !== 16 || ivBytes.length !== 16) throw new Error('AES 密钥或初始向量长度无效');
  const chunks = []; let finalized = false, autoPadding = true;
  const decipher = {
    setAutoPadding(value = true) { if (finalized) throw new Error('解密已经完成'); autoPadding = !!value; return decipher; },
    update(value, encoding, outputEncoding) {
      if (finalized) throw new Error('解密已经完成');
      chunks.push(binary(value, encoding));
      return outputEncoding == null ? Buffer.alloc(0) : '';
    },
    final(encoding) {
      if (finalized) throw new Error('解密已经完成');
      finalized = true;
      const output = nativeProcess({ operation: 'decrypt', algorithm: 'AES-128-CBC',
        data: Buffer.concat(chunks).toString('base64'), key: keyBytes.toString('base64'), iv: ivBytes.toString('base64'), autoPadding });
      return encoding == null ? output : output.toString(encoding);
    },
  };
  return decipher;
}
export default { randomBytes, createHash, createDecipheriv };
