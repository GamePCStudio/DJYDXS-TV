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
        public Category(int fid, String name) { this.fid = fid; this.name = name; }
    }

    public static class Movie {
        public String tid = "";
        public int fid;
        public String name = "";
        public String pic = "";
        public String remarks = ""; // 日期 · 片长
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
        CATS.add(new Category(112, "4K全景声"));
        CATS.add(new Category(37,  "1080P最新剧集"));
        CATS.add(new Category(58,  "1080P蓝光"));
        CATS.add(new Category(2,   "1080P杜比5.1"));
        // 已按要求移除：1080P.Remux / 4K剧集.115网盘 / 国语特效MKV / 转载资源区 / 资源补档
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
        Paged<List<Movie>> out = new Paged<>();
        out.data = new ArrayList<>();
        String url = BASE + "/forum.php?mod=forumdisplay&fid=" + fid + "&page=" + page + "&movmod=haibao";
        Http.Resp r = Http.get(url);
        if (r.code != 200 || isLoginWall(r.body)) {
            // 表格布局版块：先试默认页
            r = Http.get(BASE + "/forum-" + fid + "-" + page + ".html");
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
            // 从卡片（副标题/年份或任意位置）提取日期，单独放到底部角标显示，
            // 不再只依赖详情页探测（RE_POSTED 不匹配时也能显示）
            String date = extractDate(sub);
            if (date.isEmpty()) date = extractDate(body);
            if (!date.isEmpty()) {
                m.remarks = date;
                // 副标题本身就是日期时不再并入片名，避免重复
                if (extractDate(sub).isEmpty() && !sub.isEmpty() && !m.name.contains(sub)) {
                    m.name = (m.name + " " + sub).trim();
                }
            } else if (!sub.isEmpty() && !m.name.contains(sub)) {
                m.name = (m.name + " " + sub).trim();
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
            java.util.Map<String, String> rowDates = parseRowDates(fid, page);
            if (!rowDates.isEmpty()) {
                for (Movie m : out.data) {
                    if ((m.remarks == null || m.remarks.isEmpty()) && rowDates.containsKey(m.tid)) {
                        m.remarks = rowDates.get(m.tid);
                    }
                }
            }
        }
        return out;
    }

    /** 抓表格布局页（forum-{fid}-{page}.html），按 tid 提取每行「最后回复时间」作为角标日期。 */
    private static java.util.Map<String, String> parseRowDates(int fid, int page) {
        java.util.Map<String, String> map = new java.util.HashMap<>();
        try {
            Http.Resp r = Http.get(BASE + "/forum-" + fid + "-" + page + ".html");
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
        if (!post.isEmpty()) {
            String raw = post.replaceAll("(?i)<img[^>]*>", " ")
                    .replaceAll("(?i)<br\\s*/?>", "\n")
                    .replaceAll("<[^>]+>", "");
            raw = unescape(raw).replaceAll("\\s+", " ").trim();
            if (raw.length() > 600) raw = raw.substring(0, 600);
            d.movie.content = raw;
        }
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
        // 日期+片长
        String date = extractDate(g1(RE_POSTED, html));
        String runtime = g1(RE_RUNTIME, post.isEmpty() ? html : post);
        StringBuilder rem = new StringBuilder();
        if (!date.isEmpty()) rem.append(date);
        if (!runtime.isEmpty()) {
            if (rem.length() > 0) rem.append(" · ");
            rem.append(runtime).append("分钟");
        }
        d.movie.remarks = rem.toString();

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

    public static String unescape(String s) {
        if (s == null) return "";
        return s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")
                .replace("&#x27;", "'");
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

    /** 探测一部影片：是否有百度分享，并顺带补全海报/日期/片长（详情页一次请求三用）。 */
    private static boolean probeAndFill(Movie m) {
        if (m.tid == null || m.tid.isEmpty()) return false;
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
            // 补日期 · 片长：表格布局已写入日期（m.remarks 非空），这里用详情页兜底日期、
            // 并补全片长；已有内容不重复追加，避免「日期 · 日期」。
            String post = g1(RE_FIRST_POST, html);
            String date = extractDate(g1(RE_POSTED, html));
            String runtime = g1(RE_RUNTIME, post.isEmpty() ? html : post);
            String cur = (m.remarks == null) ? "" : m.remarks;
            StringBuilder rem = new StringBuilder(cur);
            boolean changed = false;
            if (!date.isEmpty() && !cur.contains(date)) {
                if (rem.length() > 0) rem.append(" · ");
                rem.append(date);
                changed = true;
            }
            if (!runtime.isEmpty() && !cur.contains(runtime + "分钟")) {
                if (rem.length() > 0) rem.append(" · ");
                rem.append(runtime).append("分钟");
                changed = true;
            }
            if (changed) m.remarks = rem.toString();
            return htmlHasBaiduShare(html);
        } catch (Exception e) {
            return false;
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
