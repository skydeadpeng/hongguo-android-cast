# Android 0.12.88 / 原生 TV 0.1.39 发行记录（2026-11-22 晚，取源身份自动恢复）

冻结源码：`ac4e1af`（播放身份手动重置）+ `bump` 提交（0.12.88/code111、0.1.39/code40）。

## 背景

0.12.87 发布当天用户手机出现持续播放失败。机制定位：上游按匿名取源身份分级风控——重度观看的设备收到的拒绝形态是 **code≠0 业务拒绝（新分类 BUSINESS_DENIED）**，而非空响应；0.12.83 的轮换只对「双空」触发，因此被降级的身份一直卡住不换（模拟器新身份正常，恰好验证了这一点）。

## 内容

1. **业务拒绝自动轮换**：前台取源收到 BUSINESS_DENIED 时自动注册全新匿名身份重试一轮（同 tab→POST 顺序）。防反烧节流：沿用 30 秒前台配额，新增进程级「静默窗口内最多 3 次」allowance（10 分钟无拒绝自动恢复额度，任何成功取源立即清零）；试看标记（BUSINESS_REJECTED）与格式/HTTP 类失败维持终态；预取永不轮换。真·试看集与内容级拒绝不会被误换身份。
2. **手动兜底**：关于页新增「重置播放身份」卡片（0.12.88 与自动轮换同轮发布）：清空共享与持久化身份（registered-identity.json），下次播放注册全新身份；观看记录、收藏、下载不受影响；文案提示勿短时间反复重置。

## 验证

- 注册播放模型套件 28 组 296 检查全过（含新增用例：降级拒绝在 30 秒配额内轮换一次后终态、近过期预算不消耗轮换）。
- npm test 失败集与改动前一致；真网端到端探针（check-download-quality-integration）的 1 条失败在未打补丁的干净树上完全重现——为上游对 Node 探针持久身份的限流波动，与本轮改动无关（该探针身份同样「看多了」，正好旁证限流机制）。
- 模拟器（phone36）：手动重置点击后 toast 提示、registered-identity.json 删除、下一次播放 `register ok device_id=…（全新）→ (shared) → video_tab 200 非空` → 第 1 集起播与续播第 26 集均正常推进；正常路径轮换零干扰。
- phone/tv/tvNative release-compatible 构建各过 9 项 release 检查。

## 正式下载

- phone 0.12.88/code111：[APK](https://github.com/skydeadpeng/hongguo-android-cast/releases/download/v0.12.88/Hongguo-0.12.88-phone-release-compatible.apk)。SHA256 `0473ebf90273e06701957cbdd02a7f08e5543745e29312419c6d4c19b74e0ab3`，1,702,814 字节。
- tv 0.12.88/code111：[APK](https://github.com/skydeadpeng/hongguo-android-cast/releases/download/v0.12.88/Hongguo-0.12.88-tv-release-compatible.apk)。SHA256 `c426a16ad0868d485f1a8a3cc86041fe3689a0da7148a9eb049b589a6bfd6d5a`，1,701,258 字节。
- tvnative 0.1.39/code40：[APK（arm64-v8a）](https://github.com/skydeadpeng/hongguo-android-cast/releases/download/v0.1.39/HongguoNative-0.1.39-tvNative-release-compatible-arm64-v8a.apk) SHA256 `9a8c079efc66b8366e2c55d3da07e4b234b4428816ac0e4958c2b146fdb16a26` 47,035,967 字节；[32 位 APK](https://github.com/skydeadpeng/hongguo-android-cast/releases/download/v0.1.39/HongguoNative-0.1.39-tvNative-release-compatible-armeabi-v7a.apk) SHA256 `9d2a2efb213dd0f907fef63bb55bae2cfa24d40ab8eb54d8d590e4df6fc0cf57` 43,308,364 字节。

## 边界

- 自动轮换每进程静默窗口内最多 3 次；用尽后转终态提示，手动重置入口不受限。
- 真网探针的上游限流波动（探针自身身份被降级）与本轮改动无关，未在本轮处理。
- 模拟器验证以构建冒烟与取源链路为主；实体设备试播未执行（沿用发行惯例，用户实机反馈为准）。
