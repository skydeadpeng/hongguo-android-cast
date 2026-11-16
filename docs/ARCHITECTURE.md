# 架构对照表（2026-11）

本仓库是多端短剧播放应用的单一源仓库。本文回答"哪个入口、哪套 UI、哪个更新源"——这是历史上最容易漂移的三件事（2026-11 评审发现入口与打包配置脱节、更新源三处分裂，均已修复并有 CI 守卫）。

## 平台 × 入口 × UI × 更新源

| 线 | 入口 / 宿主 | 渲染 UI | 构建产物 | 更新机制 | 发布仓库 |
|---|---|---|---|---|---|
| 桌面（现役） | Electron `main-android.js` | `src/android/` React → `dist-react-android/` | win nsis/portable、mac dmg/zip、linux AppImage | 应用内检查 + `electron-updater`（`src/native/app-update-installer.js`） | `skydeadpeng/hongguo-android-cast` Releases（`latest.yml`/`latest-mac.yml`） |
| 桌面（冻结参考） | Electron `main.js` | `src/` React → `dist-react/` | 不再打包 | 不再使用 | 源码留档，`build.files` 已不含 `main.js` |
| Android 手机/TV（WebView） | `MainActivity` + `NativeBridge` | 同一份 `src/android/` React，esbuild 适配进 APK assets | `Hongguo-*-release[-compatible][-abi].apk` | `AppUpdater` + `UpdatePolicy` 校验，清单 `android/update-{phone,tv}.json` | `skydeadpeng/hongguo-android-cast` Releases |
| Android TV 原生 | `TvNativeActivity`（tvNative flavor，libVLC 软解） | 原生 View | `HongguoNative-*-tvNative-release[-compatible][-abi].apk` | 同上，清单 `android/update-tvnative.json`（minSdk 23 + ABI 资产名） | 同上 |
| webOS | webOS TV（零 npm 依赖） | `webos/js/` vanilla JS | ipk（`webos/build.sh`） | 手动侧载 | ipk 附件 |

发布仓库统一为 **`skydeadpeng/hongguo-android-cast`**：桌面用 `latest.yml`/`latest-mac.yml`（electron-updater），安卓线用 `android/update-*.json` 清单。`jackotom/hongguo-drama` 是上游仓库，自 2026-11 起由上游发布 7.x，**不是**本 fork 的更新源（历史遗留的指向已全部改掉，`scripts/check-electron-entry.cjs` 守卫）。

## 共享层

- `src/native/`（约 40 模块）：取源/签名/解密/下载/NAS 的唯一参考实现，主进程运行；60 个 `*.test.cjs` 单测。桌面壳经 `android-bridge.js` 复用安卓同构语义；`app-source-registered.js` 是安卓登记身份签名链，与桌面 `app-source.js` 是两条协议分支，勿混用。
- `src/android/`：安卓 React UI，一份源两处消费——桌面壳（`vite.desktop-android.config.mjs` → `dist-react-android/`）与 APK assets（`vite.android.config.mjs` + esbuild 适配器）。共享面刻意极小（仅 icons.jsx）。
- `src/`：旧桌面 UI（冻结参考），`main.js` 5554 行的过程式主进程随之冻结；新功能进 `src/native/` 模块 + 安卓壳。

## 构建链

- 桌面：`npm run build:mac|build|build:linux`（先 `build:react:shell` 产出 dist-react-android，再 electron-builder；`build.files` 只含现入口四件套 + `src/native/**` + `src/store.js`）。
- 安卓：`npm run build:android:*`（`scripts/build-android.sh`，phone/tv/tvNative flavor）。
- 开发：`npm run dev`（dev.js 起 `vite.desktop-android.config` dev server，`main-android.js` 读 `VITE_DEV_SERVER_URL` 走 HMR；缺 startup-bootstrap.js 时自动补生成）。
- 守卫：`npm test` 聚合 src/native 单测 + `check-*.cjs` 检查（含 `check-electron-entry.cjs`）；安卓线另有 `check:android` 与独立 harness（`android/tests/`，`UpdatePolicyTest` 用 javac+argfile 直跑）。

## 已知边界

- webOS 全量正片依赖 `webos/relay/server.cjs` 本地中转，实机验收未完成。
- Android 黄果源仅在线播放（无下载/收藏），是有意边界。
- 面向最终用户的下载目录名与各端限制见 AGENTS.md 与 `docs/release-*.md`。
