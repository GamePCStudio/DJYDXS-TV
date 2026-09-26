package com.supermov.tv;

import android.util.Log;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 本地 HTTP 代理（剧库同款思路，只服务百度一家）。
 *
 * 作用：给播放器一个固定的 127.0.0.1 地址，由代理去请求百度 dlink，并统一补上头：
 *   - User-Agent: netdisk（百度 dlink 不带它会被 403 或强制限速）
 *   - Cookie: BDUSS/STOKEN（部分文件需要登录态）
 *   - Referer: https://pan.baidu.com/
 * 同时自己处理 302（dlink 会跳到 baidupcs.com CDN，跳转后仍必须带 UA）与 Range 透传，
 * 播放器侧因此不需要任何自定义 header 能力，拖进度/续播都正常。
 */
public final class LocalProxy {

    private static final String TAG = "SupeMov";
    /** 百度直链必须的 UA：官方客户端标识，缺失直接 403 */
    private static final String NETDISK_UA = "netdisk";
    private static final int MAX_REDIRECT = 6;

    private ServerSocket server;
    private Thread acceptThread;
    private volatile boolean running;
    private int port;

    public synchronized int start() throws IOException {
        if (running) return port;
        // 只监听回环地址，端口交给系统分配，避免与其它应用冲突
        server = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
        port = server.getLocalPort();
        running = true;
        acceptThread = new Thread(this::acceptLoop, "bd-proxy-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        Log.d(TAG, "proxy: started on 127.0.0.1:" + port);
        return port;
    }

    public synchronized void stop() {
        running = false;
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
        }
        server = null;
        acceptThread = null;
    }

    public int port() {
        return port;
    }

    /** 把真实直链包成播放器可用的本地地址。 */
    public String urlFor(String target) {
        try {
            return "http://127.0.0.1:" + port + "/p?u=" + URLEncoder.encode(target, "UTF-8");
        } catch (Exception e) {
            return target;
        }
    }

    private void acceptLoop() {
        while (running) {
            Socket s = null;
            try {
                s = server.accept();
            } catch (IOException e) {
                if (!running) return;
                continue;
            }
            final Socket sock = s;
            Thread t = new Thread(() -> handle(sock), "bd-proxy-conn");
            t.setDaemon(true);
            t.start();
        }
    }

