package com.kidlink.beacon.ui.widget;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

/**
 * 可以原地换位的 LinearLayout —— 专给「拖动排序」用。
 *
 * 普通做法 `removeView(row)` + `addView(row, index)` 在拖动过程中不能用：
 * `ViewGroup.removeViewInternal()` 里有一句 `cancelTouchTarget(view)`，
 * 会把正在跟手的那一行从触摸链里摘掉、并给它发一个 ACTION_CANCEL。
 * 表现就是拖过一行之后拖动莫名中断，得重新按住。
 *
 * {@code detachViewFromParent} / {@code attachViewToParent} 只动子 View 数组，
 * 既不取消触摸目标，也不触发 detached-from-window，手势能一直活着。
 * 这两个方法在 ViewGroup 里是 protected（RecyclerView 就是这么用的），
 * 所以包一层把自己的子类方法暴露出来。
 */
public class SortableLinearLayout extends LinearLayout {

    public SortableLinearLayout(Context context) {
        super(context);
    }

    public SortableLinearLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public SortableLinearLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    /** 把某个子 View 挪到 index 位置，不打断正在进行的手势 */
    public void moveChild(View child, int index) {
        if (child == null || child.getParent() != this) return;
        if (index < 0) index = 0;
        ViewGroup.LayoutParams lp = child.getLayoutParams();
        detachViewFromParent(child);
        attachViewToParent(child, Math.min(index, getChildCount()), lp);
        requestLayout();
    }
}
