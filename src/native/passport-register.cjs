/**
 * 红果 Passport 设备注册的桌面移植（对应安卓 PassportSigner/PassportDevice/DanmakuSigner）。
 * 算法说明：SM3/MD5/自定义变换均为红果 App 注册协议的既有要求，本文件是该协议的客户端模拟。
 * 自包含：常量、签名器、设备加密、注册流程与已登记单集模型请求都在本文件内。
 */
const crypto = require('crypto');
const zlib = require('zlib');

const REGISTER = 'https://log.snssdk.com/service/2/device_register/';
const UA = 'com.phoenix.read/73532 (Linux; U; Android 16; zh_CN; 25053RT47C; Build/BP2A.250605.031.A3; Cronet/TTNetVersion:04657795 2026-01-23 QuicVersion:c67e9834 2025-09-08)';
const SALT = unhex('4dd4c2e6b83162090e52b3c7a6733ba41cb2462b829ab58a196b39db57177524f49baf7f08e8d68d26a72e37c1a95a2f1f05a51892aef2949732b62a38aadd58');
const MAGIC = Buffer.from([0x74, 0x63, 5, 0x10, 0, 0]);
const ARGUS_KEY = unhex('ac1adaae95a7af94a5114ab3b3a97dd80050aa0a39314c40528caec95256c28c');
const SBOX = unhex('fa7d086b9c59b34b045f39d0384a91990067a6209ff54d827326eedf18668333800319fbd9feaeaaa9b052c60bf379254e78b436ac5d1a279e88dbbd3c63ec4915c1301fdcb856d46ccdca0943c835a3ef1ef496d2fc0e727b9484d1ea455a62023fd31281342bdd7ee628f2a54613013b21f66137292a0ded8cafbf9d5cbb24760f75e45389e1988db19a65704f544c58ab6e6f8b23c407110cbacfa0a48ed8053d14b2da74c3d7e7bed67fde48163e8590a155b7774222c986502e17f964312c9bf16d1c4468e3e9a89397cb3257ebe5716aadc0ccc7c5fd601da22d47a7e251695e7ace0a41b6958ff7b987e03a06108ab5f85bd5f0bc92ff7c2fc2e81b40ec1bdabdba98910cb22b83413467fb0ad876b546055961237590872ae350154cacb179ebaee595470468f0863d518b0fca8ee4b94ef21282bc0ed5f7ef2825cf5b5de96a5502e133be93e7f5ad9d3e3924a8e2fa1757d07a0d0830d6b8a38dfd079ac41e6e226497d21db0bf45663f6cdddb2780a711dca6c552f8c0b6c85c0073607ba01913aac935484bd3a4cd9f99f3104440547e29f4061fa2aba12f3cf6af856236217f5edf201ab3b4e6ff72848f6526945a77ea4378c74acc2c146bc6e87453fcd41cce317003188c96383289f13a5fd7f9a969b7633758c23bc371cb9e92018a0b4d889bbb4f6d6fe0fea549de561609ed9cc12dee817dd97cd12e425b4dc1a65dea44fd454e1ba13fd189e17d2faadbabad59cbb1ce9a28c9e0f670394ad7ff30f5ddbc573b118db2ee00b6e61a5a7cf9dec4cd2e80bbb94ca59f8408c66f426cf027e78b3a9c51fb6721754131a7ca20432ab7bfd97af2b5f88c2c23834f8f60a004133714e301c563665c7481df58bd68903dd2b334f419933229d649ae0d4bd8079eac1e2d0b40b872ba761071a8e4561d48fee5c24791da87269d1f886bc098be25099733a385165e7fdc6e54e9f7a9c8e8c377d0822bec02628a920e3eb00f05f3f19678388636183c24cf0ab453cc6165a4c794d5157e6def792235126a8e5206557b46645095e20cedd31703a29b99eb1cfcafd47369fa5ff72c1ebfc8e1f39f76807148aa94ad64fb89c660c332b34dd2e044dd5fa8b1c7682334c96d127fb7eb15bea9d17893a00c92a4d747e38ac270ab26419a79a7d814858fc06f56d08c11b92e3ce29dcf0ede035d463ecd38430f335ad91a656c223bfc30a688ea37a2b48d8e519cd640eef9f884f4ae97e9ca0a456757042f835cd5c5c482b6a391981f4aac96816ecb1b0908af1895497d54edfa16313adab866f5a5f1fe10010674cc63df7c2825f6ceb24f8be5bc8769bb8621070036e70b50599b1ce862581961f2bd275eba1de699423d0d2ab5dc5b29f02d4c537b6a734e3f75ff4ba1351755723920d3b0fdef02ec777ee42bdb90c1059e7ad4526b2413');
const ORDERS = unhex('0f0704000908030a060b050d0e010c020f05080c0009020103070e060b0a0d04060500070c000a04080f010b0d09020e060b02050403080101070a000d0c090e0d070e0f0b0208030c0509010004060a0d0902060f0b0a040807000c0503010e0c090f07060f030e020d0405010b0a000c050a090e0802040407030f01060b000b0f0408020a0700090d06010e03050c0b060a0508020c03070f0e090d0001040906080f050800040a0b030d01020c0e090d0c0604070a03030f00080105020e01000d0f090a0b0e0402080703060c0501080a0c0f0905060b000304020e070d04080f000c0f0e0d0a01060207090503040205080d0b0a0606000e0f070c09030a08040f000b01060d0c0709030e05020a070b050f00020e0108030d0c0609040d070f08050f06040b0a0e0c090002030d0c020704010b0e0e08060f09050003');
const F13_MD5 = [0xa7aefe20, 0x7149f1d6, 0x47e4ca07, 0xe9b58f67, 0x93b924de, 0xc614d0f5, 0x38afe0ef, 0xb2bbad73, 0xe24444c3, 0x9d3aec9b, 0xdf7b37e4, 0xd8b16d40, 0xf8ac31b8, 0x76b9a90b, 0x31d833ee, 0x953fce64, 0x353595a4, 0x4609c13b, 0x36925008, 0x8c6d0925, 0x5df5c177, 0x1cfbf52b, 0x8a4fa7f0, 0x114ca35e, 0x8193f984, 0x7a7a8733, 0x316ab4d5, 0x3c20cfc9, 0xa6d84453, 0x3a18500c, 0x798ec47a, 0x97a76b28, 0x66c4ff96, 0x51716443, 0x0dd2fc3b, 0xb5696da7, 0xbbeb3ac5, 0x5c53d204, 0xd32608ce, 0x7279b9ec, 0xf4188ecf, 0xf7d793db, 0x332cc491, 0xab76ae15, 0x9bebe727, 0x18a01384, 0x5be9f8a7, 0x5f90a754, 0x39b663c0, 0x36673c83, 0x7c92f514, 0x9d7d94d7, 0xe2e8d9aa, 0x5f7e9ea9, 0x7abd4551, 0x569e05da, 0x40a25632, 0x3df5a9a5, 0xbab37d80, 0x454286dc, 0x3f5d4e78, 0x03d7b75d, 0xb1fe4af7, 0xa5ab26a3];

