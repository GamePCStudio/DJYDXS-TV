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
    /**
     * 宿主列表。只为 {@link #refreshHighlight()} 服务：它要拿到「已经挂上来的条目」
     * 才能就地改高亮，从而不必走数据集通知（走通知会把焦点弄丢，见该方法注释）。
     */
    private RecyclerView host;

    /** 绑定宿主列表（可选）。 */
    public void attach(RecyclerView rv) {
        host = rv;
    }

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
        applyHighlight(h.itemView, o.highlight);
        // 同上：焦点与点击都落在条目根这一个 View 上。
        // 若让内部 TextView 可聚焦，它在 7.1.2 上会抢走焦点并吞掉确认键 → 点了没反应。
        h.itemView.setFocusable(true);
        h.itemView.setClickable(true);
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

    /**
     * 只把「高亮态」重刷一遍，<b>不发任何数据集通知</b>。
     *
     * <p><b>为什么不能用 {@code notifyDataSetChanged()}：</b>那会让 RecyclerView 把所有
     * 条目视图摘下来重新挂载，而视图一旦离开窗口，系统就会把它身上的焦点清掉。
     * 遥控器上的表现就是<b>「按下顶栏某个栏目后，焦点从被按下的那一栏上跑掉了」</b>
     * —— 焦点会被重新分配给窗口里第一个可聚焦项，也就是列表第 0 项。</p>
     *
     * <p>顶栏条目少、永远全部可见（不需要回收），所以就地改已挂载的 View 最稳，也最省。</p>
     */
    public void refreshHighlight() {
        if (host == null) return;
        for (int i = 0; i < host.getChildCount(); i++) {
            View child = host.getChildAt(i);
            int pos = host.getChildAdapterPosition(child); // 未绑定项返回 -1(NO_POSITION)
            if (pos < 0 || pos >= items.size()) continue;
            applyHighlight(child, items.get(pos).highlight);
        }
    }

    /**
     * 高亮落到文字颜色上（选中蓝 / 未选灰）。
     *
     * <p>绑定与就地刷新共用这一份，避免两处各写一遍颜色后走样。</p>
     */
    private static void applyHighlight(View root, boolean highlight) {
        TextView tv = root.findViewById(R.id.tvOption);
        if (tv != null) tv.setTextColor(highlight ? 0xFF1E88E5 : 0xFFC6CBD2);
    }

    static class VH extends RecyclerView.ViewHolder {
        TextView tv;
        VH(@NonNull View v) {
            super(v);
            tv = v.findViewById(R.id.tvOption);
        }
    }
}
