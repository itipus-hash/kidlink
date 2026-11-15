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

    /** 自愿捐赠收款邮箱；点设置页底部那行就复制它 */
    private static final String DONATE_EMAIL = "supit@163.com";
    /** 开源仓库地址；点「项目地址」那行用浏览器打开 */
    private static final String REPO_URL = "https://github.com/itipus-hash/kidlink";

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
    private com.google.android.material.switchmaterial.SwitchMaterial swAutoMinimize;
    /** refresh() 回填开关时置 true，避免把「回填」当成用户点击去弹权限对话框 */
    private boolean bindingSwitches = false;

    /** 指定 WiFi 不提醒：设置页那一行的两行文字 */
    private TextView txtWifiValue;
    private TextView txtWifiHint;

    /** WiFi 名单弹窗打开时才有值，用来在权限回调 / 增删后原地重画 */
    private TextView wifiDlgCurrent;
    private android.widget.LinearLayout wifiDlgList;
    private TextView wifiDlgEmpty;

    /**
     * 读 WiFi 名称要「位置信息」权限（Android 10 起 SSID 算位置信息）。
     * 权限给到之后直接把当前 WiFi 加进名单，不用让用户再点一次。
     */
    private final androidx.activity.result.ActivityResultLauncher<String> locationLauncher =
            registerForActivityResult(
                    new androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
                    granted -> {
                        if (granted) {
                            addCurrentWifi();
                        } else {
                            Feedback.show(this, R.string.wifi_block_need_location);
                        }
                    });

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
        txtVibrate = findViewById(R.id.txt_vibrate_value);
        txtFrontTip = findViewById(R.id.txt_front_tip_value);
        txtPushChevron = findViewById(R.id.txt_push_chevron);
        txtNearChevron = findViewById(R.id.txt_near_chevron);

        groupPushDetails = findViewById(R.id.group_push_details);
        groupNearDetails = findViewById(R.id.group_near_details);
        groupSystemDetails = findViewById(R.id.group_system_details);
        txtSystemChevron = findViewById(R.id.txt_system_chevron);
        swExitAfter = findViewById(R.id.sw_exit_after);
        swAutoMinimize = findViewById(R.id.sw_auto_minimize);
        txtWifiValue = findViewById(R.id.txt_wifi_value);
        txtWifiHint = findViewById(R.id.txt_wifi_hint);

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

        // 打开就自动最小化：属于「后台常驻」类开关，打开时必须把 ROM 的省电策略说清楚，
        // 否则用户以为开了就一定收得到，实际被系统清掉。
        swAutoMinimize.setOnCheckedChangeListener((btn, checked) -> {
            if (bindingSwitches) return;
            BeaconPrefs.setAutoMinimize(this, checked);
            if (checked) {
                showBackgroundPermissionTip();
            } else {
                Feedback.show(SettingsActivity.this, R.string.auto_minimize_off_toast);
            }
        });

        // 指定 WiFi 不提醒：点开名单管理，里面可以一键加当前 WiFi
        findViewById(R.id.row_wifi_block).setOnClickListener(v -> showWifiBlockDialog());

        // 开源免费说明：整块可点，轻触把支付宝邮箱复制到剪贴板
        findViewById(R.id.row_donate).setOnClickListener(v -> {
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(android.content.ClipData.newPlainText("email", DONATE_EMAIL));
            }
            Feedback.show(v, R.string.donate_copied);
        });

        // 项目地址单独一行：内层可点会先吃掉事件，不影响外层「点整块复制邮箱」
        findViewById(R.id.txt_repo).setOnClickListener(v -> {
            try {
                startActivity(new android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse(REPO_URL)));
            } catch (Exception e) {
                Feedback.show(this, R.string.open_repo_failed);
            }
        });

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
        bindingSwitches = true;
        swAutoMinimize.setChecked(BeaconPrefs.isAutoMinimize(this));
        bindingSwitches = false;

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

        int vibrateSec = BeaconPrefs.getVibrateNearSec(this);
        txtVibrate.setText(vibrateSec <= 0
                ? getString(R.string.vibrate_off)
                : getString(R.string.vibrate_item, vibrateSec));

        int frontTip = BeaconPrefs.getFrontTipMeters(this);
        txtFrontTip.setText(frontTip <= 0
                ? getString(R.string.vibrate_choice_off)
                : getString(R.string.front_tip_item, frontTip));

        renderWifiBlock();
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

    /** 打开「自动最小化」时弹一次：把 ROM 需要放行的三项讲清楚，并给一个直达入口 */
    private void showBackgroundPermissionTip() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.auto_minimize_perm_title)
                .setMessage(R.string.auto_minimize_perm_body)
                .setNegativeButton(R.string.got_it, null)
                .setPositiveButton(R.string.go_to_settings, (d, w) -> openAppSettings())
                .show();
    }

    /** 跳到本应用的系统设置页（自启动 / 电池优化都在这一层下面） */
    private void openAppSettings() {
        try {
            startActivity(new android.content.Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.fromParts("package", getPackageName(), null)));
        } catch (Exception e) {
            Feedback.show(this, R.string.open_settings_failed);
        }
    }

    // === 指定 WiFi 不提醒 ===
    //
    // 这一层要解决的是「距离表达不了位置」的问题：在家 / 在公司，距离可能很小，
    // 但那里本来就不需要提醒。把 WiFi 名称加进名单，连上就直接不推。
    // 名单存在 prefs 里，判定在 MainActivity 真正要发请求之前做一次。

    /** 设置页那一行：多少条名单 + 当前连的 WiFi（以及此刻是否被屏蔽） */
    private void renderWifiBlock() {
        if (txtWifiValue == null) return;
        List<String> list = BeaconPrefs.getWifiBlockList(this);
        txtWifiValue.setText(list.isEmpty()
                ? getString(R.string.wifi_block_value_none)
                : getString(R.string.wifi_block_value_count, list.size()));

        String ssid = com.kidlink.beacon.data.WifiGate.currentSsid(this);
        if (ssid == null) {
            txtWifiHint.setText(R.string.wifi_block_hint_off);
        } else if (BeaconPrefs.isWifiBlocked(this, ssid)) {
            txtWifiHint.setText(getString(R.string.wifi_block_hint_blocked, ssid));
        } else {
            txtWifiHint.setText(getString(R.string.wifi_block_hint_normal, ssid));
        }
    }

    private void showWifiBlockDialog() {
        android.view.View content = getLayoutInflater()
                .inflate(R.layout.dialog_wifi_block, null, false);
        wifiDlgCurrent = content.findViewById(R.id.txt_wifi_current);
        wifiDlgList = content.findViewById(R.id.wifi_list);
        wifiDlgEmpty = content.findViewById(R.id.txt_wifi_empty);
        content.findViewById(R.id.btn_add_current_wifi)
                .setOnClickListener(v -> addCurrentWifi());

        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.wifi_block_dialog_title)
                .setView(content)
                .setPositiveButton(R.string.wifi_block_done, null)
                .create();
        // 弹窗一关就把引用清掉：权限回调晚到也不会往已经销毁的列表里塞东西
        dialog.setOnDismissListener(d -> {
            wifiDlgCurrent = null;
            wifiDlgList = null;
            wifiDlgEmpty = null;
        });
        dialog.show();
        renderWifiDialog();
    }

    /** 重画名单弹窗里的内容（打开状态时才有效） */
    private void renderWifiDialog() {
        if (wifiDlgList == null) return;
        String ssid = com.kidlink.beacon.data.WifiGate.currentSsid(this);
        wifiDlgCurrent.setText(ssid == null
                ? getString(R.string.wifi_block_current_none)
                : getString(R.string.wifi_block_current, ssid));

        List<String> list = BeaconPrefs.getWifiBlockList(this);
        wifiDlgList.removeAllViews();
        wifiDlgEmpty.setVisibility(list.isEmpty()
                ? android.view.View.VISIBLE : android.view.View.GONE);
        for (String name : list) {
            wifiDlgList.addView(makeWifiRow(name));
        }
    }

    /** 名单一行：名称 + 右侧「删除」 */
    private android.view.View makeWifiRow(final String name) {
        android.widget.LinearLayout row = new android.widget.LinearLayout(this);
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(dp(24), dp(6), dp(12), dp(6));

        TextView label = new TextView(this);
        label.setText(name);
        label.setTextSize(14);
        label.setTextColor(androidx.core.content.ContextCompat
                .getColor(this, R.color.text_primary));
        label.setSingleLine(true);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(label, new android.widget.LinearLayout.LayoutParams(
                0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView del = new TextView(this);
        del.setText(R.string.delete);
        del.setTextSize(14);
        del.setTextColor(androidx.core.content.ContextCompat
                .getColor(this, R.color.danger));
        del.setPadding(dp(12), dp(8), dp(12), dp(8));
        android.util.TypedValue tv = new android.util.TypedValue();
        if (getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true)) {
            del.setBackgroundResource(tv.resourceId);
        }
        del.setOnClickListener(v -> removeWifi(name));
        row.addView(del);
        return row;
    }

    /**
     * 把当前连着的 WiFi 加进名单。
     *
     * 读 SSID 需要「位置信息」权限：没给就先申请，给到之后回到这里继续加，
     * 不用让用户再点一次。仍然读不到（没连 WiFi / 系统位置总开关关着）就如实说原因，
     * 不要静默失败 —— 否则用户会以为功能坏了。
     */
    private void addCurrentWifi() {
        if (!com.kidlink.beacon.data.WifiGate.canReadSsid(this)) {
            locationLauncher.launch(android.Manifest.permission.ACCESS_FINE_LOCATION);
            return;
        }
        String ssid = com.kidlink.beacon.data.WifiGate.currentSsid(this);
        if (ssid == null) {
            Feedback.show(this, com.kidlink.beacon.data.WifiGate.isLocationEnabled(this)
                    ? R.string.wifi_block_no_ssid
                    : R.string.wifi_block_location_off);
            return;
        }
        if (BeaconPrefs.isWifiBlocked(this, ssid)) {
            Feedback.show(this, getString(R.string.wifi_block_exists, ssid));
            return;
        }
        List<String> list = BeaconPrefs.getWifiBlockList(this);
        list.add(ssid);
        BeaconPrefs.setWifiBlockList(this, list);
        renderWifiDialog();
        renderWifiBlock();
        Feedback.show(this, getString(R.string.wifi_block_added, ssid));
    }

    private void removeWifi(String name) {
        List<String> list = BeaconPrefs.getWifiBlockList(this);
        list.remove(name);
        BeaconPrefs.setWifiBlockList(this, list);
        renderWifiDialog();
        renderWifiBlock();
        Feedback.show(this, getString(R.string.wifi_block_removed, name));
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
