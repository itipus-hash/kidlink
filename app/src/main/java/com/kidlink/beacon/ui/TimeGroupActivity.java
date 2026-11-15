package com.kidlink.beacon.ui;

import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.kidlink.beacon.R;
import com.kidlink.beacon.data.BeaconPrefs;
import com.kidlink.beacon.data.ChildEntity;
import com.kidlink.beacon.data.TimeGroup;
import com.kidlink.beacon.data.TimeRanges;
import com.kidlink.beacon.ui.widget.Feedback;

import java.util.ArrayList;
import java.util.List;

/**
 * 某一个娃的时间段类型：上学 / 放学 / 兴趣班 ……
 *
 * 这块 UI 原来在「设置 → 消息推送设置」里，是全局一份。搬到每个娃下面是因为
 * 不同娃的作息本来就不一样，全局一份只能顾一头；同时信标上的「场景标签」被删掉了，
 * 推送文案里那个括号从此只由时间段决定。
 *
 * 交互和原来一模一样：
 * - 点一行 → 编辑（名称 / 星期 / 多个时间段）
 * - 按住右边的 ≡ 拖动 → 排序；排在前面的优先级高，命中多个时取第一个
 */
public class TimeGroupActivity extends AppCompatActivity {

    private static final int MIN_RANGE_MINUTES = 15;
    private static final int[] DAY_VALUES = {1, 2, 3, 4, 5, 6, 7};
    private static final int[] DAY_LABELS = {
            R.string.day_mon, R.string.day_tue, R.string.day_wed, R.string.day_thu,
            R.string.day_fri, R.string.day_sat, R.string.day_sun};

    private long childId;
    private String childName;

    private LinearLayout timeGroupList;
    private TextView txtTimeGroupEmpty;

