# Android 0.12.80 / 原生 TV 0.1.31 联合发行

日期：2026-11-17（Asia/Shanghai）。手机／TV WebView 0.12.80 与原生 TV 0.1.31 已正式公开发布。仅涉及手机、TV WebView、tvNative，不发布桌面或 webOS 新版。

## 版本与修复

| 客户端 | 版本 / versionCode | 最低系统 | 安装包 |
|---|---|---|---|
| 手机 | 0.12.80 / 103 | Android 8.0 / API26 | `Hongguo-0.12.80-phone-release-compatible.apk` |
| TV WebView | 0.12.80 / 103 | Android 8.0 / API26 | `Hongguo-0.12.80-tv-release-compatible.apk` |
| 原生 TV arm64 | 0.1.31 / 32 | Android 6.0 / API23 | `HongguoNative-0.1.31-tvNative-release-compatible-arm64-v8a.apk` |
| 原生 TV 32位 | 0.1.31 / 32 | Android 6.0 / API23 | `HongguoNative-0.1.31-tvNative-release-compatible-armeabi-v7a.apk` |

- 修正 Java v5 `getIv` / `sumMd5` 的无符号字节处理；真网对照证明原 `getIv` 选错签名分支／ticket 导致 HTTP200 空正文。`sumMd5` 为完整校验一致性修正，不将其单独描述为已证实的取源拒绝原因。
- `RegisteredPlaybackModel.ModelHttpTransport` 的活动 Call 归各实例所有，预取关闭不再取消前台取源。连接池仍共享。
- `PlaybackSources` 显式原生首播空响应恢复共用20秒预算，最多三次延迟重试（1／2.5／5秒）；fresh、外层恢复与媒体刷新保持单次，取消和迟到结果检查保留。
- 发行检查另修正既有 `plan==null` 的 App CDN 域判断反转：App 域缺准备计划时明确报过期，普通 legacy 网页下载保持原策略。完整媒体合同的既有失败不再作为未处理项带入本次发行。
- 回归入口使用实际依赖，v5 比较独立 Kotlin 完整20字节向量。CI 改为实际 Gradle 编译并修正 universal debug APK 上传路径。debug 网络配置移除私人 CA 信任。

原始诊断、上游对照及修复阶段历史结果见 [修复记录](playback-repair-2026-11-17.md)。

## 正式包构建与身份

本轮从当前修复源码重新执行三个 `scripts/build-android.sh --<flavor>-release-compatible`，均成功。不是改名发布此前 QA debug APK。四个最终 APK 均核对包名、versionName/code、minSdk、non-debuggable、实际证书和 ABI；tvNative 副 ABI 另外检查，不只依赖清单生成器。

兼容安装证书 SHA256：`3a883cc41a4ce34b462a8c12c0d289e6c985d3e2c0d6ecf2b516b5cc75e353ab`。保留原三个应用 ID；不清理用户账号、记录、下载或设置。

| APK | SHA256 |
|---|---|
| phone | `fb63b8aa306d2ace15890a6e79cbcd605be15fbb08d0677598041c276701baa2` |
| tv | `7efba4d6b8e8a295f2eb89dcbb7b6fbfc8f71ddad8e7c1a9f5ae25a964f79f77` |
| tvNative arm64 | `26f4e1e212c81b460ab8aa39bfe20ddc1ce214a2f0ca640b5e53de0789b17092` |
| tvNative armeabi-v7a | `39c4251a8cbe8a30cbad91cdd61b8250404cf866cbe03a2836eb184d754c562d` |

构建、APK身份、签名、源码输入摘要及最终清单保存在独立本地目录：`/Users/xiaomingpeng/Desktop/红果/verification/android-release-2026-11-17/`。测试资料、视频、凭据和带签名媒体地址不进入公开源码或发行附件。

## 本轮验收

