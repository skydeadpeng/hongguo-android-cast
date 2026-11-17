# 安卓红果播放诊断 — 2026-11-17

> **初始诊断（历史记录）**：本文保留修复前的取证结论和当时状态。后续已定位 Java v5 签名无符号字节缺陷，并实施取消隔离与原生恢复修复；冻结修复手机包已通过API32模拟器真实1080P连续播放／切集，TV包截图采集受阻，实体设备尚未测试。当前修复、测试和保留事项见 `/Users/xiaomingpeng/Desktop/红果/docs/playback-repair-2026-11-17.md`。下文“源码未改”“空响应”等描述均指初始诊断阶段。

## 结论

对象为手机 / TV WebView 安卓版。源码基线 `9ebfbff`；本地 phone/tv Release 元数据为 0.12.79 / code102。用户手机实际安装版本、失败集号和设备日志尚未采集，以下不冒称设备现场唯一根因。

已确认三个独立事实：

1. 当前网络匿名 App 模型取源仍返回 HTTP 200 / 0 字节，旧 551 和本项目 v5 都如此；这是取源阶段，不是收到视频后的解码错误。
2. 0.12.79 新增 JS 1/3/8 秒恢复没有进入手机/TV WebView 的真实在线播放主路径。原生仍只有旧的 700ms 同档重取；PlayerActivity 外层还有自己的单次恢复/降档，并非完全零重试。
3. `RegisteredPlaybackModel.ModelHttpTransport.ACTIVE_CALLS` 全局静态集合存在跨任务取消：关闭预取 transport 会取消不属于自己的前台请求。真实生产类的离线重现成功；仅在副本改为实例所有权后，前台得到保留。正式 Java 源码/APK/安装均未更改。

第2、3项是确定的本地代码缺陷；它们不独立证明服务器返回空正文的具体判定原因。当前没有证据把“加重试”当作完整解决上游空包的协议修复。

## 上游截至本次核查的状态

| 项目 | 核查结果 | 验证层级 |
|---|---|---|
| N3urda/hongguoTV | 最新正式 Kotlin 0.16.6，包含 v5 与 device_level=1 兼容修复；0.16.7 仍是 PR | 作者文档记录 0.16.5 TV模拟器 3剧7集出画/切集；0.16.6 修复记录取得5档 ByteVC1并读取65536字节。不是本轮本机上游App验收 |
| tsukiyo-zero/hongguoTV | 最新 v0.1.32，11月16日发布，v5 已合入 | 官方对照：同接口/匿名身份/集号/gzip，旧签名0字节，新签名24345字节、code0、5档；720P媒体头/解密样本准备成功，资源12834640字节。文档明确未完整下载或电视出画 |
| jackotom/hongguo-drama | v7.5 系列，release正文现为内部7.5.2 | 作者记录最终Mac包真实两集1080P下载及静音播放，Windows真实下载和兼容播放；它是桌面验收，非本安卓包验收 |
| 327044572/hongguo-downloader | 未见本次v5恢复提交 | Issue3仍记录HTTP200空正文 |
| zhenyong97/hongguo-downloader | 未见本次v5恢复提交 | Issue11“已失效”；旧发行成功不作为当前可播证明 |

“上游可用”仅指各自列出的验收层级，不保证本网络、任意集或用户实体手机当前全部可播。

官方资料（均只读读取）：
```text
https://github.com/N3urda/hongguoTV/releases/tag/kotlin-v0.16.6
https://github.com/N3urda/hongguoTV/blob/main/kotlin-tv/ISSUE59_LIVE_VERIFICATION.md
https://github.com/N3urda/hongguoTV/commit/216fd3eaaafbe0f6a7e7928c055aaf693fdba40b
https://github.com/tsukiyo-zero/hongguoTV/blob/main/kotlin-tv/PLAYBACK_V5.md
https://github.com/tsukiyo-zero/hongguoTV/pull/109
https://github.com/jackotom/hongguo-drama/releases/tag/v7.5
https://github.com/327044572/hongguo-downloader/issues/3
https://github.com/zhenyong97/hongguo-downloader/issues/11
```

## 本轮真网与真实素材

从官网目录随机选《错失的遗憾》，剧集 `7706004220599618584`，首集 `7706006636074126361`（82集）。独立匿名身份，没有读取正式账号/偏好/下载记录，没有记录身份、密钥或带签名媒体地址。

- `probe-live.cjs`：官网旧JS parser返回PREVIEW_ONLY；匿名注册HTTP200。旧551、v5 device_level=1、同身份3秒后v5重试均HTTP200、0字节、0候选。Node/HTTP1传输，不冒称Android OkHttp或上游Kotlin同网结果。
- `probe-guest.cjs`：先确认官网分集/归属、明确游客资格、非登录、完整时长及尺寸；官网正常游客POST返回HTTP200、StatusCode=0。
- 真实完整下载：11,158,873字节，H.264 / AAC，360×640；实际173.202653秒，官网声明173.203秒。
- 本机FFmpeg以 `-an -frames:v 3 -f null -` 静音解码成功，exit0。只验证媒体字节与本机解码，不冒称Android UI出画。
- 辅助Librempeg的null muxer默认编码器不在编译中，exit8；该命令配置问题不作为片源故障证据。FFmpeg成功结果独立保留。

