package com.supermov.tv;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
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
 * 首页，两种形态：
 *
 * <ul>
 *   <li><b>专题墙</b>（默认）—— 全屏横版背景层 + Hero 文字面板 + 多条横向专题行，
 *       即 SAM-CINEMA {@code page_home} 的形态；</li>
 *   <li><b>列表页</b>—— 原来的「分类 + 过滤器 + 7 列海报网格」。
 *       搜索、点版块、点行尾「更多」时进入。这部分逻辑一行没动，
 *       连同分页、空态兜底、返回栈全部复用。</li>
 * </ul>
 *
 * <h3>焦点即预览</h3>
 * 焦点落在任意一张海报上，Hero 的文字与全屏背景就换成那部片子（300ms 防抖）。
 * 背景图优先用真正的 16:9 横图（{@link BackdropMap}），取不到才回落到竖海报居中裁剪。
 */
public class MainActivity extends Activity {

    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    /** 只给「焦点防抖」用，和 {@link #main} 分开：上面那个还跑着列表加载的 post，别互相踩。 */
    private final Handler heroDebounce = new Handler(Looper.getMainLooper());

    private RecyclerView rvCats;
    private RecyclerView rvFilters;
    private RecyclerView rvList;
    private LinearLayout listPane;
    private TextView btnSearch;
    private TextView tvEmpty;

    /** 专题墙（竖向 RV，第 0 项是 Hero 面板）。 */
    private RecyclerView rvHome;
    private HomeAdapter homeAdapter;

    /** 全屏背景层两张图：交叉淡入用「新图放后面、把前面那张淡出」。 */
    private ImageView bgA, bgB;
    /** 当前可见的是不是 bgA。初始 false —— 第一次换图会落在 bgA 上并把它设为可见。 */
    private boolean bgTopIsA = false;
    private String bgCurrentUrl = "";
    private int bgGen = 0;

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
    private String searchKeyword = ""; // 空 = 浏览版块；非空 = 搜索模式
    private int currentTypeid = 0;      // 版块内主题分类过滤（0=全部）
    private int currentFilterIndex = 0; // 选中的过滤器下标

    /**
     * 顶栏第 0 项固定是「首页」，也就是进去的第一屏（专题墙）；
     * 其后才是各版块，最后两项是 设置 / 下载。
     */
    private static final int CAT_HOME = 0;

    /** true = 当前显示列表页；false = 显示专题墙。 */
    private boolean listMode = false;
    /** 顶栏当前高亮的栏目下标（0 = 首页）。返回键恢复页面状态靠它，不拿 fid 反推。 */
    private int currentCat = CAT_HOME;
    /** 专题墙数据是否已经拿过 —— 点「首页」来回切栏目时不必重跑那 16 条 SQL。 */
    private boolean homeLoaded = false;
    /** 列表页的数据来源：非 null 表示「从某条专题行的『更多』进来」，此时不用 fid/搜索。 */
    private MovieStore.Topic listTopic;
    /** 列表页空态提示里显示的名字。 */
    private String listTitle = "";

    /** 焦点停下后要切到的那部片子（配合 {@link #HERO_DEBOUNCE_MS} 防抖）。 */
    private MovieStore.Movie pendingHero;
    /** 首次进专题墙时把焦点落到大标题上一次，之后不再抢焦点。 */
    private boolean firstHomeFocusDone = false;

    /** 遥控器连按方向键时只对「停下来那一张」重渲染 Hero。SAM-CINEMA 原版用 500ms，这里取 300ms。 */
    private static final int HERO_DEBOUNCE_MS = 300;

    /**
     * 上下键方向（{@link #moveFocusBetweenRows} 系列方法的入参）：下 = 1、上 = -1。
     *
     * <p>刻意不用 {@code View.FOCUS_DOWN/UP}（那是 130 / 33）当内部方向值：
     * 这里要做的是「大 / 小」比较，±1 读起来清楚。只在调系统焦点搜索
     * （{@link View#focusSearch(int)}）时转成 FOCUS_* 常量，见 {@link #focusDirConst}。</p>
     */
    private static final int DIR_DOWN = 1;
    private static final int DIR_UP = -1;

    /**
     * 焦点目标的查找重试额度：每 {@link #FOCUS_RETRY_MS} 一次，共 400ms。
     *
     * <p>目标行要靠 {@code scrollToPosition} 之后的下一次布局才会挂上来，
     * 一次 post 未必赶得上；而电视盒子的单帧比手机长，额度给宽一点更稳。</p>
     */
    private static final int FOCUS_RETRY = 10;
    private static final int FOCUS_RETRY_MS = 40;

    /** 导航历史：搜索/切分类/切过滤器/进专题列表前压栈，返回键弹栈回到上一个页面状态。 */
    private static class ViewState {
        final String kw;
        final int fid;
        final int typeid;
        final MovieStore.Topic topic;
        final String title;
        final boolean list;
        /**
         * 当时顶栏高亮的是哪一栏。
         *
         * <p>存下标而不是返回时用 fid 反推：现在顶栏多了「首页」这一项，
         * 而 {@code currentFid} 会留着上一个版块的值（回到首页时不会清零），
         * 拿它反推会把「4K全景声」点亮，和实际显示的专题墙对不上。</p>
         */
        final int cat;

