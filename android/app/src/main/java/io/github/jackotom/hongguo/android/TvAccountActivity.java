package io.github.jackotom.hongguo.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.content.Context;
import android.widget.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import org.json.JSONObject;

/** Native front end of the same official account coordinator used by the WebView. */
public final class TvAccountActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Button> actions = new ArrayList<>();
    private LinearLayout form;
    private TextView status, error;
    private Button sync;
    private String rendered = "", action = "", localMessage = "", webPhase = "idle";
    private JSONObject snapshot;
    private ImageView qrView;
    private TextView qrStatus;
    private Bitmap qrImage;
    private volatile boolean qrActive, qrPolling;
    private volatile boolean visible;
    private Future<?> currentTask;
    private volatile int generation;
    private final Runnable changed = () -> main.post(this::refresh);
    private final Runnable tick = new Runnable() {
        @Override public void run() { if (visible) { refresh(); main.postDelayed(this, 1000); } }
    };
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state); TvUi.prepareWindow(this);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(TvUi.BACKGROUND);
        root.setPadding(TvUi.dp(this, 28), TvUi.dp(this, 16), TvUi.dp(this, 28), TvUi.dp(this, 16));
        LinearLayout heading = TvUi.row(this); Button back = TvUi.button(this, "返回"); back.setOnClickListener(v -> finish()); heading.addView(back);
        TextView title = TvUi.text(this, "红果账号", 22, TvUi.TEXT); title.setPadding(TvUi.dp(this, 16), 0, 0, 0); heading.addView(title); root.addView(heading);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(false);
        LinearLayout content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); content.setPadding(0, TvUi.dp(this, 12), 0, 0);
        status = TvUi.text(this, "正在读取账号…", 16, TvUi.TEXT); content.addView(status);
        TextView note = TvUi.text(this, "未登录也可观看。登录红果官方账号后同步收藏、历史与进度；本机记录仅在确认导入后合并。", 13, TvUi.MUTED);
        note.setPadding(0, TvUi.dp(this, 8), 0, TvUi.dp(this, 8)); content.addView(note);
        error = TvUi.text(this, "", 14, 0xffff9699); content.addView(error);
        form = new LinearLayout(this); form.setOrientation(LinearLayout.VERTICAL); content.addView(form);
        scroll.addView(content); root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1)); setContentView(root);
    }
    private boolean alive() { return visible && !isFinishing() && !isDestroyed(); }
    private void refresh() {
        if (!alive()) return;
        try {
            snapshot = AccountSync.get().getAccount();
            // The website QR session is a first-class login; the SMS session may coexist.
            JSONObject web = snapshot.optJSONObject("web");
            boolean webAuth = web != null && web.optBoolean("authenticated");
            boolean authenticated = snapshot.optBoolean("authenticated");
            boolean signed = authenticated || webAuth;
            webPhase = web == null ? "idle" : web.optString("phase", "idle");
            if (webAuth) { qrActive = false; qrImage = null; }
            String key = (signed ? "signed" : "qr") + "|" + (webAuth ? "1" : "0") + (qrActive ? "1" : "0") + webPhase;
            if (!key.equals(rendered)) { rendered = key; renderForm(signed, webAuth); }
            JSONObject cloud = snapshot.optJSONObject("sync");
            String message = signed ? "已登录 · " + (authenticated ? snapshot.optJSONObject("user").optString("name", "红果用户") : web.optString("user", "{}").isEmpty() ? "红果用户" : snapshot.optJSONObject("web").optJSONObject("user").optString("name", "红果用户"))
                : qrActive ? "等待扫码" : "未登录 · 扫码登录后同步收藏与进度";
            if (!action.isEmpty() || snapshot.optBoolean("busy")) message = "正在处理账号请求…";
            else if (signed && cloud != null) message += cloud.optBoolean("syncing") ? " · 正在同步…" : " · 待同步 " + cloud.optInt("pendingCount") + " 条";
            status.setText(message);
            String problem = snapshot.optString("error", ""); if (problem.isEmpty() && cloud != null) problem = cloud.optString("error", "");
            error.setText(problem.isEmpty() ? localMessage : problem);
            boolean busy = !action.isEmpty() || snapshot.optBoolean("busy");
            for (Button button : actions) button.setEnabled(!busy);
            if (sync != null && cloud != null && cloud.optBoolean("syncing")) sync.setEnabled(false);
            if (qrStatus != null) qrStatus.setText(webPhase.equals("scanned") ? "已扫描，请在手机红果 App 中确认登录" : "等待扫码（" + (web == null ? 0 : web.optInt("expiresIn")) + " 秒后过期）…");
        } catch (Exception failure) { error.setText(TvUi.message(failure)); }
    }
    private void showMessage(String message) { localMessage = message; error.setText(message); }
    private Button addAction(String label, Runnable run) {
        Button button = TvUi.button(this, label); button.setOnClickListener(v -> run.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2); params.topMargin = TvUi.dp(this, 10);
        form.addView(button, params); actions.add(button); return button;
    }
    private void renderForm(boolean authenticated, boolean webAuth) {
        form.removeAllViews(); actions.clear(); sync = null; qrView = null; qrStatus = null;
        if (authenticated) {
            sync = addAction("立即同步", () -> perform("sync", null));
            addAction("导入本机记录", () -> confirm("导入本机记录？", "将本应用登录前的收藏、历史与进度合并到当前账号，并进行云端同步；原本机记录保留。", "import"));
            // The logout lane follows the session that actually holds the login.
            if (authenticated && !webAuth) addAction("退出账号", () -> confirm("退出红果账号？", "退出后显示登录前的本机记录，当前账号的本地记录与待同步操作保留。", "logout"));
        }
        // The website QR login is the only entry: no SMS code, no extra verification.
        form.addView(TvUi.text(this, "扫码登录", 16, TvUi.TEXT));
        if (webAuth) {
            JSONObject user = snapshot == null ? null : snapshot.optJSONObject("web");
            JSONObject account = user == null ? null : user.optJSONObject("user");
            form.addView(TvUi.text(this, "已通过官网扫码登录：" + (account == null ? "红果用户" : account.optString("name", "红果用户") + " " + account.optString("idSuffix")), 14, TvUi.TEXT));
            addAction("退出官网账号", () -> perform("webLogout", null));
        } else if (qrActive) {
            qrView = new ImageView(this); qrView.setImageBitmap(qrImage); qrView.setContentDescription("红果官网登录二维码");
            LinearLayout.LayoutParams image = new LinearLayout.LayoutParams(TvUi.dp(this, 320), TvUi.dp(this, 320)); image.topMargin = TvUi.dp(this, 8);
            form.addView(qrView, image);
            qrStatus = TvUi.text(this, "等待扫码…", 14, TvUi.TEXT); form.addView(qrStatus);
            addAction("取消扫码", () -> perform("webCancel", null));
        } else {
            form.addView(TvUi.text(this, "用手机红果 App 扫描二维码并在手机上确认即可登录官方账号。", 13, TvUi.MUTED));
            addAction("获取二维码", () -> perform("webQrcode", null));
        }
        addAction("重新读取状态", this::refresh);
    }
    private void confirm(String title, String text, String method) {
        new AlertDialog.Builder(this).setTitle(title).setMessage(text).setNegativeButton("取消", null)
            .setPositiveButton("确认", (d, w) -> perform(method, null)).show();
    }
    private void perform(String method, JSONObject params) {
        if (!action.isEmpty()) return;
        action = method; showMessage(""); int ticket = ++generation;
        InputMethodManager keyboard = (InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(form.getWindowToken(), 0);
        refresh();
        currentTask = worker.submit(() -> {
            if (!visible || ticket != generation) return;
            String failure = ""; JSONObject result = null;
            try {
                switch (method) {
                    case "sync": result = AccountSync.get().syncNow(); break;
                    case "import": result = AccountSync.get().importGuest(); break;
                    case "logout": result = AccountSync.get().logout(); break;
                    case "webQrcode": result = AccountSync.get().webQrcode(); break;
                    case "webCancel": result = AccountSync.get().webCancel(); break;
                    case "webLogout": result = AccountSync.get().webLogout(); break;
                }
            } catch (Exception problem) { failure = TvUi.message(problem); }
            final String message = failure; final JSONObject response = result;
            main.post(() -> {
                if (!alive() || ticket != generation) return;
                action = ""; refresh();
                if (!message.isEmpty()) showMessage(message);
                else if (method.equals("import") && response != null && response.optJSONObject("importResult") != null) {
                    JSONObject count = response.optJSONObject("importResult");
                    showMessage("已导入收藏 " + count.optInt("favoritesImportedCount") + " 条、观看历史 " + count.optInt("historyImportedCount") + " 条");
                }
                if (method.equals("webQrcode")) {
                    Bitmap image = message.isEmpty() && response != null ? qrBitmap(response.optString("image")) : null;
                    if (image != null) { qrImage = image; qrActive = true; refresh(); startQrPolling(); }
                    else if (message.isEmpty()) showMessage("二维码获取未完成，请重试");
                } else if (method.equals("webCancel") || method.equals("webLogout")) { qrActive = false; qrImage = null; }
            });
        });
    }
    /** Polls the scan state on the worker while a QR is on screen; expiry and errors stop the loop. */
    private void startQrPolling() {
        if (qrPolling) return;
        qrPolling = true;
        worker.submit(() -> {
            while (qrActive && alive()) {
                try { AccountSync.get().webPoll(); }
                catch (Exception problem) { String text = TvUi.message(problem); main.post(() -> { if (alive()) showMessage(text); }); qrActive = false; }
                main.post(this::refresh);
                if (!qrActive || !alive()) break;
                try { Thread.sleep(2500); } catch (InterruptedException stopped) { break; }
            }
            qrPolling = false;
        });
    }
    private static Bitmap qrBitmap(String dataUrl) {
        int at = dataUrl == null ? -1 : dataUrl.lastIndexOf("base64,");
        if (at < 0 || dataUrl.length() - at > 1024 * 1024) return null;
        try { byte[] bytes = Base64.decode(dataUrl.substring(at + 7), Base64.DEFAULT); return BitmapFactory.decodeByteArray(bytes, 0, bytes.length); }
        catch (Exception invalid) { return null; }
    }
    private void cancel() {
        ++generation; action = ""; if (currentTask != null) currentTask.cancel(true);
        try { AccountSync.get().webCancel(); showMessage(""); rendered = ""; refresh(); }
        catch (Exception problem) { showMessage(TvUi.message(problem)); }
    }
    @Override protected void onResume() {
        super.onResume(); visible = true; TvUi.prepareWindow(this); AccountSync.get().foreground();
        AccountSync.get().setListener(changed); main.post(tick);
    }
    @Override protected void onPause() {
        visible = false; ++generation; action = ""; qrActive = false; if (currentTask != null) currentTask.cancel(true); main.removeCallbacks(tick); AccountSync.get().clearListener(changed);
        AccountSync.get().flush(); super.onPause();
    }
    @Override protected void onDestroy() { worker.shutdownNow(); main.removeCallbacksAndMessages(null); super.onDestroy(); }
}
