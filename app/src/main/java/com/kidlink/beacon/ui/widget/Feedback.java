package com.kidlink.beacon.ui.widget;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * APP 内唯一的轻提示（替代 Toast / Snackbar）。
 *
 * 移植自 RideAssist（io.github.supit.rideassist.ui.widget.Feedback），零第三方依赖，
 * 只用 android.* framework 类。
 *
 * ## 外观
 * 深灰 #1F1F1F + 80% 不透明度 + 14dp 圆角的胶囊文字，水平居中，
 * 浮在底部导航栏上方 12dp（本项目没有底部导航，用 96dp 兜底离底距离）。
 *
 * ## 三段式动画
 * 1. 从下方 56dp 移上来 + 淡入（360ms，减速插值）
 * 2. 停够 2.2s / 3.8s（从入场动画结束开始计时）
 * 3. 滑回下方 + 淡出（360ms，加速插值）→ 移除
 *
 * ## 为什么不用系统那几个
 * - Snackbar：只能贴 CoordinatorLayout 底部，必须有 View 宿主；
 * - Toast：Android 11 起文字 Toast 外观由系统接管，setView() 被忽略；
 * - Dialog：浮动窗口会被系统栏区域裁剪，Gravity.BOTTOM + lp.y 算不准。
 * 所以直接把 TextView 加进 android.R.id.content，位置交给布局系统（bottomMargin）保证。
 *
 * ## 调用
 * <pre>
 * Feedback.show(view, "文案");              // 普通，停 2.2s
 * Feedback.show(context, "文案", true);     // 长驻，停 3.8s
 * </pre>
 */
public final class Feedback {

    // ==================== 外观 ====================

    /** 底色：深灰 */
    private static final int BG_COLOR = 0xFF1F1F1F;

    /** 背景不透明度：0xCC = 204/255 = 80% */
    private static final int BG_ALPHA = 0xCC;

    private static final int CORNER_DP = 14;
    private static final int PAD_H_DP = 22;
    private static final int PAD_V_DP = 14;
    private static final float TEXT_SP = 15f;

    // ==================== 动画节奏 ====================

    private static final long RISE_MS = 360L;
    private static final long EXIT_MS = 360L;
    private static final long HOLD_SHORT_MS = 2200L;
    private static final long HOLD_LONG_MS = 3800L;

    /** 入场起始点上滑距离：从最终定位点下方这么多 dp 冒上来（不是凭空淡入） */
    private static final int SLIDE_UP_DP = 56;

    // ==================== 位置 ====================

    /** 与底部标签页之间的间距 */
    private static final int BOTTOM_NAV_GAP_DP = 12;

    /** 没有底部标签页时的兜底离底距离 ≈ 标准标签页栏高度，保证不贴屏底 */
    private static final int FALLBACK_BOTTOM_DP = 96;

    /** 提示 View 的 tag：用来找当前那条，避免静态持有 View 泄漏 Activity */
    private static final String TAG_FEEDBACK = "kidlink_feedback";

    private Feedback() {}

    // ==================== 对外接口 ====================

    /** 显示一条轻提示（普通时长 2.2s） */
    public static void show(View anchor, CharSequence text) {
        show(anchor != null ? anchor.getContext() : null, text, false);
    }

    /** 显示一条轻提示 */
    public static void show(View anchor, CharSequence text, boolean longHold) {
        show(anchor != null ? anchor.getContext() : null, text, longHold);
    }

    /** 显示一条轻提示（普通时长 2.2s） */
    public static void show(Context context, CharSequence text) {
        show(context, text, false);
    }

    /** 文案用资源 id 的便捷重载 */
    public static void show(Context context, int resId) {
        show(context, resId, false);
    }

    /** 文案用资源 id 的便捷重载 */
    public static void show(Context context, int resId, boolean longHold) {
        if (context == null) return;
        show(context, context.getString(resId), longHold);
    }

    /** 文案用资源 id 的便捷重载 */
    public static void show(View anchor, int resId) {
        show(anchor, resId, false);
    }

    /** 文案用资源 id 的便捷重载 */
    public static void show(View anchor, int resId, boolean longHold) {
        if (anchor == null) return;
        show(anchor.getContext(), anchor.getContext().getString(resId), longHold);
    }

