package com.supermov.tv;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/** 影片海报列表。 */
public class MovieAdapter extends RecyclerView.Adapter<MovieAdapter.VH> {

    public interface OnClick {
        void onClick(MovieStore.Movie m);
    }

    public interface OnLongClick {
        void onLongClick(MovieStore.Movie m);
    }

    /**
     * 焦点海报变更回调（LAMPA 效果①：焦点移到哪张海报，全屏背景就交叉淡入那张海报）。
     * 参数 = 当前聚焦条目的海报 URL；失焦（焦点离开整张海报墙）时传 null。
     */
    public interface OnFocusPoster {
        void onFocusPoster(String picUrl);
    }

    private final List<MovieStore.Movie> items = new ArrayList<>();
    private OnClick listener;
    private OnLongClick longListener;
    private OnFocusPoster focusPosterListener;

    public void setOnFocusPoster(OnFocusPoster l) {
        focusPosterListener = l;
    }

    public void setItems(List<MovieStore.Movie> list) {
        items.clear();
        if (list != null) items.addAll(list);
        notifyDataSetChanged();
    }

    public void addItems(List<MovieStore.Movie> list) {
        if (list == null) return;
        int start = items.size();
        items.addAll(list);
        notifyItemRangeInserted(start, list.size());
    }

    /**
     * 把这些 tid 的条目摘掉。
     *
     * <p>后台探测发现某些影片在站内没有百度网盘分享，就把它们摘掉 —— 与其让用户点进去
     * 发现「没有可用的百度网盘链接」，不如一开始就不出现。逐条 notifyItemRemoved
     * （而不是 notifyDataSetChanged）是为了保住电视上当前焦点和滚动位置。</p>
     *
     * <p><b>为什么传「要删的」而不是「要留的」：</b>翻页时 {@code items} 里同时躺着前几页的
     * 条目，而探测只覆盖当前这一页。若按白名单「只留本页 tid」，前一页的海报会被一并清掉
     * （往上翻会发现上面几屏空了）。黑名单与页码无关，重复调用也安全。</p>
     */
    public void dropTids(java.util.Set<String> tids) {
        if (tids == null || tids.isEmpty() || items.isEmpty()) return;
        for (int i = items.size() - 1; i >= 0; i--) {
            String tid = items.get(i).tid;
            if (tid != null && tids.contains(tid)) {
                items.remove(i);
                notifyItemRemoved(i);
            }
        }
    }

    public void setOnClick(OnClick l) {
        listener = l;
    }

    /**
     * 重新绑定当前所有条目。
     *
     * <p>后台探测是**原地改** {@link MovieStore.Movie}（补海报、补「日期 · 片长」角标），
     * {@link #setItems} 只拷了引用、不会收到任何通知 —— 不重绑的话，补好的角标
     * 要等下次进这个版块才看得见。</p>
     *
     * <p>用 {@code notifyItemRangeChanged} 而不是 {@code notifyDataSetChanged}：
     * 前者不移除/新增条目，电视上的焦点和滚动位置都能保住。</p>
     */
    public void refreshAll() {
        if (items.isEmpty()) return;
        notifyItemRangeChanged(0, items.size());
    }

    /** 短按 = 进详情页；长按 = 快捷操作（直接转存），电视遥控器上按住 OK 键。 */
    public void setOnLongClick(OnLongClick l) {
        longListener = l;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_movie, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
        MovieStore.Movie m = items.get(pos);
        h.tvName.setText(m.name);
        // 年月日 角标（图片内底部），无内容时隐藏
        String rem = m.remarks == null ? "" : m.remarks.trim();
        h.tvBadge.setText(rem);
        h.tvBadge.setVisibility(rem.isEmpty() ? View.GONE : View.VISIBLE);
        if (m.pic != null && !m.pic.isEmpty()) {
            h.ivPic.setTag(m.pic);
            ImageLoader.load(m.pic, h.ivPic);
        } else {
            h.ivPic.setImageDrawable(null);
        }
        // 条目根必须**自己**可聚焦 + 可点击：Android 8.0(API 26) 之前没有 FOCUSABLE_AUTO
        // （「可点击即自动可聚焦」这一条），只把监听器挂在容器上，7.1.2 这类机器上
        // 海报条目根本拿不到焦点、按确认也没反应。显式声明，任何版本行为一致。
        h.itemView.setFocusable(true);
        h.itemView.setClickable(true);
        h.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onClick(m);
        });
        h.itemView.setOnLongClickListener(v -> {
            if (longListener == null) return false;
            longListener.onLongClick(m);
            return true;
        });
        // TV 焦点反馈（LAMPA 效果②）：白描边圈 + 标题变色，**不缩放、不位移、不闪整卡**。
        // 描边画在 bg_movie_focus 外圈层（item_movie FrameLayout 四周 6dp margin 留空隙），
        // 视觉外扩 0.5em 不挤兄弟布局。切换即换背景（瞬切），与 LAMPA animation-card-focus
        // 的轻量入场对齐；海报/标题始终实显，不做 alpha 闪烁（闪烁=廉价感）。
        // LAMPA 效果①：焦点拿到/失去时回调全屏背景层（交叉淡入当前海报）。
        h.itemView.setOnFocusChangeListener((v, has) -> {
            h.ivPic.setBackgroundResource(has ? R.drawable.bg_movie_focus : R.drawable.bg_movie_normal);
            h.tvName.setTextColor(has ? 0xFF42A5F5 : 0xFFFFFFFF);
            if (focusPosterListener != null) {
                focusPosterListener.onFocusPoster(has ? m.pic : null);
            }
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        ImageView ivPic;
        TextView tvName;
        TextView tvBadge;

        VH(@NonNull View v) {
            super(v);
            ivPic = v.findViewById(R.id.ivPic);
            tvName = v.findViewById(R.id.tvName);
            tvBadge = v.findViewById(R.id.tvBadge);
        }
    }
}
