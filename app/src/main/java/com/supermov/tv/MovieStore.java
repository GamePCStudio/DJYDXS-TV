package com.supermov.tv;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 内容仓库（本 App 唯一取数入口）。
 *
 * <h3>数据来源</h3>
 * 全部来自内嵌/已在线更新的 {@code SuperMOV.db}，不再访问任何论坛页面。
 * 只读两个<b>视图</b>，不碰基表：
 * <ul>
 *   <li>{@code v_movie_app} —— 影片主表（干净片名、年份、题材、豆瓣/IMDb 评分、网盘链接、剧集数、总容量）</li>
 *   <li>{@code v_episode}   —— 剧集/文件清单（完整网盘路径、后缀、大小、季集号、分享链接与 shareid）</li>
 * </ul>
 *
 * <p><b>为什么只读视图：</b>上游 {@code SuperMOV.db} 未来会重构（新增豆瓣/TMDB ID、
 * 拆表、换字段名）。只要视图的列名不变，App 一行代码都不用改。视图定义由数据侧维护，
 * 是 App 与数据库之间唯一的契约。新增字段请在视图里加列，不要改名字。</p>
 *
 * <h3>与旧 Site 的关系</h3>
 * 本类整体替换了原来的 {@code Site}（论坛 Discuz 解析）。数据模型
 * （{@link Movie} / {@link Category} / {@link Filter} / {@link Box} / {@link Detail} / {@link Paged}）
 * 刻意保持了与 Site 相同的形状，界面层只需把 {@code Site.} 换成 {@code MovieStore.}。
 */
public final class MovieStore {

    private static final String TAG = "SupeMov";

    /** 列表每页条数。数据在本地，比论坛翻页可以放宽很多。 */
    public static final int PAGE_SIZE = 40;

    private static Context appCtx;
    private static SQLiteDatabase db;

    private MovieStore() {
    }

    // ==================================================================
    // 初始化 / 连接
    // ==================================================================

    /** 首次使用前调用（各界面 onCreate 里调一次即可）。幂等。 */
    public static synchronized void init(Context ctx) {
        if (ctx == null) return;
        if (appCtx == null) appCtx = ctx.getApplicationContext();
        database();
    }

    private static synchronized SQLiteDatabase database() {
        if (db != null && db.isOpen()) return db;
        if (appCtx == null) {
            Log.d(TAG, "MovieStore: 未 init，无法打开数据库");
            return null;
        }
        try {
            db = MovieDb.openReadOnly(appCtx);
            pinKnown = null;    // 新开的连接 -> 视图结构要重新探一次
        } catch (Throwable e) {
            Log.d(TAG, "MovieStore.database 打开失败: " + e);
            db = null;
        }
        return db;
    }

    /** 数据库被在线更新替换后调用：关掉旧连接并重开。 */
    public static synchronized void reload() {
        try {
            if (db != null) db.close();
        } catch (Throwable ignore) {
        }
        db = null;
        filterCache.clear();
        cats = null;
        pinKnown = null;    // 换库后视图结构可能变（在线更新可能装回一版老库），必须重探
        database();
    }

    /** 库是否可用（用于界面给出「数据库未就位」这类明确提示，而不是空列表）。 */
    public static boolean isReady() {
        return database() != null;
    }

    // ==================================================================
    // 数据模型（形状与旧 Site 保持一致，界面层改动最小）
    // ==================================================================

    public static class Category {
        public final int fid;
        public final String name;
        public final List<Filter> filters = new ArrayList<>();

        public Category(int fid, String name) {
            this.fid = fid;
            this.name = name;
        }
    }

    /**
     * 过滤器。
     *
     * <p>旧实现里 {@code typeid} 是论坛的主题分类 id；现在数据库没有这个概念，
     * 于是 {@code typeid} 退化为「同一个版块内过滤器的序号」（0 = 全部），
     * 真正的过滤条件放在 {@link #key}（题材名），由 {@link #category} 查表还原成 SQL。</p>
     */
    public static class Filter {
        public final String name;
        public final int typeid;
        /** 题材名（对应 movie.genres 里的一个标签）；空串 = 不过滤。 */
        public final String key;

        public Filter(String name, int typeid) {
            this(name, typeid, "");
        }

        public Filter(String name, int typeid, String key) {
            this.name = name;
            this.typeid = typeid;
            this.key = key == null ? "" : key;
        }
    }

    /** 列表 / 详情共用的影片模型。字段名沿用旧 Site，避免界面层大改。 */
    public static class Movie {
        /** 稳定主键，取自 movie.src_tid（对应原论坛帖子 id）。界面用它做去重与跳转。 */
        public String tid = "";
        public int fid;
        /** 版块名（数据库 forum_name，即分类栏上那个标签）。 */
        public String forumName = "";
        /** 干净中文片名（movie.title_cn）。 */
        public String name = "";
        /** 英文片名。 */
        public String nameEn = "";
        /** 别名（JSON 数组字符串，可能为空）。 */
        public String altNames = "";
        /** 海报地址。 */
        public String pic = "";
        /** 角标："2026 · 117分钟"。 */
        public String remarks = "";
        /** 资料区文本（◎译名 / ◎年代 / … / ◎上映日期），详情页上半屏直接显示。 */
        public String content = "";
        /** 简介正文。 */
        public String intro = "";
        public String year = "";
        /** 上映日期 年-月-日，可能为空。 */
        public String releaseDate = "";
        /**
         * 片长（分钟），0 = 未知。
         *
         * <p>{@link #remarks} 里虽然也含片长，但那是「年 · 分钟 · N集」拼好的一整串，
         * Hero 信息行要的是「地区 / 年份 / 类型 / 片长」这种逐项可控的形态，
         * 拼串拆不出来，所以单列一个字段。</p>
         */
        public int runtimeMin;
        public String genres = "";
        public String region = "";
        public double ratingDouban;
        public double ratingImdb;
        public boolean hasDouban;
        public boolean hasImdb;
        /** 豆瓣 / IMDb 链接（有则给出，详情页可跳转）。 */
        public String doubanUrl = "";
        public String imdbUrl = "";
        public int videoCount;
        public long totalSize;
        /** 网盘分享链接与提取码（第一版取该片的第一条可用链接）。 */
        public String panUrl = "";
        public String panPwd = "";
        public String panStatus = "";
        /** 稳定 uid（movie.uid，形如 t12345），剧集表按它关联。 */
        public String uid = "";
        /** "1080P.Remux" 之类的原始归类，详情页可显示。 */
        public String classification = "";
    }

