package io.github.jackotom.hongguo.android;

/** JVM-only safety checks; these do not claim device playback or issue any request. */
public final class PlaybackFailureCategoryChecks {
    private static int checks;

    public static void main(String[] args) {
        check(null, "none");
        check("官网播放信息与所选分集不一致", "guest_identity_mismatch");
        check("官网返回的剧集身份不匹配", "guest_identity_mismatch");
        check("本集需要官网支持的登录播放流程，请在官方应用查看", "login_required");
        check("官网游客播放请求未获允许（HTTP 401）", "guest_http_401");
        check("官网游客播放请求未获允许（HTTP 403）", "guest_http_403");
        check("官网游客播放请求未获允许（HTTP 302）", "guest_http_302");
        check("官网游客播放请求未获允许（HTTP 429）", "guest_http_other");
        check("官网游客播放请求未获允许（HTTP 4030）", "unknown");
        check("官网游客播放请求未完成，请稍后重试", "guest_request_incomplete_unknown_cause");
        check("官网视频未通过完整播放验证，请在官方应用查看", "guest_full_file_validation_failed");
        check("官网游客播放信息格式无效", "guest_format_invalid");
        check("官网播放地址已过期或有效期无效", "guest_expired_or_invalid_expiry");
        check("官网未提供可确认的完整公开片源", "guest_complete_public_source_unconfirmed");
        check("自动恢复未成功：官网游客播放请求未获允许（HTTP 403）；原错误：域名解析失败", "guest_http_403");
        check("自动恢复未成功：unrecognized；原错误：域名解析失败", "unknown");
        check("官网返回的分集身份不匹配，可重试或选择其他分集", "guest_identity_mismatch");
        check("当前片源或设备解码暂不可用（1/-1004），可重试或选择其他分集", "decoder_error");
        check("域名解析失败", "network_dns");
        check("Unable to resolve host \"redacted.invalid\": No address associated with hostname", "network_dns");
        check("Trust anchor for certification path not found.", "network_tls");
        check("Read timed out", "network_socket_timeout");
        check("signed URL or arbitrary upstream text", "unknown");
        check("官网游客播放请求未获允许（HTTP 403） trailing sensitive data", "unknown");
        check("prefix 官网播放信息与所选分集不一致", "unknown");
        check("timeout", "unknown");
        check(new String(new char[8193]).replace('\0', 'x'), "unknown");
        need(PlaybackFailureCategory.terminalDialogState(true, false, false));
        need(!PlaybackFailureCategory.terminalDialogState(false, false, false));
        need(!PlaybackFailureCategory.terminalDialogState(true, true, false));
        need(!PlaybackFailureCategory.terminalDialogState(true, false, true));
        for (PlaybackFailureCategory.Category category : PlaybackFailureCategory.Category.values())
            need(category.code.matches("[a-z0-9_]+"));
        System.out.println("PLAYBACK_DIAGNOSTIC_CHECKS_PASSED=" + checks);
    }

    private static void check(String text, String expected) {
        need(PlaybackFailureCategory.classify(text).code.equals(expected));
    }

    private static void need(boolean value) {
        checks++;
        if (!value) throw new AssertionError("diagnostic_allowlist_check_failed_" + checks);
    }
}
