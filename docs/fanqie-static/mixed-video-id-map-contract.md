# mixed_video_id_map 定点静态核对

日期：2026-11-11；样本：番茄官方7.4.3.32，SHA-256 daaf7463fbcaa10ac1db93d16ef873c97eb386397e42f8a0af1085c1ced0cb7e。只读静态调查，无产品改动、无接口请求。

## 结论

当前官方SDK的 map key 是“内容类型数值”的短整数表示，不是通用“这是视频ID”的常量。对已经确认为非MotionComic(1004)的内容，固定 `{"1004":[vid]}` 与本样本的官方构造不一致。这个结论来自真实调用层构造及V2到普通multi的回退，而不只是枚举名称。

但静态证据不能证明服务器是否容错、历史版本曾采用何种兼容策略，或此差异就是用户的超时根因。也不能把所有map key统一改成1：ShortSeriesPlay、ShortPlay、MotionComic、UnrealMotionComic等类型不同。必须先掌握本集来源提供的真实内容类型并保留ID命名空间。

## 声明与serializer

- classes16.dex GetVideoModelRequest.mixedVideoIdMap 与 MGetVideoModelV2Request.mixedVideoIdMap 的 Signature 均为 `Map<Short,List<String>>`。
- 注解字段名都是 `mixed_video_id_map`。
- 两类静态初始化均创建 LinkedHashMapSerializer；key serializer 为 `en8.g2`，其descriptor明确为kotlin.Short，调用encodeShort/decodeShort，不是 VideoContentType enum serializer；value为字符串列表serializer。这说明wire map的key按Short序列化，但单凭此声明不能确认业务含义。
- 证据：classes16-dump.txt行3450591–3450593、3480794–3480796；model-evidence/classes16 中两request类的 `<clinit>`。

## 实际构造

1. 下载准备路径：classes5.dex `k45.f.invoke`
   - 该闭包保存一个 `seriessdk...VideoContentType` 参数，日志标识 ShortSeriesDownloadCacheDataManager。
   - 0x76f78c 调用 VideoContentType.getValue()。
   - 0x76f794 int-to-short，0x76f796 Short.valueOf。
   - 遍历SaasVideoData，读取每条 `.vid` 字符串（0x76f7d4），加入列表。
   - 把内容类型短整数与vid列表组成map；0x76f800赋给 MGetVideoModelV2Request.mixedVideoIdMap。
   - 这是key来自内容类型、value来自单视频vid的直接证据。

2. 播放预取路径：classes5.dex `l55.d1.a`
   - 日志prefetchVideoModel。
   - 当MultiVideoModelOpt.enable打开，按每个l55.f1对象的c整型字段分组（0x783724），key转Short（0x7837b2–7837b4）。
   - 每组读取对象b字符串字段作为ID列表（0x78385c）。
   - 0x783a6a写入MGetVideoModelV2Request.mixedVideoIdMap。
   - 支持多类型分别分组，未见统一强制1004；该片段不能独立证明f1.c所有上游来源值，应结合下载路径的明确枚举证据解释。

## V2与普通multi语义

classes5.dex `vb5.s0.e(MGetVideoModelV2Request, ...)`：
- 配置分支可以直接调用mGetVideoModelV2RxJava（带dr_scene路径）。
- 普通路径新建GetVideoModelRequest，复制bizParam、NovelCommonParam及mixedVideoIdMap；0x899b3c从V2读取map，0x899b40原样写入普通请求，再于0x899b56调用mGetVideoModelRxJava。
- 在这个真实回退路径没有key转换表或“V2的1改为普通multi的1004”的转换。
- 未发现“SDK1/2 map key是不同ID类型语义”的支持证据。SDK还有主App旧GET另一模型族，不能据名称当作同一接口版本。

## 不要混用三种概念

- map key / content_type：本样本构造来自VideoContentType，ShortSeriesPlay=1、ShortPlay=7、Album=1003、MotionComic=1004、UnrealMotionComic=1007。
- biz_param.video_id_type：另一枚举VideoSeriesIdType，VideoId=0、SeriesId=1、PostId=2，描述ID选取命名空间；不是上述map key。
- vid字符串：本次下载构造读取SaasVideoData.vid。静态格式相似不能证明可以用series_id或album_id代替。

## 对现有项目的建议

- `content_type=1`适用于已知ShortSeriesPlay的单模型请求；不能由此证明它涵盖所有目录内容。
- 固定1004的旧multi请求不是当前官方构造下的通用短剧请求。应记录为独立类型传递问题，不凭静态枚举把它整体替换为1。
- 本轮先保留已验证的预算、错误类别、明文标记与试看保护修复；如后续修类型，应从目录/详情可靠提取类型，测试不同类型及缺失类型处理，不能自动枚举多个type轮番请求。

## 证据与复现

- mixed-map-builders.json：三个相关方法的完整反汇编。
- series-api-caller-vb5-s0.txt：V2到普通multi回退。
- map-key-short-serializer.txt：key serializer。
- prefetch-model-request-f1.txt：预取请求类。
- 原始APK未运行。这些原始反汇编证据不随仓库分发。可使用 `scripts/fanqie-static/extract_dex_class.py`，选择classes5.dex的Lk45/f;、Ll55/d1;、Lvb5/s0;，或classes15.dex的Len8/g2;复现。
