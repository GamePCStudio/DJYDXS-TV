package com.djydxs.tv;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.widget.ImageView;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 轻量图片异步加载（内存缓存，无第三方依赖）。 */
public final class ImageLoader {
    private static final Map<String, Bitmap> CACHE = new ConcurrentHashMap<>();
    private static final ExecutorService POOL = Executors.newFixedThreadPool(6);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private ImageLoader() {}

    public static void load(String url, ImageView iv) {
        Bitmap cached = CACHE.get(url);
        if (cached != null) {
            iv.setImageBitmap(cached);
            return;
        }
        iv.setTag(url);
        POOL.execute(() -> {
            Bitmap bmp = fetch(url);
            if (bmp != null) {
                CACHE.put(url, bmp);
            }
            MAIN.post(() -> {
                // 只有该 ImageView 还在显示这张图才设置
                Object tag = iv.getTag();
                if (bmp != null && url.equals(tag)) {
                    iv.setImageBitmap(bmp);
                }
            });
        });
    }

    private static Bitmap fetch(String url) {
        // 两次尝试：失败自动重试一次（论坛图床偶发抽风）
        for (int i = 0; i < 2; i++) {
            HttpURLConnection conn = null;
            try {
                URL u = new URL(url);
                conn = (HttpURLConnection) u.openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(15000);
                conn.setRequestProperty("User-Agent", Http.UA);
                conn.setRequestProperty("Referer", "https://4kzimu.top/");
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
