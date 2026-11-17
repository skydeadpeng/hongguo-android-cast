# 安卓红果播放修复记录 — 2026-11-17

> 后续发行：2026-11-17 联合发行目标为手机／TV WebView 0.12.80/code103、tvNative 0.1.31/code32，最终正式包验证与发布回执见 [发行记录](android-release-2026-11-17.md)。发行准备中已修正下文历史 Contract391 的域名判断反转；旧测试结果仍保留。

> 修复阶段冻结状态：已定位并修正 Java v5 签名缺陷、跨任务取消缺陷及真实原生路径的恢复缺口；JVM 真网已恢复 1080P 模型取源。冻结 code103 手机修复 APK 已在独立 API32 模拟器通过真实视频首帧、连续30秒及切集验收；TV 修复 APK 的截图采集受阻，未完成同等验收。实体手机／TV尚未测试。本文替代初始诊断中的“尚未修改源码”状态，不删除历史取证。

## 1. 确定的问题与修复

### 1.1 v5 签名把 Java 有符号 byte 当作无符号八位字节

文件：`/Users/xiaomingpeng/Desktop/红果/android/app/src/main/java/io/github/jackotom/hongguo/android/PlaybackSignerV5.java`。

- `getIv` 原先直接使用 `data[i]`。高位为 1 的字节会在 Java 转 int 时符号扩展，与上游 Kotlin `Byte.u()`、JS Buffer 的无符号语义不同，进而选错签名分支和 `_rticket`。
- 修复将参与 `getIv` 运算的字节统一为 `data[i] & 255`；上游要求的 class12 与独立 Kotlin 选择的 ticket 均恢复一致。
- `sumMd5` 同样补齐无符号转换，修复 F13 后四字节校验的一致性。**该校验差异不是已经独立证明的空响应原因**：隔离实验中，仅保留旧校验、使用正确 query 的请求仍得到有效模型。真网直接支持的故障点是 `getIv` 导致的分支／ticket 选择差异。
- 原测试只比对 16 字节摘要及宽松校验位，旧实现也能通过 326 次检查。现在使用独立上游 Kotlin 生成的完整 20 字节与 32 个选定 ticket；原载体与身份绑定断言保留。
- JS 生产签名无需同步修改：独立 Kotlin 对照的 32 个完整向量中，JS 均一致。

### 1.2 预取结束会取消前台模型请求

文件：`/Users/xiaomingpeng/Desktop/红果/android/app/src/main/java/io/github/jackotom/hongguo/android/RegisteredPlaybackModel.java`。

- `ModelHttpTransport.ACTIVE_CALLS` 原为静态全局集合；任意 fetch 的 finally 关闭 transport 时会取消其他实例的 Call。前台 resolver 与后续分集预取使用不同执行器，真实路径存在并发重叠。
- 运行 Call 改由 transport 实例持有；关闭与登记共用同一把锁，关闭只取消本实例请求，重复关闭幂等。
- 成功、执行失败、读流失败都移除登记；关闭后的登记不执行，关闭后的迟到成功不发布。共享静态 OkHttp client 与 h2 连接池保留。
- 实际生产类离线复现：基线关闭预取后 `foregroundCancelCount=1 / prefetchCancelCount=1`；修后为 `0 / 1`。10 组 128 项检查通过，未发网络请求。

### 1.3 JS 恢复没有覆盖手机／TV WebView 的实际取源链

文件：`/Users/xiaomingpeng/Desktop/红果/android/app/src/main/java/io/github/jackotom/hongguo/android/PlaybackSources.java`。

- `PlayerActivity` 首播、切集、恢复与预取显式传入 `RegisteredPlaybackLookup::resolve`，因此走 Java，不经过浏览器 `window.__androidAppResolve`。仅修改共享 JS 中的 1/3/8 秒恢复不能修正此路径。
- 显式原生播放边界现在以同一 20 秒总预算处理 `APP_EMPTY_RESPONSE`：初次请求后最多再取三次，间隔为 1000／2500／5000ms；每次传入剩余预算。普通 Auto 与固定 1080P 均保留本次请求的质量、分集与 ceiling。
- 明确业务拒绝、无效响应、编码／质量不符不因本恢复获得重复请求资格。已有一次传输错误恢复及 DNS 负缓存等待保留。
- `fresh`、播放器外层单次恢复及捕获 plan 的媒体刷新保持单次取源；不会把四次初始尝试变为四乘四。普通手动下载仍走原有默认 lookup 策略。
- 延迟可中断；调用前后及异常返回后都检查取消与截止，过期成功不发布。本次未改变播放器进度、暂停意图或 generation 状态机。

