package io.github.jackotom.hongguo.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.*;
import org.json.JSONObject;

/** Shared TV typography, poster cards and independent focus/selection states. */
final class TvUi {
    static final int BACKGROUND = 0xff131516, SURFACE = 0xff202325, TEXT = 0xfff2f2f2,
        MUTED = 0xffa9adb1, ACCENT = 0xffe5484d;
    private TvUi() { }
    static int dp(Context context, int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
    static void prepareWindow(Activity activity) {
        activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        activity.getWindow().setStatusBarColor(BACKGROUND);
        activity.getWindow().setNavigationBarColor(BACKGROUND);
        activity.getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }
    private static GradientDrawable shape(Context context, int radius, int color, int border) {
        GradientDrawable shape = new GradientDrawable(); shape.setColor(color);
        shape.setCornerRadius(dp(context, radius));
        if (border != 0) shape.setStroke(dp(context, 2), border);
        return shape;
    }
    static StateListDrawable focusSurface(int radiusDp, Context context) {
        StateListDrawable result = new StateListDrawable();
        result.addState(new int[]{android.R.attr.state_focused}, shape(context, radiusDp, 0xff343a3f, TEXT));
        result.addState(new int[]{android.R.attr.state_pressed}, shape(context, radiusDp, 0xff4c2528, ACCENT));
        result.addState(new int[]{android.R.attr.state_selected}, shape(context, radiusDp, 0xff4c2528, ACCENT));
        result.addState(new int[]{-android.R.attr.state_enabled}, shape(context, radiusDp, 0xff191b1d, 0));
        result.addState(new int[0], shape(context, radiusDp, SURFACE, 0));
        return result;
    }
    /** Only the outline is overlaid: a solid selector would obscure poster artwork. */
    static StateListDrawable selector(Context context) {
        StateListDrawable selector = new StateListDrawable();
        selector.addState(new int[]{android.R.attr.state_focused}, shape(context, 8, Color.TRANSPARENT, TEXT));
        selector.addState(new int[]{android.R.attr.state_pressed}, shape(context, 8, Color.TRANSPARENT, ACCENT));
        selector.addState(new int[0], shape(context, 8, Color.TRANSPARENT, 0));
        return selector;
    }
    static int posterColumns(Context context) {
        android.content.res.Configuration c = context.getResources().getConfiguration();
        int targetWidth = c.screenHeightDp <= 400 ? 96 : 140;
        return Math.max(3, Math.min(6, (c.screenWidthDp - 48) / targetWidth));
    }
    static void configureGrid(GridView grid) {
        grid.setSelector(selector(grid.getContext()));
        grid.setDrawSelectorOnTop(true);
        grid.setClipToPadding(false);
        grid.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        grid.setVerticalScrollBarEnabled(false);
    }
    static TextView text(Context context, String value, float size, int color) {
        TextView view = new TextView(context);
        view.setText(value); view.setTextColor(color); view.setTextSize(size);
        view.setFontFeatureSettings("kern");
        return view;
    }
    static Button button(Context context, String label) {
        Button view = new Button(context);
        view.setText(label); view.setAllCaps(false); view.setTextSize(15); view.setTextColor(TEXT);
        view.setFocusable(true); view.setFocusableInTouchMode(false);
        view.setMinWidth(0); view.setMinimumWidth(0); view.setMinHeight(dp(context, 40)); view.setMinimumHeight(dp(context, 40));
        view.setBackground(focusSurface(6, context));
        view.setPadding(dp(context, 14), dp(context, 6), dp(context, 14), dp(context, 6));
        return view;
    }
    static LinearLayout row(Context context) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }
    static ImageView poster(Context context, JSONObject item) {
        ImageView image = new ImageView(context); image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setBackground(shape(context, 6, 0xff292d30, 0)); image.setClipToOutline(true);
        TvPosterLoader.bind(image, item.optString("coverUrl", ""), item.optString("title", "短剧"));
        return image;
    }
    static View seriesCard(Context context, JSONObject item, View convert) {
        SeriesCard card = convert instanceof SeriesCard ? (SeriesCard) convert : new SeriesCard(context);
        card.title.setText(item.optString("title", "短剧"));
        String history = item.optString("historyLabel", "");
        String label = item.optString("episodeLabel", ""); int count = item.optInt("episodeCount", 0);
        card.meta.setText(!history.isEmpty() ? history : !label.isEmpty() ? label : count > 0 ? "全 " + count + " 集" : "查看详情");
        TvPosterLoader.bind(card.cover, item.optString("coverUrl", ""), item.optString("title", "短剧"));
        card.setContentDescription(card.title.getText() + "，" + card.meta.getText());
        return card;
    }
    private static final class SeriesCard extends LinearLayout {
        final ImageView cover; final TextView title, meta;
        SeriesCard(Context context) {
            super(context); setOrientation(VERTICAL); setPadding(dp(context, 4), dp(context, 4), dp(context, 4), dp(context, 6));
            setBackground(shape(context, 8, SURFACE, 0)); setFocusable(false); setClickable(false);
            cover = new ImageView(context); cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
            cover.setBackground(shape(context, 5, 0xff292d30, 0)); cover.setClipToOutline(true);
            addView(cover, new LinearLayout.LayoutParams(-1, dp(context, 160)));
            title = text(context, "", 14, TEXT); title.setMaxLines(2); title.setMinLines(2); title.setEllipsize(TextUtils.TruncateAt.END);
            title.setPadding(dp(context, 6), dp(context, 6), dp(context, 6), 0);
            addView(title, new LinearLayout.LayoutParams(-1, -2));
            meta = text(context, "", 12, MUTED); meta.setSingleLine(); meta.setEllipsize(TextUtils.TruncateAt.END);
            meta.setPadding(dp(context, 6), dp(context, 2), dp(context, 6), 0);
            addView(meta, new LinearLayout.LayoutParams(-1, -2));
        }
        @Override protected void onMeasure(int width, int height) {
            int w = MeasureSpec.getSize(width) - getPaddingLeft() - getPaddingRight();
            ViewGroup.LayoutParams params = cover.getLayoutParams(); int imageHeight = Math.max(1, w * 4 / 3);
            if (params.height != imageHeight) { params.height = imageHeight; cover.setLayoutParams(params); }
            super.onMeasure(width, height);
        }
    }
    static void error(Activity activity, String message, Runnable retry) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
            .setTitle("提示").setMessage(message == null || message.trim().isEmpty() ? "操作未成功，请重试" : message)
            .setNegativeButton("关闭", null);
        if (retry != null) builder.setPositiveButton("重试", (dialog, which) -> retry.run());
        builder.show();
    }
    static String message(Exception error) {
        String text = error == null ? null : error.getMessage();
        return text == null || text.trim().isEmpty() ? "操作未成功，请重试" : text;
    }
}
