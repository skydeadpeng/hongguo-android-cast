# Android 播放修复联合发行 · 2026-11-19

- [手机版与 TV WebView 0.12.81](https://github.com/skydeadpeng/hongguo-android-cast/releases/tag/v0.12.81)：code104，Latest，公开于 2026-11-19T22:16:25+08:00。
- [原生 TV 0.1.32](https://github.com/skydeadpeng/hongguo-android-cast/releases/tag/v0.1.32)：code33，分别提供 arm64-v8a / armeabi-v7a，公开于 2026-11-19T22:16:22+08:00。
- 两个标签均冻结至 `3d984f5dd6126171411eb162c12868437538e22e`；发行后文档不修改标签或附件。

## 根因与修复

手机正式候选测试中，首集播放后从真实「下一集」切第 2 集，主接口返回空正文，兼容 GET 在建连接阶段抛出 `ConnectException`。该异常以前进了无效响应分类，未进入现有有界重试。修复仅在兼容 GET 的传输边界映射 `ECONNREFUSED`，先检查取消和截止；GET 采用同一固定服务主机的系统 DNS 完整地址列表，不再固定单地址。未改变分集、签名、身份、清晰度或明确拒绝规则。没有确认上游某个地址为何拒绝连接。

此前另一个非法解压缺口已独立修正：带 gzip 头的零字节响应按空响应或 Retry-After 分类；真正损坏的非空 gzip 仍被拒绝。保留两轮失败报告，不把诊断包单独点第 2 集视为切集成功。

## 正式包验收

独立 API32 arm64 模拟器，真实红果素材，冷缓存、音量 0 且静音；首集实际完整缓存与播放，按生产「下一集」按钮，第二集实际出帧并持续播放。手机版两集均曾在主接口返回空正文后通过备用 GET 实际取源成功。三个包覆盖安装均核验合成文件与偏好留存；未验实体设备、原生 32 位运行或完整应用内 OTA 安装流程。

| 客户端 | code | 首集实际进度增量 | 切第 2 集后进度增量 |
|---|---:|---:|---:|
| phone | 104 | 30426 ms | 10203 ms |
| tv | 104 | 30286 ms | 10161 ms |
| tvNative | 33 | 30201 ms | 10217 ms |

## 附件与通道

| APK | 字节数 | SHA-256 |
|---|---:|---|
| [Hongguo-0.12.81-phone-release-compatible.apk](https://github.com/skydeadpeng/hongguo-android-cast/releases/download/v0.12.81/Hongguo-0.12.81-phone-release-compatible.apk) | 1724434 | `7223fae6e4dd2af90d8fc580291b7e6328cf9691b8a9e28b2a0d419f690b0396` |
| [Hongguo-0.12.81-tv-release-compatible.apk](https://github.com/skydeadpeng/hongguo-android-cast/releases/download/v0.12.81/Hongguo-0.12.81-tv-release-compatible.apk) | 1723686 | `22b10ebe1189b4ea202bd83dc97ac72d709a26e001472e83414c63745699c2b2` |
| [HongguoNative-0.1.32-tvNative-release-compatible-arm64-v8a.apk](https://github.com/skydeadpeng/hongguo-android-cast/releases/download/v0.1.32/HongguoNative-0.1.32-tvNative-release-compatible-arm64-v8a.apk) | 47053351 | `d01d8a0d8900fbea1c9bfbfab087b8c6a06c1405e540e360310bc7137bd8b6db` |
| [HongguoNative-0.1.32-tvNative-release-compatible-armeabi-v7a.apk](https://github.com/skydeadpeng/hongguo-android-cast/releases/download/v0.1.32/HongguoNative-0.1.32-tvNative-release-compatible-armeabi-v7a.apk) | 43325745 | `75922acd987bb1984d302149ec796acdda0a6435b4ad8adeeeaf28ef943a046d` |

四个 APK 沿用此前兼容安装证书。两套 Release 共 15 个附件于 2026-11-19T22:18:36+08:00 完成匿名完整下载，和冻结本地文件逐个核对实收字节数及 SHA-256。之后才提交并推送三个稳定通道，提交为 `c3e0adf956256052537c60426dda78668a2bc72f`。原始与带缓存戳的 raw URL 均复核 104 / 104 / 33，并与各发行快照逐字节相同。旧版 Release 附件未覆盖。
