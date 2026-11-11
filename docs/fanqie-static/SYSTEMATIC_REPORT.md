# 番茄小说 7.4.3.32 系统静态调查

日期：2026-11-11。最终目标：借番茄视频实现核对、修复现有红果播放，不新增番茄来源。产品改动与播放验收由实施任务负责，本调查没有修改产品。

## 结论与修复价值

1. 官方客户端存在两套视频协议，不可混用。Series SDK 使用 `/novel/player/` 的 POST、嵌套 `biz_param`；主 App 旧 `/reading/bookapi/video_tab/` 使用 GET、平铺参数。方法注解、序列化类和实际调用边均已核对。主 App 旧 GET 在本次找到的调用者中服务视频荐书、Trailer 冷启动及部分多详情；不是已证明可以替代完整短剧接口的捷径。
2. 当前短剧调用层 `vb5.s0`（classes5.dex）确实调用 Series SDK 单集与多集接口，按配置选普通及带 `dr_scene` 的新变体；不能仅凭包内字符串认定新变体始终生效。
3. 两族 `video_model` 的字段类型均为 String。SDK `SaaSVideoModelData` 还在模型字符串外层携带 `is_trial_video`（boolean）、`expire_time`（long）、`main_url`/`backup_url`（String）等。投影跨桥时不能只保留内层视频列表而忽略外层试看状态。
4. 官方 `VideoInfo.extractFields(JSONObject)` 确实读取 `encrypt_info.encrypt`：先 `optJSONObject("encrypt_info")`，再 `optBoolean("encrypt")` 写入 `mEncrypt`。这是保留明确 `encrypt=false` 的直接证据。项目基线 `RegisteredPlaybackModel.project()` 只保留 encrypt_info.spade_a，会丢失 JS 已依赖的明确明文标记。应保留上游明确值，不能照搬官方 optBoolean 的缺省 false 当作任何缺字段都是明文。
5. JS 先建立 12 秒期限、原生登记模型可花 20 秒、provider 错误被吞的预算问题是项目自身可复现缺陷。官方协议对比不构成更换域名、放宽保护或延长重试的依据。
6. 官网公开网页未验证到完整未登录明文剧集流。`fanqienovel.com/manju` 与 `/video` 普通 GET 返回404，主页 video 是宣传横幅；不得作为已实现的播放来源。
7. 静态分析不能证明服务器是否强制登录/设备登记/签名，也不能证明某条 main_url 当下可播。包内授权字段及通用网络/安全中间件表明请求和播放可能携带授权上下文，但仅有字段、客户端可缺省或没有显式登录判断都不能反推无认证要求。本轮没有发私有 API 请求验证此事。

## 来源和真实性校验

官网 `https://fanqienovel.com/` 直接链接 `https://www.changdunovel.com/`；下载站脚本中的 Android 按钮指向 `https://fqnovel.ugurl.cn/ncX3`，该公开入口重定向到官方 APK CDN。完整链在 apk-source.json。

- 分析文件：fanqie-official.apk（不纳入源码仓库）；144772557 字节。
- SHA-256：daaf7463fbcaa10ac1db93d16ef873c97eb386397e42f8a0af1085c1ced0cb7e。
- 包：com.dragon.read；versionCode 74332；versionName 7.4.3.32。
- minSdk 21；targetSdk/compileSdk 35；本次分发样本 ABI armeabi-v7a。
- apksigner verify --verbose --print-certs：退出0，v1/v2/v3校验通过；未附 SourceStamp，不把签名自校验当成厂商证书清单的独立背书。
- 证书 SHA-256：1a5a89132e82ed5b9b2aa05cbf138b6a00096326cf90a1c17480aa0af1f0a163。
- 小米商店交叉核对包与版本：https://m.app.mi.com/details?id=com.dragon.read 。

## 覆盖范围

全包 ZIP/DEX 结构索引：23354 条 ZIP entry，解压总大小334921304字节；25个DEX，327262个类定义，方法引用表条目累计1576043（跨DEX重复，不是唯一方法数）；所有DEX SHA-1及Adler32头校验。119个.so条目中118个识别为ELF头，1个libhost.so以非标准KOM标记开头，未解包或执行；531个assets，243个JSON、11个JS。

完整 Manifest 解析得到774个activity、1个activity-alias、200个service、54个receiver、46个provider、224条uses-permission。数量含第三方SDK，不等于主应用实际会使用所有权限。

视频相关名称索引：12714个类，214条视频/搜索相关接口字符串，1016个模型候选；83个相关assets、420个相关res路径。关键词覆盖会有误报/漏报，不等于语义上的全部视频代码。

深入核对：classes9/16/17的RPC与请求/响应/枚举，classes4/5/6/10/11/16的视频调用边，classes14的播放器JSON提取；官方网页和静态前端的公开视频入口。对119个原生库仅做文件/ELF/媒体库存在性清单，没有逐函数反汇编。没有审阅32万类每条指令，没有恢复被混淆或运行期下载的全部代码，也没有审计第三方SDK全部业务。故这是全包索引加视频链路重点分析，不是“所有逻辑均已完整还原”。

