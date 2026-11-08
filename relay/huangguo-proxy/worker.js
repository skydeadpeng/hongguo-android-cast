// 黄果官方站 Cloudflare Worker 代理：API 透传 + 封面代理 + m3u8 改写 + 分片透传。
// 用途：绕过部分运营商网络对 huangguoai.com 及内容 CDN 的连接重置。
// 部署后把 https://<你的Worker域名> 加入 App 备用线路即可，路径与官方 API 完全一致。
const UPSTREAM = "https://huangguoai.com";

export default {
  async fetch(request) {
    const url = new URL(request.url);
    const path = url.pathname;
    if (path === "/__health") return json({ ok: true });
    if (path.startsWith("/api/")) return proxyApi(url);
    if (path === "/img") return proxyImage(url);
    if (path === "/m3u8") return proxyPlaylist(url);
    if (path === "/seg") return proxySegment(url);
    return json({ error: "not found" }, 404);
  },
};

async function proxyApi(url) {
  const target = new URL(UPSTREAM + url.pathname);
  target.search = url.search;
  const response = await fetch(target, { headers: { "User-Agent": "Mozilla/5.0", Referer: UPSTREAM + "/" } });
  return new Response(response.body, {
    status: response.status,
    headers: { "Content-Type": "application/json", "Access-Control-Allow-Origin": "*", "Cache-Control": "public, max-age=30" },
  });
}

async function proxyImage(url) {
  const target = url.searchParams.get("url") || "";
  if (!/^https:\/\/[\w.-]+\/[\s\S]*$/i.test(target)) return new Response("bad url", { status: 400 });
  const response = await fetch(target, { headers: { "User-Agent": "Mozilla/5.0" } });
  return new Response(response.body, {
    status: response.status,
    headers: { "Content-Type": response.headers.get("Content-Type") || "image/jpeg", "Access-Control-Allow-Origin": "*", "Cache-Control": "public, max-age=86400" },
  });
}

// m3u8：改写分片与 AES key 的绝对地址为本 Worker 代理，播放器只需拿到 Worker 域名清单。
async function proxyPlaylist(url) {
  const target = url.searchParams.get("url") || "";
  if (!/^https:\/\//i.test(target)) return new Response("bad url", { status: 400 });
  const response = await fetch(target, { headers: { "User-Agent": "Mozilla/5.0" } });
  const text = await response.text();
  const base = new URL(target);
  const baseDir = base.pathname.substring(0, base.pathname.lastIndexOf("/") + 1);
  const body = text.split("\n").map(line => {
    const value = line.trim();
    if (!value || value.startsWith("#")) return rewriteKeys(line, base, baseDir);
    const absolute = value.startsWith("http") ? value : value.startsWith("/") ? base.origin + value : base.origin + baseDir + value;
    return "/seg?url=" + encodeURIComponent(absolute);
  }).join("\n");
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
  const response = await fetch(target, { headers: { "User-Agent": "Mozilla/5.0" } });
  return new Response(response.body, {
    status: response.status,
    headers: { "Content-Type": response.headers.get("Content-Type") || "video/mp2t", "Access-Control-Allow-Origin": "*", "Cache-Control": "public, max-age=3600" },
  });
}

function json(data, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { "Content-Type": "application/json", "Access-Control-Allow-Origin": "*" },
  });
}
