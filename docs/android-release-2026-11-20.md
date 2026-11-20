# Android 0.12.82 / 原生 TV 0.1.33

冻结源码：`caeaa2969bef7909f4f5ff3488ab6ad20462a360`（两标签同一提交）。

新增 15 个影视来源中台入口（5 个默认启用）；移除原独立黄果入口；更新发现、搜索、详情和影视来源 UI。内置来源原生播放器提供静音、暂停、可定位进度及全屏。红果原生播放的正式取源顺序与上版相同。

## 正式下载

- phone 0.12.82/code105：[APK](https://github.com/skydeadpeng/hongguo-android-cast/releases/download/v0.12.82/Hongguo-0.12.82-phone-release-compatible.apk)。
- tv 0.12.82/code105：[APK](https://github.com/skydeadpeng/hongguo-android-cast/releases/download/v0.12.82/Hongguo-0.12.82-tv-release-compatible.apk)。
- tvnative 0.1.33/code34：[APK](https://github.com/skydeadpeng/hongguo-android-cast/releases/download/v0.1.33/HongguoNative-0.1.33-tvNative-release-compatible-arm64-v8a.apk)（arm64-v8a），[32 位 APK](https://github.com/skydeadpeng/hongguo-android-cast/releases/download/v0.1.33/HongguoNative-0.1.33-tvNative-release-compatible-armeabi-v7a.apk)。

## 验收

四个非调试 APK 的包名、版本、ABI、证书、ZIP 结构通过验证。手机在 Android 36 模拟器从上一版覆盖升级，保留旧版搜索历史；两款电视线在 Android 34 模拟器覆盖升级并实际遥控浏览、启用状态和返回。原生 TV 32 位包通过构建、结构、签名与摘要检查，未实际运行。签名正式包未做在线播放或应用内安装流程的设备端验收。内置来源播放器曾在隔离调试包使用已下载真实素材离线验证 MP4/HLS、首帧、暂停续播、跳转、全屏、错误和返回。

公开附件 15 个完成匿名完整下载及 SHA-256 核验。旧版发行与历史标签不替换。
