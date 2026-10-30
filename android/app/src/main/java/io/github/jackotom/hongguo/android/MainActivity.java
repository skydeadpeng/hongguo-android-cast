package io.github.jackotom.hongguo.android;

import android.app.*;
import android.os.*;
import android.net.Uri;
import android.net.http.SslError;
import android.view.*;
import android.webkit.*;
import android.util.Log;
import android.widget.*;
import android.content.pm.PackageInfo;
import android.content.res.Configuration;
import java.io.*;
import java.net.URLConnection;
import java.util.*;

public final class MainActivity extends Activity {
    static final String ORIGIN = "https://appassets.androidplatform.net";
    private WebView web;
    private FrameLayout webContainer;
    private NativeBridge bridge;
    private OfficialSeriesBridge officialSeries;
    private AppUpdater updates;
    private boolean loaded;
    private final Handler startupHandler = new Handler(Looper.getMainLooper());
    private final StartupPolicy startup = new StartupPolicy();
    private Runnable startupTimeout;
    private LinearLayout startupPanel;
    private boolean softwareRendering;
    private boolean manualDiagnostics;
    private final Runnable accountChanged = () -> {
        try { script("window.__androidAccountChanged && window.__androidAccountChanged(" + new org.json.JSONObject().put("owner", AccountSync.get().ownerStamp()) + ")"); }
        catch (Exception ignored) { script("window.__androidAccountChanged && window.__androidAccountChanged()"); }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        updates = new AppUpdater(this);
        AccountSync.get().setListener(accountChanged);
        softwareRendering = BuildConfig.IS_TV && state != null && state.getBoolean("startupSoftwareRendering", false);
        FrameLayout container = new FrameLayout(this); webContainer = container;
        container.setBackgroundColor(0xff131516);
        if (!BuildConfig.IS_TV) getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        if (Build.VERSION.SDK_INT >= 30) {
            // Inset the native parent, not WebView padding: CSS fixed elements use its viewport.
            getWindow().setDecorFitsSystemWindows(false);
            container.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                int imeBottom = BuildConfig.IS_TV ? 0 : insets.getInsets(WindowInsets.Type.ime()).bottom;
                applyWebInsets(container, bars.left, bars.top, bars.right, bars.bottom, imeBottom);
                return WindowInsets.CONSUMED;
            });
        }
        setContentView(container);
        container.requestApplyInsets();
        PlaybackSources.attach(this);
        startWebView(softwareRendering);
    }
    private void startWebView(boolean useSoftware) {
        cancelStartupTimeout();
        loaded = false;
        destroyWebView();
        softwareRendering = BuildConfig.IS_TV && useSoftware;
        final String token = UUID.randomUUID().toString();
        startup.begin(token);
        showStartupPanel(false);
        try {
            WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG);
            final WebView candidate = new WebView(this);
            web = candidate;
            if (softwareRendering) candidate.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
            candidate.setBackgroundColor(0xff131516);
            candidate.setFocusable(true); candidate.setFocusableInTouchMode(true);
            webContainer.addView(candidate, 0, new FrameLayout.LayoutParams(-1, -1));
            if (BuildConfig.DEBUG) candidate.setWebChromeClient(new WebChromeClient() {
                @Override public boolean onConsoleMessage(ConsoleMessage message) {
                    if (message.messageLevel() != ConsoleMessage.MessageLevel.ERROR) return false;
                    String text = message.message().replaceAll("https?://[^\\s\"'<>]+", "[URL]");
                    text = text.replaceAll("(?i)(token|signature|authorization|cookie)=([^\\s&]+)", "$1=[REDACTED]");
                    Log.e("HongguoWebView", "line=" + message.lineNumber() + " " + text.substring(0, Math.min(300, text.length())));
                    return true;
                }
            });
            WebSettings settings = candidate.getSettings();
            settings.setJavaScriptEnabled(true); settings.setDomStorageEnabled(true);
            settings.setAllowFileAccess(false); settings.setAllowContentAccess(false);
            settings.setAllowFileAccessFromFileURLs(false); settings.setAllowUniversalAccessFromFileURLs(false);
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
            settings.setJavaScriptCanOpenWindowsAutomatically(false); settings.setSupportMultipleWindows(false);
            settings.setMediaPlaybackRequiresUserGesture(true);
            CookieManager.getInstance().setAcceptCookie(false);
            CookieManager.getInstance().setAcceptThirdPartyCookies(candidate, false);
            candidate.setWebViewClient(createWebClient(candidate, token));
            bridge = new NativeBridge(this);
            candidate.addJavascriptInterface(bridge, "AndroidBridge");
            officialSeries = new OfficialSeriesBridge(this);
            candidate.addJavascriptInterface(officialSeries, "AndroidSeries");
            candidate.addJavascriptInterface(updates.new Bridge(), "AndroidUpdates");
            candidate.addJavascriptInterface(new CryptoBridge(), "AndroidCrypto");
            candidate.addJavascriptInterface(new StartupBridge(candidate, token), "AndroidStartup");
            startupTimeout = () -> failStartup(candidate, token, "timeout");
            startupHandler.postDelayed(startupTimeout, StartupPolicy.TIMEOUT_MS);
            candidate.loadUrl(ORIGIN + "/index.html?startup=" + token);
        } catch (RuntimeException failure) {
            failStartup(web, token, "webview_unavailable");
            destroyWebView();
        }
    }
    private WebViewClient createWebClient(final WebView candidate, final String token) {
        return new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) { return !local(request.getUrl()); }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri url = request.getUrl();
                if (!local(url)) return url.getScheme() != null && url.getScheme().equals("https") ? null : error(403);
                if (!request.getMethod().equals("GET")) return error(405);
                String path = url.getPath();
                if (path == null || path.equals("/")) path = "/index.html";
                if (path.contains("..") || path.contains("\\") || path.indexOf('\0') >= 0 || !path.matches("/[a-zA-Z0-9_./@-]+")) return error(403);
                String type = path.endsWith(".js") ? "text/javascript" : path.endsWith(".css") ? "text/css" : path.endsWith(".html") ? "text/html" : URLConnection.guessContentTypeFromName(path);
                if (type == null) type = "application/octet-stream";
                try {
                    Map<String, String> headers = new HashMap<>();
                    headers.put("Content-Security-Policy", "default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' https: data:; font-src 'self'; connect-src 'none'; frame-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'");
                    headers.put("X-Content-Type-Options", "nosniff"); headers.put("Cache-Control", "no-cache");
                    return new WebResourceResponse(type, "UTF-8", 200, "OK", headers, getAssets().open("web" + path));
                } catch (IOException exception) { return error(404); }
            }
            @Override public void onPageFinished(WebView view, String url) {
                // A downloaded document may still contain unsupported JavaScript. Only React's commit acknowledges startup.
                if (currentStartup(candidate, token) && local(Uri.parse(url)) && loaded) view.requestFocus();
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError failure) {
                if (request.isForMainFrame()) failStartup(candidate, token, "page_error");
            }
            @Override public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
                if (request.isForMainFrame() && response.getStatusCode() >= 400) failStartup(candidate, token, "http_error");
            }
            @Override public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError failure) {
                handler.cancel();
                if (failure != null && mainDocument(failure.getUrl(), token)) failStartup(candidate, token, "ssl_error");
            }
            @Override public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                if (candidate == web && startup.matches(token)) {
                    failStartup(candidate, token, "render_process_gone");
                    destroyWebView();
                }
                return true;
            }
        };
    }
    public final class StartupBridge {
        private final WebView candidate;
        private final String token;
        StartupBridge(WebView candidate, String token) { this.candidate = candidate; this.token = token; }
        @JavascriptInterface public void ready(String callbackToken) {
            startupHandler.post(() -> {
                if (!token.equals(callbackToken) || !currentStartup(candidate, token) || !startup.ready(token)) return;
                loaded = true; cancelStartupTimeout(); hideStartupPanel(); candidate.setVisibility(View.VISIBLE); candidate.requestFocus();
                updates.pageReady();
            });
        }
        @JavascriptInterface public void failed(String callbackToken, String code) {
            startupHandler.post(() -> {
                if (!token.equals(callbackToken) || !currentStartup(candidate, token) || !startup.javascriptFailed(token, code)) return;
                loaded = false; cancelStartupTimeout(); showStartupPanel(true);
            });
        }
    }
    private boolean currentStartup(WebView candidate, String token) {
        return !isFinishing() && candidate != null && candidate == web && startup.matches(token) && mainDocument(candidate.getUrl(), token);
    }
    private static boolean mainDocument(String value, String token) {
        if (value == null || token == null) return false;
        Uri uri = Uri.parse(value);
        return local(uri) && "/index.html".equals(uri.getPath()) && token.equals(uri.getQueryParameter("startup"));
    }
    private void failStartup(WebView candidate, String token, String code) {
        if (isFinishing() || candidate != web || !startup.nativeFailed(token, code)) return;
        loaded = false; cancelStartupTimeout(); showStartupPanel(true);
    }
    private void cancelStartupTimeout() {
        if (startupTimeout != null) startupHandler.removeCallbacks(startupTimeout);
        startupTimeout = null;
    }
    private void showStartupPanel(boolean failed) {
        hideStartupPanel();
        if (failed && web != null) web.setVisibility(View.INVISIBLE);
        LinearLayout panel = new LinearLayout(this); startupPanel = panel;
        panel.setClickable(true); panel.setFocusable(false);
        panel.setOrientation(LinearLayout.VERTICAL); panel.setGravity(Gravity.CENTER);
        panel.setPadding(dp(24), dp(18), dp(24), dp(18)); panel.setBackgroundColor(0xff131516);
        panel.addView(startupText(failed ? "首页启动未完成" : "正在启动红果…", 22), new LinearLayout.LayoutParams(-1, -2));
        String detail = failed ? "本地首页未能完成启动。可先重试；软件渲染仅用于尝试兼容电视的网页显示。" : "正在准备本地首页，请稍候。首次启动可能需要一点时间。";
        panel.addView(startupText(detail, 16), new LinearLayout.LayoutParams(-1, -2));
        Button firstAction = null;
        if (failed) {
            panel.addView(startupText("错误代码：" + startup.failure() + "\n" + startupDiagnostics(), 14), new LinearLayout.LayoutParams(-1, -2));
            Button retry = startupButton("重新加载首页", () -> startWebView(softwareRendering));
            firstAction = retry;
            panel.addView(retry, new LinearLayout.LayoutParams(-1, dp(52)));
            if (BuildConfig.IS_TV) panel.addView(startupButton(softwareRendering ? "改用默认渲染重试" : "改用软件渲染重试", () -> startWebView(!softwareRendering)), new LinearLayout.LayoutParams(-1, dp(52)));
            panel.addView(startupButton("退出", () -> finish()), new LinearLayout.LayoutParams(-1, dp(52)));
        }
        webContainer.addView(panel, new FrameLayout.LayoutParams(-1, -1));
        if (firstAction != null) firstAction.requestFocus();
    }
    private void showManualDiagnostics() {
        if (!BuildConfig.IS_TV || isFinishing() || webContainer == null) return;
        hideStartupPanel();
        manualDiagnostics = true;
        if (web != null) web.setVisibility(View.INVISIBLE);
        LinearLayout panel = new LinearLayout(this); startupPanel = panel;
        panel.setClickable(true); panel.setFocusable(false);
        panel.setOrientation(LinearLayout.VERTICAL); panel.setGravity(Gravity.CENTER);
        panel.setPadding(dp(24), dp(18), dp(24), dp(18)); panel.setBackgroundColor(0xff131516);
        panel.addView(startupText("启动与网页显示诊断", 22), new LinearLayout.LayoutParams(-1, -2));
        String state = startup.isReady() ? "首页已完成挂载" : startup.failed() ? "首页启动未完成 · " + startup.failure() : "正在等待首页挂载";
        panel.addView(startupText(state + "\n" + startupDiagnostics(), 14), new LinearLayout.LayoutParams(-1, -2));
        panel.addView(startupText("可尝试切换网页渲染方式；切换仅影响网页显示，收藏和下载记录保留。", 16), new LinearLayout.LayoutParams(-1, -2));
        if (startup.isReady() && web != null) panel.addView(startupButton("继续使用", () -> closeManualDiagnostics()), new LinearLayout.LayoutParams(-1, dp(52)));
        Button rendering = startupButton(softwareRendering ? "改用默认渲染重试" : "改用软件渲染重试", () -> startWebView(!softwareRendering));
        panel.addView(rendering, new LinearLayout.LayoutParams(-1, dp(52)));
        panel.addView(startupButton("重新加载首页", () -> startWebView(softwareRendering)), new LinearLayout.LayoutParams(-1, dp(52)));
        panel.addView(startupButton("退出", () -> finish()), new LinearLayout.LayoutParams(-1, dp(52)));
        webContainer.addView(panel, new FrameLayout.LayoutParams(-1, -1));
        rendering.requestFocus();
    }
    private void closeManualDiagnostics() {
        if (!manualDiagnostics) return;
        if (startup.isReady() && web != null) {
            hideStartupPanel(); web.setVisibility(View.VISIBLE); web.requestFocus();
        } else showStartupPanel(startup.failed());
    }
    private TextView startupText(String value, int size) {
        TextView text = new TextView(this); text.setText(value); text.setTextColor(0xfff2f2f2);
        text.setTextSize(size); text.setGravity(Gravity.CENTER); text.setPadding(0, dp(4), 0, dp(8)); return text;
    }
    private Button startupButton(String label, Runnable action) {
        Button button = new Button(this); button.setText(label); button.setTextSize(16); button.setAllCaps(false);
        button.setFocusable(true); button.setFocusableInTouchMode(true); button.setOnClickListener(view -> action.run()); return button;
    }
    private String startupDiagnostics() {
        String webView = "系统未报告版本";
        try {
            PackageInfo info = WebView.getCurrentWebViewPackage();
            if (info != null) webView = diagnosticText(info.packageName) + " " + diagnosticText(info.versionName);
        } catch (RuntimeException ignored) { webView = "版本读取失败"; }
        return "Android API " + Build.VERSION.SDK_INT + " · WebView " + webView + "\n网页渲染：" + (softwareRendering ? "软件" : "系统默认") + " · App " + diagnosticText(BuildConfig.VERSION_NAME);
    }
    private static String diagnosticText(String value) {
        if (value == null) return "未知";
        String plain = value.replaceAll("[^a-zA-Z0-9._ -]", "_");
        return plain.substring(0, Math.min(90, plain.length()));
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void hideStartupPanel() { if (startupPanel != null && webContainer != null) webContainer.removeView(startupPanel); startupPanel = null; manualDiagnostics = false; }
    private void destroyWebView() {
        if (updates != null) updates.pageUnavailable();
        OfficialSeriesBridge oldSeries = officialSeries; officialSeries = null;
        if (oldSeries != null) oldSeries.close();
        NativeBridge oldBridge = bridge; bridge = null;
        if (oldBridge != null) oldBridge.close();
        WebView oldWeb = web; web = null;
        if (oldWeb != null) {
            try {
                oldWeb.removeJavascriptInterface("AndroidSeries"); oldWeb.removeJavascriptInterface("AndroidBridge"); oldWeb.removeJavascriptInterface("AndroidCrypto"); oldWeb.removeJavascriptInterface("AndroidStartup"); oldWeb.removeJavascriptInterface("AndroidUpdates");
                oldWeb.stopLoading();
            } catch (RuntimeException ignored) { /* A dead renderer may reject operations; removal and destruction still follow. */ }
            finally {
                if (webContainer != null) webContainer.removeView(oldWeb);
                try { oldWeb.destroy(); } catch (RuntimeException ignored) { }
            }
        }
    }
    private void applyWebInsets(FrameLayout container, int left, int top, int right, int bottom, int imeBottom) {
        container.setPadding(left, top, right, MobileLayoutPolicy.webBottomInset(BuildConfig.IS_TV, bottom, imeBottom));
    }
    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        if (!BuildConfig.IS_TV && webContainer != null) webContainer.requestApplyInsets();
    }
    @Override protected void onSaveInstanceState(Bundle state) { state.putBoolean("startupSoftwareRendering", softwareRendering); super.onSaveInstanceState(state); }
    private static boolean local(Uri url) { return "https".equals(url.getScheme()) && "appassets.androidplatform.net".equals(url.getHost()) && url.getPort() == -1 && url.getUserInfo() == null; }
    private static WebResourceResponse error(int status) { return new WebResourceResponse("text/plain", "UTF-8", status, "Blocked", Collections.emptyMap(), new ByteArrayInputStream(new byte[0])); }
    boolean trusted() { return web != null && web.getUrl() != null && local(Uri.parse(web.getUrl())); }
    void script(String source) { runOnUiThread(() -> { if (!isFinishing() && trusted()) web.evaluateJavascript(source, null); }); }
    @Override protected void onResume() { super.onResume(); if (officialSeries != null) officialSeries.resume(); if (updates != null) updates.resume(); AccountSync.get().setListener(accountChanged); AccountSync.get().foreground(); if (web != null) { web.onResume(); if (loaded) script("window.__androidResume && window.__androidResume()"); } }
    @Override protected void onPause() { if (updates != null) updates.pause(); AccountSync.get().flush(); if (web != null) web.onPause(); super.onPause(); }
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (BuildConfig.IS_TV && event.getKeyCode() == KeyEvent.KEYCODE_MENU) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) showManualDiagnostics();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }
    @Override public void onBackPressed() {
        if (manualDiagnostics) { closeManualDiagnostics(); return; }
        if (!startup.isReady() || !trusted()) { confirmExit(); return; }
        web.evaluateJavascript("Boolean(window.__androidBack && window.__androidBack())", result -> { if (!"true".equals(result)) confirmExit(); });
    }
    private void confirmExit() { new AlertDialog.Builder(this).setTitle("退出红果？").setMessage("收藏、观看进度和下载记录会保留在本机。").setNegativeButton("继续使用", null).setPositiveButton("退出", (dialog, which) -> finish()).show(); }
    @Override protected void onDestroy() { if (updates != null) updates.destroy(); AccountSync.get().clearListener(accountChanged); cancelStartupTimeout(); startup.destroy(); PlaybackSources.detach(this); destroyWebView(); super.onDestroy(); }
}
