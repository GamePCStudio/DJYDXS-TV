package com.supermov.tv;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

/**
 * 数据库在线更新（走 {@code https://800915.xyz/db/SuperMOV.json} 清单）。
 *
 * <h3>为什么必须有清单</h3>
 * 只靠「每次开机拉一遍 .db 比大小」是不可行的：库有 2 MB 起步且会持续增长，
 * 拿它做版本探测等于每次启动都消耗一次完整流量。清单只有 ~1.5 KB，
 * 里面写着远端包的 {@code version / size / sha256}，App 用它做三件事：
 * <ol>
 *   <li><b>比版本</b>：远端 version 不大于本地就直接结束，连下载都不发起；</li>
 *   <li><b>比结构</b>：清单里的 {@code schema_version} 高于本 App 能读的版本时，
 *       提示「请升级应用」，而不是下载一个自己读不懂的库；</li>
 *   <li><b>校验包</b>：下载完先用清单里的 size + sha256 验一遍，
 *       再让 SQLite 自己做 integrity_check，最后才替换。
 *       这一步能挡住「下载被截断」「CDN 返回了旧版」这类静默故障。</li>
 * </ol>
 *
 * <h3>静默容忍</h3>
 * {@code 800915.xyz/db/} 还没上线。线上返回 404/超时/证书错误时，
 * 全部按「没有更新」处理，只写日志，绝不打扰用户 —— 也不能因此挡住 App 启动。
 */
public final class DbUpdater {

    private static final String TAG = "SupeMov";

    public static final String MANIFEST_URL = "https://800915.xyz/db/SuperMOV.json";

    /** 单次下载上限，防止远端挂掉时返回一个莫名其妙的巨大响应把设备写满。 */
    private static final long MAX_DOWNLOAD = 64L * 1024 * 1024;

    private DbUpdater() {
    }

    // ------------------------------------------------------------------
    // 数据结构
    // ------------------------------------------------------------------

    public static class Manifest {
        public String url = "";
        public int version = -1;
        public int schemaVersion = -1;
        public String sha256 = "";
        public long size = -1;
        public String buildId = "";
        public String notes = "";

        public boolean valid() {
            return version > 0 && url != null && url.startsWith("http") && sha256.length() >= 32;
        }

        @Override
        public String toString() {
            return "manifest{v=" + version + " schema=" + schemaVersion
                    + " size=" + size + " build=" + buildId + "}";
        }
    }

    public interface Callback {
        /** 一定在主线程回调。updated=true 表示真的换了库。 */
        void onResult(boolean updated, int localVersion, int remoteVersion, String message);
    }

    // ------------------------------------------------------------------
    // 对外入口
    // ------------------------------------------------------------------

