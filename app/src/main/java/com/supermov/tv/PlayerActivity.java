package com.supermov.tv;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
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

import java.util.HashMap;
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
     */
    private static final long SEEK_COMMIT_DELAY = 350L;
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
                if (state == Player.STATE_READY && !chromeArmable) {
                    chromeArmable = true;
                    main.removeCallbacks(hideChrome);
                    main.postDelayed(hideChrome, CHROME_HIDE_DELAY);
                }
            }
        });
        playerView.setPlayer(player);

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
        MediaItem.Builder b = new MediaItem.Builder().setUri(android.net.Uri.fromFile(f));
        player.setMediaItem(b.build());
        player.prepare();
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

    private void startPlay(String url, long pos, boolean m3u8) {
        if (finishing || player == null) return;
        MediaItem.Builder b = new MediaItem.Builder().setUri(url);
        if (m3u8) b.setMimeType(MimeTypes.APPLICATION_M3U8);
        player.setMediaItem(b.build());
        player.prepare();
        if (pos > 0) player.seekTo(pos);
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
     * 真正的 seekTo 延迟 350ms 提交，所以一串连按只跳一次。
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        boolean isRewind = (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_MEDIA_REWIND);
        boolean isForward = (code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD);
        boolean isOk = (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER || code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);

        if (!isRewind && !isForward && !isOk) {
            // 其它键（返回/音量…）照旧透传，顺手把顶部底部提示亮 3 秒
            if (e.getAction() == KeyEvent.ACTION_DOWN) pokeChrome();
            return super.dispatchKeyEvent(e);
        }

        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;   // 吞掉 UP，免得再响一次按键音
        if (e.getRepeatCount() > 0) return true;                 // 长按的重复事件忽略：按一下就是一下

        if (isRewind || isForward) {
            long step = (code == KeyEvent.KEYCODE_MEDIA_REWIND || code == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)
                    ? SEEK_STEP_BIG_MS : SEEK_STEP_MS;
            doSeek(step, isRewind);
        } else {
            togglePlayPause();
        }
        return true;
    }

    /** 按时间快退/快进；连续按在同一基准上叠加位移。 */
    private void doSeek(long step, boolean backward) {
        if (player == null) return;
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
        if (boxSeek == null) return;
        long dur = (player == null) ? C.TIME_UNSET : player.getDuration();
        long pos;
        if (seekPending && seekTarget >= 0) {
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
        if (player != null && !isFinishing()) player.pause();
    }

    @Override
    protected void onDestroy() {
        finishing = true;
        main.removeCallbacks(hideChrome);
        main.removeCallbacks(hideSeekOverlay);
        main.removeCallbacks(commitSeek);
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
