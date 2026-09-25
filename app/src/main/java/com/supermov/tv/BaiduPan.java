package com.supermov.tv;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 百度网盘：扫码登录 + 分享转存（对齐 BaiduPCS-Py 的网页接口流程）。 */
public final class BaiduPan {

    // 转存链路必须用桌面 UA：移动 UA 会被百度导向 wap 分享页（无 yunData/file_list 数据）
    public static final String DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
    private BaiduPan() {}

    public static class QrSession {
        public String sign = "";
        public String qrimgUrl = "";
        public String error = ""; // 失败原因（net=HTTP码 / errno / 解析失败）
    }

    public static class QrPoll {
        public String status = "wait"; // wait / scanned / ok / fail
        public String message = "";
    }

    // ---------- 扫码登录 ----------

    /** 1) 取二维码：passport.baidu.com/v2/api/getqrcode，带明确错误信息 */
    public static QrSession qrStart() {
        QrSession s = new QrSession();
        Http.Resp r = Http.get("https://passport.baidu.com/v2/api/getqrcode?lp=pc&qrloginfrom=skip");
        if (r.code == 0) {
            s.error = "网络不可达(检查电视网络/DNS)";
            return s;
        }
        if (r.code != 200 || r.body.isEmpty()) {
            s.error = "接口HTTP " + r.code;
            return s;
        }
        try {
            JSONObject o = new JSONObject(r.body);
            String errno = String.valueOf(o.opt("errno"));
            if (!"0".equals(errno)) {
                s.error = "接口errno=" + errno;
                return s;
            }
            s.sign = o.optString("sign", "");
            s.qrimgUrl = o.optString("imgurl", "");
            if (s.sign.isEmpty() || s.qrimgUrl.isEmpty()) {
                s.error = "响应缺 sign/imgurl 字段";
            }
        } catch (Exception e) {
            s.error = "响应非JSON(可能被风控拦截)";
        }
        return s;
 }