    public static class Box {
        public String type = "baidu";
        public String url;
        public String pwd;
    }

    /** 详情 = 影片资料 + 可用链接 + 库内已知的剧集清单（零网络）。 */
    public static class Detail {
        public Movie movie = new Movie();
        public List<Box> boxes = new ArrayList<>();
        /** 库内已知的视频文件（来自 v_episode）。顺序已按 季 → 集 → 路径 排好。 */
        public List<Episode> episodes = new ArrayList<>();
    }

    /** 剧集/文件条目（来自 v_episode）。 */
    public static class Episode {
        public long videoId;
        public long panLinkId;
        /** 完整网盘路径，如 /转载/千年魔戒.DVDRip/xxx.mkv */
        public String path = "";
        public String filename = "";
        /** 转存后应使用的文件名（rename_to 有值时非空）。 */
        public String finalFilename = "";
        public String ext = "";
        public long size;
        public int season;
        public int episode;
        public String panUrl = "";
        public String panPwd = "";
        public String shareid = "";
        public String shareUk = "";

        public String displayName() {
            return finalFilename == null || finalFilename.isEmpty() ? filename : finalFilename;
        }
    }

    public static class Paged<T> {
        public T data;
        public int pageCount = 1;
    }

    /** 最近一次加载的失败状态。数据库版没有「登录过期 / 搜索限流」，恒为空串，仅为界面兼容保留。 */
    public static volatile String lastLoadError = "";

    // ==================================================================
    // 分类与过滤器
    // ==================================================================

    private static volatile List<Category> cats;

    /**
     * 分类栏白名单：{fid, 界面显示名}，<b>数组顺序即界面顺序</b>。
     *
     * <p>为什么从这里定死，而不是继续从 {@code forum_name} 现取：数据侧的版块名是给站长看的
     * （"4KSDR.Remux"、"1080P高码版"），不是给用户看的。这里做三件事 ——
     * <b>只保留这四个版块</b>、<b>用产品化的显示名覆盖</b>、<b>固定排序</b>；
     * 数据侧再冒出别的版块，也不会漏到界面上。</p>
     *
     * <p>注意匹配用 <b>fid</b> 而不是版块名：名字改过好几轮了（"4K全景声" → "4KSDR.Remux"），
     * 拿名字当 key 早晚失配；fid 是数据侧的主键，稳。当前对应关系 ——</p>
     * <pre>
     *   112  4KSDR.Remux     → 4K全景声
     *    37  1080P最新剧集    → 最新剧集•美剧
     *    58  1080P高码版      → 蓝光影片
     *     2  最新1080P电影    → 杜比5.1影片
     * </pre>
     */
    private static final int[] CAT_FIDS = {112, 37, 58, 2};
    private static final String[] CAT_NAMES = {"4K全景声", "最新剧集•美剧", "蓝光影片", "杜比5.1影片"};

    /**
     * 版块（分类栏）：严格按 {@link #CAT_FIDS} 的顺序与 {@link #CAT_NAMES} 的显示名产出，
     * 其余版块一律不显示。
     *
     * <p>仍会读一次库拿到各版块的影片数，但<b>只用于日志诊断</b>（某个栏目为什么是空的，
     * 一看 logcat 便知）；即便某个 fid 当前 0 部也照样出栏 —— 保证界面上这四个位置永远固定，
     * 不会因为数据波动导致栏目错位。</p>
     */
    public static List<Category> categories() {
        List<Category> c0 = cats;
        if (c0 != null) return c0;
        Map<Integer, Integer> counts = new HashMap<>();
        SQLiteDatabase q = database();
        if (q != null) {
            Cursor c = null;
            try {
                c = q.rawQuery("SELECT fid, COUNT(*) FROM v_movie_app GROUP BY fid", null);
                while (c.moveToNext()) {
                    counts.put((int) lng(c, 0), (int) lng(c, 1));
                }
            } catch (Throwable e) {
                Log.d(TAG, "categories 查询失败: " + e);
            } finally {
                if (c != null) c.close();
            }
        }
        List<Category> out = new ArrayList<>();
        for (int i = 0; i < CAT_FIDS.length; i++) {
            Integer n = counts.get(CAT_FIDS[i]);
            if (n == null || n == 0) {
                // 出栏但不报数据 —— 这种情况基本只有两种原因：库没就位，或数据侧版块被清空了
                Log.d(TAG, "分类栏 [" + CAT_NAMES[i] + "] fid=" + CAT_FIDS[i] + " 在当前库中没有影片");
            } else {
                Log.d(TAG, "分类栏 [" + CAT_NAMES[i] + "] fid=" + CAT_FIDS[i] + " 共 " + n + " 部");
            }
            out.add(new Category(CAT_FIDS[i], CAT_NAMES[i]));
        }
        cats = out;
        return out;
    }

    private static final Map<Integer, List<Filter>> filterCache = new HashMap<>();

