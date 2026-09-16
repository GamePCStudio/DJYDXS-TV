package com.supermov.tv;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 简易 HTTP：带持久 Cookie jar，手动跟随 302（每一跳的 Set-Cookie 都入库）。
 * 对齐 PY 版行为：Discuz search.php 第一跳 302 会下发新 sid cookie，
 * 自动重定向模式会丢失中间跳的 Set-Cookie，导致第二跳鉴权失败 —— 必须手动跟。
 */
public final class Http {
    public static final String UA =
            "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    public static class Resp {
        public int code;
        public String body;
        public Map<String, List<String>> headers;
    }

    /** 转存链路切桌面 UA（移动 UA 会被百度导向 wap 分享页）。线程级开关。 */
    public static final ThreadLocal<Boolean> desktopUa = ThreadLocal.withInitial(() -> false);

    private Http() {}

    /** GET/POST（不自动重定向），把响应 Set-Cookie 存进 jar。 */
    private static Resp raw(String method, String url, String body,
                            Map<String, String> extraHeaders) {
        HttpURLConnection conn = null;
        try {
            URL u = new URL(url);
            conn = (HttpURLConnection) u.openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(20000);
            conn.setRequestMethod(method);
            conn.setInstanceFollowRedirects(false); // 关键：手动跟跳
            conn.setRequestProperty("User-Agent",
                    Boolean.TRUE.equals(desktopUa.get()) ? BaiduPan.DESKTOP_UA : UA);
            conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8");
            conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
            if (url.contains("pan.baidu.com")) {
                conn.setRequestProperty("Referer", "https://pan.baidu.com/");
            } else if (url.contains("4kzimu.top")) {
                conn.setRequestProperty("Referer", "https://4kzimu.top/");
            }
            String cookie = CookieStore.cookieFor(url);
            if (cookie != null && !cookie.isEmpty()) {
                conn.setRequestProperty("Cookie", cookie);
            }
            if (extraHeaders != null) {
                for (Map.Entry<String, String> e : extraHeaders.entrySet()) {
                    conn.setRequestProperty(e.getKey(), e.getValue());
                }
            }
            if (body != null) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                conn.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
            }
            int code = conn.getResponseCode();
            InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String respBody = readAll(is);
            Map<String, List<String>> headers = conn.getHeaderFields();
            CookieStore.storeFrom(url, headers);
            String reqCookie = conn.getRequestProperty("Cookie");
            String ckLog = reqCookie == null ? "none"
                    : (reqCookie.length() + "ch: " + reqCookie.substring(0, Math.min(80, reqCookie.length())) + "…");
            android.util.Log.d("SupeMov", "HTTP " + method + " " + code + " " + url
                    + " | bodyLen=" + respBody.length()
                    + " | loc=" + headerValue(headers, "location")
                    + " | ua=" + (Boolean.TRUE.equals(desktopUa.get()) ? "PC" : "MOB")
                    + " | ck=" + ckLog);
            Resp r = new Resp();
            r.code = code;
            r.body = respBody;
            r.headers = headers;
            return r;
        } catch (Exception e) {
            android.util.Log.d("SupeMov", "HTTP-ERR " + method + " " + url + " :: " + e);
            Resp r = new Resp();
            r.code = 0;
            r.body = "";
            return r;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 带手动重定向的请求：最多跟 5 跳，每跳 Set-Cookie 入 jar，Location 相对路径自动补全。 */
    public static Resp request(String method, String url, String body,
                               Map<String, String> extraHeaders, boolean followRedirects) {
        String cur = url;
        for (int hop = 0; hop < 5; hop++) {
            Resp r = raw(method, cur, body, extraHeaders);
            if (followRedirects && (r.code == 301 || r.code == 302 || r.code == 303 || r.code == 307)) {
                String loc = headerValue(r.headers, "location");
                if (loc == null || loc.isEmpty()) return r;
                cur = absUrl(cur, loc);
                // 303/302 after POST -> GET
                if (r.code == 303 || (r.code == 302 && "POST".equals(method))) {
                    method = "GET";
                    body = null;
                }
                continue;
            }
            return r;
        }
        Resp r = new Resp();
        r.code = 0;
        r.body = "";
        return r;
    }

    private static String headerValue(Map<String, List<String>> headers, String name) {
        if (headers == null) return null;
        for (Map.Entry<String, List<String>> e : headers.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(name) && e.getValue() != null && !e.getValue().isEmpty()) {
                return e.getValue().get(0);
            }
        }
        return null;
    }

    private static String absUrl(String base, String loc) {
        try {
            return new java.net.URL(new URL(base), loc).toString();
        } catch (Exception e) {
            return base;
        }
    }

    public static Resp get(String url) {
        return request("GET", url, null, null, true);
    }

    public static Resp getNoRedirect(String url) {
        return request("GET", url, null, null, false);
    }

    public static Resp post(String url, String body) {
        return request("POST", url, body, null, true);
    }

    private static String readAll(InputStream is) {
        if (is == null) return "";
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[8192];
            int n;
            while ((n = br.read(buf)) > 0) sb.append(buf, 0, n);
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
