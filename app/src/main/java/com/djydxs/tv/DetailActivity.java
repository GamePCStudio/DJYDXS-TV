package com.djydxs.tv;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 影片详情：简介 + 百度网盘线路 + 一键转存。 */
public class DetailActivity extends Activity {

    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private TextView tvName;
    private TextView tvMeta;
    private TextView tvContent;
    private LinearLayout boxGroup;
    private TextView tvTransfer;
    private TextView tvTransferResult;

    private Site.Detail detail;
    private String shareUrl = "";
    private String sharePwd = "";
    private boolean transferring = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_detail);

        tvName = findViewById(R.id.tvDetailName);
        tvMeta = findViewById(R.id.tvDetailMeta);
        tvContent = findViewById(R.id.tvDetailContent);
        boxGroup = findViewById(R.id.boxLineGroup);
        tvTransfer = findViewById(R.id.tvTransfer);
        tvTransferResult = findViewById(R.id.tvTransferResult);

        int fid = getIntent().getIntExtra("fid", 0);
        String tid = getIntent().getStringExtra("tid");
        String name = getIntent().getStringExtra("name");
        if (name != null) tvName.setText(name);

        pool.execute(() -> {
            Site.Detail d = Site.detail(fid, tid);
            main.post(() -> bindDetail(d));
        });

        tvTransfer.setOnClickListener(v -> doTransfer());
    }

    private void bindDetail(Site.Detail d) {
        detail = d;
        if (d == null || (d.movie.name.isEmpty() && d.boxes.isEmpty())) {
            tvName.setText("加载失败");
            tvContent.setText("详情获取失败（Cookie 失效或网络问题）。");
            tvTransfer.setVisibility(View.GONE);
            return;
        }
        tvName.setText(d.movie.name);
        tvMeta.setText(d.movie.remarks);
        tvContent.setText(d.movie.content.isEmpty() ? "（无简介）" : d.movie.content);

        // 只保留百度线路（Site 已过滤），展示线路 + 一键转存按钮
        boxGroup.removeAllViews();
        if (d.boxes.isEmpty()) {
            TextView no = makeLine("无可用下载链接", false);
            boxGroup.addView(no);
            tvTransfer.setVisibility(View.GONE);
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
        tvTransfer.setVisibility(View.VISIBLE);
        String dir = Settings.saveDir();
        tvTransfer.setText("转存到我的百度网盘 → " + dir);
        String last = Settings.lastTransfer();
        if (last != null && !last.isEmpty()) {
            tvTransferResult.setText("最近转存：" + humanLast(last));
        }
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
                // 兕底：任何异常都不允许闪退，转成失败提示
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
}