    /**
     * 某版块的过滤器：数据库驱动 —— 从该版块影片的题材标签里取出现频次最高的若干个。
     *
     * <p>这样以后数据侧新增题材（比如「悬疑」「武侠」）不需要改 App 代码，重启即生效。</p>
     */
    public static List<Filter> filtersFor(int fid) {
        synchronized (filterCache) {
            List<Filter> cached = filterCache.get(fid);
            if (cached != null) return cached;
        }
        List<Filter> out = new ArrayList<>();
        out.add(new Filter("全部", 0, ""));
        Map<String, Integer> freq = new LinkedHashMap<>();
        SQLiteDatabase q = database();
        if (q != null) {
            Cursor c = null;
            try {
                c = q.rawQuery("SELECT genres FROM v_movie_app WHERE " + fidWhere(fid)
                        + " AND genres IS NOT NULL AND genres != ''", null);
                while (c.moveToNext()) {
                    String g = c.getString(0);
                    for (String tag : parseTags(g)) {
                        Integer n = freq.get(tag);
                        freq.put(tag, n == null ? 1 : n + 1);
                    }
                }
            } catch (Throwable e) {
                Log.d(TAG, "filtersFor 题材统计失败: " + e);
            } finally {
                if (c != null) c.close();
            }
        }
        // 按出现次数降序，最多取 10 个，避免过滤器行拉到屏幕外
        List<Map.Entry<String, Integer>> es = new ArrayList<>(freq.entrySet());
        Collections.sort(es, (a, b) -> b.getValue() - a.getValue());
        int idx = 1;
        for (Map.Entry<String, Integer> e : es) {
            if (idx > 10) break;
            // 只留真正有分量的题材（至少两部片子），否则会出现一长串只对应 1 部的标签
            if (e.getValue() < 2) continue;
            out.add(new Filter(e.getKey(), idx++, e.getKey()));
        }
        synchronized (filterCache) {
            filterCache.put(fid, out);
        }
        return out;
    }

    private static String filterKey(int fid, int typeid) {
        if (typeid <= 0) return "";
        for (Filter f : filtersFor(fid)) {
            if (f.typeid == typeid) return f.key;
        }
        return "";
    }

    // ==================================================================
    // 列表 / 搜索
    // ==================================================================

    /** 版块列表（按上映日期倒序，新片在前）。 */
    public static Paged<List<Movie>> category(int fid, int page) {
        return category(fid, page, 0);
    }

    /** 版块列表 + 题材过滤（typeid 来自 {@link #filtersFor(int)}，0 = 全部）。 */
    public static Paged<List<Movie>> category(int fid, int page, int typeid) {
        Paged<List<Movie>> out = new Paged<>();
        out.data = new ArrayList<>();
        StringBuilder where = new StringBuilder(fidWhere(fid));
        List<String> args = new ArrayList<>();
        String key = filterKey(fid, typeid);
        if (!key.isEmpty()) {
            where.append(" AND genres LIKE ?");
            args.add("%\"" + key + "\"%");
        }
        queryPage(out, where.toString(), args, page);
        return out;
    }

    /** 搜索：中文名 / 英文名 / 别名 三处匹配。 */
    public static Paged<List<Movie>> search(String kw, int page) {
        Paged<List<Movie>> out = new Paged<>();
        out.data = new ArrayList<>();
        String k = kw == null ? "" : kw.trim();
        if (k.isEmpty()) return out;
        String like = "%" + k + "%";
        // 全角/半角冒号、书名号都当普通字符处理；LIKE 通配符转义交给参数
        String where = "(name LIKE ? OR title_en LIKE ? OR title_alt LIKE ?)";
        List<String> args = new ArrayList<>();
        args.add(like);
        args.add(like);
        args.add(like);
        queryPage(out, where, args, page);
        return out;
    }

    /** 把一个 WHERE 条件 + 分页统一执行掉（列表列集合 + 默认排序）。 */
    private static void queryPage(Paged<List<Movie>> out, String where,
                                  List<String> args, int page) {
        queryPage(out, where, args, page, false, orderBy());
    }

    /**
     * 同上，但可指定「列集合」与「排序」。
     *
     * @param withSynopsis 是否多取一列 synopsis（专题行要拿它填 Hero 简介），
     *                     同时改用 {@link #readTopicRow} 读行 —— 列与读法必须成对，
     *                     否则下标错位会读出「看起来像数据、其实是另一列」的静默脏值
     * @param order        完整 ORDER BY 子句（调用方给，不再内部拼 {@link #orderBy()}）
     */
    private static void queryPage(Paged<List<Movie>> out, String where,
                                  List<String> args, int page,
                                  boolean withSynopsis, String order) {
        SQLiteDatabase q = database();
        if (q == null) {
            out.pageCount = 1;
            return;
        }
        int p = Math.max(1, page);
        Cursor c = null;
        try {
            int total = 0;
            c = q.rawQuery("SELECT COUNT(*) FROM v_movie_app WHERE " + where, toArray(args));
            if (c.moveToFirst()) total = c.getInt(0);
            c.close();
            c = null;
            out.pageCount = Math.max(1, (total + PAGE_SIZE - 1) / PAGE_SIZE);
            if ((p - 1) * PAGE_SIZE >= total) {
                out.pageCount = Math.max(1, out.pageCount);
                return;
            }
            List<String> a2 = new ArrayList<>(args);
            a2.add(String.valueOf(PAGE_SIZE));
            a2.add(String.valueOf((p - 1) * PAGE_SIZE));
            c = q.rawQuery("SELECT " + (withSynopsis ? DETAIL_COLS : LIST_COLS)
                    + " FROM v_movie_app WHERE " + where
                    + " ORDER BY " + order + " LIMIT ? OFFSET ?", toArray(a2));
            while (c.moveToNext()) {
                out.data.add(withSynopsis ? readTopicRow(c) : readListRow(c));
            }
        } catch (Throwable e) {
            Log.d(TAG, "queryPage 查询失败: " + e);
        } finally {
            if (c != null) c.close();
        }
    }

