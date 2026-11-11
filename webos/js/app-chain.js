/**
 * webOS App 链（官方加密片源）——设备注册 + 签名 + 模型请求。
 *
 * 能力边界（实测结论）：
 *  - 设备注册与签名在浏览器里可以完整跑通（本文件已逐字节对齐桌面实现）。
 *  - 但注册响应带来的会话凭据属于 log.snssdk.com 域，浏览器无法读取跨域 Set-Cookie，
 *    也不能替 fqnovel.com 设置它；服务端又不返回 CORS 凭据头。
 *    因此纯浏览器直连拿不到模型（服务端返回空体）。
 *  - 解法：把「注册 + 模型」两步放到一个同源中转上（配置 appProxy 即可），
 *    浏览器只与中转通信，签名与凭据都由中转保管。
 *
 * 中转契约（POST，JSON）：
 *   请求 { vid, seriesId, quality }
 *   响应 { ok:true, items:[{url, quality, spade, contentKey, userAgent, referer}] }
 *                或 { ok:false, error }
 */
(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory(require('./signer.js'), require('./passport.js'));
  else root.HongguoAppChain = factory(root.HongguoSigner, root.HongguoPassport);
})(typeof self !== 'undefined' ? self : this, function (signer, passport) {
  'use strict';

  const MODEL_API = 'https://api5-normal-sinfonlineb.fqnovel.com/novel/player/multi_video_model/v1/';

  /** 直连模型请求（仅在能自带会话凭据的环境可用，例如中转服务内）。 */
  async function fetchModelDirect(vid, identity, { fetchImpl } = {}) {
    const doFetch = fetchImpl || fetch.bind(globalThis);
    const payload = JSON.stringify({
      biz_param: { detail_page_version: 0, device_level: 3, disable_digg_stat: false, need_all_video_definition: true, need_mp4_align: false, use_os_player: false, use_server_dns: false, video_platform: 1024 },
      mixed_video_id_map: { '1004': [vid] },
    });
    const signed = signer.signAppRequest(MODEL_API, payload, { deviceId: identity.deviceId, installId: identity.installId });
    const cookie = [identity.cookie, identity.deviceToken ? 'device_token=' + identity.deviceToken : '', 'install_id=' + identity.installId].filter(Boolean).join('; ');
    const response = await doFetch(signed.url, { method: 'POST', headers: { ...signed.headers, Cookie: cookie }, body: signed.body, credentials: 'include' });
    const text = await response.text();
    if (text.length < 8) throw new Error('模型接口返回空响应');
    const body = JSON.parse(text);
    const entry = body.data && body.data[vid];
    const model = entry ? (typeof entry.video_model === 'string' ? JSON.parse(entry.video_model) : entry.video_model) : null;
    if (!model || !model.video_list) throw new Error('模型未返回本集片源');
    return model;
  }

  /** 模型 → 可播放条目列表（解密信息原样带出，供播放端处理）。 */
  function modelItems(model, vid) {
    const items = [];
    for (const row of Object.values(model.video_list || {})) {
      if (!row) continue;
      const url = row.main_url || row.backup_url || (Array.isArray(row.backup_url_list) ? row.backup_url_list[0] : '');
      if (!url) continue;
      items.push({
        url, vid: String(vid),
        quality: row.quality_desc || row.definition || row.height || '',
        codec: row.codec_type || '',
        width: Number(row.vwidth || row.width || 0),
        height: Number(row.vheight || row.height || 0),
        spade: row.spade_a || row.encrypt_info && row.encrypt_info.spade_a || '',
        contentKey: row.encrypt_info && row.encrypt_info.content_key || '',
        userAgent: 'com.phoenix.read/71332 (Linux; U; Android 16; zh_CN; 25053RT47C)',
        referer: '',
      });
    }
    return items;
  }

  /**
   * 取本集片源：有中转走中转；无中转返回 null（调用方回落官网链路）。
   */
  async function resolveEpisode(vid, { proxy, fetchImpl } = {}) {
    if (!proxy) return null;
    const doFetch = fetchImpl || fetch.bind(globalThis);
    const response = await doFetch(proxy.replace(/\/$/, '') + '/episode', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      credentials: 'include',
      body: JSON.stringify({ vid: String(vid) }),
    });
    const body = await response.json().catch(() => null);
    if (!body || !body.ok || !Array.isArray(body.items) || !body.items.length) throw new Error((body && body.error) || '中转未返回片源');
    return body.items;
  }

  return { resolveEpisode, fetchModelDirect, modelItems, MODEL_API };
});
