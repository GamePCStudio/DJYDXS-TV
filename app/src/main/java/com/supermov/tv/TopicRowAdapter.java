package com.supermov.tv;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/**
 * 一条专题行内部的<b>横向</b>海报列表。
 *
 * <p>条目 = N 张海报 + 末尾一个「更多」卡（对应 SAM-CINEMA 的 {@code ItemVodMore}，
 * 原版是用一条 {@code vod_id = -1} 的假数据实现的）。</p>
 *
 * <h3>为什么焦点回调挂在这里而不是外层</h3>
 * 驱动 Hero 的规则是「焦点落到哪张海报，Hero 就换成那部片子」。焦点事件必须从
 * <b>真正持有焦点的那个 View</b> 上报 —— 挂在行容器上收不到（条目的根才是 focusable）。
 * 所以每个条目的 {@code onFocusChange} 直接回调 {@link OnFocusMovie}，
 * 「更多」卡刻意<b>不</b>上报：它不对应任何影片，不该把 Hero 清空或换掉。</p>
 */
public class TopicRowAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int T_MOVIE = 0;
    private static final int T_MORE = 1;

    /** 点海报 → 进详情页。 */
    public interface OnOpen {
        void onOpen(MovieStore.Movie m);
    }

    /** 长按海报 → 快捷转存（电视遥控器上按住 OK）。 */
    public interface OnLongPress {
        void onLongPress(MovieStore.Movie m);
    }

    /** 点「更多」→ 进该行的完整列表。 */
    public interface OnMore {
        void onMore();
    }

    /** 焦点落到某张海报（离开整行时会带着上一张的引用再来一次，调用方自己判重）。 */
    public interface OnFocusMovie {
        void onFocusMovie(MovieStore.Movie m);
    }

    private final List<MovieStore.Movie> items = new ArrayList<>();
    private boolean withMore;
    private OnOpen openListener;
    private OnLongPress longListener;
    private OnMore moreListener;
    private OnFocusMovie focusListener;

    public void setItems(List<MovieStore.Movie> list, boolean withMoreCard) {
        items.clear();
        if (list != null) items.addAll(list);
        withMore = withMoreCard && !items.isEmpty();
        notifyDataSetChanged();
    }

    public void setOnOpen(OnOpen l) {
        openListener = l;
    }

    public void setOnLongPress(OnLongPress l) {
        longListener = l;
    }

    public void setOnMore(OnMore l) {
        moreListener = l;
    }

    public void setOnFocusMovie(OnFocusMovie l) {
        focusListener = l;
    }

    @Override
    public int getItemViewType(int position) {
        return (withMore && position == items.size()) ? T_MORE : T_MOVIE;
    }

    @Override
    public int getItemCount() {
        return items.size() + (withMore ? 1 : 0);
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inf = LayoutInflater.from(parent.getContext());
        if (viewType == T_MORE) {
            return new MoreVH(inf.inflate(R.layout.item_topic_more, parent, false));
        }
        return new CardVH(inf.inflate(R.layout.item_topic_card, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int pos) {
        if (holder instanceof MoreVH) {
            holder.itemView.setOnClickListener(v -> {
                if (moreListener != null) moreListener.onMore();
            });
            holder.itemView.setFocusable(true);
            holder.itemView.setClickable(true);
            return;
        }
        CardVH h = (CardVH) holder;
        MovieStore.Movie m = items.get(pos);
        h.tvName.setText(m.name);
        String rem = m.remarks == null ? "" : m.remarks.trim();
        h.tvBadge.setText(rem);
        h.tvBadge.setVisibility(rem.isEmpty() ? View.GONE : View.VISIBLE);
        if (m.pic != null && !m.pic.isEmpty()) {
            h.ivPic.setTag(m.pic);
            ImageLoader.load(m.pic, h.ivPic);
        } else {
            h.ivPic.setImageDrawable(null);
        }
        // 条目根必须自己可聚焦 + 可点击（API 26 之前没有 FOCUSABLE_AUTO，见 UI 研究报告 §3.3）
        h.itemView.setFocusable(true);
        h.itemView.setClickable(true);
        h.itemView.setOnClickListener(v -> {
            if (openListener != null) openListener.onOpen(m);
        });
        h.itemView.setOnLongClickListener(v -> {
            if (longListener == null) return false;
            longListener.onLongPress(m);
            return true;
        });
        h.itemView.setOnFocusChangeListener((v, has) -> {
            v.setScaleX(has ? 1.08f : 1f);
            v.setScaleY(has ? 1.08f : 1f);
            v.setAlpha(has ? 1f : 0.78f);
            h.ivPic.setBackgroundResource(has ? R.drawable.bg_movie_focus : R.drawable.bg_movie_normal);
            h.tvName.setTextColor(has ? 0xFF42A5F5 : 0xFFFFFFFF);
            // 只上报「拿到焦点」：上报「失去焦点」会把 Hero 清成空白，
            // 而横向行之间跳转时必然先失去再拿到，那一瞬间的空白会闪出来。
            if (has && focusListener != null) focusListener.onFocusMovie(m);
        });
    }

    static class CardVH extends RecyclerView.ViewHolder {
        android.widget.ImageView ivPic;
        TextView tvName;
        TextView tvBadge;

        CardVH(@NonNull View v) {
            super(v);
            ivPic = v.findViewById(R.id.ivPic);
            tvName = v.findViewById(R.id.tvName);
            tvBadge = v.findViewById(R.id.tvBadge);
        }
    }

    static class MoreVH extends RecyclerView.ViewHolder {
        MoreVH(@NonNull View v) {
            super(v);
        }
    }
}
