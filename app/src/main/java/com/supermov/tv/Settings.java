package com.supermov.tv;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.File;

/** 应用设置：百度授权状态 + 转存目录 + 下载目录。 */
public final class Settings {
    private static final String FILE = "supermov_settings";
    private static final String K_SAVE_DIR = "save_dir";
    private static final String K_DL_DIR = "download_dir";
    private static final String K_BAIDU_USER = "baidu_user";
    private static final String K_LAST_TRANSFER = "last_transfer";
    private static final String K_LAST_TITLE = "last_transfer_title";
    private static final String K_LAST_PATH = "last_transfer_path";
    private static final String K_ASKED_ALL_FILES = "asked_all_files";

    private static SharedPreferences p;
    /** Application Context：缺省下载目录要按当前权限状态现算，不能只在 init 时算一次。 */
    private static Context appCtx;

    private Settings() {}

    private static SharedPreferences p() {
        return p;
    }

    public static void init(Context ctx) {
        if (p == null) {
            Context app = ctx.getApplicationContext();
            appCtx = app;
            p = app.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        }
    }

    public static String saveDir() {
        return p().getString(K_SAVE_DIR, "/超级影库");
    }

    public static void setSaveDir(String dir) {
        p().edit().putString(K_SAVE_DIR, dir).apply();
    }

    /**
     * 下载落盘目录（绝对路径）。
     *
     * <p>没设置过时用缺省落点：v1.20 起是公共存储 {@code /sdcard/超级影库}（用盒子自带的
     * 文件管理器 / Kodi 都能直接看到），没拿到「所有文件访问」权限时退回 App 专属目录。
     * <b>每次都现算</b> —— 用户可能刚在设置里授了权，缓存住就还是老路径。</p>
     */
    public static String downloadDir() {
        String d = p() == null ? "" : p().getString(K_DL_DIR, "");
        if (d != null && !d.isEmpty()) return d;
        if (appCtx != null) return Storage.defaultDir(appCtx);
        return Storage.PUBLIC_ROOT;
    }

    public static void setDownloadDir(String dir) {
        p().edit().putString(K_DL_DIR, dir == null ? "" : dir).apply();
    }

    /** 下载目录的简短展示名（TV 上路径太长，只显示末尾两段）。 */
    public static String downloadDirShort() {
        String d = downloadDir();
        try {
            File f = new File(d);
            String n = f.getName();
            File par = f.getParentFile();
            if (par != null && par.getName() != null && !par.getName().isEmpty()) {
                return par.getName() + "/" + n;
            }
            return n;
        } catch (Throwable e) {
            return d;
        }
    }

    public static String baiduUser() {
        return p().getString(K_BAIDU_USER, "");
    }

    public static void setBaiduUser(String name) {
        p().edit().putString(K_BAIDU_USER, name == null ? "" : name).apply();
    }

    public static String lastTransfer() {
        return p().getString(K_LAST_TRANSFER, "");
    }

    public static void setLastTransfer(String text) {
        p().edit().putString(K_LAST_TRANSFER, text == null ? "" : text).apply();
    }

    /** 是否已经引导过「所有文件访问」授权（避免每次启动都弹）。 */
    public static boolean askedAllFiles() {
        return p().getBoolean(K_ASKED_ALL_FILES, false);
    }

    public static void setAskedAllFiles(boolean asked) {
        p().edit().putBoolean(K_ASKED_ALL_FILES, asked).apply();
    }

    /** 把最近一次转存结果序列化成 "time|dir|ok" 形式。 */
    public static void recordTransfer(String dir, boolean ok) {
        recordTransfer(dir, ok, "", "");
    }

