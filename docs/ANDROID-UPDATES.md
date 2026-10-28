# Android 应用内更新与发布

## 使用

0.12.11/code34首版包含更新器；0.12.12/code35修复首次开启安装来源权限后返回的续装状态。0.12.10及更早版本先手动覆盖安装一次；0.12.11及后续版本可在关于页检查在线更新。自动检查按24小时节流，播放中不弹提示，稍后不反复提示同一版本。下载和系统安装均需用户确认。

0.12.11首次开启安装来源权限后如停在等待状态，返回关于页重新检查更新，再点继续安装；已下载并验证的包会保留。0.12.12修复该返回分支，重新校验APK并保存记录后进入系统安装器。

0.12.17/code40 的更新器新增固定加速线路，保留 0.12.16 的播放自动取源/低分辨率恢复、0.12.15 的下载删除，以及既有账号、收藏、进度和更新缓存恢复修复。更新通道及兼容证书不变，历史 Release 资产不替换。0.12.16/code39 及更早更新器不会使用新线路；如果旧版直连下载失败，先手动覆盖安装加速版一次，随后由新版更新器自动切换线路。

手机包`io.github.jackotom.hongguo.android`、TV包`io.github.jackotom.hongguo.android.tv`、原生TV包`io.github.jackotom.hongguo.android.tvnative`分开，不能互换。原生TV是独立版本线（0.1.x），清单只与自身版本比较，不会提示安装手机/TV 网页版。更新保留现有安装证书；更换证书会破坏覆盖更新链，不通过卸载清数据处理。

原生 TV 0.1.2 / code3：滚动设置页提供默认开启的「自动检查更新」开关、最近成功检查时间和手动检查。启动、返回前台以及前台周期调度统一判断到期；成功检查间隔 24 小时，失败 15 分钟后自动重试，关闭自动后仍可手动检查。选择「稍后」不会反复提示相同版本，但到期仍会查询更高版本。发现新版或下载就绪时，主导航旁显示更新入口；播放期间不弹提示，返回主界面再处理。下载、验证和系统安装保留分别确认，不进行静默安装。

## 固定渠道

- 手机：https://raw.githubusercontent.com/skydeadpeng/hongguo-android-cast/main/android/update-phone.json
- 电视：https://raw.githubusercontent.com/skydeadpeng/hongguo-android-cast/main/android/update-tv.json
- 原生电视：https://raw.githubusercontent.com/skydeadpeng/hongguo-android-cast/main/android/update-tvnative.json

只接受自有仓库不可变`releases/download/v<version>/Hongguo-<version>-<phone|tv>-release-compatible.apk`或`-release.apk`；原生电视为`HongguoNative-<version>-tvNative-release-compatible.apk`或`-release.apk`。不读取aston项目或桌面上游清单，不允许任意用户输入更新地址。

## 本地构建与生成清单

```sh
bash scripts/build-android.sh --phone-release-compatible
bash scripts/build-android.sh --tv-release-compatible
bash scripts/build-android.sh --tvnative-release-compatible
node scripts/generate-android-update.mjs --apk dist/android/Hongguo-0.12.17-phone-release-compatible.apk --device phone --output dist/android/update-phone.json
node scripts/generate-android-update.mjs --apk dist/android/Hongguo-0.12.17-tv-release-compatible.apk --device tv --output dist/android/update-tv.json
node scripts/generate-android-update.mjs --apk dist/android/HongguoNative-0.1.2-tvNative-release-compatible.apk --device tvnative --output dist/android/update-tvnative.json
```

版本随实际发布调整，生成器从已签名APK读取字段，拒绝错包、debug包、错签名和非规范文件名，重新打开生成清单确认SHA256。使用SDK Build Tools 35.0.0的aapt与apksigner。不包含上传动作。

清单字段：schemaVersion=1、channel=android-compatible、flavor、packageName、versionCode整数、versionName、minSdk、apkUrl、size、sha256，可选notes。清单自报信息不能替代实际APK签名和包信息校验。