        ViewState(String kw, int fid, int typeid, MovieStore.Topic topic, String title,
                  boolean list, int cat) {
            this.kw = kw;
            this.fid = fid;
            this.typeid = typeid;
            this.topic = topic;
            this.title = title;
            this.list = list;
            this.cat = cat;
        }
    }

    private final java.util.ArrayDeque<ViewState> navHistory = new java.util.ArrayDeque<>();

    private void pushHistory() {
        navHistory.addLast(new ViewState(searchKeyword, currentFid, currentTypeid,
                listTopic, listTitle, listMode, currentCat));
        while (navHistory.size() > 30) navHistory.pollFirst();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        CookieStore.init(this);
        Settings.init(this);
        MovieStore.init(this); // 打开 SuperMOV.db（内嵌 / 已在线更新）—— 全部内容都来自这里
        BackdropMap.init(this); // 载入 assets/backdrops.json（TMDB id → 横版背景图，8.7KB）
        setContentView(R.layout.activity_list);

        btnSearch = findViewById(R.id.btnSearch);
        tvEmpty = findViewById(R.id.emptyView);
        rvCats = findViewById(R.id.recyclerView);
        rvFilters = findViewById(R.id.rvFilters);
        rvList = findViewById(R.id.rvMovies);
        listPane = findViewById(R.id.listPane);
        rvHome = findViewById(R.id.rvHome);
        bgA = findViewById(R.id.bgBackdropA);
        bgB = findViewById(R.id.bgBackdropB);

        // 分类行：第 0 项固定是「首页」（= 专题墙，也就是进去的第一屏），
        // 其后才是各版块，最后两项是 设置 / 下载
        rvCats.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        catAdapter = new OptionAdapter();
        catAdapter.attach(rvCats); // 「只刷高亮不发通知」需要宿主列表定位已挂载条目
        catOptions.add(new OptionAdapter.Option("首页"));
        for (MovieStore.Category c : MovieStore.categories()) {
            catOptions.add(new OptionAdapter.Option(c.name));
        }
        catOptions.add(new OptionAdapter.Option("⚙ 设置"));
        catOptions.add(new OptionAdapter.Option("⬇ 下载"));
        catAdapter.setItems(catOptions);
        catAdapter.setOnClick((o, pos) -> {
            int catCount = MovieStore.categories().size();
            if (pos == CAT_HOME) {
                // 首页 = 回到专题墙。它就是根页面，所以不压返回栈
                toHome();
                refocusOption(rvCats, pos, 0);
            } else if (pos == catCount + 1) {
                startActivity(new Intent(this, SettingsActivity.class));
            } else if (pos == catCount + 2) {
                // 下载队列：不压历史栈（它不是内容页，返回应直接回首页）
                startActivity(new Intent(this, DownloadActivity.class));
            } else if (pos >= 1 && pos <= catCount) {
                pushHistory(); // 返回键可回到上一个版块
                showListPane();
                listTopic = null;
                listTitle = "";
                searchKeyword = "";
                currentFid = MovieStore.categories().get(pos - 1).fid;
                currentTypeid = 0;
                currentFilterIndex = 0;
                markCat(pos);
                buildFilterRow();
                reload();
                // 遥控器习惯：焦点要留在「被按下的那一栏」上，而不是跟着页面跳走
                refocusOption(rvCats, pos, 0);
            }
        });
        rvCats.setAdapter(catAdapter);
        markCat(CAT_HOME); // 默认停在「首页」上，与进去看到的第一屏（专题墙）一致

        // 过滤器行（各版块主题分类，搜索模式与专题列表隐藏）
        rvFilters.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        filterAdapter = new OptionAdapter();
        filterAdapter.attach(rvFilters);
        filterAdapter.setOnClick((o, pos) -> {
            List<MovieStore.Filter> fs = MovieStore.filtersFor(currentFid);
            if (pos < 0 || pos >= fs.size()) return;
            MovieStore.Filter f = fs.get(pos);
            if (f.typeid == currentTypeid) return;
            pushHistory(); // 返回键可回到上一个过滤状态
            currentTypeid = f.typeid;
            currentFilterIndex = pos;
            buildFilterRow(); // 这一行会整表重建，焦点会被清掉 —— 下面补回来
            reload();
            refocusOption(rvFilters, pos, 0);
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

        // 专题墙：第 0 项 Hero 面板，其后每条一项专题行
        rvHome.setLayoutManager(new LinearLayoutManager(this));
        homeAdapter = new HomeAdapter();
        homeAdapter.setCallbacks(this::openDetail, this::confirmTransfer,
                this::openTopicMore, this::onFocusMovie);
        rvHome.setAdapter(homeAdapter);

        // 搜索按钮
        btnSearch.setOnClickListener(v -> showSearchDialog());
        btnSearch.setOnFocusChangeListener((v, has) -> {
            v.setAlpha(has ? 1f : 0.85f);
            v.setScaleX(has ? 1.1f : 1f);
            v.setScaleY(has ? 1.1f : 1f);
        });

        // 默认：进专题墙
        currentFid = MovieStore.categories().get(0).fid;
        buildFilterRow(); // currentFid 就绪后重建过滤器行
        loadHome();

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
        showListPane();
        listTopic = null;
        listTitle = "";
        searchKeyword = kw;
        buildFilterRow(); // 搜索模式内部会隐藏过滤器行
        reload();
    }

    /**
     * 刷新分类行高亮。pos = {@link #CAT_HOME} 表示停在「首页」那一栏。
     *
     * <p>这里刻意<b>不用 {@code notifyDataSetChanged()}</b>：整表重建会让 RecyclerView
     * 把条目视图摘下来重新挂载，视图离开窗口时系统会清掉它的焦点 ——
     * 遥控器上就是「按下顶栏某一栏，焦点从这一栏上跑掉了」。
     * 顶栏只有 6 个条目且永远全部可见，就地改已挂载的 View 即可。</p>
     */
    private void markCat(int pos) {
        currentCat = pos;
        for (int i = 0; i < catOptions.size(); i++) {
            catOptions.get(i).highlight = (i == pos);
        }
        catAdapter.refreshHighlight();
    }

    /**
     * 按当前版块重建过滤器行（每版块过滤器不同；搜索模式与专题列表隐藏）。
     *
     * <p>专题列表的过滤条件就是这条专题本身（比如「动作」那一行），
     * 再叠一层题材过滤器没有意义，所以一并隐藏。</p>
     */
    private void buildFilterRow() {
        List<MovieStore.Filter> fs = MovieStore.filtersFor(currentFid);
        List<OptionAdapter.Option> opts = new ArrayList<>();
        for (MovieStore.Filter f : fs) {
            OptionAdapter.Option o = new OptionAdapter.Option(f.name);
            o.highlight = (f.typeid == currentTypeid);
            opts.add(o);
        }
        filterAdapter.setItems(opts);
        boolean browsing = listTopic == null
                && (searchKeyword == null || searchKeyword.isEmpty());
        rvFilters.setVisibility((browsing && !fs.isEmpty()) ? View.VISIBLE : View.GONE);
    }

    // ==================================================================
    // 专题墙 ↔ 列表页 的切换
    // ==================================================================

    private void showHome() {
        listMode = false;
        listPane.setVisibility(View.GONE);
        rvHome.setVisibility(View.VISIBLE);
    }

    private void showListPane() {
        listMode = true;
        rvHome.setVisibility(View.GONE);
        listPane.setVisibility(View.VISIBLE);
    }

    /**
     * 回到顶栏的「首页」栏目 = 显示专题墙。
     *
     * <p>专题墙数据拿过一次就不再重查：它一轮是 16 条 SQL（各条专题各一趟），
     * 来回切栏目重跑一遍既没必要，也会让 Hero 白闪一下。只有第一次进来才真正去查。</p>
     */
    private void toHome() {
        markCat(CAT_HOME);
        listTopic = null;
        listTitle = "";
        searchKeyword = "";
        boolean needLoad = !homeLoaded;
        showHome();
        if (needLoad) loadHome();
    }

    /** 点专题行右侧的「更多 ›」：进这条专题的完整列表。 */
    private void openTopicMore(MovieStore.Topic t) {
        if (t == null) return;
        pushHistory();
        showListPane();
        listTopic = t;
        listTitle = t.title;
        searchKeyword = "";
        currentFid = t.fid; // fid 行是真实版块；题材 / 榜单行为 0
        currentTypeid = 0;
        currentFilterIndex = 0;
        markCat(catIndexOfFid(t.fid));
        buildFilterRow(); // 这里会把过滤器行藏掉（listTopic != null）
        reload();
    }

    /**
     * 版块 fid → 顶栏下标。
     *
     * <p>顶栏第 0 项是「首页」，所以版块整体 +1。找不到对应版块时给「首页」：
     * 题材 / 榜单专题的 fid 是 0，搜索也不属于任何版块，而它们都是从首页专题墙进去的，
     * 让「首页」那一栏保持点亮比「顶栏一栏都不亮」更像话。</p>
     */
    private int catIndexOfFid(int fid) {
        List<MovieStore.Category> cs = MovieStore.categories();
        for (int i = 0; i < cs.size(); i++) {
            if (cs.get(i).fid == fid) return i + 1;
        }
        return CAT_HOME;
    }

    /** 拉专题墙数据并填充。放后台线程：16 行 × 各一趟 SQL，一次性算完再上屏。 */
    private void loadHome() {
        pool.execute(() -> {
            final List<MovieStore.Topic> ts = MovieStore.topics();
            MovieStore.Movie hero = null;
            for (MovieStore.Topic t : ts) {
                if (!t.movies.isEmpty()) {
                    hero = t.movies.get(0);
                    break;
                }
            }
            final MovieStore.Movie first = hero;
            main.post(() -> {
                if (isFinishing()) return;
                homeAdapter.setTopics(ts);
                homeAdapter.showHero(first);
                applyBackdrop(first);
                // 拿到内容才算加载过；空结果（比如库还没就位）留 false，下次点「首页」再试
                homeLoaded = !ts.isEmpty();
                if (!ts.isEmpty() && !firstHomeFocusDone && !listMode) {
                    firstHomeFocusDone = true;
                    // 焦点落到大标题：一上来就能看到「上键回大标题 / 下键潜海报区」这套协议，
                    // 也是为了让「往下按」第一下就有反应（大标题 → 第一行海报）。
                    //
                    // 必须走带重试的 focusHeroTitle，不能只 post 一帧 ——
                    // notifyDataSetChanged 引起的那次布局还没跑完时 Hero 面板压根还没绑上
                    // （heroPanelView 为 null），焦点会静默落到窗口里第一个可聚焦项（顶栏）上。
                    focusHeroTitle(DIR_DOWN);
                }
            });
        });
    }

    // ==================================================================
    // 焦点即预览：Hero 文案 + 全屏背景
    // ==================================================================

    /**
     * 焦点落到某张海报。
     *
     * <p>防抖 300ms 再重渲染：遥控器连按方向键会一路刷过十几张海报，
     * 每张都换一次背景的话，既打满线程池又只看到一片闪烁。
     * 只有「停下来那一张」才有意义 —— 这与 SAM-CINEMA 的处理方式一致。</p>
     */
    private void onFocusMovie(MovieStore.Movie m) {
        if (m == null) return;
        pendingHero = m;
        heroDebounce.removeCallbacks(heroApplyRunnable);
        heroDebounce.postDelayed(heroApplyRunnable, HERO_DEBOUNCE_MS);
    }

    private final Runnable heroApplyRunnable = new Runnable() {
        @Override
        public void run() {
            MovieStore.Movie m = pendingHero;
            if (m == null || isFinishing()) return;
            homeAdapter.showHero(m);
            applyBackdrop(m);
        }
    };

    /**
     * 换全屏背景。
     *
     * <p>取图优先级：<b>TMDB 横版图</b>（16:9）→ <b>竖海报居中裁剪</b>。
     * 前者实测覆盖 217/321 部（68%），剩下的是论坛自传图、没有 TMDB id、
     * 或 TMDB 上确实没有 backdrop 的片子 —— 那些直接拿竖海报铺满，
     * 中间区域完整保留，观感与改造前一致，不会开天窗。</p>
     */
    private void applyBackdrop(final MovieStore.Movie m) {
        if (m == null) return;
        final String poster = m.pic == null ? "" : m.pic;
        final String backdrop = BackdropMap.backdropUrl(poster);
        final String key = backdrop.isEmpty() ? poster : backdrop;
        if (key.isEmpty() || key.equals(bgCurrentUrl)) return;
        bgCurrentUrl = key;
        final int gen = ++bgGen;

        Bitmap hit = BackdropLoader.peek(key);
        if (hit != null) {
            swapBackdrop(hit);
            return;
        }
        BackdropLoader.load(key, 0, 0, bmp -> {
            if (gen != bgGen) return; // 已被更新的焦点取代
            if (bmp != null) {
                swapBackdrop(bmp);
                return;
            }
            // 横图拉失败 → 回落竖海报。注意把 bgCurrentUrl 记成海报地址，
            // 否则同一张挂掉的横图会在每次焦点经过时重试一遍。
            if (!key.equals(poster) && !poster.isEmpty()) {
                bgCurrentUrl = poster;
                Bitmap p = BackdropLoader.peek(poster);
                if (p != null) {
                    swapBackdrop(p);
                    return;
                }
                final int gen2 = bgGen;
                BackdropLoader.load(poster, 0, 0, b2 -> {
                    if (gen2 == bgGen && b2 != null) swapBackdrop(b2);
                });
            }
        });
    }

    /**
     * 把新图换上并交叉淡入。
     *
     * <p>两张 ImageView 叠着（bgB 在 bgA 之上），新图永远落在<b>当前在后面</b>的那张上，
     * 再把前面那张淡出 —— 视觉上就是一次干净的交叉溶解。
     * 若改成「单张图 alpha 0 → 1」，中间会露一下底色，快速切焦点时非常明显。</p>
     */
    private void swapBackdrop(Bitmap bmp) {
        if (bmp == null || bgA == null || bgB == null) return;
        ImageView incoming = bgTopIsA ? bgB : bgA;
        ImageView outgoing = bgTopIsA ? bgA : bgB;
        incoming.animate().cancel();
        incoming.setImageBitmap(bmp);
        incoming.setAlpha(1f);
        boolean first = outgoing.getDrawable() == null;
        bgTopIsA = !bgTopIsA;
        if (first) return; // 首次没有旧图可淡出，直接上
        outgoing.animate().cancel();
        outgoing.animate().alpha(0f).setDuration(280).start();
    }

    // ==================================================================
    // 遥控器上下键：Hero ↔ 专题行 ↔ 相邻专题行
    // ==================================================================

    /**
     * 先于焦点视图拿到方向键（Activity 的 dispatchKeyEvent 在按键分发链最前面），
     * 交给 {@link #moveFocusBetweenRows} 判断是否接管。
     *
     * <p>只处理 {@code ACTION_DOWN}：长按会不断续发 DOWN，只处理它即可持续移动；
     * UP 交回系统，免得长按时把「抬手」也吃掉。</p>
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            int code = e.getKeyCode();
            int dir = 0;
            if (code == KeyEvent.KEYCODE_DPAD_DOWN) dir = DIR_DOWN;
            else if (code == KeyEvent.KEYCODE_DPAD_UP) dir = DIR_UP;
            if (dir != 0 && moveFocusBetweenRows(dir)) return true;
        }
        return super.dispatchKeyEvent(e);
    }

    /**
     * 上下键做「行级」跳转。
     *
     * <p><b>为什么必须自己接管：</b>外层是竖向 RecyclerView，每条专题行里又嵌了一个横向
     * RecyclerView。焦点落在横向行里的某张海报上时按上下键，系统焦点搜索只会在这条横向行
     * 内部找目标（找不到就把事件交回父容器），父容器又只认得「行容器」这一层 ——
     * 结果是上下键要么没反应、要么在同一行里绕圈。嵌套 RV 这个坑必须手动跨过去。</p>
     *
     * <p>协议（与 SAM-CINEMA 一致）：</p>
     * <ul>
     *   <li>第一行任一张海报按<b>上键</b> → 回到 Hero 大标题；</li>
     *   <li>Hero 大标题按<b>下键</b> → 潜进第一行第一张海报；</li>
     *   <li>顶栏（分类行 / 搜索按钮）按<b>下键</b> → 进 Hero 大标题；</li>
     *   <li>行与行之间按上下键 → 跳到相邻行的<b>同一列</b>，位置感不丢。</li>
     * </ul>
     *
     * <p><b>返回 true = 这个按键由我们接管了。</b>接管是有代价的：一旦接管之后
     * 目标焦点没落上去，事件也不会再回到系统，遥控器上就是「按了完全没反应」。
     * 所以每个异步跳转的最后一步都必须走到 {@link #systemFocusFallback}，
     * 把「按方向找下一个」交回系统兜底。</p>
     *
     * @param dir {@link #DIR_DOWN} 或 {@link #DIR_UP}
     */
    private boolean moveFocusBetweenRows(int dir) {
        if (listMode || homeAdapter == null || homeAdapter.getItemCount() == 0) return false;
        View f = getCurrentFocus();
        if (f == null) {
            // 焦点掉到空处（浮层刚关掉、条目被回收…）。不接的话事件会交给系统，
            // 而系统挑的第一个可聚焦项往往是顶栏 —— 表现就是「按了没反应 / 焦点乱跳」。
            // 往下按就把它接回 Hero 大标题，这也是用户此刻想看到的东西。
            if (dir > 0) return focusHeroTitle(dir);
            return false;
        }

        // 情形一：焦点在某条专题行里。
        // 注意范围要放宽到「整条行」而不只是「横向 RV 里」—— 行标题右边那个
        // 「更多 ›」也是 focusable 的，而它是行根的直接子 View、不在横向 RV 里。
        // 只认 rvTopicRow 的话，焦点一旦落在「更多」上，上下键就没人管了，
        // 又变成「按了没反应」。
        RecyclerView rowRv = ancestorTopicRowRv(f);
        View rowItem = directChildOf(rvHome, f);
        if (rowItem != null) {
            int pos = rvHome.getChildAdapterPosition(rowItem);
            if (homeAdapter.isTopicRow(pos)) {
                int col = 0;                       // 「更多」不在海报行里，列号无从谈起，按 0 算
                if (rowRv != null) {
                    int c = rowRv.getChildAdapterPosition(f);
                    if (c >= 0) col = c;
                }

                if (dir > 0) {
                    if (pos + 1 < homeAdapter.getItemCount()) {
                        focusTopicRow(pos + 1, col, dir);
                        return true;
                    }
                    // 已在最后一行。这里不接管：交回系统，让它按常规搜索去别处
                    // （比如往下就什么都找不到，自然停住），比我们吞掉事件什么都不做强。
                    return false;
                }
                if (pos > HomeAdapter.HERO_INDEX + 1) {
                    focusTopicRow(pos - 1, col, dir);
                    return true;
                }
                // 已在第一行 → 回 Hero 大标题
                return focusHeroTitle(dir);
            }
        }

        // 情形二：焦点在 Hero 面板里
        View hero = homeAdapter.heroPanelView();
        if (hero != null && isInsideOrSelf(hero, f)) {
            if (dir > 0) {
                focusTopicRow(HomeAdapter.HERO_INDEX + 1, 0, dir);
                return true;
            }
            return false; // 向上交给顶栏（分类行 / 搜索按钮）
        }

        // 情形三：焦点在顶栏（分类行里的某一栏 / 搜索按钮）
        // 下键从顶栏潜进专题墙的 Hero 大标题。这条对「首页」这一栏是必需的：
        // 从别的栏目切回首页后，焦点按遥控器习惯留在被按下的那一栏上，
        // 若下键不接管，就只能指望系统焦点搜索 —— 而 Hero 是 rvHome 的第 0 项，
        // 往下滚过之后它可能不在可视区，系统找不到，焦点会卡在顶栏出不去。
        if (dir > 0 && isInTopBar(f)) {
            return focusHeroTitle(dir);
        }
        return false;
    }

    /**
     * Hero 大标题拿焦点（顺带把 Hero 滚进视野）。
     *
     * <p>{@code scrollToPosition} 是异步的：本帧布局走完之前 Hero 可能还没被挂回来，
     * 直接 requestFocus 会失败（焦点原地不动），所以 post 出去重试几帧 ——
     * 和 {@link #focusTopicRow} 是同一套路子。</p>
     *
     * @param dir 原按键方向，只用于重试全部落空后交回系统搜索
     */
    private boolean focusHeroTitle(int dir) {
        if (homeAdapter == null || homeAdapter.getItemCount() == 0) return false;
        rvHome.scrollToPosition(HomeAdapter.HERO_INDEX);
        postFocusHero(0, dir);
        return true;
    }

    /**
     * Hero 大标题的焦点重试。Hero 面板里没有横向 RV，目标就是那个「大标题」。
     *
     * <p>「大标题」能拿焦点有个前提：Hero 面板根<b>不能</b>写
     * {@code descendantFocusability="blocksDescendants"} —— 那会让所有子孙的
     * {@code requestFocus()} 恒返回 false（AOSP 的 hasAncestorThatBlocksDescendantFocus），
     * 见 {@code item_hero_panel.xml} 的注释。</p>
     */
    private void postFocusHero(final int attempt, final int dir) {
        if (isFinishing()) return;
        rvHome.postDelayed(() -> {
            if (isFinishing()) return;
            RecyclerView.ViewHolder vh =
                    rvHome.findViewHolderForAdapterPosition(HomeAdapter.HERO_INDEX);
            // 只认「真挂在 rvHome 上」的 Hero：被回收/摘掉的旧面板
            // requestFocus() 同样返回 true，但那个焦点是假的（下一次布局就没了）
            if (vh != null && vh.itemView.getParent() == rvHome) {
                if (isFocusInside(vh.itemView)) return; // 已确认落在 Hero 里，收工
                TextView title = vh.itemView.findViewById(R.id.heroTitleBig);
                // 判据是「下一轮复查时焦点真在这个 View 上」，不是 requestFocus 的返回值 ——
                // 对已经脱离窗口的旧条目它同样返回 true
                if (title != null && title.requestFocus()) {
                    if (isFocusInside(title)) return;
                }
                if (vh.itemView.requestFocus() && isFocusInside(vh.itemView)) return;
            }
            if (attempt < FOCUS_RETRY) {
                postFocusHero(attempt + 1, dir);
                return;
            }
            android.util.Log.d("SupeMov", "Hero 大标题拿焦点失败（重试 "
                    + FOCUS_RETRY + " 次），交回系统搜索 dir=" + dir);
            systemFocusFallback(dir);
        }, FOCUS_RETRY_MS);
    }

    /**
     * 兜底：把「按方向找下一个」交回系统焦点搜索。
     *
     * <p>自己接管上下键的代价是：目标没找到、事件又被我们吞掉，遥控器就彻底没反应了。
     * 所以每个异步跳转重试耗尽时都要走到这里 —— 从<b>当前焦点</b>出发按方向找下一个
     * 可聚焦项，找到就落上去。找不到也只是这一次按键没结果，不会让焦点卡死。</p>
     */
    private void systemFocusFallback(int dir) {
        if (dir == 0) return;
        View f = getCurrentFocus();
        if (f == null) return;
        View next = f.focusSearch(focusDirConst(dir));
        if (next != null && next != f && next.isFocusable()) next.requestFocus();
    }

    /** 内部方向（±1）→ 系统焦点搜索常量（{@code View.FOCUS_DOWN/UP}）。 */
    private static int focusDirConst(int dir) {
        return dir > 0 ? View.FOCUS_DOWN : View.FOCUS_UP;
    }

    /** 当前焦点是不是落在 root 上或 root 里面（root 内的任意子孙都算）。 */
    private boolean isFocusInside(View root) {
        View f = getCurrentFocus();
        return f != null && isInsideOrSelf(root, f);
    }

    /** 焦点是不是在顶栏上（分类行里的某一栏 / 搜索按钮）。 */
    private boolean isInTopBar(View v) {
        if (v == btnSearch) return true;
        return v == rvCats || directChildOf(rvCats, v) != null;
    }

    /**
     * 把焦点按回顶栏某一行选项里的第 pos 项（分类行 / 过滤器行共用）。
     *
     * <p>遥控器习惯：按下顶栏某一栏之后，焦点应当留在<b>被按下的那一栏</b>上，
     * 而不是跟着页面一起跳到别处去。可现在一次切页里有一串动作都可能把焦点带走
     * （过滤行整表重建、空态文案显隐、列表整表重建……），虽然分类行已经改成
     * 「只刷高亮不动数据集」，仍在这里统一复位一次兜底。</p>
     *
     * <p>那一行若刚做过整表重建，新条目要等下一次布局才挂上来，一次 post 未必赶得上，
     * 所以重试几帧。<b>成功的判据是「下一轮复查时焦点确实在这一项上」</b>，
     * 而不是 {@code requestFocus()} 的返回值 —— 对已经脱离 RecyclerView 的旧条目，
     * 它照样返回 true，但那个焦点是假的（下一次布局就没了）。</p>
     */
    private void refocusOption(final RecyclerView rv, final int pos, final int attempt) {
        if (isFinishing() || rv == null) return;
        rv.postDelayed(() -> {
            if (isFinishing()) return;
            View cur = rv.getFocusedChild();
            if (cur != null && rv.getChildAdapterPosition(cur) == pos) return; // 已确认落在目标上
            RecyclerView.ViewHolder vh = rv.findViewHolderForAdapterPosition(pos);
            // 只对「真挂在这一行上」的条目请求焦点（同 postFocusHero 的道理）
            if (vh != null && vh.itemView.getParent() == rv) vh.itemView.requestFocus();
            if (attempt < FOCUS_RETRY) refocusOption(rv, pos, attempt + 1);
        }, FOCUS_RETRY_MS);
    }

    /** 从焦点 View 往上找它所属的「专题行横向 RV」。不在任何行里则返回 null。 */
    private RecyclerView ancestorTopicRowRv(View v) {
        android.view.ViewParent p = v.getParent();
        while (p instanceof View) {
            if (p instanceof RecyclerView && ((View) p).getId() == R.id.rvTopicRow) {
                return (RecyclerView) p;
            }
            p = ((View) p).getParent();
        }
        return null;
    }

    /** 从 v 一路向上，返回 parent 的直接子 View。 */
    private View directChildOf(RecyclerView parent, View v) {
        android.view.ViewParent p = v.getParent();
        while (p instanceof View) {
            if (p == parent) return v;
            v = (View) p;
            p = v.getParent();
        }
        return null;
    }

    private static boolean isInsideOrSelf(View ancestor, View v) {
        if (ancestor == v) return true;
        android.view.ViewParent p = v.getParent();
        while (p instanceof View) {
            if (p == ancestor) return true;
            p = ((View) p).getParent();
        }
        return false;
    }

    /**
     * 把焦点送到第 pos 项那条专题行的第 hintCol 列。
     *
     * <p>目标行可能还没被布局出来（RV 只保留可视区 + 少量缓存），所以要
     * {@code scrollToPosition} 之后再 post 出去重试几次 —— 一次 post 未必够，
     * 大跨度跳行时布局要过好几帧。</p>
     *
     * @param dir 原按键方向，只用于重试全部落空后交回系统搜索
     */
    private void focusTopicRow(int pos, int hintCol, int dir) {
        if (homeAdapter == null || pos < 0 || pos >= homeAdapter.getItemCount()) return;
        rvHome.scrollToPosition(pos);
        postFocusRow(pos, hintCol, 0, dir);
    }

    private void postFocusRow(final int pos, final int hintCol, final int attempt, final int dir) {
        if (isFinishing()) return;
        rvHome.postDelayed(() -> {
            if (isFinishing()) return;
            RecyclerView.ViewHolder vh = rvHome.findViewHolderForAdapterPosition(pos);
            // 只认「真挂在 rvHome 上」的那一行（同 postFocusHero 的道理）
            RecyclerView inner = (vh == null || vh.itemView.getParent() != rvHome) ? null
                    : (RecyclerView) vh.itemView.findViewById(R.id.rvTopicRow);
            if (inner != null && inner.getChildCount() > 0) {
                int c = Math.min(Math.max(0, hintCol), inner.getChildCount() - 1);
                View target = inner.getChildAt(c);
                if (target != null && target.requestFocus() && isFocusInside(target)) return;
            }
            if (attempt < FOCUS_RETRY) {
                postFocusRow(pos, hintCol, attempt + 1, dir);
                return;
            }
            // 撑到这儿说明这一行确实没拿到焦点（布局没出来 / 卡片不可聚焦）。
            // 不能就这么算了 —— 按键已经被我们吞掉，必须交回系统兜底，
            // 否则遥控器上就是「按了完全没反应」。
            android.util.Log.d("SupeMov", "专题行 " + pos + " 第 " + hintCol
                    + " 列拿焦点失败（重试 " + FOCUS_RETRY + " 次），交回系统搜索 dir=" + dir);
            systemFocusFallback(dir);
        }, FOCUS_RETRY_MS);
    }

    @Override
    public void onBackPressed() {
        ViewState prev = navHistory.pollLast();
        if (prev == null) {
            // 没有历史了：当前在列表页就先退回专题墙，真的到头了才退出程序
            if (listMode) {
                listTopic = null;
                listTitle = "";
                searchKeyword = "";
                buildFilterRow();
                showHome();
                return;
            }
            super.onBackPressed();
            return;
        }
        searchKeyword = prev.kw == null ? "" : prev.kw;
        currentTypeid = prev.typeid;
        currentFid = prev.fid;
        listTopic = prev.topic;
        listTitle = prev.title == null ? "" : prev.title;
        // 顶栏高亮用当时存下的下标原样恢复（不能拿 fid 反推：「首页」是按不动 fid 的）
        markCat(prev.cat);
        // 恢复过滤器高亮（按 typeid 找回下标）
        currentFilterIndex = 0;
        List<MovieStore.Filter> fs = MovieStore.filtersFor(currentFid);
        for (int i = 0; i < fs.size(); i++) {
            if (fs.get(i).typeid == currentTypeid) {
                currentFilterIndex = i;
                break;
            }
        }
        if (prev.list) showListPane();
        else showHome();
        buildFilterRow(); // 搜索模式 / 专题列表内部会隐藏，浏览模式恢复显示
        if (prev.list) reload();
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
    private void openDetail(MovieStore.Movie m) {
        Intent it = new Intent(this, DetailActivity.class);
        it.putExtra("fid", m.fid);
        it.putExtra("tid", m.tid);
        it.putExtra("name", m.name);
        it.putExtra("pic", m.pic);
        startActivity(it);
    }

    /** 长按海报：快捷转存（保留原来的快速通道，不用先进详情页）。 */
    private void confirmTransfer(MovieStore.Movie m) {
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

    private void doQuickTransfer(MovieStore.Movie m) {
        Toast.makeText(this, "转存中…", Toast.LENGTH_SHORT).show();
        final int fid = m.fid;
        final String tid = m.tid;
        final String name = m.name;
        pool.execute(() -> {
            // 解析分享链接
            MovieStore.Detail d = MovieStore.detail(fid, tid);
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
                    rr = BaiduPan.transfer(url, pwd, Settings.saveDir(), name);
                } catch (Throwable e) {
                    rr = new BaiduPan.TransferResult();
                    rr.ok = false;
                    rr.message = "转存异常：" + e.getClass().getSimpleName();
                }
            }
            final BaiduPan.TransferResult r = rr;
            main.post(() -> {
                Settings.recordTransfer(Settings.saveDir(), r.ok);
                if (r.spaceFull) {
                    // 长按快捷键转存也可能撞上空间不足 —— 同样要弹框说清楚，
                    // 不能靠一个三秒就消失的 Toast 交代「你得去删网盘里的文件」
                    showNoSpaceDialog(r.message);
                } else {
                    Toast.makeText(this, (r.ok ? "✔ " : "✘ ") + r.message, Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    /**
     * 网盘空间不足的提示框（首页长按快捷转存的入口）。
     *
     * <p>话术统一取自 {@link BaiduPan#MSG_NO_SPACE}，详情页那份 {@code showNoSpaceDialog}
     * 用的是同一个常量 —— 两个入口的说法必须一模一样，否则用户会以为是两个不同的问题。</p>
     */
    private void showNoSpaceDialog(String msg) {
        String body = (msg == null || msg.isEmpty()) ? BaiduPan.MSG_NO_SPACE : msg;
        AlertDialog dlg = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog)
                .setTitle("网盘空间不足")
                .setMessage(body + "\n\n在线播放与下载都要先把影片转存到你的网盘，"
                        + "空间不足时两者都无法使用。")
                .setPositiveButton("知道了", null)
                .setNegativeButton("去清理空间", (d, w) -> {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW,
                                android.net.Uri.parse("https://pan.baidu.com/main/disk")));
                    } catch (Throwable e) {
                        Toast.makeText(this, "请在电脑或手机上打开百度网盘清理空间",
                                Toast.LENGTH_LONG).show();
                    }
                })
                .create();
        dlg.show();
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).requestFocus();
    }

    /**
     * 载入列表页的某一页（专题墙走 {@link #loadHome()}）。
     *
     * <p>三种数据来源，按优先级：专题列表 &gt; 搜索 &gt; 版块。</p>
     *
     * <p>用 {@link #loadGen} 实现「最新一次点击说了算」：新点击会让先前的请求作废，
     * 但**不会再像以前那样把用户的点击整个丢掉** —— 旧实现开头的 {@code if (loading) return;}
     * 遇上「正在加载下一页时点版块」，新点击直接被丢掉，停在「加载中…」再也不动。</p>
     */
    private void loadPage(int page) {
        final int gen = ++loadGen;
        loading = true;
        final String kw = searchKeyword;
        final int fid = currentFid;
        final int ftypeid = currentTypeid;
        final MovieStore.Topic topic = listTopic;
        if (page == 1) {
            tvEmpty.setVisibility(View.VISIBLE);
            tvEmpty.setText("加载中…");
        }
        pool.execute(() -> {
            MovieStore.lastLoadError = "";
            MovieStore.Paged<List<MovieStore.Movie>> res;
            if (topic != null) {
                res = MovieStore.topicPage(topic, page);
            } else if (kw != null && !kw.isEmpty()) {
                res = MovieStore.search(kw, page);
            } else {
                res = MovieStore.category(fid, page, ftypeid);
            }
            final List<MovieStore.Movie> raw = new ArrayList<>(res.data);
            final int pageCount = res.pageCount;
            android.util.Log.d("SupeMov", "list fid=" + fid + " typeid=" + ftypeid
                    + " page=" + page + " topic=" + (topic == null ? "" : topic.title)
                    + " kw=" + (kw == null ? "" : kw)
                    + " got=" + raw.size() + " pageCount=" + pageCount);

            // 数据库本地查询，一次就能出画，不用再分「先出画、后探测」两段。
            // （旧版要逐个帖子请求论坛确认有没有百度链接，才不得不那样做。）
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
        });
    }

    /** 列表为空时的那句提示（数据库未就位 / 版块为空 / 搜索无结果 / 专题为空）。 */
    private void showEmpty() {
        tvEmpty.setVisibility(View.VISIBLE);
        if (!MovieStore.isReady()) {
            tvEmpty.setText("影片数据库未就位，请重启应用");
        } else if (listTopic != null) {
            tvEmpty.setText("「" + listTitle + "」暂时没有内容");
        } else if (searchKeyword != null && !searchKeyword.isEmpty()) {
            tvEmpty.setText("没有找到「" + searchKeyword + "」");
        } else {
            tvEmpty.setText("这个版块暂时没有内容");
        }
    }

    private boolean kwEquals(String kw) {
        return (searchKeyword == null ? "" : searchKeyword)
                .equals(kw == null ? "" : kw);
    }
}
