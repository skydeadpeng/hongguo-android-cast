# Android 0.12.91 / 0.1.41 发行记录（2026-11-24，SDK 播放取源路线）

冻结源码：`09c7247`（SDK 取源路线）+ bump 提交 `f556f3f`（0.12.91/code114，tvNative 0.1.41/code42）。四个安装包均在 `f556f3f` 的干净工作树中构建，工作区无任何未提交改动。

## 背景

2026-11-23 晚，服务端对本机签名的全部请求形态（含 0.12.90 的新版签名取址）均返回空 200，三端公开集之外的剧集无法取源。上游 8.1（源码冻结、未发版）验证了当晚仍可用的路线：匿名设备登记后，经官方 SDK 镜像的一个函数取得摘要、换取会话令牌，再用带该令牌的请求取源。上游的实现依赖 Python + Unicorn，无法进入手机包。

## 内容（`09c7247`）

- SDK 取源路线移植为原生实现：协议部分（令牌交换封包、播放请求布局、v5 信封与摘要组帧）以 Java 重写，摘要核心由 `android/app/src/main/cpp` 的本地适配层在应用进程内调用官方 SDK 镜像中的一个函数完成。
- 镜像不随包分发：运行时从官方安装包的两个字节分段取得 SDK 镜像与配置（双重 SHA-256 固定），断流可续传、校验通过后缓存；安装包仅增大约 58–64 KB。
- 取源顺序：arm64 设备上 SDK 路线优先，任何情况下（设备不支持、尚未就绪、被拒或空应答）都回落到原有的签名请求梯子，梯子本身未改。
- 适配库只编 arm64：由 CMake 按 ABI 决定是否产出，32 位 tvNative 包保留 libVLC、行为与之前一致。
- 故障隔离：非摘要线程的故障原样交还给进程原有的处置、不代为重抛；镜像内一旦出现故障或超时，本进程内停用该路线。
- 第三方署名补入 `THIRD-PARTY-NOTICES.md` 与 `src/native/licenses/NOTICE.txt`；构建说明补 NDK / CMake 前提。

## 验证

- 协议移植：以上游未经修改的 Python 源码为黄金向量（素材全部合成），23 组向量 77 项断言逐字节一致；适配层在 arm64 模拟器上与同一预言机的 24 组摘要向量逐字节一致（两种内存载体，最慢 3.8 ms）。向量生成脚本 `android/tests/generate-sdk-playback-vectors.py` 已入库，需上游 `phone_account` 子集与 pycryptodome；本次核对它重新生成的文件与仓库内向量逐字节一致。
- 故障处理（真实信号）：Android 14 arm64 模拟器 `SDK_FAULT_FORWARD_OK checks=14 raised=918979 sent=831`（调用期间其他线程约 92 万次故障逐个被原有处理器吸收、被发送的信号逐个转交一次、摘要线程自身故障被收敛）；无自有处理器的进程按预期死于 SIGSEGV。macOS 宿主机 `checks=9`（该平台不区分信号来源，转交一例明确跳过）。复现：`bash android/tests/run-sdk-fault-forward-test.sh [--android]`。
- 素材下载：脚本化 CDN 下的离线测试 39 项（断流续传、截止时间、跳转、错误分段、损坏、缓存）全过。测试过程中查出并修正两处缺陷：缓存命中判断用错长度（每次冷启动都会重新下载）、单次请求 20 秒超时封顶（慢网络永远下不完）。
- 离线回归：`check:android`、`check:android:playback` 通过；登记模型 31 组 / 321 项、分派 35 项、取消所有权检查全过；`npm test` 失败清单与改动前基线逐条相同（4 条存量失败）。
- 模拟器实测（电视 AVD，Android 14 / arm64，全程媒体音量 0，素材在红果短剧内随机选取并真实下载）：
  - 正式包 `f556f3f`：手机 0.12.91 与 TV WebView 0.12.91 清空数据后播放《嫌疑人的秘密2藏金生死线》第 10 集（公开范围外），取源→会话→模型 HTTP 200，系统解码器 HEVC 1080×1920，预取第 11 集，旧梯子零请求；tvNative 0.1.41 同签名覆盖升级 0.1.40→0.1.41，清空数据后随机抽到《离婚当天，婆爱为我撑腰》第 58 集，同链路播放并预取第 59 集。
  - 开发包：tvNative 随机抽到同剧第 15 集，自动连播至第 16 集并预取第 17 集；切换「软解优先」后 libVLC 解码状态下连播 17→18→19，第 19 集的取源在 libVLC 已加载的进程内完成。
