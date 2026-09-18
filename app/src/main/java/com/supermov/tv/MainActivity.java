package com.supermov.tv;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 首页：顶部标题+搜索按钮，分类一行横滚（胶囊样式），下方影片列表，无限翻页。
 */
public class MainActivity extends Activity {

    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private RecyclerView rvCats;
    private RecyclerView rvFilters;
    private RecyclerView rvList;
    private TextView btnSearch;
    private TextView tvEmpty;

    private OptionAdapter catAdapter;
    private OptionAdapter filterAdapter;
    private MovieAdapter movieAdapter;

    private final List<OptionAdapter.Option> catOptions = new ArrayList<>();

    private int currentFid;
    private int currentPage = 1;
    private int totalPages = 1;
    private int posterSpans = 7; // 海报列数（固定 7 列）
    private boolean loading = false;
    /** 载入请求代号：每次点击 +1，旧请求回来时对不上号就丢弃（「最新一次点击说了算」）。 */
    private volatile int loadGen = 0;
    /** 百度链接探测专用线程池：跑在它上面，才不会把「下一次点击」的列表请求堵在同一个线程后面。 */
    private final ExecutorService probePool = Executors.newSingleThreadExecutor();
    private String searchKeyword = ""; // 空 = 浏览版块；非空 = 搜索模式
    private int currentTypeid = 0;      // 版块内主题分类过滤（0=全部）
    private int currentFilterIndex = 0; // 选中的过滤器下标

    /** 导航历史：搜索/切分类/切过滤器前压栈，返回键弹栈回到上一个页面状态。 */
    private static class ViewState {
        final String kw; final int fid; final int typeid;
        ViewState(String kw, int fid, int typeid) { this.kw = kw; this.fid = fid; this.typeid = typeid; }
    }
    private final java.util.ArrayDeque<ViewState> navHistory = new java.util.ArrayDeque<>();

