package com.supermov.tv;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 下载引擎（自研，零第三方依赖）。
 *
 * <p>队列模型：单 worker 线程顺序消费 QUEUED 任务；每个任务用 {@link #SEGS} 条 HTTP Range 连接
 * 并行拉取，通过 RandomAccessFile 随机写入 {@code <目标文件>.dlpart}，全部完成后 rename 成正式文件名。
 * 百度对单连接限速，多连接并行对非会员同样显著提速——所以并发是在「连接数」这一层。</p>
 *
 * <p>断点续传：分段进度写在 {@code <目标文件>.dlpart.json}。每次重入都：</p>
 * <ol>
 *   <li>重新取直链 —— dlink 只有 8 小时有效期，绝不能缓存复用</li>
 *   <li>重新探测总长度，只有「总长度 + 分段数」都一致才允许续传，否则从零开始（宁可重下也不写坏文件）</li>
 * </ol>
 *
 * <p>健壮性设计：</p>
 * <ul>
 *   <li>探测阶段就判断服务端是否支持 Range；不支持则退化为单连接顺序下载</li>
 *   <li>请求强制 {@code Accept-Encoding: identity}，避免 gzip 破坏 Range 语义</li>
 *   <li>dlink 会 302 到 CDN，自己跟跳转并在每一跳重新补 UA=netdisk（HttpURLConnection 自动跳转会丢头）</li>
 *   <li>单个分片抖动 -> 分段内部自旋重试（线性退避），超出上限才判失败</li>
 *   <li>进程被杀 -> {@link #init} 把 RUNNING 回滚成 QUEUED，下次启动自动接着下</li>
 * </ul>
 */
public final class DlEngine {

    private static final String TAG = "SupeMov";

    /** 并行连接数。百度对单连接限速，4 条连接通常能把非会员速度拉起来。 */
    private static final int SEGS = 4;
    /** 单个分片的最大重试次数。 */
    private static final int SEG_RETRY = 8;
    /** 读缓冲。 */
    private static final int BUF = 128 * 1024;
    /** 直链要带的 UA：百度官方客户端标识，缺失直接 403。 */
    private static final String NETDISK_UA = "netdisk";

    /** 队列/进度变化回调（可能在任意线程触发，UI 侧自行 post 到主线程）。 */
    public interface Observer {
        void onChanged();
    }

    private static final DlEngine INST = new DlEngine();

    public static DlEngine get() {
        return INST;
    }

    private Context app;
    private DlDb db;

    /** 内存队列 = 唯一真源，顺序即队列顺序。 */
    private final List<Dl> tasks = new ArrayList<>();
    private final List<Observer> observers = new ArrayList<>();

    private Thread worker;
    private volatile boolean stopping = false;
    private volatile boolean drained = true;
    private volatile Dl current;
    private volatile String note = "";
    private final AtomicBoolean cancel = new AtomicBoolean(false);
    private final AtomicBoolean inited = new AtomicBoolean(false);
    private volatile long lastPersist;

    private DlEngine() {
    }

    // ==================== 生命周期 ====================

    public void init(Context c) {
        if (!inited.compareAndSet(false, true)) return;
        app = c.getApplicationContext();
        try {
            db = DlDb.get(app);
        } catch (Throwable e) {
            Log.d(TAG, "dl db init fail " + e);
        }
        synchronized (tasks) {
            tasks.clear();
            if (db != null) tasks.addAll(db.list());
            // 上次进程被杀时标记为 RUNNING 的，回滚成排队，下次自动续传
            for (Dl t : tasks) {
                if (t.status == Dl.RUNNING) {
                    t.status = Dl.QUEUED;
                    t.error = "";
                }
            }
        }
        persistAll();
        Log.d(TAG, "dl init tasks=" + tasks.size());
    }

    /** 启动 worker（幂等）。 */
    public void start() {
        stopping = false;
        synchronized (this) {
            if (worker != null && worker.isAlive()) return;
            worker = new Thread(this::loop, "dl-worker");
            worker.setDaemon(true);
            worker.start();
        }
    }

    /** 队列是否还有活（排队中/下载中）。 */
    public boolean busy() {
        synchronized (tasks) {
            for (Dl t : tasks) {
                if (t.active()) return true;
            }
        }
        return false;
    }

    /** worker 已经跑完且队列为空 —— 服务可以收工了。 */
    public boolean drained() {
        return drained && !busy();
    }

    public Dl currentTask() {
        return current;
    }

    public String note() {
        return note;
    }

    // ==================== 观察者 ====================

    public void addObserver(Observer o) {
        if (o == null) return;
        synchronized (observers) {
            if (!observers.contains(o)) observers.add(o);
        }
    }

    public void removeObserver(Observer o) {
        synchronized (observers) {
            observers.remove(o);
        }
    }

    private void notifyChanged() {
        List<Observer> copy;
        synchronized (observers) {
            if (observers.isEmpty()) return;
            copy = new ArrayList<>(observers);
        }
        for (Observer o : copy) {
            try {
                o.onChanged();
            } catch (Throwable ignored) {
            }
        }
    }

    // ==================== 队列操作 ====================

    public List<Dl> snapshot() {
        synchronized (tasks) {
            return new ArrayList<>(tasks);
        }
    }

    public Dl find(long id) {
        synchronized (tasks) {
            for (Dl t : tasks) {
                if (t.id == id) return t;
            }
        }
        return null;
    }

    /** 入队并启动服务（返回任务 id）。 */
    public long enqueue(Context c, Dl t) {
        init(c);
        if (t.created <= 0) t.created = System.currentTimeMillis();
        t.status = Dl.QUEUED;
        long id = 0;
        if (db != null) {
            try {
                id = db.insert(t);
            } catch (Throwable e) {
                Log.d(TAG, "dl insert fail " + e);
            }
        }
        t.id = id;
        drained = false;
        synchronized (tasks) {
            tasks.add(t);
        }
        notifyChanged();
        DlService.start(c);
        start();
        return id;
    }

    /** 暂停：正在下的立即停手（已下分片保留），排队的标记为暂停。 */
    public void pause(long id) {
        Dl t = find(id);
        if (t == null) return;
        if (t.status == Dl.RUNNING) {
            t.status = Dl.PAUSED;
            persist(t);
            cancel.set(true); // 让分片线程尽快退出
        } else if (t.status == Dl.QUEUED) {
            t.status = Dl.PAUSED;
            persist(t);
        }
        notifyChanged();
    }

    /** 继续 / 重试：回到排队，worker 会自动接手（断点续传）。 */
    public void resume(long id) {
        Dl t = find(id);
        if (t == null) return;
        if (t.status == Dl.PAUSED || t.status == Dl.ERROR) {
            t.status = Dl.QUEUED;
            t.error = "";
            persist(t);
            drained = false;
            if (app != null) DlService.start(app);
            start();
        }
        notifyChanged();
    }

    /** 暂停全部。 */
    public void pauseAll() {
        synchronized (tasks) {
            for (Dl t : tasks) {
                if (t.active()) {
                    t.status = Dl.PAUSED;
                    persist(t);
                }
            }
        }
        cancel.set(true);
        notifyChanged();
    }

    /** 继续全部（含失败重试）。 */
    public void resumeAll() {
        boolean any = false;
        synchronized (tasks) {
            for (Dl t : tasks) {
                if (t.status == Dl.PAUSED || t.status == Dl.ERROR) {
                    t.status = Dl.QUEUED;
                    t.error = "";
                    persist(t);
                    any = true;
                }
            }
        }
        if (any) {
            drained = false;
            if (app != null) DlService.start(app);
            start();
        }
        notifyChanged();
    }

    /**
     * 删除任务：清掉队列行 + 断点残留（.dlpart / .dlpart.json）。
     * 已下好的正式文件不动——删文件是用户的决定，不该由「删任务」代劳。
     */
    public void remove(long id) {
        Dl t = find(id);
        if (t == null) return;
        if (t.status == Dl.RUNNING) cancel.set(true);
        synchronized (tasks) {
            tasks.remove(t);
        }
        if (db != null) {
            try {
                db.delete(id);
            } catch (Throwable ignored) {
            }
        }
        cleanupPart(t);
        notifyChanged();
    }

    /** 删除全部任务（已下好的正式文件保留）。 */
    public long removeAll() {
        return removeAll(false);
    }

    /**
     * 删除全部任务：停掉正在下的、清空队列、删掉所有断点残留（.dlpart / .dlpart.json）。
     *
     * <p>默认口径与单个删除一致：**已下好的正式文件不动** —— 删影片文件是用户的决定，
     * 不该由「清空列表」代劳。断点文件是预分配过 total 大小的，清掉能实打实释放空间。</p>
     *
     * @param alsoFiles true 时才把已下载完成的正式文件一并删掉（顺手清掉被删空的目录）
     * @return 实际释放的字节数（断点预分配空间 + 被删的正式文件）
     */
    public long removeAll(boolean alsoFiles) {
        // 先把正在下的标成暂停，否则 loop 收尾时会把取消当成「下载完成」
        Dl c = current;
        if (c != null && c.status == Dl.RUNNING) c.status = Dl.PAUSED;
        cancel.set(true); // 让分片线程尽快退出

        List<Dl> copy;
        synchronized (tasks) {
            copy = new ArrayList<>(tasks);
            tasks.clear();
        }
        long freed = 0;
        for (Dl t : copy) {
            freed += cleanupPartBytes(t);
            if (alsoFiles) freed += deleteOut(t);
        }
        if (db != null) {
            try {
                db.deleteAll();
            } catch (Throwable ignored) {
            }
        }
        note = "";
        notifyChanged();
        return freed;
    }

    /** 清掉所有已完成的任务行。 */
    public void clearDone() {
        synchronized (tasks) {
            for (int i = tasks.size() - 1; i >= 0; i--) {
                if (tasks.get(i).status == Dl.DONE) tasks.remove(i);
            }
        }
        if (db != null) {
            try {
                db.clearFinished();
            } catch (Throwable ignored) {
            }
        }
        notifyChanged();
    }

    /** 服务被销毁时调用：让当前任务停在可续传的状态。 */
    public void pauseCurrent() {
        Dl c = current;
        if (c != null && c.status == Dl.RUNNING) {
            c.status = Dl.PAUSED;
            persist(c);
        }
        cancel.set(true);
    }

    /** 进程/服务退出：未完成的回到队列，下次自动续传。 */
    public void suspendAll() {
        cancel.set(true);
        stopping = true;
        synchronized (tasks) {
            for (Dl t : tasks) {
                if (t.status == Dl.RUNNING) {
                    t.status = Dl.QUEUED;
                    persist(t);
                }
            }
        }
        notifyChanged();
    }

    // ==================== worker ====================

    private void loop() {
        Log.d(TAG, "dl worker start");
        while (!stopping) {
            Dl t = takeNextQueued();
            if (t == null) break;
            current = t;
            cancel.set(false);
            notifyChanged();
            try {
                runTask(t);
                if (t.status == Dl.RUNNING) {
                    t.status = Dl.DONE;
                    t.done = t.total;
                    t.error = "";
                    note = "已完成：" + t.fileName;
                    Log.d(TAG, "dl done " + t.fileName);
                }
            } catch (Throwable e) {
                String msg = shortMsg(e);
                if (t.status == Dl.PAUSED) {
                    // 用户暂停：保持暂停
                } else if (stopping) {
                    t.status = Dl.QUEUED; // 服务停了，留着下次续传
                    t.error = "";
                } else {
                    t.status = Dl.ERROR;
                    t.error = msg;
                }
                Log.d(TAG, "dl task " + t.id + " end status=" + t.status + " msg=" + msg);
            }
            persist(t);
            current = null;
            notifyChanged();
        }
        note = "";
        drained = true;
        notifyChanged();
        Log.d(TAG, "dl worker exit");
    }

    private Dl takeNextQueued() {
        synchronized (tasks) {
            for (Dl t : tasks) {
                if (t.status == Dl.QUEUED) {
                    t.status = Dl.RUNNING;
                    t.error = "";
                    persist(t);
                    return t;
                }
            }
        }
        return null;
    }

    // ==================== 单个任务的下载 ====================

    private void runTask(Dl t) throws Exception {
        File dir = new File(t.dir);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("无法创建目录：" + t.dir);
        }
        if (!dir.canWrite()) {
            throw new IOException("目录不可写（可能是权限或外接盘只读）：" + t.dir);
        }
        if (t.fileName == null || t.fileName.isEmpty()) t.fileName = "video.mp4";

        // ① 取直链（8 小时过期，每次跑都必须重取，绝不复用）
        note = "获取直链…";
        notifyChanged();
        String url = "";
        if (t.fsId > 0 && t.bpath != null && !t.bpath.isEmpty()) {
            url = BaiduPan.dlink(t.fsId, t.bpath);
        }
        if (url.isEmpty()) {
            // fs_id 失效（文件被删/改名）-> 回网盘重新认片
            note = "重新定位网盘文件…";
            notifyChanged();
            BaiduPan.PlayFile pf = BaiduPan.resolvePlayable(t.rootDir, t.name);
            if (!pf.ok) throw new IOException(pf.message.isEmpty() ? "网盘里找不到该文件" : pf.message);
            t.fsId = pf.fsId;
            t.bpath = pf.path;
            // 只有在还没开始写盘（没有断点残留）时才允许改文件名，否则会写到另一份文件上
            if (!pf.name.isEmpty()
                    && !new File(dir, t.fileName + ".dlpart").exists()) {
                t.fileName = Dl.safeName(pf.name);
            }
            url = BaiduPan.dlink(t.fsId, t.bpath);
        }
        if (url.isEmpty()) throw new IOException("取直链失败（登录态失效或被风控）");
        checkCancel();

        // 文件名可能刚刚被改写 -> 路径必须在这里才算，保证 part/meta/out 与 DB 记录一致
        final File part = new File(dir, t.fileName + ".dlpart");
        final File meta = new File(dir, t.fileName + ".dlpart.json");
        final File out = new File(dir, t.fileName);

        // ② 探测总长度 + 是否支持 Range
        note = "探测文件大小…";
        notifyChanged();
        Probe p = probe(url);
        if (p.total <= 0) throw new IOException("未取到文件大小（HTTP " + p.code + "）");
        long total = p.total;
        t.total = total;
        if (total <= 0) {
            t.done = 0;
            return;
        }

        // ③ 断点：总长度 + 分段数都一致才续传，否则从零开始
        final int segs = p.ranged ? SEGS : 1;
        final long segSize = (total + segs - 1) / segs;
        long[] done = new long[segs];
        boolean resume = false;
        if (part.exists() && part.length() == total) {
            long[] m = readMeta(meta, segs, total, segSize);
            if (m != null) {
                done = m;
                resume = true;
            }
        }
        // 服务端不支持 Range 时根本没法跳过已下载的字节，续传会一直卡在第一段重试。
        // 这种情况必须老实从头下（分段只有 1 段，会整段覆盖，不会留下旧数据）。
        if (!p.ranged) {
            for (int i = 0; i < segs; i++) done[i] = 0;
            resume = false;
        }
        long already = 0;
        for (long d : done) already += d;
        Log.d(TAG, "dl task " + t.id + " total=" + total + " ranged=" + p.ranged
                + " segs=" + segs + " resume=" + resume + " already=" + already);

        RandomAccessFile rafOpen = null;
        try {
            rafOpen = new RandomAccessFile(part, "rw");
            rafOpen.setLength(total); // 预分配（断点时是幂等的）
        } catch (IOException e) {
            closeQuietly(rafOpen);
            throw new IOException("无法创建目标文件（分区可能不支持大于 4GB 的文件，如 FAT32）："
                    + e.getMessage());
        }
        final RandomAccessFile raf = rafOpen;
        final String segUrl = url;

        final AtomicLong totalDone = new AtomicLong(already);
        t.done = already;
        final String[] errBox = new String[1];
        final Object writeLock = new Object();
        final long[] segDone = done;
        final AtomicBoolean segFinished = new AtomicBoolean(false);

        // ④ 进度上报线程（600ms 一次，2s 落一次盘）
        Thread reporter = new Thread(() -> {
            long lastBytes = totalDone.get();
            long lastTime = System.currentTimeMillis();
            while (!segFinished.get()) {
                long now = System.currentTimeMillis();
                long bytes = totalDone.get();
                long dt = now - lastTime;
                double speed = dt > 0 ? (bytes - lastBytes) * 1000.0 / dt : 0;
                lastBytes = bytes;
                lastTime = now;
                t.done = bytes;
                note = "下载中 " + (total > 0 ? (bytes * 100 / total) : 0) + "%"
                        + " · " + human(bytes) + "/" + human(total)
                        + " · " + human((long) speed) + "/s"
                        + (segs > 1 ? " · " + segs + " 连接" : "");
                if (now - lastPersist > 2000) {
                    lastPersist = now;
                    persist(t);
                    writeMeta(meta, total, segSize, segDone);
                }
                notifyChanged();
                try {
                    Thread.sleep(600);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "dl-progress");
        reporter.setDaemon(true);
        reporter.start();

        // ⑤ 分片并发拉取
        List<Thread> pool = new ArrayList<>();
        try {
            for (int i = 0; i < segs; i++) {
                final int idx = i;
                final long s = i * segSize;
                final long e = Math.min(total, s + segSize);
                if (segDone[idx] >= e - s) continue; // 该段已下完
                Thread th = new Thread(() -> segLoop(segUrl, raf, writeLock, idx, s, e,
                        segDone, totalDone, errBox), "dl-seg" + i);
                th.setDaemon(true);
                th.start();
                pool.add(th);
            }
            for (Thread th : pool) {
                th.join();
            }
        } finally {
            segFinished.set(true);
            reporter.interrupt();
            try {
                reporter.join(1500);
            } catch (InterruptedException ignored) {
            }
            try {
                raf.close();
            } catch (IOException ignored) {
            }
        }

        if (cancel.get()) return; // 暂停/停止：保留 part + meta，下次续传
        if (errBox[0] != null && totalDone.get() < total) throw new IOException(errBox[0]);

        // ⑥ 落定：rename 成正式文件名
        note = "整理文件…";
        notifyChanged();
        writeMeta(meta, total, segSize, segDone);
        if (out.exists()) out.delete();
        if (!part.renameTo(out)) {
            // 跨卷/异常情况：流式拷贝兜底
            copyFile(part, out);
            part.delete();
        }
        meta.delete();
        t.done = total;
        t.total = total;
        note = "已完成：" + t.fileName;
    }

    /**
     * 单个分片：从 segStart+done[idx] 拉到 segEnd，边拉边随机写入。
     * 任何一次网络抖动都不会丢进度 —— done[idx] 是权威偏移，重试就从这里续。
     */
    private void segLoop(String url, RandomAccessFile raf, Object lock, int idx,
                         long segStart, long segEnd, long[] segDone,
                         AtomicLong totalDone, String[] errBox) {
        long need = segEnd - segStart;
        int retry = 0;
        while (segDone[idx] < need) {
            if (cancel.get()) return;
            long from = segStart + segDone[idx];
            HttpURLConnection c = null;
            InputStream in = null;
            try {
                c = open(url, "bytes=" + from + "-" + (segEnd - 1));
                int code = c.getResponseCode();
                if (code == 416) {
                    // 请求区间越界 == 该段其实已经下完
                    long add = need - segDone[idx];
                    segDone[idx] = need;
                    totalDone.addAndGet(add);
                    return;
                }
                if (code != 200 && code != 206) {
                    throw new IOException("HTTP " + code);
                }
                if (code == 200 && from > 0) {
                    // 我们请求了 Range 却拿到整文件，写下去会错位 —— 必须换链，不能将错就错
                    throw new IOException("服务端忽略 Range（HTTP 200）");
                }
                in = c.getInputStream();
                byte[] buf = new byte[BUF];
                long pos = from;
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (cancel.get()) return;
                    synchronized (lock) {
                        raf.seek(pos);
                        raf.write(buf, 0, n);
                    }
                    pos += n;
                    segDone[idx] += n;
                    totalDone.addAndGet(n);
                }
                retry = 0;
            } catch (Throwable ex) {
                if (cancel.get()) return;
                retry++;
                if (retry > SEG_RETRY) {
                    if (errBox[0] == null) {
                        errBox[0] = "第 " + (idx + 1) + " 个分片失败：" + shortMsg(ex);
                    }
                    return;
                }
                try {
                    Thread.sleep(400L * retry); // 线性退避，避免风控层叠
                } catch (InterruptedException ie) {
                    return;
                }
            } finally {
                closeQuietly(in);
                if (c != null) c.disconnect();
            }
        }
    }

    // ==================== HTTP ====================

    private static class Probe {
        int code;
        long total;
        boolean ranged;
    }

    /** 用 {@code Range: bytes=0-0} 探测：206 + Content-Range 说明支持 Range 且拿到总长度。 */
    private Probe probe(String url) {
        Probe p = new Probe();
        HttpURLConnection c = null;
        try {
            c = open(url, "bytes=0-0");
            p.code = c.getResponseCode();
            if (p.code == 206) {
                p.total = parseTotal(c.getHeaderField("Content-Range"));
                p.ranged = p.total > 0;
            }
            if (p.total <= 0) {
                long len = c.getContentLength();
                if (len > 0) p.total = len;
            }
            String ar = c.getHeaderField("Accept-Ranges");
            if (ar != null && ar.toLowerCase().contains("bytes") && p.total > 0) p.ranged = true;
        } catch (Throwable e) {
            Log.d(TAG, "probe err " + e);
        } finally {
            if (c != null) c.disconnect();
        }
        // 兜底：再来一次不带 Range 的探测
        if (p.total <= 0) {
            HttpURLConnection c2 = null;
            try {
                c2 = open(url, null);
                p.code = c2.getResponseCode();
                long len = c2.getContentLength();
                if (len > 0) p.total = len;
                p.ranged = false;
            } catch (Throwable ignored) {
            } finally {
                if (c2 != null) c2.disconnect();
            }
        }
        return p;
    }

    /** 从 {@code bytes 0-0/12345} 里取总数。 */
    private static long parseTotal(String contentRange) {
        if (contentRange == null) return 0;
        int i = contentRange.lastIndexOf('/');
        if (i < 0 || i == contentRange.length() - 1) return 0;
        try {
            return Long.parseLong(contentRange.substring(i + 1).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 打开直链并自己跟 302：dlink 会跳到 baidupcs CDN，跳转后仍必须带 UA=netdisk。
     * 强制 identity 编码，否则 gzip 会让 Range 的字节偏移失去意义。
     */
    private static HttpURLConnection open(String url, String range) throws IOException {
        String cur = url;
        String cookie = CookieStore.cookieFor("https://pan.baidu.com/");
        for (int hop = 0; hop <= 6; hop++) {
            HttpURLConnection c = (HttpURLConnection) new URL(cur).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setRequestProperty("User-Agent", NETDISK_UA);
            c.setRequestProperty("Referer", "https://pan.baidu.com/");
            c.setRequestProperty("Accept-Encoding", "identity");
            if (cookie != null && !cookie.isEmpty()) c.setRequestProperty("Cookie", cookie);
            if (range != null && !range.isEmpty()) c.setRequestProperty("Range", range);
            int code = c.getResponseCode();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                if (loc == null || loc.isEmpty()) throw new IOException("重定向缺少目标地址");
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

    // ==================== 断点元数据 ====================

    private static long[] readMeta(File meta, int segs, long total, long segSize) {
        if (!meta.exists()) return null;
        InputStream in = null;
        try {
            in = new FileInputStream(meta);
            byte[] buf = new byte[(int) Math.min(meta.length(), 64 * 1024)];
            int off = 0;
            int n;
            while (off < buf.length && (n = in.read(buf, off, buf.length - off)) > 0) off += n;
            JSONObject o = new JSONObject(new String(buf, 0, off, "UTF-8"));
            if (o.optLong("total", -1) != total) return null;
            if (o.optInt("segs", -1) != segs) return null;
            JSONArray a = o.optJSONArray("done");
            if (a == null || a.length() != segs) return null;
            long[] r = new long[segs];
            for (int i = 0; i < segs; i++) {
                long v = a.optLong(i, 0);
                long need = Math.min(total, (i + 1L) * segSize) - i * segSize;
                if (v < 0 || v > need) return null;
                r[i] = v;
            }
            return r;
        } catch (Throwable e) {
            return null;
        } finally {
            closeQuietly(in);
        }
    }

    private void writeMeta(File meta, long total, long segSize, long[] done) {
        OutputStream out = null;
        try {
            JSONObject o = new JSONObject();
            o.put("total", total);
            o.put("segSize", segSize);
            o.put("segs", done.length);
            JSONArray a = new JSONArray();
            for (long d : done) a.put(d);
            o.put("done", a);
            o.put("time", System.currentTimeMillis());
            out = new FileOutputStream(meta);
            out.write(o.toString().getBytes("UTF-8"));
            out.flush();
        } catch (Throwable ignored) {
        } finally {
            closeQuietly(out);
        }
    }

    // ==================== 杂项 ====================

    private void checkCancel() throws IOException {
        if (cancel.get()) throw new IOException("cancelled");
    }

    private void persist(Dl t) {
        if (db == null || t == null || t.id <= 0) return;
        try {
            db.update(t);
        } catch (Throwable ignored) {
        }
    }

    private void persistAll() {
        List<Dl> copy = snapshot();
        for (Dl t : copy) persist(t);
    }

    /** 清掉断点残留文件（不动正式文件）。 */
    private void cleanupPart(Dl t) {
        cleanupPartBytes(t);
    }

    /**
     * 清掉断点残留文件，返回释放的字节数。
     *
     * <p>体积按**文件实际占用**算，不用任务里的 total —— .dlpart 是
     * {@code setLength(total)} 预分配出来的，没下完的任务实际占盘就是整个文件大小，
     * 所以这个数字是真能释放出来的。</p>
     */
    private long cleanupPartBytes(Dl t) {
        long freed = 0;
        try {
            if (t.dir == null || t.fileName == null) return 0;
            File part = new File(t.dir, t.fileName + ".dlpart");
            if (part.isFile()) {
                long len = part.length();
                if (part.delete()) freed += len;
            }
            File meta = new File(t.dir, t.fileName + ".dlpart.json");
            if (meta.isFile()) {
                long len = meta.length();
                if (meta.delete()) freed += len;
            }
        } catch (Throwable ignored) {
        }
        return freed;
    }

    /**
     * 删掉已下载完成的正式文件，返回释放的字节数。
     *
     * <p>文件不存在（用户自己在文件管理器里删过了）不算错，静默跳过。</p>
     */
    private long deleteOut(Dl t) {
        long freed = 0;
        try {
            if (t.dir == null || t.fileName == null || t.fileName.isEmpty()) return 0;
            File out = new File(t.dir, t.fileName);
            if (out.isFile()) {
                long len = out.length();
                if (out.delete()) freed += len;
            }
            pruneEmptyDirs(new File(t.dir));
        } catch (Throwable ignored) {
        }
        return freed;
    }

    /**
     * 从 dir 向上逐级删掉「空目录」。
     *
     * <p>落盘目录是按网盘结构 1:1 建的，删完文件会留下一串空壳（片名 / Season 1），
     * 看着很脏。这里只在目录**确实为空**时才删，遇到非空立刻停手。</p>
     *
     * <p><b>删除边界</b>：向上找名为「超级影库」的下载根目录做锚点，只允许删到它为止。
     * 找不到锚点（用户自定义了奇怪的落盘目录）时**最多只删文件所在的那一级**，
     * 绝不继续上溯 —— 否则一串空目录删上去可能把 {@code /mnt/usb} 这种挂载点删掉。</p>
     */
    private void pruneEmptyDirs(File dir) {
        try {
            File anchor = null;
            for (File f = dir; f != null; f = f.getParentFile()) {
                if ("超级影库".equals(f.getName())) {
                    anchor = f;
                    break;
                }
            }
            File cur = dir;
            for (int up = 0; cur != null; up++) {
                if (anchor == null && up >= 1) return;        // 没锚点：只删文件所在目录本身
                if (anchor != null && up > 8) return;          // 有锚点：层数兜底
                String[] kids = cur.list();
                if (kids == null || kids.length > 0) return;   // 非空：停手
                File parent = cur.getParentFile();
                if (parent == null) return;
                if (!cur.delete()) return;
                if (anchor != null && cur.equals(anchor)) return; // 删到下载根目录收手
                cur = parent;
            }
        } catch (Throwable ignored) {
        }
    }

    private static void copyFile(File from, File to) throws IOException {
        InputStream in = null;
        OutputStream out = null;
        try {
            in = new FileInputStream(from);
            out = new FileOutputStream(to);
            byte[] buf = new byte[512 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
        } finally {
            closeQuietly(in);
            closeQuietly(out);
        }
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (Throwable ignored) {
        }
    }

    private static String shortMsg(Throwable e) {
        if (e == null) return "未知错误";
        String m = e.getMessage();
        if (m == null || m.isEmpty()) return e.getClass().getSimpleName();
        if (m.length() > 90) m = m.substring(0, 90);
        return m;
    }

    /** 人类可读体积。 */
    public static String human(long bytes) {
        if (bytes <= 0) return "0B";
        if (bytes >= 1024L * 1024 * 1024) {
            return String.format(java.util.Locale.CHINA, "%.2fGB", bytes / 1073741824.0);
        }
        if (bytes >= 1024 * 1024) {
            return String.format(java.util.Locale.CHINA, "%.1fMB", bytes / 1048576.0);
        }
        if (bytes >= 1024) return (bytes / 1024) + "KB";
        return bytes + "B";
    }
}
