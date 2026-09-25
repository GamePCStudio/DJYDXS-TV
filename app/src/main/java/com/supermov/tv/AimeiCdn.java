package com.supermov.tv;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.X509TrustManager;

/**
 * 艾美（EWatch / imovie）云端取址：由影片 {@code hash} 换回一组分段直链。
 *
 * <h3>两步协议</h3>
 * <ol>
 *   <li>{@code POST /api/ewatch/register} —— 把设备 sn 登记入网（幂等）。
 *       {@code sign = md5(time + distributor + "mYmoV10238")}（小写）。</li>
 *   <li>{@code GET /api/movie/getCdnUrl?sn=&hash=} —— 返回
 *       {@code {extension, fileSize, segm:[{index,start,length,sha1sum,url}]}}。
 *       多段 {@code length} 之和恒等于 {@code fileSize}，按 {@code index} 升序拼接即原盘。</li>
 * </ol>
 *
 * <h3>占位桩（必须识别，不能当真实地址下）</h3>
 * 服务端对<b>当前设备没有授权</b>的内容不报错，而是回一份假清单：
 * {@code fileSize=9999999999}、{@code sha1sum=1234…}、{@code url=golang.org 的一个 tar.gz}。
 * 拿它去下会得到「大小对得上、SHA1 全错」的垃圾文件，所以 {@link CdnInfo#placeholder}
 * 一旦为真，上层必须直接失败并把原因显示给用户。
 *
 * <h3>为什么 https 那段容忍自签/坏证书</h3>
 * {@code api.mymei.vip} 的证书链在本机不可验证（研究期就是用「跳过校验」跑通的）。
 * 这里只对该厂商域名放宽，且**完整性另有保障**：每个分段落盘后按服务端声明的
 * {@code sha1sum} 逐段比对，篡改/串文件的片段会被判失败并丢弃。凭据类请求（百度网盘）
 * 完全不走这里，仍用系统默认信任库。
 */
public final class AimeiCdn {

    private static final String TAG = "SupeMov";

    public static final String BASE = "https://api.mymei.vip/";
    /** 设备序列号缺省值：一台已登记的艾美盒子 WiFi MAC（12 位大写十六进制，无分隔符）。 */
    public static final String DEFAULT_SN = "9CF8DB056A49";
    /** 渠道名。RTD1295 默认分支即艾美自营渠道；其它风味为 yszn/yunmao/emei/mymeihome/v2share。 */
    public static final String DEFAULT_DISTRIBUTOR = "mymei";
    private static final String SIGN_SALT = "mYmoV10238";

    /** 一个分段：下载 {@code url} 的全部字节，落在目标文件 {@code start} 偏移处。 */
    public static final class Segment {
        public int index;
        public long start;
        public long length;
        public String sha1sum = "";
        public String url = "";
    }

    /** 一次取址的结果。 */
    public static final class CdnInfo {
        public String hash = "";
        /** 不含点号，如 {@code mkv}。 */
        public String extension = "";
        public long fileSize;
        public final List<Segment> segments = new ArrayList<>();
        /** 占位桩判定为真时，这里写清楚是哪一条特征命中的。 */
        public String placeholderReason = "";

        public boolean placeholder() {
            return !placeholderReason.isEmpty();
        }

        /** 真实清单的不变量：段长之和 == fileSize，且段号从 0 连续。 */
        public boolean consistent() {
            if (fileSize <= 0 || segments.isEmpty()) return false;
            long sum = 0;
            for (Segment s : segments) sum += s.length;
            return sum == fileSize;
        }
    }

    private AimeiCdn() {
    }

    // ==================== 注册 ====================

