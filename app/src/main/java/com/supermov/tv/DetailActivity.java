package com.supermov.tv;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 影片详情：简介 + 百度网盘线路 + 三个操作（在线播放 / 下载 / 转存）。 */
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
    private boolean downloading = false;

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

        // 三个操作入口
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
                // 点线路 = 复制链接（TV 上最直接的用法）
                android.content.ClipboardManager cm =
                        (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                cm.setPrimaryClip(android.content.ClipData.newPlainText("url", b.url));
                Toast.makeText(this, "链接已复制，可到百度网盘APP粘贴", Toast.LENGTH_LONG).show();
            });
            boxGroup.addView(line);
        }
        setActionsVisible(true);
        String dir = Settings.saveDir();
        tvTransferDir.setText("网盘转存目录：" + dir
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

    /** 在线播放：交给 PlayerActivity（原画直链 + 本地代理 + M3U8 兜底）。 */
    private void doPlay() {
        if (!CookieStore.hasBaiduLogin()) {
            Toast.makeText(this, "未授权，请先到 设置→百度网盘扫码", Toast.LENGTH_LONG).show();
            startActivity(new Intent(this, QrActivity.class));
            return;
        }
        if (shareUrl.isEmpty()) {
            Toast.makeText(this, "没有可用的百度网盘链接", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent it = new Intent(this, PlayerActivity.class);
        it.putExtra("fid", fid);
        it.putExtra("tid", tid);
        it.putExtra("name", movieName);
        it.putExtra("url", shareUrl);
        it.putExtra("pwd", sharePwd);
        startActivity(it);
    }

    /** 下载：选落盘目录 -> 定位网盘文件（没转存过就先转存）-> 加入下载队列。 */
    private void doDownload() {
        if (downloading) return;
        if (!CookieStore.hasBaiduLogin()) {
            Toast.makeText(this, "未授权，请先到 设置→百度网盘扫码", Toast.LENGTH_LONG).show();
            startActivity(new Intent(this, QrActivity.class));
            return;
        }
        if (shareUrl.isEmpty()) {
            Toast.makeText(this, "没有可用的百度网盘链接", Toast.LENGTH_SHORT).show();
            return;
        }
        showDirPicker();
    }

    /** TV 上用遥控器打路径太痛苦，所以列出所有可写位置让用户挑。 */
    private void showDirPicker() {
        final List<Storage.Target> ts = Storage.targets(this);
        final String[] labels = new String[ts.size() + 1];
        for (int i = 0; i < ts.size(); i++) {
            labels[i] = ts.get(i).label + "\n" + ts.get(i).dir;
        }
        labels[ts.size()] = "✎ 手动输入路径…";

        AlertDialog dlg = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog)
                .setTitle("下载到")
                .setItems(labels, (d, which) -> {
                    if (which == ts.size()) {
                        showManualDir();
                        return;
                    }
                    Storage.Target t = ts.get(which);
                    if (t.needAllFiles && !Storage.hasAllFiles(this)) {
                        Storage.requestAllFiles(this);
                        Toast.makeText(this, "请授予「所有文件访问」权限后重新点下载", Toast.LENGTH_LONG).show();
                        return;
                    }
                    startDownload(t.dir);
                })
                .create();
        dlg.show();
    }

    private void showManualDir() {
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
                        Toast.makeText(this, "路径不能为空", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    startDownload(v);
                })
                .setNegativeButton("取消", null)
                .create();
        dlg.show();
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).requestFocus();
    }

    private void startDownload(final String dir) {
        downloading = true;
        tvTransferResult.setText("正在准备下载…（定位网盘文件）");
        final String name = movieName;
        final String rootDir = Settings.saveDir();
        pool.execute(() -> {
            BaiduPan.PlayFile pf = BaiduPan.resolvePlayable(rootDir, name);
            if (!pf.ok) {
                // 没转过存 -> 自动转存一次再定位
                main.post(() -> tvTransferResult.setText("尚未转存，正在转存到 " + rootDir + " …"));
                BaiduPan.TransferResult tr = null;
                try {
                    tr = BaiduPan.transfer(shareUrl, sharePwd, rootDir);
                } catch (Throwable e) {
                    tr = null;
                }
                if (tr != null && tr.ok) {
                    Settings.recordTransfer(rootDir, true);
                    pf = BaiduPan.resolvePlayable(rootDir, name);
                }
                if (!pf.ok) {
                    final String m = pf.message.isEmpty() ? "未能在网盘里定位到文件" : pf.message;
                    main.post(() -> {
                        downloading = false;
                        tvTransferResult.setText("✘ 下载准备失败：" + m);
                        Toast.makeText(this, m, Toast.LENGTH_LONG).show();
                    });
                    return;
                }
            }

            Dl t = new Dl();
            t.fid = fid;
            t.tid = tid;
            t.name = name;
            t.rootDir = rootDir;
            t.fsId = pf.fsId;
            t.bpath = pf.path;
            t.fileName = Dl.safeName(pf.name.isEmpty() ? name : pf.name);
            t.dir = dir;
            t.total = pf.size;
            t.created = System.currentTimeMillis();

            long id = DlEngine.get().enqueue(this, t);
            final BaiduPan.PlayFile fpf = pf;
            main.post(() -> {
                downloading = false;
                if (id <= 0) {
                    tvTransferResult.setText("✘ 加入下载队列失败（目录不可写？）：" + dir);
                    Toast.makeText(this, "加入下载队列失败", Toast.LENGTH_LONG).show();
                    return;
                }
                tvTransferResult.setText("✔ 已加入下载队列（#" + id + "）\n"
                        + Dl.safeName(fpf.name) + "\n"
                        + (dir.endsWith("/") ? dir + Dl.safeName(fpf.name) : dir + "/" + Dl.safeName(fpf.name)));
                Toast.makeText(this, "已加入下载队列，开始下载", Toast.LENGTH_SHORT).show();
                startActivity(new Intent(this, DownloadActivity.class));
            });
        });
    }

    private void doTransfer() {
        if (transferring) return;
        if (shareUrl.isEmpty()) {
            Toast.makeText(this, "没有可转存的百度网盘链接", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!CookieStore.hasBaiduLogin()) {
            Toast.makeText(this, "未授权，请先到 设置→百度网盘扫码", Toast.LENGTH_LONG).show();
            startActivity(new Intent(this, QrActivity.class));
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
                Toast.makeText(this, fr.message, Toast.LENGTH_LONG).show();
            });
        });
    }

    private int dip(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
