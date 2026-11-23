# Android 0.12.90 / 0.1.40 发行记录（2026-11-23，播放取源修复）

冻结源码：`53735e4`（播放修复）+ bump 提交 `3327c4d`（0.12.90/code113，tvNative 0.1.40/code41）。

## 背景

2026-11-23 服务端取源接口形态再切换：仅应答新版签名的按集取址（video_tab GET）请求，既有两种请求形态全部返回空 200，三端在线播放中断。排查同时定位一处与本次切换无关的存量缺陷：JS 签名链协议常量沿用旧版取值，导致桌面 / WebView 的 JS 取源请求从未被服务端接受过（此前 WebView 走原生取源不受影响，桌面主链路一直依赖它）。

## 内容（`53735e4`）

- 原生取源梯子改为「新版签名按集取址 → 新签名模型请求 → 旧签名按集取址」，整条梯子全部返回空才更换取源身份（此前两种形态即轮换，白白消耗注册额度）。
- JS 签名链协议常量修正（medusa 字段 9：67503104 → 0x040a0300），并附 Java 侧生成的黄金向量回归（4 个载波字全覆盖）。
- 共享 JS 取源梯子与桌面 `appRegisteredModel` 同步插入新版签名取址档位。
- 同步更新 4 个受额外档位影响的既有检查；新增 15 个离线用例与向量文件。

## 验证

- 离线回归：原生模型套件 31 组 / 321 项全过；新增 JS 用例 15 项全过；`npm test` 失败集与改动前基线逐行一致（4 条存量失败）；CI 两个 WebView 测试文件 53/53；编译本类的 18 个 JVM 测试脚本通过与基线一致（10 过 / 9 个存量失败，两树相同）。
- 真网对照（同一新身份、同一集）：旧两形态空 200，新版签名取址 22KB / 5 档；Java 签名交 OkHttp 发送、JS 签名同客户端发送逐头替换定位到 JS medusa 字段 9；时间差以 17ms 内发送与 1.5s 延迟发送双向排除。
- 模拟器（phone36，静音，真实剧《家犬之谜》）：旧 0.12.89 同态下 `video_tab`/`player/v1` 连续空 200 并轮换身份；新包一次 `video_tab/v5 http=200 bytes=3617` 起播，用户确认可播放。
- 桌面：Electron 44.9.1 真实 `appRegisteredModel` 链路一次注册 + 一次新版取址，1056ms 取得 5 档。
- 构建：phone / tv release-compatible 各过 9 项发行检查；tvNative 双架构构建通过。

## 资产

| 资产 | SHA256 | 字节 |
|---|---|---|
| [phone 0.12.90/code113](https://github.com/skydeadpeng/hongguo-android-cast/releases/download/v0.12.90/Hongguo-0.12.90-phone-release-compatible.apk) | `4229da8d5067af6d112180e8b0af6ac000c6eadddc9b9a6ec77e1f39b6e823d0` | 1703482 |
| [tv 0.12.90/code113](https://github.com/skydeadpeng/hongguo-android-cast/releases/download/v0.12.90/Hongguo-0.12.90-tv-release-compatible.apk) | `eeda9ddfad3c88c3f316f02ea13688f56c8cb1a71baed591c9ed862bc7676549` | 1701982 |
| [tvNative arm64 0.1.40/code41](https://github.com/skydeadpeng/hongguo-android-cast/releases/download/v0.1.40/HongguoNative-0.1.40-tvNative-release-compatible-arm64-v8a.apk) | `0c55334f71a8974da2820ca9bd02cc0f9a6bc401f6c917299663782794c5676a` | 47036118 |
| [tvNative armeabi-v7a 0.1.40/code41](https://github.com/skydeadpeng/hongguo-android-cast/releases/download/v0.1.40/HongguoNative-0.1.40-tvNative-release-compatible-armeabi-v7a.apk) | `32c77431ff592a3999e6f80838128eedff9a6903c7fc5225814884757b31067d` | 43308523 |

## 边界与限制

- 本地构建取本机 SHA256；GitHub 侧上传后未做匿名下载回读对账（上一轮做法），如需可后补。
- TV WebView 与 tvNative 的模拟器装机播放验收本轮未跑（phone 模拟器已验）；tvNative 0.1.40 包含同一 Java 修复，风险集中在 WebView JS 链（tvNative 不用 JS 链）。
- webOS 客户端为独立 v3 协议链路，本轮不覆盖且已随 11-16 服务端强制新版签名失效，另行处理。
- 上游（jackotom）桌面版本轮无对应更新；桌面修复基于本仓库独立排查完成。
