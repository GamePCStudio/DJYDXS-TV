package com.supermov.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 影片详情：简介 + 百度网盘线路 + 三个操作（在线播放 / 下载 / 转存）。
 *
 * <p>三个入口**各自跳到自己的模块**：</p>
 * <ul>
 *   <li><b>▶ 在线播放</b> → {@link PlayerActivity}（原画直链 + 本地代理 + M3U8 兜底）</li>
 *   <li><b>⬇ 下载</b> → 选文件 → 选落盘目录 → 入队 → 跳 {@link DownloadActivity}</li>
 *   <li><b>⇪ 转存</b> → 转存到网盘，成功后直接给出「播放 / 下载」的下一步</li>
 * </ul>
 *
 * <p><b>多文件与多级文件夹</b>：转存下来的分享经常是一整个文件夹，里面可能是多集剧、
 * 上下部、CD1/CD2，或再套一层「季」目录。所以这里不做「自动挑一个最大的」，
 * 而是把该片名下的**全部**视频递归枚举出来让用户挑：播放走单选，下载走多选（默认全选）。
 * 下载时**按网盘里的相对目录结构落盘**（{@code 目标目录/第二季/03.mkv}），
 * 这样不同文件夹里的同名文件不会互相覆盖。</p>
 */
public class DetailActivity extends Activity {

    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private TextView tvName;
    private TextView tvMeta;
    private TextView tvContent;
    private LinearLayout boxGroup;
    private TextView btnPlay;
    private TextView btnDownload;
    private TextView btnTransfer;
    private TextView tvTransferDir;
    private TextView tvTransferResult;

    private Site.Detail detail;
    private String movieName = "";
    private String shareUrl = "";
    private String sharePwd = "";
    private int fid;
    private String tid = "";

    private boolean transferring = false;
    /** 定位网盘文件是异步的，防止用户连按几次按钮并发拉一堆请求 */
    private boolean locating = false;

    /** 拿到「该片名下全部视频文件」后的回调；files 为空时 err 给出原因。 */
    private interface FilesCb {
        void on(List<BaiduPan.PlayFile> files, String err);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_detail);

        tvName = findViewById(R.id.tvDetailName);
        tvMeta = findViewById(R.id.tvDetailMeta);
        tvContent = findViewById(R.id.tvDetailContent);
        boxGroup = findViewById(R.id.boxLineGroup);
        btnPlay = findViewById(R.id.btnPlay);
        btnDownload = findViewById(R.id.btnDownload);
        btnTransfer = findViewById(R.id.btnTransfer);
        tvTransferDir = findViewById(R.id.tvTransferDir);
        tvTransferResult = findViewById(R.id.tvTransferResult);

        fid = getIntent().getIntExtra("fid", 0);
        tid = nz(getIntent().getStringExtra("tid"));
        movieName = nz(getIntent().getStringExtra("name"));
        if (!movieName.isEmpty()) tvName.setText(movieName);

        pool.execute(() -> {
            Site.Detail d = Site.detail(fid, tid);
            main.post(() -> bindDetail(d));
        });

