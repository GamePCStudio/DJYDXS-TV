package com.supermov.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.List;

/**
 * 下载队列页：任务列表 + 进度 + 暂停/继续/重试/播放本地/删除。
 *
 * 页面只做展示与转发，状态真源在 {@link DlEngine}；订阅它的回调做刷新。
 */
public class DownloadActivity extends Activity implements DlEngine.Observer {

    private static final int REQ_NOTI = 7001;

    private final Handler main = new Handler(Looper.getMainLooper());

    private RecyclerView rv;
    private TextView tvSummary;
    private TextView tvEmpty;
    private DownloadAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        CookieStore.init(this);
        Settings.init(this);
        DlEngine.get().init(this);
        setContentView(R.layout.activity_downloads);

        rv = findViewById(R.id.rvDl);
        tvSummary = findViewById(R.id.tvDlSummary);
        tvEmpty = findViewById(R.id.tvDlEmpty);

        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new DownloadAdapter(new DownloadAdapter.Action() {
            @Override
            public void onToggle(Dl t) {
                if (t.status == Dl.RUNNING || t.status == Dl.QUEUED) {
                    DlEngine.get().pause(t.id);
                } else {
                    DlEngine.get().resume(t.id);
                }
            }

            @Override
            public void onPlay(Dl t) {
                playLocal(t);
            }

            @Override
            public void onRemove(Dl t) {
                confirmRemove(t);
            }
        });
        rv.setAdapter(adapter);

        findViewById(R.id.btnDlStartAll).setOnClickListener(v -> {
            DlEngine.get().resumeAll();
            Toast.makeText(this, "已开始 / 继续全部任务", Toast.LENGTH_SHORT).show();
        });
        findViewById(R.id.btnDlPauseAll).setOnClickListener(v -> {
            DlEngine.get().pauseAll();
            Toast.makeText(this, "已暂停全部任务（可随时继续，断点已保留）", Toast.LENGTH_SHORT).show();
        });
        findViewById(R.id.btnDlClearDone).setOnClickListener(v -> {
            DlEngine.get().clearDone();
            Toast.makeText(this, "已清空完成记录（文件保留在原处）", Toast.LENGTH_SHORT).show();
            refresh();
        });

        askNotificationPermission();
        refresh();
    }

    /** Android 13+ 通知需要运行时授权，否则前台服务的进度通知不显示（下载本身不受影响）。 */
    private void askNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return;
        try {
            if (checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_NOTI);
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        DlEngine.get().addObserver(this);
        refresh();
    }

    @Override
    protected void onPause() {
        DlEngine.get().removeObserver(this);
        super.onPause();
    }

    @Override
    public void onChanged() {
        main.post(this::refresh);
    }

    private void refresh() {
        if (isFinishing()) return;
        List<Dl> list = DlEngine.get().snapshot();
        adapter.setItems(list);
        boolean empty = list.isEmpty();
        tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        rv.setVisibility(empty ? View.GONE : View.VISIBLE);

        int running = 0;
        int done = 0;
        int failed = 0;
        long bytes = 0;
        for (Dl t : list) {
            if (t.status == Dl.RUNNING) running++;
            if (t.status == Dl.DONE) done++;
            if (t.status == Dl.ERROR) failed++;
            bytes += t.done;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("共 ").append(list.size()).append(" 个任务");
        if (running > 0) sb.append(" · 下载中 ").append(running);
        if (done > 0) sb.append(" · 已完成 ").append(done);
        if (failed > 0) sb.append(" · 失败 ").append(failed);
        if (bytes > 0) sb.append(" · 已下载 ").append(DlEngine.human(bytes));
        String note = DlEngine.get().note();
        if (note != null && !note.isEmpty()) sb.append("\n").append(note);
        tvSummary.setText(sb.toString());
    }

    private void playLocal(Dl t) {
        File f = new File(t.path());
        if (!f.exists() || f.length() <= 0) {
            Toast.makeText(this, "文件不存在：" + t.path(), Toast.LENGTH_LONG).show();
            return;
        }
        Intent it = new Intent(this, PlayerActivity.class);
        it.putExtra("file", f.getAbsolutePath());
        it.putExtra("name", t.name);
        startActivity(it);
    }

    private void confirmRemove(final Dl t) {
        AlertDialog dlg = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog)
                .setTitle("删除任务")
                .setMessage("从队列移除：\n" + t.fileName
                        + "\n\n已下载的正式文件不会被删除；未完成的断点缓存会清掉。")
                .setPositiveButton("删除", (d, w) -> DlEngine.get().remove(t.id))
                .setNegativeButton("取消", null)
                .create();
        dlg.show();
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).requestFocus();
    }

    @Override
    public void onBackPressed() {
        finish();
    }
}
