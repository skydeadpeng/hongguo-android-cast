# 红果短剧 · webOS（LG 电视）客户端

webOS TV 原生 Web 应用（`.ipk`），零 npm 依赖：目录/选集走官网链路，官方加密片源链（设备注册 + 签名）已完整移植。

## 现状（0.2.0）

- ✅ 分类浏览（真人剧 / 漫剧 / AI剧）——官网目录
- ✅ 详情页与选集——102 集级别的完整目录
- ✅ 在线播放——官网开放的明文 MP4（前几集试看，其余集数官网不开放网页源）
- ✅ 连播：播完自动下一集 + 后台预取下一集（零等待）+ 连播开关
- ✅ 遥控 D-pad 导航、返回键、进度拖动
- ✅ 官方加密链已移植并逐字节验证（MD5 / SM3 / Gorgon / 设备注册信封）
- ⏳ **全量正片**：浏览器拿不到注册会话凭据（见下），需要中转或电视端原生能力
- ⏳ 真机验收：需 LG 电视（webOS 4.5+）开发者模式

## 为什么纯浏览器拿不到全量正片（实测结论）

官网网页源只开放每部剧的前几集（`accessible_episode_cnt`，实测 3~5 集），完整正片必须走官方 App 通道：

1. **设备注册**（`log.snssdk.com/service/2/device_register/`）→ 返回 `device_id` / `install_id` / `device_token` 与会话 Cookie
2. **模型请求**（`fqnovel.com/novel/player/multi_video_model/v1/`）→ 返回加密片源地址与密钥

这套流程在浏览器里**注册能成功**（`webos/js/passport.js`，已实测拿到 device_id），但模型请求会拿到空响应，原因有二：

- 注册响应的会话凭据属于 `log.snssdk.com` 域，浏览器既读不到跨域 `Set-Cookie`，也不能替 `fqnovel.com` 设置它；
- 服务端不返回 `Access-Control-Allow-Credentials`，`credentials:'include'` 也无法让浏览器接管该 Cookie。

桌面版之所以能通，是因为它在 Node 侧**手动**把凭据写进了请求头。

### 解法

`webos/js/app-chain.js` 已定义好中转契约：在任意同源服务上跑一个小代理（Cloudflare Worker / 自有服务器均可），
代理负责注册、保管凭据、请求模型，浏览器只与代理通信。客户端在 `设置` 里填入中转地址即可自动启用全量正片：

```
POST <中转地址>/episode
请求 { "vid": "7701937936731737112" }
响应 { "ok": true, "items": [ { "url": "...", "quality": "1080p", "spade": "...", "contentKey": "..." } ] }
```

未配置中转时自动回落官网链路（试看集数可正常播放）。

## 文件

- `appinfo.json` — webOS 应用元数据
- `js/source.js` — 目录/选集/官网播放地址解析（与桌面同源）
- `js/crypto-primitives.js` — 纯 JS MD5 + gzip(stored) + WebCrypto 包装
- `js/signer.js` — SM3 / Gorgon 签名机（与桌面字节级一致）
- `js/passport.js` — 浏览器版设备注册
- `js/app-chain.js` — 官方加密链与中转契约
- `js/app.js` — 界面、连播与预取
- `build.sh` — 手写 ar 归档打 `.ipk`（macOS 自带 ar 会附加符号表被 opkg 拒绝）

## 测试

```bash
node scripts/check-webos-source.cjs    # 解析器离线测试（19 项）
node scripts/check-webos-crypto.cjs   # 与桌面实现逐字节对拍（14 项）
node scripts/check-webos-signer.cjs   # Gorgon 对拍
```

## 安装到电视

```bash
bash webos/build.sh                     # → webos/dist/com.skydeadpeng.hongguo_0.2.0_all.ipk
npm i -g @webos-tools/cli               # ares-cli
ares-install -d <TV_IP> -P <passphrase> webos/dist/*.ipk
ares-launch -d <TV_IP> com.skydeadpeng.hongguo
```

真机调试：`ares-inspect -d <TV_IP> -a com.skydeadpeng.hongguo`（Chrome DevTools）。
