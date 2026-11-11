// Gorgon 对拍：桌面 xGorgon（已被 landpage 实测验证）vs webOS 浏览器版
const fs = require('fs');
const path = require('path');
const Module = require('module');
const appSourcePath = path.join(__dirname, '..', 'src', 'native', 'app-source-registered.js');
// 以真实模块加载 app-source，再用其内部导出做对照（保持 require 相对路径可用）。
const appSource = require(appSourcePath);
const mine = require(path.join(__dirname, '..', 'webos', 'js', 'signer.js'));
const query = 'ac=wifi&aid=8662&app_name=novelread&device_id=7701935931787316286&iid=7701935931787316286&ts=1793666418&_rticket=1793666418000';
const body = Buffer.from(JSON.stringify({ magic_tag: 'ss_app_log', header: { a: 1 } }), 'utf8');
let ok = 0, total = 0;
for (const [ts, rnd] of [[1793666418, 54321], [1700000000, 1], [1750000000, 65535], [1793666418, 12345]]) {
  total++;
  const a = appSource.__xGorgon(query, body, ts, rnd);
  const b = mine.xGorgon(query, new Uint8Array(body), ts, rnd);
  if (a === b) ok++; else console.log(`MISMATCH ts=${ts} rnd=${rnd}\n  桌面: ${a}\n  我的: ${b}`);
}
console.log(`Gorgon 对拍: ${ok}/${total}`, ok === total ? 'ALL MATCH' : 'FAIL');
process.exit(ok === total ? 0 : 1);
