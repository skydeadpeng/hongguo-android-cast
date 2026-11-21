# Android 0.12.83 / 0.12.84 / 0.12.85 / 0.12.86 / 原生 TV 0.1.34 / 0.1.35 / 0.1.36 / 0.1.37 发行记录（2026-11-21）

## 0.12.86 / 0.1.37（黄果视频来源恢复，同日第四轮）

冻结源码：`8383826`（黑桃年龄确认标记轮与黄果视频 fit 域恢复同提交）。

黄果视频（huangguo-video）国内全链失效根因：主域名 huangguo.video 被 DNS 污染到不可路由 IP 且 TLS Client Hello 被 SNI 阻断；官方 TG 频道（t.me/huangguo_xxx）置顶公布最新网址 **huangguoai.fit**。fit 实测：SNI 未封、全站路径接受运行时默认 Android UA；污染 DNS 拒连仅 ~31ms。两域均在 Cloudflare Bot 盾后，Worker 反代子请求一律 1106（wrangler dev --remote 实测确认），source-proxy 路线对本源不可用，hgv 路由保留但不可达（详见 relay/source-proxy/README.md）。

修复内容：模块改 fit 首选域 + huangguo.video 末位镜像，仅对网络层失败（SOURCE_NETWORK/SOURCE_TIMEOUT）换域重试（HTTP 终态透传，保住 403 终态单次调用契约），旧任务遗留主域地址自动归一化；安卓侧新增 `NetworkGuard.sourceAddresses`——manifest hosts 注册域 DoH 优先解析（1.1.1.1 → 223.5.5.5 顺序，逐应答公网 SSRF 校验，双失败回系统解析），模块取页（SourceModuleRuntime.loadPage）与播放器 mediaClient DNS 钩子共用，m3u8/分片/密钥请求同享干净解析。黑桃轮：播放页年龄确认标记按源精确放行（SITE_COOKIES 白名单），其余 Cookie 输入保持拒绝。

验证：模块运行时套件 15 模块全过（offline metadata、denial=terminal、bundle SHA-256 锁定）；NetworkGuardTest 51 检查含 DoH 优先、SSRF 拒绝、通配/未注册域旁路用例；真网端到端 Node 桩（钉 DoH 真实 CF IP）分类→目录→详情→取流→X-Preview-Token→56 分片→AES-128 密钥→首分片全链 200，旧域地址归一化用例通过；污染降级模式（fit 钉污染 IP）1.1 秒快速失败不挂死；phone/tv/tvNative release-compatible 构建各过 9 项 release 检查。资产核验：v0.12.86 六附件匿名下载 SHA256 全对；v0.1.37 双 ABI 包经 GitHub 服务端 SHA256 摘要与尺寸比对一致（按用户指示跳过大文件匿名下载）。边界：模拟器应用内冒烟按用户指示中止于来源页导航，未完成源内实测；封面走 WebView 直载吃设备系统 DNS，污染且加密 DNS 不可用的网络可能缺图；GFW 对主域的 SNI 阻断为间歇性，末位镜像偶尔可达但不可依赖。

