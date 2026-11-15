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
import androidx.core.widget.NestedScrollView;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.kidlink.beacon.R;
import com.kidlink.beacon.data.BeaconDbHelper;
import com.kidlink.beacon.data.BeaconEntity;
import com.kidlink.beacon.data.BeaconPrefs;
import com.kidlink.beacon.data.ChildEntity;
import com.kidlink.beacon.qr.ConfigBundleCodec;
import com.kidlink.beacon.ui.widget.Feedback;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 蓝牙信标管理界面 —— 按娃分组。
 *
 * 页面结构（运行时由 rebuildGroups() 拼出来）：
 * <pre>
 *   许小鱼  2 颗                       ▾
 *   [书包]  [奥数包]
 *   [ + 添加到许小鱼 ]
 *
 *   许大鱼  1 颗                       ▸      ← 折叠起来只剩这一行
 *
 *   ＋ 新建一个娃
 * </pre>
 *
 * 一个娃一个分组，一个分组一个两列网格：
 * - 点分组头 → 折叠 / 展开（状态存 prefs，下次进来还是那样）
 * - 长按分组头 → 改名 / 删除这个娃
 * - 网格最后一格永远是「+ 添加」，点了扫进来的信标**直接归这一组**
 * - 卡片点一下进信标信息（所属娃 / 场景标签 / 清冷却 / 删除都在里面）
 * - 按住卡片上下滑 = 组内拖动排序
 *
 * 跨组移动不做拖拽：把一个娃的卡片拖到另一个娃那里，和组内排序的手势太容易冲突，
 * 而且跨组移动是低频操作 —— 进信息页选「所属娃」更明确。
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
    /** 所有分组的容器，每次 reload 全量重建 */
    private LinearLayout layoutGroups;
    private NestedScrollView scrollGroups;
    /** 新建娃（只在已经有娃时显示） */
    private View btnNewChild;
    /** 空状态里的购买建议整块（有信标就隐藏） */
    private View layoutBuyTip;
    /** 还没有娃时的整块入口：新建一个娃 */
    private View layoutEmptyAdd;
    /** children.id → 娃名 */
    private final Map<Long, String> childNames = new HashMap<>();
    /** 当前打开的信标信息弹窗 —— 点「所属娃 / 场景标签」前要先关掉，免得弹窗叠弹窗 */
    private androidx.appcompat.app.AlertDialog infoDialog;
    /**
     * 从哪一组点的「+ 添加」。
     * 扫完 / 扫完码回来时，把还没归属的新信标挂到这个娃下面。
     */
    private long pendingChildId = 0;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(),
                    result -> { /* 结果不阻断使用，操作时会再次校验 */ });

    private final ActivityResultLauncher<Intent> resultLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() == RESULT_OK && pendingChildId > 0) {
                            // 先认领再 ensureChildren —— 否则新信标会被塞给第一个娃
                            dbHelper.assignUnassignedTo(pendingChildId);
                        }
                        pendingChildId = 0;
                        reload();
                    });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_beacon_manager);

        dbHelper = new BeaconDbHelper(this);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        // 右上角「分享」：整套娃 + 信标打成一张（或几页）二维码，换手机不用一颗颗扫
        toolbar.inflateMenu(R.menu.menu_beacon_manager);
        toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.action_share_all) {
                shareAllConfig();
                return true;
            }
            return false;
        });

        layoutGroups = findViewById(R.id.layout_groups);
        scrollGroups = findViewById(R.id.scroll_groups);
        btnNewChild = findViewById(R.id.btn_new_child);
        btnNewChild.setOnClickListener(v -> askNewChild(null));
        layoutEmptyAdd = findViewById(R.id.layout_empty_add);
        // 这块只在「一个娃都没有」时出现，点了就是建娃
        layoutEmptyAdd.setOnClickListener(v -> askNewChild(null));

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

    // === 分组重建 ===

    /**
     * 整页按「娃 → 信标」重拼一遍。
     *
     * 全量重建而不是局部刷新：娃和信标的数量都很少（个位数），
     * 重建几十个 View 的开销远小于维护增量更新的复杂度 ——
     * 改名、改归属、删娃、加信标全走这一条路径，不容易出现「某处忘了刷新」。
     */
    private void reload() {
        // 每颗信标都必须挂在某个娃下面；没有娃就用第一颗信标名建一个
        dbHelper.ensureChildren();

        List<ChildEntity> children = dbHelper.getAllChildren();
        childNames.clear();
        for (ChildEntity c : children) {
            childNames.put(c.getId(), c.displayName());
        }
        List<BeaconEntity> all = dbHelper.getAllBeacons();

        // 重建会导致滚动位置回到顶部，先记下来，重建后补回去
        final int scrollY = scrollGroups == null ? 0 : scrollGroups.getScrollY();
        layoutGroups.removeAllViews();

        if (children.isEmpty()) {
            // 一颗信标都没有：没有分组可显示，只留「添加信标」那一个大入口
            layoutGroups.setVisibility(View.GONE);
        } else {
            layoutGroups.setVisibility(View.VISIBLE);
            for (ChildEntity c : children) {
                addGroupView(c, dbHelper.getBeaconsByChild(c.getId()));
            }
        }

        // 一个娃都没有时整页只剩这一块能点：「新建一个娃」。
        // 有娃之后这块就收起来 —— 每个分组里已经有一格「+ 添加信标」，
        // 再摆一个大块只是重复，而且会让人以为「加信标」和「加进这组」是两件事。
        final boolean noChild = children.isEmpty();
        layoutEmptyAdd.setVisibility(noChild ? View.VISIBLE : View.GONE);
        // 已经有娃了才在底部再给一个「新建一个娃」；没有娃时上面那块就是它，别重复
        btnNewChild.setVisibility(noChild ? View.GONE : View.VISIBLE);
        layoutBuyTip.setVisibility(all.isEmpty() ? View.VISIBLE : View.GONE);

        if (scrollGroups != null) {
            scrollGroups.post(() -> scrollGroups.scrollTo(0, scrollY));
        }
    }

    /**
     * 分享整套配置：娃 + 全部信标打成二维码。
     *
     * 一页装得下就是一张静态码；装不下就切成几页，交给展示页自动轮播成动态码。
     * 换手机、给家里人配一台，扫一遍就全过去了，不用一颗颗扫。
     */
    private void shareAllConfig() {
        List<String> frames = ConfigBundleCodec.encode(dbHelper);
        if (frames.isEmpty()) {
            Feedback.show(this, R.string.share_config_empty);
            return;
        }
        Intent it = new Intent(this, QRShareActivity.class);
        it.putStringArrayListExtra("qr_frames", new ArrayList<>(frames));
        startActivity(it);
    }

    /** 拼一个娃的分组：分组头 + 两列网格（最后一格是「+ 添加」） */
    private void addGroupView(ChildEntity child, List<BeaconEntity> beacons) {
        final boolean collapsed = BeaconPrefs.isChildCollapsed(this, child.getId());

        View header = LayoutInflater.from(this)
                .inflate(R.layout.item_child_header, layoutGroups, false);
        TextView name = header.findViewById(R.id.txt_child_name);
        TextView count = header.findViewById(R.id.txt_child_count);
        final TextView chevron = header.findViewById(R.id.txt_chevron);

        name.setText(child.displayName());
        count.setText(getString(R.string.child_beacon_count, beacons.size()));
        chevron.setText(collapsed ? R.string.chevron_collapsed : R.string.chevron_expanded);

        final RecyclerView rv = new RecyclerView(this);
        rv.setLayoutManager(new GridLayoutManager(this, 2));
        rv.setNestedScrollingEnabled(false);
        rv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        rv.setClipToPadding(false);
        rv.setPadding(dp(6), 0, dp(6), 0);
        rv.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        BeaconAdapter adapter = new BeaconAdapter(child, rv);
        adapter.setData(beacons);
        rv.setAdapter(adapter);
        rv.setVisibility(collapsed ? View.GONE : View.VISIBLE);

        header.setOnClickListener(v -> {
            boolean nowCollapsed = !BeaconPrefs.isChildCollapsed(this, child.getId());
            BeaconPrefs.setChildCollapsed(this, child.getId(), nowCollapsed);
            chevron.setText(nowCollapsed
                    ? R.string.chevron_collapsed : R.string.chevron_expanded);
            rv.setVisibility(nowCollapsed ? View.GONE : View.VISIBLE);
        });
        header.setOnLongClickListener(v -> {
            showChildMenu(child);
            return true;
        });

        layoutGroups.addView(header);
        layoutGroups.addView(rv);
    }

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

    private void showAddOptions(ChildEntity child) {
        final CharSequence[] items = {
                getString(R.string.add_by_ble),
                getString(R.string.add_by_qr)
        };
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.add_beacon)
                .setItems(items, (dialog, which) -> {
                    if (which == 0) {
                        startBleScan(child);
                    } else {
                        startQrScan(child);
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void startBleScan(ChildEntity child) {
        if (!hasBlePermission()) {
            requestPermissions();
            tip(getString(R.string.perm_bluetooth));
            return;
        }
        pendingChildId = child == null ? 0 : child.getId();
        resultLauncher.launch(new Intent(this, ScanBeaconActivity.class));
    }

    private void startQrScan(ChildEntity child) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions();
            tip(getString(R.string.perm_camera));
            return;
        }
        pendingChildId = child == null ? 0 : child.getId();
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

    // === 娃的改名 / 删除 ===

    private void showChildMenu(ChildEntity child) {
        final CharSequence[] items = {
                getString(R.string.child_rename),
                getString(R.string.child_delete)
        };
        new MaterialAlertDialogBuilder(this)
                .setTitle(child.displayName())
                .setItems(items, (d, which) -> {
                    if (which == 0) {
                        askRenameChild(child);
                    } else {
                        confirmDeleteChild(child);
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void askRenameChild(ChildEntity child) {
        EditText input = new EditText(this);
        input.setText(child.getName());
        input.setSelection(input.getText().length());
        input.setHint(R.string.info_child_hint);
        input.setSingleLine(true);

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.child_rename)
                .setView(wrapInPadding(input))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.save, (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) {
                        tip(getString(R.string.rename_empty));
                        return;
                    }
                    dbHelper.renameChild(child.getId(), name);
                    reload();
                })
                .show();
    }

    /**
     * 删娃。删的是分组，不是信标 —— 名下的信标会并到剩下的第一个娃下面。
     * 所以「只剩一个娃且名下有信标」时不允许删：否则信标没地方可去，
     * 下次 ensureChildren() 又会拿第一颗信标名建一个出来，等于白删。
     */
    private void confirmDeleteChild(ChildEntity child) {
        List<ChildEntity> children = dbHelper.getAllChildren();
        List<BeaconEntity> owned = dbHelper.getBeaconsByChild(child.getId());

        if (children.size() <= 1 && !owned.isEmpty()) {
            tipLong(getString(R.string.child_delete_last));
            return;
        }

        String msg = owned.isEmpty()
                ? getString(R.string.child_delete_msg_empty, child.displayName())
                : getString(R.string.child_delete_msg, child.displayName(), owned.size());

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.child_delete)
                .setMessage(msg)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.child_delete, (d, w) -> {
                    dbHelper.deleteChild(child.getId());
                    reload();
                    tip(getString(R.string.child_deleted, child.displayName()));
                })
                .show();
    }

    // === 归属（娃 / 场景标签） ===

    /**
     * 卡片第二行：场景标签。
     * 分组头已经写了娃名，卡片里再写一遍纯属重复，所以这里只留标签。
     */
    private String tagOf(BeaconEntity b) {
        return b.getLabel();
    }

    /**
     * 把信标移进某个娃的分组。
     *
     * 新建娃放在同一个列表的最后一项：用户挪信标的时候，往往正是在那一刻
     * 意识到「这个娃还没建过」，让他退出再找入口不如就地建。
     */
    private void pickChild(BeaconEntity b) {
        final List<ChildEntity> children = dbHelper.getAllChildren();
        final CharSequence[] items = new CharSequence[children.size() + 1];
        for (int i = 0; i < children.size(); i++) {
            items[i] = children.get(i).displayName();
        }
        items[children.size()] = getString(R.string.info_child_new);

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.info_child_title)
                .setItems(items, (d, which) -> {
                    if (which < children.size()) {
                        ChildEntity target = children.get(which);
                        dbHelper.setBeaconChild(b.getId(), target.getId());
                        reload();
                        tip(getString(R.string.info_child_moved, target.displayName()));
                    } else {
                        askNewChild(b);
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /**
     * 新建一个娃。
     *
     * @param beacon 建完把这个信标移进去；传 null 表示只建个空分组
     *               （从页面底部「＋ 新建一个娃」进来就是这种）
     */
    private void askNewChild(BeaconEntity beacon) {
        EditText input = new EditText(this);
        input.setHint(R.string.info_child_hint);
        input.setSingleLine(true);

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.info_child_new)
                .setView(wrapInPadding(input))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.save, (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) {
                        tip(getString(R.string.rename_empty));
                        return;
                    }
                    long id = dbHelper.insertChild(name);
                    if (id <= 0) {
                        tip(getString(R.string.info_child_new_failed));
                        return;
                    }
                    if (beacon != null) {
                        dbHelper.setBeaconChild(beacon.getId(), id);
                        reload();
                        tip(getString(R.string.info_child_moved, name));
                    } else {
                        reload();
                        tip(getString(R.string.child_created, name));
                    }
                })
                .show();
    }

    /**
     * 场景标签：「奥数」「钢琴」这种，决定推送文案的括号里写什么。
     *
     * 留空 = 不限场景，推送时退回按时间段分组猜。所以这里必须允许存空串，
     * 不校验「不能为空」—— 「清空标签」本身就是一个正当操作。
     */
    private void editLabel(BeaconEntity b) {
        EditText input = new EditText(this);
        input.setText(b.getLabel());
        input.setSelection(input.getText().length());
        input.setHint(R.string.info_label_hint);
        input.setSingleLine(true);

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.info_label_title)
                .setMessage(R.string.info_label_msg)
                .setView(wrapInPadding(input))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.save, (d, w) -> {
                    b.setLabel(input.getText().toString());
                    dbHelper.updateBeacon(b);
                    reload();
                    tip(getString(R.string.info_label_saved));
                })
                .show();
    }

    // === 推送冷却 / 信标信息 ===

    /**
     * 清除这个信标的推送冷却。
     * 冷却时间是按娃分开记的，所以清的是这个信标所属娃的冷却。
     */
    private void resetPushCooldown(BeaconEntity b) {
        long childId = b.getChildId();
        long last = Math.max(BeaconPrefs.getLastPushAtByChild(this, childId),
                BeaconPrefs.getLastApproachAtByChild(this, childId));
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
        BeaconPrefs.clearCooldownByChild(this, childId);
        tipLong(getString(R.string.cooldown_reset_done, b.getName()));
    }

    /**
     * 信标信息：归属 / 场景标签 / UUID / Major·Minor·蓝牙地址 / 上次推送·冷却，底部一排操作。
     */
    private void showBeaconInfo(BeaconEntity b) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        box.setPadding(pad, dp(4), pad, dp(4));

        // 归属和场景标签放在最上面：这两项决定首页显示什么、推送写什么，
        // 是这个页面里唯一会经常改的东西
        String childName = childNames.get(b.getChildId());
        addPickerRow(box, R.string.info_child,
                childName == null || childName.isEmpty()
                        ? getString(R.string.info_child_none) : childName,
                v -> { dismissInfo(); pickChild(b); });
        addPickerRow(box, R.string.info_label,
                b.hasLabel() ? b.getLabel() : getString(R.string.info_label_none),
                v -> { dismissInfo(); editLabel(b); });

        addInfoRow(box, R.string.info_uuid, b.getUuidFormatted(), true);
        // 三件套和蓝牙地址挤一行：同型号信标只有末尾那串地址不同，放一起最好对比
        addInfoRow(box, R.string.info_major_minor,
                b.getMajor() + " / " + b.getMinor() + " · "
                        + (b.hasMac() ? b.getMac() : getString(R.string.info_mac_short_none)),
                true);

        // 「上次推送」取两种推送里更近的那一次（按娃记的冷却）
        long last = Math.max(BeaconPrefs.getLastPushAtByChild(this, b.getChildId()),
                BeaconPrefs.getLastApproachAtByChild(this, b.getChildId()));
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
        infoDialog = dialog;

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

    /**
     * 一行「可点的设置项」：灰色小标签 + 加粗的主色值。
     * 用主色 + 点击涟漪暗示它可点，不用另外加按钮。
     */
    private void addPickerRow(LinearLayout parent, int labelRes, String value,
                              View.OnClickListener onClick) {
        TextView label = new TextView(this);
        label.setText(labelRes);
        label.setTextSize(12);
        label.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        LinearLayout.LayoutParams lpLabel = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpLabel.topMargin = dp(14);
        parent.addView(label, lpLabel);

        TextView tv = new TextView(this);
        tv.setText(value);
        tv.setTextSize(15);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        tv.setTextColor(ContextCompat.getColor(this, R.color.primary));
        tv.setPadding(0, dp(8), 0, dp(8));
        android.util.TypedValue out = new android.util.TypedValue();
        if (getTheme().resolveAttribute(android.R.attr.selectableItemBackground, out, true)) {
            tv.setBackgroundResource(out.resourceId);
        }
        tv.setOnClickListener(onClick);
        parent.addView(tv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /** 关掉正在显示的信标信息弹窗，避免「弹窗上再叠一层弹窗」 */
    private void dismissInfo() {
        try {
            if (infoDialog != null && infoDialog.isShowing()) infoDialog.dismiss();
        } catch (Exception ignored) {
        }
        infoDialog = null;
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

    // === 删除信标 ===

    private void confirmDelete(BeaconEntity b) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.menu_delete)
                .setMessage(getString(R.string.confirm_delete) + "\n\n" + b.getName())
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.menu_delete, (d, w) -> {
                    dbHelper.deleteBeacon(b.getId());
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

    /** 一个娃一个 Adapter，内部维护这一组的信标列表 */
    private class BeaconAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        private final List<BeaconEntity> items = new ArrayList<>();
        private final ChildEntity child;
        private final RecyclerView recyclerView;

        BeaconAdapter(ChildEntity child, RecyclerView recyclerView) {
            this.child = child;
            this.recyclerView = recyclerView;
        }

        void setData(List<BeaconEntity> data) {
            items.clear();
            if (data != null) items.addAll(data);
            notifyDataSetChanged();
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
                // 卡片高亮没有「选中」的意思（首页按娃显示），统一取消描边
                card.setStrokeWidth(dp(2));
                card.setStrokeColor(ContextCompat.getColor(BeaconManagerActivity.this,
                        R.color.transparent));
                miniCircle.setBackgroundResource(R.drawable.bg_circle_mini_gray);

                txtName.setText(b.getName());
                txtName.setTextColor(ContextCompat.getColor(BeaconManagerActivity.this,
                        R.color.text_primary));

                // 第二行只放场景标签：娃名在分组头上已经写过了
                txtTag.setText(tagOf(b));
                txtTag.setTextColor(ContextCompat.getColor(BeaconManagerActivity.this,
                        R.color.text_secondary));

                attachGestures();
            }

            /**
             * 按下即滑就拖：
             * 点按 → 信息页；滑动超过 touchSlop → 组内换位；按住 500ms 不动 → 也是信息页
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
                                // 禁止外层滚动把手势抢走，否则拖到一半就被 CANCEL
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
                                // 拖动结束 → 落库（只动这一组，其他组的顺序不受影响）
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
                                    // 单击 = 打开信息页（设置所属娃 / 场景标签都在里面）
                                    showBeaconInfo(items.get(pos));
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
                    View childView = recyclerView.getChildAt(i);
                    if (childView == itemView) continue;
                    if (x >= childView.getLeft() && x < childView.getRight()
                            && y >= childView.getTop() && y < childView.getBottom()) {
                        int pos = recyclerView.getChildAdapterPosition(childView);
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
                // 第二行写清楚加到谁身上：页面上有几个「+」，不写归属会点错。
                // 名字放小字那行而不是标题那行 —— 娃名可能很长，标题撑不开会被截成「添加到…」
                TextView hint = itemView.findViewById(R.id.txt_add_hint);
                hint.setText(getString(R.string.add_to_child, child.displayName()));
                itemView.setOnClickListener(v -> showAddOptions(child));
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
    }
}