- 首次运行需下载约 5.4 MB 素材：网络顺畅时 1 秒内就绪，模拟器资源饥饿时约 50 秒；再次启动命中缓存约 1 秒。

## 资产

四个资产发布后已匿名下载回读，SHA256 与冻结产物一致。

| 资产 | SHA256 | 字节 |
|---|---|---|
| [phone 0.12.91/code114](https://github.com/skydeadpeng/hongguo-android-cast/releases/download/v0.12.91/Hongguo-0.12.91-phone-release-compatible.apk) | `a9d8f1cfdf8ceefbeab29aeb758f9d202ea941dde696b7116377ea044508111d` | 1762551 |
| [tv 0.12.91/code114](https://github.com/skydeadpeng/hongguo-android-cast/releases/download/v0.12.91/Hongguo-0.12.91-tv-release-compatible.apk) | `e55a722e735a9410e866d8bdf0157dbd19841149d707cf9e2eca5c02dc579fa4` | 1762363 |
| [tvNative arm64 0.1.41/code42](https://github.com/skydeadpeng/hongguo-android-cast/releases/download/v0.1.41/HongguoNative-0.1.41-tvNative-release-compatible-arm64-v8a.apk) | `d017dbbc5ad1c09526a162bd378051d9987b5072738fb97a48fec69cf32406f8` | 47101876 |
| [tvNative armeabi-v7a 0.1.41/code42](https://github.com/skydeadpeng/hongguo-android-cast/releases/download/v0.1.41/HongguoNative-0.1.41-tvNative-release-compatible-armeabi-v7a.apk) | `fc116258404d4e00806bf98c26acd462ef7aab9aaa99b320769ca8abba7404ec` | 43341326 |

## 随本次修正的清单问题

tvNative 更新清单 `android/update-tvnative.json` 中各架构条目的键在 0.1.39、0.1.40 两次发布里写成了 `url`，而客户端（`AppUpdater` 的 `Candidate.parse`）读取的是 `apkUrl`，这两版的 tvNative 应用内检查更新会解析失败。本次清单由生成脚本产出，键名为 `apkUrl`，与 0.1.37 / 0.1.38 一致。

## 边界与限制

- 仅 arm64 设备有 SDK 取源路线。32 位设备、x86 模拟器的取源行为与之前完全相同：在旧梯子被清空的服务端状态下，公开集之外的剧集仍可能无法取源。
- 仅在模拟器上验证，未在实体设备上试播。tvNative 的 Android 6.0–9.0 内存载体路径未在真实系统上跑过；映射失败时路线自动停用、回落旧梯子。
- 长时间运行与平台风控未知：会话 30 分钟更换，连续 3 次失败后本进程内停用该路线；平台接口或 SDK 变动可能使路线失效，届时行为等同于改动前。
- 来源许可：移植所依据的 `phone_account` 子集，其在上游所述源码归档内的许可原文未单独核验（上游以 GPL-3.0 分发，本项目沿用 GPL-3.0），已在署名中如实写明。
- 回归中的既有偶发：`RegisteredPlaybackModelTest` 的断言「v5 ticket search stays within the signed second」依赖签名时刻落在秒内的位置，基线代码同样存在越界时刻（用测试的确切请求测算，600,000 个签名时刻中 1,893 个违反，改动前后数字相同）。本轮出现过两次，重跑均通过；该断言本身不是签名器保证的性质，未改动，是否放宽由维护者决定。
- 模拟器资源饥饿时 tvNative 冷启动会出现 ANR，经与改动前基线同条件对照确认为环境表现（安静环境下无 ANR），与本次改动无关。
- CI 工作流仍引用多源拆除时已删除的脚本，本轮只补了 NDK / CMake 安装步骤，未运行。
- 桌面版与 webOS 版本轮未改动。更新检查有 24 小时冷却，可到「关于 → 检查更新」手动触发。
