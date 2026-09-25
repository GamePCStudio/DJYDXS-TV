package com.supermov.tv;

import android.os.Build;
import android.util.Log;

import java.io.File;
import java.util.Locale;

/**
 * 机型识别 + 下载后处理策略。
 *
 * <h3>为什么要分机型</h3>
 * 同一部片落到不同厂商的硬盘播放器上，目录与命名要求并不一样（有的整机库按「片名文件夹 +
 * 片名.ext」扫描刮削，有的要求保留原始文件名，有的只认特定扩展名）。本类的两个出口
 * {@link #targetDir(File, String)} 与 {@link #finalName(String, String, FilmNaming.Caps)}
 * 就是留给这些差异的**唯一**切入口：新支持一种机型时只改这两个方法里的分支，
 * 不动下载引擎。
 *
 * <h3>当前实现</h3>
 * 只有「通用」一套动作（先建中文片名目录，下载完把文件改成电影名）。识别到
 * 威动 / 艾美 / 视易 / 海美迪 时先按通用处理，但会把机型记进日志与设置页 ——
 * 拿到真机数据后在分支里替换即可。
 *
 * <h3>识别口径</h3>
 * 靠 {@code Build.MANUFACTURER / BRAND / MODEL} 的关键字匹配。这些字符串由厂商自己填，
 * 同一品牌不同批次可能不同，所以匹配表按「多别名 + 小写包含」写，并把**原始三元组**
 * 一并暴露出来（{@link #raw()}）便于按真机反馈补条目。
 */
public final class DeviceProfile {

    private static final String TAG = "SupeMov";

    public enum Brand {
        WEIDONG("威动"), AIMEI("艾美"), SHIYI("视易"), HAIMIDI("海美迪"), GENERIC("通用");

        public final String display;

        Brand(String display) {
            this.display = display;
        }
    }

    /** 品牌别名表：命中任一关键字（小写包含）即判为该品牌。首列是 Brand 枚举名。 */
    private static final String[][] ALIASES = {
            {"AIMEI", "mymei", "emei", "ewatch", "imovie", "艾美"},
            {"HAIMIDI", "himd", "haimidi", "海美迪"},
            {"WEIDONG", "weidong", "vdo", "vod", "威动"},
            {"SHIYI", "shiyi", "shi-yi", "视易"},
    };

    private static volatile DeviceProfile inst;

    private final Brand brand;
    private final String manufacturer;
    private final String model;
    private final String matched;

    private DeviceProfile(Brand brand, String manufacturer, String model, String matched) {
        this.brand = brand;
        this.manufacturer = manufacturer;
        this.model = model;
        this.matched = matched;
    }

    public static DeviceProfile get() {
        DeviceProfile d = inst;
        if (d != null) return d;
        synchronized (DeviceProfile.class) {
            if (inst == null) inst = detect();
            return inst;
        }
    }

    private static DeviceProfile detect() {
        String mf = nz(Build.MANUFACTURER) + " " + nz(Build.BRAND);
        String md = nz(Build.MODEL);
        String hay = (mf + " " + md).toLowerCase(Locale.ROOT);
        Brand b = Brand.GENERIC;
        String hit = "";
        for (String[] row : ALIASES) {
            for (int i = 1; i < row.length; i++) {
                if (hay.contains(row[i])) {
                    b = Brand.valueOf(row[0]);
                    hit = row[i];
                    break;
                }
            }
            if (b != Brand.GENERIC) break;
        }
        DeviceProfile d = new DeviceProfile(b, nz(Build.MANUFACTURER), md, hit);
        Log.d(TAG, "机型识别: " + d.raw() + " -> " + b.display + (hit.isEmpty() ? "" : "（命中 " + hit + "）"));
        return d;
    }

    private static String nz(String s) {
        return s == null ? "" : s.trim();
    }

    public Brand brand() {
        return brand;
    }

    /** 识别依据：原始三元组 + 命中的关键字，供设置页与日志排查用。 */
    public String raw() {
        return "厂商=" + manufacturer + " 型号=" + model + " 品牌=" + brand.display;
    }

    public String label() {
        String base = brand.display + "版";
        return model.isEmpty() ? base : base + "（" + model + "）";
    }

    // ==================== 后处理策略 ====================

    /**
     * 影片落地目录。
     *
     * <p>通用口径：在用户选的下载根目录下建一层**中文片名**目录。片名即目录名，
     * 播放器/文件管理器里一眼能认出；不同片子的同名文件也因此不会互相覆盖。</p>
     */
    public File targetDir(File root, String title) {
        FilmNaming.Caps caps = FilmNaming.capsFor(root);
        String dirName = FilmNaming.safe(title, caps, "未命名影片");
        return new File(root, dirName);
    }

    /**
     * 下载完成后的正式文件名（含扩展名）。
     *
     * <p>通用口径：改成**电影名 + 云端给的真实容器后缀**。下载中途用的是
     * {@code <hash>.<ext>}，因为它与服务端分段路径同名、断点续传时不会漂移。</p>
     */
    public String finalName(String title, String ext, FilmNaming.Caps caps) {
        String base = FilmNaming.safe(title, caps, "未命名影片");
        return FilmNaming.withExt(base, ext);
    }

    /** 校验通过后是否还要做额外动作（媒体库扫描、写侧车文件等）：通用版目前只做改名。 */
    public void afterDownload(File finalFile, Dl task) {
        // 占位：威动/艾美/视易/海美迪 的专属后处理将来挂在这里
    }
}