// ===== 基础字节工具 =====
function unhex(text) { return Buffer.from(text, 'hex'); }
function hex(data) { return Buffer.from(data).toString('hex'); }
function md5(data) { return crypto.createHash('md5').update(data).digest(); }
function sha512(data) { return crypto.createHash('sha512').update(data).digest(); }
function join(...arrays) { return Buffer.concat(arrays.map(item => Buffer.from(item))); }
function u8(value) { return value & 255; }
function rot8(value, n) { return ((value << n) | (value >>> (8 - n))) & 255; }
function pad16(data) { const n = 16 - data.length % 16; const out = Buffer.alloc(data.length + n, n); data.copy(out); return out; }
function readLe32(data, at) { return data.readInt32LE(at); }
function writeLe32(data, at, value) { data.writeInt32LE(value | 0, at); }
function le32Buffer(value) { const out = Buffer.alloc(4); out.writeInt32LE(value | 0, 0); return out; }
function rotl32(value, n) { return ((value << n) | (value >>> (32 - n))) | 0; }
function rotr32(value, n) { return ((value >>> n) | (value << (32 - n))) | 0; }
function reverseBytes32(value) { return ((value & 255) << 24) | ((value & 65280) << 8) | ((value >>> 8) & 65280) | ((value >>> 24) & 255); }
/** Java Integer.reverse：32 位比特序反转（非字节序）。 */
function bitReverse32(value) {
  let v = value >>> 0, r = 0;
  for (let i = 0; i < 32; i++) { r = (r << 1) | (v & 1); v >>>= 1; }
  return r >>> 0;
}

// ===== SM3（DanmakuSigner.sm3 逐字节移植）=====
function sm3(source) {
  const source_ = Buffer.from(source);
  const total = ((source_.length + 9 + 63) / 64 | 0) * 64;
  const data = Buffer.alloc(total); source_.copy(data); data[source_.length] = 0x80;
  // Java ByteBuffer.putLong 默认大端：SM3 填充的比特长度按大端写入
  data.writeBigInt64BE(BigInt(source_.length) * 8n, total - 8);
  const state = [0x7380166f, 0x4914b2b9, 0x172442d7, 0xda8a0600, 0xa96f30bc, 0x163138aa, 0xe38dee4d, 0xb0fb0e4e];
  const w = new Array(68);
  for (let offset = 0; offset < total; offset += 64) {
    for (let i = 0; i < 16; i++) w[i] = data.readInt32BE(offset + i * 4);
    for (let i = 16; i < 68; i++) {
      const x = w[i - 16] ^ w[i - 9] ^ rotl32(w[i - 3], 15);
      w[i] = x ^ rotl32(x, 15) ^ rotl32(x, 23) ^ rotl32(w[i - 13], 7) ^ w[i - 6];
    }
    let a = state[0], b = state[1], c = state[2], d = state[3], e = state[4], f = state[5], g = state[6], h = state[7];
    for (let i = 0; i < 64; i++) {
      const a12 = rotl32(a, 12);
      const s1 = rotl32((a12 + e + rotl32(i < 16 ? 0x79cc4519 : 0x7a879d8a, i)) | 0, 7);
      const s2 = s1 ^ a12;
      const ff = i < 16 ? (a ^ b ^ c) : ((a & b) | (a & c) | (b & c));
      const gg = i < 16 ? (e ^ f ^ g) : ((e & f) | (~e & g));
      const t1 = (ff + d + s2 + (w[i] ^ w[i + 4])) | 0;
      const t2 = (gg + h + s1 + w[i]) | 0;
      d = c; c = rotl32(b, 9); b = a; a = t1;
      h = g; g = rotl32(f, 19); f = e; e = (t2 ^ rotl32(t2, 9) ^ rotl32(t2, 17)) | 0;
    }
    const next = [a, b, c, d, e, f, g, h];
    for (let i = 0; i < 8; i++) state[i] = state[i] ^ next[i];
  }
  const out = Buffer.alloc(32);
  for (let i = 0; i < 8; i++) out.writeInt32BE(state[i], i * 4);
  return out;
}

