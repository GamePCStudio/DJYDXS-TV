package com.supermov.tv;

import android.content.Context;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.audio.AudioCapabilities;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.DefaultAudioSink;

import java.util.ArrayList;
import java.util.List;

/**
 * 播放引擎装配层（v1.25 · DJYDXS2Nexio）。
 *
 * <p>把「设置页」里的音频 / 视频选项翻译成 media3 的构造参数，避免这些细节散落到
 * {@link PlayerActivity} 里。两条主线：</p>
 *
 * <ul>
 *   <li><b>音频</b>：自动 / 源码直通 / 强制解码。直通靠「伪造音频能力表」实现 ——
 *       见 {@link EngineRenderersFactory#buildAudioSink} 里那段注释，为什么必须用
 *       无 Context 的 {@code DefaultAudioSink.Builder()}。</li>
 *   <li><b>视频</b>：画面比例、字幕字号在 Activity 侧直接生效；
 *       清晰度上限影响取流策略（在 PlayerActivity 里判断）。</li>
 * </ul>
 */
@OptIn(markerClass = UnstableApi.class)
public final class PlaybackEngine {

    private static final String TAG = "SupeMov";

    private PlaybackEngine() {
    }

    // ---------- 音频 ----------

    /**
     * 造 RenderersFactory。
     *
     * @param forceDecode 直通失败后的兜底：true = 换回 PCM 解码重播一遍
     */
    public static DefaultRenderersFactory renderersFactory(Context ctx, boolean forceDecode) {
        return new EngineRenderersFactory(ctx, injectedCapabilities(forceDecode),
                Settings.decoderFallback(), Settings.decoderPrefer());
    }

    /**
     * 轨道选择器。
     *
     * <p>以前直接用 {@code ExoPlayer.Builder} 的缺省 selector —— 拿不到实例，也就改不了
     * 隧道模式（{@code setTunnelingEnabled} 是 {@code DefaultTrackSelector} 的参数）。
     * 现在自己造一个，把设置页的隧道开关带上。</p>
     */
    public static DefaultTrackSelector trackSelector(Context ctx) {
        DefaultTrackSelector sel = new DefaultTrackSelector(ctx);
        try {
            sel.setParameters(sel.getParameters().buildUpon()
                    .setTunnelingEnabled(Settings.tunneling())
                    .build());
        } catch (Throwable e) {
            Log.w(TAG, "tunneling 设置失败: " + e);
        }
        return sel;
    }