    /**
     * 记下最近一次转存：除了目录与成败，还记**片名 + 网盘落地路径**。
     *
     * <p>为什么要记片名：以前只看「15 秒内转过存」这一个时间窗，于是「刚转存完 A，马上
     * 点 B」时 B 也以为自己刚才转过存 —— 转存被跳过，最后拿到的是 A 的文件。现在必须
     * 片名一致才认。</p>
     *
     * <p>为什么要记落地路径：分享里的文件夹名和片名经常不一样（被改过名、被顺延成
     * 「(2)」），按片名在网盘里搜不到时，用这条路径精确回查。</p>
     */
    public static void recordTransfer(String dir, boolean ok, String title, String netdiskPath) {
        try {
            JSONObject o = new JSONObject();
            o.put("time", System.currentTimeMillis());
            o.put("dir", dir);
            o.put("ok", ok);
            setLastTransfer(o.toString());
        } catch (Exception ignored) {
        }
        p().edit()
                .putString(K_LAST_TITLE, title == null ? "" : title)
                .putString(K_LAST_PATH, netdiskPath == null ? "" : netdiskPath)
                .apply();
    }

    /** 最近一次转存的片名（用来确认「那一次转的就是这部片」）。 */
    public static String lastTransferTitle() {
        return p().getString(K_LAST_TITLE, "");
    }

    /** 最近一次转存在网盘里的落地绝对路径（可能为空）。 */
    public static String lastTransferPath() {
        return p().getString(K_LAST_PATH, "");
    }

    // ---------- 后台下载限速（v1.18）----------

    private static final String K_DL_LIMIT = "dl_limit_mbps";
    /** 后台下载限速缺省值（Mbps） */
    public static final int DL_LIMIT_DEFAULT = 20;
    /** 后台下载限速上限（Mbps） */
    public static final int DL_LIMIT_MAX = 40;
    /** 后台下载限速下限（Mbps） */
    public static final int DL_LIMIT_MIN = 1;

    /**
     * 后台下载限速（Mbps）。
     *
     * <p><b>只管后台</b>：下载管理页在前台时是不限速的（用户正看着进度，不该掐他带宽）；
     * 一旦离开那个页面 —— 去浏览海报、回桌面、或者退出程序后的后台下载 —— 就按这个值限速。</p>
     */
    public static int dlLimitMbps() {
        if (p() == null) return DL_LIMIT_DEFAULT;
        int v = p().getInt(K_DL_LIMIT, DL_LIMIT_DEFAULT);
        if (v < DL_LIMIT_MIN) v = DL_LIMIT_MIN;
        if (v > DL_LIMIT_MAX) v = DL_LIMIT_MAX;
        return v;
    }

    public static void setDlLimitMbps(int mbps) {
        int v = mbps;
        if (v < DL_LIMIT_MIN) v = DL_LIMIT_MIN;
        if (v > DL_LIMIT_MAX) v = DL_LIMIT_MAX;
        if (p() == null) return;
        p().edit().putInt(K_DL_LIMIT, v).apply();
    }

    /**
     * 后台限速换算成「字节/秒」给限速器用。
     *
     * <p>单位换算别弄反：设置里填的是 {@code Mbps}（比特），下载器按字节读写，
     * {@code 1MB/s = 8Mbps} → {@code B/s = Mbps × 1000000 ÷ 8}。</p>
     */
    public static long dlLimitBps() {
        return dlLimitMbps() * 1000000L / 8;
    }

    // ---------- 在线播放带宽提示（v1.20）----------

    private static final String K_SKIP_ONLINE_WARN = "skip_online_warn";

    /** 是否已勾过「不再提示」：勾过之后详情页点在线播放不再弹带宽提示。 */
    public static boolean skipOnlineWarn() {
        if (p() == null) return false;
        try {
            return p().getBoolean(K_SKIP_ONLINE_WARN, false);
        } catch (Throwable e) {
            return false;
        }
    }

    public static void setSkipOnlineWarn(boolean skip) {
        if (p() == null) return;
        p().edit().putBoolean(K_SKIP_ONLINE_WARN, skip).apply();
    }

    // ---------- 播放进度记忆（v1.15）----------

    /** 进度记录的 key 前缀，便于整体淘汰 */
    private static final String K_POS = "pos|";
    /** 超过这个时间没碰过的记录当过期 */
    private static final long POS_TTL_MS = 60L * 24 * 3600 * 1000;
    /** 记录条数上限，超了按时间淘汰到 POS_KEEP 条 */
    private static final int POS_MAX = 400;
    private static final int POS_KEEP = 150;

