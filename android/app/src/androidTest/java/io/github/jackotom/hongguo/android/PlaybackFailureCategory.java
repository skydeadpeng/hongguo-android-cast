package io.github.jackotom.hongguo.android;

/** Pure, lossy allowlist for QA diagnostics. Never returns input text or captured groups. */
final class PlaybackFailureCategory {
    enum Category {
        NONE("none"), UNKNOWN("unknown"),
        GUEST_IDENTITY_MISMATCH("guest_identity_mismatch"),
        LOGIN_REQUIRED("login_required"),
        GUEST_HTTP_401("guest_http_401"), GUEST_HTTP_403("guest_http_403"),
        GUEST_HTTP_302("guest_http_302"), GUEST_HTTP_OTHER("guest_http_other"),
        GUEST_PERMISSION_NOT_EXPLICIT("guest_permission_not_explicit"),
        GUEST_PUBLIC_PLAYBACK_DENIED("guest_public_playback_denied"),
        GUEST_REQUEST_TIMEOUT("guest_request_timeout"),
        GUEST_REQUEST_INCOMPLETE("guest_request_incomplete_unknown_cause"),
        GUEST_FORMAT_INVALID("guest_format_invalid"),
        GUEST_EXPIRY_INVALID("guest_expired_or_invalid_expiry"),
        GUEST_METADATA_INVALID("guest_declared_metadata_invalid"),
        GUEST_SOURCE_UNCONFIRMED("guest_complete_public_source_unconfirmed"),
        GUEST_FILE_VALIDATION_FAILED("guest_full_file_validation_failed"),
        GUEST_URL_REJECTED("guest_media_address_rejected"),
        GUEST_SERVICE_UNSUPPORTED("guest_media_service_unsupported"),
        GUEST_PROVENANCE_REQUIRED("guest_provenance_recheck_required"),
        WEB_PAGE_INVALID("web_page_data_invalid"), WEB_EPISODE_UNAVAILABLE("web_episode_unavailable"),
        WEB_IDENTITY_MISMATCH("web_page_identity_mismatch"), WEB_AUTH_FLOW_REQUIRED("web_supported_auth_flow_required"),
        APP_LOOKUP_TIMEOUT("app_lookup_timeout"),
        APP_LOOKUP_INCOMPLETE("app_registration_or_lookup_incomplete_unknown_cause"),
        APP_REGISTRATION_INCOMPLETE("app_device_registration_incomplete"),
        APP_MODEL_INVALID("app_model_response_invalid"), APP_MODEL_MISSING("app_model_or_source_missing"),
        APP_EMPTY_RESPONSE("app_empty_response"), APP_BUSINESS_DENIED("app_playback_business_denied"),
        APP_PREVIEW_ONLY("app_preview_only"), APP_IDENTITY_MISMATCH("app_source_identity_mismatch"),
        APP_SOURCE_HTTP("app_source_http_error"), APP_QUALITY_UNAVAILABLE("app_quality_or_codec_unavailable"),
        APP_SECURITY_REJECTED("app_source_security_validation_failed"),
        APP_SIGNING_UNAVAILABLE("app_playback_signing_unavailable"),
        APP_NETWORK_FAILURE("app_network_request_failed_unknown_cause"),
        APP_SERVICE_UNAVAILABLE("app_source_unavailable_unknown_cause"),
        REQUEST_CANCELLED("source_request_cancelled"),
        SOURCE_HTTP("source_http_error"),
        NETWORK_DNS("network_dns"), NETWORK_TLS("network_tls"),
        NETWORK_SOCKET_TIMEOUT("network_socket_timeout"),
        DECODER_ERROR("decoder_error"), DECODER_PREPARE_TIMEOUT("decoder_prepare_timeout"),
        BUFFERING_TIMEOUT("buffering_timeout");

        final String code;
        Category(String code) { this.code = code; }
        boolean knownError() { return this != NONE && this != UNKNOWN; }
    }

    private PlaybackFailureCategory() { }

    static Category classify(String input) {
        if (input == null || input.isEmpty()) return Category.NONE;
        if (input.length() > 8192) return Category.UNKNOWN;
        String text = input.trim();
        // These are the exact production PlayerActivity wrappers. Do not classify the
        // historical suffix as the current failure when the current cause is unknown.
        String prefix = "自动恢复未成功：";
        if (text.startsWith(prefix)) text = text.substring(prefix.length());
        int original = text.indexOf("；原错误：");
        if (original >= 0) text = text.substring(0, original);
        String suffix = "，可重试或选择其他分集";
        if (text.endsWith(suffix)) text = text.substring(0, text.length() - suffix.length());

        // PlaybackSources adds this exact suffix only for a repeated APP_EMPTY_RESPONSE.
        // It cannot turn arbitrary text, or a historical cause, into a known diagnosis.
        String retriedEmpty = "（已重取1次仍未返回有效数据，请稍后重试）";
        if (text.endsWith(retriedEmpty)) {
            Category category = classifyCurrent(text.substring(0, text.length() - retriedEmpty.length()));
            return category == Category.APP_EMPTY_RESPONSE ? category : Category.UNKNOWN;
        }
        return classifyCurrent(text);
    }

