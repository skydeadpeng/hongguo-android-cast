# Fanqie 7.4.3.32 static model contract

Read-only evidence from official APK, DEX classes9/classes16/classes17, Android SDK dexdump -a -d. No APK execution, network API call, authentication emulation, or product changes.

## Most important integration findings

- Main App `com.dragon.read.rpc.model.GetVideoModelRequest` is flat. Series SDK request is nested; they are not interchangeable.
- SDK detail request uses `series_id`; SDK model request uses `video_id`. Do not copy an album ID or series ID into video_id without preserving the ID selector.
- `VideoSeriesIdType`: VideoId=0, SeriesId=1, PostId=2. These are enum-defined namespaces, not proof of the namespace of a particular catalog ID.
- `VideoContentType` differs from `ContentType`. Main and series VideoContentType use ShortSeriesPlay=1, ShortPlay=7, Album=1003, MotionComic=1004, UnrealMotionComic=1007. Main ContentType instead describes user content categories (VideoSeriesPost=5).
- `video_model` is a String, not a native nested object, in both main VideoModelData and SDK SaaSVideoModelData. Main response additionally offers `main_url` and `backup_url` String fields. Static schema alone does not establish that these URLs are populated, playable, or authorized for a given item.
- Main response envelope is `code`, `message`, `data`; SDK adds `debug_info`, `log_id` and uses a different error enum. Main multi-model response is data.video_model_data (Map); SDK multi-model response data itself is Map<String,SaaSVideoModelData>.
- Main request deserialization defaults verified in constructor: object/enum/string fields null; booleans false; source integer 0. SDK GetVideoBizParam defaults similarly null/false/0. Do not interpret client defaults as required server values or infer the server's absent-value semantics.
- Every generated field listed below is descriptor optional=true. This means generated client decoding permits absence, not that a valid server request can omit every field.
- Authentication/token-related field NAMES are retained only as schema. No token/key values were collected or emulated. No endpoint auth or DRM bypass is recommended.

## Exact descriptor field sequences and JVM model types

### com/dragon/read/rpc/model/SaaSVideoAlbumDetailData

Wire fields (case-sensitive, descriptor order): `album_data`, `video_detail_data`.

Model fields/types: `albumData: Lcom/dragon/read/rpc/model/VideoAlbumDetailInfo;`, `videoDetailData: Ljava/util/Map;`.

Evidence: `model-evidence/classes9/com.dragon.read.rpc.model.SaaSVideoAlbumDetailData$a.txt`.

### com/dragon/read/rpc/model/SaaSVideoDetailData

Wire fields (case-sensitive, descriptor order): `video_data`, `lock_data`, `preview_material_list`, `style`, `video_post_data`, `video_relate_book`, `log_pb`.

Model fields/types: `lockData: Lcom/dragon/read/rpc/model/EpisodeLockData;`, `logPb: Ljava/util/Map;`, `previewMaterialList: Ljava/util/List;`, `style: Lcom/dragon/read/rpc/model/SaaSVideoDetailStyle;`, `videoData: Lcom/dragon/read/rpc/model/VideoDetailInfo;`, `videoPostData: Lcom/dragon/read/rpc/model/VideoPostData;`, `videoRelateBook: Lcom/dragon/read/rpc/model/VideoRelateBook;`.

Evidence: `model-evidence/classes9/com.dragon.read.rpc.model.SaaSVideoDetailData$a.txt`.

### com/dragon/read/rpc/model/SaaSVideoModelData

Wire fields (case-sensitive, descriptor order): `video_model`, `video_width`, `video_height`, `play_auth_token`, `expire_time`, `is_trial_video`, `main_url`, `backup_url`, `disable_vsr`, `language_preference`, `video_preload_info`, `log_pb`.

Model fields/types: `backupUrl: Ljava/lang/String;`, `disableVSR: Z`, `expireTime: J`, `isTrialVideo: Z`, `languagePreference: Lcom/dragon/read/rpc/model/LanguagePreference;`, `logPb: Ljava/util/Map;`, `mainUrl: Ljava/lang/String;`, `playAuthToken: Ljava/lang/String;`, `videoHeight: I`, `videoModel: Ljava/lang/String;`, `videoPreLoadInfo: Lcom/dragon/read/rpc/model/MediaPreLoadInfo;`, `videoWidth: I`.

