package com.supermov.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.OptIn;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;

import android.graphics.Color;

import androidx.media3.common.Format;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Tracks;
import androidx.media3.ui.CaptionStyleCompat;
import androidx.media3.ui.SubtitleView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 在线播放（ExoPlayer / NEXIO media3 fork，1.10.0 基线）。
 *
 * <p>引擎配置（音频直通/解码、声道数、音轨语言、解码器回退）全部来自
 * {@link PlaybackEngine} —— 它读设置页的选项；本类只负责取流与播控。</p>
 *
 * 取流策略（按用户选定的「直链 + 本地代理 + M3U8 兜底」）：
 *   1. 先在自己网盘的转存目录里定位这部影片（没转存过则自动转存一次）
 *   2. /api/filemetas 取原画 dlink → 交给 LocalProxy（补 UA=netdisk）→ 播放原画
 *   3. 原画失败/卡死 → 换一条新直链重试一次（dlink 只有 8 小时有效期）
 *   4. 仍失败 → 降级 pan.baidu.com/api/streaming 的 M3U8 转码流（上限 1080p），并 seek 回原进度
 *
 * <p>若用户在设置里限了「在线清晰度上限」，第 2/3 步会跳过，直接走第 4 步的转码流。</p>
 */
@OptIn(markerClass = UnstableApi.class)
public class PlayerActivity extends Activity {

    private static final String TAG = "SupeMov";

    private final Handler main = new Handler(Looper.getMainLooper());

    private PlayerView playerView;
    private TextView tvStatus;
    private TextView tvPlayerHint;
    private View boxSeek;
    private ProgressBar pbSeek;
    private ProgressBar pbBuffering;
    private TextView tvSeekTip;
    private TextView tvSeekCur;
    private TextView tvSeekTotal;
    private ExoPlayer player;
    private LocalProxy proxy;

    private String name = "";
    private String shareUrl = "";
    private String sharePwd = "";
    /** 非空 = 播放已下载到本地的文件（不走百度取流） */
    private String localFile = "";

    /**
     * 详情页已经确定了具体文件（多集/多文件时用户手动选的那一集）。
     * 有值时**不再按片名去网盘猜**——否则 20 集的剧永远只会播到同一个文件。
     */
    private long reqFsId = 0;
    private String reqBpath = "";
    private String reqFname = "";

    private BaiduPan.PlayFile playFile;
    private boolean usingM3u8 = false;
    private boolean retriedDlink = false;
    private boolean finishing = false;

    /** 直通失败后已降级成 PCM 解码重播；true 之后不再重复降级 */
    private boolean forcedDecode = false;
    /** 当前正在播的地址 —— 换引擎重播时要按它回去（原画是 LocalProxy 的地址） */
    private String curUrl = "";

    // ---------- 播放器交互（v1.11）----------

    /** 顶部文件名 / 底部按键提示：播放就绪 3 秒后自动隐藏，动遥控器再亮一次 */
    private static final long CHROME_HIDE_DELAY = 3000L;
    /** 中央快进/快退覆盖层的停留时间 */
    private static final long SEEK_OVERLAY_HOLD = 2500L;
    /**
     * 连按时延迟一点才真正 seekTo：一串连按只跳一次。
     * 否则每按一下都发一次 seek，LocalProxy 直链会被反复重连，画面疯狂缓冲。
     *
     * <p>必须大于 {@link #HOLD_ARM_MS}：这样「按住不放」升级成拖动时，
     * 还能把这一次待提交的 seek 取消掉，整段长按只真跳一次（松手那一刻）。</p>
     */
    private static final long SEEK_COMMIT_DELAY = 500L;
    /** 右键（前进）：每按一次前跳 20 秒 */
    private static final long SEEK_STEP_FWD_MS = 20000L;
    /** 左键（后退）：每按一次回退 15 秒 */
    private static final long SEEK_STEP_BACK_MS = 15000L;
    /** 遥控器「快进/快退」专用键的步长 */
    private static final long SEEK_STEP_BIG_MS = 30000L;
    /** 两次按键间隔小于它就算同一轮连按（位移叠加） */
    private static final long SEEK_COMBINE_GAP = 1200L;

    /** 播放真正就绪后才开始倒计时：取流阶段的「正在定位影片…」得让用户看见 */
    private boolean chromeArmable = false;
    private long seekBase = 0;
    private int seekSteps = 0;
    private long seekTarget = -1;
    private boolean seekPending = false;
    private long lastSeekKeyAt = 0;

    // ---------- v1.15：字幕/音轨切换 · 进度记忆 · 长按拖动 ----------

    /** 按住超过它就算「长按」，升级成连续拖动 */
    private static final long HOLD_ARM_MS = 450L;
    /** 拖动时进度条的刷新间隔（慢速段） */
    private static final long DRAG_TICK_MS = 100L;
    /** 从「按下」算起按住到这一刻 -> 拖动提速 */
    private static final long DRAG_BOOST_MS = 900L;
    /** 提速后的刷新间隔：100ms -> 40ms，拖动速度约 ×2.5 */
    private static final long DRAG_TICK_FAST_MS = 40L;
    /** 拖满整片大约需要多少个 tick（慢速段 240 × 100ms ≈ 24 秒；900ms 提速后约 10 秒） */
    private static final long DRAG_TICKS_FULL = 240L;
    /** 拖动时每 tick 的最小步长，免得短片拖起来太黏 */
    private static final long DRAG_MIN_STEP_MS = 1500L;
    /** 每 10 秒落一次播放进度 */
    private static final long PROGRESS_SAVE_INTERVAL = 10000L;
    /** 进度不到这里就不值得记（防止「只看了个片头」把记录写脏） */
    private static final long RESUME_MIN_MS = 15000L;
    /** 离结尾这么近就当看完了，直接清记录 */
    private static final long RESUME_TAIL_MS = 20000L;
    /** 字幕字号现在由设置页决定（{@link Settings#subFraction()}），缺省仍是 0.066 屏高 */

    /** 轨道选择框的条目字号：TV 上默认字号太大，一屏放不下几条轨道。 */
    private static final float DIALOG_ITEM_SP = 13f;

    /** 长按拖动中 */
    private boolean dragging = false;
    private boolean dragBackward = false;
    private long dragPos = 0;
    /** 上一次左右键的方向，长按成立时沿用它 */
    private boolean lastSeekBackward = false;
    /**
     * 本次左右键「按下」的时刻。提速阈值从按下那一刻算起，不能从 {@code beginDrag} 算起
     * —— 那样 450ms 的拖动起步延迟会被叠加进去，用户按住 900ms 时其实还没提速。
     */
    private long holdStartAt = 0;
    /** 记忆的播放进度；-1 = 还没查过 */
    private long resumePos = -1;
    /** 是否要在出画后提示「继续上次进度」 */
    private boolean resumeNotice = false;
    /** 顶部状态栏是否已经切到「常态 = 文件名」（首次就绪时切一次，之后只管保持） */
    private boolean titleSettled = false;

    /** 按住不放 -> 升级成连续拖动 */
    private final Runnable holdArm = new Runnable() {
        @Override
        public void run() {
            beginDrag(lastSeekBackward);
        }
    };

    /** 拖动中：只动进度条，不真 seek；松手才落点 */
    private final Runnable dragTick = new Runnable() {
        @Override
        public void run() {
            if (!dragging || player == null) return;
            long dur = player.getDuration();
            if (dur == C.TIME_UNSET || dur <= 0) {
                endDrag(false);
                return;
            }
            long stepMs = dur / DRAG_TICKS_FULL;
            if (stepMs < DRAG_MIN_STEP_MS) stepMs = DRAG_MIN_STEP_MS;
            dragPos += dragBackward ? -stepMs : stepMs;
            if (dragPos < 0) dragPos = 0;
            if (dragPos > dur) dragPos = dur;
            showSeekOverlay(dragBackward ? "\u25c0\u25c0 \u5feb\u9000\u4e2d"
                    : "\u5feb\u8fdb\u4e2d \u25b6\u25b6", dragPos);
            // 按住满 DRAG_BOOST_MS 后提速：tick 间隔由 100ms 收紧到 40ms（步长不变 -> 速度约 ×2.5）
            long held = android.os.SystemClock.uptimeMillis() - holdStartAt;
            main.postDelayed(this, held >= DRAG_BOOST_MS ? DRAG_TICK_FAST_MS : DRAG_TICK_MS);
        }
    };