    /** 正在编辑的草稿：时间段 + 勾选的星期，保存前不落盘 */
    private List<int[]> editingRanges;
    private LinearLayout editingList;
    private TextView editingEmpty;
    private final java.util.Set<Integer> editingDays = new java.util.TreeSet<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_time_group);

        childId = getIntent().getLongExtra("child_id", 0);
        childName = getIntent().getStringExtra("child_name");
        if (childName == null || childName.trim().isEmpty()) childName = "这个娃";
        if (childId <= 0) {
            finish();
            return;
        }

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle(getString(R.string.time_group_title, childName));
        toolbar.setNavigationOnClickListener(v -> finish());

        timeGroupList = findViewById(R.id.time_group_list);
        txtTimeGroupEmpty = findViewById(R.id.txt_time_group_empty);
        findViewById(R.id.btn_add_time_group).setOnClickListener(v -> editTimeGroup(-1));

        renderTimeGroups();
    }

    @Override
    protected void onResume() {
        super.onResume();
        renderTimeGroups();
    }

    /** 这个娃名下的分组 */
    private List<TimeGroup> groups() {
        return BeaconPrefs.getTimeGroupsFor(this, childId);
    }

    private void saveGroups(List<TimeGroup> list) {
        BeaconPrefs.setTimeGroupsFor(this, childId, list);
    }

    /** 按当前配置重画时间段类型列表 */
    private void renderTimeGroups() {
        if (timeGroupList == null) return;
        List<TimeGroup> groups = groups();
        timeGroupList.removeAllViews();
        txtTimeGroupEmpty.setVisibility(groups.isEmpty() ? View.VISIBLE : View.GONE);

        for (TimeGroup g : groups) {
            View row = getLayoutInflater()
                    .inflate(R.layout.item_time_group, timeGroupList, false);
            TextView name = row.findViewById(R.id.txt_group_name);
            TextView summary = row.findViewById(R.id.txt_group_summary);
            name.setText(g.displayName());
            summary.setText(g.summary());

            // 把分组对象挂在行上：拖动换了顺序之后，「点谁编辑谁」靠它算下标，
            // 不能再用渲染时的那个 i（拖完就过期了）
            row.setTag(g);
            row.setOnClickListener(v -> editTimeGroup(timeGroupList.indexOfChild(row)));

            // 只有右边的 ≡ 手柄能拖，行身照旧交给 ScrollView 滚页面
            View handle = row.findViewById(R.id.drag_handle);
            if (handle != null) attachGroupDrag(handle, row);
            timeGroupList.addView(row);
        }
    }

    /**
     * 分组行：**按住右边的 ≡ 手柄就能拖**，不需要长按，行身照常滑动滚页面。
     *
     * 手柄的 DOWN 由自己吃掉，并且第一时间 `requestDisallowInterceptTouchEvent(true)` ——
     * 外层 ScrollView 的 onInterceptTouchEvent 在子 View 之前跑，晚一步就被它抢去滚页面，
     * 子 View 只剩一个 CANCEL。这也是为什么不拿整行当拖区：整行锁手势就没法滚页面了。
     *
     * 跟手的三个要素（和信标卡片一致）：
     * 1. translation 用绝对定位（布局位置 + 手指 − 抓取偏移），不累加；
     *    换位后布局位置会变，累加会越拖越偏。
     * 2. 命中判定用行的中心（getLocationOnScreen，不含 translation），并跳过自己；
     *    拿手指判会「刚挨到上一行就跳掉」，要压过对方半行才换。
     * 3. 换位前记下屏幕位置，布局回调里把 translation 补回来，视觉上不闪。
     */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private void attachGroupDrag(final View handle, final View row) {
        final int slop = android.view.ViewConfiguration.get(this).getScaledTouchSlop();
        final float[] down = new float[2];
        final float[] grab = new float[2];
        final boolean[] dragging = {false};

        handle.setOnTouchListener((v, ev) -> {
            switch (ev.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN: {
                    down[0] = ev.getRawX();
                    down[1] = ev.getRawY();
                    dragging[0] = false;
                    // 按下就锁住手势，别让外层 ScrollView 抢去滚页面（晚了就抢不回来）
                    if (row.getParent() != null) {
                        row.getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    v.setPressed(true);      // 手柄自身给一点按下反馈
                    return true;             // 手柄吃掉，行身的点击不受影响
                }
                case android.view.MotionEvent.ACTION_MOVE: {
                    if (!dragging[0]) {
                        if (Math.abs(ev.getRawY() - down[1]) <= slop
                                && Math.abs(ev.getRawX() - down[0]) <= slop) {
                            return true;
                        }
                        dragging[0] = true;
                        // 手指按在手柄上，但拖的是整行：以行的左上角为锚点算抓取偏移
                        int[] loc = new int[2];
                        row.getLocationOnScreen(loc);
                        grab[0] = down[0] - loc[0];
                        grab[1] = down[1] - loc[1];
                        row.setTranslationZ(dp(12));
                        row.setScaleX(1.02f);
                        row.setScaleY(1.02f);
                    }

                    int[] loc = new int[2];
                    row.getLocationOnScreen(loc);
                    float baseY = loc[1] - row.getTranslationY();
                    row.setTranslationX(0f);   // 只允许上下拖
                    row.setTranslationY(ev.getRawY() - grab[1] - baseY);

                    int target = groupIndexUnder(row);
                    if (target >= 0 && target != timeGroupList.indexOfChild(row)) {
                        final int[] keep = new int[2];
                        row.getLocationOnScreen(keep);
                        // 这里**不能**用 removeView + addView：
                        // ViewGroup.removeViewInternal() 里有 cancelTouchTarget(view)，
                        // 会把正在跟手的那一行从触摸链里摘掉并发一个 CANCEL ——
                        // 表现就是「拖过一行就自己跳出拖动」。
                        // moveChild 内部走 detachViewFromParent / attachViewToParent，
                        // 只动子 View 数组，不碰窗口附着、不取消触摸目标，手势一直活着。
                        if (timeGroupList instanceof com.kidlink.beacon.ui.widget.SortableLinearLayout) {
                            ((com.kidlink.beacon.ui.widget.SortableLinearLayout) timeGroupList)
                                    .moveChild(row, target);
                        }
                        row.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                            @Override
                            public void onLayoutChange(View x, int l, int t, int r, int b,
                                                       int ol, int ot, int orr, int ob) {
                                row.removeOnLayoutChangeListener(this);
                                int[] after = new int[2];
                                row.getLocationOnScreen(after);
                                row.setTranslationY(row.getTranslationY() + keep[1] - after[1]);
                            }
                        });
                    }
                    return true;
                }
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL: {
                    if (row.getParent() != null) {
                        row.getParent().requestDisallowInterceptTouchEvent(false);
                    }
                    v.setPressed(false);
                    v.jumpDrawablesToCurrentState();
                    if (!dragging[0]) return true;   // 只是点了一下手柄，什么都不做
                    dragging[0] = false;
                    // 松手：滑回格子里的自然位置（translation 归零即落位）
                    row.animate().translationX(0f).translationY(0f)
                            .scaleX(1f).scaleY(1f).setDuration(130).start();
                    row.setTranslationZ(0f);
                    saveGroupOrder();
                    return true;
                }
                default:
                    return false;
            }
        });
    }

    /**
     * 被拖那一行的**中心**现在压在谁身上（跳过自己）。
     *
     * 用行的中心而不是手指位置：手指才刚探进上一行一寸就换位，看着就像"跳掉"。
     * 要压过对方半行（中心落进对方矩形）才换，和系统里列表拖动的门槛一致。
     */
    private int groupIndexUnder(View dragged) {
        int[] dloc = new int[2];
        dragged.getLocationOnScreen(dloc);
        float centerY = dloc[1] + dragged.getHeight() / 2f;
        for (int i = 0; i < timeGroupList.getChildCount(); i++) {
            View child = timeGroupList.getChildAt(i);
            if (child == dragged) continue;
            int[] loc = new int[2];
            child.getLocationOnScreen(loc);
            if (centerY >= loc[1] && centerY < loc[1] + child.getHeight()) return i;
        }
        return -1;
    }

    /**
     * 把界面上当前的顺序写回 prefs。
     *
     * 顺序是有意义的：接娃那条推送命中多个时间段时，取**最靠前**的那个当标签，
     * 所以这里排在前面的分组优先级更高。
     */
    private void saveGroupOrder() {
        List<TimeGroup> ordered = new ArrayList<>();
        for (int i = 0; i < timeGroupList.getChildCount(); i++) {
            Object tag = timeGroupList.getChildAt(i).getTag();
            if (tag instanceof TimeGroup) ordered.add((TimeGroup) tag);
        }
        // 行数对不上说明没全部打上 tag，宁可不写，也别把配置写乱
        if (ordered.isEmpty() || ordered.size() != timeGroupList.getChildCount()) return;
        saveGroups(ordered);
    }

    /** index < 0 表示新增 */
    private void editTimeGroup(final int index) {
        List<TimeGroup> groups = groups();
        final TimeGroup editing = (index >= 0 && index < groups.size()) ? groups.get(index) : null;

        editingRanges = new ArrayList<>();
        if (editing != null) {
            editingRanges.addAll(TimeRanges.parse(editing.ranges));
        } else {
            editingRanges.add(new int[]{7 * 60, 8 * 60});
        }
        editingDays.clear();
        if (editing != null) {
            editingDays.addAll(editing.pickedDays());
        } else {
            // 新建默认周一到周日全勾 —— 「不勾 = 不推送」，全勾才是能用起来的状态
            for (int v : DAY_VALUES) editingDays.add(v);
        }

        View content = getLayoutInflater().inflate(R.layout.dialog_time_group, null, false);
        final EditText edtName = content.findViewById(R.id.edt_group_name);
        editingList = content.findViewById(R.id.time_range_list);
        editingEmpty = content.findViewById(R.id.txt_range_empty);
        final com.google.android.material.chip.ChipGroup chipGroup =
                content.findViewById(R.id.chip_days);

        if (editing != null) {
            edtName.setText(editing.name);
            edtName.setSelection(edtName.getText().length());
        }

        // 周一到周日 7 个可勾选的标签，勾中直接整颗变绿；一个都不勾 = 这几天不推送
        for (int i = 0; i < DAY_VALUES.length; i++) {
            com.google.android.material.chip.Chip chip =
                    new com.google.android.material.chip.Chip(this);
            chip.setText(DAY_LABELS[i]);
            chip.setCheckable(true);
            chip.setTag(DAY_VALUES[i]);
            chip.setChecked(editingDays.contains(DAY_VALUES[i]));
            // 不要默认那个 ✓，勾中的状态用背景色表达
            chip.setCheckedIconVisible(false);
            chip.setChipBackgroundColor(dayChipBackground());
            chip.setTextColor(dayChipTextColor());
            chip.setChipStrokeWidth(0f);
            chip.setChipMinHeight(dp(32));
            chip.setEnsureMinTouchTargetSize(false);
            chipGroup.addView(chip);
        }

        content.findViewById(R.id.btn_add_range).setOnClickListener(v -> {
            int start = 7 * 60;
            int end = 8 * 60;
            if (!editingRanges.isEmpty()) {
                int[] last = editingRanges.get(editingRanges.size() - 1);
                start = Math.min(23 * 60, last[1] + 30);
                end = Math.min(24 * 60, start + 60);
                if (end <= start) {
                    start = 7 * 60;
                    end = 8 * 60;
                }
            }
            editingRanges.add(new int[]{start, end});
            renderEditingRanges();
        });

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this)
                .setTitle(editing == null ? R.string.time_group_add : R.string.time_group_name_hint)
                .setView(content)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.save, null);
        if (editing != null) builder.setNeutralButton(R.string.delete, null);

        final androidx.appcompat.app.AlertDialog dialog = builder.create();
        dialog.setOnShowListener(x -> {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                    .setOnClickListener(v -> {
                        String name = edtName.getText().toString().trim();
                        if (name.isEmpty()) {
                            Feedback.show(this, R.string.time_group_name_empty);
                            return;
                        }
                        // 收集勾选的星期
                        StringBuilder days = new StringBuilder();
                        for (int i = 0; i < chipGroup.getChildCount(); i++) {
                            View child = chipGroup.getChildAt(i);
                            if (child instanceof com.google.android.material.chip.Chip
                                    && ((com.google.android.material.chip.Chip) child).isChecked()) {
                                Object tag = child.getTag();
                                if (tag instanceof Integer) {
                                    if (days.length() > 0) days.append(',');
                                    days.append((int) (Integer) tag);
                                }
                            }
                        }

                        List<TimeGroup> list = groups();
                        TimeGroup g = new TimeGroup(name, days.toString(),
                                TimeRanges.format(editingRanges), childId);
                        if (editing == null) {
                            list.add(g);
                        } else if (index < list.size()) {
                            list.set(index, g);
                        }
                        saveGroups(list);
                        dialog.dismiss();
                        renderTimeGroups();
                        Feedback.show(this, R.string.detect_setting_saved);
                    });

            android.widget.Button neutral =
                    dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL);
            if (neutral != null) {
                neutral.setOnClickListener(v -> {
                    dialog.dismiss();
                    confirmDeleteTimeGroup(index);
                });
            }
        });

        renderEditingRanges();
        dialog.show();
    }

    private void confirmDeleteTimeGroup(final int index) {
        List<TimeGroup> groups = groups();
        if (index < 0 || index >= groups.size()) return;
        final TimeGroup g = groups.get(index);

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.time_group_delete)
                .setMessage(getString(R.string.token_delete_message, g.displayName()))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete, (d, w) -> {
                    List<TimeGroup> list = groups();
                    if (index < list.size()) list.remove(index);
                    saveGroups(list);
                    renderTimeGroups();
                    Feedback.show(this, R.string.token_deleted);
                })
                .show();
    }

    private void renderEditingRanges() {
        if (editingList == null) return;
        editingList.removeAllViews();
        editingEmpty.setVisibility(editingRanges.isEmpty() ? View.VISIBLE : View.GONE);

        for (int i = 0; i < editingRanges.size(); i++) {
            final int index = i;
            final int[] range = editingRanges.get(i);

            View row = getLayoutInflater()
                    .inflate(R.layout.item_time_range, editingList, false);
            TextView start = row.findViewById(R.id.txt_range_start);
            TextView end = row.findViewById(R.id.txt_range_end);
            start.setText(TimeRanges.hhmm(range[0]));
            end.setText(TimeRanges.hhmm(range[1]));

            // 改开始时间不报错，按原时长把结束时间一起推过去；改结束时间同理往前推
            start.setOnClickListener(v -> pickTime(range[0], picked -> {
                int duration = Math.max(MIN_RANGE_MINUTES, range[1] - range[0]);
                range[0] = picked;
                if (range[1] <= range[0]) {
                    range[1] = Math.min(24 * 60, range[0] + duration);
                }
                renderEditingRanges();
            }));
            end.setOnClickListener(v -> pickTime(range[1], picked -> {
                int duration = Math.max(MIN_RANGE_MINUTES, range[1] - range[0]);
                range[1] = picked;
                if (range[1] <= range[0]) {
                    range[0] = Math.max(0, range[1] - duration);
                }
                renderEditingRanges();
            }));
            row.findViewById(R.id.btn_range_delete).setOnClickListener(v -> {
                if (index < editingRanges.size()) editingRanges.remove(index);
                renderEditingRanges();
            });
            editingList.addView(row);
        }
    }

    private interface OnTimePicked {
        void onPicked(int minuteOfDay);
    }

    private void pickTime(int current, OnTimePicked callback) {
        int h = Math.min(23, current / 60);
        int m = current % 60;
        new android.app.TimePickerDialog(this,
                (view, hour, minute) -> callback.onPicked(hour * 60 + minute),
                h, m, true).show();
    }

    // === 星期标签的配色：勾中 = 绿底白字，没勾 = 浅灰底灰字 ===

    private android.content.res.ColorStateList dayChipBackground() {
        return new android.content.res.ColorStateList(
                new int[][]{{android.R.attr.state_checked}, {}},
                new int[]{
                        androidx.core.content.ContextCompat.getColor(this, R.color.primary),
                        androidx.core.content.ContextCompat.getColor(this, R.color.bg_main)});
    }

    private android.content.res.ColorStateList dayChipTextColor() {
        return new android.content.res.ColorStateList(
                new int[][]{{android.R.attr.state_checked}, {}},
                new int[]{
                        androidx.core.content.ContextCompat.getColor(this, R.color.white),
                        androidx.core.content.ContextCompat.getColor(this, R.color.text_secondary)});
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    /** 给别的页面用的摘要：「上学 · 放学」，没有就「未设置」 */
    public static String summarize(List<TimeGroup> groups) {
        if (groups == null || groups.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (TimeGroup g : groups) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(g.displayName());
        }
        return sb.toString();
    }

    /** 便捷构造：带上娃名，供管理页跳转 */
    public static android.content.Intent intentFor(android.content.Context ctx, ChildEntity child) {
        android.content.Intent it = new android.content.Intent(ctx, TimeGroupActivity.class);
        it.putExtra("child_id", child.getId());
        it.putExtra("child_name", child.displayName());
        return it;
    }
}
