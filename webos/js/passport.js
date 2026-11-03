/**
 * webOS 浏览器端设备注册（对应安卓 PassportDevice + OfficialApiClient.registerDevice）。
 * 依赖 crypto-primitives.js：纯 JS MD5/gzip + WebCrypto 的 SHA-512/AES-CBC。
 * 浏览器没有 Node，因此全部走异步 WebCrypto；SM3 签名机见 signer.js。
 */
(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory(require('./crypto-primitives.js'));
  else root.HongguoPassport = factory(root.HongguoCrypto);
})(typeof self !== 'undefined' ? self : this, function (primitives) {
  'use strict';

  const REGISTER = 'https://log.snssdk.com/service/2/device_register/';
  const UA = 'com.phoenix.read/73532 (Linux; U; Android 16; zh_CN; 25053RT47C; Build/BP2A.250605.031.A3; Cronet/TTNetVersion:04657795 2026-01-23 QuicVersion:c67e9834 2025-09-08)';
  const SALT = primitives.md5Hex ? null : null;
  const SALT_HEX = '4dd4c2e6b83162090e52b3c7a6733ba41cb2462b829ab58a196b39db57177524f49baf7f08e8d68d26a72e37c1a95a2f1f05a51892aef2949732b62a38aadd58';
  const MAGIC = new Uint8Array([0x74, 0x63, 5, 0x10, 0, 0]);

  const hex = bytes => Array.from(bytes).map(b => b.toString(16).padStart(2, '0')).join('');
  const unhex = text => {
    const out = new Uint8Array(text.length >> 1);
    for (let i = 0; i < out.length; i++) out[i] = parseInt(text.substr(i * 2, 2), 16);
    return out;
  };
  const concat = (...arrays) => {
    let size = 0;
    for (const item of arrays) size += item.length;
    const out = new Uint8Array(size);
    let at = 0;
    for (const item of arrays) { out.set(item, at); at += item.length; }
    return out;
  };
  const utf8 = text => new TextEncoder().encode(text);
  function uuid() {
    const b = primitives.randomBytes(16);
    b[6] = (b[6] & 0x0f) | 0x40; b[8] = (b[8] & 0x3f) | 0x80;
    const h = hex(b);
    return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
  }

  /** 设备档案（安卓 baseQuery + PassportDevice.payload 的浏览器等价物）。 */
  function deviceProfile() {
    return {
      cdid: uuid(), clientudid: uuid(),
      openudid: hex(primitives.randomBytes(8)), created: Date.now(),
    };
  }
  function headerFor(device) {
    const header = {
      access: 'wifi', app_version: '7.3.5.32', app_version_minor: '', channel: 'update_64',
      device_brand: 'Redmi', device_manufacturer: 'Redmi', device_model: '25053RT47C',
      device_category: 'phone', device_platform: 'android', os: 'Android', os_version: '16',
      display_density: 'xxxhdpi', resolution: '2772x1280', language: 'zh',
      tz_name: 'Asia/Shanghai', region: 'CN', package: 'com.phoenix.read',
      cpu_abi: 'arm64-v8a', display_name: '红果短剧', sdk_version: '3.7.2', sdk_flavor: 'china',
      aid: 8662, os_api: 36, density_dpi: 520, timezone: 8, tz_offset: 28800,
      version_code: 73532, manifest_version_code: 73532, update_version_code: 73532,
      sdk_target_version: 29, guest_mode: 0, is_system_app: 0, not_request_sender: 0,
      apk_first_install_time: device.created,
      cdid: device.cdid, clientudid: device.clientudid, openudid: device.openudid,
      oaid_may_support: true, ipv6_list: [], sim_serial_number: [],
      custom: { is_android_fold: 0, filter_warn: 0, web_ua: '', is_android_pad: 0, client_ipv4: '' },
    };
    return header;
  }
  async function deviceKey(seed) {
    const salt = unhex(SALT_HEX);
    const first = await primitives.sha512(seed);
    return primitives.sha512(concat(first, salt));
  }
  async function encryptPayload(plain, seed) {
    const compressed = primitives.gzipStored(plain);
    const hash = await primitives.sha512(compressed);
    const derived = await deviceKey(seed);
    const body = await primitives.aesCbcEncrypt(derived.slice(0, 16), derived.slice(16, 32), concat(hash, compressed));
    return concat(MAGIC, seed, body);
  }
  async function decryptPayload(wire) {
    const derived = await deviceKey(wire.slice(6, 38));
    const plain = await primitives.aesCbcDecrypt(derived.slice(0, 16), derived.slice(16, 32), wire.slice(38));
    const hash = await primitives.sha512(plain.slice(64));
    const same = hash.length === plain.length - 64 && hash.every((value, i) => value === plain[64 + i]);
    if (!same) throw new Error('设备注册加密响应校验失败');
    const gz = plain.slice(64);
    // gzip 解压：DecompressionStream 是浏览器原生能力。
    if (typeof DecompressionStream === 'function') {
      const stream = new Blob([gz]).stream().pipeThrough(new DecompressionStream('gzip'));
      const buffer = await new Response(stream).arrayBuffer();
      return new Uint8Array(buffer);
    }
    throw new Error('当前环境缺少 gzip 解压能力');
  }

  /** 注册请求的 query（安卓 baseQuery + tt_data/ts/_rticket/package）。 */
  function registerQuery(device) {
    return {
      ac: 'wifi', aid: '8662', app_name: 'novelread', channel: 'update_64', device_brand: 'Redmi',
      device_platform: 'android', device_type: '25053RT47C', dpi: '520', language: 'zh',
      manifest_version_code: '73532', os: 'android', os_api: '36', os_version: '16',
      resolution: '1280*2772', ssmix: 'a', update_version_code: '73532', version_code: '73532',
      version_name: '7.3.5.32', cdid: device.cdid, openudid: device.openudid,
      tt_data: 'a', ts: String(Math.floor(Date.now() / 1000)), _rticket: String(Date.now()),
      package: 'com.phoenix.read',
    };
  }
  function encodeQuery(map) {
    return Object.keys(map).sort().map(key => encodeStrict(key) + '=' + encodeStrict(map[key])).join('&');
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

  /**
   * 完成一次匿名设备注册，返回 device_id/install_id/会话凭据。
   * 注册响应的 Cookie 与 device_token 必须在后续模型请求里带上，否则服务端按未登记设备处理。
   */
  async function registerDevice({ signer, fetchImpl, withCredentials } = {}) {
    const doFetch = fetchImpl || (typeof fetch === 'function' ? fetch.bind(globalThis) : null);
    if (!doFetch) throw new Error('当前环境缺少网络能力');
    const device = deviceProfile();
    const stamp = Date.now();
    const seed = primitives.randomBytes(32);
    const plain = utf8(JSON.stringify({ magic_tag: 'ss_app_log', header: headerFor(device), _gen_time: stamp }));
    const body = await encryptPayload(plain, seed);
    const query = registerQuery(device);
    query.ts = String(Math.floor(stamp / 1000));
    query._rticket = String(stamp);
    const raw = encodeQuery(query);
    const signed = await signer.signRegister(raw, body, stamp, device.openudid);
    const headers = {
      'User-Agent': UA,
      'Content-Type': 'application/octet-stream;tt-data=a',
      'Accept': 'application/json; charset=utf-8,application/x-protobuf',
      'Content-Encoding': 'gzip',
      'X-Tt-Request-Tag': 't=0;n=1',
      'Log-Encode-Type': 'gzip',
      'Lc': '102',
      'Sdk-Version': '2',
      'Passport-Sdk-Version': '60755',
      'Passport-Sdk-Settings': 'device_transfer_s_0,device_transfer_ab_1',
      'X-SS-DP': '8662',
      'X-Vc-Bdturing-Sdk-Version': '4.0.3.cn',
      'X-Tt-Store-Region': 'cn',
      'X-Tt-Store-Region-Src': 'did',
      'X-Khronos': String(Math.floor(stamp / 1000)),
      'X-Argus': b32le(Math.floor(stamp / 1000)),
      'X-SS-Req-Ticket': String(stamp),
      'X-SS-STUB': primitives.md5Hex(body).toUpperCase(),
      'X-Gorgon': signed.gorgon,
    };
    const response = await doFetch(REGISTER + '?' + raw, { method: 'POST', headers, body, credentials: withCredentials ? 'include' : 'same-origin' });
    if (!response.ok) throw new Error('设备注册响应 HTTP ' + response.status);
    let wire = new Uint8Array(await response.arrayBuffer());
    if (wire.length >= 6 && MAGIC.every((value, i) => wire[i] === value)) wire = await decryptPayload(wire);
    const text = new TextDecoder().decode(wire);
    const identity = { deviceId: '', installId: '', deviceToken: '' };
    const pick = key => {
      const str = new RegExp('"' + key + '_str"\\s*:\\s*"([1-9][0-9]{0,29})"').exec(text);
      if (str) return str[1];
      const num = new RegExp('"' + key + '"\\s*:\\s*"?([1-9][0-9]{0,29})"?').exec(text);
      return num ? num[1] : '';
    };
    identity.deviceId = pick('device_id');
    identity.installId = pick('install_id');
    const token = /"device_token"\s*:\s*"([^"]+)"/.exec(text);
    identity.deviceToken = token ? token[1] : '';
    if (!identity.deviceId || !identity.installId) throw new Error('设备注册未返回有效身份');
    const setCookie = typeof response.headers.getSetCookie === 'function' ? response.headers.getSetCookie() : [];
    identity.cookie = setCookie.map(item => item.split(';')[0]).join('; ');
    identity.device = device;
    return identity;
  }
  function b32le(value) {
    const out = new Uint8Array(4);
    new DataView(out.buffer).setInt32(0, value | 0, true);
    let binary = '';
    for (const byte of out) binary += String.fromCharCode(byte);
    return btoa(binary);
  }

  return { registerDevice, deviceProfile, registerQuery, encodeQuery, hex, unhex, concat, utf8, UA };
});
