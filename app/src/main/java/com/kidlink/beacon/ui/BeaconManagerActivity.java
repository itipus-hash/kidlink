package com.kidlink.beacon.ui;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.kidlink.beacon.R;
import com.kidlink.beacon.data.BeaconDbHelper;
import com.kidlink.beacon.data.BeaconEntity;
import com.kidlink.beacon.data.BeaconPrefs;
import com.kidlink.beacon.ui.widget.Feedback;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 蓝牙信标管理界面
 *
 * 网格手势（按下即滑就拖，三种手势互不打架）：
 * - 点按（未移动就松手）→ 选中 / 取消选中（绿色高亮，最多 3 个）
 * - 移动超过 touchSlop → 拖动排序（占位格永远是最后一格）
 * - 按住不动 ≥500ms → 弹出菜单（自定义名称 / 分享信标 / 查看信标信息）
 *   清除冷却、解绑设备、删除信标都收在「查看信标信息」里，菜单只留常用的三项
 *
 * 最后一格永远是 [+ 添加] 占位格：点击弹出「蓝牙扫描 / 扫码导入」选择。
 */
public class BeaconManagerActivity extends AppCompatActivity {

    private static final int TYPE_BEACON = 0;
    private static final int TYPE_ADD = 1;
    private static final long HOLD_MS = 500L;

    /**
     * 一个信标都没有时，底部那条推荐里给的淘宝链接。
     *
     * 放在这里而不是首页：用户「手里没硬件」这件事，是在信标管理页发现的 ——
     * 进来一看是空的，这时候顺手给型号最合适。
     */
    private static final String SHOP_URL = "https://m.tb.cn/h.j9RiIck";

    private BeaconDbHelper dbHelper;
    private BeaconAdapter adapter;
    private RecyclerView recyclerView;
    /** 空状态里的购买建议整块（有信标就隐藏） */
    private View layoutBuyTip;
    /** 已选中的信标 id，最多 BeaconPrefs.MAX_SELECTED 个 */
    private List<Long> selectedIds = new ArrayList<>();