// ===== PassportSigner =====
function ivHash(data, value) {
  for (let i = 0; i < data.length; i++) {
    value = (i & 1) === 0
      ? ((value >>> 4) ^ value ^ (value << 6) ^ u8(data[i])) | 0
      : (~((value >>> 7) ^ value ^ (u8(data[i]) | (value << 12)))) | 0;
  }
  return value | 0;
}
function branchOf(query, body, ts) {
  const q = sm3(Buffer.from(query, 'utf8'));
  const m = body.length === 0 ? Buffer.alloc(16) : md5(body);
  return { iv: ivHash(le32Buffer(ts), ivHash(m, ivHash(q, 0x20230928))), queryHash: q, bodyHash: m };
}
function encodeQuery(map) {
  const keys = Object.keys(map).sort();
  return keys.map(key => encodeURIComponentStrict(key) + '=' + encodeURIComponentStrict(map[key])).join('&');
}
function encodeURIComponentStrict(value) {
  let out = '';
  const bytes = Buffer.from(String(value), 'utf8');
  for (const byte of bytes) {
    const c = byte;
    if (c >= 65 && c <= 90 || c >= 97 && c <= 122 || c >= 48 && c <= 57 || c === 45 || c === 95 || c === 46 || c === 126) out += String.fromCharCode(c);
    else if (c === 32) out += '+';
    else out += '%' + '0123456789ABCDEF'[c >>> 4] + '0123456789ABCDEF'[c & 15];
  }
  return out;
}
function gorgon(query, body, ts, nonce) {
  const q = md5(Buffer.from(query, 'utf8'));
  const m = body.length === 0 ? Buffer.alloc(16) : md5(body);
  const data = Buffer.alloc(20);
  q.copy(data, 0, 0, 4); m.copy(data, 4, 0, 4);
  le32Buffer(67503104).copy(data, 12);
  // Java ByteBuffer.putInt 默认大端：ts 字段按大端写入
  data.writeInt32BE(ts | 0, 16);
  const key = [0x4a, 0x40, 0x16, (nonce >>> 8) & 255, 0x47, 0x6c, 1, nonce & 255];
  const box = new Array(256);
  for (let i = 0; i < 256; i++) box[i] = i;
  let j = 0;
  for (let i = 0; i < 256; i++) { j = (j + box[i] + key[i % 8]) & 255; box[i] = box[j]; } // 协议赋值，故意非交换
  j = 0;
  for (let offset = 0; offset < 20; offset++) {
    const i = offset + 1;
    j = (j + box[i]) & 255;
    const y = box[j];
    box[i] = y;
    data[offset] ^= box[(y * 2) & 255] & 255;
  }
  for (let i = 0; i < 20; i++) {
    const n = u8(data[i]);
    const rotated = ((n << 4) | (n >>> 4)) & 255;
    const v = rotated ^ u8(data[(i + 1) % 20]);
    data[i] = ((bitReverse32(v) >>> 24) ^ 0xeb) & 255;
  }
  return hex(join(Buffer.from([0x84, 4, nonce & 255, (nonce >>> 8) & 255, 0x40, 1]), data));
}
function helios(ts, nonce) {
  // Java: bytes(hex(md5(...))) 保留 hex 字符串的 ASCII 字节（32 字节），不是摘要二进制。
  const raw = Buffer.from(hex(md5(join(le32Buffer(nonce), Buffer.from('8662')))), 'ascii');
  let left = BigInt.asIntN(64, raw.readBigUInt64LE(0));
  let right = BigInt.asIntN(64, raw.readBigUInt64LE(8));
  const queue = [BigInt.asIntN(64, raw.readBigUInt64LE(16)), BigInt.asIntN(64, raw.readBigUInt64LE(24))];
  const rotateRight64 = (value, n) => BigInt.asIntN(64, (BigInt.asUintN(64, value) >> BigInt(n)) | (BigInt.asUintN(64, value) << BigInt(64 - n)));
  const rotateLeft64 = (value, n) => BigInt.asIntN(64, (BigInt.asUintN(64, value) << BigInt(n)) | (BigInt.asUintN(64, value) >> BigInt(64 - n)));
  const rounds = new Array(34);
  rounds[0] = left;
  for (let i = 0; i < 33; i++) {
    const v = (rotateRight64(queue.shift(), 8) + left) | 0n;
    queue.push(BigInt.asIntN(64, v));
    left = BigInt.asIntN(64, v ^ rotateLeft64(left, 3));
    rounds[i + 1] = left;
  }
  const plain = pad16(Buffer.from(Number(ts >>> 0) + '-1588093228-8662', 'utf8'));
  const out = [le32Buffer(nonce)];
  for (let offset = 0; offset < plain.length; offset += 16) {
    let a = BigInt.asIntN(64, plain.readBigUInt64LE(offset));
    let b = BigInt.asIntN(64, plain.readBigUInt64LE(offset + 8));
    for (const r of rounds) {
      b = BigInt.asIntN(64, r ^ (a + rotateRight64(b, 8)));
      a = BigInt.asIntN(64, b ^ rotateLeft64(a, 3));
    }
    const block = Buffer.alloc(16);
    block.writeBigUInt64LE(BigInt.asUintN(64, a), 0);
    block.writeBigUInt64LE(BigInt.asUintN(64, b), 8);
    out.push(block);
  }
  return join(...out).toString('base64');
}
function f13(branch, ts) {
  const group = ((branch.iv & 15) - 2) / 3;
  if (!Number.isInteger(group) || group < 0 || group > 4) throw new Error('账号签名分支无效');
  const seeds = [0x8980f29b, 0xeb549c7f, 0xb08726db, 0xd40cb5e6, 0xe8f559e4];
  const count = (seeds[group] + ts) | 0;
  const shift = (count + 5) & 7;
  const msg = [branch.queryHash, branch.bodyHash, le32Buffer(ts)];
  for (const x of unhex('8496779dd4150bf8')) msg.push(Buffer.from([(u8(x) * 257) >>> shift & 255]));
  msg.push(Buffer.from([0xa0, 1, 0, 0]));
  const m = join(...msg);
  const start = [0x79e0f2fb, 0xc8b52570, 0xebc2f8cd, 0x7c104d93];
  const end = [0x19be4866, 0xe85986b4, 0xe19b326e, 0x71d1d7d4];
  for (let i = 0; i < 4; i++) { start[i] = rotr32(start[i], count & 31); end[i] = rotr32(end[i], (count + 6) & 31); }
  let a = start[0], x = start[1], c = start[2], d = start[3];
  const shifts = [[7, 12, 17, 22], [5, 9, 14, 20], [4, 11, 16, 23], [6, 10, 15, 21]];
  for (let i = 0; i < 64; i++) {
    let f;
    switch (i / 16 | 0) {
      case 0: f = (x & c) | (~x & d); break;
      case 1: f = (x & d) | (c & ~d); break;
      case 2: f = x ^ c ^ d; break;
      default: f = c ^ (x | ~d);
    }
    const word = m.readInt32LE(u8(ORDERS[group * 64 + i]) * 4);
    const next = (x + rotl32((a + f + word + rotr32(F13_MD5[i], (count + 1) & 31)) | 0, shifts[i / 16 | 0][i % 4])) | 0;
    a = d; d = c; c = x; x = next;
  }
  const out = [Buffer.alloc(0)];
  const tail = [a, x, c, d];
  for (let i = 0; i < 4; i++) out.push(le32Buffer((start[i] + tail[i]) ^ end[i]));
  const hash = join(...out);
  let check = 0x20220420;
  for (let i = 0; i < 12; i++) {
    check = (i & 1) === 0
      ? ((check >>> 3) ^ check ^ u8(hash[i]) ^ (check << 7)) | 0
      : ((check >>> 5) ^ check ^ ~(u8(hash[i]) | (check << 11))) | 0;
  }
  return join(hash, le32Buffer((check | 4) ^ 0x1000000));
}
function protoBuilder() {
  const chunks = [];
  const raw = n => { let value = BigInt(n); while (value & ~127n) { chunks.push(Buffer.from([Number(value & 127n) | 128])); value >>= 7n; } chunks.push(Buffer.from([Number(value)])); };
  const sint = (field, n) => { raw(BigInt(field) * 8n); raw(BigInt.asIntN(64, BigInt(n)) << 1n ^ BigInt.asIntN(64, BigInt(n)) >> 63n); };
  const blob = (field, bytes) => { if (bytes.length === 0) return; raw(BigInt(field) * 8n + 2n); raw(BigInt(bytes.length)); chunks.push(Buffer.from(bytes)); };
  const text = (field, value) => blob(field, Buffer.from(String(value), 'utf8'));
  const decimal = (field, n) => { raw(BigInt(field) * 8n + 5n); const out = Buffer.alloc(4); out.writeFloatLE(n, 0); chunks.push(out); };
  return { sint, blob, text, decimal, data: () => Buffer.concat(chunks) };
}
function protoFor(query, ts, nonce, device, branch, f13Bytes) {
  const d = protoBuilder(), env = protoBuilder(), report = protoBuilder(), count = protoBuilder(), p = protoBuilder();
  d.sint(1, 1); d.sint(2, 2); d.text(3, '8662'); d.text(4, device); d.text(5, 'Ai6svO3PyrwDOUSmO6ZcResxu'); d.text(6, '!noperm!');
  d.sint(7, -888888); d.sint(8, -888888); d.sint(9, 3); d.sint(10, -888888); d.text(11, '!notset!'); d.text(12, 'Asia/Shanghai,8'); d.text(13, 'zh_CN'); d.sint(14, 4);
  const floats = [255.24993896484375, 35.58599090576172, 3.467449188232422, 3.467449188232422, 255.1754913330078, 42.17544174194336];
  for (let i = 0; i < floats.length; i++) d.decimal(i + 16, floats[i]);
  d.text(22, '16'); d.sint(23, 41); d.sint(24, 36);
  for (let i = 25; i <= 28; i++) d.sint(i, 1728388016635 + (i === 28 ? 2 : 0));
  d.sint(29, -1);
  const names = ['25053RT47C', 'Redmi', '25053RT47C', '25053RT47C', 'Redmi', 'Redmi', 'Redmi'];
  for (let i = 0; i < names.length; i++) d.text(i + 30, names[i]);
  d.sint(38, 31);
  report.sint(1, ts >>> 0); report.sint(2, -2); report.sint(4, 200);
  env.sint(1, 110); env.sint(2, 146331399); env.sint(3, 146331396); env.sint(5, 7); env.text(6, 'v04.06.04.03-bugfix'); env.sint(7, 11001);
  env.blob(12, d.data()); env.blob(13, report.data()); env.text(14, '7.3.5.32');
  count.sint(1, 111); count.sint(2, 10); count.sint(3, 694367); count.sint(5, 586952199);
  p.blob(1, unhex('f7e85ffad7d7dc3bd62ac87057cf6118')); p.sint(2, 3); p.sint(3, nonce >>> 0); p.text(4, '8662'); p.text(5, device);
  p.text(6, '1588093228'); p.text(7, '7.3.5.32'); p.text(8, 'v04.06.04-ml-android'); p.sint(9, 67503104);
  p.blob(10, unhex('4001000000000000')); p.sint(12, ts >>> 0); p.blob(13, f13Bytes); p.blob(14, branch.queryHash.subarray(0, 6));
  p.blob(15, count.data()); p.text(16, 'AXYQOS6n2m60x1fVZHIrH3iol'); p.sint(17, ts >>> 0);
  p.blob(19, sm3(join(Buffer.from(query, 'utf8'), branch.bodyHash, Buffer.from('none'))));
  p.text(20, 'none'); p.sint(21, 312); p.blob(23, env.data());
  p.text(24, '{"cmr":16777216,"cmr2":16777216,"un_h":1879194040,"vpn":0,"kd":0,"fkd":3672518972,"pd":-1872573247,"dyn":"","do":0,"tk":true}');
  return p.data();
}
function xmXor(data, key) {
  const out = Buffer.from(data);
  for (let i = 0; i < data.length; i++) {
    const k = (i * 4) & 28, a = u8(key[k]), b = u8(key[k + 1]);
    let v = (~(rot8(u8(data[i]), 4) + a) ^ b) & 255;
    v = (rot8(v, 3) + b) & 255;
    out[data.length - 1 - i] = ~(v ^ a) & 255;
  }
  const flag = u8(out[out.length - 1]) ^ u8(out[out.length - 2]);
  const old = u8(out[0]);
  out[0] = (out[0] + ~(flag & 255)) & 255;
  out[1] = (out[1] + (u8(out[0]) ^ u8(out[out.length - 1]) ^ 254)) & 255;
  out[2] = (out[2] + ((flag - old) ^ rot8(u8(out[1]), 3) ^ 2)) & 255;
  for (let i = 0; i < out.length - 4; i++) out[i + 3] = (out[i + 3] + ~(rot8(u8(out[i + 2]), 3) ^ u8(out[i + 1]) ^ ((i + 3) & 255))) & 255;
  out[out.length - 1] ^= out[out.length - 2];
  let sum = 0;
  for (let i = 1; i < out.length; i++) sum += u8(out[i]);
  out[0] = ((u8(out[0]) ^ u8(out[1])) + sum) & 255;
  return out;
}
function aesBlock(block, mk, con, order) {
  const b = Buffer.from(block);
  const addRound = base => { for (let j = 0; j < b.length; j++) b[j] ^= mk[base + (j / 4 | 0) * 4 + con[j % 4]]; };
  addRound(0);
  for (let round = 1; round <= 2; round++) {
    const prev = Buffer.from(b);
    for (let j = 0; j < 16; j++) { const index = order[j]; b[j] = SBOX[u8(prev[con[index / 4 | 0] * 4 + index % 4])]; }
    if (round === 1) {
      const prev2 = Buffer.from(b);
      for (let j = 0; j < 16; j++) b[j] = prev2[(j / 4 | 0) * 4 + con[j % 4]];
      for (let col = 0; col < 4; col++) {
        const a = u8(b[col]), c = u8(b[4 + col]), d = u8(b[8 + col]), e = u8(b[12 + col]);
        const t = a ^ c ^ d ^ e;
        const xt = x2 => ((x2 << 1) ^ ((x2 & 128) !== 0 ? 0x1b : 0)) & 255;
        b[col] ^= (t ^ xt(a ^ c)) & 255;
        b[4 + col] ^= (t ^ xt(c ^ d)) & 255;
        b[8 + col] ^= (t ^ xt(d ^ e)) & 255;
        b[12 + col] ^= (t ^ xt(e ^ a)) & 255;
      }
    }
    addRound(round * 16);
  }
  for (let j = 0; j < 16; j++) b[j] ^= mk[16 + j];
  return b;
}
function aesV3(data, ts) {
  if (data.length < 248) throw new Error('账号签名长度无效');
  const index = ts & 3;
  const box = SBOX.subarray(index * 256, (index + 1) * 256);
  const cons = [[1, 0, 2, 3], [2, 0, 3, 1], [0, 1, 3, 2], [1, 0, 2, 3]];
  const orders = [[0, 9, 14, 11, 4, 13, 2, 7, 8, 1, 6, 15, 12, 5, 10, 3], [0, 9, 14, 15, 4, 13, 2, 7, 8, 1, 6, 3, 12, 5, 10, 11], [0, 9, 14, 7, 4, 13, 2, 11, 8, 1, 6, 3, 12, 5, 10, 15], [0, 9, 14, 11, 4, 13, 2, 7, 8, 1, 6, 15, 12, 5, 10, 3]];
  const con = cons[index], order = orders[index];
  const init = [0xca025ddc, 0x823dc546, 0xc9420583, 0xc298225f][index];
  const key = unhex('f1593376766ea98d34f31b057a9d5be4');
  const mk = Buffer.alloc(48);
  for (let j = 0; j < 16; j++) mk[j] = key[j] ^ ((init >>> ((j % 4) * 8)) & 255);
  let r = 8;
  for (let j = 4; j < 12; j++) {
    let prev = Buffer.from(mk.subarray(j * 4 - 4, j * 4));
    if (j % 4 === 0) prev = Buffer.from([(init >>> (r & 24)) ^ box[u8(prev[1])], box[u8(prev[2])], box[u8(prev[3])], box[u8(prev[0])]]);
    r += 2;
    for (let k = 0; k < 4; k++) mk[j * 4 + k] = prev[k] ^ mk[j * 4 + k - 16];
  }
  const plain = Buffer.alloc(32);
  for (let j = 0; j < 31; j++) {
    const off = j * 8;
    plain[j] = (((u8(data[off]) >>> 4) & 2) | (u8(data[off + 1]) & 64) | ((u8(data[off + 2]) >>> 2) & 1) | ((u8(data[off + 3]) << 3) & 128) | ((u8(data[off + 4]) >>> 1) & 4) | ((u8(data[off + 5]) << 3) & 16) | ((u8(data[off + 6]) << 5) & 32) | ((u8(data[off + 7]) >>> 4) & 8));
  }
  plain[31] = 1;
  let previous = unhex('1fe109a4125283f418de9e051a969e12');
  const encrypted = Buffer.alloc(32);
  for (let j = 0; j < 32; j += 16) {
    const block = Buffer.from(data.subarray(j, j + 16));
    for (let k = 0; k < 16; k++) block[k] ^= previous[k];
    previous = aesBlock(block, mk, con, order);
    previous.copy(encrypted, j);
  }
  const out = join(Buffer.from([encrypted[31]]), data);
  for (let j = 0; j < 31; j++) {
    const off = 1 + j * 8, k = u8(encrypted[j]);
    out[off] = (u8(out[off]) & ~32) | ((k << 4) & 32);
    out[off + 1] = (u8(out[off + 1]) & ~64) | (k & 64);
    out[off + 2] = (u8(out[off + 2]) & ~4) | ((k << 2) & 4);
    out[off + 3] = (u8(out[off + 3]) & ~16) | ((k >>> 3) & 16);
    out[off + 4] = (u8(out[off + 4]) & ~8) | ((k << 1) & 8);
    out[off + 5] = (u8(out[off + 5]) & ~2) | ((k >>> 3) & 2);
    out[off + 6] = (u8(out[off + 6]) & ~1) | ((k >>> 5) & 1);
    out[off + 7] = (u8(out[off + 7]) & 127) | ((k << 4) & 128);
  }
  return out;
}
function medusa(query, ts, nonce, hashRand, xmRand, device, branch) {
  const f13Bytes = f13(branch, ts);
  let p = protoFor(query, ts, nonce, device, branch, f13Bytes);
  const signKey = unhex('8ebdfa3806ecc5cee79423e6029ed82540bc2218bb7eaef71cb691f7aa8aa2f5');
  const key = sm3(join(signKey, le32Buffer(hashRand), signKey));
  const d1 = (hashRand >>> 16) & 255;
  const seedValue = ~(((d1 << 11) | (hashRand >>> 24)) ^ (d1 >>> 5) ^ d1) | 0;
  const seed = le32Buffer(seedValue);
  p = join(unhex('4001000000000000'), xmXor(p, key));
  for (let a = 0, z = p.length - 1; a < z; a++, z--) { const t = p[a]; p[a] = p[z]; p[z] = t; }
  for (let i = 0; i < p.length; i++) p[i] ^= seed[(~i) & 3];
  let check = 0x18000001 | ((u8(branch.queryHash[0]) & 63) << 14) | ((u8(f13Bytes[0]) & 63) << 8);
  const body = join(Buffer.from([0x35]), le32Buffer(xmRand), le32Buffer(check), p, le32Buffer(hashRand).subarray(2, 4));
  const enc = aesV3(body, ts);
  const header = unhex('03000000f7e85ffad7d7dc3bd62ac87057cf6118');
  for (let j = 0; j < 20; j += 4) {
    const value = header.readInt32LE(j) ^ ts;
    header.writeInt32LE(value | 0, j);
  }
  return join(header, le32Buffer(hashRand).subarray(0, 2), Buffer.from([0, 1]), enc).toString('base64');
}
/** 账号族签名：抖动 _rticket 找 (iv&15)%3==2 的分支，输出 query+headers。 */
function signQuery(input, body, stamp, device, random) {
  if (!Array.isArray(random) || random.length !== 4) throw new Error('账号签名参数无效');
  const ts = (stamp / 1000) | 0;
  const map = { ...input };
  let raw = null, selected = null;
  for (let i = 0; i < 256; i++) {
    map._rticket = String(stamp + i);
    const candidate = encodeQuery(map);
    const b = branchOf(candidate, body, ts);
    if ((b.iv & 15) % 3 === 2) { raw = candidate; selected = b; break; }
  }
  if (raw === null) throw new Error('账号签名生成未成功');
  const headers = {
    'X-Khronos': String(ts >>> 0),
    'X-SS-Req-Ticket': String(map._rticket),
    'X-Helios': helios(ts, random[3]),
    'X-Medusa': medusa(raw, ts, random[0], random[1], random[2], device, selected),
    'X-Gorgon': gorgon(raw, body, ts, random[0] & 65535),
  };
  if (body.length > 0) headers['X-SS-STUB'] = hex(selected.bodyHash).toUpperCase();
  return { query: raw, headers };
}