        btnPlay.setOnClickListener(v -> doPlay());
        btnDownload.setOnClickListener(v -> doDownload());
        btnTransfer.setOnClickListener(v -> doTransfer());
    }

    private void bindDetail(Site.Detail d) {
        detail = d;
        if (d == null || (d.movie.name.isEmpty() && d.boxes.isEmpty())) {
            tvName.setText("加载失败");
            tvContent.setText("详情获取失败（Cookie 失效或网络问题）。");
            setActionsVisible(false);
            return;
        }
        if (d.movie.name != null && !d.movie.name.isEmpty()) {
            movieName = d.movie.name;
            tvName.setText(d.movie.name);
        }
        tvMeta.setText(d.movie.remarks);
        tvContent.setText(d.movie.content.isEmpty() ? "（无简介）" : d.movie.content);

        // 只保留百度线路（Site 已过滤），展示线路 + 底部三个操作
        boxGroup.removeAllViews();
        if (d.boxes.isEmpty()) {
            TextView no = makeLine("无可用下载链接", false);
            boxGroup.addView(no);
            setActionsVisible(false);
            return;
        }
        Site.Box first = d.boxes.get(0);
        shareUrl = first.url;
        sharePwd = first.pwd;
        for (Site.Box b : d.boxes) {
            String label = "百度网盘" + (b.pwd == null || b.pwd.isEmpty() ? "" : "（提取码 " + b.pwd + "）");
            TextView line = makeLine(label, true);
            line.setOnClickListener(v -> {
                android.content.ClipboardManager cm =
                        (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                cm.setPrimaryClip(android.content.ClipData.newPlainText("url", b.url));
                Toast.makeText(this, "链接已复制，可到百度网盘APP粘贴", Toast.LENGTH_LONG).show();
            });
            boxGroup.addView(line);
        }
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

    private TextView makeLine(String text, boolean clickable) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(15);
        tv.setTextColor(0xFFEEEEEE);
        tv.setPadding(dip(14), dip(10), dip(14), dip(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dip(8);
        tv.setLayoutParams(lp);
        tv.setBackground(getDrawable(android.R.color.darker_gray));
        tv.getBackground().setAlpha(24);
        tv.setFocusable(clickable);
        tv.setClickable(clickable);
        if (clickable) {
            tv.setOnFocusChangeListener((v, has) -> v.setAlpha(has ? 1f : 0.8f));
        }
        return tv;
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
            final String err = fs.isEmpty()
                    ? (rs.message == null || rs.message.isEmpty() ? "网盘里没有找到视频文件" : rs.message)
                    : "";
            main.post(() -> {
                locating = false;
                cb.on(fs, err);
            });
        });
    }

    /** 定位；目录里没有就转存一次再定位。 */
    private BaiduPan.Resolved ensureResolved() {
        String rootDir = Settings.saveDir();
        BaiduPan.Resolved rs = BaiduPan.resolveAll(rootDir, movieName);
        if (!rs.files.isEmpty()) return rs;

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
        Settings.recordTransfer(rootDir, tr.ok);

        BaiduPan.Resolved again = BaiduPan.resolveAll(rootDir, movieName);
        if (!again.files.isEmpty()) return again;
        if (!tr.ok && (again.message == null || again.message.isEmpty())) again.message = tr.message;
        return again;
    }

    /** 最近 15 秒内成功转存过？（读 Settings 里已持久化的那次结果，不用再开一个字段） */
    private boolean recentlyTransferred() {
        try {
            org.json.JSONObject o = new org.json.JSONObject(Settings.lastTransfer());
            return o.optBoolean("ok", false)
                    && System.currentTimeMillis() - o.optLong("time", 0) < 15000;
        } catch (Throwable e) {
            return false;
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
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).requestFocus();
    }

    /**
     * 入队。**按网盘里的相对目录结构落盘**：{@code 目标目录/第二季/03.mkv}。
     * 这样多文件夹下的同名文件不会互相覆盖，用户下完在本地也是分好文件夹的。
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
                        + "\n落盘目录：" + dir + list);
                toast("已开始下载 " + n + " 个文件");
                startActivity(new Intent(this, DownloadActivity.class));
            });
        });
    }

    // ==================== ③ 转存 ====================

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
        tvTransferResult.setText("转存中…");
        final String url = shareUrl;
        final String pwd = sharePwd;
        final String dir = Settings.saveDir();
        pool.execute(() -> {
            BaiduPan.TransferResult r;
            try {
                r = BaiduPan.transfer(url, pwd, dir);
            } catch (Throwable e) {
                // 兜底：任何异常都不允许闪退，转成失败提示
                r = new BaiduPan.TransferResult();
                r.ok = false;
                r.message = "转存异常：" + e.getClass().getSimpleName();
            }
            final BaiduPan.TransferResult fr = r;
            main.post(() -> {
                transferring = false;
                Settings.recordTransfer(dir, fr.ok);
                String t = new java.text.SimpleDateFormat("M-d HH:mm", java.util.Locale.CHINA)
                        .format(new java.util.Date());
                tvTransferResult.setText((fr.ok ? "✔ " : "✘ ") + fr.message + "   (" + t + ")");
                toast(fr.message);
                if (fr.ok) {
                    // 转存成功 -> 立刻把文件列出来，直接给「播放 / 下载」的下一步
                    withFiles((fs, err) -> {
                        if (!err.isEmpty()) {
                            tvTransferResult.setText("✔ 转存成功；但没找到视频文件：" + err);
                            return;
                        }
                        showAfterTransfer(fs);
                    });
                }
            });
        });
    }

    /** 转存完成后的分流：直接告诉用户接下来能干什么。 */
    private void showAfterTransfer(final List<BaiduPan.PlayFile> files) {
        tvTransferResult.setText("✔ 转存完成，网盘里找到 " + files.size() + " 个视频文件");
        AlertDialog dlg = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog)
                .setTitle("转存完成")
                .setMessage("已在网盘里找到 " + files.size() + " 个视频文件，接下来？")
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
    }

    // ==================== 工具 ====================

    /** 列表项文案：相对目录 + 文件名 + 体积。 */
    private String labelOf(BaiduPan.PlayFile pf) {
        String rel = pf.rel == null || pf.rel.isEmpty() ? "" : pf.rel + " / ";
        String size = pf.size > 0 ? "   " + humanSize(pf.size) : "";
        return rel + pf.name + size;
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

    private int dip(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
