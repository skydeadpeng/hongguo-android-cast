# 红果 Android Cast

**手机选剧，电视接着看。** 面向 Android 手机与 Android TV 的独立客户端，支持局域网 DLNA 视频投屏：手机选电视、选集控播，同季自动连播，提前缓存后 5 集，看过的临时缓存自动清理。

[下载手机 / TV 版](https://github.com/skydeadpeng/hongguo-android-cast/releases) · [Android 详细说明](ANDROID.md) · [应用内更新](docs/ANDROID-UPDATES.md) · [上游项目](https://github.com/jackotom/hongguo-drama)

## 为大屏连续观看而做

- **手机就是遥控器**：发现局域网内的 DLNA 电视或盒子，手机上直接切集、播放 / 暂停、拖动进度，也可停止投屏回到手机继续看。
- **本季连着看**：手机、TV 本机播放及手机投屏均支持同一季内自动下一集；保留横向分季 Tab 与各季观看记录，当前季播完停止，不自动跳到下一季。
- **后 5 集提前准备**：投屏时预取后续 5 集，减少换集等待；按前一集、当前集、后 5 集维护窗口，自动清理窗口外的已看临时缓存，不删除手动下载。
- **可选合并投屏**：把本季连续 2 / 3 / 5 集准备成一段 MP4 再投出，减少接收端换片次数；编码配置不兼容时回退逐集投屏，不进行转码。
- **离开再回来，知道看到哪**：本机保存集数与播放位置。自建同步入口及后台任务已移除，旧数据文件保留；已接入红果官方手机号登录，支持官方收藏、历史与进度同步。
- **照顾取源失败**：支持画质选择、备用源、重新取源及自动降画质重试。0.12.8 补齐网页预览识别、完整源回退与取源等待期限，不把预览误当整集。

> 5 集是预取目标，不代表每次都已缓存完成；受片源可用性、网络、单集 96 MiB 和投屏缓存 768 MiB 预算影响。手机本机播放沿用自己的预取策略。

## 下载与安装

手机 / TV WebView 当前版本：**0.12.18 / code41**，见 [v0.12.18 Releases](https://github.com/skydeadpeng/hongguo-android-cast/releases/tag/v0.12.18)。独立原生 TV 当前版本：**0.1.2 / code3**，见 [v0.1.2 Releases](https://github.com/skydeadpeng/hongguo-android-cast/releases/tag/v0.1.2)。本地构建输出位于 `dist/android/`。

| 版本 | 文件名 | 用途 |
| --- | --- | --- |
| 手机 WebView | `Hongguo-0.12.18-phone-release-compatible.apk` | 手机触控、本机播放、DLNA / 浏览器投屏 |
| TV WebView | `Hongguo-0.12.18-tv-release-compatible.apk` | 系统 WebView 界面，电视遥控与原生播放 |
| 独立原生 TV | `HongguoNative-0.1.2-tvNative-release-compatible.apk` | 全原生电视界面与 Java 取源，不依赖系统 WebView |

- 三种安装包均要求 Android **8.0+**（API 26），使用原生视频解码器；只有手机 / TV WebView 版的界面依赖系统 WebView。
- 手机与 TV 使用不同应用 ID。TV 版是独立播放器，安装 TV APK 不会自动把电视变成 DLNA 接收器。
- `release-compatible` 为非调试 release 构建，但沿用既有安装证书，方便旧用户覆盖安装保留记录；不是独立生产签名。自行构建时，只有使用同一证书才能覆盖原包。
- [桌面版原说明](README.desktop.md)与 Electron 桌面源码仍保留，当前基线为 2.3.0；Android 手机 / TV WebView 版本号为 0.12.18，原生 TV 使用独立的 0.1.x 版本线，只按需借鉴上游改进，未整体升级为桌面 3.8。
- 0.12.12 接入官方短信登录/MFA、官方云同步、官方系列剧目录及完整账号隔离。游客记录仅显式确认后导入；较大账号库采用有界UI投影和原生单剧进度点查。
- 0.12.11 移除自建同步账号功能，新增本机只读时间轴弹幕与应用内更新器；首个更新器版本需手动安装，稳定更新清单需随自己的 Release 发布。官方短信登录尚未接入。
- 0.12.10 对 App 取源的 HTTP 200 空数据增加一次同集、同画质的有界重签恢复，区分空响应与无效格式；不把空响应当作高清缺失，不自动跳集。
- 0.12.9 保留刷新失败前的列表，新增官网搜索备用通道与分集双入口有界加载，并取消独立 AirPlay 接收器；原有 DLNA、浏览器投屏、账号同步和严格下载画质保持。

0.12.13 补齐游客收藏导入、播放中进度 checkpoint、不确定发码输入和MFA边界；修复会话刷新丢失、同步状态卡住及旧剧详情刷新。0.12.12历史资产保持不变；安装0.12.11及后续版本的用户可在「关于 → 版本更新」检查升级。

0.12.14 修复应用覆盖升级后缓存恢复的状态误报：缓存包必须完整验证通过才显示“可继续安装”；同版已安装、损坏、签名不匹配等缓存会清理，不把删除后的包当作可安装。

## 原生 TV 独立版

**给电视或盒子直接安装使用的原生客户端，不是手机版，也不是手机投屏接收器。** 适合系统 WebView 老旧、网页版界面兼容性欠佳的设备；电视端用于本机播放，手机端保留 DLNA / 浏览器投屏控制。

| 项目 | 说明 |
| --- | --- |
| 当前版本 | **0.1.2 / code3**，独立维护，不与 WebView 版 0.12.x 比较版本号 |
| 构建 flavor | `tvNative` |
| 包名 | `io.github.jackotom.hongguo.android.tvnative` |
| 运行方式 | Java 原生界面、原生取源与共享播放器，横屏 / 遥控器操作 |
| 安装包 | [HongguoNative-0.1.2-tvNative-release-compatible.apk](https://github.com/skydeadpeng/hongguo-android-cast/releases/download/v0.1.2/HongguoNative-0.1.2-tvNative-release-compatible.apk) |
| 对应源码 | [0.1.2 源码与校验和](https://github.com/skydeadpeng/hongguo-android-cast/releases/tag/v0.1.2)；此仓库同时保留整个红果项目源码 |

- **界面与观看**：海报分类、搜索与搜索历史管理、收藏、观看历史与续播、详情选集和分季；支持清晰度、倍速、自动连播、弹幕和本机下载 / 离线播放 / 确认删除。
- **遥控器**：方向键移动，确认键进入或播放，返回键返回；白色边框表示当前焦点，红色状态表示当前栏目或分类。
- **账号与数据**：可接入红果官方手机号登录及收藏 / 历史 / 进度同步。三种安装包可共存，但各自的本机记录、下载文件与设置独立；不会自动读取另一包的数据。游客记录只在确认后导入当前账号。
- **自动升级**：设置内「自动检查更新」默认开启，启动、返回前台与前台周期检查；成功检查间隔 24 小时，失败按 15 分钟间隔重试。发现新版后确认下载，校验大小、SHA-256、包名、版本和签名后，再由系统确认安装；播放中不弹出升级提示。关闭自动后仍可手动检查。
- **安装选择**：TV WebView 版和原生 TV 版可同时保留；只在同包名、同签名的原生版本之间覆盖升级。原生渠道使用 `android/update-tvnative.json`，不会替换手机 / TV WebView 稳定清单。

```sh
# 独立原生 TV：使用现有兼容证书的 release 构建
bash scripts/build-android.sh --tvnative-release-compatible

# 仅编译原生 TV debug，适合已准备好 Android SDK 的开发环境
gradle -p android :app:assembleTvNativeDebug
```

界面与下载播放已在 Android 模拟器验证；具体电视遥控、厂商系统和解码兼容性仍以设备表现为准。更新流程与源码发布步骤见 [应用内更新](docs/ANDROID-UPDATES.md)。

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
- 自建同步登录、导入和网络后台任务已撤下；原本机游客记录继续可用，历史账号文件留在原处但不再加载或自动合并。官方手机号登录、追加短信验证与云端收藏/历史/进度同步已接入；仅中国大陆+86，真实验证码在设备上输入。
- 「关于 → 版本更新」检查我们自己的手机/TV发行渠道，下载经大小、SHA256、实际包名/版本/签名核验后交系统确认安装；正在播放、投屏或下载时不强行更新。
- 0.12.10及更早版本先手动覆盖安装一次；0.12.11及后续版本从「关于 → 版本更新」读取本仓库手机/TV稳定清单。0.12.11首次授权安装来源后如停在等待状态，重新检查更新再继续安装；0.12.12已修复该返回分支。见[更新发布说明](docs/ANDROID-UPDATES.md)；官方账号操作见[账号说明](docs/ANDROID-ACCOUNT-SYNC.md)。

## 从源码构建

准备 Node.js **22.12+**、npm、JDK 17、Android SDK Platform 35，以及 Gradle 8.13 或项目 Gradle Wrapper。在仓库根目录执行：

```sh
npm ci

# 手机 / TV WebView / 原生 TV debug 包
npm run build:android

# 非调试 release 构建，使用本机兼容证书
npm run build:android:phone:release-compatible
npm run build:android:tv:release-compatible
bash scripts/build-android.sh --tvnative-release-compatible
```

结果写入 `dist/android/`。兼容证书默认读取 `~/.android/debug.keystore`，也可通过 `HONGGUO_COMPAT_KEYSTORE` 指定；新证书与发布包证书不一致时，需要卸载后安装，卸载前请保存所需数据。

正式分发可使用自己的外部签名，设置 `HONGGUO_RELEASE_KEYSTORE`、`HONGGUO_RELEASE_STORE_PASSWORD`、`HONGGUO_RELEASE_KEY_ALIAS`，以及可选的 `HONGGUO_RELEASE_KEY_PASSWORD`，然后执行：

```sh
npm run build:android:phone:release
npm run build:android:tv:release
```

签名私钥与密码不提交到仓库。依赖已缓存时可加 `GRADLE_OFFLINE=1` 离线构建。更多构建、测试与版本记录见 [ANDROID.md](ANDROID.md)。

## 项目结构

| 路径 | 内容 |
| --- | --- |
| `src/android/` | 手机 / TV WebView 界面、桥接与数据源解析 |
| `android/app/src/tvNative/` | 独立原生 TV 启动配置；界面由 Java `Tv*` 类实现 |
| `android/` | 原生播放器、DLNA、浏览器接收、缓存与本机记录 |
| `server/login/` | 历史自建服务源码（Android 0.12.11 起不再连接） |
| `src/native/` | 与桌面版共享的部分取源实现 |
| `ANDROID.md` | Android 版本记录、功能说明与验证情况 |

## 来源与许可

基于 [jackotom/hongguo-drama](https://github.com/jackotom/hongguo-drama) 追加 Android 手机、TV 与局域网投屏实现，感谢上游作者。原项目及第三方声明继续保留，采用 [GPL-3.0](LICENSE)；请同时阅读 [NOTICE](NOTICE) 与 [第三方声明](THIRD-PARTY-NOTICES.md)。

这是社区开源修改版，不是来源平台的官方客户端。剧集、清晰度和可播放范围由片源决定；自动化验证不等同于全部手机、电视或接收端上的实机播放验收。