// ===== PassportDevice =====
function createDevice() {
  const open = crypto.randomBytes(8);
  return {
    cdid: crypto.randomUUID(), clientudid: crypto.randomUUID(),
    openudid: hex(open), created: Date.now(),
  };
}
function devicePayload(device, stamp) {
  const header = Object.fromEntries([['access', 'wifi'], ['app_version', '7.3.5.32'], ['app_version_minor', ''], ['channel', 'update_64'], ['device_brand', 'Redmi'], ['device_manufacturer', 'Redmi'], ['device_model', '25053RT47C'], ['device_category', 'phone'], ['device_platform', 'android'], ['os', 'Android'], ['os_version', '16'], ['display_density', 'xxxhdpi'], ['resolution', '2772x1280'], ['language', 'zh'], ['tz_name', 'Asia/Shanghai'], ['region', 'CN'], ['package', 'com.phoenix.read'], ['cpu_abi', 'arm64-v8a'], ['display_name', '红果短剧'], ['sdk_version', '3.7.2'], ['sdk_flavor', 'china']]
    .map(([key, value]) => [key, value]));
  const numeric = [['aid', 8662], ['os_api', 36], ['density_dpi', 520], ['timezone', 8], ['tz_offset', 28800], ['version_code', 73532], ['manifest_version_code', 73532], ['update_version_code', 73532], ['sdk_target_version', 29], ['guest_mode', 0], ['is_system_app', 0], ['not_request_sender', 0]];
  for (const [key, value] of numeric) header[key] = value;
  header.apk_first_install_time = device.created;
  header.cdid = device.cdid; header.clientudid = device.clientudid; header.openudid = device.openudid;
  header.oaid_may_support = true; header.ipv6_list = []; header.sim_serial_number = [];
  header.custom = { is_android_fold: 0, filter_warn: 0, web_ua: '', is_android_pad: 0, client_ipv4: '' };
  return Buffer.from(JSON.stringify({ magic_tag: 'ss_app_log', header, _gen_time: stamp }), 'utf8');
}
function derivedKey(seed) {
  if (!seed || seed.length !== 32) throw new Error('设备加密参数无效');
  return sha512(join(sha512(seed), SALT));
}
function gzipZeroed(plain) {
  const compressed = zlib.gzipSync(plain, { level: 9 });
  // 与 Java best=true 一致：固定 10 字节头（mtime=0、XFL=0、OS=0）
  Buffer.from([0x1f, 0x8b, 8, 0, 0, 0, 0, 0, 0, 0]).copy(compressed, 0, 0, 10);
  return compressed;
}
function encryptDevicePayload(plain, seed) {
  const compressed = gzipZeroed(plain);
  const hash = sha512(compressed);
  const derived = derivedKey(seed);
  // Java：IV 取自 key(seed) 的 [16:32]，不随包传输；输出 = MAGIC + seed + AES-CBC(PKCS5)(sha512(gz) || gz)
  const cipher = crypto.createCipheriv('aes-128-cbc', derived.subarray(0, 16), derived.subarray(16, 32));
  const encrypted = Buffer.concat([cipher.update(join(hash, compressed)), cipher.final()]);
  return join(MAGIC, seed, encrypted);
}
function decryptDeviceResponse(wire) {
  if (!wire || wire.length < 118 || !wire.subarray(0, 6).equals(MAGIC) || (wire.length - 38) % 16 !== 0) throw new Error('设备注册加密响应无效');
  const derived = derivedKey(wire.subarray(6, 38));
  const decipher = crypto.createDecipheriv('aes-128-cbc', derived.subarray(0, 16), derived.subarray(16, 32));
  decipher.setAutoPadding(true);
  const plain = Buffer.concat([decipher.update(wire.subarray(38)), decipher.final()]);
  if (plain.length < 64 || !plain.subarray(0, 64).equals(sha512(plain.subarray(64)))) throw new Error('设备注册加密响应校验失败');
  return zlib.gunzipSync(plain.subarray(64));
}

