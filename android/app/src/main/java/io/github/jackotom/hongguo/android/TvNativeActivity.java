package io.github.jackotom.hongguo.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.*;
import org.json.JSONArray;
import org.json.JSONObject;

/** WebView-free TV library. Navigation, category selection and remote focus are separate states. */
public class TvNativeActivity extends Activity {
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(4));
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<JSONObject> items = new ArrayList<>();
    private final List<Button> navButtons = new ArrayList<>();
    private AppUpdater updates;
    private Future<?> loadTask;
    private String mode = "catalog", category = "real-drama", libraryView = "favorites";
    private int page = 1, totalPages = 1, generation;
    private boolean loading, foreground, destroyed, hasResumed, downloadError;
    private String libraryOwnerStamp = "";
    private LinearLayout secondary, stateRow, emptyPanel, pagePanel;
    private FrameLayout content;
    private GridView grid;
    private BaseAdapter adapter;
    private ScrollView pageScroll;
    private Button more, retry, continueWatching;
    private TextView status, pageSummary, updateStatus, cacheStatus;
    private ProgressBar busyIndicator;
    private Button updateDownload, updateInstall, updateCancel, updateShortcut, automaticChecks, checkUpdate;
    private TextView lastChecked;
    private View updateSection;
    private boolean automaticChecksSaving;
    private JSONObject recent;
    private final Runnable heartbeat = new Runnable() {
        @Override public void run() {
            if (!foreground || destroyed) return;
            if (updates != null) { updates.automaticCheck(); refreshUpdateStatus(); }
            if (mode.equals("downloads") && !loading && !downloadError) loadDownloads(false);
            handler.postDelayed(this, 1800);
        }
    };
    private final PlaybackSources.NativeLookup lookup = (sid, vid, quality, ceiling, timeoutMs) ->
        registeredSource().resolve(vid, quality, ceiling == 0 ? null : Integer.valueOf(ceiling));
    /** Fresh anonymous registration per lookup, exactly like the production fallback path. */
    static AppMediaSource registeredSource() {
        return new AppMediaSource(vid -> {
            try (RegisteredPlaybackModel model = new RegisteredPlaybackModel()) {
                return model.fetch(vid);
            } catch (RegisteredPlaybackModel.ModelError error) {
                throw new AppMediaSource.AppSourceException(error.getMessage(), error.code);
            }
        });
    }
    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        TvUi.prepareWindow(this);
        if (saved != null) {
            mode = saved.getString("mode", "catalog");
            category = saved.getString("category", "real-drama");
            libraryView = saved.getString("libraryView", "favorites");
        }
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(TvUi.BACKGROUND);
        LinearLayout header = TvUi.row(this);
        header.setPadding(dp(24), dp(12), dp(24), dp(8));
        TextView brand = TvUi.text(this, "红果", 20, TvUi.TEXT);
        brand.setTypeface(null, android.graphics.Typeface.BOLD);
        header.addView(brand, new LinearLayout.LayoutParams(dp(64), -2));
        String[][] destinations = { { "catalog", "发现" }, { "search", "搜索" }, { "library", "我的追剧" }, { "downloads", "下载" }, { "settings", "设置" } };
        for (String[] destination : destinations) {
            Button button = TvUi.button(this, destination[1]);
            button.setTag(destination[0]);
            button.setTextSize(14); button.setSingleLine(); button.setMinWidth(0);
            button.setPadding(dp(4), dp(6), dp(4), dp(6));
            button.setOnClickListener(view -> {
                if (destination[0].equals("search")) TvPlay.launch(this, new Intent(this, TvSearchActivity.class));
                else if (!mode.equals(destination[0])) select(destination[0], category);
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(40), 1f);
            params.leftMargin = dp(6);
            header.addView(button, params); navButtons.add(button);
        }
        updateShortcut = TvUi.button(this, "有新版本");
        updateShortcut.setTextSize(12); updateShortcut.setSingleLine(); updateShortcut.setSelected(true);
        updateShortcut.setPadding(dp(6), dp(6), dp(6), dp(6));
        updateShortcut.setVisibility(View.GONE);
        updateShortcut.setOnClickListener(view -> openUpdateSettings());
        LinearLayout.LayoutParams shortcutParams = new LinearLayout.LayoutParams(dp(72), dp(40));
        shortcutParams.leftMargin = dp(6); header.addView(updateShortcut, shortcutParams);
        root.addView(header, new LinearLayout.LayoutParams(-1, -2));
        secondary = TvUi.row(this);
        secondary.setPadding(dp(24), 0, dp(24), dp(8));
        root.addView(secondary, new LinearLayout.LayoutParams(-1, -2));
        stateRow = TvUi.row(this);
        stateRow.setPadding(dp(24), 0, dp(24), dp(6));
        busyIndicator = new ProgressBar(this);
        stateRow.addView(busyIndicator, new LinearLayout.LayoutParams(dp(20), dp(20)));
        status = TvUi.text(this, "", 13, TvUi.MUTED);
        status.setMaxLines(2); status.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(0, -2, 1f);
        statusParams.leftMargin = dp(8); stateRow.addView(status, statusParams);
        retry = TvUi.button(this, "重试");
        stateRow.addView(retry, new LinearLayout.LayoutParams(-2, dp(36)));
        root.addView(stateRow, new LinearLayout.LayoutParams(-1, -2));
        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1f));
        grid = new GridView(this);
        int width = getResources().getConfiguration().screenWidthDp;
        grid.setNumColumns(TvUi.posterColumns(this));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setHorizontalSpacing(dp(12)); grid.setVerticalSpacing(dp(14));
        grid.setPadding(dp(24), dp(3), dp(24), dp(14));
        grid.setClipToPadding(false);
        TvUi.configureGrid(grid);
        adapter = new SeriesAdapter(); grid.setAdapter(adapter);
        grid.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= 0 && position < items.size()) openSeries(items.get(position));
        });
        content.addView(grid, new FrameLayout.LayoutParams(-1, -1));
        pageScroll = new ScrollView(this);
        pageScroll.setFillViewport(false);
        pageScroll.setPadding(dp(24), 0, dp(24), dp(16));
        pagePanel = new LinearLayout(this); pagePanel.setOrientation(LinearLayout.VERTICAL);
        pageScroll.addView(pagePanel, new ScrollView.LayoutParams(-1, -2));
        content.addView(pageScroll, new FrameLayout.LayoutParams(-1, -1));
        emptyPanel = new LinearLayout(this);
        emptyPanel.setOrientation(LinearLayout.VERTICAL); emptyPanel.setGravity(Gravity.CENTER);
        emptyPanel.setPadding(dp(28), dp(12), dp(28), dp(16));
        content.addView(emptyPanel, new FrameLayout.LayoutParams(-1, -1));
        more = TvUi.button(this, "加载更多");
        more.setOnClickListener(view -> loadCatalog(true));
        LinearLayout footer = TvUi.row(this); footer.setGravity(Gravity.CENTER);
        footer.setPadding(0, 0, 0, dp(8));
        footer.addView(more, new LinearLayout.LayoutParams(-2, dp(38)));
        root.addView(footer, new LinearLayout.LayoutParams(-1, -2));
        setContentView(root);
        updates = new AppUpdater(this, null); updates.pageReady();
        PlaybackSources.attachNative(lookup);
        select(mode, category);
        navButtons.get(mode.equals("library") ? 2 : mode.equals("downloads") ? 3 : mode.equals("settings") ? 4 : 0).requestFocus();
    }
    private int dp(int value) { return TvUi.dp(this, value); }
    private boolean alive(int ticket) { return !destroyed && !isFinishing() && !isDestroyed() && ticket == generation; }
    private void invalidateLoad() {
        generation++; loading = false;
        if (loadTask != null) loadTask.cancel(true);
        worker.purge();
    }
    private void select(String nextMode, String nextCategory) {
        invalidateLoad(); mode = nextMode; category = nextCategory; page = totalPages = 1; downloadError = false;
        items.clear(); adapter.notifyDataSetChanged(); more.setVisibility(View.GONE);
        pageScroll.setVisibility(View.GONE); emptyPanel.setVisibility(View.GONE); grid.setVisibility(View.VISIBLE);
        pagePanel.removeAllViews(); pageScroll.scrollTo(0, 0);
        state("", false, null); buildSecondary();
        for (Button button : navButtons) button.setSelected(mode.equals(button.getTag()));
        if (mode.equals("catalog")) loadCatalog(false);
        else if (mode.equals("library")) loadLibrary();
        else if (mode.equals("downloads")) loadDownloads(true);
        else showSettings();
    }
    private void buildSecondary() {
        secondary.removeAllViews(); recent = null; continueWatching = null;
        if (mode.equals("catalog")) {
            String[][] categories = { { "real-drama", "真人剧" }, { "comic-drama", "漫剧" }, { "ai-drama", "AI剧" } };
            for (String[] entry : categories) {
                Button button = smallButton(entry[1]); button.setTag(entry[0]); button.setSelected(category.equals(entry[0]));
                button.setOnClickListener(view -> {
                    if (!category.equals(entry[0])) { select("catalog", entry[0]); focusSecondary(entry[0]); }
                });
                addSecondary(button);
            }
            continueWatching = smallButton("继续观看"); continueWatching.setVisibility(View.GONE);
            continueWatching.setOnClickListener(view -> { if (recent != null) openSeries(recent); });
            addSecondary(continueWatching);
        } else if (mode.equals("library")) {
            for (String[] entry : new String[][] { { "favorites", "收藏" }, { "history", "观看历史" } }) {
                Button button = smallButton(entry[1]); button.setTag(entry[0]); button.setSelected(libraryView.equals(entry[0]));
                button.setOnClickListener(view -> {
                    if (libraryView.equals(entry[0])) return;
                    libraryView = entry[0]; invalidateLoad(); items.clear(); adapter.notifyDataSetChanged();
                    emptyPanel.setVisibility(View.GONE); buildSecondary(); focusSecondary(entry[0]); loadLibrary();
                });
                addSecondary(button);
            }
        } else {
            TextView heading = TvUi.text(this, mode.equals("settings") ? "设置" : "下载", 19, TvUi.TEXT);
            heading.setTypeface(null, android.graphics.Typeface.BOLD);
            addSecondary(heading);
        }
        pageSummary = TvUi.text(this, "", 12, TvUi.MUTED);
        LinearLayout.LayoutParams countParams = new LinearLayout.LayoutParams(0, -2, 1f); countParams.leftMargin = dp(8);
        secondary.addView(pageSummary, countParams);
        if (mode.equals("library")) {
            Button account = smallButton("账号");
            account.setOnClickListener(view -> TvPlay.launch(this, new Intent(this, TvAccountActivity.class)));
            addSecondary(account);
        }
        if (!mode.equals("settings")) {
            Button refresh = smallButton("刷新");
            refresh.setOnClickListener(view -> { if (!loading) reload(); }); addSecondary(refresh);
        }
    }
    private void focusSecondary(String tag) {
        View destination = secondary.findViewWithTag(tag);
        if (destination != null) destination.requestFocus();
    }
    private Button smallButton(String label) {
        Button button = TvUi.button(this, label); button.setTextSize(13);
        button.setMinWidth(0); button.setPadding(dp(10), dp(5), dp(10), dp(5));
        return button;
    }
    private void addSecondary(View view) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, dp(36));
        if (secondary.getChildCount() > 0) params.leftMargin = dp(6);
        secondary.addView(view, params);
    }
    private void reload() {
        if (mode.equals("catalog")) { invalidateLoad(); page = totalPages = 1; loadCatalog(false); }
        else if (mode.equals("library")) { invalidateLoad(); loadLibrary(); }
        else if (mode.equals("downloads")) { invalidateLoad(); downloadError = false; loadDownloads(true); }
    }
    private void state(String message, boolean busy, Runnable action) {
        status.setText(message); busyIndicator.setVisibility(busy ? View.VISIBLE : View.GONE);
        retry.setVisibility(action == null ? View.GONE : View.VISIBLE);
        retry.setOnClickListener(action == null ? null : view -> action.run());
        stateRow.setVisibility(message.isEmpty() && !busy && action == null ? View.GONE : View.VISIBLE);
    }
    private void empty(String title, String note, String actionLabel, Runnable action) {
        emptyPanel.removeAllViews();
        TextView heading = TvUi.text(this, title, 20, TvUi.TEXT); heading.setGravity(Gravity.CENTER);
        emptyPanel.addView(heading, new LinearLayout.LayoutParams(-1, -2));
        TextView explanation = TvUi.text(this, note, 14, TvUi.MUTED); explanation.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(-1, -2); textParams.topMargin = dp(8);
        emptyPanel.addView(explanation, textParams);
        if (action != null) {
            Button button = TvUi.button(this, actionLabel); button.setOnClickListener(view -> action.run());
            LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(-2, dp(40)); buttonParams.topMargin = dp(16);
            emptyPanel.addView(button, buttonParams);
        }
        emptyPanel.setVisibility(View.VISIBLE); grid.setVisibility(View.GONE); pageScroll.setVisibility(View.GONE);
    }
    private void submit(int ticket, Runnable action) {
        try { loadTask = worker.submit(action); }
        catch (RejectedExecutionException rejected) { if (alive(ticket)) { loading = false; state("操作正在完成，请重试。", false, this::reload); } }
    }
    private void loadCatalog(boolean append) {
        if (!mode.equals("catalog") || loading || append && page >= totalPages) return;
        final int ticket = ++generation;
        final int target = append ? page + 1 : 1;
        final String targetCategory = category;
        final int selection = grid.getSelectedItemPosition(), first = grid.getFirstVisiblePosition();
        final int previousCount = items.size();
        final boolean focusMore = more.hasFocus();
        loading = true; more.setEnabled(false); more.setText(append ? "加载中…" : "加载更多");
        emptyPanel.setVisibility(View.GONE); grid.setVisibility(View.VISIBLE);
        state(append ? "正在加载下一页…" : "正在读取片单…", true, null);
        submit(ticket, () -> {
            try {
                JSONObject result = SiteCatalog.loadCatalog(targetCategory, target);
                JSONArray rows = result.getJSONArray("items");
                runOnUiThread(() -> {
                    if (!alive(ticket) || !mode.equals("catalog") || !targetCategory.equals(category)) return;
                    loading = false; more.setEnabled(true); more.setText("加载更多");
                    if (!append) items.clear();
                    Set<String> seen = new HashSet<>();
                    for (JSONObject row : items) seen.add(row.optString("seriesId"));
                    for (int i = 0; i < rows.length(); i++) {
                        JSONObject row = rows.optJSONObject(i);
                        if (row != null && seen.add(row.optString("seriesId"))) items.add(row);
                    }
                    page = result.optInt("page", target); totalPages = Math.max(page, result.optInt("totalPages", page));
                    adapter.notifyDataSetChanged();
                    if (append) {
                        grid.setSelection(Math.max(0, focusMore ? Math.min(previousCount, items.size() - 1) : selection >= 0 ? selection : first));
                        if (focusMore && !items.isEmpty()) grid.requestFocus();
                    }
                    more.setVisibility(page < totalPages ? View.VISIBLE : View.GONE);
                    pageSummary.setText("第 " + page + " 页 · " + items.size() + " 部");
                    refreshRecentHistory();
                    state("", false, null);
                    if (items.isEmpty()) empty("这个分类暂时没有短剧", "可以刷新片单，或切换其他分类。", "重新加载", () -> loadCatalog(false));
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (!alive(ticket)) return;
                    loading = false; more.setEnabled(true); more.setText("加载更多");
                    state(TvUi.message(error), false, () -> loadCatalog(append));
                    if (items.isEmpty()) empty("片单读取未完成", "已有记录会保留，请检查网络后重试。", "重新加载", () -> loadCatalog(false));
                });
            }
        });
    }
    private void refreshRecentHistory() {
        if (!mode.equals("catalog") || continueWatching == null) return;
        final int ticket = generation;
        try { worker.execute(() -> {
            try {
                AccountSync.Selection owner = AccountSync.get().captureSelection();
                JSONArray rows = new LibraryStore(this).library().getJSONArray("history");
                AccountSync.get().requireCurrent(owner);
                JSONObject latest = null;
                for (int i = 0; i < rows.length(); i++) {
                    JSONObject row = rows.optJSONObject(i);
                    if (row != null && (latest == null || row.optLong("updatedAt") > latest.optLong("updatedAt"))) latest = row;
                }
                final JSONObject result = latest;
                runOnUiThread(() -> {
                    if (!alive(ticket) || !mode.equals("catalog") || continueWatching == null) return;
                    try { AccountSync.get().requireCurrent(owner); }
                    catch (Exception changed) { recent = null; continueWatching.setVisibility(View.GONE); return; }
                    recent = result;
                    continueWatching.setVisibility(recent == null ? View.GONE : View.VISIBLE);
                    if (recent != null) {
                        continueWatching.setText("继续 第 " + recent.optInt("index", 1) + " 集");
                        continueWatching.setContentDescription("继续观看 " + recent.optString("title") + " 第 " + recent.optInt("index", 1) + " 集");
                    }
                });
            } catch (Exception failure) {
                runOnUiThread(() -> { if (alive(ticket) && continueWatching != null) { recent = null; continueWatching.setVisibility(View.GONE); } });
            }
        }); } catch (RejectedExecutionException ignored) { /* The catalog remains usable without a resume shortcut. */ }
    }
    private void loadLibrary() {
        if (loading || !mode.equals("library")) return;
        final int ticket = ++generation;
        final String view = libraryView;
        final int selection = grid.getSelectedItemPosition(), first = grid.getFirstVisiblePosition();
        loading = true; state("正在读取本机剧库…", true, null);
        submit(ticket, () -> {
            try {
                AccountSync.Selection owner = AccountSync.get().captureSelection();
                String ownerStamp = AccountSync.get().ownerStamp();
                JSONArray rows = new LibraryStore(this).library().getJSONArray(view);
                AccountSync.get().requireCurrent(owner);
                List<JSONObject> display = new ArrayList<>();
                for (int i = 0; i < rows.length(); i++) {
                    JSONObject original = rows.optJSONObject(i); if (original == null) continue;
                    JSONObject row = new JSONObject(original.toString());
                    if (view.equals("history")) row.put("historyLabel", "看到第 " + row.optInt("index", 1) + " 集 · " + time(row.optLong("positionMs")));
                    display.add(row);
                }
                display.sort(Comparator.comparingLong((JSONObject row) -> row.optLong(view.equals("history") ? "updatedAt" : "favoriteAt")).reversed());
                runOnUiThread(() -> {
                    if (!alive(ticket) || !view.equals(libraryView)) return;
                    try { AccountSync.get().requireCurrent(owner); }
                    catch (Exception changed) { loading = false; state(TvUi.message(changed), false, this::loadLibrary); return; }
                    loading = false; libraryOwnerStamp = ownerStamp; items.clear(); items.addAll(display); adapter.notifyDataSetChanged();
                    pageSummary.setText(items.size() + " 部"); state("", false, null);
                    if (items.isEmpty()) empty(view.equals("history") ? "还没有观看记录" : "还没有收藏", view.equals("history") ? "播放后，这里会显示最近观看的短剧与进度。" : "在短剧详情中加入收藏，下次就能在这里找到。", "去发现短剧", () -> select("catalog", category));
                    else { emptyPanel.setVisibility(View.GONE); grid.setVisibility(View.VISIBLE); grid.setSelection(Math.max(0, selection >= 0 ? selection : first)); }
                });
            } catch (Exception error) {
                runOnUiThread(() -> { if (alive(ticket)) { loading = false; state(TvUi.message(error), false, this::loadLibrary); if (items.isEmpty()) empty("剧库读取未完成", TvUi.message(error), "重新读取", this::loadLibrary); } });
            }
        });
    }
    private void loadDownloads(boolean showBusy) {
        if (loading || !mode.equals("downloads")) return;
        final int ticket = ++generation;
        loading = true;
        if (showBusy) state("正在读取下载…", true, null);
        submit(ticket, () -> {
            try {
                JSONArray rows = TvDownloads.rows(this);
                runOnUiThread(() -> {
                    if (!alive(ticket) || !mode.equals("downloads")) return;
                    loading = false; downloadError = false; state("", false, null); pageSummary.setText(rows.length() + " 集");
                    if (rows.length() == 0) { empty("还没有下载", "打开短剧详情，选择一集后使用「下载本集」。本页只展示原生版的本机下载。", "去发现短剧", () -> select("catalog", category)); return; }
                    emptyPanel.setVisibility(View.GONE); grid.setVisibility(View.GONE); pageScroll.setVisibility(View.VISIBLE);
                    renderDownloads(rows);
                });
            } catch (Exception error) {
                runOnUiThread(() -> { if (alive(ticket)) { loading = false; downloadError = true; state(TvUi.message(error), false, () -> loadDownloads(true)); if (pagePanel.getChildCount() == 0) empty("下载读取未完成", TvUi.message(error), "重新读取", () -> loadDownloads(true)); } });
            }
        });
    }
    private void renderDownloads(JSONArray rows) {
        // Update existing buttons in place so the remote focus and scroll position survive polling.
        String focusedId = pagePanel.findFocus() == null ? "" : String.valueOf(pagePanel.findFocus().getTag());
        int scrollY = pageScroll.getScrollY();
        boolean sameRows = pagePanel.getChildCount() == rows.length();
        if (sameRows) for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (!(pagePanel.getChildAt(i) instanceof Button) || !String.valueOf(row == null ? -1 : row.optLong("id")).equals(String.valueOf(pagePanel.getChildAt(i).getTag()))) { sameRows = false; break; }
        }
        if (!sameRows) pagePanel.removeAllViews();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i); if (row == null) continue;
            Button button = sameRows ? (Button) pagePanel.getChildAt(i) : TvUi.button(this, "");
            button.setTag(Long.toString(row.optLong("id")));
            button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL); button.setTextSize(15); button.setAllCaps(false);
            JSONObject series = row.optJSONObject("series");
            String title = series == null ? row.optString("title", "短剧") : series.optString("title", "短剧");
            JSONObject episode = row.optJSONObject("episode"); int index = episode == null ? row.optInt("index", 1) : episode.optInt("index", 1);
            long downloaded = row.optLong("downloadedBytes"), total = row.optLong("totalBytes");
            String progress = total > 0 ? " · " + bytes(downloaded) + " / " + bytes(total) : downloaded > 0 ? " · " + bytes(downloaded) : "";
            button.setText(title + " · 第 " + index + " 集\n" + row.optString("statusLabel", row.optString("status")) + progress);
            button.setContentDescription(button.getText() + "，确认键打开操作");
            button.setOnClickListener(view -> showDownloadActions(row));
            if (!sameRows) {
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.bottomMargin = dp(8);
                pagePanel.addView(button, params);
                if (focusedId.equals(String.valueOf(button.getTag()))) button.requestFocus();
            }
        }
        if (!sameRows) pageScroll.post(() -> pageScroll.scrollTo(0, scrollY));
    }
    private void showDownloadActions(JSONObject row) {
        boolean playable = row.optBoolean("playable");
        new AlertDialog.Builder(this).setTitle(row.optString("title", "下载操作"))
            .setItems(playable ? new String[] { "离线播放", "删除下载" } : new String[] { "取消并删除下载" }, (dialog, which) -> {
                if (playable && which == 0) TvDownloads.play(this, row);
                else new AlertDialog.Builder(this).setTitle("删除这个下载？")
                    .setMessage("将取消任务，并删除本机视频文件和下载记录。收藏和观看历史会保留。")
                    .setNegativeButton("保留", null).setPositiveButton("确认删除", (confirm, id) -> deleteDownload(row.optLong("id", -1))).show();
            }).setNegativeButton("关闭", null).show();
    }
    private void deleteDownload(long id) {
        if (loading) invalidateLoad(); // A local status poll must not swallow a confirmed delete.
        downloadError = false;
        final int ticket = ++generation; loading = true; state("正在删除下载…", true, null);
        submit(ticket, () -> {
            try {
                TvDownloads.delete(this, id);
                runOnUiThread(() -> { if (alive(ticket)) { loading = false; loadDownloads(true); } });
            } catch (Exception error) {
                runOnUiThread(() -> { if (alive(ticket)) { loading = false; downloadError = true; state(TvUi.message(error), false, () -> deleteDownload(id)); } });
            }
        });
    }
    private void openSeries(JSONObject row) {
        String id = row.optString("seriesId");
        if (!id.isEmpty()) TvPlay.launch(this, new Intent(this, TvDetailActivity.class).putExtra("seriesId", id));
    }
    private void showSettings() {
        grid.setVisibility(View.GONE); emptyPanel.setVisibility(View.GONE); pageScroll.setVisibility(View.VISIBLE);
        pagePanel.removeAllViews(); pageScroll.scrollTo(0, 0);
        try {
            JSONObject options = PlayerOptions.read(this);
            section("播放");
            Button quality = settingRow("默认清晰度", qualityLabel(options.optString("quality", "auto")));
            quality.setOnClickListener(view -> chooseQuality(quality));
            Button speed = settingRow("默认倍速", speedLabel(options.optDouble("speed", 1)));
            speed.setOnClickListener(view -> chooseSpeed(speed));
            Button danmaku = settingRow("播放器弹幕", options.optBoolean("danmaku") ? "开启" : "关闭");
            danmaku.setOnClickListener(view -> {
                try { boolean next = !PlayerOptions.read(this).getBoolean("danmaku"); saveOption("danmaku", next, danmaku, next ? "开启" : "关闭"); }
                catch (Exception error) { state(TvUi.message(error), false, null); }
            });
            section("存储");
            cacheStatus = note("正在读取播放缓存…");
            Button clear = settingRow("播放缓存", "清除");
            clear.setOnClickListener(view -> new AlertDialog.Builder(this).setTitle("清除播放缓存？")
                .setMessage("清理临时播放缓存；手动下载的视频、收藏和观看历史会保留。")
                .setNegativeButton("取消", null).setPositiveButton("确认清除", (dialog, which) -> clearCache(clear)).show());
            readCache();
            section("应用更新");
            updateSection = pagePanel.getChildAt(pagePanel.getChildCount() - 1);
            note("启动时与前台定期检查，发现新版后确认下载和安装。");
            automaticChecks = settingRow("自动检查更新", updates.automaticChecksEnabled() ? "开启" : "关闭");
            automaticChecks.setOnClickListener(view -> saveAutomaticChecks(!updates.automaticChecksEnabled()));
            automaticChecks.setEnabled(!automaticChecksSaving);
            lastChecked = note("");
            note("红果原生版 " + BuildConfig.VERSION_NAME + " · 独立安装，数据保存在此应用内");
            updateStatus = note("");
            checkUpdate = settingRow("检查新版本", "检查更新");
            checkUpdate.setOnClickListener(view -> { updates.manualCheck(); refreshUpdateStatus(); });
            updateDownload = settingRow("更新包", "下载更新"); updateDownload.setOnClickListener(view -> { updates.manualDownload(); refreshUpdateStatus(); });
            updateInstall = settingRow("已就绪更新", "安装更新"); updateInstall.setOnClickListener(view -> { updates.manualInstall(); refreshUpdateStatus(); });
            updateCancel = settingRow("进行中的更新", "取消更新"); updateCancel.setOnClickListener(view -> { updates.manualCancel(); refreshUpdateStatus(); });
            refreshUpdateStatus();
        } catch (Exception error) { state(TvUi.message(error), false, () -> select("settings", category)); }
    }
    private void section(String title) {
        TextView heading = TvUi.text(this, title, 17, TvUi.TEXT); heading.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.topMargin = dp(pagePanel.getChildCount() == 0 ? 0 : 16); params.bottomMargin = dp(6);
        pagePanel.addView(heading, params);
    }
    private TextView note(String text) {
        TextView note = TvUi.text(this, text, 13, TvUi.MUTED);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.bottomMargin = dp(8);
        pagePanel.addView(note, params); return note;
    }
    private Button settingRow(String label, String value) {
        LinearLayout row = TvUi.row(this);
        row.setPadding(dp(12), dp(6), dp(8), dp(6)); row.setBackgroundColor(TvUi.SURFACE);
        TextView text = TvUi.text(this, label, 15, TvUi.TEXT);
        row.addView(text, new LinearLayout.LayoutParams(0, -2, 1f));
        Button button = TvUi.button(this, value); button.setTextSize(14); button.setMinWidth(0);
        row.addView(button, new LinearLayout.LayoutParams(-2, dp(40)));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.bottomMargin = dp(4);
        pagePanel.addView(row, params); return button;
    }
    private void chooseQuality(Button button) {
        try {
            String[] values = { "auto", "720p", "1080p" }, labels = { "自动", "720p 及以上", "1080p 及以上" };
            String current = PlayerOptions.read(this).getString("quality"); int selected = 0;
            for (int i = 0; i < values.length; i++) if (values[i].equals(current)) selected = i;
            new AlertDialog.Builder(this).setTitle("默认清晰度").setSingleChoiceItems(labels, selected, (dialog, which) -> { dialog.dismiss(); saveOption("quality", values[which], button, labels[which]); }).setNegativeButton("取消", null).show();
        } catch (Exception error) { state(TvUi.message(error), false, null); }
    }
    private void chooseSpeed(Button button) {
        try {
            double[] values = { .75, 1, 1.25, 1.5, 2 }; String[] labels = { "0.75×", "1×", "1.25×", "1.5×", "2×" };
            double current = PlayerOptions.read(this).getDouble("speed"); int selected = 1;
            for (int i = 0; i < values.length; i++) if (Math.abs(values[i] - current) < .001) selected = i;
            new AlertDialog.Builder(this).setTitle("默认倍速").setSingleChoiceItems(labels, selected, (dialog, which) -> { dialog.dismiss(); saveOption("speed", values[which], button, labels[which]); }).setNegativeButton("取消", null).show();
        } catch (Exception error) { state(TvUi.message(error), false, null); }
    }
    private void saveOption(String key, Object value, Button button, String label) {
        if (!button.isEnabled()) return;
        final int ticket = generation; button.setEnabled(false);
        try {
            worker.execute(() -> {
                try {
                    PlayerOptions.update(this, new JSONObject().put(key, value));
                    runOnUiThread(() -> { if (alive(ticket)) { button.setText(label); button.setEnabled(true); state("设置已保存", false, null); } });
                } catch (Exception error) { runOnUiThread(() -> { if (alive(ticket)) { button.setEnabled(true); state(TvUi.message(error), false, null); } }); }
            });
        } catch (RejectedExecutionException rejected) { button.setEnabled(true); state("设置正在保存，请稍后重试。", false, null); }
    }
    private void readCache() {
        final int ticket = generation;
        try { worker.execute(() -> {
            try {
                JSONObject cache = PlaybackCache.get(this).status();
                runOnUiThread(() -> { if (alive(ticket) && cacheStatus != null) cacheStatus.setText(cache.optInt("files") + " 个临时文件 · " + bytes(cache.optLong("bytes")) + "，不含手动下载"); });
            } catch (Exception error) { runOnUiThread(() -> { if (alive(ticket) && cacheStatus != null) cacheStatus.setText(TvUi.message(error)); }); }
        }); } catch (RejectedExecutionException ignored) { if (cacheStatus != null) cacheStatus.setText("缓存读取正忙，可稍后重新进入设置。"); }
    }
    private void clearCache(Button button) {
        final int ticket = generation; button.setEnabled(false); state("正在清理播放缓存…", true, null);
        try { worker.execute(() -> {
            try {
                JSONObject result = PlaybackCache.get(this).clear();
                runOnUiThread(() -> { if (alive(ticket)) { button.setEnabled(true); state("已清理 " + result.optInt("removed") + " 个临时文件；手动下载保持不变。", false, null); readCache(); } });
            } catch (Exception error) { runOnUiThread(() -> { if (alive(ticket)) { button.setEnabled(true); state(TvUi.message(error), false, () -> clearCache(button)); } }); }
        }); } catch (RejectedExecutionException rejected) { button.setEnabled(true); state("缓存操作正忙，请重试。", false, () -> clearCache(button)); }
    }
    private void openUpdateSettings() {
        if (!mode.equals("settings")) select("settings", category);
        pageScroll.post(() -> {
            if (destroyed || !mode.equals("settings") || updateSection == null) return;
            pageScroll.smoothScrollTo(0, Math.max(0, updateSection.getTop() - dp(8)));
            Button target = updateInstall != null && ((View) updateInstall.getParent()).getVisibility() == View.VISIBLE ? updateInstall
                : updateDownload != null && ((View) updateDownload.getParent()).getVisibility() == View.VISIBLE ? updateDownload : checkUpdate;
            if (target != null) target.requestFocus();
        });
    }
    /** Persist off the UI thread and keep the last saved value visible after a failed commit. */
    private void saveAutomaticChecks(boolean enabled) {
        if (updates == null || automaticChecksSaving) return;
        final int ticket = generation;
        automaticChecksSaving = true; refreshUpdateStatus();
        state("正在保存自动检查设置…", true, null);
        try { worker.execute(() -> {
            try {
                updates.setAutomaticChecks(enabled);
                runOnUiThread(() -> {
                    automaticChecksSaving = false;
                    if (destroyed) return;
                    refreshUpdateStatus();
                    if (alive(ticket)) state(enabled ? "自动检查更新已开启" : "自动检查更新已关闭，仍可手动检查", false, null);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    automaticChecksSaving = false;
                    if (destroyed) return;
                    refreshUpdateStatus();
                    if (alive(ticket)) state(TvUi.message(error), false, () -> saveAutomaticChecks(enabled));
                });
            }
        }); } catch (RejectedExecutionException busy) {
            automaticChecksSaving = false; refreshUpdateStatus();
            state("设置正在保存，请稍后重试。", false, () -> saveAutomaticChecks(enabled));
        }
    }
    private void refreshUpdateStatus() {
        if (updates == null) return;
        JSONObject snapshot = updates.snapshotJson(); if (snapshot == null) return;
        String state = snapshot.optString("state");
        boolean available = state.equals("available"), ready = state.equals("ready");
        if (updateShortcut != null) {
            boolean heldFocus = updateShortcut.hasFocus();
            updateShortcut.setText(ready ? "安装更新" : "有新版本");
            updateShortcut.setContentDescription((ready ? "更新已就绪" : "发现新版本 " + snapshot.optString("versionName")) + "，打开应用更新");
            updateShortcut.setVisibility(available || ready ? View.VISIBLE : View.GONE);
            if (heldFocus && !available && !ready) navButtons.get(4).requestFocus();
        }
        if (updateStatus == null || !mode.equals("settings")) return;
        if (automaticChecks != null) {
            automaticChecks.setText(automaticChecksSaving ? "保存中…" : snapshot.optBoolean("automaticChecks", true) ? "开启" : "关闭");
            automaticChecks.setEnabled(!automaticChecksSaving);
        }
        if (lastChecked != null) {
            long checked = snapshot.optLong("lastCheckedAt");
            String label = "尚未检查";
            if (checked > 0) {
                java.util.Date date = new java.util.Date(checked);
                label = android.text.format.DateFormat.getDateFormat(this).format(date) + " " + android.text.format.DateFormat.getTimeFormat(this).format(date);
            }
            lastChecked.setText("最近检查：" + label);
        }
        String message = snapshot.optString("message");
        long downloaded = snapshot.optLong("downloaded"), total = snapshot.optLong("size");
        if (state.equals("downloading") && total > 0) message += " · " + Math.min(100, downloaded * 100 / total) + "%";
        if (snapshot.has("versionName") && !state.equals("idle")) message += "\n新版本 " + snapshot.optString("versionName") + " · " + bytes(total);
        if (snapshot.optString("notes").length() > 0) message += "\n" + snapshot.optString("notes");
        updateStatus.setText(message.isEmpty() ? "可检查是否有新版本。" : message);
        if (updateDownload != null) ((View) updateDownload.getParent()).setVisibility(available || state.equals("error") && snapshot.has("versionName") ? View.VISIBLE : View.GONE);
        if (updateInstall != null) ((View) updateInstall.getParent()).setVisibility(ready ? View.VISIBLE : View.GONE);
        if (updateCancel != null) ((View) updateCancel.getParent()).setVisibility(snapshot.optBoolean("busy") && !state.equals("checking") ? View.VISIBLE : View.GONE);
    }
    private static String qualityLabel(String quality) { return quality.equals("1080p") ? "1080p 及以上" : quality.equals("720p") ? "720p 及以上" : "自动"; }
    private static String speedLabel(double speed) { return String.format(Locale.ROOT, "%s×", speed == Math.rint(speed) ? Integer.toString((int) speed) : Double.toString(speed)); }
    private static String bytes(long value) { return value >= 1024 * 1024 * 1024L ? String.format(Locale.ROOT, "%.1f GB", value / (1024d * 1024 * 1024)) : value >= 1024 * 1024 ? String.format(Locale.ROOT, "%.1f MB", value / (1024d * 1024)) : Math.max(0, value / 1024) + " KB"; }
    private static String time(long ms) { long seconds = Math.max(0, ms / 1000); return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60); }
    private final class SeriesAdapter extends BaseAdapter {
        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int position) { return items.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View convert, ViewGroup parent) { return TvUi.seriesCard(TvNativeActivity.this, items.get(position), convert); }
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        out.putString("mode", mode); out.putString("category", category); out.putString("libraryView", libraryView);
        super.onSaveInstanceState(out);
    }
    @Override protected void onResume() {
        super.onResume(); foreground = true; AccountSync.get().foreground();
        if (updates != null) { updates.resume(); refreshUpdateStatus(); }
        handler.removeCallbacks(heartbeat); handler.postDelayed(heartbeat, 1800);
        if (hasResumed && mode.equals("library")) {
            invalidateLoad();
            if (!libraryOwnerStamp.equals(AccountSync.get().ownerStamp())) {
                items.clear(); adapter.notifyDataSetChanged(); emptyPanel.setVisibility(View.GONE);
            }
            loadLibrary();
        } else if (mode.equals("downloads") && !loading) loadDownloads(true);
        else if (mode.equals("settings")) refreshUpdateStatus();
        else if (mode.equals("catalog") && !loading) refreshRecentHistory();
        hasResumed = true;
    }
    @Override protected void onPause() {
        foreground = false; handler.removeCallbacks(heartbeat);
        if (updates != null) updates.pause(); AccountSync.get().flush(); super.onPause();
    }
    @Override public void onBackPressed() {
        if (!mode.equals("catalog")) { select("catalog", category); navButtons.get(0).requestFocus(); return; }
        if (grid.hasFocus() || more.hasFocus()) { navButtons.get(0).requestFocus(); return; }
        super.onBackPressed();
    }
    @Override protected void onDestroy() {
        destroyed = true; invalidateLoad(); handler.removeCallbacksAndMessages(null);
        PlaybackSources.detachNative(lookup); if (updates != null) updates.destroy();
        worker.shutdownNow(); super.onDestroy();
    }
}
