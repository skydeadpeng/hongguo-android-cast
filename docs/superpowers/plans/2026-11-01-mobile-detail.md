# 移动端选集页改版实施计划

> **For agentic workers:** 当前环境无执行计划子技能，由当前会话逐项实施并验证，不自动提交。

**Goal:** 竖屏首屏直接展示剧集信息和选集，减少重复提示。

**Architecture:** 沿用 Android React 详情页与原生桥接。单季历史不触发分季面板；跨季续看仍保留入口。设置采用原生 details 折叠，不改播放与下载请求。

**Tech Stack:** React 18、CSS、esbuild、Chrome CDP。

---

### 任务 1：回归测试
- [x] 修改 `scripts/check-android-mobile-ui.mjs`，断言单季不显示分季、首屏至少三排集数、无重复取源标签、底部准确续看、设置默认折叠。
- [x] 运行移动端脚本，确认新增测试因重复分季面板失败，原有 16 组通过。

### 任务 2：布局与提示
- [x] 修改 `src/android/App.jsx`，把分季放在摘要后面，同季续看合并到底部；分季仅保留版本信息和必要错误。
- [x] 折叠设置，缩短日常说明，保留影响下载与缓存的必要提示。
- [x] 修改 `src/android/style.css`，紧凑摘要、一行简介、数字选集、轻量范围选择、单行播放栏；兼容窄屏、大字体、横屏和主题。

### 任务 3：验证
- [x] 移动端界面脚本 17 组通过，检查 320/360/390 像素、横竖切换、大字体、真实触摸事件和截图。
- [x] 分季播放回归 13 组通过，32 项逻辑测试通过，Android UI 构建成功。
- [x] 截图采用合成数据，未运行真机、模拟器或真实片源验收。

测试使用本机 Node 22 与独立 Chromium，避免系统 Chrome 的无响应问题。运行示例：

```sh
CHROME_BIN=/Users/xiaomingpeng/Library/Caches/ms-playwright/chromium_headless_shell-1243/chrome-headless-shell-mac-arm64/chrome-headless-shell /Users/xiaomingpeng/.nvm/versions/node/v22.22.2/bin/node scripts/check-android-mobile-ui.mjs
```
