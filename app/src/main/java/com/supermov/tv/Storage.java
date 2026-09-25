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
 * v1.20 起默认落点是公共存储 {@code /sdcard/山姆影库}（需要权限），
 * 没权限时退回 App 专属目录 —— 见 {@link #defaultDir}。</p>
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
        return new File(base, "山姆影库").getAbsolutePath();
    }

    /** 公共存储下的缺省落点（本机内置存储）。 */
    public static final String PUBLIC_ROOT = "/sdcard/山姆影库";

    /**
     * 默认下载目录（用户没改过时用它）。
     *
     * <p>v1.20 起缺省落点改为公共存储 {@code /sdcard/山姆影库} —— 下载完的片子用盒子
     * 自带的文件管理器 / Kodi / X-plore 都能直接看到；代价是写它需要「所有文件访问」。
     * <b>没拿到权限时仍退回 App 专属目录</b>，否则一上来每次下载都失败，用户连原因都看不到。</p>
     */
    public static String defaultDir(Context c) {
        if (hasAllFiles(c)) return PUBLIC_ROOT;
        return appDir(c);
    }

    /**
     * 枚举候选落盘目录：本机存储 + 已挂载的移动存储 / 硬盘 + 扫到的挂载点。
     *
     * <p>分三路找，是为了一个都不漏：</p>
     * <ol>
     *   <li><b>本机存储</b>：固定项 {@code /sdcard/山姆影库}；</li>
     *   <li><b>移动存储 / 移动硬盘</b>：走 {@link android.os.storage.StorageManager} 拿系统
     *       认可的存储卷（U 盘、SD 卡、USB 硬盘都在这里）。比扫目录可靠 —— 扫目录靠「能不能读」，
     *       未授权时插着的盘直接看不见；这里能把「插着但还没授权」的盘也列出来让用户去授权；</li>
     *   <li><b>兜底扫挂载点</b>：{@code /storage} 与 {@code /mnt} 下可写的目录（部分盒子把 NAS /
     *       共享盘挂在非标位置，StorageManager 不认）。</li>
     * </ol>
     * <p>每一项都自动补上 {@code /山姆影库} 子目录，与网盘侧的转存根目录同名。</p>
     */
    public static List<Target> targets(Context c) {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<Target> out = new ArrayList<>();
        boolean all = hasAllFiles(c);

        // ① 本机内置存储
        addTarget(out, seen, "本机存储 · 内置", PUBLIC_ROOT, !all);

        // ② 系统认可的存储卷（U盘 / SD卡 / 移动硬盘）
        for (String v : volumes(c)) {
            String name = new File(v).getName();
            if (name == null || name.isEmpty()) name = v;
            addTarget(out, seen, "外接存储 · " + name,
                    new File(v, "山姆影库").getAbsolutePath(), !all);
        }

        // ③ 兜底：扫挂载点（NAS / 共享盘常挂在 /mnt 下）。可写的才列，避免噪音。
        scan(new File("/storage"), 2, out, seen);
        scan(new File("/mnt"), 2, out, seen);

        return out;
    }

    /** 加一项：按落地路径去重；未授权时标题里标「需授权」，其余情况不啰嗦。 */
    private static void addTarget(List<Target> out, LinkedHashSet<String> seen,
                                  String label, String dir, boolean needAllFiles) {
        if (dir == null || dir.isEmpty()) return;
        if (!seen.add(dir)) return;
        out.add(new Target(needAllFiles ? label + "（需授权）" : label, dir, needAllFiles));
    }

    /**
     * 用 {@link android.os.storage.StorageManager} 列出系统认可的存储卷根路径。
     *
     * <p>比自己扫 {@code /storage} 可靠：扫目录只能靠「能不能读」，未授权时挂着的 U 盘
     * 直接看不见；这里能拿到卷列表，把「插着但还没授权」的盘也显示出来。</p>
     */
    private static List<String> volumes(Context c) {
        List<String> out = new ArrayList<>();
        if (c == null || Build.VERSION.SDK_INT < 24) return out;
        try {
            android.os.storage.StorageManager sm = (android.os.storage.StorageManager)
                    c.getSystemService(Context.STORAGE_SERVICE);
            if (sm == null) return out;
            for (android.os.storage.StorageVolume v : sm.getStorageVolumes()) {
                String p = volumePath(v);
                if (p == null || p.isEmpty()) continue;
                if (p.equals("/sdcard") || p.startsWith("/storage/emulated")) continue;
                out.add(p);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /**
     * 取某个存储卷的挂载根路径。
     *
     * <p>{@code getDirectory()} 是 API 30 才有的公开方法；老版本只能反射 {@code getPath()}
     * （hidden API，部分 ROM 会拦），两条都拿不到就放弃这个卷。</p>
     */
    private static String volumePath(android.os.storage.StorageVolume v) {
        if (v == null) return null;
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                File d = v.getDirectory();
                if (d != null) return d.getAbsolutePath();
            } catch (Throwable ignored) {
            }
        }
        try {
            Object r = v.getClass().getMethod("getPath").invoke(v);
            if (r instanceof String) return (String) r;
        } catch (Throwable ignored) {
        }
        return null;
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
                    String dir = new File(f, "山姆影库").getAbsolutePath();
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
