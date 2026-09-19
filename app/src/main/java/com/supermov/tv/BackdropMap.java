package com.supermov.tv;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 横版背景图（16:9 backdrop）查表。
 *
 * <h3>背景：库里根本没有横图字段</h3>
 * {@code v_movie_app} 只有竖版海报 {@code poster}（比例 ≈2:3），
 * {@code movie} 表 42 列里没有任何 screenshot / behind / backdrop 性质的列。
 * 但 SAM-CINEMA 首页的灵魂是「全屏横版背景 + Hero 文字面板」，所以必须另找横图来源。
 *
 * <h3>横图从哪来</h3>
 * 实测发现竖海报的 URL 里已经藏着 TMDB 的影片 id：
 * <pre>
 *   …/posters/2133.jpg       → TMDB movie id 2133
 *   …/posters/tv/253960.jpg  → TMDB tv    id 253960   （剧集必须在 /tv/ 下查，/movie/ 返回 0 张）
 * </pre>
 * 而 TMDB 的横图地址形如 {@code image.tmdb.org/t/p/w1280/<file_path>}，
 * 其中 {@code file_path} 是哈希、无法从 id 推出来，必须查一次。
 *
 * <p>TMDB 官方 API 需要 token（账号下的 {@code tmdb.800915.xyz} 代理实测
 * {@code /3/configuration} 返回 401），但 <b>图片侧 {@code /t/p/...} 免鉴权可用</b>，
 * 且 TMDB 网页端 {@code /{movie|tv}/{id}/images/backdrops} 免 token 就能刮到 file_path。</p>
 *
 * <p>所以策略是：<b>把「id → file_path」离线算好，随 APK 打包（assets/backdrops.json，8.7KB），
 * App 运行时只解析 id + 拼图片 URL</b>。这样零 token、零运行时刮页面、零数据侧改库，
 * 离线也能用。映射表当前覆盖 188/204 个唯一 TMDB id（92%），换算到界面口径是 217/321 部（68%）。</p>
 *
 * <h3>拿不到横图怎么办</h3>
 * 剩下 32% 的片子（论坛自传图、无 TMDB id、或 TMDB 上确实没有 backdrop）返回空串，
 * 调用方回落到竖海报 {@code centerCrop} 铺满 —— 与改造前的做法一致，不会开天窗。
 *
 * <h3>为什么映射表不做成数据库列</h3>
 * 数据侧只要在 {@code v_movie_app} 里加一列就能下发，但那样 App 必须配合做「探测-降级」
 * （老库没这列时整条 SQL 抛异常，被 catch 吞掉后表现为列表静默变空，见 {@code MovieStore.pinTopReady}）。
 * 放 assets 里则是纯粹的 App 侧自足：不动库、不动更新通道、不引入新的失败模式。
 */
public final class BackdropMap {

    private static final String TAG = "SupeMov";

    /**
     * 图片基址：走账号下已部署的 Cloudflare Worker 代理（{@code GamePCStudio/tmdbproxy}）。
     *
     * <p>为什么不直连 {@code https://image.tmdb.org/t/p/}：该域名在国内可达性很差，
     * 而 Worker 走 Cloudflare 边缘，且代理对不带 Authorization 的 GET 自带 10 分钟边缘缓存，
     * 同一张图第二次拉是 CDN 命中。</p>
     */
    public static final String IMAGE_BASE = "https://tmdb.800915.xyz/t/p/";

    /**
     * 取图档位。TMDB 横图只有 {@code w300 / w780 / w1280 / original} 四档。
     *
     * <p>选 {@code w1280}（1280×720，约 170~270KB）而不是 {@code original}：
     * 映射表里 134/188 的 original 是 <b>4K 3840×2160</b>，原图单张常达 1~3MB，
     * 解码成 ARGB_8888 要 33MB，在电视盒子上必然 OOM；而 w1280 是固定尺寸，
     * 1080p 屏上 1.5 倍放大，配上压暗层几乎看不出差别。</p>
     *
     * <p>顺带一提：库里原来的横图走的是对象存储的 {@code /backdrops/<id>.jpg}，
     * 只有 780×439 一档（实测 20 种更大尺寸路径全部 404）。换成本表后画质从
     * 780×439 提到 1280×720，且源自 4K 原图。</p>
     */
    public static final String SIZE = "w1280";

    /** 从海报 URL 里抠出「剧集/电影」+ TMDB id。组 1 = "tv/"（剧集时非空），组 2 = 数字 id。 */
    private static final Pattern RE_POSTER =
            Pattern.compile("/posters/(tv/)?(\\d+)\\.", Pattern.CASE_INSENSITIVE);

    private static volatile Map<String, String> map;
    private static volatile boolean loaded;

    private BackdropMap() {
    }

    /**
     * 载入 assets/backdrops.json。幂等，失败也只记日志（回落竖海报，不影响主流程）。
     * 首次调用会读 8.7KB 的 asset，之后走内存。
     */
    public static void init(Context ctx) {
        if (loaded) return;
        synchronized (BackdropMap.class) {
            if (loaded) return;
            Map<String, String> m = new HashMap<>();
            if (ctx != null) {
                InputStream is = null;
                try {
                    is = ctx.getAssets().open("backdrops.json");
                    ByteArrayOutputStream bos = new ByteArrayOutputStream(16384);
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
                    JSONObject o = new JSONObject(new String(bos.toByteArray(), "UTF-8"));
                    java.util.Iterator<String> it = o.keys();
                    while (it.hasNext()) {
                        String k = it.next();
                        String v = o.optString(k, "");
                        if (!k.isEmpty() && !v.isEmpty()) m.put(k, v);
                    }
                } catch (Throwable e) {
                    Log.d(TAG, "BackdropMap 载入 assets/backdrops.json 失败: " + e);
                } finally {
                    try {
                        if (is != null) is.close();
                    } catch (Throwable ignore) {
                    }
                }
            }
            map = m;
            loaded = true;
            Log.d(TAG, "BackdropMap 载入完成，映射条数 = " + m.size());
        }
    }

    /** 海报 URL → TMDB id（不是则空串）。 */
    public static String tmdbId(String posterUrl) {
        if (posterUrl == null || posterUrl.isEmpty()) return "";
        Matcher mm = RE_POSTER.matcher(posterUrl);
        return mm.find() ? mm.group(2) : "";
    }

    /** 该海报是不是剧集（URL 里带 {@code /posters/tv/}）。仅用于日志诊断。 */
    public static boolean isTv(String posterUrl) {
        if (posterUrl == null) return false;
        Matcher mm = RE_POSTER.matcher(posterUrl);
        return mm.find() && mm.group(1) != null;
    }

    /**
     * 海报 URL → 可直接给 ImageView 用的横版背景图 URL。
     *
     * <p>返回空串表示「查不到」（无 TMDB id / 映射表里没有 / 表没载入），
     * 调用方应当回落到竖海报。</p>
     */
    public static String backdropUrl(String posterUrl) {
        String id = tmdbId(posterUrl);
        if (id.isEmpty()) return "";
        Map<String, String> m = map;
        if (m == null || m.isEmpty()) return "";
        String path = m.get(id);
        if (path == null || path.isEmpty()) return "";
        return IMAGE_BASE + SIZE + "/" + path;
    }

    /** 映射条数（诊断用）。 */
    public static int size() {
        Map<String, String> m = map;
        return m == null ? 0 : m.size();
    }
}
