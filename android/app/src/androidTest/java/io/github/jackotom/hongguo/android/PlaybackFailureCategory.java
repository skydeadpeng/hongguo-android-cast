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
            case "域名解析失败": case "域名解析为空": case "域名解析超时":
            case "域名解析繁忙，请重试": case "DoH 无 A 记录":
            case "App域名解析失败": case "App域名解析超时":
            case "App域名解析繁忙，请重试": return Category.NETWORK_DNS;
            case "Trust anchor for certification path not found.":
            case "Chain validation failed":
            case "SSL handshake aborted": return Category.NETWORK_TLS;
            case "Read timed out": case "read timed out": case "connect timed out":
            case "SSL handshake timed out":
            case "播放缓存下载超时": case "页面请求超时":
            case "App请求超时": return Category.NETWORK_SOCKET_TIMEOUT;
            case "设备准备视频超时，正在尝试其他片源": return Category.DECODER_PREPARE_TIMEOUT;
            case "视频持续缓冲超时，正在自动重新取源": return Category.BUFFERING_TIMEOUT;
            default:
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