    /**
     * 后台检查并（必要时）更新。可在 App 启动或进入设置页时调用。
     *
     * @param cb 可为 null
     */
    public static void checkAndUpdate(final Context ctx, final Callback cb) {
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(new Runnable() {
            @Override
            public void run() {
                final int local = MovieDb.installedDbVersion(ctx);
                boolean updated = false;
                String msg;
                int remote = -1;
                try {
                    Manifest mf = fetchManifest();
                    Log.d(TAG, "DbUpdater: " + mf);
                    if (mf == null || !mf.valid()) {
                        msg = "没有可用的更新清单";
                    } else {
                        remote = mf.version;
                        if (mf.schemaVersion > MovieDb.SCHEMA_SUPPORTED) {
                            msg = "服务端数据库结构已升级（v" + mf.schemaVersion
                                    + "），请升级本应用后再更新数据";
                        } else if (mf.version <= local) {
                            msg = "已是最新（v" + local + "）";
                        } else {
                            if (tryInstall(ctx, mf)) {
                                updated = true;
                                msg = "已更新到 v" + mf.version;
                            } else {
                                msg = "更新失败，继续使用本地数据 v" + local;
                            }
                        }
                    }
                } catch (Throwable e) {
                    msg = "检查更新失败（已忽略）：" + e;
                    Log.d(TAG, "DbUpdater: " + msg);
                }
                final String fm = msg;
                final boolean fu = updated;
                final int fr = remote;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (cb != null) cb.onResult(fu, local, fr, fm);
                    }
                });
            }
        }, "db-updater").start();
    }

    // ------------------------------------------------------------------
    // 清单
    // ------------------------------------------------------------------

    /** 取清单。任何失败（404 / 超时 / 解析不了）都返回 null，不抛。 */
    public static Manifest fetchManifest() {
        HttpURLConnection conn = null;
        try {
            URL u = new URL(MANIFEST_URL);
            conn = (HttpURLConnection) u.openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("User-Agent", BaiduPan.DESKTOP_UA);
            // 清单必须每次拿最新，避免中间层缓存住旧版本
            conn.setRequestProperty("Cache-Control", "no-cache");
            int code = conn.getResponseCode();
            if (code != 200) {
                Log.d(TAG, "DbUpdater: manifest HTTP " + code + "（按无更新处理）");
                return null;
            }
            return parse(streamToString(conn.getInputStream()));
        } catch (Throwable e) {
            Log.d(TAG, "DbUpdater: manifest 拉取失败（按无更新处理）" + e);
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    static Manifest parse(String json) {
        try {
            JSONObject o = new JSONObject(json);
            JSONObject db = o.optJSONObject("db");
            if (db == null) return null;
            Manifest m = new Manifest();
            m.url = db.optString("url", "");
            m.version = (int) db.optLong("version", -1);
            m.schemaVersion = db.optInt("schemaVersion",
                    db.optInt("schema_version", -1));
            m.sha256 = db.optString("sha256", "").toLowerCase(java.util.Locale.ROOT);
            m.size = db.optLong("size", -1);
            m.buildId = db.optString("db_build_id", "");
            org.json.JSONArray cl = o.optJSONArray("changelog");
            if (cl != null && cl.length() > 0) {
                m.notes = cl.optJSONObject(0).optString("notes", "");
            }
            return m;
        } catch (Throwable e) {
            Log.d(TAG, "DbUpdater.parse " + e);
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 下载 + 校验 + 替换
    // ------------------------------------------------------------------

    private static boolean tryInstall(Context ctx, Manifest mf) {
        File tmp = new File(ctx.getCacheDir(), "SuperMOV.db.download");
        //noinspection ResultOfMethodCallIgnored
        tmp.delete();
        if (!download(mf.url, tmp, mf.size)) return false;

        // ① 体积
        if (mf.size > 0 && tmp.length() != mf.size) {
            Log.d(TAG, "DbUpdater: 体积不符 期望=" + mf.size + " 实际=" + tmp.length());
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return false;
        }
        // ② sha256
        String sha = sha256(tmp);
        if (!mf.sha256.isEmpty() && !mf.sha256.equalsIgnoreCase(sha)) {
            Log.d(TAG, "DbUpdater: sha256 不符 期望=" + mf.sha256 + " 实际=" + sha);
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return false;
        }
        // ③ SQLite 自检
        if (!integrityOk(tmp)) {
            Log.d(TAG, "DbUpdater: integrity_check 未通过");
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return false;
        }
        // ④ 库内版本必须与清单一致（挡住「清单是新的、包还是旧的」这种发布事故）
        int v = MovieDb.dbVersionOf(ctx, tmp);
        if (mf.version > 0 && v != mf.version) {
            Log.d(TAG, "DbUpdater: 包内 db_version=" + v + " 与清单 " + mf.version + " 不一致");
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return false;
        }
        // ⑤ 结构版本必须能读
        int sv = readMetaInt(tmp, "schema_version");
        if (sv > MovieDb.SCHEMA_SUPPORTED) {
            Log.d(TAG, "DbUpdater: schema " + sv + " 超出本 App 支持范围");
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return false;
        }

        MovieDb.replaceAtomically(tmp, MovieDb.dbFile(ctx));
        MovieDb.setInstalledDbVersion(ctx, mf.version);
        Log.d(TAG, "DbUpdater: 已切换到 v" + mf.version + " (" + sha.substring(0, 12) + ")");
        return true;
    }

    private static boolean download(String url, File dest, long expectSize) {
        HttpURLConnection conn = null;
        InputStream in = null;
        FileOutputStream out = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("User-Agent", BaiduPan.DESKTOP_UA);
            int code = conn.getResponseCode();
            if (code != 200) {
                Log.d(TAG, "DbUpdater: db HTTP " + code);
                return false;
            }
            long len = conn.getContentLength();
            if (len > MAX_DOWNLOAD) {
                Log.d(TAG, "DbUpdater: 响应过大 " + len);
                return false;
            }
            in = conn.getInputStream();
            out = new FileOutputStream(dest);
            byte[] buf = new byte[128 * 1024];
            long total = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_DOWNLOAD) {
                    Log.d(TAG, "DbUpdater: 超过下载上限");
                    return false;
                }
                out.write(buf, 0, n);
            }
            out.flush();
            return true;
        } catch (Throwable e) {
            Log.d(TAG, "DbUpdater.download " + e);
            return false;
        } finally {
            closeQuietly(in);
            closeQuietly(out);
            if (conn != null) conn.disconnect();
        }
    }

    static boolean integrityOk(File f) {
        SQLiteDatabase db = null;
        Cursor c = null;
        try {
            db = SQLiteDatabase.openDatabase(f.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
            c = db.rawQuery("PRAGMA integrity_check", null);
            if (c.moveToFirst()) {
                String r = c.getString(0);
                return "ok".equalsIgnoreCase(r);
            }
            return false;
        } catch (Throwable e) {
            Log.d(TAG, "DbUpdater.integrityOk " + e);
            return false;
        } finally {
            if (c != null) c.close();
            if (db != null) db.close();
        }
    }

    private static int readMetaInt(File f, String key) {
        SQLiteDatabase db = null;
        Cursor c = null;
        try {
            db = SQLiteDatabase.openDatabase(f.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
            c = db.rawQuery("SELECT value FROM meta WHERE key=?", new String[]{key});
            if (c.moveToFirst()) return Integer.parseInt(c.getString(0).trim());
            return -1;
        } catch (Throwable e) {
            return -1;
        } finally {
            if (c != null) c.close();
            if (db != null) db.close();
        }
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    static String sha256(File f) {
        InputStream in = null;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            in = new java.io.FileInputStream(f);
            byte[] buf = new byte[128 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            return toHex(md.digest());
        } catch (Throwable e) {
            Log.d(TAG, "DbUpdater.sha256 " + e);
            return "";
        } finally {
            closeQuietly(in);
        }
    }

    private static String toHex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) {
            sb.append(Character.forDigit((x >> 4) & 0xF, 16));
            sb.append(Character.forDigit(x & 0xF, 16));
        }
        return sb.toString();
    }

    private static String streamToString(InputStream is) throws java.io.IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        return new String(bos.toByteArray(), "UTF-8");
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (Throwable ignore) {
        }
    }
}