## 视频入口与调用链证据

Manifest中确认 ShortSeriesActivity、ShortSeriesDetailActivity、VideoAlbumDetailActivity、ShortSeriesLandActivity、ShortSeriesListActivity；外部scheme有dragon1967，host有reading.snssdk.com、zlink.fqnovel.com、fqnovel.ugurl.cn。仅证明路由注册，未触发deep link。

静态链路的已验证片段：
- `vb5.s0.d(GetVideoDetailRequest, boolean)` → `seriessdk...rpc.d$a.getVideoDetailRxJava`。
- `vb5.s0.g(GetVideoModelRequest)` → 同接口 `getVideoModelRxJava`。
- `vb5.s0.e(MGetVideoModelV2Request, ...)` → 配置分支选择 `mGetVideoModelV2RxJava`，或拷贝字段到 GetVideoModelRequest 后 `mGetVideoModelRxJava`。
- `vb5.s0.f(MGetVideoDetailV2Request)` 有相同新旧详情分流。
- `com.dragon.read.component.shortvideo.impl.v2.data.b$a.a(String)` → `new JSONObject` → `VideoRef.extractFields` → `VideoModel.setVideoRef`。
- autoplay/reader preload实现把 VideoModel 传入 `PlayEntity.setVideoModel`，不是简单把随意URL交给系统VideoView。
- 官方库存在 libttffmpeg、libttmplayer、libuniplayer、libvideodec；网络库libsscronet/libttboringssl。存在性不证明某次实际选择哪种解码/传输。

证据：video-call-edges.json、series-api-caller-vb5-s0.txt、player-model-converter.txt、player-videoinfo.txt、manifest-summary.json。调用边是所选方法的静态引用，不是完整运行轨迹。

## 协议与模型

完整逐字段及枚举表见 model-contract.md / model-contract-data.json / enum-contract-data.json。关键区别：

- Series detail输入series_id；album输入album_id，可带series_ids；model输入video_id。
- VideoSeriesIdType枚举：VideoId=0、SeriesId=1、PostId=2。不可将不同来源的数字ID仅因格式相同就互换。
- VideoContentType枚举：ShortSeriesPlay=1、ShortPlay=7、Album=1003、MotionComic=1004、UnrealMotionComic=1007。不要与另一ContentType类混淆。
- 主App单模型响应data为VideoModelData；主App批量为data.video_model_data映射。Series批量data直接是映射。
- 生成序列化descriptor均标optional=true，仅表示客户端反序列化允许缺字段，不能说明服务器允许全部省略。
- API上声明RpcParams=true、RpcSerializer=JSON；GET的实际编码由通用RPC层处理，不能从注解武断构造GET请求体。
- Feed方法getFeedRxJava→GET /reading/playlet/shortbuy_api/get_feed/v1/；商城Feed/Page/Tab各有独立GetMallPlaylet...Request/Response。
- 搜索tab→GET /reading/bookapi/search/tab/v:version/，输入GetSearchPageRequest；suggest有独立SuggestRequest。
- 本轮没有追定所有`v:version`替换值、动态主机/调度配置、实验分流结果，因此不能提供“照这些字段即可必然成功”的请求配方。

## 安全修复边界

- 修复总期限、取消和首个失败类别的传递；明确业务拒绝不得改成普通网络错误继续无界备用取源。
- 保留明确明文标记及外层试看/过期等状态；未知加密继续拒绝，不从缺失字段推断明文。
- 维持HTTPS/域名/分集身份/真实尺寸/完整时长校验，继续拒绝预览冒充完整片源。
- 不复制认证/DRM实现，不提取账号或播放授权值，不用番茄aid替换红果aid作为绕行。
- 需要真实媒体结果时另做授权环境中的正常请求与静音播放验收。本报告不证明联网、解码、连播或投屏成功。

## 复现与证据

仓库 scripts/fanqie-static/ 脚本：fanqie_static_inventory.py（全包索引）、scan_fanqie_calls.py（选定DEX调用边）、extract_dex_class.py（命名类反汇编）。同目录模型脚本：extract_model_contract.py、extract_enum_contract.py、write_contract_report.py。

工具均只将APK作为数据读取，使用已安装的官方 Android SDK aapt/apksigner/dexdump；未安装第三方反编译程序，也未安装或执行APK。扫描未收集运行期账号、登记ID、媒体URL或密钥。认证相关字段名只用于模型边界说明。

## 授权状态补充

`VideoErr` 的已确认拒绝枚举包括 NoPermission=101003、Locked=101005、PaidLocked=101007、UnPaidBlock=101008。应作为停止/报告业务拒绝的依据，不把它们转为普通超时继续换身份或取流。补充试看片、过期、EpisodeLockData及encrypt字段的证据见 `plaintext-and-entitlement-contract.md`。

## 仓库交付说明

本目录保存汇总、模型/枚举schema、来源和全包校验摘要。文中提到的完整DEX、反汇编文本、模型证据片段和APK不纳入源码仓库；可按 README.md 在本地分析目录复现。产品修改与实际验证结果见 implementation-review.md、verification.md。
