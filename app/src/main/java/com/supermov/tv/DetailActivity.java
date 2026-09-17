package com.supermov.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 影片详情：海报 + 简介 + 三个操作（在线播放 / 下载 / 转存）。
 *
 * <p>三个入口**各自跳到自己的模块**：</p>
 * <ul>
 *   <li><b>▶ 在线播放</b> → {@link PlayerActivity}（原画直链 + 本地代理 + M3U8 兜底）</li>
 *   <li><b>⬇ 下载</b> → 选文件 → 选落盘目录 → 入队 → 跳 {@link DownloadActivity}</li>
 *   <li><b>⇪ 转存</b> → 先查网盘里有没有，没有才转存；转存后校验有没有缺文件</li>
 * </ul>
 *
 * <p><b>多文件与多级文件夹</b>：转存下来的分享经常是一整个文件夹，里面可能是多集剧、
 * 上下部、CD1/CD2，或再套一层「季」目录。所以这里不做「自动挑一个最大的」，
 * 而是把该片名下的**全部**视频递归枚举出来让用户挑：播放走单选，下载走多选（默认全选）。
 * 下载时**按网盘里的目录结构原样落盘**（{@code 目标目录/片名文件夹/第二季/03.mkv}）——
 * 网盘里是什么层级，本地就是什么层级，不同文件夹里的同名集也不会互相覆盖。</p>
 */
public class DetailActivity extends Activity {

    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private ImageView ivPic;
    private TextView tvName;
    private TextView tvContent;
    private TextView tvIntro;
    private TextView btnPlay;
    private TextView btnDownload;
    private TextView btnTransfer;
    private TextView tvTransferDir;
    private TextView tvTransferResult;

    private Site.Detail detail;
    private String movieName = "";
    private String picUrl = "";
    private String shareUrl = "";
    private String sharePwd = "";
    private int fid;
    private String tid = "";

    private boolean transferring = false;
    /** 定位网盘文件是异步的，防止用户连按几次按钮并发拉一堆请求 */
    private boolean locating = false;
    /** 命中项的文件夹名：列表里只显示它**里面**的相对路径，避免每行都重复一遍片名 */
    private String anchorFolder = "";

    /** 拿到「该片名下全部视频文件」后的回调；files 为空时 err 给出原因。 */
    private interface FilesCb {
        void on(List<BaiduPan.PlayFile> files, String err);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_detail);

        ivPic = findViewById(R.id.ivDetailPic);
        tvName = findViewById(R.id.tvDetailName);
        tvContent = findViewById(R.id.tvDetailContent);
        tvIntro = findViewById(R.id.tvDetailIntro);
        btnPlay = findViewById(R.id.btnPlay);
        btnDownload = findViewById(R.id.btnDownload);
        btnTransfer = findViewById(R.id.btnTransfer);
        tvTransferDir = findViewById(R.id.tvTransferDir);
        tvTransferResult = findViewById(R.id.tvTransferResult);

        fid = getIntent().getIntExtra("fid", 0);
        tid = nz(getIntent().getStringExtra("tid"));
        movieName = nz(getIntent().getStringExtra("name"));
        picUrl = nz(getIntent().getStringExtra("pic"));
        if (!movieName.isEmpty()) setTitle(movieName, "");
        loadPic();

        pool.execute(() -> {
            Site.Detail d = Site.detail(fid, tid);
            main.post(() -> bindDetail(d));
        });

