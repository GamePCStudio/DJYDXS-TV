package com.supermov.tv;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 4kzimu.top（Discuz X5.0）站点解析：版块列表 / 详情 / _NTCJ_BOXES 下载源。 */
public final class Site {
    public static final String BASE = "https://4kzimu.top";

    /** 论坛登录 Cookie（与 PY 版 DJYDXS-BD.py 内置 COOKIES 同源，解锁会员版块）。
     *  过期后可在 设置→论坛登录 里重新登录覆盖。 */
    public static final String DEFAULT_FORUM_COOKIE =
            "cTo3_2132_saltkey=LT8prnHZ; cTo3_2132_lastvisit=1786540756; cTo3_2132_d_i18n=1; "
            + "cTo3_2132_auth=9a78u8lqMEXHcRmxDZNME8RdODrNj7EGVDPztAMw0acbfosCY%2BwDHkZzuResPPmCxMgYICq33ahy%2FPIAoIStwVjl; "
            + "cTo3_2132_lastcheckfeed=1410%7C1786544367; cTo3_2132_nofavfid=1; cTo3_2132_smile=5D1; "
            + "cTo3_2132_visitedfid=112D2; cTo3_2132_member_login_status=1; cTo3_2132_movmod_112=liebiao; "
            + "cTo3_2132_st_t=1410%7C1788883036%7C45167182f20df932a09c05ea4e2e8eaa; "
            + "cTo3_2132_forum_lastvisit=D_2_1787903684D_112_1788883036; cTo3_2132_sid=F0TW8w; "
            + "cTo3_2132_lip=104.28.211.46%2C1788883024; cTo3_2132_onlineusernum=34; "
            + "cTo3_2132_ulastactivity=5df3hv1yM3mnCkPeJS4JTPJMoqRackSdYz3hxkDo2gpoky2WRZXY; "
            + "cTo3_2132_lastact=1788940692%09plugin.php%09";

    public static class Category {
        public final int fid;
        public final String name;
        public final List<Filter> filters = new ArrayList<>();
        public Category(int fid, String name) { this.fid = fid; this.name = name; }
    }

    /** 版块主题分类过滤器（Discuz filter=typeid&typeid=X；typeid=0 表示全部）。 */
    public static class Filter {
        public final String name;
        public final int typeid;
        public Filter(String name, int typeid) { this.name = name; this.typeid = typeid; }
    }

    public static class Movie {
        public String tid = "";
        public int fid;
        public String name = "";
        public String pic = "";
        public String remarks = ""; // 年月日（角标只显示日期）
        public String content = "";
    }

    public static class Box {
        public String type;  // baidu/115/ed2k/...
        public String url;
        public String pwd;
    }

    public static class Detail {
        public Movie movie = new Movie();
        public List<Box> boxes = new ArrayList<>();
    }

    private static final List<Category> CATS = new ArrayList<>();
    static {
        // 各版块的过滤器（名称与 typeid 均来自论坛页面实测，各版块互不相同；
        // 「限制级」「情欲」按要求不显示，「全部」= typeid 0）
        Category c = new Category(112, "4K全景声");
        c.filters.add(new Filter("全部", 0));
        c.filters.add(new Filter("国语", 312));
        c.filters.add(new Filter("剧集", 321));
        c.filters.add(new Filter("怀旧港片", 303));
        c.filters.add(new Filter("老片新看", 304));
        c.filters.add(new Filter("佳片有约", 305));
        c.filters.add(new Filter("国内", 306));
        c.filters.add(new Filter("日韩", 307));
        c.filters.add(new Filter("欧美", 308));
        c.filters.add(new Filter("其他", 309));
        c.filters.add(new Filter("动画", 311));
        c.filters.add(new Filter("TOP250", 313));
        c.filters.add(new Filter("漫威", 317));
        c.filters.add(new Filter("DC", 318));
        c.filters.add(new Filter("纪录片", 343));
        c.filters.add(new Filter("星影", 314));
        c.filters.add(new Filter("WEB-DL", 322));
        CATS.add(c);

        c = new Category(37, "1080P最新剧集");
        // 论坛页实测（fid=37）：连载中146 限制级433 美剧148 国内183 韩剧338 日剧339
        //                动画剧集340 泰国436 纪录片335 老美剧348；已剔除「限制级」
        c.filters.add(new Filter("全部", 0));
        c.filters.add(new Filter("连载中", 146));
        c.filters.add(new Filter("美剧", 148));
        c.filters.add(new Filter("国内", 183));
        c.filters.add(new Filter("韩剧", 338));
        c.filters.add(new Filter("日剧", 339));
        c.filters.add(new Filter("动画剧集", 340));
        c.filters.add(new Filter("泰国", 436));
        c.filters.add(new Filter("纪录片", 335));
        c.filters.add(new Filter("老美剧", 348));
        CATS.add(c);

        c = new Category(58, "1080P蓝光");
        // 论坛页实测（fid=58）：活动推荐453 演唱会371 情欲342 限制级301 电影168 怀旧港片228
        //   老片新看269 星影225 佳片有约267 经典永存326 国内231 欧美229 日韩230 其他323
        //   动画319 国配270 WEB-DL248 TOP250 261 纪录片337；已剔除「情欲」「限制级」
        c.filters.add(new Filter("全部", 0));
        c.filters.add(new Filter("活动推荐", 453));
        c.filters.add(new Filter("演唱会", 371));
        c.filters.add(new Filter("电影", 168));
        c.filters.add(new Filter("怀旧港片", 228));
        c.filters.add(new Filter("老片新看", 269));
        c.filters.add(new Filter("星影", 225));
        c.filters.add(new Filter("佳片有约", 267));
        c.filters.add(new Filter("经典永存", 326));
        c.filters.add(new Filter("国内", 231));
        c.filters.add(new Filter("欧美", 229));
        c.filters.add(new Filter("日韩", 230));
        c.filters.add(new Filter("其他", 323));
        c.filters.add(new Filter("动画", 319));
        c.filters.add(new Filter("国配", 270));
        c.filters.add(new Filter("WEB-DL", 248));
        c.filters.add(new Filter("TOP250", 261));
        c.filters.add(new Filter("纪录片", 337));
        CATS.add(c);

        c = new Category(2, "1080P杜比5.1");
        // 论坛页实测（fid=2）：活动推荐449 情欲341 老片新看268 限制级265 演唱会370 佳片有约29
        //   星影224 经典永存325 怀旧港片226 日韩53 国内52 欧美54 其他199 纪录片336
        //   动画162 国语184 TOP250 88；已剔除「情欲」「限制级」
        c.filters.add(new Filter("全部", 0));
        c.filters.add(new Filter("活动推荐", 449));
        c.filters.add(new Filter("老片新看", 268));
        c.filters.add(new Filter("演唱会", 370));
        c.filters.add(new Filter("佳片有约", 29));
        c.filters.add(new Filter("星影", 224));
        c.filters.add(new Filter("经典永存", 325));
        c.filters.add(new Filter("怀旧港片", 226));
        c.filters.add(new Filter("日韩", 53));
        c.filters.add(new Filter("国内", 52));
        c.filters.add(new Filter("欧美", 54));
        c.filters.add(new Filter("其他", 199));
        c.filters.add(new Filter("纪录片", 336));
        c.filters.add(new Filter("动画", 162));
        c.filters.add(new Filter("国语", 184));
        c.filters.add(new Filter("TOP250", 88));
        CATS.add(c);
        // 已按要求移除：1080P.Remux / 4K剧集.115网盘 / 国语特效MKV / 转载资源区 / 资源补档
    }