    /**
     * 列表查询的列。顺序与 {@link #readListRow} 里的下标一一对应 —— 改这里必须同步改那里。
     * 详情查询在此基础上追加 synopsis（见 {@link #DETAIL_COLS}）。
     */
    private static final String LIST_COLS = "src_tid,uid,fid,forum_name,name,title_en,"
            + "title_alt,year,release_date,runtime_min,region,genres,rating_douban,"
            + "rating_imdb,poster,classification,pan_status,pan_url,pan_pwd,"
            + "video_count,total_size";

    /**
     * 排序：**置顶在前**，然后有上映日期的按日期倒序，没有的按年份、再按 id 倒序。
     *
     * <p>用 {@code COALESCE} 把缺值压到末尾，避免 NULL 在 SQLite 里排最前导致
     * 「一堆没年份的片子」霸占首页。</p>
     *
     * <p>置顶（{@code movie.pin_top}）与日期无关：测试库、公告片这类「运营想固定摆在
     * 某栏目首位」的条目，靠伪造一个 9999 年的上映日期来实现是很脏的 ——
     * 那个假日期会直接显示在详情页的资料区里。所以单独给一列。</p>
     */
    private static final String ORDER_BY_PIN =
            "COALESCE(pin_top,0) DESC, COALESCE(release_date,'') DESC, "
                    + "COALESCE(year,0) DESC, src_tid DESC";

    /** 老库（视图里没有 pin_top）用的排序。写 pin_top 会整条 SQL 报错。 */
    private static final String ORDER_BY_PLAIN =
            "COALESCE(release_date,'') DESC, COALESCE(year,0) DESC, src_tid DESC";

    /**
     * 视图里到底有没有 pin_top。{@code null} = 还没探过。
     *
     * <p>App 是「内嵌库 + 在线更新」两条腿走路，完全可能碰上「新 App + 上一版库」——
     * 那一刻视图还没有 pin_top。而 {@code ORDER BY} 里写一个不存在的列会让整条查询
     * 直接抛异常，被 {@link #queryPage} 的兜底 catch 吞掉后，表现是<b>整个列表空白</b>
     * 而不是报错。这种「静默变空」最难查，所以打开库时探一次，探不到就退回旧排序，
     * 只是置顶不生效而已。</p>
     */
    private static volatile Boolean pinKnown;

    private static boolean pinTopReady() {
        Boolean v = pinKnown;
        if (v != null) return v;
        boolean ok = false;
        Cursor c = null;
        try {
            SQLiteDatabase q = db;
            if (q != null) {
                c = q.rawQuery("PRAGMA table_info(v_movie_app)", null);
                while (c.moveToNext()) {
                    if ("pin_top".equals(c.getString(1))) {
                        ok = true;
                        break;
                    }
                }
            }
        } catch (Throwable e) {
            Log.d(TAG, "探测 v_movie_app.pin_top 失败: " + e);
        } finally {
            if (c != null) c.close();
        }
        pinKnown = ok;
        Log.d(TAG, "置顶列 pin_top 可用 = " + ok);
        return ok;
    }

    private static String orderBy() {
        return pinTopReady() ? ORDER_BY_PIN : ORDER_BY_PLAIN;
    }

    private static Movie readListRow(Cursor c) {
        Movie m = new Movie();
        m.tid = str(c, 0);
        m.uid = str(c, 1);
        m.fid = (int) lng(c, 2);
        m.forumName = str(c, 3);
        m.name = str(c, 4);
        m.nameEn = str(c, 5);
        m.altNames = str(c, 6);
        m.year = str(c, 7);
        m.releaseDate = str(c, 8);
        m.runtimeMin = (int) lng(c, 9);
        m.region = str(c, 10);
        m.genres = str(c, 11);
        m.ratingDouban = dbl(c, 12);
        m.ratingImdb = dbl(c, 13);
        m.pic = str(c, 14);
        m.classification = str(c, 15);
        m.panStatus = str(c, 16);
        m.panUrl = str(c, 17);
        m.panPwd = str(c, 18);
        m.videoCount = (int) lng(c, 19);
        m.totalSize = lng(c, 20);
        m.remarks = buildRemarks(m.year, m.runtimeMin, m.videoCount);
        if (m.name.isEmpty()) m.name = m.nameEn;
        return m;
    }

    /** fid 是数据侧的真实版块号；fid<=0 视为不过滤（兜底）。 */
    private static String fidWhere(int fid) {
        return fid <= 0 ? "1=1" : "fid=" + fid;
    }

    // ==================================================================
    // 专题行（SAM-CINEMA 式「背景 + Hero + 多条横向海报行」的数据源）
    // ==================================================================

    /**
     * 一条专题行 = 一个维度 + 一串影片。
     *
     * <p>专题<b>不是</b>数据库里的表，而是用 SQL 在现有字段上<b>合成</b>出来的：
     * 库里没有 topic 表，但版块（fid）、题材（genres JSON）、评分（rating_douban）、
     * 上映日期（release_date）四样原料全都在 {@code v_movie_app} 里现成，
     * 所以零表结构变更、零数据侧改动就能拼出 16 行。</p>
     */
    public static class Topic {
        /** 行标题（界面上那行小字）。 */
        public final String title;
        /** 维度：{@code "fid"} 版块 ｜ {@code "genre"} 题材 ｜ {@code "top"} 豆瓣高分 ｜ {@code "new"} 最新上架。 */
        public final String kind;
        /** kind="fid" 时的版块号，其余为 0。 */
        public final int fid;
        /** kind="genre" 时的题材名，其余为空串。 */
        public final String genre;
        /** 首页展示的影片（最多 {@link #TOPIC_ROWSIZE} 条）。 */
        public final List<Movie> movies = new ArrayList<>();

        Topic(String title, String kind, int fid, String genre) {
            this.title = title;
            this.kind = kind;
            this.fid = fid;
            this.genre = genre == null ? "" : genre;
        }
    }

    /** 专题行每行取多少条：横向滚两三屏的量，「更多」再展开全部。 */
    public static final int TOPIC_ROWSIZE = 20;

