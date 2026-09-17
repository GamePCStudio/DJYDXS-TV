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
    /** 没设置过下载目录时的缺省值（App 专属外部目录，免权限可写）。 */
    private static String defaultDlDir = "";

    private Settings() {}

    private static SharedPreferences p() {
        return p;
    }

    public static void init(Context ctx) {
        if (p == null) {
            Context app = ctx.getApplicationContext();
            p = app.getSharedPreferences(FILE, Context.MODE_PRIVATE);
            defaultDlDir = Storage.appDir(app);
        }
    }

    public static String saveDir() {
        return p().getString(K_SAVE_DIR, "/超级影库");
    }

    public static void setSaveDir(String dir) {
        p().edit().putString(K_SAVE_DIR, dir).apply();
    }

    /** 下载落盘目录（绝对路径）。 */
    public static String downloadDir() {
        String d = p().getString(K_DL_DIR, "");
        if (d == null || d.isEmpty()) {
            if (defaultDlDir == null || defaultDlDir.isEmpty()) return "/sdcard";
            return defaultDlDir;
        }
        return d;
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
}

