/**
 * 内置 FFmpeg 单次调用（CENC 解密转码拷贝）。
 * 安全说明：可执行文件来自固定的打包资源路径白名单；参数为静态数组；
 * decryptionKey 在调用前已通过 32 位十六进制强校验（见调用方）。
 */
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');

function ffmpegExecutable() {
  const roots = process.resourcesPath ? [process.resourcesPath] : [path.join(__dirname, '..', '..')];
  for (const root of roots) {
    // 打包布局：build/ffmpeg/darwin-<arch> → resources/bin/ffmpeg；开发布局：build/ffmpeg/...
    for (const candidate of [path.join(root, 'bin', 'ffmpeg'), path.join(root, 'bin', 'ffmpeg.exe'), path.join(root, 'ffmpeg', 'ffmpeg')]) {
      if (fs.existsSync(candidate)) return candidate;
    }
    for (const arch of [process.arch, 'arm64', 'x64']) {
      const candidate = path.join(root, 'build', 'ffmpeg', `darwin-${arch}`, 'ffmpeg');
      if (fs.existsSync(candidate)) return candidate;
    }
  }
  return 'ffmpeg';
}

/** 用内置 ffmpeg 完成 CENC 解密（-c copy 流拷贝，不重编码）。 */
function decryptCencFile(input, output, decryptionKey, onLog) {
  return new Promise((resolve, reject) => {
    if (!/^[a-f\d]{32}$/i.test(String(decryptionKey || ''))) { reject(new Error('片源解密密钥格式无效')); return; }
    const args = ['-y', '-hide_banner', '-loglevel', 'error', '-decryption_key', String(decryptionKey),
      '-i', String(input), '-map', '0:v:0', '-map', '0:a?', '-c', 'copy', '-movflags', '+faststart', '-f', 'mp4', String(output)];
    const { spawn } = require('child_process');
    const child = spawn(ffmpegExecutable(), args, { windowsHide: true, stdio: ['ignore', 'ignore', 'pipe'] });
    let stderr = '';
    child.stderr.on('data', chunk => { stderr += String(chunk); if (onLog && stderr.length < 4000) onLog(String(chunk)); });
    child.on('error', reject);
    child.on('close', code => code === 0 ? resolve() : reject(new Error('FFmpeg 解密失败（' + code + '）：' + stderr.slice(0, 200))));
  });
}

/**
 * 加密片源的整段下载与解密（调用方只传播放信息对象，密钥在本模块内校验）。
 * 返回 {kind:'buffer', buffer} 供自定义协议整段供给。
 */