    /**
     * 题材行只收「至少这么多部片子」的题材。
     *
     * <p>库里题材长尾很长（30 个标签），频次 &lt; 5 的（歌舞 4、西部 4、家庭 3、短片 2、
     * 甚至数据侧误填的「测试」「杜比」「DTS」各 1 条）单独成行会变成一条只有一两张海报的
     * 空横条，观感比没有还差。</p>
     */
    private static final int GENRE_MIN_COUNT = 5;

    /** 题材行最多出几行。10 行 × 20 张 + 4 版块行 + 2 个榜单行 = 16 行专题墙。 */
    private static final int GENRE_MAX_ROWS = 10;

    /**
     * 首页专题墙的全部行。
     *
     * <p>行序即界面顺序，刻意这样排：<b>版块行在前</b>（对应原来顶部的四个分类，
     * 用户最熟悉）、<b>题材行居中</b>（探索性浏览）、<b>榜单行收尾</b>
     * （豆瓣高分 / 最新上架，天然的「到底了」信号）。</p>
     *
     * <p>空行不出：某个版块 0 部、或某个题材全部片子都落在白名单之外时直接跳过，
     * 免得首页出现一条什么都没的横条。整段包在 try/catch 里 —— 任何一行查失败
     * 都只影响那一行，不会让首页整体空白。</p>
     */
    public static List<Topic> topics() {
        List<Topic> out = new ArrayList<>();
        SQLiteDatabase q = database();
        if (q == null) return out;
        long t0 = System.currentTimeMillis();
        try {
            // ① 版块行：白名单四个栏目各一行（表头行顺序 = CAT_FIDS 顺序）
            Map<Integer, Integer> counts = new HashMap<>();
            Cursor c = null;
            try {
                c = q.rawQuery("SELECT fid, COUNT(*) FROM v_movie_app WHERE " + whitelistWhere()
                        + " GROUP BY fid", null);
                while (c.moveToNext()) counts.put((int) lng(c, 0), (int) lng(c, 1));
            } catch (Throwable e) {
                Log.d(TAG, "topics 版块计数失败: " + e);
            } finally {
                if (c != null) c.close();
            }
            for (int i = 0; i < CAT_FIDS.length; i++) {
                Integer n = counts.get(CAT_FIDS[i]);
                if (n == null || n == 0) {
                    Log.d(TAG, "专题行跳过空版块 " + CAT_NAMES[i] + " fid=" + CAT_FIDS[i]);
                    continue;
                }
                Topic t = new Topic(CAT_NAMES[i], "fid", CAT_FIDS[i], "");
                queryTopic(t);
                if (!t.movies.isEmpty()) out.add(t);
            }

            // ② 题材行：白名单内出现频次最高的若干题材
            for (String g : topGenres(GENRE_MIN_COUNT, GENRE_MAX_ROWS)) {
                Topic t = new Topic(g, "genre", 0, g);
                queryTopic(t);
                if (!t.movies.isEmpty()) out.add(t);
            }

            // ③ 豆瓣高分（实测 208/321 部有分）
            Topic top = new Topic("豆瓣高分", "top", 0, "");
            queryTopic(top);
            if (!top.movies.isEmpty()) out.add(top);

            // ④ 最新上架（实测 316/321 部有上映日期）
            Topic fresh = new Topic("最新上架", "new", 0, "");
            queryTopic(fresh);
            if (!fresh.movies.isEmpty()) out.add(fresh);
        } catch (Throwable e) {
            Log.d(TAG, "topics 构建失败: " + e);
        }
        Log.d(TAG, "专题墙 " + out.size() + " 行，用时 " + (System.currentTimeMillis() - t0) + "ms");
        return out;
    }

    /**
     * 界面只显示 {@link #CAT_FIDS} 这四个版块，专题行也只在它们里面取材。
     *
     * <p>库里 327 部有 6 部不属于任何白名单版块，若专题行不设这个边界，
     * 会出现「首页看到一部片子，但四个栏目里都找不到它」的诡异体验。</p>
     */
    private static String whitelistWhere() {
        StringBuilder sb = new StringBuilder("fid IN (");
        for (int i = 0; i < CAT_FIDS.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(CAT_FIDS[i]);
        }
        return sb.append(')').toString();
    }

    /** 白名单内出现频次最高的题材名（取前 max 个，且频次不低于 minCount）。 */
    private static List<String> topGenres(int minCount, int max) {
        List<String> out = new ArrayList<>();
        Map<String, Integer> freq = new LinkedHashMap<>();
        SQLiteDatabase q = database();
        if (q == null) return out;
        Cursor c = null;
        try {
            c = q.rawQuery("SELECT genres FROM v_movie_app WHERE " + whitelistWhere()
                    + " AND genres IS NOT NULL AND genres != ''", null);
            while (c.moveToNext()) {
                for (String tag : parseTags(c.getString(0))) {
                    Integer n = freq.get(tag);
                    freq.put(tag, n == null ? 1 : n + 1);
                }
            }
        } catch (Throwable e) {
            Log.d(TAG, "topGenres 统计失败: " + e);
        } finally {
            if (c != null) c.close();
        }
        List<Map.Entry<String, Integer>> es = new ArrayList<>(freq.entrySet());
        Collections.sort(es, (a, b) -> b.getValue() - a.getValue());
        for (Map.Entry<String, Integer> e : es) {
            if (out.size() >= max) break;
            if (e.getValue() < minCount) continue;
            out.add(e.getKey());
        }
        Log.d(TAG, "题材行取到 " + out.size() + " 个（门槛 ≥" + minCount + " 部）: " + out);
        return out;
    }