    /** 记下某部片看到哪儿。key 由调用方给（本地路径 / 网盘路径+大小）。 */
    public static void savePos(String key, long posMs, long durMs) {
        if (p() == null || key == null || key.isEmpty()) return;
        try {
            p().edit().putString(K_POS + key,
                    posMs + "|" + durMs + "|" + System.currentTimeMillis()).apply();
        } catch (Throwable ignored) {
        }
        prunePos();
    }

    /**
     * 读回某部片的进度。
     *
     * @return {positionMs, durationMs, savedAtMs}；没有记录或已过期返回 null
     */
    public static long[] loadPos(String key) {
        if (p() == null || key == null || key.isEmpty()) return null;
        String v;
        try {
            v = p().getString(K_POS + key, "");
        } catch (Throwable e) {
            return null;
        }
        if (v == null || v.isEmpty()) return null;
        try {
            String[] a = v.split("\\|");
            if (a.length < 3) return null;
            long pos = Long.parseLong(a[0]);
            long dur = Long.parseLong(a[1]);
            long at = Long.parseLong(a[2]);
            if (System.currentTimeMillis() - at > POS_TTL_MS) return null;
            return new long[]{pos, dur, at};
        } catch (Throwable e) {
            return null;
        }
    }

    /** 清掉某部片的进度（看完了 / 想从头看）。 */
    public static void clearPos(String key) {
        if (p() == null || key == null || key.isEmpty()) return;
        try {
            p().edit().remove(K_POS + key).apply();
        } catch (Throwable ignored) {
        }
    }

    /**
     * 淘汰进度记录。
     *
     * <p>SharedPreferences 是把整份文件读进内存的，记录无限涨会拖慢启动 ——
     * 所以先删过期（60 天），条数还超上限就按保存时间从新到旧留 POS_KEEP 条。</p>
     */
    private static void prunePos() {
        try {
            java.util.Map<String, ?> all = p().getAll();
            java.util.List<String> keys = new java.util.ArrayList<String>();
            java.util.List<Long> times = new java.util.ArrayList<Long>();
            for (java.util.Map.Entry<String, ?> en : all.entrySet()) {
                String k = en.getKey();
                if (k == null || !k.startsWith(K_POS)) continue;
                long at = 0L;
                Object v = en.getValue();
                if (v instanceof String) {
                    String[] a = ((String) v).split("\\|");
                    if (a.length >= 3) {
                        try {
                            at = Long.parseLong(a[2]);
                        } catch (Throwable ignored) {
                        }
                    }
                }
                keys.add(k);
                times.add(at);
            }
            if (keys.size() <= POS_MAX) return;

            long now = System.currentTimeMillis();
            java.util.List<Integer> alive = new java.util.ArrayList<Integer>();
            java.util.List<String> doomed = new java.util.ArrayList<String>();
            for (int i = 0; i < keys.size(); i++) {
                if (now - times.get(i) > POS_TTL_MS) doomed.add(keys.get(i));
                else alive.add(i);
            }
            if (alive.size() > POS_KEEP) {
                final java.util.List<Long> t = times;
                java.util.Collections.sort(alive, new java.util.Comparator<Integer>() {
                    @Override
                    public int compare(Integer a, Integer b) {
                        return Long.compare(t.get(a), t.get(b));   // 由旧到新
                    }
                });
                for (int i = 0; i < alive.size() - POS_KEEP; i++) doomed.add(keys.get(alive.get(i)));
            }
            if (doomed.isEmpty()) return;
            SharedPreferences.Editor ed = p().edit();
            for (String k : doomed) ed.remove(k);
            ed.apply();
        } catch (Throwable ignored) {
        }
    }

