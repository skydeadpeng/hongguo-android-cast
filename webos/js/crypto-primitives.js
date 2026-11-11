/**
 * webOS 浏览器端加密原语：纯 JS MD5 + gzip(stored) + WebCrypto 包装。
 * 零依赖：webOS TV 没有 Node，签名链需要的摘要与封装在这里补齐。
 * 挂载到 window.HongguoCrypto（也可在 Node 下 require 供测试）。
 */
(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory();
  else root.HongguoCrypto = factory();
})(typeof self !== 'undefined' ? self : this, function () {
  'use strict';

  // ===== 纯 JS MD5（RFC 1321）=====
  const S = [7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
    5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
    4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
    6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21];
  const K = new Uint32Array(64);
  for (let i = 0; i < 64; i++) K[i] = Math.floor(Math.abs(Math.sin(i + 1)) * 4294967296);
  function rotl(value, shift) { return ((value << shift) | (value >>> (32 - shift))) >>> 0; }
  function md5(bytes) {
    const input = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
    const length = input.length;
    const padded = new Uint8Array((((length + 8) >> 6) + 1) * 64);
    padded.set(input);
    padded[length] = 0x80;
    const bits = BigInt(length) * 8n;
    for (let i = 0; i < 8; i++) padded[padded.length - 8 + i] = Number((bits >> BigInt(8 * i)) & 0xffn);
    let a0 = 0x67452301, b0 = 0xefcdab89, c0 = 0x98badcfe, d0 = 0x10325476;
    const view = new DataView(padded.buffer);
    for (let offset = 0; offset < padded.length; offset += 64) {
      const M = new Uint32Array(16);
      for (let i = 0; i < 16; i++) M[i] = view.getUint32(offset + i * 4, true);
      let A = a0, B = b0, C = c0, D = d0;
      for (let i = 0; i < 64; i++) {
        let F, g;
        if (i < 16) { F = (B & C) | (~B & D); g = i; }
        else if (i < 32) { F = (D & B) | (~D & C); g = (5 * i + 1) % 16; }
        else if (i < 48) { F = B ^ C ^ D; g = (3 * i + 5) % 16; }
        else { F = C ^ (B | ~D); g = (7 * i) % 16; }
        F = (F + A + K[i] + M[g]) >>> 0;
        A = D; D = C; C = B;
        B = (B + rotl(F, S[i])) >>> 0;
      }
      a0 = (a0 + A) >>> 0; b0 = (b0 + B) >>> 0; c0 = (c0 + C) >>> 0; d0 = (d0 + D) >>> 0;
    }
    const out = new Uint8Array(16);
    const outView = new DataView(out.buffer);
    outView.setUint32(0, a0, true); outView.setUint32(4, b0, true);
    outView.setUint32(8, c0, true); outView.setUint32(12, d0, true);
    return out;
  }
  function md5Hex(bytes) {
    return Array.from(md5(bytes)).map(b => b.toString(16).padStart(2, '0')).join('');
  }

  // ===== CRC32（gzip 尾部校验）=====
  const CRC_TABLE = (() => {
    const table = new Uint32Array(256);
    for (let i = 0; i < 256; i++) {
      let c = i;
      for (let k = 0; k < 8; k++) c = (c & 1) ? (0xedb88320 ^ (c >>> 1)) : (c >>> 1);
      table[i] = c >>> 0;
    }
    return table;
  })();
  function crc32(bytes) {
    let crc = 0xffffffff;
    for (let i = 0; i < bytes.length; i++) crc = CRC_TABLE[(crc ^ bytes[i]) & 0xff] ^ (crc >>> 8);
    return (crc ^ 0xffffffff) >>> 0;
  }

  /**
   * gzip 封装（deflate 用 stored 块，不压缩）。
   * 服务端 zlib 能正常解开；仅体积变大，注册包本身很小。
   */
  function gzipStored(bytes) {
    const input = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
    const out = [];
    out.push(0x1f, 0x8b, 8, 0, 0, 0, 0, 0, 0, 0); // 固定头：mtime=0, XFL=0, OS=0
    let offset = 0;
    if (input.length === 0) {
      out.push(0x01, 0x00, 0x00, 0xff, 0xff);
    }
    while (offset < input.length) {
      const size = Math.min(65535, input.length - offset);
      const last = offset + size >= input.length ? 1 : 0;
      out.push(last, size & 0xff, (size >>> 8) & 0xff, ~size & 0xff, (~size >>> 8) & 0xff);
      for (let i = 0; i < size; i++) out.push(input[offset + i]);
      offset += size;
    }
    const crc = crc32(input);
    out.push(crc & 0xff, (crc >>> 8) & 0xff, (crc >>> 16) & 0xff, (crc >>> 24) & 0xff);
    const size = input.length >>> 0;
    out.push(size & 0xff, (size >>> 8) & 0xff, (size >>> 16) & 0xff, (size >>> 24) & 0xff);
    return new Uint8Array(out);
  }

  // ===== WebCrypto 包装（异步）=====
  const subtle = () => {
    const api = (typeof globalThis !== 'undefined' ? globalThis.crypto : null) || (typeof self !== 'undefined' ? self.crypto : null);
    if (!api || !api.subtle) throw new Error('设备加密组件不可用（需要 WebCrypto）');
    return api.subtle;
  };
  function randomBytes(size) {
    const api = (typeof globalThis !== 'undefined' ? globalThis.crypto : null) || (typeof self !== 'undefined' ? self.crypto : null);
    const out = new Uint8Array(size);
    if (!api || !api.getRandomValues) throw new Error('设备随机数组件不可用');
    api.getRandomValues(out);
    return out;
  }
  async function sha512(bytes) {
    return new Uint8Array(await subtle().digest('SHA-512', bytes));
  }
  async function aesCbcEncrypt(key, iv, data) {
    const cryptoKey = await subtle().importKey('raw', key, { name: 'AES-CBC' }, false, ['encrypt']);
    return new Uint8Array(await subtle().encrypt({ name: 'AES-CBC', iv }, cryptoKey, data));
  }
  async function aesCbcDecrypt(key, iv, data) {
    const cryptoKey = await subtle().importKey('raw', key, { name: 'AES-CBC' }, false, ['decrypt']);
    return new Uint8Array(await subtle().decrypt({ name: 'AES-CBC', iv }, cryptoKey, data));
  }

  return { md5, md5Hex, crc32, gzipStored, randomBytes, sha512, aesCbcEncrypt, aesCbcDecrypt };
});
