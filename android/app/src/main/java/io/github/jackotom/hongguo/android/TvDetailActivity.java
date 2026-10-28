package io.github.jackotom.hongguo.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import java.util.concurrent.*;
import org.json.JSONArray;
import org.json.JSONObject;

/** Content-first TV detail. Focus, viewed progress and the selected download target are separate states. */
public class TvDetailActivity extends Activity {
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(8));
    private String seriesId;
    private JSONObject detail, history;
    private JSONArray episodes;
    private TextView title, meta, description, status;
    private Button resume, favorite, download, more, retry;
    private LinearLayout seasons, posterSlot;
    private HorizontalScrollView seasonScroll;
    private GridView grid;
    private BaseAdapter adapter;
    private boolean favoriteOn, favoriteBusy, downloadBusy, libraryReady;
    private int generation, selectedPosition, libraryGeneration;
    private Future<?> detailTask, seasonTask, downloadTask;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        TvUi.prepareWindow(this);
        seriesId = getIntent().getStringExtra("seriesId");
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(TvUi.BACKGROUND);
        root.setPadding(TvUi.dp(this, 24), TvUi.dp(this, 8), TvUi.dp(this, 24), TvUi.dp(this, 8));
        LinearLayout heading = TvUi.row(this);
        Button back = TvUi.button(this, "返回"); back.setOnClickListener(view -> onBackPressed());
        heading.addView(back, new LinearLayout.LayoutParams(-2, TvUi.dp(this, 40)));
        title = TvUi.text(this, "读取剧集…", 21, TvUi.TEXT); title.setSingleLine(); title.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, -2, 1f); titleParams.leftMargin = TvUi.dp(this, 12);
        heading.addView(title, titleParams); root.addView(heading, new LinearLayout.LayoutParams(-1, TvUi.dp(this, 44)));

        LinearLayout overview = TvUi.row(this); overview.setPadding(0, TvUi.dp(this, 8), 0, TvUi.dp(this, 8));
        posterSlot = new LinearLayout(this);
        overview.addView(posterSlot, new LinearLayout.LayoutParams(TvUi.dp(this, 78), TvUi.dp(this, 104)));
        LinearLayout info = new LinearLayout(this); info.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams infoParams = new LinearLayout.LayoutParams(0, -1, 1f); infoParams.leftMargin = TvUi.dp(this, 16);
        overview.addView(info, infoParams);
        meta = TvUi.text(this, "", 13, TvUi.MUTED); meta.setSingleLine(); meta.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(meta, new LinearLayout.LayoutParams(-1, -2));
        description = TvUi.text(this, "正在读取剧集详情…", 13, TvUi.MUTED); description.setMaxLines(2); description.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(description, new LinearLayout.LayoutParams(-1, 0, 1f));
        LinearLayout actions = TvUi.row(this);
        resume = TvUi.button(this, "开始观看"); resume.setOnClickListener(view -> play(resolveResume()));
        favorite = TvUi.button(this, "收藏"); favorite.setOnClickListener(view -> toggleFavorite());
        download = TvUi.button(this, "下载第 1 集"); download.setOnClickListener(view -> confirmDownload());
        more = TvUi.button(this, "更多"); more.setOnClickListener(view -> showMore());
        for (Button button : new Button[]{ resume, favorite, download, more }) {
            actions.addView(button, new LinearLayout.LayoutParams(-2, TvUi.dp(this, 40)));
            ((LinearLayout.LayoutParams) button.getLayoutParams()).rightMargin = TvUi.dp(this, 8);
        }
        info.addView(actions, new LinearLayout.LayoutParams(-1, TvUi.dp(this, 40)));
        root.addView(overview, new LinearLayout.LayoutParams(-1, TvUi.dp(this, 120)));
        seasonScroll = new HorizontalScrollView(this); seasonScroll.setHorizontalScrollBarEnabled(false); seasonScroll.setVisibility(View.GONE);
        seasons = TvUi.row(this); seasonScroll.addView(seasons, new HorizontalScrollView.LayoutParams(-2, -2));
        root.addView(seasonScroll, new LinearLayout.LayoutParams(-1, TvUi.dp(this, 42)));
        status = TvUi.text(this, "正在读取选集…", 12, TvUi.MUTED); status.setSingleLine(); status.setEllipsize(TextUtils.TruncateAt.END);
        root.addView(status, new LinearLayout.LayoutParams(-1, TvUi.dp(this, 24)));
        retry = TvUi.button(this, "重新加载剧集"); retry.setVisibility(View.GONE); retry.setOnClickListener(view -> load(seriesId));
        root.addView(retry, new LinearLayout.LayoutParams(-2, TvUi.dp(this, 40)));
        grid = new GridView(this);
        int width = getResources().getConfiguration().screenWidthDp;
        grid.setNumColumns(Math.max(5, Math.min(10, (width - 48) / 72)));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH); grid.setHorizontalSpacing(TvUi.dp(this, 8)); grid.setVerticalSpacing(TvUi.dp(this, 8));
        grid.setPadding(0, TvUi.dp(this, 2), 0, TvUi.dp(this, 4)); TvUi.configureGrid(grid);
        adapter = new EpisodeAdapter(); grid.setAdapter(adapter);
        grid.setOnItemClickListener((parent, view, position, id) -> play(position));
        grid.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) { selectedPosition = position; updateDownloadLabel(); }
            public void onNothingSelected(AdapterView<?> parent) { }
        });
        root.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root); load(seriesId);
    }
    private boolean current(int ticket, String id) { return !isFinishing() && !isDestroyed() && ticket == generation && id.equals(seriesId); }
    private void setActions() {
        boolean ready = detail != null && episodes != null && episodes.length() > 0;
        resume.setEnabled(ready && libraryReady && !downloadBusy); favorite.setEnabled(detail != null && libraryReady && !favoriteBusy && !downloadBusy);
        download.setEnabled(ready && !downloadBusy); more.setEnabled(detail != null && !downloadBusy);
        for (int i = 0; i < seasons.getChildCount(); i++) seasons.getChildAt(i).setEnabled(!downloadBusy);
    }
    private void load(String id) {
        if (downloadBusy) return;
        if (id == null || !id.matches("[0-9]{1,30}")) { title.setText("剧集信息无效"); status.setText("请返回列表重新选择剧集。"); return; }
        if (detailTask != null) detailTask.cancel(true); if (seasonTask != null) seasonTask.cancel(true);
        final String target = id; final int ticket = ++generation; seriesId = target;
        detail = null; episodes = null; history = null; selectedPosition = 0; favoriteBusy = false; libraryReady = false;
        title.setText("读取剧集…"); meta.setText(""); description.setText("正在读取剧集详情…"); status.setText("正在读取选集…");
        posterSlot.removeAllViews(); seasons.removeAllViews(); seasonScroll.setVisibility(View.GONE); retry.setVisibility(View.GONE);
        adapter.notifyDataSetChanged(); setActions();
        detailTask = worker.submit(() -> {
            try {
                JSONObject loaded = SiteCatalog.loadDetail(target);
                if (loaded.optJSONArray("episodes") == null || loaded.getJSONArray("episodes").length() == 0) throw new java.io.IOException("此剧暂无可读取的分集，请稍后重试");
                runOnUiThread(() -> {
                    if (!current(ticket, target)) return;
                    detail = loaded; episodes = loaded.optJSONArray("episodes"); title.setText(loaded.optString("title", "短剧详情"));
                    StringBuilder line = new StringBuilder("全 ").append(episodes.length()).append(" 集");
                    JSONArray tags = loaded.optJSONArray("tags"); if (tags != null) for (int i = 0; i < Math.min(3, tags.length()); i++) line.append(" · ").append(tags.optString(i));
                    meta.setText(line.toString()); description.setText(loaded.optString("description", "暂无剧情简介"));
                    ImageView poster = TvUi.poster(this, loaded); posterSlot.addView(poster, new LinearLayout.LayoutParams(-1, -1));
                    status.setText("选集 · 确认播放，方向键选择下载目标"); setActions(); adapter.notifyDataSetChanged(); resume.requestFocus();
                    refreshLibrary(true); loadSeasons(target, ticket);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (!current(ticket, target)) return;
                    title.setText("剧集加载未完成"); description.setText(TvUi.message(error)); status.setText("检查网络后重新加载，或返回列表选择其他短剧。");
                    retry.setVisibility(View.VISIBLE); retry.requestFocus();
                });
            }
        });
    }
    private void loadSeasons(String id, int ticket) {
        seasonTask = worker.submit(() -> {
            try {
                JSONArray rows = new OfficialSeriesService(this).load(id, client -> { }).getJSONArray("items");
                runOnUiThread(() -> {
                    if (!current(ticket, id)) return;
                    seasons.removeAllViews(); if (rows.length() < 2) { seasonScroll.setVisibility(View.GONE); return; }
                    int selectedOffset = 0;
                    for (int i = 0; i < rows.length(); i++) {
                        JSONObject row = rows.optJSONObject(i); if (row == null) continue;
                        String sid = row.optString("seriesId", ""); if (sid.isEmpty()) continue;
                        Button season = TvUi.button(this, row.optString("seasonLabel", "系列剧")); season.setSelected(sid.equals(seriesId));
                        season.setOnClickListener(view -> { if (!sid.equals(seriesId)) load(sid); });
                        seasons.addView(season, new LinearLayout.LayoutParams(-2, TvUi.dp(this, 36)));
                        ((LinearLayout.LayoutParams) season.getLayoutParams()).rightMargin = TvUi.dp(this, 8);
                        if (sid.equals(seriesId)) selectedOffset = i;
                    }
                    seasonScroll.setVisibility(View.VISIBLE); setActions();
                    final int offset = selectedOffset; seasonScroll.post(() -> { if (current(ticket, id) && offset < seasons.getChildCount()) seasonScroll.smoothScrollTo(seasons.getChildAt(offset).getLeft(), 0); });
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (!current(ticket, id)) return;
                    seasons.removeAllViews(); Button again = TvUi.button(this, "分季未读取 · 重试");
                    again.setOnClickListener(view -> loadSeasons(id, ticket)); seasons.addView(again, new LinearLayout.LayoutParams(-2, TvUi.dp(this, 36))); seasonScroll.setVisibility(View.VISIBLE);
                });
            }
        });
    }
    private int resolveResume() {
        if (episodes == null || history == null) return 0;
        String vid = history.optString("vid", "");
        for (int i = 0; i < episodes.length(); i++) if (episodes.optJSONObject(i) != null && vid.equals(episodes.optJSONObject(i).optString("vid"))) return i;
        return 0;
    }
    private void refreshLibrary(boolean locate) {
        if (detail == null) return;
        final String target = seriesId; final int ticket = generation, libraryTicket = ++libraryGeneration;
        libraryReady = false; resume.setText("读取记录…"); setActions();
        worker.execute(() -> {
            try {
                AccountSync.Selection selection = AccountSync.get().captureSelection(); LibraryStore store = new LibraryStore(this);
                JSONObject last = store.seriesHistory(target); JSONArray favorites = store.library().getJSONArray("favorites");
                AccountSync.get().requireCurrent(selection);
                boolean saved = false; for (int i = 0; i < favorites.length(); i++) if (target.equals(favorites.optJSONObject(i) == null ? "" : favorites.optJSONObject(i).optString("seriesId"))) saved = true;
                final boolean on = saved;
                runOnUiThread(() -> {
                    if (!current(ticket, target) || libraryTicket != libraryGeneration || detail == null) return;
                    try { AccountSync.get().requireCurrent(selection); } catch (Exception changed) { return; }
                    history = last; favoriteOn = on; libraryReady = true; favorite.setText(on ? "已收藏" : "收藏"); favorite.setSelected(on);
                    updateResume(locate);
                });
            } catch (Exception error) {
                runOnUiThread(() -> { if (current(ticket, target) && libraryTicket == libraryGeneration) status.setText("观看记录读取未完成 · 更多中可刷新"); });
            }
        });
    }
    private void updateResume(boolean locate) {
        int position = resolveResume();
        resume.setText(history == null ? "开始观看" : "继续第 " + (position + 1) + " 集"); resume.setSelected(true);
        if (locate) { selectedPosition = position; grid.setSelection(position); }
        updateDownloadLabel(); adapter.notifyDataSetChanged(); setActions();
    }
    private void updateDownloadLabel() { if (download != null) download.setText(downloadBusy ? "准备下载…" : "下载第 " + (selectedPosition + 1) + " 集"); }
    private void play(int position) {
        if (downloadBusy || detail == null || episodes == null || position < 0 || position >= episodes.length()) return;
        try {
            JSONObject series = LibraryStore.series(detail), episode = LibraryStore.episode(episodes.getJSONObject(position));
            TvPlay.launch(this, PlayLauncher.build(this, series, episode, LibraryStore.episodes(episodes, episode), null, false, new JSONObject().put("resolveInPlayer", true)));
        } catch (Exception error) { TvUi.error(this, TvUi.message(error), null); }
    }
    private void toggleFavorite() {
        if (favoriteBusy || downloadBusy || detail == null) return;
        final JSONObject item = detail; final String target = seriesId; final int ticket = generation;
        final AccountSync.Selection selection; final LibraryStore store; final JSONObject series;
        try {
            selection = AccountSync.get().captureSelection(); store = new LibraryStore(this); series = LibraryStore.series(item);
        } catch (Exception error) { TvUi.error(this, TvUi.message(error), null); return; }
        libraryGeneration++; favoriteBusy = true; favorite.setText("保存中…"); setActions();
        worker.execute(() -> {
            Exception failure = null;
            try {
                AccountSync.get().requireCurrent(selection); store.toggle(series);
            } catch (Exception error) { failure = error; }
            final Exception error = failure;
            runOnUiThread(() -> {
                if (!current(ticket, target)) return;
                favoriteBusy = false; favorite.setText(favoriteOn ? "已收藏" : "收藏"); setActions();
                if (error == null) refreshLibrary(false); else TvUi.error(this, TvUi.message(error), this::toggleFavorite);
            });
        });
    }
    private void confirmDownload() {
        if (detail == null || episodes == null || downloadBusy || selectedPosition >= episodes.length()) return;
        final JSONObject item = detail, episode = episodes.optJSONObject(selectedPosition);
        try {
            String quality = PlayerOptions.read(this).getString("quality");
            new AlertDialog.Builder(this).setTitle("下载第 " + (selectedPosition + 1) + " 集？")
                .setMessage("保存到本机，完成后可在「下载」中离线观看。清晰度：" + (quality.equals("auto") ? "自动" : quality + " 及以上") + "；下载不自动降低清晰度。")
                .setNegativeButton("取消", null).setPositiveButton("开始下载", (dialog, which) -> startDownload(item, episode, quality)).show();
        } catch (Exception error) { TvUi.error(this, TvUi.message(error), null); }
    }
    private void startDownload(JSONObject item, JSONObject episode, String quality) {
        if (downloadBusy || detail != item || episode == null) return;
        final int ticket = generation; final String target = seriesId;
        downloadBusy = true; updateDownloadLabel(); setActions(); status.setText("正在准备本集下载，完成前请保留此页面…");
        downloadTask = worker.submit(() -> {
            Exception failure = null;
            try { TvDownloads.start(this, item, episode, quality); } catch (Exception error) { failure = error; }
            final Exception error = failure;
            runOnUiThread(() -> {
                if (!current(ticket, target)) return;
                downloadBusy = false; updateDownloadLabel(); setActions();
                if (error == null) status.setText("已加入本机下载 · 返回首页的「下载」查看");
                else { status.setText("下载准备未完成，可重试"); TvUi.error(this, TvUi.message(error), this::confirmDownload); }
            });
        });
    }
    private void showMore() {
        if (detail == null || downloadBusy) return;
        new AlertDialog.Builder(this).setTitle("剧集操作").setItems(new String[]{ "完整简介", "从第 1 集播放", "定位续看集", "刷新剧集与记录" }, (dialog, which) -> {
            if (which == 0) {
                ScrollView scroll = new ScrollView(this); TextView text = TvUi.text(this, detail.optString("description", "暂无剧情简介"), 16, TvUi.TEXT);
                text.setPadding(TvUi.dp(this, 24), TvUi.dp(this, 12), TvUi.dp(this, 24), TvUi.dp(this, 12)); scroll.addView(text);
                new AlertDialog.Builder(this).setTitle("剧情简介").setView(scroll).setPositiveButton("关闭", null).show();
            } else if (which == 1) play(0);
            else if (which == 2) { selectedPosition = resolveResume(); grid.setSelection(selectedPosition); grid.requestFocus(); }
            else load(seriesId);
        }).show();
    }
    private final class EpisodeAdapter extends BaseAdapter {
        @Override public int getCount() { return episodes == null ? 0 : episodes.length(); }
        @Override public Object getItem(int position) { return episodes == null ? null : episodes.optJSONObject(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View convert, ViewGroup parent) {
            TextView item = convert instanceof TextView ? (TextView) convert : new TextView(TvDetailActivity.this);
            item.setGravity(Gravity.CENTER); item.setTextSize(14); item.setTextColor(TvUi.TEXT); item.setMaxLines(2);
            item.setBackground(TvUi.focusSurface(6, TvDetailActivity.this)); item.setPadding(0, TvUi.dp(TvDetailActivity.this, 6), 0, TvUi.dp(TvDetailActivity.this, 6));
            item.setLayoutParams(new AbsListView.LayoutParams(-1, TvUi.dp(TvDetailActivity.this, 52))); item.setFocusable(false); item.setClickable(false);
            JSONObject episode = episodes.optJSONObject(position);
            boolean viewed = history != null && episode != null && history.optString("vid").equals(episode.optString("vid"));
            boolean complete = viewed && history.optLong("durationMs") > 0 && history.optLong("positionMs") >= history.optLong("durationMs") - 5000;
            String state = viewed ? complete ? "已看" : "续看" : episode != null && !episode.optBoolean("webAvailable") ? "自动取源" : "";
            item.setText((position + 1) + (state.isEmpty() ? "" : "\n" + state)); item.setActivated(viewed);
            item.setContentDescription("第 " + (position + 1) + " 集" + (state.isEmpty() ? "" : "，" + state) + "，确认播放");
            return item;
        }
    }
    @Override protected void onResume() { super.onResume(); AccountSync.get().foreground(); if (detail != null) refreshLibrary(true); }
    @Override protected void onPause() { AccountSync.get().flush(); super.onPause(); }
    @Override public void onBackPressed() {
        if (downloadBusy) new AlertDialog.Builder(this).setTitle("下载正在准备").setMessage("返回会结束本次尚未完成的下载准备，已完成的下载会保留。").setNegativeButton("继续等待", null).setPositiveButton("返回", (dialog, which) -> { if (downloadTask != null) downloadTask.cancel(true); finish(); }).show();
        else super.onBackPressed();
    }
    @Override protected void onDestroy() { generation++; worker.shutdownNow(); super.onDestroy(); }
}
