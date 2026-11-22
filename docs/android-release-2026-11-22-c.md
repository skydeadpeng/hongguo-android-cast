# Android 0.12.89 发行记录（2026-11-22 深夜，深色沉浸界面改版）

冻结源码：`b37cb5e`（深色沉浸 UI 改版）+ bump 提交（0.12.89/code112）。tvNative 不受本轮影响，保持 0.1.39。

## 内容

WebView 界面锁定深色沉浸短剧风（对标主流短剧 App），覆盖发现 / 详情 / 搜索 / 追剧 / 下载 / 关于全部页面：

- 品牌红渐变顶栏与状态栏融合（MainActivity 状态栏/导航栏着色 + 白色系统图标；insets controller 在 setContentView 之后取——decor 之前为 null，已修）。
- 底部导航深色毛玻璃浮层 + 选中胶囊；海报卡片投影 + 按压缩放 + 渐变集数角标；选集深色圆角网格 + 红色选中；播放栏深色浮层 + 渐变主按钮；toast 毛玻璃。
- 加载态改为海报骨架屏 shimmer（无文字）；关于页三段长说明删除、卡片化分组；主题以源顺序覆盖层锁定深色（不跟随系统）。

边界：原生播放器全屏页与 tvNative 原生界面不动；桌面 Electron 共用此 WebView 壳同步变深色。

## 验证

- phone/tv release-compatible 构建各过 9 项 release 检查；0.12.89 phone 包模拟器覆盖安装冷启动无崩溃。
- 检查 playback 56 断言全过；npm test 失败集与基线一致（真网探针 2 条为上游限流波动，未改树重现）。
- 逐页视觉核验经 WebView 渲染管线截图（发现 / 详情 / 关于）；模拟器 adb screencap 为冻结 GPU 帧，不代表真实渲染（历史已知）。
- 资产核验：phone 包匿名下载 SHA256 一致（`1fb47e4f…`，1,703,114 字节）；tv 包 `3ec54ea6…`（1,701,466 字节）。

## 正式下载

- phone 0.12.89/code112：[APK](https://github.com/skydeadpeng/hongguo-android-cast/releases/download/v0.12.89/Hongguo-0.12.89-phone-release-compatible.apk)。
- tv 0.12.89/code112：[APK](https://github.com/skydeadpeng/hongguo-android-cast/releases/download/v0.12.89/Hongguo-0.12.89-tv-release-compatible.apk)。
- tvnative：保持 0.1.39（本轮无改动）。
