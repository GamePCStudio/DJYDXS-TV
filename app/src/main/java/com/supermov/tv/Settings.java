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
    /**
     * 缺省 8：这是「<b>上限</b>」，不是「目标值」—— 意思是 7.1 也允许尝试直通。
     *
     * <p><b>为什么从 v1.25 的 6 改回 8：</b>v1.25 曾把它设成 6，起因是某台小米/Amlogic
     * 盒子建 8 声道 AudioTrack 报 {@code AudioFlinger status -12}。但那个常量是
     * <b>全设备统一</b>的，而 media3 判定「这条音轨能不能直通」正好逐条比这个上限 ——
     * 读 fork 源码可确证：</p>
     *
     * <pre>
     *   AudioCapabilities.AudioProfile#supportsChannelCount(n)  →  n &lt;= maxChannelCount
     *   AudioCapabilities#getPassthroughConfigForFormat(...)：supportsChannelCount 为 false
     *       就 return null → AudioTrackAudioOutputProvider#getFormatSupportLevel 判
     *       FORMAT_UNSUPPORTED → media3 认为这条音轨不能直通
     * </pre>
     *
     * <p>后果：凡是 <b>7.1 的 TrueHD / DTS-HD</b>，在 maxChannelCount=6 时一律被判成
     * 「不可直通」，直通当场失效 —— 这正是「上个版本 TrueHD / DTS-HD 能源码的设备，
     * 改了之后不能源码了」。而且对 -12 那台盒子，把它降到 6 也只是把「报错」换成
     * 「静音丢轨」，并没有真正修好。</p>
     *
     * <p><b>正确做法：设备差异不能靠一个统一常量扛 —— 宽进 + 运行时逐级降道。</b>
     * 这里只当上限用（缺省 8）；真正能吃几声道由实测结果 {@link #ptOkCh()} 决定：
     * 直通建轨失败时播放器会按 8 → 6 → 2 逐级降道重试，成功的那一档写回本机，
     * 下次开局直接用它，不再试错。</p>
     */
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

    // ---- 在线清晰度：固定原画（v1.26）----
    //
    // v1.26 起按「永远原画、不考虑转码」执行：设置页不再提供「清晰度上限」，
    // 播放器只走 /api/filemetas 的原画直链，不降级到 pan.baidu.com/api/streaming
    // 的 M3U8 转码流。
    // v1.27 起失败处理也收敛成一条：原画取不到就重跑整条原画流程（最多 3 次），
    // 不再有「换一条新直链」这种旁路 —— 换链只治直链过期，治不了风控/接口抖动。
    // 因此这一组偏好项（qualityCap / QUALITY_* / setQualityCap）整体删除 ——
    // 留着只会出现「设置里还能选转码、播放器却永不转码」的自相矛盾状态。

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

    // ==================== 播放 · 视频（v1.26）====================

    // ---- 最大分辨率上限（0 = 自动，不设限）----

    private static final String K_MAX_VIDEO_HEIGHT = "video_max_height";
    /** 自动：不干预选轨 */
    public static final int VIDEO_HEIGHT_AUTO = 0;

    /**
     * 视频最大高度（像素），0 = 自动。
     *
     * <p>落到 {@code TrackSelectionParameters#setMaxVideoSize}：超过这个高度的视频轨会被
     * 排到「不在约束内」，最终优先挑更低的那条。用途是盒子/电视只到 1080p 时，
     * 别硬啃 4K 片源把硬解拖垮。</p>
     */
    public static int maxVideoHeight() {
        if (p() == null) return VIDEO_HEIGHT_AUTO;
        int v;
        try {
            v = p().getInt(K_MAX_VIDEO_HEIGHT, VIDEO_HEIGHT_AUTO);
        } catch (Throwable e) {
            return VIDEO_HEIGHT_AUTO;
        }
        if (v != VIDEO_HEIGHT_AUTO && v != 720 && v != 1080 && v != 2160) v = VIDEO_HEIGHT_AUTO;
        return v;
    }

    public static void setMaxVideoHeight(int h) {
        if (p() == null) return;
        if (h != VIDEO_HEIGHT_AUTO && h != 720 && h != 1080 && h != 2160) h = VIDEO_HEIGHT_AUTO;
        p().edit().putInt(K_MAX_VIDEO_HEIGHT, h).apply();
    }

    // ---- 最大帧率上限（0 = 自动）----

    private static final String K_MAX_VIDEO_FPS = "video_max_fps";
    public static final int VIDEO_FPS_AUTO = 0;

    /** 视频最大帧率，0 = 自动。落到 {@code setMaxVideoFrameRate}。 */
    public static int maxVideoFrameRate() {
        if (p() == null) return VIDEO_FPS_AUTO;
        int v;
        try {
            v = p().getInt(K_MAX_VIDEO_FPS, VIDEO_FPS_AUTO);
        } catch (Throwable e) {
            return VIDEO_FPS_AUTO;
        }
        if (v != VIDEO_FPS_AUTO && v != 24 && v != 30 && v != 60) v = VIDEO_FPS_AUTO;
        return v;
    }

    public static void setMaxVideoFrameRate(int fps) {
        if (p() == null) return;
        if (fps != VIDEO_FPS_AUTO && fps != 24 && fps != 30 && fps != 60) fps = VIDEO_FPS_AUTO;
        p().edit().putInt(K_MAX_VIDEO_FPS, fps).apply();
    }

    // ---- 解码器优先（0 自动 / 1 优先硬解 / 2 优先软解）----

    private static final String K_DECODER_PREFER = "decoder_prefer";
    public static final int DECODER_AUTO = 0;
    /** 优先硬解（{@code MediaCodecSelector.DEFAULT}）：省电、发热低，个别盒子颜色不对时可换这个 */
    public static final int DECODER_PREFER_HW = 1;
    /** 优先软解（{@code MediaCodecSelector.PREFER_SOFTWARE}）：硬解花屏 / 绿屏时的退路，吃 CPU */
    public static final int DECODER_PREFER_SW = 2;

    public static int decoderPrefer() {
        if (p() == null) return DECODER_AUTO;
        int v;
        try {
            v = p().getInt(K_DECODER_PREFER, DECODER_AUTO);
        } catch (Throwable e) {
            return DECODER_AUTO;
        }
        if (v < DECODER_AUTO || v > DECODER_PREFER_SW) v = DECODER_AUTO;
        return v;
    }

    public static void setDecoderPrefer(int v) {
        if (p() == null) return;
        if (v < DECODER_AUTO || v > DECODER_PREFER_SW) v = DECODER_AUTO;
        p().edit().putInt(K_DECODER_PREFER, v).apply();
    }

    // ---- 隧道模式（Tunneling）----

    private static final String K_TUNNELING = "video_tunneling";

    /**
     * 隧道模式：把音视频同步交给系统（{@code DefaultTrackSelector#setTunnelingEnabled}）。
     *
     * <p>缺省关。开着在部分盒子上能显著改善 A/V 同步、并让 HDR / 杜比视界的直通更顺，
     * 但也有设备一开就黑屏或没声音 —— 所以做成开关，由用户实测决定。</p>
     */
    public static boolean tunneling() {
        if (p() == null) return false;
        try {
            return p().getBoolean(K_TUNNELING, false);
        } catch (Throwable e) {
            return false;
        }
    }

    public static void setTunneling(boolean on) {
        if (p() == null) return;
        p().edit().putBoolean(K_TUNNELING, on).apply();
    }

    // ---- 杜比视界（DV）处理 ----

    private static final String K_DV_POLICY = "dv_policy";
    /** 自动：完全交给设备与 media3（8.0+ 且屏幕不支持 DV 时会自动改用基础层解码器） */
    public static final int DV_AUTO = 0;
    /** 优先避开：排轨时把杜比视界轨压到最低，优先挑 HDR10 / SDR 轨 */
    public static final int DV_AVOID = 1;

    /**
     * 杜比视界处理。
     *
     * <p><b>先说清楚 app 层能做什么、不能做什么：</b>DV 能不能点亮，取决于
     * ① 显示端是否支持 DV（{@code Display.getHdrCapabilities()} 里有
     * {@code HDR_TYPE_DOLBY_VISION}）② 芯片有没有 DV 解码器。这两件都不是应用能改的。
     * media3 自带的处理是：Android 8.0(API 26) 以上、屏幕不支持 DV 时，
     * {@code MediaCodecVideoRenderer} 自动去找 H.264 / H.265 基础层解码器（DV7→HDR10 那条路）。
     * <b>API 26 以下这段逻辑整个不执行</b>，所以 Android 7 盒子遇到纯 DV 片源很可能直接黑屏。</p>
     *
     * <p>应用唯一能做的就是在<b>选轨</b>上让步：{@link #DV_AVOID} 时把 DV 轨排到最后，
     * 让同一部片里若同时存在 HDR10 轨就会被优先选中。片源只有 DV 一条轨时，这个开关无效。</p>
     */
    public static int dvPolicy() {
        if (p() == null) return DV_AUTO;
        int v;
        try {
            v = p().getInt(K_DV_POLICY, DV_AUTO);
        } catch (Throwable e) {
            return DV_AUTO;
        }
        if (v < DV_AUTO || v > DV_AVOID) v = DV_AUTO;
        return v;
    }

    public static void setDvPolicy(int v) {
        if (p() == null) return;
        if (v < DV_AUTO || v > DV_AVOID) v = DV_AUTO;
        p().edit().putInt(K_DV_POLICY, v).apply();
    }

    // ==================== 播放 · 字幕（v1.26）====================

    // ---- 字幕模式 ----

    private static final String K_SUB_MODE = "sub_mode";
    /** 跟随片源（缺省）：片源默认轨是什么就是什么，播放中可用遥控器上下键切换 */
    public static final int SUB_MODE_SOURCE = 0;
    /** 总是开：自动选中「首选字幕语言」的那条；没有则退回第一条文本轨 */
    public static final int SUB_MODE_ALWAYS = 1;
    /** 关：默认不出字幕（仍可在播放中手动打开） */
    public static final int SUB_MODE_OFF = 2;

    public static int subMode() {
        if (p() == null) return SUB_MODE_SOURCE;
        int v;
        try {
            v = p().getInt(K_SUB_MODE, SUB_MODE_SOURCE);
        } catch (Throwable e) {
            return SUB_MODE_SOURCE;
        }
        if (v < SUB_MODE_SOURCE || v > SUB_MODE_OFF) v = SUB_MODE_SOURCE;
        return v;
    }

    public static void setSubMode(int v) {
        if (p() == null) return;
        if (v < SUB_MODE_SOURCE || v > SUB_MODE_OFF) v = SUB_MODE_SOURCE;
        p().edit().putInt(K_SUB_MODE, v).apply();
    }

    // ---- 首选字幕语言 ----

    private static final String K_SUB_LANG = "sub_pref_lang";

    /** 首选字幕语言（ISO 639-1）；空串 = 不指定。 */
    public static String subPreferredLang() {
        if (p() == null) return "";
        try {
            String v = p().getString(K_SUB_LANG, "");
            return v == null ? "" : v;
        } catch (Throwable e) {
            return "";
        }
    }

    public static void setSubPreferredLang(String lang) {
        if (p() == null) return;
        p().edit().putString(K_SUB_LANG, lang == null ? "" : lang).apply();
    }

    // ---- 字幕颜色 / 描边 ----

    private static final String K_SUB_COLOR = "sub_color";
    public static final int SUB_COLOR_WHITE_OUTLINE = 0;
    public static final int SUB_COLOR_YELLOW_OUTLINE = 1;
    public static final int SUB_COLOR_BOX = 2;
    public static final int SUB_COLOR_PLAIN = 3;

    public static int subColor() {
        if (p() == null) return SUB_COLOR_WHITE_OUTLINE;
        int v;
        try {
            v = p().getInt(K_SUB_COLOR, SUB_COLOR_WHITE_OUTLINE);
        } catch (Throwable e) {
            return SUB_COLOR_WHITE_OUTLINE;
        }
        if (v < 0 || v > SUB_COLOR_PLAIN) v = SUB_COLOR_WHITE_OUTLINE;
        return v;
    }

    public static void setSubColor(int v) {
        if (p() == null) return;
        if (v < 0 || v > SUB_COLOR_PLAIN) v = SUB_COLOR_WHITE_OUTLINE;
        p().edit().putInt(K_SUB_COLOR, v).apply();
    }

    // ---- 字幕位置 ----

    private static final String K_SUB_POS = "sub_pos";
    public static final int SUB_POS_BOTTOM = 0;
    public static final int SUB_POS_MIDDLE = 1;
    public static final int SUB_POS_TOP = 2;

    public static int subPos() {
        if (p() == null) return SUB_POS_BOTTOM;
        int v;
        try {
            v = p().getInt(K_SUB_POS, SUB_POS_BOTTOM);
        } catch (Throwable e) {
            return SUB_POS_BOTTOM;
        }
        if (v < SUB_POS_BOTTOM || v > SUB_POS_TOP) v = SUB_POS_BOTTOM;
        return v;
    }

    public static void setSubPos(int v) {
        if (p() == null) return;
        if (v < SUB_POS_BOTTOM || v > SUB_POS_TOP) v = SUB_POS_BOTTOM;
        p().edit().putInt(K_SUB_POS, v).apply();
    }

    /** 字幕底边留白（屏幕高度的比例）：位置档位 -> SubtitleView#setBottomPaddingFraction。 */
    public static float subBottomPadding() {
        int v = subPos();
        if (v == SUB_POS_TOP) return 0.80f;
        if (v == SUB_POS_MIDDLE) return 0.45f;
        return 0.10f;
    }

    // ---- 外挂字幕：本地同目录同名自动加载 ----

    private static final String K_SUB_EXT_AUTO = "sub_ext_auto";

    /**
     * 播本地文件时，自动在同目录找同名 .ass / .ssa / .srt / .vtt 挂上。
     *
     * <p>为什么需要它：内嵌字幕（MKV 里的 S_TEXT/ASS 等）media3 本来就支持，
     * 但<b>外挂字幕此前完全没有入口</b> —— 用户下的片子和字幕是两个文件时，
     * 表现就是「ASS SSA 字幕没支持」。缺省开。</p>
     */
    public static boolean subExternalAuto() {
        if (p() == null) return true;
        try {
            return p().getBoolean(K_SUB_EXT_AUTO, true);
        } catch (Throwable e) {
            return true;
        }
    }

    public static void setSubExternalAuto(boolean on) {
        if (p() == null) return;
        p().edit().putBoolean(K_SUB_EXT_AUTO, on).apply();
    }

    // ---- 外挂字幕：手动指定（路径或 http(s) 直链）----

    private static final String K_SUB_MANUAL = "sub_manual";

    /** 手动指定的字幕路径 / URL；空串 = 不用。 */
    public static String subManual() {
        if (p() == null) return "";
        try {
            String v = p().getString(K_SUB_MANUAL, "");
            return v == null ? "" : v;
        } catch (Throwable e) {
            return "";
        }
    }

    public static void setSubManual(String v) {
        if (p() == null) return;
        p().edit().putString(K_SUB_MANUAL, v == null ? "" : v.trim()).apply();
    }

    /** 按后缀猜字幕 mime；认不出来返回空串（交给 media3 自己嗅探）。 */
    public static String subMimeFor(String name) {
        if (name == null) return "";
        String n = name.toLowerCase(java.util.Locale.US);
        int q = n.indexOf('?');
        if (q >= 0) n = n.substring(0, q);
        if (n.endsWith(".ass") || n.endsWith(".ssa")) return androidx.media3.common.MimeTypes.TEXT_SSA;
        if (n.endsWith(".srt")) return androidx.media3.common.MimeTypes.APPLICATION_SUBRIP;
        if (n.endsWith(".vtt")) return androidx.media3.common.MimeTypes.TEXT_VTT;
        if (n.endsWith(".ttml") || n.endsWith(".xml")) return androidx.media3.common.MimeTypes.APPLICATION_TTML;
        if (n.endsWith(".sub")) return androidx.media3.common.MimeTypes.APPLICATION_SUBRIP;
        return "";
    }

    // ==================== 直通声道自适应记忆（v1.26）====================

    private static final String K_PT_OK_CH = "pt_ok_ch";
    private static final String K_PT_DEV = "pt_dev_key";

    /**
     * 本机上「实测能出声」的直通声道数；0 = 还没测出来。
     *
     * <p>这是「设备差异」的正确落点：不写死常量，而是让第一次播放去试
     * （8 → 6 → 2 逐级降道），成功的那一档记下来，之后开局直接用。
     * 换机器 / 刷固件后 {@link #ptDeviceKey()} 变化，旧记忆自动作废。</p>
     */
    public static int ptOkCh() {
        if (p() == null) return 0;
        try {
            if (!ptDeviceKey().equals(p().getString(K_PT_DEV, ""))) return 0;
            int v = p().getInt(K_PT_OK_CH, 0);
            return (v == 2 || v == 6 || v == 8) ? v : 0;
        } catch (Throwable e) {
            return 0;
        }
    }

    public static void setPtOkCh(int ch) {
        if (p() == null) return;
        if (ch != 2 && ch != 6 && ch != 8) return;
        p().edit()
                .putInt(K_PT_OK_CH, ch)
                .putString(K_PT_DEV, ptDeviceKey())
                .apply();
    }

    /**
     * 抹掉本机的直通实测记忆，下次播放重新逐级试（8 → 6 → 2）。
     *
     * <p>换功放 / 换 HDMI 线 / 刷固件之后，旧结论可能已经不准 —— 设置页留了这个出口。</p>
     */
    public static void clearPtOkCh() {
        if (p() == null) return;
        p().edit().remove(K_PT_OK_CH).remove(K_PT_DEV).apply();
    }

    /** 设备指纹：机型 + 固件版本 + SDK + HDMI 输出名。用于让「实测记忆」跟着设备走。 */
    public static String ptDeviceKey() {
        String model = android.os.Build.MODEL == null ? "" : android.os.Build.MODEL;
        String disp = android.os.Build.DISPLAY == null ? "" : android.os.Build.DISPLAY;
        StringBuilder sb = new StringBuilder();
        sb.append(model).append('|').append(disp).append('|').append(android.os.Build.VERSION.SDK_INT);
        try {
            android.media.AudioManager am = appCtx == null ? null
                    : (android.media.AudioManager) appCtx.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                for (android.media.AudioDeviceInfo d : am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)) {
                    if (d.getType() == android.media.AudioDeviceInfo.TYPE_HDMI
                            || d.getType() == android.media.AudioDeviceInfo.TYPE_HDMI_ARC
                            || d.getType() == android.media.AudioDeviceInfo.TYPE_HDMI_EARC) {
                        sb.append('|').append(d.getProductName());
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return sb.toString();
    }

    /**
     * 开局该用几声道：设置上限与实测值的<b>较小者</b>。
     *
     * <p>实测值优先 —— 它是「这台设备真的开得出声」的证据；上限是用户的主观约束
     * （比如「我只要 5.1」）。两者取小，既尊重用户，又不会再去撞已知的墙。</p>
     */
    public static int ptEffectiveCap() {
        int cap = audioMaxChannels();
        int ok = ptOkCh();
        if (ok > 0 && ok < cap) return ok;
        return cap;
    }
}

