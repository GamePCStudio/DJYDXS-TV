package com.supermov.tv;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 扫码授权页：显示二维码 + 轮询扫码状态。
 *
 * <p><b>为什么用两条线程池 + 轮次代号（v1.26 修复）：</b>旧实现只有一个单线程池，
 * 而「轮询状态」是一个最长 60 次 x 2.5 秒（≈150 秒）的循环，整段占着那条线程；
 * 用户点「重新获取二维码」时，取新码的任务只能排在队尾 —— 按钮看起来完全没反应。
 * 更糟的是 {@code startQr()} 里把 {@code stopPoll} 置回 false，等于给旧轮询续了命。</p>
 *
 * <p>现在：取码与轮询各占一条线程；每轮扫码有一个自增的 {@code gen}，点一次刷新
 * 就让上一轮立即作废（旧轮询最多再跑完当前这一次 sleep 就退出），UI 回调也带 gen 校验，
 * 避免旧轮次把新二维码的状态文字覆盖掉。</p>
 */
public class QrActivity extends Activity {

    /** 取二维码图片（网络下载）专用 */
    private final ExecutorService fetchPool = Executors.newSingleThreadExecutor();
    /** 轮询扫码状态专用 —— 与取码分开，刷新按钮才不会被它挡住 */
    private final ExecutorService pollPool = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private ImageView ivQr;
    private TextView tvStatus;

    /** 当前轮次代号；自增即作废旧轮次 */
    private volatile int gen = 0;
    private volatile boolean stopPoll = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_qr);
        ivQr = findViewById(R.id.ivQr);
        tvStatus = findViewById(R.id.tvQrStatus);
        findViewById(R.id.tvQrRetry).setOnClickListener(v -> startQr());
        startQr();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopPoll = true;
        gen++;                      // 让在飞的轮询与回调立刻失效
        fetchPool.shutdownNow();
        pollPool.shutdownNow();
    }

    /** 取一张新二维码。点一次刷新或首次进入都会走这里。 */
    private void startQr() {
        final int myGen = ++gen;    // 关键：先作废上一轮，再去取新码
        tvStatus.setText("正在获取二维码…");
        ivQr.setImageDrawable(null);
        fetchPool.execute(() -> {
            final BaiduPan.QrSession s = BaiduPan.qrStart();
            if (myGen != gen) return;
            if (s.error != null && !s.error.isEmpty()) {
                main.post(() -> {
                    if (myGen == gen) tvStatus.setText("获取失败：" + s.error);
                });
                return;
            }
            // 二维码图片必须在工作线程下载（主线程下载会抛 NetworkOnMainThreadException）
            final byte[] img = BaiduPan.fetchImage(s.qrimgUrl);
            if (myGen != gen) return;
            final Bitmap bmp = img == null ? null : BitmapFactory.decodeByteArray(img, 0, img.length);
            main.post(() -> {
                if (myGen != gen) return;
                if (bmp != null) {
                    ivQr.setImageBitmap(bmp);
                    tvStatus.setText("请用 百度网盘APP 扫一扫");
                    startPolling(myGen, s.sign);
                } else {
                    // 图片下载失败：给出直链让用户手机浏览器打开扫码
                    tvStatus.setText("二维码图下载失败。\n可在手机浏览器打开：\n" + s.qrimgUrl);
                }
            });
        });
    }

    /** 轮询这一轮二维码的扫码状态；{@code myGen} 一旦过期就立刻收工。 */
    private void startPolling(final int myGen, final String sign) {
        pollPool.execute(() -> {
            int tries = 0;
            while (!stopPoll && myGen == gen && tries < 60) {
                tries++;
                final BaiduPan.QrPoll r = BaiduPan.qrPoll(sign);
                if (myGen != gen) return;
                if ("scanned".equals(r.status)) {
                    main.post(() -> {
                        if (myGen == gen) tvStatus.setText("已扫码 \u2713  请在手机上点「确认登录」");
                    });
                } else if ("ok".equals(r.status)) {
                    main.post(() -> {
                        if (myGen != gen) return;
                        tvStatus.setText("授权成功 \u2713");
                        // 取账号昵称（网络请求放到取码线程，别堵轮询）
                        fetchPool.execute(() -> {
                            String name = BaiduPan.userName();
                            Settings.setBaiduUser(name);
                            final boolean sessOk = BaiduPan.sessionValid();
                            main.post(() -> {
                                Toast.makeText(this, sessOk
                                        ? "授权成功：" + (name.isEmpty() ? "百度账号" : name)
                                        : "已扫码但会话校验失败，请重试扫码", Toast.LENGTH_LONG).show();
                                finish();
                            });
                        });
                    });
                    return;
                }
                try {
                    Thread.sleep(2500);
                } catch (InterruptedException e) {
                    return;             // 被 shutdownNow 打断：直接退出
                }
            }
            if (!stopPoll && myGen == gen) {
                main.post(() -> {
                    if (myGen == gen) tvStatus.setText("二维码已超时，请点下方「重新获取二维码」");
                });
            }
        });
    }
}
