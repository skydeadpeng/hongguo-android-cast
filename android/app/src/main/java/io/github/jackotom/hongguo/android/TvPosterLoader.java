package io.github.jackotom.hongguo.android;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;
import java.io.File;
import java.lang.ref.WeakReference;
import java.util.*;
import java.util.concurrent.*;

/** Bounded artwork loading. Public HTTPS transport, no account headers or permanent files. */
final class TvPosterLoader {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService WORK = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(48), r -> {
        Thread thread = new Thread(r, "hongguo-posters"); thread.setDaemon(true); return thread;
    });
    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "hongguo-poster-timeout"); thread.setDaemon(true); return thread;
    });
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(8 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getAllocationByteCount(); }
    };
    // Only touched on the main thread. Recycled views are checked by URL before delivery.
    private static final Map<String, List<WeakReference<ImageView>>> WAITING = new HashMap<>();
    static void bind(ImageView view, String raw, String title) {
        String url = EpisodeResolver.publicHttpsUrl(raw);
        Object previous = view.getTag();
        if (url.equals(previous) && view.getDrawable() != null) return;
        view.setTag(url); view.setImageDrawable(null);
        view.setContentDescription(title + "，封面");
        if (url.isEmpty()) { view.setContentDescription(title + "，暂无封面"); return; }
        Bitmap ready = CACHE.get(url);
        if (ready != null) { view.setImageBitmap(ready); return; }
        List<WeakReference<ImageView>> subscribers = WAITING.get(url);
        if (subscribers != null) {
            for (WeakReference<ImageView> subscriber : subscribers) if (subscriber.get() == view) return;
            subscribers.add(new WeakReference<>(view)); return;
        }
        subscribers = new ArrayList<>(); subscribers.add(new WeakReference<>(view)); WAITING.put(url, subscribers);
        Context context = view.getContext().getApplicationContext();
        try { WORK.execute(() -> load(context, url)); }
        catch (RejectedExecutionException full) { WAITING.remove(url); }
    }
    private static void load(Context context, String url) {
        File temporary = null; Bitmap result = null;
        Thread owner = Thread.currentThread();
        ScheduledFuture<?> timeout = TIMER.schedule(owner::interrupt, 8, TimeUnit.SECONDS);
        try {
            temporary = File.createTempFile("tv-poster-", ".image", context.getCacheDir());
            NetworkGuard.download(url, temporary, 3 * 1024 * 1024);
            BitmapFactory.Options options = new BitmapFactory.Options(); options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(temporary.getPath(), options);
            if (options.outWidth < 1 || options.outHeight < 1 || (long)options.outWidth * options.outHeight > 40_000_000L) return;
            options.inSampleSize = 1;
            while (options.outWidth / options.inSampleSize > 480 || options.outHeight / options.inSampleSize > 640) options.inSampleSize *= 2;
            options.inJustDecodeBounds = false; result = BitmapFactory.decodeFile(temporary.getPath(), options);
            if (result != null) CACHE.put(url, result);
        } catch (Exception ignored) { /* Artwork failure never prevents navigation or playback. */ }
        finally {
            timeout.cancel(false); Thread.interrupted();
            if (temporary != null) temporary.delete();
            final Bitmap bitmap = result;
            MAIN.post(() -> {
                List<WeakReference<ImageView>> subscribers = WAITING.remove(url);
                if (subscribers == null) return;
                for (WeakReference<ImageView> reference : subscribers) {
                    ImageView view = reference.get();
                    if (view != null && url.equals(view.getTag())) {
                        if (bitmap != null) view.setImageBitmap(bitmap);
                        else view.setContentDescription(view.getContentDescription() + "，封面暂未加载");
                    }
                }
            });
        }
    }
}
