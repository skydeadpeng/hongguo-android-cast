# Android 0.12.83 / 0.12.84 / 原生 TV 0.1.34 / 0.1.35 发行记录（2026-11-21）

## 0.12.84 / 0.1.35（画质排序修复，同日追加）

冻结源码：`e35b6cb`。

0.12.83 上线后用户反馈下载大量落 360p。根因：video_tab 模型把各清晰度档挂在同一编码名下（每档单编码），而旧选档排序以编码为主——bounded 按像素升序、auto 按 H.264 优先——把选择钉在最低的 H.264 档。修复：bounded 与 auto 改为短边最高优先、同档 H.264 优先（Java `AppMediaSource.rank` 与 JS `app-source-registered.js` 同步修改，android mac source bundle 构建时重新生成，不入库）；固定画质非边界路径保持按满足画质的最接近档选择。

验证：AppMediaSource 31 检查（含「单编码低档不得压过高档」回归用例）、JS mac-source 52、entitlement 240、source budget 186、播放原生恢复 78 检查全部通过；TV 模拟器实测连播至第 18 集，控制面板显示实际分辨率 1080×1920（修复前同链路 720p 及以下）。

正式下载：
- phone 0.12.84/code107：[APK](https://github.com/skydeadpeng/hongguo-android-cast/releases/download/v0.12.84/Hongguo-0.12.84-phone-release-compatible.apk)。
- tv 0.12.84/code107：[APK](https://github.com/skydeadpeng/hongguo-android-cast/releases/download/v0.12.84/Hongguo-0.12.84-tv-release-compatible.apk)。
- tvnative 0.1.35/code36：[APK（arm64-v8a）](https://github.com/skydeadpeng/hongguo-android-cast/releases/download/v0.1.35/HongguoNative-0.1.35-tvNative-release-compatible-arm64-v8a.apk)、[32 位 APK](https://github.com/skydeadpeng/hongguo-android-cast/releases/download/v0.1.35/HongguoNative-0.1.35-tvNative-release-compatible-armeabi-v7a.apk)。

## 0.12.83 / 0.1.34（连播取源自愈）

冻结源码：`6c0fc4a`（本轮迁移修复 + 版本 bump 的合并提交）。

本轮把 hongguoTV（kotlin-tv）2026-11-20 起的播放形态迁移进 Java 取源线：匿名 POST（含 v1 与 :dr_scene 变体）已普遍返回空体，而 GET video_tab 仍可下发模型，因此前台取源改为 video_tab GET 主路径、签名 POST 仅作回退；两路均空时注册全新匿名身份重试一轮（30 秒静态冷却、每次取源单次额度、预取永不轮换），修复 0.12.82 连播数集后「App片源登记或取源未完成」的失败。

## 正式下载

- phone 0.12.83/code106：[APK](https://github.com/skydeadpeng/hongguo-android-cast/releases/download/v0.12.83/Hongguo-0.12.83-phone-release-compatible.apk)。
- tv 0.12.83/code106：[APK](https://github.com/skydeadpeng/hongguo-android-cast/releases/download/v0.12.83/Hongguo-0.12.83-tv-release-compatible.apk)。
- tvnative 0.1.34/code35：[APK（arm64-v8a）](https://github.com/skydeadpeng/hongguo-android-cast/releases/download/v0.1.34/HongguoNative-0.1.34-tvNative-release-compatible-arm64-v8a.apk)、[32 位 APK](https://github.com/skydeadpeng/hongguo-android-cast/releases/download/v0.1.34/HongguoNative-0.1.34-tvNative-release-compatible-armeabi-v7a.apk)。

## 验证

- 注册模型回归 27 组 294 检查全部通过（覆盖 tab-first 顺序、轮换额度、双空轮换、Retry-After 终止语义）；原生恢复 14 组 78 检查、注册模型 node 投影测试通过。
- tvDebug 实测：tv34_dpad（Android TV 14）模拟器自第 6 集起播，连播越过第 7 集（0.12.82 的必然失败点）并持续推进至第 13 集，全程无「App片源登记或取源未完成」弹窗。
- release 兼容包（tv，code106）覆盖安装后冒烟：首页 feed 正常，第 13 集起播成功。
- 11 个公开附件 SHA-256 摘要已列入 `SHA256SUMS.txt`，`SOURCE_COMMIT.txt` 指向冻结提交。

## 限制

- 实体手机、实体 TV 及应用内 OTA 完整流程本轮未验收。
- 30 秒冷却内的连续失败交给上层既有重试；若服务端进一步掏空 video_tab，需跟进 hongguoTV 上游或 relay 路线。
- gradle.properties 新增本机 JDK 21 工具链固定（Gradle 8.9 不支持 JDK 25），仅影响本机构建环境。
- 旧版发行与历史标签不替换。
