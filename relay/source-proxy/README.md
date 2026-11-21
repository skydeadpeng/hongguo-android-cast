# 来源站 Cloudflare Worker 反代（source-proxy）

国内直连被墙/DNS 死路的影视来源站，经本 Worker 按域名白名单反代后直连可用。
一个 Worker 挂 7 个自定义域名，按 Host 路由到对应源站；白名单外一律 404（防 SSRF）。

## 域名 ↔ 源站

| 域名 | 源站 | 2026-11-21 实测 |
|---|---|---|
| dsd.skydeadpeng.online | www.dsd.com.se（帝果） | ✅ 200 / 1.8s |
| hanju.skydeadpeng.online | huangju.net（剧果站根） | ✅ 200 / 2.6s |
| hanjuapi.skydeadpeng.online | api.huangju.net（剧果 API） | ✅ POST /auth/guest 业务校验正常 |
| hanjucdn.skydeadpeng.online | video.huangju.net（剧果视频/分片 CDN） | ✅ 转发可达（上游对探测路径 403 属正常拒绝） |
| hgv.skydeadpeng.online | huangguo.video（黄果视频） | ❌ 403 CF 1106（源站盾） |
| msv.skydeadpeng.online | missav.live（MissAV） | ❌ 403 盾页（源站盾） |
| h1.skydeadpeng.online | hanime1.me（Hanime1） | ❌ 403 质询（源站盾） |

黄果 AI 复用既有 `huoguo.skydeadpeng.online`（relay/huangguo-proxy），`/api/videos/category/ai-manju` 实测返回完整数据。

## 黄果视频（huangguo-video）2026-11-21 结论：反代路线不可用，改走直连

官方 TG 频道（t.me/huangguo_xxx）置顶：主域名 `huangguo.video`，最新网址 **`huangguoai.fit`**。实测：

| 链路 | huangguo.video | huangguoai.fit |
|---|---|---|
| 国内 DNS | 污染到不可路由 IP | 污染到 221.228.32.13（拒绝连接 ~31ms） |
| TLS SNI 直连 | ❌ Client Hello 即被 RST | ✅ 未封（TLSv1.3 + HTTP/2） |
| Cloudflare Bot 盾（Worker 子请求） | ❌ 1106 | ❌ 1106（两域都在 CF 后且开了盾） |
| 运行时默认 Android UA（直连） | ❌ WAF 拒绝 | ✅ 全路径放行（HTML/m3u8/preview-token/封面） |

`hgv` 路由因此保留在 worker.js 里但实际不可达（1106 是 Cloudflare 对被盾 zone 的
Worker 子请求拦截，换 UA/请求头无效）。黄果视频模块（vendor/source-modules/huangguo-video）
改为 `huangguoai.fit` 首选域直连 + `huangguo.video` 末位镜像，污染 DNS 由安卓端
`NetworkGuard.sourceAddresses`（DoH 优先解析，manifest hosts 注册）兜底；唯一已知
边界是封面走 WebView 直载，吃设备系统 DNS，污染网络上可能缺图。

## 部署

```bash
cd relay/source-proxy
npx wrangler deploy
```

## 路径说明

- `/<任意路径>` → 对应源站同路径透传（GET/POST/HEAD，跟随重定向，15 秒超时）
- `/__health` → `{"ok":true,"host":…,"upstream":…}`
- JSON 响应 30 秒边缘缓存；HTML/其他 no-store；Range/Content-Range 透传
- 视频 m3u8 分片一般走源站 CDN，不经本代理
