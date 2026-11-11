package io.github.jackotom.hongguo.android;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import java.util.concurrent.*;
import org.json.*;

/** 原生 TV 黄果详情：官方分集列表直读选集，播放直接使用官方签名 m3u8。 */
public final class TvHuangguoDetailActivity extends Activity {
    private String seriesId;
    private LinearLayout panel;
    private ProgressBar busy;
    private TextView status;
    private Button retry;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private int generation;
    private boolean destroyed;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        TvUi.prepareWindow(this);
        seriesId = getIntent().getStringExtra("seriesId");
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        // 与红果 TV 详情一致：显式深色背景，浅色文字才有对比度。
        root.setBackgroundColor(TvUi.BACKGROUND);
        root.setPadding(TvUi.dp(this, 24), TvUi.dp(this, 16), TvUi.dp(this, 24), TvUi.dp(this, 16));
        LinearLayout head = TvUi.row(this);
        Button back = TvUi.button(this, "← 返回");
        back.setOnClickListener(view -> finish());
        head.addView(back, new LinearLayout.LayoutParams(-2, TvUi.dp(this, 40)));
        TextView title = TvUi.text(this, "黄果漫剧", 20, TvUi.TEXT);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, -2, 1f);
        titleParams.leftMargin = TvUi.dp(this, 12); titleParams.gravity = Gravity.CENTER_VERTICAL;
        head.addView(title, titleParams);
        root.addView(head, new LinearLayout.LayoutParams(-1, -2));
        busy = new ProgressBar(this); busy.setVisibility(View.GONE);
        status = TvUi.text(this, "", 13, TvUi.MUTED); status.setMaxLines(2);
        retry = TvUi.button(this, "重新加载"); retry.setVisibility(View.GONE);
        LinearLayout stateRow = TvUi.row(this);
        stateRow.addView(busy, new LinearLayout.LayoutParams(TvUi.dp(this, 20), TvUi.dp(this, 20)));
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(0, -2, 1f);
        statusParams.leftMargin = TvUi.dp(this, 8); statusParams.gravity = Gravity.CENTER_VERTICAL;
        stateRow.addView(status, statusParams);
        stateRow.addView(retry, new LinearLayout.LayoutParams(-2, TvUi.dp(this, 36)));
        root.addView(stateRow, new LinearLayout.LayoutParams(-1, -2));
        ScrollView scroll = new ScrollView(this);
        panel = new LinearLayout(this); panel.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(panel, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);
        retry.setOnClickListener(view -> load());
        load();
    }

    @Override protected void onDestroy() {
        destroyed = true; worker.shutdownNow(); super.onDestroy();
    }

    private void state(boolean loading, String message, boolean canRetry) {
        runOnUiThread(() -> {
            if (destroyed) return;
            busy.setVisibility(loading ? View.VISIBLE : View.GONE);
            status.setText(message == null ? "" : message);
            retry.setVisibility(canRetry ? View.VISIBLE : View.GONE);
        });
    }

    private void load() {
        final int ticket = ++generation;
        state(true, "正在读取黄果剧集…", false);
        worker.execute(() -> {
            try {
                JSONObject detail = HuangguoApi.detail(seriesId);
                if (destroyed || ticket != generation) return;
                runOnUiThread(() -> { if (!destroyed && ticket == generation) render(detail); });
            } catch (Exception error) {
                if (!destroyed && ticket == generation) state(false, TvUi.message(error), true);
            }
        });
    }

    private void render(JSONObject detail) {
        panel.removeAllViews();
        // 头部：封面 + 右侧信息（与红果 TV 详情一致的横向布局；封面经 Worker 解密为明文）。
        LinearLayout head = TvUi.row(this);
        head.setPadding(0, TvUi.dp(this, 8), 0, TvUi.dp(this, 12));
        LinearLayout.LayoutParams coverParams = new LinearLayout.LayoutParams(TvUi.dp(this, 168), TvUi.dp(this, 224));
        String cover = detail.optString("coverUrl", "");
        if (!cover.isEmpty()) head.addView(TvUi.poster(this, detail), coverParams);
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        TextView heading = TvUi.text(this, detail.optString("title", "黄果漫剧"), 21, TvUi.TEXT);
        heading.setTypeface(null, android.graphics.Typeface.BOLD);
        info.addView(heading, new LinearLayout.LayoutParams(-1, -2));
        TextView count = TvUi.text(this, detail.optInt("episodeCount", 0) + " 集 · 黄果官方片源", 14, TvUi.MUTED);
        LinearLayout.LayoutParams countParams = new LinearLayout.LayoutParams(-1, -2);
        countParams.topMargin = TvUi.dp(this, 6);
        info.addView(count, countParams);
        String description = detail.optString("description", "");
        if (!description.isEmpty()) {
            TextView intro = TvUi.text(this, description, 13, TvUi.MUTED);
            intro.setMaxLines(6);
            intro.setLineSpacing(0, 1.25f);
            LinearLayout.LayoutParams introParams = new LinearLayout.LayoutParams(-1, -2);
            introParams.topMargin = TvUi.dp(this, 10);
            info.addView(intro, introParams);
        }
        LinearLayout.LayoutParams infoParams = new LinearLayout.LayoutParams(0, -2, 1f);
        infoParams.leftMargin = TvUi.dp(this, 16);
        head.addView(info, infoParams);
        panel.addView(head, new LinearLayout.LayoutParams(-1, -2));
        TextView episodesTitle = TvUi.text(this, "选集", 16, TvUi.TEXT);
        episodesTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        panel.addView(episodesTitle, new LinearLayout.LayoutParams(-1, -2));
        GridLayout episodes = new GridLayout(this);
        episodes.setColumnCount(6);
        episodes.setUseDefaultMargins(true);
        LinearLayout.LayoutParams episodesParams = new LinearLayout.LayoutParams(-1, -2);
        episodesParams.topMargin = TvUi.dp(this, 8);
        JSONArray rows = detail.optJSONArray("episodes");
        for (int i = 0; rows != null && i < rows.length(); i++) {
            JSONObject episode = rows.optJSONObject(i);
            if (episode == null) continue;
            Button button = TvUi.button(this, episode.optString("label", "第" + (i + 1) + "集"));
            boolean locked = episode.optBoolean("locked");
            button.setEnabled(!locked);
            if (locked) button.setAlpha(0.45f);
            GridLayout.LayoutParams cell = new GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED));
            cell.width = TvUi.dp(this, 96); cell.height = TvUi.dp(this, 44);
            button.setOnClickListener(view -> play(detail, episode));
            episodes.addView(button, cell);
        }
        panel.addView(episodes, episodesParams);
        state(false, "", false);
        panel.requestFocus();
    }

    private void play(JSONObject detail, JSONObject episode) {
        final int index = episode.optInt("index", 1);
        state(true, "正在解析第 " + index + " 集…", false);
        final int ticket = ++generation;
        worker.execute(() -> {
            try {
                String url = HuangguoApi.episodeUrl(seriesId, index);
                if (destroyed || ticket != generation) return;
                JSONObject series = new JSONObject().put("seriesId", seriesId)
                    .put("title", detail.optString("title", "黄果漫剧"))
                    .put("coverUrl", detail.optString("coverUrl", ""))
                    .put("episodeCount", detail.optInt("episodeCount", 0));
                JSONObject current = new JSONObject().put("vid", seriesId + String.format("%04d", index)).put("index", index);
                JSONObject options = PlayerOptions.read(this);
                TvPlay.launch(this, PlayLauncher.build(this, series, current,
                    new JSONArray().put(current), url, false,
                    new JSONObject().put("speed", options.optDouble("speed", 1.0))));
                runOnUiThread(() -> { if (!destroyed) state(false, "", false); });
            } catch (Exception error) {
                if (!destroyed && ticket == generation) state(false, TvUi.message(error), true);
            }
        });
    }
}
