package com.supermov.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
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
        catAdapter.setItems(catOptions);
        catAdapter.setOnClick((o, pos) -> {
            if (pos >= Site.categories().size()) {
                startActivity(new Intent(this, SettingsActivity.class));
            } else {
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
        movieAdapter.setOnClick(m -> confirmTransfer(m));
        rvList.setAdapter(movieAdapter);
        rvList.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                super.onScrolled(rv, dx, dy);
                LinearLayoutManager lm = (LinearLayoutManager) rv.getLayoutManager();
                if (lm == null) return;
                int last = lm.findLastVisibleItemPosition();
                // 提前量随列数放大（一行就有 posterSpans 个条目）
                if (!loading && currentPage < totalPages
                        && last >= movieAdapter.getItemCount() - posterSpans * 2) {
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

    /** 点击海报：确认后直接转存该影片到设置目录。 */
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

    private void loadPage(int page) {
        if (loading) return;
        loading = true;
        final String kw = searchKeyword;
        final int fid = currentFid;
        final int ftypeid = currentTypeid;
        tvEmpty.setVisibility(View.VISIBLE);
        tvEmpty.setText("加载中…");
        pool.execute(() -> {
            Site.lastLoadError = "";
            Site.Paged<List<Site.Movie>> res;
            if (kw != null && !kw.isEmpty()) {
                res = Site.search(kw, page);
            } else {
                res = Site.category(fid, page, ftypeid);
            }
            // 后台线程过滤：只保留有百度网盘分享链接的影片
            Site.filterBaiduOnly(res.data, 8);
            main.post(() -> {
                loading = false;
                // 过期结果丢弃（用户已切换版块/过滤器/搜索词）
                boolean stale = !kwEquals(kw)
                        || ((kw == null || kw.isEmpty())
                            && (fid != currentFid || ftypeid != currentTypeid));
                if (stale) return;
                // 海报/日期/片长已在后台探测阶段补全（filterBaiduOnly）
                currentPage = page;
                totalPages = Math.max(1, res.pageCount);
                if (page == 1) movieAdapter.setItems(res.data);
                else movieAdapter.addItems(res.data);
                boolean empty = movieAdapter.getItemCount() == 0;
                tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
                if (empty && "login".equals(Site.lastLoadError)) {
                    tvEmpty.setText("论坛登录已过期，请到 设置 → 论坛登录 重新登录");
                    Toast.makeText(this, "论坛登录已过期，请到 设置 → 论坛登录 重新登录", Toast.LENGTH_LONG).show();
                } else if (empty && "flood".equals(Site.lastLoadError)) {
                    tvEmpty.setText("搜索太频繁，请等 10 秒后再试");
                    Toast.makeText(this, "搜索太频繁，请等 10 秒后再试", Toast.LENGTH_SHORT).show();
                } else {
                    tvEmpty.setText(empty ? "没有内容" : "");
                }
            });
        });
    }

    private boolean kwEquals(String kw) {
        return (searchKeyword == null ? "" : searchKeyword)
                .equals(kw == null ? "" : kw);
    }
}