async function prepareDecryptedMedia(playInfo, { axios, signal, maxBytes } = {}) {
  const axiosGet = axios && axios.get ? axios.get : require('axios').get;
  const limit = maxBytes || 500 * 1024 * 1024;
  const url = String(playInfo && playInfo.url || '');
  if (!/^https:\/\//.test(url) || url.length > 4096) throw new Error('片源地址无效');
  const contentKey = String(playInfo && playInfo.contentKey || '');
  if (!/^[a-f\d]{32}$/i.test(contentKey)) throw new Error('片源解密密钥格式无效');
  const response = await axiosGet(url, {
    responseType: 'stream', timeout: 60000, maxRedirects: 5,
    headers: { 'User-Agent': String(playInfo.userAgent || 'Mozilla/5.0').slice(0, 512), ...(playInfo.referer ? { Referer: String(playInfo.referer).slice(0, 2048) } : {}) },
  });
  const total = Number(response.headers['content-length']) || 0;
  if (total > limit) { response.data.destroy(); throw new Error('本集过大，请下载后播放'); }
  const chunks = []; let received = 0;
  for await (const chunk of response.data) {
    received += chunk.length;
    if (received > limit) { response.data.destroy(); throw new Error('本集过大，请下载后播放'); }
    chunks.push(chunk);
    if (signal && signal.aborted) { response.data.destroy(); throw new Error('已取消'); }
  }
  if (!received) throw new Error('视频下载不完整，请重试');
  const encrypted = Buffer.concat(chunks);
  const fsp = require('fs/promises');
  const os = require('os');
  const temporary = await fsp.mkdtemp(path.join(os.tmpdir(), 'hongguo-decrypt-'));
  try {
    const input = path.join(temporary, 'encrypted.mp4'), output = path.join(temporary, 'decoded.mp4');
    await fsp.writeFile(input, encrypted);
    await decryptCencFile(input, output, contentKey);
    return { kind: 'buffer', buffer: await fsp.readFile(output) };
  } finally { fsp.rm(temporary, { recursive: true, force: true }).catch(() => { }); }
}

/**
 * 安卓同款"下载到本地再播放"：整集取回（加密源就地解密）写入 destination 文件。
 * 明文源流式直写；CENC（contentKey）与 spadeA 在本模块内校验密钥并解密。
 * 返回 {bytes}。调用方只传播放信息对象与目标路径。
 */
async function downloadEpisodeToFile(playInfo, destination, { axios, signal, onProgress } = {}) {
  const axiosGet = axios && axios.get ? axios.get : require('axios').get;
  const limit = 500 * 1024 * 1024;
  const url = String(playInfo && playInfo.url || '');
  if (!/^https:\/\//.test(url) || url.length > 4096) throw new Error('片源地址无效');
  const contentKey = String(playInfo && playInfo.contentKey || '');
  const spadeA = String(playInfo && playInfo.spadeA || '');
  if (contentKey && !/^[a-f\d]{32}$/i.test(contentKey)) throw new Error('片源解密密钥格式无效');
  const response = await axiosGet(url, {
    responseType: 'stream', timeout: 60000, maxRedirects: 5,
    headers: { 'User-Agent': String(playInfo.userAgent || 'Mozilla/5.0').slice(0, 512), ...(playInfo.referer ? { Referer: String(playInfo.referer).slice(0, 2048) } : {}) },
  });
  const total = Number(response.headers['content-length']) || 0;
  if (total > limit) { response.data.destroy(); throw new Error('本集过大，请下载后播放'); }
  let progressAt = 0, received = 0;
  response.data.on('data', chunk => {
    received += chunk.length;
    if (onProgress && Date.now() - progressAt >= 200) { progressAt = Date.now(); try { onProgress(received, total); } catch { } }
  });
  const fsp = require('fs/promises');
  const pipeline = require('stream/promises').pipeline;
  // 临时名必须唯一：预取与手动切集可能同时下载同一集，共用固定名会互相覆盖，
  // 一个流程清理临时文件时另一个的 ffmpeg 正在读它（No such file or directory）。
  const unique = crypto.randomBytes(6).toString('hex');
  const stem = path.basename(destination);
  const encryptedPath = contentKey || spadeA ? path.join(path.dirname(destination), `incoming-${unique}-${stem}`) : destination;
  const decodedPath = path.join(path.dirname(destination), `decoded-${unique}-${stem}`);
  // 明文源也先落临时名再改名：中途退出的半截文件不能留在缓存目录里被当成完整集。
  const plainTempPath = path.join(path.dirname(destination), `incoming-${unique}-${stem}`);
  try {
    await pipeline(response.data, fs.createWriteStream(contentKey || spadeA ? encryptedPath : plainTempPath, { mode: 0o600 }), { signal });
    if (contentKey) {
      await decryptCencFile(encryptedPath, decodedPath, contentKey);
      await fsp.rename(decodedPath, destination);
    } else if (spadeA) {
      const { decryptInWorker } = require('./decrypt-worker.cjs');
      const buffer = await fsp.readFile(encryptedPath);
      const key = (() => { try { return require('./hongguo').deriveKey(spadeA); } catch { return null; } })();
      if (!key) throw new Error('密钥派生失败');
      const decrypted = await decryptInWorker({ buffer, key }, { signal });
      await fsp.writeFile(decodedPath, decrypted, { mode: 0o600 });
      await fsp.rename(decodedPath, destination);
    } else {
      await fsp.rename(plainTempPath, destination);
    }
    const stat = await fsp.stat(destination);
    if (!stat.size) throw new Error('视频下载不完整，请重试');
    return { bytes: stat.size };
  } finally {
    if (encryptedPath !== destination) fsp.rm(encryptedPath, { force: true }).catch(() => { });
    if (plainTempPath !== destination) fsp.rm(plainTempPath, { force: true }).catch(() => { });
    fsp.rm(decodedPath, { force: true }).catch(() => { });
  }
}

module.exports = { decryptCencFile, ffmpegExecutable, prepareDecryptedMedia, downloadEpisodeToFile };