    /** 把专题的维度翻译成 WHERE 条件（+ 绑定参数）。 */
    private static void topicFilter(Topic t, StringBuilder where, List<String> args) {
        if ("fid".equals(t.kind)) {
            // 版块行直接锁 fid，不再叠白名单（fid 本身就在白名单里）
            where.append("fid=").append(t.fid);
            return;
        }
        where.append(whitelistWhere());
        if ("genre".equals(t.kind)) {
            // genres 是 JSON 数组字符串，用 "题材名" 带引号匹配，避免「动作」命中「动作片」这类前缀包含
            where.append(" AND genres LIKE ?");
            args.add("%\"" + t.genre + "\"%");
        } else if ("top".equals(t.kind)) {
            where.append(" AND rating_douban > 0");
        } else {
            where.append(" AND release_date IS NOT NULL AND release_date != ''");
        }
    }

    /** 专题的 ORDER BY。 */
    private static String topicOrder(Topic t) {
        if ("top".equals(t.kind)) {
            return "rating_douban DESC, rating_imdb DESC, "
                    + "COALESCE(release_date,'') DESC, src_tid DESC";
        }
        if ("new".equals(t.kind)) {
            return "COALESCE(release_date,'') DESC, COALESCE(year,0) DESC, src_tid DESC";
        }
        // 版块行 / 题材行沿用版块列表的排序：置顶优先，然后上映日期倒序
        return orderBy();
    }

    /** 专题行条目 = 列表行 + synopsis（Hero 简介要用），所以列集合与读法都和列表不同。 */
    private static Movie readTopicRow(Cursor c) {
        Movie m = readListRow(c);
        m.intro = str(c, IDX_SYNOPSIS);
        return m;
    }

    /** 填一行的首页内容（取 {@link #TOPIC_ROWSIZE} 条，不分页）。 */
    private static void queryTopic(Topic t) {
        SQLiteDatabase q = database();
        if (q == null) return;
        StringBuilder where = new StringBuilder();
        List<String> args = new ArrayList<>();
        topicFilter(t, where, args);
        args.add(String.valueOf(TOPIC_ROWSIZE));
        Cursor c = null;
        try {
            c = q.rawQuery("SELECT " + DETAIL_COLS + " FROM v_movie_app WHERE " + where
                    + " ORDER BY " + topicOrder(t) + " LIMIT ?", toArray(args));
            while (c.moveToNext()) t.movies.add(readTopicRow(c));
        } catch (Throwable e) {
            Log.d(TAG, "专题行[" + t.title + "] 查询失败: " + e);
        } finally {
            if (c != null) c.close();
        }
    }

    /**
     * 行尾「更多」进去的那个列表页（分页取该行全部影片）。
     *
     * <p>复刻 SAM-CINEMA 的 {@code ItemVodMore}：行内只放 20 条，
     * 想看完整个题材（剧情有 170 部）得靠这里。</p>
     */
    public static Paged<List<Movie>> topicPage(Topic t, int page) {
        Paged<List<Movie>> out = new Paged<>();
        out.data = new ArrayList<>();
        if (t == null) return out;
        StringBuilder where = new StringBuilder();
        List<String> args = new ArrayList<>();
        topicFilter(t, where, args);
        queryPage(out, where.toString(), args, page, true, topicOrder(t));
        return out;
    }

    // ==================================================================
    // 详情
    // ==================================================================

    /** 详情（film 资料 + 可用网盘链接 + 库内剧集清单）。签名与旧 Site 兼容。 */
    public static Detail detail(int fid, String tid) {
        Detail d = new Detail();
        if (tid == null || tid.isEmpty()) return d;
        SQLiteDatabase q = database();
        if (q == null) return d;

        Cursor c = null;
        try {
            c = q.rawQuery("SELECT " + DETAIL_COLS + " FROM v_movie_app WHERE src_tid=?",
                    new String[]{tid});
            if (c.moveToFirst()) {
                Movie m = readDetailRow(c);
                m.fid = fid;
                fillExtUrls(m.uid, m);
                m.content = buildInfoBlock(m);
                d.movie = m;
            }
        } catch (Throwable e) {
            Log.d(TAG, "detail 查询失败: " + e);
        } finally {
            if (c != null) c.close();
        }
        if (d.movie.tid.isEmpty()) return d;

        // 链接：优先取该片「已有视频文件」的那条分享；没有就退回任意一条有 url 的
        d.episodes = episodesOf(d.movie.uid);
        String url = "", pwd = "";
        if (!d.episodes.isEmpty()) {
            for (Episode e : d.episodes) {
                if (!e.panUrl.isEmpty()) {
                    url = e.panUrl;
                    pwd = e.panPwd;
                    break;
                }
            }
        }
        if (url.isEmpty()) url = d.movie.panUrl;
        if (pwd.isEmpty()) pwd = d.movie.panPwd;
        if (!url.isEmpty()) {
            Box b = new Box();
            b.type = "baidu";
            b.url = url;
            b.pwd = pwd;
            d.boxes.add(b);
        }
        return d;
    }

    /**
     * synopsis 追加在 {@link #LIST_COLS} 之后，下标固定 = 21。
     *
     * <p>{@code LIST_COLS} 有 21 列（下标 0..20），追加的 synopsis 落在 21。</p>
     *
     * <p>⚠️ <b>顺手修掉的一处既有缺陷</b>：这里原先写的是 {@code str(c, 19)}，
     * 而 19 是 {@code video_count} —— 于是详情页的「简介」区一直显示的是一个数字
     * （该片的文件数），真正的剧情简介从没露过面。详情查询和专题行查询共用这个下标，
     * 所以抽成常量，避免以后 {@code LIST_COLS} 再变长时又一次错位。</p>
     */
    private static final int IDX_SYNOPSIS = 21;

    /** 详情比列表多取一列：简介正文。 */
    private static final String DETAIL_COLS = LIST_COLS + ",synopsis";

    private static Movie readDetailRow(Cursor c) {
        Movie m = readListRow(c);
        m.intro = str(c, IDX_SYNOPSIS);
        return m;
    }

