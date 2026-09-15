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
    private BaiduPan() {}

    public static class QrSession {
        public String sign = "";
        public String qrimgUrl = "";
    }

    public static class QrPoll {
        public String status = "wait"; // wait / scanned / ok / fail
        public String message = "";
    }

    // ---------- 扫码登录 ----------

    /** 1) 取二维码：passport.baidu.com/v2/api/getqrcode */
    public static QrSession qrStart() {
        QrSession s = new QrSession();
        Http.Resp r = Http.get("https://passport.baidu.com/v2/api/getqrcode?lp=pc&qrloginfrom=skip");
        if (r.code != 200 || r.body.isEmpty()) return s;
        try {
            JSONObject o = new JSONObject(r.body);
            if (!"0".equals(String.valueOf(o.opt("errno")))) return s;
            s.sign = o.optString("sign", "");
            s.qrimgUrl = o.optString("imgurl", "");
        } catch (Exception ignored) {
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
                Http.Resp r2 = Http.request("GET",
                        "https://passport.baidu.com/v2/api/qrlogin?bduss=&qrloginfrom=pc&bdToken="
                                + java.net.URLEncoder.encode(v, "UTF-8"),
                        null, null, true);
                if (r2.code == 200 && !r2.body.isEmpty()) {
                    // 响应里带 BDUSS=xxx（qrlogin 302 到 v2/api/loginhistory 或直接 JSON）
                    Matcher m = Pattern.compile("BDUSS=([A-Za-z0-9%~_\\-]{40,})").matcher(r2.body);
                    if (m.find()) {
                        String bduss = java.net.URLDecoder.decode(m.group(1), "UTF-8");
                        CookieStore.put("https://pan.baidu.com", "BDUSS", bduss);
                        CookieStore.put("https://passport.baidu.com", "BDUSS", bduss);
                        // STOKEN 通常在 qrlogin 的 Set-Cookie 里，兜底从 cookie store 取
                        String stoken = CookieStore.get("https://passport.baidu.com", "STOKEN");
                        if (stoken == null || stoken.isEmpty()) {
                            stoken = CookieStore.get("https://pan.baidu.com", "STOKEN");
                        }
                        if (stoken != null && !stoken.isEmpty()) {
                            CookieStore.put("https://pan.baidu.com", "STOKEN", stoken);
                        }
                        out.status = "ok";
                        out.message = "授权成功";
                        return out;
                    }
                    // JSON 形式 {"bduss":"..."}
                    Matcher m2 = Pattern.compile("\"bduss\"\\s*:\\s*\"([A-Za-z0-9~_\\-]{40,})\"").matcher(r2.body);
                    if (m2.find()) {
                        CookieStore.put("https://pan.baidu.com", "BDUSS", m2.group(1));
                        out.status = "ok";
                        out.message = "授权成功";
                        return out;
                    }
                }
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
        // 1) 确保目录存在
        if (!ensureDir(targetDir)) {
            out.message = "转存目录创建失败：" + targetDir;
            return out;
        }
        // 2) 解析 surl
        String surl = resolveSurl(shareUrl);
        if (surl.isEmpty()) {
            out.message = "分享链接格式无法识别";
            return out;
        }
        // 3) 直接打开分享页，尝试拿 yunData
        String sekey = "";
        Http.Resp open = Http.get(shareUrl);
        if (open.code == 200) {
            sekey = CookieStore.get(shareUrl, "BDCLND");
            if (sekey == null) sekey = "";
        }
        // 4) 需要提取码：share/verify 换 BDCLND
        if ((sekey == null || sekey.isEmpty()) && pwd != null && !pwd.isEmpty()) {
            String verifyBody = "pwd=" + enc(pwd) + "&vcode=&vcode_str=";
            Http.Resp vr = Http.request("POST",
                    "https://pan.baidu.com/share/verify?surl=" + surl
                            + "&t=" + System.currentTimeMillis()
                            + "&channel=chunlei&web=1&bdstoken=null&clienttype=0&app_id=250528",
                    verifyBody, null, true);
            String errno = errnoOf(vr.body);
            if (vr.code != 200 || !"0".equals(errno)) {
                out.message = "提取码错误或链接失效(" + errno + ")";
                return out;
            }
            String ck = CookieStore.get(shareUrl, "BDCLND");
            sekey = ck == null ? "" : ck;
        }
        // 5) 带 BDCLND 访问分享页拿 yunData
        if (sekey != null && !sekey.isEmpty()) {
            CookieStore.put(shareUrl, "BDCLND", sekey);
        }
        Http.Resp page = Http.get(shareUrl);
        if (page.code != 200 || page.body.isEmpty()) {
            out.message = "分享页访问失败";
            return out;
        }
        if (page.body.contains("分享的文件已经被取消") || page.body.contains("已失效")) {
            out.message = "分享链接已失效";
            return out;
        }
        Matcher my = Pattern.compile("yunData\\.setData\\((\\{.*?\\})\\);", Pattern.DOTALL).matcher(page.body);
        if (!my.find()) {
            out.message = "分享页解析失败(可能需在APP确认或链接失效)";
            return out;
        }
        try {
            JSONObject yd = new JSONObject(my.group(1));
            String shareid = yd.optString("shareid", "");
            String uk = yd.optString("share_uk", yd.optString("uk", ""));
            String bdstoken = yd.optString("bdstoken", "");
            if (bdstoken.isEmpty()) bdstoken = getBdstoken();
            if (shareid.isEmpty() || uk.isEmpty()) {
                out.message = "分享信息缺少 shareid/uk";
                return out;
            }
            JSONArray fileList = yd.optJSONArray("file_list");
            if (fileList == null) {
                JSONObject flObj = yd.optJSONObject("file_list");
                fileList = flObj == null ? null : flObj.optJSONArray("list");
            }
            if (fileList == null || fileList.length() == 0) {
                out.message = "分享里没有文件";
                return out;
            }
            // 根目录只有一个子目录：进入内层转存
            String transferFrom = "/";
            if (fileList.length() == 1) {
                JSONObject f0 = fileList.optJSONObject(0);
                if (f0 != null && f0.optInt("isdir", 0) == 1) {
                    String subPath = f0.optString("path", "");
                    if (!subPath.isEmpty()) {
                        transferFrom = subPath;
                        fileList = listShareDir(shareid, uk, subPath, shareUrl);
                        if (fileList == null || fileList.length() == 0) {
                            out.message = "分享目录为空";
                            return out;
                        }
                    }
                }
            }
            // 6) 收集 fs_id
            StringBuilder fsids = new StringBuilder("[");
            int cnt = 0;
            for (int i = 0; i < fileList.length(); i++) {
                JSONObject f = fileList.optJSONObject(i);
                if (f == null) continue;
                long fsId = f.optLong("fs_id", 0);
                if (fsId <= 0) continue;
                if (cnt > 0) fsids.append(",");
                fsids.append(fsId);
                cnt++;
            }
            fsids.append("]");
            if (cnt == 0) {
                out.message = "分享内没有可转存的文件";
                return out;
            }
            // 7) share/transfer
            String body = "fsidlist=" + enc(fsids.toString()) + "&path=" + enc(targetDir);
            Http.Resp tr = Http.request("POST",
                    "https://pan.baidu.com/share/transfer?shareid=" + shareid
                            + "&from=" + uk + "&bdstoken=" + bdstoken
                            + "&channel=chunlei&clienttype=0&web=1&app_id=250528",
                    body, null, true);
            String errno = errnoOf(tr.body);
            out.ok = "0".equals(errno);
            out.message = transferMsg(errno);
            if (out.ok) out.message += " → " + targetDir;
            return out;
        } catch (Exception e) {
            out.message = "转存异常：" + e.getClass().getSimpleName();
            return out;
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
        // 已存在？
        Http.Resp list = Http.get("https://pan.baidu.com/api/list?clienttype=0&app_id=250528&web=1&dir=" + enc(p));
        if (list.code == 200 && list.body.replace(" ", "").contains("\"errno\":0")) return true;
        // 创建
        Http.Resp cr = Http.request("POST",
                "https://pan.baidu.com/api/create?clienttype=0&app_id=250528&web=1",
                "path=" + enc(p) + "&isdir=1&block_list=%5B%5D", null, true);
        return cr.code == 200 && cr.body.replace(" ", "").contains("\"errno\":0");
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

    /** 下载二维码图片字节。 */
    public static byte[] fetchImage(String url) {
        HttpURLConnection conn = null;
        try {
            URL u = new URL(url);
            conn = (HttpURLConnection) u.openConnection();
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("User-Agent", Http.UA);
            String ck = CookieStore.cookieFor(url);
            if (ck != null && !ck.isEmpty()) {
                conn.setRequestProperty("Cookie", ck);
            }
            InputStream is = conn.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
