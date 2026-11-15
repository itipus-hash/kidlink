package com.kidlink.beacon.ui;

import android.Manifest;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.view.View;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.kidlink.beacon.R;
import com.kidlink.beacon.ble.BeaconScanner;
import com.kidlink.beacon.ble.IBeaconData;
import com.kidlink.beacon.ble.IBeaconParser;
import com.kidlink.beacon.data.BeaconDbHelper;
import com.kidlink.beacon.data.BeaconEntity;
import com.kidlink.beacon.data.BeaconPrefs;
import com.kidlink.beacon.data.ChildEntity;
import com.kidlink.beacon.data.WifiGate;
import com.kidlink.beacon.push.PushClient;
import com.kidlink.beacon.service.BeaconForegroundService;
import com.kidlink.beacon.ui.widget.Feedback;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

/**
 * 首页：按**娃**显示，一块面板 = 一个娃。
 *
 * 为什么不是按信标：信标是绑在包上的。书包一颗、奥数包一颗，两颗信标是同一个娃，
 * 按信标显示就成了「两块各算各的」，家长看到的不是「我离许小鱼多远」，
 * 而是两台设备各自的信号，反而更绕。按娃显示之后，一个娃名下挂几颗信标都行，
 * 谁信号强就拿谁当「当前」，小字标出「当前：奥数包」。
 *
 * 支持同时显示最多 3 个娃，纵向等分：
 * - 1 个 → 一块占满整个区域
 * - 2 个 → 上下各一半
 * - 3 个 → 三等分，每块整体缩一档（图标 / 字号小一点，保证不溢出）
 *
 * 推送冷却按娃算（同趟带的两个包只推一条），文案优先级见 buildArriveMessage()。
 */
public class MainActivity extends AppCompatActivity {

    private static final int RSSI_WINDOW = 5;

    /** 从常驻通知点进来时带的标记：这种入口是「我要看界面」，不自动最小化 */
    public static final String EXTRA_FROM_NOTIFICATION = "from_notification";
    /** 每隔这么久检查一次"距离上次收到广播过去多久了" */
    private static final long CHECK_TICK_MS = 1000;
    /** 推送距离在设置里可调，默认 2 米；离开这个距离 + 3 米后重新武装推送 */
    private static final double REARM_EXTRA_METERS = 3.0;
    /**
     * 任意两条「近距离推送」之间至少隔这么久。
     *
     * 近距离推送在很远的距离（用户设了 50 米）就会触发，那个距离上 RSSI 换算出来的
     * 数字纯属噪声 —— 人能站在 28 米处看着屏幕，「已靠近」却已经反复触发好几轮。
     * 持久冷却管的是「这一趟」，这 90 秒管的是「这一秒」，两个一起兜底。
     */
    private static final long MIN_APPROACH_GAP_MS = 90_000L;
    /** 这么久一条广播都没收到，就认为扫描僵了，重启一次 */
    private static final long SCAN_DEAD_MS = 90_000L;
    /** 后台时更激进：安静 15 秒就重启扫描 */
    private static final long BG_SCAN_REFRESH_MS = 15_000L;

    /** 上下一共三块 */
    private static final int PANEL_COUNT = 3;
    /** 只有一块时底部留白，保持图标偏上的观感；多块时贴边均分 */
    private static final int PAD_BOTTOM_SINGLE_DP = 150;
    private static final int PAD_BOTTOM_MULTI_DP = 6;
    private static final int PAD_SIDE_DP = 24;

    private BeaconDbHelper dbHelper;
    private BeaconScanner scanner;

    private final Panel[] panels = new Panel[PANEL_COUNT];
    /** 当前显示的娃（顺序 = 从上到下），最多三个 */
    private final List<ChildEntity> currentChildren = new ArrayList<>();
    /**
     * 参与扫描的全部信标。
     *
     * 首页不再有「选中哪几个」的概念 —— 只要信标有归属的娃，就一起扫。
     * 一个娃下面几颗信标互为主备，谁在手边谁就用得上。
     */
    private final List<BeaconEntity> currentBeacons = new ArrayList<>();
    /** findPanel() 顺带记下这条广播命中的是哪一颗信标（一个娃下面可能有好几颗） */
    private BeaconEntity matchedBeacon;
    /** 三条水平分隔线，按块数显示前 N-1 条 */
    private View[] dividers;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean emptyTipShown = false;
    /** 当前这批扫描目标的指纹，用来判断"要不要重启扫描" */
    private String scanTargetKey = "";
    /** 本轮扫描开始时间，配合看门狗用 */
    private long scanStartedAt = 0;
    private long lastScanRestartAt = 0;

    /** 一块显示区：圆环 + 娃名 + 当前信标 + 距离 + 信号 + 状态，各自带独立测量状态 */
    private static class Panel {
        View container;
        View circleBox;
        View circleBg;
        View icon;
        TextView name;
        /** 小字：这个娃现在身上是哪颗信标 */
        TextView sub;
        TextView distance;
        TextView rssi;
        TextView state;
        View rowSignal;
        /** 空状态才显示的那个「添加信标」按钮（只有第一块有） */
        View btnAdd;

        ChildEntity child;
        /** 这个娃名下的全部信标 */
        final List<BeaconEntity> beacons = new ArrayList<>();
        /** 每颗信标各自一条 RSSI 平滑窗口，互不干扰 */
        final java.util.Map<Long, LinkedList<Integer>> rssiBuf = new java.util.HashMap<>();
        final java.util.Map<Long, Integer> rssiSmooth = new java.util.HashMap<>();
        /** 每颗信标最后一次收到广播的时间，用来把「已经不在场」的那颗排除出比较 */
        final java.util.Map<Long, Long> lastSeen = new java.util.HashMap<>();
        /** 上一次刷新时信号最强的那颗，用于「当前：xx」 */
        BeaconEntity best;

        boolean hadSignal = false;
        long lastSignalAt = 0;
        double lastShownDistance = -1;
        /** 上一次渲染时是否处于变色距离以内，用于判断"刚进入" */
        boolean near = false;
        /** 上一次渲染时是否已在悬浮提醒的触发距离内 */
        boolean frontTipOn = false;
        /** 这个娃「已经在到达区内」；只有距离超过阈值 + 3 米才清（跨重启保存在 prefs） */
        boolean arrivedFired = false;
        /** 这个娃「已经在接近区内」；同样只认距离真的走远，信号抖动和冷启动都不清 */
        boolean approachFired = false;
    }

    /** Activity 是否在前台：决定悬浮提醒要不要真弹、以及后台扫描看门狗用哪个阈值 */
    private boolean isForeground = false;
    /**
     * 这次 onPause 是不是跳到应用内部的页面（设置 / 信标管理）。
     * 从那些页面返回不算「新的一趟接送」，不应该把推送机会又刷新一遍。
     */
    private boolean navigatingInternal = false;

