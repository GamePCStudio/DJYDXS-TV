package com.supermov.tv;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/** 下载队列列表适配器（TV 遥控器：每项三个可聚焦按钮）。 */
public class DownloadAdapter extends RecyclerView.Adapter<DownloadAdapter.VH> {

    public interface Action {
        void onToggle(Dl t);

        void onPlay(Dl t);

        void onRemove(Dl t);
    }

    private final List<Dl> items = new ArrayList<>();
    private final Action action;

    public DownloadAdapter(Action action) {
        this.action = action;
    }

    public void setItems(List<Dl> list) {
        items.clear();
        if (list != null) items.addAll(list);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_download, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        Dl t = items.get(position);

        h.title.setText(t.fileName == null || t.fileName.isEmpty() ? t.name : t.fileName);
        String state = (t.status == Dl.QUEUED && t.queuePos > 0)
                ? "排队中 · 第 " + t.queuePos + " 位"     // 串行队列：排在谁后面一目了然
                : t.statusText();
        h.state.setText(state
                + (t.total > 0 ? " · " + DlEngine.human(t.done) + "/" + DlEngine.human(t.total) : ""));
        h.pb.setProgress(t.percent());
        h.path.setText(t.dir == null || t.dir.isEmpty() ? "" : t.dir);

        // 按钮文案随状态走
        if (t.status == Dl.RUNNING) {
            h.toggle.setText("⏸ 暂停");
            h.toggle.setVisibility(View.VISIBLE);
        } else if (t.status == Dl.QUEUED) {
            h.toggle.setText("⏸ 暂停");
            h.toggle.setVisibility(View.VISIBLE);
        } else if (t.status == Dl.PAUSED) {
            h.toggle.setText("▶ 继续");
            h.toggle.setVisibility(View.VISIBLE);
        } else if (t.status == Dl.DONE) {
            h.toggle.setVisibility(View.GONE);
        } else {
            h.toggle.setText("↻ 重试");
            h.toggle.setVisibility(View.VISIBLE);
        }

        // 播放按钮常显：没下完的按下去会给一句明确提示，
        // 比「按钮时有时无」更让人明白发生了什么
        h.play.setVisibility(View.VISIBLE);
        h.remove.setVisibility(View.VISIBLE);

        h.toggle.setOnClickListener(v -> action.onToggle(t));
        h.play.setOnClickListener(v -> action.onPlay(t));
        h.remove.setOnClickListener(v -> action.onRemove(t));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final TextView title;
        final TextView state;
        final TextView path;
        final ProgressBar pb;
        final TextView toggle;
        final TextView play;
        final TextView remove;

        VH(View v) {
            super(v);
            title = v.findViewById(R.id.tvDlTitle);
            state = v.findViewById(R.id.tvDlState);
            path = v.findViewById(R.id.tvDlPath);
            pb = v.findViewById(R.id.pbDl);
            toggle = v.findViewById(R.id.btnDlToggle);
            play = v.findViewById(R.id.btnDlPlay);
            remove = v.findViewById(R.id.btnDlRemove);
        }
    }
}
