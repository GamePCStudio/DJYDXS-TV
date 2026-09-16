package com.djydxs.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/** 设置页：百度网盘扫码 / 转存目录 / 授权状态 / 解除授权。 */
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

        // ② 转存目录（预设 + 自定义当前值）
        group.addView(sectionLabel("转存目录"));
        String current = Settings.saveDir();
        for (String dir : new String[]{"/apps/DJYDXS", "/apps/TVBox", "/影视资源", "/来自TVBox的转存"}) {
            boolean cur = dir.equals(current);
            group.addView(option((cur ? "● " : "○ ") + dir + (cur ? "  ← 当前" : ""),
                    "转存目标目录（不存在会自动创建）", v -> {
                        Settings.setSaveDir(dir);
                        Toast.makeText(this, "转存目录已设为 " + dir, Toast.LENGTH_SHORT).show();
                        rebuild();
                    }));
        }

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

        // ③.5 连接诊断
        group.addView(option("连接诊断", "测试论坛/百度接口连通性", v -> runDiag()));

        // ④ 论坛登录（TV 上用内置浏览器登录，解锁会员版块）
        group.addView(sectionLabel("论坛账号"));
        group.addView(option("论坛登录（4kzimu.top）", "内置浏览器登录后自动记住 Cookie，解锁会员版块", v ->
                startActivity(new Intent(this, WebLoginActivity.class))));

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

    private void runDiag() {
        Toast.makeText(this, "诊断中…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            final StringBuilder sb = new StringBuilder();
            // 论坛首页
            Http.Resp r1 = Http.get(Site.BASE + "/forum.php");
            boolean forumOk = r1.code == 200 && !Site.isLoginWall(r1.body);
            sb.append("论坛: ").append(r1.code == 0 ? "网络不可达" : ("HTTP " + r1.code))
              .append(forumOk ? " (已登录)" : (r1.code == 200 ? " (被登录墙拦截)" : ""));
            sb.append("\n");
            // 百度二维码接口
            Http.Resp r2 = Http.get("https://passport.baidu.com/v2/api/getqrcode?lp=pc&qrloginfrom=skip");
            String qrHint = "";
            if (r2.code == 200 && r2.body.contains("sign")) qrHint = " (正常)";
            sb.append("百度扫码接口: ").append(r2.code == 0 ? "网络不可达" : ("HTTP " + r2.code)).append(qrHint);
            sb.append("\n");
            // 百度网盘登录态
            sb.append("百度登录态: ").append(CookieStore.hasBaiduLogin() ? "已授权" : "未授权");
            sb.append("\n");
            // 论坛 Cookie
            String auth = CookieStore.get(Site.BASE + "/", "cTo3_2132_auth");
            sb.append("论坛Cookie: ").append(auth == null || auth.isEmpty() ? "未注入" : "已内置");
            runOnUiThread(() -> new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog)
                    .setTitle("连接诊断")
                    .setMessage(sb.toString())
                    .setPositiveButton("好", null)
                    .show());
        }).start();
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
