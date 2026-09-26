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
import java.nio.ByteBuffer;
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
    /** 速度 EMA 权重：ETA 拿速度当分母，用瞬时值会一秒钟跳一个样。 */
    private static final double SPEED_EMA = 0.25;
    /** 速度有效期：这么久没有新采样（任务收尾 / 暂停）就把速度当 0，别拿旧速度瞎估。 */
    private static final long SPEED_STALE_MS = 4000;

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
    /**
     * worker 是否活着（含"正在挑任务"这段）。
     *
     * <p>它和「往队列里加任务」必须在同一把锁（{@link #tasks}）里改，否则会出现：
     * worker 刚判定队列为空、还没来得及置位，主线程入队后调 {@link #start()} 看到
     * worker 还活着就不新建 —— 那个任务从此没人接手。判定逻辑见 {@link #loop()}。</p>
     */
    private volatile boolean workerAlive = false;
    private volatile boolean stopping = false;
    private volatile boolean drained = true;
    private volatile Dl current;
    private volatile String note = "";
    private final AtomicBoolean cancel = new AtomicBoolean(false);
    private final AtomicBoolean inited = new AtomicBoolean(false);
    private volatile long lastPersist;

    /**
     * 后台下载限速（**字节/秒**）；{@code <= 0} = 不限速。
     *
     * <p>口径：下载管理页在前台时置 0（用户正看着进度，别掐带宽）；离开页面、以及
     * 冷启动进后台时置 {@link Settings#dlLimitBps()}。设置里给用户看的是 Mbps。</p>
     */
    private volatile long rateLimitBps = 0;
    /** 限速窗口：多个分片线程共用一份额度，必须加锁 */
    private final Object rateLock = new Object();
    private long rateWindowStart = 0;
    private long rateWindowBytes = 0;

    /** 平滑后的下载速度（字节/秒），ETA 的分母。 */
    private volatile double smoothBps = 0;
    /** 最后一次速度采样时刻：太久没更新说明已经停下。 */
    private volatile long smoothAt = 0;

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
        // 冷启动 / 服务重启后先按「后台下载限速」起步：这时没有人在看下载页，
        // 等用户真的进了下载管理页，onResume 会把限速放开
        Settings.init(c);
        rateLimitBps = Settings.dlLimitBps();
        Log.d(TAG, "dl init tasks=" + tasks.size() + " rateLimit=" + rateLimitBps);
    }

    /**
     * 启动 worker（幂等）。{@code workerAlive} 与队列同锁，见 {@link #loop()}。
     */
    public void start() {
        stopping = false;
        synchronized (tasks) {
            if (workerAlive) return;
            workerAlive = true;
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

    // ==================== 队列剩余 / 预计完成时间 ====================

    /** 队列剩余量的估算中间结果。 */
    private static final class Plan {
        /** 已确定还要下的字节（total 已知的那部分）。 */
        long known;
        /** total 还没探测到的待下任务数。 */
        int unknown;
        /** 单个任务的参考体积，用来估 unknown 那部分。 */
        long refBytes;
        /** RUNNING + QUEUED 的任务数。 */
        int active;

        long estimate() {
            return known + (long) unknown * refBytes;
        }

        /** 含估算成分 -> 文案上要标「左右」。 */
        boolean approx() {
            return unknown > 0;
        }
    }

    /**
     * 统计队列还剩多少要下。
     *
     * <p>只算会被自动接着下的任务（RUNNING + QUEUED），暂停 / 失败的不算 —— 那些要等用户点继续。
     * 入队时 {@code total} 一般已经从网盘清单带过来了（多集剧逐集体积都是现成的），
     * 所以这个数基本就是准的；少数还没探测到体积的，按「参考体积 × 个数」估。</p>
     */
    private Plan plan() {
        Plan p = new Plan();
        Dl cur = current;
        long sumTotal = 0;
        int nTotal = 0;
        for (Dl t : snapshot()) {
            if (t.status != Dl.RUNNING && t.status != Dl.QUEUED) continue;
            p.active++;
            if (t.total > 0) {
                long left = t.total - t.done;
                if (left > 0) p.known += left;
                sumTotal += t.total;
                nTotal++;
            } else {
                p.unknown++;
            }
        }
        if (cur != null && cur.total > 0) {
            p.refBytes = cur.total;              // 优先拿正在下的这部当参考
        } else if (nTotal > 0) {
            p.refBytes = sumTotal / nTotal;      // 退一步：已探测任务的平均体积
        }
        return p;
    }

    /** 队列还要下多少字节（含对未探测任务的估算）。 */
    public long remainBytes() {
        return plan().estimate();
    }

    /**
     * 有效下载速度（字节/秒）：限速时按额度封顶（真实吞吐不会超过它）；
     * 停手超过 {@link #SPEED_STALE_MS} 视为 0。
     */
    public double speedBps() {
        long at = smoothAt;
        if (at <= 0 || System.currentTimeMillis() - at > SPEED_STALE_MS) return 0;
        double v = smoothBps;
        long limit = rateLimitBps;
        if (limit > 0 && v > limit) v = limit;
        return v > 0 ? v : 0;
    }

    /** 预计全部下完还要多久（毫秒）；{@code <=0} = 算不出来（没在下 / 拿不到速度）。 */
    public long etaMs() {
        Dl cur = current;
        if (cur == null || cur.status != Dl.RUNNING) return 0;
        Plan p = plan();
        long remain = p.estimate();
        if (remain <= 0) return 0;
        double sp = speedBps();
        if (sp <= 0) return 0;
        double ms = remain * 1000.0 / sp;
        if (ms <= 0 || ms > Long.MAX_VALUE / 4) return 0;
        return (long) ms;
    }

    /**
     * 拼在顶部信息「N 线程」后面的尾巴：
     * {@code · 队列剩 4.5GB · 预计约 12 分钟 · 后面还有 2 个}。
     *
     * <p>没有下载中的任务、或暂时算不出速度就返回空串 —— 宁可少显示一段，
     * 也别显示一个每秒乱跳的数字。任务体积还没探测到时，「队列剩」写作「队列剩约」。</p>
     */
    public String etaSuffix() {
        Dl cur = current;
        if (cur == null || cur.status != Dl.RUNNING) return "";
        Plan p = plan();
        StringBuilder sb = new StringBuilder();
        long remain = p.estimate();
        if (remain > 0) {
            // 「队列剩」而不是「剩」：前面那个 1.2GB/2.8GB 是当前这一部，
            // 这个数是整条队列（含后面排队的），得说清楚
            sb.append(p.approx() ? " · 队列剩约 " : " · 队列剩 ").append(human(remain));
        }
        long ms = etaMs();
        if (ms > 0) sb.append(" · 预计 ").append(duration(ms));   // duration() 自带「约」，别叠成「约…左右」
        int next = p.active - 1;
        if (next > 0) sb.append(" · 后面还有 ").append(next).append(" 个");
        return sb.toString();
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

    // ==================== 后台限速 ====================

    /**
     * 设置下载限速（**字节/秒**，{@code <=0} = 不限速）。
     *
     * <p>下载管理页 onResume 里放开（传 0）、onPause 里按设置恢复。改值时把窗口清零，
     * 免得旧窗口里攒的字节让新速率起跑就判「已经超额」。</p>
     */
    public void setRateLimit(long bytesPerSec) {
        long v = bytesPerSec < 0 ? 0 : bytesPerSec;
        if (rateLimitBps == v) return;
        rateLimitBps = v;
        synchronized (rateLock) {
            rateWindowStart = 0;
            rateWindowBytes = 0;
        }
    }

    /** 当前限速（字节/秒，0 = 不限速）。 */
    public long rateLimit() {
        return rateLimitBps;
    }

    /**
     * 全局限速闸门：把**多分片合计**的吞吐压在 rateLimitBps 以内。
     *
     * <p>做法是按累计字节算出「这些数据本该花多久」，超前了就把差值睡掉。比「每个线程各限
     * 1/4」更准 —— 某个分片先下完时，剩下的分片能自动把整条额度用满。</p>
     *
     * <p>单次最多睡 300ms：暂停 / 取消要能及时响应，而且睡久了也只是把额度往后滚。</p>
     */
    private void throttle(int bytes) {
        long limit = rateLimitBps;
        if (limit <= 0 || bytes <= 0) return;
        synchronized (rateLock) {
            long now = System.currentTimeMillis();
            if (rateWindowStart == 0) {
                rateWindowStart = now;
                rateWindowBytes = 0;
            }
            rateWindowBytes += bytes;
            long shouldSpend = rateWindowBytes * 1000L / limit;   // 本该花掉的毫秒数
            long elapsed = now - rateWindowStart;
            if (shouldSpend > elapsed) {
                long sleep = shouldSpend - elapsed;
                if (sleep > 300) sleep = 300;
                try {
                    Thread.sleep(sleep);
                } catch (InterruptedException ignored) {
                }
            }
            // 窗口滚动，别让累计值无限长（也顺手抹平瞬时抖动）
            if (System.currentTimeMillis() - rateWindowStart > 5000) {
                rateWindowStart = System.currentTimeMillis();
                rateWindowBytes = 0;
            }
        }
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

    /**
     * 单 worker 顺序消费队列 —— **同一时刻只有一个任务在下载**（每个任务内部再开 4 条连接）。
     *
     * <p>一个任务收尾后（成功 / 失败 / 暂停）循环回到这里拿下一个 QUEUED，所以队列是自动接着下的。</p>
     *
     * <p>「判定队列空 -> 退出」必须和 {@link #enqueue} 的入队落在同一把锁里：否则会出现
     * worker 刚判定空、还没置 {@code workerAlive=false}，主线程入队后 {@link #start()}
     * 看到 worker 还活着就不新建 —— 新任务从此没人接手，要手动点「继续」才动。</p>
     */
    private void loop() {
        Log.d(TAG, "dl worker start");
        while (!stopping) {
            Dl t;
            synchronized (tasks) {
                t = takeNextQueued();
                if (t == null) {
                    workerAlive = false;   // 与 enqueue 的 add 互斥：谁后到谁负责
                    break;
                }
            }
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
            smoothBps = 0;        // 换任务了：上一个任务的速度不能拿来算这一个的 ETA
            smoothAt = 0;
            notifyChanged();
        }
        note = "";
        drained = true;
        notifyChanged();
        synchronized (tasks) {
            workerAlive = false;
        }
        // 兜底：万一刚好在「我决定退出」和「置位」之间有人入队（那会儿 start() 看到我还活着
        // 就没新建），这里自己再拉一次，保证队列不会被晾着。
        boolean again;
        synchronized (tasks) {
            again = !stopping && hasQueuedLocked();
        }
        if (again) {
            Log.d(TAG, "dl worker exit but queue refilled -> restart");
            start();
        }
        Log.d(TAG, "dl worker exit");
    }

    /** 必须在 {@code synchronized (tasks)} 里调用。 */
    private boolean hasQueuedLocked() {
        for (Dl t : tasks) {
            if (t.status == Dl.QUEUED) return true;
        }
        return false;
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

        // hash 片源走完全另一条路：云端分段清单 + 逐段 SHA1 + 合并后改名
        if (Dl.SRC_HASH.equals(t.source)) {
            runHashTask(t, dir);
            return;
        }

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
                if (speed < 0) speed = 0;
                // 速度做 EMA：ETA 的分母用瞬时值会一秒跳一个样
                smoothBps = smoothBps <= 0 ? speed : smoothBps + (speed - smoothBps) * SPEED_EMA;
                smoothAt = now;
                lastBytes = bytes;
                lastTime = now;
                t.done = bytes;
                note = "下载中 " + (total > 0 ? (bytes * 100 / total) : 0) + "%"
                        + " · " + human(bytes) + "/" + human(total)
                        + " · " + mbps(speed)
                        + (segs > 1 ? " · " + segs + " 线程" : "")
                        + etaSuffix();
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

    // ==================== hash 片源：云端分段下载 ====================

    /**
     * 同时下几个云端分段。
     *
     * <p>CDN 每段约 100 MiB（首段不规则）。并发到 4 路已能把家宽跑满，再多只会让
     * 边缘节点更早停流 —— 实测失败是「段数越多越容易卡」，不是「段数越多越快」。</p>
     */
    private static final int CDN_WORKERS = 4;
    /** 单段停滞超时：CDN 大文件长连接会中途停流，45s 没新字节就判卡死并续传。 */
    private static final int CDN_STALL_TIMEOUT_MS = 45000;
    /** 单段最多续传次数。 */
    private static final int CDN_RESUME_RETRY = 12;
    /** Matroska 文件头魔数，用于「拼完的确实是能播的 MKV」这道终检。 */
    private static final byte[] EBML_MAGIC = {(byte) 0x1A, (byte) 0x45, (byte) 0xDF, (byte) 0xA3};

    /** 下载过程中的中间文件名：只跟 hash 有关，与容器后缀无关，所以换 ext 也不会让断点失效。 */
    static String hashStageName(Dl t) {
        String h = (t.hash == null || t.hash.isEmpty()) ? "hashsrc" : t.hash;
        return h + ".dlpart";
    }

    private void runHashTask(Dl t, File dir) throws Exception {
        if (t.hash == null || t.hash.length() != 40) {
            throw new IOException("这条任务没有有效的影片云指纹，无法向云端取址");
        }
        String sn = Settings.cdnSn();

        // ① 取址（分段链接带服务端签名的日期段与令牌，每次都重取，绝不复用旧链接）
        note = "取云端分段地址…";
        notifyChanged();
        AimeiCdn.register(sn, Settings.cdnDistributor());   // 幂等；失败也继续试取址
        AimeiCdn.CdnInfo info = AimeiCdn.fetch(sn, t.hash);
        if (info.placeholder()) {
            // 只给一句能看懂的话：占位桩细节由 AimeiCdn 的日志输出，序列号不进界面
            throw new IOException("本设备暂时无法取得影片授权，稍后再试");
        }
        checkCancel();
        final long total = info.fileSize;
        final int n = info.segments.size();
        t.ext = info.extension;
        t.total = total;
        Log.d(TAG, "hash dl " + t.hash + " ext=" + info.extension + " total=" + total + " segs=" + n);

        final File stage = new File(dir, hashStageName(t));
        final File meta = new File(dir, stage.getName() + ".json");
        final long[] segDone = new long[n];
        final boolean[] segOk = new boolean[n];
        final boolean resumed = readCdnMeta(meta, t.hash, total, n, segDone, segOk);
        long already = 0;
        for (long d : segDone) already += d;
        if (!resumed && stage.exists()) stage.delete();   // 断点对不上：从零开始，别拿脏数据续

        final RandomAccessFile raf;
        try {
            raf = new RandomAccessFile(stage, "rw");
            raf.setLength(total);   // 预分配，各段按 start 绝对偏移写
        } catch (IOException e) {
            throw new IOException("无法创建目标文件（分区可能不支持大于 4GB 的文件，如 FAT32）："
                    + e.getMessage());
        }
        final java.nio.channels.FileChannel ch = raf.getChannel();
        final AtomicLong done = new AtomicLong(already);
        final java.util.concurrent.atomic.AtomicInteger cursor =
                new java.util.concurrent.atomic.AtomicInteger(0);
        final String[] errBox = new String[1];
        t.done = already;

        // ② 进度上报（沿用 600ms 一次、2s 落一次断点元数据的节奏）
        final AtomicBoolean finished = new AtomicBoolean(false);
        Thread reporter = new Thread(() -> {
            long lastBytes = done.get();
            long lastTime = System.currentTimeMillis();
            while (!finished.get()) {
                long now = System.currentTimeMillis();
                long bytes = done.get();
                double speed = now > lastTime ? (bytes - lastBytes) * 1000.0 / (now - lastTime) : 0;
                if (speed < 0) speed = 0;
                smoothBps = smoothBps <= 0 ? speed : smoothBps + (speed - smoothBps) * SPEED_EMA;
                smoothAt = now;
                lastBytes = bytes;
                lastTime = now;
                t.done = bytes;
                int verified = 0;
                synchronized (segOk) {
                    for (boolean b : segOk) if (b) verified++;
                }
                note = "下载中 " + (total > 0 ? (bytes * 100 / total) : 0) + "% · "
                        + human(bytes) + "/" + human(total) + " · " + mbps(speed)
                        + " · 分段 " + verified + "/" + n
                        + etaSuffix();
                if (now - lastPersist > 2000) {
                    lastPersist = now;
                    persist(t);
                    writeCdnMeta(meta, t.hash, total, segDone, segOk);
                }
                notifyChanged();
                try {
                    Thread.sleep(600);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "cdn-progress");
        reporter.setDaemon(true);
        reporter.start();

        // ③ 分段并发：每段一个工作线程，从同一个游标领活
        List<Thread> pool = new ArrayList<>();
        try {
            int workers = Math.min(CDN_WORKERS, n);
            for (int i = 0; i < workers; i++) {
                Thread th = new Thread(() -> {
                    while (!cancel.get() && errBox[0] == null) {
                        int idx = cursor.getAndIncrement();
                        if (idx >= n) return;
                        AimeiCdn.Segment seg = info.segments.get(idx);
                        try {
                            cdnSegment(seg, ch, stage, idx, segDone, segOk, done);
                        } catch (IOException e) {
                            if (!cancel.get()) errBox[0] = shortMsg(e);
                            return;
                        }
                    }
                }, "cdn-seg");
                th.setDaemon(true);
                th.start();
                pool.add(th);
            }
            for (Thread th : pool) th.join();
        } finally {
            finished.set(true);
            reporter.interrupt();
            try {
                reporter.join(1500);
            } catch (InterruptedException ignored) {
            }
            closeQuietly(ch);
            try {
                raf.close();
            } catch (IOException ignored) {
            }
        }

        if (cancel.get()) {
            writeCdnMeta(meta, t.hash, total, segDone, segOk);
            return;    // 暂停/停止：保留中间文件与断点，下次接着下
        }
        if (errBox[0] != null) throw new IOException(errBox[0]);

        // ④ 终检：逐段 SHA1 已经在下载时校过，这里再核总长与容器头
        note = "校验影片完整性…";
        notifyChanged();
        verifyFinal(t, stage, info);

        // ⑤ 后处理：按机型策略定最终目录与文件名（通用版 = 中文片名目录 + 改名为片名）
        note = "整理文件…";
        notifyChanged();
        DeviceProfile prof = DeviceProfile.get();
        FilmNaming.Caps caps = FilmNaming.capsFor(dir);
        String title = (t.name == null || t.name.isEmpty()) ? t.hash : t.name;
        File out = FilmNaming.unique(dir, prof.finalName(title, info.extension, caps));
        if (!stage.renameTo(out)) {
            copyFile(stage, out);
            stage.delete();
        }
        meta.delete();
        t.fileName = out.getName();
        t.done = total;
        t.total = total;
        prof.afterDownload(out, t);
        note = "已完成：" + out.getName();
    }

    /**
     * 下一个云端分段并逐段校 SHA1：写满后<b>从文件里回读</b>该段字节再算摘要。
     *
     * <p>回读而不是边下边算，是为了让断点续传天然正确 —— 上一进程写进去的前缀与本次
     * 续上的部分，拼出来的摘要一定等于落盘内容，不用管中途崩在哪一次。</p>
     */
    private void cdnSegment(AimeiCdn.Segment seg, java.nio.channels.FileChannel ch, File stage,
                            int idx, long[] segDone, boolean[] segOk, AtomicLong totalDone)
            throws IOException {
        synchronized (segOk) {
            if (segOk[idx]) return;    // 已校验过的段直接复用，绝不重下
        }
        long need = seg.length;
        long have = clampSegDone(segDone, idx, need);
        for (int attempt = 1; have < need; attempt++) {
            if (cancel.get()) return;
            if (attempt > CDN_RESUME_RETRY) {
                throw new IOException("分段 " + seg.index + " 续传 " + CDN_RESUME_RETRY
                        + " 次仍未下完（已 " + human(have) + "/" + human(need) + "），CDN 可能在停流");
            }
            long from = seg.start + have;
            long to = seg.start + need - 1;
            HttpURLConnection c = null;
            InputStream in = null;
            try {
                c = AimeiCdn.openSegment(seg.url, have > 0 ? (have + "-" + (need - 1)) : null);
                int code = c.getResponseCode();
                if (code == 416) {
                    // 区间越界 == 这段其实已经在盘上了，直接去校验
                    have = need;
                    break;
                }
                if (code != 200 && code != 206) {
                    throw new IOException("分段 " + seg.index + " HTTP " + code);
                }
                if (have > 0 && code == 200) {
                    // 服务端忽略了 Range：只能整段重来，否则会把两段拼一起
                    segDone[idx] = 0;
                    have = 0;
                }
                in = c.getInputStream();
                c.setReadTimeout(CDN_STALL_TIMEOUT_MS);
                byte[] buf = new byte[BUF];
                long pos = from;
                while (have < need) {
                    if (cancel.get()) return;
                    int len = in.read(buf, 0, (int) Math.min(buf.length, need - have));
                    if (len <= 0) break;
                    writeAt(ch, buf, len, pos);
                    pos += len;
                    have += len;
                    segDone[idx] = have;
                    totalDone.addAndGet(len);
                    throttle(len);
                }
            } catch (java.net.SocketTimeoutException e) {
                Log.d(TAG, "cdn seg " + seg.index + " 停滞，第 " + attempt + " 次续传");
            } catch (IOException e) {
                if (cancel.get()) return;
                Log.d(TAG, "cdn seg " + seg.index + " 第 " + attempt + " 次失败：" + e);
            } finally {
                closeQuietly(in);
                if (c != null) c.disconnect();
            }
            if (have >= need) break;
            if (cancel.get()) return;
        }

        if (have < need) throw new IOException("分段 " + seg.index + " 未下满（"
                + human(have) + "/" + human(need) + "）");
        String actual = sha1Range(stage, seg.start, seg.length);
        if (!seg.sha1sum.isEmpty() && !seg.sha1sum.equalsIgnoreCase(actual)) {
            // 写满但摘要不对 = 数据真的错了，重下这一段才有意义，继续续传只会浪费带宽
            synchronized (segOk) {
                segOk[idx] = false;
            }
            segDone[idx] = 0;
            totalDone.addAndGet(-have);
            throw new IOException("分段 " + seg.index + " SHA1 校验不符（期望 " + head(seg.sha1sum)
                    + " 实际 " + head(actual) + "，偏移 " + seg.start + "）—— 该段已作废，重下会从这一段重来");
        }
        synchronized (segOk) {
            segOk[idx] = true;
        }
        Log.d(TAG, "cdn seg " + seg.index + " ok sha1=" + head(actual));
    }

    /** 断点元数据里的偏移不可能超过段长（库被改过 / 分段方案变了），超了就从零开始。 */
    private static long clampSegDone(long[] segDone, int idx, long need) {
        long v = segDone[idx];
        if (v < 0 || v > need) {
            segDone[idx] = 0;
            return 0;
        }
        return v;
    }

    private static long writeAt(java.nio.channels.FileChannel ch, byte[] buf, int len, long pos)
            throws IOException {
        ByteBuffer bb = ByteBuffer.wrap(buf, 0, len);
        long written = 0;
        while (bb.hasRemaining()) {
            int k = ch.write(bb, pos + written);
            if (k <= 0) throw new IOException("写入返回 " + k);
            written += k;
        }
        return written;
    }

    /** 最终校验：总长 + 容器头 + 分段数一致。任一不过就不产出正式文件。 */
    private void verifyFinal(Dl t, File stage, AimeiCdn.CdnInfo info) throws IOException {
        long len = stage.length();
        if (len != info.fileSize) {
            throw new IOException("大小校验失败：落盘 " + len + " 字节，云端声明 " + info.fileSize + " 字节");
        }
        if (!info.consistent()) {
            throw new IOException("分段清单不自洽：各段长度之和对不上 fileSize");
        }
        long sumDone = 0;
        for (AimeiCdn.Segment s : info.segments) sumDone += s.length;
        if (sumDone != info.fileSize) {
            throw new IOException("分段长度之和 " + sumDone + " 与 fileSize " + info.fileSize + " 不符");
        }
        if ("mkv".equalsIgnoreCase(info.extension)) {
            InputStream in = null;
            try {
                in = new FileInputStream(stage);
                byte[] head = new byte[4];
                int off = 0;
                while (off < 4) {
                    int r = in.read(head, off, 4 - off);
                    if (r <= 0) break;
                    off += r;
                }
                if (off < 4 || head[0] != EBML_MAGIC[0] || head[1] != EBML_MAGIC[1]
                        || head[2] != EBML_MAGIC[2] || head[3] != EBML_MAGIC[3]) {
                    throw new IOException("文件头不是 Matroska（读到 " + hex(head, off) + "），拼接结果不可播");
                }
            } finally {
                closeQuietly(in);
            }
        }
    }

    private static String hex(byte[] b, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(String.format(java.util.Locale.ROOT, "%02X", b[i] & 0xFF));
        return sb.length() == 0 ? "空" : sb.toString();
    }

    private static String head(String s) {
        if (s == null) return "";
        return s.length() <= 12 ? String.valueOf(s) : s.substring(0, 12);
    }

    /** 回读文件的一段算 SHA1（十六进制小写）。 */
    static String sha1Range(File f, long start, long length) throws IOException {
        InputStream in = null;
        java.security.MessageDigest md;
        try {
            md = java.security.MessageDigest.getInstance("SHA-1");
        } catch (Exception e) {
            throw new IOException("设备不支持 SHA-1", e);
        }
        try {
            in = new FileInputStream(f);
            long skip = start;
            while (skip > 0) {
                long s = in.skip(skip);
                if (s <= 0) throw new IOException("回读偏移越界：" + start);
                skip -= s;
            }
            byte[] buf = new byte[BUF];
            long left = length;
            while (left > 0) {
                int r = in.read(buf, 0, (int) Math.min(buf.length, left));
                if (r <= 0) break;
                md.update(buf, 0, r);
                left -= r;
            }
            if (left > 0) throw new IOException("回读不足：" + (length - left) + "/" + length + " 字节");
            byte[] dg = md.digest();
            StringBuilder sb = new StringBuilder(dg.length * 2);
            for (byte b : dg) sb.append(String.format(java.util.Locale.ROOT, "%02x", b & 0xFF));
            return sb.toString();
        } finally {
            closeQuietly(in);
        }
    }

    // ==================== hash 片源的断点元数据 ====================

    /** 读分段进度。对不上（换了影片 / 换了文件大小 / 段数变了）就返回 false，让上层从零开始。 */
    private static boolean readCdnMeta(File meta, String hash, long total, int segs,
                                       long[] segDone, boolean[] segOk) {
        if (!meta.exists() || hash == null) return false;
        InputStream in = null;
        try {
            in = new FileInputStream(meta);
            byte[] buf = new byte[(int) Math.min(meta.length(), 1024 * 1024)];
            int off = 0;
            int n;
            while (off < buf.length && (n = in.read(buf, off, buf.length - off)) > 0) off += n;
            JSONObject o = new JSONObject(new String(buf, 0, off, "UTF-8"));
            if (!"cdn".equals(o.optString("kind"))) return false;
            if (!hash.equalsIgnoreCase(o.optString("hash"))) return false;
            if (o.optLong("total", -1) != total) return false;
            JSONArray a = o.optJSONArray("segs");
            if (a == null || a.length() != segs) return false;
            for (int i = 0; i < segs; i++) {
                JSONObject s = a.optJSONObject(i);
                if (s == null) return false;
                long d = s.optLong("done", -1);
                if (d < 0) return false;
                segDone[i] = d;
                segOk[i] = s.optBoolean("ok");
            }
            return true;
        } catch (Throwable e) {
            return false;
        } finally {
            closeQuietly(in);
        }
    }

    private void writeCdnMeta(File meta, String hash, long total, long[] segDone, boolean[] segOk) {
        OutputStream out = null;
        try {
            JSONObject o = new JSONObject();
            o.put("kind", "cdn");
            o.put("hash", hash);
            o.put("total", total);
            JSONArray a = new JSONArray();
            for (int i = 0; i < segDone.length; i++) {
                JSONObject s = new JSONObject();
                s.put("i", i);
                s.put("done", segDone[i]);
                boolean ok;
                synchronized (segOk) {
                    ok = segOk[i];
                }
                s.put("ok", ok);
                a.put(s);
            }
            o.put("segs", a);
            o.put("time", System.currentTimeMillis());
            out = new FileOutputStream(meta);
            out.write(o.toString().getBytes("UTF-8"));
            out.flush();
        } catch (Throwable ignored) {
        } finally {
            closeQuietly(out);
        }
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
                    throttle(n);            // 后台限速（下载管理页在前台时是空操作）
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
     * <p><b>删除边界</b>：向上找名为「山姆影库」的下载根目录做锚点，只允许删到它为止。
     * 找不到锚点（用户自定义了奇怪的落盘目录）时**最多只删文件所在的那一级**，
     * 绝不继续上溯 —— 否则一串空目录删上去可能把 {@code /mnt/usb} 这种挂载点删掉。</p>
     */
    private void pruneEmptyDirs(File dir) {
        try {
            File anchor = null;
            for (File f = dir; f != null; f = f.getParentFile()) {
                if ("山姆影库".equals(f.getName())) {
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

    /**
     * 下载速率：**字节/秒 → Mbps**。
     *
     * <p>换算别弄反：{@code MB/s} 是字节、{@code Mbps} 是比特，{@code 1MB/s = 8Mbps}。
     * 网络口径按十进制算（1Mbps = 1,000,000 bit/s），所以是 {@code B/s × 8 ÷ 1e6}。</p>
     */
    public static String mbps(double bytesPerSec) {
        if (bytesPerSec <= 0) return "0.0Mbps";
        double v = bytesPerSec * 8.0 / 1000000.0;
        if (v >= 100) return String.format(java.util.Locale.CHINA, "%.0fMbps", v);
        return String.format(java.util.Locale.CHINA, "%.1fMbps", v);
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

    /**
     * 剩余时长的口语化表达：{@code 约 12 分钟} / {@code 约 1 小时 5 分}。
     *
     * <p>故意只给到「分钟」这个粒度 —— ETA 本来就是估的，写成 {@code 12:34} 反而像精确值。</p>
     */
    public static String duration(long ms) {
        long sec = ms / 1000;
        if (sec < 30) return "不到 1 分钟";
        if (sec < 90) return "约 1 分钟";
        long min = (sec + 30) / 60;              // 四舍五入到分钟
        if (min < 60) return "约 " + min + " 分钟";
        long h = min / 60;
        long m = min % 60;
        if (h < 10 && m > 0) return "约 " + h + " 小时 " + m + " 分";
        return "约 " + h + " 小时";
    }
}