    // ==================== 播放 · 音频（v1.25 · DJYDXS2Nexio）====================
    //
    // 这一组设置只影响「声音怎么出 HDMI」：
    //   自动 / 源码直通（位流原样送功放）/ 强制解码（一律解成 PCM）。
    // 具体怎么落到 media3 上，全部在 PlaybackEngine 里实现，这里只存值。

    private static final String K_AUDIO_MODE = "audio_mode";
    /** 跟随设备能力（缺省）：不干预，功放能点亮就点亮 */
    public static final int AUDIO_MODE_AUTO = 0;
    /** 源码直通：伪造能力表强制把位流送出去 */
    public static final int AUDIO_MODE_PASSTHROUGH = 1;
    /** 强制解码：全部解成 PCM 再输出（盒子自身解码） */
    public static final int AUDIO_MODE_PCM = 2;

    public static int audioMode() {
        if (p() == null) return AUDIO_MODE_AUTO;
        int v;
        try {
            v = p().getInt(K_AUDIO_MODE, AUDIO_MODE_AUTO);
        } catch (Throwable e) {
            return AUDIO_MODE_AUTO;
        }
        if (v < AUDIO_MODE_AUTO || v > AUDIO_MODE_PCM) v = AUDIO_MODE_AUTO;
        return v;
    }

    public static void setAudioMode(int mode) {
        if (p() == null) return;
        if (mode < AUDIO_MODE_AUTO || mode > AUDIO_MODE_PCM) mode = AUDIO_MODE_AUTO;
        p().edit().putInt(K_AUDIO_MODE, mode).apply();
    }

    // ---- 源码直通要声明哪些编码（逗号分隔的 token）----

    private static final String K_PT_CODECS = "audio_pt_codecs";

    public static final String PT_AC3 = "ac3";
    public static final String PT_EAC3 = "eac3";
    public static final String PT_EAC3_JOC = "eac3joc";
    public static final String PT_DTS = "dts";
    public static final String PT_DTS_HD = "dtshd";
    public static final String PT_TRUEHD = "truehd";
    public static final String PT_AC4 = "ac4";

    /** 设置页按这个顺序列出可选编码。 */
    public static final String[] PT_ALL = {
            PT_AC3, PT_EAC3, PT_EAC3_JOC, PT_DTS, PT_DTS_HD, PT_TRUEHD, PT_AC4};

    /**
     * 缺省开启 AC3 / E-AC3 / E-AC3(JOC) / DTS。
     *
     * <p>这四种国产盒子命中率最高；DTS-HD / TrueHD / AC-4 需要设备侧授权解码器，
     * 乱勾的结果是「有画面没声音」，所以缺省不开，让用户按功放面板实测后再加。</p>
     */
    public static final String PT_DEFAULT = "ac3,eac3,eac3joc,dts";

    /** 编码 token -> 中文名（设置页显示用）。 */
    public static String ptLabel(String token) {
        if (PT_AC3.equals(token)) return "AC-3（杜比数字 5.1）";
        if (PT_EAC3.equals(token)) return "E-AC-3（杜比数字+ 5.1/7.1）";
        if (PT_EAC3_JOC.equals(token)) return "E-AC-3 JOC（杜比全景声）";
        if (PT_DTS.equals(token)) return "DTS（DTS 5.1）";
        if (PT_DTS_HD.equals(token)) return "DTS-HD / DTS:X";
        if (PT_TRUEHD.equals(token)) return "TrueHD（杜比无损）";
        if (PT_AC4.equals(token)) return "AC-4";
        return token;
    }

    public static String passthroughCodecs() {
        if (p() == null) return PT_DEFAULT;
        String v;
        try {
            v = p().getString(K_PT_CODECS, PT_DEFAULT);
        } catch (Throwable e) {
            return PT_DEFAULT;
        }
        return v == null ? PT_DEFAULT : v;
    }

    /** token 是否已勾选（不引入 Set，SharedPreferences 存的就是一串逗号分隔值）。 */
    public static boolean passthroughCodec(String token) {
        if (token == null || token.isEmpty()) return false;
        String all = passthroughCodecs();
        int from = 0;
        while (from <= all.length()) {
            int cut = all.indexOf(',', from);
            String one = (cut < 0) ? all.substring(from) : all.substring(from, cut);
            if (one.trim().equals(token)) return true;
            if (cut < 0) break;
            from = cut + 1;
        }
        return false;
    }