    /**
     * 从 {@code movie_ext_id} 取该片的豆瓣 / IMDb 链接。
     *
     * <p>库里可能只存了 ID 没存 url，这时按标准地址拼出来。ID 是外部系统的稳定标识，
     * 比片名可靠得多（片名会被运营修正、会有重复译名）。</p>
     */
    private static void fillExtUrls(String uid, Movie m) {
        if (uid == null || uid.isEmpty()) return;
        SQLiteDatabase q = database();
        if (q == null) return;
        Cursor c = null;
        try {
            c = q.rawQuery("SELECT e.source, e.id, e.url FROM movie_ext_id e "
                    + "JOIN movie mv ON mv.id = e.movie_id WHERE mv.uid=?", new String[]{uid});
            while (c.moveToNext()) {
                String src = str(c, 0).toLowerCase();
                String id = str(c, 1);
                String url = str(c, 2);
                if ("douban".equals(src)) {
                    m.hasDouban = true;
                    if (id.matches("\\d+")) m.doubanUrl = "https://movie.douban.com/subject/" + id + "/";
                    else if (!url.isEmpty()) m.doubanUrl = url;
                } else if ("imdb".equals(src)) {
                    m.hasImdb = true;
                    if (id.startsWith("tt")) m.imdbUrl = "https://www.imdb.com/title/" + id + "/";
                    else if (!url.isEmpty()) m.imdbUrl = url;
                }
            }
        } catch (Throwable e) {
            Log.d(TAG, "fillExtUrls 失败: " + e);
        } finally {
            if (c != null) c.close();
        }
    }

    /**
     * 把数据库字段拼成详情页上半屏的「资料区」。
     *
     * <p>顺序刻意把 {@code ◎上映日期} 放在<b>最后</b>：界面上用
     * {@link #splitAtUpcoming(String)} 在它这里切成两段，于是资料区归上块、
     * 简介归下块（下块由布局锁 8 行），和原论坛版式完全一致。</p>
     */
    private static String buildInfoBlock(Movie m) {
        StringBuilder sb = new StringBuilder();
        line(sb, "◎译　名　", m.name);
        line(sb, "◎片　名　", m.nameEn);
        line(sb, "◎年　代　", m.year);
        line(sb, "◎产　地　", m.region);
        line(sb, "◎类　别　", joinTags(m.genres, " / "));
        if (m.ratingDouban > 0) line(sb, "◎豆瓣评分　", trimNum(m.ratingDouban));
        if (m.ratingImdb > 0) line(sb, "◎IMDb评分　", trimNum(m.ratingImdb));
        line(sb, "◎豆瓣链接　", m.doubanUrl);
        line(sb, "◎IMDb链接　", m.imdbUrl);
        line(sb, "◎上映日期　", m.releaseDate);
        return sb.toString().trim();
    }

    private static void line(StringBuilder sb, String label, String val) {
        if (val == null || val.isEmpty()) return;
        sb.append(label).append(val.trim()).append('\n');
    }

    private static String trimNum(double d) {
        if (d == Math.floor(d)) return String.valueOf((long) d);
        return String.format(java.util.Locale.ROOT, "%.1f", d);
    }

    /** 某部影片在库里的全部剧集/文件。 */
    public static List<Episode> episodesOf(String uid) {
        List<Episode> out = new ArrayList<>();
        if (uid == null || uid.isEmpty()) return out;
        SQLiteDatabase q = database();
        if (q == null) return out;
        Cursor c = null;
        try {
            c = q.rawQuery("SELECT video_id,pan_link_id,path,filename,final_filename,ext,"
                    + "size,season,episode,pan_url,pan_pwd,shareid,share_uk "
                    + "FROM v_episode WHERE movie_uid=? "
                    + "ORDER BY COALESCE(season,0),COALESCE(episode,0),path",
                    new String[]{uid});
            while (c.moveToNext()) {
                Episode e = new Episode();
                e.videoId = lng(c, 0);
                e.panLinkId = lng(c, 1);
                e.path = str(c, 2);
                e.filename = str(c, 3);
                e.finalFilename = str(c, 4);
                e.ext = str(c, 5);
                e.size = lng(c, 6);
                e.season = (int) lng(c, 7);
                e.episode = (int) lng(c, 8);
                e.panUrl = str(c, 9);
                e.panPwd = str(c, 10);
                e.shareid = str(c, 11);
                e.shareUk = str(c, 12);
                out.add(e);
            }
        } catch (Throwable ex) {
            Log.d(TAG, "episodesOf 查询失败: " + ex);
        } finally {
            if (c != null) c.close();
        }
        return out;
    }

    // ==================================================================
    // 文本工具（从旧 Site 原样移植 —— 这些是调试过很多轮的，不要重写）
    // ==================================================================

    private static final Pattern RE_LINE_UPCOMING = Pattern.compile("^\\s*◎\\s*上映日期");
    private static final Pattern RE_INFO_HEAD = Pattern.compile("(?m)^\\s*◎\\s*IMDb链接");
    private static final Pattern RE_INFO_INTRO = Pattern.compile("(?m)^\\s*◎\\s*简\\s*介");
    private static final Pattern RE_LINE_YEAR = Pattern.compile("^\\s*◎\\s*年\\s*代");
    private static final Pattern RE_LINE_JUNK = Pattern.compile("DJYDXS");

