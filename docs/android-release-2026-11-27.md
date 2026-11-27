# Android 0.12.92 / 0.1.42 发行记录（2026-11-27，官网分集改版适配）

冻结源码：`c6233ab`（分集读取修复）+ bump 提交 `98f761b`（0.12.92/code115，tvNative 0.1.42/code43）。四个安装包均在 `98f761b` 的干净工作树中构建，工作区无未提交改动。

## 背景

2026-11-27 起，官网剧集页 `/player/<series_id>` 的分集数据变了：`vid_list` 只返回前 3 集，元素从数字分集编号变成 `v1_...` 混淆令牌，`accessible_episode_cnt` 由 5 变为 3，而数字分集编号的播放页地址直接返回 404。实测样本 `7706908220253555737`：`episode_cnt=71`，`vid_list` 为 3 个令牌；`/player/<编号>/<数字分集编号>` 与 `/player/_/<编号>` 均 404。

三端都靠这一页取分集，结果是进入剧集后无集可选：WebView 线详情页 0 集，tvNative 报「来源未返回有效分集」，桌面线报「官网分集数据异常」。

上游 `jackotom/hongguo-drama` 同日发布 8.5（issue #217）。本轮按其方案移植。上游链路第一跳的 `openhongguo`（Go/WASM npm 包）未采用：安卓线零运行依赖，且第二跳已实测够用。

## 内容（`c6233ab`）

- 完整数字分集改由签名的 App 详情接口 `novel/player/video_detail/v1/` 读取（7.4.5.32 档案，Medusa 字段 21 = 312），与播放梯子共用登记身份。
  - 安卓：新增 `AppEpisodeList`。首个节点限 4.5 秒，握手被掐、连接重置或停滞则在换节点后重试一次，总预算 9 秒；业务拒绝、空应答与格式错误不重试。
  - 桌面：`fetchAppEpisodeList`。分集链路顺序为 App 详情 → 官网（仅完整页）→ 旧 preload 接口；有调用方期限时 App 详情最多占其中的六成。
- 官网页仍先读。只列出前几集的页面——令牌页，或只列出可公开前缀的数字页——被识别为「预览」并触发补全；`webAvailable` 标在对应的前几集上。WebView 线经新增的桥接方法 `appEpisodeList` 取补全结果。
- 官网游客取源改走令牌：`EpisodeResolver` 按已加载的分集列表把数字编号映射到令牌，访问 `/player/<series_id>/<token>`，游客兜底接口接受令牌。
- `run-site-catalog-tests.sh` 修复：此前在 HEAD 上就编不过（缺一个源文件与 Android SDK jar）。工作区遗留的 11-23 路由诊断日志改为设备外静默的 `trace`，设备上照常输出。
- webOS 版未改：没有 App 签名路线，官网改版后无法列出分集，需另行移植。

## 验证

- 真实接口：本仓库 Java 签名器（主机 JVM）对 10 部剧（真人剧、漫剧、AI 剧，71–785 集）请求 `video_detail/v1`，全部返回完整、有序、无重复的数字分集。桌面线同一链路对 3 部剧（71 / 317 / 785 集）结果相同。
- 离线回归（Node 22）：
  - 安卓：目录与搜索页 49 + 17 项；分集解析 305 项、路由传输 42 项、SDK35 编译；签名与分集列表 92 项；登记模型 31 组 321 项；SDK 向量 77 项；桥接分派 35 项；`check:android` 56 项；`check:android:playback` 10 组 56 项。
  - 页面模块：WebView 取源模块 45 项与相关 5 组检查；桌面分集链路新增 2 组用例通过。
  - `npm test` 失败清单与改动前基线逐条相同，为 4 条存量失败（Windows 安装器配置、真实素材解码检查、两组下载画质用例），无新增失败。
- 模拟器实测（电视 AVD，Android 14 / arm64，无音频启动，全程静音，素材在红果内随机选取）：
  - 手机包（调试构建）：首页随机抽到《温夜潮湿》，详情页显示「78 集」、选集 1–30（超过 30 集分组显示）；改动前同一位置为 0 集。
  - 点第 5 集（官网公开范围之外）：官网路由按预期判定未公开，转入 App 链路，`HongguoSDK: session ok`、`player http=200 bytes=24960`，播放器进入前台。

## 资产

| 资产 | SHA256 | 字节 |
|---|---|---|
| [phone 0.12.92/code115](https://github.com/skydeadpeng/hongguo-android-cast/releases/download/v0.12.92/Hongguo-0.12.92-phone-release-compatible.apk) | `4d5b3ea6e6bd00ac222fd7f83e9092a1ba86f4a697981900afa4863cdc4fe87e` | 1763111 |
| [tv 0.12.92/code115](https://github.com/skydeadpeng/hongguo-android-cast/releases/download/v0.12.92/Hongguo-0.12.92-tv-release-compatible.apk) | `2e44d7523d597513a4e10156517b24c8b8c1dc54e71d0170fffbd25d468cc9a6` | 1762923 |
| [tvNative arm64 0.1.42/code43](https://github.com/skydeadpeng/hongguo-android-cast/releases/download/v0.1.42/HongguoNative-0.1.42-tvNative-release-compatible-arm64-v8a.apk) | `a66164fd27c4770bd83682a9ba973ec23f86b291394c29392895588001bac0ec` | 47102408 |
| [tvNative armeabi-v7a 0.1.42/code43](https://github.com/skydeadpeng/hongguo-android-cast/releases/download/v0.1.42/HongguoNative-0.1.42-tvNative-release-compatible-armeabi-v7a.apk) | `dee8d0163c715c4dd6b474739a86fff2d275375f333f9cd13e4a8d7cd236bc6e` | 43341877 |

手机与 TV 两个包已匿名整包下载回读，SHA256 与冻结产物一致。原生 TV 两个包体量较大，已匿名分段（首尾各 1 MiB）下载比对一致，GitHub 记录的上传摘要亦与上表一致。

## 边界与限制

- **本轮没有完成播放出画面的验收。** 验证所用主机当天处于代理 TUN（假 IP DNS）模式，模拟器内媒体 CDN 域名解析到 `198.18.x.x`，被网络守卫以「域名解析到非公网地址」拒绝。这是既有行为，与本次改动无关。应用内链路验证到「取源成功、拿到模型」为止。
- **tvNative 详情页未做界面实测。** 同一环境下 tvNative 首页片单（走 App 接口）加载失败，无法经首页进入详情。tvNative 与手机包共用同一套 Java 代码，该部分经离线用例与主机 JVM 真实请求验证。
- 未在实体设备上验证。更新检查有 24 小时冷却，可到「关于 → 检查更新」手动触发。
- 读取完整分集依赖 App 详情接口。该接口被限流或拒绝时，只列出可公开前缀的数字页仍可单独使用，令牌页则提示重试。
- 官网令牌只在加载过该剧分集列表后才知道（进程内缓存 30 分钟）。进程重启后未重新进入详情的剧没有官网路由，直接走 App 链路，与官网从未列出的分集相同。
- 安卓端媒体 CDN 域名在假 IP DNS 的代理 / VPN 下仍会被网络守卫拒绝；上游 8.5 的 #215 只修了桌面线。是否为媒体域名增加可信 DoH 竞速需另议，不在本轮范围。
