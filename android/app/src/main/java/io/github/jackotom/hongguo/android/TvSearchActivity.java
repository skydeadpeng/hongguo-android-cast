package io.github.jackotom.hongguo.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Parcelable;
import android.text.InputFilter;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import org.json.JSONArray;
import org.json.JSONObject;

/** Native TV search. Each query owns its cursor, deduplication set and generation. */
public class TvSearchActivity extends Activity {
    // A cancelled transport may take a moment to exit. The second worker lets a new
    // query reach the transport without waiting for an obsolete UI operation.
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1));
    private final ExecutorService historyWorker = Executors.newSingleThreadExecutor();
    private final List<JSONObject> items = new ArrayList<>();
    private final Set<String> seen = new HashSet<>();
    private EditText input;
    private GridView grid;
    private BaseAdapter adapter;
    private Button submit, more, retry, historyToggle, clearHistory;
    private TextView heading, status, historyNote;
    private LinearLayout historyRow, historyPanel, footer;
    private String keyword = "";
    private JSONObject cursor;
    private boolean hasMore, limitedResults, generationBusy, historyOpen = true, returningFromDetail;
    private boolean historyBusy;
    private volatile int generation;
    private int historyGeneration, selectedPosition;
    private Future<?> currentRequest;
    private Runnable retryAction;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        TvUi.prepareWindow(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(TvUi.BACKGROUND);
        root.setPadding(TvUi.dp(this, 24), TvUi.dp(this, 12), TvUi.dp(this, 24), TvUi.dp(this, 12));

        LinearLayout bar = TvUi.row(this);
        Button back = TvUi.button(this, "返回");
        back.setContentDescription("返回上一页");
        back.setOnClickListener(view -> finish());
        bar.addView(back, new LinearLayout.LayoutParams(-2, -2));
        input = new EditText(this);
        input.setTextSize(16); input.setTextColor(TvUi.TEXT); input.setHintTextColor(TvUi.MUTED);
        input.setHint("输入剧名搜索"); input.setContentDescription("搜索剧名"); input.setSingleLine();
        input.setFilters(new InputFilter[]{ new InputFilter.LengthFilter(100) });
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        input.setOnEditorActionListener((view, action, event) -> {
            if (action == EditorInfo.IME_ACTION_SEARCH) { search(); return true; }
            return false;
        });
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(0, TvUi.dp(this, 42), 1f);
        inputParams.leftMargin = TvUi.dp(this, 12); inputParams.rightMargin = TvUi.dp(this, 12);
        bar.addView(input, inputParams);
        submit = TvUi.button(this, "搜索");
        submit.setOnClickListener(view -> search());
        bar.addView(submit, new LinearLayout.LayoutParams(-2, -2));
        root.addView(bar, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout headingRow = TvUi.row(this);
        headingRow.setPadding(0, TvUi.dp(this, 8), 0, 0);
        heading = TvUi.text(this, "搜索短剧", 20, TvUi.TEXT);
        heading.setMaxLines(1); heading.setEllipsize(TextUtils.TruncateAt.END);
        headingRow.addView(heading, new LinearLayout.LayoutParams(0, -2, 1f));
        historyToggle = TvUi.button(this, "收起历史"); historyToggle.setTextSize(13);
        historyToggle.setOnClickListener(view -> { historyOpen = !historyOpen; updateHistoryVisibility(); });
        headingRow.addView(historyToggle, new LinearLayout.LayoutParams(-2, -2));
        root.addView(headingRow, new LinearLayout.LayoutParams(-1, -2));

        historyPanel = new LinearLayout(this);
        historyPanel.setOrientation(LinearLayout.VERTICAL);
        LinearLayout recent = TvUi.row(this);
        HorizontalScrollView historyScroll = new HorizontalScrollView(this);
        historyScroll.setHorizontalScrollBarEnabled(false);
        historyRow = TvUi.row(this);
        historyScroll.addView(historyRow, new HorizontalScrollView.LayoutParams(-2, -2));
        recent.addView(historyScroll, new LinearLayout.LayoutParams(0, -2, 1f));
        clearHistory = TvUi.button(this, "清空"); clearHistory.setTextSize(13);
        clearHistory.setOnClickListener(view -> new AlertDialog.Builder(this)
            .setTitle("清空最近搜索？").setMessage("只清空这台设备上的搜索记录，收藏和观看记录会保留。")
            .setNegativeButton("取消", null).setPositiveButton("确认清空", (dialog, which) -> changeHistory(null, true)).show());
        recent.addView(clearHistory, new LinearLayout.LayoutParams(-2, -2));
        historyPanel.addView(recent, new LinearLayout.LayoutParams(-1, -2));
        historyNote = TvUi.text(this, "最近搜索 · 长按关键词可删除单条", 11, TvUi.MUTED);
        historyPanel.addView(historyNote, new LinearLayout.LayoutParams(-1, -2));
        root.addView(historyPanel, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout stateRow = TvUi.row(this);
        stateRow.setPadding(0, TvUi.dp(this, 4), 0, TvUi.dp(this, 2));
        status = TvUi.text(this, "输入剧名，或选择最近搜索。", 13, TvUi.MUTED);
        status.setMaxLines(2); status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        stateRow.addView(status, new LinearLayout.LayoutParams(0, -2, 1f));
        retry = TvUi.button(this, "重试"); retry.setTextSize(13); retry.setVisibility(View.GONE);
        retry.setOnClickListener(view -> { if (retryAction != null) retryAction.run(); });
        stateRow.addView(retry, new LinearLayout.LayoutParams(-2, -2));
        root.addView(stateRow, new LinearLayout.LayoutParams(-1, -2));

        grid = new GridView(this);
        int columns = TvUi.posterColumns(this);
        grid.setNumColumns(columns); grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setHorizontalSpacing(TvUi.dp(this, 12)); grid.setVerticalSpacing(TvUi.dp(this, 12));
        grid.setPadding(TvUi.dp(this, 3), TvUi.dp(this, 4), TvUi.dp(this, 3), TvUi.dp(this, 4));
        TvUi.configureGrid(grid);
        adapter = new ResultAdapter(); grid.setAdapter(adapter);
        grid.setOnItemClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= items.size()) return;
            String seriesId = items.get(position).optString("seriesId", "");
            if (seriesId.isEmpty()) return;
            selectedPosition = position; returningFromDetail = true;
            TvPlay.launch(this, new Intent(this, TvDetailActivity.class).putExtra("seriesId", seriesId));
        });
        root.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1f));
        footer = TvUi.row(this); footer.setGravity(Gravity.CENTER); footer.setVisibility(View.GONE);
        more = TvUi.button(this, "加载更多"); more.setOnClickListener(view -> searchMore());
        footer.addView(more, new LinearLayout.LayoutParams(-2, -2));
        root.addView(footer, new LinearLayout.LayoutParams(-1, -2));
        setContentView(root);
        if (state != null) restore(state);
        updateHistoryVisibility(); loadHistory();
        // D-pad users enter with a visible control focused; opening the keyboard
        // remains an explicit action rather than covering the initial history.
        if (state == null) submit.requestFocus();
    }

    private boolean alive() { return !isFinishing() && !isDestroyed(); }
    private void updateHistoryVisibility() {
        historyPanel.setVisibility(historyOpen ? View.VISIBLE : View.GONE);
        historyToggle.setText(historyOpen ? "收起历史" : "最近搜索");
        historyToggle.setSelected(historyOpen);
    }
    private void loadHistory() {
        final int ticket = ++historyGeneration;
        historyWorker.execute(() -> {
            try {
                JSONArray keywords = new LibraryStore(getApplicationContext()).searchHistory().getJSONArray("keywords");
                runOnUiThread(() -> { if (alive() && ticket == historyGeneration) renderHistory(keywords, ""); });
            } catch (Exception error) {
                runOnUiThread(() -> { if (alive() && ticket == historyGeneration) {
                    historyNote.setText("搜索历史读取失败；展开历史后可重新读取。");
                    historyToggle.setOnClickListener(view -> { historyOpen = true; updateHistoryVisibility(); loadHistory(); });
                } });
            }
        });
    }
    private void renderHistory(JSONArray keywords, String note) {
        historyRow.removeAllViews();
        historyToggle.setOnClickListener(view -> { historyOpen = !historyOpen; updateHistoryVisibility(); });
        for (int i = 0; i < keywords.length() && i < 20; i++) {
            String value = keywords.optString(i);
            if (value.isEmpty()) continue;
            Button chip = TvUi.button(this, value); chip.setTextSize(13);
            chip.setContentDescription("搜索：" + value + "。长按可删除这条搜索历史");
            chip.setOnClickListener(view -> { input.setText(value); search(); });
            chip.setOnLongClickListener(view -> {
                new AlertDialog.Builder(this).setTitle("删除这条搜索记录？").setMessage(value)
                    .setNegativeButton("取消", null).setPositiveButton("删除", (dialog, which) -> changeHistory(value, false)).show();
                return true;
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
            params.rightMargin = TvUi.dp(this, 8); historyRow.addView(chip, params);
        }
        if (historyRow.getChildCount() == 0) historyRow.addView(TvUi.text(this, "还没有搜索记录", 13, TvUi.MUTED));
        clearHistory.setEnabled(!historyBusy && keywords.length() > 0);
        historyNote.setText(!note.isEmpty() ? note : "最近搜索 · 长按关键词可删除单条");
    }
    private void changeHistory(String value, boolean clear) {
        if (historyBusy) return;
        historyBusy = true; clearHistory.setEnabled(false);
        final int ticket = ++historyGeneration;
        historyWorker.execute(() -> {
            try {
                LibraryStore store = new LibraryStore(getApplicationContext());
                JSONObject result = clear ? store.clearSearchHistory() : store.removeSearchHistory(new JSONObject().put("keyword", value));
                JSONArray keywords = result.getJSONArray("keywords");
                runOnUiThread(() -> { if (alive()) {
                    historyBusy = false; clearHistory.setEnabled(true);
                    if (ticket == historyGeneration) renderHistory(keywords, clear ? "搜索记录已清空" : "搜索记录已删除");
                    if (getCurrentFocus() == null) historyToggle.requestFocus();
                } });
            } catch (Exception error) {
                runOnUiThread(() -> { if (alive()) {
                    historyBusy = false; clearHistory.setEnabled(true);
                    if (ticket == historyGeneration) historyNote.setText(TvUi.message(error) + "，请重试。");
                } });
            }
        });
    }
    private void search() {
        String query = input.getText().toString().trim();
        if (query.isEmpty() || query.length() > 100) {
            status.setText("请输入 1 至 100 字的搜索词。"); input.requestFocus(); return;
        }
        startSearch(query);
    }
    private void startSearch(String query) {
        // Invalidate before touching UI. A busy old request never blocks submission
        // and its callback cannot clear the new request's busy flag or cursor.
        final int ticket = ++generation;
        if (currentRequest != null) currentRequest.cancel(true);
        worker.purge();
        keyword = query; input.setText(query); seen.clear(); items.clear(); cursor = null;
        hasMore = false; limitedResults = false; generationBusy = false;
        adapter.notifyDataSetChanged(); footer.setVisibility(View.GONE);
        retry.setVisibility(View.GONE); retryAction = null;
        historyOpen = false; updateHistoryVisibility();
        heading.setText("“" + query + "”的搜索结果");
        InputMethodManager ime = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (ime != null) ime.hideSoftInputFromWindow(input.getWindowToken(), 0);
        submit.requestFocus();
        fetch(ticket, query, 0, "", "");
    }
    private void searchMore() {
        if (generationBusy || cursor == null || !hasMore) return;
        fetch(generation, keyword, cursor.optInt("offset", 0), cursor.optString("searchId", ""), cursor.optString("passback", ""));
    }
    private void fetch(int ticket, String query, int offset, String searchId, String passback) {
        if (ticket != generation || !query.equals(keyword) || generationBusy) return;
        generationBusy = true; retry.setVisibility(View.GONE); retryAction = null;
        more.setEnabled(false); more.setText("正在加载…");
        status.setText(offset == 0 ? "正在搜索“" + query + "”…" : "已显示 " + items.size() + " 部 · 正在加载更多…");
        final Set<String> previousIds = new HashSet<>(seen);
        try {
            currentRequest = worker.submit(() -> {
                Exception failure = null; JSONObject page = null;
                try {
                    JSONObject params = new JSONObject().put("keyword", query).put("offset", offset).put("timeoutMs", 30000);
                    if (!searchId.isEmpty()) params.put("searchId", searchId);
                    if (!passback.isEmpty()) params.put("passback", passback);
                    page = AppSearchPage.parse(SearchClient.search(getApplicationContext(), params), offset, previousIds);
                } catch (Exception error) { failure = error; }
                if (Thread.currentThread().isInterrupted() || ticket != generation) return;
                if (page == null && offset == 0) {
                    try { page = SiteCatalog.loadSearchWeb(query).put("hasMore", false).put("webFallback", true).put("cursor", new JSONObject().put("offset", 0)); failure = null; }
                    catch (Exception error) { if (failure == null) failure = error; }
                }
                if (Thread.currentThread().isInterrupted() || ticket != generation) return;
                final JSONObject result = page; final Exception error = failure;
                runOnUiThread(() -> acceptPage(ticket, query, offset, result, error));
            });
        } catch (RejectedExecutionException busy) {
            acceptPage(ticket, query, offset, null, new java.io.IOException("搜索正在切换，请重试"));
        }
    }
    private void acceptPage(int ticket, String query, int offset, JSONObject result, Exception error) {
        if (!alive() || ticket != generation || !query.equals(keyword)) return;
        generationBusy = false; currentRequest = null; more.setEnabled(true); more.setText("加载更多");
        if (result == null) {
            status.setText((items.isEmpty() ? "" : "已显示 " + items.size() + " 部 · ") + TvUi.message(error));
            retryAction = offset == 0 ? () -> startSearch(query) : this::searchMore;
            retry.setVisibility(View.VISIBLE); footer.setVisibility(View.GONE); return;
        }
        JSONArray rows = result.optJSONArray("items");
        if (rows != null) for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row != null && seen.add(row.optString("seriesId", ""))) items.add(row);
        }
        cursor = result.optJSONObject("cursor");
        hasMore = result.optBoolean("hasMore", false) && cursor != null;
        limitedResults = result.optBoolean("webFallback", false);
        adapter.notifyDataSetChanged(); footer.setVisibility(hasMore ? View.VISIBLE : View.GONE);
        status.setText(items.isEmpty() ? "没有找到相关短剧，换个关键词试试。" : "已显示 " + items.size() + " 部" + (hasMore ? " · 可继续加载" : limitedResults ? " · 官网首屏结果" : " · 已全部显示"));
        if (offset == 0) {
            final int historyTicket = ++historyGeneration;
            historyWorker.execute(() -> {
                try {
                    JSONArray keywords = new LibraryStore(getApplicationContext()).addSearchHistory(new JSONObject().put("keyword", query)).getJSONArray("keywords");
                    runOnUiThread(() -> { if (alive() && historyTicket == historyGeneration) renderHistory(keywords, ""); });
                } catch (Exception failure) {
                    runOnUiThread(() -> { if (alive() && historyTicket == historyGeneration) historyNote.setText("搜索已完成，但历史保存失败，请稍后重试。"); });
                }
            });
        }
        // Do not asynchronously request focus here: a user may have moved back to
        // the input or navigation while the request was running.
    }
    private void restore(Bundle state) {
        input.setText(state.getString("input", "")); keyword = state.getString("keyword", "");
        historyOpen = state.getBoolean("historyOpen", keyword.isEmpty());
        try {
            JSONArray saved = new JSONArray(state.getString("items", "[]"));
            for (int i = 0; i < saved.length(); i++) {
                JSONObject row = saved.optJSONObject(i);
                if (row != null && seen.add(row.optString("seriesId", ""))) items.add(row);
            }
            String savedCursor = state.getString("cursor", "");
            if (!savedCursor.isEmpty()) cursor = new JSONObject(savedCursor);
        } catch (Exception invalid) { items.clear(); seen.clear(); cursor = null; }
        hasMore = state.getBoolean("hasMore", false) && cursor != null;
        limitedResults = state.getBoolean("limitedResults", false);
        adapter.notifyDataSetChanged(); footer.setVisibility(hasMore ? View.VISIBLE : View.GONE);
        if (!keyword.isEmpty()) {
            heading.setText("“" + keyword + "”的搜索结果");
            status.setText(items.isEmpty() ? "搜索尚未完成，请重试。" : "已显示 " + items.size() + " 部" + (hasMore ? " · 可继续加载" : limitedResults ? " · 官网首屏结果" : " · 已全部显示"));
            if (items.isEmpty()) { retryAction = () -> startSearch(keyword); retry.setVisibility(View.VISIBLE); }
        }
        Parcelable gridState = state.getParcelable("gridState");
        boolean gridFocused = state.getBoolean("gridFocused");
        grid.post(() -> {
            if (gridState != null) grid.onRestoreInstanceState(gridState);
            if (gridFocused && !items.isEmpty()) grid.requestFocus();
        });
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("input", input.getText().toString()); state.putString("keyword", keyword);
        state.putBoolean("historyOpen", historyOpen); state.putBoolean("hasMore", hasMore); state.putBoolean("limitedResults", limitedResults);
        JSONArray saved = new JSONArray(); for (JSONObject row : items) saved.put(row);
        String json = saved.toString();
        if (json.length() <= 160000) { state.putString("items", json); state.putString("cursor", cursor == null ? "" : cursor.toString()); }
        state.putParcelable("gridState", grid.onSaveInstanceState()); state.putBoolean("gridFocused", grid.hasFocus());
    }
    private final class ResultAdapter extends BaseAdapter {
        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int position) { return items.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View convert, ViewGroup parent) { return TvUi.seriesCard(TvSearchActivity.this, items.get(position), convert); }
    }
    @Override protected void onResume() {
        super.onResume(); AccountSync.get().foreground();
        if (returningFromDetail) {
            returningFromDetail = false;
            grid.post(() -> { if (alive() && !items.isEmpty()) { grid.requestFocus(); grid.setSelection(Math.min(selectedPosition, items.size() - 1)); } });
        }
    }
    @Override protected void onPause() { AccountSync.get().flush(); super.onPause(); }
    @Override protected void onDestroy() {
        generation++; historyGeneration++;
        if (currentRequest != null) currentRequest.cancel(true);
        worker.shutdownNow(); historyWorker.shutdownNow(); super.onDestroy();
    }
}
