package io.github.jackotom.hongguo.android;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** One local-document attempt; old callbacks cannot acknowledge another attempt. */
final class StartupPolicy {
    static final long TIMEOUT_MS = 20000;
    private static final Set<String> JS_CODES = new HashSet<>(Arrays.asList("bootstrap_error", "script_error", "unsupported_webview"));
    private static final Set<String> NATIVE_CODES = new HashSet<>(Arrays.asList("page_error", "http_error", "ssl_error", "render_process_gone", "timeout", "webview_unavailable"));
    private String token;
    private String state = "idle";
    private String failure = "";
    void begin(String nextToken) {
        if (nextToken == null || !nextToken.matches("[a-zA-Z0-9-]{16,80}")) throw new IllegalArgumentException("启动标识无效");
        token = nextToken; state = "loading"; failure = "";
    }
    boolean matches(String candidate) { return token != null && token.equals(candidate); }
    boolean ready(String candidate) {
        if (!matches(candidate) || !state.equals("loading")) return false;
        state = "ready"; return true;
    }
    boolean javascriptFailed(String candidate, String code) {
        if (!JS_CODES.contains(code)) return false;
        return fail(candidate, code, false);
    }
    boolean nativeFailed(String candidate, String code) {
        if (!NATIVE_CODES.contains(code)) return false;
        return fail(candidate, code, code.equals("render_process_gone"));
    }
    private boolean fail(String candidate, String code, boolean allowReady) {
        if (!matches(candidate) || !(state.equals("loading") || allowReady && state.equals("ready"))) return false;
        state = "failed"; failure = code; return true;
    }
    void destroy() { token = null; state = "destroyed"; }
    boolean loading() { return state.equals("loading"); }
    boolean isReady() { return state.equals("ready"); }
    boolean failed() { return state.equals("failed"); }
    String failure() { return failure; }
    String token() { return token; }
}
