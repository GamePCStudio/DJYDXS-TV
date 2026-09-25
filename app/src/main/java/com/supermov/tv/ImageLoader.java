package com.supermov.tv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 海报异步加载：内存 LRU + 磁盘缓存 + 解码期降采样。
 *
 * <p><b>为什么不能「下下来直接 setImageBitmap」：</b>艾美对象存储的
 * {@code *_preview.jpg} 实测有 2000×3000 / 4.2 MB 这种规格（15339 霸主），解成
 * ARGB_8888 是 24 MB 一张 Bitmap；海报墙一屏 7 列就是 160 MB+，电视那几百 MB 堆
 * 根本装不下 —— 表现为 GC 抖动、整墙出图慢、甚至 OOM。所以这里先在解码时用
 * inSampleSize 按显示尺寸裁（2000 px → 500 px，内存降到约 1/16），再落磁盘缓存，
 * 往回翻屏就不会重新走网络。</p>
 */
public final class ImageLoader {
    private static final String TAG = "SupeMov";
    /**
     * 没测到控件宽度时的降采样目标宽。艾美预览海报实测宽都是 769 px（高 1000~2000），
     * 目标取 384 正好落在 inSampleSize=2 这一档：一张 769×2000 从 6.1 MB 降到 1.5 MB。
     */
    private static final int MAX_DECODE_W = 384;
    private static final int THREADS = 6;
    /** 磁盘缓存上限。超过就按最后访问时间删最旧的。 */
    private static final long DISK_CAP = 96L * 1024 * 1024;
    private static final int CONNECT_TIMEOUT_MS = 6000;
    private static final int READ_TIMEOUT_MS = 12000;
    /** 拉取失败的地址短时间内不再重试，避免来回滚动时把线程全占在死链上。 */
    private static final long FAIL_TTL_MS = 5 * 60 * 1000;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService POOL = Executors.newFixedThreadPool(THREADS);
    private static final Map<String, Long> FAILED = new ConcurrentHashMap<>();
    private static final AtomicLong WRITES = new AtomicLong();

    private static volatile LruCache<String, Bitmap> mem;
    private static volatile File cacheDir;

    private ImageLoader() {}

    public static void load(String url, ImageView iv) {
        load(url, iv, 0);
    }

    /** targetW 传 0 表示按 {@link #MAX_DECODE_W} 裁；传实测像素宽则裁得更小、更省。 */
    public static void load(String url, final ImageView iv, int targetW) {
        if (url == null || url.isEmpty() || iv == null) return;
        final String key = url;
        Bitmap cached = peek(key);
        if (cached != null) {
            iv.setTag(key);
            iv.setImageBitmap(cached);
            return;
        }
        iv.setTag(key);
        final int want = targetW > 0 ? Math.min(targetW, MAX_DECODE_W) : MAX_DECODE_W;
        final Context app = iv.getContext().getApplicationContext();
        POOL.execute(() -> {
            Bitmap bmp = obtain(app, key, want);
            MAIN.post(() -> {
                // 只有该 ImageView 还在显示这张图才设置（回收复用时 tag 已经变了）
                if (bmp != null && key.equals(iv.getTag())) iv.setImageBitmap(bmp);
            });
        });
    }

    private static LruCache<String, Bitmap> cache() {
        LruCache<String, Bitmap> m = mem;
        if (m != null) return m;
        synchronized (ImageLoader.class) {
            if (mem == null) {
                long budget = Runtime.getRuntime().maxMemory() / 8;
                int max = (int) Math.max(8L << 20, Math.min(budget, 48L << 20));
                mem = new LruCache<String, Bitmap>(max) {
                    @Override
                    protected int sizeOf(String k, Bitmap b) {
                        return b == null ? 0 : b.getAllocationByteCount();
                    }
                };
            }
            return mem;
        }
    }

    private static Bitmap peek(String key) {
        Bitmap b = cache().get(key);
        return b != null && !b.isRecycled() ? b : null;
    }

    /** 内存 → 磁盘 → 网络。全走线程池，可以在工作线程里阻塞。 */
    private static Bitmap obtain(Context app, String url, int targetW) {
        Bitmap b = peek(url);
        if (b != null) return b;
        Long failAt = FAILED.get(url);
        if (failAt != null && System.currentTimeMillis() - failAt < FAIL_TTL_MS) return null;
        byte[] raw = readDisk(app, url);
        if (raw == null) {
            raw = download(url);
            if (raw == null) {
                FAILED.put(url, System.currentTimeMillis());
                return null;
            }
            FAILED.remove(url);
            writeDisk(app, url, raw);
        }
        Bitmap bmp = decode(raw, targetW);
        if (bmp != null) cache().put(url, bmp);
        return bmp;
    }

