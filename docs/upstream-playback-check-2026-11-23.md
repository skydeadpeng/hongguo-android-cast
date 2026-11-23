# 上游 8.0 / 8.1 播放能力实测（2026-11-23）

日期：2026-11-23 21:00–21:43（Asia/Shanghai）。目的：确认上游 `jackotom/hongguo-drama` 是否更新、最新版本在当前服务端状态下能否播放。本记录只涉及上游桌面版实测，不改变本仓库任何代码或发行物。

## 上游状态（21:20 核对）

- 最新正式发行仍是 `v8.0`（18:34:11 发布，冻结源码 `8d14d07`）。作者在 #181（20:57）确认 8.0 只能播放前几集、后续集偶发只有 360P。
- `8.1` 仅源码冻结：main `b20413b`「release: 8.1 restore full playback and speed downloads」（20:38），`.github/release-8.1-target.json` 状态 `source-frozen`；截至 21:20 无 `v8.1` 标签和 Release。

## 样本

《嫌疑人的秘密2藏金生死线》，SID `7706404415887903806`，官网目录 88 集，`web_accessible_episodes=5`。分集：第 1 集 `7706406239239932952`、第 5 集 `7706406325462256664`、第 10 集 `7706406288325872664`、第 23 集 `7706406223892974616`。全部为红果真实片源，未使用彩条或测试图。

## v8.0（冻结源码 8d14d07，原样加载其模块）

- 官网公开兜底（`fallback-play-info`）：第 1、5 集返回完整 H.264 360×640；第 6、10、23 集 HTTP 403。
- App 入口（`fetchAppPlayUrl`，自动画质）：第 1 集与第 10 集均为匿名设备注册成功后，6 条路由各 2 次全部 HTTP 200 / 0 字节——新版签名 POST `player/v1`（sinfonlineb / sinfonlinea）、POST `video_model/v1/`（sinfonlinea / sinfonlinec）、新版签名 GET `video_tab`、旧签名 POST `multi_video_model/v1/`；最终 `EMPTY_RESPONSE`。
- 上游新版签名器（`src/native/playback-v5.js`）字段 9 常量为 `0x040a0300`（正确），所以它的 GET `video_tab` 为空是服务端真实应答。

结论：v8.0 在当前状态下只能播放官网公开范围内的前几集，且为 360P。

## 8.1 的新增路线：SDK 取源

- 组成：`src/native/sdk-playback.js`（主进程拉起独立执行文件，最多 3 个并发）、`scripts/sdk-playback/resolve_cli.py`、`scripts/sdk-playback/vendor/phone_account/`（第三方源码子集）、`scripts/build-sdk-playback-mac.sh`（python3.11 + PyInstaller onefile + Unicorn 2.1.4 + Requests + PyCryptodome）。执行文件不入库，需本地构建；只有 macOS arm64 / x64。
- 流程：注册匿名设备 → 从官方 APK（74532）按字节范围下载并校验 `libmetasec_ml.so`（4,501,136 字节）与 `sdk-app-config.encoded`（304 字节）→ 在 Unicorn 中运行该镜像计算指纹 → `mssdk.zijieapi.com/sdi/get_token` 换 SDI token → 请求 `novel/player/multi_video_model/player/v1` 取播放模型。只连 `log.snssdk.com`、`lf9-apk.ugapk.cn`（及 `jspcdn.cn` 重定向）、`mssdk.zijieapi.com`、`fqnovel.com`；不读写账号。
- 取源顺序：在线播放 = 官方入口（`video_tab` → 新签名 `player/v1` → 单集模型 → 旧接口）全空后走 SDK，再回官网公开源和旧接口；数字分集下载 = SDK 优先。
- 直接运行构建出的执行文件（21:25）：第 10 集成功，首次 4.97 秒（含素材下载与注册），时长 66.363 秒，5 档 360p / 480p / 540p / 720p / 1080p，均为 HEVC（bytevc1），1080p 为 1080×1920、7,520,159 字节，带解密信息；第 23 集 1.63 秒，时长 64.297 秒，1080p 4,520,562 字节。

## 8.1 真实应用验收

环境：main `b20413b` 源码模式运行，Electron 44.9.1，依赖版本与上游 lockfile 逐项一致（复用本仓库 `node_modules`），渲染层用上游 `npm run build:react` 构建，软件解码资源清单与上游一致。SDK 执行文件由上游脚本从仓库内置的 vendored 子集构建，未从第三方地址下载。

隔离与静音：独立 `--user-data-dir` 并预置隔离下载目录；启动参数 `--mute-audio`，播放偏好置静音 / 音量 0，并锁定 `HTMLMediaElement.muted`。全程经 CDP 读到的播放器状态均为 `muted=true, volume=0`。测试前后核对正式数据目录 `~/Library/Application Support/hongguo-downloader`：0 个文件被修改。

| 操作 | 结果 |
|---|---|
| 21:37:33 首页卡片 →「立即播放」第 1 集 | 约 10 秒起播，1080×1920，时长 151.9 秒；12 秒内进度 15.08→27.35，解码帧 381→688，丢帧 0 |
| 21:38:58 选集点击第 10 集（公开范围外） | 约 20 秒起播，1080×1920，时长 66.36 秒；27 秒内进度 13.61→41.25，丢帧 0 |
| 连播自动切第 11 集（公开范围外） | 无缝接上，1080×1920，时长 106.95 秒；16 秒内进度 44.31→60.59，丢帧 0 |
| 21:41:49 双击第 54、55 集加入下载 | 21:41:56「已下载 1/88」，21:41:59「2/88」；两个文件经 ffprobe 核对 |

下载文件：第 54 集 6,848,020 字节，HEVC 1080×1920、63.76 秒、AAC；第 55 集 6,393,993 字节，HEVC 1080×1920、60.47 秒、AAC；各取前 5 秒解码无错误。

收尾：测试实例退出后无残留进程与临时目录；测试下载、隔离配置、匿名身份、SDK 缓存和含签名地址的原始输出已删除。

## 限制

- 8.1 尚未发布；本轮只验证 macOS arm64（Apple Silicon）从源码运行，单部剧、单一网络、单一时间窗口。
- 上游 x64 只在 Rosetta 下自测；Windows / Linux 本轮不包含 SDK 执行文件，沿用 8.0 的回退，不具备本轮效果。
- 在线切到公开范围外的集，本次单次观察约 20 秒才起新集（官方入口先扫一遍，且与「缓存后续 2 集」共用节流队列）；样本只有一次。
- SDK 路线使用匿名身份换 SDI token，长期是否触发风控未知。
- vendored 源码与官方 SDK 镜像的署名和许可见上游 `THIRD-PARTY-NOTICES.md`（Unicorn GPL-2.0 等）。

## 对本仓库的含义

- 本仓库 0.12.90 梯子的第一档与上游 8.0 的新签名 GET `video_tab` 属同一类请求；本轮上游该请求在第 1、10 集均为空。本仓库 Java 签名器的同形态请求今晚未单独复测，是否同样失效待确认。
- 上游 8.1 证明：官方入口全空时，经 SDK 指纹换 token 的取源路线当晚可用。该路线依赖 Python + Unicorn 执行环境，与手机包体积目标（0.12.87 约 1.6 MiB）存在冲突，移植方案需另行评估。
