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
    }

    /** 一键转存：分享链接(+提取码) -> targetDir。 */
    public static TransferResult transfer(String shareUrl, String pwd, String targetDir) {
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
        if (targetDir == null || !targetDir.startsWith("/")) targetDir = "/超级影库";
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
            // bdstoken 优先从分享页 yunData 里提取（无登录态也带），失败才走 gettemplatevariable
            String bdstoken = "";
            Matcher mbt = Pattern.compile("bdstoken[^a-f0-9]{0,6}([a-f0-9]{32})").matcher(html);
            if (mbt.find()) bdstoken = mbt.group(1);
            if (bdstoken.isEmpty()) bdstoken = getBdstoken();

            // 5) file_list：独立 JSON 块 "file_list":[{...}]
            long[] fsids = null;
            boolean rootIsDir = false;
            String flJson = extractFileListJson(html);
            if (flJson != null) {
                try {
                    JSONArray fl = new JSONArray(flJson);
                    List<Long> ids = new ArrayList<>();
                    for (int i = 0; i < fl.length(); i++) {
                        JSONObject f = fl.optJSONObject(i);
                        if (f == null) continue;
                        long id = f.optLong("fs_id", 0);
                        if (id <= 0) continue;
                        ids.add(id);
                        // 目录项原样保留：转存目录项 = 连目录带内容一起复制（结构原样）
                    }
                    fsids = new long[ids.size()];
                    for (int i = 0; i < ids.size(); i++) fsids[i] = ids.get(i);
                } catch (Exception ignored) {
                }
            }
            android.util.Log.d("SupeMov", "transfer: fsids=" + (fsids == null ? "null" : fsids.length) + " shareid=" + shareid + " uk=" + uk);
            if (fsids == null || fsids.length == 0) {
                // 兜底：页面 file_list 不可用时，走 share/list 接口拉根目录
                JSONArray rootList = listShareDir(shareid, uk, "/", shareUrl);
                List<Long> ids2 = new ArrayList<>();
                if (rootList != null) {
                    for (int i = 0; i < rootList.length(); i++) {
                        JSONObject f = rootList.optJSONObject(i);
                        if (f != null && f.optLong("fs_id", 0) > 0) ids2.add(f.optLong("fs_id"));
                    }
                }
                if (ids2.isEmpty()) {
                    out.message = "没有文件[页长" + html.length()
                            + " yun=" + html.contains("yunData")
                            + " fl=" + html.contains("fs_id")
                            + " 验证=" + html.contains("安全验证") + "]";
                    return out;
                }
                fsids = new long[ids2.size()];
                for (int i = 0; i < ids2.size(); i++) fsids[i] = ids2.get(i);
            }
            StringBuilder fsarr = new StringBuilder("[");
            for (int i = 0; i < fsids.length; i++) {
                if (i > 0) fsarr.append(",");
                fsarr.append(fsids[i]);
            }
            fsarr.append("]");

            // 6) share/transfer（带 BDCLND cookie）
            hdrs.put("Cookie", finalCookie);
            hdrs.put("X-Requested-With", "XMLHttpRequest");
            hdrs.put("Origin", "https://pan.baidu.com");
            String bdussT = CookieStore.get("https://pan.baidu.com", "BDUSS");
            String ckT = (hdrs.get("Cookie") == null ? "" : hdrs.get("Cookie") + "; ")
                    + "BDUSS=" + (bdussT == null ? "" : bdussT);
            hdrs.put("Cookie", ckT);
            String body = "fsidlist=" + enc(fsarr.toString()) + "&path=" + enc(targetDir);
            Http.Resp tr = Http.request("POST",
                    "https://pan.baidu.com/share/transfer?shareid=" + shareid
                            + "&from=" + uk + "&bdstoken=" + bdstoken
                            + "&channel=chunlei&clienttype=0&web=1&app_id=250528",
                    body, hdrs, true);
            String errno = errnoOf(tr.body);
            android.util.Log.d("SupeMov", "transfer: final errno=" + errno + " body=" + tr.body.substring(0, Math.min(200, tr.body.length())));
            out.ok = "0".equals(errno);
            if (out.ok) {
                out.message = "转存成功 → " + targetDir;
                return out;
            }
            // errno=12 目标目录已有同名文件：自动顺延到 目录(2) (3) … 最多试到 (5)
            if ("12".equals(errno)) {
                for (int n = 2; n <= 5; n++) {
                    String alt = targetDir.replaceAll("/$", "") + " (" + n + ")";
                    if (!ensureDir(alt)) continue;
                    String body2 = "fsidlist=" + enc(fsarr.toString()) + "&path=" + enc(alt);
                    Http.Resp tr2 = Http.request("POST",
                            "https://pan.baidu.com/share/transfer?shareid=" + shareid
                                    + "&from=" + uk + "&bdstoken=" + bdstoken
                                    + "&channel=chunlei&clienttype=0&web=1&app_id=250528",
                            body2, hdrs, true);
                    String errno2 = errnoOf(tr2.body);
                    if ("0".equals(errno2)) {
                        out.ok = true;
                        out.message = "目标目录已有同名影片，已转存到 → " + alt;
                        return out;
                    }
                    if (!"12".equals(errno2)) {
                        out.message = transferMsg(errno2);
                        return out;
                    }
                }
                out.message = "目标目录已存在同名影片，且 (2)~(5) 目录都被占用；请在网盘清理后重试";
                return out;
            }
            out.message = transferMsg(errno);
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
            // 转存目录可能因同名被顺延为 目录(2)..(5)，依次找一遍
            String[] dirs = {
                    rootDir,
                    trimSlash(rootDir) + " (2)", trimSlash(rootDir) + " (3)",
                    trimSlash(rootDir) + " (4)", trimSlash(rootDir) + " (5)"
            };
            String key = normName(keyword);
            JSONObject pick = null;
            for (String dir : dirs) {
                JSONArray list = listDir(dir);
                if (list == null) continue; // 目录不存在
                JSONObject newest = null;
                long newestTime = -1;
                for (int i = 0; i < list.length(); i++) {
                    JSONObject f = list.optJSONObject(i);
                    if (f == null) continue;
                    if (f.optLong("fs_id", 0) <= 0) continue;
                    long mt = f.optLong("server_mtime", 0);
                    if (mt > newestTime) {
                        newestTime = mt;
                        newest = f;
                    }
                    if (pick == null && !key.isEmpty()) {
                        String nm = normName(f.optString("server_filename", ""));
                        if (!nm.isEmpty() && (nm.contains(key) || key.contains(nm))) pick = f;
                    }
                }
                // 没找到同名项时，仅当目录里最新一项是「刚刚转存的」（10 分钟内）才认，
                // 否则宁可报错也不能播错片子
                if (pick == null && newest != null
                        && System.currentTimeMillis() / 1000 - newestTime < 600) {
                    pick = newest;
                }
                if (pick != null) break;
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
            JSONObject o = new JSONObject(r.body);
            String[] keys = {"m3u8_url", "m3u8", "url", "dlink"};
            for (String k : keys) {
                String v = o.optString(k, "");
                if (!v.isEmpty() && v.startsWith("http")) {
                    android.util.Log.d("SupeMov", "streaming(" + type + ") key=" + k);
                    return v;
                }
            }
            android.util.Log.d("SupeMov", "streaming(" + type + ") errno=" + o.opt("errno")
                    + " keys=" + o.keys());
            return "";
        } catch (Throwable e) {
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
        if ("4".equals(errno)) return "网盘空间不足或非会员转存次数已超限";
        if ("-6".equals(errno)) return "百度登录态失效，请重新扫码授权";
        if ("-70".equals(errno)) return "文件存在安全风险，百度拒绝转存";
        if ("-30".equals(errno)) return "文件已失效或被百度屏蔽";
        if ("-1".equals(errno) || errno == null || errno.isEmpty()) return "转存接口无响应(网络/风控)";
        return "转存失败 errno=" + errno;
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