## 2. JVM 真网证据

### 2.1 上游 Kotlin、原 Java 与修后 Java 对照

输入为同一内存匿名身份、相同原始 query 字段、gzip 正文和输入时间戳；剧集 `7706004220599618584`、分集 `7706006636074126361`。完整原签名因分支选择而生成不同最终 query；修后最终 query、正文和时间戳与 Kotlin 对照一致。未读取正式账号。

| 路线 | 实际响应 |
|---|---|
| 独立上游 Kotlin 对照 | HTTP200，24302 字节，业务 code0，5 档 |
| 原 Java 完整签名 | HTTP200，0 字节；未命中上游 class12 |
| 修后 Java 完整签名 | HTTP200，24406 字节，业务 code0，5 档 |
| 修后模型的真实媒体头 | HTTP206，读取65536字节，存在 MP4 ftyp，ByteVC1／720P，存在解密参数 |

该实验只读取媒体头，不代表完成视频下载或设备出画。

离线独立 oracle：修前 32/32 个完整 F13 不同、28/32 个 ticket 不同；修后完整 F13、ticket、class12 均 0 差异。

证据：
- `/Users/xiaomingpeng/Desktop/红果/verification/playback-repair-2026-11-17/protocol/VERIFICATION.txt`
- `/Users/xiaomingpeng/Desktop/红果/verification/playback-repair-2026-11-17/protocol/REPAIR-LIVE-RESULTS.txt`
- `/Users/xiaomingpeng/Desktop/红果/verification/playback-repair-2026-11-17/protocol/CHECKSUM-ISOLATION-RESULTS.txt`

### 2.2 原样生产 lookup 的 1080P 取源

新 JVM 调用实际 `RegisteredPlaybackLookup.resolve`，使用生产默认匿名注册、20秒预算、固定1080P、ceiling0，不注入媒体地址：

```text
INPUT sid=7706890951175719961 vid=7706892777824455704 quality=1080p ceiling=0 exactProductionLookup=true independentAnonymousIdentity=true identitySource=productionDefault identityPersisted=false accountUsed=false
RESULT success=true source=app codec= quality=1080p width=1080 height=1920 urlPresent=true keyPresent=true variantCount=1 elapsedMs=578
EXIT_STATUS=0
```

这里 `codec=` 是生产 JSON 投影的空字段，不据此声称已验证设备解码。原始记录见 `/Users/xiaomingpeng/Desktop/红果/verification/playback-repair-2026-11-17/protocol/FULL-LOOKUP-VERIFICATION.txt`。

## 3. 回归与构建

统一原生入口：

```sh
cd '/Users/xiaomingpeng/Desktop/红果'
npm run check:android:playback-native
node --test '/Users/xiaomingpeng/Desktop/红果/scripts/check-android-mac-source.test.mjs' '/Users/xiaomingpeng/Desktop/红果/scripts/check-android-registered-model.test.mjs'
node '/Users/xiaomingpeng/Desktop/红果/scripts/check-android-signer-offsets.cjs'
```

已保留的结果：
- 取消所有权：10组／128检查通过。
- 原生空模型恢复：最新扩展为11组／63检查通过，包含普通Auto与固定1080P路由。
- 注册模型：17组／230检查；PlayerSigner 47检查；修后 v5 326检查与独立完整向量通过；JS投影与RPC派发回归通过。
- 浏览器／共享模型组合：53项通过。测试修复让嵌套551／v5 signer也使用同一VM Date／RNG；实际551取源检查Argus／Ladon，独立真实源码bundle保留32种seed的Medusa／Gorgon字节、分支0/2及签名唯一性检查。未改变JS生产逻辑。
- Android源码／导航：52项通过。初始播放为8组／98检查，1080P恢复为9组／104检查，播放器选项为130检查，自动降档为23检查；逐项结果保存在 `native-recovery` 目录。
- **完整原生合同仍有既有失败**：`bash android/tests/run-native-media-contract-tests.sh` 退出1，失败为 `PlaybackSourcesContractTest.java:391: expired App plan failed open`。用原始 `PlaybackSources` 副本也复现相同失败，属于既有的 no-plan download-domain 分支，本次未修。分离执行的 `--lookup-only`（6组／34检查及7组／18检查）与 `--recovery-only`（9组／50检查）均退出0；不将这些局部通过表述为完整原生合同全绿。精确命令与输出见 `/Users/xiaomingpeng/Desktop/红果/verification/playback-repair-2026-11-17/native-recovery/VERIFICATION.txt`。
- CI最前编译从手写SDK-only javac／伪BuildConfig改为真实Gradle `:app:compilePhoneDebugJavaWithJavac`，使OkHttp等依赖按项目图解析；两个新增回归显式加入CI。Kotlin优选2.0.20，缺失时从缓存选运行jar并排除sources／javadoc／metadata；1.8.22-only缓存实测通过。
- phone／TV debug及测试APK最终构建成功：`final-apk-build.log` 为 `BUILD SUCCESSFUL in 14s`。这是构建结果，不替代播放验收。

