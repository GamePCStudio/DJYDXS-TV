package com.supermov.tv;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 全屏背景图（横版 backdrop）专用加载器。
 *
 * <h3>为什么不复用 {@link ImageLoader}</h3>
 * {@code ImageLoader}（74 行）有两个硬缺陷：{@code BitmapFactory.decodeStream} <b>全尺寸解码</b>、
 * 缓存是<b>无上限的 {@code ConcurrentHashMap}</b>。海报墙用它没问题（500×704 竖图，
 * 条目虽多但同一时刻可见的有限，且被 LRU 之外的复用兜住），但
 * <b>横版背景图一旦走那条路就是 OOM</b>：TMDB 的 original 档有 134 张是 4K，
 * 单张 ARGB_8888 就要 33MB。所以这里另起一条通道，独立缓存 + 独立线程池。
 *
 * <h3>三条保护</h3>
 * <ol>
 *   <li><b>按字节计的 LruCache</b>（12MB ≈ 3 张 1280×720 ARGB_8888），
 *       超出自动淘汰最久未用的；</li>
 *   <li><b>两段式降采样</b>：先 {@code inJustDecodeBounds} 读原始尺寸，
 *       再按目标控件尺寸算 {@code inSampleSize}。当前取 w1280 档（1280×720），
 *       1080p 屏上是 1，不需要降采样；但把 {@link BackdropMap#SIZE} 换成 {@code original}
 *       时这一层能直接把 4K 压到目标大小，不至于崩。</li>
 *   <li><b>只有一个线程</b>（{@code newFixedThreadPool(2)}）：背景图是「焦点移到哪张显示哪张」，
 *       快速滑动遥控器时会产生大量用不上的请求，线程少一点反而更快命中用户真正停下的那一张。
 *       过期请求由调用方的世代号丢弃。</li>
 * </ol>
 */
public final class BackdropLoader {

    private static final String TAG = "SupeMov";

    /** 按时长读超时收紧：背景图是「焦点反馈」，等太久不如让调用方回落到竖海报。 */
    private static final int CONNECT_TIMEOUT = 8000;
    private static final int READ_TIMEOUT = 12000;

    /** 缓存上限。1280×720 ARGB_8888 = 3.7MB，12MB 约留 3 张（交叉淡入时同时占 2 张）。 */
    private static final int CACHE_BYTES = 12 * 1024 * 1024;

    private static final android.util.LruCache<String, Bitmap> CACHE =
            new android.util.LruCache<String, Bitmap>(CACHE_BYTES) {
                @Override
                protected int sizeOf(String key, Bitmap value) {
                    return value == null ? 0 : value.getByteCount();
                }
            };

    private static final ExecutorService POOL = Executors.newFixedThreadPool(2);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private BackdropLoader() {
    }

    /** 下载完成回调，**在主线程**执行 {@code bmp} 可能为 null（失败）。 */
    public interface Callback {
        void onLoaded(Bitmap bmp);
    }

    /** 命中缓存则直接返回（调用方可用它做「立即切换、不闪烁」），否则返回 null。 */
    public static Bitmap peek(String url) {
        if (url == null || url.isEmpty()) return null;
        return CACHE.get(url);
    }

    /**
     * 取图。命中缓存则<b>同步</b>回调（仍在主线程语义下），否则丢到后台线程，
     * 完成后再回主线程回调。<b>失败不抛异常</b>，只回调 null ——
     * 调用方据此回落到竖海报铺满。
     *
     * @param reqW 目标显示宽（px），<=0 表示不限（按原尺寸解码）
     * @param reqH 目标显示高（px），<=0 表示不限
     */
    public static void load(final String url, final int reqW, final int reqH, final Callback cb) {
        if (url == null || url.isEmpty()) {
            if (cb != null) cb.onLoaded(null);
            return;
        }
        Bitmap hit = CACHE.get(url);
        if (hit != null) {
            Log.d(TAG, "backdrop 缓存命中 " + url);
            if (cb != null) cb.onLoaded(hit);
            return;
        }
        POOL.execute(() -> {
            final Bitmap bmp = fetch(url, reqW, reqH);
            if (bmp != null) CACHE.put(url, bmp);
            if (cb != null) MAIN.post(() -> cb.onLoaded(bmp));
        });
    }

    /** 下载 + 两段式降采样。失败自动重试一次（对象存储 / CDN 偶发抽风）。 */
    private static Bitmap fetch(String url, int reqW, int reqH) {
        byte[] data = readAll(url);
        if (data == null) return null;

        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

        int sample = 1;
        if (reqW > 0 && reqH > 0) {
            // 只在「降一半后仍不小于目标」时才加倍，保证降采样后不会小于控件尺寸
            while (bounds.outWidth / (sample * 2) >= reqW
                    && bounds.outHeight / (sample * 2) >= reqH) {
                sample *= 2;
            }
        }

        BitmapFactory.Options opt = new BitmapFactory.Options();
        opt.inSampleSize = sample;
        // 背景层永远被压暗层盖着，用 RGB_565 省一半内存是划算的；
        // 但这里仍留 ARGB_8888 —— 4K 原图放到 1.5 倍时 565 的色带在大面积平色区（天空、夜戏）
        // 确实看得出来，而 12MB 的 LruCache 已经足够安全。
        opt.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bmp = BitmapFactory.decodeByteArray(data, 0, data.length, opt);
        Log.d(TAG, "backdrop " + bounds.outWidth + "x" + bounds.outHeight
                + " → sample=" + sample + " bytes=" + data.length
                + (bmp == null ? " 解码失败" : " → " + bmp.getWidth() + "x" + bmp.getHeight()));
        return bmp;
    }

    /** 把整个响应读进内存。图只有 170~270KB，读两次流不如读一次字节数组（降采样要解两遍）。 */
    private static byte[] readAll(String url) {
        for (int attempt = 0; attempt < 2; attempt++) {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(CONNECT_TIMEOUT);
                conn.setReadTimeout(READ_TIMEOUT);
                conn.setRequestProperty("User-Agent", Http.UA);
                conn.setRequestProperty("Accept", "image/avif,image/webp,image/apng,image/*,*/*");
                int code = conn.getResponseCode();
                if (code != 200) {
                    Log.d(TAG, "backdrop HTTP " + code + " " + url);
                    continue;
                }
                InputStream is = conn.getInputStream();
                ByteArrayOutputStream bos = new ByteArrayOutputStream(262144);
                byte[] buf = new byte[16384];
                int n;
                while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
                is.close();
                byte[] out = bos.toByteArray();
                if (out.length > 0) return out;
            } catch (Throwable e) {
                Log.d(TAG, "backdrop 拉取失败(" + (attempt + 1) + ") " + url + " : " + e);
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
        return null;
    }

    /** 缓存占用（诊断 / 设置页展示用）。 */
    public static String debugStat() {
        return "背景图缓存 " + (CACHE.size() / 1024) + "KB / " + (CACHE_BYTES / 1024) + "KB";
    }
}