    /** 先只读头拿尺寸，再按目标宽取 2 的幂次降采样（硬件解码路径上最快的档位）。 */
    private static Bitmap decode(byte[] raw, int targetW) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(raw, 0, raw.length, bounds);
        BitmapFactory.Options opt = new BitmapFactory.Options();
        opt.inPreferredConfig = Bitmap.Config.ARGB_8888;
        opt.inSampleSize = sampleSize(bounds.outWidth, targetW);
        try {
            return BitmapFactory.decodeByteArray(raw, 0, raw.length, opt);
        } catch (Throwable e) {
            Log.d(TAG, "海报解码失败: " + e);
            return null;
        }
    }

    static int sampleSize(int w, int targetW) {
        if (w <= 0 || targetW <= 0) return 1;
        int s = 1;
        while (w / (s * 2) >= targetW && s < 16) s *= 2;
        return s;
    }

    private static byte[] download(String url) {
        for (int attempt = 0; attempt < 2; attempt++) {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(READ_TIMEOUT_MS);
                conn.setRequestProperty("User-Agent", Http.UA);
                // 海报来自对象存储（艾美 sto.imovie.com.cn），不是原论坛站点，
                // 所以不再塞论坛 Referer；将来若开了防盗链，在这里换成对应站点 origin。
                conn.setRequestProperty("Accept", "image/avif,image/webp,image/apng,*/*");
                if (conn.getResponseCode() != 200) continue;
                ByteArrayOutputStream bos = new ByteArrayOutputStream(
                        Math.max(8192, conn.getContentLength()));
                InputStream is = conn.getInputStream();
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
                is.close();
                byte[] b = bos.toByteArray();
                if (b.length > 512) return b;        // 太小的一律当坏图丢弃
            } catch (Exception e) {
                // 换 attempt 再试一次
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
        return null;
    }

    // ==================== 磁盘缓存 ====================

    private static File dir(Context app) {
        File d = cacheDir;
        if (d != null) return d;
        File n = new File(app.getFilesDir(), "posters");
        if (!n.isDirectory() && !n.mkdirs()) Log.d(TAG, "posters 目录建不出来: " + n);
        cacheDir = n;
        return n;
    }

    private static File file(Context app, String url) {
        return new File(dir(app), sha1(url));
    }

    private static byte[] readDisk(Context app, String url) {
        File f = file(app, url);
        if (!f.isFile() || f.length() <= 512) return null;
        try {
            InputStream is = new FileInputStream(f);
            byte[] b = new byte[(int) f.length()];
            int off = 0;
            while (off < b.length) {
                int n = is.read(b, off, b.length - off);
                if (n <= 0) break;
                off += n;
            }
            is.close();
            f.setLastModified(System.currentTimeMillis());   // 命中即「最近用过」
            return off == b.length ? b : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static void writeDisk(Context app, String url, byte[] raw) {
        File f = file(app, url);
        try {
            FileOutputStream os = new FileOutputStream(f);
            os.write(raw);
            os.close();
        } catch (Exception e) {
            Log.d(TAG, "海报写盘失败: " + e);
            return;
        }
        if (WRITES.incrementAndGet() % 16 == 0) trimDisk(app);
    }

    /** 简单按「最后访问时间的早晚」淘汰，不额外维护索引文件。 */
    static void trimDisk(Context app) {
        File d = dir(app);
        File[] fs = d.listFiles();
        if (fs == null || fs.length == 0) return;
        long total = 0;
        for (File f : fs) total += f.length();
        if (total <= DISK_CAP) return;
        ArrayList<File> list = new ArrayList<>();
        Collections.addAll(list, fs);
        Collections.sort(list, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return Long.compare(a.lastModified(), b.lastModified());
            }
        });
        long freed = 0;
        long need = total - DISK_CAP;
        for (File f : list) {
            if (freed >= need) break;
            long len = f.length();
            if (f.delete()) freed += len;
        }
        Log.d(TAG, "海报缓存超限，已释放 " + (freed >> 10) + " KiB");
    }

    private static String sha1(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format(java.util.Locale.ROOT, "%02x", b & 0xFF));
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(s.hashCode());
        }
    }
}