Evidence: `model-evidence/classes9/com.dragon.read.rpc.model.SaaSVideoModelData$a.txt`.

### com/dragon/read/rpc/model/VideoModelData

Wire fields (case-sensitive, descriptor order): `video_model`, `authorization`, `video_width`, `video_height`, `play_auth_token`, `expire_time`, `is_trial_video`, `main_url`, `backup_url`.

Model fields/types: `authorization: Ljava/lang/String;`, `backupUrl: Ljava/lang/String;`, `expireTime: J`, `isTrialVideo: Z`, `mainUrl: Ljava/lang/String;`, `playAuthToken: Ljava/lang/String;`, `videoHeight: I`, `videoModel: Ljava/lang/String;`, `videoWidth: I`.

Evidence: `model-evidence/classes9/com.dragon.read.rpc.model.VideoModelData$a.txt`.

### seriessdk/com/dragon/read/saas/rpc/model/GetVideoAlbumDetailRequest

Wire fields (case-sensitive, descriptor order): `album_id`, `biz_param`, `need_video_detail_info`, `series_ids`, `NovelCommonParam`.

Model fields/types: `albumId: Ljava/lang/String;`, `bizParam: Lseriessdk/com/dragon/read/saas/rpc/model/GetVideoBizParam;`, `needVideoDetailInfo: Z`, `novelCommonParam: Lseriessdk/com/dragon/read/saas/rpc/model/NovelCommonParam;`, `seriesIds: Ljava/util/List;`.

Evidence: `model-evidence/classes16/seriessdk.com.dragon.read.saas.rpc.model.GetVideoAlbumDetailRequest$a.txt`.

### seriessdk/com/dragon/read/saas/rpc/model/GetVideoAlbumDetailResponse

Wire fields (case-sensitive, descriptor order): `data`, `debug_info`, `code`, `message`, `log_id`.

Model fields/types: `code: Lseriessdk/com/dragon/read/saas/rpc/model/VideoErr;`, `data: Lseriessdk/com/dragon/read/saas/rpc/model/SaaSVideoAlbumDetailData;`, `debugInfo: Ljava/lang/String;`, `logID: Ljava/lang/String;`, `message: Ljava/lang/String;`.

Evidence: `model-evidence/classes16/seriessdk.com.dragon.read.saas.rpc.model.GetVideoAlbumDetailResponse$a.txt`.

### seriessdk/com/dragon/read/saas/rpc/model/GetVideoBizParam

Wire fields (case-sensitive, descriptor order): `video_platform`, `need_all_video_definition`, `use_os_player`, `video_id_type`, `from_video_id`, `device_level`, `source`, `need_mp4_align`, `image_shrink_datas_str`, `screen_width_px`, `item_map`, `caller_scene`, `detail_page_version`, `use_server_dns`, `use_server_dns_scene`, `expire_strategy`, `disable_digg_stat`, `disable_video_relate_book`, `open_playlet_extend_param`, `multi_lang_landing_hint`, `multi_lang_landing_hint_map`.

Model fields/types: `callerScene: Ljava/lang/String;`, `detailPageVersion: I`, `deviceLevel: Lseriessdk/com/dragon/read/saas/rpc/model/DeviceLevel;`, `disableDiggStat: Z`, `disableVideoRelateBook: Z`, `expireStrategy: Ljava/lang/String;`, `fromVideoId: Ljava/lang/String;`, `imageShrinkDatasStr: Ljava/lang/String;`, `itemMap: Ljava/util/Map;`, `multiLangLandingHint: Lseriessdk/com/dragon/read/saas/rpc/model/MultiLangLandingHint;`, `multiLangLandingHintMap: Ljava/util/Map;`, `needAllVideoDefinition: Z`, `needMp4Align: Z`, `openPlayletExtendParam: Ljava/lang/String;`, `screenWidthPx: Ljava/lang/String;`, `source: Lseriessdk/com/dragon/read/saas/rpc/model/VideoDetailSource;`, `useOsPlayer: Z`, `useServerDns: Z`, `useServerDnsScene: Ljava/lang/String;`, `videoIdType: Lseriessdk/com/dragon/read/saas/rpc/model/VideoSeriesIdType;`, `videoPlatform: Lseriessdk/com/dragon/read/saas/rpc/model/VideoPlatformType;`.

