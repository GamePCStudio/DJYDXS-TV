package com.supermov.tv;

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
                // 旧代码在这里硬塞论坛 Referer，那些请求其实一直没起作用；
                // 若将来 OSS 开了防盗链，这里改成对应站点的 origin 即可。
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
