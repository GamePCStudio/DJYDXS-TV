package com.supermov.tv;

/**
 * 一条下载任务（= 一部影片的一个视频文件）。
 *
 * 队列与断点进度都由 {@link DlDb} 持久化：进程被杀、盒子断电后重新打开 App，
 * 队列还在、已下载的分片还在，会自动接着下。
 */
public class Dl {

    public static final int QUEUED  = 0; // 排队
    public static final int RUNNING = 1; // 下载中
    public static final int PAUSED  = 2; // 已暂停（可续传）
    public static final int DONE    = 3; // 已完成
    public static final int ERROR   = 4; // 失败（可重试续传）

    /** 片源类型：网盘（百度网盘直链）或 hash（云端分段地址）。 */
    public static final String SRC_BAIDU = "baidu";
    public static final String SRC_HASH = "hash";

    public long id;

    /** 走哪条取址路径；空串按 {@link #SRC_BAIDU} 处理，兼容升级前就存在的旧任务行。 */
    public String source = SRC_BAIDU;
    /** {@link #SRC_HASH} 时用：40 位十六进制影片指纹。 */
    public String hash = "";
    /** {@link #SRC_HASH} 时用：云端给出的容器后缀（不含点号），也是校验后的落盘后缀。 */
    public String ext = "mkv";

    // ---- 网盘侧定位信息（用于取直链）----
    public int fid;
    public String tid = "";
    /** 影片名：fs_id 失效时用它回网盘里重新认片 */
    public String name = "";
    /** 转存根目录（如 /山姆影库） */
    public String rootDir = "";
    /** 网盘文件 fs_id / 完整路径：命中后取链最稳，避免目录里片子多了认错 */
    public long fsId;
    public String bpath = "";

    // ---- 落盘侧 ----
    public String fileName = "";
    public String dir = "";
    public long total;
    public long done;

    public int status = QUEUED;
    public String error = "";
    public long created;

    /**
     * 队列位次（**仅 UI 用，不落库**）：QUEUED 时是它在排队里的名次（从 1 起），其余状态为 0。
     *
     * <p>用来在下载页显示「排队中 · 第 2 位」—— 让「同一时刻只有一个在下」这件事看得见。</p>
     */
    public int queuePos;

    public boolean active() {
        return status == QUEUED || status == RUNNING;
    }

    public boolean finished() {
        return status == DONE;
    }

    public int percent() {
        if (status == DONE) return 100;
        if (total <= 0) return 0;
        long p = done * 100 / total;
        return (int) (p < 0 ? 0 : (p > 100 ? 100 : p));
    }

    public String statusText() {
        switch (status) {
            case RUNNING: return "下载中 " + percent() + "%";
            case QUEUED:  return "排队中";
            case PAUSED:  return "已暂停 " + percent() + "%";
            case DONE:    return "已完成";
            default:      return error.isEmpty() ? "失败（可续传）" : "失败：" + error;
        }
    }

    /** 落盘绝对路径。 */
    public String path() {
        if (dir == null || dir.isEmpty()) return fileName;
        return dir.endsWith("/") ? dir + fileName : dir + "/" + fileName;
    }

    /**
     * 界面显示用的文件名。hash 片源下载途中的真名是「40 位云指纹.dlpart」，那串十六进制
     * 对用户没有意义，这里只把前缀换成影片名称、保留 {@code .dlpart} 后缀（看得出还没下完）。
     *
     * <p><b>只影响显示</b>：真名与断点续传的定位键都不动，播文件、删文件仍走 {@link #path()}。</p>
     */
    public String displayName() {
        String f = fileName == null ? "" : fileName;
        int dot = f.indexOf(".dlpart");
        if (dot < 0) return f.isEmpty() ? name : f;
        String title = name == null || name.isEmpty() ? f.substring(0, dot) : name;
        return title + f.substring(dot);
    }

    /** 文件名净化：去掉非法字符与路径穿越，避免在 Android/共享目录上写失败。 */
    public static String safeName(String s) {
        if (s == null || s.isEmpty()) return "video.mp4";
        String r = s.replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]", "_").trim();
        r = r.replaceAll("^[.]+", "");
        if (r.length() > 120) {
            // 保扩展名，裁主体
            int dot = r.lastIndexOf('.');
            String ext = (dot > 0 && r.length() - dot <= 8) ? r.substring(dot) : "";
            r = r.substring(0, 120 - ext.length()) + ext;
        }
        return r.isEmpty() ? "video.mp4" : r;
    }
}
