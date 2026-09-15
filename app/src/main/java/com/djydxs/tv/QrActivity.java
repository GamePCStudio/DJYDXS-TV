package com.djydxs.tv;

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

/** 扫码授权页：显示二维码 + 轮询状态。 */
public class QrActivity extends Activity {

    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private ImageView ivQr;
    private TextView tvStatus;

    private volatile String sign = "";
    private volatile boolean polling = false;
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
    }

    private void startQr() {
        stopPoll = false;
        polling = false;
        tvStatus.setText("正在获取二维码…");
        ivQr.setImageDrawable(null);
        pool.execute(() -> {
            BaiduPan.QrSession s = BaiduPan.qrStart();
            if (s.sign.isEmpty() || s.qrimgUrl.isEmpty()) {
                main.post(() -> tvStatus.setText("二维码获取失败，请重试（检查网络）"));
                return;
            }
            sign = s.sign;
            // 二维码图片必须在工作线程下载（主线程下载会抛 NetworkOnMainThreadException）
            byte[] img = BaiduPan.fetchImage(s.qrimgUrl);
            final android.graphics.Bitmap bmp =
                    img == null ? null : BitmapFactory.decodeByteArray(img, 0, img.length);
            main.post(() -> {
                if (bmp != null) {
                    ivQr.setImageBitmap(bmp);
                } else {
                    tvStatus.setText("二维码下载失败，请点下方重试");
                }
                if (bmp != null) {
                    tvStatus.setText("请用 百度网盘APP 扫一扫");
                    startPolling();
                }
            });
        });
    }

    private void startPolling() {
        if (polling) return;
        polling = true;
        pool.execute(() -> {
            String mySign = sign;
            int tries = 0;
            while (!stopPoll && mySign.equals(sign) && tries < 60) {
                tries++;
                BaiduPan.QrPoll r = BaiduPan.qrPoll(mySign);
                if ("scanned".equals(r.status)) {
                    main.post(() -> tvStatus.setText("已扫码 ✓  请在手机上点「确认登录」"));
                } else if ("ok".equals(r.status)) {
                    main.post(() -> {
                        tvStatus.setText("授权成功 ✓");
                        // 取账号昵称
                        pool.execute(() -> {
                            String name = BaiduPan.userName();
                            Settings.setBaiduUser(name);
                            main.post(() -> {
                                Toast.makeText(this, "授权成功：" + (name.isEmpty() ? "百度账号" : name),
                                        Toast.LENGTH_LONG).show();
                                finish();
                            });
                        });
                    });
                    polling = false;
                    return;
                }
                try {
                    Thread.sleep(2500);
                } catch (InterruptedException e) {
                    break;
                }
            }
            polling = false;
            if (!stopPoll && mySign.equals(sign)) {
                main.post(() -> tvStatus.setText("二维码已超时，请点击下方重新获取"));
            }
        });
    }
}
