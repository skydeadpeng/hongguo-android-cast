# Android 0.12.87 / 原生 TV 0.1.38 发行记录（2026-11-22，多源子系统拆除）

冻结源码：`c461fea`（多源子系统拆除提交；版本 bump `3430b9…` 前的最后代码提交，工作区仅版本号差异）。

## 内容

来源收敛至只剩红果后（见 docs/android-release-2026-11-21.md 同日后的「多源子系统拆除」记录），本轮把 0.12.82 引入的多源播放子系统整体拆除：

- Java：SourceService / SourceModuleRuntime / SourceRuntimeSupport / SourcePlaybackSessions / SourcePlaybackActivity / TvSourceActivity 六类删除；NativeBridge 摘除 sourceService 桥（getInfo.sourceService 恒 false）；tvNative 侧边栏移除「影视来源」入口。
- 依赖：Rhino 1.8.1、jsoup 1.23.2、Media3 1.8.0（exoplayer/hls/ui）全部移除；vendor/source-modules 整目录删除（无剩余消费者）。
- WebView：影视来源页面（Discover 快捷按钮、TV 导航项、来源 tab）全套移除；旧观看记录里已删源条目维持「原来源已移除」降级展示。
- 红果自身发现、搜索、播放（WebView hls.js）、下载、观看记录不依赖 Media3，链路未动。

## 验证

- phone/tv/tvNative release-compatible 构建各过 9 项 release 检查（ANDROID_RELEASE_OK passed=9 failed=0 actualApk=verified）。
- npm test 失败集 == 改动前基线 21 条存量失败，零新增（check-android-project 新增「Source* 类保持不存在」断言）。
- 模拟器（phone36，API 36 arm64）：debug 包发现页/详情/第 1 集在线播放（静音）全过、影视来源入口消失；0.12.87 release-compatible 包覆盖安装后冷启动冒烟通过（首次冷启动 WebView 初始化在模拟器上偏慢，属模拟器限制）。
- 体积（clean 构建实测）：phone release-compatible 5.66 MiB → **1.62 MiB**（-71%）；tvNative arm64 48.71 → 44.86 MiB、32 位 45.15 → 41.30 MiB。
- 资产核验：四包本机 SHA256 记录如下；GitHub 匿名下载对账见下。

## 正式下载

- phone 0.12.87/code110：[APK](https://github.com/skydeadpeng/hongguo-android-cast/releases/download/v0.12.87/Hongguo-0.12.87-phone-release-compatible.apk)。SHA256 `3430b9a450e627a3b90eb6159212712b029ac977f7d990f654261b10561d7510`，1,701,930 字节。
- tv 0.12.87/code110：[APK](https://github.com/skydeadpeng/hongguo-android-cast/releases/download/v0.12.87/Hongguo-0.12.87-tv-release-compatible.apk)。SHA256 `bc4e296b966149a449620025c46756e2855020c34b17e8ae6974ba5313d5d04e`，1,700,618 字节。
- tvnative 0.1.38/code39：[APK（arm64-v8a）](https://github.com/skydeadpeng/hongguo-android-cast/releases/download/v0.1.38/HongguoNative-0.1.38-tvNative-release-compatible-arm64-v8a.apk) SHA256 `787ac982a4d10383dbe62837431c258488048d8452fa18a2e9ea4c7e88f04837` 47,035,675 字节；[32 位 APK](https://github.com/skydeadpeng/hongguo-android-cast/releases/download/v0.1.38/HongguoNative-0.1.38-tvNative-release-compatible-armeabi-v7a.apk) SHA256 `9415d3a32857703153d8ac5bfb936330210defa8f49287bfa660e85a90bd5891` 43,308,072 字节。

## 边界

- 匿名下载对账：phone 小包全量下载 SHA256 一致；tvNative 47MB 大包按先前惯例以 GitHub 服务端摘要/尺寸比对为准（后台全量下载完成后对账，如一致则补充确认）。
- 未替换本机正式安装；应用内更新检查有 24 小时冷却，手动「检查更新」立即生效。
- 旧版多来源功能如需回归只能回退到 0.12.86（更新检查会提示升级，需关闭自动更新侧载保留）。