因此官网游客低清链路并非全站断流；App高清模型空包也确实存在。未取得上游Kotlin当前同网络直接取流结果时，维护者验收记录与本轮实测保持区分。

证据目录：
`/Users/xiaomingpeng/Desktop/红果/verification/playback-diagnosis-2026-11-17`

## 真实原生路径

- `PlayerActivity.java:506,509,519,1104` 首开、恢复、预取都显式传 `RegisteredPlaybackLookup::resolve`。
- `PlaybackSources.java:355-368` 显式lookup直接返回；不经过376行 `window.__androidAppResolve`。
- `RegisteredPlaybackLookup.java:15-23` 直接 `AppMediaSource → RegisteredPlaybackModel.fetch`。
- `RegisteredPlaybackModel.java:138-151` 单次 `playerV1`；上层 `PlaybackSources.java:344` 只旧700ms同档重取。
- 新1/3/8秒延迟位于 `src/native/app-source-registered.js:416-458`，在此播放路径未执行。APK中存在这段JS也不代表原生播放器调用了它。

实跑输出：
```text
NATIVE_EXPLICIT_LOOKUP nativeCalls=1 jsProviderCalls=0 result=success networkRequests=0
NATIVE_PLAYBACK_EMPTY nativeCalls=2 jsProviderCalls=0 recoveryDelayMs=700 result=APP_EMPTY_RESPONSE networkRequests=0
REGISTERED_MODEL_EMPTY bootstrapCalls=1 modelRequests=1 result=APP_EMPTY_RESPONSE internalRetries=0 networkRequests=0
NATIVE_ROUTING_REPRO_OK productionFilesModified=0 videoFixtures=0 networkRequests=0
```

## 并发取消缺陷与修正副本

- `PlayerActivity.java:32,34` 前台resolver与cacheWorker是不同执行器；682行前台取源、1099-1104行预取可重叠。
- `RegisteredPlaybackModel.java:155-158` 每次fetch结束都会close transport。
- `RegisteredPlaybackModel.java:394,398` 静态ACTIVE_CALLS导致close取消其他实例持有的Call。
- 修正副本只将ACTIVE_CALLS变为实例集合，以及默认engine与productionOkhttpCall实例绑定，共3处；保留共享OkHttp连接池。

输入为两个fake okhttp3.Call（前台、预取各一个），仅关闭预取transport；实际生产类参与实验，网络为零。

字面结果：
```text
BASELINE foregroundCancelCount=1 prefetchCancelCount=1 activeSetIsStatic=true networkRequests=0
BASELINE_OK unrelatedForeground=canceled ownPrefetch=canceled
MODIFIED foregroundCancelCount=0 prefetchCancelCount=1 activeSetIsStatic=false networkRequests=0
MODIFIED_OK unrelatedForeground=preserved ownPrefetch=canceled
ROLLBACK_RESTORED sha256=cc338a5a30b34a217fd620dabd30ca3a34b5dc864ff90c00539105edd574c7fc
ROLLBACK foregroundCancelCount=1 prefetchCancelCount=1 activeSetIsStatic=true networkRequests=0
ROLLBACK_OK unrelatedForeground=canceled ownPrefetch=canceled
```

三阶段退出均0。回滚只在另一个验证副本上执行，恢复基线串扰；MODIFIED_FILE.java仍保持修正，正式源码原样。

四件产物（精确命令/输入/输出/退出与摘要见VERIFICATION.txt）：
- `/Users/xiaomingpeng/Desktop/红果/verification/playback-diagnosis-2026-11-17/cancel-fix/MODIFIED_FILE.java`
- `/Users/xiaomingpeng/Desktop/红果/verification/playback-diagnosis-2026-11-17/cancel-fix/DIFF_FILE.patch`
- `/Users/xiaomingpeng/Desktop/红果/verification/playback-diagnosis-2026-11-17/cancel-fix/VERIFICATION.txt`
- `/Users/xiaomingpeng/Desktop/红果/verification/playback-diagnosis-2026-11-17/cancel-fix/ROLLBACK.sh`

生产原件 SHA256：`cc338a5a30b34a217fd620dabd30ca3a34b5dc864ff90c00539105edd574c7fc`。
修正副本 SHA256：`e63dfcd7febbf2c044b0f2f657e4e20ed4f5d77f97e3696b3076fef882fa28fb`。

## 修复落点

先修原生transport的取消所有权，再将有界、可取消、保留同集/进度/暂停意图的恢复接到真实Java播放边界；不继续只堆JS重试。v5空包需用正式Android传输与上游Kotlin同网、同集对照后再定位，cdid稳定性为待验证差异，不预先定因。新APK发布前须以独立资料/真实随机素材静音验首播、切集、前台与预取重叠；本轮未发布或覆盖安装。
