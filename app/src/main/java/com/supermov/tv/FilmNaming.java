package com.supermov.tv;

import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 落盘文件名的合法性处理：先认清目标分区是什么文件系统，再按它的规矩裁名字。
 *
 * <h3>为什么要认清文件系统</h3>
 * 盒子上的下载盘可能是 ext4（内部存储）、vfat/exfat（U 盘 / 老 TF 卡）、ntfs（移动硬盘）、
 * 也可能是 sdcardfs / fuse 这层代理。四者的禁忌不完全一样：
 * <ul>
 *   <li>共性：路径分隔符与控制字符不能进名字；</li>
 *   <li>FAT/NTFS 额外：{@code * ? " &lt; &gt; |} 全禁，**结尾的点号和空格会被静默丢弃**
 *       （「美国队长.」落盘变成「美国队长」，两边就成两个文件了），
 *       且保留名 {@code CON/PRN/AUX/NUL/COM1-9/LPT1-9} 不能用作文件名（不论扩展名）；</li>
 *   <li>长度：ext4/xfs 按 **UTF-8 字节** 限 255，FAT 按 **字符** 限 255。中文一个字 3 字节，
 *       所以必须按字节截，否则在内部存储上 {@code mkdir} 直接 ENAMETOOLONG。</li>
 * </ul>
 * 判不出挂载类型时按**最严的并集**处理 —— 宁可名字短一点、多两个下划线，
 * 也不要下完 40 GB 才发现文件写歪了。
 */
public final class FilmNaming {

    private static final String TAG = "SupeMov";

    /** FAT/NTFS 都不能出现的字符（含路径分隔符与控制字符）。 */
    private static final String ILLEGAL_UNION = "\\/:*?\"<>|";
    /** Windows 系保留名：作为主名出现时整个文件名不可用。 */
    private static final List<String> RESERVED = Arrays.asList(
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");

    /** 目标分区的脾气。 */
    public static final class Caps {
        /** 挂载类型（ext4 / vfat / exfat / ntfs / sdcardfs / fuse / unknown）。 */
        public String fsType = "unknown";
        /** 是否要按 FAT/NTFS 那套额外禁忌来裁。 */
        public boolean windowsLike = true;
        /** 单个路径段的上限。 */
        public int maxNameBytes = 255;

        boolean strict() {
            return windowsLike;
        }
    }

    private FilmNaming() {
    }

    /** 认目标目录所在分区。失败就返回最严的默认口径。 */
    public static Caps capsFor(File dir) {
        Caps c = new Caps();
        try {
            String mp = mountPointOf(dir);
            c.fsType = mp == null ? "unknown" : fsTypeOf(dir, mp);
            c.windowsLike = !("ext4".equals(c.fsType) || "ext3".equals(c.fsType)
                    || "xfs".equals(c.fsType) || "btrfs".equals(c.fsType));
        } catch (Throwable e) {
            Log.d(TAG, "FilmNaming.capsFor " + e);
        }
        return c;
    }

    /**
     * 把任意片名裁成目标分区可用的单段名字（不含扩展名处理）。
     *
     * @param fallback 裁完为空时的兜底名
     */
    public static String safe(String raw, Caps caps, String fallback) {
        String s = raw == null ? "" : raw.trim();
        if (s.isEmpty()) return fallback;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch < 0x20 || ch == 0x7F) {
                sb.append('_');
                continue;
            }
            if (ILLEGAL_UNION.indexOf(ch) >= 0) {
                sb.append('_');
                continue;
            }
            // 全角斜杠/冒号在片名里很常见（"电影：续集"），换成半角会撞禁忌，直接换下划线
            if (ch == '：' || ch == '／' || ch == '\\') sb.append('_');
            else sb.append(ch);
        }
        String r = sb.toString();
        if (caps == null || caps.strict()) {
            // FAT/NTFS 会静默丢掉结尾的点与空格 -> 我们主动去掉，保证两边看到的是同一个名字
            while (r.length() > 0 && (r.endsWith(" ") || r.endsWith("."))) {
                r = r.substring(0, r.length() - 1);
            }
            String head = r;
            int dot = head.indexOf('.');
            if (dot > 0) head = head.substring(0, dot);
            if (RESERVED.contains(head.toUpperCase(Locale.ROOT))) r = "_" + r;
        }
        r = r.replaceAll("[ 　]{2,}", " ").trim();
        if (r.isEmpty()) r = fallback;
        int limit = caps == null ? 120 : caps.maxNameBytes;
        r = cutBytes(r, Math.min(limit, 120));
        return r.isEmpty() ? fallback : r;
    }

    /** 按 UTF-8 字节数截断，且不把多字节字符砍成半个。 */
    static String cutBytes(String s, int maxBytes) {
        if (s.getBytes().length <= maxBytes) return s;
        int keep = 0;
        int n = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            int size = cp < 0x80 ? 1 : (cp < 0x800 ? 2 : (cp < 0x10000 ? 3 : 4));
            if (n + size > maxBytes) break;
            n += size;
            i += Character.charCount(cp);
            keep = i;
        }
        return s.substring(0, keep).trim();
    }

    /** {@code 目录/片名.ext}：扩展名单独带上，避免把点号当成非法字符砍掉。 */
    public static String withExt(String base, String ext) {
        if (ext == null || ext.isEmpty()) return base;
        String e = ext.startsWith(".") ? ext : "." + ext;
        return base + e.toLowerCase(Locale.ROOT);
    }

    /**
     * 同级重名时顺延：{@code 名字} -> {@code 名字 (2)} -> {@code 名字 (3)}…
     * 与百度网盘落地时的命名习惯一致，便于用户自己对得上。
     */
    public static File unique(File parent, String name) {
        File f = new File(parent, name);
        if (!exists(f)) return f;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 2; i < 200; i++) {
            File t = new File(parent, base + " (" + i + ")" + ext);
            if (!exists(t)) return t;
        }
        return new File(parent, base + " (" + System.currentTimeMillis() % 100000 + ")" + ext);
    }

    private static boolean exists(File f) {
        return f != null && f.exists();
    }

    // ==================== 挂载类型探测 ====================

    /** 从 /proc/mounts 找出覆盖 dir 的最长挂载点。 */
    private static String mountPointOf(File dir) throws IOException {
        String abs = dir.getAbsolutePath();
        File probe = dir;
        // 目标目录可能还没建（首次下载），向上找到存在的祖先再比对
        while (probe != null && !probe.exists()) probe = probe.getParentFile();
        if (probe != null) abs = probe.getAbsolutePath();
        String best = null;
        for (String line : readLines("/proc/mounts")) {
            String[] p = line.split("\\s+");
            if (p.length < 3) continue;
            String mp = p[1].endsWith("/") && !"/".equals(p[1])
                    ? p[1].substring(0, p[1].length() - 1) : p[1];
            if ("/".equals(mp)) continue;
            if (abs.equals(mp) || abs.startsWith(mp + "/")) {
                if (best == null || mp.length() > best.length()) best = mp;
            }
        }
        return best;
    }

    private static String fsTypeOf(File dir, String mountPoint) throws IOException {
        if (mountPoint == null) return "unknown";
        for (String line : readLines("/proc/mounts")) {
            String[] p = line.split("\\s+");
            if (p.length >= 3 && mountPoint.equals(p[1])) return p[2].toLowerCase(Locale.ROOT);
        }
        return "unknown";
    }

    private static List<String> readLines(String path) {
        List<String> out = new ArrayList<>();
        InputStream in = null;
        try {
            in = new FileInputStream(path);
            byte[] buf = new byte[8192];
            StringBuilder sb = new StringBuilder();
            int n;
            while ((n = in.read(buf)) > 0) {
                sb.append(new String(buf, 0, n));
                int at;
                while ((at = sb.indexOf("\n")) >= 0) {
                    out.add(sb.substring(0, at));
                    sb.delete(0, at + 1);
                }
            }
            if (sb.length() > 0) out.add(sb.toString());
        } catch (Throwable e) {
            Log.d(TAG, "读 " + path + " 失败 " + e);
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                }
            }
        }
        return out;
    }
}