三个最终 non-debuggable release 在独立 API32 / arm64 模拟器完成本轮真实下载／播放，使用生产取源与生产播放器，未注入媒体地址或成功回调。测试素材为本轮从红果随机选择的《我的婆婆我罩着2：一致对外》前两集（SID `7706890951175719961`）。全程媒体音量0且静音；测试视频不公开分发。

| 客户端 | 公开旧包→新包 | 30秒阶段进度增量 | 实际Surface变帧 | 切第2集后进度增量 |
|---|---|---:|---:|---:|
| phone | code102→103 | 30253ms | 30次 | 10155ms |
| TV WebView | code102→103 | 30045ms | 29次 | 10274ms |
| tvNative arm64 | code31→32 | 30247ms | 30次 | 10107ms |

三线均确认实际1080×1920画面及143767ms首集时长；旧包覆盖安装后私有文件与偏好哨兵保留。9张真实视频Surface PixelCopy图片全部目视核对；UI截图接口曾返回null的旧问题不用于豁免当前门槛。本轮不是完整浏览UI或应用内OTA验收，也不是实体手机／TV验收；armeabi-v7a仅静态架构、签名与元数据核对，未在32位设备运行。测试结束已停止三线播放、保持静音，仅打开手机主界面。

正式包验证器全部通过，见本地 `device/ALL-LINES-VERIFIED.json` 及 `device/VERIFICATION.txt`。此前 `0.12.79-repair.20261117` debug 包结果保持为历史，不代替本次正式包。

离线核心回归已实跑通过：完整NativeMediaContracts（Crypto41／合同213／恢复50／Lookup34／原生Lookup18）、原生恢复11组63项、取消所有权128项、v5签名326项、注册模型230项、共享模型53测试、签名偏移64项；更新生成器与更新策略43测试通过。最终第二轮完整CI前置矩阵30条命令全部exit0（30 PASS／0 FAIL），没有用重跑覆盖失败日志。第一轮既有编译／fixture失败及修复证据保留，见 `regressions/FINAL-SUMMARY.txt` 与 `regressions/round2/SUMMARY.txt`。冻结提交的 main 与两个 tag 远端 Android CI 均已 completed/success，含生产 Java 编译、完整回归、APK 构建及 artifact 上传，详见下方正式发布回执。

发行前同时修正测试入口的真实依赖闭包、已迁移文件复制函数的源码定位，以及host JVM的显式API35与真实NOFOLLOW_LINKS文件系统适配；这些host-only类不编入App。entitlement测试增加完成哨兵，防止悬空Promise导致exit0但实际0项检查；保持原业务断言并补齐测试用AES-CBC桥，修后14组240项真实完成。生产源码及四个APK未因测试入口修复而变化。

## 发布顺序

1. 已合并远端 main 的现有更新清单，未用旧本地清单覆盖远端。
2. 冻结源码、两个版本标签、四个最终 APK、精确源码归档、版本快照与 SHA256。
3. 两组 Release 资产上传后核验下载大小和散列；原生 TV 使用 `latest=false`，手机／TV WebView 为 Latest。
4. 资产公开可取后，最后提交三个稳定更新清单，核对 raw 通道；历史资产与标签不替换。

本轮未将实体设备、应用内安装全流程或32位设备解码写为已通过；已有 ADB 覆盖升级也不代替 OTA 业务验收。

## 正式发布回执