        btnPlay.setOnClickListener(v -> doPlay());
        btnDownload.setOnClickListener(v -> doDownload());
        btnTransfer.setOnClickListener(v -> doTransfer());
    }

    /** 海报：列表页已经带了 pic 就直接用；没有就等详情解析出来再补。 */
    private void loadPic() {
        if (ivPic == null || picUrl.isEmpty()) return;
        ImageLoader.load(picUrl, ivPic);
    }

    private void bindDetail(Site.Detail d) {
        detail = d;
        if (d == null || (d.movie.name.isEmpty() && d.boxes.isEmpty())) {
            tvName.setText("加载失败");
            tvContent.setText("详情获取失败（Cookie 失效或网络问题）。");
            setActionsVisible(false);
            return;
        }
        if (d.movie.name != null && !d.movie.name.isEmpty()) movieName = d.movie.name;
        // 片名与「日期 · 片长」并到同一行（原来是上下两行，白占一行纵向空间）
        setTitle(movieName, d.movie.remarks);
        // 资料区（到「◎上映日期」为止）与它下面的整块正文分两个 TextView：
        // 后者在布局里锁死 maxLines=8 —— 8 是屏幕折行后的行数，只能在布局层限，文本层裁不了
        String[] parts = Site.splitAtUpcoming(d.movie.content);
        tvContent.setText(parts[0].isEmpty() ? "（无简介）" : parts[0]);
        if (parts.length > 1 && !parts[1].isEmpty()) {
            tvIntro.setText(parts[1]);
            tvIntro.setVisibility(View.VISIBLE);
        } else {
            tvIntro.setText("");
            tvIntro.setVisibility(View.GONE);
        }
        // 列表页没给海报时，用详情页解析出来的
        if (picUrl.isEmpty() && d.movie.pic != null && !d.movie.pic.isEmpty()) {
            picUrl = d.movie.pic;
            loadPic();
        }

        // 线路区块已按要求去掉：只取第一条链接用于播放/下载/转存，不再展示「播放线路」
        if (d.boxes.isEmpty()) {
            tvTransferResult.setText("该影片没有可用的百度网盘链接");
            setActionsVisible(false);
            return;
        }
        Site.Box first = d.boxes.get(0);
        shareUrl = first.url;
        sharePwd = first.pwd;

        setActionsVisible(true);
        // 电视遥控器：进页面就把焦点放到主操作上，否则满屏静态文字看不出能按哪儿
        btnPlay.post(() -> btnPlay.requestFocus());
        tvTransferDir.setText("网盘转存目录：" + Settings.saveDir()
                + "\n下载落盘目录：" + Settings.downloadDir()
                + "\n（均可在 设置 中修改）");
        String last = Settings.lastTransfer();
        if (last != null && !last.isEmpty()) {
            tvTransferResult.setText("最近转存：" + humanLast(last));
        }
    }

    private void setActionsVisible(boolean visible) {
        int v = visible ? View.VISIBLE : View.GONE;
        btnPlay.setVisibility(v);
        btnDownload.setVisibility(v);
        btnTransfer.setVisibility(v);
        tvTransferDir.setVisibility(v);
    }

    private String humanLast(String json) {
        try {
            org.json.JSONObject o = new org.json.JSONObject(json);
            long ts = o.optLong("time", 0);
            String dir = o.optString("dir", "");
            boolean ok = o.optBoolean("ok", false);
            String t = new java.text.SimpleDateFormat("M-d HH:mm", java.util.Locale.CHINA)
                    .format(new java.util.Date(ts));
            return (ok ? "✔ " : "✘ ") + t + " → " + dir;
        } catch (Exception e) {
            return json;
        }
    }

    // ==================== 公共：先定位出「全部文件」 ====================

    /**
     * 在网盘里把这部片名下的全部视频枚举出来；目录里没有就先自动转存一次再枚举。
     * 结果回主线程交给 cb。
     */
    private void withFiles(final FilesCb cb) {
        if (locating) return;
        locating = true;
        tvTransferResult.setText("正在定位网盘文件…");
        pool.execute(() -> {
            BaiduPan.Resolved rs = ensureResolved();
            final List<BaiduPan.PlayFile> fs = rs.files;
            final String folder = rs.anchorName == null ? "" : rs.anchorName;
            final String err = fs.isEmpty()
                    ? (rs.message == null || rs.message.isEmpty() ? "网盘里没有找到视频文件" : rs.message)
                    : "";
            main.post(() -> {
                locating = false;
                anchorFolder = folder;
                cb.on(fs, err);
            });
        });
    }

    /** 定位；目录里没有就转存一次再定位。 */
    private BaiduPan.Resolved ensureResolved() {
        String rootDir = Settings.saveDir();
        BaiduPan.Resolved rs = BaiduPan.resolveAll(rootDir, movieName);
        if (!rs.files.isEmpty()) return rs;

        // 名字没搜到 ≠ 没转存过：分享里的文件夹可能被改过名。这时用「上次转存这部片」
        // 记下的落地路径精确回查（片名对不上就返回空，绝不会串到别的片子）
        String rem = rememberedPath();
        if (!rem.isEmpty()) {
            BaiduPan.Resolved at = BaiduPan.resolveAt(rootDir, rem);
            if (!at.files.isEmpty()) return at;
        }

        // 刚手动转过存（15 秒内）-> 不再自动重转一次，否则只会拿到 errno=12「已有同名文件」，
        // 把真正的原因（分享里没有视频文件之类）盖掉
        if (recentlyTransferred()) return rs;

        main.post(() -> tvTransferResult.setText("网盘里还没有，正在转存到 " + rootDir + " …"));
        BaiduPan.TransferResult tr;
        try {
            tr = BaiduPan.transfer(shareUrl, sharePwd, rootDir);
        } catch (Throwable e) {
            tr = new BaiduPan.TransferResult();
            tr.ok = false;
            tr.message = "转存异常：" + e.getClass().getSimpleName();
        }
        String toPath = tr.toPaths.isEmpty() ? "" : tr.toPaths.get(0);
        Settings.recordTransfer(rootDir, tr.ok, movieName, toPath);

        // 转存接口把「实际落地路径」告诉了我们 -> 直接按它精确取，不再靠片名去猜
        // （分享里的文件夹名和片名经常不一样）
        if (tr.ok && !toPath.isEmpty()) {
            BaiduPan.Resolved exact = BaiduPan.resolveAt(rootDir, toPath);
            if (!exact.files.isEmpty()) return exact;
        }
        BaiduPan.Resolved again = BaiduPan.resolveAll(rootDir, movieName);
        if (!again.files.isEmpty()) return again;
        if (!tr.ok && (again.message == null || again.message.isEmpty())) again.message = tr.message;
        return again;
    }

    /**
     * 最近 15 秒内成功转存过**这部片**？
     *
     * <p>必须连片名一起比：以前只看时间窗，于是「刚转存完 A，马上点 B」时 B 也被当成
     * 「刚刚转过存」而跳过转存，最后拿到的是 A 的文件。</p>
     */
    private boolean recentlyTransferred() {
        try {
            String t = Settings.lastTransferTitle();
            if (t == null || t.isEmpty() || !t.equals(movieName)) return false;
            org.json.JSONObject o = new org.json.JSONObject(Settings.lastTransfer());
            return o.optBoolean("ok", false)
                    && System.currentTimeMillis() - o.optLong("time", 0) < 15000;
        } catch (Throwable e) {
            return false;
        }
    }

    /** 上次转存「这部片」落地的网盘路径；片名不一致就返回空（防止张冠李戴）。 */
    private String rememberedPath() {
        try {
            String t = Settings.lastTransferTitle();
            if (t == null || t.isEmpty() || !t.equals(movieName)) return "";
            return Settings.lastTransferPath();
        } catch (Throwable e) {
            return "";
        }
    }

    // ==================== ① 在线播放 ====================

    private void doPlay() {
        if (!CookieStore.hasBaiduLogin()) {
            needAuth();
            return;
        }
        if (shareUrl.isEmpty()) {
            toast("没有可用的百度网盘链接");
            return;
        }
        withFiles((fs, err) -> {
            if (!err.isEmpty()) {
                tvTransferResult.setText("✘ " + err);
                toast(err);
                return;
            }
            if (fs.size() == 1) {
                playFile(fs.get(0));
            } else {
                showPickSingle(fs);
            }
        });
    }

    /** 多文件：单选一个来播。 */
    private void showPickSingle(final List<BaiduPan.PlayFile> files) {
        String[] labels = new String[files.size()];
        for (int i = 0; i < files.size(); i++) labels[i] = labelOf(files.get(i));
        AlertDialog dlg = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog)
                .setTitle("选择要播放的文件（共 " + files.size() + " 个）")
                .setItems(labels, (d, which) -> playFile(files.get(which)))
                .setNegativeButton("取消", null)
                .create();
        dlg.show();
        tuneDialog(dlg);
    }

    /** 带上 fsId/path 直接播指定文件，避免播放页再按片名猜一次而播错集。 */
    private void playFile(BaiduPan.PlayFile pf) {
        Intent it = new Intent(this, PlayerActivity.class);
        it.putExtra("fid", fid);
        it.putExtra("tid", tid);
        it.putExtra("name", movieName);
        it.putExtra("fsId", pf.fsId);
        it.putExtra("bpath", pf.path);
        it.putExtra("fname", pf.name);
        startActivity(it);
    }

    // ==================== ② 下载 ====================

    private void doDownload() {
        if (!CookieStore.hasBaiduLogin()) {
            needAuth();
            return;
        }
        if (shareUrl.isEmpty()) {
            toast("没有可用的百度网盘链接");
            return;
        }
        withFiles((fs, err) -> {
            if (!err.isEmpty()) {
                tvTransferResult.setText("✘ " + err);
                toast(err);
                return;
            }
            if (fs.size() == 1) {
                showDirPicker(fs);
            } else {
                showPickMulti(fs);
            }
        });
    }

    /** 多文件：多选（默认全选，遥控器上少按几次），选完再挑落盘目录。 */
    private void showPickMulti(final List<BaiduPan.PlayFile> files) {
        final String[] labels = new String[files.size()];
        for (int i = 0; i < files.size(); i++) labels[i] = labelOf(files.get(i));
        final boolean[] checked = new boolean[files.size()];
        for (int i = 0; i < checked.length; i++) checked[i] = true;

        final AlertDialog dlg = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog)
                .setTitle("选择要下载的文件（默认全选）")
                .setMultiChoiceItems(labels, checked, (d, which, isChecked) -> {
                    checked[which] = isChecked;
                    refreshPickButton(d, checked);
                })
                .setPositiveButton("下一步", (d, w) -> {
                    List<BaiduPan.PlayFile> sel = new ArrayList<>();
                    for (int i = 0; i < files.size(); i++) {
                        if (checked[i]) sel.add(files.get(i));
                    }
                    if (sel.isEmpty()) {
                        toast("没有选中任何文件");
                        return;
                    }
                    showDirPicker(sel);
                })
                .setNeutralButton("反选", null)
                .setNegativeButton("取消", null)
                .create();
        // 按钮默认点一下就关窗，所以「反选」要自己接管点击
        dlg.setOnShowListener(x -> {
            refreshPickButton(dlg, checked);
            dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                for (int i = 0; i < checked.length; i++) {
                    checked[i] = !checked[i];
                    dlg.getListView().setItemChecked(i, checked[i]);
                }
                refreshPickButton(dlg, checked);
            });
        });
        dlg.show();
        tuneDialog(dlg);
    }

    private void refreshPickButton(DialogInterface d, boolean[] checked) {
        int n = 0;
        for (boolean b : checked) {
            if (b) n++;
        }
        if (d instanceof AlertDialog) {
            TextView b = ((AlertDialog) d).getButton(AlertDialog.BUTTON_POSITIVE);
            if (b != null) {
                b.setText(n == 0 ? "请选择文件" : "下一步：选目录（" + n + " 个）");
            }
        }
    }

    /** TV 上用遥控器打路径太痛苦，所以列出所有可写位置让用户挑。 */
    private void showDirPicker(final List<BaiduPan.PlayFile> files) {
        final List<Storage.Target> ts = Storage.targets(this);
        final String[] labels = new String[ts.size() + 1];
        for (int i = 0; i < ts.size(); i++) {
            labels[i] = ts.get(i).label + "\n" + ts.get(i).dir;
        }
        labels[ts.size()] = "✎ 手动输入路径…";

        AlertDialog dlg = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog)
                .setTitle(files.size() == 1 ? "下载到" : ("下载 " + files.size() + " 个文件到"))
                .setItems(labels, (d, which) -> {
                    if (which == ts.size()) {
                        showManualDir(files);
                        return;
                    }
                    Storage.Target t = ts.get(which);
                    if (t.needAllFiles && !Storage.hasAllFiles(this)) {
                        Storage.requestAllFiles(this);
                        Toast.makeText(this, "请授予「所有文件访问」权限后重新点下载", Toast.LENGTH_LONG).show();
                        return;
                    }
                    startDownload(t.dir, files);
                })
                .create();
        dlg.show();
        tuneDialog(dlg);
    }

    private void showManualDir(final List<BaiduPan.PlayFile> files) {
        final EditText input = new EditText(this);
        input.setText(Settings.downloadDir());
        input.setSelection(input.getText().length());
        input.setTextSize(15);
        AlertDialog dlg = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog)
                .setTitle("下载目录（绝对路径）")
                .setView(input)
                .setPositiveButton("开始下载", (d, w) -> {
                    String v = input.getText().toString().trim();
                    if (v.isEmpty()) {
                        toast("路径不能为空");
                        return;
                    }
                    startDownload(v, files);
                })
                .setNegativeButton("取消", null)
                .create();
        dlg.show();
        widen(dlg, 1.3f);
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).requestFocus();
    }

    /**
     * 入队。**按网盘里的目录结构原样落盘**：{@code 目标目录/片名文件夹/第二季/03.mkv}。
     *
     * <p>{@code pf.rel} 的根是「转存根目录」（如 /超级影库），所以网盘里的
     * {@code /超级影库/片名/第二季/03.mkv} 在本地就是
     * {@code 下载目录/片名/第二季/03.mkv} —— 一层不少，原汁原味。
     * 顺带好处：不同文件夹下的同名集不会互相覆盖。</p>
     */
    private void startDownload(final String dir, final List<BaiduPan.PlayFile> files) {
        tvTransferResult.setText("正在加入下载队列…");
        final String rootDir = Settings.saveDir();
        pool.execute(() -> {
            final Set<String> used = new HashSet<>();
            int okCount = 0;
            long totalBytes = 0;
            StringBuilder listing = new StringBuilder();
            for (BaiduPan.PlayFile pf : files) {
                String subRel = safeRel(pf.rel);
                String targetDir = subRel.isEmpty() ? dir : joinPath(dir, subRel);
                String fileName = uniqueName(targetDir, Dl.safeName(pf.name), used);

                Dl t = new Dl();
                t.fid = fid;
                t.tid = tid;
                t.name = movieName;
                t.rootDir = rootDir;
                t.fsId = pf.fsId;
                t.bpath = pf.path;
                t.fileName = fileName;
                t.dir = targetDir;
                t.total = pf.size;
                t.created = System.currentTimeMillis();

                long id = DlEngine.get().enqueue(this, t);
                if (id > 0) {
                    okCount++;
                    totalBytes += pf.size;
                    if (listing.length() < 600) {
                        listing.append("\n· ").append(subRel.isEmpty() ? "" : subRel + "/").append(fileName);
                    }
                }
            }
            final int n = okCount;
            final long tb = totalBytes;
            final String list = listing.toString();
            main.post(() -> {
                if (n <= 0) {
                    tvTransferResult.setText("✘ 加入下载队列失败（目录不可写？）：" + dir);
                    toast("加入下载队列失败");
                    return;
                }
                tvTransferResult.setText("✔ 已加入下载队列 " + n + " 个文件"
                        + (tb > 0 ? "，共 " + DlEngine.human(tb) : "")
                        + "\n按网盘原目录结构落盘到：" + dir + list);
                toast("已开始下载 " + n + " 个文件");
                startActivity(new Intent(this, DownloadActivity.class));
            });
        });
    }

    // ==================== ③ 转存 ====================

    /**
     * 转存。**先查后转**：
     * <ol>
     *   <li>网盘里已经有这部影片 → 直接提示「已经转存过了」，**绝不重复转一次**
     *       （对已存在的文件再转，百度会回 errno=4/-10 之类，被误报成「网盘空间不足」）</li>
     *   <li>确实没有 → 转存</li>
     *   <li>转存后按分享清单做**完整性校验**：多集剧 / 多级文件夹最容易缺文件，
     *       缺了就明确列出缺哪些，不给「转存成功」的假象</li>
     * </ol>
     */
    private void doTransfer() {
        if (transferring) return;
        if (shareUrl.isEmpty()) {
            toast("没有可转存的百度网盘链接");
            return;
        }
        if (!CookieStore.hasBaiduLogin()) {
            needAuth();
            return;
        }
        transferring = true;
        tvTransferResult.setText("正在检查网盘里是否已有这部影片…");
        final String url = shareUrl;
        final String pwd = sharePwd;
        final String dir = Settings.saveDir();
        pool.execute(() -> {
            // ① 先查：已经转存过就到此为止（先按片名；名字被改过就用上次的落地路径回查）
            BaiduPan.Resolved have = BaiduPan.resolveAll(dir, movieName);
            if (have.files.isEmpty()) {
                String rem = rememberedPath();
                if (!rem.isEmpty()) have = BaiduPan.resolveAt(dir, rem);
            }
            if (!have.files.isEmpty()) {
                final List<BaiduPan.PlayFile> fs = have.files;
                final String where = have.anchorPath;
                main.post(() -> {
                    transferring = false;
                    tvTransferResult.setText("✔ 网盘里已经有这部影片（" + fs.size() + " 个文件），无需重复转存"
                            + "\n位置：" + where);
                    toast("已转存过，无需重复转存");
                    showAfterTransfer(fs, "已经转存过了", "网盘里已有 " + fs.size() + " 个视频文件，接下来？");
                });
                return;
            }

            // ② 确实没有 -> 转存
            main.post(() -> tvTransferResult.setText("网盘里还没有，正在转存到 " + dir + " …"));
            BaiduPan.TransferResult r;
            try {
                r = BaiduPan.transfer(url, pwd, dir);
            } catch (Throwable e) {
                // 兜底：任何异常都不允许闪退，转成失败提示
                r = new BaiduPan.TransferResult();
                r.ok = false;
                r.message = "转存异常：" + e.getClass().getSimpleName();
            }
            Settings.recordTransfer(dir, r.ok, movieName,
                    r.toPaths.isEmpty() ? "" : r.toPaths.get(0));
            final BaiduPan.TransferResult fr = r;

            if (!fr.ok) {
                main.post(() -> {
                    transferring = false;
                    String t = new java.text.SimpleDateFormat("M-d HH:mm", java.util.Locale.CHINA)
                            .format(new java.util.Date());
                    tvTransferResult.setText("✘ " + fr.message + "   (" + t + ")");
                    toast(fr.message);
                });
                return;
            }

            // ③ 转存后：枚举 + 按分享清单校验完整性（优先按转存接口给的落地路径精确取）
            BaiduPan.Resolved rs = fr.toPaths.isEmpty()
                    ? new BaiduPan.Resolved()
                    : BaiduPan.resolveAt(dir, fr.toPaths.get(0));
            if (rs.files.isEmpty()) rs = BaiduPan.resolveAll(dir, movieName);
            List<BaiduPan.PlayFile> expect =
                    BaiduPan.shareManifest(fr.shareid, fr.uk, url);
            final List<BaiduPan.PlayFile> got = rs.files;
            final List<BaiduPan.PlayFile> miss = expect.isEmpty()
                    ? new ArrayList<BaiduPan.PlayFile>() : missingOf(expect, got);
            final int expectN = expect.size();
            final String where = rs.anchorPath.isEmpty() ? dir : rs.anchorPath;

            main.post(() -> {
                transferring = false;
                String t = new java.text.SimpleDateFormat("M-d HH:mm", java.util.Locale.CHINA)
                        .format(new java.util.Date());
                if (got.isEmpty()) {
                    tvTransferResult.setText("✘ 转存似乎成功，但网盘里没找到视频文件"
                            + (rs.message.isEmpty() ? "" : "：" + rs.message) + "   (" + t + ")");
                    toast("转存后没找到视频文件");
                    return;
                }
                if (expectN <= 0) {
                    // 分享清单拿不到（接口失败）-> 不做「不完整」的判断，避免误报
                    tvTransferResult.setText("✔ 转存完成，网盘里找到 " + got.size() + " 个视频文件"
                            + "\n位置：" + where + "   (" + t + ")");
                    showAfterTransfer(got, "转存完成", "网盘里找到 " + got.size() + " 个视频文件，接下来？");
                    return;
                }
                if (miss.isEmpty()) {
                    tvTransferResult.setText("✔ 转存完成且完整：分享里 " + expectN + " 个视频文件，网盘里 " + got.size() + " 个，全都在"
                            + "\n位置：" + where + "   (" + t + ")");
                    toast("转存完成，文件齐全");
                    showAfterTransfer(got, "转存完成（已校验，文件齐全）",
                            "分享里 " + expectN + " 个视频文件，网盘里 " + got.size() + " 个，一个不缺。接下来？");
                    return;
                }
                tvTransferResult.setText("⚠ 转存完成，但可能不完整：分享里 " + expectN + " 个视频文件，网盘里只有 "
                        + got.size() + " 个，缺 " + miss.size() + " 个"
                        + "\n位置：" + where + "   (" + t + ")");
                showIncomplete(got, miss, expectN);
            });
        });
    }

    // ==================== 转存完成后的分流 ====================

    private void showAfterTransfer(final List<BaiduPan.PlayFile> files, String title, String msg) {
        AlertDialog dlg = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog)
                .setTitle(title)
                .setMessage(msg)
                .setPositiveButton("▶ 在线播放", (d, w) -> {
                    if (files.size() == 1) playFile(files.get(0));
                    else showPickSingle(files);
                })
                .setNeutralButton("⬇ 下载", (d, w) -> {
                    if (files.size() == 1) showDirPicker(files);
                    else showPickMulti(files);
                })
                .setNegativeButton("稍后", null)
                .create();
        dlg.show();
        widen(dlg, 1.3f);
    }

    /** 校验发现缺文件：如实列出缺哪些，而不是一句「转存成功」糊过去。 */
    private void showIncomplete(final List<BaiduPan.PlayFile> have,
                                final List<BaiduPan.PlayFile> miss, int expectN) {
        StringBuilder sb = new StringBuilder();
        sb.append("分享里有 ").append(expectN).append(" 个视频文件，网盘里只找到 ")
                .append(have.size()).append(" 个，缺 ").append(miss.size()).append(" 个：\n");
        for (int i = 0; i < miss.size() && i < 10; i++) {
            sb.append("· ").append(labelOf(miss.get(i))).append("\n");
        }
        if (miss.size() > 10) sb.append("… 其余 ").append(miss.size() - 10).append(" 个\n");
        sb.append("\n常见原因：该分享本身就不完整、百度对个别文件做了屏蔽，或转存时被限流。");

        AlertDialog dlg = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog)
                .setTitle("⚠ 转存可能不完整")
                .setMessage(sb.toString())
                .setPositiveButton("先看已有的", (d, w) -> showAfterTransfer(have,
                        "转存完成（可能缺文件）", "网盘里现有 " + have.size() + " 个视频文件，接下来？"))
                .setNeutralButton("复制缺失清单", (d, w) -> copyMissing(miss))
                .setNegativeButton("知道了", null)
                .create();
        dlg.show();
        widen(dlg, 1.3f);
    }

    private void copyMissing(List<BaiduPan.PlayFile> miss) {
        StringBuilder sb = new StringBuilder();
        for (BaiduPan.PlayFile f : miss) {
            sb.append(f.rel == null || f.rel.isEmpty() ? "" : f.rel + "/").append(f.name).append("\n");
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("missing", sb.toString()));
        toast("缺失清单已复制（" + miss.size() + " 项）");
    }

    /**
     * 分享清单 vs 网盘实际，返回「分享里有、网盘里没有」的那些。
     *
     * <p>两轮比对：先按「文件名 + 体积」精确匹配；剩下的再只按文件名匹配一次。
     * 宁可少报也不误报 —— 体积字段缺失或口径不同时不会把好好的文件算成缺集。</p>
     */
    private static List<BaiduPan.PlayFile> missingOf(List<BaiduPan.PlayFile> expect,
                                                     List<BaiduPan.PlayFile> actual) {
        List<BaiduPan.PlayFile> rest = new ArrayList<>();
        Map<String, Integer> byKey = count(actual, true);
        for (BaiduPan.PlayFile f : expect) {
            String k = key(f, true);
            Integer n = byKey.get(k);
            if (n != null && n > 0) {
                byKey.put(k, n - 1);
            } else {
                rest.add(f);
            }
        }
        if (rest.isEmpty()) return rest;

        List<BaiduPan.PlayFile> miss = new ArrayList<>();
        Map<String, Integer> byName = count(actual, false);
        for (BaiduPan.PlayFile f : rest) {
            String k = key(f, false);
            Integer n = byName.get(k);
            if (n != null && n > 0) {
                byName.put(k, n - 1);
            } else {
                miss.add(f);
            }
        }
        return miss;
    }

    private static Map<String, Integer> count(List<BaiduPan.PlayFile> list, boolean withSize) {
        Map<String, Integer> m = new HashMap<>();
        for (BaiduPan.PlayFile f : list) {
            String k = key(f, withSize);
            Integer n = m.get(k);
            m.put(k, n == null ? 1 : n + 1);
        }
        return m;
    }

    private static String key(BaiduPan.PlayFile f, boolean withSize) {
        String n = f.name == null ? "" : f.name.toLowerCase().replaceAll("\\s+", "");
        return withSize ? n + "|" + f.size : n;
    }

    // ==================== 对话框统一调优 ====================
    //
    // 剧集与合集的文件名很长（片名 + 季 + 集 + 分辨率 + 音轨 + 压制组…），而系统默认的
    // 列表对话框又窄又用中号字，一行放不下几个字，用户根本分不清是第几集。所以统一做两件事：
    //   ① 宽度在「当前实际宽度」基础上**加大 30%**（上限屏宽的 94%，不越出屏幕）
    //   ② 条目字号调小一档并允许折成两行 —— 同样的宽度能多装约 1/4 的字

    /** 列表条目字号（sp）。系统默认约 16sp，调小一档能让长片名显示得更完整。 */
    private static final float DIALOG_ITEM_SP = 13f;

    /** 加宽 + 缩字号。必须在 {@code dlg.show()} 之后调用（ListView 要 show 时才建出来）。 */
    private void tuneDialog(final AlertDialog dlg) {
        widen(dlg, 1.3f);
        shrinkItems(dlg);
    }

    /** 把窗口宽度在「当前实际宽度」基础上放大 factor 倍，上限为屏宽的 94%。 */
    private void widen(final AlertDialog dlg, final float factor) {
        final Window w = dlg.getWindow();
        if (w == null) return;
        // 布局完成前 getWidth() 还是 0，post 到下一轮再量
        w.getDecorView().post(() -> {
            int base = w.getDecorView().getWidth();
            int screen = getResources().getDisplayMetrics().widthPixels;
            int target = base > 0 ? (int) (base * factor) : (int) (screen * 0.85f);
            int cap = (int) (screen * 0.94f);
            if (target > cap) target = cap;
            w.setLayout(target, ViewGroup.LayoutParams.WRAP_CONTENT);
        });
    }

    /** 列表条目：缩小字号、允许两行、超出用省略号（长片名 / 长路径尽量显示完整）。 */
    private void shrinkItems(AlertDialog dlg) {
        ListView lv = dlg.getListView();
        if (lv == null) return;
        // 条目是回收复用的，后滚出来的孩子也要处理 -> 挂个层级监听
        lv.setOnHierarchyChangeListener(new ViewGroup.OnHierarchyChangeListener() {
            @Override
            public void onChildViewAdded(View parent, View child) {
                shrinkText(child);
            }

            @Override
            public void onChildViewRemoved(View parent, View child) {
            }
        });
        for (int i = 0; i < lv.getChildCount(); i++) shrinkText(lv.getChildAt(i));
    }

    private void shrinkText(View v) {
        if (v instanceof TextView) {
            TextView tv = (TextView) v;
            tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, DIALOG_ITEM_SP);
            tv.setSingleLine(false);
            tv.setMaxLines(2);
            tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        } else if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) shrinkText(g.getChildAt(i));
        }
    }

    // ==================== 工具 ====================

    /**
     * 列表项文案：相对目录 + 文件名 + 体积。
     *
     * <p>目录只显示「命中文件夹**里面**」的那几层 —— 片名文件夹本身在每一行重复一遍没有意义，
     * 还会把真正有用的集数 / 文件名挤到右边被省略掉。</p>
     */
    private String labelOf(BaiduPan.PlayFile pf) {
        String rel = relForShow(pf);
        rel = rel.isEmpty() ? "" : rel + " / ";
        String size = pf.size > 0 ? "   " + humanSize(pf.size) : "";
        return rel + pf.name + size;
    }

    /** 展示用的相对目录：去掉开头那一层「片名文件夹」。 */
    private String relForShow(BaiduPan.PlayFile pf) {
        String rel = pf.rel == null ? "" : pf.rel;
        if (anchorFolder.isEmpty()) return rel;
        if (rel.equals(anchorFolder)) return "";
        if (rel.startsWith(anchorFolder + "/")) return rel.substring(anchorFolder.length() + 1);
        return rel;
    }

    private String humanSize(long bytes) {
        if (bytes <= 0) return "";
        if (bytes >= 1024L * 1024 * 1024) {
            return String.format(java.util.Locale.CHINA, "%.2fGB", bytes / 1073741824.0);
        }
        if (bytes >= 1024 * 1024) {
            return String.format(java.util.Locale.CHINA, "%.0fMB", bytes / 1048576.0);
        }
        return (bytes / 1024) + "KB";
    }

    private void needAuth() {
        Toast.makeText(this, "未授权，请先到 设置→百度网盘扫码", Toast.LENGTH_LONG).show();
        startActivity(new Intent(this, QrActivity.class));
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    /** 网盘的相对目录（可能多级），逐段净化，保留层级。 */
    private static String safeRel(String rel) {
        if (rel == null || rel.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String seg : rel.split("/")) {
            String s = seg.replaceAll("[\\\\:*?\"<>|\\r\\n\\t]", "_").trim();
            s = s.replaceAll("^[.]+", "");
            if (s.isEmpty()) continue;
            if (sb.length() > 0) sb.append("/");
            sb.append(s);
        }
        return sb.toString();
    }

    private static String joinPath(String dir, String sub) {
        if (dir.endsWith("/")) return dir + sub;
        return dir + "/" + sub;
    }

    /** 同一落盘目录里重名（不同子文件夹下的同名集）自动加序号，避免互相覆盖。 */
    private static String uniqueName(String dirPath, String fileName, Set<String> used) {
        if (used.add(dirPath + "/" + fileName)) return fileName;
        int dot = fileName.lastIndexOf('.');
        String base = dot > 0 ? fileName.substring(0, dot) : fileName;
        String ext = dot > 0 ? fileName.substring(dot) : "";
        for (int i = 2; i < 200; i++) {
            String cand = base + " (" + i + ")" + ext;
            if (used.add(dirPath + "/" + cand)) return cand;
        }
        return fileName;
    }

    /**
     * 片名 + 同一行右侧的「日期 · 片长」角标。
     *
     * <p>原来角标单独占一行（片名 24sp 加粗白、角标 13sp 灰），白占一行。现在合成一行：
     * 片名保持原样式，角标缩小变灰，中间插一个 14dp 的固定空隙 —— 普通空格宽度随字号走、
     * 全角空格又太宽，所以用 {@link GapSpan} 精确占位。</p>
     */
    private void setTitle(String name, String meta) {
        String n = nz(name);
        String m = nz(meta);
        if (n.isEmpty() || m.isEmpty()) {
            tvName.setText(n.isEmpty() ? m : n);
            return;
        }
        android.text.SpannableString sp = new android.text.SpannableString(n + " " + m);
        int metaStart = n.length() + 1;
        sp.setSpan(new GapSpan((int) (14 * getResources().getDisplayMetrics().density)),
                n.length(), metaStart, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        int metaPx = (int) android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_SP, 13,
                getResources().getDisplayMetrics());
        sp.setSpan(new android.text.style.AbsoluteSizeSpan(metaPx),
                metaStart, sp.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sp.setSpan(new android.text.style.ForegroundColorSpan(0xFF9AA0A6),
                metaStart, sp.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        tvName.setText(sp);
    }

    /** 固定宽度的占位空隙（spannable 里放不了「多少 dp」的空白，只能自己占一格）。 */
    private static class GapSpan extends android.text.style.ReplacementSpan {
        private final int px;

        GapSpan(int px) { this.px = px; }

        @Override
        public int getSize(android.graphics.Paint paint, CharSequence text, int start, int end,
                           android.graphics.Paint.FontMetricsInt fm) {
            return px;
        }

        @Override
        public void draw(android.graphics.Canvas canvas, CharSequence text, int start, int end,
                         float x, int top, int y, int bottom, android.graphics.Paint paint) {
            // 只占位，不画任何东西
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
