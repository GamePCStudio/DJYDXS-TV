package com.supermov.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/** 设置页：百度网盘扫码 / 转存目录 / 下载目录 / 后台下载限速 / 授权状态 / 解除授权。 */
public class SettingsActivity extends Activity {

    private LinearLayout group;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        TextView section = findViewById(R.id.tvSection);
        section.setText("⚙ 设置");
        group = findViewById(R.id.lineGroup);
        rebuild();
    }

    @Override
    protected void onResume() {
        super.onResume();
        rebuild(); // 从扫码页/诊断返回后立即刷新授权状态显示
    }

    private void rebuild() {
        group.removeAllViews();
        boolean authed = CookieStore.hasBaiduLogin();
        String user = Settings.baiduUser();

        // ① 百度网盘扫码
        String qrTitle = "百度网盘扫码 " + (authed ? "· 已授权" + (user.isEmpty() ? "" : "(" + user + ")") : "· 未授权");
        group.addView(option(qrTitle, "用百度网盘APP扫码授权，用于一键转存", v ->
                startActivity(new Intent(this, QrActivity.class))));

        // ② 转存目录（可编辑）
        String current = Settings.saveDir();
        group.addView(sectionLabel("转存目录"));
        group.addView(option("转存目录: " + current,
                "点按修改（目录不存在会自动创建）", v -> showEditDirDialog()));

        // ②.5 下载（落盘目录 + 队列入口）
        group.addView(sectionLabel("下载"));
        group.addView(option("下载目录: " + Settings.downloadDir(),
                Storage.hasAllFiles(this)
                        ? "点按修改；会列出本机存储、已挂载的 U盘 / 移动硬盘 / NAS"
                        : "点按修改；写入公共存储需先授予「所有文件访问」",
                v -> showDownloadDirPicker()));
        group.addView(option("后台下载限速: " + Settings.dlLimitMbps() + " Mbps",
                "缺省 " + Settings.DL_LIMIT_DEFAULT + " Mbps，可设 "
                        + Settings.DL_LIMIT_MIN + " ~ " + Settings.DL_LIMIT_MAX + " Mbps\n"
                        + "下载管理页面内下载不限速；退出该页面后按此限速",
                v -> showDlLimitDialog()));

        // ②.7 播放 · 音频（v1.25：NEXIO 内核带来的直通/解码开关）
        group.addView(sectionLabel("播放 · 音频"));
        group.addView(option("音频输出: " + audioModeName(Settings.audioMode()),
                audioModeHint(),
                v -> showAudioModeDialog()));
        group.addView(option("源码直通编码: " + passthroughSummary(),
                "只在「源码直通」模式下生效。勾选功放能点亮的格式\n"
                        + "AC-3 / E-AC-3 命中率最高；DTS-HD / TrueHD / AC-4 多数国产盒子没有"
                        + "授权解码器，勾了可能「有画面没声音」",
                v -> showPassthroughCodecDialog()));
        group.addView(option("最大声道数: " + Settings.audioMaxChannels() + " ch",
                "2 = 立体声 / 6 = 5.1 / 8 = 7.1\n按功放实际声道数选，选大了可能被降混或干脆没声",
                v -> showMaxChannelDialog()));
        group.addView(option("直通失败自动回退: " + onOff(Settings.passthroughFallback()),
                "开：直通建音频轨失败时自动改用解码重播，不会一播就崩\n"
                        + "关：失败就停在错误上（排查设备到底支不支持时用）",
                v -> {
                    Settings.setPassthroughFallback(!Settings.passthroughFallback());
                    rebuild();
                }));
        group.addView(option("首选音轨语言: " + langName(Settings.audioPreferredLang()),
                "多音轨时优先挑这个语种；「不指定」= 跟随片源默认轨",
                v -> showAudioLangDialog()));

        // ②.8 播放 · 视频
        group.addView(sectionLabel("播放 · 视频"));
        group.addView(option("画面比例: " + resizeName(Settings.videoResize()),
                "适应屏幕 = 保留原始比例、可能有黑边（推荐）\n"
                        + "拉伸 / 裁剪 = 填满屏幕，前者会变形，后者会切掉边缘",
                v -> showResizeDialog()));
        group.addView(option("在线清晰度上限: " + qualityName(Settings.qualityCap()),
                "原画优先 = 先取原画直链，失败才降级转码流（推荐）\n"
                        + "选 1080p / 720p 会直接走云端转码流，省带宽但清晰度打折",
                v -> showQualityDialog()));
        group.addView(option("硬解失败回退软解: " + onOff(Settings.decoderFallback()),
                "开：盒子硬解不了该编码时自动换别的解码器再试\n"
                        + "关：直接报解码失败（排查不支持编码时用）",
                v -> {
                    Settings.setDecoderFallback(!Settings.decoderFallback());
                    rebuild();
                }));
        group.addView(option("字幕字号: " + subSizeName(Settings.subSize()),
                "以屏幕高度为基准；电视上离得远，建议「大」",
                v -> showSubSizeDialog()));
        group.addView(option("记住播放进度: " + onOff(Settings.rememberPos()),
                "关掉后不再自动续播，每次从头开始（已记录的进度不会被删）",
                v -> {
                    Settings.setRememberPos(!Settings.rememberPos());
                    rebuild();
                }));

        // ③ 状态（真实会话校验异步更新）
        group.addView(sectionLabel("状态"));
        final TextView statCard = option("授权状态：" + (authed ? "检测中…" : "未授权"),
                "百度账号 Cookie 保存在本机", null);
        group.addView(statCard);
        if (authed) {
            new Thread(() -> {
                final boolean ok = BaiduPan.sessionValid();
                runOnUiThread(() -> {
                    statCard.setText("授权状态：" + (ok
                            ? "已授权（会话有效）"
                            : "已扫码但会话已失效 → 请重新扫码")
                            + "\n百度账号 Cookie 保存在本机");
                });
            }).start();
        }
        group.addView(option("最近转存：" + (Settings.lastTransfer().isEmpty() ? "无" : "有记录"),
                "详情页转存后更新", null));

        // ③.5 连接诊断
        group.addView(option("连接诊断", "测试论坛/百度接口连通性", v -> runDiag()));

        // ⑤ 解除授权
        if (authed) {
            group.addView(option("解除百度网盘授权", "清除本机保存的百度 Cookie", v -> {
                CookieStore.clearBaidu();
                Settings.setBaiduUser("");
                Toast.makeText(this, "已解除授权", Toast.LENGTH_SHORT).show();
                rebuild();
            }));
        }
    }

    private void showEditDirDialog() {
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(
                this, android.R.style.Theme_DeviceDefault_Dialog);
        builder.setTitle("转存目录");
        final android.widget.EditText input = new android.widget.EditText(this);
        input.setText(Settings.saveDir());
        input.setSelection(input.getText().length());
        input.setTextSize(16);
        builder.setView(input);
        builder.setPositiveButton("保存", (d, w) -> {
            String v = input.getText().toString().trim();
            if (!v.startsWith("/")) v = "/" + v;
            v = v.replaceAll("/+$", "");
            if (v.isEmpty()) v = "/超级影库";
            Settings.setSaveDir(v);
            Toast.makeText(this, "转存目录已设为 " + v, Toast.LENGTH_SHORT).show();
            rebuild();
        });
        builder.setNegativeButton("取消", null);
        builder.show();
        input.requestFocus();
    }

    /** 下载落盘目录：列出所有可写位置（含 U 盘 / 已挂载 NAS），也可手动输入。 */
    private void showDownloadDirPicker() {
        final List<Storage.Target> ts = Storage.targets(this);
        final String[] labels = new String[ts.size() + 1];
        for (int i = 0; i < ts.size(); i++) {
            labels[i] = ts.get(i).label + "\n" + ts.get(i).dir;
        }
        labels[ts.size()] = "✎ 手动输入路径…";

        android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(
                this, android.R.style.Theme_DeviceDefault_Dialog)
                .setTitle("下载目录")
                .setItems(labels, (d, which) -> {
                    if (which == ts.size()) {
                        showManualDownloadDir();
                        return;
                    }
                    Storage.Target t = ts.get(which);
                    if (t.needAllFiles && !Storage.hasAllFiles(this)) {
                        Storage.requestAllFiles(this);
                        Toast.makeText(this, "授予「所有文件访问」后重新选择即可", Toast.LENGTH_LONG).show();
                        return;
                    }
                    Settings.setDownloadDir(t.dir);
                    Toast.makeText(this, "下载目录已设为\n" + t.dir, Toast.LENGTH_LONG).show();
                    rebuild();
                })
                .create();
        dlg.show();
    }

    private void showManualDownloadDir() {
        final android.widget.EditText input = new android.widget.EditText(this);
        input.setText(Settings.downloadDir());
        input.setSelection(input.getText().length());
        input.setTextSize(15);
        android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(
                this, android.R.style.Theme_DeviceDefault_Dialog)
                .setTitle("下载目录（绝对路径）")
                .setView(input)
                .setPositiveButton("保存", (d, w) -> {
                    String v = input.getText().toString().trim();
                    if (v.isEmpty()) {
                        Toast.makeText(this, "路径不能为空", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (Storage.needsPermission(this, v) && !Storage.hasAllFiles(this)) {
                        Storage.requestAllFiles(this);
                        Toast.makeText(this, "该位置需要「所有文件访问」权限，授权后重试",
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    Settings.setDownloadDir(v);
                    Toast.makeText(this, "下载目录已设为 " + v, Toast.LENGTH_SHORT).show();
                    rebuild();
                })
                .setNegativeButton("取消", null)
                .create();
        dlg.show();
        dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE).requestFocus();
    }

    /**
     * 后台下载限速：给几个常用档位让遥控器直接选，也能手动输入。
     *
     * <p>档位而不是滑动条：电视上遥控器左右划不准，列表点选更省事。</p>
     */
    private void showDlLimitDialog() {
        final int[] presets = {5, 10, 15, 20, 25, 30, 40};
        final int cur = Settings.dlLimitMbps();
        final String[] labels = new String[presets.length + 1];
        for (int i = 0; i < presets.length; i++) {
            labels[i] = (presets[i] == cur ? "● " : "○ ") + presets[i] + " Mbps"
                    + (presets[i] == Settings.DL_LIMIT_DEFAULT ? "（缺省）" : "");
        }
        labels[presets.length] = "✎ 手动输入…";

        android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(
                this, android.R.style.Theme_DeviceDefault_Dialog)
                .setTitle("后台下载限速")
                .setItems(labels, (d, which) -> {
                    if (which == presets.length) {
                        showManualDlLimit();
                        return;
                    }
                    Settings.setDlLimitMbps(presets[which]);
                    Toast.makeText(this, "后台下载限速已设为 " + Settings.dlLimitMbps() + " Mbps",
                            Toast.LENGTH_SHORT).show();
                    rebuild();
                })
                .setNegativeButton("取消", null)
                .create();
        dlg.show();
    }

    private void showManualDlLimit() {
        final android.widget.EditText input = new android.widget.EditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        input.setText(String.valueOf(Settings.dlLimitMbps()));
        input.setSelection(input.getText().length());
        input.setTextSize(16);
        android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(
                this, android.R.style.Theme_DeviceDefault_Dialog)
                .setTitle("后台下载限速（Mbps）")
                .setMessage("可填 " + Settings.DL_LIMIT_MIN + " ~ " + Settings.DL_LIMIT_MAX
                        + "，缺省 " + Settings.DL_LIMIT_DEFAULT
                        + "\n下载管理页面内下载不限速")
                .setView(input)
                .setPositiveButton("保存", (d, w) -> {
                    int v;
                    try {
                        v = Integer.parseInt(input.getText().toString().trim());
                    } catch (Throwable e) {
                        Toast.makeText(this, "请输入数字", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Settings.setDlLimitMbps(v);
                    Toast.makeText(this, "后台下载限速已设为 " + Settings.dlLimitMbps() + " Mbps",
                            Toast.LENGTH_SHORT).show();
                    rebuild();
                })
                .setNegativeButton("取消", null)
                .create();
        dlg.show();
        dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE).requestFocus();
    }

    // ---------- 播放 · 音频 ----------

    private String audioModeName(int mode) {
        if (mode == Settings.AUDIO_MODE_PASSTHROUGH) return "源码直通（送功放）";
        if (mode == Settings.AUDIO_MODE_PCM) return "强制解码（PCM）";
        return "自动（跟随设备）";
    }

    private String audioModeHint() {
        int mode = Settings.audioMode();
        if (mode == Settings.AUDIO_MODE_PASSTHROUGH) {
            return "位流原样送 HDMI，由功放解码 —— 功放面板显示 DD / DD+ / DTS 才算成功\n"
                    + "本机强制声明支持下列编码，因此即使盒子不上报也能试";
        }
        if (mode == Settings.AUDIO_MODE_PCM) {
            return "全部解成 PCM 再输出 —— 兼容性最好，但 5.1 可能被降混成立体声";
        }
        return "不干预，按设备上报的能力决定直通还是解码（缺省，最稳）";
    }

    private String passthroughSummary() {
        if (Settings.audioMode() != Settings.AUDIO_MODE_PASSTHROUGH) {
            return "已勾 " + Settings.passthroughCodecCount() + " 项 · 当前模式不生效";
        }
        int n = Settings.passthroughCodecCount();
        if (n == 0) return "一个都没勾 —— 等于全部解码";
        return "已勾 " + n + " 项";
    }

    private void showAudioModeDialog() {
        final int[] modes = {
                Settings.AUDIO_MODE_AUTO,
                Settings.AUDIO_MODE_PASSTHROUGH,
                Settings.AUDIO_MODE_PCM};
        final String[] labels = new String[modes.length];
        for (int i = 0; i < modes.length; i++) {
            labels[i] = (modes[i] == Settings.audioMode() ? "● " : "○ ") + audioModeName(modes[i]);
        }
        android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(
                this, android.R.style.Theme_DeviceDefault_Dialog)
                .setTitle("音频输出")
                .setItems(labels, (d, which) -> {
                    Settings.setAudioMode(modes[which]);
                    Toast.makeText(this, "音频输出已改为 " + audioModeName(modes[which]),
                            Toast.LENGTH_SHORT).show();
                    rebuild();
                })
                .setNegativeButton("取消", null)
                .create();
        dlg.show();
    }

    /**
     * 直通编码多选。
     *
     * <p>勾一下立刻落盘（不需要按「完成」才生效），这样即使用户直接按返回键退出，
     * 改动也已经保存了。</p>
     */
    private void showPassthroughCodecDialog() {
        final String[] all = Settings.PT_ALL;
        final String[] labels = new String[all.length];
        final boolean[] checked = new boolean[all.length];
        for (int i = 0; i < all.length; i++) {
            labels[i] = Settings.ptLabel(all[i]);
            checked[i] = Settings.passthroughCodec(all[i]);
        }
        android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(
                this, android.R.style.Theme_DeviceDefault_Dialog)
                .setTitle("源码直通编码")
                .setMultiChoiceItems(labels, checked, (d, which, isChecked) ->
                        Settings.setPassthroughCodec(all[which], isChecked))
                .setPositiveButton("完成", (d, w) -> rebuild())
                .create();
        dlg.show();
    }

    private void showMaxChannelDialog() {
        final int[] presets = {2, 6, 8};
        final String[] labels = new String[presets.length];
        for (int i = 0; i < presets.length; i++) {
            labels[i] = (presets[i] == Settings.audioMaxChannels() ? "● " : "○ ")
                    + presets[i] + " ch"
                    + (presets[i] == 2 ? "（立体声）" : (presets[i] == 6 ? "（5.1）" : "（7.1）"))
                    + (presets[i] == Settings.AUDIO_MAX_CH_DEFAULT ? "（缺省）" : "");
        }
        android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(
                this, android.R.style.Theme_DeviceDefault_Dialog)
                .setTitle("最大声道数")
                .setItems(labels, (d, which) -> {
                    Settings.setAudioMaxChannels(presets[which]);
                    Toast.makeText(this, "最大声道数已设为 " + Settings.audioMaxChannels() + " ch",
                            Toast.LENGTH_SHORT).show();
                    rebuild();
                })
                .setNegativeButton("取消", null)
                .create();
        dlg.show();
    }

    private void showAudioLangDialog() {
        final String[] codes = {"", "zh", "en", "ja", "yue"};
        final String[] labels = new String[codes.length];
        for (int i = 0; i < codes.length; i++) {
            labels[i] = (codes[i].equals(Settings.audioPreferredLang()) ? "● " : "○ ")
                    + langName(codes[i]);
        }
        android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(
                this, android.R.style.Theme_DeviceDefault_Dialog)
                .setTitle("首选音轨语言")
                .setItems(labels, (d, which) -> {
                    Settings.setAudioPreferredLang(codes[which]);
                    rebuild();
                })
                .setNegativeButton("取消", null)
                .create();
        dlg.show();
    }

    private String langName(String code) {
        if (code == null || code.isEmpty()) return "不指定";
        if ("zh".equals(code)) return "中文";
        if ("en".equals(code)) return "英语";
        if ("ja".equals(code)) return "日语";
        if ("yue".equals(code)) return "粤语";
        return code;
    }

    // ---------- 播放 · 视频 ----------

    private void showResizeDialog() {
        final int[] modes = {Settings.RESIZE_FIT, Settings.RESIZE_FILL, Settings.RESIZE_ZOOM};
        final String[] labels = new String[modes.length];
        for (int i = 0; i < modes.length; i++) {
            labels[i] = (modes[i] == Settings.videoResize() ? "● " : "○ ") + resizeName(modes[i]);
        }
        android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(
                this, android.R.style.Theme_DeviceDefault_Dialog)
                .setTitle("画面比例")
                .setItems(labels, (d, which) -> {
                    Settings.setVideoResize(modes[which]);
                    rebuild();
                })
                .setNegativeButton("取消", null)
                .create();
        dlg.show();
    }

    private String resizeName(int mode) {
        if (mode == Settings.RESIZE_FILL) return "拉伸填满（会变形）";
        if (mode == Settings.RESIZE_ZOOM) return "裁剪填满（切边缘）";
        return "适应屏幕（推荐）";
    }

    private void showQualityDialog() {
        final int[] caps = {Settings.QUALITY_ORIGINAL, Settings.QUALITY_1080, Settings.QUALITY_720};
        final String[] labels = new String[caps.length];
        for (int i = 0; i < caps.length; i++) {
            labels[i] = (caps[i] == Settings.qualityCap() ? "● " : "○ ") + qualityName(caps[i]);
        }
        android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(
                this, android.R.style.Theme_DeviceDefault_Dialog)
                .setTitle("在线清晰度上限")
                .setItems(labels, (d, which) -> {
                    Settings.setQualityCap(caps[which]);
                    rebuild();
                })
                .setNegativeButton("取消", null)
                .create();
        dlg.show();
    }

    private String qualityName(int cap) {
        if (cap == Settings.QUALITY_1080) return "≤ 1080p 转码流";
        if (cap == Settings.QUALITY_720) return "≤ 720p 转码流";
        return "原画优先";
    }

    private void showSubSizeDialog() {
        final int[] sizes = {0, 1, 2, 3};
        final String[] labels = new String[sizes.length];
        for (int i = 0; i < sizes.length; i++) {
            labels[i] = (sizes[i] == Settings.subSize() ? "● " : "○ ") + subSizeName(sizes[i]);
        }
        android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(
                this, android.R.style.Theme_DeviceDefault_Dialog)
                .setTitle("字幕字号")
                .setItems(labels, (d, which) -> {
                    Settings.setSubSize(sizes[which]);
                    rebuild();
                })
                .setNegativeButton("取消", null)
                .create();
        dlg.show();
    }

    private String subSizeName(int size) {
        if (size == 0) return "小";
        if (size == 1) return "中";
        if (size == 3) return "特大";
        return "大（缺省）";
    }

    private static String onOff(boolean on) {
        return on ? "开" : "关";
    }

    private void runDiag() {
        Toast.makeText(this, "诊断中…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            final StringBuilder sb = new StringBuilder();
            // 论坛首页
            Http.Resp r1 = Http.get(Site.BASE + "/forum.php");
            boolean forumOk = r1.code == 200 && !Site.isLoginWall(r1.body);
            sb.append("论坛: ").append(r1.code == 0 ? "网络不可达" : ("HTTP " + r1.code))
              .append(forumOk ? " (已登录)" : (r1.code == 200 ? " (被登录墙拦截)" : ""));
            sb.append("\n");
            // 百度二维码接口
            Http.Resp r2 = Http.get("https://passport.baidu.com/v2/api/getqrcode?lp=pc&qrloginfrom=skip");
            String qrHint = "";
            if (r2.code == 200 && r2.body.contains("sign")) qrHint = " (正常)";
            sb.append("百度扫码接口: ").append(r2.code == 0 ? "网络不可达" : ("HTTP " + r2.code)).append(qrHint);
            sb.append("\n");
            // 百度网盘登录态
            sb.append("百度登录态: ").append(CookieStore.hasBaiduLogin() ? "已授权" : "未授权");
            sb.append("\n");
            // 论坛 Cookie
            String auth = CookieStore.get(Site.BASE + "/", "cTo3_2132_auth");
            sb.append("论坛Cookie: ").append(auth == null || auth.isEmpty() ? "未注入" : "已内置");
            runOnUiThread(() -> new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog)
                    .setTitle("连接诊断")
                    .setMessage(sb.toString())
                    .setPositiveButton("好", null)
                    .show());
        }).start();
    }

    private TextView sectionLabel(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(0xFF1E88E5);
        tv.setTextSize(15);
        tv.setPadding(0, dip(14), 0, dip(4));
        return tv;
    }

    private TextView option(String title, String sub, View.OnClickListener click) {
        TextView tv = new TextView(this);
        tv.setText(sub == null || sub.isEmpty() ? title : title + "\n" + sub);
        tv.setTextSize(15);
        tv.setTextColor(0xFFEEEEEE);
        tv.setPadding(dip(14), dip(10), dip(14), dip(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dip(8);
        tv.setLayoutParams(lp);
        tv.setBackground(getDrawable(android.R.color.darker_gray));
        tv.getBackground().setAlpha(24);
        tv.setFocusable(true);
        tv.setClickable(click != null);
        tv.setOnFocusChangeListener((v, has) -> v.setBackgroundColor(has ? 0xFF2A323C : 0x1A1F2600));
        if (click != null) tv.setOnClickListener(click);
        return tv;
    }

    private int dip(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
