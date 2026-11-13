package com.kidlink.beacon.ui;

import android.os.Bundle;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.kidlink.beacon.R;
import com.kidlink.beacon.data.BeaconPrefs;
import com.kidlink.beacon.data.PushToken;
import com.kidlink.beacon.data.TimeGroup;
import com.kidlink.beacon.data.TimeRanges;
import com.kidlink.beacon.ui.widget.Feedback;

import java.util.List;

/**
 * 设置页（首页点标题「接娃助手」进入）
 *
 * 1. 接娃人 —— 默认取手机蓝牙名
 * 2. 推送设置（折叠）—— 多条 PushPlus 通道、推送距离、推送冷却
 * 3. 图标颜色超时时间 / 图标变色触发距离 / 震动 / 悬浮提醒触发距离
 */
public class SettingsActivity extends AppCompatActivity {

    private TextView txtPicker;
    private TextView txtCooldown;
    private TextView txtInterval;
    private TextView txtNear;
    private TextView txtPushDistance;
    private TextView txtApproach;
    private TextView txtVibrate;
    private TextView txtFrontTip;
    private TextView txtPushChevron;
    private TextView txtNearChevron;

    private android.view.View groupPushDetails;
    private android.view.View groupNearDetails;
    private android.view.View groupSystemDetails;
    private TextView txtSystemChevron;
    private com.google.android.material.switchmaterial.SwitchMaterial swExitAfter;

    /** 动态填进去的一串 PushPlus 通道行 */
    private android.widget.LinearLayout tokenList;
    private android.widget.TextView txtTokenEmpty;

    /** 推送设置默认折叠；刷新时按这个变量保持用户的展开状态 */
    private boolean pushExpanded = false;
    /** 近距离提醒设置默认折叠 */
    private boolean nearExpanded = false;
    /** 系统设置默认折叠 */
    private boolean systemExpanded = false;

