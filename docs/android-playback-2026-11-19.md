# Android 播放更新：0.12.81 / 原生 TV 0.1.32

日期：2026-11-19（Asia/Shanghai）。本轮为本地已构建候选包，尚未发布 GitHub Release，尚未切换三个稳定更新清单；既有公开 0.12.80 / 0.1.31 不变。

## 上游差异与本地实现

对照 [N3urda/hongguoTV 0.16.10 / 478f006](https://github.com/N3urda/hongguoTV/commit/478f00683f086f1e5d135b3bcbb443e3cbc8b252)。本地此前已有 v5 三头签名和 `device_level=1`，此次没有替换签名算法，也没有将 Media3 的晚帧丢弃／Android 5 音频缓冲配置硬套到 VideoView/libVLC。

- `RegisteredPlaybackModel.fetch`：前台 v5 POST 返回空白正文且没有 `Retry-After` 时，同一匿名登记身份、同一分集和同一总截止时间内追加一次 `video_tab` GET。健康模型、HTTP 拒绝、业务拒绝、格式异常、取消和到期均不触发这个兼容请求。
- 新增 `TabPlaybackSigner`：恢复 GET 使用上游旧 Medusa branch-0 协议，不使用 dormant 551 `PlayerSigner`。仅复用 `AccountSigner` 的纯算法原语，不复用账号请求或账号会话；已有账号向量不变。
- POST 与 GET 共用 did/iid/cdid。POST 不带 Cookie；GET 只接受登记路由 Cookie 白名单，不接入登录账号 Cookie。成功后完整保留既有身份四字段，继续跨启动复用，不新增身份轮换。
- 空响应带 `Retry-After` 时返回 `APP_SERVICE_BACKOFF`，原生与桥接层不自动重试或降档。取消和迟到结果检查继续有效。
- `PlayerActivity` 后台下一集预取显式使用 speculative lookup：不发兼容 GET、不进入前台空响应重试梯子。首播、手动切集、恢复及前台 CDN 刷新仍使用前台策略。
- 当前分集必须精确匹配。试看标记、媒体加密、尺寸、质量、地址、完整文件等既有校验保持不变。黄果链路不变。

## 安装包

以下四包采用原安装证书的非 debuggable `release-compatible` 构建，原包名、最低系统和ABI边界不变。手机／TV WebView `0.12.81 / code104`，tvNative `0.1.32 / code33`。

| 客户端 | 本地产物 |
|---|---|
| 手机 | `dist/android/Hongguo-0.12.81-phone-release-compatible.apk` |
| TV WebView | `dist/android/Hongguo-0.12.81-tv-release-compatible.apk` |
| 原生 TV 64位 | `dist/android/HongguoNative-0.1.32-tvNative-release-compatible-arm64-v8a.apk` |
| 原生 TV 32位 | `dist/android/HongguoNative-0.1.32-tvNative-release-compatible-armeabi-v7a.apk` |

证书 SHA256：`3a883cc41a4ce34b462a8c12c0d289e6c985d3e2c0d6ecf2b516b5cc75e353ab`。四包的版本、包名、证书、非调试及原生ABI均逐包核验；SHA256 和核对记录位于 `verification/android-playback-20261119/APK-SHA256SUMS.txt`、`APK-IDENTITY.json`。

## 功能回归

- 新行为离线基线：原 `RegisteredPlaybackModel` 对 POST 空正文仅发1次请求，`FALLBACK_MISSING code=APP_EMPTY_RESPONSE modelRequests=1`，exit1；修改后同输入发 POST + GET，`FALLBACK_OK modelRequests=2 methods=POST,GET exactEpisode=true`，exit0。
- 注册模型23组285项、v5签名326项、GET签名196项（32组独立上游Kotlin完整URL及全部签名头摘要零差异）、账号签名16项、原生恢复13组67项、JS桥16组162项、取消所有权及完整NativeMediaContracts均通过。新GET与账号向量已加入Android CI。
- 正确入口 `node scripts/check-android-source-candidates.mjs` 仍有旧fixture及host编译依赖漂移：新旧生产源码均相同15项失败，未为凑通过而改写旧断言。直接调用其底层shell缺少 `--input` 的首次误调用exit2另行保留。对照见 `regressions/full/SOURCE-CANDIDATES-COMPARISON.json`，不把此项标为通过。

## 真实播放验收

本轮使用全新隔离 API32 arm64 AVD，host `-no-audio`，媒体音量0且静音。随机从红果真实目录选取《女主不追主角，却倒追我反派》，SID `7704683516834892825`，第1集 `7704685770241477657`、第2集 `7704685836628921369`。使用最终生产APK、真实取源和完整缓存下载，不注入媒体地址或播放成功回调，不使用彩条。

三线首次执行均通过，均为 App 来源1080×1920，首集130403ms；本轮实际下载缓存首集7587169字节、第二集9681178字节。

| 客户端 | 覆盖版本 | 30秒阶段墙钟 / 进度增量 | Surface变帧 | 第2集进度增量 |
|---|---|---|---:|---:|
| 手机 | 0.12.80→0.12.81 | 30241ms / 30251ms | 30 | 10280ms |
| TV WebView | 0.12.80→0.12.81 | 30188ms / 30234ms | 29 | 10462ms |
| 原生 TV arm64 | 0.1.31→0.1.32 | 30094ms / 30088ms | 29 | 10225ms |

本轮白名单网络记录只观察到有效 v5 POST，未实际触发兼容 GET；GET 的恢复及失败边界由真实生产类的离线故障注入验证，不将离线结果冒充真实服务的兜底命中。

手机首次首帧约35.5秒（含生产取源和完整缓存下载），不将此次结果描述为秒开。每线执行旧正式包→候选包覆盖、私有文件／偏好哨兵保留、实际首帧、30秒Surface变帧／进度、真实切第2集及10秒进度。结果见本轮 `verification/android-playback-20261119/device/DEVICE-VERIFICATION.txt` 和各线白名单JSON。模拟器不替代实体手机／电视或32位设备验收；ADB覆盖安装不替代完整应用内OTA流程。本轮不删除正式应用的账号、下载、观看记录或设置，不自动公开发布测试素材。

## 源码与重跑

```sh
export PATH="$HOME/.nvm/versions/node/v22.22.2/bin:$PATH"
bash android/tests/run-tab-playback-signer-tests.sh
bash android/tests/run-registered-playback-model-tests.sh
bash android/tests/run-playback-native-recovery-tests.sh
node scripts/check-android-registered-model.test.mjs
npm run check:android
npm run build:android:ui
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' gradle -p android --offline --no-daemon assemblePhoneRelease assembleTvRelease assembleTvNativeRelease -PhongguoReleaseSigning=compatible
```

修改前备份、散列、完整diff及可执行恢复脚本保存在本轮verification目录；恢复验证仅在副本执行，实际工作区保持新版。未更改本轮开始前已有的桌面来源工作。
