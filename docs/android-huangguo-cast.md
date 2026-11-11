# Android 黄果投屏实现与验证

## 范围

只修改 Android。手机原有「投屏」中的 DLNA 与浏览器接收端共用黄果准备器，电视端/桌面端没有增加新的投屏入口。未修改桌面播放、发布版本、签名、安装或更新渠道。

黄果的官方点播 HLS 不能直接交给原来只接受完整 MP4 的本机供流服务器。本次先在手机私有缓存中读取完整 HLS、处理官方公开返回的 identity AES-128 密钥、再以 Android MediaExtractor / MediaMuxer 转封装为 MP4。完成后才原子发布，并继续使用现有预取、缓存引用、Range 供流、连播、停止及合并窗口流程。没有引入 FFmpeg、Media3 或转码依赖。

## 支持与拒绝边界

- 仅有限、带 ENDLIST 的连续 MPEG-TS 点播清单；视频为 H.264，音频若存在则须 AAC。
- 支持未加密片段与 identity AES-128/CBC，包括显式 IV、媒体序号 IV、密钥轮换和 METHOD=NONE。
- 主播放列表、fMP4/MAP、字节范围、DISCONTINUITY、GAP、直播、SAMPLE-AES、非 identity KEYFORMAT、DRM/PSSH、加密/部分媒体样本一律明确失败，不降级成错误媒体、不尝试破解。
- 明确 4xx（含认证、付费、限流及地区限制）或业务拒绝后不再尝试镜像或代理；返回的媒体地址/密钥/分片每次连接及重定向仍通过 NetworkGuard HTTPS、公网地址和证书检查。
- 黄果只有官方当前片源，投屏按「自动」记录，不把播放器此前的 1080p 偏好当作实际保证。
- 清单最多 512 KiB / 1024 片 / 2 小时；单片最多 16 MiB，单集传输及 MP4 受现有 96 MiB 文件上限约束，总准备期限 3 分钟（含等待与读取）。全进程仅一项 HLS 转封装，额外暂存最多一个 TS 来源与一个分片。
- 修复旧 DNS 竞速控制流：每个完成结果只消费一次，无效首路保留另一条合法解析机会；等待受同一期限与取消约束，提交失败也释放已启动任务。没有改变 DNS/DoH 端点、公网检查或 TLS 验证策略。
- 来源有声明时长时，清单不足八成拒绝；转封装还按实际读取样本核对时长，防止提早 EOS 生成短片。
- 取消、停止、失败、过期回调不发布部分文件；中间 TS/分片/密钥及时清理，进程被杀遗留的固定暂存文件在下次下载准备前清理。Android 同步原生媒体调用不能在调用内部强行打断，会在返回后立即检查取消和期限。
- 临时缓存使用独立版本键，遵守原有引用保护和队列清理。手动下载、收藏和普通 HLS 在线播放的边界没有改变。

## 自动检查

新增：

```sh
bash android/tests/run-network-guard-race-tests.sh
bash android/tests/run-huangguo-api-tests.sh
bash android/tests/run-huangguo-hls-tests.sh
bash android/tests/run-huangguo-remux-tests.sh
bash android/tests/run-huangguo-cast-source-tests.sh
```

协议及媒体边界测试使用离线字节夹具和明确的 Android 边界替身，不将其称为真实视频播放验收，也没有生成彩条视频。Android cast CI 用 Java 17 + Android SDK 35 编译全部 Android Java，并运行这些检查及原有网络/缓存/投屏回归；按用户要求，它还构建并保存手机 Debug APK 为 GitHub Actions artifact（保留14天）；不创建正式 Release、不合并、不部署。

本轮本地检查（2026-11-11）：

