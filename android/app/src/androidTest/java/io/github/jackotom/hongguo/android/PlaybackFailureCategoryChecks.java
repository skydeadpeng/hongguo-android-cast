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
        checkPreGuestAndAppFailures();
        need(PlaybackFailureCategory.terminalDialogState(true, false, false));
        need(!PlaybackFailureCategory.terminalDialogState(false, false, false));
        need(!PlaybackFailureCategory.terminalDialogState(true, true, false));
        need(!PlaybackFailureCategory.terminalDialogState(true, false, true));
        for (PlaybackFailureCategory.Category category : PlaybackFailureCategory.Category.values())
            need(category.code.matches("[a-z0-9_]+"));
        System.out.println("PLAYBACK_DIAGNOSTIC_CHECKS_PASSED=" + checks);
    }

    private static void checkPreGuestAndAppFailures() {
        // Exact production messages, not fixture network requests or inferred causes.
        String[][] messages = {
            {"来源页面为空或超过大小限制", "web_page_data_invalid"},
            {"来源页面未返回目录数据", "web_page_data_invalid"},
            {"来源目录数据不是有效 JSON", "web_page_data_invalid"},
            {"来源目录数据不完整", "web_page_data_invalid"},
            {"该分集未向官网提供公开播放页面，请在官方 App 查看", "web_episode_unavailable"},
            {"该分集未向官网公开播放，请在官方 App 查看", "web_episode_unavailable"},
            {"该分集暂无官网公开播放地址", "web_episode_unavailable"},
            {"播放页面与请求剧集或分集不一致", "web_page_identity_mismatch"},
            {"官网需要受支持的授权播放流程", "web_supported_auth_flow_required"},
            {"App片源登记或取源未完成，请重试", "app_registration_or_lookup_incomplete_unknown_cause"},
            {"App片源设备登记未完成", "app_device_registration_incomplete"},
            {"App片源登记和取源超时，请重试", "app_lookup_timeout"},
            {"App片源登记和取源总等待超时", "app_lookup_timeout"},
            {"App片源请求总等待超时", "app_lookup_timeout"},
            {"App片源准备超时，请重试", "app_lookup_timeout"},
            {"App片源请求超时，请重试", "app_lookup_timeout"},
            {"App 分集取源超时", "app_lookup_timeout"},
            {"App 片源请求超时", "app_lookup_timeout"},
            {"App片源模型响应超过大小限制", "app_model_response_invalid"},
            {"App片源模型响应编码无效", "app_model_response_invalid"},
            {"App片源模型响应格式无效", "app_model_response_invalid"},
            {"App片源试看状态无效", "app_model_response_invalid"},
            {"App片源模型字段无效", "app_model_response_invalid"},
            {"App片源模型缺少清晰度列表", "app_model_response_invalid"},
            {"App片源模型清晰度列表无效", "app_model_response_invalid"},
            {"App片源模型清晰度无效", "app_model_response_invalid"},
            {"App备用片源列表无效", "app_model_response_invalid"},
            {"App备用片源地址无效", "app_model_response_invalid"},
            {"App片源尺寸信息无效", "app_model_response_invalid"},
            {"App片源准备信息无效", "app_model_response_invalid"},
            {"App 片源试看状态无效", "app_model_response_invalid"},
            {"App 单集模型缺少有效片源列表", "app_model_response_invalid"},
            {"App 模型接口返回了无效格式，请稍后重试", "app_model_response_invalid"},
            {"App 片源信息接口返回了无效格式，请稍后重试", "app_model_response_invalid"},
            {"设备请求返回了无效响应", "app_model_response_invalid"},
            {"App 接口响应超过大小限制", "app_model_response_invalid"},
            {"App接口未返回有效数据", "app_empty_response"},
            {"App 模型接口返回空数据，请稍后重试", "app_empty_response"},
            {"App 片源信息接口返回空数据，请稍后重试", "app_empty_response"},
            {"App接口未返回本集片源模型", "app_model_or_source_missing"},
            {"App 接口未返回本集片源模型", "app_model_or_source_missing"},
            {"App 接口未返回本集片源地址", "app_model_or_source_missing"},
            {"App 接口未返回所选分集，已停止取址", "app_model_or_source_missing"},
            {"App接口暂未提供本集播放信息", "app_playback_business_denied"},
            {"App 接口暂未提供本集播放信息", "app_playback_business_denied"},
            {"App接口仅提供本集试看，请在官方App查看", "app_preview_only"},
            {"App 接口仅提供本集试看，请在官方 App 查看", "app_preview_only"},
            {"App片源与分集不一致", "app_source_identity_mismatch"},
            {"App 已登记设备的单集模型与请求不一致", "app_source_identity_mismatch"},
            {"片源与分集不一致", "app_source_identity_mismatch"},
            {"片源与剧集不一致", "app_source_identity_mismatch"},
            {"分集结果不匹配", "app_source_identity_mismatch"},
            {"本集暂未提供兼容的 H.264/HEVC 片源", "app_quality_or_codec_unavailable"},
            {"本集暂无尺寸可确认且满足播放清晰度边界的片源", "app_quality_or_codec_unavailable"},
            {"本集暂无真实尺寸符合播放降档边界的 App 片源", "app_quality_or_codec_unavailable"},
            {"播放片源尺寸未满足所请求边界", "app_quality_or_codec_unavailable"},
            {"App 片源地址格式无效", "app_source_security_validation_failed"},
            {"App 片源返回了不受支持的服务地址", "app_source_security_validation_failed"},
            {"App 接口发生不支持的跳转", "app_source_security_validation_failed"},
            {"App 片源跳转次数过多", "app_source_security_validation_failed"},
            {"App签名请求发生跳转，已停止转发", "app_source_security_validation_failed"},
            {"App片源服务地址无效", "app_source_security_validation_failed"},
            {"App 播放签名不可用", "app_playback_signing_unavailable"},
            {"App 片源网络请求失败，请稍后重试", "app_network_request_failed_unknown_cause"},
            {"App片源服务尚未准备", "app_source_unavailable_unknown_cause"},
            {"片源页面已关闭", "app_source_unavailable_unknown_cause"},
            {"分集正在准备，请稍后重试", "app_source_unavailable_unknown_cause"},
            {"App片源暂未取得", "app_source_unavailable_unknown_cause"},
            {"App 片源暂不可用", "app_source_unavailable_unknown_cause"},
            {"设备网络组件未连接", "app_source_unavailable_unknown_cause"},
            {"App片源请求已取消", "source_request_cancelled"},
            {"App 片源请求已取消", "source_request_cancelled"},
            {"App 分集取源已取消", "source_request_cancelled"},
            {"播放准备已取消", "source_request_cancelled"},
            {"播放恢复已取消", "source_request_cancelled"},
            {"已切换分集", "source_request_cancelled"},
            {"官网取源超时", "network_socket_timeout"},
            {"网络请求超时", "network_socket_timeout"},
            {"App片源服务返回 HTTP 403", "app_source_http_error"},
            {"App片源响应 HTTP 503", "app_source_http_error"},
            {"App 片源请求失败（HTTP 429）", "app_source_http_error"},
            {"本集暂无尺寸可确认且满足 720p 的 App 片源，未降级播放", "app_quality_or_codec_unavailable"},
            {"本集暂无尺寸可确认且满足 1080p 的 H.264/HEVC 片源，未降级", "app_quality_or_codec_unavailable"},
            {"本集暂无尺寸可确认且满足 480p 的片源，未降级。", "app_quality_or_codec_unavailable"}
        };
        for (String[] row : messages) {
            check(row[0], row[1]);
            check("自动恢复未成功：" + row[0] + "；原错误：域名解析失败", row[1]);
            check(row[0] + "，可重试或选择其他分集", row[1]);
            // No substring matching; incidental text, IDs, and URLs cannot become output.
            check("prefix " + row[0], "unknown");
            check(row[0] + " https://synthetic.invalid/?token=SYNTHETIC_SECRET", "unknown");
        }
        check("App接口未返回有效数据（已重取1次仍未返回有效数据，请稍后重试）", "app_empty_response");
        check("App 模型接口返回空数据，请稍后重试（已重取1次仍未返回有效数据，请稍后重试）", "app_empty_response");
        check("自动恢复未成功：App接口未返回有效数据（已重取1次仍未返回有效数据，请稍后重试）；原错误：域名解析失败", "app_empty_response");
        check("App片源登记或取源未完成，请重试（已重取1次仍未返回有效数据，请稍后重试）", "unknown");
        check("unknown（已重取1次仍未返回有效数据，请稍后重试）", "unknown");
        check("App接口未返回有效数据（已重取2次仍未返回有效数据，请稍后重试）", "unknown");
        check("自动恢复未成功：unknown；原错误：App接口暂未提供本集播放信息", "unknown");
        check("App片源服务返回 HTTP 4030", "unknown");
        check("App 片源请求失败（HTTP 99）", "unknown");
        check("App 片源请求失败（HTTP 600）", "unknown");
        check("本集暂无尺寸可确认且满足 2160p 的片源，未降级。", "unknown");
        check("上游设备登记被拒绝: {\"device_id\":\"SYNTHETIC_ID\"}", "unknown");
        check("java.io.IOException: https://synthetic.invalid/?token=SYNTHETIC_SECRET", "unknown");
        check("App片源登记或取源未完成，请重试：HTTP 403", "unknown");
        check("App 片源网络请求失败，请稍后重试：DNS", "unknown");
    }

    private static void check(String text, String expected) {
        need(PlaybackFailureCategory.classify(text).code.equals(expected));
    }

    private static void need(boolean value) {
        checks++;
        if (!value) throw new AssertionError("diagnostic_allowlist_check_failed_" + checks);
    }
}