修后有效日志包括：
- `/Users/xiaomingpeng/Desktop/红果/verification/playback-repair-2026-11-17/protocol/MODIFIED-REGISTERED-MODEL-TESTS.txt`
- `/Users/xiaomingpeng/Desktop/红果/verification/playback-repair-2026-11-17/native-recovery/MODIFIED.log`
- `/Users/xiaomingpeng/Desktop/红果/verification/playback-repair-2026-11-17/signer-fixture/modified.txt`
- `/Users/xiaomingpeng/Desktop/红果/verification/playback-repair-2026-11-17/final-apk-build.log`

`android-web-regressions.log`、`android-web-protocol-regressions.log` 保留修前测试装配失败，不作为修后通过日志。`test-entrypoints/native-suite.txt` 是新增入口首次通过记录，当时原生恢复为10组／53检查；最新11组／63检查以 `native-recovery/MODIFIED.log` 为准。

### Debug 系统证书配置

仅debug source set移除对本地 `@raw/mitm_ca` 的额外信任，保留系统CA和 `cleartextTrafficPermitted=false`，使干净检出不依赖本地诊断证书。release主manifest未加该debug覆盖、未放宽HTTPS验证。相关两个文件须随修复一起保留：
- `/Users/xiaomingpeng/Desktop/红果/android/app/src/debug/AndroidManifest.xml`
- `/Users/xiaomingpeng/Desktop/红果/android/app/src/debug/res/xml/network_security_config.xml`

## 4. 真实素材与最终设备结果

素材由本轮从官网公开目录24项中随机选择：《我的婆婆我罩着2：一致对外》，剧集 `7706890951175719961`，第1集 `7706892777824455704`，第2集 `7706892785571351576`；选择记录为北京时间2026-11-17 14:21:10。全程独立测试资料、独立播放缓存，开始前静音且检查媒体流音量0／muted；未使用彩条或合成视频。

最终设备是独立API32 `google_apis arm64` 手机模拟器、host GPU；TV APK同样运行在这台手机模拟器上，不是实体电视验收。早期API36模拟器先后出现wlan0 DOWN、软件渲染不稳定及instrumentation未进入onStart，相关历史记录保留。最终重建干净API32 AVD后，2026-11-17 15:10:54至15:11:54四轮检查均为网络UP／0%丢包；网络检查本身不替代视频验收。网络记录见 `/Users/xiaomingpeng/Desktop/红果/verification/playback-repair-2026-11-17/emulator-reset/final-network-stability.txt`。

### 4.1 冻结 code103 手机包：通过

最终报告为 `/Users/xiaomingpeng/Desktop/红果/verification/playback-repair-2026-11-17/final-phone-device/report.json`，状态 `passed`，总耗时52996ms。测试使用生产播放器组件及生产intent builder，不注入URL或play_info，由播放器按固定1080P自行取源；不覆盖发现页／详情页的完整交互路径。

| 验收项 | 最终实际结果 |
|---|---|
| 播放前静音 | 媒体流音量0，`music_stream_muted=true` |
| 第一集真实首帧 | 启动后6691ms，1080×1920，时长143767ms，`native_app_1080_plan=true`、`full_download=true` |
| 真实画面 | PixelCopy读取实际视频Surface成功；首帧、30秒后、切集后三图已人工查看 |
| 连续播放 | 30080ms观察期内进度推进30082ms，31次Surface采样、30次画面变化，无终止弹窗 |
| 生产“下一集”按钮 | 第2集generation2，时长132400ms，1080×1920；观察约10秒，进度推进8419ms且画面变化 |