    private static Category classifyCurrent(String text) {
        switch (text) {
            case "官网播放信息与所选分集不一致":
            case "官网分集不属于当前剧集":
            case "官网返回的分集身份不匹配":
            case "官网返回的剧集身份不匹配": return Category.GUEST_IDENTITY_MISMATCH;
            case "本集需要官网支持的登录播放流程，请在官方应用查看": return Category.LOGIN_REQUIRED;
            case "官网游客播放请求未获允许（HTTP 401）": return Category.GUEST_HTTP_401;
            case "官网游客播放请求未获允许（HTTP 403）": return Category.GUEST_HTTP_403;
            case "官网游客播放请求未获允许（HTTP 302）": return Category.GUEST_HTTP_302;
            case "官网未明确允许本集使用公开游客播放流程": return Category.GUEST_PERMISSION_NOT_EXPLICIT;
            case "官网暂未允许本集公开播放":
            case "官网暂未允许本集游客播放": return Category.GUEST_PUBLIC_PLAYBACK_DENIED;
            case "官网游客播放请求超时，请稍后重试": return Category.GUEST_REQUEST_TIMEOUT;
            case "官网游客播放请求未完成，请稍后重试": return Category.GUEST_REQUEST_INCOMPLETE;
            case "官网游客播放信息格式无效": return Category.GUEST_FORMAT_INVALID;
            case "官网播放地址有效期无效":
            case "官网播放地址已过期或有效期无效":
            case "官网播放地址已过期，请重新选择分集": return Category.GUEST_EXPIRY_INVALID;
            case "官网缺少可验证的分集时长或尺寸":
            case "官网分集尺寸无效": return Category.GUEST_METADATA_INVALID;
            case "官网未提供可确认的完整公开片源": return Category.GUEST_SOURCE_UNCONFIRMED;
            case "官网视频未通过完整播放验证，请在官方应用查看": return Category.GUEST_FILE_VALIDATION_FAILED;
            case "官网播放地址未通过HTTPS公网校验": return Category.GUEST_URL_REJECTED;
            case "官网返回了不受支持的媒体服务": return Category.GUEST_SERVICE_UNSUPPORTED;
            case "官网游客播放信息必须由当前页面重新核实": return Category.GUEST_PROVENANCE_REQUIRED;
            // EpisodeResolver page checks, distinct from the subsequent guest POST.
            case "来源页面为空或超过大小限制": case "来源页面未返回目录数据":
            case "来源目录数据不是有效 JSON": case "来源目录数据不完整": return Category.WEB_PAGE_INVALID;
            case "该分集未向官网提供公开播放页面，请在官方 App 查看":
            case "该分集未向官网公开播放，请在官方 App 查看":
            case "该分集暂无官网公开播放地址": return Category.WEB_EPISODE_UNAVAILABLE;
            case "播放页面与请求剧集或分集不一致": return Category.WEB_IDENTITY_MISMATCH;
            case "官网需要受支持的授权播放流程": return Category.WEB_AUTH_FLOW_REQUIRED;
            // NativeBridge intentionally masks non-ModelError registration failures.
            // This wording proves no specific DNS/TLS/HTTP or permission cause.
            case "App片源登记或取源未完成，请重试": return Category.APP_LOOKUP_INCOMPLETE;
            case "App片源设备登记未完成": return Category.APP_REGISTRATION_INCOMPLETE;
            case "App片源登记和取源超时，请重试": case "App片源登记和取源总等待超时":
            case "App片源请求总等待超时": case "App片源准备超时，请重试":
            case "App片源请求超时，请重试": case "App 分集取源超时":
            case "App 片源请求超时": return Category.APP_LOOKUP_TIMEOUT;
            // RegisteredPlaybackModel and the shared production App-source adapter.
            case "App片源模型响应超过大小限制": case "App片源模型响应编码无效":
            case "App片源模型响应格式无效": case "App片源试看状态无效":
            case "App片源模型字段无效": case "App片源模型缺少清晰度列表":
            case "App片源模型清晰度列表无效": case "App片源模型清晰度无效":
            case "App备用片源列表无效": case "App备用片源地址无效":
            case "App片源尺寸信息无效": case "App片源准备信息无效":
            case "App 片源试看状态无效": case "App 单集模型缺少有效片源列表":
            case "App 模型接口返回了无效格式，请稍后重试":
            case "App 片源信息接口返回了无效格式，请稍后重试":
            case "设备请求返回了无效响应": case "App 接口响应超过大小限制": return Category.APP_MODEL_INVALID;
            case "App接口未返回有效数据": case "App 模型接口返回空数据，请稍后重试":
            case "App 片源信息接口返回空数据，请稍后重试": return Category.APP_EMPTY_RESPONSE;
            case "App接口未返回本集片源模型": case "App 接口未返回本集片源模型":
            case "App 接口未返回本集片源地址":
            case "App 接口未返回所选分集，已停止取址": return Category.APP_MODEL_MISSING;
            case "App接口暂未提供本集播放信息":
            case "App 接口暂未提供本集播放信息": return Category.APP_BUSINESS_DENIED;
            case "App接口仅提供本集试看，请在官方App查看":
            case "App 接口仅提供本集试看，请在官方 App 查看": return Category.APP_PREVIEW_ONLY;
            case "App片源与分集不一致": case "App 已登记设备的单集模型与请求不一致":
            case "片源与分集不一致": case "片源与剧集不一致": case "分集结果不匹配": return Category.APP_IDENTITY_MISMATCH;
            case "本集暂未提供兼容的 H.264/HEVC 片源":
            case "本集暂无尺寸可确认且满足播放清晰度边界的片源":
            case "本集暂无真实尺寸符合播放降档边界的 App 片源":
            case "播放片源尺寸未满足所请求边界": return Category.APP_QUALITY_UNAVAILABLE;
            case "App 片源地址格式无效": case "App 片源返回了不受支持的服务地址":
            case "App片源服务地址无效":
            case "App 接口发生不支持的跳转": case "App 片源跳转次数过多":
            case "App签名请求发生跳转，已停止转发": return Category.APP_SECURITY_REJECTED;
            case "App 播放签名不可用": return Category.APP_SIGNING_UNAVAILABLE;
            case "App 片源网络请求失败，请稍后重试": return Category.APP_NETWORK_FAILURE;
            case "App片源服务尚未准备": case "片源页面已关闭": case "分集正在准备，请稍后重试":
            case "App片源暂未取得": case "App 片源暂不可用": case "设备网络组件未连接": return Category.APP_SERVICE_UNAVAILABLE;
            case "App片源请求已取消": case "App 片源请求已取消": case "App 分集取源已取消":
            case "播放准备已取消": case "播放恢复已取消": case "已切换分集": return Category.REQUEST_CANCELLED;
            case "域名解析失败": case "域名解析为空": case "域名解析超时":
            case "域名解析繁忙，请重试": case "DoH 无 A 记录":
            case "App域名解析失败": case "App域名解析超时":
            case "App域名解析繁忙，请重试": return Category.NETWORK_DNS;
            case "Trust anchor for certification path not found.":
            case "Chain validation failed":
            case "SSL handshake aborted": return Category.NETWORK_TLS;
            case "Read timed out": case "read timed out": case "connect timed out":
            case "SSL handshake timed out":
            case "播放缓存下载超时": case "页面请求超时": case "官网取源超时": case "网络请求超时":
            case "App请求超时": return Category.NETWORK_SOCKET_TIMEOUT;
            case "设备准备视频超时，正在尝试其他片源": return Category.DECODER_PREPARE_TIMEOUT;
            case "视频持续缓冲超时，正在自动重新取源": return Category.BUFFERING_TIMEOUT;
            default:
                if (text.matches("App片源(?:服务返回|响应) HTTP [1-5][0-9]{2}")
                        || text.matches("App 片源请求失败（HTTP [1-5][0-9]{2}）")) return Category.APP_SOURCE_HTTP;
                if (text.matches("本集暂无尺寸可确认且满足 (?:1080p|720p|480p|360p) 的(?: App | H\\.264/HEVC )?片源，未降级(?:播放|。)?"))
                    return Category.APP_QUALITY_UNAVAILABLE;
                if (text.matches("官网游客播放请求未获允许（HTTP [1-5][0-9]{2}）")) return Category.GUEST_HTTP_OTHER;
                if (text.matches("(?:播放页面|片源)响应 HTTP [1-5][0-9]{2}")) return Category.SOURCE_HTTP;
                if (text.matches("Unable to resolve host \"[^\"\\r\\n]+\": No address associated with hostname")) return Category.NETWORK_DNS;
                if (text.matches("当前片源或设备解码暂不可用（-?[0-9]{1,10}/-?[0-9]{1,10}）")) return Category.DECODER_ERROR;
                return Category.UNKNOWN;
        }
    }

    static boolean terminalDialogState(boolean hudDialog, boolean prepared, boolean decoderPreparing) {
        // playbackError() clears prepared/decoderPreparing before showing its dialog.
        // The runner never opens controls, options, casts, or another player dialog.
        return hudDialog && !prepared && !decoderPreparing;
    }
}