    /**
     * 这一趟前台是否已经提示过「当前 WiFi 已暂停提醒」。
     * 提示一次就够 —— 目的是别让用户以为震动 / 推送坏了，而不是反复提醒。
     */
    private boolean wifiMutedTipShown = false;

    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                if (!currentBeacons.isEmpty()) startScanForCurrent();
            });

    private final ActivityResultLauncher<Intent> managerLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                // 返回后重新加载（可能在管理页被改名/删除/改选择）
                loadCurrentBeacons();
                if (!currentBeacons.isEmpty()) startScanForCurrent();
            });

    /**
     * 首页直接添加信标（空状态专用）。
     * 加完要重新加载 —— 新信标会带出一个娃，首页才有面板可显示。
     */
    private final ActivityResultLauncher<Intent> addLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                loadCurrentBeacons();
                if (!currentBeacons.isEmpty()) startScanForCurrent();
            });

    /**
     * 每隔一秒看一眼每一块：距离上次收到广播过去多久了。
     * 只有超过用户设置的「检测间隔」才把图标变回灰色 —— 丢几个包不会误报。
     */
    private final Runnable signalWatch = new Runnable() {
        @Override
        public void run() {
            if (scanner != null && scanner.isScanning()) {
                long limitMs = BeaconPrefs.getDetectIntervalSec(MainActivity.this) * 1000L;
                long now = System.currentTimeMillis();
                for (Panel p : panels) {
                    if (p.child == null || !p.hadSignal) continue;
                    if (now - p.lastSignalAt > limitMs) {
                        p.hadSignal = false;
                        renderLost(p);
                    }
                }
                // 看门狗：久久收不到任何广播（后台被系统悄悄掐了扫描？）就自己重启一次。
                // 后台时这个阈值压到 15 秒 —— 部分 ROM 切后台后会静默停止投递扫描结果，
                // 只有重新 startScan 才恢复，用「安静太久就重启」把它顶住。
                long last = lastAnySignalAt();
                long silentSince = last > 0 ? now - last : now - scanStartedAt;
                long idleLimit = isForeground ? SCAN_DEAD_MS : BG_SCAN_REFRESH_MS;
                if (silentSince > idleLimit && now - lastScanRestartAt > idleLimit) {
                    lastScanRestartAt = now;
                    scanStartedAt = now;
                    scanner.stopScan();
                    scanner.startScan();
                }
            }
            handler.postDelayed(this, CHECK_TICK_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        dbHelper = new BeaconDbHelper(this);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.action_manage) {
                navigatingInternal = true;
                managerLauncher.launch(new Intent(this, BeaconManagerActivity.class));
                return true;
            }
            return false;
        });

        TextView titleView = findViewById(R.id.txt_title);
        titleView.setText(buildTitle());
        titleView.setOnClickListener(v -> {
            navigatingInternal = true;
            startActivity(new Intent(this, SettingsActivity.class));
        });

        dividers = new View[]{findViewById(R.id.divider_h), findViewById(R.id.divider_h_2)};
        bindPanel(0,
                R.id.panel_1, R.id.circle_container, R.id.circle_bg, R.id.img_beacon,
                R.id.txt_beacon_name, R.id.txt_sub, R.id.txt_distance,
                R.id.row_signal, R.id.txt_rssi, R.id.txt_state);
        bindPanel(1,
                R.id.panel_2, R.id.circle_container_2, R.id.circle_bg_2, R.id.img_beacon_2,
                R.id.txt_beacon_name_2, R.id.txt_sub_2, R.id.txt_distance_2,
                R.id.row_signal_2, R.id.txt_rssi_2, R.id.txt_state_2);
        bindPanel(2,
                R.id.panel_3, R.id.circle_container_3, R.id.circle_bg_3, R.id.img_beacon_3,
                R.id.txt_beacon_name_3, R.id.txt_sub_3, R.id.txt_distance_3,
                R.id.row_signal_3, R.id.txt_rssi_3, R.id.txt_state_3);

        scanner = new BeaconScanner(this);        scanner.setListener(new BeaconScanner.OnBeaconScanListener() {
            @Override
            public void onBeaconFound(IBeaconData beacon, int filteredRssi, double distance) {
                // 统一在 onRawBeaconData 处理
            }

            @Override
            public void onRawBeaconData(IBeaconData beacon, ScanResult scanResult) {
                handleBeaconSignal(beacon, scanResult);
            }

            @Override
            public void onScanError(int errorCode, String message) {
                handler.post(() -> {
                    if (errorCode == -1) {
                        setStateText(panels[0], getString(R.string.bluetooth_off), R.color.text_secondary);
                    }
                });
            }
        });

        requestPermissions();

        maybeAutoMinimize(savedInstanceState);
    }

    /**
     * 「打开 APP 后自动最小化」：只在冷启动那一次退到后台。
     *
     * 为什么只认冷启动：
     * - 退到后台后 task 还在，用户再点图标走的是 onNewIntent（singleTop），不会再次触发，
     *   所以不会出现「点了图标也进不了界面」的死锁。
     * - 从常驻通知点进来带 EXTRA_FROM_NOTIFICATION，直接放行到界面。
     * - 权限没给齐就不退：退下去也扫不到，只会让人误以为在跑。
     * - **连着「不提醒」的 WiFi 也不退**：那个网络下本来就什么都不会响，
     *   再退到后台等于把 APP 藏起来 —— 用户打开就是想看一眼，让他看。
     */
    private void maybeAutoMinimize(Bundle savedInstanceState) {
        if (savedInstanceState != null) return;
        if (!BeaconPrefs.isAutoMinimize(this)) return;
        if (WifiGate.blocked(this)) return;
        Intent it = getIntent();
        if (it != null && it.getBooleanExtra(EXTRA_FROM_NOTIFICATION, false)) return;
        if (scanner == null || !scanner.hasScanPermission()) return;
        // 等首帧画出来再退：onCreate 里立刻退，窗口还没上屏，用户会觉得「点了没反应」
        handler.postDelayed(this::minimizeToBackground, 600);
    }

    private void minimizeToBackground() {
        if (isFinishing() || isDestroyed()) return;
        android.widget.Toast.makeText(this, R.string.auto_minimize_on_toast,
                android.widget.Toast.LENGTH_SHORT).show();
        moveTaskToBack(true);
    }

    /**
     * 标题栏：接娃助手 + 小一号半透明的版本号。
     * 版本号用 Span 缩到 0.62 倍并调淡，不另外拆一个 TextView，避免 toolbar 里排版漂移。
     */
    private CharSequence buildTitle() {
        String base = getString(R.string.app_name);
        String version = " v" + com.kidlink.beacon.BuildConfig.VERSION_NAME;
        android.text.SpannableString ss = new android.text.SpannableString(base + version);
        int start = base.length();
        int end = ss.length();
        ss.setSpan(new android.text.style.RelativeSizeSpan(0.62f),
                start, end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        ss.setSpan(new android.text.style.ForegroundColorSpan(0xCCFFFFFF),
                start, end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return ss;
    }

    private void bindPanel(int index, int containerId, int circleBoxId, int circleId, int iconId,
                           int nameId, int subId, int distanceId, int signalId, int rssiId,
                           int stateId) {
        Panel p = new Panel();
        p.container = findViewById(containerId);
        p.circleBox = findViewById(circleBoxId);
        p.circleBg = findViewById(circleId);
        p.icon = findViewById(iconId);
        p.name = findViewById(nameId);
        p.sub = findViewById(subId);
        p.distance = findViewById(distanceId);
        p.rowSignal = findViewById(signalId);
        p.rssi = findViewById(rssiId);
        p.state = findViewById(stateId);
        // 只有第一块布局里有这个按钮，另外两块查不到就是 null
        p.btnAdd = p.container.findViewById(R.id.btn_empty_add);
        panels[index] = p;
    }

    // === 空状态：引导直接添加 ===

    /**
     * 「一个信标都没有」时点中间那块（或那颗按钮）走这里。
     *
     * 以前是直接跳信标管理页 —— 但管理页也是空的，用户到了那边还得再找入口，
     * 等于白跑一趟。空库时用户想干的事只有一件：把信标加进来。
     * 所以这里直接给「扫描 / 扫码」两条路，添加完返回首页立刻就有面板了。
     */
    private void showAddOptions() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.add_beacon)
                .setItems(new CharSequence[]{
                        getString(R.string.add_by_ble),
                        getString(R.string.add_by_qr)
                }, (dialog, which) -> {
                    // 两个页面自己会申请权限，这里不用先拦
                    addLauncher.launch(new Intent(this, which == 0
                            ? ScanBeaconActivity.class : QRScanActivity.class));
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /**
     * 空状态才让中间那块可点、并显示那颗按钮。
     * 有信标之后必须收回点击 —— 否则滑动/误触会莫名弹出添加框。
     */
    private void setEmptyTap(boolean on) {
        for (Panel p : panels) {
            if (p.btnAdd != null) {
                p.btnAdd.setVisibility(on ? View.VISIBLE : View.GONE);
                p.btnAdd.setOnClickListener(on ? v -> showAddOptions() : null);
            }
        }
        Panel first = panels[0];
        if (first == null) return;
        if (on) {
            first.container.setOnClickListener(v -> showAddOptions());
        } else {
            first.container.setOnClickListener(null);
        }
    }

    /**
     * 窗口真正可见后再提示：冷启动时 onCreate 里弹会被 Splash 盖掉
     * （提示动画在首帧之前就跑完了）。这里只在第一次获得焦点时弹一次。
     */
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && !emptyTipShown) {
            emptyTipShown = true;
            if (dbHelper.getAllBeacons().isEmpty()) {
                Feedback.show(findViewById(android.R.id.content),
                        getString(R.string.no_beacon_tip), true);
            }
        }
    }

    private void requestPermissions() {
        List<String> perms = new ArrayList<>();
        perms.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_SCAN);
            perms.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS);
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

    @Override
    protected void onResume() {
        super.onResume();
        isForeground = true;
        // 后台运行和扫描固定开启：挂一条常驻通知吊住进程，
        // 按 Home 之后扫描回调还能继续跑（震动 / 推送才有意义）
        BeaconForegroundService.start(this);
        loadCurrentBeacons();
        // 回到前台 = 新的一趟接送，两条推送各补一次机会。
        // 不清的话会「卡住」：万一「已靠近」是被 50 米档位的误差误触发的，
        // 人其实还没到，这一趟就再也不会响，只能眼睁睁看着 APP 一直挂在后台。
        if (!navigatingInternal) rearmForNewSession();
        navigatingInternal = false;
        if (!currentBeacons.isEmpty()) {
            startScanForCurrent();
        }
        tipIfWifiMuted();
    }

    /**
     * 当前连着「不提醒」名单里的 WiFi 时，冒一条悬浮提示告诉用户：
     * 不是坏了，是这个网络下被静音了。每趟前台只提示一次。
     *
     * 只提示、不打扰：用 Feedback（和「保存成功」同款底部胶囊），
     * 不带震动、不进通知栏 —— 否则「静音」这件事本身又变成一次提醒。
     */
    private void tipIfWifiMuted() {
        if (!WifiGate.blocked(this)) {
            wifiMutedTipShown = false;   // 换了网络，下次再连回来重新提示
            return;
        }
        if (wifiMutedTipShown) return;
        wifiMutedTipShown = true;
        String ssid = WifiGate.currentSsid(this);
        // 等首帧上屏再冒泡，否则冷启动时提示会在 Splash 底下跑完
        final String text = getString(R.string.wifi_block_muted_tip,
                ssid == null ? "" : ssid);
        handler.postDelayed(() -> {
            if (isFinishing() || isDestroyed()) return;
            Feedback.show(findViewById(android.R.id.content), text, true);
        }, 900);
    }

    /**
     * 新行程开工：把「已经在区内」的标记清掉，让「已靠近」「接到了」各恢复一次触发机会。
     *
     * 只清标记、不动冷却时间戳 —— 频率限制还是冷却说了算，
     * 否则站在家门口反复切前台就能反复收到提醒。
     */
    private void rearmForNewSession() {
        for (Panel p : panels) {
            if (p.child == null) continue;
            p.approachFired = false;
            p.arrivedFired = false;
            BeaconPrefs.setApproachInsideByChild(this, p.child.getId(), false);
            BeaconPrefs.setArrivalInsideByChild(this, p.child.getId(), false);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        isForeground = false;
        // 不停扫描：后台靠前台服务保活
    }

    // === 按娃加载面板 ===

    private void loadCurrentBeacons() {
        // 每颗信标都必须有归属的娃；没有娃（从老版本升上来）就用第一个信标名建一个
        List<ChildEntity> allChildren = dbHelper.ensureChildren();

        // 只显示「名下有信标」的娃，一个娃挂几颗信标都行
        List<ChildEntity> shownChildren = new ArrayList<>();
        List<List<BeaconEntity>> shownBeacons = new ArrayList<>();
        List<BeaconEntity> scanList = new ArrayList<>();
        for (ChildEntity c : allChildren) {
            List<BeaconEntity> bs = dbHelper.getBeaconsByChild(c.getId());
            if (bs.isEmpty()) continue;
            scanList.addAll(bs);
            // 超过三块就不显示了，但扫描照扫 —— 信标多一颗不多
            if (shownChildren.size() >= PANEL_COUNT) continue;
            shownChildren.add(c);
            shownBeacons.add(bs);
        }

        // 有几个娃就显示几块，分隔线只出现在相邻两块之间
        int count = shownChildren.size();
        for (int i = 1; i < PANEL_COUNT; i++) {
            panels[i].container.setVisibility(count > i ? View.VISIBLE : View.GONE);
        }
        for (int i = 0; i < dividers.length; i++) {
            dividers[i].setVisibility(count > i + 1 ? View.VISIBLE : View.GONE);
        }
        applyPanelLayout(count);

        // 分组没变（只是从设置页 / 信标管理页 / 后台回来）→ **保留上次画面**，
        // 不能一回来就把图标刷灰、距离清空。只有真的换了娃或换了归属才重置。
        if (samePanels(shownChildren, shownBeacons)) {
            currentBeacons.clear();
            currentBeacons.addAll(scanList);
            for (int i = 0; i < panels.length; i++) {
                Panel p = panels[i];
                if (i >= shownChildren.size()) {
                    p.child = null;
                    p.beacons.clear();
                    continue;
                }
                p.child = shownChildren.get(i);
                syncBeacons(p, shownBeacons.get(i));
                // 管理页可能改过娃名 / 信标名 / 场景标签
                if (p.hadSignal) {
                    p.name.setText(p.child.displayName());
                    renderSub(p);
                }
                // 「已在区内」的真实状态在 prefs 里（信标信息页的「清除冷却」会把它清掉），
                // 内存这份只是镜像 —— 不同步的话，清完冷却回到首页还是不会推，
                // 非得把 APP 杀了重开（那时走的是下面那个分支）才生效。
                p.approachFired = BeaconPrefs.isApproachInsideByChild(this, p.child.getId());
                p.arrivedFired = BeaconPrefs.isArrivalInsideByChild(this, p.child.getId());
            }
            if (shownChildren.isEmpty()) {
                currentChildren.clear();
                renderNoBeacon(panels[0]);
            }
            setEmptyTap(shownChildren.isEmpty());
            return;
        }

        currentChildren.clear();
        currentChildren.addAll(shownChildren);
        currentBeacons.clear();
        currentBeacons.addAll(scanList);

        for (int i = 0; i < panels.length; i++) {
            Panel p = panels[i];
            p.child = i < shownChildren.size() ? shownChildren.get(i) : null;
            syncBeacons(p, i < shownBeacons.size() ? shownBeacons.get(i) : null);
            p.rssiBuf.clear();
            p.rssiSmooth.clear();
            p.lastSeen.clear();
            p.best = null;
            p.hadSignal = false;
            p.lastSignalAt = 0;
            p.lastShownDistance = -1;
            // 「已经在区内」是持久状态：APP 接完人会自己退出，这里必须从 prefs 恢复，
            // 否则重新打开时人还站在信标旁边，又推一条「已靠近」
            p.arrivedFired = p.child != null
                    && BeaconPrefs.isArrivalInsideByChild(this, p.child.getId());
            p.approachFired = p.child != null
                    && BeaconPrefs.isApproachInsideByChild(this, p.child.getId());
            p.near = false;
            p.frontTipOn = false;
            resetPanelView(p);
        }

        if (currentChildren.isEmpty()) {
            renderNoBeacon(panels[0]);
        } else {
            for (Panel p : panels) {
                if (p.child != null) renderSearching(p);
            }
        }
        setEmptyTap(currentChildren.isEmpty());
    }

    /** 把新的信标列表同步进 panel（只换内容，不换 panel 对象） */
    private void syncBeacons(Panel p, List<BeaconEntity> fresh) {
        p.beacons.clear();
        if (fresh != null) p.beacons.addAll(fresh);
    }

    /**
     * 面板归属有没有变：娃的先后顺序 + 每个娃名下信标的 id。
     *
     * 比的是内存里那份（panels[i].beacons），不是 currentBeacons ——
     * currentBeacons 装的是全部扫描目标，可能比显示的娃名下的信标多。
     */
    private boolean samePanels(List<ChildEntity> children, List<List<BeaconEntity>> beacons) {
        if (currentChildren.size() != children.size()) return false;
        for (int i = 0; i < children.size(); i++) {
            if (currentChildren.get(i).getId() != children.get(i).getId()) return false;
            List<BeaconEntity> oldBs = panels[i].beacons;
            List<BeaconEntity> newBs = beacons.get(i);
            if (oldBs.size() != newBs.size()) return false;
            for (int j = 0; j < oldBs.size(); j++) {
                if (oldBs.get(j).getId() != newBs.get(j).getId()) return false;
            }
        }
        return true;
    }

    /**
     * 按块数调整每块的大小。
     *
     * 一块：图标大、底部留白多，视觉重心偏上；
     * 两块：图标不变，去掉留白，上下各一半；
     * 三块：高度只有 1/3，圆环和字号整体缩一档，否则内容会顶出格子。
     */
    private void applyPanelLayout(int count) {
        boolean triple = count >= 3;
        int side = dp(PAD_SIDE_DP);
        int bottom = dp(count <= 1 ? PAD_BOTTOM_SINGLE_DP : PAD_BOTTOM_MULTI_DP);

        int circlePx = dp(triple ? 100 : 140);
        int iconPx = dp(triple ? 38 : 52);
        int nameMargin = dp(triple ? 8 : 18);
        float nameSp = triple ? 15f : 19f;
        float distSp = triple ? 22f : 30f;
        int nameH = dp(triple ? 22 : 28);
        int subH = dp(triple ? 14 : 18);
        int distH = dp(triple ? 32 : 42);
        int signalH = dp(triple ? 16 : 20);
        int stateH = dp(triple ? 28 : 36);
        float stateSp = triple ? 12f : 13f;
        float subSp = triple ? 10f : 12f;

        for (Panel p : panels) {
            p.container.setPaddingRelative(side, 0, side, bottom);

            setSize(p.circleBox, circlePx, circlePx);
            setSize(p.icon, iconPx, iconPx);

            android.view.ViewGroup.LayoutParams lpName = p.name.getLayoutParams();
            lpName.height = nameH;
            if (lpName instanceof android.view.ViewGroup.MarginLayoutParams) {
                ((android.view.ViewGroup.MarginLayoutParams) lpName).topMargin = nameMargin;
            }
            p.name.setLayoutParams(lpName);
            p.name.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, nameSp);

            setHeight(p.sub, subH);
            p.sub.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, subSp);

            android.view.ViewGroup.LayoutParams lpDist = p.distance.getLayoutParams();
            lpDist.height = distH;
            p.distance.setLayoutParams(lpDist);
            p.distance.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, distSp);

            setHeight(p.rowSignal, signalH);
            setHeight(p.state, stateH);
            p.state.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, stateSp);
        }
    }

    private void setSize(View v, int w, int h) {
        if (v == null) return;
        android.view.ViewGroup.LayoutParams lp = v.getLayoutParams();
        lp.width = w;
        lp.height = h;
        v.setLayoutParams(lp);
    }

    private void setHeight(View v, int h) {
        if (v == null) return;
        android.view.ViewGroup.LayoutParams lp = v.getLayoutParams();
        lp.height = h;
        v.setLayoutParams(lp);
    }

    private void startScanForCurrent() {
        if (currentBeacons.isEmpty()) return;
        if (!scanner.hasScanPermission()) return;
        if (!scanner.isBluetoothReady()) {
            setStateText(panels[0], getString(R.string.bluetooth_off), R.color.text_secondary);
            return;
        }

        String key = targetKey(currentBeacons);
        // 本来就扫着同一批信标（只是切了个界面回来）→ 别重启，
        // 重启会清掉 RSSI 平滑缓冲，距离数字会突然跳一下
        if (scanner.isScanning() && key.equals(scanTargetKey) && !scanLooksDead()) {
            handler.removeCallbacks(signalWatch);
            handler.postDelayed(signalWatch, CHECK_TICK_MS);
            return;
        }

        scanner.stopScan();
        scanner.resetRssiFilter();
        for (Panel p : panels) {
            p.rssiBuf.clear();
            p.rssiSmooth.clear();
            p.lastSeen.clear();
            if (p.child != null && !p.hadSignal) renderSearching(p);
        }
        // 首页同时盯好几个信标：用软件层的多目标过滤，硬件过滤一律不设
        scanner.clearFilter();
        scanner.setTargets(currentBeacons);
        if (scanner.startScan()) {
            scanTargetKey = key;
            scanStartedAt = System.currentTimeMillis();
            handler.removeCallbacks(signalWatch);
            handler.postDelayed(signalWatch, CHECK_TICK_MS);
        }
    }

    private String targetKey(List<BeaconEntity> list) {
        StringBuilder sb = new StringBuilder();
        for (BeaconEntity b : list) sb.append(b.getId()).append(',');
        return sb.toString();
    }

    /** 扫描"看着像死了"：超过 90 秒一条广播都没收到，就该重启一次扫描 */
    private boolean scanLooksDead() {
        return lastAnySignalAt() > 0 && System.currentTimeMillis() - lastAnySignalAt() > SCAN_DEAD_MS;
    }

    /** 所有面板里最近一次收到广播的时间 */
    private long lastAnySignalAt() {
        long latest = 0;
        for (Panel p : panels) {
            if (p.child != null && p.lastSignalAt > latest) latest = p.lastSignalAt;
        }
        return latest;
    }

    private void stopScan() {
        if (scanner != null) scanner.stopScan();
    }

    // === 信号处理 ===

    private void handleBeaconSignal(IBeaconData data, ScanResult scanResult) {
        final String mac = macOf(scanResult);
        final Panel p = findPanel(data, mac);
        if (p == null || matchedBeacon == null) return;
        final BeaconEntity hit = matchedBeacon;
        final int rssi = data.rssi;

        // 旧数据里没有 MAC：第一次收到广播就顺手补上。
        // 补完之后这个信标只认自己的 MAC，同品牌三件套一样的另一台就不会串进来。
        if (!hit.hasMac() && !mac.isEmpty()) {
            hit.setMac(mac);
            dbHelper.updateMac(hit.getId(), mac);
        }

        // 平滑窗口、在场时间、渲染全部挪到主线程做：
        // 广播回调是另一条线程，几个 Map 同时读写会互相踩。
        handler.post(() -> {
            if (p.child == null) return;
            smoothRssi(p, hit.getId(), rssi);
            p.lastSeen.put(hit.getId(), System.currentTimeMillis());
            p.hadSignal = true;
            p.lastSignalAt = System.currentTimeMillis();
            renderSignal(p);
        });
    }

    private static String macOf(ScanResult result) {
        try {
            if (result != null && result.getDevice() != null) {
                String mac = result.getDevice().getAddress();
                return mac == null ? "" : mac.trim().toUpperCase();
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    /**
     * 这条广播属于哪一块（哪个娃）。
     *
     * 一个娃名下可能挂了好几颗信标（书包一颗、奥数包一颗），所以是扫一遍每个娃的
     * 信标列表。匹配顺序：有 MAC 的先比 MAC，再比 UUID+Major+Minor。
     * 只比三件套是不够的 —— 同品牌的两台信标出厂可能 UUID 一模一样，
     * 光看三件套会把两台设备都算成同一颗。
     *
     * 命中的那颗顺手记在 {@link #matchedBeacon} 里，调用方要知道是哪一颗
     * （补 MAC、算这颗自己的平滑值）。
     */
    private Panel findPanel(IBeaconData data, String mac) {
        matchedBeacon = null;
        String m = mac == null ? "" : mac.trim().toUpperCase();
        for (Panel p : panels) {
            if (p.child == null) continue;
            for (BeaconEntity b : p.beacons) {
                if (b.hasMac() && !b.getMac().equals(m)) continue;
                if (b.getMajor() != data.major) continue;
                if (b.getMinor() != data.minor) continue;
                if (IBeaconParser.uuidMatch(data.uuid, b.getUuidFormatted())) {
                    matchedBeacon = b;
                    return p;
                }
            }
        }
        return null;
    }

    /** 每颗信标各有一条自己的平滑窗口，几个包同时在场也不会互相拉低 */
    private void smoothRssi(Panel p, long beaconId, int rssi) {
        LinkedList<Integer> buf = p.rssiBuf.get(beaconId);
        if (buf == null) {
            buf = new LinkedList<>();
            p.rssiBuf.put(beaconId, buf);
        }
        buf.addLast(rssi);
        while (buf.size() > RSSI_WINDOW) buf.removeFirst();
        int sum = 0;
        for (int r : buf) sum += r;
        p.rssiSmooth.put(beaconId, sum / buf.size());
    }

    /**
     * 这个娃现在身上是哪颗信标 —— 取平滑后信号最强、且**还在场**的那颗。
     *
     * 两个关键点：
     * 1. 用平滑值而不是单包 RSSI，否则书包在楼下、奥数包在楼上时，
     *    「当前」会在一两个包之间来回跳。
     * 2. 超过检测间隔没再收到广播的那颗直接排除 —— 只留下最后一次的强值的话，
     *    奥数包放进柜子一下午，首页会一直指着它说「当前：奥数包」，
     *    而人其实早就只带着书包了。
     */
    private BeaconEntity pickBestBeacon(Panel p) {
        long limitMs = BeaconPrefs.getDetectIntervalSec(this) * 1000L;
        long now = System.currentTimeMillis();
        BeaconEntity best = null;
        int bestRssi = Integer.MIN_VALUE;
        for (BeaconEntity b : p.beacons) {
            Integer r = p.rssiSmooth.get(b.getId());
            Long seen = p.lastSeen.get(b.getId());
            if (r == null || seen == null) continue;
            if (now - seen > limitMs) continue;
            if (r > bestRssi) {
                bestRssi = r;
                best = b;
            }
        }
        return best;
    }

    // === 渲染 ===
    //
    // 圆环、信标名、状态、距离几块的高度都在 XML 里写死了，
    // 这里只切换 INVISIBLE/VISIBLE，不改变占用空间，
    // 所以任何状态下图标和名字的纵向位置都不会动。

    /** 清空这一块的残留内容（切换分组时用） */
    private void resetPanelView(Panel p) {
        p.circleBg.setBackgroundResource(R.drawable.bg_circle_gray);
        p.name.setText("");
        if (p.sub != null) p.sub.setText("");
        p.distance.setText("");
        p.distance.setVisibility(View.INVISIBLE);
        p.rowSignal.setVisibility(View.INVISIBLE);
        p.rssi.setText("");
        p.state.setText("");
    }

    /** 一个娃都没接（库里没有信标） */
    private void renderNoBeacon(Panel p) {
        resetPanelView(p);
        setStateText(p, getString(R.string.no_beacon_selected), R.color.text_secondary);
    }

    /** 已经有娃但还没收到过信号：图标灰着就够了，不写任何文案 */
    private void renderSearching(Panel p) {
        p.circleBg.setBackgroundResource(R.drawable.bg_circle_gray);
        p.name.setText(p.child != null ? p.child.displayName() : "");
        if (p.sub != null) p.sub.setText("");
        p.distance.setText("");
        p.distance.setVisibility(View.INVISIBLE);
        p.rowSignal.setVisibility(View.INVISIBLE);
        p.lastShownDistance = -1;
        p.state.setText("");
    }

    /** 超过检测间隔还没收到广播：只把图标变灰，不写"未检测到信号" */
    private void renderLost(Panel p) {
        p.circleBg.setBackgroundResource(R.drawable.bg_circle_gray);
        p.name.setText(p.child != null ? p.child.displayName() : "");
        if (p.sub != null) p.sub.setText("");
        p.distance.setText("");
        p.distance.setVisibility(View.INVISIBLE);
        p.rowSignal.setVisibility(View.INVISIBLE);
        p.lastShownDistance = -1;
        // 断线不代表走远：15 秒收不到广播（默认 detect_interval）就当离区的话，
        // 家里信号一抖，两条推送都会重新武装一次。离开只认「距离真的超过阈值 + 3 米」。
        p.state.setText("");
    }

    /**
     * 小字：这个娃现在身上是哪颗信标。
     * 一个娃只挂一颗信标时没必要写 —— 屏幕上多一行字，却没多出任何信息。
     */
    private void renderSub(Panel p) {
        if (p.sub == null) return;
        if (p.beacons.size() <= 1 || p.best == null) {
            p.sub.setText("");
            return;
        }
        p.sub.setText(getString(R.string.main_current_beacon, p.best.getName()));
    }

    /**
     * 按「本块里信号最强的那颗信标」重画。
     *
     * 距离、图标颜色、悬浮提醒、两条推送全都跟着这颗走 —— 一个娃名下挂几个包，
     * 家长关心的始终是「离娃最近的那颗有多远」，而不是某一颗特定的信标。
     */
    private void renderSignal(Panel p) {
        if (p.child == null) return;
        BeaconEntity best = pickBestBeacon(p);
        if (best == null) return;
        Integer smooth = p.rssiSmooth.get(best.getId());
        if (smooth == null) return;
        final int rssi = smooth;
        final double distance = best.estimateDistance(rssi);
        if (distance <= 0) return;
        p.best = best;

        p.name.setText(p.child.displayName());
        renderSub(p);
        p.distance.setVisibility(View.VISIBLE);
        p.rowSignal.setVisibility(View.VISIBLE);
        p.rssi.setText(getString(R.string.rssi_value, rssi));

        // 只有进入「变色距离」以内图标才变绿；
        // 超出这个距离即使信号良好，也只把距离数字显示出来。
        int nearMeters = BeaconPrefs.getNearMeters(this);
        boolean near = distance <= nearMeters;
        p.circleBg.setBackgroundResource(near ? R.drawable.bg_circle_green : R.drawable.bg_circle_gray);

        // 进/出变色区域：进的那一刻震一下。
        // 出去要超出 2 米才算，否则站在边界上信号抖一抖就反复震。
        if (near && !p.near) {
            p.near = true;
            onEnterNearZone(p, distance);
        } else if (!near && distance > nearMeters + 2) {
            p.near = false;
        }

        // 悬浮提醒有自己的触发距离（默认 10 米，和变色距离无关）
        handleFrontTip(p, distance);

        // 显示迟滞：变化小于 0.3 米且没跨档位就不刷新文字，
        // 否则 RSSI 抖一下数字就跟着跳，看着像坏了。
        boolean bucketChanged = bucketOf(distance) != bucketOf(p.lastShownDistance);
        if (p.lastShownDistance < 0
                || bucketChanged
                || Math.abs(distance - p.lastShownDistance) >= 0.3) {
            p.lastShownDistance = distance;
            p.distance.setText(formatDistance(distance));
        }

        p.state.setText("");

        // 先判断远的「近距离推送」，再判断近的「接送成功推送」
        maybePushApproach(p, distance);
        maybePushArrival(p, distance);
    }

    /**
     * 进入变色区域。
     *
     * 两件事，闸门各不相同：
     * 1. **悬浮提醒** —— 在页面底部轻轻冒一条，不受冷却管控，每次进来都提示
     * 2. **震动** —— 和推送共用冷却（读 lastPushAt），一次到达只热闹一次
     *
     * 注意震动只读不写冷却：震动发生在变色距离（一般 5 米），推送在推送距离（默认 2 米），
     * 如果震动把冷却占了，走到 2 米时那条微信就发不出去了。
     *
     * WiFi 屏蔽时连震动一起停 —— 这条名单的定义是「不提醒」，不只是「不推送」。
     */
    private void onEnterNearZone(Panel p, double distance) {
        if (p.child == null) return;
        if (WifiGate.blocked(this)) return;
        if (inArrivalCooldown(p.child.getId())) return;
        vibrateNear();
    }

    /**
     * 悬浮提醒：距离在设置里填（0 = 关闭，默认 10 米），
     * 和「图标变色触发距离」是两个独立的门槛 —— 可以先在 10 米看到提示，再在 5 米看到变绿。
     * 出去要超出设定值 + 2 米才算，免得站在边界反复冒泡。
     */
    private void handleFrontTip(Panel p, double distance) {
        int meters = BeaconPrefs.getFrontTipMeters(this);
        if (meters <= 0) {
            p.frontTipOn = false;
            return;
        }
        if (distance <= meters) {
            if (!p.frontTipOn) {
                p.frontTipOn = true;
                // WiFi 屏蔽时不冒泡，但标记照常落下：这次进来算过了，换网络也不补一条
                if (isForeground && !WifiGate.blocked(this) && p.child != null) {
                    showLightTip(p.child.displayName(), meters);
                }
            }
        } else if (distance > meters + 2) {
            p.frontTipOn = false;
        }
    }

    /** 悬浮提醒的文案 */
    private String frontTipText(String beaconName, int meters) {
        return getString(R.string.near_tip_format, beaconName, meters);
    }

    /**
     * 悬浮提醒：直接用 Feedback —— 和「保存配置」时那条提示是同一个组件，
     * 位置、外观、动画都一模一样（底部居中的深灰胶囊，升起 360ms，停 2.2s，落下）。
     */
    private void showLightTip(String beaconName, int meters) {
        Feedback.show(this, frontTipText(beaconName, meters));
    }

    /** 这个娃是否还在到达冷却期内（冷却按娃算：同趟带的两个包只推一条） */
    private boolean inArrivalCooldown(long childId) {
        long cooldownMs = BeaconPrefs.getPushCooldownMin(this) * 60_000L;
        return System.currentTimeMillis() - BeaconPrefs.getLastPushAtByChild(this, childId) < cooldownMs;
    }

    private Vibrator vibrator() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                VibratorManager vm = (VibratorManager) getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                return vm == null ? null : vm.getDefaultVibrator();
            }
            return (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 进入变色区域持续震 N 秒（设置里可选 关闭 / 5 / 10 / 15 秒）。
     * 用「两下短震 + 停顿」的节律重复，比一直嗡嗡震更容易被注意到，也不那么吵。
     */
    private void vibrateNear() {
        final int seconds = BeaconPrefs.getVibrateNearSec(this);
        if (seconds <= 0) return;

        final Vibrator v = vibrator();
        if (v == null || !v.hasVibrator()) return;

        try {
            // 0ms 等待 → 震 200 → 停 120 → 震 200 → 停 400，然后从头循环
            long[] pattern = {0, 200, 120, 200, 400};
            v.vibrate(VibrationEffect.createWaveform(pattern, 0));
            handler.removeCallbacks(stopVibrate);
            handler.postDelayed(stopVibrate, seconds * 1000L);
        } catch (Exception ignored) {
        }
    }

    private final Runnable stopVibrate = new Runnable() {
        @Override
        public void run() {
            Vibrator v = vibrator();
            if (v != null) {
                try {
                    v.cancel();
                } catch (Exception ignored) {
                }
            }
        }
    };

    /** 开着的那几条通道的名字，拼成「爸爸、妈妈」 */
    private String pushedNames() {
        StringBuilder sb = new StringBuilder();
        for (com.kidlink.beacon.data.PushToken t : BeaconPrefs.getEnabledPushTokens(this)) {
            if (sb.length() > 0) sb.append('、');
            sb.append(t.displayName());
        }
        return sb.toString();
    }

    /**
     * 推送成功后在界面上说一声「已推送消息给爸爸、妈妈」。
     * 没有开着的通道时什么都不显示。
     */
    private void showPushedTip() {
        String names = pushedNames();
        if (names.isEmpty()) return;
        Feedback.show(this, getString(R.string.pushed_to, names));
    }

    /**
     * 接送成功推送完成 → 等提示条走完，退出 APP。
     *
     * 前台后台都退：接完这一趟就该彻底收工，下一次接送自己重新打开。
     * 退出会把前台服务和扫描一起停掉，所以之后不会有任何提醒，直到下次手动打开。
     *
     * 提示条节奏：上升 360ms + 停 2200ms + 落下 360ms，留点余量取 3200ms。
     */
    private static final long EXIT_AFTER_TIP_MS = 3200L;

    private void exitAfterTipIfNeeded() {
        if (!BeaconPrefs.isExitAfterArrival(this)) return;
        handler.postDelayed(this::exitApp, EXIT_AFTER_TIP_MS);
    }

    /**
     * 彻底退出：停扫描 → 撤常驻通知 → 关界面 → 杀进程。
     *
     * 顺序有讲究：先掐掉 handler（扫描看门狗每秒都在跑，不掐的话
     * 关界面的瞬间还可能再触发一次判断），再停扫描和前台服务，
     * 最后才 finishAffinity + System.exit。
     */
    private void exitApp() {
        try {
            handler.removeCallbacksAndMessages(null);
            isForeground = false;
            if (scanner != null) scanner.stopScan();
            // 先把常驻通知撤掉，否则退出后通知栏还挂着一条
            BeaconForegroundService.stop(this);
            finishAffinity();
        } catch (Exception ignored) {
        }
        // finishAffinity 只关界面，进程还留着；用独立的 Handler（onDestroy 会清掉成员 handler）
        // 补一刀彻底退出，这样最近任务里也不会有残留卡片
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                System.exit(0);
            } catch (Exception ignored) {
            }
        }, 300);
    }

    /**
     * 近距离推送：**由远变近的那一次**推一条「妈妈已靠近许小鱼，28米。」。
     *
     * 语义是「进入事件」，不是「距离现在还小」这个状态：
     * - 进区 → 落一个持久标记（`approach_inside`），推不推都只处理这一次
     * - 站在旁边两小时、信号断了又接上 → 标记还是 true，不会因为冷却到期再推一条
     * - 只有距离真的超过「阈值 + 3 米」→ 才清标记，下回再进区才推
     * - APP 重启 → 标记从 prefs 恢复，人在旁边也不会重推
     *
     * 冷却（1.4.35 修）：
     * - 自己的持久冷却 `last_approach_at` + 接送成功的 `last_push_at`，取更近的那个
     * - 再加 90 秒全局最小间隔，挡住 RSSI 抖动导致的一进一出
     *
     * 之前只读接送成功的冷却、自己从不写入，而 28 米这个距离上根本到不了 2 米的
     * 接送成功阈值，于是 lastPushAt 永远停在好几小时前 → 冷却形同虚设。
     */
    private void maybePushApproach(Panel p, double distance) {
        if (p.child == null) return;

        final int approachMeters = BeaconPrefs.getApproachMeters(this);
        final long childId = p.child.getId();

        if (approachMeters <= 0) {
            p.approachFired = false;
            return;
        }

        // 已经算「在区内」：只要没真的走远就一直保持，中间怎么抖都不推
        if (p.approachFired) {
            if (distance > approachMeters + REARM_EXTRA_METERS) {
                p.approachFired = false;
                BeaconPrefs.setApproachInsideByChild(this, childId, false);
            }
            return;
        }
        if (distance > approachMeters) return;

        // 人已经站在娃旁边（到达距离内）→ 不用说「已靠近」，后面那条「接到」就够，
        // 否则回家一开 APP 就是「已靠近3米」+「接到了」两条连响
        if (distance <= BeaconPrefs.getPushMeters(this) + 2) {
            p.approachFired = true;
            BeaconPrefs.setApproachInsideByChild(this, childId, true);
            return;
        }

        // 进区：先把状态落下来，后面无论推没推成，这次进入都不会再重复触发
        p.approachFired = true;
        BeaconPrefs.setApproachInsideByChild(this, childId, true);

        // 指定 WiFi 不提醒（家里 / 公司这类已知不用提醒的地方）。
        // 状态照常落下 —— 这次进入就不再补推了，只是不发请求出去。
        if (WifiGate.blocked(this)) return;

        final java.util.List<String> tokens = BeaconPrefs.getEnabledTokenValues(this);
        if (tokens.isEmpty()) return;
        if (inApproachCooldown(childId) || inApproachGap()) return;

        // 时间段：今天一整天没勾任何分组 → 这天不推（和接送成功推送保持一致）
        final java.util.Date now = new java.util.Date();
        if (!todayHasAnyGroup(childId, now)) return;

        String picker = BeaconPrefs.getPickerName(this);
        String childName = p.child.displayName();
        String meters = String.format(java.util.Locale.getDefault(), "%.0f", distance);
        final String msg = getString(R.string.approach_push_message, picker, childName, meters);

        new PushClient().sendAll(tokens, msg, msg, (ok, m) -> {
            if (ok) {
                long at = System.currentTimeMillis();
                BeaconPrefs.setLastApproachAtByChild(MainActivity.this, childId, at);
                BeaconPrefs.setLastAnyPushAt(MainActivity.this, at);
                showPushedTip();
            } else {
                // 推失败不写冷却、也退回「不在区内」，下一包还能重试
                p.approachFired = false;
                BeaconPrefs.setApproachInsideByChild(MainActivity.this, childId, false);
                Feedback.show(MainActivity.this, getString(R.string.push_failed, m));
            }
        });
    }

    /**
     * 近距离推送的冷却：这个娃上次「已靠近」+ 上次「接到人」，
     * 两个时间戳取更近的那个 —— 接过人之后短期内就不用再喊「正在靠近」了。
     */
    private boolean inApproachCooldown(long childId) {
        long cooldownMs = BeaconPrefs.getPushCooldownMin(this) * 60_000L;
        long now = System.currentTimeMillis();
        if (now - BeaconPrefs.getLastPushAtByChild(this, childId) < cooldownMs) return true;
        return now - BeaconPrefs.getLastApproachAtByChild(this, childId) < cooldownMs;
    }

    /** 90 秒全局最小间隔（不分信标、不分类型），只用在近距离推送上 */
    private boolean inApproachGap() {
        long last = BeaconPrefs.getLastAnyPushAt(this);
        return last > 0 && System.currentTimeMillis() - last < MIN_APPROACH_GAP_MS;
    }

    /**
     * 接送成功推送：进入「接送成功推送」距离内推一条「奶奶在23:11接到许小鱼！」。
     * 这个信标自己冷却时间内最多一次，推成功才写冷却。
     * 两个信标各推各的：爸爸接到许小鱼 / 爸爸接到许大鱼。
     * 有没有通道可推，只看 token 列表里有没有开着的。
     */
    /**
     * 接送成功推送：进入「接送成功推送」距离内推一条「奶奶接到许小鱼了（放学）！」。
     * 这个娃冷却时间内最多一次，推成功才写冷却。
     *
     * 冷却是按娃算的：一趟带书包 + 奥数包出门，两个包先后进区也只推一条，
     * 不会出现「接到许小鱼」连响两次。
     */
    private void maybePushArrival(Panel p, double distance) {
        if (p.child == null) return;

        final double pushMeters = BeaconPrefs.getPushMeters(this);
        final long childId = p.child.getId();
        final BeaconEntity beacon = p.best;

        // 同样按「进入事件」处理：进过 2 米就一直算在区内，直到真的走出 5 米外
        if (p.arrivedFired) {
            if (distance > pushMeters + REARM_EXTRA_METERS) {
                p.arrivedFired = false;
                BeaconPrefs.setArrivalInsideByChild(this, childId, false);
            }
            return;
        }
        if (distance > pushMeters) return;

        // 进区：先落状态，冷却中 / 今天不推也不会在之后再补一条
        p.arrivedFired = true;
        BeaconPrefs.setArrivalInsideByChild(this, childId, true);

        // 指定 WiFi 不提醒：这里同样先落状态再判断，
        // 免得「在家被屏蔽」这件事在换了网络之后又补推一条迟到的「接到」
        if (WifiGate.blocked(this)) return;

        final java.util.List<String> tokens = BeaconPrefs.getEnabledTokenValues(this);
        if (tokens.isEmpty()) return;
        if (inArrivalCooldown(childId)) return;

        String picker = BeaconPrefs.getPickerName(this);
        java.util.Date now = new java.util.Date();

        // 这个娃的时间段今天一天都没勾 → 今天整天不推接送通知（周末不接娃就是这种情况）
        if (!todayHasAnyGroup(childId, now)) return;

        final String msg = buildArriveMessage(picker, p.child, beacon, now);

        new PushClient().sendAll(tokens, msg, msg, (ok, m) -> {
            if (ok) {
                long at = System.currentTimeMillis();
                BeaconPrefs.setLastPushAtByChild(MainActivity.this, childId, at);
                BeaconPrefs.setLastAnyPushAt(MainActivity.this, at);
                showPushedTip();
                // 提示条走完再退（设置里可以关掉）
                exitAfterTipIfNeeded();
            } else {
                // 推失败不写冷却，也退回「不在区内」，走出 5 米再回来还能重试
                p.arrivedFired = false;
                BeaconPrefs.setArrivalInsideByChild(MainActivity.this, childId, false);
                Feedback.show(MainActivity.this, getString(R.string.push_failed, m));
            }
        });
    }

    /** 这个娃今天（星期几）有没有至少一个时间段勾上了；一个都没勾 = 今天不推送 */
    private boolean todayHasAnyGroup(long childId, java.util.Date now) {
        java.util.List<com.kidlink.beacon.data.TimeGroup> groups =
                BeaconPrefs.getTimeGroupsFor(this, childId);
        if (groups.isEmpty()) return true;   // 没配置就不做这层限制
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.setTime(now);
        int isoDay = (cal.get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7 + 1;
        for (com.kidlink.beacon.data.TimeGroup g : groups) {
            if (g != null && g.matchesDay(isoDay)) return true;
        }
        return false;
    }

    /**
     * 接送成功的文案，两层：
     *
     * 1. **时间段命中** → 「爸爸接到许小鱼了（放学）」
     *    命中这个娃某个分组的时间窗口就用那个分组名，改名字文案自动跟着变。
     *    时间段挂在娃下面（信标管理 → 分组里的「时间段」），每个娃一套。
     * 2. **都没命中** → 「爸爸接到许小鱼了」
     *
     * 娃名取信标归属的那个娃，不是信标名 —— 家长要看到的是「接到了谁」，
     * 不是「哪颗设备被扫到了」。只有一颗信标、又没设归属的极端情况才退回信标名。
     *
     * 不带具体时间：推送本身就是「此刻」发生的，通知栏有收到时间，
     * 文案里再写一遍 22:24 只会让句子变长、还容易被误读成「记录」而不是「通知」。
     *
     * 时间段是**精准匹配**：只在窗口内的那一分钟算命中，窗口结束就不再算。
     * 晚接几分钟就会掉回第 3 层 —— 这是有意的，宁可少挂一个标签，
     * 也不要让 12:20 接到的娃被标成「放学」。想覆盖晚接就把那个分组的时间段拉长。
     * 分组还带星期维度：不在它勾选的星期里就跳过。
     * 多个分组同时命中时按设置里的先后顺序取第一个（正常配置不该重叠）。
     */
    private String buildArriveMessage(String picker, ChildEntity child, BeaconEntity beacon,
                                      java.util.Date now) {
        String childName = child != null && !child.getName().isEmpty()
                ? child.displayName()
                : (beacon != null ? beacon.getName() : "");

        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.setTime(now);
        int minuteOfDay = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60
                + cal.get(java.util.Calendar.MINUTE);
        // Calendar 里 1=周日 … 7=周六，换成 ISO 的 1=周一 … 7=周日
        int isoDay = (cal.get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7 + 1;

        long groupChildId = child != null ? child.getId() : 0;
        for (com.kidlink.beacon.data.TimeGroup g : BeaconPrefs.getTimeGroupsFor(this, groupChildId)) {
            if (g == null) continue;
            if (!g.matchesDay(isoDay)) continue;
            if (com.kidlink.beacon.data.TimeRanges.contains(g.ranges, minuteOfDay)) {
                return getString(R.string.arrive_typed_message, picker, childName,
                        g.displayName());
            }
        }
        return getString(R.string.arrive_push_message, picker, childName);
    }

    /** 距离显示分档，用于判断要不要重绘 */
    private int bucketOf(double d) {
        if (d < 1.0) return 0;
        if (d < 10.0) return 1;
        return 2;
    }

    /**
     * RSSI 换算出来的距离，1 米以内本来就没有分辨率可言（±5dB 噪声 ≈ ±0.5 米），
     * 所以贴着信标时给「1 米内」，而不是编一个 0.1 米出来。
     */
    private String formatDistance(double d) {
        if (d < 1.0) return getString(R.string.distance_near);
        if (d < 10.0) return getString(R.string.distance_approx, d);
        return getString(R.string.distance_far, Math.round(d));
    }

    private void setStateText(Panel p, String text, int colorRes) {
        p.state.setText(text);
        p.state.setTextColor(ContextCompat.getColor(this, colorRes));
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        if (scanner != null) scanner.stopScan();
        BeaconForegroundService.stop(this);
    }
}
