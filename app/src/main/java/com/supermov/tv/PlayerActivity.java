package com.supermov.tv;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.WindowManager;
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
    private ExoPlayer player;
    private LocalProxy proxy;

    private String name = "";
    private String shareUrl = "";
    private String sharePwd = "";
    /** 非空 = 播放已下载到本地的文件（不走百度取流） */
    private String localFile = "";

    private BaiduPan.PlayFile playFile;
    private boolean usingM3u8 = false;
    private boolean retriedDlink = false;
    private boolean finishing = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_player);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        playerView = findViewById(R.id.playerView);
        tvStatus = findViewById(R.id.tvPlayerStatus);

        name = nz(getIntent().getStringExtra("name"));
        shareUrl = nz(getIntent().getStringExtra("url"));
        sharePwd = nz(getIntent().getStringExtra("pwd"));
        localFile = nz(getIntent().getStringExtra("file"));

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
        MediaItem.Builder b = MediaItem.Builder().setUri(android.net.Uri.fromFile(f));
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
        MediaItem.Builder b = MediaItem.Builder().setUri(url);
        if (m3u8) b.setMimeType(MimeTypes.APPLICATION_M3U8);
        player.setMediaItem(b.build());
        player.prepare();
        if (pos > 0) player.seekTo(pos);
        player.setPlayWhenReady(true);
    }

    private void handlePlaybackError(PlaybackException error) {
        if (finishing) return;
        Log.d(TAG, "playback error code=" + error.getErrorCode() + " msg=" + error.getMessage());
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

    private String describe(PlaybackException e) {
        switch (e.getErrorCode()) {
            case PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS:
                return "服务器拒绝访问（HTTP 错误，可能是直链过期或风控）";
            case PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED:
            case PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT:
                return "网络连接失败/超时";
            case PlaybackException.ERROR_CODE_DECODER_INIT_FAILED:
            case PlaybackException.ERROR_CODE_DECODING_FAILED:
                return "解码失败（电视盒子不支持该编码，如部分 4K/AV1）";
            case PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED:
            case PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED:
                return "文件/清单解析失败";
            default:
                return e.getErrorCodeName();
        }
    }

    // ---------- UI ----------

    private void status(final String s) {
        Log.d(TAG, "status: " + s);
        main.post(() -> tvStatus.setText(s));
    }

    private void fail(final String s) {
        Log.d(TAG, "fail: " + s);
        main.post(() -> {
            tvStatus.setText("✘ " + s);
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