Evidence: `model-evidence/classes16/seriessdk.com.dragon.read.saas.rpc.model.GetVideoBizParam$a.txt`.

### seriessdk/com/dragon/read/saas/rpc/model/GetVideoDetailRequest

Wire fields (case-sensitive, descriptor order): `series_id`, `biz_param`, `content_type`, `NovelCommonParam`.

Model fields/types: `bizParam: Lseriessdk/com/dragon/read/saas/rpc/model/GetVideoBizParam;`, `novelCommonParam: Lseriessdk/com/dragon/read/saas/rpc/model/NovelCommonParam;`, `seriesId: Ljava/lang/String;`, `videoContentType: Lseriessdk/com/dragon/read/saas/rpc/model/VideoContentType;`.

Evidence: `model-evidence/classes16/seriessdk.com.dragon.read.saas.rpc.model.GetVideoDetailRequest$a.txt`.

### seriessdk/com/dragon/read/saas/rpc/model/GetVideoDetailResponse

Wire fields (case-sensitive, descriptor order): `data`, `BizParam`, `debug_info`, `code`, `message`, `log_id`.

Model fields/types: `bizParam: Lseriessdk/com/dragon/read/saas/rpc/model/GetVideoBizParam;`, `code: Lseriessdk/com/dragon/read/saas/rpc/model/VideoErr;`, `data: Lseriessdk/com/dragon/read/saas/rpc/model/SaaSVideoDetailData;`, `debugInfo: Ljava/lang/String;`, `logID: Ljava/lang/String;`, `message: Ljava/lang/String;`.

Evidence: `model-evidence/classes16/seriessdk.com.dragon.read.saas.rpc.model.GetVideoDetailResponse$a.txt`.

### seriessdk/com/dragon/read/saas/rpc/model/GetVideoModelRequest

Wire fields (case-sensitive, descriptor order): `video_id`, `biz_param`, `content_type`, `mixed_video_id_map`, `NovelCommonParam`.

Model fields/types: `bizParam: Lseriessdk/com/dragon/read/saas/rpc/model/GetVideoBizParam;`, `mixedVideoIdMap: Ljava/util/Map;`, `novelCommonParam: Lseriessdk/com/dragon/read/saas/rpc/model/NovelCommonParam;`, `videoContentType: Lseriessdk/com/dragon/read/saas/rpc/model/VideoContentType;`, `videoId: Ljava/lang/String;`.

Evidence: `model-evidence/classes16/seriessdk.com.dragon.read.saas.rpc.model.GetVideoModelRequest$a.txt`.

### seriessdk/com/dragon/read/saas/rpc/model/GetVideoModelResponse

Wire fields (case-sensitive, descriptor order): `data`, `debug_info`, `code`, `message`, `log_id`.

Model fields/types: `code: Lseriessdk/com/dragon/read/saas/rpc/model/VideoErr;`, `data: Lseriessdk/com/dragon/read/saas/rpc/model/SaaSVideoModelData;`, `debugInfo: Ljava/lang/String;`, `logID: Ljava/lang/String;`, `message: Ljava/lang/String;`.

Evidence: `model-evidence/classes16/seriessdk.com.dragon.read.saas.rpc.model.GetVideoModelResponse$a.txt`.

### seriessdk/com/dragon/read/saas/rpc/model/MGetVideoModelResponse

Wire fields (case-sensitive, descriptor order): `data`, `debug_info`, `code`, `message`, `log_id`.

Model fields/types: `code: Lseriessdk/com/dragon/read/saas/rpc/model/VideoErr;`, `data: Ljava/util/Map;`, `debugInfo: Ljava/lang/String;`, `logID: Ljava/lang/String;`, `message: Ljava/lang/String;`.

Evidence: `model-evidence/classes16/seriessdk.com.dragon.read.saas.rpc.model.MGetVideoModelResponse$a.txt`.

