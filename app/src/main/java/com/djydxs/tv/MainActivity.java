package com.djydxs.tv;

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
    private RecyclerView rvList;
    private TextView tvTitle;
    private TextView btnSearch;
    private TextView tvEmpty;

    private OptionAdapter catAdapter;
    private MovieAdapter movieAdapter;

    private int currentFid;
    private int currentPage = 1;
    private int totalPages = 1;
    private boolean loading = false;
    private String searchKeyword = ""; // 空 = 浏览版块；非空 = 搜索模式

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        CookieStore.init(this);
        Settings.init(this);
        Site.ensureForumCookie(); // 注入内置论坛 Cookie，解锁会员版块
        setContentView(R.layout.activity_list);

        tvTitle = findViewById(R.id.titleView);
        btnSearch = findViewById(R.id.btnSearch);
        tvEmpty = findViewById(R.id.emptyView);
        rvCats = findViewById(R.id.recyclerView);
        rvList = findViewById(R.id.rvMovies);

        // 分类行
        rvCats.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        catAdapter = new OptionAdapter();
        List<OptionAdapter.Option> cats = new ArrayList<>();
        for (Site.Category c : Site.categories()) {
            cats.add(new OptionAdapter.Option(c.name));
        }
        cats.add(new OptionAdapter.Option("⚙ 设置"));
        catAdapter.setItems(cats);
        catAdapter.setOnClick((o, pos) -> {
            if (pos >= Site.categories().size()) {
                startActivity(new Intent(this, SettingsActivity.class));
            } else {
                searchKeyword = "";
                currentFid = Site.categories().get(pos).fid;
                tvTitle.setText(Site.categories().get(pos).name);
                reload();
            }
        });
        rvCats.setAdapter(catAdapter);

        // 影片列表
        rvList.setLayoutManager(new LinearLayoutManager(this));
        movieAdapter = new MovieAdapter();
        movieAdapter.setOnClick(m -> {
            Intent it = new Intent(this, DetailActivity.class);
            it.putExtra("fid", m.fid);
            it.putExtra("tid", m.tid);
            it.putExtra("name", m.name);
            startActivity(it);
        });
        rvList.setAdapter(movieAdapter);
        rvList.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                super.onScrolled(rv, dx, dy);
                LinearLayoutManager lm = (LinearLayoutManager) rv.getLayoutManager();
                if (lm == null) return;
                int last = lm.findLastVisibleItemPosition();
                if (!loading && currentPage < totalPages && last >= movieAdapter.getItemCount() - 4) {
                    loadPage(currentPage + 1);
                }
            }
        });

        // 搜索按钮
        btnSearch.setOnClickListener(v -> showSearchDialog());
        btnSearch.setOnFocusChangeListener((v, has) -> v.setAlpha(has ? 1f : 0.85f));

        // 默认加载第一个版块
        currentFid = Site.categories().get(0).fid;
        tvTitle.setText(Site.categories().get(0).name);
        loadPage(1);
    }

    private void showSearchDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog);
        builder.setTitle("搜索影片");

        final EditText input = new EditText(this);
        input.setHint("输入片名关键词");
        input.setTextSize(16);
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
        searchKeyword = kw;
        tvTitle.setText("搜索: " + kw);
        reload();
    }

    private void reload() {
        currentPage = 1;
        totalPages = 1;
        movieAdapter.setItems(new ArrayList<>());
        loadPage(1);
    }

    private void loadPage(int page) {
        if (loading) return;
        loading = true;
        final String kw = searchKeyword;
        final int fid = currentFid;
        tvEmpty.setVisibility(View.VISIBLE);
        tvEmpty.setText("加载中…");
        pool.execute(() -> {
            Site.Paged<List<Site.Movie>> res;
            if (kw != null && !kw.isEmpty()) {
                res = Site.search(kw, page);
            } else {
                res = Site.category(fid, page);
            }
            main.post(() -> {
                loading = false;
                // 过期结果丢弃（用户已切换版块/搜索词）
                boolean stale = (kw == null || kw.isEmpty())
                        ? (!kwEquals(kw) || fid != currentFid)
                        : !kwEquals(kw);
                if (stale) return;
                currentPage = page;
                totalPages = Math.max(1, res.pageCount);
                if (page == 1) movieAdapter.setItems(res.data);
                else movieAdapter.addItems(res.data);
                boolean empty = movieAdapter.getItemCount() == 0;
                tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
                tvEmpty.setText(empty ? "没有内容" : "");
            });
        });
    }

    private boolean kwEquals(String kw) {
        return (searchKeyword == null ? "" : searchKeyword)
                .equals(kw == null ? "" : kw);
    }
}