    /**
     * 显示一条轻提示。
     *
     * @param context 任意 Context（Activity / View / Receiver 都行）
     * @param longHold 是否长驻（对应系统 Toast 的 LENGTH_LONG）
     */
    public static void show(Context context, CharSequence text, boolean longHold) {
        if (context == null || text == null) return;
        if (context.getApplicationContext() == null && !(context instanceof Activity)) return;

        Activity activity = findActivity(context);
        if (activity == null || activity.isFinishing()
                || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && activity.isDestroyed())) {
            // 拿不到可用 Activity（Receiver / 后台）→ 交给系统 Toast
            Toast.makeText(context.getApplicationContext() != null
                            ? context.getApplicationContext() : context,
                    text, longHold ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT).show();
            return;
        }
        showCentered(activity, text, longHold ? HOLD_LONG_MS : HOLD_SHORT_MS);
    }

    // ==================== 内部实现 ====================

    private static void showCentered(final Activity activity, CharSequence text, final long holdMs) {
        final View contentView = activity.findViewById(android.R.id.content);
        if (!(contentView instanceof FrameLayout)) {
            Toast.makeText(activity, text, Toast.LENGTH_SHORT).show();
            return;
        }
        final FrameLayout root = (FrameLayout) contentView;

        // 上一条还没消失就先收掉，避免叠成一摞（用 tag 找，不持有静态引用）
        View old = root.findViewWithTag(TAG_FEEDBACK);
        if (old != null) root.removeView(old);

        final TextView tv = new TextView(activity);
        tv.setText(text);
        tv.setTag(TAG_FEEDBACK);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(TEXT_SP);
        tv.setGravity(Gravity.CENTER);
        int padH = dp(activity, PAD_H_DP);
        int padV = dp(activity, PAD_V_DP);
        tv.setPadding(padH, padV, padH, padV);
        tv.setBackground(roundedBackground(activity));
        // 入场初始态：位于最终定位点下方 + 全透明，动画负责「移上来 + 淡入」
        tv.setTranslationY(dp(activity, SLIDE_UP_DP));
        tv.setAlpha(0f);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.bottomMargin = bottomInsetPx(activity);
        root.addView(tv, lp);

        // ⚠️ 同一个 View 的 ViewPropertyAnimator 只能跑一个动画，
        //    所以「停够时间」的延迟必须挂在这次入场动画的 withEndAction 上；
        //    另起一次 tv.animate().start() 会把上升动画顶掉。
        tv.animate()
                .translationY(0f)
                .alpha(1f)
                .setDuration(RISE_MS)
                .setInterpolator(new DecelerateInterpolator())
                .withEndAction(() -> {
                    // 计时从入场动画结束开始，保证「停够」时长
                    tv.postDelayed(() -> {
                        if (tv.getParent() == null) return;
                        tv.animate()
                                .translationY(dp(activity, SLIDE_UP_DP))
                                .alpha(0f)
                                .setDuration(EXIT_MS)
                                .setInterpolator(new AccelerateInterpolator())
                                .withEndAction(() -> {
                                    ViewGroup parent = tv.getParent() instanceof ViewGroup
                                            ? (ViewGroup) tv.getParent() : null;
                                    if (parent != null) parent.removeView(tv);
                                })
                                .start();
                    }, holdMs);
                })
                .start();
    }

    /**
     * 离底距离 = 底部导航栏高度 + 间距。
     * 本项目没有底部导航栏（无 bottom_nav），直接用兜底值。
     */
    private static int bottomInsetPx(Activity activity) {
        return dp(activity, FALLBACK_BOTTOM_DP) + dp(activity, BOTTOM_NAV_GAP_DP);
    }

    /** 圆角 + 半透明底色 */
    private static GradientDrawable roundedBackground(Context context) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setCornerRadius(dp(context, CORNER_DP));
        d.setColor(Color.argb(BG_ALPHA,
                Color.red(BG_COLOR), Color.green(BG_COLOR), Color.blue(BG_COLOR)));
        return d;
    }

    private static Activity findActivity(Context context) {
        Context ctx = context;
        while (ctx instanceof ContextWrapper) {
            if (ctx instanceof Activity) return (Activity) ctx;
            ctx = ((ContextWrapper) ctx).getBaseContext();
        }
        return null;
    }

    private static int dp(Context context, int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density);
    }
}
