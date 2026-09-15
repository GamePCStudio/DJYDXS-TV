package com.djydxs.tv;

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
        if (!CookieStore.hasBaiduLogin()) {
            out.message = "未授权百度网盘，请先到 设置→百度网盘扫码";
            return out;
        }
        if (targetDir == null || !targetDir.startsWith("/")) targetDir = "/apps/DJYDXS";
        Http.desktopUa.set(true); // 整条链路用桌面 UA
        try {
            if (!ensureDir(targetDir)) {
                out.message = "转存目录创建失败：" + targetDir;
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
                Http.Resp vr = Http.request("POST",
                        "https://pan.baidu.com/share/verify?surl=" + surl
                                + "&t=" + System.currentTimeMillis()
                                + "&channel=chunlei&web=1&bdstoken=null&clienttype=0&app_id=250528",
                        vbody, null, true);
                String errno = errnoOf(vr.body);
                if (vr.code != 200 || !"0".equals(errno)) {
                    out.message = "提取码错误或链接失效(" + errno + ")";
                    return out;
                }
                Matcher mr = Pattern.compile("randsk[^\\d]{0,8}([\\w%]+)").matcher(vr.body);
                if (mr.find()) {
                    sekey = mr.group(1); // URL 编码值，可直接放 Cookie
                }
            } else {
                // 无提取码：直接开一次分享页，若回 Set-Cookie BDCLND 也带上
                Http.get(shareUrl);
            }

            // 2) 手动拼 Cookie（BDCLND 不能依赖 CookieHandler）
            StringBuilder ck = new StringBuilder(cookie == null ? "" : cookie);
            if (sekey != null && !sekey.isEmpty()) {
                if (ck.length() > 0 && !ck.toString().endsWith("; ")) ck.append("; ");
                ck.append("BDCLND=").append(sekey);
            }
            String finalCookie = ck.toString();

            // 3) 打开分享页（桌面 UA + BDCLND）
            java.util.Map<String, String> hdrs = new java.util.HashMap<>();
            hdrs.put("Cookie", finalCookie);
            Http.Resp page = Http.request("GET", shareUrl, null, hdrs, true);
            if (page.code != 200 || page.body.isEmpty()) {
                out.message = "分享页访问失败(" + page.code + ")";
                return out;
            }
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
                out.message = "分享页无数据(可能真失效或需验证)";
                return out;
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
                    String onlyDirPath = null;
                    for (int i = 0; i < fl.length(); i++) {
                        JSONObject f = fl.optJSONObject(i);
                        if (f == null) continue;
                        long id = f.optLong("fs_id", 0);
                        if (id <= 0) continue;
                        // 根目录只有一个子目录 -> 进内层
                        if (f.optInt("isdir", 0) == 1 && fl.length() == 1) {
                            onlyDirPath = f.optString("path", "/");
                            rootIsDir = true;
                        } else {
                            ids.add(id);
                        }
                    }
                    if (rootIsDir && onlyDirPath != null) {
                        JSONArray sub = listShareDir(shareid, uk, onlyDirPath, shareUrl);
                        if (sub != null) {
                            for (int i = 0; i < sub.length(); i++) {
                                JSONObject f = sub.optJSONObject(i);
                                if (f != null && f.optLong("fs_id", 0) > 0) ids.add(f.optLong("fs_id"));
                            }
                        }
                    }
                    fsids = new long[ids.size()];
                    for (int i = 0; i < ids.size(); i++) fsids[i] = ids.get(i);
                } catch (Exception ignored) {
                }
            }
            if (fsids == null || fsids.length == 0) {
                out.message = "分享内没有可转存的文件";
                return out;
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
            String body = "fsidlist=" + enc(fsarr.toString()) + "&path=" + enc(targetDir);
            Http.Resp tr = Http.request("POST",
                    "https://pan.baidu.com/share/transfer?shareid=" + shareid
                            + "&from=" + uk + "&bdstoken=" + bdstoken
                            + "&channel=chunlei&clienttype=0&web=1&app_id=250528",
                    body, hdrs, true);
            String errno = errnoOf(tr.body);
            out.ok = "0".equals(errno);
            out.message = transferMsg(errno);
            if (out.ok) out.message += " → " + targetDir;
            return out;
        } finally {
            Http.desktopUa.set(false);
        }
    }

    private static String transferMsg(String errno) {
        if ("0".equals(errno)) return "转存成功";
        if ("12".equals(errno)) return "目标目录已有同名文件";
        if ("105".equals(errno)) return "分享链接已损坏/失效";
        if ("4".equals(errno)) return "网盘空间不足或转存次数超限";
        if ("-1".equals(errno) || errno == null || errno.isEmpty()) return "转存失败(接口无响应)";
        return "转存失败 errno=" + errno;
    }

    private static JSONArray listShareDir(String shareid, String uk, String dir, String referer) {
        Http.Resp r = Http.get("https://pan.baidu.com/share/list?shareid=" + shareid
                + "&uk=" + uk + "&root=" + ("/".equals(dir) || dir.isEmpty() ? "1" : "0")
                + "&dir=" + enc(dir) + "&clienttype=0&web=1&channel=chunlei");
        try {
            JSONObject o = new JSONObject(r.body);
            if ("0".equals(String.valueOf(o.opt("errno")))) {
                return o.optJSONArray("list");
            }
        } catch (Exception ignored) {
        }
        return null;
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