    /** 定时落盘播放进度 */
    private final Runnable saveTick = new Runnable() {
        @Override
        public void run() {
            saveProgressNow();
            main.postDelayed(this, PROGRESS_SAVE_INTERVAL);
        }
    };


    private final Runnable hideChrome = new Runnable() {
        @Override
        public void run() {
            fadeOut(tvStatus);
            fadeOut(tvPlayerHint);
        }
    };

    private final Runnable hideSeekOverlay = new Runnable() {
        @Override
        public void run() {
            fadeOut(boxSeek);
        }
    };

    /** 连按结束后才真正跳转 */
    private final Runnable commitSeek = new Runnable() {
        @Override
        public void run() {
            if (player == null || seekTarget < 0) return;
            seekPending = false;
            player.seekTo(seekTarget);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_player);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        playerView = findViewById(R.id.playerView);
        tvStatus = findViewById(R.id.tvPlayerStatus);
        tvPlayerHint = findViewById(R.id.tvPlayerHint);
        boxSeek = findViewById(R.id.boxPlayerSeek);
        pbSeek = findViewById(R.id.pbPlayerSeek);
        pbBuffering = findViewById(R.id.pbPlayerBuffering);
        tvSeekTip = findViewById(R.id.tvPlayerSeekTip);
        tvSeekCur = findViewById(R.id.tvPlayerSeekCur);
        tvSeekTotal = findViewById(R.id.tvPlayerSeekTotal);

        // 播放器 UI 全部自绘：关掉 media3 自带的控制条。
        // 它在 TV 上会和左右键抢事件（方向键变成移动焦点而不是快进），
        // 关掉后按键行为完全由 dispatchKeyEvent 接管，交互对齐 VLC。
        playerView.setUseController(false);

        name = nz(getIntent().getStringExtra("name"));
        shareUrl = nz(getIntent().getStringExtra("url"));
        sharePwd = nz(getIntent().getStringExtra("pwd"));
        localFile = nz(getIntent().getStringExtra("file"));
        reqFsId = getIntent().getLongExtra("fsId", 0);
        reqBpath = nz(getIntent().getStringExtra("bpath"));
        reqFname = nz(getIntent().getStringExtra("fname"));

        // 画面比例这类「一次性生效」的视频设置，先就位再建播放器
        applyVideoSettings();

        buildPlayer();

        // 每 10 秒落一次进度（另外在暂停 / 退出 / 播完时也会落）
        main.postDelayed(saveTick, PROGRESS_SAVE_INTERVAL);

        if (!localFile.isEmpty()) {
            playLocalFile();
        } else {
            prepareAndPlay();
        }
    }

    /**
     * 建播放器。
     *
     * <p>抽成方法是因为「源码直通失败」时要整个换掉它：音频能力表是建 AudioSink 时的快照，
     * 改不掉，只能重建。见 {@link #rebuildWithDecode()}。</p>
     */
    private void buildPlayer() {
        player = new ExoPlayer.Builder(this)
                // 音频 sink 的来源由 PlaybackEngine 决定（自动 / 直通 / 解码）
                .setRenderersFactory(PlaybackEngine.renderersFactory(this, forcedDecode))
                .setMediaSourceFactory(new DefaultMediaSourceFactory(dataSourceFactory()))
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        // 必须 MOVIE：设成 MUSIC / SPEECH 时，部分盒子的 HAL 会直接拒绝建直通轨
                        .setContentType(C.CONTENT_TYPE_MOVIE)
                        .build(), true)
                .setHandleAudioBecomingNoisy(true)
                .build();
        // 初始轨道偏好：最大声道数 / 首选音轨语言 / 直通编码优先。
        // 从播放器「现有」参数 buildUpon 叠加 —— 别新建 Builder，那会丢掉它自带的默认值
        // （例如字幕首选语言 = 系统语言），字幕自动选择会跟着变味。
        try {
            player.setTrackSelectionParameters(
                    PlaybackEngine.withAudioPreferences(
                            player.getTrackSelectionParameters().buildUpon()).build());
        } catch (Throwable e) {
            Log.d(TAG, "setTrackSelectionParameters err " + e);
        }
        player.addListener(new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException error) {
                handlePlaybackError(error);
            }

