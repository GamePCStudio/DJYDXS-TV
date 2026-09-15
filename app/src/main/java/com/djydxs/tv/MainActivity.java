package com.djydxs.tv;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 首页：左侧(顶部横向)版块分类 + 影片网格 + 设置入口。
 * TV 遥控器友好：分类一行横滚，列表纵向滚动。
 */
public class MainActivity extends Activity {

    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private RecyclerView rvCats;
    private RecyclerView rvList;
    private TextView tvTitle;
    private TextView tvEmpty;

    private OptionAdapter catAdapter;
    private MovieAdapter movieAdapter;

    private int currentFid;
    private int currentPage = 1;
    private int totalPages = 1;
    private boolean loading = false;
    private boolean settingsSelected = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        CookieStore.init(this);
        Settings.init(this);
        setContentView(R.layout.activity_list);

        tvTitle = findViewById(R.id.titleView);
        tvEmpty = findViewById(R.id.emptyView);
        rvCats = findViewById(R.id.recyclerView);

        // 列表：纵向 + 无限翻页
        rvList = findViewById(R.id.rvMovies);
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

        // 顶部：分类 + 设置，横向滚动
        rvCats.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        catAdapter = new OptionAdapter();
        List<OptionAdapter.Option> cats = new ArrayList<>();
        for (Site.Category c : Site.categories()) {
            cats.add(new OptionAdapter.Option(c.name));
        }
        cats.add(new OptionAdapter.Option("⚙ 设置").hi());
        catAdapter.setItems(cats);
        catAdapter.setOnClick((o, pos) -> {
            if (pos >= Site.categories().size()) {
                startActivity(new Intent(this, SettingsActivity.class));
            } else {
                settingsSelected = false;
                currentFid = Site.categories().get(pos).fid;
                tvTitle.setText(Site.categories().get(pos).name);
                reload();
            }
        });
        rvCats.setAdapter(catAdapter);

        // 默认加载第一个版块
        currentFid = Site.categories().get(0).fid;
        tvTitle.setText(Site.categories().get(0).name);
        loadPage(1);
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
        final int fid = currentFid;
        tvEmpty.setVisibility(View.VISIBLE);
        tvEmpty.setText("加载中…");
        pool.execute(() -> {
            Site.Paged<List<Site.Movie>> res = Site.category(fid, page);
            main.post(() -> {
                loading = false;
                if (fid != currentFid) return; // 已切换版块，丢弃过期结果
                currentPage = page;
                totalPages = Math.max(1, res.pageCount);
                if (page == 1) movieAdapter.setItems(res.data);
                else movieAdapter.addItems(res.data);
                tvEmpty.setVisibility(movieAdapter.getItemCount() == 0 ? View.VISIBLE : View.GONE);
                tvEmpty.setText("没有内容（若持续为空，请在浏览器登录论坛后重新打开）");
            });
        });
    }
}
