# 番茄公开网页视频入口核对

日期：2026-11-11。只读：官网 HTML/JS、搜索索引、官方 APK 的 DEX 字符串。未运行 APK、登录、注册设备、调用私有取流接口、处理验证码或尝试获取/解密受保护媒体。

## 可确认结论

- 官方网页根入口是 https://fanqienovel.com/ 。页面直接链接官方下载站 https://www.changdunovel.com/ 。公开首页主导航及内容集中于小说、作家、排行榜、版权展示。搜索索引也返回这些内容，未找到可验证的番茄漫剧/短剧网页播放页。
- 首页“热门版权改编动漫、影视尽在番茄小说”的“查看全部”点击目的地为 `/writer/zone/copyright`。不能将这句话或版权目录误认成视频播放入口。
- 首页 Home bundle 中确实有 HTML `video` 元素，但它由 `bannerList[].video_uri` 驱动，为静音循环宣传横幅。它不是连续剧播放器，不能据此宣称官网直接提供完整剧集。
- 针对未证实的候选路径 `https://fanqienovel.com/manju` 和 `https://fanqienovel.com/video`，普通无登录 GET 均返回 HTTP 404。不要在产品中将这两个猜测地址作为已验证可用入口。
- 官网主 JS bundle 存在通用模型的 `video_model`、`use_video_model`、`episode_cnt` 字段。这是共享生成模型的静态证据；本次没有发现对应 `/video`、`/drama` 或 `manju` 播放路由。不能从模型字段推断网页端开放了原始媒体。
- 全部 `classes*.dex` 中的 URL 字面量按 manju/short_video/video_share/drama/video_player/video-play 过滤，结果仅有奖励接口、评论引导和 UI 图片。没有找到直接的公开漫剧/短剧网页入口。该方法不覆盖运行期服务端下发链接、拼接链接或非字面量配置。

## 对接建议与限制

本次未验证任何“未登录即可取得完整且未加密剧集流”的番茄官方网页能力。此结论是“没有发现和验收”，不是“证明所有番茄网站都不存在此功能”。不得把首页宣传视频当剧集测试素材，不要拿 404 候选路径做 WebView 播放方案，也不要用共享 video_model 字段替代真实播放验收。

用户已明确目标为利用番茄实现修复现有红果播放，不新增番茄入口。官方根页或下载页不能替代该目标；必须另在正常授权流程中验证实际内容、全时长、播放器兼容和权限，本次网页复核没有做这些操作。

## 可复核来源和本地证据

- 官方主页：https://fanqienovel.com/ ，已存在抓取 `fanqie.html`。
- 官方首页脚本：https://lf-fe.fqnovelstatic.com/obj/novel-fanqie-fe/toutiao/muye/Home.608a201c.js ，本轮保存 `fanqie-web-home.js`。
- 官方主脚本：https://lf-fe.fqnovelstatic.com/obj/novel-fanqie-fe/toutiao/muye/js/muye_f9eb17e2.js ，本轮保存 `fanqie-web-bundle.js`。
- 普通 GET 结果：`fanqie-web-fetches.json`，包含两条候选路径 404 和官方主脚本 HTTP 200（3906556 字节）。
- DEX 字面量过滤：`fanqie-web-static-urls.txt`，样本 `fanqie-official.apk`（7.4.3.32 / com.dragon.read，来源及完整性见本目录 `SYSTEMATIC_REPORT.md`）。
- 公开搜索用 `site:fanqienovel.com/manju` 和 `site:fanqienovel.com "视频" "短剧"`；结果仅作为找入口线索，未采信第三方下载器或影视聚合站。

上述完整抓取文件保存在本地分析输出目录，不纳入源码仓库；本报告不作为播放成功凭证。