### seriessdk/com/dragon/read/saas/rpc/model/MGetVideoModelV2Request

Wire fields (case-sensitive, descriptor order): `mixed_video_id_map`, `biz_param`, `dr_scene`, `NovelCommonParam`.

Model fields/types: `bizParam: Lseriessdk/com/dragon/read/saas/rpc/model/GetVideoBizParam;`, `drScene: Ljava/lang/String;`, `mixedVideoIdMap: Ljava/util/Map;`, `novelCommonParam: Lseriessdk/com/dragon/read/saas/rpc/model/NovelCommonParam;`.

Evidence: `model-evidence/classes16/seriessdk.com.dragon.read.saas.rpc.model.MGetVideoModelV2Request$a.txt`.

### seriessdk/com/dragon/read/saas/rpc/model/SaaSVideoAlbumDetailData

Wire fields (case-sensitive, descriptor order): `album_data`, `video_detail_data`.

Model fields/types: `albumData: Lseriessdk/com/dragon/read/saas/rpc/model/VideoAlbumDetailInfo;`, `videoDetailData: Ljava/util/Map;`.

Evidence: `model-evidence/classes16/seriessdk.com.dragon.read.saas.rpc.model.SaaSVideoAlbumDetailData$a.txt`.

### seriessdk/com/dragon/read/saas/rpc/model/SaaSVideoDetailData

Wire fields (case-sensitive, descriptor order): `video_data`, `lock_data`, `preview_material_list`, `style`, `video_post_data`, `video_relate_book`, `log_pb`.

Model fields/types: `lockData: Lseriessdk/com/dragon/read/saas/rpc/model/EpisodeLockData;`, `logPb: Ljava/util/Map;`, `previewMaterialList: Ljava/util/List;`, `style: Lseriessdk/com/dragon/read/saas/rpc/model/SaaSVideoDetailStyle;`, `videoData: Lseriessdk/com/dragon/read/saas/rpc/model/VideoDetailInfo;`, `videoPostData: Lseriessdk/com/dragon/read/saas/rpc/model/VideoPostData;`, `videoRelateBook: Lseriessdk/com/dragon/read/saas/rpc/model/VideoRelateBook;`.

Evidence: `model-evidence/classes16/seriessdk.com.dragon.read.saas.rpc.model.SaaSVideoDetailData$a.txt`.

### seriessdk/com/dragon/read/saas/rpc/model/SaaSVideoModelData

Wire fields (case-sensitive, descriptor order): `video_model`, `video_width`, `video_height`, `play_auth_token`, `expire_time`, `is_trial_video`, `main_url`, `backup_url`, `disable_vsr`, `language_preference`, `video_preload_info`, `log_pb`.

Model fields/types: `backupUrl: Ljava/lang/String;`, `disableVSR: Z`, `expireTime: J`, `isTrialVideo: Z`, `languagePreference: Lseriessdk/com/dragon/read/saas/rpc/model/LanguagePreference;`, `logPb: Ljava/util/Map;`, `mainUrl: Ljava/lang/String;`, `playAuthToken: Ljava/lang/String;`, `videoHeight: I`, `videoModel: Ljava/lang/String;`, `videoPreLoadInfo: Lseriessdk/com/dragon/read/saas/rpc/model/MediaPreLoadInfo;`, `videoWidth: I`.

Evidence: `model-evidence/classes16/seriessdk.com.dragon.read.saas.rpc.model.SaaSVideoModelData$a.txt`.

### com/dragon/read/rpc/model/GetVideoBizParam

Wire fields (case-sensitive, descriptor order): `video_platform`, `need_all_video_definition`, `use_os_player`, `video_id_type`, `from_video_id`, `device_level`, `source`, `need_mp4_align`, `image_shrink_datas_str`, `screen_width_px`, `item_map`, `caller_scene`, `detail_page_version`, `use_server_dns`, `use_server_dns_scene`, `expire_strategy`, `disable_digg_stat`, `disable_video_relate_book`, `open_playlet_extend_param`, `multi_lang_landing_hint`, `multi_lang_landing_hint_map`.

