#!/usr/bin/env node
/**
 * webOS 全量正片中转服务。
 *
 * 浏览器拿不到官方注册会话（跨域 Cookie），本服务在服务端完成设备注册、
 * 会话维护与模型请求，向 webOS 客户端暴露一个极简 JSON 接口：
 *
 *   GET  /health                 → { ok: true, deviceId }
 *   POST /episode { vid }        → { ok:true, items:[{url,quality,width,height,codec,extra}] }
 *
 * 复用桌面端已实测的签名链（passport-register + app-source signedAppRequest）。
 * 零外部依赖（仅 axios）；Node 18+ 直接 `node server.cjs` 即可运行。
 */
const http = require('http');
const path = require('path');
const fs = require('fs');
const axios = require('axios');

const PORT = Number(process.env.PORT || 8787);
const STATE_DIR = process.env.STATE_DIR || path.join(__dirname, 'state');
const IDENTITY_FILE = path.join(STATE_DIR, 'passport-device.json');

const passport = require(path.join(__dirname, '..', '..', 'src', 'native', 'passport-register.cjs'));
const appSource = require(path.join(__dirname, '..', '..', 'src', 'native', 'app-source.js'));

const MODEL_API = 'https://api5-normal-sinfonlineb.fqnovel.com/novel/player/multi_video_model/v1/';

let identityPromise = null;
function ensureIdentity() {
  if (!identityPromise) {
    identityPromise = (async () => {
      try {
        const saved = JSON.parse(fs.readFileSync(IDENTITY_FILE, 'utf8'));
        if (saved && saved.deviceId && saved.installId && saved.cookie) return saved;
      } catch { }
      return await reRegister();
    })();
    identityPromise.catch(() => { }).finally(() => { identityPromise = null; });
  }
  return identityPromise;
}
async function reRegister() {
  const identity = await passport.registerDevice({ axios });
  identity.registeredAt = Date.now();
  fs.mkdirSync(STATE_DIR, { recursive: true });
  fs.writeFileSync(IDENTITY_FILE, JSON.stringify(identity, null, 2));
  console.log('[relay] 设备注册成功 device_id=' + identity.deviceId);
  return identity;
}
function identityOrNull() {
  try {
    const saved = JSON.parse(fs.readFileSync(IDENTITY_FILE, 'utf8'));
    if (saved && saved.deviceId && saved.installId && saved.cookie) return saved;
  } catch { }
  return null;
}

async function fetchModel(vid) {
  let identity = identityOrNull() || (await ensureIdentity());
  const payload = JSON.stringify({
    biz_param: { detail_page_version: 0, device_level: 3, disable_digg_stat: false, need_all_video_definition: true, need_mp4_align: false, use_os_player: false, use_server_dns: false, video_platform: 1024 },
    mixed_video_id_map: { '1004': [String(vid)] },
  });
  let lastError = null;
  for (let attempt = 0; attempt < 3; attempt++) {
    appSource.setDeviceIdentity({ device_id: identity.deviceId, iid: identity.installId });
    try {
      const cookieParts = [identity.cookie, identity.deviceToken ? 'device_token=' + identity.deviceToken : '', 'install_id=' + identity.installId].filter(Boolean).join('; ');
      const signed = appSource.signedAppRequest(MODEL_API, payload);
      const response = await axios.post(signed.url, signed.body, {
        headers: { ...signed.headers, Cookie: cookieParts },
        timeout: 15000, responseType: 'text', validateStatus: () => true, maxContentLength: 8 * 1024 * 1024,
      });
      const text = typeof response.data === 'string' ? response.data : JSON.stringify(response.data);
      if (!text || text.length < 8) { lastError = new Error('模型空响应'); identity = await reRegister(); continue; }
      const body = JSON.parse(text);
      if (Number(body.Code ?? body.code ?? -1) !== 0) throw new Error('模型请求未通过');
      const entry = body.data && body.data[vid];
      const model = entry ? (typeof entry.video_model === 'string' ? JSON.parse(entry.video_model) : entry.video_model) : null;
      if (!model || !model.video_list) { lastError = new Error('模型未返回本集'); identity = await reRegister(); continue; }
      return model;
    } catch (error) {
      lastError = error;
      console.log('[relay] 模型请求失败 attempt=' + attempt, error.message);
    } finally { appSource.setDeviceIdentity(null); }
  }
  throw lastError || new Error('模型请求未成功');
}

function modelToItems(model, vid) {
  const items = [];
  for (const row of Object.values(model.video_list || {})) {
    if (!row) continue;
    const url = row.main_url || row.backup_url || (Array.isArray(row.backup_url_list) && row.backup_url_list[0]) || '';
    if (!url) continue;
    items.push({
      url, vid: String(vid),
      quality: row.quality_desc || row.definition || '',
      codec: row.codec_type || '',
      width: Number(row.vwidth || row.width || 0),
      height: Number(row.vheight || row.height || 0),
      spade: row.spade_a || '',
      contentKey: row.encrypt_info && row.encrypt_info.content_key || '',
      extra: row.encrypt_info ? JSON.stringify(row.encrypt_info) : '',
    });
  }
  return items;
}

const server = http.createServer((req, res) => {
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET,POST,OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type');
  if (req.method === 'OPTIONS') { res.writeHead(204); res.end(); return; }

  if (req.url === '/health') {
    const saved = identityOrNull();
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ ok: true, service: 'hongguo-relay', deviceId: saved && saved.deviceId || null }));
    return;
  }

  if (req.method === 'POST' && req.url === '/episode') {
    let raw = '';
    req.on('data', c => { raw += c; if (raw.length > 16384) req.destroy(); });
    req.on('end', async () => {
      try {
        const { vid } = JSON.parse(raw || '{}');
        if (!/^\d{1,30}$/.test(String(vid || ''))) throw new Error('vid 无效');
        const model = await fetchModel(vid);
        const items = modelToItems(model, vid);
        if (!items.length) throw new Error('模型未返回可用片源');
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ ok: true, items }));
      } catch (error) {
        res.writeHead(502, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ ok: false, error: error.message || '中转失败' }));
      }
    });
    return;
  }

  res.writeHead(404, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify({ ok: false, error: 'not found' }));
});

server.listen(PORT, () => {
  console.log('[relay] webOS 中转服务已启动: http://0.0.0.0:' + PORT);
  console.log('[relay] 健康检查: /health  片源: POST /episode {"vid":"..."}');
  ensureIdentity().catch(error => console.log('[relay] 初始注册失败（首次请求时重试）:', error.message));
});
