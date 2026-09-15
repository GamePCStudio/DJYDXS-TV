package com.djydxs.tv;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 启动门面：云端 MAC 授权检查。已授权进主程序；未授权显示 MAC + 退出按钮。 */
public class GateActivity extends Activity {

    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private TextView tvMac;
    private TextView tvStatus;
    private TextView btnExit;
    private TextView btnRetry;

    private String mac = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        CookieStore.init(this);
        Settings.init(this);
        setContentView(R.layout.activity_gate);

        tvMac = findViewById(R.id.tvGateMac);
        tvStatus = findViewById(R.id.tvGateStatus);
        btnExit = findViewById(R.id.btnExit);
        btnRetry = findViewById(R.id.btnRetry);

        btnExit.setOnClickListener(v -> finishAffinity());
        btnRetry.setOnClickListener(v -> check());

        mac = MacUtil.getMac();
        tvMac.setText("本机 MAC：" + (mac.isEmpty() ? "获取失败" : mac));
        check();
    }

    private void check() {
        if (mac.isEmpty()) {
            tvStatus.setText("无法获取本机 MAC，无法校验授权");
            btnRetry.setVisibility(View.VISIBLE);
            return;
        }
        tvStatus.setText("正在校验授权…");
        btnRetry.setVisibility(View.GONE);
        btnExit.setVisibility(View.GONE);
        pool.execute(() -> {
            MacAuth.Result r = MacAuth.check(mac);
            main.post(() -> {
                if (r.networkOk && r.authorized) {
                    tvStatus.setText("本机已授权 ✓");
                    startActivity(new Intent(this, MainActivity.class));
                    finish();
                } else if (r.networkOk) {
                    // 在库但停用/过期，或不在库
                    String why;
                    if ("not_found".equals(r.reason)) why = "本机未授权";
                    else if ("revoked".equals(r.reason)) why = "本机授权已被停用";
                    else if ("expired".equals(r.reason)) why = "本机授权已过期";
                    else why = "本机未授权";
                    tvStatus.setText("⚠ 本机未授权\n" + why
                            + "\nMAC: " + mac + "\n请联系管理方添加授权");
                    btnExit.setVisibility(View.VISIBLE);
                    btnRetry.setVisibility(View.VISIBLE);
                } else {
                    tvStatus.setText("授权服务器连接失败，请检查网络后重试");
                    btnExit.setVisibility(View.VISIBLE);
                    btnRetry.setVisibility(View.VISIBLE);
                }
            });
        });
    }
}