    /**
     * 伪造一份音频能力表。
     *
     * <p>media3 判断「这条压缩音轨能不能直通」的唯一依据，就是 sink 手里的
     * {@link AudioCapabilities#supportsEncoding(int)}。而国产盒子 / TV 的 Audio HAL
     * 大量不上报环绕声编码 —— 不干预时 {@code supportsEncoding(ENCODING_AC3)} 就是 false，
     * 于是 sink 走 PCM 模式把 5.1 静默降混成 2.0，<b>不报错、不抛异常</b>，
     * 最容易被误判成「片源问题」。所以要看功放点亮 DD/DTS，就得把能力表改掉。</p>
     *
     * @return {@code null} = 不干预，用设备真实能力表（自动模式）
     */
    @Nullable
    public static AudioCapabilities injectedCapabilities(boolean forceDecode) {
        int[] enc;
        if (forceDecode) {
            // 只声明 PCM -> 任何压缩编码都不被 sink 支持 -> ExoPlayer 必须挂解码器解成 PCM
            enc = new int[] {C.ENCODING_PCM_16BIT};
            Log.i(TAG, "audio caps: forced PCM (decode fallback)");
        } else {
            int mode = Settings.audioMode();
            if (mode == Settings.AUDIO_MODE_AUTO) {
                Log.i(TAG, "audio caps: 跟随设备（不干预）");
                return null;
            }
            if (mode == Settings.AUDIO_MODE_PCM) {
                enc = new int[] {C.ENCODING_PCM_16BIT};
                Log.i(TAG, "audio caps: PCM only (force decode)");
            } else {
                List<Integer> list = new ArrayList<Integer>();
                list.add(Integer.valueOf(C.ENCODING_PCM_16BIT));   // 解码路径永远要有
                if (Settings.passthroughCodec(Settings.PT_AC3)) list.add(Integer.valueOf(C.ENCODING_AC3));
                if (Settings.passthroughCodec(Settings.PT_EAC3)) list.add(Integer.valueOf(C.ENCODING_E_AC3));
                if (Settings.passthroughCodec(Settings.PT_EAC3_JOC)) list.add(Integer.valueOf(C.ENCODING_E_AC3_JOC));
                if (Settings.passthroughCodec(Settings.PT_AC4)) list.add(Integer.valueOf(C.ENCODING_AC4));
                if (Settings.passthroughCodec(Settings.PT_DTS)) list.add(Integer.valueOf(C.ENCODING_DTS));
                if (Settings.passthroughCodec(Settings.PT_DTS_HD)) list.add(Integer.valueOf(C.ENCODING_DTS_HD));
                if (Settings.passthroughCodec(Settings.PT_TRUEHD)) list.add(Integer.valueOf(C.ENCODING_DOLBY_TRUEHD));
                enc = new int[list.size()];
                for (int i = 0; i < list.size(); i++) enc[i] = list.get(i).intValue();
                Log.i(TAG, "audio caps: 源码直通，声明 " + list.size() + " 种编码");
            }
        }
        int maxCh = effectiveMaxAudioCh();
        if (maxCh < 2) maxCh = 2;
        Log.i(TAG, "audio caps: maxChannelCount=" + maxCh
                + (capOverride > 0 ? "（降道重试中）" : "")
                + (Settings.ptOkCh() > 0 ? "（实测可用 " + Settings.ptOkCh() + "ch）" : ""));
        return newDeprecatedCaps(enc, maxCh);
    }

    // ---------- 声道口径（v1.26：上限 + 实测，不再全设备统一写死）----------

    /**
     * 直通降道重试时临时指定的声道上限。
     *
     * <p>为什么要做成全局静态量：{@code AudioCapabilities} 是 <b>建 AudioSink 时的快照</b>，
     * 而 {@code TrackSelectionParameters} 用的是另一次读取 —— 两处必须同一个口径，
     * 否则会出现「能力表说 8 能直通、选轨上限却只有 6」的自相矛盾（v1.25 的回归就是这么来的）。
     * 播放器重建引擎前用 {@link #setChannelCapOverride(int)} 设一次，两处同时生效。</p>
     */
    private static volatile int capOverride = 0;

    /** 设置直通降道重试的临时声道上限（0 = 不覆盖）。 */
    public static void setChannelCapOverride(int ch) {
        capOverride = (ch == 2 || ch == 6 || ch == 8) ? ch : 0;
    }

    /**
     * 当前该用的音频声道上限。
     *
     * <p>优先级：降道重试的临时值 &gt; 本机实测可用值 &gt; 设置页上限。
     * 「实测可用值」来自 {@link Settings#ptOkCh()} —— 直通真的出过声的那一档，
     * 这是解决「设备差异」的正解：不猜、不写死，让设备自己说话并记住结论。</p>
     */
    public static int effectiveMaxAudioCh() {
        if (capOverride > 0) return capOverride;
        if (Settings.audioMode() == Settings.AUDIO_MODE_PASSTHROUGH) return Settings.ptEffectiveCap();
        return Settings.audioMaxChannels();
    }

    /**
     * {@link AudioCapabilities} 的公开构造函数已 deprecated，官方建议改用
     * {@code getCapabilities(Context)}；但在本 fork（1.10.0 基线）里，
     * <b>这个构造函数是无 Context 场景下唯一能自定义能力表的入口</b>，只能继续用。
     */
    @SuppressWarnings("deprecation")
    private static AudioCapabilities newDeprecatedCaps(int[] encodings, int maxChannelCount) {
        return new AudioCapabilities(encodings, maxChannelCount);
    }