    /** 指定版块的过滤器列表（每版块不同；含「全部」）。 */
    public static List<Filter> filtersFor(int fid) {
        for (Category c : CATS) if (c.fid == fid) return c.filters;
        return new ArrayList<>();
    }

    public static List<Category> categories() {
        return CATS;
    }

    private Site() {}

    private static final Pattern RE_THREAD = Pattern.compile(
            "<a href=\"thread-(\\d+)-1-\\d+\\.html\"[^>]*>(.*?)</a>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    /** 懒加载图片：优先 file=（Discuz 首楼真实图），其次 data-src/_src/origin，最后 src= */
    private static final Pattern RE_IMG_LAZY = Pattern.compile(
            "<img[^>]*?(?:file|data-src|data-original|_src|origin)[ \t]*=\"(https?:[^\"]+|//[^\"]+)\"",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern RE_IMG = Pattern.compile(
            "<img[^>]*?src=\"(https?:[^\"]+|//[^\"]+)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern RE_HB_CARD = Pattern.compile(
            "<li[^>]*class=\"[^\" ]*haibao-movie-card[^\" ]*\"[^>]*>(.*?)</li>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    // 2026-09 新版海报墙：<li class="byg_threadlist_pic_li"> <a href="thread-x" title="标题" style="background-image: url(海报URL)">...</a>
    private static final Pattern RE_BYG_CARD = Pattern.compile(
            "<li[^>]*class=\"[^\" ]*byg_threadlist_pic_li[^\" ]*\"[^>]*>(.*?)</li>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern RE_BYG_ANCHOR = Pattern.compile(
            "<a[^>]*href=\"thread-(\\d+)-1-\\d+\\.html\"[^>]*title=\"([^\"]*)\"[^>]*style=\"[^\"]*background-image:\\s*url\\(([^)]+)\\)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern RE_BYG_TITLE = Pattern.compile(
            "<h3[^>]*class=\"[^\" ]*byg_pic_tit[^\" ]*\"[^>]*>\\s*<a[^>]*>(.*?)</a>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern RE_HB_TID = Pattern.compile("href=\"thread-(\\d+)-1-\\d+\\.html\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern RE_HB_TITLE = Pattern.compile(
            "<p[^>]*class=\"[^\"]*haibao-card-title[^\"]*\"[^>]*>(.*?)</p>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern RE_HB_SUB = Pattern.compile(
            "<p[^>]*class=\"[^\"]*haibao-card-(?:sub|year)[^\"]*\"[^>]*>(.*?)</p>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern RE_NTCJ = Pattern.compile("window\\._NTCJ_BOXES=(\\[[^\\r\\n]*\\]);");
    private static final Pattern RE_SUBJECT = Pattern.compile(
            "<span id=\"thread_subject\"[^>]*>(.*?)</span>", Pattern.DOTALL);
    private static final Pattern RE_FIRST_POST = Pattern.compile(
            "<td class=\"t_f\" id=\"postmessage_\\d+\">(.*?)</td>\\s*</tr>", Pattern.DOTALL);
    private static final Pattern RE_POSTED = Pattern.compile(
            "<em id=\"authorposton\\d+\">(?:<span[^>]*>)?\\s*[^<]*?(\\d{4}-\\d{1,2}-\\d{1,2})",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern RE_RUNTIME = Pattern.compile("片\\s*长[^0-9]{0,8}(\\d{1,3})\\s*分钟");
    private static final Pattern RE_PAGES = Pattern.compile("共\\s*(\\d+)\\s*页", Pattern.CASE_INSENSITIVE);
    // 表格布局每行：<tbody id="normalthread_x"> … <em>(发表于)? YYYY-M-D … </tbody>
    // 允许日期被 <span> 包裹（与 PY _RE_ROW_DATE 一致），作为海报角标日期的可靠来源
    private static final Pattern RE_TBODY = Pattern.compile(
            "<tbody id=\"(?:normalthread|stickthread)_(\\d+)\">(.*?)</tbody>",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern RE_ROW_DATE = Pattern.compile(
            "<em[^>]*>(?:<span[^>]*>)?\\s*(?:发表于\\s*)?(\\d{4}-\\d{1,2}-\\d{1,2})",
            Pattern.CASE_INSENSITIVE);
    // 百度网盘分享链接（详情页正文兜底用 + 列表过滤用）
    private static final Pattern RE_BAIDU_URL = Pattern.compile(
            "https?://pan\\.baidu\\.com/s/[A-Za-z0-9_\\-]+(?:\\?pwd=[A-Za-z0-9]+)?",
            Pattern.CASE_INSENSITIVE);
    // Discuz formhash（登录后页面里带，搜索请求需要）
    private static final Pattern RE_FORMHASH_INPUT = Pattern.compile(
            "name=\"formhash\"\\s+value=\"([a-f0-9]+)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern RE_FORMHASH_URL = Pattern.compile("formhash=([a-f0-9]+)", Pattern.CASE_INSENSITIVE);

    /** 最近一次加载/搜索的失败状态："login"=论坛登录过期, "flood"=搜索太频繁, 空串=正常。 */
    public static volatile String lastLoadError = "";
    private static volatile String formhash = "";

    private static void extractFormhash(String html) {
        if (html == null || html.isEmpty()) return;
        Matcher m = RE_FORMHASH_INPUT.matcher(html);
        if (!m.find()) m = RE_FORMHASH_URL.matcher(html);
        if (m.find()) formhash = m.group(1);
    }

    public static boolean isLoginWall(String html) {
        if (html == null || html.length() < 100) return false;
        // Discuz 标准登录页
        if (html.contains("name=\"loginsubmit\"")) return true;
        // 站点 SMS 登录插件页（jzsjiale_sms）整站拦截时的落点，标题为“登录”
        if (html.contains("<title>登录</title>")) return true;
        return false;
    }

    /** 启动时注入内置论坛 Cookie（用户没有手动登录过时生效）。 */
    public static void ensureForumCookie() {
        String have = CookieStore.get(BASE + "/", "cTo3_2132_auth");
        if (have == null || have.isEmpty()) {
            for (String kv : DEFAULT_FORUM_COOKIE.split("; ")) {
                int i = kv.indexOf('=');
                if (i <= 0) continue;
                CookieStore.put(BASE + "/", kv.substring(0, i).trim(), kv.substring(i + 1).trim());
            }
        }
    }

    /** 兜底：用户手动粘贴论坛 Cookie（设置页用）。 */
    public static void setForumCookieRaw(String raw) {
        if (raw == null) return;
        for (String kv : raw.replace("\n", "").split(";")) {
            int i = kv.indexOf('=');
            if (i <= 0) continue;
            CookieStore.put(BASE + "/", kv.substring(0, i).trim(), kv.substring(i + 1).trim());
        }
    }

    /** 版块列表（海报墙 + 表格兜底），返回条目与总页数。 */
    public static Paged<List<Movie>> category(int fid, int page) {
        return category(fid, page, 0);
    }

    /** 版块列表（可带主题分类过滤 typeid，0=全部）。 */
    public static Paged<List<Movie>> category(int fid, int page, int typeid) {
        Paged<List<Movie>> out = new Paged<>();
        out.data = new ArrayList<>();
        String url = BASE + "/forum.php?mod=forumdisplay&fid=" + fid + "&page=" + page + "&movmod=haibao";
        if (typeid > 0) url += "&filter=typeid&typeid=" + typeid;
        Http.Resp r = Http.get(url);
        if (r.code != 200 || isLoginWall(r.body)) {
            // 表格布局版块兜底：无 typeid 用短地址；有 typeid 用完整参数（不带 movmod 走默认布局）
            String fb = (typeid > 0)
                    ? BASE + "/forum.php?mod=forumdisplay&fid=" + fid
                      + "&filter=typeid&typeid=" + typeid + "&page=" + page
                    : BASE + "/forum-" + fid + "-" + page + ".html";
            r = Http.get(fb);
            if (r.code != 200 || isLoginWall(r.body)) {
                lastLoadError = "login";
                return out;
            }
        }
        String html = r.body;
        extractFormhash(html);
        out.pageCount = pageCount(html);

        // 2026-09 新版海报墙（byg_*）：background-image 上直接带海报 URL
        Matcher mg = RE_BYG_CARD.matcher(html);
        boolean usedByg = false;
        while (mg.find()) {
            String body = mg.group(1);
            Matcher ma = RE_BYG_ANCHOR.matcher(body);
            if (!ma.find()) continue;
            Movie m = new Movie();
            m.tid = ma.group(1);
            m.fid = fid;
            m.name = cleanTitle(unescape(ma.group(2)));
            String pic = normPic(ma.group(3));
            if (!pic.isEmpty()) m.pic = pic;
            // title 缺失时退回 byg_pic_tit
            if (m.name.isEmpty()) {
                m.name = stripTags(g1(RE_BYG_TITLE, body));
            }
            if (m.name.length() < 1) continue;
            out.data.add(m);
            usedByg = true;
        }

        // 旧版海报墙卡片
        Matcher mc = RE_HB_CARD.matcher(html);
        boolean usedCards = false;
        while (mc.find()) {
            String body = mc.group(1);
            Matcher mt = RE_HB_TID.matcher(body);
            if (!mt.find()) continue;
            Movie m = new Movie();
            m.tid = mt.group(1);
            m.fid = fid;
            Matcher mp = RE_IMG_LAZY.matcher(body);
            if (!mp.find()) mp = RE_IMG.matcher(body);
            if (mp.find()) {
                String p = normPic(mp.group(1));
                if (!p.isEmpty()) m.pic = p;
            }
            m.name = cleanTitle(stripTags(g1(RE_HB_TITLE, body)));
            String sub = stripTags(g1(RE_HB_SUB, body));
            // 仅从副标题取完整日期（如 2026-9-15）作为角标；副标题若是孤立年份（2026）
            // 则不并入片名、也不放进角标，交给表格布局页 rowDates 按 tid 补真实日期。
            // 注意：绝不要把年份拼回片名（用户不需要片名带年份）。
            String date = extractDate(sub);
            if (!date.isEmpty()) {
                m.remarks = date;
            }
            if (m.name.length() < 1) continue;
            out.data.add(m);
            usedCards = true;
        }
        // 表格布局兜底
        if (!usedByg && !usedCards) {
            Matcher mt = RE_THREAD.matcher(html);
            while (mt.find()) {
                Movie m = new Movie();
                m.tid = mt.group(1);
                m.fid = fid;
                m.name = stripTags(mt.group(2));
                if (m.name.length() < 2) continue;
                out.data.add(m);
            }
        }
        // 与 PY 版 _parse_category 一致：海报墙卡片通常只标年份，完整日期来自表格布局
        // 每行的「最后回复时间」<em>YYYY-M-D</em>。单独抓一份表格布局页抽日期，按 tid 合并
        // 到海报条目（已含日期则保留，抽不到则交给详情页探测 RE_POSTED 兜底）。
        if (!out.data.isEmpty()) {
            java.util.Map<String, String> rowDates = parseRowDates(fid, page, typeid);
            if (!rowDates.isEmpty()) {
                for (Movie m : out.data) {
                    // 表格布局页按 tid 抽到的「最后回复时间」是可靠真实日期，优先采用（覆盖列表阶段的脏值）
                    if (rowDates.containsKey(m.tid)) {
                        m.remarks = rowDates.get(m.tid);
                    }
                }
            }
        }
        return out;
    }

    /** 抓表格布局页，按 tid 提取每行「最后回复时间」作为角标日期。
     *  带 typeid 时用完整参数 + movmod=liebiao 强制表格布局，保证过滤后的日期与条目对应。 */
    private static java.util.Map<String, String> parseRowDates(int fid, int page, int typeid) {
        java.util.Map<String, String> map = new java.util.HashMap<>();
        try {
            String u = (typeid > 0)
                    ? BASE + "/forum.php?mod=forumdisplay&fid=" + fid
                      + "&filter=typeid&typeid=" + typeid + "&page=" + page + "&movmod=liebiao"
                    : BASE + "/forum-" + fid + "-" + page + ".html";
            Http.Resp r = Http.get(u);
            if (r.code != 200 || isLoginWall(r.body)) return map;
            Matcher rm = RE_TBODY.matcher(r.body);
            while (rm.find()) {
                String tid = rm.group(1);
                String d = g1(RE_ROW_DATE, rm.group(2));
                if (!d.isEmpty()) map.put(tid, extractDate(d));
            }
        } catch (Exception ignored) {
        }
        return map;
    }

    public static Paged<List<Movie>> search(String kw, int page) {
        Paged<List<Movie>> out = new Paged<>();
        out.data = new ArrayList<>();
        out.pageCount = 1;
        try {
            // 第 1 页：发起新搜索（10 秒防刷）；翻页：searchid 复用结果
            // Discuz X5 对裸 GET 搜索会踢登录墙/拒绝，带 formhash 的 GET 优先，
            // 再退回 POST（标准表单提交方式）。
            String enc = java.net.URLEncoder.encode(kw, "UTF-8");
            Http.Resp r;
            String html;
            if (page == 1) {
                String fh = formhash;
                String fhq = fh.isEmpty() ? "" : "&formhash=" + fh;
                r = Http.get(BASE + "/search.php?mod=forum&srchtxt=" + enc
                        + "&searchsubmit=yes" + fhq);
                if (r.code != 200 || isLoginWall(r.body)) {
                    // POST 兜底（Discuz 搜索表单的标准提交方式）
                    r = Http.request("POST", BASE + "/search.php?mod=forum",
                            "searchsubmit=yes&srchtxt=" + enc + "&srhfid=0" + fhq, null, true);
                }
                if (r.code != 200 || isLoginWall(r.body)) {
                    lastLoadError = "login";
                    return out;
                }
                html = r.body;
                extractFormhash(html);
                // Discuz 防刷：两次搜索间隔太短
                if (html.contains("两次搜索间隔") || html.contains("搜索间隔") || html.contains("搜索太频繁")) {
                    lastLoadError = "flood";
                    return out;
                }
            } else {
                String sid = lastSearchSid;
                if (sid == null || sid.isEmpty()) return out;
                r = Http.get(BASE + "/search.php?mod=forum&searchid=" + sid
                        + "&orderby=lastpost&ascdesc=desc&searchsubmit=yes&kw="
                        + enc + "&page=" + page);
                if (r.code != 200 || isLoginWall(r.body)) {
                    lastLoadError = "login";
                    return out;
                }
                html = r.body;
            }
            Matcher sidm = Pattern.compile("searchid=(\\d+)").matcher(html);
            if (sidm.find()) lastSearchSid = sidm.group(1);

            // 真实结构（实测）：
            // <li class="pbw" id="35844">
            //   <h3 class="xs3"> <a href="forum.php?mod=viewthread&tid=...">标题</a> </h3>
            //   <p>...摘要...</p>
            //   <p> <span>2026-8-17 15:52</span> - <span>...作者...</span> - <span><a href="forum-112-1.html">版块</a></span> </p>
            Pattern li = Pattern.compile(
                    "<li[^>]*class=\"pbw\"[^>]*id=\"(\\d+)\"[^>]*>(.*?)</li>",
                    Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
            Matcher mm = li.matcher(html);
            Pattern at = Pattern.compile("<h3[^>]*>\\s*<a[^>]*>(.*?)</a>", Pattern.DOTALL);
            Pattern fdate = Pattern.compile("<span>\\s*(\\d{4}-\\d{1,2}-\\d{1,2})");
            Pattern ffid = Pattern.compile("forum-(\\d+)-\\d+\\.html");
            while (mm.find()) {
                String tid = mm.group(1);
                String body = mm.group(2);
                Matcher ta = at.matcher(body);
                if (!ta.find()) continue;
                Movie m = new Movie();
                m.tid = tid;
                m.name = stripTags(ta.group(1));
                if (m.name.length() < 2) continue;
                Matcher ff = ffid.matcher(body);
                m.fid = ff.find() ? Integer.parseInt(ff.group(1)) : 0;
                Matcher fd = fdate.matcher(body);
                if (fd.find()) m.remarks = fd.group(1);
                out.data.add(m);
            }
            // 总条数：找到 "相关内容 90 个" -> 36 条/页
            Matcher tm = Pattern.compile("相关内容\\s*(\\d+)\\s*个").matcher(html);
            if (tm.find()) {
                try {
                    int total = Integer.parseInt(tm.group(1));
                    out.pageCount = Math.max(1, (total + 35) / 36);
                } catch (Exception ignored) {}
            }
            Matcher pm = Pattern.compile("共\\s*(\\d+)\\s*页").matcher(html);
            if (pm.find()) {
                try { out.pageCount = Math.max(1, Integer.parseInt(pm.group(1))); } catch (Exception ignored) {}
            }
            if (!out.data.isEmpty()) lastLoadError = "";
            return out;
        } catch (Exception e) {
            return out;
        }
    }

    private static volatile String lastSearchSid = "";

    /** 资料区起点：「◎IMDb链接」——按需求，从这一行开始不显示。 */
    private static final Pattern RE_INFO_HEAD = Pattern.compile(
            "^[ \\t\\u3000]*◎[ \\t\\u3000]*(?:IMDb|豆瓣)[ \\t\\u3000]*链[ \\t\\u3000]*接.*$",
            Pattern.MULTILINE);
    /** 资料区终点：「◎简　　介」——从这一行继续显示。 */
    private static final Pattern RE_INFO_INTRO = Pattern.compile(
            "^[ \\t\\u3000]*◎[ \\t\\u3000]*(?:剧情)?[ \\t\\u3000]*简[ \\t\\u3000]*介.*$",
            Pattern.MULTILINE);
    /** 整行不要：「◎年　　代　2026」（年份已经并到片名后面显示）。 */
    private static final Pattern RE_LINE_YEAR = Pattern.compile(
            "^[ \\t\\u3000]*◎[ \\t\\u3000]*年[ \\t\\u3000]*代");
    /** 「◎上映日期」行 —— 用它把正文切成「资料区 / 它下面的整块正文」两段，见 splitAtUpcoming。 */
    private static final Pattern RE_LINE_UPCOMING = Pattern.compile(
            "^[ \\t\\u3000]*◎[ \\t\\u3000]*上[ \\t\\u3000]*映[ \\t\\u3000]*日[ \\t\\u3000]*期");
    /** 含 DJYDXS 的行（论坛自带的转载/压制声明），整行不显示。 */
    private static final Pattern RE_LINE_JUNK = Pattern.compile(
            "DJYDXS", Pattern.CASE_INSENSITIVE);

    /**
     * 首帖 HTML → 适合电视上阅读的纯文本。
     *
     * <p><b>换行</b>：论坛资料区是一个「◎字段」占一行的排版。旧实现用 {@code \s+}
     * 把所有空白统一压成一个空格，整块资料连同简介被挤成一整段没有换行的长文，
     * 电视上根本没法读。现在只压缩<b>行内</b>空白，换行严格按 {@code <br>} 还原，
     * 连续空行最多留一个（当段落分隔）。</p>
     *
     * <p><b>砍中段</b>：按需求，{@code ◎IMDb链接} 到 {@code ◎简　　介} 之间的内容
     * （豆瓣评分 / 豆瓣链接 / 片长 / 导演 / 主演长名单）不显示，只留基本资料 + 简介；
     * {@code ◎简　　介} 这个表头行本身、以及它下面那个空行也一起去掉。</p>
     *
     * <p><b>去噪</b>：{@code ◎年　　代} 整行不要（年份已并到片名后面）；含 DJYDXS 的转载声明整行不显示。</p>
     *
     * <p><b>切两段</b>：界面要求「{@code ◎上映日期} 往下整块最多 8 行」，所以调用方还要用
     * {@link #splitAtUpcoming(String)} 把资料区和它下面的正文分开渲染，8 行由布局的 maxLines 锁。</p>
     */
    static String cleanPost(String post) {
        if (post == null || post.isEmpty()) return "";
        String s = post
                .replaceAll("(?is)<script[^>]*>.*?</script>", " ")
                .replaceAll("(?is)<style[^>]*>.*?</style>", " ")
                // ① 先吃掉 HTML 源码自身的换行与缩进 —— 它不是「显示换行」，
                //    否则每个 <br> 后面都会多顶出一个空行，每个 ◎ 字段之间都是空的
                .replaceAll("[ \\t\\r\\n]*\\n[ \\t\\r\\n]*", " ")
                .replaceAll("(?i)<img[^>]*>", " ")
                // ② 真正的换行只认 <br>（以及块级标签的收尾）
                .replaceAll("(?i)<br\\s*/?\\s*>", "\n")
                .replaceAll("(?i)</(?:p|div|tr|li|h[1-6])\\s*>", "\n")
                .replaceAll("<[^>]+>", "");
        s = unescape(s);
        // Discuz 插件残留（如 [dztc_contact]…[/dztc_contact]）当正文显示出来很脏，去掉
        s = s.replaceAll("\\[/?(?:dztc_\\w+|attach(?:img)?|free|hide|quote|code|media|flash|audio|video|url|img)\\]", " ");
        s = tidyLines(s);
        s = cutInfoMiddle(s);
        s = dropNoiseLines(s);
        // 说明：「◎上映日期 往下最多 8 行」是**显示行**限制，交给调用方 splitAtUpcoming + 布局 maxLines
        if (s.length() > 2000) s = s.substring(0, 2000) + "…";
        return s;
    }

    /** 逐行规整：行内连续空白压成一个空格、去掉行首尾半角空白；空行最多留一个。 */
    static String tidyLines(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        boolean gap = false;
        for (String line : s.split("\n")) {
            String t = line.replace('\u00A0', ' ').replace('\t', ' ');
            t = t.replaceAll(" {2,}", " ");
            t = t.replaceAll(" +\u3000", "\u3000");
            t = t.replaceAll("\u3000 +", "\u3000");
            t = t.trim(); // 只去半角空白，全角缩进（论坛的 　　）保留
            // 整行只有全角空格 → 当空行（论坛用 　　<br> 当段间距），否则会连续空出好几行
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

    /**
     * 去掉「◎IMDb链接」→「◎简　　介」之间的资料区，并**连「◎简　　介」表头行和它下面那个空行一起**去掉
     * （表头留着没意义，正文自己会说话）。
     * 找不到「◎简　　介」时，从「◎IMDb链接」一路砍到末尾。
     */
    static String cutInfoMiddle(String s) {
        Matcher head = RE_INFO_HEAD.matcher(s);
        if (!head.find()) return s;
        int from = head.start();
        Matcher intro = RE_INFO_INTRO.matcher(s);
        if (!intro.find(from)) return s.substring(0, from).trim();   // 没有简介表头 → 砍到末尾
        String cut = s.substring(0, from).replaceAll("\\s+$", "");
        String keep = s.substring(intro.end()).replaceAll("^\\s+", "");  // 表头下那个空行一起吃掉
        if (cut.isEmpty()) return keep.trim();
        return (cut + "\n\n" + keep).replaceAll("\n{3,}", "\n\n").trim();
    }

    /**
     * 把清洗后的正文按「◎上映日期」切成两段：[0] = 资料区（含该行），[1] = 它下面的整块正文
     * （◎IMDb评分 → 简介 → 片尾说明）。
     *
     * <p>界面要求「{@code ◎上映日期} 往下整块最多保留 8 行」。这 8 行是<b>显示行</b>——
     * 电视按可用宽度折行之后的行数，而源码里一整段简介就是一行，在文本层根本裁不动，
     * 所以这里只负责切，行数上限交给布局（{@code activity_detail.xml} 的 maxLines）。</p>
     *
     * <p>找不到「◎上映日期」时不切（整篇都当资料区），返回长度 1 的数组。</p>
     */
    static String[] splitAtUpcoming(String s) {
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

    /** 整行丢掉：①「◎年　　代」；②含 DJYDXS 的转载/压制声明行。 */
    static String dropNoiseLines(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (String ln : s.split("\n", -1)) {
            if (RE_LINE_YEAR.matcher(ln).find()) continue;
            if (RE_LINE_JUNK.matcher(ln).find()) continue;
            sb.append(ln).append('\n');
        }
        return sb.toString().trim();
    }

    /** 详情：标题/海报/简介/日期/片长 + 下载源（只保留百度）。 */
    public static Detail detail(int fid, String tid) {
        Detail d = new Detail();
        Http.Resp r = Http.get(BASE + "/thread-" + tid + "-1-1.html");
        if (r.code != 200 || isLoginWall(r.body)) return d;
        String html = r.body;

        d.movie.tid = tid;
        d.movie.fid = fid;
        d.movie.name = cleanTitle(stripTags(g1(RE_SUBJECT, html)));
        extractFormhash(html);

        String post = g1(RE_FIRST_POST, html);
        if (!post.isEmpty()) d.movie.content = cleanPost(post);
        // 海报：详情页优先懒加载属性（Discuz file=），再退回 src=
        if (!post.isEmpty()) {
            Matcher mi = RE_IMG_LAZY.matcher(post);
            if (!mi.find()) mi = RE_IMG.matcher(post);
            if (mi.find()) d.movie.pic = normPic(mi.group(1));
        }
        if (d.movie.pic.isEmpty()) {
            Pattern poster = Pattern.compile(
                    "file=\"(https?://[^\"\\s]+?(?:posters|DSXintu|dstmdb)[^\"\\s]*)\"", Pattern.CASE_INSENSITIVE);
            Matcher mp = poster.matcher(post.isEmpty() ? html : post);
            if (mp.find()) d.movie.pic = normPic(mp.group(1));
        }
        // 角标：日期 · 片长（与 PY _marks 一致：年月日 + 时长分钟）
        d.movie.remarks = buildRemarks("", html);

        // 下载源：只保留百度
        Matcher mb = RE_NTCJ.matcher(html);
        if (mb.find()) {
            try {
                Object o = new JSONTokener(mb.group(1)).nextValue();
                if (o instanceof JSONArray) {
                    JSONArray arr = (JSONArray) o;
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject b = arr.optJSONObject(i);
                        if (b == null) continue;
                        String type = optLower(b, "type");
                        String url = optStr(b, "url");
                        if (url.isEmpty()) continue;
                        if (!"baidu".equals(type)) continue; // 只保留百度网盘
                        Box box = new Box();
                        box.type = type;
                        box.url = url;
                        box.pwd = optStr(b, "pwd");
                        d.boxes.add(box);
                    }
                }
            } catch (Exception ignored) {
            }
        }
        // 兜底：正文正则挖百度链接
        if (d.boxes.isEmpty()) {
            Matcher pm = RE_BAIDU_URL.matcher(html);
            while (pm.find() && d.boxes.size() < 6) {
                Box b = new Box();
                b.type = "baidu";
                b.url = pm.group();
                Matcher pw = Pattern.compile("(?:pwd|password)=([A-Za-z0-9]+)").matcher(b.url);
                b.pwd = pw.find() ? pw.group(1) : "";
                d.boxes.add(b);
            }
        }
        return d;
    }

    private static String normPic(String p) {
        if (p == null) return "";
        p = p.trim();
        if (p.isEmpty()) return "";
        if (p.startsWith("//")) p = "https:" + p;
        if (p.startsWith("/")) p = BASE + p;                 // 站内相对路径
        if (p.startsWith("data:")) return "";                 // base64 占位图
        String low = p.toLowerCase();
        if (low.contains("nophoto") || low.contains("logo")
                || low.contains("template/") || low.contains("static/")
                || low.contains("smiley") || low.contains("/common/")) return "";
        if (!low.startsWith("http")) return "";
        return p;
    }

    // ---------- utils ----------
    public static class Paged<T> {
        public T data;
        public int pageCount = 1;
    }

    private static int pageCount(String html) {
        Matcher m = RE_PAGES.matcher(html);
        if (m.find()) {
            try { return Integer.parseInt(m.group(1)); } catch (Exception ignored) {}
        }
        return 1;
    }

    private static String g1(Pattern p, String s) {
        if (s == null || s.isEmpty()) return "";
        Matcher m = p.matcher(s);
        return m.find() ? m.group(1) : "";
    }

    private static String optStr(JSONObject o, String k) {
        String v = o.optString(k, "");
        return v == null ? "" : v.trim();
    }

    private static String optLower(JSONObject o, String k) {
        return optStr(o, k).toLowerCase();
    }

    /** 片名规范化：《兄弟连：薪火永续》4K.SDR.2026.2160p.WEB-DL 6.14G -> 兄弟连：薪火永续 */
    public static String cleanTitle(String raw) {
        String s = raw == null ? "" : raw.trim();
        // 《...》优先
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("《([^》]+)》").matcher(s);
        if (m.find()) return m.group(1).trim();
        // 去掉技术参数尾巴：从第一个 4K/1080P/2160p/WEB-DL/BluRay/REMUX/HDR 起截断
        java.util.regex.Matcher t = java.util.regex.Pattern.compile(
                "[\\.\\s]*(4K|2160[pP]|1080[pP]|720[pP]|WEB-?DL|BluRay|Blu-ray|REMUX|HDR|DV杜比视界|杜比视界|SDR|HDR10|Atmos|DDP?5?\\.?1|MA|蓝光原盘|高码版).*$",
                java.util.regex.Pattern.CASE_INSENSITIVE).matcher(s);
        if (t.find()) s = s.substring(0, t.start()).trim();
        // 去尾部体积
        s = s.replaceAll("[\\s\\.]*\\d+(\\.\\d+)?[GMT]$", "").trim();
        // 去标题尾部孤立的发行年份（如 " 2026" / ".2026"）：年份须以分隔符（空格/点）与前文
        // 隔开、且为 19xx/20xx 才剥离，避免误伤「银翼杀手2049」这类片名本身含年份的影片
        s = s.replaceAll("[\\s.]+((?:19|20)\\d{2})$", "").trim();
        // 去多余分隔
        s = s.replaceAll("^[《\\[]+|[》\\]]+$", "").trim();
        return s;
    }

    /** 从任意文本提取日期并规范化为 年-月-日（去前导零）。 */
    public static String extractDate(String s) {
        if (s == null) return "";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "(\\d{4})-(\\d{1,2})-(\\d{1,2})").matcher(s);
        if (!m.find()) return "";
        try {
            return Integer.parseInt(m.group(1)) + "-" + Integer.parseInt(m.group(2))
                    + "-" + Integer.parseInt(m.group(3));
        } catch (Exception e) {
            return m.group(0);
        }
    }

    public static String stripTags(String s) {
        if (s == null) return "";
        return unescape(s.replaceAll("<[^>]+>", "")).replaceAll("\\s+", " ").trim();
    }

    /** 拼海报/详情角标：优先保留已有 remarks 里的日期（表格布局/列表阶段抽到的真实日期），
     *  再补详情页首楼里的片长，结果形如 "2026-9-9 · 117分钟"；只有其一则只显示其一。
     *  与 PY _marks 保持一致（日期 + 时长）。 */
    private static String buildRemarks(String existing, String html) {
        if (html == null || html.isEmpty()) return existing == null ? "" : existing.trim();
        String post = g1(RE_FIRST_POST, html);
        String scope = post.isEmpty() ? html : post;
        String date = extractDate(g1(RE_POSTED, html));
        String runtime = g1(RE_RUNTIME, scope);
        String base = (existing == null) ? "" : existing.trim();
        // 去掉已有 remarks 里可能残留的片长尾巴，避免重复
        base = base.replaceAll("·?\\s*\\d{1,3}分钟", "").trim();
        if (base.isEmpty() && !date.isEmpty()) base = date;
        StringBuilder sb = new StringBuilder();
        if (!base.isEmpty()) sb.append(base);
        if (!runtime.isEmpty()) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(runtime).append("分钟");
        }
        return sb.toString();
    }

    /** 数字 HTML 实体：十进制 &#NNN; 与十六进制 &#xHHHH;（论坛把标题里部分汉字转义成这种形式，如 行=&#x884C;）。 */
    private static final Pattern RE_DEC_ENT = Pattern.compile("&#(\\d+);");
    private static final Pattern RE_HEX_ENT = Pattern.compile("&#x([0-9a-fA-F]+);");

    /** 码点转字符串（兼容 >0xFFFF 辅助平面字符，用代理对表示）。 */
    private static String codePointToString(int code) {
        if (code < 0 || code > 0x10FFFF) return null;
        if (code <= 0xFFFF) return String.valueOf((char) code);
        return new String(Character.toChars(code));
    }

    /** 解码十进制/十六进制数字实体（修复片名里被转义的汉字显示为 &#x884C; 这类乱码）。 */
    private static String decodeNumericEntities(String s) {
        if (s == null || s.indexOf("&#") < 0) return s;
        Matcher md = RE_DEC_ENT.matcher(s);
        if (md.find()) {
            StringBuffer sb = new StringBuffer();
            md.reset();
            while (md.find()) {
                int code = -1;
                try { code = Integer.parseInt(md.group(1)); } catch (Exception ignored) {}
                String rep = (code > 0 && code <= 0x10FFFF) ? codePointToString(code) : md.group(0);
                md.appendReplacement(sb, Matcher.quoteReplacement(rep == null ? md.group(0) : rep));
            }
            md.appendTail(sb);
            s = sb.toString();
        }
        Matcher mh = RE_HEX_ENT.matcher(s);
        if (mh.find()) {
            StringBuffer sb = new StringBuffer();
            mh.reset();
            while (mh.find()) {
                int code = -1;
                try { code = Integer.parseInt(mh.group(1), 16); } catch (Exception ignored) {}
                String rep = (code > 0 && code <= 0x10FFFF) ? codePointToString(code) : mh.group(0);
                mh.appendReplacement(sb, Matcher.quoteReplacement(rep == null ? mh.group(0) : rep));
            }
            mh.appendTail(sb);
            s = sb.toString();
        }
        return s;
    }

    public static String unescape(String s) {
        if (s == null) return "";
        // 先处理常见命名实体（含 Discuz 常用标点），再处理数字实体
        s = s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&apos;", "'")
                .replace("&#39;", "'").replace("&#x27;", "'")
                .replace("&nbsp;", " ").replace("&middot;", "·")
                .replace("&hellip;", "…").replace("&copy;", "©").replace("&reg;", "®")
                .replace("&trade;", "™").replace("&bull;", "•").replace("&times;", "×")
                .replace("&mdash;", "—").replace("&ndash;", "–")
                .replace("&laquo;", "«").replace("&raquo;", "»")
                .replace("&ldquo;", "“").replace("&rdquo;", "”")
                .replace("&lsquo;", "‘").replace("&rsquo;", "’")
                .replace("&deg;", "°").replace("&plusmn;", "±");
        return decodeNumericEntities(s);
    }

    /** 列表项海报缺失时补抓详情页拿海报（限量，避免拖慢首屏）。 */
    public static void prefetchPics(List<Movie> movies, int max) {
        int done = 0;
        for (Movie m : movies) {
            if (done >= max) break;
            if (m.pic != null && !m.pic.isEmpty()) continue;
            try {
                Http.Resp r = Http.get(BASE + "/thread-" + m.tid + "-1-1.html");
                if (r.code != 200 || isLoginWall(r.body)) continue;
                String post = g1(RE_FIRST_POST, r.body);
                String scope = post.isEmpty() ? r.body : post;
                Matcher mi = RE_IMG_LAZY.matcher(scope);
                if (!mi.find()) mi = RE_IMG.matcher(scope);
                if (mi.find()) {
                    String p = normPic(mi.group(1));
                    if (!p.isEmpty()) {
                        m.pic = p;
                        done++;
                    }
                }
            } catch (Exception ignored) {
            }
        }
    }

    public static List<Movie> emptyList() {
        return Collections.emptyList();
    }

    /** 该影片是否有百度网盘分享链接（抓详情页轻量判断：_NTCJ_BOXES 的 baidu 项 / 正文 pan.baidu.com 链接）。 */
    public static boolean hasBaiduShare(String tid) {
        if (tid == null || tid.isEmpty()) return false;
        try {
            Http.Resp r = Http.get(BASE + "/thread-" + tid + "-1-1.html");
            if (r.code != 200 || isLoginWall(r.body)) return false;
            return htmlHasBaiduShare(r.body);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean htmlHasBaiduShare(String html) {
        Matcher mb = RE_NTCJ.matcher(html);
        if (mb.find()) {
            try {
                Object o = new JSONTokener(mb.group(1)).nextValue();
                if (o instanceof JSONArray) {
                    JSONArray arr = (JSONArray) o;
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject b = arr.optJSONObject(i);
                        if (b == null) continue;
                        if ("baidu".equals(optLower(b, "type")) && !optStr(b, "url").isEmpty()) return true;
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return RE_BAIDU_URL.matcher(html).find();
    }

    /**
     * 探测结果缓存：tid -> 有没有百度分享。
     *
     * <p>列表页每翻一页都要探测 36 个帖子（约 4.7MB），而用户来回切版块时同一批帖子会被
     * 反复探测（实测一次会话里同一页探测了 5 遍）。缓存后回访不再发请求，也不用等。</p>
     */
    private static final java.util.Map<String, Boolean> BAIDU_PROBE =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 探测一部影片：是否有百度分享，并顺带补全海报/日期/片长（详情页一次请求三用）。 */
    private static boolean probeAndFill(Movie m) {
        if (m.tid == null || m.tid.isEmpty()) return false;
        Boolean cached = BAIDU_PROBE.get(m.tid);
        if (cached != null) return cached;   // 探过就不再来一遍（海报/日期列表页已经有）
        try {
            Http.Resp r = Http.get(BASE + "/thread-" + m.tid + "-1-1.html");
            if (r.code != 200 || isLoginWall(r.body)) return false;
            String html = r.body;
            extractFormhash(html);
            // 补海报
            if (m.pic == null || m.pic.isEmpty()) {
                String post = g1(RE_FIRST_POST, html);
                String scope = post.isEmpty() ? html : post;
                Matcher mi = RE_IMG_LAZY.matcher(scope);
                if (!mi.find()) mi = RE_IMG.matcher(scope);
                if (mi.find()) {
                    String p = normPic(mi.group(1));
                    if (!p.isEmpty()) m.pic = p;
                }
            }
            // 角标：日期 · 片长。已有日期（表格布局/列表阶段）优先保留，这里补上详情页片长
            m.remarks = buildRemarks(m.remarks, html);
            boolean has = htmlHasBaiduShare(html);
            if (BAIDU_PROBE.size() > 4000) BAIDU_PROBE.clear();
            BAIDU_PROBE.put(m.tid, has);
            return has;
        } catch (Exception e) {
            return false;   // 网络抖动：不写缓存，下次还探
        }
    }

    /** 过滤列表：移除没有百度网盘分享链接的影片，同时补全海报/日期/片长（并发，避免逐个串行太慢）。 */
    public static void filterBaiduOnly(List<Movie> movies, int threads) {
        if (movies == null || movies.isEmpty()) return;
        int n = Math.max(1, Math.min(threads, movies.size()));
        ExecutorService ex = Executors.newFixedThreadPool(n);
        try {
            List<Future<Boolean>> fs = new ArrayList<>();
            for (final Movie m : movies) {
                fs.add(ex.submit(() -> probeAndFill(m)));
            }
            List<Movie> keep = new ArrayList<>();
            for (int i = 0; i < movies.size(); i++) {
                boolean ok;
                try {
                    ok = fs.get(i).get();
                } catch (Exception e) {
                    ok = false;
                }
                if (ok) keep.add(movies.get(i));
            }
            movies.clear();
            movies.addAll(keep);
        } finally {
            ex.shutdown();
        }
    }
}