Model fields/types: `callerScene: Ljava/lang/String;`, `detailPageVersion: I`, `deviceLevel: Lcom/dragon/read/rpc/model/DeviceLevel;`, `disableDiggStat: Z`, `disableVideoRelateBook: Z`, `expireStrategy: Ljava/lang/String;`, `fromVideoId: Ljava/lang/String;`, `imageShrinkDatasStr: Ljava/lang/String;`, `itemMap: Ljava/util/Map;`, `multiLangLandingHint: Lcom/dragon/read/rpc/model/MultiLangLandingHint;`, `multiLangLandingHintMap: Ljava/util/Map;`, `needAllVideoDefinition: Z`, `needMp4Align: Z`, `openPlayletExtendParam: Ljava/lang/String;`, `screenWidthPx: Ljava/lang/String;`, `source: Lcom/dragon/read/rpc/model/VideoDetailSource;`, `useOsPlayer: Z`, `useServerDns: Z`, `useServerDnsScene: Ljava/lang/String;`, `videoIdType: Lcom/dragon/read/rpc/model/VideoSeriesIdType;`, `videoPlatform: Lcom/dragon/read/rpc/model/VideoPlatformType;`.

Evidence: `model-evidence/classes17/com.dragon.read.rpc.model.GetVideoBizParam$a.txt`.

### com/dragon/read/rpc/model/GetVideoModelRequest

Wire fields (case-sensitive, descriptor order): `video_id`, `video_platform`, `need_all_video_definition`, `use_os_player`, `video_id_type`, `source`, `from_video_id`, `device_level`, `need_mp4_align`.

Model fields/types: `deviceLevel: Lcom/dragon/read/rpc/model/DeviceLevel;`, `fromVideoId: Ljava/lang/String;`, `needAllVideoDefinition: Z`, `needMp4Align: Z`, `source: I`, `useOsPlayer: Z`, `videoId: Ljava/lang/String;`, `videoIdType: Lcom/dragon/read/rpc/model/VideoSeriesIdType;`, `videoPlatform: Lcom/dragon/read/rpc/model/VideoPlatformType;`.

Evidence: `model-evidence/classes17/com.dragon.read.rpc.model.GetVideoModelRequest$a.txt`.

### com/dragon/read/rpc/model/GetVideoModelResponse

Wire fields (case-sensitive, descriptor order): `code`, `message`, `data`.

Model fields/types: `code: Lcom/dragon/read/rpc/model/BookApiERR;`, `data: Lcom/dragon/read/rpc/model/VideoModelData;`, `message: Ljava/lang/String;`.

Evidence: `model-evidence/classes17/com.dragon.read.rpc.model.GetVideoModelResponse$a.txt`.

### com/dragon/read/rpc/model/MGetVideoModelData

Wire fields (case-sensitive, descriptor order): `video_model_data`.

Model fields/types: `videoModelData: Ljava/util/Map;`.

Evidence: `model-evidence/classes17/com.dragon.read.rpc.model.MGetVideoModelData$a.txt`.

### com/dragon/read/rpc/model/MGetVideoModelResponse

Wire fields (case-sensitive, descriptor order): `code`, `message`, `data`.

Model fields/types: `code: Lcom/dragon/read/rpc/model/BookApiERR;`, `data: Lcom/dragon/read/rpc/model/MGetVideoModelData;`, `message: Ljava/lang/String;`.

Evidence: `model-evidence/classes17/com.dragon.read.rpc.model.MGetVideoModelResponse$a.txt`.

## Relevant enums

