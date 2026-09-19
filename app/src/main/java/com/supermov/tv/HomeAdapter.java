package com.supermov.tv;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 首页的竖向列表：<b>第 0 项是 Hero 文字面板，其后每项是一条专题行</b>。
 *
 * <p>把 Hero 做成列表的第 0 项（而不是固定钉在页面上方）有两个好处：
 * 往下滚时 Hero 自然滚出屏幕，给专题墙让出整屏高度；而上键协议要回到大标题时，
 * 只要 {@code scrollToPosition(0)} 就能把它拉回来 —— 不需要额外维护「被滚走的状态」。</p>
 *
 * <h3>两条焦点约定</h3>
 * <ul>
 *   <li>Hero 面板根<b>不能</b>写 {@code blocksDescendants}：面板是「容器」，
 *       大标题/播放/详情都是它的子孙，祖先一旦 blocksDescendants，子孙的
 *       {@code requestFocus()} 会被 AOSP 的 {@code hasAncestorThatBlocksDescendantFocus()}
 *       直接判否，谁都拿不到焦点（踩过一次，症状是「首页出来了但往下按没反应」）。
 *       所以面板用 {@code afterDescendants}，靠「信息行/简介不给 focusable」来挡，
 *       而不是靠 blocksDescendants。</li>
 *   <li>每一条专题行的横向 RecyclerView 各持有一个 {@link TopicRowAdapter} 实例，
 *       在 {@code onCreateViewHolder} 里建一次并绑定，不在每次 bind 时重建 ——
 *       重建会让内层列表丢掉滚动位置与焦点。</li>
 * </ul>
 */