    private void handle(Socket sock) {
        HttpURLConnection conn = null;
        try {
            sock.setTcpNoDelay(true);
            InputStream in = sock.getInputStream();
            String head = readHead(in);
            if (head.isEmpty()) return;
            String[] lines = head.split("\r\n");
            String[] first = lines[0].split(" ");
            if (first.length < 2) return;
            String method = first[0];
            String path = first[1];
            Map<String, String> reqHeaders = new HashMap<>();
            for (int i = 1; i < lines.length; i++) {
                int c = lines[i].indexOf(':');
                if (c > 0) {
                    reqHeaders.put(lines[i].substring(0, c).trim().toLowerCase(),
                            lines[i].substring(c + 1).trim());
                }
            }
            String target = extractTarget(path);
            if (target == null || target.isEmpty()) {
                writeSimple(sock, 400, "Bad Request");
                return;
            }
            String range = reqHeaders.get("range");
            conn = openFollowRedirect(target, range, "HEAD".equalsIgnoreCase(method));
            int code = conn.getResponseCode();
            Log.d(TAG, "proxy: " + method + " code=" + code
                    + " range=" + (range == null ? "-" : range)
                    + " len=" + conn.getContentLength());
            if (code >= 400) {
                writeSimple(sock, code, "Upstream Error");
                return;
            }
            OutputStream raw = sock.getOutputStream();
            BufferedOutputStream out = new BufferedOutputStream(raw, 64 * 1024);
            StringBuilder h = new StringBuilder();
            h.append("HTTP/1.1 ").append(code == 206 ? "206 Partial Content"
                    : code == 200 ? "200 OK" : code + " OK").append("\r\n");
            copyHeader(conn, h, "Content-Type");
            copyHeader(conn, h, "Content-Length");
            copyHeader(conn, h, "Content-Range");
            copyHeader(conn, h, "Accept-Ranges");
            copyHeader(conn, h, "ETag");
            copyHeader(conn, h, "Last-Modified");
            h.append("Connection: close\r\n\r\n");
            out.write(h.toString().getBytes("ISO-8859-1"));
            if ("HEAD".equalsIgnoreCase(method)) {
                out.flush();
                return;
            }
            InputStream body = conn.getInputStream();
            byte[] buf = new byte[128 * 1024];
            long total = 0;
            int n;
            while ((n = body.read(buf)) > 0) {
                out.write(buf, 0, n);
                out.flush(); // 边下边播：必须及时 flush，否则播放器收不到数据
                total += n;
            }
            Log.d(TAG, "proxy: done bytes=" + total);
        } catch (Throwable e) {
            // 播放器切源/退出会直接断开连接，属正常，不打堆栈
            Log.d(TAG, "proxy: conn end " + e.getClass().getSimpleName());
        } finally {
            if (conn != null) conn.disconnect();
            try {
                sock.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** 请求 dlink，自己跟随 302（每次跳转都要重新补 UA，HttpURLConnection 的自动跳转会丢头）。 */
    private HttpURLConnection openFollowRedirect(String url, String range, boolean head) throws IOException {
        String cur = url;
        String cookie = CookieStore.cookieFor("https://pan.baidu.com/");
        for (int i = 0; i <= MAX_REDIRECT; i++) {
            HttpURLConnection c = (HttpURLConnection) new URL(cur).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(15000);
            c.setReadTimeout(25000);
            c.setRequestMethod(head ? "HEAD" : "GET");
            c.setRequestProperty("User-Agent", NETDISK_UA);
            c.setRequestProperty("Referer", "https://pan.baidu.com/");
            if (cookie != null && !cookie.isEmpty()) c.setRequestProperty("Cookie", cookie);
            if (range != null && !range.isEmpty()) c.setRequestProperty("Range", range);
            int code = c.getResponseCode();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String loc = c.getHeaderField("Location");
                if (loc == null || loc.isEmpty()) return c; // 没给跳转目标，当最终响应处理
                c.disconnect();
                if (loc.startsWith("/")) {
                    URL base = new URL(cur);
                    loc = base.getProtocol() + "://" + base.getHost() + loc;
                }
                cur = loc;
                continue;
            }
            return c;
        }
        throw new IOException("重定向次数过多");
    }

    private void copyHeader(HttpURLConnection from, StringBuilder to, String name) {
        String v = from.getHeaderField(name);
        if (v != null && !v.isEmpty()) to.append(name).append(": ").append(v).append("\r\n");
    }

    private void writeSimple(Socket sock, int code, String text) {
        try {
            OutputStream o = sock.getOutputStream();
            byte[] body = text.getBytes("UTF-8");
            String h = "HTTP/1.1 " + code + " " + text + "\r\n"
                    + "Content-Length: " + body.length + "\r\n"
                    + "Connection: close\r\n\r\n";
            o.write(h.getBytes("ISO-8859-1"));
            o.write(body);
            o.flush();
        } catch (IOException ignored) {
        }
    }

    /** 读请求头（到 \r\n\r\n 为止）。视频请求没有 body，不必继续读。 */
    private String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        int state = 0;
        int b;
        while ((b = in.read()) >= 0) {
            bos.write(b);
            // 匹配结尾的 \r\n\r\n
            state = (b == '\r' && (state == 0 || state == 2)) ? state + 1
                    : (b == '\n' && (state == 1 || state == 3)) ? state + 1 : 0;
            if (state == 4) break;
            if (bos.size() > 16384) break;
        }
        return bos.toString("ISO-8859-1");
    }

    private String extractTarget(String path) {
        int q = path.indexOf("?");
        if (q < 0) return null;
        String query = path.substring(q + 1);
        for (String kv : query.split("&")) {
            if (kv.startsWith("u=")) {
                try {
                    return URLDecoder.decode(kv.substring(2), "UTF-8");
                } catch (Exception e) {
                    return null;
                }
            }
        }
        return null;
    }

    /** 调试用：列出跳转链（排查 403 时用） */
    public static String probe(String url) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(15000);
            c.setReadTimeout(15000);
            c.setRequestProperty("User-Agent", NETDISK_UA);
            c.setRequestProperty("Range", "bytes=0-1023");
            int code = c.getResponseCode();
            Map<String, List<String>> hs = c.getHeaderFields();
            return "code=" + code + " len=" + c.getContentLength() + " hdrs=" + hs;
        } catch (Exception e) {
            return "probe fail " + e;
        }
    }
}
