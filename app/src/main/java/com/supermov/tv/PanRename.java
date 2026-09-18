package com.supermov.tv;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 转存后更名。
 *
 * <h3>为什么要它</h3>
 * 部分分享把影片文件的扩展名做了伪装（例如分享里是 {@code .xs}，实际是 mp4 内容）。
 * 这类文件转存到自己的网盘后，扩展名仍然是 {@code .xs}：
 * <ul>
 *   <li>播放器/解码器按后缀判断容器，可能直接拒绝打开；</li>
 *   <li>应用内按「视频后缀」筛选的列表会漏掉它；</li>
 *   <li>投屏、外部播放器、后续归档都会踩坑。</li>
 * </ul>
 * 所以转存成功后、正式起播之前，按规则把后缀改成真实容器后缀。
 *
 * <h3>三条硬规则（踩过坑的结论，别改）</h3>
 * <ol>
 *   <li><b>只改文件，绝不改目录</b>。改目录名会让目录下所有子路径失效，
 *       我们已经按 {@code pan_video.path} 记了绝对路径，改目录 = 全表作废。</li>
 *   <li><b>改名必须发生在转存之后</b>。分享里的文件不属于自己，改不了；
 *       转存到自己网盘后 {@code fs_id} 才是可改名的。</li>
 *   <li><b>改完必须回读校验</b>。filemanager 的响应在不同账号/风控下不一致，
 *       不能只看 errno 就算成功，要重新 list 一次目录确认新名字真的在。</li>
 * </ol>
 *
 * <h3>调用位置</h3>
 * {@code DetailActivity} 转存成功拿到 {@code toPaths} 之后、构建剧集列表之前：
 * <pre>
 *   PanRename.Result rr = PanRename.renameAfterTransfer(ctx, toPaths);
 *   if (rr.renamed &gt; 0) { 重新拉一次剧集列表 }
 * </pre>
 */
public final class PanRename {

    private static final String TAG = "SupeMov";
    private static final String LEDGER = "pan_rename_ledger";

    /** 单次 filemanager 请求携带的改名条目上限。批量太大时部分失败不好定位。 */
    private static final int BATCH = 20;

    private PanRename() {
    }

    // ------------------------------------------------------------------
    // 规则
    // ------------------------------------------------------------------

    /** 一条更名规则。scope=ext 时用 pattern 匹配后缀、newExt 作为目标后缀；
     *  scope=filename_regex 时用 pattern+replacement 对整名做正则替换。 */
    public static class Rule {
        public String name = "";
        public String scope = "ext";
        public String pattern = "";
        public String newExt = "";
        public String replacement = "";
        public int priority = 100;
        public boolean enabled = true;

        @Override
        public String toString() {
            return "ext".equals(scope)
                    ? (pattern + " -> " + newExt + " (p" + priority + ")")
                    : (pattern + " ~> " + replacement + " (p" + priority + ")");
        }
    }

    /**
     * 内置兜底规则。
     *
     * <p><b>只在读不到数据库的 rename_rule 表时使用</b>。库里的规则是权威的，
     * 因为后缀伪装的花样会变，运营侧改库就能生效，不必重新发版。</p>
     *
     * <p>注意：当前实测库里只出现过 {@code .mkv} 与 {@code .mp4}，下面这些是
     * 按需求预置的。真正上线前请拿出现 {@code .xs} 的那个分享实测一次，
     * 确认命中与结果。</p>
     */
    private static final Rule[] BUILTIN = new Rule[]{
            mk("xs2mp4", ".xs", ".mp4", 10),
            mk("xsv2mp4", ".xsv", ".mp4", 10),
            mk("txs2mp4", ".txs", ".mp4", 10),
            mk("mp41_2mp4", ".mp41", ".mp4", 20),
            mk("mp4v2mp4", ".mp4v", ".mp4", 20),
            mk("mkv1_2mkv", ".mkv1", ".mkv", 20),
    };

    private static Rule mk(String name, String from, String to, int prio) {
        Rule r = new Rule();
        r.name = name;
        r.scope = "ext";
        r.pattern = from;
        r.newExt = to;
        r.priority = prio;
        r.enabled = true;
        return r;
    }

