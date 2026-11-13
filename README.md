# 红果 Android Cast（红果短剧 Hongguo Drama）

> **红果短剧多端第三方客户端** —— 已发布：📱 Android 手机 · 📺 Android TV（WebView / 原生双版本）；开发中：🔵 webOS（LG 电视）· 🍎tv Apple TV（tvOS）。
> 桌面版（Windows / macOS / Linux）由上游项目发布，本仓库专注手机与电视客户端。全部客户端见 [客户端总览](docs/CLIENTS.md) 或 [Releases](https://github.com/skydeadpeng/hongguo-android-cast/releases)。

**红果短剧的 Android 客户端：手机选剧，电视接着看。** 面向 Android 手机与 Android TV，看红果短剧（免费短剧平台）的独立客户端，支持局域网 DLNA 视频投屏：手机选电视、选集控播，同季自动连播，提前缓存后 5 集，看过的临时缓存自动清理。支持按剧分组下载、整部无损合并导出、扫码登录后云同步收藏与观看进度。

**内置黄果漫剧（黄果短剧 / HuangguoAI）免费在线看。** 底部导航一键进入黄果漫剧：AI 漫剧、AI 短剧分类浏览与搜索，点开即在线播放，选集、上一集 / 下一集、自动连播齐全，无需登录；首次使用自动测速选中并记住最快线路，华为 / 荣耀设备同样秒开。黄果入口可在设置中显示 / 隐藏、切换线路、加数字密码锁。

<p align="center">
  <a href="https://github.com/skydeadpeng/hongguo-android-cast/releases/latest"><img alt="最新版本" src="https://img.shields.io/github/v/release/skydeadpeng/hongguo-android-cast?style=flat-square"></a>
  <img alt="Android 8.0 或更新版本" src="https://img.shields.io/badge/Android-8.0%2B-3ddc84?style=flat-square&logo=android&logoColor=white">
  <a href="LICENSE"><img alt="GPL-3.0 许可证" src="https://img.shields.io/badge/license-GPL--3.0-555555?style=flat-square"></a>
</p>

[下载安装](#下载与安装) · [Android 详细说明](ANDROID.md) · [应用内更新](docs/ANDROID-UPDATES.md) · [客户端总览](docs/CLIENTS.md) · [上游项目](https://github.com/jackotom/hongguo-drama)

## 为大屏连续观看而做

- **手机就是遥控器**：发现局域网内的 DLNA 电视或盒子，手机上直接切集、播放 / 暂停、拖动进度，也可停止投屏回到手机继续看。
- **本季连着看**：手机、TV 本机播放及手机投屏均支持同一季内自动下一集；保留横向分季 Tab 与各季观看记录，当前季播完停止，不自动跳到下一季。
- **后 5 集提前准备**：投屏时预取后续 5 集，减少换集等待；按前一集、当前集、后 5 集维护窗口，自动清理窗口外的已看临时缓存，不删除手动下载。
- **可选合并投屏**：把本季连续 2 / 3 / 5 集准备成一段 MP4 再投出，减少接收端换片次数；编码配置不兼容时回退逐集投屏，不进行转码。
- **离开再回来，知道看到哪**：本机保存集数与播放位置。自建同步入口及后台任务已移除，旧数据文件保留；已接入红果官方手机号登录，支持官方收藏、历史与进度同步。
- **照顾取源失败**：支持画质选择、备用源、重新取源及自动降画质重试。不把预览片段误当整集。
- **内置黄果漫剧源**：AI 漫剧 / AI 短剧在线播放，官方签名直连 + 多线路自动测速记忆；设置里可开关入口、固定线路、密码锁；观看记录同样支持删除与清空。

> 5 集是预取目标，不代表每次都已缓存完成；受片源可用性、网络、单集 96 MiB 和投屏缓存 768 MiB 预算影响。手机本机播放沿用自己的预取策略。

## 下载与安装

当前版本：手机 / TV WebView **0.12.68**，原生 TV **0.1.20**。按设备下载一个 APK 安装即可，要求 Android 8.0+。

| 客户端 | 版本 | 下载 | 说明 |
|---|---|---|---|
| Android 手机 / 平板 | 0.12.68 | [下载 APK](https://github.com/skydeadpeng/hongguo-android-cast/releases/download/v0.12.68/Hongguo-0.12.68-phone-release-compatible.apk) | 触屏操作、本机播放、DLNA 投屏、扫码登录云同步 |
| Android TV / 盒子（WebView） | 0.12.68 | [下载 APK](https://github.com/skydeadpeng/hongguo-android-cast/releases/download/v0.12.68/Hongguo-0.12.68-tv-release-compatible.apk) | 遥控器友好的 WebView 界面，原生解码播放 |
| Android 原生 TV | 0.1.20 | [下载 APK](https://github.com/skydeadpeng/hongguo-android-cast/releases/download/v0.1.20/HongguoNative-0.1.20-tvNative-release-compatible.apk) | 不依赖系统 WebView 的全原生电视界面 |

[查看全部版本](https://github.com/skydeadpeng/hongguo-android-cast/releases) · [版本记录与验证情况](ANDROID.md)

- **手机**：下载后直接安装（需允许安装未知来源应用）；覆盖安装保留本地记录。
- **TV**：U 盘拷入 APK 用电视自带文件管理器安装，或通过 adb 安装：`adb install Hongguo-*-tv-*.apk`。TV 版与手机版应用 ID 不同，互不影响。
- **升级**：0.12.11 起可在「关于 → 版本更新」检查并安装新版，安装包经大小、SHA256、实际包名 / 版本 / 签名核验后才交系统安装；正在播放、投屏或下载时不强行更新。详见[应用内更新说明](docs/ANDROID-UPDATES.md)。

本项目是社区维护的开源修改版，**不是红果、西饭或河马的官方客户端，也未获平台官方背书**。不提供付费分集解锁；剧集、清晰度和可用范围取决于来源平台。

## 开始使用

1. 打开「设置」，选择下载目录并保存。
2. 在「发现短剧」浏览，或在「搜索与下载」输入剧名、红果分享链接或剧集 ID。
3. 打开详情点「立即播放」，也可选择集数与清晰度加入下载队列。
4. 下次从「我的剧库」或「观看记录」接着看；下载进度、失败重试和整剧合并在「下载管理」。

## 三步开始 DLNA 投屏

1. **连同一局域网**：手机与电视连接同一 Wi-Fi / 局域网，避免访客网络或设备隔离；在电视上打开支持 DLNA 的无线投屏接收入口。
2. **手机选电视**：在手机版打开剧集播放器，点击「投屏」，搜索并选择电视。片源先在手机准备，随后经局域网交给电视播放；这不是手机屏幕镜像。
3. **手机控播放**：直接选集或上一集 / 下一集，按需打开「连播」。投屏菜单可管理连接、重试、查看诊断或停止；通知栏也能停止投屏。

### 想减少频繁换片？试试合并投屏

在投屏菜单选择每段 **2 / 3 / 5 集**，停止后重新连接 DLNA 生效。手机会先准备本季连续分集，再做样本级无损拼接；首次准备需要更久，不跨季拼接，也不新增编码转换。各集解码配置不一致时，自动回到逐集模式。

### 电视兼容性

接收端需要支持 **DLNA MediaRenderer / AVTransport**。仅有 Miracast 镜像或仅有 Google Cast 不等于支持 DLNA；搜索、控播、定位和片尾状态表现因电视及接收应用而异。没有匹配所有型号的通用保证，遇到问题可复制应用内的投屏诊断。合并投屏的实体设备兼容性仍需实际验证。

## Mac、iPhone、iPad：浏览器也能接收

手机版还保留「**投屏 → Mac / iPhone / iPad · 浏览器接收**」：

1. 两端连接同一局域网，安卓手机开启浏览器接收。
2. 用 Safari 打开手机显示的接收地址，输入本次配对码。
3. 在接收页点「开始播放」，随后用手机或接收页控制播放与同季连播。

视频仍走「**片源 → 安卓手机缓存 → 局域网 → 浏览器**」，无需另装 Mac 后端。接收页应保持可用，避免系统休眠或后台挂起。

这不是安卓原生 AirPlay，也不是系统屏幕镜像。Safari 提供 AirPlay 按钮时，可由 Safari 再转投；项目未实现安卓端 Apple TV 发现与配对。Safari / Apple TV 的实体设备兼容性仍需验收。完整说明见 [Android 文档](ANDROID.md)。

## 弹幕、账号与更新

- 播放器内可主动开启只读时间轴弹幕，默认关闭；跟随暂停、拖动与换集。离线和投屏期间不叠加弹幕，读取失败不阻断播放。
- 自建同步登录、导入和网络后台任务已撤下；原本机游客记录继续可用，历史账号文件留在原处但不再加载或自动合并。官方手机号登录、追加短信验证与云端收藏/历史/进度同步已接入；仅中国大陆+86，真实验证码在设备上输入。账号操作详见[账号说明](docs/ANDROID-ACCOUNT-SYNC.md)。
- 「关于 → 版本更新」检查本仓库手机 / TV 发行渠道，下载经大小、SHA256、实际包名/版本/签名核验后交系统确认安装；正在播放、投屏或下载时不强行更新。
- 0.12.10 及更早版本先手动覆盖安装一次；0.12.11 及后续版本从「关于 → 版本更新」读取本仓库手机/TV稳定清单。见[更新发布说明](docs/ANDROID-UPDATES.md)。

## 开发中：webOS 与 Apple TV

- **webOS 电视版（LG 电视）**：复用 web 界面与桌面版 JS 取源链路（纯浏览器运行时 + webOS 跨域许可），遥控器焦点导航，HTML5 视频播放，打包为 webOS `.ipk`。源三接口已实测、ipk 已打包，webOS 实机播放待验收。
- **Apple TV 版（tvOS）**：SwiftUI + AVPlayer 原生方案，取源签名链按黄金向量测试移植到 Swift。将以 Xcode 源码工程形式发布：tvOS 应用必须经 Xcode 签名才能安装到设备（免费 Apple ID 亦可），无法像 APK 一样直接分发安装包。

规划与状态详见[客户端总览](docs/CLIENTS.md)。

## 桌面版与上游项目

本仓库基于上游桌面项目 [jackotom/hongguo-drama](https://github.com/jackotom/hongguo-drama)（GPL-3.0）分叉开发，在其取源、播放与下载内核上追加 Android 手机 / TV 与 webOS 客户端。

桌面版（Windows / macOS / Linux）的安装包、应用内更新与版本说明**由上游项目发布**，请前往 [上游 Releases](https://github.com/jackotom/hongguo-drama/releases/latest) 下载；本仓库 Releases 只提供手机与电视客户端。上游的完整功能说明、版本历史与常见问题也见上游仓库 README。

## 从源码构建

应用基于 Electron + React + Node.js。准备 Node.js **22.12+**、npm、JDK 17、Android SDK Platform 35，以及 Gradle 8.13 或项目 Gradle Wrapper。在仓库根目录执行：

```sh
npm ci

# 手机 / TV WebView / 原生 TV debug 包
npm run build:android

# 非调试 release 构建，使用本机兼容证书
npm run build:android:phone:release-compatible
npm run build:android:tv:release-compatible
bash scripts/build-android.sh --tvnative-release-compatible

# 仅编译原生 TV debug，适合已准备好 Android SDK 的开发环境
gradle -p android :app:assembleTvNativeDebug
```

结果写入 `dist/android/`。兼容证书默认读取 `~/.android/debug.keystore`，也可通过 `HONGGUO_COMPAT_KEYSTORE` 指定；新证书与发布包证书不一致时，需要卸载后安装，卸载前请保存所需数据。

正式分发可使用自己的外部签名，设置 `HONGGUO_RELEASE_KEYSTORE`、`HONGGUO_RELEASE_STORE_PASSWORD`、`HONGGUO_RELEASE_KEY_ALIAS`，以及可选的 `HONGGUO_RELEASE_KEY_PASSWORD`，然后执行：

```sh
npm run build:android:phone:release
npm run build:android:tv:release
```

签名私钥与密码不提交到仓库。依赖已缓存时可加 `GRADLE_OFFLINE=1` 离线构建。

界面与下载播放已在 Android 模拟器验证；具体电视遥控、厂商系统和解码兼容性仍以设备表现为准。更多构建、测试与版本记录见 [ANDROID.md](ANDROID.md)；更新发布步骤见[应用内更新](docs/ANDROID-UPDATES.md)。

## 项目结构

| 路径 | 内容 |
| --- | --- |
| `src/android/` | 手机 / TV WebView 界面、桥接与数据源解析 |
| `android/app/src/tvNative/` | 独立原生 TV 启动配置；界面由 Java `Tv*` 类实现 |
| `android/` | 原生播放器、DLNA、浏览器接收、缓存与本机记录 |
| `server/login/` | 历史自建服务源码（Android 0.12.11 起不再连接） |
| `src/native/` | 与桌面版共享的部分取源实现 |
| `webos/` | webOS 电视客户端（开发中） |
| `ANDROID.md` | Android 版本记录、功能说明与验证情况 |

## 来源与许可

基于 [jackotom/hongguo-drama](https://github.com/jackotom/hongguo-drama) 追加 Android 手机、TV 与局域网投屏实现，感谢上游作者。原项目及第三方声明继续保留，采用 [GPL-3.0](LICENSE)；请同时阅读 [NOTICE](NOTICE) 与 [第三方声明](THIRD-PARTY-NOTICES.md)。

这是社区开源修改版，不是来源平台的官方客户端。剧集、清晰度和可播放范围由片源决定；自动化验证不等同于全部手机、电视或接收端上的实机播放验收。
