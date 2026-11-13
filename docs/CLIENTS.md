# 红果短剧客户端总览（全平台下载与安装指南）

红果短剧（Hongguo Drama / 红果免费短剧）的多平台第三方客户端合集：手机、电视、电脑都能看。本项目是独立开源客户端，非官方出品；播放、下载、收藏与进度同步能力与官方接口一致。

## 已发布客户端

| 平台 | 版本线 | 下载 | 一句话说明 |
| --- | --- | --- | --- |
| Android 手机 | 0.12.x | [Releases](https://github.com/skydeadpeng/hongguo-android-cast/releases) `Hongguo-*-phone-*.apk` | 触屏操作、本机播放、DLNA 投屏、按剧下载、扫码登录云同步 |
| Android TV / 电视盒子 | 0.12.x | [Releases](https://github.com/skydeadpeng/hongguo-android-cast/releases) `Hongguo-*-tv-*.apk` | 遥控器友好的 WebView 界面，原生解码播放 |
| Android 原生 TV | 0.1.x | [Releases](https://github.com/skydeadpeng/hongguo-android-cast/releases) `HongguoNative-*.apk` | 不依赖系统 WebView 的全原生电视界面 |
| Windows 电脑 | 以上游为准 | [上游 Releases](https://github.com/jackotom/hongguo-drama/releases/latest) `hongguo-downloader-*-win-x64-setup.exe` / `*-win-x64.zip` | 电脑看短剧，批量下载 + 一键合并导出 mp4；由上游项目发布 |
| macOS 电脑（Apple Silicon / Intel） | 以上游为准 | [上游 Releases](https://github.com/jackotom/hongguo-drama/releases/latest) `Hongguo-*-mac-*.dmg` / `.zip` | 同 Windows 版功能，macOS 13+；由上游项目发布 |
| Linux 电脑 | 以上游为准 | [上游 Releases](https://github.com/jackotom/hongguo-drama/releases/latest) `hongguo-downloader-*-linux-x64.AppImage` | AppImage 双击即用；由上游项目发布 |

Android 客户端由本仓库发布；桌面三端（Windows / macOS / Linux）由上游项目 [jackotom/hongguo-drama](https://github.com/jackotom/hongguo-drama) 构建与发布，版本与更新以上游为准。

### 安装要点

- **Android 手机 / 平板**：下载 phone APK 直接安装（需允许安装未知来源应用）；覆盖安装保留本地记录；要求 Android 8.0+。
- **Android TV / 投屏盒子 / 电视**：U 盘拷入 TV APK 用电视自带文件管理器安装，或通过 adb 安装：`adb install Hongguo-*-tv-*.apk`。TV 版与手机版应用 ID 不同，互不影响。
- **Windows**：setup.exe 安装版（开始菜单 / 桌面快捷方式）或 zip 免安装绿色版（解压即用）。上游 EXE 未做代码签名，SmartScreen 首次提示选「仍要运行」。
- **macOS**：Apple Silicon 选 `mac-arm64`，Intel 选 `mac-x64`。上游 Mac 包已完成开发者签名与公证，可直接打开。内置 ffmpeg，无需 Homebrew。
- **Linux**：`chmod +x *.AppImage && ./…AppImage`。内置 ffmpeg。
- 桌面三端的合并导出、兼容格式转码所需 ffmpeg / ffprobe 均已随包内置，来源与许可见各包 `resources/bin` 内说明文件。

## 开发中的客户端

- **webOS 电视版（LG 电视）**：面向 LG webOS TV 的电视应用。技术方案：复用 web 界面与桌面版 JS 取源链路（纯浏览器运行时 + webOS 关闭 webSecurity 的跨域许可），遥控器焦点导航，HTML5 视频播放；打包为 webOS .ipk。状态：开发中。
- **Apple TV 版（tvOS）**：面向 Apple TV 的原生 tvOS 应用（SwiftUI + AVPlayer），取源签名链按黄金向量测试移植到 Swift。发布形式为 Xcode 源码工程：tvOS 应用必须经 Xcode 签名才能安装到设备（免费 Apple ID 亦可），无法像 APK 一样直接分发安装包。状态：开发中。

## 核心能力（所有客户端一致）

- 红果短剧集库浏览、搜索、换一批发现
- 多画质播放（自动 / 1080P / 720P / 540P / 360P）与倍速
- 按剧分组下载、批量下载、整部无损合并导出单个 mp4（桌面版）/ 合并版入库（Android 版）
- 扫码登录红果账号，收藏与观看进度云同步
- Android 版支持 DLNA / 浏览器投屏到电视

## 常见搜索入口

红果短剧 TV 版下载 · 红果短剧 电脑版 · 红果短剧 Windows 客户端 · 红果短剧 Mac 客户端 · 红果短剧 Linux · 红果免费短剧 电视 · 红果短剧 APK · 红果短剧 投屏 · 短剧 DLNA · 红果短剧 合并下载 · 红果短剧 离线缓存 · 红果短剧 webOS · 红果短剧 Apple TV

## 相关文档

- [Android 详细说明](../ANDROID.md)
- [桌面版原说明](../README.desktop.md)
- [应用内更新机制](ANDROID-UPDATES.md)
