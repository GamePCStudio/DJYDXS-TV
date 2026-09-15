package com.djydxs.tv;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 持久化 Cookie jar：论坛 Cookie + 百度 BDUSS/STOKEN 等。 */
public final class CookieStore {
    private static final Map<String, Map<String, String>> JAR = new HashMap<>();
    private static SharedPreferences prefs;

    private CookieStore() {}

    public static void init(Context ctx) {
        if (prefs == null) {
            prefs = ctx.getApplicationContext()
                    .getSharedPreferences("djydxs_cookies", Context.MODE_PRIVATE);
        }
    }

    private static Map<String, String> bucket(String host) {
        Map<String, String> b = JAR.get(host);
        if (b == null) {
            b = new HashMap<>();
            JAR.put(host, b);
            if (prefs != null) {
                String saved = prefs.getString("ck_" + host, null);
                if (saved != null) {
                    for (String kv : saved.split("; ")) {
                        int i = kv.indexOf('=');
                        if (i > 0) b.put(kv.substring(0, i), kv.substring(i + 1));
                    }
                }
            }
        }
        return b;
    }

    /** 从 Set-Cookie 响应头存储。 */
    public static void storeFrom(String url, Map<String, List<String>> headers) {
        if (headers == null) return;
        String host = hostOf(url);
        if (host == null) return;
        List<String> setCookies = headers.get("set-cookie");
        if (setCookies == null) setCookies = headers.get("Set-Cookie");
        if (setCookies == null) return;
        boolean changed = false;
        synchronized (JAR) {
            Map<String, String> b = bucket(host);
            for (String sc : setCookies) {
                String first = sc.split(";", 2)[0];
                int i = first.indexOf('=');
                if (i <= 0) continue;
                String k = first.substring(0, i).trim();
                String v = first.substring(i + 1).trim();
                // 空值=过期删除
                if (v.isEmpty()) {
                    b.remove(k);
                } else {
                    b.put(k, v);
                }
                changed = true;
            }
            if (changed && prefs != null) {
                StringBuilder sb = new StringBuilder();
                for (Map.Entry<String, String> e : b.entrySet()) {
                    sb.append(e.getKey()).append('=').append(e.getValue()).append("; ");
                }
                prefs.edit().putString("ck_" + host, sb.toString()).apply();
            }
        }
    }

    /** 取请求用 Cookie 头。 */
    public static String cookieFor(String url) {
        String host = hostOf(url);
        if (host == null) return "";
        synchronized (JAR) {
            Map<String, String> b = bucket(host);
            List<String> parts = new ArrayList<>();
            // BDUSS/STOKEN 放最前
            for (String pri : new String[]{"BDUSS", "STOKEN", "BDCLND"}) {
                String v = b.get(pri);
                if (v != null) parts.add(pri + "=" + v);
            }
            for (Map.Entry<String, String> e : b.entrySet()) {
                if (!priContains(e.getKey())) parts.add(e.getKey() + "=" + e.getValue());
            }
            return join(parts, "; ");
        }
    }

    private static boolean priContains(String k) {
        return "BDUSS".equals(k) || "STOKEN".equals(k) || "BDCLND".equals(k);
    }

    private static String join(List<String> parts, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) sb.append(sep);
            sb.append(parts.get(i));
        }
        return sb.toString();
    }

    /** 手动塞 cookie（扫码成功后写入 BDUSS/STOKEN）。 */
    public static void put(String url, String name, String value) {
        String host = hostOf(url);
        if (host == null) return;
        synchronized (JAR) {
            Map<String, String> b = bucket(host);
            if (value == null || value.isEmpty()) b.remove(name);
            else b.put(name, value);
            if (prefs != null) {
                StringBuilder sb = new StringBuilder();
                for (Map.Entry<String, String> e : b.entrySet()) {
                    sb.append(e.getKey()).append('=').append(e.getValue()).append("; ");
                }
                prefs.edit().putString("ck_" + host, sb.toString()).apply();
            }
        }
    }

    public static String get(String url, String name) {
        String host = hostOf(url);
        if (host == null) return "";
        synchronized (JAR) {
            return bucket(host).get(name);
        }
    }

    public static boolean hasBaiduLogin() {
        String v = get("https://pan.baidu.com", "BDUSS");
        return v != null && v.length() > 40;
    }

    public static void clearBaidu() {
        put("https://pan.baidu.com", "BDUSS", null);
        put("https://pan.baidu.com", "STOKEN", null);
        put("https://passport.baidu.com", "BDUSS", null);
        put("https://passport.baidu.com", "STOKEN", null);
    }

    private static String hostOf(String url) {
        try {
            return new java.net.URL(url).getHost();
        } catch (Exception e) {
            return null;
        }
    }
}
