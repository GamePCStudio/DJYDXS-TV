package com.supermov.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.CheckBox;
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

    /** 顶部标题原文；第一行说明接在它后面、走普通字重（见 {@link #setTopTitle()}）。 */
    private static final String TITLE = "⬇ 下载队列";
    private static final String TITLE_TIP =
            "  本页面下载为不限速下载，影片会以最快速度下载完成。"
                    + "根据设备网络情况可实现极速50Mbps~350Mbps超高网速下载。"
                    + "极速期间，可能会对同网络其他设备上网造成干扰。";

    private final Handler main = new Handler(Looper.getMainLooper());

    private RecyclerView rv;
    private TextView tvTitle;
    private TextView tvSummary;
    private TextView tvSpeedTip;
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
        tvTitle = findViewById(R.id.tvDlTitle);
        tvSummary = findViewById(R.id.tvDlSummary);
        tvSpeedTip = findViewById(R.id.tvDlSpeedTip);
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
        findViewById(R.id.btnDlClearAll).setOnClickListener(v -> confirmRemoveAll());

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
        // 下载管理页在前台 = 用户正盯着进度看，此时不限速
        DlEngine.get().setRateLimit(0);
        // 顶部两行说明：第一行跟在标题后面（标题粗体蓝，接的句子普通字重浅灰，同一行同字号）；
        // 第二行是小字，Mbps 取设置的后台限速 —— 放在 onResume，用户改完设置回这页要看到新值。
        setTopTitle();
        tvSpeedTip.setText("退出本页面和软件以后，下载不会停止，会降为" + Settings.dlLimitMbps()
                + "Mbps后台速度下载，保障本设备在线播放流畅。无限速的极速下载必须将设备保持在本页面");
        // 队列里还有排队的就接着下：进程被杀 / 服务被回收之后再回到这一页，
        // 不该让用户以为「它停下了」还要手动点一次「全部开始」。
        DlEngine.get().start();
        refresh();
    }

    @Override
    protected void onPause() {
        DlEngine.get().removeObserver(this);
        // 离开本页（去浏览海报页 / 回桌面 / 退出程序）后按设置恢复后台限速
        Settings.init(this);
        DlEngine.get().setRateLimit(Settings.dlLimitBps());
        super.onPause();
    }

    @Override
    public void onChanged() {
        main.post(this::refresh);
    }

    /**
     * 顶部第一行 = 标题 + 前台不限速的说明，写在同一个 TextView 里好自然连排。
     *
     * <p>标题那段沿用布局里的粗体蓝；后面接的说明只压掉加粗、换成浅灰。字号不动 ——
     * 这里不加任何 SizeSpan，两段都继承 TextView 自己的 18sp。</p>
     */
    private void setTopTitle() {
        android.text.SpannableString s = new android.text.SpannableString(TITLE + TITLE_TIP);
        s.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.NORMAL),
                TITLE.length(), s.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        s.setSpan(new android.text.style.ForegroundColorSpan(0xFF9AA0A6),
                TITLE.length(), s.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        tvTitle.setText(s);
    }

    private void refresh() {
        if (isFinishing()) return;
        List<Dl> list = DlEngine.get().snapshot();
        // 排队位次：让串行队列在列表里看得见（第 2 位要等第 1 位下完）
        int pos = 0;
        for (Dl t : list) {
            t.queuePos = (t.status == Dl.QUEUED) ? ++pos : 0;
        }
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

    /**
     * 播放已下载的影片。
     *
     * <p>没下完的一律不播：断点文件（{@code .dlpart}）也被预分配成了完整长度，但内容是空洞，
     * 播出来只会黑屏 / 花屏 —— 不如直接说清楚「还没下完」。</p>
     *
     * <p>下完了却找不到文件的，先把「记录路径」和「按文件名在当前下载目录里找一遍」两条路
     * 都走一下（用户可能中途换过下载目录、或自己挪过文件夹）。</p>
     */
    private void playLocal(Dl t) {
        if (t.status != Dl.DONE) {
            Toast.makeText(this, "影片没有下载完成（" + t.percent() + "%），下载完成后才能播放",
                    Toast.LENGTH_LONG).show();
            return;
        }
        File f = resolveLocalFile(t);
        if (f == null) {
            Toast.makeText(this, "找不到已下载的文件：\n" + t.path(), Toast.LENGTH_LONG).show();
            return;
        }
        if (!f.canRead()) {
            Toast.makeText(this, "没有读取该文件的权限，请到 设置 → 下载目录 授予「所有文件访问」",
                    Toast.LENGTH_LONG).show();
            return;
        }
        Intent it = new Intent(this, PlayerActivity.class);
        it.putExtra("file", f.getAbsolutePath());
        it.putExtra("name", t.name);
        startActivity(it);
    }

    /** 定位已下载的影片文件：先按记录路径，找不到再按文件名到当前下载目录里找一遍。 */
    private File resolveLocalFile(Dl t) {
        try {
            File f = new File(t.path());
            if (f.isFile() && f.length() > 0) return f;
        } catch (Throwable ignored) {
        }
        try {
            String name = t.fileName;
            if (name == null || name.isEmpty()) return null;
            File found = findByName(new File(Settings.downloadDir()), name, 0);
            if (found != null) return found;
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 在 dir 下（最多钻 4 层）按文件名找。同层先扫完再进子目录，命中更符合直觉。 */
    private static File findByName(File dir, String name, int depth) {
        if (dir == null || depth > 4) return null;
        File[] kids;
        try {
            kids = dir.listFiles();
        } catch (Throwable e) {
            return null;
        }
        if (kids == null) return null;
        for (File f : kids) {
            if (f.isFile() && name.equals(f.getName()) && f.length() > 0) return f;
        }
        for (File f : kids) {
            if (f.isDirectory()) {
                File r = findByName(f, name, depth + 1);
                if (r != null) return r;
            }
        }
        return null;
    }

    private void confirmRemove(final Dl t) {
        AlertDialog dlg = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog)
                .setTitle("删除任务")
                .setMessage("从队列移除：\n" + t.displayName()
                        + "\n\n已下载的正式文件不会被删除；未完成的断点缓存会清掉。")
                .setPositiveButton("删除", (d, w) -> DlEngine.get().remove(t.id))
                .setNegativeButton("取消", null)
                .create();
        dlg.show();
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).requestFocus();
    }

    /**
     * 删除全部任务：先弹确认框（**默认焦点在「取消」**，免得遥控器上连按 OK 直接清空）。
     *
     * <p>默认口径与单个删除一致：只清任务记录 + 断点缓存，已下载完成的影片文件保留在原处。
     * 断点文件是预分配过 total 大小的，所以顺带把能释放的空间报给用户看。</p>
     *
     * <p>想连影片文件一起删，就在弹框里勾上「同时删除已下载的影片文件」——
     * 该选项**默认不勾**，只有显式勾选才会连文件一起删。</p>
     */
    private void confirmRemoveAll() {
        final List<Dl> list = DlEngine.get().snapshot();
        if (list.isEmpty()) {
            Toast.makeText(this, "下载队列已经是空的", Toast.LENGTH_SHORT).show();
            return;
        }
        int running = 0;
        int unfinished = 0;
        int done = 0;
        long partBytes = 0;
        for (Dl t : list) {
            if (t.status == Dl.RUNNING) running++;
            if (t.status == Dl.DONE) done++;
            else unfinished++;
            if (t.status != Dl.DONE) partBytes += (t.total > 0 ? t.total : t.done);
        }
        long[] fs = doneFileStats(list);
        final int fileCount = (int) fs[0];
        final long fileBytes = fs[1];

        StringBuilder sb = new StringBuilder();
        sb.append("共 ").append(list.size()).append(" 个任务");
        if (done > 0) sb.append("，其中已完成 ").append(done).append(" 个");
        sb.append("。\n\n");
        if (running > 0) sb.append("· ").append(running).append(" 个正在下载，会立即停止\n");
        if (unfinished > 0) {
            sb.append("· 未完成任务的断点缓存会被清掉");
            if (partBytes > 0) sb.append("，约可释放 ").append(DlEngine.human(partBytes));
            sb.append("\n");
        }
        if (fileCount > 0) {
            sb.append("· 已下载完成的影片文件默认保留，勾选下方选项可一并删除\n");
        } else {
            sb.append("· 当前没有已下载完成的影片文件\n");
        }
        sb.append("\n此操作不可撤销，确定删除全部任务吗？");

        // 「同时删除已下载文件」用复选框而不是再加一个按钮：确认框里出现两个都写着
        // 「删除」的按钮，遥控器上左右一晃就可能误按，复选框是显式的二次确认。
        final boolean[] alsoFiles = {false};
        final CheckBox cb = new CheckBox(this);
        int pad = (int) (getResources().getDisplayMetrics().density * 20);
        cb.setPadding(pad, pad / 2, pad, 0);
        cb.setTextSize(13f);
        cb.setTextColor(0xFFE8EAED);
        cb.setChecked(false);
        if (fileCount > 0) {
            cb.setText("同时删除已下载的影片文件\n" + fileCount + " 个文件 · 共 "
                    + DlEngine.human(fileBytes) + "（删除后无法恢复）");
            cb.setEnabled(true);
        } else {
            cb.setText("同时删除已下载的影片文件\n（当前没有可删除的本地文件）");
            cb.setEnabled(false);
        }
        cb.setOnCheckedChangeListener((v, checked) -> alsoFiles[0] = checked);

        AlertDialog dlg = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog)
                .setTitle("删除全部任务")
                .setMessage(sb.toString())
                .setView(cb)
                .setPositiveButton("删除全部", (d, w) -> doRemoveAll(alsoFiles[0]))
                .setNegativeButton("取消", null)
                .create();
        dlg.show();
        dlg.getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus();
    }

    /** 已下载完成的本地文件统计：返回 {文件数, 总体积}。 */
    private long[] doneFileStats(List<Dl> list) {
        int n = 0;
        long bytes = 0;
        for (Dl t : list) {
            if (t.status != Dl.DONE) continue;
            try {
                File f = new File(t.path());
                if (f.isFile() && f.length() > 0) {
                    n++;
                    bytes += f.length();
                }
            } catch (Throwable ignored) {
            }
        }
        return new long[]{n, bytes};
    }

    /**
     * 执行删除（可含本地影片文件）。
     *
     * <p>放后台线程：删大文件 + 清空目录都是 IO，几 GB 的量在主线程做会把遥控器卡住。</p>
     */
    private void doRemoveAll(final boolean alsoFiles) {
        final int n = DlEngine.get().snapshot().size();
        Thread th = new Thread(() -> {
            final long freed = DlEngine.get().removeAll(alsoFiles);
            main.post(() -> {
                if (isFinishing()) return;
                String msg;
                if (alsoFiles) {
                    msg = "已删除全部 " + n + " 个任务，本地影片文件一并删除"
                            + (freed > 0 ? "（释放 " + DlEngine.human(freed) + "）" : "");
                } else {
                    msg = "已删除全部 " + n + " 个任务（影片文件保留）";
                }
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                refresh();
            });
        }, "dl-remove-all");
        th.start();
    }

    @Override
    public void onBackPressed() {
        finish();
    }
}
