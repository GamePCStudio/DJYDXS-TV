package com.supermov.tv;

import android.app.Activity;
import android.content.Intent;

/**
 * LAMPA 效果③：页面栈方向过渡动画。
 *
 * <p>对齐 LAMPA {@code animation-activity / animation-from-below}：进入下级页时新页从屏幕下方
 * 滑入 + 淡入（about 250ms），返回时系统对父 Activity 自动播放对称动画。
 * 电视端"有方向感的页面推进"，替代默认硬切。</p>
 *
 * <p>实现：{@link Activity#overridePendingTransition(int, int)} 挂系统过渡 + 自定义 XML
 * （{@code res/anim/page_enter_from_below / page_exit_hold}，API 21+，本 App minSdk 24 直接可用，
 * 兼容 Android TV 7.x），零第三方依赖。</p>
 */
public final class PageTransition {

    private PageTransition() {}

    /** 启动一个下级 Activity，带「从下方滑入 + 淡入」过渡。 */
    public static void open(Activity from, Intent it) {
        from.startActivity(it);
        from.overridePendingTransition(R.anim.page_enter_from_below, R.anim.page_exit_hold);
    }

    /** 便捷重载：按目标类构造 Intent 再启动。 */
    public static void open(Activity from, Class<? extends Activity> to) {
        open(from, new Intent(from, to));
    }
}