    private void pushHistory() {
        navHistory.addLast(new ViewState(searchKeyword, currentFid, currentTypeid));
        while (navHistory.size() > 30) navHistory.pollFirst();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        CookieStore.init(this);
        Settings.init(this);
        Site.ensureForumCookie(); // 注入内置论坛 Cookie，解锁会员版块
        setContentView(R.layout.activity_list);

        btnSearch = findViewById(R.id.btnSearch);
        tvEmpty = findViewById(R.id.emptyView);
        rvCats = findViewById(R.id.recyclerView);
        rvFilters = findViewById(R.id.rvFilters);
        rvList = findViewById(R.id.rvMovies);

        // 分类行
        rvCats.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        catAdapter = new OptionAdapter();
        for (Site.Category c : Site.categories()) {
            catOptions.add(new OptionAdapter.Option(c.name));
        }
        catOptions.add(new OptionAdapter.Option("⚙ 设置"));
        catOptions.add(new OptionAdapter.Option("⬇ 下载"));
        catAdapter.setItems(catOptions);
        catAdapter.setOnClick((o, pos) -> {
            int catCount = Site.categories().size();
            if (pos == catCount) {
                startActivity(new Intent(this, SettingsActivity.class));
            } else if (pos == catCount + 1) {
                // 下载队列：不压历史栈（它不是内容页，返回应直接回首页）
                startActivity(new Intent(this, DownloadActivity.class));
            } else if (pos >= 0 && pos < catCount) {
                pushHistory(); // 返回键可回到上一个版块
                searchKeyword = "";
                currentFid = Site.categories().get(pos).fid;
                currentTypeid = 0;
                currentFilterIndex = 0;
                markCat(pos);
                buildFilterRow();
                reload();
            }
        });
        rvCats.setAdapter(catAdapter);
        markCat(0);

        // 过滤器行（各版块主题分类，搜索模式隐藏）
        rvFilters.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        filterAdapter = new OptionAdapter();
        filterAdapter.setOnClick((o, pos) -> {
            List<Site.Filter> fs = Site.filtersFor(currentFid);
            if (pos < 0 || pos >= fs.size()) return;
            Site.Filter f = fs.get(pos);
            if (f.typeid == currentTypeid) return;
            pushHistory(); // 返回键可回到上一个过滤状态
            currentTypeid = f.typeid;
            currentFilterIndex = pos;
            buildFilterRow();
            reload();
        });
        rvFilters.setAdapter(filterAdapter);
        buildFilterRow();

        // 影片列表：固定 7 列海报墙
        posterSpans = 7;
        rvList.setLayoutManager(new GridLayoutManager(this, posterSpans));
        movieAdapter = new MovieAdapter();
        movieAdapter.setOnClick(this::openDetail);
        movieAdapter.setOnLongClick(this::confirmTransfer);
        rvList.setAdapter(movieAdapter);
        rvList.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                super.onScrolled(rv, dx, dy);
                LinearLayoutManager lm = (LinearLayoutManager) rv.getLayoutManager();
                if (lm == null) return;
                int last = lm.findLastVisibleItemPosition();
                // 提前量随列数放大（一行就有 posterSpans 个条目）
                int total = movieAdapter.getItemCount();
                // total > 0 也是必要的：列表还是空的时候 last 会是 -1，
                // 不加这个判断就会「空列表 -> 立刻请求第 2 页 -> 再第 3 页」连轴转
                if (!loading && currentPage < totalPages && total > 0
                        && last >= total - posterSpans * 2) {
                    loadPage(currentPage + 1);
                }
            }
        });

        // 搜索按钮
        btnSearch.setOnClickListener(v -> showSearchDialog());
        btnSearch.setOnFocusChangeListener((v, has) -> {
            v.setAlpha(has ? 1f : 0.85f);
            v.setScaleX(has ? 1.1f : 1f);
            v.setScaleY(has ? 1.1f : 1f);
        });

        // 默认加载第一个版块
        currentFid = Site.categories().get(0).fid;
        buildFilterRow(); // currentFid 就绪后重建过滤器行
        loadPage(1);

        requestStartupPermissions();
    }

    private static final int REQ_STARTUP = 7001;

    /**
     * 启动时把该要的权限一次要到手。
     *
     * <ul>
     *   <li><b>通知权限</b>（Android 13+）：下载进度通知依赖它；没有它前台服务照跑，只是通知栏看不到进度。</li>
     *   <li><b>存储读写</b>（API ≤ 32 读 / ≤ 29 写）：下载落盘到 公共存储 / U盘 / NAS，以及播放已下载的本地文件。</li>
     *   <li><b>所有文件访问</b>（Android 11+）：特殊权限，只能跳到系统页面授予，首次启动引导一次；
     *       不授权也能正常用（下载落在应用专属目录，免权限）。</li>
     * </ul>
     */
    private void requestStartupPermissions() {
        try {
            List<String> need = new ArrayList<>();
            if (Build.VERSION.SDK_INT >= 33 && !granted("android.permission.POST_NOTIFICATIONS")) {
                need.add("android.permission.POST_NOTIFICATIONS");
            }
            if (Build.VERSION.SDK_INT <= 32 && !granted(Manifest.permission.READ_EXTERNAL_STORAGE)) {
                need.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            }
            if (Build.VERSION.SDK_INT <= 29 && !granted(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                need.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            }
            if (!need.isEmpty()) {
                requestPermissions(need.toArray(new String[0]), REQ_STARTUP);
            }
        } catch (Throwable ignored) {
        }

        // 「所有文件访问」只能去系统设置页打开；只在首次启动引导，避免每次开都弹
        if (Build.VERSION.SDK_INT >= 30 && !Storage.hasAllFiles(this) && !Settings.askedAllFiles()) {
            Settings.setAskedAllFiles(true);
            AlertDialog dlg = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog)
                    .setTitle("存储权限")
                    .setMessage("要把影片下载到 公共存储 / U盘 / 已挂载的 NAS，需要「所有文件访问」权限。\n\n"
                            + "不授权也能正常用：下载会保存到应用专属目录（免权限）。\n"
                            + "以后想改，可在 设置 → 下载目录 里再授权。")
                    .setPositiveButton("去授权", (d, w) -> Storage.requestAllFiles(this))
                    .setNegativeButton("以后再说", null)
                    .create();
            dlg.show();
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).requestFocus();
        }
    }

    private boolean granted(String perm) {
        try {
            return checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable e) {
            return false;
        }
    }

    private void showSearchDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog);
        builder.setTitle("搜索影片");

        final EditText input = new EditText(this);
        input.setHint("输入片名关键词");
        input.setTextSize(13);
        builder.setView(input);

        builder.setPositiveButton("搜索", (d, w) -> {
            String kw = input.getText().toString().trim();
            if (kw.isEmpty()) return;
            startSearch(kw);
        });
        builder.setNegativeButton("取消", null);
        builder.show();
        input.requestFocus();
    }

    private void startSearch(String kw) {
        pushHistory(); // 返回键可退出搜索结果、回到搜索前的页面
        searchKeyword = kw;
        rvFilters.setVisibility(View.GONE); // 搜索模式无过滤器
        reload();
    }

    /** 刷新分类行高亮。 */
    private void markCat(int pos) {
        for (int i = 0; i < catOptions.size(); i++) {
            catOptions.get(i).highlight = (i == pos);
        }
        catAdapter.notifyDataSetChanged();
    }

    /** 按当前版块重建过滤器行（每版块过滤器不同；搜索模式隐藏）。 */
    private void buildFilterRow() {
        List<Site.Filter> fs = Site.filtersFor(currentFid);
        List<OptionAdapter.Option> opts = new ArrayList<>();
        for (Site.Filter f : fs) {
            OptionAdapter.Option o = new OptionAdapter.Option(f.name);
            o.highlight = (f.typeid == currentTypeid);
            opts.add(o);
        }
        filterAdapter.setItems(opts);
        rvFilters.setVisibility((searchKeyword == null || searchKeyword.isEmpty()) && !fs.isEmpty()
                ? View.VISIBLE : View.GONE);
    }

    @Override
    public void onBackPressed() {
        ViewState prev = navHistory.pollLast();
        if (prev == null) {
            super.onBackPressed(); // 没有历史了才退出程序
            return;
        }
        searchKeyword = prev.kw == null ? "" : prev.kw;
        currentTypeid = prev.typeid;
        currentFid = prev.fid;
        // 恢复分类高亮
        int catPos = 0;
        List<Site.Category> cs = Site.categories();
        for (int i = 0; i < cs.size(); i++) {
            if (cs.get(i).fid == currentFid) { catPos = i; break; }
        }
        markCat(catPos);
        // 恢复过滤器高亮（按 typeid 找回下标）
        currentFilterIndex = 0;
        List<Site.Filter> fs = Site.filtersFor(currentFid);
        for (int i = 0; i < fs.size(); i++) {
            if (fs.get(i).typeid == currentTypeid) { currentFilterIndex = i; break; }
        }
        buildFilterRow(); // 搜索模式内部会隐藏，浏览模式恢复显示
        reload();
    }

    private void reload() {
        currentPage = 1;
        totalPages = 1;
        movieAdapter.setItems(new ArrayList<>());
        loadPage(1);
    }

    /**
     * 点击海报：进详情页。
     *
     * <p>「在线播放 / 下载 / 转存」三个入口都在 {@link DetailActivity} 里，
     * 并且那里才处理得了「一个分享里多个文件 / 多层文件夹」的选择与分流。
     * 之前这里直接弹「转存」对话框，导致点影片永远只有转存一条路。</p>
     */
    private void openDetail(Site.Movie m) {
        Intent it = new Intent(this, DetailActivity.class);
        it.putExtra("fid", m.fid);
        it.putExtra("tid", m.tid);
        it.putExtra("name", m.name);
        it.putExtra("pic", m.pic);
        startActivity(it);
    }

    /** 长按海报：快捷转存（保留原来的快速通道，不用先进详情页）。 */
    private void confirmTransfer(Site.Movie m) {
        if (!CookieStore.hasBaiduLogin()) {
            Toast.makeText(this, "未授权百度网盘，请先到 设置→百度网盘扫码", Toast.LENGTH_LONG).show();
            startActivity(new Intent(this, QrActivity.class));
            return;
        }
        String dir = Settings.saveDir();
        AlertDialog dlg = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog)
                .setTitle("转存影片")
                .setMessage("《" + m.name + "》\n转存到：" + dir)
                .setPositiveButton("转存", (d, w) -> doQuickTransfer(m))
                .setNegativeButton("取消", null)
                .create();
        dlg.show();
        // TV 遥控器：焦点直接落在"转存"上
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).requestFocus();
    }

    private void doQuickTransfer(Site.Movie m) {
        Toast.makeText(this, "转存中…", Toast.LENGTH_SHORT).show();
        final int fid = m.fid;
        final String tid = m.tid;
        final String name = m.name;
        pool.execute(() -> {
            // 解析分享链接
            Site.Detail d = Site.detail(fid, tid);
            String url = "", pwd = "";
            if (d != null && !d.boxes.isEmpty()) {
                url = d.boxes.get(0).url;
                pwd = d.boxes.get(0).pwd;
            }
            BaiduPan.TransferResult rr;
            if (url.isEmpty()) {
                rr = new BaiduPan.TransferResult();
                rr.ok = false;
                rr.message = "该影片没有百度网盘分享链接";
            } else {
                try {
                    rr = BaiduPan.transfer(url, pwd, Settings.saveDir());
                } catch (Throwable e) {
                    rr = new BaiduPan.TransferResult();
                    rr.ok = false;
                    rr.message = "转存异常：" + e.getClass().getSimpleName();
                }
            }
            final BaiduPan.TransferResult r = rr;
            main.post(() -> {
                Settings.recordTransfer(Settings.saveDir(), r.ok);
                Toast.makeText(this, (r.ok ? "✔ " : "✘ ") + r.message, Toast.LENGTH_LONG).show();
            });
        });
    }

    /**
     * 载入某一页。**先出画、后筛选**，两段式：
     *
     * <ol>
     *   <li>列表页 + 表格页解析完就先 setItems 把海报墙画出来（约 1 秒）；</li>
     *   <li>再在后台逐帖探测「有没有百度网盘分享」，把没有的条目摘掉。</li>
     * </ol>
     *
     * <p>老实现是「把 36 个帖子全探测完才画」——一次版块切换要 5 秒多，屏幕上从头到尾
     * 只有一句「加载中…」，看着像卡死；而且那个探测跑在 {@code pool}（单线程）上，
     * 直接把后续请求堵在后面。</p>
     *
     * <p>另外用 {@link #loadGen} 实现「最新一次点击说了算」：新点击会让先前的请求作废，
     * 但**不会再像以前那样把用户的点击整个丢掉** —— 旧实现开头的 {@code if (loading) return;}
     * 遇上「正在加载下一页时点版块」，新点击直接被丢掉，停在「加载中…」再也不动。</p>
     */
    private void loadPage(int page) {
        final int gen = ++loadGen;
        loading = true;
        final String kw = searchKeyword;
        final int fid = currentFid;
        final int ftypeid = currentTypeid;
        if (page == 1) {
            tvEmpty.setVisibility(View.VISIBLE);
            tvEmpty.setText("加载中…");
        }
        pool.execute(() -> {
            Site.lastLoadError = "";
            Site.Paged<List<Site.Movie>> res;
            if (kw != null && !kw.isEmpty()) {
                res = Site.search(kw, page);
            } else {
                res = Site.category(fid, page, ftypeid);
            }
            final List<Site.Movie> raw = new ArrayList<>(res.data);
            final int pageCount = res.pageCount;
            // 诊断：本条日志能一次分清「压根没解析到条目」和「解析到了但被摘光」，
            // 省得下次只能靠「有没有探测请求」反推。
            android.util.Log.d("SupeMov", "list fid=" + fid + " typeid=" + ftypeid
                    + " page=" + page + " kw=" + (kw == null ? "" : kw)
                    + " parsed=" + raw.size() + " pageCount=" + pageCount
                    + " probe=" + ((raw.isEmpty() || gen != loadGen) ? "skip" : "run"));

            // ① 先出画：不等百度链接探测
            main.post(() -> {
                if (gen != loadGen) return; // 已被更新的点击取代（新的那次会自己画）
                loading = false;
                currentPage = page;
                totalPages = Math.max(1, pageCount);
                if (page == 1) movieAdapter.setItems(raw);
                else movieAdapter.addItems(raw);
                if (movieAdapter.getItemCount() > 0) {
                    // 「加载中…」正拿着焦点时把它隐藏，焦点会掉到空处（遥控器上就是「焦点消失」）
                    boolean hadEmptyFocus = tvEmpty.isFocused();
                    tvEmpty.setVisibility(View.GONE);
                    if (hadEmptyFocus) rvList.requestFocus();
                } else {
                    showEmpty();
                }
            });

            if (raw.isEmpty() || gen != loadGen) return;

            // ② 再筛选：没有百度网盘分享链接的条目摘掉（独立线程池，不挡住下一次点击的加载）
            final List<Site.Movie> before = new ArrayList<>(raw);
            probePool.execute(() -> {
                Site.filterBaiduOnly(raw, 8);
                // 差集 = 被摘掉的那批。**只能传「要删的 tid」**：翻到第 2 页时 items 里
                // 还躺着第 1 页的条目，若按白名单「只留本页 tid」，第 1 页会被一起清空。
                final java.util.Set<String> alive = new java.util.HashSet<>();
                for (Site.Movie m : raw) if (m.tid != null) alive.add(m.tid);
                final java.util.Set<String> drop = new java.util.HashSet<>();
                for (Site.Movie m : before) {
                    if (m.tid != null && !alive.contains(m.tid)) drop.add(m.tid);
                }
                main.post(() -> {
                    if (gen != loadGen) return;
                    // 摘条目可能正好摘掉当前聚焦的那张海报：电视上焦点会掉到空处，先记下来
                    boolean hadListFocus = rvList.findFocus() != null;
                    if (!drop.isEmpty()) movieAdapter.dropTids(drop);
                    // 探测顺带把海报 / 日期·片长补进了 Movie 对象（原地改的），
                    // 必须重绑一次才会渲染出来 —— 否则角标要等下次进这个版块才出现
                    movieAdapter.refreshAll();
                    if (movieAdapter.getItemCount() == 0) showEmpty();
                    if (hadListFocus && rvList.findFocus() == null) {
                        if (!rvList.requestFocus() && tvEmpty.getVisibility() == View.VISIBLE) {
                            tvEmpty.requestFocus();
                        }
                    }
                });
            });
        });
    }

    /** 列表为空时的那句提示（登录失效 / 搜索太频繁 / 真的没内容）。 */
    private void showEmpty() {
        tvEmpty.setVisibility(View.VISIBLE);
        if ("login".equals(Site.lastLoadError)) {
            tvEmpty.setText("论坛登录已过期，请到 设置 → 论坛登录 重新登录");
            Toast.makeText(this, "论坛登录已过期，请到 设置 → 论坛登录 重新登录", Toast.LENGTH_LONG).show();
        } else if ("flood".equals(Site.lastLoadError)) {
            tvEmpty.setText("搜索太频繁，请等 10 秒后再试");
            Toast.makeText(this, "搜索太频繁，请等 10 秒后再试", Toast.LENGTH_SHORT).show();
        } else {
            tvEmpty.setText("没有内容");
        }
    }

    private boolean kwEquals(String kw) {
        return (searchKeyword == null ? "" : searchKeyword)
                .equals(kw == null ? "" : kw);
    }
}