- 新增 DNS 竞速56项在真实JDK17与Android30离线运行均通过；受影响的网络守卫46项也在最终代码上重新通过。
- 严格取源/拒绝处理 519 项、HLS 协议/下载边界 71 项、转封装边界 17 例、实际缓存/生命周期 93 项通过。取源测试提取真实方法，但网络和 JSON 解析使用明确替身，不是线上 API 验收。
- `npm run check:android` 的 48 项测试及项目检查通过；浏览器接收端 42 项与无 AirPlay 12 项检查通过。
- 原有纯 Java DLNA 集成 55、预取 73、缓存窗口 187、连播 94、暂停 EOF 162、会话诊断 27 项检查通过。
- 初始环境只有 Java21 模块编译器；后来安装了经摘要验证的官方 Temurin JDK17、Android SDK35 和 Gradle8.9，用真实 Java17 重跑上述新增边界套件通过。
- 全部 Android 生产 Java 用真实 SDK35 编译通过；`npm run build:android:ui` 成功；`:app:assemblePhoneDebug` 成功（33 tasks）。仅产生 Debug APK，未改发布/签名/更新渠道。
- 补齐真实 Android JSON 依赖后，原有网络守卫46、请求头3组、投屏来源93、合并来源29、浏览器会话220、浏览器服务器121、浏览器集成78、队列进度16、播放器缓存保护37、DLNA会话149、文件验证19项也通过。
- 开始改动前的原有媒体诊断基线有一项失败：`DlnaMediaDiagnosticsTest.java:191` 的 “successful writes counted before receiver closes”。基本媒体服务器 123 项检查通过；本次未修改该诊断或声称该套件全通过。
- 另有旧 source-deadline 套件的“第3次DNS任务必须饱和拒绝”断言与既有最多8条DNS线程不符：修改前后都为9/10组通过。未改变旧DNS池大小或改写旧断言；本次新竞速56项覆盖共享期限和取消。
- 发现两项旧测试入口漂移：浏览器UI替身缺 `cancelPlaybackWatchdogs`/`decoderPreparing`，旧完整会话runner的BuildConfig缺 `NATIVE_UI`；请求头runner漏 JSON classpath。对应未改动入口未冒充通过；独立会话/请求头测试已用正确依赖直接执行通过。
- Android30 软件模拟器已实际执行原有4组运行时测试，832项通过。真实黄果探针实际执行到 `feed`，以 `IOException` 安全失败，未获得真实媒体；宿主对官方片单请求返回的是 “Site Unavailable” HTML 而非 API JSON。没有更改证书验证、绕过访问限制或用合成视频替代。
- 新增HLS协议71项已在真实Android30运行通过，和上述832项合计903项；DNS修复后重跑46项并新增竞速56项通过，去重后实际Android检查共959项。Debug APK实际安装成功并进入MainActivity，但无硬件加速的软件模拟器频繁出现System UI ANR，未取得正常主页挂载。一次关闭Vulkan的软件GLES冷启尝试仍然迟缓，未视作应用成功验收。
- DNS修复后的最终Debug APK已在同一Android30模拟器重装成功；没有把安装成功当作主页或媒体验收成功。
- 最终本地Debug APK SHA-256：`6d6d925e129805fa030e4c49e2ba1166ea35a6273fa07a4de0adf9b19a00c4c5`。CI会从同一提交另行构建，打包时间/Debug签名不同，不能用这个摘要代替CI构建物的摘要。
- 真实原生媒体转封装、画面解码、电视/浏览器端到端投屏仍未验收。交付的是开发测试 Debug APK，不是正式发行；不能沿用旧发行结果。

## 仍需真实设备验收

1. 仅使用独立测试资料和目录，随机选择真实红果下载素材先回归原投屏，测试全程静音。
2. 再选择可正常在线播放的黄果点播集，验证手机到真实 DLNA 电视和浏览器接收端的画面、时长、音画同步、拖动、连播、五集预取与合并窗口。
3. 准备过程中取消、快速切集、重复连接、熄屏/回前台、停止服务、网络中断后重试；检查旧文件读完之前不被删除，取消后的迟到任务不复播。
4. 无法证明支持的设备或清单类型应保留为未验证；不能以离线测试或 SDK 编译代替真实媒体/接收端验收。
