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

    /** 把最近一次转存结果序列化成 "time|dir|ok" 形式。 */
    public static void recordTransfer(String dir, boolean ok) {
        try {
            JSONObject o = new JSONObject();
            o.put("time", System.currentTimeMillis());
            o.put("dir", dir);
            o.put("ok", ok);
            setLastTransfer(o.toString());
        } catch (Exception ignored) {
        }
    }
}