三张图位于同一 `final-phone-device` 目录：`first-frame.png`、`after-30s.png`、`after-switch.png`。此结果绑定下方手机主APK摘要，不借用早期code102的单次通过记录。

### 4.2 冻结 code103 TV包：截图采集受阻，未完成验收

最终报告为 `/Users/xiaomingpeng/Desktop/红果/verification/playback-repair-2026-11-17/final-tv-device/report.json`，状态 `blocked`，原因为 `app_screenshot_unavailable`，总耗时47786ms。主界面和播放器启动通过；测试已执行1080P来源、时长／尺寸与PixelCopy真实帧校验，随后UiAutomation截图返回null，首帧阶段未形成完整证据，30秒连续播放与切集阶段未执行完成。该阻点不是已证实的签名／取源失败，也不写成TV通过。实体手机、实体TV及独立TV原生版本轮均未验收。

### 4.3 保留的历史尝试

历史结果必须分开解释：
1. 重置后旧code102修复构建曾完整通过一次：1080×1920真实视频Surface首帧7487ms，完整下载，30秒播放推进30121ms、画面变化30次；切第2集后推进10152ms，静音检查通过。报告为 `/Users/xiaomingpeng/Desktop/红果/verification/playback-repair-2026-11-17/modified-phone-device/first-reset-run/report.json`。
2. 同一手机后续复测在首帧之后失败：`renderer_unchanged_or_unavailable_5s`；TV一次首帧之后出现 `episode_identity_changed`，再试为 `activity_launch_timeout`。这些失败没有被第一次成功覆盖。
3. API36切换host GPU后仍出现instrumentation挂在pid0、未进入onStart，保存在 `/Users/xiaomingpeng/Desktop/红果/verification/playback-repair-2026-11-17/api36-host-startup-attempt`。这次没有进入取源阶段，不归因为上游失败。最终验收改用上述API32环境；历史失败不被删除或改写为通过。

冻结测试包为 `0.12.79-repair.20261117 / versionCode103`，版本覆盖来自独立QA Gradle init脚本；主项目常规版本元数据未以此冒充正式发行。包位于：
- `/Users/xiaomingpeng/Desktop/红果/verification/playback-repair-2026-11-17/apk/hongguo-phone-0.12.79-repair.20261117.apk`
- `/Users/xiaomingpeng/Desktop/红果/verification/playback-repair-2026-11-17/apk/hongguo-tv-0.12.79-repair.20261117.apk`

摘要清单：`/Users/xiaomingpeng/Desktop/红果/verification/playback-repair-2026-11-17/apk/SHA256SUMS.txt`。本轮未发布GitHub正式版本，未替换本机正式安装。

主APK SHA256：
- phone：`559a320d3e0767289f0ccb99ed34f70dc8f33bb8dff7b7db20cb960ea6626ffd`
- TV：`27f6295a7f5e5c8b0af7e68f0b0781760f5684bd81b2f17e895090d486b22778`

为支持干净API32环境，独立AndroidTest的SDK guard由36改为32后重建，主APK未变；最新测试APK摘要以该清单为准。报告 `qa_commit=9ebfbff…` 表示修复工作区的源码基线，不表示这些修改已形成干净冻结提交；最终设备结果绑定主APK摘要与本轮源码／diff证据。

## 5. 证据与回滚

根证据目录：`/Users/xiaomingpeng/Desktop/红果/verification/playback-repair-2026-11-17`。

- `protocol`：签名修复快照／diff／验证／回滚脚本，以及独立Kotlin和受限真网对照。
- `cancel-ownership`：实例取消隔离四件产物与基线／修后／独立副本回滚。
- `native-recovery`：原生恢复源码快照、基线与修后测试、相关旧回归与runner修补记录。
- `test-entrypoints`：npm／CI与依赖选择四文件快照包、diff、验证、独立副本回滚。
- `signer-fixture`：测试装配修正四件产物、53项组合通过及独立副本回滚。

回滚验证均只作用于另外的证据副本；生产源码和MODIFIED快照保留修复。`protocol/SOURCE_MANIFEST.json` 明确记录的是基线9ebfbff，并非最终源码清单。账号凭据、匿名身份、解密密钥、带签名媒体地址均不写入本修复文档。