    /** 2) 轮询：channel/unicast；已确认 -> 3) qrlogin 拿 BDUSS。 */
    public static QrPoll qrPoll(String sign) {
        QrPoll out = new QrPoll();
        Http.Resp r = Http.get("https://passport.baidu.com/channel/unicast?channel_id=" + sign + "&callback=");
        if (r.code != 200 || r.body.isEmpty()) return out; // wait
        String jsonStr = extractParenJson(r.body);
        if (jsonStr == null) return out;
        try {
            JSONObject o = new JSONObject(jsonStr);
            if (!"0".equals(String.valueOf(o.opt("errno")))) return out;
            String cvRaw = o.optString("channel_v", "");
            if (cvRaw.isEmpty()) return out;
            JSONObject cv = new JSONObject(cvRaw);
            String st = String.valueOf(cv.opt("status"));
            if ("1".equals(st)) {
                out.status = "scanned";
                out.message = "已扫码，请在手机上点确认";
                return out;
            }
            if ("0".equals(st)) {
                String v = cv.optString("v", "");
                if (v.isEmpty()) return out;
                // 实测有效端点（2026-09）：v2/api/qrlogin 已 404，
                // 改用 v3/login/main/qrbdusslogin?bduss=<v>，BDUSS/STOKEN 走 Set-Cookie 下发
                Http.Resp r2 = Http.request("GET",
                        "https://passport.baidu.com/v3/login/main/qrbdusslogin?bduss="
                                + java.net.URLEncoder.encode(v, "UTF-8") + "&qrloginfrom=pc",
                        null, null, true);
                // Http 层已把响应 Set-Cookie 存入 CookieStore（passport + 泛域）
                String bduss = CookieStore.get("https://pan.baidu.com", "BDUSS");
                if (bduss == null || bduss.isEmpty()) {
                    bduss = CookieStore.get("https://passport.baidu.com", "BDUSS");
                }
                if (bduss != null && bduss.length() > 40) {
                    String stoken = CookieStore.get("https://pan.baidu.com", "STOKEN");
                    if (stoken == null || stoken.isEmpty()) {
                        stoken = CookieStore.get("https://passport.baidu.com", "STOKEN");
                    }
                    if (stoken != null && !stoken.isEmpty()) {
                        CookieStore.put("https://pan.baidu.com", "STOKEN", stoken);
                    }
                    // 从响应 JSON 里顺带拿用户名（可失败）
                    String uname = "";
                    // 用户名由 userName() 接口单独获取（QrActivity 里会调）
                    out.status = "ok";
                    out.message = "授权成功" + (uname.isEmpty() ? "" : ":" + uname);
                    return out;
                }
                // 没拿到 BDUSS：区分错误
                if (r2.body.contains("310005") || r2.body.contains("过期")) {
                    out.message = "确认已超时，请重新扫码";
                } else if (r2.code == 0) {
                    out.message = "网络异常";
                }
                return out;
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static String extractParenJson(String s) {
        int l = s.indexOf('(');
        int rr = s.lastIndexOf(')');
        if (l >= 0 && rr > l) return s.substring(l + 1, rr);
        return s;
    }

    /** 4) 用登录态取账号昵称（可失败）。 */
    public static String userName() {
        try {
            Http.Resp r = Http.get("https://pan.baidu.com/api/user/getinfo?clienttype=0&app_id=250528&web=1");
            if (r.code == 200 && !r.body.isEmpty()) {
                JSONObject o = new JSONObject(r.body);
                if ("0".equals(String.valueOf(o.opt("errno")))) {
                    JSONObject ui = o.optJSONObject("user_info");
                    if (ui != null) return ui.optString("username", "");
                }
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    // ---------- 转存 ----------

    public static class TransferResult {
        public boolean ok;
        public String message;
        /** 分享页解析出的 shareid / uk：转存成功后用来递归核对「分享清单是否被完整转存」。 */
        public String shareid = "";
        public String uk = "";
        /**
         * 转存**实际落地**的网盘绝对路径（如 /山姆影库/特别行动：母狮.S03.1080P）。
         *
         * <p>分享里的文件夹名和片名经常不一样（被改名、被顺延成「(2)」），所以落地后不能用
         * 片名去猜 —— 直接拿这里记下的路径精确取文件，永远播不错。</p>
         */
        public final List<String> toPaths = new ArrayList<>();
        /**
         * 失败原因是不是「目标网盘空间不够」。
         *
         * <p>为 true 时 {@link #message} 已经是给用户看的完整话术（{@link #MSG_NO_SPACE}），
         * 调用方直接展示即可，不要再自己拼「转存失败：」前缀 —— 否则会变成
         * 「转存失败：百度网盘空间已满…」这种半截话。</p>
         */
        public boolean spaceFull;
        /**
         * 因为目标目录已有同名影片而实际落地的顺延目录（如 {@code /山姆影库/片名 (2)}）。
         * 空串 = 正常落到目标目录，没有顺延。
         */
        public String superseded = "";
    }

    /**
     * 从转存响应里取出「实际落地路径」。
     *
     * <p>百度回的是
     * {@code {"errno":0,"extra":{"list":[{"from":"/分享里的名字","to":"/山姆影库/实际名字"}]}}，
     * 取 to；没有 extra 时退回「目标目录下同名项」；再不行拿分享里的名字拼到目标目录下
     * （{@link #locate} 会核对是否真的存在）。</p>
     */
    private static void collectTargets(TransferResult out, String respBody, String targetDir) {
        try {
            JSONObject o = new JSONObject(respBody);
            JSONObject extra = o.optJSONObject("extra");
            JSONArray list = extra == null ? null : extra.optJSONArray("list");
            List<String> froms = new ArrayList<>();
            if (list != null) {
                for (int i = 0; i < list.length(); i++) {
                    JSONObject it = list.optJSONObject(i);
                    if (it == null) continue;
                    String to = it.optString("to", "");
                    if (!to.isEmpty() && !out.toPaths.contains(to)) out.toPaths.add(to);
                    String f = it.optString("from", "");
                    if (!f.isEmpty()) froms.add(f);
                }
            }
            if (out.toPaths.isEmpty()) {
                JSONArray info = o.optJSONArray("info");
                if (info != null) {
                    for (int i = 0; i < info.length(); i++) {
                        JSONObject it = info.optJSONObject(i);
                        if (it == null) continue;
                        String p = it.optString("path", "");
                        if (!p.isEmpty() && p.startsWith(trimSlash(targetDir) + "/")
                                && !out.toPaths.contains(p)) out.toPaths.add(p);
                    }
                }
            }
            if (out.toPaths.isEmpty()) {
                for (String f : froms) {
                    String leaf = f;
                    int sl = leaf.lastIndexOf('/');
                    if (sl >= 0) leaf = leaf.substring(sl + 1);
                    if (leaf.isEmpty()) continue;
                    String p = trimSlash(targetDir) + "/" + leaf;
                    if (!out.toPaths.contains(p)) out.toPaths.add(p);
                }
            }
        } catch (Throwable ignored) {
        }
        android.util.Log.d("SupeMov", "transfer: toPaths=" + out.toPaths);
    }

    /**
     * 从 share/list（或页面 file_list）的 JSON 数组里取全部 fs_id。
     *
     * <p><b>目录项原样保留</b>：fsidlist 里放一个「文件夹」的 fs_id，百度会把该文件夹
     * 连同里面的内容<b>递归复制</b>过去 —— 这正是「按原样转存」的关键。
     * 若只下发文件夹里的散文件，网盘里就变成一片平铺，分享中原本的层级全丢了。</p>
     */
    private static long[] idsOf(JSONArray arr) {
        List<Long> ids = new ArrayList<>();
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject f = arr.optJSONObject(i);
                if (f == null) continue;
                long id = f.optLong("fs_id", 0);
                if (id > 0 && !ids.contains(id)) ids.add(id);
            }
        }
        long[] out = new long[ids.size()];
        for (int i = 0; i < out.length; i++) out[i] = ids.get(i);
        return out;
    }

    /** 一键转存（不按片名建子文件夹，等价于 {@code movieName=null}）。 */
    public static TransferResult transfer(String shareUrl, String pwd, String targetDir) {
        return transfer(shareUrl, pwd, targetDir, null);
    }

    // ---------- 空间不足：判定靠容量，不靠猜 errno ----------

    /** 网盘空间不足的统一话术。任何入口报这个错都用它，保证口径一致。 */
    public static final String MSG_NO_SPACE =
            "百度网盘空间已满，无法转存。\n"
                    + "请先删除网盘里的部分文件腾出空间，之后才能正常在线播放和下载。";

    /** 剩余空间超过这个值就直接跳过体积统计 —— 库里最大的片子也就几十 GB。 */
    private static final long SPACE_PLENTY_BYTES = 150L * 1024 * 1024 * 1024;
    /** 剩余空间低于这个值就认为「放不下任何一部」：库里全是 4K 片源，最小也几百 MB。 */
    private static final long SPACE_DANGER_BYTES = 20L * 1024 * 1024;
    /** 统计分享体积时最多列几个目录。用尽则退化为「下界」，下界依然够用来证明放不下。 */
    private static final int SIZE_WALK_DIRS = 6;

    /**
     * 已知的「空间不足」errno。
     *
     * <p><b>只当旁证，不单独下结论。</b>百度转存失败回的码并不专一：errno=4 在公开资料里
     * 被解释成「存储好像出问题了」，同一份资料又把 -9/-10 说成容量不足，互相矛盾。
     * 拿这种码当判据必然误报，所以主判据是 {@link #quota()}（真实容量）。</p>
     */
    private static final java.util.Set<String> SPACE_ERRNOS =
            new java.util.HashSet<>(java.util.Arrays.asList("31116", "36009", "-999"));

    private static final String[] SPACE_WORDS = {
            "空间不足", "容量不足", "剩余空间", "空间已满", "存储空间不足", "购买空间", "扩容"
    };

    /** 响应体里有没有「空间不足」的措辞。 */
    private static boolean bodySaysNoSpace(String body) {
        if (body == null || body.isEmpty()) return false;
        for (String w : SPACE_WORDS) {
            if (body.contains(w)) return true;
        }
        return false;
    }

    /** 网盘容量。字段为 -1 表示没取到。 */
    public static class Quota {
        public boolean ok;
        public long total = -1, used = -1, free = -1;
    }

    /**
     * 查当前账号的容量（{@code /api/quota}）。
     *
     * <p>这是「空间满了」的主判据：直接拿真实剩余空间跟要转存的体积比，
     * 比事后猜 errno 可靠得多。</p>
     */
    public static Quota quota() {
        Quota q = new Quota();
        if (!CookieStore.hasBaiduLogin()) return q;
        Http.desktopUa.set(true);
        try {
            Http.Resp r = Http.get("https://pan.baidu.com/api/quota?checkfree=1&checkexpire=1"
                    + "&clienttype=0&app_id=250528&web=1");
            JSONObject o = new JSONObject(r.body);
            if (!"0".equals(String.valueOf(o.opt("errno")))) {
                android.util.Log.d("SupeMov", "quota errno=" + o.opt("errno"));
                return q;
            }
            q.total = o.optLong("total", -1);
            q.used = o.optLong("used", -1);
            q.free = o.optLong("free", -1);
            q.ok = q.free >= 0;
            android.util.Log.d("SupeMov", "quota total=" + q.total
                    + " used=" + q.used + " free=" + q.free);
        } catch (Throwable e) {
            android.util.Log.d("SupeMov", "quota 失败 " + e);
        } finally {
            Http.desktopUa.set(false);
        }
        return q;
    }

    /**
     * 递归合计分享体积，返回**下界**。
     *
     * <p>为什么下界就够用：我们要判断的是「剩余空间 &lt; 需要的大小」。
     * 下界偏小只会让我们<b>少判</b>几次空间不足（退回去靠 errno 旁证），
     * 不会误判成「空间不足」，方向是安全的。所以预算用尽可以直接停，
     * 不必为了一个精确总数把整棵目录树爬完（大分享那是几百次请求）。</p>
     */
    private static long shareSizeLowerBound(String shareid, String uk, String referer) {
        long[] acc = new long[]{0L};
        int[] budget = new int[]{SIZE_WALK_DIRS};
        walkSize(shareid, uk, "/", referer, acc, budget, 0);
        return acc[0];
    }

    private static void walkSize(String shareid, String uk, String dir, String referer,
                                 long[] acc, int[] budget, int depth) {
        if (budget[0] <= 0 || depth > 3) return;
        budget[0]--;
        JSONArray arr = listShareDir(shareid, uk, dir, referer);
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject f = arr.optJSONObject(i);
            if (f == null) continue;
            if (f.optInt("isdir", 0) == 1) {
                String p = f.optString("path", "");
                if (!p.isEmpty() && budget[0] > 0) {
                    walkSize(shareid, uk, p, referer, acc, budget, depth + 1);
                }
            } else {
                long sz = f.optLong("size", 0);
                if (sz > 0) acc[0] += sz;
            }
        }
    }

    /**
     * 把片名清洗成合法的百度网盘目录名。
     *
     * <p>百度对目录名的硬约束：不能含 {@code / \ : * ? " &lt; &gt; |}，
     * 否则报 errno=-7「文件或目录名错误」；长度也有上限，超了会被截断甚至失败。
     * 片名里带冒号的极常见（《志愿军：雄兵出击》），不清洗必然踩。</p>
     */
    static String safeDirName(String name) {
        String s = name == null ? "" : name.trim();
        // 全角冒号先变半角，下一步统一处理；书名号《》无害，保留可读性
        s = s.replace('\uff1a', ':');
        s = s.replaceAll("[\\\\/:*?\"<>|\r\n\t]", " ");
        s = s.replaceAll("\\s+", " ").trim();
        // 结尾的点和空格在部分系统上会被吃掉 -> 「建了却找不到」，一并去掉
        s = s.replaceAll("[. ]+$", "");
        if (s.length() > 60) s = s.substring(0, 60).trim();
        if (s.isEmpty()) s = "未命名影片";
        return s;
    }

    /** 人类可读体积（错误提示里要用，别让用户读 3.7e10 这种数字）。 */
    private static String humanSize(long n) {
        if (n < 0) return "未知";
        if (n < 1024) return n + " B";
        double kb = n / 1024.0;
        if (kb < 1024) return String.format(java.util.Locale.ROOT, "%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format(java.util.Locale.ROOT, "%.1f MB", mb);
        double gb = mb / 1024.0;
        if (gb < 1024) return String.format(java.util.Locale.ROOT, "%.2f GB", gb);
        return String.format(java.util.Locale.ROOT, "%.2f TB", gb / 1024.0);
    }

    /** 一次 share/transfer 调用的结果。 */
    private static class Call {
        String errno = "-1";
        String body = "";
    }

    /** 发一次 share/transfer。 */
    private static Call callTransfer(long[] ids, String dest, String shareid, String uk,
                                     String bdstoken, java.util.Map<String, String> hdrs) {
        Call c = new Call();
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < ids.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(ids[i]);
        }
        sb.append("]");
        String body = "fsidlist=" + enc(sb.toString()) + "&path=" + enc(dest);
        Http.Resp tr = Http.request("POST",
                "https://pan.baidu.com/share/transfer?shareid=" + shareid
                        + "&from=" + uk + "&bdstoken=" + bdstoken
                        + "&channel=chunlei&clienttype=0&web=1&app_id=250528",
                body, hdrs, true);
        c.body = tr.body == null ? "" : tr.body;
        c.errno = errnoOf(c.body);
        android.util.Log.d("SupeMov", "transfer: dest=" + dest + " n=" + ids.length
                + " errno=" + c.errno + " body=" + c.body.substring(0, Math.min(200, c.body.length())));
        return c;
    }

    /**
     * 把一批 fs_id 转存进 dest，必要时自动顺延目录。
     *
     * <p>errno=12 的语义是「<b>部分</b>文件已存在于目标文件夹」（百度页面里的文案表），
     * 也就是这一批其实部分成功了。旧实现为了不丢文件会顺延出 {@code dest (2)}~{@code (5)}
     * 再转一遍 —— 这个策略保留，但<b>顺延的基准改成真正写入的那个目录</b>：
     * 以前无论写哪儿都顺延根目录，现在文件进的是 {@code 根目录/片名/}，
     * 冲突自然也该在 {@code 根目录/片名 (2)/} 上解决。</p>
     *
     * @return true = 成功（落地路径已写入 out.toPaths）；false = 失败（out.message 已填好）
     */
    private static boolean transferBatch(long[] ids, String dest, String shareid, String uk,
                                         String bdstoken, java.util.Map<String, String> hdrs,
                                         TransferResult out) {
        Call c = callTransfer(ids, dest, shareid, uk, bdstoken, hdrs);
        if ("0".equals(c.errno)) {
            collectTargets(out, c.body, dest);
            return true;
        }
        if ("12".equals(c.errno)) {
            for (int n = 2; n <= 5; n++) {
                String alt = dest.replaceAll("/$", "") + " (" + n + ")";
                if (!ensureDir(alt)) continue;
                Call c2 = callTransfer(ids, alt, shareid, uk, bdstoken, hdrs);
                if ("0".equals(c2.errno)) {
                    collectTargets(out, c2.body, alt);
                    out.superseded = alt;
                    return true;
                }
                if (!"12".equals(c2.errno)) {
                    fillFailure(out, c2.errno, c2.body);
                    return false;
                }
            }
            out.message = "目标目录已存在同名影片，且 (2)~(5) 目录都被占用；请在网盘清理后重试";
            return false;
        }
        fillFailure(out, c.errno, c.body);
        return false;
    }

    /**
     * 把一次失败翻译成给用户看的话，并顺手判定是不是「空间不足」。
     *
     * <p>判定顺序有意从确定到不确定：先看响应体里有没有明说空间问题，再看 errno 是否属于
     * 已知的空间码，最后才用容量做旁证。**不敢确定的宁可原样报 errno**，
     * 也不猜「空间不足」——错报会让用户去删不该删的东西。</p>
     */
    private static void fillFailure(TransferResult out, String errno, String body) {
        if (bodySaysNoSpace(body) || SPACE_ERRNOS.contains(errno)) {
            out.spaceFull = true;
            out.message = MSG_NO_SPACE;
            android.util.Log.d("SupeMov", "transfer: 判定空间不足(errno=" + errno + ")");
            return;
        }
        // 旁证：容量查得到，且剩余已经少到放不下任何一部片 —— 这时不管 errno 是什么，
        // 「空间满了」都是对用户最有用的结论。
        Quota q = quota();
        if (q.ok && q.free >= 0 && q.free < SPACE_DANGER_BYTES) {
            out.spaceFull = true;
            out.message = MSG_NO_SPACE + "\n（网盘只剩 " + humanSize(q.free) + "）";
            android.util.Log.d("SupeMov", "transfer: 空间见底旁证成立 free=" + q.free
                    + " errno=" + errno);
            return;
        }
        out.message = transferMsg(errno);
    }

    // ---------- 一键转存 ----------

    /**
     * 一键转存：分享链接(+提取码) -&gt; targetDir；分享根目录里是<b>散文件</b>时，
     * 先建一个以片名命名的文件夹再转进去。
     *
     * <h3>转存到哪儿：一条确定性的规则</h3>
     * <ul>
     *   <li>分享根目录里是<b>文件夹</b> -&gt; 原样转存到 targetDir。百度会把文件夹连内容
     *       递归复制过来，转存目录下自然出现「片名文件夹/剧集」这一层；</li>
     *   <li>分享根目录里是<b>文件</b>（完全没有文件夹，或者文件夹和散文件混在一起）
     *       -&gt; 先在 targetDir 下建一个以片名命名的文件夹，把其中的<b>文件</b>转进去；
     *       同一批里的文件夹仍原样转到 targetDir，保住分享原有的层级。</li>
     * </ul>
     *
     * <p><b>为什么必须加第二条：</b>整个 App 的定位 / 播放 / 下载都是「在转存根目录里按片名
     * 找那个文件夹，再往下钻」。分享根目录直接是散文件时，文件会平铺在转存根目录下 ——
     * 按片名找文件夹永远找不到，只能靠转存接口回的落地路径一次性定位，换部片子就迷路；
     * 下载时也没法按片名建目录（多部片子的散文件会全糊在同一层）。加一层片名文件夹之后，
     * 两种分享形态在网盘里的样子就统一了。</p>
     *
     * @param movieName 片名；为空则不建子文件夹（退回旧行为）
     */
    public static TransferResult transfer(String shareUrl, String pwd, String targetDir,
                                         String movieName) {
        TransferResult out = new TransferResult();
        android.util.Log.d("SupeMov", "transfer: start url=" + shareUrl + " pwd=" + pwd + " dir=" + targetDir);
        if (!CookieStore.hasBaiduLogin()) {
            out.message = "未授权百度网盘，请先到 设置→百度网盘扫码";
            return out;
        }
        Http.desktopUa.set(true);
        if (!sessionValid()) {
            android.util.Log.d("SupeMov", "transfer: session INVALID (api/list errno!=0)");
            out.message = "百度登录已失效，请到 设置→百度网盘扫码 重新授权";
            return out;
        }
        if (targetDir == null || !targetDir.startsWith("/")) targetDir = "/山姆影库";
        Http.desktopUa.set(true); // 整条链路用桌面 UA
        try {
            android.util.Log.d("SupeMov", "transfer: ensureDir " + targetDir);
            if (!ensureDir(targetDir)) {
                out.message = "转存目录创建失败(登录态可能失效)：" + targetDir;
                return out;
            }
            String surl = resolveSurl(shareUrl);
            if (surl.isEmpty()) {
                out.message = "分享链接格式无法识别";
                return out;
            }
            String cookie = CookieStore.cookieFor("https://pan.baidu.com/");

            // 1) verify 换 randsk（带提取码时）—— randsk 就是 BDCLND 的 URL 编码值
            String sekey = "";
            if (pwd != null && !pwd.isEmpty()) {
                String vbody = "pwd=" + enc(pwd) + "&vcode=&vcode_str=";
                java.util.Map<String, String> vh = new java.util.HashMap<>();
                vh.put("Referer", "https://pan.baidu.com/share/init?surl=" + surl);
                Http.Resp vr = Http.request("POST",
                        "https://pan.baidu.com/share/verify?surl=" + surl
                                + "&t=" + System.currentTimeMillis()
                                + "&channel=chunlei&web=1&bdstoken=null&clienttype=0&app_id=250528",
                        vbody, vh, true);
                String errno = errnoOf(vr.body);
                android.util.Log.d("SupeMov", "transfer: verify errno=" + errno + " body=" + vr.body.substring(0, Math.min(200, vr.body.length())));
                if (vr.code != 200 || !"0".equals(errno)) {
                    out.message = "提取码错误或链接失效(" + errno + ")";
                    return out;
                }
                Matcher mr = Pattern.compile("\042randsk\042:\042([A-Za-z0-9%]+)\042").matcher(vr.body);
                if (mr.find()) {
                    sekey = mr.group(1); // URL 编码值，可直接放 Cookie
                    // 关键：BDCLND 写入 CookieStore —— 后续 listShareDir 等
                    // Http.get 请求自动携带；否则首次浏览分享子目录时缺它返回空
                    CookieStore.put("https://pan.baidu.com/", "BDCLND", sekey);
                }
            } else {
                // 无提取码：直接开一次分享页，若回 Set-Cookie BDCLND 也带上
                Http.get(shareUrl);
            }

            // 2) 打开分享页：只带 BDCLND，绝不带 BDUSS！
            // 百度对"合法但已失效"的 BDUSS 会强制登录重定向（Auth Login Params Not Corret）；
            // 无 BDUSS 时分享页正常返回数据（PC 实测）。BDUSS 只在 share/transfer 时用。
            String finalCookie = (sekey != null && !sekey.isEmpty())
                    ? "BDCLND=" + sekey : "";
            android.util.Log.d("SupeMov", "transfer: open-share cookie = "
                    + (finalCookie.isEmpty() ? "EMPTY" : "BDCLND only (" + finalCookie.length() + "ch)"));

            // 3) 打开分享页（桌面 UA + BDCLND）
            java.util.Map<String, String> hdrs = new java.util.HashMap<>();
            hdrs.put("Cookie", finalCookie);
            hdrs.put("Referer", "https://pan.baidu.com/share/init?surl=" + surl);
            Http.Resp page = Http.request("GET", shareUrl, null, hdrs, true);
            if (page.code != 200 || page.body.isEmpty()) {
                out.message = "分享页访问失败(" + page.code + ")";
                return out;
            }
            android.util.Log.d("SupeMov", "transfer: page len=" + page.body.length()
                    + " hasYun=" + page.body.contains("yunData")
                    + " hasFL=" + page.body.contains("fs_id")
                    + " verify=" + page.body.contains("安全验证"));
            // 注意：页面模板里常驻"已失效"字样（分享者信息区），不能作为失效判据！
            // 失效与否由后面的 shareid/uk/file_list 数据提取决定。
            String html = page.body;

            // 4) 提取 shareid / uk：三种形态（window.yunData={...} / locals.mset({...}) / yunData.setData({...})）
            String shareid = null, uk = null;
                    Pattern pSid = Pattern.compile("shareid[^\\d]{0,8}(\\d{9,})");
                    Pattern pUk = Pattern.compile("share_uk[^\\d]{0,8}(\\d{9,})");
            Matcher ms = pSid.matcher(html);
            if (ms.find()) shareid = ms.group(1);
            Matcher mu = pUk.matcher(html);
            if (mu.find()) uk = mu.group(1);
            if (shareid == null || uk == null) {
                // 兜底：页面无数据且我们还没做过 verify（pwd 为空或之前跳过）——
                // 百度有些分享需要 verify 一次才出数据（哪怕无提取码）
                if (sekey.isEmpty() && pwd != null && !pwd.isEmpty()) {
                    String vbody2 = "pwd=" + enc(pwd) + "&vcode=&vcode_str=";
                    Http.Resp vr2 = Http.request("POST",
                            "https://pan.baidu.com/share/verify?surl=" + surl
                                    + "&t=" + System.currentTimeMillis()
                                    + "&channel=chunlei&web=1&bdstoken=null&clienttype=0&app_id=250528",
                            vbody2, null, true);
                    Matcher mr2 = Pattern.compile("\042randsk\042:\042([A-Za-z0-9%]+)\042").matcher(vr2.body);
                    String sekey2 = mr2.find() ? mr2.group(1) : "";
                    if (!sekey2.isEmpty()) {
                        CookieStore.put("https://pan.baidu.com/", "BDCLND", sekey2);
                        String ck2 = "BDCLND=" + sekey2; // 分享页不带 BDUSS（会触发登录重定向）
                        java.util.Map<String, String> hdrs2 = new java.util.HashMap<>();
                        hdrs2.put("Cookie", ck2);
                        page = Http.request("GET", shareUrl, null, hdrs2, true);
                        html = page.body;
                        Matcher ms2 = pSid.matcher(html);
                        if (ms2.find()) shareid = ms2.group(1);
                        Matcher mu2 = pUk.matcher(html);
                        if (mu2.find()) uk = mu2.group(1);
                    }
                }
                if (shareid == null || uk == null) {
                    String diag = "len=" + html.length()
                            + " hasYun=" + html.contains("yunData")
                            + " hasFL=" + html.contains("file_list")
                            + " hasVerify=" + html.contains("安全验证");
                    out.message = "分享页无数据[" + diag + "]";
                    return out;
                }
            }
            // 记下 shareid/uk：转存成功后 DetailActivity 要拿它对分享清单做完整性校验
            out.shareid = shareid;
            out.uk = uk;

            // bdstoken 优先从分享页 yunData 里提取（无登录态也带），失败才走 gettemplatevariable
            String bdstoken = "";
            Matcher mbt = Pattern.compile("bdstoken[^a-f0-9]{0,6}([a-f0-9]{32})").matcher(html);
            if (mbt.find()) bdstoken = mbt.group(1);
            if (bdstoken.isEmpty()) bdstoken = getBdstoken();

            // 5) 取分享根条目（带 isdir），据此决定「文件放哪儿、文件夹放哪儿」。
            //
            //    以 share/list 的根列表为主源、不用页面里的 file_list：后者是渲染层片段，
            //    多文件 / 多级目录时会被截断（少集），而且有时会把文件夹**展开成散文件** ——
            //    照它转存，网盘里就变成「一堆文件平铺在转存目录下」，原有层级全丢。
            //
            //    根条目里的「文件夹」项本身就代表「连文件夹带内容一起递归复制」，
            //    所以原样下发 = 转存结果与分享结构一致。
            JSONArray roots = listShareDir(shareid, uk, "/", shareUrl);
            String srcTag = "share/list(root)";
            if (roots == null || roots.length() == 0) {
                // 兜底：share/list 拿不到（风控 / 接口异常）才退回页面 file_list。
                // 这个来源会把文件夹**展平**，条目基本都是文件 —— 正好适用「文件进片名文件夹」。
                srcTag = "page file_list(兜底)";
                String flJson = extractFileListJson(html);
                if (flJson != null) {
                    try {
                        roots = new JSONArray(flJson);
                    } catch (Exception ignored) {
                    }
                }
            }

            JSONArray dirArr = new JSONArray();
            JSONArray fileArr = new JSONArray();
            long rootFileBytes = 0;      // 根条目里「文件」的体积（目录项的 size 在百度侧常为 0）
            if (roots != null) {
                for (int i = 0; i < roots.length(); i++) {
                    JSONObject f = roots.optJSONObject(i);
                    if (f == null || f.optLong("fs_id", 0) <= 0) continue;
                    if (f.optInt("isdir", 0) == 1) {
                        dirArr.put(f);
                    } else {
                        fileArr.put(f);
                        long sz = f.optLong("size", 0);
                        if (sz > 0) rootFileBytes += sz;
                    }
                }
            }
            long[] dirIds = idsOf(dirArr);
            long[] fileIds = idsOf(fileArr);
            android.util.Log.d("SupeMov", "transfer: roots from " + srcTag
                    + " dirs=" + dirIds.length + " files=" + fileIds.length
                    + " rootFileBytes=" + rootFileBytes
                    + " shareid=" + shareid + " uk=" + uk);
            if (dirIds.length == 0 && fileIds.length == 0) {
                out.message = "没有文件[页长" + html.length()
                        + " yun=" + html.contains("yunData")
                        + " fl=" + html.contains("fs_id")
                        + " 验证=" + html.contains("安全验证") + "]";
                return out;
            }

            // 5b) 容量预检 —— 「空间满了」的**主判据**。
            //
            //     为什么不靠 errno：百度转存失败回的码并不专一，errno=4 被解释成「存储出问题」，
            //     同一份资料又把 -9/-10 说成容量不足，拿它判定必然误报。
            //     直接拿真实剩余空间跟要转存的体积比，才是确定性的判定。
            //
            //     需要多少：根条目里文件的体积是准的；若分享根是文件夹（目录项 size 常为 0），
            //     就递归统计一个**下界** —— 下界偏小只会让我们少判几次，不会误判。
            //     剩余空间充裕（>150GB）时连统计都不做，省掉一次目录遍历。
            Quota q0 = quota();
            long needLower = rootFileBytes;
            if (q0.ok && q0.free >= 0 && q0.free < SPACE_PLENTY_BYTES
                    && dirIds.length > 0 && rootFileBytes <= 0) {
                needLower = shareSizeLowerBound(shareid, uk, shareUrl);
            }
            if (q0.ok && q0.free >= 0 && needLower > 0 && q0.free < needLower) {
                android.util.Log.d("SupeMov", "transfer: 空间不足预检拦下 needLower=" + needLower
                        + " free=" + q0.free);
                out.spaceFull = true;
                out.message = MSG_NO_SPACE + "\n（需要 " + humanSize(needLower)
                        + "，网盘只剩 " + humanSize(q0.free) + "）";
                return out;
            }
            if (q0.ok && q0.free >= 0 && q0.free < SPACE_DANGER_BYTES) {
                android.util.Log.d("SupeMov", "transfer: 空间见底拦下 free=" + q0.free);
                out.spaceFull = true;
                out.message = MSG_NO_SPACE + "\n（网盘只剩 " + humanSize(q0.free) + "）";
                return out;
            }

            // 6) 分批转存：文件先转（进「片名」文件夹），文件夹后转（进根目录）
            hdrs.put("Cookie", finalCookie);
            hdrs.put("X-Requested-With", "XMLHttpRequest");
            hdrs.put("Origin", "https://pan.baidu.com");
            String bdussT = CookieStore.get("https://pan.baidu.com", "BDUSS");
            String ckT = (hdrs.get("Cookie") == null ? "" : hdrs.get("Cookie") + "; ")
                    + "BDUSS=" + (bdussT == null ? "" : bdussT);
            hdrs.put("Cookie", ckT);

            String root = trimSlash(targetDir);
            String fileDest = root;
            if (fileIds.length > 0 && movieName != null && !movieName.trim().isEmpty()) {
                fileDest = root + "/" + safeDirName(movieName);
                if (!ensureDir(fileDest)) {
                    out.message = "影片文件夹创建失败(登录态可能失效)：" + fileDest;
                    return out;
                }
                android.util.Log.d("SupeMov", "transfer: 文件转存目标 = " + fileDest);
            }

            // 文件批次先发：片名文件夹是这部片最稳的锚点，让它排在 toPaths 最前面，
            // 调用方拿 toPaths.get(0) 就能直接定位，不必再按片名猜。
            if (fileIds.length > 0
                    && !transferBatch(fileIds, fileDest, shareid, uk, bdstoken, hdrs, out)) {
                return out;
            }
            if (dirIds.length > 0
                    && !transferBatch(dirIds, root, shareid, uk, bdstoken, hdrs, out)) {
                return out;
            }

            out.ok = true;
            out.message = (out.superseded.isEmpty()
                    ? "转存成功 → " : "目标目录已有同名影片，已转存到 → ")
                    + (out.toPaths.isEmpty() ? targetDir : out.toPaths.get(0));
            return out;
        } finally {
            Http.desktopUa.set(false);
        }
    }

    // ---------- 播放取流（原画直链 / M3U8 转码流） ----------

    /** 待播放的文件：fs_id + 网盘内完整路径（取 dlink 与 M3U8 都需要它）。 */
    public static class PlayFile {
        public boolean ok;
        public String message = "";
        public long fsId;
        public String path = "";
        public String name = "";
        public long size;
        /** 相对「命中的那个目录」的子目录（如 "第二季"、"剧名/Season 1"）；直接在根下则为空。
         *  多集/多文件夹时用来分组展示，也用来在本地还原同样的目录结构。 */
        public String rel = "";
    }

    private static final String[] VIDEO_EXT = {
            ".mp4", ".mkv", ".avi", ".ts", ".m2ts", ".mov", ".wmv", ".flv", ".rmvb", ".rm", ".mpg", ".mpeg", ".m4v"
    };

    /** 列出自己网盘目录（api/list）。失败返回 null。 */
    public static JSONArray listDir(String dir) {
        Http.desktopUa.set(true);
        String u = "https://pan.baidu.com/api/list?clienttype=0&app_id=250528&web=1"
                + "&order=time&desc=1&num=200&page=1&dir=" + enc(dir);
        Http.Resp r = Http.get(u);
        try {
            JSONObject o = new JSONObject(r.body);
            if ("0".equals(String.valueOf(o.opt("errno")))) return o.optJSONArray("list");
            android.util.Log.d("SupeMov", "listDir " + dir + " errno=" + o.opt("errno"));
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * 在网盘 rootDir 里找出「这部电影」对应的可播放文件：
     * 先按片名匹配，匹配不到取最新的一项；若命中的是目录（转存常见形态）自动下钻
     * 两层，取其中体积最大的视频文件。
     */
    public static PlayFile resolvePlayable(String rootDir, String keyword) {
        PlayFile out = new PlayFile();
        Http.desktopUa.set(true);
        try {
            // 与 resolveAll 共用同一套「只认名字」的匹配：绝不猜「目录里最新的一项」，
            // 否则刚转存完 A 再点 B，B 会拿到 A 的文件
            String key = normName(keyword);
            JSONObject pick = findAnchor(rootDir, key);
            if (pick == null) {
                String rem = rememberedPathFor(keyword);
                if (!rem.isEmpty()) pick = locate(rootDir, rem);
            }
            if (pick == null) {
                out.message = key.isEmpty()
                        ? "网盘目录里没有可播放的内容"
                        : "网盘里没找到这部影片（可先点「转存」再播放）";
                return out;
            }
            // 命中的是目录 -> 下钻找视频文件
            if (pick.optInt("isdir", 0) == 1) {
                JSONObject vf = deepFindVideo(pick.optString("path", ""), 0);
                if (vf == null) {
                    out.message = "目录里没找到视频文件：" + pick.optString("server_filename", "");
                    return out;
                }
                pick = vf;
            }
            out.fsId = pick.optLong("fs_id", 0);
            out.path = pick.optString("path", "");
            out.name = pick.optString("server_filename", "");
            out.size = pick.optLong("size", 0);
            out.ok = out.fsId > 0 && !out.path.isEmpty();
            if (!out.ok) out.message = "未能定位可播放文件";
            android.util.Log.d("SupeMov", "resolvePlayable -> " + out.path + " size=" + out.size);
            return out;
        } catch (Throwable e) {
            out.message = "取流异常：" + e.getClass().getSimpleName();
            return out;
        }
    }

    /** 目录下钻（最多 2 层），返回体积最大的视频文件；找不到返回 null。 */
    private static JSONObject deepFindVideo(String dir, int depth) {
        if (dir == null || dir.isEmpty() || depth > 2) return null;
        JSONArray list = listDir(dir);
        if (list == null) return null;
        JSONObject best = null;
        List<JSONObject> subDirs = new ArrayList<>();
        for (int i = 0; i < list.length(); i++) {
            JSONObject f = list.optJSONObject(i);
            if (f == null) continue;
            if (f.optInt("isdir", 0) == 1) {
                subDirs.add(f);
                continue;
            }
            String nm = f.optString("server_filename", "").toLowerCase();
            boolean isVideo = false;
            for (String ext : VIDEO_EXT) {
                if (nm.endsWith(ext)) {
                    isVideo = true;
                    break;
                }
            }
            if (!isVideo) continue;
            if (best == null || f.optLong("size", 0) > best.optLong("size", 0)) best = f;
        }
        if (best != null) return best;
        // 本层没有视频，继续下钻（剧集/合集常见的 季/集 两级结构）
        for (JSONObject d : subDirs) {
            JSONObject r = deepFindVideo(d.optString("path", ""), depth + 1);
            if (r != null) return r;
        }
        return null;
    }

    // ==================== 多文件 / 多级文件夹 ====================

    /** 递归深度上限：剧集一般是 剧名/季/集 三层，留点余量。 */
    private static final int MAX_DEPTH = 5;
    /** 单次枚举的文件数上限，避免把接口拖垮、也避免列表长到没法用遥控器翻。 */
    private static final int MAX_FILES = 300;

    /** 一部片子对应的一批待选文件（可能很多集）。 */
    public static class Resolved {
        public boolean ok;
        public String message = "";
        /** 命中的那个目录/文件在网盘里的路径 */
        public String anchorPath = "";
        /** 命中项的名字（网盘里的文件夹名 / 文件名）——本地落盘要按它建一层同名文件夹 */
        public String anchorName = "";
        public final List<PlayFile> files = new ArrayList<>();
    }

    /**
     * 「这部片名下的**全部**视频文件」（递归穿过子文件夹）。
     *
     * <p>为什么不复用 {@link #resolvePlayable}：它只返回体积最大的那一个。转存下来的
     * 分享经常是一整个文件夹——多集剧、上下部、CD1/CD2，或再套一层「季」目录。
     * 只挑一个的话用户既看不到其它文件、也没法选，播错集只能自己去网盘翻。</p>
     *
     * <p>命中规则的取舍：</p>
     * <ul>
     *   <li>命中的是<b>目录</b> → 把该目录下（含各级子目录）所有视频都收上来</li>
     *   <li>命中的是<b>单个文件</b> → 收它自己 + 同目录下名字相近的兄弟（CD1/CD2、上下部），
     *       避免把同目录里别的影片也一起带进来</li>
     * </ul>
     */
    public static Resolved resolveAll(String rootDir, String keyword) {
        String key = normName(keyword);
        Http.desktopUa.set(true);
        JSONObject pick = findAnchor(rootDir, key);
        if (pick == null) {
            // 名字匹配不上时，只有当「上次转存的正是这部片」才用记下的落地路径回查
            String rem = rememberedPathFor(keyword);
            if (!rem.isEmpty()) pick = locate(rootDir, rem);
        }
        String miss = key.isEmpty()
                ? "网盘目录里没有可播放的内容"
                : "网盘里没找到这部影片（可先点「转存」再试）";
        return materialize(pick, miss, key);
    }

    /** 按转存落地路径精确解析（转存刚结束时用；名字对不上也能定位到）。 */
    public static Resolved resolveAt(String rootDir, String absPath) {
        Http.desktopUa.set(true);
        JSONObject pick = locate(rootDir, absPath);
        return materialize(pick, "网盘里没找到这部影片（可先点「转存」再试）", absPath);
    }

    /** 把「命中的那一项」展开成可播放文件表；pick 为 null 时返回带 message 的空结果。 */
    private static Resolved materialize(JSONObject pick, String missMsg, String keyForLog) {
        Resolved out = new Resolved();
        if (pick == null) {
            out.message = missMsg;
            return out;
        }
        try {
            out.anchorPath = pick.optString("path", "");
            out.anchorName = pick.optString("server_filename", "");
            if (pick.optInt("isdir", 0) == 1) {
                // 命中文件夹：里面所有视频都收上来（递归穿子目录）。
                // rel 从**文件夹自己的名字**开始 —— 于是 rel 的语义统一为
                // 「相对转存根目录的子目录」，下载时能原样建出 片名文件夹/季/ 这一层层级，
                // 而不是把几百集平铺在下载根目录里。
                collectVideos(out.anchorPath, out.anchorName, out.files, 0);
            } else {
                // 命中单个文件：自己 + 同目录下名字相近的兄弟
                String core = coreName(pick.optString("server_filename", ""));
                String parent = parentOf(pick.optString("path", ""));
                for (PlayFile f : listVideos(parent)) {
                    String fc = coreName(f.name);
                    if (f.fsId == pick.optLong("fs_id", 0)
                            || (!core.isEmpty() && (fc.contains(core) || core.contains(fc)))) {
                        out.files.add(f);
                    }
                }
                if (out.files.isEmpty()) {
                    PlayFile self = toFile(pick, "");
                    if (self != null) out.files.add(self);
                }
            }
            sortNaturally(out.files);
            out.ok = !out.files.isEmpty();
            if (!out.ok) out.message = "目录里没找到视频文件：" + pick.optString("server_filename", "");
            android.util.Log.d("SupeMov", "resolveAll key=" + keyForLog + " -> " + out.anchorPath
                    + " files=" + out.files.size()
                    + " relRoot=" + (out.anchorName.isEmpty() ? "(单文件,平铺)" : out.anchorName));
            return out;
        } catch (Throwable e) {
            out.message = "枚举文件异常：" + e.getClass().getSimpleName();
            return out;
        }
    }

    /**
     * 在转存根目录里**按名字**找那一项；找不到返回 null。
     *
     * <p><b>这里绝不能有「猜最新一项」的兜底。</b>老实现里有一条：名字没匹配上、但目录里最新
     * 一项是 10 分钟内创建的，就认它。于是「刚转存完 A 片，再点 B 片」时 —— B 还没转存过、
     * 名字当然匹配不上 —— 直接把刚转存的 A 返回了：用户点任何片子出来的都是 A 的剧集列表，
     * 而且因为「查到了」，连转存都被跳过（连转存按钮都会说「已转存过」）。名字匹配不上就该
     * 如实报「没找到」，由调用方决定要不要转存。</p>
     *
     * <p>「转存下来的文件夹名和片名对不上」的情况改用落地路径定位，见 {@link #locate}。</p>
     */
    private static JSONObject findAnchor(String rootDir, String key) {
        if (key == null || key.isEmpty()) {
            android.util.Log.d("SupeMov", "findAnchor: 片名为空，不猜");
            return null;
        }
        for (String dir : rootDirs(rootDir)) {
            JSONArray list = listDir(dir);
            if (list == null) continue; // 目录不存在
            for (int i = 0; i < list.length(); i++) {
                JSONObject f = list.optJSONObject(i);
                if (f == null || f.optLong("fs_id", 0) <= 0) continue;
                String nm = normName(f.optString("server_filename", ""));
                if (!nm.isEmpty() && (nm.contains(key) || key.contains(nm))) return f;
            }
        }
        android.util.Log.d("SupeMov", "findAnchor miss key=" + key);
        return null;
    }

    /** 转存根目录候选：同名冲突时百度会把目录顺延成 目录(2)..(5)。 */
    private static String[] rootDirs(String rootDir) {
        return new String[]{
                rootDir,
                trimSlash(rootDir) + " (2)", trimSlash(rootDir) + " (3)",
                trimSlash(rootDir) + " (4)", trimSlash(rootDir) + " (5)"
        };
    }

    /**
     * 按**绝对路径**在转存根目录里精确取那一项（先比完整 path，再比末段名字）。
     *
     * <p>转存接口会把实际落地路径告诉我们（{@link TransferResult#toPaths}），用它定位不存在
     * 「播错片」的可能 —— 这比拿片名做模糊匹配可靠得多。</p>
     */
    private static JSONObject locate(String rootDir, String absPath) {
        if (absPath == null || absPath.isEmpty()) return null;
        String leaf = absPath;
        int sl = leaf.lastIndexOf('/');
        if (sl >= 0) leaf = leaf.substring(sl + 1);
        String leafKey = normName(leaf);
        if (leafKey.isEmpty()) return null;
        for (String dir : rootDirs(rootDir)) {
            JSONArray list = listDir(dir);
            if (list == null) continue;
            for (int i = 0; i < list.length(); i++) {
                JSONObject f = list.optJSONObject(i);
                if (f == null || f.optLong("fs_id", 0) <= 0) continue;
                if (absPath.equals(f.optString("path", ""))) return f;
            }
            for (int i = 0; i < list.length(); i++) {
                JSONObject f = list.optJSONObject(i);
                if (f == null || f.optLong("fs_id", 0) <= 0) continue;
                if (leafKey.equals(normName(f.optString("server_filename", "")))) return f;
            }
        }
        android.util.Log.d("SupeMov", "locate miss path=" + absPath);
        return null;
    }

    /**
     * 上次转存**这一部片**落地的网盘路径；片名对不上就返回空（防止张冠李戴）。
     * 用于「分享里的文件夹名被改过、按片名匹配不到」时精确回查。
     */
    private static String rememberedPathFor(String keyword) {
        try {
            String t = Settings.lastTransferTitle();
            if (t == null || t.isEmpty()) return "";
            if (!normName(t).equals(normName(keyword))) return "";
            return Settings.lastTransferPath();
        } catch (Throwable e) {
            return "";
        }
    }


    /**
     * 递归收集 dir 下的视频文件；rel 是相对「命中目录」的子路径（用于展示与本地还原目录结构）。
     * 先收本层文件再下钻子目录 —— 这样列表里本层的集排在子文件夹的集前面。
     */
    private static void collectVideos(String dir, String rel, List<PlayFile> out, int depth) {
        if (dir == null || dir.isEmpty() || depth > MAX_DEPTH || out.size() >= MAX_FILES) return;
        JSONArray list = listDir(dir);
        if (list == null) return;
        List<JSONObject> subDirs = new ArrayList<>();
        for (int i = 0; i < list.length(); i++) {
            JSONObject f = list.optJSONObject(i);
            if (f == null || f.optLong("fs_id", 0) <= 0) continue;
            if (f.optInt("isdir", 0) == 1) {
                subDirs.add(f);
                continue;
            }
            if (!isVideo(f.optString("server_filename", ""))) continue;
            PlayFile p = toFile(f, rel);
            if (p != null) out.add(p);
        }
        for (JSONObject d : subDirs) {
            if (out.size() >= MAX_FILES) return;
            String nm = d.optString("server_filename", "");
            collectVideos(d.optString("path", ""), rel.isEmpty() ? nm : rel + "/" + nm, out, depth + 1);
        }
    }

    /** 某一层目录下的视频文件（不下钻）。 */
    private static List<PlayFile> listVideos(String dir) {
        List<PlayFile> out = new ArrayList<>();
        if (dir == null || dir.isEmpty()) return out;
        JSONArray list = listDir(dir);
        if (list == null) return out;
        for (int i = 0; i < list.length(); i++) {
            JSONObject f = list.optJSONObject(i);
            if (f == null || f.optLong("fs_id", 0) <= 0) continue;
            if (f.optInt("isdir", 0) == 1) continue;
            if (!isVideo(f.optString("server_filename", ""))) continue;
            PlayFile p = toFile(f, "");
            if (p != null) out.add(p);
        }
        return out;
    }

    private static PlayFile toFile(JSONObject f, String rel) {
        if (f == null) return null;
        PlayFile p = new PlayFile();
        p.fsId = f.optLong("fs_id", 0);
        p.path = f.optString("path", "");
        p.name = f.optString("server_filename", "");
        p.size = f.optLong("size", 0);
        p.rel = rel == null ? "" : rel;
        p.ok = p.fsId > 0 && !p.path.isEmpty();
        return p.ok ? p : null;
    }

    private static boolean isVideo(String name) {
        if (name == null || name.isEmpty()) return false;
        String l = name.toLowerCase();
        for (String ext : VIDEO_EXT) {
            if (l.endsWith(ext)) return true;
        }
        return false;
    }

    /** 文件名的「主体」（去扩展名后归一化），用于判断是不是同一部片的兄弟文件。 */
    private static String coreName(String fileName) {
        if (fileName == null) return "";
        String s = fileName;
        int dot = s.lastIndexOf('.');
        if (dot > 0 && s.length() - dot <= 6) s = s.substring(0, dot);
        return normName(s);
    }

    private static String parentOf(String path) {
        if (path == null) return "";
        int i = path.lastIndexOf('/');
        return i <= 0 ? "" : path.substring(0, i);
    }

    /** 自然序：让「第2集」排在「第10集」前面（纯字典序会反过来）。 */
    public static void sortNaturally(List<PlayFile> files) {
        if (files == null || files.size() < 2) return;
        java.util.Collections.sort(files, (a, b) -> {
            int c = naturalCompare(a.rel, b.rel);
            return c != 0 ? c : naturalCompare(a.name, b.name);
        });
    }

    private static int naturalCompare(String a, String b) {
        String x = a == null ? "" : a;
        String y = b == null ? "" : b;
        int i = 0;
        int j = 0;
        while (i < x.length() && j < y.length()) {
            char ca = x.charAt(i);
            char cb = y.charAt(j);
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                int si = i;
                int sj = j;
                while (i < x.length() && Character.isDigit(x.charAt(i))) i++;
                while (j < y.length() && Character.isDigit(y.charAt(j))) j++;
                String na = x.substring(si, i).replaceFirst("^0+(?!$)", "");
                String nb = y.substring(sj, j).replaceFirst("^0+(?!$)", "");
                if (na.length() != nb.length()) return na.length() - nb.length();
                int c = na.compareTo(nb);
                if (c != 0) return c;
            } else {
                if (ca != cb) return ca - cb;
                i++;
                j++;
            }
        }
        return (x.length() - i) - (y.length() - j);
    }

    /** 取原画直链 dlink（8 小时有效；必须配 UA=netdisk，由 LocalProxy 负责）。 */
    public static String dlink(long fsId, String path) {
        Http.desktopUa.set(true);
        try {
            String target = "[\"" + path + "\"]";
            String u = "https://pan.baidu.com/api/filemetas?dlink=1"
                    + "&fsids=" + enc("[" + fsId + "]")
                    + "&target=" + enc(target)
                    + "&clienttype=0&app_id=250528&web=1&channel=chunlei";
            Http.Resp r = Http.get(u);
            JSONObject o = new JSONObject(r.body);
            String errno = String.valueOf(o.opt("errno"));
            if (!"0".equals(errno)) {
                android.util.Log.d("SupeMov", "dlink errno=" + errno);
                return "";
            }
            JSONArray info = o.optJSONArray("info");
            if (info == null || info.length() == 0) return "";
            JSONObject i0 = info.optJSONObject(0);
            if (i0 == null) return "";
            String d = i0.optString("dlink", "");
            if (d.isEmpty()) d = i0.optString("url", "");
            android.util.Log.d("SupeMov", "dlink ok len=" + d.length());
            return d;
        } catch (Throwable e) {
            return "";
        }
    }

    /** M3U8 云端转码流（原画播不动时的兜底）。type 取 M3U8_AUTO_480 / _720 / _1080。 */
    public static String streamingUrl(String path, String type) {
        Http.desktopUa.set(true);
        try {
            String u = "https://pan.baidu.com/api/streaming?type=" + type
                    + "&path=" + enc(path)
                    + "&clienttype=0&app_id=250528&web=1&channel=chunlei&check_blue=1&vip=2";
            Http.Resp r = Http.get(u);
            String body = r.body == null ? "" : r.body;

            // 优先按 JSON 找；解析不了（body 被截断 / 返回的是 HTML 风控页）就退到正则，
            // 两者都不成也要把原因打出来 —— 之前这里 catch 完直接 return ""，
            // 日志里只剩一句「转码流不可用」，等于什么都没说。
            try {
                JSONObject o = new JSONObject(body);
                String[] keys = {"m3u8_url", "m3u8", "url", "dlink"};
                for (String k : keys) {
                    String v = o.optString(k, "");
                    if (!v.isEmpty() && v.startsWith("http")) {
                        android.util.Log.d("SupeMov", "streaming(" + type + ") key=" + k);
                        return v;
                    }
                }
                android.util.Log.w("SupeMov", "streaming(" + type + ") 无地址 key, errno="
                        + o.opt("errno") + " keys=" + o.keys());
            } catch (Throwable e) {
                android.util.Log.w("SupeMov", "streaming(" + type + ") JSON 解析失败: " + e
                        + " bodyLen=" + body.length()
                        + " head=" + body.substring(0, Math.min(160, body.length())));
            }
            // 兜底：直接在原始响应里捞一个含 m3u8 的 http 地址
            // （JSON 结构变了、body 被截断也还能捞到；不写复杂正则，避免转义出错）
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("https?://[^\"'<> ]+").matcher(body);
            while (m.find()) {
                String hit = m.group();
                if (hit.contains("m3u8")) {
                    android.util.Log.d("SupeMov", "streaming(" + type + ") 正则兜底命中");
                    return hit;
                }
            }
            android.util.Log.w("SupeMov", "streaming(" + type + ") 未取到地址 code=" + r.code
                    + " bodyLen=" + body.length());
            return "";
        } catch (Throwable e) {
            android.util.Log.w("SupeMov", "streaming(" + type + ") 异常: " + e);
            return "";
        }
    }

    /** 去掉末尾斜杠（拼 "目录 (2)" 这类顺延目录时用）。 */
    private static String trimSlash(String p) {
        if (p == null) return "";
        return (p.endsWith("/") && p.length() > 1) ? p.substring(0, p.length() - 1) : p;
    }

    /** 片名归一化：只留中英文与数字，用于在网盘里认片。 */
    private static String normName(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c)) sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }

    private static String transferMsg(String errno) {
        if ("0".equals(errno)) return "转存成功";
        if ("12".equals(errno)) return "目标目录已有同名文件";
        if ("105".equals(errno)) return "分享链接已损坏/失效";
        if ("4".equals(errno)) return "转存被拒(errno=4)：可能是网盘空间不足、非会员转存次数超限，或该文件已在网盘里";
        if ("-6".equals(errno)) return "百度登录态失效，请重新扫码授权";
        if ("-70".equals(errno)) return "文件存在安全风险，百度拒绝转存";
        if ("-30".equals(errno)) return "文件已失效或被百度屏蔽";
        if ("-1".equals(errno) || errno == null || errno.isEmpty()) return "转存接口无响应(网络/风控)";
        return "转存失败 errno=" + errno;
    }

    /**
     * 递归列出「分享里的全部视频文件」（名称 + 体积 + 相对分享根的路径）。
     *
     * <p>用途：转存后核对有没有**缺文件** —— 多集剧、多级文件夹最容易少转几集，
     * 只回一句「转存成功」是不负责任的。shareid / uk 由 {@link #transfer} 放在
     * {@link TransferResult} 里带回来。</p>
     *
     * <p>返回 <b>空表表示「拿不到清单」</b>（接口失败 / 风控 / 分享已失效），
     * 外部必须按「无法校验」处理，绝不能当成「分享里没有文件」。</p>
     */
    public static List<PlayFile> shareManifest(String shareid, String uk, String referer) {
        List<PlayFile> out = new ArrayList<>();
        if (shareid == null || shareid.isEmpty() || uk == null || uk.isEmpty()) return out;
        // 必须桌面 UA：移动 UA 会被导向 wap 分享页，share/list 会返回空
        Http.desktopUa.set(true);
        try {
            walkShare(shareid, uk, referer, "/", "", out, 0);
        } catch (Throwable e) {
            android.util.Log.d("SupeMov", "shareManifest err " + e);
        } finally {
            Http.desktopUa.set(false);
        }
        android.util.Log.d("SupeMov", "shareManifest shareid=" + shareid + " videos=" + out.size());
        return out;
    }

    private static void walkShare(String shareid, String uk, String referer, String dir,
                                  String rel, List<PlayFile> out, int depth) {
        if (dir == null || dir.isEmpty() || depth > MAX_DEPTH || out.size() >= MAX_FILES) return;
        JSONArray list = listShareDir(shareid, uk, dir, referer);
        if (list == null) return;
        List<JSONObject> subDirs = new ArrayList<>();
        for (int i = 0; i < list.length(); i++) {
            JSONObject f = list.optJSONObject(i);
            if (f == null) continue;
            String nm = f.optString("server_filename", "");
            if (f.optInt("isdir", 0) == 1) {
                subDirs.add(f);
                continue;
            }
            if (!isVideo(nm)) continue;
            PlayFile p = new PlayFile();
            p.fsId = f.optLong("fs_id", 0);
            p.name = nm;
            p.size = f.optLong("size", 0);
            p.path = f.optString("path", "");
            p.rel = rel == null ? "" : rel;
            p.ok = true;
            out.add(p);
        }
        for (JSONObject d : subDirs) {
            if (out.size() >= MAX_FILES) return;
            String nm = d.optString("server_filename", "");
            walkShare(shareid, uk, referer, d.optString("path", ""),
                    rel == null || rel.isEmpty() ? nm : rel + "/" + nm, out, depth + 1);
        }
    }

    private static JSONArray listShareDir(String shareid, String uk, String dir, String referer) {
        // 最小集 Cookie（BDUSS+BDCLND）：全量 cookieFor 会带 STOKEN 等干扰验证态
        String bduss = CookieStore.get("https://pan.baidu.com", "BDUSS");
        String bdclnd = CookieStore.get("https://pan.baidu.com", "BDCLND");
        StringBuilder ckl = new StringBuilder();
        if (bdclnd != null && !bdclnd.isEmpty()) ckl.append("BDCLND=").append(bdclnd);
        java.util.Map<String, String> lh = new java.util.HashMap<>();
        lh.put("Cookie", ckl.toString());
        lh.put("Referer", referer == null ? "https://pan.baidu.com/" : referer);
        Http.Resp r = Http.request("GET", "https://pan.baidu.com/share/list?shareid=" + shareid
                + "&uk=" + uk + "&root=" + ("/".equals(dir) || dir.isEmpty() ? "1" : "0")
                + "&dir=" + enc(dir) + "&clienttype=0&web=1&channel=chunlei", null, lh, true);
        try {
            android.util.Log.d("SupeMov", "share/list dir=" + dir + " body=" + r.body.substring(0, Math.min(180, r.body.length())));
            JSONObject o = new JSONObject(r.body);
            if ("0".equals(String.valueOf(o.opt("errno")))) {
                return o.optJSONArray("list");
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** 会话是否真的有效：api/list 探测（本地有 BDUSS 不代表服务器侧会话还在）。 */
    public static boolean sessionValid() {
        Http.Resp r = Http.get("https://pan.baidu.com/api/list?clienttype=0&app_id=250528&web=1&dir=%2F");
        return r.code == 200 && "0".equals(errnoOf(r.body));
    }

    private static boolean ensureDir(String path) {
        if (path == null || !path.startsWith("/")) return false;
        String p = path.endsWith("/") && path.length() > 1 ? path.substring(0, path.length() - 1) : path;
        // 已存在？api/list errno=0 或 errno=-9(目录不存在) 都属正常响应
        Http.Resp list = Http.get("https://pan.baidu.com/api/list?clienttype=0&app_id=250528&web=1&dir=" + enc(p));
        if (list.code == 200 && "0".equals(errnoOf(list.body))) return true;
        // 创建
        Http.Resp cr = Http.request("POST",
                "https://pan.baidu.com/api/create?clienttype=0&app_id=250528&web=1",
                "path=" + enc(p) + "&isdir=1&block_list=%5B%5D", null, true);
        return cr.code == 200 && "0".equals(errnoOf(cr.body));
    }

    private static String getBdstoken() {
        Http.Resp r = Http.get("https://pan.baidu.com/api/gettemplatevariable?clienttype=0&app_id=250528&web=1&fields=%5B%22bdstoken%22%5D");
        try {
            JSONObject o = new JSONObject(r.body);
            JSONObject res = o.optJSONObject("result");
            if (res != null) return res.optString("bdstoken", "");
        } catch (Exception ignored) {
        }
        return "";
    }

    private static String resolveSurl(String shareUrl) {
        Matcher m = Pattern.compile("/s/([A-Za-z0-9_\\-]+)").matcher(shareUrl == null ? "" : shareUrl);
        if (!m.find()) return "";
        String s = m.group(1);
        if (s.startsWith("1") && s.length() > 20) s = s.substring(1);
        return s;
    }

    /** 从 HTML 里提取含 fs_id 的 "file_list":[...] JSON（页面有多个 file_list，只有真实文件列表含 fs_id）。 */
    private static String extractFileListJson(String html) {
        if (html == null) return null;
        int from = 0;
        while (true) {
            int key = html.indexOf("file_list", from);
            if (key < 0) return null;
            from = key + 9;
            int lb = html.indexOf('[', key);
            if (lb < 0) return null;
            boolean inStr = false, esc = false;
            int depth = 0;
            int end = -1;
            for (int i = lb; i < html.length() && i < lb + 200000; i++) {
                char c = html.charAt(i);
                if (esc) { esc = false; continue; }
                if (c == '\\') { esc = true; continue; }
                if (c == '"') inStr = !inStr;
                if (inStr) continue;
                if (c == '[') depth++;
                else if (c == ']') {
                    depth--;
                    if (depth == 0) { end = i + 1; break; }
                }
            }
            if (end < 0) return null;
            String candidate = html.substring(lb, end);
            if (candidate.contains("fs_id")) return candidate;
            // 否则继续找下一个 file_list 出现位置
        }
    }

    private static String errnoOf(String body) {
        if (body == null || body.isEmpty()) return "-1";
        try {
            JSONObject o = new JSONObject(body);
            if (o.has("errno")) return String.valueOf(o.get("errno"));
            JSONArray info = o.optJSONArray("info");
            if (info != null && info.length() > 0) {
                JSONObject i0 = info.optJSONObject(0);
                if (i0 != null && i0.has("errno")) return String.valueOf(i0.get("errno"));
            }
        } catch (Exception ignored) {
        }
        return "-1";
    }

    private static String enc(String s) {
        try {
            return java.net.URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    /** 下载二维码图片字节（imgurl 可能无协议前缀，自动补 https:）。 */
    public static byte[] fetchImage(String url) {
        if (url != null && url.startsWith("//")) url = "https:" + url;
        if (url != null && !url.startsWith("http")) url = "https://" + url;
        for (int attempt = 0; attempt < 2; attempt++) {
            HttpURLConnection conn = null;
            try {
                URL u = new URL(url);
                conn = (HttpURLConnection) u.openConnection();
                conn.setConnectTimeout(12000);
                conn.setReadTimeout(15000);
                conn.setRequestProperty("User-Agent", Http.UA);
                conn.setRequestProperty("Referer", "https://passport.baidu.com/");
                int code = conn.getResponseCode();
                if (code != 200) continue;
                InputStream is = conn.getInputStream();
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
                if (bos.size() > 0) return bos.toByteArray();
            } catch (Exception e) {
                // retry
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
        return null;
    }
}
