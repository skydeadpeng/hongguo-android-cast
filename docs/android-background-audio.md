# 手机版后台 / 熄屏音频播放

手机端在原生播放器 `PlayerActivity` 的控制栏新增「听音频」按钮，把当前分集交给前台服务
`AudioPlaybackService`，锁屏或切到后台后继续播放声音（不渲染画面）。

## 用法（自动切换）

1. 在手机上正常播放任意分集；**按 Home / 切到其它 App，或直接按电源键熄屏**，播放会自动切换为音频：
   - 本地视频解码被释放，通知栏出现「红果后台播放」常驻通知，锁屏后继续听声音；
   - 若用户是**主动暂停**后才离开，则保持暂停，不会自动播放音频。
2. **回到本 App**（把播放器切回前台，或点通知栏卡片）：音频服务自动停止，视频从音频停下的同一集、
   同一进度无缝续播（含后台期间用耳机 / 通知切过的集），不会双份声音。
3. 通知栏 / 锁屏 / 耳机线控支持：播放 / 暂停、上一集、下一集、进度拖动、退出。
4. 控制栏仍保留「听音频」手动按钮：点了会关闭播放页、纯后台听；点通知卡片再回到视频。

切换不发生的情形：投屏中、未开始播放、用户已主动暂停、正在缓冲、播放器正在退出。
投屏中「听音频」按钮会提示先停止投屏，避免与 DLNA / 浏览器投屏抢占播放。

## 实现要点

- **只放音频**：服务的 `MediaPlayer` 从不设置 Surface，并在 `onPrepared` 里 `deselectTrack`
  所有视频轨，只解码音频轨，熄屏时不做任何画面渲染。
- **片源复用**：红果片源在播放器里已通过 `PlaybackCache.fetchForeground` 落地为本地 mp4。
  服务优先用播放器移交的已就绪本地文件 URI；切集时复用 `PlaybackSources.resolvePlayback` +
  `PlaybackCache.fetchForeground` 取源，与视频播放器完全同一条链路。
- **保活**：`mediaPlayback` 类型前台服务 + 通知栏；`PARTIAL_WAKE_LOCK` 保证熄屏后 CPU 不休眠；
  Wi-Fi Lock 保证切集取源不被网络休眠打断；音频焦点（8.0+ 用 `AudioFocusRequest`）在来电 /
  其他 App 播放时暂停，短暂失去焦点可恢复或 duck；拔出耳机（`ACTION_AUDIO_BECOMING_NOISY`）自动暂停。
- **控制**：原生 `MediaSession`（无需新增依赖）承载通知栏 MediaStyle、锁屏与蓝牙 / 有线耳机媒体键；
  `PlaybackState` / `MediaMetadata` 同步标题、时长与进度。
- **进度**：服务每 15 秒及暂停 / 切集 / 结束时通过 `LibraryStore.history` 写回观看记录，回到播放器续播。
- **连播**：本集播放结束按「连播」开关自动进入下一集，队列末尾停止。

## 改动文件

| 文件 | 改动 |
| --- | --- |
| `android/app/src/main/java/.../AudioPlaybackService.java` | 新增：前台音频服务、MediaSession、WakeLock、音频焦点、取源与队列 |
| `android/app/src/main/java/.../PlayerActivity.java` | 新增「听音频」按钮与移交逻辑；切后台 / 熄屏自动切音频、回前台自动切回视频 |
| `android/app/src/main/java/.../AudioPlaybackService.java` | 前台音频服务 + MediaSession + WakeLock + 音频焦点；新增播放快照供回前台精确续播 |
| `android/app/src/main/AndroidManifest.xml` | 新增 WAKE_LOCK / FOREGROUND_SERVICE / FOREGROUND_SERVICE_MEDIA_PLAYBACK / POST_NOTIFICATIONS 权限与 service 声明 |

## 权限与兼容

- Android 13（API33）+ 通知需 `POST_NOTIFICATIONS`；未授权时前台服务仍运行，但通知可能不显示。
- Android 14（API34）+ `mediaPlayback` 前台服务需 `FOREGROUND_SERVICE_MEDIA_PLAYBACK`，已声明。
- 仅手机 flavor 暴露入口（`BuildConfig.IS_TV` 为 false）；服务类随主源码编译，但 TV 不会启动它。
- 最低支持随手机 flavor 的 API26。

## 已知边界

- 后台音频依赖片源能被解析为本地文件；少数只提供在线流、无法完整下载到缓存的片源，后台可能
  无法就绪，会提示回到播放器。
- 离线「手动下载」剧集：移交的是 DownloadManager 的 content URI，服务只接受可直接读的本地文件；
  此类剧集走 `PlaybackCache` 中已生成的缓存文件，若未生成则提示回到播放器。
- 熄屏续航仍受系统省电策略影响；部分厂商 ROM 在深度省电下可能限制前台服务，属系统行为。

## 验证状态

- 已通过：Java 括号 / 结构静态检查（剥离字符串与注释）、AndroidManifest XML 解析、项目内外部符号
  （`PlaybackSources` / `PlaybackCache` / `PlaybackPlaylist` / `RegisteredPlaybackLookup` /
  `LibraryStore`）方法签名逐一核对、缓存 key 规则与播放器一致性核对。
- 未执行：gradle 编译与真机验收。本机无 Android SDK 与 JDK17，无法产出 APK；合入正式发行前需按
  `docs/android-release-*.md` 的流程用真实素材在手机上验证锁屏、通知控制、切集、来电与回跳。

本功能为社区修改版新增，未做真机编译与实机验收；以上静态检查不能替代真机验证。
