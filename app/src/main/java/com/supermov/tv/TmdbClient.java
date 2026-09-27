package com.supermov.tv;

import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Random;

/**
 * TMDB v3 客户端（生成 NFO / 海报的唯一网络出口）。
 *
 * <h3>为什么不用 {@link Http}</h3>
 * 两点必须自己来：① {@code Http} 会把完整 URL 打进 logcat，而 v3 的 {@code api_key} 就在 URL 里
 * —— 密钥不能进日志；② 海报是二进制，{@code Http} 只回字符串。
 * 所以这里的日志一律只打「状态码 + 路径」，不打查询串。
 *
 * <h3>域名</h3>
 * 主用国内可达的读缓存镜像，失败再退官方域名（镜像列表与片源那套 JS 源保持一致）。
 *
 * <h3>节流</h3>
 * 全局串行 + 每次请求之间隔 0.4~0.9 秒，429 按 {@code Retry-After} 退避并封顶 10 秒。
 * 补生成是一批几百部的循环，不做限速会直接把 TMDB 打到风控。
 */
public final class TmdbClient {

    private static final String TAG = "SupeMov";

    private static final String[] API_BASES = {
            "https://tmdb.s8k.ccwu.cc/3",
            "https://api.tmdb.org/3",
            "https://api.themoviedb.org/3",
    };

    private static final String[] IMAGE_BASES = {
            "https://tmdb.s8k.ccwu.cc/t/p/",
            "https://image.tmdb.org/t/p/",
    };

    private static final String UA = "Mozilla/5.0 (Linux; Android 9; SDK) AppleWebKit/537.36"
            + " (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36";

    private static final Random RAND = new Random();
    private static long lastCallAt;

    private TmdbClient() {}

    /** 没填密钥就什么都别做（密钥只存在本机设置里，不随 APK 发布）。 */
    public static boolean ready() {
        return !Settings.tmdbApiKey().isEmpty();
    }

    /** GET 一个 v3 路径（如 {@code /movie/438799}）；params 里不用带 api_key。失败返回 null。 */
    public static JSONObject get(String path, Map<String, String> params) {
        String key = Settings.tmdbApiKey();
        if (key.isEmpty()) return null;
        String query = buildQuery(params, key);
        for (String base : API_BASES) {
            for (int attempt = 0; attempt < 2; attempt++) {
                throttle();
                HttpURLConnection conn = null;
                try {
                    conn = open(base + path + query, key);
                    int code = conn.getResponseCode();
                    if (code == 429) {
                        long waitS = Math.min(10L, retryAfter(conn));
                        Log.d(TAG, "TMDB 429 限流，等 " + waitS + "s 再试 " + path);
                        sleep(waitS * 1000L + rand(300));
                        continue;
                    }
                    if (code >= 500) {
                        sleep(500 + rand(500));
                        continue;
                    }
                    String body = read(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
                    if (code == 404) return null;    // TMDB 明确说没这个东西，换域名也一样
                    if (code >= 400) {
                        Log.d(TAG, "TMDB " + code + " " + path + " :: " + brief(body));
                        return null;
                    }
                    JSONObject o = new JSONObject(body);
                    if (o.has("success") && !o.optBoolean("success", true)) {
                        Log.d(TAG, "TMDB 返回 success=false " + path + " :: " + brief(body));
                        return null;
                    }
                    return o;
                } catch (Throwable e) {
                    Log.d(TAG, "TMDB 请求异常 " + base + path + " :: " + e);
                } finally {
                    if (conn != null) conn.disconnect();
                }
            }
        }
        return null;
    }

    /** 下载 TMDB 图片（{@code path} 形如 {@code /abc123.jpg}），失败返回 null。 */
    public static byte[] getImage(String path, String size) {
        if (path == null || path.isEmpty() || !path.startsWith("/")) return null;
        for (String base : IMAGE_BASES) {
            throttle();
            HttpURLConnection conn = null;
            try {
                conn = openNoKey(base + size + path);
                int code = conn.getResponseCode();
                if (code != 200) {
                    Log.d(TAG, "TMDB 图片 " + code + " " + path);
                    continue;
                }
                byte[] data = readBytes(conn.getInputStream());
                // 小于 3KB 的「图」基本是占位错误或半张图，当失败处理，别落一张坏图
                if (data == null || data.length < 3072) continue;
                return data;
            } catch (Throwable e) {
                Log.d(TAG, "TMDB 图片异常 " + base + path + " :: " + e);
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
        return null;
    }

    /** 图片的完整外链地址（写进 NFO 的 &lt;thumb&gt;，播放器读不到本地文件时还能直接拉图）。 */
    public static String imageUrl(String path, String size) {
        if (path == null || path.isEmpty() || !path.startsWith("/")) return "";
        return IMAGE_BASES[0] + size + path;
    }

    // ==================== 内部 ====================

    /** 全局节流：串行 + 0.4~0.9 秒抖动。 */
    private static synchronized void throttle() {
        long min = 400L + rand(500);
        long now = System.currentTimeMillis();
        long wait = lastCallAt + min - now;
        if (wait > 0) sleep(wait);
        lastCallAt = System.currentTimeMillis();
    }

    private static HttpURLConnection open(String url, String key) throws Exception {
        HttpURLConnection conn = openNoKey(url);
        // v4 令牌（三段带点号的长串）走请求头；32 位十六进制的 v3 key 只能走 api_key 参数
        if (key.indexOf('.') > 0) {
            conn.setRequestProperty("Authorization", "Bearer " + key);
        }
        return conn;
    }

    private static HttpURLConnection openNoKey(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(12000);
        conn.setReadTimeout(20000);
        conn.setRequestMethod("GET");
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", UA);
        conn.setRequestProperty("Accept", "application/json,image/*,*/*;q=0.8");
        conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
        return conn;
    }

    private static String buildQuery(Map<String, String> params, String key) {
        StringBuilder sb = new StringBuilder("?");
        if (key.indexOf('.') <= 0) sb.append("api_key=").append(enc(key));
        if (params != null) {
            for (Map.Entry<String, String> e : params.entrySet()) {
                if (sb.length() > 1) sb.append('&');
                sb.append(e.getKey()).append('=').append(enc(e.getValue()));
            }
        }
        return sb.toString();
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    private static long retryAfter(HttpURLConnection conn) {
        try {
            String h = conn.getHeaderField("Retry-After");
            if (h == null) return 2;
            String t = h.trim();
            if (t.matches("\\d+")) return Long.parseLong(t);
            // HTTP-date 形式的 Retry-After：URLConnection 已经帮我们解成毫秒
            long at = conn.getHeaderFieldDate("Retry-After", 0L);
            long secs = at <= 0 ? 2 : (at - System.currentTimeMillis()) / 1000L;
            return secs > 0 ? secs : 2;
        } catch (Throwable e) {
            return 2;
        }
    }

    private static String read(InputStream is) {
        byte[] b = readBytes(is);
        return b == null ? "" : new String(b, StandardCharsets.UTF_8);
    }

    private static byte[] readBytes(InputStream is) {
        if (is == null) return null;
        try (InputStream in = is) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
            byte[] buf = new byte[16384];
            int n;
            long total = 0;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                total += n;
                if (total > 12L * 1024 * 1024) return null;    // 原图再大也不该进电视
            }
            return out.toByteArray();
        } catch (Throwable e) {
            return null;
        }
    }

    private static String brief(String s) {
        if (s == null) return "";
        return s.length() > 160 ? s.substring(0, 160) + "…" : s;
    }

    private static int rand(int bound) {
        synchronized (RAND) {
            return RAND.nextInt(bound);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