            @Override
            public void onPlaybackStateChanged(int state) {
                if (pbBuffering != null) {
                    pbBuffering.setVisibility(
                            state == Player.STATE_BUFFERING ? View.VISIBLE : View.GONE);
                }
                if (state == Player.STATE_READY) {
                    if (resumeNotice) {
                        resumeNotice = false;
                        // 用记录的位置本身提示，不依赖 seek 之后 getCurrentPosition 的时点
                        showSeekOverlay("\u7ee7\u7eed\u4e0a\u6b21\u8fdb\u5ea6 " + fmtTime(resumePos),
                                resumePos);
                    }
                    if (!chromeArmable) {
                        chromeArmable = true;
                        main.removeCallbacks(hideChrome);
                        main.postDelayed(hideChrome, CHROME_HIDE_DELAY);
                    }
                    // 顶部回到「常态 = 片名/文件名」：续播提示只该出现在中央覆盖层里，
                    // 否则用户一动遥控器把顶栏亮出来，看到的还是那句过期的进度话术
                    if (!titleSettled) {
                        titleSettled = true;
                        showTitleBar();
                    }
                } else if (state == Player.STATE_ENDED) {
                    // 看完了：把进度记录清掉，下次从头开始
                    Settings.clearPos(progressKey());
                }
            }
        });
        playerView.setPlayer(player);
        applySubtitleStyle();
    }

    /** 画面比例：取值与 AspectRatioFrameLayout 的 RESIZE_MODE_* 对齐。 */
    private void applyVideoSettings() {
        if (playerView == null) return;
        try {
            playerView.setResizeMode(Settings.videoResize());
        } catch (Throwable e) {
            Log.d(TAG, "setResizeMode err " + e);
        }
    }

    /**
     * 字幕渲染在 PlayerView 的默认布局里（SubtitleView, id=exo_subtitles）。
     * 默认是「无描边白字 + 5.33% 屏高」，电视上远看偏小，这里统一改成黑描边，
     * 比例取设置页的「字幕字号」（缺省 6.6%）。
     */
    private void applySubtitleStyle() {
        try {
            SubtitleView sv = playerView.getSubtitleView();
            if (sv != null) {
                sv.setStyle(new CaptionStyleCompat(Color.WHITE, Color.TRANSPARENT, Color.TRANSPARENT,
                        CaptionStyleCompat.EDGE_TYPE_OUTLINE, Color.BLACK, null));
                sv.setFractionalTextSize(Settings.subFraction());
                sv.setBottomPaddingFraction(0.10f);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 播放已下载到本地的文件（下载队列里「播放本地」进来的）。 */
    private void playLocalFile() {
        java.io.File f = new java.io.File(localFile);
        if (!f.exists() || f.length() <= 0) {
            fail("本地文件不存在或为空：\n" + localFile);
            return;
        }
        // 落在公共存储 / U盘 / NAS 上的文件，没有「所有文件访问」时读得到名字却读不到内容，
        // 表现就是 ExoPlayer 立刻报错 —— 这里先拦一下，给出能操作的解法
        if (!f.canRead()) {
            fail("没有读取该文件的权限：\n" + localFile
                    + "\n请到 设置 → 下载目录 授予「所有文件访问」后再播");
            return;
        }
        status("本地播放：" + f.getName() + "（" + humanSize(f.length()) + "）");
        long start = freshStartPos();
        MediaItem.Builder b = new MediaItem.Builder().setUri(android.net.Uri.fromFile(f));
        player.setMediaItem(b.build());
        player.prepare();
        if (start > 0) player.seekTo(start);
        player.setPlayWhenReady(true);
    }

    /**
     * 交给 ExoPlayer 的数据源工厂（v1.22-local-datasource）。
     *
     * <p>DefaultHttpDataSource 只认 http/https：把 file:// 丢给它，会在 openConnection() 里
     * 把 FileURLConnection 强转成 HttpURLConnection 抛 ClassCastException，
     * 表现为 ERROR_CODE_IO_UNSPECIFIED（「本地文件播放失败：网络连接失败/超时」）。
     * 全片都下载完了、文件权限也正常，却一按播放就报错，就是这里。</p>
     *
     * <p>DefaultDataSource 按 URI 的 scheme 分流：file:// 给 FileDataSource，
     * content:// 给 ContentDataSource，http/https（含 M3U8 分片）才落到下面的 httpFactory。</p>
     */
    private DefaultDataSource.Factory dataSourceFactory() {
        return new DefaultDataSource.Factory(this, httpFactory());
    }

    /** 只负责网络那一路的 HTTP 头：原画走代理不需要它，但 M3U8 的分片请求必须自带 UA=netdisk。 */
    private DefaultHttpDataSource.Factory httpFactory() {
        DefaultHttpDataSource.Factory f = new DefaultHttpDataSource.Factory()
                .setUserAgent("netdisk")
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(25000);
        Map<String, String> headers = new HashMap<>();
        headers.put("Referer", "https://pan.baidu.com/");
        String cookie = CookieStore.cookieFor("https://pan.baidu.com/");
        if (cookie != null && !cookie.isEmpty()) headers.put("Cookie", cookie);
        f.setDefaultRequestProperties(headers);
        return f;
    }

    // ---------- 取流 ----------

    private void prepareAndPlay() {
        if (!CookieStore.hasBaiduLogin()) {
            status("未授权百度网盘，正在打开扫码页…");
            Toast.makeText(this, "请先扫码授权百度网盘", Toast.LENGTH_LONG).show();
            startActivity(new Intent(this, QrActivity.class));
            finish();
            return;
        }

        // 详情页已经选定了具体文件（多集/多文件时用户挑的那一集）-> 直接用，不再按片名去猜。
        // 这条分支必须放在最前面：如果还走 resolvePlayable，20 集的剧永远只会播到同一个文件。
        if (reqFsId > 0 && !reqBpath.isEmpty()) {
            final BaiduPan.PlayFile pf = new BaiduPan.PlayFile();
            pf.ok = true;
            pf.fsId = reqFsId;
            pf.path = reqBpath;
            pf.name = reqFname.isEmpty() ? name : reqFname;
            // 设置里限了清晰度上限 -> 直接走云端转码流，没必要再去取原画直链
            if (Settings.qualityCap() != Settings.QUALITY_ORIGINAL) {
                playFile = pf;
                status("按设置走云端转码流…（" + pf.name + "）");
                startTranscode(Settings.qualityCap());
                return;
            }
            status("正在获取原画直链…（" + pf.name + "）");
            new Thread(() -> {
                try {
                    String d = BaiduPan.dlink(pf.fsId, pf.path);
                    if (d.isEmpty()) {
                        playFile = pf;
                        fallbackM3u8("原画直链获取失败");
                        return;
                    }
                    playFile = pf;
                    proxy = new LocalProxy();
                    proxy.start();
                    final String u = proxy.urlFor(d);
                    status("原画播放：" + pf.name);
                    main.post(() -> startPlay(u, 0, false));
                } catch (Throwable e) {
                    Log.d(TAG, "direct play err " + e);
                    fail("取流异常：" + e.getClass().getSimpleName());
                }
            }, "player-direct").start();
            return;
        }

        status("正在定位影片…（" + name + "）");
        new Thread(() -> {
            try {
                final String dir = Settings.saveDir();
                BaiduPan.PlayFile pf = BaiduPan.resolvePlayable(dir, name);
                if (!pf.ok) {
                    // 没转过存（或目录里没有）-> 自动转存一次，转存功能已具备
                    status("尚未转存，正在转存到 " + dir + " …");
                    BaiduPan.TransferResult tr = BaiduPan.transfer(shareUrl, sharePwd, dir);
                    if (!tr.ok) {
                        fail("转存失败：" + tr.message);
                        return;
                    }
                    Settings.recordTransfer(dir, true);
                    status("转存成功，正在定位文件…");
                    pf = BaiduPan.resolvePlayable(dir, name);
                }
                if (!pf.ok) {
                    fail(pf.message.isEmpty() ? "未能在网盘里定位到可播放文件" : pf.message);
                    return;
                }
                playFile = pf;
                int cap = Settings.qualityCap();
                if (cap != Settings.QUALITY_ORIGINAL) {
                    status("按设置走云端转码流…（" + humanSize(pf.size) + "）");
                    main.post(() -> startTranscode(cap));
                    return;
                }
                status("正在获取原画直链…（" + humanSize(pf.size) + "）");
                String dlink = BaiduPan.dlink(pf.fsId, pf.path);
                if (dlink.isEmpty()) {
                    fallbackM3u8("原画直链获取失败");
                    return;
                }
                proxy = new LocalProxy();
                proxy.start();
                status("原画播放：" + pf.name);
                main.post(() -> startPlay(proxy.urlFor(dlink), 0, false));
            } catch (Throwable e) {
                Log.d(TAG, "prepare err " + e);
                fail("取流异常：" + e.getClass().getSimpleName());
            }
        }, "player-fetch").start();
    }

    /**
     * 全新开播时「该从哪儿开始」：有记忆的进度就用它，否则 0。
     *
     * <p>只在这里读一次并缓存到 resumePos —— 中途换链/降级时要保住手上这条进度，
     * 不能再被旧记忆覆盖回去。</p>
     */
    private long freshStartPos() {
        if (resumePos < 0) resumePos = loadResumePos();
        if (resumePos > 0) {
            resumeNotice = true;
            status("\u6b63\u5728\u4ece\u4e0a\u6b21\u7684\u8fdb\u5ea6\u7ee7\u7eed\u2026\uff08"
                    + fmtTime(resumePos) + "\uff09");
            return resumePos;
        }
        return 0;
    }

    private void startPlay(String url, long pos, boolean m3u8) {
        if (finishing || player == null) return;
        curUrl = url == null ? "" : url;
        // pos > 0 = 换链/降级时保住手上这条进度；pos <= 0 = 全新开播，这时才去读记忆的进度
        long start = (pos > 0) ? pos : freshStartPos();
        MediaItem.Builder b = new MediaItem.Builder().setUri(url);
        if (m3u8) b.setMimeType(MimeTypes.APPLICATION_M3U8);
        player.setMediaItem(b.build());
        player.prepare();
        if (start > 0) player.seekTo(start);
        player.setPlayWhenReady(true);
    }

    /**
     * 直接以云端转码流起播（设置里限了「在线清晰度上限」时走这条）。
     *
     * <p>转码流走 pan.baidu.com/api/streaming，清晰度由 type 决定，不需要原画直链，
     * 也不受「原画直链只有 8 小时有效期」的影响。调用前必须先把 {@link #playFile} 备好。</p>
     */
    private void startTranscode(int cap) {
        if (finishing) return;
        final BaiduPan.PlayFile pf = playFile;
        final String code = transcodeCode(cap);
        if (pf == null || pf.path == null || pf.path.isEmpty() || code.isEmpty()) {
            fail("清晰度设置无效，无法起播转码流");
            return;
        }
        usingM3u8 = true;
        new Thread(() -> {
            String u = BaiduPan.streamingUrl(pf.path, code);
            final String url = u == null ? "" : u;
            main.post(() -> {
                if (finishing) return;
                if (url.isEmpty()) {
                    fail("转码流不可用（可能受会员权限限制或接口已变动）");
                    return;
                }
                status("转码流播放（" + codeLabel(code) + "）");
                startPlay(url, 0, true);
            });
        }, "player-transcode").start();
    }

    /** 清晰度档位 -> 百度转码类型码；「原画优先」档不产生码（走原画直链）。 */
    private static String transcodeCode(int cap) {
        if (cap == Settings.QUALITY_1080) return "M3U8_AUTO_1080";
        if (cap == Settings.QUALITY_720) return "M3U8_AUTO_720";
        return "";
    }

    private static String codeLabel(String code) {
        if ("M3U8_AUTO_1080".equals(code)) return "\u2264 1080p";
        if ("M3U8_AUTO_720".equals(code)) return "\u2264 720p";
        if ("M3U8_AUTO_480".equals(code)) return "\u2264 480p";
        return code;
    }

    private void handlePlaybackError(PlaybackException error) {
        if (finishing) return;
        Log.d(TAG, "playback error code=" + error.getErrorCodeName() + " msg=" + error.getMessage());
        // 源码直通建不出音频轨（盒子/功放真不支持这个格式）-> 换解码重播，
        // 别让用户对着一句英文错误码发呆。只在直通模式且没关回退时才做。
        final boolean audioFail = isAudioTrackFailure(error);
        if (audioFail) {
            // 留给设置页的「音频诊断」，这类问题不该再让人去抓 logcat
            PlaybackEngine.noteAudioError(error.getErrorCodeName() + " / " + describe(error));
        }
        if (audioFail && PlaybackEngine.shouldFallbackToDecode(forcedDecode)) {
            rebuildWithDecode();
            return;
        }
        // 音频问题但回退被关掉（或已经回退过一次）：说清楚是音频的锅，
        // 别再报「原画播放失败」把人引到网络/清晰度上去
        if (audioFail) {
            fail("音频输出失败（已关掉自动回退）：" + describe(error));
            return;
        }
        final long pos = player.getCurrentPosition();
        // 本地文件没有「换直链」和「降级转码流」两条路：这是文件读取或解码的问题，
        // 老代码会掉进 fallbackM3u8 里报一句莫名其妙的「原画播放失败」，让人无从下手
        if (!localFile.isEmpty()) {
            fail("本地文件播放失败：" + describe(error) + localHint(error));
            return;
        }
        if (!usingM3u8) {
            // 第一次失败：直链可能已过期（8h）或被风控掐断 -> 换一条新链再试
            if (!retriedDlink && playFile != null && proxy != null) {
                retriedDlink = true;
                status("播放中断，正在重新获取直链…");
                new Thread(() -> {
                    String d = BaiduPan.dlink(playFile.fsId, playFile.path);
                    if (d != null && !d.isEmpty()) {
                        main.post(() -> {
                            status("已换新直链，继续播放");
                            startPlay(proxy.urlFor(d), pos, false);
                        });
                    } else {
                        fallbackM3u8("重新取链失败");
                    }
                }, "player-relink").start();
                return;
            }
            fallbackM3u8("原画播放失败");
            return;
        }
        fail("播放失败：" + describe(error));
    }

    /**
     * 是不是「音频输出轨」相关的失败。
     *
     * <p>media3 的错误码在 1.11 起只能拿名字（{@code getErrorCodeName()}）；6001/6002
     * 分别对应 {@code ERROR_CODE_AUDIO_TRACK_INIT_FAILED} / {@code ..._WRITE_FAILED}，
     * 名字里都带 {@code AUDIO_TRACK}，这里按子串判即可。</p>
     */
    private boolean isAudioTrackFailure(PlaybackException e) {
        String code = "";
        try {
            code = e.getErrorCodeName();
        } catch (Throwable ignored) {
        }
        if (code != null && code.contains("AUDIO_TRACK")) return true;
        // 还有一类更隐蔽的：直通路径上 AudioSink 内部抛的异常
        // （AudioTrackAudioOutputProvider.getAudioTrackMinBufferSize 里的 checkState、
        //  AudioTrack$Builder.build() 抛的 "Cannot create AudioTrack"），
        // media3 会把它包成 ExoPlaybackException: Unexpected runtime error —— 错误码名
        // ERROR_CODE_FAILED_RUNTIME_CHECK 里既没有 AUDIO_TRACK、也看不出是音频。
        // 不认出来的话，音频问题会被当成「原画播放失败」丢给百度云端转码，而转码对 mkv
        // 常常不可用 —— 用户看到的就是「原画要转码，转码也播不了」，彻底没路走。
        // 只能顺着 cause 链看堆栈落在哪个包（这套判定与 media3 版本无关）。
        Throwable t = e;
        for (int depth = 0; t != null && depth < 12; depth++) {
            String m = t.getMessage();
            if (m != null && m.contains("Cannot create AudioTrack")) return true;
            StackTraceElement[] st = t.getStackTrace();
            if (st != null) {
                for (StackTraceElement f : st) {
                    String cn = f.getClassName();
                    if (cn != null && cn.startsWith("androidx.media3.exoplayer.audio.")) return true;
                }
            }
            Throwable next = t.getCause();
            t = (next == t) ? null : next;
        }
        return false;
    }

    /**
     * 源码直通失败 → 换 PCM 解码重播。
     *
     * <p>为什么要把整个 Player 换掉：音频能力表是建 AudioSink 时读进去的快照，
     * 播到一半改不掉 —— 只能新建一个 player（这次带 {@code forcedDecode=true}），
     * 再回到刚才的位置继续。</p>
     */
    private void rebuildWithDecode() {
        if (finishing || forcedDecode) return;
        String url = curUrl;
        if (url == null || url.isEmpty()) {
            fail("音频直通失败，且没有可重播的地址");
            return;
        }
        long pos = 0;
        try {
            if (player != null) pos = player.getCurrentPosition();
        } catch (Throwable ignored) {
        }
        final boolean m3u8 = usingM3u8;
        forcedDecode = true;
        status("音频直通失败，已自动改用解码输出重播");
        try {
            if (player != null) {
                playerView.setPlayer(null);
                player.release();
                player = null;
            }
        } catch (Throwable ignored) {
        }
        buildPlayer();
        startPlay(url, pos, m3u8);
    }

    /** 降级到百度云端转码 M3U8（720p 优先，再退 480p），并保留当前进度。 */
    private void fallbackM3u8(String why) {
        if (finishing) return;
        if (usingM3u8 || playFile == null) {
            main.post(() -> fail(why));
            return;
        }
        usingM3u8 = true;
        status(why + "，正在切换到转码流…");
        new Thread(() -> {
            String u = "";
            // 用户在设置里限了清晰度就按他的来（如 ≤1080p），否则缺省 720p -> 480p 逐级退
            String preferred = transcodeCode(Settings.qualityCap());
            if (!preferred.isEmpty()) u = BaiduPan.streamingUrl(playFile.path, preferred);
            if (u == null || u.isEmpty()) u = BaiduPan.streamingUrl(playFile.path, "M3U8_AUTO_720");
            if (u == null || u.isEmpty()) u = BaiduPan.streamingUrl(playFile.path, "M3U8_AUTO_480");
            final String url = u == null ? "" : u;
            main.post(() -> {
                if (finishing) return;
                if (url.isEmpty()) {
                    fail("转码流不可用：百度没返回可播地址（mkv 转码常受限，已记日志）");
                    return;
                }
                long pos = player.getCurrentPosition();
                status("已降级为转码流（M3U8，清晰度上限 1080p）");
                startPlay(url, pos, true);
            });
        }, "player-m3u8").start();
    }

    /**
     * 把 media3 的错误码翻成中文提示。
     *
     * 注意：media3 1.11.0 起 PlaybackException 已经**移除**了 getErrorCode()，
     * 只能拿 getErrorCodeName()（形如 "ERROR_CODE_IO_BAD_HTTP_STATUS"）做字符串判断，
     * 所以这里不要写 switch(错误码 int)。
     */
    private String describe(PlaybackException e) {
        String code = "";
        try {
            code = e.getErrorCodeName();
        } catch (Throwable ignored) {
        }
        if (code == null) code = "";
        if (code.contains("IO_BAD_HTTP_STATUS") || code.contains("IO_NO_PERMISSION")) {
            return "服务器拒绝访问（HTTP 错误，可能是直链过期或风控）";
        }
        if (code.contains("NETWORK_CONNECTION_FAILED") || code.contains("NETWORK_CONNECTION_TIMEOUT")
                || code.contains("IO_UNSPECIFIED") || code.contains("TIMEOUT")) {
            // 本地文件根本没走网络：报「网络连接失败」只会把人带偏（v1.22-local-datasource）
            return localFile.isEmpty()
                    ? "网络连接失败/超时"
                    : "本地文件读取失败（文件损坏或所在存储不可读）";
        }
        if (code.contains("DECODER_INIT_FAILED") || code.contains("DECODING_FAILED")
                || code.contains("DECODING_FORMAT_UNSUPPORTED")
                || code.contains("DECODING_FORMAT_EXCEEDS_CAPABILITIES")) {
            return "解码失败（电视盒子不支持该编码，如部分 4K/AV1）";
        }
        if (code.contains("PARSING_CONTAINER_MALFORMED") || code.contains("PARSING_MANIFEST_MALFORMED")) {
            return "文件/清单解析失败";
        }
        if (code.contains("IO_FILE_NOT_FOUND")) {
            return "文件不存在（直链可能已失效）";
        }
        if (!code.isEmpty()) return code;
        String msg = e.getMessage();
        return (msg == null || msg.isEmpty()) ? "播放错误" : msg;
    }

    /** 本地文件失败时的补充排查提示（权限 / 编码），没有可用提示就返回空串。 */
    private String localHint(PlaybackException e) {
        String code = "";
        try {
            code = e.getErrorCodeName();
        } catch (Throwable ignored) {
        }
        if (code == null) code = "";
        if (code.contains("IO_NO_PERMISSION") || code.contains("IO_FILE_NOT_FOUND")) {
            return "\n请到 设置 → 下载目录 授予「所有文件访问」后再试";
        }
        if (code.contains("DECODER") || code.contains("DECODING")) {
            return "\n这台盒子解不了该编码，可换别的版本再下载";
        }
        if (code.contains("IO_UNSPECIFIED")) {
            return "\n文件可能没下完整或已被挪走，删除后重新下载一次再试";
        }
        return "";
    }

    // ---------- 播控（v1.11：VLC 式左右键快退/快进 + 3 秒自动隐藏）----------

    /**
     * 左右键 / 快进快退键 = 时间跳转，并在屏幕中央弹一条带进度条的提示。
     *
     * 连按（间隔小于 1.2 秒）会在同一基准位置上叠加：按 3 次 = 位移 30 秒。
     * 真正的 seekTo 延迟 500ms 提交（SEEK_COMMIT_DELAY），所以一串连按只跳一次；
     * 长按拖动则整个手势只在松手时提交一次。
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        boolean isRewind = (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_MEDIA_REWIND);
        boolean isForward = (code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD);
        boolean isOk = (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER || code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
        boolean isSubs = (code == KeyEvent.KEYCODE_DPAD_UP);
        boolean isAudio = (code == KeyEvent.KEYCODE_DPAD_DOWN);

        if (!isRewind && !isForward && !isOk && !isSubs && !isAudio) {
            // 其它键（返回/音量…）照旧透传，顺手把顶部底部提示亮 3 秒
            if (e.getAction() == KeyEvent.ACTION_DOWN) pokeChrome();
            return super.dispatchKeyEvent(e);
        }

        // 上/下 = 列出字幕轨 / 音轨让用户挑（长按产生的重复事件忽略：按一下弹一次）
        if (isSubs || isAudio) {
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
                showTrackPicker(isSubs ? C.TRACK_TYPE_TEXT : C.TRACK_TYPE_AUDIO);
            }
            return true;
        }

        // 抬手：如果刚才在长按拖动，这里才真正落到拖到的位置
        if (e.getAction() == KeyEvent.ACTION_UP) {
            main.removeCallbacks(holdArm);
            if (dragging) endDrag(true);
            return true;                 // 吞掉 UP，免得再响一次按键音
        }
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;

        if (e.getRepeatCount() > 0) return true;   // 长按的重复事件：位移在首次按下时已算过
        if (dragging) {
            // 万一手上的 UP 事件丢了（切窗口/弹框），任意一次新的按下也能把它收掉
            endDrag(true);
            return true;
        }

        if (isOk) {
            togglePlayPause();
            return true;
        }

        long step;
        if (code == KeyEvent.KEYCODE_MEDIA_REWIND || code == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) {
            step = SEEK_STEP_BIG_MS;                                   // 遥控器专用快进/快退键
        } else {
            step = isRewind ? SEEK_STEP_BACK_MS : SEEK_STEP_FWD_MS;    // 左退 15 秒 / 右进 20 秒
        }
        holdStartAt = android.os.SystemClock.uptimeMillis();           // 提速阈值从「按下」算起
        doSeek(step, isRewind);
        // 按住不放超过 450ms -> 升级成连续拖动（松手才落点）；按住满 900ms 再提速
        main.removeCallbacks(holdArm);
        main.postDelayed(holdArm, HOLD_ARM_MS);
        return true;
    }

    // ---------- 上/下键：选择字幕轨与音轨 ----------

    /**
     * 弹出轨道选择框：把这一类的候选轨道全列出来让用户挑。
     *
     * <p>为什么不做「按一下切一条」：轨道顺序由容器里 TrackGroup 的排列决定，
     * 用户根本不知道要按几下才轮到「国语 5.1」，按过头还得绕一整圈。
     * 列出名字 + 标出当前选中项，一次到位。字幕多给一条「关闭字幕」。</p>
     */
    private void showTrackPicker(int type) {
        if (player == null) return;
        boolean isText = (type == C.TRACK_TYPE_TEXT);
        String what = isText ? "\u5b57\u5e55" : "\u97f3\u8f68";

        // 弹框前先把左右键那个「还没成立的长按」收掉：否则用户是在长按途中按的上键，
        // 松手事件会被对话框吃掉、回不到 Activity，dragTick 会一直空转下去。
        main.removeCallbacks(holdArm);
        if (dragging) endDrag(true);

        if (player.getPlaybackState() == Player.STATE_IDLE) {
            showSeekOverlay("\u89c6\u9891\u8fd8\u6ca1\u51c6\u5907\u597d");
            return;
        }

        // 把「受支持」的候选轨道摊平成平行数组
        List<Tracks.Group> groups = new ArrayList<>();
        List<Integer> idxs = new ArrayList<>();
        for (Tracks.Group g : player.getCurrentTracks().getGroups()) {
            if (g.getType() != type) continue;
            for (int i = 0; i < g.length; i++) {
                if (!g.isTrackSupported(i)) continue;
                groups.add(g);
                idxs.add(i);
            }
        }

        if (groups.isEmpty()) {
            // 还在缓冲时轨道表可能尚未解析出来，这时报「没有字幕」是误报
            if (player.getPlaybackState() == Player.STATE_BUFFERING) {
                showSeekOverlay("\u6b63\u5728\u89e3\u6790\u8f68\u9053\uff0c\u7a0d\u540e\u518d\u8bd5 \u00b7 " + what);
                return;
            }
            showSeekOverlay(isText ? "\u5f53\u524d\u5f71\u7247\u6ca1\u6709\u5b57\u5e55\u8f68"
                    : "\u5f53\u524d\u5f71\u7247\u6ca1\u6709\u97f3\u8f68");
            return;
        }

        // 当前选中的是哪一条（字幕全关时 = -1）
        int cur = -1;
        for (int i = 0; i < groups.size(); i++) {
            if (groups.get(i).isTrackSelected(idxs.get(i).intValue())) {
                cur = i;
                break;
            }
        }

        // 这里用 setItems 而不是 setSingleChoiceItems：后者的条目是 CheckedTextView，
        // 给它改 ellipsize / maxLines 在部分 ROM 上会让条目文字直接不显示。
        // 选中项自己点一个实心圆，效果一样，长轨名还能换两行。
        final List<String> items = new ArrayList<>();
        final List<Tracks.Group> itemGroup = new ArrayList<>();
        final List<Integer> itemIdx = new ArrayList<>();
        final List<Boolean> itemOff = new ArrayList<>();

        if (isText) {
            items.add(mark(cur < 0) + "\u5173\u95ed\u5b57\u5e55");
            itemGroup.add(null);
            itemIdx.add(0);
            itemOff.add(Boolean.TRUE);
        }
        for (int i = 0; i < groups.size(); i++) {
            items.add(mark(i == cur) + trackLabel(groups.get(i), idxs.get(i).intValue(), type));
            itemGroup.add(groups.get(i));
            itemIdx.add(idxs.get(i));
            itemOff.add(Boolean.FALSE);
        }

        AlertDialog dlg = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog)
                .setTitle("\u9009\u62e9" + what)
                .setItems(items.toArray(new String[0]), (d, which) ->
                        applyTrack(type, itemGroup.get(which), itemIdx.get(which).intValue(),
                                itemOff.get(which).booleanValue()))
                .setNegativeButton("\u53d6\u6d88", null)
                .create();
        dlg.show();
        tuneDialog(dlg);
    }

    /** 列表里的选中标记：实心圆 / 空心圆，两个字符等宽，列不会参差。 */
    private String mark(boolean on) {
        return on ? "\u25cf " : "\u25cb ";
    }

    /** 真正改选轨：走 {@link TrackSelectionParameters}，不重建 TrackSelector，改完立刻生效。 */
    private void applyTrack(int type, Tracks.Group g, int idx, boolean off) {
        if (player == null) return;
        boolean isText = (type == C.TRACK_TYPE_TEXT);
        TrackSelectionParameters.Builder b = player.getTrackSelectionParameters().buildUpon();
        b.clearOverridesOfType(type);
        String label;
        if (off || g == null) {
            b.setTrackTypeDisabled(type, true);
            label = "\u5173\u95ed";
        } else {
            b.setTrackTypeDisabled(type, false);
            b.setOverrideForType(new TrackSelectionOverride(g.getMediaTrackGroup(), idx));
            label = trackLabel(g, idx, type);
        }
        try {
            player.setTrackSelectionParameters(b.build());
        } catch (Throwable e) {
            Log.d(TAG, "setTrackSelectionParameters err " + e);
            toast("\u5207\u6362\u5931\u8d25");
            return;
        }
        showSeekOverlay((isText ? "\u5b57\u5e55\uff1a" : "\u97f3\u8f68\uff1a") + label);
    }

    /**
     * 轨道的展示名。
     *
     * <p>容器给的语言字段多是 ISO 码（{@code en} / {@code zh} / {@code cn}），直接显示等于
     * 让用户读代码。这里统一翻成中文：{@code en}→英语、{@code zh}/{@code cn}→中文，其余
     * 语种同理。已经自带可读 label 的（如「国语 5.1」「SDH」）保留原样，只在它本身没有
     * 中文时补一个中文语种，免得把好好的名字覆盖掉。</p>
     */
    private String trackLabel(Tracks.Group g, int idx, int type) {
        Format f = g.getTrackFormat(idx);
        String label = (f == null || f.label == null) ? "" : f.label.trim();
        String lang = (f == null || f.language == null) ? "" : f.language.trim();

        String s = zhLang(label);                       // label 本身就是语言标识
        if (s.isEmpty() && !label.isEmpty()) {
            String zh = zhLang(lang);
            s = (zh.isEmpty() || hasHan(label)) ? label : (label + " \u00b7 " + zh);
        }
        if (s.isEmpty()) {
            String zh = zhLang(lang);
            s = zh.isEmpty()
                    ? ((type == C.TRACK_TYPE_TEXT ? "\u5b57\u5e55 " : "\u97f3\u8f68 ") + (idx + 1))
                    : zh;
        }
        if (f != null && type == C.TRACK_TYPE_AUDIO && f.channelCount > 0) {
            s = s + " \u00b7 " + f.channelCount + "ch";
        }
        if (s.length() > 26) s = s.substring(0, 26) + "\u2026";
        return s;
    }

    /**
     * 语言标识 → 中文名；认不出来返回空串（调用方好据此判断「没翻出来」）。
     *
     * <p>三种写法都收：ISO 639-1 两字母（{@code en}）、ISO 639-2/B 三字母（{@code eng}）、
     * 英文全名（{@code English}）；带地区的组合（{@code zh-Hans} / {@code pt-BR}）先按整串查，
     * 查不到再退到主语言码。大小写与 {@code _}/{@code -} 分隔符都做了归一。</p>
     */
    private static String zhLang(String raw) {
        if (raw == null) return "";
        String k = raw.trim().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
        if (k.isEmpty()) return "";
        String zh = LANG_ZH.get(k);
        if (zh != null) return zh;
        int cut = k.indexOf('-');
        if (cut > 0) {
            zh = LANG_ZH.get(k.substring(0, cut));
            if (zh != null) return zh;
        }
        // 形如 "english (sdh)" / "chinese simplified"：取首段再查一次
        cut = k.indexOf(' ');
        if (cut > 0) {
            zh = LANG_ZH.get(k.substring(0, cut));
            if (zh != null) return zh;
        }
        return "";
    }

    /** 字串里是否已经有汉字（有的话就不用再补中文语种名了）。 */
    private static boolean hasHan(String s) {
        if (s == null) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) return true;
        }
        return false;
    }

    /** 语言标识 → 中文名对照表（常见语种 + 简繁/粤语变体）。 */
    private static final Map<String, String> LANG_ZH = langZhMap();

    private static Map<String, String> langZhMap() {
        Map<String, String> m = new HashMap<>();
        // 中文：先放通用码，再放繁体/粤语这类更具体的变体（zhLang 先查整串，命中的就是它）
        for (String k : new String[]{"zh", "cn", "chi", "zho", "chs", "chinese", "zh-cn", "zh-sg",
                "zh-hans", "zh-hans-cn", "chinese simplified", "chinese (simplified)", "sc"}) {
            m.put(k, "中文");
        }
        for (String k : new String[]{"zh-tw", "zh-hk", "zh-mo", "zh-hant", "cht", "chinese traditional",
                "chinese (traditional)", "tc"}) {
            m.put(k, "繁体中文");
        }
        m.put("yue", "粤语");
        m.put("cantonese", "粤语");
        m.put("cmn", "国语");
        m.put("mandarin", "国语");
        // 常见语种
        for (String k : new String[]{"en", "eng", "english", "en-us", "en-gb"}) m.put(k, "英语");
        for (String k : new String[]{"ja", "jp", "jpn", "japanese"}) m.put(k, "日语");
        for (String k : new String[]{"ko", "kr", "kor", "korean"}) m.put(k, "韩语");
        for (String k : new String[]{"fr", "fra", "fre", "french"}) m.put(k, "法语");
        for (String k : new String[]{"de", "deu", "ger", "german"}) m.put(k, "德语");
        for (String k : new String[]{"es", "spa", "spanish"}) m.put(k, "西班牙语");
        for (String k : new String[]{"it", "ita", "italian"}) m.put(k, "意大利语");
        for (String k : new String[]{"ru", "rus", "russian"}) m.put(k, "俄语");
        for (String k : new String[]{"pt", "por", "portuguese"}) m.put(k, "葡萄牙语");
        for (String k : new String[]{"ar", "ara", "arabic"}) m.put(k, "阿拉伯语");
        for (String k : new String[]{"th", "tha", "thai"}) m.put(k, "泰语");
        for (String k : new String[]{"vi", "vie", "vietnamese"}) m.put(k, "越南语");
        for (String k : new String[]{"id", "ind", "indonesian"}) m.put(k, "印尼语");
        for (String k : new String[]{"ms", "msa", "may", "malay"}) m.put(k, "马来语");
        for (String k : new String[]{"hi", "hin", "hindi"}) m.put(k, "印地语");
        for (String k : new String[]{"tr", "tur", "turkish"}) m.put(k, "土耳其语");
        for (String k : new String[]{"pl", "pol", "polish"}) m.put(k, "波兰语");
        for (String k : new String[]{"nl", "nld", "dut", "dutch"}) m.put(k, "荷兰语");
        for (String k : new String[]{"sv", "swe", "swedish"}) m.put(k, "瑞典语");
        for (String k : new String[]{"da", "dan", "danish"}) m.put(k, "丹麦语");
        for (String k : new String[]{"no", "nor", "norwegian"}) m.put(k, "挪威语");
        for (String k : new String[]{"fi", "fin", "finnish"}) m.put(k, "芬兰语");
        for (String k : new String[]{"el", "gre", "ell", "greek"}) m.put(k, "希腊语");
        for (String k : new String[]{"he", "heb", "hebrew"}) m.put(k, "希伯来语");
        for (String k : new String[]{"cs", "ces", "cze", "czech"}) m.put(k, "捷克语");
        for (String k : new String[]{"hu", "hun", "hungarian"}) m.put(k, "匈牙利语");
        for (String k : new String[]{"uk", "ukr", "ukrainian"}) m.put(k, "乌克兰语");
        for (String k : new String[]{"ro", "ron", "rum", "romanian"}) m.put(k, "罗马尼亚语");
        for (String k : new String[]{"sk", "slk", "slo", "slovak"}) m.put(k, "斯洛伐克语");
        for (String k : new String[]{"bg", "bul", "bulgarian"}) m.put(k, "保加利亚语");
        for (String k : new String[]{"fa", "fas", "per", "persian"}) m.put(k, "波斯语");
        for (String k : new String[]{"ta", "tam", "tamil"}) m.put(k, "泰米尔语");
        for (String k : new String[]{"te", "tel", "telugu"}) m.put(k, "泰卢固语");
        for (String k : new String[]{"hr", "hrv", "croatian"}) m.put(k, "克罗地亚语");
        for (String k : new String[]{"sr", "srp", "serbian"}) m.put(k, "塞尔维亚语");
        for (String k : new String[]{"sl", "slv", "slovenian"}) m.put(k, "斯洛文尼亚语");
        for (String k : new String[]{"et", "est", "estonian"}) m.put(k, "爱沙尼亚语");
        for (String k : new String[]{"lv", "lav", "latvian"}) m.put(k, "拉脱维亚语");
        for (String k : new String[]{"lt", "lit", "lithuanian"}) m.put(k, "立陶宛语");
        for (String k : new String[]{"is", "isl", "ice", "icelandic"}) m.put(k, "冰岛语");
        for (String k : new String[]{"tl", "tgl", "fil", "filipino", "tagalog"}) m.put(k, "菲律宾语");
        m.put("und", "未知语种");
        m.put("unknown", "未知语种");
        return m;
    }

    // ---------- 长按左右键：连续拖动进度 ----------

    /** 长按成立：先把进度条接管过来，之后每次 tick 只动进度条，不真 seek。 */
    private void beginDrag(boolean backward) {
        if (dragging || finishing || player == null) return;
        if (player.getPlaybackState() == Player.STATE_IDLE) return;
        if (!player.isCurrentMediaItemSeekable()) return;
        long dur = player.getDuration();
        if (dur == C.TIME_UNSET || dur <= 0) return;      // 时长未知（直播）没法拖

        dragging = true;
        dragBackward = backward;
        // 兜底：没经过按键分支就进拖动时，把提速计时从这一刻算起，
        // 免得 holdStartAt 还停在 0、被当成「早就按住很久」而一上来就满速。
        if (holdStartAt <= 0) holdStartAt = android.os.SystemClock.uptimeMillis();
        // 起点沿用「刚按下的那一步」算出的目标位置，进度条就不会往回跳
        long base = (seekTarget >= 0) ? seekTarget : player.getCurrentPosition();
        if (base < 0) base = 0;
        if (base > dur) base = dur;
        dragPos = base;

        // 刚才那一步不再单独跳了，等松手一起落
        main.removeCallbacks(commitSeek);
        seekPending = false;
        main.removeCallbacks(dragTick);
        main.post(dragTick);
    }

    /** 松手：真正落到拖到的位置。 */
    private void endDrag(boolean commit) {
        boolean was = dragging;
        dragging = false;
        main.removeCallbacks(dragTick);
        if (!was || !commit || finishing || player == null) return;
        long dur = player.getDuration();
        long p = Math.max(0L, dragPos);
        if (dur > 0 && p > dur) p = dur;
        player.seekTo(p);
        seekTarget = p;
        seekPending = false;
        lastSeekKeyAt = 0;                 // 下一轮按键重新取基准
        showSeekOverlay((dragBackward ? "\u25c0 \u5df2\u62d6\u5230 " : "\u5df2\u62d6\u5230 ")
                + fmtTime(p), p);
        saveProgressNow();
    }

    // ---------- 记忆播放进度 ----------

    /** 进度记录的 key：本地文件按路径、网盘按「路径 + 大小」——重传后大小变了就自动换一条。 */
    private String progressKey() {
        if (!localFile.isEmpty()) return "file:" + localFile;
        if (playFile != null && playFile.path != null && !playFile.path.isEmpty()) {
            if (playFile.size > 0) return "pan:" + playFile.path + "#" + playFile.size;
            return "pan:" + playFile.path;
        }
        if (!reqBpath.isEmpty()) return "pan:" + reqBpath;
        return name.isEmpty() ? "" : "name:" + name;
    }

    /** 读上次看到哪儿；不值得续播（太靠前/太靠后/没记录）一律返回 0。 */
    private long loadResumePos() {
        if (!Settings.rememberPos()) return 0;   // 设置里关了续播就不读
        String k = progressKey();
        if (k.isEmpty()) return 0;
        long[] v = Settings.loadPos(k);
        if (v == null) return 0;
        long pos = v[0];
        long dur = v[1];
        if (pos < RESUME_MIN_MS) return 0;
        if (dur > 0 && pos > dur - RESUME_TAIL_MS) return 0;
        return pos;
    }

    /** 立即落一次进度（定时器 / 暂停 / 退出时调用）。 */
    private void saveProgressNow() {
        if (player == null) return;
        if (!Settings.rememberPos()) return;   // 设置里关了续播就不写
        String k = progressKey();
        if (k.isEmpty()) return;
        int st = player.getPlaybackState();
        if (st == Player.STATE_IDLE) return;
        if (st == Player.STATE_ENDED) {
            Settings.clearPos(k);
            return;
        }
        long dur = player.getDuration();
        if (dur == C.TIME_UNSET || dur <= 0) return;      // 时长还没解析出来 / 直播，不记
        long pos = dragging ? dragPos : player.getCurrentPosition();
        if (pos < RESUME_MIN_MS) return;                  // 刚开头，不值得记
        if (pos > dur - RESUME_TAIL_MS) {                 // 快看完了 -> 下次从头
            Settings.clearPos(k);
            return;
        }
        Settings.savePos(k, pos, dur);
    }

    /** 按时间快退/快进；连续按在同一基准上叠加位移。 */
    private void doSeek(long step, boolean backward) {
        if (player == null) return;
        lastSeekBackward = backward;
        int st = player.getPlaybackState();
        if (st == Player.STATE_IDLE) {
            // ENDED 也允许跳：看完按左键退回上一段，ExoPlayer 会自动继续播
            toast("视频还没准备好");
            return;
        }
        if (!player.isCurrentMediaItemSeekable()) {
            toast("该流不支持快进/快退");
            return;
        }

        long dur = player.getDuration();
        if (dur == C.TIME_UNSET || dur <= 0) dur = -1;

        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastSeekKeyAt > SEEK_COMBINE_GAP) {
            // 新的一轮连按：以「当下播放位置」为基准
            seekBase = Math.max(0L, player.getCurrentPosition());
            seekSteps = 0;
        }
        lastSeekKeyAt = now;
        seekSteps++;

        long offset = seekSteps * step;
        long target = seekBase + (backward ? -offset : offset);
        if (target < 0) target = 0;
        if (dur > 0 && target > dur) target = dur;

        seekTarget = target;
        seekPending = true;
        main.removeCallbacks(commitSeek);
        main.postDelayed(commitSeek, SEEK_COMMIT_DELAY);
        showSeekOverlay((backward ? "快退 " : "快进 ") + (offset / 1000) + " 秒");
    }

    /**
     * OK 键 = 播放/暂停（media3 自带控制条已关，这个键自己接管）。
     *
     * 用 getPlayWhenReady 而不是 isPlaying：缓冲中 isPlaying() 是 false，
     * 拿它判断会变成「又点了一次播放」，缓冲阶段根本暂停不下来。
     */
    private void togglePlayPause() {
        if (player == null) return;
        boolean toPlay = !player.getPlayWhenReady();
        player.setPlayWhenReady(toPlay);
        seekPending = false;
        showSeekOverlay(toPlay ? "播放" : "暂停");
        // 按 OK 后顶部一律显示影片文件名（中央覆盖层负责报「播放 / 暂停」）
        showTitleBar();
    }

    /** 中央覆盖层：一行提示 + 进度条 + 当前/总时长，2.5 秒后自动淡出。 */
    private void showSeekOverlay(String tip) {
        showSeekOverlay(tip, -1);
    }

    /** 同上，但进度条画在指定位置上（长按拖动时用）。 */
    private void showSeekOverlay(String tip, long posExplicit) {
        if (boxSeek == null) return;
        long dur = (player == null) ? C.TIME_UNSET : player.getDuration();
        long pos;
        if (posExplicit >= 0) {
            pos = posExplicit;
        } else if (seekPending && seekTarget >= 0) {
            pos = seekTarget;
        } else {
            pos = (player == null) ? 0 : player.getCurrentPosition();
        }
        tvSeekTip.setText(tip);
        tvSeekCur.setText(fmtTime(pos));
        tvSeekTotal.setText(dur > 0 ? fmtTime(dur) : "--:--");
        if (dur > 0) {
            int p = (int) (pos * 1000L / dur);
            pbSeek.setProgress(Math.max(0, Math.min(1000, p)));
        } else {
            pbSeek.setProgress(0);
        }
        fadeIn(boxSeek);
        main.removeCallbacks(hideSeekOverlay);
        main.postDelayed(hideSeekOverlay, SEEK_OVERLAY_HOLD);
    }

    /**
     * 把顶部状态栏复位成「常态内容」= 影片文件名。
     *
     * <p>顶栏的作用是让用户一眼认出「现在放的是哪一部、哪一集」，取流进度那几句只是过渡态。
     * 播放就绪、以及每次按 OK 播放/暂停之后都调一次，保证顶栏不会停在过期话术上。</p>
     */
    private void showTitleBar() {
        String n = displayName();
        if (n == null || n.isEmpty()) return;
        status(n);
    }

    /**
     * 顶栏要显示的名字：优先**具体文件名**（多集剧能分清是哪一集、什么版本），
     * 再退到详情页带过来的集名，最后才是片名。
     */
    private String displayName() {
        if (!localFile.isEmpty()) {
            try {
                String n = new java.io.File(localFile).getName();
                if (n != null && !n.isEmpty()) return n;
            } catch (Throwable ignored) {
            }
        }
        if (playFile != null && playFile.name != null && !playFile.name.isEmpty()) return playFile.name;
        if (!reqFname.isEmpty()) return reqFname;
        return name;
    }

    /** 动遥控器时把顶部/底部提示重新亮出来（同样 3 秒后再消失）。 */
    private void pokeChrome() {
        if (!chromeArmable) return;
        fadeIn(tvStatus);
        fadeIn(tvPlayerHint);
        main.removeCallbacks(hideChrome);
        main.postDelayed(hideChrome, CHROME_HIDE_DELAY);
    }

    private void fadeIn(final View v) {
        if (v == null) return;
        v.animate().cancel();
        if (v.getVisibility() == View.VISIBLE) {
            v.setAlpha(1f);
            return;
        }
        v.setAlpha(0f);
        v.setVisibility(View.VISIBLE);
        v.animate().alpha(1f).setDuration(180).start();
    }

    private void fadeOut(final View v) {
        if (v == null || v.getVisibility() != View.VISIBLE) return;
        v.animate().cancel();
        v.animate().alpha(0f).setDuration(280).start();
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (v.getVisibility() == View.VISIBLE && v.getAlpha() < 0.05f) {
                    v.setVisibility(View.GONE);
                }
            }
        }, 320);
    }

    // ---------- 对话框在 TV 上的调优 ----------

    /** TV 上对话框默认又窄、字又大，一屏放不下几条轨道 —— 统一走这里调一遍。 */
    private void tuneDialog(final AlertDialog dlg) {
        widen(dlg, 1.3f);
        shrinkItems(dlg);
    }

    /** 把窗口宽度在「当前实际宽度」基础上放大 factor 倍，上限为屏宽的 94%。 */
    private void widen(final AlertDialog dlg, final float factor) {
        final Window w = dlg.getWindow();
        if (w == null) return;
        // 布局完成前 getWidth() 还是 0，post 到下一轮再量
        w.getDecorView().post(() -> {
            int base = w.getDecorView().getWidth();
            int screen = getResources().getDisplayMetrics().widthPixels;
            int target = base > 0 ? (int) (base * factor) : (int) (screen * 0.85f);
            int cap = (int) (screen * 0.94f);
            if (target > cap) target = cap;
            w.setLayout(target, ViewGroup.LayoutParams.WRAP_CONTENT);
        });
    }

    /** 列表条目：缩小字号、允许两行、超出用省略号（长轨名尽量显示完整）。 */
    private void shrinkItems(AlertDialog dlg) {
        ListView lv = dlg.getListView();
        if (lv == null) return;
        // 条目是回收复用的，后滚出来的孩子也要处理 -> 挂个层级监听
        lv.setOnHierarchyChangeListener(new ViewGroup.OnHierarchyChangeListener() {
            @Override
            public void onChildViewAdded(View parent, View child) {
                shrinkText(child);
            }

            @Override
            public void onChildViewRemoved(View parent, View child) {
            }
        });
        for (int i = 0; i < lv.getChildCount(); i++) shrinkText(lv.getChildAt(i));
    }

    private void shrinkText(View v) {
        if (v instanceof TextView) {
            TextView tv = (TextView) v;
            tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, DIALOG_ITEM_SP);
            tv.setSingleLine(false);
            tv.setMaxLines(2);
            tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        } else if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) shrinkText(g.getChildAt(i));
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    /** 00:00 / 1:02:03 */
    private String fmtTime(long ms) {
        if (ms < 0) ms = 0;
        long total = ms / 1000;
        long h = total / 3600;
        long m = (total % 3600) / 60;
        long sec = total % 60;
        if (h > 0) return String.format(java.util.Locale.CHINA, "%d:%02d:%02d", h, m, sec);
        return String.format(java.util.Locale.CHINA, "%02d:%02d", m, sec);
    }

    // ---------- UI ----------

    private void status(final String s) {
        Log.d(TAG, "status: " + s);
        main.post(() -> {
            tvStatus.setText(s);
            fadeIn(tvStatus);
            if (chromeArmable) {
                main.removeCallbacks(hideChrome);
                main.postDelayed(hideChrome, CHROME_HIDE_DELAY);
            }
        });
    }

    private void fail(final String s) {
        Log.d(TAG, "fail: " + s);
        main.post(() -> {
            tvStatus.setText("✘ " + s);
            fadeIn(tvStatus);
            if (chromeArmable) {
                main.removeCallbacks(hideChrome);
                main.postDelayed(hideChrome, CHROME_HIDE_DELAY);
            }
            Toast.makeText(this, s, Toast.LENGTH_LONG).show();
        });
    }

    private String humanSize(long bytes) {
        if (bytes <= 0) return "未知大小";
        if (bytes >= 1024L * 1024 * 1024) return String.format(java.util.Locale.CHINA, "%.2fGB", bytes / 1073741824.0);
        if (bytes >= 1024 * 1024) return String.format(java.util.Locale.CHINA, "%.0fMB", bytes / 1048576.0);
        return (bytes / 1024) + "KB";
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    @Override
    protected void onStop() {
        super.onStop();
        main.removeCallbacks(holdArm);
        endDrag(false);
        saveProgressNow();                 // 息屏/切走前把进度存下来
        if (player != null && !isFinishing()) player.pause();
    }

    @Override
    protected void onDestroy() {
        finishing = true;
        saveProgressNow();                 // 退出前最后落一次
        main.removeCallbacks(hideChrome);
        main.removeCallbacks(hideSeekOverlay);
        main.removeCallbacks(commitSeek);
        main.removeCallbacks(holdArm);
        main.removeCallbacks(dragTick);
        main.removeCallbacks(saveTick);
        try {
            if (player != null) {
                playerView.setPlayer(null);
                player.release();
                player = null;
            }
        } catch (Throwable ignored) {
        }
        if (proxy != null) {
            proxy.stop();
            proxy = null;
        }
        super.onDestroy();
    }
}