## 发布次序

1. 先完成回归、手机/TV签名构建并核验与旧安装证书一致。
2. 从最终APK生成清单和校验和，保管现有私钥，不提交私钥或凭据。
3. 上传不可变版本Release资产；同一versionCode不替换不同内容。
4. 确认资产下载及散列正确后，最后更新`android/update-phone.json`、`update-tv.json`、`update-tvnative.json`稳定清单。稳定清单不应先于可用 APK 发布。原生 TV 清单必须使用本次最终签名 APK 的真实 size/SHA256；不发布占位散列。原生 TV Release 保持 `--latest=false`，不覆盖手机/TV WebView 版的 Latest 标记。
5. 真实手机及TV分别从旧版走应用内安装，检查未知来源权限返回、取消/重试及数据保留；ADB覆盖安装不替代这条业务验收。

## 安装与数据

更新下载进入私有updates目录，不进入播放缓存。下载取消/错误清理半成品；完整文件安装前重新校验准确size/SHA、真实包名/versionCode/versionName/SDK/签名；只读content URI交给系统安装器。打开安装器并不等于安装成功，以新版本重启后的实际版本为准。

安装前有业务互斥和同步落盘checkpoint。活跃播放器、投屏/合并服务、手动下载/准备任务存在时先结束业务再重试；记录写盘失败不启动安装。失败、取消或权限拒绝不清账号历史、下载、设置或视频缓存。


## 固定加速下载线路（0.12.17）

此方案参考上次下载的 aston Android 对应源码 `UpdatePolicy.sources()`（本地参考提交 `8e6769b1a09847b529ad796a78f23ae9dbfb4387`），只复用固定线路策略，不替换本应用的仓库、版本、包名、签名或安装逻辑。

APK 依次尝试以下 HTTPS 前缀拼接原始 GitHub Release URL：

1. `https://gh-proxy.com/`
2. `https://v4.gh-proxy.org/`
3. `https://gh-proxy.org/`
4. `https://ghfast.top/`
5. 原始 GitHub 地址

- 公开稳定清单及保存的 `apkUrl` 始终保持本仓库的精确不可变 GitHub 地址；加速 URL 是本机更新器派生的传输线路，不把清单改成代理 URL，也不接受任意用户输入更新地址。更新清单的规范入口仍是固定 raw GitHub URL，传输也依次使用这四条加速线路，失败后回退原入口；每次检查附带本机生成的缓存戳，清单解析、包型和版本校验在单条线路内完成。
- 每次换线重新下载并清零进度，不拼接其它来源的半成品；准确大小、SHA256、真实 APK 包名/版本/SDK 和当前完整签名集合全部通过后才保留为可安装包。错误 HTML、截断或篡改内容不会交给安装器。
- 代理只改变公开清单与 APK 的传输路径，不接触账号、播放片源、视频缓存或系统代理设置；界面显示当前线路与切换状态，不显示 CDN 签名参数。取消、退出、旧请求或已过期任务不得继续换线或重新取得安装资格。
- 保留 HTTPS 和受限跳转，只能访问当前已选加速服务或既有 GitHub 资产主机；不照搬参考项目任意 HTTPS 跳转的开放边界。每条线路及整个下载操作均有有限期限，失败后仍可手动重试。
- 加速服务的可用性和速度依赖设备网络。旧版客户端没有这段路由逻辑，因此仅修改远端清单不能为旧版补上代理；首次需正常覆盖安装带新更新器的 APK，保留数据与安装证书。

- 若某线路返回与已安装版本相同的合法清单，继续检查其它线路是否有新版本；陈旧版本、同码异名、错包型、坏 JSON 或超限内容不会据此误报“已是最新”。清单跳转仅限本机派生的当前加速地址及固定 raw 路径与同一缓存戳，不借用 APK 资产域名的较宽 CDN 白名单。