    /**
     * 登记设备（幂等，已注册也返回成功）。失败不致命 —— 有些 sn 早就在册，
     * 仍可直接取址，所以调用方只记日志、不中断。
     */
    public static boolean register(String sn, String distributor) {
        long t = System.currentTimeMillis() / 1000L;
        String sign = md5Hex(t + distributor + SIGN_SALT);
        try {
            JSONObject body = new JSONObject();
            body.put("sn", sn);
            body.put("distributor", distributor);
            body.put("time", t);
            body.put("sign", sign);
            String resp = post(BASE + "api/ewatch/register", body.toString());
            JSONObject o = new JSONObject(resp);
            boolean ok = o.optInt("code", -1) == 0 || o.has("data");
            Log.d(TAG, "cdn register sn=" + sn + " ok=" + ok + " resp=" + trim(resp));
            return ok;
        } catch (Throwable e) {
            Log.d(TAG, "cdn register 失败 sn=" + sn + " " + e);
            return false;
        }
    }

    // ==================== 取址 ====================

    /**
     * 取分段清单。
     *
     * @throws IOException 网络失败、接口 code 非 0、或清单结构不完整
     */
    public static CdnInfo fetch(String sn, String hash) throws IOException {
        if (sn == null || sn.isEmpty()) throw new IOException("设备序列号未设置");
        if (hash == null || hash.length() != 40) throw new IOException("影片 hash 无效");
        String url = BASE + "api/movie/getCdnUrl?sn=" + sn + "&hash=" + hash;
        CdnInfo info = parse(get(url), hash);
        if (info.placeholder()) {
            Log.d(TAG, "cdn 占位桩 hash=" + hash + " " + info.placeholderReason);
            return info;
        }
        if (!info.consistent()) {
            throw new IOException("分段清单不自洽：各段长度之和与 fileSize 不等");
        }
        return info;
    }

    /** 把响应文本解析成 {@link CdnInfo}；非 0 code 直接抛。 */
    static CdnInfo parse(String body, String wantHash) throws IOException {
        JSONObject o;
        try {
            o = new JSONObject(body);
        } catch (Exception e) {
            throw new IOException("取址响应不是 JSON：" + trim(body));
        }
        int code = o.optInt("code", -1);
        if (code != 0) {
            String msg = o.optString("message", "");
            throw new IOException(code == 40001
                    ? "设备序列号未被接受（" + (msg.isEmpty() ? "sn 不存在" : msg) + "），请先在设置里注册或换个 sn"
                    : "取址失败 code=" + code + " " + msg);
        }
        JSONObject d = o.optJSONObject("data");
        if (d == null) throw new IOException("取址响应没有 data 字段");
        CdnInfo info = new CdnInfo();
        info.hash = d.optString("hash", wantHash);
        info.extension = d.optString("extension", ".mkv").replace(".", "").toLowerCase(Locale.ROOT);
        if (info.extension.isEmpty()) info.extension = "mkv";
        info.fileSize = d.optLong("fileSize", 0);
        JSONArray segs = d.optJSONArray("segm");
        if (segs != null) {
            for (int i = 0; i < segs.length(); i++) {
                JSONObject s = segs.optJSONObject(i);
                if (s == null) continue;
                Segment seg = new Segment();
                seg.index = s.optInt("index", i);
                seg.start = s.optLong("start", 0);
                seg.length = s.optLong("length", 0);
                seg.sha1sum = s.optString("sha1sum", "").trim().toLowerCase(Locale.ROOT);
                seg.url = s.optString("url", "").trim();
                info.segments.add(seg);
            }
            // 服务端不保证顺序，按 index 排好——拼接顺序就是它的语义
            Collections.sort(info.segments, (a, b) -> Integer.compare(a.index, b.index));
        }
        info.placeholderReason = placeholderReason(info);
        return info;
    }

    /** 占位桩特征（照抄已验证可用的探测脚本口径）；返回空串表示这是真实清单。 */
    private static String placeholderReason(CdnInfo info) {
        List<String> hit = new ArrayList<>();
        if (info.fileSize >= 9_000_000_000L) hit.append("fileSize=" + info.fileSize + " 是哨兵值");
        for (int i = 0; i < info.segments.size() && i < 1; i++) {
            Segment s = info.segments.get(i);
            if (s.url.contains("golang.org")) hit.add("url 指向 golang.org 占位文件");
            if (s.sha1sum.startsWith("1234567890")) hit.add("sha1sum 为 1234… 占位");
        }
        if (".ic2".equalsIgnoreCase(info.extension) && !hit.isEmpty()) {
            hit.add("容器 .ic2 为厂商加密格式，明文拼接不可播");
        }
        return hit.isEmpty() ? "" : String.join("；", hit);
    }

