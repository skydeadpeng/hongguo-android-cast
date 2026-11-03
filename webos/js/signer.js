/**
 * webOS 浏览器版请求签名（对应桌面 src/native/app-source.js 的签名机）。
 * 纯 JS：SM3 + RC4-Gorgon + MD5（来自 crypto-primitives.js），不依赖 Node。
 * 已登记设备身份 + Cookie 的组合在桌面端实测可用，此处保持同一协议。
 */
(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory(require('./crypto-primitives.js'));
  else root.HongguoSigner = factory(root.HongguoCrypto);
})(typeof self !== 'undefined' ? self : this, function (primitives) {
  'use strict';

  // ===== SM3（GB/T 32905，Uint8Array 版）=====
  function sm3(input) {
    const source = input instanceof Uint8Array ? input : new Uint8Array(input);
    const total = Math.ceil((source.length + 9) / 64) * 64;
    const data = new Uint8Array(total);
    data.set(source);
    data[source.length] = 0x80;
    const bits = BigInt(source.length) * 8n;
    // 大端写入比特长度（Java ByteBuffer.putLong 默认大端）。
    for (let i = 0; i < 8; i++) data[total - 8 + i] = Number((bits >> BigInt(8 * (7 - i))) & 0xffn);
    const view = new DataView(data.buffer);
    const state = new Uint32Array([0x7380166f, 0x4914b2b9, 0x172442d7, 0xda8a0600, 0xa96f30bc, 0x163138aa, 0xe38dee4d, 0xb0fb0e4e]);
    const words = new Uint32Array(68);
    const rotate = (x, n) => ((x << (n & 31)) | (x >>> ((32 - n) & 31))) >>> 0;
    for (let offset = 0; offset < total; offset += 64) {
      for (let i = 0; i < 16; i++) words[i] = view.getUint32(offset + i * 4, false);
      for (let i = 16; i < 68; i++) {
        const x = words[i - 16] ^ words[i - 9] ^ rotate(words[i - 3], 15);
        words[i] = x ^ rotate(x, 15) ^ rotate(x, 23) ^ rotate(words[i - 13], 7) ^ words[i - 6];
      }
      let [a, b, c, d, e, f, g, h] = state;
      for (let i = 0; i < 64; i++) {
        const a12 = rotate(a, 12);
        const s1 = rotate((a12 + e + rotate(i < 16 ? 0x79cc4519 : 0x7a879d8a, i)) >>> 0, 7);
        const s2 = s1 ^ a12;
        const ff = i < 16 ? a ^ b ^ c : (a & b) | (a & c) | (b & c);
        const gg = i < 16 ? e ^ f ^ g : (e & f) | (~e & g);
        const t1 = (ff + d + s2 + (words[i] ^ words[i + 4])) >>> 0;
        const t2 = (gg + h + s1 + words[i]) >>> 0;
        d = c; c = rotate(b, 9); b = a; a = t1;
        h = g; g = rotate(f, 19); f = e;
        e = (t2 ^ rotate(t2, 9) ^ rotate(t2, 17)) >>> 0;
      }
      const next = [a, b, c, d, e, f, g, h];
      for (let i = 0; i < 8; i++) state[i] = state[i] ^ next[i];
    }
    const out = new Uint8Array(32);
    const outView = new DataView(out.buffer);
    for (let i = 0; i < 8; i++) outView.setUint32(i * 4, state[i], false);
    return out;
  }

  // ===== Gorgon 家族 =====
  function reverseBits(value) { let result = 0; for (let i = 0; i < 8; i++) result = (result << 1) | ((value >> i) & 1); return result; }
  function rc4Gorgon(data, key) {
    const s = Array.from({ length: 256 }, (_v, i) => i); let j = 0;
    // 协议赋值：s[i] = s[j]，不是标准 RC4 的交换。
    for (let i = 0; i < 256; i += 1) { j = (j + s[i] + key[i % key.length]) & 255; s[i] = s[j]; }
    let i = 0; j = 0;
    return new Uint8Array(data.map(value => { i += 1; const x = s[i]; j += x; const y = s[j & 255]; s[i] = y; return value ^ s[(y + y) & 255]; }));
  }
  function be32(value) {
    const out = new Uint8Array(4);
    new DataView(out.buffer).setUint32(0, value >>> 0, false);
    return out;
  }
  function le32(value) {
    const out = new Uint8Array(4);
    new DataView(out.buffer).setUint32(0, value >>> 0, true);
    return out;
  }
  function concat(...arrays) {
    let size = 0;
    for (const item of arrays) size += item.length;
    const out = new Uint8Array(size);
    let at = 0;
    for (const item of arrays) { out.set(item, at); at += item.length; }
    return out;
  }
  function hex(bytes) { return Array.from(bytes).map(b => b.toString(16).padStart(2, '0')).join(''); }

  function xGorgon(query, body, timestamp, random) {
    const sdk = le32(67503104);
    const queryMd5 = primitives.md5(utf8(query)).slice(0, 4);
    const bodyMd5 = body ? primitives.md5(body).slice(0, 4) : new Uint8Array(4);
    const input = concat(queryMd5, bodyMd5, new Uint8Array(4), sdk, be32(timestamp));
    const key = [0x4a, 0x40, 0x16, (random >> 8) & 255, 0x47, 0x6c, 0x01, random & 255];
    const out = rc4Gorgon(Array.from(input), key);
    for (let i = 0; i < out.length; i += 1) {
      let value = out[i]; value = ((value >> 4) | (value << 4)) & 255;
      const next = i + 1 < out.length ? out[i + 1] : out[0];
      out[i] = (~(reverseBits((next ^ value) & 255) ^ 20)) & 255;
    }
    return hex(concat(new Uint8Array([0x84, 0x04, random & 255, (random >> 8) & 255, 0x40, 0x01]), out));
  }

  // ===== branch 选择（sm3 + md5 混合）=====
  function u32(value) { return value >>> 0; }
  function getIv(iv, data) {
    let value = u32(iv);
    for (let i = 0; i < data.length; i += 1) {
      if ((i & 1) === 0) value = u32((value >>> 4) ^ value ^ (value << 6) ^ data[i]);
      else value = u32(~((value >>> 7) ^ value ^ (data[i] | (value << 12))));
    }
    return value;
  }
  function branchOf(url, body, khronos) {
    const bodyMd5 = body ? primitives.md5(body) : new Uint8Array(16);
    const q = sm3(utf8(url.split('?')[1]));
    const iv = getIv(getIv(getIv(0x20230928, q), bodyMd5), le32(khronos));
    const low = iv & 15;
    return { iv: low - (((low * 171) >> 9) * 3) };
  }

  function utf8(text) { return new TextEncoder().encode(text); }
  /** 协议 nonce：使用 WebCrypto 强随机而非 Math.random。 */
  function strongRandom16() {
    const api = (typeof globalThis !== 'undefined' ? globalThis.crypto : null) || (typeof self !== 'undefined' ? self.crypto : null);
    if (api && api.getRandomValues) { const b = new Uint8Array(2); api.getRandomValues(b); return (b[0] << 8) | b[1]; }
    return Math.floor(Math.random() * 65536);
  }

  /**
   * 签一次 App 家族请求（模型/目录接口）。
   * 返回 { url, headers, body }；调用方补上 Cookie 与设备身份。
   */
  function signAppRequest(api, payload, { deviceId, installId, random } = {}) {
    const identity = {
      ac: 'wifi', channel: 'update_64', aid: '8662', app_name: 'novelread',
      version_code: '71332', version_name: '7.1.3.32', device_platform: 'android', os: 'android',
      ssmix: 'a', device_type: '25053RT47C', device_brand: 'Redmi', language: 'zh', os_api: '36',
      os_version: '16', manifest_version_code: '71332', resolution: '1280*2772', dpi: '520',
      update_version_code: '71332', host_abi: 'arm64-v8a', dragon_device_type: 'phone',
      pv_player: '71332', compliance_status: '0', need_personal_recommend: '1',
      player_so_load: '1', is_android_pad_screen: '0',
      device_id: deviceId, iid: installId,
    };
    const body = utf8(payload);
    const khronos = Math.floor(Date.now() / 1000);
    const ticketBase = Date.now();
    let requestTicket = ticketBase, query = '', url = '';
    for (let offset = 0; offset < 32; offset += 1) {
      requestTicket = ticketBase + offset;
      const values = { ...identity, ts: String(khronos), _rticket: String(requestTicket) };
      query = Object.keys(values).sort().map(key => encodeStrict(key) + '=' + encodeStrict(values[key])).join('&');
      url = api + '?' + query;
      if (branchOf(url, body, khronos).iv !== 1) break;
    }
    const nonce = random === undefined ? strongRandom16() : random;
    const headers = {
      'User-Agent': 'com.phoenix.read/71332 (Linux; U; Android 16; zh_CN; 25053RT47C; Build/BP2A.250605.031.A3; Cronet/TTNetVersion:04657795 2026-01-23 QuicVersion:c67e9834 2025-09-08)',
      'Accept': 'application/json; charset=utf-8,application/x-protobuf',
      'Content-Type': 'application/json; charset=UTF-8',
      'x-xs-from-web': '0',
      'x-ss-req-ticket': String(requestTicket),
      'x-tt-request-tag': 't=0;n=0',
      'sdk-version': '2',
      'passport-sdk-version': '50561',
      'x-vc-bdturing-sdk-version': '3.7.2.cn',
      'x-ss-stub': hex(primitives.md5(body)),
      'x-gorgon': xGorgon(query, body, khronos, nonce),
      'x-khronos': String(khronos),
    };
    return { url, headers, body };
  }

  /** 注册请求的 Gorgon 头（安卓 PassportSigner.gorgon 的等价实现）。 */
  function signRegister(rawQuery, body, stamp, deviceId) {
    const ts = Math.floor(stamp / 1000);
    const nonce = strongRandom16();
    const q = primitives.md5(utf8(rawQuery));
    const m = primitives.md5(body);
    const data = new Uint8Array(20);
    data.set(q.slice(0, 4), 0);
    data.set(m.slice(0, 4), 4);
    le32(67503104).forEach((value, i) => { data[12 + i] = value; });
    new DataView(data.buffer).setInt32(16, ts | 0, false);
    const key = [0x4a, 0x40, 0x16, (nonce >>> 8) & 255, 0x47, 0x6c, 1, nonce & 255];
    const box = Array.from({ length: 256 }, (_v, i) => i);
    let j = 0;
    for (let i = 0; i < 256; i += 1) { j = (j + box[i] + key[i % 8]) & 255; const t = box[i]; box[i] = box[j]; box[j] = t; }
    j = 0;
    for (let offset = 0; offset < 20; offset += 1) { const i = offset + 1; j = (j + box[i]) & 255; box[i] = box[j]; data[offset] ^= box[(box[i] + box[j]) & 255]; }
    for (let i = 0; i < 20; i += 1) {
      const n = data[i];
      const rotated = ((n << 4) | (n >>> 4)) & 255;
      const v = rotated ^ data[(i + 1) % 20];
      data[i] = ((reverseBits32(v) >>> 24) ^ 20) & 255;
    }
    return { gorgon: hex(concat(new Uint8Array([0x84, 0x04, nonce & 255, (nonce >>> 8) & 255, 0x40, 0x01]), data)) };
  }
  /** Java Integer.reverse：32 位比特序反转。 */
  function reverseBits32(value) {
    let v = value >>> 0, r = 0;
    for (let i = 0; i < 32; i++) { r = (r << 1) | (v & 1); v >>>= 1; }
    return r >>> 0;
  }
  function encodeStrict(value) {
    const bytes = utf8(String(value));
    let out = '';
    for (const byte of bytes) {
      const c = byte;
      if ((c >= 65 && c <= 90) || (c >= 97 && c <= 122) || (c >= 48 && c <= 57) || c === 45 || c === 95 || c === 46 || c === 126) out += String.fromCharCode(c);
      else if (c === 32) out += '+';
      else out += '%' + '0123456789ABCDEF'[c >>> 4] + '0123456789ABCDEF'[c & 15];
    }
    return out;
  }

  return { sm3, xGorgon, branchOf, signAppRequest, signRegister, hex, utf8, concat };
});
