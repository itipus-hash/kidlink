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
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

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
 * ## 多条同时出现时往上摞
 * 一条还没消失又来一条，**不是把旧的那条顶掉**（那样用户根本读不完）：
 * 旧的整摞往上让位、继续走自己的倒计时，新的在下面出现。
 *
 * 实现是一个贴底的竖向 LinearLayout 容器，新消息 addView 到末尾，容器往上长。
 * 光靠布局会「瞬移」，所以在加新消息之前先把每条的**布局位置**记下来，
 * 加完再算一遍，把位移量先当成 translationY 按回老位置，然后动画归零 ——
 * 看上去就是被顶上去的。
 *
 * ⚠️ 只有「已停稳」的气泡参与归位动画。还在上升的（刚加的）、
 * 正在淡出的（要走的）一律跳过：对它们调 animate() 会把各自正在跑的
 * 位移/透明度动画一起掐掉，气球会卡在半透明或者半路上。
 * 所以每个气泡身上挂一个状态 tag：RISING → 停稳 → EXITING。
 *
 * 最多同时 3 条，再多就把最早那条直接收掉（它已经待得最久了）。
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

    /** 摞起来的几条之间的间距 */
    private static final int STACK_GAP_DP = 8;

    /** 最多同时显示几条，超过就把最早那条收掉 */
    private static final int MAX_STACK = 3;

    /** 提示容器的 tag：用来找当前那摞，避免静态持有 View 泄漏 Activity */
    private static final String TAG_CONTAINER = "kl_feedback_container";

    /** 气泡状态：正在上升（不参与归位，否则会掐掉自己的入场动画） */
    private static final String TAG_RISING = "kl_feedback_rising";

    /** 气泡状态：正在淡出（不参与归位，同理） */
    private static final String TAG_EXITING = "kl_feedback_exiting";

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

        Activity activity = findActivity(context);
        if (activity == null || activity.isFinishing()
                || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && activity.isDestroyed())) {
            // 拿不到可用 Activity（Receiver / 后台）→ 交给系统 Toast
            Context app = context.getApplicationContext();
            Toast.makeText(app != null ? app : context,
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

        LinearLayout found = root.findViewWithTag(TAG_CONTAINER);
        if (found == null) {
            found = new LinearLayout(activity);
            found.setTag(TAG_CONTAINER);
            found.setOrientation(LinearLayout.VERTICAL);
            found.setGravity(Gravity.CENTER_HORIZONTAL);
            // 入场动画会把消息往下拽 56dp，超出容器边界也得画出来
            found.setClipChildren(false);
            found.setClipToPadding(false);
            FrameLayout.LayoutParams lpStack = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            lpStack.bottomMargin = bottomInsetPx(activity);
            root.addView(found, lpStack);
        }
        final LinearLayout container = found;

        // 1) 先把现有每条的布局位置记下来（要按回原位用），
        //    同时把正在淡出的那条直接摘掉 —— 它最多还剩 360ms，留着只会跟着挪、糊成一团
        final Map<View, Float> oldY = new IdentityHashMap<>();
        final List<View> kept = new ArrayList<>();
        for (int i = 0; i < container.getChildCount(); i++) {
            View child = container.getChildAt(i);
            if (TAG_EXITING.equals(child.getTag())) {
                container.removeViewAt(i);
                i--;
                continue;
            }
            oldY.put(child, layoutY(container, child));
            kept.add(child);
        }

        // 2) 超过上限就把最早那条直接收掉（它已经待得最久）
        while (kept.size() + 1 > MAX_STACK && !kept.isEmpty()) {
            View eldest = kept.remove(0);
            container.removeView(eldest);
        }

        // 3) 新消息接在最下面，容器跟着往上长
        final TextView tv = buildMessage(activity, text);
        tv.setTag(TAG_RISING);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = kept.isEmpty() ? 0 : dp(activity, STACK_GAP_DP);
        container.addView(tv, lp);

        // 4) 布局跑完之后，让「已停稳」的旧气泡先按回原位、再滑到新位置
        if (!kept.isEmpty()) {
            container.post(() -> slideKept(container, oldY, kept));
        }

        // ⚠️ 同一个 View 的 ViewPropertyAnimator 只能跑一个动画，
        //    所以「停够时间」的延迟必须挂在这次入场动画的 withEndAction 上；
        //    另起一次 tv.animate().start() 会把上升动画顶掉。
        tv.animate()
                .translationY(0f)
                .alpha(1f)
                .setDuration(RISE_MS)
                .setInterpolator(new DecelerateInterpolator())
                .withEndAction(() -> {
                    // 入场结束 → 状态改成「停稳」，可以参与以后的归位动画了
                    if (tv.getParent() == null) return;
                    tv.setTag(null);
                    // 计时从入场动画结束开始，保证「停够」时长
                    tv.postDelayed(() -> {
                        if (tv.getParent() == null) return;
                        tv.setTag(TAG_EXITING);
                        tv.animate()
                                .translationY(dp(activity, SLIDE_UP_DP))
                                .alpha(0f)
                                .setDuration(EXIT_MS)
                                .setInterpolator(new AccelerateInterpolator())
                                .withEndAction(() -> dismiss(container, tv))
                                .start();
                    }, holdMs);
                })
                .start();
    }

    /**
     * 把「已停稳」的那几条，先摆回改动前的屏幕位置，再动画归位。
     *
     * delta = 老位置 − 新位置：容器往上长时 delta 为正 → translationY 先往下按，
     * 归零就是往上推；容器变矮时 delta 为负 → 先往上按，归零就是往下落。
     * 同一套符号两边都对，所以共用一个方法。
     */
    private static void slideKept(LinearLayout container, Map<View, Float> oldY, List<View> kept) {
        for (View child : kept) {
            if (child.getParent() == null) continue;
            // 上升中 / 淡出中的不动，否则会把它们自己的动画掐断
            if (TAG_RISING.equals(child.getTag()) || TAG_EXITING.equals(child.getTag())) continue;

            Float before = oldY.get(child);
            if (before == null) continue;
            float delta = before - layoutY(container, child);
            if (Math.abs(delta) < 0.5f) {
                child.setTranslationY(0f);
                continue;
            }
            child.animate().cancel();
            child.setTranslationY(delta);
            child.animate()
                    .translationY(0f)
                    .setDuration(RISE_MS)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
        }
    }

    /**
     * 一条消息消失：把它摘掉，剩下那几条因为容器变矮会往下掉，
     * 同样先把它们按回原位再滑回去，避免「啪」地跳一下。
     */
    private static void dismiss(LinearLayout container, TextView tv) {
        if (container.indexOfChild(tv) < 0) {
            // 已经被 MAX_STACK 那条逻辑收走了
            return;
        }

        final Map<View, Float> oldY = new IdentityHashMap<>();
        final List<View> kept = new ArrayList<>();
        for (int i = 0; i < container.getChildCount(); i++) {
            View child = container.getChildAt(i);
            if (child == tv) continue;
            oldY.put(child, layoutY(container, child));
            kept.add(child);
        }

        container.removeView(tv);

        if (container.getChildCount() == 0) {
            // 空了就把容器也摘掉，别留一个空壳贴在那儿
            ViewGroup parent = (ViewGroup) container.getParent();
            if (parent != null) parent.removeView(container);
            return;
        }
        if (!kept.isEmpty()) {
            container.post(() -> slideKept(container, oldY, kept));
        }
    }

    /**
     * 气泡在屏幕上的布局 Y（不含自身 translationY）。
     * 直接取 getY() 会把动画中的位移也算进去，归位就算错了。
     */
    private static float layoutY(ViewGroup container, View child) {
        int[] loc = new int[2];
        container.getLocationOnScreen(loc);
        return loc[1] + child.getTop();
    }

    /** 造一条消息气泡：深灰胶囊 + 入场初始态（下方 56dp、全透明） */
    private static TextView buildMessage(Context context, CharSequence text) {
        TextView tv = new TextView(context);
        tv.setText(text);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(TEXT_SP);
        tv.setGravity(Gravity.CENTER);
        int padH = dp(context, PAD_H_DP);
        int padV = dp(context, PAD_V_DP);
        tv.setPadding(padH, padV, padH, padV);
        tv.setBackground(roundedBackground(context));
        // 入场初始态：位于最终定位点下方 + 全透明，动画负责「移上来 + 淡入」
        tv.setTranslationY(dp(context, SLIDE_UP_DP));
        tv.setAlpha(0f);
        return tv;
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
