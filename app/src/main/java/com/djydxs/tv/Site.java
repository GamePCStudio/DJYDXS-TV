package com.djydxs.tv;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 4kzimu.top（Discuz X5.0）站点解析：版块列表 / 详情 / _NTCJ_BOXES 下载源。 */
public final class Site {
    public static final String BASE = "https://4kzimu.top";

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
        CATS.add(new Category(112, "4KSDR.Remux"));
        CATS.add(new Category(119, "1080P.Remux"));
        CATS.add(new Category(58,  "1080P高码版"));
        CATS.add(new Category(37,  "1080P最新剧集"));
        CATS.add(new Category(2,   "最新1080P电影"));
        CATS.add(new Category(115, "4K剧集.115网盘"));
        CATS.add(new Category(116, "国语特效MKV"));
        CATS.add(new Category(86,  "转载资源区"));
        CATS.add(new Category(78,  "资源补档"));
    }

    public static List<Category> categories() {
        return CATS;
    }

    private Site() {}

    private static final Pattern RE_THREAD = Pattern.compile(
            "<a href=\"thread-(\\d+)-1-\\d+\\.html\"[^>]*>(.*?)</a>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern RE_IMG = Pattern.compile("<img[^>]*?src=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern RE_HB_CARD = Pattern.compile(
            "<li class=\"haibao-movie-card\">(.*?)</li>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
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
            "<em id=\"authorposton\\d+\">[^<]*?(\\d{4}-\\d{1,2}-\\d{1,2})", Pattern.CASE_INSENSITIVE);
    private static final Pattern RE_RUNTIME = Pattern.compile("片\\s*长[^0-9]{0,8}(\\d{1,3})\\s*分钟");
    private static final Pattern RE_PAGES = Pattern.compile("共\\s*(\\d+)\\s*页", Pattern.CASE_INSENSITIVE);

    public static boolean isLoginWall(String html) {
        return html != null && html.length() > 100
                && html.contains("name=\"loginsubmit\"")
                && html.contains("登录");
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
            if (r.code != 200 || isLoginWall(r.body)) return out;
        }
        String html = r.body;
        out.pageCount = pageCount(html);

        // 海报墙卡片
        Matcher mc = RE_HB_CARD.matcher(html);
        boolean usedCards = false;
        while (mc.find()) {
            String body = mc.group(1);
            Matcher mt = RE_HB_TID.matcher(body);
            if (!mt.find()) continue;
            Movie m = new Movie();
            m.tid = mt.group(1);
            m.fid = fid;
            Matcher mp = RE_IMG.matcher(body);
            if (mp.find()) {
                String p = mp.group(1);
                if (p != null && !p.contains("nophoto") && !p.startsWith("template/") && !p.startsWith("static/")) {
                    m.pic = p;
                }
            }
            m.name = stripTags(g1(RE_HB_TITLE, body));
            String sub = stripTags(g1(RE_HB_SUB, body));
            if (!sub.isEmpty() && !m.name.contains(sub)) m.name = (m.name + " " + sub).trim();
            if (m.name.length() < 1) continue;
            m.remarks = "";
            out.data.add(m);
            usedCards = true;
        }
        // 表格布局兜底
        if (!usedCards) {
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
        return out;
    }

    public static Paged<List<Movie>> search(String kw, int page) {
        Paged<List<Movie>> out = new Paged<>();
        out.data = new ArrayList<>();
        try {
            String url = BASE + "/search.php?mod=forum&srchtxt="
                    + java.net.URLEncoder.encode(kw, "UTF-8") + "&searchsubmit=yes";
            if (page > 1) url += "&page=" + page;
            Http.Resp r = Http.get(url);
            if (r.code != 200 || isLoginWall(r.body)) return out;
            String html = r.body;
            // 搜索结果行：<li class="pbw" id="TID"> ... <h3>...<a ...>标题</a>
            Pattern li = Pattern.compile(
                    "<li[^>]*class=\"pbw\"[^>]*id=\"(\\d+)\"[^>]*>\\s*<h3[^>]*>\\s*<a[^>]*>(.*?)</a>",
                    Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
            Matcher mm = li.matcher(html);
            while (mm.find()) {
                Movie m = new Movie();
                m.tid = mm.group(1);
                m.name = stripTags(mm.group(2));
                if (m.name.length() < 2) continue;
                m.fid = 0;
                out.data.add(m);
            }
            out.pageCount = 5;
            return out;
        } catch (Exception e) {
            return out;
        }
    }

    /** 详情：标题/海报/简介/日期/片长 + 下载源（只保留百度）。 */
    public static Detail detail(int fid, String tid) {
        Detail d = new Detail();
        Http.Resp r = Http.get(BASE + "/thread-" + tid + "-1-1.html");
        if (r.code != 200 || isLoginWall(r.body)) return d;
        String html = r.body;

        d.movie.tid = tid;
        d.movie.fid = fid;
        d.movie.name = stripTags(g1(RE_SUBJECT, html));

        String post = g1(RE_FIRST_POST, html);
        if (!post.isEmpty()) {
            String raw = post.replaceAll("(?i)<img[^>]*>", " ")
                    .replaceAll("(?i)<br\\s*/?>", "\n")
                    .replaceAll("<[^>]+>", "");
            raw = unescape(raw).replaceAll("\\s+", " ").trim();
            if (raw.length() > 600) raw = raw.substring(0, 600);
            d.movie.content = raw;
        }
        // 海报
        if (!post.isEmpty()) {
            Matcher mi = RE_IMG.matcher(post);
            if (mi.find()) d.movie.pic = mi.group(1);
        }
        if (d.movie.pic.isEmpty()) {
            Pattern poster = Pattern.compile(
                    "file=\"(https?://[^\"\\s]+?(?:posters|DSXintu|dstmdb)[^\"\\s]*)\"", Pattern.CASE_INSENSITIVE);
            Matcher mp = poster.matcher(post.isEmpty() ? html : post);
            if (mp.find()) d.movie.pic = mp.group(1);
        }
        // 日期+片长
        String date = g1(RE_POSTED, html);
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
            Pattern pb = Pattern.compile(
                    "https?://pan\\.baidu\\.com/s/[A-Za-z0-9_\\-]+(?:\\?pwd=[A-Za-z0-9]+)?",
                    Pattern.CASE_INSENSITIVE);
            Matcher pm = pb.matcher(html);
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

    public static List<Movie> emptyList() {
        return Collections.emptyList();
    }
}
