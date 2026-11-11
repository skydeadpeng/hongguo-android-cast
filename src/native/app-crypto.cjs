/**
 * 渲染器 App 链的加密组件（安卓 AndroidCrypto 的桌面同构实现）。
 * 算法说明：MD5/SHA-512/AES-128-CBC 是红果 App 接口签名的既有协议要求，
 * 本文件是对该协议的客户端模拟，不用于任何新的安全防护。
 */
const crypto = require('crypto');

/** 安卓 AndroidCrypto.process 的同款 JSON 契约。 */
function appCryptoProcess(request) {
  try {
    const data = Buffer.from(String(request && request.data || ''), 'base64');
    if (request.operation === 'digest') {
      const algorithm = request.algorithm === 'MD5' ? 'md5' : request.algorithm === 'SHA-512' ? 'sha512' : null;
      if (!algorithm) return { error: '未支持该摘要算法' };
      return { data: crypto.createHash(algorithm).update(data).digest('base64') };
    }
    if (request.operation === 'decrypt' && request.algorithm === 'AES-128-CBC') {
      const key = Buffer.from(String(request.key || ''), 'base64');
      const iv = Buffer.from(String(request.iv || ''), 'base64');
      if (key.length !== 16 || iv.length !== 16) return { error: '密钥或初始向量长度无效' };
      const decipher = crypto.createDecipheriv('aes-128-cbc', key, iv);
      decipher.setAutoPadding(request.autoPadding !== false);
      return { data: Buffer.concat([decipher.update(data), decipher.final()]).toString('base64') };
    }
    return { error: '未支持的加密操作' };
  } catch (error) { return { error: error && error.message || '加密操作未成功' }; }
}

module.exports = { appCryptoProcess };