- com.dragon.read.rpc.model.VideoErr: SUCCESS=0, FastReject=100, IllegalAccess=110, HitVerifyCode=111, AppReject=112, ParamInvalid=100001, DataEmpty=100002, DupOperation=100003, InternalError=100004, ServiceError=101000, BookFulllyRemove=101001, ContentVerifying=101002, VideoNoPermission=101003, AgeIllegal=101004, VideoLocked=101005, RpcTimeOut=101006, VideoPaidLocked=101007, VideoUnPaidBlock=101008, CustomRecordNumberNotFound=101009, FreqCtrl=101010, BookFieldNotSupport=200001, I18nVideoModelRiskControlError=300001
- com.dragon.read.rpc.model.VideoSeriesIdType: VideoId=0, SeriesId=1, PostId=2
- com.dragon.read.rpc.model.VideoPlatformType: Unknown=0, PlatformDouyin=1, UgcVideo=2, Outer=3, PlatformXigua=4, Chameleon=5, Mixed=1024
- com.dragon.read.rpc.model.VideoContentType: ShortSeriesPlay=1, ScenePlay=2, ExpertRecommendBook=3, PPTRecommendBook=4, Movie=5, TelePlay=6, ShortPlay=7, DropMaterial=1000, PUGC=1001, UnrealShortPlay=1002, Album=1003, MotionComic=1004, FamousSceneVideo=1005, UgcPostVideo=1006, UnrealMotionComic=1007, CommerceVideoSlice=1008, AIRewriteDraft=1009, Video1ColPushBookVideo=1010, FamousSceneTtv=1011, AdReplacePlay=1012
- com.dragon.read.rpc.model.DeviceLevel: Low=1, Middle=2, High=3
- com.dragon.read.rpc.model.ContentType: NoneContentType=0, PostStory=1, UgcStory=2, AIGC=3, PgcPayStory=4, VideoSeriesPost=5, KOLVideoSeriesPost=6
- seriessdk.com.dragon.read.saas.rpc.model.VideoErr: SUCCESS=0, FastReject=100, IllegalAccess=110, HitVerifyCode=111, AppReject=112, ParamInvalid=100001, DataEmpty=100002, DupOperation=100003, InternalError=100004, ServiceError=101000, BookFulllyRemove=101001, ContentVerifying=101002, VideoNoPermission=101003, AgeIllegal=101004, VideoLocked=101005, RpcTimeOut=101006, VideoPaidLocked=101007, VideoUnPaidBlock=101008, CustomRecordNumberNotFound=101009, FreqCtrl=101010, BookFieldNotSupport=200001, I18nVideoModelRiskControlError=300001
- seriessdk.com.dragon.read.saas.rpc.model.VideoSeriesIdType: VideoId=0, SeriesId=1, PostId=2
- seriessdk.com.dragon.read.saas.rpc.model.VideoPlatformType: Unknown=0, PlatformDouyin=1, UgcVideo=2, Outer=3, PlatformXigua=4, Chameleon=5, Mixed=1024
- seriessdk.com.dragon.read.saas.rpc.model.DeviceLevel: Low=1, Middle=2, High=3
- seriessdk.com.dragon.read.saas.rpc.model.VideoContentType: ShortSeriesPlay=1, ScenePlay=2, ExpertRecommendBook=3, PPTRecommendBook=4, Movie=5, TelePlay=6, ShortPlay=7, DropMaterial=1000, PUGC=1001, UnrealShortPlay=1002, Album=1003, MotionComic=1004, FamousSceneVideo=1005, UgcPostVideo=1006, UnrealMotionComic=1007, CommerceVideoSlice=1008, AIRewriteDraft=1009, Video1ColPushBookVideo=1010, FamousSceneTtv=1011, AdReplacePlay=1012

## Reproduction and limits

1. Extract classes9.dex and classes17.dex from fanqie-official.apk, using Python zipfile; classes16 was already extracted as fanqie-classes16.dex.
2. Run Android SDK build-tools/35.0.0/dexdump -a -d on each to produce classes9-dump.txt, classes16-dump.txt, classes17-dump.txt in a separate analysis output directory.
3. Run `python extract_model_contract.py`, then `python extract_enum_contract.py`, then `python write_contract_report.py`.
4. model-contract-data.json retains fields, optional flags, exact DEX instruction addresses for descriptor additions, and evidence file paths. enum-contract-data.json retains constructor argument values and exact disassembly address evidence.

Enum script performs straight-line constant/move tracking through static constructors; names and numeric values listed are direct constructor arguments, not guesses from ordinal position. It does not prove downstream HTTP encoding. Exact endpoint routing, query/body encoding, choice of enum for an actual video, entitlement behavior, populated URL availability, and end-to-end playback require separate call-site evidence and/or permitted tests. Model evidence alone does not establish a complete working API client.