    /** 勾掉/勾上一种编码，其余保持原样（按 {@link #PT_ALL} 的固定顺序重排）。 */
    public static void setPassthroughCodec(String token, boolean on) {
        if (p() == null || token == null || token.isEmpty()) return;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < PT_ALL.length; i++) {
            String t = PT_ALL[i];
            boolean keep = t.equals(token) ? on : passthroughCodec(t);
            if (keep) {
                if (sb.length() > 0) sb.append(',');
                sb.append(t);
            }
        }
        p().edit().putString(K_PT_CODECS, sb.toString()).apply();
    }

    /** 已勾选编码的个数（设置页展示摘要用）。 */
    public static int passthroughCodecCount() {
        int n = 0;
        for (int i = 0; i < PT_ALL.length; i++) {
            if (passthroughCodec(PT_ALL[i])) n++;
        }
        return n;
    }

    // ---- 最大声道数 ----

    private static final String K_AUDIO_MAX_CH = "audio_max_channels";
    public static final int AUDIO_MAX_CH_DEFAULT = 8;

    public static int audioMaxChannels() {
        if (p() == null) return AUDIO_MAX_CH_DEFAULT;
        int v;
        try {
            v = p().getInt(K_AUDIO_MAX_CH, AUDIO_MAX_CH_DEFAULT);
        } catch (Throwable e) {
            return AUDIO_MAX_CH_DEFAULT;
        }
        if (v != 2 && v != 6 && v != 8) v = AUDIO_MAX_CH_DEFAULT;
        return v;
    }

    public static void setAudioMaxChannels(int ch) {
        if (p() == null) return;
        if (ch != 2 && ch != 6 && ch != 8) ch = AUDIO_MAX_CH_DEFAULT;
        p().edit().putInt(K_AUDIO_MAX_CH, ch).apply();
    }

    // ---- 直通失败自动回退 ----

    private static final String K_PT_FALLBACK = "audio_pt_fallback";

    /** 缺省开：直通建轨失败时自动改用解码重播，避免一播就崩。 */
    public static boolean passthroughFallback() {
        if (p() == null) return true;
        try {
            return p().getBoolean(K_PT_FALLBACK, true);
        } catch (Throwable e) {
            return true;
        }
    }

    public static void setPassthroughFallback(boolean on) {
        if (p() == null) return;
        p().edit().putBoolean(K_PT_FALLBACK, on).apply();
    }

    // ---- 首选音轨语言 ----

    private static final String K_AUDIO_LANG = "audio_pref_lang";

    /** 首选音轨语言（ISO 639-1 两字母码）；空串 = 不指定，跟随片源默认轨。 */
    public static String audioPreferredLang() {
        if (p() == null) return "";
        try {
            String v = p().getString(K_AUDIO_LANG, "");
            return v == null ? "" : v;
        } catch (Throwable e) {
            return "";
        }
    }

    public static void setAudioPreferredLang(String lang) {
        if (p() == null) return;
        p().edit().putString(K_AUDIO_LANG, lang == null ? "" : lang).apply();
    }

    // ==================== 播放 · 视频（v1.25）====================

    // ---- 画面比例（取值与 AspectRatioFrameLayout 的 RESIZE_MODE_* 对齐）----

    private static final String K_VIDEO_RESIZE = "video_resize";
    /** 适应屏幕（RESIZE_MODE_FIT） */
    public static final int RESIZE_FIT = 0;
    /** 拉伸填满（RESIZE_MODE_FILL） */
    public static final int RESIZE_FILL = 3;
    /** 裁剪填满（RESIZE_MODE_ZOOM） */
    public static final int RESIZE_ZOOM = 4;

    public static int videoResize() {
        if (p() == null) return RESIZE_FIT;
        int v;
        try {
            v = p().getInt(K_VIDEO_RESIZE, RESIZE_FIT);
        } catch (Throwable e) {
            return RESIZE_FIT;
        }
        if (v != RESIZE_FIT && v != RESIZE_FILL && v != RESIZE_ZOOM) v = RESIZE_FIT;
        return v;
    }

    public static void setVideoResize(int mode) {
        if (p() == null) return;
        if (mode != RESIZE_FIT && mode != RESIZE_FILL && mode != RESIZE_ZOOM) mode = RESIZE_FIT;
        p().edit().putInt(K_VIDEO_RESIZE, mode).apply();
    }

    // ---- 在线清晰度上限 ----

    private static final String K_QUALITY_CAP = "quality_cap";
    /** 原画优先（缺省）：先取原画直链，失败才降级转码流 */
    public static final int QUALITY_ORIGINAL = 0;
    /** 直接走 ≤1080p 转码流 */
    public static final int QUALITY_1080 = 1;
    /** 直接走 ≤720p 转码流 */
    public static final int QUALITY_720 = 2;

    public static int qualityCap() {
        if (p() == null) return QUALITY_ORIGINAL;
        int v;
        try {
            v = p().getInt(K_QUALITY_CAP, QUALITY_ORIGINAL);
        } catch (Throwable e) {
            return QUALITY_ORIGINAL;
        }
        if (v < QUALITY_ORIGINAL || v > QUALITY_720) v = QUALITY_ORIGINAL;
        return v;
    }

    public static void setQualityCap(int cap) {
        if (p() == null) return;
        if (cap < QUALITY_ORIGINAL || cap > QUALITY_720) cap = QUALITY_ORIGINAL;
        p().edit().putInt(K_QUALITY_CAP, cap).apply();
    }

    // ---- 硬解失败回退软解 ----

    private static final String K_DECODER_FALLBACK = "decoder_fallback";

    public static boolean decoderFallback() {
        if (p() == null) return true;
        try {
            return p().getBoolean(K_DECODER_FALLBACK, true);
        } catch (Throwable e) {
            return true;
        }
    }

    public static void setDecoderFallback(boolean on) {
        if (p() == null) return;
        p().edit().putBoolean(K_DECODER_FALLBACK, on).apply();
    }

    // ---- 字幕字号（占屏幕高度的比例）----

    private static final String K_SUB_SIZE = "sub_size";
    /** 缺省 = 「大」，与 v1.24 之前的观感一致（0.066 屏高） */
    public static final int SUB_SIZE_DEFAULT = 2;

    public static int subSize() {
        if (p() == null) return SUB_SIZE_DEFAULT;
        int v;
        try {
            v = p().getInt(K_SUB_SIZE, SUB_SIZE_DEFAULT);
        } catch (Throwable e) {
            return SUB_SIZE_DEFAULT;
        }
        if (v < 0 || v > 3) v = SUB_SIZE_DEFAULT;
        return v;
    }

    public static void setSubSize(int size) {
        if (p() == null) return;
        if (size < 0 || size > 3) size = SUB_SIZE_DEFAULT;
        p().edit().putInt(K_SUB_SIZE, size).apply();
    }

    /** 字幕字号档位 -> media3 的「占屏幕高度比例」。 */
    public static float subFraction() {
        int v = subSize();
        if (v == 0) return 0.040f;    // 小
        if (v == 1) return 0.0533f;   // 中（media3 默认）
        if (v == 3) return 0.080f;    // 特大
        return 0.066f;                // 大（缺省）
    }

    // ---- 记住播放进度 ----

    private static final String K_REMEMBER_POS = "remember_pos";

    /** 关掉之后：不再写也不再读续播进度（进度记录不会清，只是不用）。 */
    public static boolean rememberPos() {
        if (p() == null) return true;
        try {
            return p().getBoolean(K_REMEMBER_POS, true);
        } catch (Throwable e) {
            return true;
        }
    }

    public static void setRememberPos(boolean on) {
        if (p() == null) return;
        p().edit().putBoolean(K_REMEMBER_POS, on).apply();
    }
}