    /**
     * 把资料区按「◎上映日期」切成两段：[0] = 资料区（含该行），[1] = 它下面的正文。
     *
     * <p>界面要求「◎上映日期 往下整块最多 8 行」，那是<b>显示行</b>限制，
     * 由布局的 {@code maxLines} 锁；这里只负责切。</p>
     */
    public static String[] splitAtUpcoming(String s) {
        if (s == null || s.isEmpty()) return new String[]{""};
        String[] lines = s.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (!RE_LINE_UPCOMING.matcher(lines[i]).find()) continue;
            StringBuilder head = new StringBuilder(s.length());
            StringBuilder tail = new StringBuilder(s.length());
            for (int k = 0; k <= i; k++) head.append(lines[k]).append('\n');
            for (int k = i + 1; k < lines.length; k++) tail.append(lines[k]).append('\n');
            String h = head.toString().trim();
            String t = tail.toString().trim();
            return t.isEmpty() ? new String[]{h} : new String[]{h, t};
        }
        return new String[]{s};
    }

    /** 逐行规整：行内连续空白压成一个空格、去掉行首尾半角空白；空行最多留一个。 */
    public static String tidyLines(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        boolean gap = false;
        for (String line : s.split("\n")) {
            String t = line.replace('\u00A0', ' ').replace('\t', ' ');
            t = t.replaceAll(" {2,}", " ");
            t = t.replaceAll(" +\u3000", "\u3000");
            t = t.replaceAll("\u3000 +", "\u3000");
            t = t.trim();
            if (t.replace("\u3000", " ").trim().isEmpty()) {
                if (sb.length() > 0) gap = true;
                continue;
            }
            if (gap) {
                sb.append('\n');
                gap = false;
            }
            sb.append(t).append('\n');
        }
        return sb.toString().trim();
    }

    /** 去掉「◎IMDb链接」→「◎简　　介」之间的资料区（表头一并去掉）。 */
    public static String cutInfoMiddle(String s) {
        if (s == null || s.isEmpty()) return "";
        Matcher head = RE_INFO_HEAD.matcher(s);
        if (!head.find()) return s;
        int from = head.start();
        Matcher intro = RE_INFO_INTRO.matcher(s);
        if (!intro.find(from)) return s.substring(0, from).trim();
        String cut = s.substring(0, from).replaceAll("\\s+$", "");
        String keep = s.substring(intro.end()).replaceAll("^\\s+", "");
        if (cut.isEmpty()) return keep.trim();
        return (cut + "\n\n" + keep).replaceAll("\n{3,}", "\n\n").trim();
    }

    /** 整行丢掉：①「◎年　　代」；②含 DJYDXS 的转载/压制声明行。 */
    public static String dropNoiseLines(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (String ln : s.split("\n", -1)) {
            if (RE_LINE_YEAR.matcher(ln).find()) continue;
            if (RE_LINE_JUNK.matcher(ln).find()) continue;
            sb.append(ln).append('\n');
        }
        return sb.toString().trim();
    }

    public static String stripTags(String s) {
        if (s == null) return "";
        return unescape(s.replaceAll("<[^>]+>", "")).replaceAll("\\s+", " ").trim();
    }

    /** HTML 实体反解（数据侧偶尔会带 &amp; 之类）。 */
    public static String unescape(String s) {
        if (s == null || s.isEmpty()) return "";
        return s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
                .replace("&ldquo;", "“").replace("&rdquo;", "”");
    }

    /** 从任意文本提取日期并规范化为 年-月-日（去前导零）。 */
    public static String extractDate(String s) {
        if (s == null) return "";
        Matcher m = Pattern.compile("(\\d{4})-(\\d{1,2})-(\\d{1,2})").matcher(s);
        if (!m.find()) return "";
        try {
            return Integer.parseInt(m.group(1)) + "-" + Integer.parseInt(m.group(2))
                    + "-" + Integer.parseInt(m.group(3));
        } catch (Exception e) {
            return m.group(0);
        }
    }

    // ==================================================================
    // 小工具
    // ==================================================================

    /** 角标："2026 · 117分钟 · 12集"（有哪个显示哪个）。 */
    private static String buildRemarks(String year, int runtimeMin, int videoCount) {
        List<String> parts = new ArrayList<>();
        if (year != null && !year.isEmpty()) parts.add(year);
        if (runtimeMin > 0) parts.add(runtimeMin + "分钟");
        if (videoCount > 1) parts.add(videoCount + "集");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) sb.append(" · ");
            sb.append(parts.get(i));
        }
        return sb.toString();
    }

    /** genres 是 JSON 数组字符串（如 ["动作","科幻"]），解析成列表；解析失败按分隔符兜底。 */
    public static List<String> parseTags(String json) {
        List<String> out = new ArrayList<>();
        if (json == null || json.isEmpty()) return out;
        String s = json.trim();
        if (s.startsWith("[")) {
            try {
                JSONArray a = new JSONArray(s);
                for (int i = 0; i < a.length(); i++) {
                    String v = a.optString(i, "").trim();
                    if (!v.isEmpty() && !out.contains(v)) out.add(v);
                }
                return out;
            } catch (Throwable ignore) {
            }
        }
        for (String p : s.split("[/、,，|｜]")) {
            String v = p.trim().replaceAll("^[\"'\\[]+|[\"'\\]]+$", "");
            if (!v.isEmpty() && !out.contains(v)) out.add(v);
        }
        return out;
    }

    private static String joinTags(String json, String sep) {
        List<String> t = parseTags(json);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < t.size(); i++) {
            if (i > 0) sb.append(sep);
            sb.append(t.get(i));
        }
        return sb.toString();
    }

    /** "5.6 GB" 这种人类可读体积。 */
    public static String humanSize(long bytes) {
        if (bytes <= 0) return "";
        double g = bytes / 1073741824.0;
        if (g >= 1) return String.format(java.util.Locale.ROOT, "%.1f GB", g);
        double m = bytes / 1048576.0;
        return String.format(java.util.Locale.ROOT, "%.0f MB", m);
    }

    private static String[] toArray(List<String> l) {
        return l.toArray(new String[0]);
    }

    private static String str(Cursor c, int i) {
        String v = c.getString(i);
        return v == null ? "" : v.trim();
    }

    private static long lng(Cursor c, int i) {
        return c.isNull(i) ? 0L : c.getLong(i);
    }

    private static double dbl(Cursor c, int i) {
        return c.isNull(i) ? 0d : c.getDouble(i);
    }
}