    /** 扫配置码回来后刷新界面，并弹一条导入成功的提示 */
    private final androidx.activity.result.ActivityResultLauncher<android.content.Intent> scanLauncher =
            registerForActivityResult(
                    new androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() != RESULT_OK) return;
                        refresh();
                        Feedback.show(this, R.string.settings_import_ok);
                    });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        // 右上角：分享配置 / 接收配置
        toolbar.inflateMenu(R.menu.menu_settings);
        toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.action_share_config) {
                showShareDialog();
                return true;
            }
            return false;
        });

        txtPicker = findViewById(R.id.txt_picker_value);
        txtCooldown = findViewById(R.id.txt_cooldown_value);
        txtInterval = findViewById(R.id.txt_interval_value);
        txtNear = findViewById(R.id.txt_near_value);
        txtPushDistance = findViewById(R.id.txt_push_distance_value);
        txtApproach = findViewById(R.id.txt_approach_value);
        timeGroupList = findViewById(R.id.time_group_list);
        txtTimeGroupEmpty = findViewById(R.id.txt_time_group_empty);
        findViewById(R.id.btn_add_time_group).setOnClickListener(v -> editTimeGroup(-1));
        txtVibrate = findViewById(R.id.txt_vibrate_value);
        txtFrontTip = findViewById(R.id.txt_front_tip_value);
        txtPushChevron = findViewById(R.id.txt_push_chevron);
        txtNearChevron = findViewById(R.id.txt_near_chevron);

        groupPushDetails = findViewById(R.id.group_push_details);
        groupNearDetails = findViewById(R.id.group_near_details);
        groupSystemDetails = findViewById(R.id.group_system_details);
        txtSystemChevron = findViewById(R.id.txt_system_chevron);
        swExitAfter = findViewById(R.id.sw_exit_after);

        findViewById(R.id.row_push_header).setOnClickListener(v -> {
            pushExpanded = !pushExpanded;
            applyPushVisibility();
        });
        findViewById(R.id.row_near_header).setOnClickListener(v -> {
            nearExpanded = !nearExpanded;
            applyNearVisibility();
        });
        findViewById(R.id.row_system_header).setOnClickListener(v -> {
            systemExpanded = !systemExpanded;
            applySystemVisibility();
        });
        swExitAfter.setOnCheckedChangeListener((btn, checked) ->
                BeaconPrefs.setExitAfterArrival(this, checked));

        tokenList = findViewById(R.id.token_list);
        txtTokenEmpty = findViewById(R.id.txt_token_empty);
        // 添加按钮在列表下面，点进去填名称 + token
        findViewById(R.id.btn_add_token).setOnClickListener(v -> showTokenDialog(-1));
        // 还没注册 PushPlus 的，直接跳官网
        findViewById(R.id.txt_token_register).setOnClickListener(v -> openPushPlusSite());

        findViewById(R.id.row_picker).setOnClickListener(v -> editPickerName());
        findViewById(R.id.row_cooldown).setOnClickListener(v -> editPushCooldown());
        findViewById(R.id.row_interval).setOnClickListener(v -> editDetectInterval());
        findViewById(R.id.row_near).setOnClickListener(v -> editNearDistance());
        findViewById(R.id.row_push_distance).setOnClickListener(v -> editPushDistance());
        findViewById(R.id.row_approach).setOnClickListener(v -> editApproach());
        findViewById(R.id.row_vibrate).setOnClickListener(v -> chooseVibrate());
        findViewById(R.id.row_front_tip).setOnClickListener(v -> editFrontTip());

        refresh();
    }

    /** 推送设置始终可见，点标题展开/收起明细 */
    private void applyPushVisibility() {
        groupPushDetails.setVisibility(pushExpanded
                ? android.view.View.VISIBLE : android.view.View.GONE);
        txtPushChevron.setText(pushExpanded ? R.string.chevron_expanded : R.string.chevron_collapsed);
    }

    /** 近距离提醒设置同理：默认折叠，点标题展开 */
    private void applyNearVisibility() {
        groupNearDetails.setVisibility(nearExpanded
                ? android.view.View.VISIBLE : android.view.View.GONE);
        txtNearChevron.setText(nearExpanded ? R.string.chevron_expanded : R.string.chevron_collapsed);
    }

    /** 系统设置同理 */
    private void applySystemVisibility() {
        groupSystemDetails.setVisibility(systemExpanded
                ? android.view.View.VISIBLE : android.view.View.GONE);
        txtSystemChevron.setText(systemExpanded
                ? R.string.chevron_expanded : R.string.chevron_collapsed);
    }

    // === 分享 / 接收配置 ===

    private void showShareDialog() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.settings_share_dialog_title)
                .setItems(new CharSequence[]{
                        getString(R.string.settings_share_action),
                        getString(R.string.settings_receive_action)
                }, (d, which) -> {
                    if (which == 0) shareConfig();
                    else receiveConfig();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** 把所有设置编码成二维码展示（不含接娃人） */
    private void shareConfig() {
        String payload = com.kidlink.beacon.qr.SettingsQrCodec.encode(this);
        if (payload == null || payload.isEmpty()) {
            Feedback.show(this, R.string.qr_generate_failed);
            return;
        }
        android.content.Intent intent = new android.content.Intent(this, QRShareActivity.class);
        intent.putExtra("settings_payload", payload);
        startActivity(intent);
    }

    /** 扫对方的配置码，直接覆盖本机设置 */
    private void receiveConfig() {
        android.content.Intent intent = new android.content.Intent(this, QRScanActivity.class);
        intent.putExtra("scan_settings", true);
        scanLauncher.launch(intent);
    }

    private void refresh() {
        applyPushVisibility();
        applyNearVisibility();
        applySystemVisibility();
        swExitAfter.setChecked(BeaconPrefs.isExitAfterArrival(this));

        txtPicker.setText(BeaconPrefs.getPickerName(this));

        renderTokens();

        txtCooldown.setText(getString(R.string.push_cooldown_item,
                formatMinutes(BeaconPrefs.getPushCooldownMin(this))));

        txtInterval.setText(getString(R.string.detect_interval_item,
                BeaconPrefs.getDetectIntervalSec(this)));
        txtNear.setText(getString(R.string.near_distance_item,
                BeaconPrefs.getNearMeters(this)));

        txtPushDistance.setText(getString(R.string.push_distance_item,
                BeaconPrefs.getPushMeters(this)));

        int approach = BeaconPrefs.getApproachMeters(this);
        txtApproach.setText(approach <= 0
                ? getString(R.string.vibrate_choice_off)
                : getString(R.string.approach_item, approach));

        renderTimeGroups();

        int vibrateSec = BeaconPrefs.getVibrateNearSec(this);
        txtVibrate.setText(vibrateSec <= 0
                ? getString(R.string.vibrate_off)
                : getString(R.string.vibrate_item, vibrateSec));

        int frontTip = BeaconPrefs.getFrontTipMeters(this);
        txtFrontTip.setText(frontTip <= 0
                ? getString(R.string.vibrate_choice_off)
                : getString(R.string.front_tip_item, frontTip));
    }

    // === 近距离推送 ===

    private void editApproach() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        input.setText(String.valueOf(BeaconPrefs.getApproachMeters(this)));
        input.setSelection(input.getText().length());

        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.settings_approach)
                .setMessage(R.string.approach_hint)
                .setView(wrapInPadding(input))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.save, null)
                .create();

        dialog.setOnShowListener(x -> dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    int meters = parseIntOr(input.getText().toString(), -1);
                    if (meters < 0 || meters > 200) {
                        Feedback.show(SettingsActivity.this, R.string.approach_invalid);
                        return;
                    }
                    BeaconPrefs.setApproachMeters(SettingsActivity.this, meters);
                    dialog.dismiss();
                    refresh();
                    Feedback.show(SettingsActivity.this, R.string.detect_setting_saved);
                }));
        dialog.show();
    }

    // === 时间段类型（上学 / 放学 / 用户自加的）===

    private android.widget.LinearLayout timeGroupList;
    private android.widget.TextView txtTimeGroupEmpty;

    /** 正在编辑的草稿：时间段 + 勾选的星期，保存前不落盘 */
    private List<int[]> editingRanges;
    private android.widget.LinearLayout editingList;
    private android.widget.TextView editingEmpty;
    private final java.util.Set<Integer> editingDays = new java.util.TreeSet<>();

    /** 时间段最短 15 分钟，改时间时按这个下限保持时长 */
    private static final int MIN_RANGE_MINUTES = 15;
    private static final int[] DAY_VALUES = {1, 2, 3, 4, 5, 6, 7};
    private static final int[] DAY_LABELS = {
            R.string.day_mon, R.string.day_tue, R.string.day_wed, R.string.day_thu,
            R.string.day_fri, R.string.day_sat, R.string.day_sun};

    /** 按当前配置重画时间段类型列表 */
    private void renderTimeGroups() {
        if (timeGroupList == null) return;
        List<TimeGroup> groups = BeaconPrefs.getTimeGroups(this);
        timeGroupList.removeAllViews();
        txtTimeGroupEmpty.setVisibility(groups.isEmpty()
                ? android.view.View.VISIBLE : android.view.View.GONE);

        for (TimeGroup g : groups) {
            android.view.View row = getLayoutInflater()
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
            android.view.View handle = row.findViewById(R.id.drag_handle);
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
    private void attachGroupDrag(final android.view.View handle, final android.view.View row) {
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
                        row.addOnLayoutChangeListener(new android.view.View.OnLayoutChangeListener() {
                            @Override
                            public void onLayoutChange(android.view.View x, int l, int t, int r, int b,
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
    private int groupIndexUnder(android.view.View dragged) {
        int[] dloc = new int[2];
        dragged.getLocationOnScreen(dloc);
        float centerY = dloc[1] + dragged.getHeight() / 2f;
        for (int i = 0; i < timeGroupList.getChildCount(); i++) {
            android.view.View child = timeGroupList.getChildAt(i);
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
        List<TimeGroup> ordered = new java.util.ArrayList<>();
        for (int i = 0; i < timeGroupList.getChildCount(); i++) {
            Object tag = timeGroupList.getChildAt(i).getTag();
            if (tag instanceof TimeGroup) ordered.add((TimeGroup) tag);
        }
        // 行数对不上说明没全部打上 tag，宁可不写，也别把配置写乱
        if (ordered.isEmpty() || ordered.size() != timeGroupList.getChildCount()) return;
        BeaconPrefs.setTimeGroups(this, ordered);
    }

    /** index < 0 表示新增 */
    private void editTimeGroup(final int index) {
        List<TimeGroup> groups = BeaconPrefs.getTimeGroups(this);
        final TimeGroup editing = (index >= 0 && index < groups.size()) ? groups.get(index) : null;

        editingRanges = new java.util.ArrayList<>();
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

        android.view.View content = getLayoutInflater()
                .inflate(R.layout.dialog_time_group, null, false);
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
                            android.view.View child = chipGroup.getChildAt(i);
                            if (child instanceof com.google.android.material.chip.Chip
                                    && ((com.google.android.material.chip.Chip) child).isChecked()) {
                                Object tag = child.getTag();
                                if (tag instanceof Integer) {
                                    if (days.length() > 0) days.append(',');
                                    days.append((int) (Integer) tag);
                                }
                            }
                        }

                        List<TimeGroup> list = BeaconPrefs.getTimeGroups(this);
                        TimeGroup g = new TimeGroup(name, days.toString(),
                                TimeRanges.format(editingRanges));
                        if (editing == null) {
                            list.add(g);
                        } else if (index < list.size()) {
                            list.set(index, g);
                        }
                        BeaconPrefs.setTimeGroups(this, list);
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
        List<TimeGroup> groups = BeaconPrefs.getTimeGroups(this);
        if (index < 0 || index >= groups.size()) return;
        final TimeGroup g = groups.get(index);

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.time_group_delete)
                .setMessage(getString(R.string.token_delete_message, g.displayName()))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete, (d, w) -> {
                    List<TimeGroup> list = BeaconPrefs.getTimeGroups(this);
                    if (index < list.size()) list.remove(index);
                    BeaconPrefs.setTimeGroups(this, list);
                    renderTimeGroups();
                    Feedback.show(this, R.string.token_deleted);
                })
                .show();
    }

    private void renderEditingRanges() {
        if (editingList == null) return;
        editingList.removeAllViews();
        editingEmpty.setVisibility(editingRanges.isEmpty()
                ? android.view.View.VISIBLE : android.view.View.GONE);

        for (int i = 0; i < editingRanges.size(); i++) {
            final int index = i;
            final int[] range = editingRanges.get(i);

            android.view.View row = getLayoutInflater()
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

    // === 推送距离 ===

    private void editPushDistance() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        input.setText(String.valueOf(BeaconPrefs.getPushMeters(this)));
        input.setSelection(input.getText().length());

        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.settings_push_distance)
                .setMessage(R.string.push_distance_hint)
                .setView(wrapInPadding(input))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.save, null)
                .create();

        dialog.setOnShowListener(x -> dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    int meters = parseIntOr(input.getText().toString(), -1);
                    if (meters < 1 || meters > 100) {
                        Feedback.show(SettingsActivity.this, R.string.push_distance_invalid);
                        return;
                    }
                    BeaconPrefs.setPushMeters(SettingsActivity.this, meters);
                    dialog.dismiss();
                    refresh();
                    Feedback.show(SettingsActivity.this, R.string.detect_setting_saved);
                }));
        dialog.show();
    }

    // === 震动时长（关闭 / 5 秒 / 10 秒 / 15 秒）===

    private static final int[] VIBRATE_OPTIONS = {0, 5, 10, 15};

    private void chooseVibrate() {
        int current = BeaconPrefs.getVibrateNearSec(this);
        CharSequence[] labels = new CharSequence[VIBRATE_OPTIONS.length];
        int checked = 0;
        for (int i = 0; i < VIBRATE_OPTIONS.length; i++) {
            labels[i] = VIBRATE_OPTIONS[i] == 0
                    ? getString(R.string.vibrate_choice_off)
                    : getString(R.string.vibrate_choice_sec, VIBRATE_OPTIONS[i]);
            if (VIBRATE_OPTIONS[i] == current) checked = i;
        }

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.settings_vibrate)
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    BeaconPrefs.setVibrateNearSec(this, VIBRATE_OPTIONS[which]);
                    d.dismiss();
                    refresh();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    // === 前台悬浮提醒（填米数，0 = 关闭）===

    private void editFrontTip() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        input.setText(String.valueOf(BeaconPrefs.getFrontTipMeters(this)));
        input.setSelection(input.getText().length());

        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.settings_front_tip)
                .setMessage(R.string.front_tip_hint)
                .setView(wrapInPadding(input))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.save, null)
                .create();

        dialog.setOnShowListener(x -> dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    int meters = parseIntOr(input.getText().toString(), -1);
                    if (meters < 0 || meters > 100) {
                        Feedback.show(SettingsActivity.this, R.string.front_tip_invalid);
                        return;
                    }
                    BeaconPrefs.setFrontTipMeters(SettingsActivity.this, meters);
                    dialog.dismiss();
                    refresh();
                    Feedback.show(SettingsActivity.this, R.string.detect_setting_saved);
                }));
        dialog.show();
    }

    /** token 只露前后各 4 位，避免截图泄露 */
    private String maskToken(String token) {
        if (token.length() <= 8) return token;
        return token.substring(0, 4) + "…" + token.substring(token.length() - 4);
    }

    // === 接娃人 ===

    private void editPickerName() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(BeaconPrefs.getPickerName(this));
        input.setSelection(input.getText().length());
        input.setHint(R.string.picker_dialog_hint);

        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.picker_dialog_title)
                .setView(wrapInPadding(input))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.save, null)
                .create();

        dialog.setOnShowListener(x -> dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) {
                        Feedback.show(SettingsActivity.this, R.string.picker_empty);
                        return;
                    }
                    BeaconPrefs.setPickerName(SettingsActivity.this, name);
                    dialog.dismiss();
                    refresh();
                }));
        dialog.show();
    }

    // === PushPlus 通道列表 ===

    /** 没有 PushPlus 账号的，直接跳官网注册，回来再粘贴 token */
    private void openPushPlusSite() {
        try {
            startActivity(new android.content.Intent(
                    android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse("https://www.pushplus.plus/")));
        } catch (Exception e) {
            Feedback.show(this, R.string.token_register_failed);
        }
    }

    /** 按当前配置重画列表：一条一行，开着的高亮 */
    private void renderTokens() {
        if (tokenList == null) return;
        List<PushToken> tokens = BeaconPrefs.getPushTokens(this);
        tokenList.removeAllViews();
        txtTokenEmpty.setVisibility(tokens.isEmpty()
                ? android.view.View.VISIBLE : android.view.View.GONE);

        for (int i = 0; i < tokens.size(); i++) {
            final int index = i;
            PushToken t = tokens.get(i);

            android.view.View row = getLayoutInflater()
                    .inflate(R.layout.item_push_token, tokenList, false);
            TextView name = row.findViewById(R.id.txt_token_name);
            TextView state = row.findViewById(R.id.txt_token_state);
            TextView value = row.findViewById(R.id.txt_token_value);

            name.setText(t.displayName());
            state.setText(t.enabled ? R.string.token_state_on : R.string.token_state_off);
            state.setTextColor(androidx.core.content.ContextCompat.getColor(this,
                    t.enabled ? R.color.primary : R.color.text_secondary));
            value.setText(maskToken(t.token));

            // 点一下切换这条是否参与推送
            row.setOnClickListener(v -> toggleToken(index));
            // 长按修改名称 / token
            row.setOnLongClickListener(v -> {
                showTokenDialog(index);
                return true;
            });
            tokenList.addView(row);
            // 每条下面都跟一条分隔线，和设置页其它条目保持一致的观感
            tokenList.addView(makeDivider());
        }
    }

    /** 和 activity_settings.xml 里那种 1dp、左右缩进 20dp 的线一样 */
    private android.view.View makeDivider() {
        android.view.View line = new android.view.View(this);
        int side = (int) (20 * getResources().getDisplayMetrics().density + 0.5f);
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                (int) (1 * getResources().getDisplayMetrics().density + 0.5f));
        lp.leftMargin = side;
        lp.rightMargin = side;
        line.setLayoutParams(lp);
        line.setBackgroundColor(androidx.core.content.ContextCompat.getColor(this, R.color.divider));
        return line;
    }

    private void toggleToken(int index) {
        List<PushToken> tokens = BeaconPrefs.getPushTokens(this);
        if (index < 0 || index >= tokens.size()) return;
        PushToken t = tokens.get(index);
        t.enabled = !t.enabled;
        BeaconPrefs.setPushTokens(this, tokens);
        renderTokens();
        Feedback.show(this, t.enabled
                ? getString(R.string.token_switched_on, t.displayName())
                : getString(R.string.token_switched_off, t.displayName()));
    }

    /** index < 0 表示新增，否则是修改 */
    private void showTokenDialog(final int index) {
        List<PushToken> tokens = BeaconPrefs.getPushTokens(this);
        final PushToken editing =
                (index >= 0 && index < tokens.size()) ? tokens.get(index) : null;

        android.view.View content = getLayoutInflater()
                .inflate(R.layout.dialog_push_token, null, false);
        final EditText edtName = content.findViewById(R.id.edt_token_name);
        final EditText edtValue = content.findViewById(R.id.edt_token_value);
        if (editing != null) {
            edtName.setText(editing.name);
            edtValue.setText(editing.token);
            edtName.setSelection(edtName.getText().length());
        }

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this)
                .setTitle(editing == null ? R.string.token_add_title : R.string.token_edit_title)
                .setView(content)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.save, null);
        if (editing != null) {
            builder.setNeutralButton(R.string.delete, null);
        }

        final androidx.appcompat.app.AlertDialog dialog = builder.create();
        dialog.setOnShowListener(x -> {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                    .setOnClickListener(v -> {
                        String name = edtName.getText().toString().trim();
                        String token = edtValue.getText().toString().trim();
                        if (token.isEmpty()) {
                            Feedback.show(SettingsActivity.this, R.string.token_blank);
                            return;
                        }

                        List<PushToken> list = BeaconPrefs.getPushTokens(this);
                        // 同一条 token 不许重复加，否则一次到达会推两条
                        for (int i = 0; i < list.size(); i++) {
                            if (editing != null && i == index) continue;
                            if (list.get(i).token.equals(token)) {
                                Feedback.show(SettingsActivity.this, R.string.token_duplicate);
                                return;
                            }
                        }

                        if (editing == null) {
                            if (name.isEmpty()) {
                                name = getString(R.string.token_default_name, list.size() + 1);
                            }
                            list.add(new PushToken(name, token, true));
                        } else {
                            if (name.isEmpty()) {
                                name = getString(R.string.token_default_name, index + 1);
                            }
                            editing.name = name;
                            editing.token = token;
                            list.set(index, editing);
                        }
                        BeaconPrefs.setPushTokens(SettingsActivity.this, list);
                        dialog.dismiss();
                        renderTokens();
                        Feedback.show(SettingsActivity.this, R.string.detect_setting_saved);
                    });

            android.widget.Button neutral =
                    dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL);
            if (neutral != null) {
                neutral.setOnClickListener(v -> {
                    dialog.dismiss();
                    confirmDeleteToken(index);
                });
            }
        });
        dialog.show();
    }

    private void confirmDeleteToken(final int index) {
        List<PushToken> tokens = BeaconPrefs.getPushTokens(this);
        if (index < 0 || index >= tokens.size()) return;
        final PushToken t = tokens.get(index);

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.token_delete_title)
                .setMessage(getString(R.string.token_delete_message, t.displayName()))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete, (d, w) -> {
                    List<PushToken> list = BeaconPrefs.getPushTokens(this);
                    if (index < list.size()) list.remove(index);
                    BeaconPrefs.setPushTokens(this, list);
                    renderTokens();
                    Feedback.show(this, R.string.token_deleted);
                })
                .show();
    }

    // === 推送冷却 ===

    private void editPushCooldown() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.setText(String.valueOf(BeaconPrefs.getPushCooldownMin(this) / 60.0));
        input.setSelection(input.getText().length());
        input.setHint("2");

        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.settings_push_cooldown)
                .setMessage(R.string.push_cooldown_hint)
                .setView(wrapInPadding(input))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.save, null)
                .create();

        dialog.setOnShowListener(x -> dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    double hours = parseDoubleOr(input.getText().toString(), -1);
                    int minutes = (int) Math.round(hours * 60);
                    if (hours <= 0 || minutes < 1 || minutes > 24 * 60) {
                        Feedback.show(SettingsActivity.this, R.string.push_cooldown_invalid);
                        return;
                    }
                    BeaconPrefs.setPushCooldownMin(SettingsActivity.this, minutes);
                    dialog.dismiss();
                    refresh();
                    Feedback.show(SettingsActivity.this, R.string.detect_setting_saved);
                }));
        dialog.show();
    }

    /** 120 → 「2 小时」，90 → 「1.5 小时」，30 → 「30 分钟」 */
    private String formatMinutes(int minutes) {
        if (minutes < 60) return getString(R.string.minutes_value, minutes);
        if (minutes % 60 == 0) return getString(R.string.hours_value, minutes / 60);
        return getString(R.string.hours_value_float, minutes / 60.0);
    }

    private double parseDoubleOr(String s, double fallback) {
        if (s == null) return fallback;
        try {
            return Double.parseDouble(s.trim().replace(",", "."));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    // === 图标检测间隔 ===

    private void editDetectInterval() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        input.setText(String.valueOf(BeaconPrefs.getDetectIntervalSec(this)));
        input.setSelection(input.getText().length());

        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.detect_interval_title)
                .setMessage(R.string.detect_interval_hint)
                .setView(wrapInPadding(input))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.save, null)
                .create();

        dialog.setOnShowListener(x -> dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    int sec = parseIntOr(input.getText().toString(), -1);
                    if (sec < 1 || sec > 600) {
                        Feedback.show(SettingsActivity.this, R.string.detect_interval_invalid);
                        return;
                    }
                    BeaconPrefs.setDetectIntervalSec(SettingsActivity.this, sec);
                    dialog.dismiss();
                    refresh();
                    Feedback.show(SettingsActivity.this, R.string.detect_setting_saved);
                }));
        dialog.show();
    }

    // === 图标变色距离 ===

    private void editNearDistance() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        input.setText(String.valueOf(BeaconPrefs.getNearMeters(this)));
        input.setSelection(input.getText().length());

        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.near_distance_title)
                .setMessage(R.string.near_distance_hint)
                .setView(wrapInPadding(input))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.save, null)
                .create();

        dialog.setOnShowListener(x -> dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    int meters = parseIntOr(input.getText().toString(), -1);
                    if (meters < 1 || meters > 100) {
                        Feedback.show(SettingsActivity.this, R.string.near_distance_invalid);
                        return;
                    }
                    BeaconPrefs.setNearMeters(SettingsActivity.this, meters);
                    dialog.dismiss();
                    refresh();
                    Feedback.show(SettingsActivity.this, R.string.detect_setting_saved);
                }));
        dialog.show();
    }

    private int parseIntOr(String s, int fallback) {
        if (s == null) return fallback;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private android.view.View wrapInPadding(android.view.View child) {
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        android.widget.FrameLayout box = new android.widget.FrameLayout(this);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(child, new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
        return box;
    }
}
