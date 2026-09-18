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
        group.addView(option("最大声道数上限: " + Settings.audioMaxChannels() + " ch",
                "2 = 立体声 / 6 = 5.1 / 8 = 7.1（缺省 8）\n"
                        + "这是「上限」而不是「写死」：媒体引擎拿它在片源里挑不超过该声道的音轨\n"
                        + "v1.25 曾把缺省压到 6，代价是 7.1 的 TrueHD / DTS-HD 一律直通不了 —— "
                        + "v1.26 改回 8，真建不出轨时自动降道重试（见下一项）",
                v -> showMaxChannelDialog()));
        group.addView(option("本机直通实测: " + ptOkText(),
                "第一次播直通片时自动逐级试 8 → 6 → 2，出声的那一档记在本机，以后开局直接用\n"
                        + "这就是「设备差异」的处理办法：不按机型一个个打补丁，让每台机器自己把话说完，"
                        + "能 8 声道的功放和只能 6 声道的盒子各得其所\n"
                        + "点按可清除记录，下次播放重新测（换功放 / 换线 / 刷固件之后用）",
                v -> showPtResetDialog()));
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

        group.addView(option("媒体诊断（音频 / 视频 / 字幕）",
                "有画面没声音、杜比视界点不亮、ASS 字幕不出来 —— 先看这里\n"
                        + "含：设备真实直通能力 / 本机直通实测声道 / 系统音频与视频解码器 / "
                        + "显示端 HDR 与 DV 能力 / 上次音频失败原因\n"
                        + "（media3 在「直通不了又没有解码器」时会静默丢弃音轨：画面照播、不报错、也没声）",
                v -> showAudioDiag()));

        // ②.8 播放 · 视频
        group.addView(sectionLabel("播放 · 视频"));
        group.addView(option("画面比例: " + resizeName(Settings.videoResize()),
                "适应屏幕 = 保留原始比例、可能有黑边（推荐）\n"
                        + "拉伸 / 裁剪 = 填满屏幕，前者会变形，后者会切掉边缘",
                v -> showResizeDialog()));
        group.addView(option("最大分辨率: " + videoHeightName(Settings.maxVideoHeight()),
                "不限制（推荐）= 片源是什么就放什么\n"
                        + "盒子/电视只到 1080p 却硬啃 4K，常见表现是掉帧、发烫、甚至解码器直接崩\n"
                        + "只有「同一部片里既有 4K 又有 1080p 轨」时才需要限制，单轨片源限了也没用",
                v -> showMaxVideoHeightDialog()));
        group.addView(option("最大帧率: " + videoFpsName(Settings.maxVideoFrameRate()),
                "不限制（推荐）。老盒子遇到 60fps 片源明显卡顿时，可以压到 30 或 24",
                v -> showMaxVideoFpsDialog()));
        group.addView(option("解码器优先: " + decoderPrefName(Settings.decoderPrefer()),
                "自动（推荐）= 先用硬件解码，失败再退软解\n"
                        + "优先硬解 = 只认硬解：省电、颜色和 HDR 更准，但遇到不支持的编码会直接失败\n"
                        + "优先软解 = 一律走 CPU：最稳，代价是 4K 会烫、会卡",
                v -> showDecoderPrefDialog()));
        group.addView(option("硬解失败回退软解: " + onOff(Settings.decoderFallback()),
                "开：盒子硬解不了该编码时自动换别的解码器再试\n"
                        + "关：直接报解码失败（排查不支持编码时用）",
                v -> {
                    Settings.setDecoderFallback(!Settings.decoderFallback());
                    rebuild();
                }));
        group.addView(option("隧道模式: " + onOff(Settings.tunneling()),
                "开 = Tunneled playback：把「解码 + 显示」交给系统一气呵成\n"
                        + "部分老电视盒上音画会更同步、切台不黑屏；也有固件直接给黑屏 —— "
                        + "打开后如果没画面就关掉（缺省关）",
                v -> {
                    Settings.setTunneling(!Settings.tunneling());
                    rebuild();
                }));
        group.addView(option("杜比视界处理: " + dvPolicyName(Settings.dvPolicy()),
                "跟随片源（推荐）。遇到「杜比视界片源全黑 / 发紫」再改成「优先非 DV 轨」\n"
                        + "实话：DV 能不能点亮由「显示端 + 芯片」决定，应用改不了 —— "
                        + "这一项只能让播放器在同一部片里改挑 HDR10 / H.265 基础层那条轨",
                v -> showDvPolicyDialog()));

        // ②.9 播放 · 字幕（v1.26：用户反馈「没有字幕相关的选项」）
        group.addView(sectionLabel("播放 · 字幕"));
        group.addView(option("字幕模式: " + subModeName(Settings.subMode()),
                "跟随片源（推荐）/ 总是打开 / 一律关闭\n"
                        + "「一律关闭」只是不自动开字幕，播放中仍然可以用上/下键手动选字幕轨",
                v -> showSubModeDialog()));
        group.addView(option("首选字幕语言: " + langName(Settings.subPreferredLang()),
                "内嵌多字幕时优先挑这个语种；「不指定」= 跟随片源默认轨",
                v -> showSubLangDialog()));
        group.addView(option("字幕字号: " + subSizeName(Settings.subSize()),
                "以屏幕高度为基准；电视上离得远，建议「大」",
                v -> showSubSizeDialog()));
        group.addView(option("字幕颜色: " + subColorName(Settings.subColor()),
                "白字黑描边（缺省）/ 黄字黑描边 / 黑底白字 / 白字无描边\n"
                        + "这是全局样式，会盖掉 ASS 里逐行的 \\c&Hxx 颜色；想保留片源原色选「白字无描边」",
                v -> showSubColorDialog()));
        group.addView(option("字幕位置: " + subPosName(Settings.subPos()),
                "下部（缺省）/ 中部 / 顶部\n"
                        + "做法是把 SubtitleView 的底边留白比例改掉，对 ASS 里写了 \\pos 的字幕同样有效",
                v -> showSubPosDialog()));
        group.addView(option("外挂字幕自动挂载: " + onOff(Settings.subExternalAuto()),
                "开（缺省）：播本地文件时自动在同目录找同名 .ass / .ssa / .srt / .vtt / .ttml 挂上\n"
                        + "片名和字幕名对不上时，用下面的「手动字幕文件」",
                v -> {
                    Settings.setSubExternalAuto(!Settings.subExternalAuto());
                    rebuild();
                }));
        group.addView(option("手动字幕文件: " + subManualText(),
                "填本地绝对路径或 http(s) 链接，优先级高于自动查找；留空 = 关掉手动挂载\n"
                        + "这条专门为「外挂 ASS / SSA 字幕」准备：片子和字幕是两个文件时走这里",
                v -> showSubManualDialog()));

        group.addView(sectionLabel("播放 · 其他"));
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

        // ③.4 影片数据库 —— App 的全部内容都来自它，出问题等于没内容，所以给个能自查的入口
        group.addView(option("影片数据库：" + dbSummary(),
                "点击检查在线更新（800915.xyz）", v -> checkDbUpdate()));

        // ③.5 连接诊断
        group.addView(option("连接诊断", "测试影片库/百度接口连通性", v -> runDiag()));

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

    /** 音频诊断：把「设备能直通什么 / 有没有解码器 / 上次为什么失败」一次摊开。 */
    private void showAudioDiag() {
        String report;
        try {
            report = PlaybackEngine.deviceCapsReport(this);
        } catch (Throwable e) {
            report = "诊断失败: " + e;
        }
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog)
                .setTitle("音频诊断")
                .setMessage(report)
                .setPositiveButton("好", null)
                .show();
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

    // ---------- 播放 · 视频（v1.26 新增）----------

    /**
     * 单选列表对话框的公共壳子。
     *
     * <p>不用 {@code java.util.function.IntConsumer} —— 它是 API 24 才有的，
     * 本应用 minSdk 23，用了会在 6.0 机器上炸。所以自己声明一个接口。</p>
     */
    private interface IntPick {
        void onPick(int v);
    }

    private void pickOne(String title, int[] values, int cur, String[] names, IntPick pick) {
        final int[] vals = values;
        final String[] labels = new String[vals.length];
        for (int i = 0; i < vals.length; i++) {
            labels[i] = (vals[i] == cur ? "● " : "○ ") + names[i];
        }
        android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(
                this, android.R.style.Theme_DeviceDefault_Dialog)
                .setTitle(title)
                .setItems(labels, (d, which) -> {
                    pick.onPick(vals[which]);
                    rebuild();
                })
                .setNegativeButton("取消", null)
                .create();
        dlg.show();
    }

    private void showMaxVideoHeightDialog() {
        final int[] vs = {Settings.VIDEO_HEIGHT_AUTO, 720, 1080, 2160};
        final String[] names = new String[vs.length];
        for (int i = 0; i < vs.length; i++) names[i] = videoHeightName(vs[i]);
        pickOne("最大分辨率", vs, Settings.maxVideoHeight(), names, Settings::setMaxVideoHeight);
    }

    private String videoHeightName(int h) {
        if (h == 720) return "≤ 720p";
        if (h == 1080) return "≤ 1080p";
        if (h == 2160) return "≤ 4K（2160p）";
        return "不限制（自动）";
    }

    private void showMaxVideoFpsDialog() {
        final int[] vs = {Settings.VIDEO_FPS_AUTO, 24, 30, 60};
        final String[] names = new String[vs.length];
        for (int i = 0; i < vs.length; i++) names[i] = videoFpsName(vs[i]);
        pickOne("最大帧率", vs, Settings.maxVideoFrameRate(), names, Settings::setMaxVideoFrameRate);
    }

    private String videoFpsName(int fps) {
        if (fps == 24) return "≤ 24 fps";
        if (fps == 30) return "≤ 30 fps";
        if (fps == 60) return "≤ 60 fps";
        return "不限制（自动）";
    }

    private void showDecoderPrefDialog() {
        final int[] vs = {Settings.DECODER_AUTO, Settings.DECODER_PREFER_HW, Settings.DECODER_PREFER_SW};
        final String[] names = new String[vs.length];
        for (int i = 0; i < vs.length; i++) names[i] = decoderPrefName(vs[i]);
        pickOne("解码器优先", vs, Settings.decoderPrefer(), names, Settings::setDecoderPrefer);
    }

    private String decoderPrefName(int v) {
        if (v == Settings.DECODER_PREFER_HW) return "优先硬解";
        if (v == Settings.DECODER_PREFER_SW) return "优先软解";
        return "自动（推荐）";
    }

    private void showDvPolicyDialog() {
        final int[] vs = {Settings.DV_AUTO, Settings.DV_AVOID};
        final String[] names = new String[vs.length];
        for (int i = 0; i < vs.length; i++) names[i] = dvPolicyName(vs[i]);
        pickOne("杜比视界处理", vs, Settings.dvPolicy(), names, Settings::setDvPolicy);
    }

    private String dvPolicyName(int v) {
        if (v == Settings.DV_AVOID) return "优先非 DV 轨（黑屏时用）";
        return "跟随片源（推荐）";
    }

    // ---------- 播放 · 字幕（v1.26 新增）----------

    private void showSubModeDialog() {
        final int[] vs = {Settings.SUB_MODE_SOURCE, Settings.SUB_MODE_ALWAYS, Settings.SUB_MODE_OFF};
        final String[] names = new String[vs.length];
        for (int i = 0; i < vs.length; i++) names[i] = subModeName(vs[i]);
        pickOne("字幕模式", vs, Settings.subMode(), names, Settings::setSubMode);
    }

    private String subModeName(int v) {
        if (v == Settings.SUB_MODE_ALWAYS) return "总是打开";
        if (v == Settings.SUB_MODE_OFF) return "一律关闭";
        return "跟随片源（推荐）";
    }

    private void showSubLangDialog() {
        final String[] codes = {"", "zh", "en", "ja", "yue"};
        final String[] labels = new String[codes.length];
        for (int i = 0; i < codes.length; i++) {
            labels[i] = (codes[i].equals(Settings.subPreferredLang()) ? "● " : "○ ") + langName(codes[i]);
        }
        android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(
                this, android.R.style.Theme_DeviceDefault_Dialog)
                .setTitle("首选字幕语言")
                .setItems(labels, (d, which) -> {
                    Settings.setSubPreferredLang(codes[which]);
                    rebuild();
                })
                .setNegativeButton("取消", null)
                .create();
        dlg.show();
    }

    private void showSubColorDialog() {
        final int[] vs = {Settings.SUB_COLOR_WHITE_OUTLINE, Settings.SUB_COLOR_YELLOW_OUTLINE,
                Settings.SUB_COLOR_BOX, Settings.SUB_COLOR_PLAIN};
        final String[] names = new String[vs.length];
        for (int i = 0; i < vs.length; i++) names[i] = subColorName(vs[i]);
        pickOne("字幕颜色", vs, Settings.subColor(), names, Settings::setSubColor);
    }

    private String subColorName(int v) {
        if (v == Settings.SUB_COLOR_YELLOW_OUTLINE) return "黄字黑描边";
        if (v == Settings.SUB_COLOR_BOX) return "黑底白字";
        if (v == Settings.SUB_COLOR_PLAIN) return "白字无描边";
        return "白字黑描边（缺省）";
    }

    private void showSubPosDialog() {
        final int[] vs = {Settings.SUB_POS_BOTTOM, Settings.SUB_POS_MIDDLE, Settings.SUB_POS_TOP};
        final String[] names = new String[vs.length];
        for (int i = 0; i < vs.length; i++) names[i] = subPosName(vs[i]);
        pickOne("字幕位置", vs, Settings.subPos(), names, Settings::setSubPos);
    }

    private String subPosName(int v) {
        if (v == Settings.SUB_POS_TOP) return "顶部";
        if (v == Settings.SUB_POS_MIDDLE) return "中部";
        return "下部（缺省）";
    }

    /** 手动字幕文件：本地绝对路径或 http(s) 链接，留空即关闭。 */
    private void showSubManualDialog() {
        final android.widget.EditText input = new android.widget.EditText(this);
        input.setText(Settings.subManual());
        input.setSelection(input.getText().length());
        input.setTextSize(15);
        android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(
                this, android.R.style.Theme_DeviceDefault_Dialog)
                .setTitle("手动字幕文件")
                .setMessage("本地绝对路径，或 http(s) 直链；留空 = 关掉手动挂载\n"
                        + "支持 .ass / .ssa / .srt / .vtt / .ttml")
                .setView(input)
                .setPositiveButton("保存", (d, w) -> {
                    Settings.setSubManual(input.getText().toString().trim());
                    Toast.makeText(this, Settings.subManual().isEmpty()
                            ? "已关闭手动字幕" : "手动字幕已设为\n" + Settings.subManual(),
                            Toast.LENGTH_LONG).show();
                    rebuild();
                })
                .setNegativeButton("取消", null)
                .create();
        dlg.show();
        dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE).requestFocus();
    }

    private String subManualText() {
        String v = Settings.subManual();
        if (v == null || v.isEmpty()) return "未指定";
        return v.length() > 42 ? "…" + v.substring(v.length() - 42) : v;
    }

    // ---------- 直通实测记忆（问题 5 的落点）----------

    private String ptOkText() {
        int ok = Settings.ptOkCh();
        if (ok <= 0) return "还没测出来（首次播直通片时自动测）";
        int eff = Settings.ptEffectiveCap();
        return ok + " ch 可用"
                + (eff < Settings.audioMaxChannels() ? "（当前实际按 " + eff + " ch 走）" : "");
    }

    private void showPtResetDialog() {
        final String body = "本机记录：" + ptOkText() + "\n"
                + "设备指纹：\n" + Settings.ptDeviceKey() + "\n\n"
                + "清除后下次播放会重新逐级试 8 → 6 → 2 声道。\n"
                + "换功放、换 HDMI 线、刷固件之后建议清一次。";
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog)
                .setTitle("本机直通实测")
                .setMessage(body)
                .setPositiveButton("清除记录", (d, w) -> {
                    Settings.clearPtOkCh();
                    Toast.makeText(this, "已清除，下次播放重新测", Toast.LENGTH_SHORT).show();
                    rebuild();
                })
                .setNegativeButton("返回", null)
                .show();
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
            // 影片数据库（内容全部来自这里，它出问题等于整个 App 没内容）
            sb.append("影片数据库: ").append(MovieStore.isReady() ? MovieDb.describe(this) : "未就位");
            sb.append("\n");
            int cats = MovieStore.categories().size();
            sb.append("版块数: ").append(cats);
            sb.append("\n");
            // 百度二维码接口（扫码授权链路）
            Http.Resp r2 = Http.get("https://passport.baidu.com/v2/api/getqrcode?lp=pc&qrloginfrom=skip");
            String qrHint = "";
            if (r2.code == 200 && r2.body.contains("sign")) qrHint = " (正常)";
            sb.append("百度扫码接口: ").append(r2.code == 0 ? "网络不可达" : ("HTTP " + r2.code)).append(qrHint);
            sb.append("\n");
            // 百度网盘登录态
            sb.append("百度登录态: ").append(CookieStore.hasBaiduLogin() ? "已授权" : "未授权");
            runOnUiThread(() -> new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog)
                    .setTitle("连接诊断")
                    .setMessage(sb.toString())
                    .setPositiveButton("好", null)
                    .show());
        }).start();
    }

    /** 设置页上那行数据库摘要。 */
    private String dbSummary() {
        if (!MovieStore.isReady()) return "未就位";
        return "数据 v" + MovieDb.installedDbVersion(this);
    }

    /**
     * 检查影片数据库的在线更新。
     *
     * <p>远端清单放在 {@code https://800915.xyz/db/SuperMOV.json}。该地址当前<b>还没上线</b>，
     * 所以拿不到清单属于正常情况 —— DbUpdater 内部把 404 / 超时 / 解析失败一律当「没有更新」
     * 静默处理，这里只会照实回报结果，不会当成错误弹红字。</p>
     */
    private void checkDbUpdate() {
        Toast.makeText(this, "检查影片数据库更新…", Toast.LENGTH_SHORT).show();
        DbUpdater.checkAndUpdate(this, (updated, local, remote, message) -> runOnUiThread(() -> {
            String extra = "";
            if (updated) {
                // 换了库必须重开连接，否则还在读旧的数据库句柄
                MovieStore.reload();
                extra = "\n\n已重新载入，返回首页即可看到新增内容。";
            }
            new AlertDialog.Builder(SettingsActivity.this, android.R.style.Theme_DeviceDefault_Dialog)
                    .setTitle("影片数据库")
                    .setMessage(message + extra)
                    .setPositiveButton("好", (d, w) -> {
                        if (updated) rebuild();
                    })
                    .show();
        }));
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
