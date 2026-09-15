package com.djydxs.tv;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** 简易 HTTP：带 Cookie jar，解析 Set-Cookie，统一 UA。 */
public final class Http {
    public static final String UA =
            "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    public static class Resp {
        public int code;
        public String body;
        public Map<String, List<String>> headers;
    }

    private Http() {}

    /** GET/POST，自动带上 CookieStore 里对应 host 的 cookie，并把响应 Set-Cookie 存回去。 */
    public static Resp request(String method, String url, String body,
                               Map<String, String> extraHeaders, boolean followRedirects) {
        HttpURLConnection conn = null;
        try {
            URL u = new URL(url);
            conn = (HttpURLConnection) u.openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(20000);
            conn.setRequestMethod(method);
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
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
            conn.setInstanceFollowRedirects(followRedirects);
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
            Resp r = new Resp();
            r.code = code;
            r.body = respBody;
            r.headers = headers;
            return r;
        } catch (Exception e) {
            Resp r = new Resp();
            r.code = 0;
            r.body = "";
            return r;
        } finally {
            if (conn != null) conn.disconnect();
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
