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

/** 通用选项列表（设置页 / 线路选择）。 */
public class OptionAdapter extends RecyclerView.Adapter<OptionAdapter.VH> {

    public interface OnClick {
        void onClick(Option o, int position);
    }

    public static class Option {
        public String title;
        public String sub = "";
        public boolean highlight = false;
        public Option(String title) { this.title = title; }
        public Option(String title, String sub) { this.title = title; this.sub = sub; }
        public Option hi() { this.highlight = true; return this; }
    }

    private final List<Option> items = new ArrayList<>();
    private OnClick listener;

    public void setItems(List<Option> list) {
        items.clear();
        if (list != null) items.addAll(list);
        notifyDataSetChanged();
    }

    public void setOnClick(OnClick l) {
        listener = l;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_option, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
        Option o = items.get(pos);
        String text = o.title + (o.sub == null || o.sub.isEmpty() ? "" : "\n" + o.sub);
        h.tv.setText(text);
        h.tv.setTextColor(o.highlight ? 0xFF1E88E5 : 0xFFC6CBD2);
        h.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onClick(o, h.getAdapterPosition());
        });
        // 焦点高亮由 bg_cat selector（蓝底白描边）处理，这里做放大反馈
        h.itemView.setOnFocusChangeListener((v, has) -> {
            v.setScaleX(has ? 1.14f : 1f);
            v.setScaleY(has ? 1.14f : 1f);
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        TextView tv;
        VH(@NonNull View v) {
            super(v);
            tv = v.findViewById(R.id.tvOption);
        }
    }
}