- 冻结源码：`39eb4e8759bf6fa38a642da7ed6157d0a8870126`；`v0.12.80` 与 `v0.1.31` 均精确指向此提交。发行后文档与稳定清单提交不改变冻结标签或已发布 APK。
- 原生 TV 于 **2026-11-17 15:46:35 +08:00** 正式公开，7 个附件；手机／TV WebView 于 **15:46:38 +08:00** 正式公开，8 个附件。
- 正式入口：[手机／TV WebView 0.12.80](https://github.com/skydeadpeng/hongguo-android-cast/releases/tag/v0.12.80) · [原生 TV 0.1.31](https://github.com/skydeadpeng/hongguo-android-cast/releases/tag/v0.1.31)。Latest 指向 `v0.12.80`，原生 TV 保持非 Latest。
- 15 个附件的远端名称、大小、GitHub 计算的 SHA256 与本地冻结附件逐项一致；所有资产状态均为 uploaded。两个源码 ZIP 各 1203 文件逐字节匹配冻结提交。
- 远端 Android CI：[main / 44040453220](https://github.com/skydeadpeng/hongguo-android-cast/actions/runs/44040453220)、[v0.12.80 / 44040528043](https://github.com/skydeadpeng/hongguo-android-cast/actions/runs/44040528043)、[v0.1.31 / 44040527595](https://github.com/skydeadpeng/hongguo-android-cast/actions/runs/44040527595) 均为 completed/success，head SHA 均与冻结提交一致；三份完整日志与原始日志 ZIP 均保存并重开核对。

- 公开资产门槛：独立 GitHub Linux runner [44042410078](https://github.com/skydeadpeng/hongguo-android-cast/actions/runs/44042410078) 的匿名下载步骤于 15:55:18 +08:00 完成并成功；15 个附件逐个完整下载并比对大小／SHA256，无凭据下载。此核验不复用鉴权草稿下载结果。本机匿名 API 额度耗尽及 TCP 超时记录单独保留，不写为公开下载通过。
- 稳定清单：确认公开资产下载步骤成功后，将 `android/update-phone-0.12.80.json`、`android/update-tv-0.12.80.json`、`android/update-tvnative-0.1.31.json` 原样复制至各自稳定入口；保留切换前0.12.79／0.1.30清单及摘要。对应 APK、版本快照与冻结标签未被重打或覆盖。

- 稳定清单提交：`b526865e12f9cf3fc20aab9b3fcd040678a091ef`，已正常推送 main。切换后本地更新回归35测试全部通过；鉴权 contents API 核验三个 main 稳定文件与冻结快照逐字节相同，独立匿名 runner 的 cache-bust raw 核验于16:00:50 +08:00同轮确认103／103／32及完整摘要。
- 稳定清单提交后的 [Android cast / 44042594975](https://github.com/skydeadpeng/hongguo-android-cast/actions/runs/44042594975) 于 **16:00:09 +08:00** completed/success，16步骤全部成功，完整日志另存。

### 通用 Test 工作流未通过项

Android 专用构建／源码回归与设备验收结果不代表仓库所有工作流通过。冻结提交的 [通用 Test / 44040453160](https://github.com/skydeadpeng/hongguo-android-cast/actions/runs/44040453160) 执行 `npm test` 仍为 exit1（4条命令失败），本次没有将其标为通过：

1. `check-windows-installer.cjs --config` 仍硬编码期待旧桌面仓库，与2026-11-16已改动的 publish 仓库配置不一致。
2. `check-download-quality.cjs` 的旧 fixture 只对 POST 返回模型，而生产代码已使用带 video_id 的签名 GET，导致 MISSING_MODEL。
3. `check-source-recovery.cjs` 同类 HTTP 方法 fixture 漂移，包含将预期403用例误送入空响应路径的情况。
4. `check-download-quality-integration.cjs` 的真实取源用例出现 Web HTTP404，随后 media lookup 地址断言失败，26／27；日志没有给出 App 路由具体失败原因，不推断所有上游均失效。

逐 blob 核对上述4脚本、通用测试入口／工作流、fixture整树、生产 src 整树、main.js 及 package-lock：本轮修复前后11项均完全相同。Android此次修复位于Java原生取源层，并在最终APK独立完成真实播放验收；未为凑通用 Test 绿色而改桌面代码或覆盖冻结资产。详细原始失败、归因与blob对照保留本地 `ci/general-test/VERIFICATION.txt`。