public class HomeAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    /** 列表第 0 项永远是 Hero 面板。 */
    public static final int HERO_INDEX = 0;
    private static final int T_HERO = 0;
    private static final int T_ROW = 1;

    public interface OnOpenMovie {
        void onOpenMovie(MovieStore.Movie m);
    }

    public interface OnLongPressMovie {
        void onLongPressMovie(MovieStore.Movie m);
    }

    /** 点某条专题行右侧的「更多 ›」。 */
    public interface OnTopicMore {
        void onTopicMore(MovieStore.Topic t);
    }

    /** 焦点落到某张海报（不区分在第几行）。 */
    public interface OnFocusMovie {
        void onFocusMovie(MovieStore.Movie m);
    }

    private final List<MovieStore.Topic> topics = new ArrayList<>();
    private MovieStore.Movie hero;

    private OnOpenMovie openListener;
    private OnLongPressMovie longListener;
    private OnTopicMore moreListener;
    private OnFocusMovie focusListener;

    /** 已绑定的 Hero 面板根 View（{@code null} = Hero 还没被创建/已回收）。 */
    private View heroPanelView;

    public void setCallbacks(OnOpenMovie open, OnLongPressMovie longPress,
                             OnTopicMore more, OnFocusMovie focus) {
        openListener = open;
        longListener = longPress;
        moreListener = more;
        focusListener = focus;
    }

    public void setTopics(List<MovieStore.Topic> list) {
        topics.clear();
        if (list != null) topics.addAll(list);
        notifyDataSetChanged();
    }

    /** 换 Hero 显示的那部片子（只重绑 Hero 那一个条目，不整表刷新，避免滚动位置丢失）。 */
    public void showHero(MovieStore.Movie m) {
        hero = m;
        if (heroPanelView != null) bindHero(heroPanelView);
    }

    public MovieStore.Movie heroMovie() {
        return hero;
    }

    /** Hero 面板的根 View，供上下键协议判位。没绑上时为 null。 */
    public View heroPanelView() {
        return heroPanelView;
    }

    /** 第 pos 项是不是一条专题行（第 0 项是 Hero）。 */
    public boolean isTopicRow(int pos) {
        return pos - HERO_INDEX >= 1 && pos - HERO_INDEX <= topics.size();
    }

    /** 第 pos 项对应的专题行下标（0 起）。不是专题行则返回 -1。 */
    public int topicIndexOf(int pos) {
        int i = pos - HERO_INDEX - 1;
        return (i >= 0 && i < topics.size()) ? i : -1;
    }

    public MovieStore.Topic topicAt(int adapterPos) {
        int i = topicIndexOf(adapterPos);
        return i < 0 ? null : topics.get(i);
    }

    @Override
    public int getItemViewType(int position) {
        return position == HERO_INDEX ? T_HERO : T_ROW;
    }

    @Override
    public int getItemCount() {
        return topics.isEmpty() ? 0 : topics.size() + 1;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inf = LayoutInflater.from(parent.getContext());
        if (viewType == T_HERO) {
            View v = inf.inflate(R.layout.item_hero_panel, parent, false);
            return new HeroVH(v);
        }
        View v = inf.inflate(R.layout.item_topic_row, parent, false);
        RowVH h = new RowVH(v);
        // 横向行：LayoutManager 必须在代码里设（XML 的 android:orientation 对 RecyclerView 无效）
        h.rv.setLayoutManager(new LinearLayoutManager(parent.getContext(),
                LinearLayoutManager.HORIZONTAL, false));
        h.adapter.setOnOpen(m -> {
            if (openListener != null) openListener.onOpenMovie(m);
        });
        h.adapter.setOnLongPress(m -> {
            if (longListener != null) longListener.onLongPressMovie(m);
        });
        h.adapter.setOnFocusMovie(m -> {
            if (focusListener != null) focusListener.onFocusMovie(m);
        });
        h.rv.setAdapter(h.adapter);
        return h;
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int pos) {
        if (holder instanceof HeroVH) {
            heroPanelView = holder.itemView;
            bindHero(holder.itemView);
            return;
        }
        RowVH h = (RowVH) holder;
        final MovieStore.Topic t = topics.get(pos - HERO_INDEX - 1);
        h.tvTitle.setText(t.title);
        // 行内塞满 TOPIC_ROWSIZE 条才说明「这一行被截断了」，否则整行已经拿全，不该出现「更多」
        boolean truncated = t.movies.size() >= MovieStore.TOPIC_ROWSIZE;
        h.tvMore.setVisibility(truncated ? View.VISIBLE : View.GONE);
        h.tvMore.setOnClickListener(v -> {
            if (moreListener != null) moreListener.onTopicMore(t);
        });
        // 行尾那张「＋ 更多」大卡也要能点（横向滚到最右时它是唯一入口）。
        // 回调必须在这里重绑：ViewHolder 是复用的，onCreateViewHolder 里拿不到
        // 当前这一行对应的 t，绑过一次就会把「更多」永远指向第一次绑定的那条专题。
        h.adapter.setOnMore(() -> {
            if (moreListener != null) moreListener.onTopicMore(t);
        });
        h.adapter.setItems(t.movies, truncated);
    }

    @Override
    public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
        super.onViewRecycled(holder);
        if (holder instanceof HeroVH && holder.itemView == heroPanelView) {
            heroPanelView = null;
        }
    }

    // ==================================================================
    // Hero 面板渲染
    // ==================================================================

    private void bindHero(View v) {
        TextView title = v.findViewById(R.id.heroTitleBig);
        TextView meta = v.findViewById(R.id.heroMeta);
        TextView rating = v.findViewById(R.id.heroRating);
        TextView intro = v.findViewById(R.id.heroIntro);
        TextView btnPlay = v.findViewById(R.id.heroBtnPlay);
        TextView btnDetail = v.findViewById(R.id.heroBtnDetail);

        final MovieStore.Movie m = hero;
        if (m == null) {
            title.setText("");
            meta.setText("");
            rating.setVisibility(View.GONE);
            intro.setVisibility(View.GONE);
            return;
        }

        title.setText(m.name);
        // 大标题是可聚焦的（上键协议的落点），焦点态把字点亮
        title.setOnFocusChangeListener((x, has) ->
                ((TextView) x).setTextColor(has ? 0xFF6FC3FF : 0xFFFFFFFF));
        // 焦点可能已经在标题上（焦点掠过海报时 Hero 会重绑）：这时不会再触发
        // onFocusChange，得照当前状态补一次颜色，否则标题会「明明有焦点却是白的」
        title.setTextColor(title.isFocused() ? 0xFF6FC3FF : 0xFFFFFFFF);
        title.setOnClickListener(x -> {
            if (openListener != null) openListener.onOpenMovie(m);
        });

        // 信息行：复刻 SAM-CINEMA 的 get_class_row() = 地区 / 年份 / 类型 / 片长
        List<String> parts = new ArrayList<>();
        if (!m.region.isEmpty()) parts.add(m.region);
        if (!m.year.isEmpty()) parts.add(m.year);
        List<String> tags = MovieStore.parseTags(m.genres);
        if (!tags.isEmpty()) {
            StringBuilder g = new StringBuilder();
            for (int i = 0; i < tags.size() && i < 3; i++) {
                if (i > 0) g.append('·');
                g.append(tags.get(i));
            }
            parts.add(g.toString());
        }
        if (m.runtimeMin > 0) parts.add(m.runtimeMin + "分钟");
        meta.setText(join(parts, " / "));

        // 评分徽章：豆瓣优先，没有就退 IMDb，都没有整块隐藏（实测豆瓣覆盖 65%）
        if (m.ratingDouban > 0) {
            rating.setText("豆瓣 " + num(m.ratingDouban));
            rating.setVisibility(View.VISIBLE);
        } else if (m.ratingImdb > 0) {
            rating.setText("IMDb " + num(m.ratingImdb));
            rating.setVisibility(View.VISIBLE);
        } else {
            rating.setVisibility(View.GONE);
        }

        // 简介：实测只有 30% 的片子有。**有则显示、无则收起**（GONE 让 Hero 高度自适应），
        // 否则七成的片子下面会留一大块空洞。
        String introText = m.intro == null ? "" : m.intro.trim();
        intro.setText(introText);
        intro.setVisibility(introText.isEmpty() ? View.GONE : View.VISIBLE);

        // 播放 / 详情都进详情页 —— 在线播放入口在详情页里（首页不直接起播）
        btnPlay.setOnClickListener(x -> {
            if (openListener != null) openListener.onOpenMovie(m);
        });
        btnDetail.setOnClickListener(x -> {
            if (openListener != null) openListener.onOpenMovie(m);
        });
    }

    private static String join(List<String> parts, String sep) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p == null || p.isEmpty()) continue;
            if (sb.length() > 0) sb.append(sep);
            sb.append(p);
        }
        return sb.toString();
    }

    /** 4.0 显示成 "4"，8.5 显示成 "8.5"。 */
    private static String num(double d) {
        if (d == Math.floor(d)) return String.valueOf((long) d);
        return String.format(Locale.ROOT, "%.1f", d);
    }

    static class HeroVH extends RecyclerView.ViewHolder {
        HeroVH(@NonNull View v) {
            super(v);
        }
    }

    static class RowVH extends RecyclerView.ViewHolder {
        final TextView tvTitle;
        final TextView tvMore;
        final RecyclerView rv;
        final TopicRowAdapter adapter = new TopicRowAdapter();

        RowVH(@NonNull View v) {
            super(v);
            tvTitle = v.findViewById(R.id.topicTitle);
            tvMore = v.findViewById(R.id.topicMore);
            rv = v.findViewById(R.id.rvTopicRow);
        }
    }
}
