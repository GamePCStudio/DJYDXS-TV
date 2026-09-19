package com.supermov.tv;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.view.animation.AlphaAnimation;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 轻量图片异步加载（内存缓存，无第三方依赖）。
 *
 * <p>LAMPA 效果⑤：图片「渐进呈现」—— 未加载完时占位（{@link #setPlaceholder} 置灰底 / 留旧图），
 * 图片到位后 alpha 0→1 淡入（对齐 LAMPA 的"图片到达后淡入 + 骨架占位"，无闪烁、无廉价感）。</p>
 */
public final class ImageLoader {
    private static final Map<String, Bitmap> CACHE = new ConcurrentHashMap<>();
    private static final ExecutorService POOL = Executors.newFixedThreadPool(6);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private ImageLoader() {}

    /** 加载图片；未命中缓存时先显示占位（保留旧图 / 由调用方给的 background），到位后淡入。 */
    public static void load(String url, ImageView iv) {
        if (url == null || url.isEmpty()) return;
        Bitmap cached = CACHE.get(url);
        if (cached != null) {
            iv.setImageBitmap(cached);
            return;
        }
        iv.setTag(url);
        // 渐进：加载中保持占位（背景色已由 layout 设好），不强行置空，避免闪白
        POOL.execute(() -> {
            Bitmap bmp = fetch(url);
            if (bmp != null) {
                CACHE.put(url, bmp);
            }
            MAIN.post(() -> {
                Object tag = iv.getTag();
                if (bmp != null && url.equals(tag)) {
                    iv.setImageBitmap(bmp);
                    fadeIn(iv, 260);   // LAMPA 效果⑤：图片到位淡入
                }
                // 加载失败：保留占位背景，不处理（下次可重试）
            });
        });
    }

    /** 淡入动画（alpha 0→1），对齐 LAMPA 图片渐进呈现。幂等：重复调用无害。 */
    private static void fadeIn(ImageView iv, int durationMs) {
        iv.setAlpha(0f);
        AlphaAnimation aa = new AlphaAnimation(0f, 1f);
        aa.setDuration(durationMs);
        aa.setInterpolator(new DecelerateInterpolator());
        aa.setFillAfter(true);
        iv.startAnimation(aa);
    }

    private static Bitmap fetch(String url) {
        // 两次尝试：失败自动重试一次（对象存储偶发抽风）
        for (int i = 0; i < 2; i++) {
            HttpURLConnection conn = null;
            try {
                URL u = new URL(url);
                conn = (HttpURLConnection) u.openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(15000);
                conn.setRequestProperty("User-Agent", Http.UA);
                // 海报来自对象存储（阿里云 OSS），不是原论坛站点。
                conn.setRequestProperty("Accept", "image/avif,image/webp,image/apng,*/*");
                int code = conn.getResponseCode();
                if (code != 200) continue;
                InputStream is = conn.getInputStream();
                Bitmap bmp = BitmapFactory.decodeStream(is);
                if (bmp != null) return bmp;
            } catch (Exception e) {
                // retry
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
        return null;
    }
}