    /** 从 SuperMOV.db 的 rename_rule 表读规则；任何异常都退回内置规则。 */
    public static List<Rule> loadRules(Context ctx) {
        List<Rule> out = new ArrayList<Rule>();
        SQLiteDatabase db = null;
        Cursor c = null;
        try {
            String path = MovieDb.dbPath(ctx);
            db = SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY);
            c = db.rawQuery("SELECT name,scope,pattern,new_ext,replacement,priority"
                    + " FROM rename_rule WHERE enabled=1 ORDER BY priority ASC", null);
            while (c.moveToNext()) {
                Rule r = new Rule();
                r.name = c.isNull(0) ? "" : c.getString(0);
                r.scope = c.isNull(1) ? "ext" : c.getString(1);
                r.pattern = c.isNull(2) ? "" : c.getString(2);
                r.newExt = c.isNull(3) ? "" : c.getString(3);
                r.replacement = c.isNull(4) ? "" : c.getString(4);
                r.priority = c.getInt(5);
                r.enabled = true;
                if (!r.pattern.isEmpty()) out.add(r);
            }
        } catch (Throwable e) {
            // 老库没有 rename_rule 表 / 数据库还没就位 —— 都不该让更名功能整体失效
            Log.d(TAG, "PanRename.loadRules fallback -> builtin (" + e + ")");
        } finally {
            if (c != null) c.close();
            if (db != null) db.close();
        }
        if (out.isEmpty()) Collections.addAll(out, BUILTIN);
        Collections.sort(out, new Comparator<Rule>() {
            @Override
            public int compare(Rule a, Rule b) {
                return a.priority - b.priority;
            }
        });
        return out;
    }

    // ------------------------------------------------------------------
    // 名字计算
    // ------------------------------------------------------------------

    private static final Pattern HAS_EXT = Pattern.compile("^(.*)\\.([A-Za-z0-9]{1,8})$");
    /** 百度禁止的字符：< > : " / \ | ? * （中文全角的 ：／ 是允许的，别误伤） */
    private static final Pattern ILLEGAL = Pattern.compile("[<>:\"/\\\\|?*\\x00-\\x1f]");
    private static final int MAX_NAME_BYTES = 250;

    /** 算目标文件名。返回 null 表示「不需要改」。 */
    public static String targetName(String fileName, List<Rule> rules) {
        if (fileName == null || fileName.isEmpty()) return null;
        Matcher em = HAS_EXT.matcher(fileName);
        if (!em.matches()) return null;               // 没有后缀，不动
        String stem = em.group(1);
        String ext = "." + em.group(2).toLowerCase(Locale.ROOT);

        String best = null;
        int bestPrio = Integer.MAX_VALUE;
        for (Rule r : rules) {
            if (r == null || !r.enabled) continue;
            String cand = null;
            if ("filename_regex".equals(r.scope)) {
                if (r.pattern.isEmpty() || r.replacement == null) continue;
                try {
                    if (Pattern.compile(r.pattern).matcher(fileName).find()) {
                        cand = fileName.replaceAll(r.pattern, r.replacement);
                    }
                } catch (Throwable ignore) {
                    continue;
                }
            } else {
                if (r.pattern.equalsIgnoreCase(ext) && !r.newExt.isEmpty()) {
                    cand = stem + r.newExt;
                }
            }
            if (cand == null || cand.equals(fileName)) continue;
            if (r.priority < bestPrio) {          // 同优先级先到先得 = 表里 order by priority
                bestPrio = r.priority;
                best = cand;
            }
        }
        if (best == null) return null;
        best = sanitize(best);
        if (best.isEmpty() || best.equals(fileName)) return null;
        return best;
    }

    /** 清掉非法字符、修掉尾部点/空格、按 UTF-8 字节上限截断（保留后缀）。 */
    private static String sanitize(String name) {
        name = ILLEGAL.matcher(name).replaceAll("_");
        name = name.replaceAll("[. ]+$", "");           // 尾部点和空格百度不收
        if (name.getBytes(java.nio.charset.Charset.forName("UTF-8")).length <= MAX_NAME_BYTES) {
            return name;
        }
        Matcher em = HAS_EXT.matcher(name);
        String stem = em.matches() ? em.group(1) : name;
        String ext = em.matches() ? "." + em.group(2) : "";
        int budget = MAX_NAME_BYTES - ext.getBytes(java.nio.charset.Charset.forName("UTF-8")).length;
        StringBuilder sb = new StringBuilder();
        int used = 0;
        for (int i = 0; i < stem.length(); i++) {
            int w = String.valueOf(stem.charAt(i))
                    .getBytes(java.nio.charset.Charset.forName("UTF-8")).length;
            if (used + w > budget) break;
            sb.append(stem.charAt(i));
            used += w;
        }
        return sb.toString() + ext;
    }

    /** 在目录里为一堆已有文件名挑一个不撞名的目标名：xxx.mp4 -> xxx (2).mp4 */
    private static String dedup(String want, java.util.Set<String> takenLow) {
        if (!takenLow.contains(want.toLowerCase(Locale.ROOT))) return want;
        Matcher em = HAS_EXT.matcher(want);
        String stem = em.matches() ? em.group(1) : want;
        String ext = em.matches() ? "." + em.group(2) : "";
        for (int n = 2; n <= 99; n++) {
            String cand = stem + " (" + n + ")" + ext;
            if (!takenLow.contains(cand.toLowerCase(Locale.ROOT))) return cand;
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 计划 & 执行
    // ------------------------------------------------------------------

    /** 一条待更名记录：也是回写给调用方（用于落台账）的契约。 */
    public static class Item {
        public long fsId;
        public String dir = "";
        public String oldName = "";
        public String oldPath = "";
        public String newName = "";
        public String newPath = "";
        public String state = "pending";   // pending / ok / skip / fail
        public String msg = "";
    }

    public static class Result {
        public int scanned;      // 扫描到的文件数
        public int planned;      // 需要改名的数目
        public int renamed;      // 实际改名成功数
        public int skipped;      // 无需改（已是目标后缀）
        public int failed;
        public final List<Item> items = new ArrayList<Item>();
        public String message = "";
    }

    /**
     * 对一个目录列表算出改名计划。
     *
     * @param list api/list 返回的 JSON 数组
     * @param dir  这个列表对应的目录绝对路径（作为 filelist.path 提交）
     */
    public static List<Item> plan(JSONArray list, String dir, List<Rule> rules) {
        List<Item> out = new ArrayList<Item>();
        if (list == null) return out;
        java.util.Set<String> takenLow = new java.util.HashSet<String>();
        for (int i = 0; i < list.length(); i++) {
            JSONObject f = list.optJSONObject(i);
            if (f == null) continue;
            takenLow.add(f.optString("server_filename", "").toLowerCase(Locale.ROOT));
        }
        for (int i = 0; i < list.length(); i++) {
            JSONObject f = list.optJSONObject(i);
            if (f == null) continue;
            if (f.optInt("isdir", 0) == 1) continue;        // ★ 目录一律不动
            String nm = f.optString("server_filename", "");
            if (nm.isEmpty()) continue;
            String want = targetName(nm, rules);
            if (want == null) continue;
            Item it = new Item();
            it.fsId = f.optLong("fs_id", 0);
            it.dir = dir;
            it.oldName = nm;
            it.oldPath = f.optString("path", trimSlash(dir) + "/" + nm);
            it.newName = dedup(want, takenLow);
            if (it.newName == null) {
                it.state = "fail";
                it.msg = "目标名冲突且 (2)~(99) 都占用了";
                out.add(it);
                continue;
            }
            takenLow.add(it.newName.toLowerCase(Locale.ROOT));
            it.newPath = trimSlash(dir) + "/" + it.newName;
            if (it.fsId <= 0) {
                it.state = "fail";
                it.msg = "列表缺少 fs_id，无法改名";
            }
            out.add(it);
        }
        return out;
    }

    /**
     * 转存成功后的总入口：对 {@code toPaths} 里的每个目录做一次「扫描 -> 改名 -> 校验」。
     *
     * <p>转存可能是「整包一个文件夹」，也可能是「若干散文件平铺」。两种情况都覆盖：
     * 对每个落地路径，先尝试当目录列一遍；列不出内容就当文件处理（取其父目录）。</p>
     */
    public static Result renameAfterTransfer(Context ctx, List<String> toPaths) {
        Result res = new Result();
        if (toPaths == null || toPaths.isEmpty()) {
            res.message = "没有转存落地路径，跳过后缀更名";
            return res;
        }
        List<Rule> rules = loadRules(ctx);
        Log.d(TAG, "PanRename: rules=" + rules);
        Http.desktopUa.set(true);
        try {
            for (String p : toPaths) {
                if (p == null || p.isEmpty()) continue;
                JSONArray list = BaiduPan.listDir(p);
                if (list != null) {
                    renameInDir(ctx, p, list, rules, res);
                } else {
                    // 不是目录 -> 当文件：拿父目录再试
                    String parent = parentOf(trimSlash(p));
                    String leaf = leafOf(trimSlash(p));
                    JSONArray pl = BaiduPan.listDir(parent);
                    if (pl != null) {
                        renameInDir(ctx, parent, pl, rules, res);
                    } else {
                        Log.d(TAG, "PanRename: 无法列出 " + parent + "，跳过 " + leaf);
                    }
                }
            }
        } catch (Throwable e) {
            res.message = "更名过程异常：" + e;
            Log.d(TAG, "PanRename err " + e);
        } finally {
            Http.desktopUa.set(false);
        }
        res.message = String.format(Locale.ROOT,
                "扫描 %d 个文件，需改名 %d，成功 %d，失败 %d",
                res.scanned, res.planned, res.renamed, res.failed);
        return res;
    }

    private static void renameInDir(Context ctx, String dir, JSONArray list, List<Rule> rules, Result res) {
        res.scanned += list.length();
        List<Item> items = plan(list, dir, rules);
        res.items.addAll(items);
        List<Item> todo = new ArrayList<Item>();
        for (Item it : items) {
            if ("fail".equals(it.state)) {
                res.failed++;
            } else {
                res.planned++;
                todo.add(it);
            }
        }
        if (todo.isEmpty()) return;

        for (int i = 0; i < todo.size(); i += BATCH) {
            List<Item> chunk = todo.subList(i, Math.min(i + BATCH, todo.size()));
            String errno = postRename(chunk);
            if ("0".equals(errno)) {
                for (Item it : chunk) it.state = "ok";
            } else if (isCollision(errno)) {
                // 服务端说撞名（我们预检过，一般是对面同时有别的文件落地）：
                // 逐个退化为「加序号」再试一次，避免整批失败。
                for (Item it : chunk) {
                    String alt = dedupCtx(it.dir, it.newName);
                    if (alt == null) {
                        it.state = "fail";
                        it.msg = "撞名且无可用后缀";
                        continue;
                    }
                    it.newName = alt;
                    it.newPath = trimSlash(it.dir) + "/" + alt;
                    List<Item> one = new ArrayList<Item>();
                    one.add(it);
                    String e2 = postRename(one);
                    it.state = "0".equals(e2) ? "ok" : "fail";
                    if (!"0".equals(e2)) it.msg = "errno=" + e2;
                }
            } else {
                for (Item it : chunk) {
                    it.state = "fail";
                    it.msg = "errno=" + errno;
                }
            }
        }

        // ★ 回读校验：响应不可尽信，重新列一次目录确认真落地
        JSONArray after = BaiduPan.listDir(dir);
        java.util.Set<String> nowLow = new java.util.HashSet<String>();
        if (after != null) {
            for (int i = 0; i < after.length(); i++) {
                JSONObject f = after.optJSONObject(i);
                if (f != null) nowLow.add(f.optString("server_filename", "").toLowerCase(Locale.ROOT));
            }
        }
        for (Item it : items) {
            if (!"ok".equals(it.state)) continue;
            if (nowLow.contains(it.newName.toLowerCase(Locale.ROOT))) {
                res.renamed++;
                ledgerPut(ctx, it);
            } else {
                it.state = "fail";
                it.msg = "改名响应成功但回读没看到新名字";
                res.failed++;
            }
        }
    }

    private static String dedupCtx(String dir, String want) {
        JSONArray l = BaiduPan.listDir(dir);
        java.util.Set<String> taken = new java.util.HashSet<String>();
        if (l != null) {
            for (int i = 0; i < l.length(); i++) {
                JSONObject f = l.optJSONObject(i);
                if (f != null) taken.add(f.optString("server_filename", "").toLowerCase(Locale.ROOT));
            }
        }
        return dedup(want, taken);
    }

    /** 提交一批改名。返回 errno 字符串（失败时 "?" 表示响应不可解析）。 */
    private static String postRename(List<Item> items) {
        try {
            JSONArray fl = new JSONArray();
            for (Item it : items) {
                JSONObject o = new JSONObject();
                o.put("id", it.fsId);
                o.put("path", trimSlash(it.dir));
                o.put("newname", it.newName);
                fl.put(o);
            }
            String bdstoken = bdstoken();
            String url = "https://pan.baidu.com/api/filemanager"
                    + "?opera=rename&async=2&onnest=fail&channel=chunlei&web=1"
                    + "&app_id=250528&clienttype=0&bdstoken=" + bdstoken;
            String body = "filelist=" + enc(fl.toString());
            java.util.Map<String, String> h = new java.util.HashMap<String, String>();
            String bduss = CookieStore.get("https://pan.baidu.com", "BDUSS");
            h.put("Cookie", "BDUSS=" + (bduss == null ? "" : bduss));
            h.put("Referer", "https://pan.baidu.com/disk/home");
            h.put("X-Requested-With", "XMLHttpRequest");
            Http.Resp r = Http.request("POST", url, body, h, true);
            Log.d(TAG, "PanRename: errno=" + errnoOf(r.body) + " body="
                    + r.body.substring(0, Math.min(240, r.body.length())));
            return errnoOf(r.body);
        } catch (Throwable e) {
            Log.d(TAG, "PanRename.postRename err " + e);
            return "?";
        }
    }

    /** 服务端「撞名」类 errno。百度在不同接口上用过 12 / -8 / -7，先都当撞名处理。 */
    private static boolean isCollision(String errno) {
        return "12".equals(errno) || "-8".equals(errno) || "-7".equals(errno);
    }

    private static String errnoOf(String body) {
        try {
            JSONObject o = new JSONObject(body);
            return String.valueOf(o.opt("errno"));
        } catch (Throwable e) {
            return "?";
        }
    }

    private static String bdstoken() {
        // bdstoken 与 STOKEN 是两个不同的东西，必须走接口取，不能拿 Cookie 顶替
        Http.Resp r = Http.get("https://pan.baidu.com/api/gettemplatevariable"
                + "?clienttype=0&app_id=250528&web=1&fields=%5B%22bdstoken%22%5D");
        try {
            JSONObject o = new JSONObject(r.body);
            JSONObject res = o.optJSONObject("result");
            if (res != null) {
                String v = res.optString("bdstoken", "");
                if (!v.isEmpty()) {
                    CookieStore.put("https://pan.baidu.com/", "bdstoken", v);
                    return v;
                }
            }
        } catch (Throwable ignore) {
        }
        String cached = CookieStore.get("https://pan.baidu.com", "bdstoken");
        return cached == null ? "" : cached;
    }

    // ------------------------------------------------------------------
    // 台账：把 oldPath -> newPath 记下来，供后续取流/续播使用
    // ------------------------------------------------------------------
    // 说明：SuperMOV.db 是随 APK 分发的只读库，改不得；所以本地状态（含这张台账）
    // 存在应用私有 SharedPreferences 里。将来有更多本地状态（进度、收藏）时，
    // 建议统一迁到 appstate.db（见设计文档「本地状态库」一节）。

    private static SharedPreferences sp(Context ctx) {
        return ctx.getSharedPreferences(LEDGER, Context.MODE_PRIVATE);
    }

    private static void ledgerPut(Context ctx, Item it) {
        try {
            sp(ctx).edit().putString(it.oldPath, it.newPath).apply();
        } catch (Throwable ignore) {
        }
    }

    /** 查台账：原绝对路径 -> 改名后的绝对路径（没改过返回原值）。 */
    public static String resolvePath(Context ctx, String path) {
        try {
            String v = sp(ctx).getString(path, null);
            return (v == null || v.isEmpty()) ? path : v;
        } catch (Throwable e) {
            return path;
        }
    }

    public static void clearLedger(Context ctx) {
        try {
            sp(ctx).edit().clear().apply();
        } catch (Throwable ignore) {
        }
    }

    // ------------------------------------------------------------------
    // 小工具（刻意与 BaiduPan 内部实现解耦，避免改一处牵连另一处）
    // ------------------------------------------------------------------

    static String trimSlash(String p) {
        if (p == null || p.isEmpty()) return "/";
        String s = p;
        while (s.length() > 1 && s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    static String parentOf(String absPath) {
        int i = absPath.lastIndexOf('/');
        if (i <= 0) return "/";
        return absPath.substring(0, i);
    }

    static String leafOf(String absPath) {
        int i = absPath.lastIndexOf('/');
        return i < 0 ? absPath : absPath.substring(i + 1);
    }

    static String enc(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }
}
