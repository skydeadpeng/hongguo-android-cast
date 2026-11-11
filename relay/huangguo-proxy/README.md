# 黄果 Cloudflare Worker 代理

部分运营商网络对 `huangguoai.com`（及内容 CDN）连接重置，App 内置备用域名也可能全部被掐。
本 Worker 把官方 API、封面与视频流整体代理到你的 Cloudflare 域名上，只要 Worker 域名可达即可完整使用。

## 部署（需要你自己的 Cloudflare 账号）

```bash
cd relay/huangguo-proxy
npx wrangler login        # 浏览器授权
npx wrangler deploy       # 部署，得到 https://huangguo-proxy.<你的子域>.workers.dev
```

workers.dev 域名在国内多数不可达，**必须绑定自定义域名**：

1. Cloudflare 控制台 → 你的域名（例如 5671234.xyz）→ Workers Routes / Custom Domains
2. 给 `huangguo-proxy` 添加 Custom Domain，例如 `hgapi.5671234.xyz`
3. 验证：`curl https://hgapi.5671234.xyz/__health` 返回 `{"ok":true}`

## App 侧接入

把你的 Worker 地址（如 `https://hgapi.5671234.xyz`）告诉我，我把它加进 App 的
`HuangguoApi.ORIGINS` 备用线路列表发新版本；流与封面走 `/m3u8` `/img` 代理，无需改播放器。

## 路径说明

- `/api/*` → 官方 JSON API 透传（加 CORS）
- `/img?url=` → 封面代理
- `/m3u8?url=` → 播放清单改写（分片与 AES key 全部落到本 Worker）
- `/seg?url=` → 分片/密钥透传
