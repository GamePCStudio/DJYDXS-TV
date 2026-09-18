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
                Settings.decoderFallback());
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
        int maxCh = Settings.audioMaxChannels();
        if (maxCh < 2) maxCh = 2;
        return newDeprecatedCaps(enc, maxCh);
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
        b.setMaxAudioChannelCount(Settings.audioMaxChannels());
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
        return sb.toString();
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
                               boolean enableDecoderFallback) {
            super(ctx);
            this.injected = injected;
            // 硬解失败要不要回退软解（DefaultRenderersFactory 自带的解码器回退）
            setEnableDecoderFallback(enableDecoderFallback);
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