    /**
     * 往「播放器现有的轨道参数」上叠加音频偏好。
     *
     * <p><b>别自己 new 一个 {@code TrackSelectionParameters.Builder}（哪怕带 Context）。</b>
     * 1.10.0 里那个带 Context 的构造函数已 deprecated 成 {@code this()}（{@code @InlineMe}），
     * Context 是白传的，造出来的是 {@code DEFAULT_WITHOUT_CONTEXT} —— 会把播放器原本带进来的
     * 默认值（例如「字幕首选语言 = 系统语言」）一并丢掉，字幕自动选择随之变味。
     * media3 官方文档给的写法就是 {@code player.getTrackSelectionParameters().buildUpon()}。</p>
     *
     * <p>{@code setMaxAudioChannelCount} 会把超过功放声道数的音轨降权/排除；
     * {@code setPreferredAudioMimeTypes} 解决「同一部片里既有 AAC 2.0 又有 AC3 5.1」的选轨问题
     * —— 不指定时 media3 的默认偏好会挑 AAC，那条轨根本没法直通。</p>
     */
    public static TrackSelectionParameters.Builder withAudioPreferences(
            TrackSelectionParameters.Builder b) {
        // 与能力表同口径：直通时是「设置上限」与「实测可用值」的较小者
        b.setMaxAudioChannelCount(effectiveMaxAudioCh());
        String lang = Settings.audioPreferredLang();
        if (lang != null && !lang.isEmpty()) b.setPreferredAudioLanguage(lang);
        if (Settings.audioMode() == Settings.AUDIO_MODE_PASSTHROUGH) {
            List<String> mimes = new ArrayList<String>();
            if (Settings.passthroughCodec(Settings.PT_TRUEHD)) mimes.add(MimeTypes.AUDIO_TRUEHD);
            if (Settings.passthroughCodec(Settings.PT_DTS_HD)) mimes.add(MimeTypes.AUDIO_DTS_HD);
            if (Settings.passthroughCodec(Settings.PT_EAC3_JOC)) mimes.add(MimeTypes.AUDIO_E_AC3_JOC);
            if (Settings.passthroughCodec(Settings.PT_EAC3)) mimes.add(MimeTypes.AUDIO_E_AC3);
            if (Settings.passthroughCodec(Settings.PT_AC3)) mimes.add(MimeTypes.AUDIO_AC3);
            if (Settings.passthroughCodec(Settings.PT_DTS)) mimes.add(MimeTypes.AUDIO_DTS);
            if (Settings.passthroughCodec(Settings.PT_AC4)) mimes.add(MimeTypes.AUDIO_AC4);
            if (!mimes.isEmpty()) {
                b.setPreferredAudioMimeTypes(mimes.toArray(new String[0]));
            }
        }
        return b;
    }

    // ---------- 视频偏好（v1.26）----------

    /**
     * 叠加视频相关偏好。
     *
     * <p>三件事，全部走 {@code TrackSelectionParameters}（不动渲染器，改完立即生效）：</p>
     * <ul>
     *   <li><b>最大分辨率 / 最大帧率</b>：{@code setMaxVideoSize} / {@code setMaxVideoFrameRate}。
     *       超出的轨会被标成「不在约束内」，从而优先挑更低的那条 —— 盒子只到 1080p 时
     *       别硬啃 4K。</li>
     *   <li><b>杜比视界处理</b>：注意 app 层<b>无法</b>切换 DV↔HDR10 的渲染方式，
     *       那取决于显示端能力与芯片解码器（media3 只在 API 26+ 且屏幕不支持 DV 时
     *       自动改用基础层解码器）。这里能做的是「选轨倾向」：{@code DV_AVOID} 时把
     *       非 DV 的常见视频 mime 列为偏好，同一部片里若有 HDR10 轨就会被优先选中。</li>
     * </ul>
     */
    public static TrackSelectionParameters.Builder withVideoPreferences(
            TrackSelectionParameters.Builder b) {
        try {
            // 只在用户设了上限时才写值：新建播放器时参数本来就是 DEFAULT，
            // 不需要 clear（TrackSelectionParameters.Builder 也没有 clearVideoFrameRateConstraints）
            int h = Settings.maxVideoHeight();
            if (h > 0) b.setMaxVideoSize(Integer.MAX_VALUE, h);
            int fps = Settings.maxVideoFrameRate();
            if (fps > 0) b.setMaxVideoFrameRate(fps);
            if (Settings.dvPolicy() == Settings.DV_AVOID) {
                // 「优先要哪些」而不是「排除哪些」—— setPreferredVideoMimeTypes 的语义就是偏好表
                b.setPreferredVideoMimeTypes(
                        MimeTypes.VIDEO_H265, MimeTypes.VIDEO_AV1, MimeTypes.VIDEO_H264, MimeTypes.VIDEO_VP9);
                Log.i(TAG, "video pref: 优先非杜比视界轨");
            }
        } catch (Throwable e) {
            Log.w(TAG, "withVideoPreferences 失败: " + e);
        }
        return b;
    }

