package com.supermov.tv;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/** 下载队列持久化（SQLite）。断点分段进度另存在目标目录的 .dlpart.json 里。 */
public final class DlDb extends SQLiteOpenHelper {

    private static final String NAME = "supermov_dl.db";
    private static final int VER = 1;

    private static DlDb inst;

    public static synchronized DlDb get(Context c) {
        if (inst == null) inst = new DlDb(c.getApplicationContext());
        return inst;
    }

    private DlDb(Context c) {
        super(c, NAME, null, VER);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS dl("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "fid INTEGER DEFAULT 0,"
                + "tid TEXT,"
                + "name TEXT,"
                + "root_dir TEXT,"
                + "fs_id INTEGER DEFAULT 0,"
                + "bpath TEXT,"
                + "file_name TEXT,"
                + "dir TEXT,"
                + "total INTEGER DEFAULT 0,"
                + "done INTEGER DEFAULT 0,"
                + "status INTEGER DEFAULT 0,"
                + "error TEXT,"
                + "created INTEGER DEFAULT 0)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
        // 目前只有一个版本，后续加列时在这里做 ALTER TABLE
    }

    public long insert(Dl t) {
        ContentValues v = toValues(t);
        return getWritableDatabase().insert("dl", null, v);
    }

    public void update(Dl t) {
        if (t.id <= 0) return;
        try {
            getWritableDatabase().update("dl", toValues(t), "id=?",
                    new String[]{String.valueOf(t.id)});
        } catch (Throwable ignored) {
        }
    }

    public void delete(long id) {
        try {
            getWritableDatabase().delete("dl", "id=?", new String[]{String.valueOf(id)});
        } catch (Throwable ignored) {
        }
    }

    /** 清空整张任务表（不动已下好的文件）。 */
    public void deleteAll() {
        try {
            getWritableDatabase().delete("dl", null, null);
        } catch (Throwable ignored) {
        }
    }

    /** 只删「已完成」的任务行（不动已下好的文件）。 */
    public void clearFinished() {
        try {
            getWritableDatabase().delete("dl", "status=?", new String[]{String.valueOf(Dl.DONE)});
        } catch (Throwable ignored) {
        }
    }

    /** 全部任务，按加入顺序（即队列顺序）返回。 */
    public List<Dl> list() {
        List<Dl> out = new ArrayList<>();
        Cursor c = null;
        try {
            c = getReadableDatabase().query("dl", null, null, null, null, null, "id ASC");
            while (c.moveToNext()) out.add(fromCursor(c));
        } catch (Throwable ignored) {
        } finally {
            if (c != null) c.close();
        }
        return out;
    }

    private static ContentValues toValues(Dl t) {
        ContentValues v = new ContentValues();
        v.put("fid", t.fid);
        v.put("tid", t.tid);
        v.put("name", t.name);
        v.put("root_dir", t.rootDir);
        v.put("fs_id", t.fsId);
        v.put("bpath", t.bpath);
        v.put("file_name", t.fileName);
        v.put("dir", t.dir);
        v.put("total", t.total);
        v.put("done", t.done);
        v.put("status", t.status);
        v.put("error", t.error);
        v.put("created", t.created);
        return v;
    }

    private static Dl fromCursor(Cursor c) {
        Dl t = new Dl();
        t.id = c.getLong(c.getColumnIndexOrThrow("id"));
        t.fid = c.getInt(c.getColumnIndexOrThrow("fid"));
        t.tid = s(c, "tid");
        t.name = s(c, "name");
        t.rootDir = s(c, "root_dir");
        t.fsId = c.getLong(c.getColumnIndexOrThrow("fs_id"));
        t.bpath = s(c, "bpath");
        t.fileName = s(c, "file_name");
        t.dir = s(c, "dir");
        t.total = c.getLong(c.getColumnIndexOrThrow("total"));
        t.done = c.getLong(c.getColumnIndexOrThrow("done"));
        t.status = c.getInt(c.getColumnIndexOrThrow("status"));
        t.error = s(c, "error");
        t.created = c.getLong(c.getColumnIndexOrThrow("created"));
        return t;
    }

    private static String s(Cursor c, String col) {
        int i = c.getColumnIndexOrThrow(col);
        String v = c.isNull(i) ? "" : c.getString(i);
        return v == null ? "" : v;
    }
}
