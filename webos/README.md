# 红果短剧 · webOS（LG 电视）客户端

webOS TV 原生 Web 应用（`.ipk`），复用桌面版同源的官网取源链，零 npm 依赖。

## 现状（0.1.0）

- ✅ 分类浏览（真人剧 / 漫剧 / AI剧）——官网 `/category/{slug}` 路由数据
- ✅ 剧集列表——`/player/{sid}` 的 `vid_list`；官网未 SEO 开放的集数置灰
- ✅ 在线播放——`/player/{sid}/{vid}` 的 ld+json `contentUrl` 明文 MP4（webOS 自带 `<video>` 直接播）
- ✅ 遥控器 D-pad 导航 + 返回键；10 英尺 UI
- ⏳ App 加密链路（签名模型 + spade 解密，覆盖全部集数与高清清晰度）——留待 0.2.x，需把桌面 `app-source.js` 的 Node crypto 签名换成纯 JS / WebCrypto
- ⏳ 真机验收：需 LG 电视（webOS 4.5+）开发者模式

## 构建与安装

```bash
# 打包（零依赖，macOS/Linux 皆可）
bash webos/build.sh            # → webos/dist/com.skydeadpeng.hongguo_0.1.0_all.ipk

# 安装到电视（另需 webOS TV 开启开发者模式，取得电视 IP 与 passphrase）
npm i -g @webos-tools/cli      # ares-cli（或按 LG 官方 SDK 安装）
ares-install -d <TV_IP> -P <passphrase> webos/dist/*.ipk
ares-launch -d <TV_IP> com.skydeadpeng.hongguo
```

真机调试：`ares-inspect -d <TV_IP> -a com.skydeadpeng.hongguo`（Chrome DevTools）。

## 跨域说明

`appinfo.json` 的 `accessControlAllowOrigin` 已放行 `https://hongguoduanju.com`；
封面图（byteimg CDN）与视频直链走 `<img>/<video>`，不受 CORS 约束。

## 文件

- `appinfo.json` — webOS 应用元数据
- `js/source.js` — 取源链（与桌面 catalog.js / hongguo.js 同源解析；Node 可加载供测试）
- `js/app.js` — 界面与遥控交互
- `build.sh` — 手写 ar 归档打 `.ipk`（macOS 自带 ar 会附加符号表项被 opkg 拒绝）
- `../scripts/check-webos-source.cjs` — 离线解析测试（19 项断言，含 2026-11 官网改版的系列级 ld+json 兼容）
