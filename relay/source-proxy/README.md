# 来源站 Cloudflare Worker 反代（source-proxy）

国内直连被墙/DNS 死路的影视来源站，经本 Worker 按域名白名单反代后直连可用。
一个 Worker 挂 6 个自定义域名，按 Host 路由到对应源站；白名单外一律 404（防 SSRF）。

## 域名 ↔ 源站

| 域名 | 源站 | 2026-11-21 实测 |
|---|---|---|
| dsd.skydeadpeng.xyz | www.dsd.com.se（帝果） | ✅ 200 / 1.8s |
| hanju.skydeadpeng.xyz | huangju.net（剧果站根） | ✅ 200 / 2.6s |
| hanjuapi.skydeadpeng.xyz | api.huangju.net（剧果 API） | ✅ POST /auth/guest 业务校验正常 |
| hgv.skydeadpeng.xyz | huangguo.video（黄果视频） | ❌ 403 CF 1106（源站盾） |
| msv.skydeadpeng.xyz | missav.live（MissAV） | ❌ 403 盾页（源站盾） |
| h1.skydeadpeng.xyz | hanime1.me（Hanime1） | ❌ 403 质询（源站盾） |

黄果 AI 复用既有 `huoguo.skydeadpeng.xyz`（relay/huangguo-proxy），`/api/videos/category/ai-manju` 实测返回完整数据。

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
