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
 * 在线播放（ExoPlayer / media3 1.11）。
 *
 * 取流策略（按用户选定的「直链 + 本地代理 + M3U8 兜底」）：
 *   1. 先在自己网盘的转存目录里定位这部影片（没转存过则自动转存一次）
 *   2. /api/filemetas 取原画 dlink → 交给 LocalProxy（补 UA=netdisk）→ 播放原画
 *   3. 原画失败/卡死 → 换一条新直链重试一次（dlink 只有 8 小时有效期）
 *   4. 仍失败 → 降级 pan.baidu.com/api/streaming 的 M3U8 转码流（上限 1080p），并 seek 回原进度
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
    /** 左右键每按一次的时间步长 */
    private static final long SEEK_STEP_MS = 10000L;
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
    /** 拖动时进度条的刷新间隔 */
    private static final long DRAG_TICK_MS = 100L;
    /** 拖满整片大约需要多少个 tick（240 × 100ms ≈ 24 秒） */
    private static final long DRAG_TICKS_FULL = 240L;
    /** 拖动时每 tick 的最小步长，免得短片拖起来太黏 */
    private static final long DRAG_MIN_STEP_MS = 1500L;
    /** 每 10 秒落一次播放进度 */
    private static final long PROGRESS_SAVE_INTERVAL = 10000L;
    /** 进度不到这里就不值得记（防止「只看了个片头」把记录写脏） */
    private static final long RESUME_MIN_MS = 15000L;
    /** 离结尾这么近就当看完了，直接清记录 */
    private static final long RESUME_TAIL_MS = 20000L;
    /** 字幕字号（占屏幕高度的比例，media3 默认 0.0533） */
    private static final float SUBTITLE_TEXT_FRACTION = 0.066f;

    /** 轨道选择框的条目字号：TV 上默认字号太大，一屏放不下几条轨道。 */
    private static final float DIALOG_ITEM_SP = 13f;

    /** 长按拖动中 */
    private boolean dragging = false;
    private boolean dragBackward = false;
    private long dragPos = 0;
    /** 上一次左右键的方向，长按成立时沿用它 */
    private boolean lastSeekBackward = false;
    /** 记忆的播放进度；-1 = 还没查过 */
    private long resumePos = -1;
    /** 是否要在出画后提示「继续上次进度」 */
    private boolean resumeNotice = false;

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
            main.postDelayed(this, DRAG_TICK_MS);
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

        player = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(httpFactory()))
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .build(), true)
                .setHandleAudioBecomingNoisy(true)
                .build();
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
                } else if (state == Player.STATE_ENDED) {
                    // 看完了：把进度记录清掉，下次从头开始
                    Settings.clearPos(progressKey());
                }
            }
        });
        playerView.setPlayer(player);

        // 字幕渲染在 PlayerView 的默认布局里（SubtitleView, id=exo_subtitles）。
        // 默认是「无描边白字 + 5.33% 屏高」，电视上远看偏小，这里改成黑描边 + 6.6%。
        try {
            SubtitleView sv = playerView.getSubtitleView();
            if (sv != null) {
                sv.setStyle(new CaptionStyleCompat(Color.WHITE, Color.TRANSPARENT, Color.TRANSPARENT,
                        CaptionStyleCompat.EDGE_TYPE_OUTLINE, Color.BLACK, null));
                sv.setFractionalTextSize(SUBTITLE_TEXT_FRACTION);
                sv.setBottomPaddingFraction(0.10f);
            }
        } catch (Throwable ignored) {
        }

        // 每 10 秒落一次进度（另外在暂停 / 退出 / 播完时也会落）
        main.postDelayed(saveTick, PROGRESS_SAVE_INTERVAL);

        if (!localFile.isEmpty()) {
            playLocalFile();
        } else {
            prepareAndPlay();
        }
    }

    /** 播放已下载到本地的文件（下载队列里「播放本地」进来的）。 */
    private void playLocalFile() {
        java.io.File f = new java.io.File(localFile);
        if (!f.exists() || f.length() <= 0) {
            fail("本地文件不存在：" + localFile);
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

    /** 播放器侧 HTTP 头：原画走代理不需要它，但 M3U8 的分片请求必须自带 UA=netdisk。 */
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
        // pos > 0 = 换链/降级时保住手上这条进度；pos <= 0 = 全新开播，这时才去读记忆的进度
        long start = (pos > 0) ? pos : freshStartPos();
        MediaItem.Builder b = new MediaItem.Builder().setUri(url);
        if (m3u8) b.setMimeType(MimeTypes.APPLICATION_M3U8);
        player.setMediaItem(b.build());
        player.prepare();
        if (start > 0) player.seekTo(start);
        player.setPlayWhenReady(true);
    }

    private void handlePlaybackError(PlaybackException error) {
        if (finishing) return;
        Log.d(TAG, "playback error code=" + error.getErrorCodeName() + " msg=" + error.getMessage());
        final long pos = player.getCurrentPosition();
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
            String u = BaiduPan.streamingUrl(playFile.path, "M3U8_AUTO_720");
            if (u == null || u.isEmpty()) u = BaiduPan.streamingUrl(playFile.path, "M3U8_AUTO_480");
            final String url = u == null ? "" : u;
            main.post(() -> {
                if (finishing) return;
                if (url.isEmpty()) {
                    fail("转码流也不可用（可能受会员权限限制或接口已变动）");
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
            return "网络连接失败/超时";
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

        long step = (code == KeyEvent.KEYCODE_MEDIA_REWIND || code == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)
                ? SEEK_STEP_BIG_MS : SEEK_STEP_MS;
        doSeek(step, isRewind);
        // 按住不放超过 450ms -> 升级成连续拖动（松手才落点）
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

    /** 轨道的展示名：优先 label，其次语言代码，再兜底「字幕 N / 音轨 N」。 */
    private String trackLabel(Tracks.Group g, int idx, int type) {
        Format f = g.getTrackFormat(idx);
        String s = (f == null) ? "" : f.label;
        if (s == null || s.trim().isEmpty()) s = (f == null) ? "" : f.language;
        if (s == null || s.trim().isEmpty()) {
            s = (type == C.TRACK_TYPE_TEXT ? "\u5b57\u5e55 " : "\u97f3\u8f68 ") + (idx + 1);
        }
        s = s.trim();
        if (f != null && type == C.TRACK_TYPE_AUDIO && f.channelCount > 0) {
            s = s + " \u00b7 " + f.channelCount + "ch";
        }
        if (s.length() > 26) s = s.substring(0, 26) + "\u2026";
        return s;
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
