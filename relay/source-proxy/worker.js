// 影视来源 Cloudflare Worker 反代：按绑定域名路由到源站，白名单外一律拒绝。
// 用途：绕过国内直连的 DNS 污染与连接重置（帝果/剧果/黄果视频/Hanime1/MissAV）。
// 分片与封面一般在源站 CDN 上，不经本代理；若个别源连 CDN 也被墙，再单独扩。
// 部署后各域名与源站路径一一对应，可直接作为来源模块的备用镜像域名。
const ROUTES = {
  "dsd.skydeadpeng.xyz": "https://www.dsd.com.se",
  "hanju.skydeadpeng.xyz": "https://huangju.net",
  "hanjuapi.skydeadpeng.xyz": "https://api.huangju.net",
  "hanjucdn.skydeadpeng.xyz": "https://video.huangju.net",
  "hgv.skydeadpeng.xyz": "https://huangguo.video",
  "msv.skydeadpeng.xyz": "https://missav.live",
  "h1.skydeadpeng.xyz": "https://hanime1.me",
};

export default {
  async fetch(request) {
    const url = new URL(request.url);
    const upstream = ROUTES[url.hostname];
    if (!upstream) return json({ error: "unknown host" }, 404);
    if (url.pathname === "/__health") return json({ ok: true, host: url.hostname, upstream });
    if (request.method === "OPTIONS") return new Response(null, { status: 204, headers: {
      "Access-Control-Allow-Origin": "*", "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
      "Access-Control-Allow-Headers": "Content-Type, Range", "Access-Control-Max-Age": "86400",
    } });

    const target = upstream + url.pathname + url.search;
    const headers = {
      "User-Agent": "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
      "Accept-Language": "zh-CN,zh;q=0.9,en;q=0.8",
    };
    // 客户端带什么 Referer/Cookie 就透传什么（剧果 CloudFront 凭证链依赖这两项）；
    // 没带时退回源站根，模拟常规浏览器访问。
    const referer = request.headers.get("Referer");
    headers["Referer"] = referer || upstream + "/";
    const cookie = request.headers.get("Cookie");
    if (cookie) headers["Cookie"] = cookie;
    const range = request.headers.get("Range");
    if (range) headers["Range"] = range;
    const init = { method: request.method, headers, redirect: "follow", signal: AbortSignal.timeout(15000) };
    if (request.method !== "GET" && request.method !== "HEAD") {
      init.body = await request.arrayBuffer();
      const ct = request.headers.get("Content-Type");
      if (ct) headers["Content-Type"] = ct;
    }

    let response;
    try {
      response = await fetch(target, init);
    } catch (e) {
      return json({ error: "upstream fetch failed", detail: String(e) }, 502);
    }
    const out = new Headers();
    const ct = response.headers.get("Content-Type") || "";
    if (ct) out.set("Content-Type", ct);
    const cr = response.headers.get("Content-Range");
    if (cr) out.set("Content-Range", cr);
    // Set-Cookie 原样透传（剧果 /play/ 的 CloudFront 签名凭证在响应头里，模块要读）。
    const setCookies = typeof response.headers.getSetCookie === "function" ? response.headers.getSetCookie() : [];
    for (const line of setCookies) out.append("Set-Cookie", line);
    out.set("Access-Control-Allow-Origin", "*");
    // JSON API 30 秒边缘缓存；带凭证或非 JSON 一律 no-store（避免把签名响应缓存串号）。
    if (setCookies.length > 0) out.set("Cache-Control", "no-store");
    else if (ct.includes("json")) out.set("Cache-Control", "public, max-age=30");
    else out.set("Cache-Control", "no-store");
    return new Response(response.body, { status: response.status, headers: out });
  },
};

function json(obj, status) {
  return new Response(JSON.stringify(obj), { status,
    headers: { "Content-Type": "application/json", "Access-Control-Allow-Origin": "*" } });
}
