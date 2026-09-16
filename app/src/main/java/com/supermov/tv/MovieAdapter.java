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
        void onClick(Site.Movie m);
    }

    private final List<Site.Movie> items = new ArrayList<>();
    private OnClick listener;

    public void setItems(List<Site.Movie> list) {
        items.clear();
        if (list != null) items.addAll(list);
        notifyDataSetChanged();
    }

    public void addItems(List<Site.Movie> list) {
        if (list == null) return;
        int start = items.size();
        items.addAll(list);
        notifyItemRangeInserted(start, list.size());
    }

    public void setOnClick(OnClick l) {
        listener = l;
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
        Site.Movie m = items.get(pos);
        h.tvName.setText(m.name);
        // 日期 · 片长 角标（图片内底部），无内容时隐藏
        String rem = m.remarks == null ? "" : m.remarks.trim();
        h.tvBadge.setText(rem);
        h.tvBadge.setVisibility(rem.isEmpty() ? View.GONE : View.VISIBLE);
        if (m.pic != null && !m.pic.isEmpty()) {
            h.ivPic.setTag(m.pic);
            ImageLoader.load(m.pic, h.ivPic);
        } else {
            h.ivPic.setImageDrawable(null);
        }
        h.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onClick(m);
        });
        // TV 焦点反馈：整体放大 + 海报高亮描边 + 标题变亮，远距离也看得清
        h.itemView.setOnFocusChangeListener((v, has) -> {
            v.setScaleX(has ? 1.08f : 1f);
            v.setScaleY(has ? 1.08f : 1f);
            v.setAlpha(has ? 1f : 0.75f);
            h.ivPic.setBackgroundResource(has ? R.drawable.bg_movie_focus : R.drawable.bg_movie_normal);
            h.tvName.setTextColor(has ? 0xFF42A5F5 : 0xFFFFFFFF);
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
