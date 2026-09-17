package com.supermov.tv;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 下载落盘位置：枚举可写目录 + 申请存储权限。
 *
 * <p>为什么这样做：Android 10 起分区存储让「直接写 /sdcard/xxx」必须拿
 * MANAGE_EXTERNAL_STORAGE（所有文件访问）；而 App 专属外部目录
 * {@code /sdcard/Android/data/<包名>/files/} 任何版本都免权限可写。
 * 所以默认落点是 App 专属目录（100% 能用），其余位置按需申请权限后再出现。</p>
 *
 * <p>NAS 不需要专门的 SMB 库：盒子通过自带文件管理器 / X-plore 之类把共享挂载后，
 * 会出现在 {@code /mnt} 或 {@code /storage} 下，和 U 盘一样直接就是普通路径，
 * 扫出来给用户选即可（TV 上用遥控器打字输路径太痛苦，所以做成列表选择）。</p>
 */
public final class Storage {

    public static class Target {
        public final String label;
        public final String dir;
        /** true 表示需要先申请「所有文件访问」权限才能用 */
        public final boolean needAllFiles;

        Target(String label, String dir, boolean needAllFiles) {
            this.label = label;
            this.dir = dir;
            this.needAllFiles = needAllFiles;
        }
    }

    private Storage() {
    }

    /** 是否已拿到「所有文件访问」（API 30+）/ 写外部存储（API < 30）。 */
    public static boolean hasAllFiles(Context c) {
        if (c == null) return false;
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                return Environment.isExternalStorageManager();
            } catch (Throwable e) {
                return false;
            }
        }
        return c.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** 跳系统页面申请「所有文件访问」。 */
    public static boolean requestAllFiles(Context c) {
        if (c == null) return false;
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                i.setData(Uri.parse("package:" + c.getPackageName()));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                c.startActivity(i);
                return true;
            }
            return false; // 低版本由调用方走 requestPermissions
        } catch (Throwable e) {
            try {
                Intent i = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                c.startActivity(i);
                return true;
            } catch (Throwable ignored) {
                return false;
            }
        }
    }

    /** App 专属外部目录（免权限，任何 Android 版本都能写）。 */
    public static String appDir(Context c) {
        File base = null;
        try {
            base = c.getExternalFilesDir(null);
        } catch (Throwable ignored) {
        }
        if (base == null) base = c.getFilesDir();
        return new File(base, "超级影库").getAbsolutePath();
    }

    /** 默认下载目录（用户没改过时用它）。 */
    public static String defaultDir(Context c) {
        return appDir(c);
    }

    /**
     * 枚举候选落盘目录。有权限时会把 U 盘 / 移动硬盘 / 已挂载的网络共享一起列出来。
     */
    public static List<Target> targets(Context c) {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<Target> out = new ArrayList<>();

        // ① 「本机 · 应用目录」不再作为选项列出（用户要求去掉）——它仍是「没设置过下载目录」
        //    时的缺省落点（见 Settings.downloadDir 与 defaultDir），只是不摆在列表里让人选。
        boolean all = hasAllFiles(c);

        // ② 公共存储
        String movies = "/sdcard/Movies/超级影库";
        if (all) {
            if (seen.add(movies)) out.add(new Target("本机 · 公共存储 Movies", movies, false));
        } else {
            if (seen.add(movies)) out.add(new Target("本机 · 公共存储 Movies（需授权）", movies, true));
        }

        // ③ 外接盘 / 已挂载的网络位置：有权限才扫，扫出来直接可选
        if (all) {
            scan(new File("/storage"), 2, out, seen);
            scan(new File("/mnt"), 2, out, seen);
        }

        return out;
    }

    /** 扫描可写挂载点。命中一层就够——挂载点本身就是共享/分区根目录。 */
    private static void scan(File root, int depth, List<Target> out, LinkedHashSet<String> seen) {
        if (root == null || depth < 0) return;
        if (!root.isDirectory() || !root.canRead()) return;
        File[] kids;
        try {
            kids = root.listFiles();
        } catch (Throwable e) {
            return;
        }
        if (kids == null) return;
        for (File f : kids) {
            String n = f.getName();
            if (n.startsWith(".")) continue;
            if (n.equals("emulated") || n.equals("self") || n.equals("obb")
                    || n.equals("sdcard0") || n.equals("runnable")) {
                continue;
            }
            if (!f.isDirectory()) continue;
            try {
                if (f.canWrite()) {
                    String dir = new File(f, "超级影库").getAbsolutePath();
                    if (seen.add(dir)) {
                        out.add(new Target("外接/NAS · " + n, dir, false));
                    }
                    continue; // 可写就不再往下钻，避免列表噪音
                }
            } catch (Throwable ignored) {
            }
            scan(f, depth - 1, out, seen);
        }
    }

    /** 目录（或其最近的已存在父目录）是否可写。 */
    public static boolean canWrite(String dir) {
        if (dir == null || dir.isEmpty()) return false;
        try {
            File f = new File(dir);
            while (f != null && !f.exists()) f = f.getParentFile();
            return f != null && f.isDirectory() && f.canWrite();
        } catch (Throwable e) {
            return false;
        }
    }

    /** 该路径是否需要「所有文件访问」权限（在 /sdcard 公共区而非 App 专属目录）。 */
    public static boolean needsPermission(Context c, String dir) {
        if (dir == null) return false;
        if (hasAllFiles(c)) return false;
        String app = appDir(c);
        if (dir.startsWith(app)) return false;
        try {
            String pkgPath = "/Android/data/" + c.getPackageName();
            if (dir.contains(pkgPath)) return false;
        } catch (Throwable ignored) {
        }
        return dir.startsWith("/sdcard") || dir.startsWith("/storage/emulated")
                || dir.startsWith("/mnt/sdcard");
    }
}