正式下载：
- phone 0.12.86/code109：[APK](https://github.com/skydeadpeng/hongguo-android-cast/releases/download/v0.12.86/Hongguo-0.12.86-phone-release-compatible.apk)。
- tv 0.12.86/code109：[APK](https://github.com/skydeadpeng/hongguo-android-cast/releases/download/v0.12.86/Hongguo-0.12.86-tv-release-compatible.apk)。
- tvnative 0.1.37/code38：[APK（arm64-v8a）](https://github.com/skydeadpeng/hongguo-android-cast/releases/download/v0.1.37/HongguoNative-0.1.37-tvNative-release-compatible-arm64-v8a.apk)、[32 位 APK](https://github.com/skydeadpeng/hongguo-android-cast/releases/download/v0.1.37/HongguoNative-0.1.37-tvNative-release-compatible-armeabi-v7a.apk)。

## 0.12.85 / 0.1.36（爱看机器人来源恢复，同日第三轮）

冻结源码：`54e421f`。

影视来源逐模块实测（tv34_dpad TV14 模拟器）：红果（发现/播放全通）、鬼片（列表/详情/播放全通，源站条目内容与简介不符属源站数据问题）、韩小圈（列表/详情/取流解析全通，播放接口 504 为源站流媒体节点过载）、青空（浏览/详情全通，播放需源站账号登录）、爱看机器人（原站 www1 整站超时）。

爱看机器人修复：BASE 自 www1 切至 www2 镜像（www1 整站超时；www 的 /play/ 详情被 Cloudflare challenge 拦 403；www2 列表/详情/签名取流接口全通）。manifest.hosts 增补 www2，捆绑 index 双摘要同步刷新。模拟器实测片单与详情恢复（真实豆瓣级元数据）；宿主机完整复现 signToken→getResN 取流链路（四条 m3u8 线路）；第三方 CDN 在宿主网络 200 可达，模拟器直连对部分 CDN 不可达属网络环境限制。

正式下载：
- phone 0.12.85/code108：[APK](https://github.com/skydeadpeng/hongguo-android-cast/releases/download/v0.12.85/Hongguo-0.12.85-phone-release-compatible.apk)。
- tv 0.12.85/code108：[APK](https://github.com/skydeadpeng/hongguo-android-cast/releases/download/v0.12.85/Hongguo-0.12.85-tv-release-compatible.apk)。
- tvnative 0.1.36/code37：[APK（arm64-v8a）](https://github.com/skydeadpeng/hongguo-android-cast/releases/download/v0.1.36/HongguoNative-0.1.36-tvNative-release-compatible-arm64-v8a.apk)、[32 位 APK](https://github.com/skydeadpeng/hongguo-android-cast/releases/download/v0.1.36/HongguoNative-0.1.36-tvNative-release-compatible-armeabi-v7a.apk)。

## 0.12.84 / 0.1.35（画质排序修复，同日追加）

冻结源码：`e35b6cb`。

0.12.83 上线后用户反馈下载大量落 360p。根因：video_tab 模型把各清晰度档挂在同一编码名下（每档单编码），而旧选档排序以编码为主——bounded 按像素升序、auto 按 H.264 优先——把选择钉在最低的 H.264 档。修复：bounded 与 auto 改为短边最高优先、同档 H.264 优先（Java `AppMediaSource.rank` 与 JS `app-source-registered.js` 同步修改，android mac source bundle 构建时重新生成，不入库）；固定画质非边界路径保持按满足画质的最接近档选择。

验证：AppMediaSource 31 检查（含「单编码低档不得压过高档」回归用例）、JS mac-source 52、entitlement 240、source budget 186、播放原生恢复 78 检查全部通过；TV 模拟器实测连播至第 18 集，控制面板显示实际分辨率 1080×1920（修复前同链路 720p 及以下）。

正式下载：
- phone 0.12.84/code107：[APK](https://github.com/skydeadpeng/hongguo-android-cast/releases/download/v0.12.84/Hongguo-0.12.84-phone-release-compatible.apk)。
- tv 0.12.84/code107：[APK](https://github.com/skydeadpeng/hongguo-android-cast/releases/download/v0.12.84/Hongguo-0.12.84-tv-release-compatible.apk)。
- tvnative 0.1.35/code36：[APK（arm64-v8a）](https://github.com/skydeadpeng/hongguo-android-cast/releases/download/v0.1.35/HongguoNative-0.1.35-tvNative-release-compatible-arm64-v8a.apk)、[32 位 APK](https://github.com/skydeadpeng/hongguo-android-cast/releases/download/v0.1.35/HongguoNative-0.1.35-tvNative-release-compatible-armeabi-v7a.apk)。

## 0.12.83 / 0.1.34（连播取源自愈）

冻结源码：`6c0fc4a`（本轮迁移修复 + 版本 bump 的合并提交）。

本轮把 hongguoTV（kotlin-tv）2026-11-20 起的播放形态迁移进 Java 取源线：匿名 POST（含 v1 与 :dr_scene 变体）已普遍返回空体，而 GET video_tab 仍可下发模型，因此前台取源改为 video_tab GET 主路径、签名 POST 仅作回退；两路均空时注册全新匿名身份重试一轮（30 秒静态冷却、每次取源单次额度、预取永不轮换），修复 0.12.82 连播数集后「App片源登记或取源未完成」的失败。

## 正式下载

- phone 0.12.83/code106：[APK](https://github.com/skydeadpeng/hongguo-android-cast/releases/download/v0.12.83/Hongguo-0.12.83-phone-release-compatible.apk)。
- tv 0.12.83/code106：[APK](https://github.com/skydeadpeng/hongguo-android-cast/releases/download/v0.12.83/Hongguo-0.12.83-tv-release-compatible.apk)。
- tvnative 0.1.34/code35：[APK（arm64-v8a）](https://github.com/skydeadpeng/hongguo-android-cast/releases/download/v0.1.34/HongguoNative-0.1.34-tvNative-release-compatible-arm64-v8a.apk)、[32 位 APK](https://github.com/skydeadpeng/hongguo-android-cast/releases/download/v0.1.34/HongguoNative-0.1.34-tvNative-release-compatible-armeabi-v7a.apk)。

## 验证

- 注册模型回归 27 组 294 检查全部通过（覆盖 tab-first 顺序、轮换额度、双空轮换、Retry-After 终止语义）；原生恢复 14 组 78 检查、注册模型 node 投影测试通过。
- tvDebug 实测：tv34_dpad（Android TV 14）模拟器自第 6 集起播，连播越过第 7 集（0.12.82 的必然失败点）并持续推进至第 13 集，全程无「App片源登记或取源未完成」弹窗。
- release 兼容包（tv，code106）覆盖安装后冒烟：首页 feed 正常，第 13 集起播成功。
- 11 个公开附件 SHA-256 摘要已列入 `SHA256SUMS.txt`，`SOURCE_COMMIT.txt` 指向冻结提交。

## 限制

- 实体手机、实体 TV 及应用内 OTA 完整流程本轮未验收。
- 30 秒冷却内的连续失败交给上层既有重试；若服务端进一步掏空 video_tab，需跟进 hongguoTV 上游或 relay 路线。
- gradle.properties 新增本机 JDK 21 工具链固定（Gradle 8.9 不支持 JDK 25），仅影响本机构建环境。
- 旧版发行与历史标签不替换。
