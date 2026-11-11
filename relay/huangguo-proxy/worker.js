// 黄果官方站 Cloudflare Worker 代理：API 透传 + 封面代理 + m3u8 改写 + 分片透传。
// 用途：绕过部分运营商网络对 huangguoai.com 及内容 CDN 的连接重置。
// 部署后把 https://<你的Worker域名> 加入 App 备用线路即可，路径与官方 API 完全一致。
const UPSTREAM = "https://huangguoai.com";

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);
    const path = url.pathname;
    if (path === "/__health") return json({ ok: true });
    // 2026-11 官方取流端点仅收 POST（JSON 体）：浏览器跨域 POST 会先发 CORS 预检，直接放行。
    if (request.method === "OPTIONS") return new Response(null, { status: 204, headers: {
      "Access-Control-Allow-Origin": "*", "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
      "Access-Control-Allow-Headers": "Content-Type", "Access-Control-Max-Age": "86400",
    } });
    if (path.startsWith("/api/")) return proxyApi(request, url);
    if (path === "/img") return proxyImage(url);
    if (path === "/m3u8") return proxyPlaylist(url, ctx);
    if (path === "/seg") return proxySegment(url);
    return json({ error: "not found" }, 404);
  },
};

async function proxyApi(request, url) {
  const target = new URL(UPSTREAM + url.pathname);
  target.search = url.search;
  const init = { method: request.method, headers: { "User-Agent": "Mozilla/5.0", Referer: UPSTREAM + "/" } };
  if (request.method === "POST") {
    init.body = await request.text();
    if (init.body) init.headers["Content-Type"] = request.headers.get("Content-Type") || "application/json";
  }
  const response = await fetch(target, init);
  const headers = { "Content-Type": "application/json", "Access-Control-Allow-Origin": "*" };
  // POST 响应含时效签名地址，不进缓存；GET 维持原有的 30 秒边缘/浏览器缓存。
  if (request.method !== "POST") headers["Cache-Control"] = "public, max-age=30";
  return new Response(response.body, { status: response.status, headers });
}

// 官方站封面为 AES-CBC 加密二进制（密钥来自站点前端常量）：解密后返回明文图片，
// 解密失败（未加密内容）时原样透传，所有客户端无需自行实现解密。
const IMG_KEY = "f5d965df75336270";
const IMG_IV = "97b60394abc2fbe1";

async function proxyImage(url) {
  const target = url.searchParams.get("url") || "";
  if (!/^https:\/\/[\w.-]+\/[\s\S]*$/i.test(target)) return new Response("bad url", { status: 400 });
  const response = await fetch(target, { headers: { "User-Agent": "Mozilla/5.0" } });
  const buffer = await response.arrayBuffer();
  const bytes = new Uint8Array(buffer);
  const isPlainImage = (bytes[0] === 0xff && bytes[1] === 0xd8) || (bytes[0] === 0x89 && bytes[1] === 0x50) || (bytes[0] === 0x52 && bytes[1] === 0x49);
  if (isPlainImage || bytes.length === 0 || bytes.length % 16 !== 0) {
    return new Response(buffer, { status: response.status,
      headers: { "Content-Type": response.headers.get("Content-Type") || "image/jpeg", "Access-Control-Allow-Origin": "*", "Cache-Control": "public, max-age=86400" } });
  }
  try {
    const key = await crypto.subtle.importKey("raw", new TextEncoder().encode(IMG_KEY), { name: "AES-CBC" }, false, ["decrypt"]);
    const decrypted = await crypto.subtle.decrypt({ name: "AES-CBC", iv: new TextEncoder().encode(IMG_IV) }, key, buffer);
    const plain = new Uint8Array(decrypted);
    const jpeg = plain[0] === 0xff && plain[1] === 0xd8;
    const png = plain[0] === 0x89 && plain[1] === 0x50;
    if (jpeg || png) {
      return new Response(decrypted, { status: 200,
        headers: { "Content-Type": jpeg ? "image/jpeg" : "image/png", "Access-Control-Allow-Origin": "*", "Cache-Control": "public, max-age=86400" } });
    }
  } catch (ignored) { /* 未按块对齐或非本密钥加密：回退原始字节 */ }
  return new Response(buffer, { status: response.status,
    headers: { "Content-Type": response.headers.get("Content-Type") || "image/octet-stream", "Access-Control-Allow-Origin": "*", "Cache-Control": "public, max-age=86400" } });
}

// m3u8：改写分片与 AES key 的绝对地址为本 Worker 代理；同时在后台把分片预取进边缘缓存，
// 播放器到达时省去逐分片回源往返（免费档单请求 subrequest 上限约 50，超出部分不预热）。
async function proxyPlaylist(url, ctx) {
  const target = url.searchParams.get("url") || "";
  if (!/^https:\/\//i.test(target)) return new Response("bad url", { status: 400 });
  const response = await fetch(target, { headers: { "User-Agent": "Mozilla/5.0" } });
  const text = await response.text();
  const base = new URL(target);
  const baseDir = base.pathname.substring(0, base.pathname.lastIndexOf("/") + 1);
  const segments = [];
  const body = text.split("\n").map(line => {
    const value = line.trim();
    if (!value || value.startsWith("#")) return rewriteKeys(line, base, baseDir);
    const absolute = value.startsWith("http") ? value : value.startsWith("/") ? base.origin + value : base.origin + baseDir + value;
    if (segments.length < 44 && !absolute.includes("crypt.key")) segments.push(absolute);
    return "/seg?url=" + encodeURIComponent(absolute);
  }).join("\n");
  if (ctx && segments.length) {
    ctx.waitUntil((async () => {
      for (let i = 0; i < segments.length; i += 6) {
        await Promise.all(segments.slice(i, i + 6).map(seg =>
          fetch(seg, { headers: { "User-Agent": "Mozilla/5.0" }, cf: { cacheEverything: true, cacheTtl: 7200 } }).catch(() => {})));
      }
    })());
  }
  return new Response(body, {
    status: response.status,
    headers: { "Content-Type": "application/vnd.apple.mpegurl", "Access-Control-Allow-Origin": "*", "Cache-Control": "public, max-age=60" },
  });
}
// 清单行内的 URI="..."（AES key 等）同样改写为本 Worker 代理。
function rewriteKeys(line, base, baseDir) {
  return line.replace(/URI="([^"]+)"/g, (all, value) => {
    const absolute = value.startsWith("http") ? value : value.startsWith("/") ? base.origin + value : base.origin + baseDir + value;
    return 'URI="/seg?url=' + encodeURIComponent(absolute) + '"';
  });
}

async function proxySegment(url) {
  const target = url.searchParams.get("url") || "";
  if (!/^https:\/\//i.test(target)) return new Response("bad url", { status: 400 });
  // cacheEverything：分片 URL 带签名但两小时内稳定，命中边缘缓存即免回源。
  const response = await fetch(target, { headers: { "User-Agent": "Mozilla/5.0" }, cf: { cacheEverything: true, cacheTtl: 7200 } });
  return new Response(response.body, {
    status: response.status,
    headers: { "Content-Type": response.headers.get("Content-Type") || "video/mp2t", "Access-Control-Allow-Origin": "*", "Cache-Control": "public, max-age=7200" },
  });
}

function json(data, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { "Content-Type": "application/json", "Access-Control-Allow-Origin": "*" },
  });
}
