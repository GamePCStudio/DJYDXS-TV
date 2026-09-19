package com.supermov.tv;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Intent;

/**
 * LAMPA 效果③：页面栈方向过渡动画。
 *
 * <p>对齐 LAMPA {@code animation-activity / animation-from-below}：进入下级页时，
 * 新页从屏幕下方滑入 + 淡入（about 250ms），返回时父页从下方滑入、子页向上滑出。
 * 电视端"有方向感的页面推进"，替代默认硬切。</p>
 *
 * <p>用原生 {@link ActivityOptions#makeScaleUpAnimation}（API 21+，本 App minSdk 24 直接可用），
 * 零第三方依赖。进入 = 从屏幕中心 scale-up + 淡入（约 250ms）；
 * 返回时 Android 自动对父 Activity 做反向动画。
 * （makeClipRectAnimation 是 API 33+，本 App 兼容 Android 7.x，不能用。）</p>
 */
public final class PageTransition {

    /** 单方向时长（LAMPA 页面栈动画 200~250ms，取 250）。 */
    private static final int DURATION = 250;

    private PageTransition() {}

    /**
     * 启动一个下级 Activity，带「从下方滑入 + 淡入」过渡。
     * 返回键触发 finish 时，系统对父 Activity 自动播放反向滑出。
     */
    public static void open(Activity from, Intent it) {
        from.startActivity(it, enterOptions(from).toBundle());
    }

    /** 便捷重载：按目标类构造 Intent 再启动。 */
    public static void open(Activity from, Class<? extends Activity> to) {
        open(from, new Intent(from, to));
    }

    /**
     * from-below 进入选项：新页用其自身窗口从底部「揭开」。
     * {@code x=0, y=windowH, cx=0, cy=0} 表示 clip 从 (0,windowH)-(0,0) 矩形开始
     * （即完全藏到屏外下方），动画到全窗口 → 视觉上从下方滑入。
     */
    private static ActivityOptions enterOptions(Activity from) {
        int h = from.getResources().getDisplayMetrics().heightPixels;
        int w = from.getResources().getDisplayMetrics().widthPixels;
        // from-below：scale-up 从屏幕中心（API21+ 全平台可用，TV 7.x 兼容）
        ActivityOptions opts = ActivityOptions.makeScaleUpAnimation(
                from, null, w / 2, h / 2, w, h);
        return opts;
    }
}