// ===== 注册与已登记单集模型 =====
function baseQuery(device) {
  return {
    ac: 'wifi', aid: '8662', app_name: 'novelread', channel: 'update_64', device_brand: 'Redmi',
    device_platform: 'android', device_type: '25053RT47C', dpi: '520', language: 'zh',
    manifest_version_code: '73532', os: 'android', os_api: '36', os_version: '16',
    resolution: '1280*2772', ssmix: 'a', update_version_code: '73532', version_code: '73532',
    version_name: '7.3.5.32', cdid: device.cdid, openudid: device.openudid,
  };
}

/**
 * 已登记设备的业务请求（安卓 cloud() 同构）：
 * baseQuery + PassportSigner 完整签名（Gorgon/Helios/Medusa）+ 安卓同款头与 Cookie。
 * 注意：desktop app-source 的 xGorgon 变体只服务于未登记的合成身份；
 * 已登记身份必须走本函数这套完整签名，服务端才返回模型数据。
 */
async function registeredCloudRequest({ axios, path, body, identity, cookie }) {
  const payload = Buffer.from(body || '', 'utf8');
  const stamp = Date.now();
  const ts = (stamp / 1000) | 0;
  const random = [crypto.randomBytes(2).readUInt16LE(0), crypto.randomBytes(2).readUInt16LE(0), crypto.randomBytes(2).readUInt16LE(0), crypto.randomBytes(2).readUInt16LE(0)];
  const query = { ...baseQuery(identity.device), device_id: identity.deviceId, iid: identity.installId };
  const signed = signQuery(query, payload, stamp, identity.deviceId, random);
  const headers = {
    ...signed.headers,
    'User-Agent': UA,
    'Accept': 'application/json',
    'X-XS-From-Web': '0',
    'Sdk-Version': '2',
    'Passport-Sdk-Version': '6031190',
    'X-SS-DP': '8662',
    'X-Tt-Store-Region': 'cn',
    'X-Tt-Store-Region-Src': 'did',
    'Content-Type': 'application/json; charset=utf-8',
  };
  const cookieParts = [];
  if (cookie) cookieParts.push(cookie);
  if (identity.deviceToken) cookieParts.push('device_token=' + identity.deviceToken);
  cookieParts.push('install_id=' + identity.installId);
  headers.Cookie = cookieParts.join('; ');
  const response = await axios.post('https://api5-normal-lq.fqnovel.com' + path + '?' + signed.query, payload, {
    headers, timeout: 15000, responseType: 'text', validateStatus: () => true, maxContentLength: 8 * 1024 * 1024,
  });
  return response;
}
async function registerDevice({ axios }) {
  const device = createDevice();
  const stamp = Date.now();
  const seed = crypto.randomBytes(32);
  const body = encryptDevicePayload(devicePayload(device, stamp), seed);
  const query = baseQuery(device);
  delete query.device_id; delete query.iid;
  query.tt_data = 'a'; query.ts = String((stamp / 1000) | 0); query._rticket = String(stamp); query.package = 'com.phoenix.read';
  const raw = encodeQuery(query);
  const ts = (stamp / 1000) | 0;
  const random = [crypto.randomBytes(2).readUInt16LE(0), 0, 0, crypto.randomBytes(2).readUInt16LE(0)];
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
    'X-Khronos': String(ts >>> 0),
    'X-Argus': le32Buffer(ts).toString('base64'),
    'X-SS-Req-Ticket': String(stamp),
    'X-SS-STUB': hex(md5(body)).toUpperCase(),
    'X-Gorgon': gorgon(raw, body, ts, random[0] & 65535),
  };
  const response = await axios.post(REGISTER + '?' + raw, body, { headers, timeout: 20000, responseType: 'arraybuffer', validateStatus: () => true });
  if (response.status !== 200) throw new Error('设备注册响应 HTTP ' + response.status);
  // 注册下发的会话凭据（Set-Cookie）与 device_token 必须在后续模型请求里带上，
  // 否则服务端按未登记设备处理并返回空响应（安卓版 saveCookies 同义）。
  const setCookie = response.headers['set-cookie'];
  const cookie = Array.isArray(setCookie) ? setCookie.map(item => item.split(';')[0]).join('; ') : String(setCookie || '').split(';')[0];
  let wire = Buffer.from(response.data);
  if (response.headers['content-encoding'] === 'gzip') wire = zlib.gunzipSync(wire);
  if (wire.subarray(0, 6).equals(MAGIC)) wire = decryptDeviceResponse(wire);
  const text = wire.toString('utf8');
  const pick = key => {
    const strMatch = new RegExp('"' + key + '_str"\\s*:\\s*"([1-9][0-9]{0,29})"').exec(text);
    if (strMatch) return strMatch[1];
    const numMatch = new RegExp('"' + key + '"\\s*:\\s*"?([1-9][0-9]{0,29})"?').exec(text);
    return numMatch ? numMatch[1] : '';
  };
  const deviceId = pick('device_id'), installId = pick('install_id');
  if (!deviceId || !installId) throw new Error('设备注册未返回有效身份');
  const tokenMatch = /"device_token"\s*:\s*"([^"]+)"/.exec(text);
  const deviceToken = tokenMatch ? tokenMatch[1] : '';
  return { deviceId, installId, device, cookie, deviceToken };
}

module.exports = { registerDevice, signQuery, registeredCloudRequest, sm3, gorgon, hex };