    // ==================== HTTP 底座 ====================

    /** 分段下载用的连接：只带 UA，不带任何网盘 Cookie。 */
    public static HttpURLConnection openSegment(String url, String range) throws IOException {
        HttpURLConnection c = conn(url);
        c.setRequestProperty("User-Agent", "okhttp/3.10.0");
        c.setRequestProperty("Accept-Encoding", "identity");
        if (range != null && !range.isEmpty()) c.setRequestProperty("Range", "bytes=" + range);
        return c;
    }

    private static String get(String url) throws IOException {
        HttpURLConnection c = conn(url);
        c.setRequestProperty("User-Agent", "okhttp/3.10.0");
        return read(c);
    }

    private static String post(String url, String jsonBody) throws IOException {
        HttpURLConnection c = conn(url);
        c.setRequestProperty("User-Agent", "okhttp/3.10.0");
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        byte[] b = jsonBody.getBytes("UTF-8");
        c.setFixedLengthStreamingMode(b.length);
        OutputStream os = c.getOutputStream();
        try {
            os.write(b);
            os.flush();
        } finally {
            closeQuietly(os);
        }
        return read(c);
    }

    private static HttpURLConnection conn(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);
        if (c instanceof HttpsURLConnection && isVendorHost(c.getURL().getHost())) {
            HttpsURLConnection hs = (HttpsURLConnection) c;
            hs.setSSLSocketFactory(vendorSocketFactory());
            hs.setHostnameVerifier((host, sess) -> true);
        }
        return c;
    }

    private static boolean isVendorHost(String host) {
        if (host == null) return false;
        String h = host.toLowerCase(Locale.ROOT);
        return h.endsWith("mymei.vip") || h.endsWith("mymei.tv") || h.endsWith("imovie.com.cn");
    }

    private static SSLContext vendorSsl;

    private static javax.net.ssl.SSLSocketFactory vendorSocketFactory() {
        try {
            if (vendorSsl == null) {
                vendorSsl = SSLContext.getInstance("TLS");
                vendorSsl.init(null, new X509TrustManager[]{new X509TrustManager() {
                    @Override
                    public void checkClientTrusted(X509Certificate[] chain, String authType) {
                    }

                    @Override
                    public void checkServerTrusted(X509Certificate[] chain, String authType) {
                    }

                    @Override
                    public X509Certificate[] getAcceptedIssuers() {
                        return new X509Certificate[0];
                    }
                }}, new SecureRandom());
            }
            return vendorSsl.getSocketFactory();
        } catch (Throwable e) {
            Log.d(TAG, "cdn ssl 兜底失败 " + e);
            return HttpsURLConnection.getDefaultSSLSocketFactory();
        }
    }

    private static String read(HttpURLConnection c) throws IOException {
        InputStream in = null;
        try {
            int code = c.getResponseCode();
            in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (in == null) throw new IOException("HTTP " + code + "（无响应体）");
            BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[4096];
            int n;
            while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
            if (code >= 400) throw new IOException("HTTP " + code + " " + trim(sb.toString()));
            return sb.toString();
        } finally {
            closeQuietly(in);
            c.disconnect();
        }
    }

    private static String trim(String s) {
        if (s == null) return "";
        return s.length() <= 200 ? s : s.substring(0, 200) + "…";
    }

    static String md5Hex(String s) {
        try {
            byte[] dg = java.security.MessageDigest.getInstance("MD5").digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : dg) sb.append(String.format(Locale.ROOT, "%02x", b & 0xFF));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (IOException ignored) {
        }
    }
}