    private final Handler handler = new Handler(Looper.getMainLooper());

    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(),
                    result -> { /* 结果不阻断使用，操作时会再次校验 */ });

    private final ActivityResultLauncher<Intent> resultLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                    result -> reload());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_beacon_manager);

        dbHelper = new BeaconDbHelper(this);
        selectedIds = BeaconPrefs.getSelectedIds(this);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        recyclerView = findViewById(R.id.recycler_beacons);
        recyclerView.setLayoutManager(new GridLayoutManager(this, 2));

        adapter = new BeaconAdapter();
        recyclerView.setAdapter(adapter);

        // 空状态里的购买建议：整块可点去淘宝，内层那行单独可点看协议说明
        layoutBuyTip = findViewById(R.id.layout_buy_tip);
        layoutBuyTip.setOnClickListener(v -> openShop());
        findViewById(R.id.txt_buy_proto).setOnClickListener(v -> showProtocolDialog());

        requestPermissions();
        reload();
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    @Override
    protected void onPause() {
        super.onPause();
    }

    private void requestPermissions() {
        List<String> perms = new ArrayList<>();
        perms.add(Manifest.permission.CAMERA);
        perms.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_SCAN);
            perms.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        List<String> toRequest = new ArrayList<>();
        for (String p : perms) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                toRequest.add(p);
            }
        }
        if (!toRequest.isEmpty()) {
            permissionLauncher.launch(toRequest.toArray(new String[0]));
        }
    }

    private void reload() {
        BeaconPrefs.ensureAutoSelect(this, dbHelper);
        selectedIds = BeaconPrefs.getSelectedIds(this);
        List<BeaconEntity> all = dbHelper.getAllBeacons();
        adapter.setData(all);
        if (layoutBuyTip != null) {
            layoutBuyTip.setVisibility(all.isEmpty() ? View.VISIBLE : View.GONE);
        }
    }

    /**
     * 空状态里那条购买建议跳淘宝。
     * 这个 APP 装完是空的，第一道门槛不在软件里，而在于手里得有颗 iBeacon ——
     * 与其让人自己去找兼容型号，不如把用过的那颗直接摆出来。
     */
    private void openShop() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(SHOP_URL)));
        } catch (Exception e) {
            Feedback.show(this, R.string.open_shop_failed);
        }
    }

    /** 「为什么只支持 iBeacon」：买错硬件的代价比读一段字大，所以写在界面上 */
    private void showProtocolDialog() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.protocol_title)
                .setMessage(R.string.protocol_body)
                .setPositiveButton(R.string.got_it, null)
                .show();
    }

    private void tip(CharSequence text) {
        Feedback.show(findViewById(android.R.id.content), text);
    }

    private void tipLong(CharSequence text) {
        Feedback.show(findViewById(android.R.id.content), text, true);
    }

    // === 添加 ===

    /**
     * 点击 + 占位格：让用户选择添加方式
     */
    private void showAddOptions() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.add_beacon)
                .setItems(new CharSequence[]{
                        getString(R.string.add_by_ble),
                        getString(R.string.add_by_qr)
                }, (dialog, which) -> {
                    if (which == 0) {
                        startBleScan();
                    } else {
                        startQrScan();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void startBleScan() {
        if (!hasBlePermission()) {
            requestPermissions();
            tip(getString(R.string.perm_bluetooth));
            return;
        }
        resultLauncher.launch(new Intent(this, ScanBeaconActivity.class));
    }

    private void startQrScan() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions();
            tip(getString(R.string.perm_camera));
            return;
        }
        resultLauncher.launch(new Intent(this, QRScanActivity.class));
    }

    private boolean hasBlePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                    == PackageManager.PERMISSION_GRANTED
                    && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    // === 选择 / 菜单 ===

    /**
     * 单击：点一下选中，再点一下取消。
     * 最多同时选中 2 个（超出给提示），最少保留 1 个（最后一个不让取消）。
     */
    private void selectBeacon(BeaconEntity b) {
        boolean wasSelected = selectedIds.contains(b.getId());
        int r = BeaconPrefs.toggleSelected(this, b.getId());
        if (r == BeaconPrefs.TOGGLE_LIMIT) {
            tipLong(getString(R.string.select_limit_reached, BeaconPrefs.MAX_SELECTED));
            return;
        }
        if (r == BeaconPrefs.TOGGLE_KEEP_ONE) {
            tipLong(getString(R.string.keep_one_selected));
            return;
        }
        selectedIds = BeaconPrefs.getSelectedIds(this);
        adapter.notifyDataSetChanged();
        tip(wasSelected
                ? getString(R.string.unselected_toast, b.getName())
                : getString(R.string.selected_toast, b.getName()));
    }

    // === 自定义名称 ===

    private void showRenameDialog(BeaconEntity b) {
        EditText input = new EditText(this);
        input.setText(b.getName());
        input.setSelection(input.getText().length());
        input.setHint(R.string.rename_hint);
        input.setSingleLine(true);

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.rename_title)
                .setView(wrapInPadding(input))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.save, (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) {
                        tip(getString(R.string.rename_empty));
                        return;
                    }
                    b.setName(name);
                    dbHelper.updateBeacon(b);
                    reload();
                })
                .show();
    }

    // === 推送冷却 / 信标信息 ===

    /**
     * 清除这个信标的推送冷却。
     * 冷却时间是按信标分开记的，两个信标互不影响 —— 这里只清当前这个。
     */
    private void resetPushCooldown(BeaconEntity b) {
        long last = Math.max(BeaconPrefs.getLastPushAt(this, b.getId()),
                BeaconPrefs.getLastApproachAt(this, b.getId()));
        if (last <= 0) {
            tip(getString(R.string.cooldown_none, b.getName()));
            return;
        }
        int cooldownMin = BeaconPrefs.getPushCooldownMin(this);
        if (System.currentTimeMillis() - last >= cooldownMin * 60_000L) {
            tip(getString(R.string.cooldown_none, b.getName()));
            return;
        }
        // 两种推送的时间戳一起清 = 立刻可以再推（含「已靠近」）
        BeaconPrefs.clearCooldown(this, b.getId());
        tipLong(getString(R.string.cooldown_reset_done, b.getName()));
    }

    /**
     * 信标信息：UUID / Major·Minor·蓝牙地址 / 上次推送·冷却，底部一排操作。
     * 长按卡片直接进这里，已经没有单独的长按菜单了。
     */
    private void showBeaconInfo(BeaconEntity b) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        box.setPadding(pad, dp(4), pad, dp(4));

        addInfoRow(box, R.string.info_uuid, b.getUuidFormatted(), true);
        // 三件套和蓝牙地址挤一行：同型号信标只有末尾那串地址不同，放一起最好对比
        addInfoRow(box, R.string.info_major_minor,
                b.getMajor() + " / " + b.getMinor() + " · "
                        + (b.hasMac() ? b.getMac() : getString(R.string.info_mac_short_none)),
                true);

        // 「上次推送」取两种推送里更近的那一次：近距离和接送成功各有各的冷却，
        // 但用户关心的是「这个信标最近一次什么时候推过」。
        long last = Math.max(BeaconPrefs.getLastPushAt(this, b.getId()),
                BeaconPrefs.getLastApproachAt(this, b.getId()));
        long leftMs = BeaconPrefs.getPushCooldownMin(this) * 60_000L
                - (System.currentTimeMillis() - last);
        String lastText = last <= 0
                ? getString(R.string.info_last_push_none)
                : new java.text.SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                        .format(new java.util.Date(last));
        String cooldownText = (last <= 0 || leftMs <= 0)
                ? getString(R.string.info_cooldown_ready)
                : getString(R.string.info_cooldown_left, formatLeft(leftMs));
        addInfoRow(box, R.string.info_push_state, lastText + " · " + cooldownText, false);

        // 二维码直接嵌在页面里，另一台手机扫一下就能导入，不用再跳到别的页面
        String payload = com.kidlink.beacon.qr.BeaconQrCodec.encodeFull(b);
        if (payload != null && !payload.isEmpty()) {
            int size = dp(200);
            android.graphics.Bitmap qr =
                    com.kidlink.beacon.qr.QrCodeGenerator.generate(payload, size);
            if (qr != null) {
                ImageView iv = new ImageView(this);
                iv.setImageBitmap(qr);
                iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                LinearLayout.LayoutParams lpQr = new LinearLayout.LayoutParams(size, size);
                lpQr.gravity = android.view.Gravity.CENTER_HORIZONTAL;
                lpQr.topMargin = dp(18);
                box.addView(iv, lpQr);
            }
        }

        // 操作区：一行三个
        LinearLayout.LayoutParams lpAction = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);

        TextView btnRename = actionButton(R.string.menu_rename, R.color.primary);
        TextView btnClear = actionButton(R.string.info_clear_cooldown, R.color.primary);
        TextView btnDelete = actionButton(R.string.menu_delete, R.color.danger);

        LinearLayout row = actionRow();
        row.addView(btnRename, lpAction);
        row.addView(btnClear, lpAction);
        row.addView(btnDelete, lpAction);
        box.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(box);

        final androidx.appcompat.app.AlertDialog dialog =
                new MaterialAlertDialogBuilder(this)
                        .setTitle(getString(R.string.beacon_info_title) + " · " + b.getName())
                        .setView(scroll)
                        .create();
        // 底部不放「确定」，点弹窗外面的空白处直接关掉
        dialog.setCanceledOnTouchOutside(true);

        btnRename.setOnClickListener(v -> {
            dialog.dismiss();
            showRenameDialog(b);
        });
        btnClear.setOnClickListener(v -> {
            dialog.dismiss();
            resetPushCooldown(b);
        });
        btnDelete.setOnClickListener(v -> {
            dialog.dismiss();
            confirmDelete(b);
        });
        dialog.show();
    }

    private LinearLayout actionRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(6), 0, dp(6));
        return row;
    }

    /** 信息页底部的一个文字按钮，几个按钮等分一行 */
    private TextView actionButton(int textRes, int colorRes) {
        TextView tv = new TextView(this);
        tv.setText(textRes);
        tv.setTextSize(14);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        tv.setTextColor(ContextCompat.getColor(this, colorRes));
        tv.setGravity(android.view.Gravity.CENTER);
        int padV = dp(12);
        tv.setPadding(0, padV, 0, padV);
        android.util.TypedValue out = new android.util.TypedValue();
        if (getTheme().resolveAttribute(android.R.attr.selectableItemBackground, out, true)) {
            tv.setBackgroundResource(out.resourceId);
        }
        return tv;
    }

    /** 一行信息：上面一句灰色小标签，下面一行可长按复制的值 */
    private void addInfoRow(LinearLayout parent, int labelRes, String value, boolean mono) {
        TextView label = new TextView(this);
        label.setText(labelRes);
        label.setTextSize(12);
        label.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        LinearLayout.LayoutParams lpLabel = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpLabel.topMargin = dp(10);
        parent.addView(label, lpLabel);

        TextView tv = new TextView(this);
        tv.setText(value);
        tv.setTextSize(14);
        tv.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        tv.setTextIsSelectable(true);
        if (mono) tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        parent.addView(tv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /** 剩余冷却：不到 1 小时说分钟，超过说小时 */
    private String formatLeft(long leftMs) {
        long minutes = Math.max(1, (leftMs + 59_999L) / 60_000L);
        if (minutes < 60) return getString(R.string.minutes_value, (int) minutes);
        return getString(R.string.hours_value_float, minutes / 60.0);
    }

    // === 删除 ===

    private void confirmDelete(BeaconEntity b) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.menu_delete)
                .setMessage(getString(R.string.confirm_delete) + "\n\n" + b.getName())
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.menu_delete, (d, w) -> {
                    dbHelper.deleteBeacon(b.getId());
                    // reload 里的 ensureAutoSelect → pruneSelected 会把失效 id 清掉
                    reload();
                    tip(getString(R.string.deleted_toast, b.getName()));
                })
                .show();
    }

    // === 工具 ===

    private View wrapInPadding(View child) {
        FrameLayout container = new FrameLayout(this);
        int h = (int) (20 * getResources().getDisplayMetrics().density);
        int v = (int) (8 * getResources().getDisplayMetrics().density);
        container.setPadding(h, v, h, 0);
        container.addView(child, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return container;
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    // === Adapter ===

    private class BeaconAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        private final List<BeaconEntity> items = new ArrayList<>();

        void setData(List<BeaconEntity> data) {
            items.clear();
            items.addAll(data);
            notifyDataSetChanged();
        }

        List<BeaconEntity> getBeacons() {
            return items;
        }

        /** 真·插入（remove + add），任何跨度都正确 */
        boolean moveItem(int from, int to) {
            if (from == to) return false;
            if (from < 0 || to < 0 || from >= items.size() || to >= items.size()) return false;
            items.add(to, items.remove(from));
            notifyItemMoved(from, to);
            return true;
        }

        @Override
        public int getItemViewType(int position) {
            return position < items.size() ? TYPE_BEACON : TYPE_ADD;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LayoutInflater inflater = LayoutInflater.from(parent.getContext());
            if (viewType == TYPE_ADD) {
                return new AddVH(inflater.inflate(R.layout.item_beacon_add, parent, false));
            }
            return new BeaconVH(inflater.inflate(R.layout.item_beacon_grid, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            if (holder instanceof BeaconVH) {
                ((BeaconVH) holder).bind(items.get(position));
            }
        }

        @Override
        public int getItemCount() {
            return items.size() + 1; // + 占位格
        }

        class BeaconVH extends RecyclerView.ViewHolder {
            final MaterialCardView card;
            final View miniCircle;
            final ImageView ivDrag;
            final TextView txtName;
            final TextView txtTag;

            // 手势状态（每个 ViewHolder 自持，回收复用自动重置）
            float downX, downY;
            /** 按下时手指相对卡片左上角的偏移，拖动时靠它让卡片贴着手指走 */
            float grabDx, grabDy;
            boolean moved, longHoldFired;
            int dragIndex = -1;
            Runnable pendingHold;
            /** 拖动期间临时摘掉的条目动画，松手后还回去 */
            RecyclerView.ItemAnimator savedAnimator;

            BeaconVH(@NonNull View itemView) {
                super(itemView);
                card = itemView.findViewById(R.id.card);
                miniCircle = itemView.findViewById(R.id.mini_circle);
                ivDrag = itemView.findViewById(R.id.iv_drag);
                txtName = itemView.findViewById(R.id.txt_name);
                txtTag = itemView.findViewById(R.id.txt_tag);
            }

            void bind(BeaconEntity b) {
                boolean selected = selectedIds.contains(b.getId());

                card.setStrokeWidth(dp(2));
                card.setStrokeColor(selected
                        ? ContextCompat.getColor(BeaconManagerActivity.this, R.color.primary)
                        : ContextCompat.getColor(BeaconManagerActivity.this, R.color.transparent));
                miniCircle.setBackgroundResource(selected
                        ? R.drawable.bg_circle_mini_green
                        : R.drawable.bg_circle_mini_gray);

                txtName.setText(b.getName());
                txtName.setTextColor(ContextCompat.getColor(BeaconManagerActivity.this,
                        selected ? R.color.primary_dark : R.color.text_primary));

                if (selected) {
                    txtTag.setText(R.string.tag_current);
                    txtTag.setTextColor(ContextCompat.getColor(BeaconManagerActivity.this, R.color.primary));
                } else {
                    txtTag.setText("");
                }

                attachGestures();
            }

            /**
             * 按下即滑就拖：
             * 点按 → 选择；滑动超过 touchSlop → 拖动换位；按住 500ms 不动 → 菜单
             */
            @SuppressLint("ClickableViewAccessibility")
            private void attachGestures() {
                final int slop = ViewConfiguration.get(itemView.getContext()).getScaledTouchSlop();

                itemView.setOnTouchListener((v, ev) -> {
                    switch (ev.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN: {
                            // 用 raw 坐标：拖动时卡片会被平移，x/y 基准会漂
                            downX = ev.getRawX();
                            downY = ev.getRawY();
                            moved = false;
                            longHoldFired = false;
                            dragIndex = getBindingAdapterPosition();
                            cancelPendingHold();
                            pendingHold = () -> {
                                if (moved) return;
                                int pos = getBindingAdapterPosition();
                                if (pos == RecyclerView.NO_POSITION || pos >= items.size()) return;
                                longHoldFired = true;
                                // 长按直接进信息页，没有再套一层菜单
                                showBeaconInfo(items.get(pos));
                            };
                            handler.postDelayed(pendingHold, HOLD_MS);
                            return true;
                        }
                        case MotionEvent.ACTION_MOVE: {
                            float dx = ev.getRawX() - downX;
                            float dy = ev.getRawY() - downY;

                            if (!moved && (Math.abs(dx) > slop || Math.abs(dy) > slop)) {
                                moved = true;
                                cancelPendingHold();
                                if (dragIndex < 0 || dragIndex >= items.size()) {
                                    dragIndex = -1;
                                    return true;
                                }
                                // 记住手指抓在卡片的哪个点上，之后每帧都按「卡片左上角贴着手指」定位
                                int[] loc = new int[2];
                                itemView.getLocationOnScreen(loc);
                                grabDx = downX - loc[0];
                                grabDy = downY - loc[1];
                                // 拖动期间关掉条目动画：交换要立刻生效，否则卡片被动画拽回去，看着就是不跟手
                                savedAnimator = recyclerView.getItemAnimator();
                                recyclerView.setItemAnimator(null);
                                // 禁止 RecyclerView 把手势抢去当滚动，否则拖到一半就被 CANCEL
                                if (v.getParent() != null) {
                                    v.getParent().requestDisallowInterceptTouchEvent(true);
                                }
                                itemView.setTranslationZ(dp(12));
                                itemView.setScaleX(1.02f);
                                itemView.setScaleY(1.02f);
                            }

                            if (moved && dragIndex >= 0 && dragIndex < items.size()) {
                                // 绝对定位：布局位置 + translation = 手指位置 − 抓取偏移。
                                // 不用累加，因为换位后布局位置会变，累加会越拖越偏。
                                int[] loc = new int[2];
                                itemView.getLocationOnScreen(loc);
                                float baseX = loc[0] - itemView.getTranslationX();
                                float baseY = loc[1] - itemView.getTranslationY();
                                itemView.setTranslationX(ev.getRawX() - grabDx - baseX);
                                itemView.setTranslationY(ev.getRawY() - grabDy - baseY);

                                int target = findTargetUnder(ev.getRawX(), ev.getRawY());
                                if (target >= 0 && target != dragIndex) {
                                    // 交换前记下卡片的屏幕位置；换位后布局会把它挪走，
                                    // 在布局回调里把 translation 补回来，视觉上就不会闪一下
                                    final int[] keep = new int[2];
                                    itemView.getLocationOnScreen(keep);
                                    if (moveItem(dragIndex, target)) {
                                        dragIndex = target;
                                        itemView.addOnLayoutChangeListener(
                                                new View.OnLayoutChangeListener() {
                                            @Override
                                            public void onLayoutChange(View v2, int l, int t,
                                                                       int r, int b,
                                                                       int ol, int ot, int orr, int ob) {
                                                itemView.removeOnLayoutChangeListener(this);
                                                int[] after = new int[2];
                                                itemView.getLocationOnScreen(after);
                                                itemView.setTranslationX(itemView.getTranslationX()
                                                        + keep[0] - after[0]);
                                                itemView.setTranslationY(itemView.getTranslationY()
                                                        + keep[1] - after[1]);
                                            }
                                        });
                                    }
                                }
                            }
                            return true;
                        }
                        case MotionEvent.ACTION_UP:
                        case MotionEvent.ACTION_CANCEL: {
                            cancelPendingHold();
                            boolean wasDragging = moved && dragIndex >= 0;

                            if (wasDragging) {
                                dragIndex = -1;
                                // 松手：从当前位置滑到格子的自然位置（translation 归零即落位）
                                itemView.animate()
                                        .translationX(0f)
                                        .translationY(0f)
                                        .scaleX(1f)
                                        .scaleY(1f)
                                        .setDuration(130)
                                        .start();
                                itemView.setTranslationZ(0f);
                                if (v.getParent() != null) {
                                    v.getParent().requestDisallowInterceptTouchEvent(false);
                                }
                                // 拖动结束 → 落库
                                dbHelper.updateSortOrders(items);
                                // 等回弹动画走完再把条目动画还回去
                                handler.postDelayed(() -> {
                                    if (savedAnimator != null) {
                                        recyclerView.setItemAnimator(savedAnimator);
                                        savedAnimator = null;
                                    }
                                }, 170);
                            } else if (ev.getActionMasked() == MotionEvent.ACTION_UP
                                    && !moved && !longHoldFired) {
                                int pos = getBindingAdapterPosition();
                                if (pos != RecyclerView.NO_POSITION && pos < items.size()) {
                                    // 单击只做"选为当前信标"；检测间隔/变色距离已移到设置页
                                    selectBeacon(items.get(pos));
                                }
                            }
                            return true;
                        }
                        default:
                            return false;
                    }
                });
            }

            /**
             * 手指下面压着哪一格。
             *
             * 用 getLeft()/getTop() 这些**布局坐标**（不受 translation 影响），
             * 否则正在跟手的那张卡永远判定成"自己"，永远换不了位。
             * 占位格（+ 添加）不参与换位，所以上限是 items.size()。
             */
            private int findTargetUnder(float rawX, float rawY) {
                int[] rvLoc = new int[2];
                recyclerView.getLocationOnScreen(rvLoc);
                float x = rawX - rvLoc[0];
                float y = rawY - rvLoc[1];
                for (int i = 0; i < recyclerView.getChildCount(); i++) {
                    View child = recyclerView.getChildAt(i);
                    if (child == itemView) continue;
                    if (x >= child.getLeft() && x < child.getRight()
                            && y >= child.getTop() && y < child.getBottom()) {
                        int pos = recyclerView.getChildAdapterPosition(child);
                        return pos < items.size() ? pos : -1;
                    }
                }
                return -1;
            }

            private void cancelPendingHold() {
                if (pendingHold != null) {
                    handler.removeCallbacks(pendingHold);
                    pendingHold = null;
                }
            }
        }

        class AddVH extends RecyclerView.ViewHolder {
            AddVH(@NonNull View itemView) {
                super(itemView);
                itemView.setOnClickListener(v -> showAddOptions());
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
    }
}
