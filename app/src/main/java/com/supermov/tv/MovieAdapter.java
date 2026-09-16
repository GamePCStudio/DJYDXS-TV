package com.supermov.tv;

import android.os.Build;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
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
        // 强制海报为 2:3 竖版（宽:高 = 2:3），不变形；以列宽推算高度，适配各种密度
        ImageView iv = v.findViewById(R.id.ivPic);
        iv.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                int w = iv.getWidth();
                if (w <= 0) return;
                int h = Math.round(w * 1.5f);
                if (iv.getLayoutParams().height != h) {
                    iv.getLayoutParams().height = h;
                    iv.requestLayout();
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                    iv.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                } else {
                    iv.getViewTreeObserver().removeGlobalOnLayoutListener(this);
                }
            }
        });
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
        Site.Movie m = items.get(pos);
        h.tvName.setText(m.name);
        h.tvRemarks.setText(m.remarks == null ? "" : m.remarks);
        if (m.pic != null && !m.pic.isEmpty()) {
            h.ivPic.setTag(m.pic);
            ImageLoader.load(m.pic, h.ivPic);
        } else {
            h.ivPic.setImageDrawable(null);
        }
        h.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onClick(m);
        });
        h.itemView.setOnFocusChangeListener((v, has) -> {
            v.setAlpha(has ? 1f : 0.85f);
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        ImageView ivPic;
        TextView tvName;
        TextView tvRemarks;

        VH(@NonNull View v) {
            super(v);
            ivPic = v.findViewById(R.id.ivPic);
            tvName = v.findViewById(R.id.tvName);
            tvRemarks = v.findViewById(R.id.tvRemarks);
        }
    }
}
