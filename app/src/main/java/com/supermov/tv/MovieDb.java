package com.supermov.tv;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * SuperMOV.db 的就位、打开与版本判定。
 *
 * <h3>为什么不能直接读 assets</h3>
 * {@code assets/} 里的文件在 APK 内部，SQLite 需要一个真实可寻址的文件路径才能打开
 * （{@code file:///android_asset/...} 只能给 WebView 用，不能给 SQLite）。
 * 所以首次运行要把库复制到 {@code getFilesDir()} 下，之后一律读这份工作副本。
 * 这同时带来两个好处：<b>更新库不必重装 APK</b>；<b>assets 永远保持出厂状态</b>，
 * 用户把库改坏了还能一键回滚。
 *
 * <h3>三层版本号 —— 取最大值</h3>
 * 同一个库可能有三份来源，谁新用谁：
 * <pre>
 *   ① 内嵌库（assets，随 APK 出厂）
 *   ② 已装库（files/，可能是上一次在线更新下来的）
 *   ③ 远端库（800915.xyz/db，清单里的版本）
 * </pre>
 * 光比「内嵌 vs 已装」是不够的。真实陷阱：用户先在线更新到 v2026092001，
 * 之后又装了一个内嵌库更旧的新 APK —— 如果直接拿内嵌库覆盖，数据就<b>降级</b>了，
 * 用户会看到「影片变少了」。所以必须把三者的 db_version 放一起比大小，
 * 只在严格更大时才替换。
 */
public final class MovieDb {

    private static final String TAG = "SupeMov";

    /** 本 App 能读的最高 schema 版本。库的 schema_version 高于它时必须提示升级，不能硬读。 */
    public static final int SCHEMA_SUPPORTED = 2;

    private static final String ASSET_NAME = "SuperMOV.db";
    private static final String WORK_NAME = "SuperMOV.db";
    private static final String PREFS = "moviedb";

    private MovieDb() {
    }

    // ------------------------------------------------------------------
    // 路径
    // ------------------------------------------------------------------

    public static File dbFile(Context ctx) {
        return new File(ctx.getFilesDir(), WORK_NAME);
    }

    public static String dbPath(Context ctx) {
        return dbFile(ctx).getAbsolutePath();
    }

    private static SharedPreferences sp(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------------
    // 就位
    // ------------------------------------------------------------------

    /**
     * 保证工作副本存在且不比内嵌库旧。幂等，可在 Application/Activity 启动时随便调。
     *
     * <p>只在「工作副本不存在」或「内嵌库版本严格更大」时才复制 —— 即在线更新的成果
     * 不会被一个更旧的新 APK 冲掉。</p>
     */
    public static synchronized void ensureReady(Context ctx) {
        File work = dbFile(ctx);
        int assetVer = assetDbVersion(ctx);
        int workVer = work.exists() ? readIntMeta(ctx, work, "db_version") : -1;

        if (work.exists() && workVer >= assetVer) {
            Log.d(TAG, "MovieDb: 工作副本已就位 v" + workVer + "（内嵌 v" + assetVer + "）");
            return;
        }
        if (!work.exists()) {
            Log.d(TAG, "MovieDb: 首次运行，从 assets 复制 v" + assetVer);
        } else {
            Log.d(TAG, "MovieDb: 内嵌库 v" + assetVer + " 比已装 v" + workVer + " 新，替换");
        }
        copyFromAssets(ctx, work);
        writeIntPref(ctx, "installed_db_version", assetVer);
    }

    /** 把内嵌库复制到工作副本。走临时文件 + rename，避免复制中途被杀留下半截库。 */
    private static void copyFromAssets(Context ctx, File work) {
        File tmp = new File(work.getAbsolutePath() + ".tmp");
        InputStream in = null;
        OutputStream out = null;
        try {
            in = ctx.getAssets().open(ASSET_NAME);
            out = new FileOutputStream(tmp);
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
        } catch (Throwable e) {
            Log.d(TAG, "MovieDb: 解包失败 " + e);
            // 解包失败时删掉半截文件，下次启动重试；绝不能留下坏库让上层崩
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return;
        } finally {
            closeQuietly(in);
            closeQuietly(out);
        }
        replaceAtomically(tmp, work);
    }

    /**
     * 原子替换：把 tmp 变成正式文件。
     *
     * <p>先删侧写文件（{@code -journal/-wal/-shm}）。旧库遗留的 journal 配上一个新库文件，
     * SQLite 会尝试「回滚」到一个根本不属于它的状态，轻则打不开、重则读出错数据。</p>
     */
    public static void replaceAtomically(File tmp, File dest) {
        deleteSidecars(dest);
        if (dest.exists()) {
            File bak = new File(dest.getAbsolutePath() + ".bak");
            deleteSidecars(bak);
            //noinspection ResultOfMethodCallIgnored
            bak.delete();
            if (!dest.renameTo(bak)) {
                Log.d(TAG, "MovieDb: 备份旧库失败，直接覆盖");
                //noinspection ResultOfMethodCallIgnored
                dest.delete();
            }
        }
        if (!tmp.renameTo(dest)) {
            Log.d(TAG, "MovieDb: rename 失败，退化为流复制");
            copyFile(tmp, dest);
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
        deleteSidecars(dest);
    }

    private static void deleteSidecars(File f) {
        for (String s : new String[]{"-journal", "-wal", "-shm"}) {
            File x = new File(f.getAbsolutePath() + s);
            if (x.exists()) {
                //noinspection ResultOfMethodCallIgnored
                x.delete();
            }
        }
    }

    private static void copyFile(File src, File dst) {
        InputStream in = null;
        OutputStream out = null;
        try {
            in = new java.io.FileInputStream(src);
            out = new FileOutputStream(dst);
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
        } catch (Throwable e) {
            Log.d(TAG, "MovieDb.copyFile " + e);
        } finally {
            closeQuietly(in);
            closeQuietly(out);
        }
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (Throwable ignore) {
        }
    }

    // ------------------------------------------------------------------
    // 打开与元信息
    // ------------------------------------------------------------------

    public static SQLiteDatabase openReadOnly(Context ctx) {
        ensureReady(ctx);
        return SQLiteDatabase.openDatabase(dbPath(ctx), null, SQLiteDatabase.OPEN_READONLY);
    }

    public static String meta(Context ctx, String key) {
        return readMeta(ctx, dbFile(ctx), key);
    }

    public static int dbVersion(Context ctx) {
        return readIntMeta(ctx, dbFile(ctx), "db_version");
    }

    public static int dbVersionOf(Context ctx, File f) {
        return readIntMeta(ctx, f, "db_version");
    }

    private static String readMeta(Context ctx, File f, String key) {
        if (f == null || !f.exists()) return "";
        SQLiteDatabase db = null;
        Cursor c = null;
        try {
            db = SQLiteDatabase.openDatabase(f.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
            c = db.rawQuery("SELECT value FROM meta WHERE key=?", new String[]{key});
            return c.moveToFirst() ? c.getString(0) : "";
        } catch (Throwable e) {
            Log.d(TAG, "MovieDb.readMeta(" + key + ") " + e);
            return "";
        } finally {
            if (c != null) c.close();
            if (db != null) db.close();
        }
    }

    private static int readIntMeta(Context ctx, File f, String key) {
        try {
            String v = readMeta(ctx, f, key);
            return v == null || v.isEmpty() ? -1 : Integer.parseInt(v.trim());
        } catch (Throwable e) {
            return -1;
        }
    }

    /**
     * 内嵌库的 db_version。
     *
     * <p>不走「复制 assets 再查 SQLite」那条路 —— 那要在首次启动时把整个库多写一遍，
     * 白费一次 IO。构建流水线会在打包时额外产出一个只有几行的
     * {@code assets/SuperMOV.version}，这里读它即可：</p>
     * <pre>
     *   db_version=2026091901
     *   schema_version=2
     *   db_build_id=da0208adb24c76f0
     * </pre>
     * <p>万一这个文件缺失（比如手工换了库忘了重新生成），退回探测式读取。</p>
     */
    private static int assetDbVersion(Context ctx) {
        String txt = readAssetText(ctx, "SuperMOV.version");
        if (txt != null) {
            for (String line : txt.split("\n")) {
                int i = line.indexOf('=');
                if (i > 0 && "db_version".equals(line.substring(0, i).trim())) {
                    try {
                        return Integer.parseInt(line.substring(i + 1).trim());
                    } catch (NumberFormatException ignore) {
                    }
                }
            }
        }
        // 兜底：真去查一次 assets 里的库（只在 version 文件缺失时发生）
        File probe = new File(ctx.getCacheDir(), "asset_probe.db");
        try {
            copyFromAssetsTo(ctx, probe);
            return readIntMeta(ctx, probe, "db_version");
        } finally {
            //noinspection ResultOfMethodCallIgnored
            probe.delete();
        }
    }

    private static String readAssetText(Context ctx, String name) {
        InputStream in = null;
        try {
            in = ctx.getAssets().open(name);
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Throwable e) {
            return null;
        } finally {
            closeQuietly(in);
        }
    }

    private static void copyFromAssetsTo(Context ctx, File dest) {
        InputStream in = null;
        OutputStream out = null;
        try {
            in = ctx.getAssets().open(ASSET_NAME);
            out = new FileOutputStream(dest);
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
        } catch (Throwable e) {
            Log.d(TAG, "MovieDb.copyFromAssetsTo " + e);
        } finally {
            closeQuietly(in);
            closeQuietly(out);
        }
    }

    // ------------------------------------------------------------------
    // 可用性判定
    // ------------------------------------------------------------------

    /**
     * 库能不能被本 App 读。
     *
     * <p>两种情况要区分开：</p>
     * <ul>
     *   <li><b>库太旧</b>（缺列/缺表）：读写时兜底降级，不要拦用户；</li>
     *   <li><b>库太新</b>（schema_version &gt; SCHEMA_SUPPORTED）：必须明确提示「请升级应用」，
     *       因为新结构可能删掉了本版本依赖的列，硬读会读到错的东西。</li>
     * </ul>
     */
    public static boolean isSchemaReadable(Context ctx) {
        int sv = readIntMeta(ctx, dbFile(ctx), "schema_version");
        return sv <= 0 || sv <= SCHEMA_SUPPORTED;
    }

    public static String describe(Context ctx) {
        File f = dbFile(ctx);
        if (!f.exists()) return "数据库未就位";
        return String.format(java.util.Locale.ROOT,
                "schema v%s / 数据 v%s / 构建 %s / %.2f MB",
                readMeta(ctx, f, "schema_version"),
                readMeta(ctx, f, "db_version"),
                readMeta(ctx, f, "db_build_id"),
                f.length() / 1048576.0);
    }

    // ------------------------------------------------------------------
    // 当前生效版本（供更新器比较）
    // ------------------------------------------------------------------

    /** 内嵌库的 db_version；用于「APK 自带的库是不是比已装的新」。 */
    public static int embeddedDbVersion(Context ctx) {
        return assetDbVersion(ctx);
    }

    /** 已装库的 db_version（工作副本）。 */
    public static int installedDbVersion(Context ctx) {
        return readIntMeta(ctx, dbFile(ctx), "db_version");
    }

    public static void setInstalledDbVersion(Context ctx, int v) {
        writeIntPref(ctx, "installed_db_version", v);
    }

    public static int lastCheckedDbVersion(Context ctx) {
        return sp(ctx).getInt("installed_db_version", -1);
    }

    private static void writeIntPref(Context ctx, String k, int v) {
        sp(ctx).edit().putInt(k, v).apply();
    }
}