    // ---------- 字幕偏好（v1.26）----------

    /**
     * 叠加字幕相关偏好。
     *
     * <p>三种模式：跟随片源（不动）、总是开（{@code setSelectTextByDefault(true)} +
     * 首选语言）、关（把文本渲染器整个禁用，播放中仍可用上下键手动打开）。</p>
     *
     * <p>注意 {@code setSelectTextByDefault} 的语义是「容器没标 default 轨也照样选一条」，
     * 正好对应「总是开」。</p>
     */
    public static TrackSelectionParameters.Builder withSubtitlePreferences(
            TrackSelectionParameters.Builder b) {
        try {
            int mode = Settings.subMode();
            if (mode == Settings.SUB_MODE_OFF) {
                b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true);
            } else {
                b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false);
                String lang = Settings.subPreferredLang();
                if (lang != null && !lang.isEmpty()) b.setPreferredTextLanguage(lang);
                b.setSelectTextByDefault(mode == Settings.SUB_MODE_ALWAYS);
            }
        } catch (Throwable e) {
            Log.w(TAG, "withSubtitlePreferences 失败: " + e);
        }
        return b;
    }

    /**
     * 出错了要不要「换解码重播」。
     *
     * <p>只在源码直通模式下、且用户没关掉回退时才做。强制直通是拿伪造能力表换来的，
     * 硬件真不支持时建 AudioTrack 会直接失败；接住它并降级，才不会「一播就崩」。</p>
     */
    public static boolean shouldFallbackToDecode(boolean alreadyDecoded) {
        if (alreadyDecoded) return false;
        if (!Settings.passthroughFallback()) return false;
        return Settings.audioMode() == Settings.AUDIO_MODE_PASSTHROUGH;
    }

    // ---------- 音频诊断 ----------

    /** 上一次音频输出失败的简述（由 PlayerActivity 失败时写入，供设置页诊断用）。 */
    private static volatile String lastAudioError = "";

    /** 记录一次音频输出失败（只用于诊断显示）。 */
    public static void noteAudioError(String what) {
        if (what != null && !what.isEmpty()) lastAudioError = what;
    }

    /**
     * 设备音频能力「体检报告」。
     *
     * <p>排查「有画面、没声音」时先看这里。media3 只有在下面二者之一成立时才会给音轨建
     * 渲染器：① 能力表说这个编码能直通；② 系统里有对应的解码器。两者都不成立时，
     * {@code DefaultTrackSelector} 会**直接不选这条音轨** —— 画面照播、不报错、也没声音，
     * 最容易误判成「片源问题」。这个报告把这两件事都摊开，省掉一轮抓 logcat。</p>
     */
    public static String deviceCapsReport(Context ctx) {
        StringBuilder sb = new StringBuilder();

        sb.append("【当前设置】\n");
        sb.append("音频输出: ").append(modeName(Settings.audioMode())).append('\n');
        sb.append("声明直通: ").append(Settings.passthroughCodecCount()).append(" 项 · ")
                .append(Settings.passthroughCodecs()).append('\n');
        sb.append("最大声道: ").append(Settings.audioMaxChannels()).append(" ch\n");
        sb.append('\n');

        sb.append("【设备上报的直通能力】\n");
        try {
            @SuppressWarnings("deprecation")
            AudioCapabilities caps = AudioCapabilities.getCapabilities(ctx);
            sb.append(capsLine(caps, "AC-3", C.ENCODING_AC3));
            sb.append(capsLine(caps, "E-AC-3", C.ENCODING_E_AC3));
            sb.append(capsLine(caps, "E-AC-3 JOC(Atmos)", C.ENCODING_E_AC3_JOC));
            sb.append(capsLine(caps, "AC-4", C.ENCODING_AC4));
            sb.append(capsLine(caps, "DTS", C.ENCODING_DTS));
            sb.append(capsLine(caps, "DTS-HD", C.ENCODING_DTS_HD));
            sb.append(capsLine(caps, "TrueHD", C.ENCODING_DOLBY_TRUEHD));
            sb.append("设备最大声道: ").append(caps.getMaxChannelCount()).append('\n');
        } catch (Throwable e) {
            sb.append("读取失败: ").append(e).append('\n');
        }
        sb.append('\n');

        sb.append("【系统里的压缩音频解码器】\n");
        sb.append(decoderReport());
        sb.append('\n');

        sb.append("【上次音频失败】\n");
        sb.append(lastAudioError.isEmpty() ? "（本次运行还没有音频失败）" : lastAudioError);
        sb.append('\n');
        sb.append(avReport(ctx));
        return sb.toString();
    }

    /**
     * 视频 / 字幕侧体检：显示端 HDR 与杜比视界能力、视频解码器、字幕解析支持、外挂字幕状态。
     *
     * <p>查「DV 到底能不能点亮」「ASS 字幕为什么没出来」先看这里 —— 三件事一次摊开。</p>
     */
    public static String avReport(Context ctx) {
        StringBuilder sb = new StringBuilder();

        sb.append("【显示端 HDR / 杜比视界】\n");
        try {
            if (android.os.Build.VERSION.SDK_INT >= 24) {
                android.hardware.display.DisplayManager dm = (android.hardware.display.DisplayManager)
                        ctx.getSystemService(Context.DISPLAY_SERVICE);
                android.view.Display d = dm == null ? null
                        : dm.getDisplay(android.view.Display.DEFAULT_DISPLAY);
                if (d == null) {
                    sb.append("取不到默认显示\n");
                } else {
                    sb.append("HDR 屏幕: ").append(d.isHdr() ? "是" : "否").append('\n');
                    android.view.Display.HdrCapabilities hc = d.getHdrCapabilities();
                    int[] types = hc == null ? null : hc.getSupportedHdrTypes();
                    sb.append("HDR 类型: ");
                    if (types == null || types.length == 0) {
                        sb.append("（无）");
                    } else {
                        for (int i = 0; i < types.length; i++) {
                            if (i > 0) sb.append(" / ");
                            sb.append(hdrName(types[i]));
                        }
                    }
                    sb.append('\n');
                    if (hc != null) {
                        sb.append("最大亮度: ").append((int) hc.getDesiredMaxLuminance())
                                .append(" / 平均 ").append((int) hc.getDesiredMaxAverageLuminance())
                                .append(" nits\n");
                    }
                }
            } else {
                sb.append("系统 < 7.0，取不到 HDR 能力（API ").append(android.os.Build.VERSION.SDK_INT).append("）\n");
            }
        } catch (Throwable e) {
            sb.append("读取失败: ").append(e).append('\n');
        }
        sb.append("说明：杜比视界能否点亮由「显示端 + 芯片解码器」决定，应用改不了。\n")
                .append("media3 仅在 API 26+ 且屏幕不支持 DV 时，才自动改用 H.264/H.265 基础层解码器。\n");
        sb.append('\n');

        sb.append("【系统视频解码器】\n");
        try {
            android.media.MediaCodecList list = new android.media.MediaCodecList(
                    android.media.MediaCodecList.REGULAR_CODECS);
            final String[][] want = {
                    {"video/avc", "H.264"},
                    {"video/hevc", "H.265/HEVC"},
                    {"video/x-vnd.on2.vp9", "VP9"},
                    {"video/av01", "AV1"},
                    {"video/dolby-vision", "Dolby Vision"},
                    {"video/mpeg2", "MPEG-2"},
                    {"video/mp4v-es", "MPEG-4"},
                    {"video/x-ms-wmv", "VC-1/WMV"},
            };
            for (String[] w : want) {
                int won = 0;
                String firstName = null;
                for (android.media.MediaCodecInfo ci : list.getCodecInfos()) {
                    if (ci == null || ci.isEncoder()) continue;
                    boolean has = false;
                    try {
                        for (String ty : ci.getSupportedTypes()) {
                            if (w[0].equalsIgnoreCase(ty)) {
                                has = true;
                                break;
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                    if (has) {
                        won++;
                        if (firstName == null) firstName = ci.getName();
                    }
                }
                sb.append(w[1]).append(": ").append(won == 0 ? "无解码器" :
                        (won + " 个 · 如 " + firstName)).append('\n');
            }
        } catch (Throwable e) {
            sb.append("枚举失败: ").append(e).append('\n');
        }
        sb.append('\n');

        sb.append("【字幕】\n");
        sb.append("解析支持: ASS/SSA(text/x-ssa) · SRT · WebVTT · TTML · PGS · VobSub · DVB · CEA608/708\n");
        sb.append("说明：MKV 内嵌 S_TEXT/ASS 会映射成 text/x-ssa，本 fork 的 SsaParser 能解析\n")
                .append("      文字/颜色/\\an 对齐/\\pos 位置/字号；\\move、\\k 卡拉OK、\\clip、\n")
                .append("      \\t 动画、\\p 绘图这些复杂特效会被丢弃（media3-ui 的 SubtitleView 不渲染）。\n");
        sb.append("字幕模式: ");
        int sm = Settings.subMode();
        sb.append(sm == Settings.SUB_MODE_OFF ? "关"
                : (sm == Settings.SUB_MODE_ALWAYS ? "总是开" : "跟随片源")).append('\n');
        sb.append("首选字幕语言: ").append(Settings.subPreferredLang().isEmpty()
                ? "不指定" : Settings.subPreferredLang()).append('\n');
        sb.append("外挂字幕自动加载(本地同目录同名): ")
                .append(Settings.subExternalAuto() ? "开" : "关").append('\n');
        sb.append("手动字幕: ").append(Settings.subManual().isEmpty()
                ? "未设置" : Settings.subManual()).append('\n');
        return sb.toString();
    }

    private static String hdrName(int type) {
        switch (type) {
            case 1: return "Dolby Vision";
            case 2: return "HDR10";
            case 3: return "HLG";
            case 4: return "HDR10+";
            default: return "未知(" + type + ")";
        }
    }

    private static String capsLine(AudioCapabilities caps, String label, int encoding) {
        boolean ok;
        try {
            ok = caps.supportsEncoding(encoding);
        } catch (Throwable e) {
            return label + ": 查询异常\n";
        }
        return label + ": " + (ok ? "支持" : "不支持") + '\n';
    }

    /** 枚举系统里的音频解码器：没有解码器又直通不了，那条音轨就会被静默丢弃。 */
    private static String decoderReport() {
        final String[][] want = {
                {"audio/mp4a-latm", "AAC"},
                {"audio/ac3", "AC-3"},
                {"audio/eac3", "E-AC-3"},
                {"audio/truehd", "TrueHD"},
                {"audio/vnd.dts", "DTS"},
                {"audio/vnd.dts.hd", "DTS-HD"},
                {"audio/ac4", "AC-4"},
        };
        StringBuilder sb = new StringBuilder();
        try {
            android.media.MediaCodecList list =
                    new android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS);
            for (android.media.MediaCodecInfo ci : list.getCodecInfos()) {
                if (ci == null || ci.isEncoder()) continue;
                String[] types;
                try {
                    types = ci.getSupportedTypes();
                } catch (Throwable e) {
                    continue;
                }
                if (types == null) continue;
                for (String t : types) {
                    for (String[] w : want) {
                        if (w[0].equalsIgnoreCase(t)) {
                            sb.append(w[1]).append(" -> ").append(ci.getName()).append('\n');
                        }
                    }
                }
            }
        } catch (Throwable e) {
            return "枚举失败: " + e + '\n';
        }
        if (sb.length() == 0) sb.append("（一个压缩音频解码器都没有 —— 只能靠直通）\n");
        return sb.toString();
    }

    private static String modeName(int mode) {
        if (mode == Settings.AUDIO_MODE_PASSTHROUGH) return "源码直通";
        if (mode == Settings.AUDIO_MODE_PCM) return "强制解码（PCM）";
        return "自动（跟随设备）";
    }

    /** 直通模式的 RenderersFactory：只改了音频 sink 的来源。 */
    public static final class EngineRenderersFactory extends DefaultRenderersFactory {

        @Nullable
        private final AudioCapabilities injected;

        EngineRenderersFactory(Context ctx, @Nullable AudioCapabilities injected,
                               boolean enableDecoderFallback, int decoderPrefer) {
            super(ctx);
            this.injected = injected;
            // 硬解失败要不要回退软解（DefaultRenderersFactory 自带的解码器回退）
            setEnableDecoderFallback(enableDecoderFallback);
            // 解码器优先级：优先软解时改用 PREFER_SOFTWARE（硬解花屏/绿屏时的退路）
            try {
                if (decoderPrefer == Settings.DECODER_PREFER_SW) {
                    setMediaCodecSelector(MediaCodecSelector.PREFER_SOFTWARE);
                    Log.i(TAG, "decoder: 优先软解");
                } else {
                    setMediaCodecSelector(MediaCodecSelector.DEFAULT);
                }
            } catch (Throwable e) {
                Log.w(TAG, "setMediaCodecSelector 失败: " + e);
            }
        }

        /**
         * 音频 sink 的来源。
         *
         * <p><b>为什么必须用无 Context 的 {@code new DefaultAudioSink.Builder()}</b>：
         * 1.10.0 的 {@code DefaultAudioSink.Builder.build()} 里是这么写的 ——</p>
         *
         * <pre>
         *   new AudioTrackAudioOutputProvider.Builder(context)
         *       .setAudioCapabilities(context != null ? null : audioCapabilities)
         * </pre>
         *
         * <p>也就是说：<b>只要构造 Builder 时给了 Context，自定义能力表就被丢掉</b>，
         * 改用 {@code AudioCapabilitiesReceiver} 读到的设备真实能力表；
         * 而 {@code AudioTrackAudioOutputProvider.Builder.setAudioCapabilities} 本身是
         * package-private 的，外部也塞不进去。唯一还能自定义能力表的入口，
         * 就是那个已 deprecated 的无参构造 —— 所以这里必须走它，
         * 并且顺手把 {@code @SuppressWarnings("deprecation")} 挂上。</p>
         *
         * <p>代价：无 Context 时 {@code AudioCapabilitiesReceiver} 不会被创建，
         * 所以 HDMI 热插拔不会自动刷新能力表。直通模式下这反而符合预期
         * （能力表要按用户的设置固定住），换线后重进播放页即可。</p>
         */
        @SuppressWarnings("deprecation")
        @Override
        protected AudioSink buildAudioSink(Context context, boolean enableFloatOutput,
                                           boolean enableAudioOutputPlaybackParams) {
            if (injected == null) {
                // 自动模式：原样交给父类（带 Context，吃设备真实能力表）
                return super.buildAudioSink(context, enableFloatOutput,
                        enableAudioOutputPlaybackParams);
            }
            return new DefaultAudioSink.Builder()
                    .setAudioCapabilities(injected)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
                    .build();
        }
    }
}
