# Android 原生宿主

项目提供三种可独立安装的 flavor：

- `phone`：手机启动入口，包名 `io.github.jackotom.hongguo.android`。
- `tv`：横屏、Leanback 启动入口、遥控器导航，包名后缀 `.tv`，可与手机版共存。
- `tvNative`：无 WebView 的独立原生 TV 版（面向系统 WebView 过旧的设备），包名后缀 `.tvnative`、独立版本线 0.1.x（当前 0.1.2 / code3）、原生界面 + Java 取源，与上两者共存。本机数据与下载不跨包自动迁移；支持自己的自动更新渠道。详见根目录 README 的「原生 TV 独立版」与 `ANDROID.md`。

Android 8.0 / API 26 起；编译 / target API 35；Java 17；AGP 8.7.2。无第三方 Android 运行库。正式发布前请配置自己的 release 签名；debug APK 仅使用本机 Android debug keystore。

## 构建

在仓库根目录使用 `scripts/build-android.sh`。前端由 Vite 生成到 `app/src/main/assets/web/`；该目录及 Gradle 构建结果不提交。独立原生编译可运行：

```sh
cd android
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" gradle :app:assemblePhoneDebug :app:assembleTvDebug
```

Android SDK 路径使用本机 `local.properties` 或 `ANDROID_HOME`。构建出的 APK 位于 `app/build/outputs/apk/phone/debug/` 与 `app/build/outputs/apk/tv/debug/`。

## 本地桥

WebView 仅从 `https://appassets.androidplatform.net/index.html` 读取打包资源，不依赖 Mac 服务。页面发送：

```js
window.AndroidBridge.postMessage(JSON.stringify({ id: 'req-1', method: 'getInfo', params: {} }));
window.__androidResponse = ({ id, result, error }) => { /* 与请求 ID 对应 */ };
```

方法：`getInfo`、`httpGet`、`getLibrary`、`toggleFavorite`、`play`、`download`、`getDownloads`、`playDownload`、`getPlaybackCache`、`clearPlaybackCache`、`openExternal`。每条请求上限 256 KiB（UTF-8）；网络在线程池执行；队列有界。页面刷新时通过 `window.__androidResume()` 刷新本地状态；Android 返回先询问 `window.__androidBack()`，未处理时显示退出确认。

- `httpGet` 仅抓取官网 `category/search/player` 页面文本；不执行来源脚本。
- 文本请求使用 HTTPS、标准证书和主机名验证，将已检查的 DNS 公网地址直接绑定 TLS socket；最多 3 次跳转且逐跳重新检查；正文最多 8 MiB。
- 播放 / 下载开始前执行 HTTPS HEAD 检查，逐跳检查公网地址；实际媒体传输由 Android VideoView / DownloadManager 接管。
- 收藏和最近 100 部观看进度保存在本机私有 SharedPreferences；两个 flavor 分别保存。
- 下载交给系统 DownloadManager，文件落在应用专属外部 Downloads 目录，记录最多 200 条；卸载应用会移除应用数据及专属文件。
- 下载管理每集提供“删除”；未完成任务显示“取消并删除”，二次确认后取消任务并移除该集离线文件及本机下载记录，不影响收藏、观看进度或播放缓存。播放、投屏或媒体准备期间先结束任务再重试；损坏或外部已移除的下载也可清理记录。
- 离线播放仅接受应用已记录且系统下载状态成功的 content URI。m3u8 播放列表仅在线播放，不作为完整视频文件下载。
- `play` 传完整简明 `episodes` 队列（最多 1000 集）和 `autoplay`、`cleanupPlaybackCache`，不把网页预览集数当成禁止后续播放的条件；下一集由原生解析器逐集核验。
- TV：方向键移动焦点、确定选择；视频区左右快退 / 快进 10 秒，上下进入按钮区；按钮区确定执行对应操作；始终显示返回按钮，系统返回键直接退出播放。
- 独立 `cache/playback-v2` 只写临时 MP4，当前文件引用保护，默认同剧保留前 1 集 / 当前集 / 预取下一集，总预算 256 MiB、单文件 96 MiB，其他剧按容量 LRU 淘汰；m3u8 直接流播放。缓存失败不打断在线播放。
- 手动下载和播放缓存分开；清缓存不触及 DownloadManager 文件或记录。离线连播只枚举同剧已成功且可读取的手动下载。

## 离线验证

```sh
./android/tests/run-network-guard-tests.sh
./android/tests/run-episode-resolver-tests.sh
./android/tests/run-playback-cache-tests.sh
./android/tests/run-library-store-queue-tests.sh
ADB_PORT=5038 HONGGUO_DEVICE=emulator-5582 ./android/tests/run-android-runtime-tests.sh
npm run check:android:playback
```

测试运行实际 NetworkGuard Java 代码的 URI、官方路径、私网 / 保留 IP 拒绝分支，不发网络请求。APK 安装、在线来源和设备解码需另外运行验证。

`run-android-runtime-tests.sh` 使用已运行的独立 QA 模拟器，在真实 Android libcore / org.json 上通过 app_process 运行离线解析器、网络守卫、缓存和队列测试；仅写入并清理自己的临时 JAR，不启动或重启前台应用。需要 SDK build-tools、JDK 17。

## 0.12.16 播放失败自动恢复

- 初次取源、媒体准备或播放中断遇到可恢复错误时，同一集先自动重新取源一次；仍失败后按实际视频尺寸尝试更低档（720p / 480p / 360p），不是回到可能再次选中高分辨率的“自动”档。
- 自动降档带真实短边上限，尺寸未知或没有符合范围的片源不会假报成功；失败阶段有次数上限，耗尽后保留重试、选集和返回入口。
- 自动恢复保留本集位置、暂停状态和已确认倍速，不改用户首选清晰度；手动下载和离线文件不自动降级、不删除文件、不转在线播放。
- 主播放器准备等待及持续缓冲有有界超时；成功、切集、切清晰度、暂停、投屏和退出会清理旧定时任务，旧回调不得覆盖新一集。
