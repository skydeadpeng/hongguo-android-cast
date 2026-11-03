#!/usr/bin/env node
/**
 * webOS 浏览器版加密层对拍：MD5 / SM3 / Gorgon 必须与桌面实现字节级一致。
 * 桌面那份已被 landpage/模型接口实测验证，字节不一致就等于协议不通。
 */
const assert = require('assert');
const path = require('path');
const crypto = require('crypto');

const primitives = require(path.join(__dirname, '..', 'webos', 'js', 'crypto-primitives.js'));
const signer = require(path.join(__dirname, '..', 'webos', 'js', 'signer.js'));
const desktopSm3 = require(path.join(__dirname, '..', 'src', 'native', 'sm3.js'));
const appSource = require(path.join(__dirname, '..', 'src', 'native', 'app-source.js'));

let checks = 0;
const check = (value, message) => { checks++; if (!value) throw new Error(message); };

// ===== MD5 =====
for (const sample of [Buffer.alloc(0), Buffer.from('abc'), Buffer.from('中文 payload'), crypto.randomBytes(200)]) {
  const expected = crypto.createHash('md5').update(sample).digest('hex');
  const actual = primitives.md5Hex(new Uint8Array(sample));
  check(expected === actual, 'MD5 mismatch for length ' + sample.length);
}

// ===== SM3 =====
for (const sample of [Buffer.alloc(0), Buffer.from('abc'), Buffer.from('a'.repeat(200)), Buffer.from('中文'), Buffer.from('x'.repeat(64))]) {
  const expected = desktopSm3(sample).toString('hex');
  const actual = Buffer.from(signer.sm3(new Uint8Array(sample))).toString('hex');
  check(expected === actual, 'SM3 mismatch for length ' + sample.length);
}

// ===== Gorgon =====
const query = 'ac=wifi&aid=8662&app_name=novelread&device_id=7701935931787316286&iid=7701935931787316286&ts=1793666418&_rticket=1793666418000';
const body = Buffer.from(JSON.stringify({ magic_tag: 'ss_app_log', header: { a: 1 } }), 'utf8');
for (const [stamp, random] of [[1793666418, 54321], [1700000000, 1], [1750000000, 65535], [1793666418, 12345]]) {
  const expected = appSource.__xGorgon(query, body, stamp, random);
  const actual = signer.xGorgon(query, new Uint8Array(body), stamp, random);
  check(expected === actual, `Gorgon mismatch ts=${stamp} random=${random}`);
}

// ===== gzip(stored) 必须能被标准 zlib 解开 =====
const zlib = require('zlib');
const payload = Buffer.from(JSON.stringify({ magic_tag: 'ss_app_log', header: { a: 1 }, n: 999 }));
const gz = Buffer.from(primitives.gzipStored(new Uint8Array(payload)));
const back = zlib.gunzipSync(gz);
check(back.equals(payload), 'gzip(stored) round trip failed');

console.log(`WEBOS_CRYPTO_OK checks=${checks}`);
