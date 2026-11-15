package com.kidlink.beacon.ui;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
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
import com.kidlink.beacon.ble.BeaconScanner;
import com.kidlink.beacon.ble.IBeaconData;
import com.kidlink.beacon.ble.IBeaconParser;
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

    /** RSSI 平滑窗口：和首页一致，5 个包取平均 */
    private static final int RSSI_WINDOW = 5;
    /**
     * 多久没再收到广播就算「未连接」。
     *
     * 信标一般 1 秒广播一次，留 5 秒的余量：掏手机、卡片刚滚出来、
     * 信号偶尔丢两个包，都不至于让绿点闪成灰的。
     */
    private static final long LIVE_STALE_MS = 5000L;
    /** 状态刷新节拍：只用来把「超时没信号」的卡片刷回灰色 */
    private static final long LIVE_TICK_MS = 1000L;

    /**
     * 淘宝淘口令，不是短链。
     *
     * 短链在手机上只会把淘宝网页版打开（还要登录、加购），而淘口令是淘宝 App 认的东西：
     * 复制之后打开淘宝，它会自己弹「发现一个宝贝」，比粘贴短链稳得多。
     * 注意这段里有 emoji 和 $，别做 trim 以外的任何处理。
     */
    private static final String SHOP_CLIP = "8Fu😊zhi0$HKVg6eLdTeR$:// MU5183,打開/";

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
    /** 底部「购买信标」（已经有信标时才出现，大推荐块同时就不显示了） */
    private View btnBuyBeacon;
    /** children.id → 娃名 */
    private final Map<Long, String> childNames = new HashMap<>();
    /** 当前打开的信标信息弹窗 —— 点「所属娃 / 场景标签」前要先关掉，免得弹窗叠弹窗 */
    private androidx.appcompat.app.AlertDialog infoDialog;
    /**
     * 从哪一组点的「+ 添加」。
     * 扫完 / 扫完码回来时，把还没归属的新信标挂到这个娃下面。
     */
    private long pendingChildId = 0;

    /**
     * 这一页自己的扫描器。
     *
     * 不共用首页那个：首页停在前台时才扫，而且只盯当前娃的信标；
     * 这一页要一次看全部娃的全部信标，而且进来就是为了确认「东西在不在包里」。
     * 两个扫描器同时在跑是允许的（系统按 callback 分发），离开这一页就停。
     */
    private BeaconScanner liveScanner;
    /** 上一轮扫描的目标集合指纹，变了才重启扫描（重启会丢一两秒的数据） */
    private String liveTargetsKey = "";
    /** beaconId → 最近一次信号（平滑 RSSI / 距离 / 时间） */
    private final Map<Long, Live> liveById = new HashMap<>();
    /** 当前页面上的所有分组 Adapter，刷新状态时逐个通知 */
    private final List<BeaconAdapter> adapters = new ArrayList<>();

    private final Handler handler = new Handler(Looper.getMainLooper());
    /**
     * 每秒跑一次：把超时没信号的卡片刷回「未连接」，顺便兜住「扫描掉了但没人重启」。
     */
    private final Runnable liveTick = new Runnable() {
        @Override
        public void run() {
            ensureLiveScanning();
            refreshLiveCards();
            handler.postDelayed(this, LIVE_TICK_MS);
        }
    };

    /**
     * 蓝牙开关监听。
     *
     * 关掉蓝牙时系统会静默停掉扫描，但扫描器自己的 isScanning 还停在 true ——
     * 只靠它判断的话，用户把蓝牙关了再打开，卡片会一直灰着不恢复。
     * 所以这里在重新打开的那一刻把状态拍平再重扫。
     */
    private final BroadcastReceiver btReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            if (!BluetoothAdapter.ACTION_STATE_CHANGED.equals(intent.getAction())) return;
            int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
            if (state == BluetoothAdapter.STATE_ON) {
                liveTargetsKey = "";   // 强制重设目标 + 重扫
                if (liveScanner != null) liveScanner.stopScan();
                startLiveScan();
            } else if (state == BluetoothAdapter.STATE_OFF) {
                if (liveScanner != null) liveScanner.stopScan();
            }
        }
    };

    /**
     * 一颗信标的实时状态。
     *
     * 平滑窗口放在这里（而不是信标对象里）：卡片重排、折叠展开、
     * 退出再进来，数字都是接着上一次的，不会突然跳一下。
     */
    private static class Live {
        final java.util.LinkedList<Integer> buf = new java.util.LinkedList<>();
        int rssi;
        double distance;
        long at;
        /** 上一次写进卡片的距离，用来做显示迟滞（-1 = 还没写过） */
        double shownDistance = -1;
    }

    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(),
                    result -> {
                        // 结果不阻断使用，操作时会再次校验。
                        // 但授权通过的这一刻要立刻开扫 —— 否则卡片会一直停在「未连接」，
                        // 直到用户退出再进来一次。
                        startLiveScan();
                    });

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
        btnBuyBeacon = findViewById(R.id.btn_buy_beacon);
        btnBuyBeacon.setOnClickListener(v -> copyShopLink());
        layoutEmptyAdd = findViewById(R.id.layout_empty_add);
        // 这块只在「一个娃都没有」时出现，点了就是建娃
        layoutEmptyAdd.setOnClickListener(v -> askNewChild(null));

        // 空状态里的购买建议：整块可点去淘宝，内层那行单独可点看协议说明
        layoutBuyTip = findViewById(R.id.layout_buy_tip);
        layoutBuyTip.setOnClickListener(v -> copyShopLink());
        findViewById(R.id.txt_buy_proto).setOnClickListener(v -> showProtocolDialog());

        requestPermissions();
        reload();
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
        ContextCompat.registerReceiver(this, btReceiver,
                new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED);
        startLiveScan();
    }

    @Override
    protected void onPause() {
        stopLiveScan();
        try {
            unregisterReceiver(btReceiver);
        } catch (IllegalArgumentException ignored) {
            // 没注册过就退出了，忽略
        }
        super.onPause();
    }

    // === 实时状态 ===

    /**
     * 开始盯这一页的所有信标。
     *
     * 目标集合变了（加了信标、改了归属、删了信标）才重启扫描 —— 重启会清空
     * 「这台设备已经报过」的记录，也会让距离数字从空开始，能不动就不动。
     */
    private void startLiveScan() {
        List<BeaconEntity> all = dbHelper.getAllBeacons();
        if (all.isEmpty()) {
            stopLiveScan();
            return;
        }
        if (liveScanner == null) {
            liveScanner = new BeaconScanner(this);
            liveScanner.setListener(new BeaconScanner.OnBeaconScanListener() {
                @Override
                public void onBeaconFound(IBeaconData beacon, int filteredRssi, double distance) {
                    // 统一在 onRawBeaconData 里处理
                }

                @Override
                public void onRawBeaconData(IBeaconData beacon, android.bluetooth.le.ScanResult scanResult) {
                    onLiveSignal(beacon, scanResult);
                }

                @Override
                public void onScanError(int errorCode, String message) {
                    // 蓝牙关了 / 没授权：卡片就停在上一次的灰字「未连接」，
                    // 不再弹提示打扰 —— 这一页本来就能看出来没信号
                }
            });
        }

        StringBuilder key = new StringBuilder();
        for (BeaconEntity b : all) {
            key.append(b.getId()).append(':').append(b.getMajor()).append('.')
                    .append(b.getMinor()).append('|');
        }
        String newKey = key.toString();
        if (!newKey.equals(liveTargetsKey)) {
            liveScanner.stopScan();
            liveScanner.setTargets(all);
            liveTargetsKey = newKey;
        }

        if (!liveScanner.hasScanPermission() || !liveScanner.isBluetoothReady()) return;
        if (!liveScanner.isScanning()) liveScanner.startScan();

        handler.removeCallbacks(liveTick);
        handler.postDelayed(liveTick, LIVE_TICK_MS);
    }

    private void stopLiveScan() {
        handler.removeCallbacks(liveTick);
        if (liveScanner != null && liveScanner.isScanning()) liveScanner.stopScan();
    }

    /**
     * 兜底：蓝牙开着、权限有、但扫描器认为自己在扫却一个包都没来的时候，重开一次。
     *
     * 只在「本来就在扫」的前提下重启是不行的 —— 系统把扫描掐掉时内部标志还是 true。
     * 所以这里改用「最近一次收到包的时间」判断：超过 2 个失效周期还没动静，
     * 就停一次再开，等价于把扫描链路复位。信标真的走远了也不会因此乱跳，
     * 因为卡片照样灰着，只是后台多扫了一次。
     */
    private void ensureLiveScanning() {
        if (liveScanner == null) return;
        if (!liveScanner.isBluetoothReady() || !liveScanner.hasScanPermission()) return;
        if (!liveScanner.isScanning()) {
            liveScanner.startScan();
            return;
        }
        long lastPacket = 0;
        for (Live l : liveById.values()) {
            if (l.at > lastPacket) lastPacket = l.at;
        }
        if (lastPacket > 0 && System.currentTimeMillis() - lastPacket > LIVE_STALE_MS * 2) {
            liveScanner.stopScan();
            liveScanner.startScan();
        }
    }

    /**
     * 收到一包广播：找到是哪颗信标，更新平滑值，顺手把这张卡片重画。
     *
     * 匹配规则和首页一致：库里存了 MAC 的只认 MAC（同品牌两台信标出厂三件套可能一样），
     * 没存 MAC 的才退回去比 UUID + Major + Minor。
     */
    private void onLiveSignal(IBeaconData data, android.bluetooth.le.ScanResult result) {
        String mac = "";
        try {
            if (result != null && result.getDevice() != null) {
                String a = result.getDevice().getAddress();
                mac = a == null ? "" : a.trim().toUpperCase(Locale.US);
            }
        } catch (Exception ignored) {
        }

        BeaconEntity hit = null;
        for (BeaconEntity b : dbHelper.getAllBeacons()) {
            if (b.getMajor() != data.major || b.getMinor() != data.minor) continue;
            if (!IBeaconParser.uuidMatch(data.uuid, b.getUuidFormatted())) continue;
            if (b.hasMac() && !mac.isEmpty() && !b.getMac().equals(mac)) continue;
            hit = b;
            break;
        }
        if (hit == null) return;

        Live live = liveById.get(hit.getId());
        if (live == null) {
            live = new Live();
            liveById.put(hit.getId(), live);
        }
        live.buf.addLast(data.rssi);
        while (live.buf.size() > RSSI_WINDOW) live.buf.removeFirst();
        int sum = 0;
        for (int r : live.buf) sum += r;
        live.rssi = sum / live.buf.size();
        live.distance = hit.estimateDistance(live.rssi);
        live.at = System.currentTimeMillis();

        refreshLiveCards();
    }

    /** 每张已绑定的卡片按自己那颗信标的最新状态重画 */
    private void refreshLiveCards() {
        long now = System.currentTimeMillis();
        for (BeaconAdapter a : adapters) {
            a.refreshLive(now);
        }
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
        // 老版本的全局时间段（设置 → 消息推送设置里配的）认领给第一个娃，只做一次
        if (!children.isEmpty()) {
            BeaconPrefs.migrateSharedTimeGroupsTo(this, children.get(0).getId());
        }
        childNames.clear();
        for (ChildEntity c : children) {
            childNames.put(c.getId(), c.displayName());
        }
        List<BeaconEntity> all = dbHelper.getAllBeacons();

        // 重建会导致滚动位置回到顶部，先记下来，重建后补回去
        final int scrollY = scrollGroups == null ? 0 : scrollGroups.getScrollY();
        layoutGroups.removeAllViews();
        adapters.clear();

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
        // 已经有信标了 → 大推荐块收起来，改在「新建一个娃」右边挂一个「购买信标」
        final boolean hasBeacon = !all.isEmpty();
        layoutBuyTip.setVisibility(hasBeacon ? View.GONE : View.VISIBLE);
        btnBuyBeacon.setVisibility(hasBeacon && !noChild ? View.VISIBLE : View.GONE);

        if (scrollGroups != null) {
            scrollGroups.post(() -> scrollGroups.scrollTo(0, scrollY));
        }

        // 页面开着的时候也可能加/删了信标（从这页扫进来的），
        // 目标集合变了就重新盯一遍，否则新加的那颗永远是灰的
        if (liveScanner != null && liveScanner.isScanning()) {
            startLiveScan();
        }
        refreshLiveCards();
    }

    /**
     * 分享整套配置：娃 + 全部信标打成二维码。
     *
     * 一页装得下就是一张静态码；装不下就切成几页，交给展示页自动轮播成动态码。
     * 换手机、给家里人配一台，扫一遍就全过去了，不用一颗颗扫。
     */
    private void shareAllConfig() {
        List<String> frames = ConfigBundleCodec.encode(this, dbHelper);
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
        adapters.add(adapter);
        rv.setVisibility(collapsed ? View.GONE : View.VISIBLE);

        // 时间段放在信标之前：先定「这个娃的作息」，再往下才是「挂了哪几颗信标」
        final View timeRow = buildTimeGroupRow(child);
        timeRow.setVisibility(collapsed ? View.GONE : View.VISIBLE);

        header.setOnClickListener(v -> {
            boolean nowCollapsed = !BeaconPrefs.isChildCollapsed(this, child.getId());
            BeaconPrefs.setChildCollapsed(this, child.getId(), nowCollapsed);
            chevron.setText(nowCollapsed
                    ? R.string.chevron_collapsed : R.string.chevron_expanded);
            rv.setVisibility(nowCollapsed ? View.GONE : View.VISIBLE);
            timeRow.setVisibility(nowCollapsed ? View.GONE : View.VISIBLE);
        });
        header.setOnLongClickListener(v -> {
            showChildMenu(child);
            return true;
        });

        layoutGroups.addView(header);
        layoutGroups.addView(timeRow);
        layoutGroups.addView(rv);
    }

    /**
     * 分组里的「时间段」一行：左边是标题，右边是这个娃已配的分组名，点进编辑页。
     *
     * 只摆一行摘要、不把时间段摊开：一个娃通常就「上学 / 放学」两条，
     * 摊开会把信标挤到屏幕外；而这两条又几乎不会天天改。
     */
    private View buildTimeGroupRow(ChildEntity child) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(dp(20), dp(8), dp(20), dp(6));
        android.util.TypedValue ripple = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
        row.setBackgroundResource(ripple.resourceId);
        row.setClickable(true);
        row.setFocusable(true);

        TextView label = new TextView(this);
        label.setText(R.string.info_time_group);
        label.setTextSize(12);
        label.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        row.addView(label);

        List<com.kidlink.beacon.data.TimeGroup> groups =
                BeaconPrefs.getTimeGroupsFor(this, child.getId());
        String summary = TimeGroupActivity.summarize(groups);

        TextView value = new TextView(this);
        value.setText(summary == null ? getString(R.string.time_group_none) : summary);
        value.setTextSize(13);
        value.setTextColor(ContextCompat.getColor(this,
                summary == null ? R.color.text_secondary : R.color.primary));
        value.setSingleLine(true);
        value.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams lpValue = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lpValue.leftMargin = dp(10);
        row.addView(value, lpValue);

        row.setOnClickListener(v -> startActivity(TimeGroupActivity.intentFor(this, child)));
        return row;
    }

    /**
     * 复制淘口令，而不是直接跳浏览器。
     *
     * 手机上「打开链接」十有八九落到浏览器里的淘宝网页版，登录、加购都别扭；
     * 复制淘口令之后打开淘宝 App，它会自己识别并弹出去商品页。
     * 所以这里只做两件事：写剪贴板 + 提示一句「打开淘宝看看」。
     */
    private void copyShopLink() {
        android.content.ClipboardManager cm =
                (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(android.content.ClipData.newPlainText(
                    getString(R.string.buy_beacon_short), SHOP_CLIP));
        }
        Feedback.show(this, R.string.buy_link_copied, true);
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
                .setTitle(getString(R.string.child_rename_title, child.displayName()))
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
                .setTitle(getString(R.string.child_delete_title, child.displayName()))
                .setMessage(msg)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.child_delete, (d, w) -> {
                    // 连这个娃名下的时间段一起清掉 —— 娃都没了，作息留着就是孤魂
                    BeaconPrefs.clearTimeGroupsFor(this, child.getId());
                    dbHelper.deleteChild(child.getId());
                    reload();
                    tip(getString(R.string.child_deleted, child.displayName()));
                })
                .show();
    }

    // === 归属 ===

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
     * 信标信息：归属 / UUID / Major·Minor·蓝牙地址 / 上次推送·冷却，底部一排操作。
     */
    private void showBeaconInfo(BeaconEntity b) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        box.setPadding(pad, dp(4), pad, dp(4));

        // 归属放最上面：它决定首页显示在谁的面板里，是这里唯一会经常改的东西。
        // 场景标签已经去掉了 —— 推送括号现在由「这个娃的时间段」决定
        String childName = childNames.get(b.getChildId());
        addPickerRow(box, R.string.info_child,
                childName == null || childName.isEmpty()
                        ? getString(R.string.info_child_none) : childName,
                v -> { dismissInfo(); pickChild(b); });

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
        /** 此刻绑在屏幕上的卡片：beaconId → ViewHolder */
        final Map<Long, BeaconVH> bound = new HashMap<>();

        BeaconAdapter(ChildEntity child, RecyclerView recyclerView) {
            this.child = child;
            this.recyclerView = recyclerView;
        }

        void setData(List<BeaconEntity> data) {
            items.clear();
            bound.clear();
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
        public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
            if (holder instanceof BeaconVH) {
                ((BeaconVH) holder).unbind();
            }
            super.onViewRecycled(holder);
        }

        /**
         * 按最新信号重画这一组里所有「正挂在屏幕上」的卡片。
         *
         * 只碰已经绑定的那几个 ViewHolder，不走 notifyDataSetChanged ——
         * 后者每秒来一次会不停重绑、重放入场动画，卡片看着像在闪。
         */
        void refreshLive(long now) {
            for (BeaconVH vh : bound.values()) {
                vh.applyLive(now);
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

            /** 这张卡片现在代表哪颗信标（回收复用会换人） */
            long beaconId = -1;

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

                txtName.setText(b.getName());
                txtName.setTextColor(ContextCompat.getColor(BeaconManagerActivity.this,
                        R.color.text_primary));

                // 第二行原来是场景标签，标签去掉了之后改放实时状态：连上就是绿的信号 + 距离
                beaconId = b.getId();
                bound.put(beaconId, this);
                applyLive(System.currentTimeMillis());

                attachGestures();
            }

            /** 卡片被回收 / 换了主人：从刷新表里摘掉，免得往旧卡片上写状态 */
            void unbind() {
                if (beaconId > 0) bound.remove(beaconId);
                beaconId = -1;
            }

            /**
             * 按这颗信标的最新信号重画圆点和第二行字。
             *
             * 连上（5 秒内收到过广播）：圆点绿 + 「-62 dBm · 约 1.2 米」
             * 没连上：圆点灰 + 「未连接」
             *
             * 距离做显示迟滞：同一档（1 米内 / 10 米内 / 10 米外）里抖动小于 0.3 米就不重写，
             * RSSI 每包差个 2~3dB 是常态，不压一下数字会一直跳。
             */
            void applyLive(long now) {
                if (beaconId <= 0) return;
                Live live = liveById.get(beaconId);
                boolean connected = live != null && now - live.at <= LIVE_STALE_MS;

                if (!connected) {
                    miniCircle.setBackgroundResource(R.drawable.bg_circle_mini_gray);
                    txtTag.setVisibility(View.VISIBLE);
                    txtTag.setTextColor(ContextCompat.getColor(BeaconManagerActivity.this,
                            R.color.text_secondary));
                    txtTag.setText(R.string.beacon_offline);
                    if (live != null) live.shownDistance = -1;
                    return;
                }

                miniCircle.setBackgroundResource(R.drawable.bg_circle_mini_green);
                txtTag.setVisibility(View.VISIBLE);
                txtTag.setTextColor(ContextCompat.getColor(BeaconManagerActivity.this,
                        R.color.primary));

                String rssiText = getString(R.string.rssi_value, live.rssi);
                boolean refresh = live.shownDistance < 0
                        || DistanceText.bucketOf(live.distance) != DistanceText.bucketOf(live.shownDistance)
                        || Math.abs(live.distance - live.shownDistance) >= 0.3;
                if (refresh) {
                    live.shownDistance = live.distance;
                    txtTag.setText(getString(R.string.beacon_live_fmt, rssiText,
                            DistanceText.format(BeaconManagerActivity.this, live.distance)));
                }
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
