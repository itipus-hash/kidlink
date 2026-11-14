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
import com.kidlink.beacon.R;
import com.kidlink.beacon.ble.BeaconScanner;
import com.kidlink.beacon.ble.IBeaconData;
import com.kidlink.beacon.ble.IBeaconParser;
import com.kidlink.beacon.data.BeaconDbHelper;
import com.kidlink.beacon.data.BeaconEntity;
import com.kidlink.beacon.data.BeaconPrefs;
import com.kidlink.beacon.data.WifiGate;
import com.kidlink.beacon.push.PushClient;
import com.kidlink.beacon.service.BeaconForegroundService;
import com.kidlink.beacon.ui.widget.Feedback;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

/**
 * 首页：显示选中的信标状态与估算距离。
 *
 * 支持同时选中最多 3 个信标，纵向等分：
 * - 选 1 个 → 一块占满整个区域
 * - 选 2 个 → 上下各一半
 * - 选 3 个 → 三等分，每块整体缩一档（图标 / 字号小一点，保证不溢出）
 *
 * 每一块的状态完全独立（各自的 RSSI 滤波、丢失判定、迟滞、冷却）；
 * 推送也是各推各的 —— 分别推「爸爸接到许小鱼」「爸爸接到许大鱼」，
 * 每个信标各自算 2 小时冷却，不会互相吃掉。
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
    /** 当前选中的信标（顺序 = 从上到下），最多三个 */
    private final List<BeaconEntity> currentBeacons = new ArrayList<>();
    /** 三条水平分隔线，按块数显示前 N-1 条 */
    private View[] dividers;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean emptyTipShown = false;
    /** 当前这批扫描目标的指纹，用来判断"要不要重启扫描" */
    private String scanTargetKey = "";
    /** 本轮扫描开始时间，配合看门狗用 */
    private long scanStartedAt = 0;
    private long lastScanRestartAt = 0;

    /** 一块显示区：圆环 + 名字 + 距离 + 信号 + 状态，各自带独立测量状态 */
    private static class Panel {
        View container;
        View circleBox;
        View circleBg;
        View icon;
        TextView name;
        TextView distance;
        TextView rssi;
        TextView state;
        View rowSignal;

        BeaconEntity beacon;
        final LinkedList<Integer> rssiBuffer = new LinkedList<>();
        boolean hadSignal = false;
        long lastSignalAt = 0;
        double lastShownDistance = -1;
        /** 上一次渲染时是否处于变色距离以内，用于判断"刚进入" */
        boolean near = false;
        /** 上一次渲染时是否已在悬浮提醒的触发距离内 */
        boolean frontTipOn = false;
        /** 这个信标「已经在到达区内」；只有距离超过阈值 + 3 米才清（跨重启保存在 prefs） */
        boolean arrivedFired = false;
        /** 这个信标「已经在接近区内」；同样只认距离真的走远，信号抖动和冷启动都不清 */
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
                    if (p.beacon == null || !p.hadSignal) continue;
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
                R.id.txt_beacon_name, R.id.txt_distance, R.id.row_signal, R.id.txt_rssi,
                R.id.txt_state);
        bindPanel(1,
                R.id.panel_2, R.id.circle_container_2, R.id.circle_bg_2, R.id.img_beacon_2,
                R.id.txt_beacon_name_2, R.id.txt_distance_2, R.id.row_signal_2, R.id.txt_rssi_2,
                R.id.txt_state_2);
        bindPanel(2,
                R.id.panel_3, R.id.circle_container_3, R.id.circle_bg_3, R.id.img_beacon_3,
                R.id.txt_beacon_name_3, R.id.txt_distance_3, R.id.row_signal_3, R.id.txt_rssi_3,
                R.id.txt_state_3);

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
                           int nameId, int distanceId, int signalId, int rssiId, int stateId) {
        Panel p = new Panel();
        p.container = findViewById(containerId);
        p.circleBox = findViewById(circleBoxId);
        p.circleBg = findViewById(circleId);
        p.icon = findViewById(iconId);
        p.name = findViewById(nameId);
        p.distance = findViewById(distanceId);
        p.rowSignal = findViewById(signalId);
        p.rssi = findViewById(rssiId);
        p.state = findViewById(stateId);
        panels[index] = p;
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
            if (p.beacon == null) continue;
            p.approachFired = false;
            p.arrivedFired = false;
            BeaconPrefs.setApproachInside(this, p.beacon.getId(), false);
            BeaconPrefs.setArrivalInside(this, p.beacon.getId(), false);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        isForeground = false;
        // 不停扫描：后台靠前台服务保活
    }

    // === 选中的信标 ===

    private void loadCurrentBeacons() {
        // 只有一个信标时自动选中（第一次添加完即生效）
        BeaconPrefs.ensureAutoSelect(this, dbHelper);

        List<Long> ids = BeaconPrefs.getSelectedIds(this);
        List<BeaconEntity> fresh = new ArrayList<>();
        for (Long id : ids) {
            if (fresh.size() >= BeaconPrefs.MAX_SELECTED) break;
            BeaconEntity b = dbHelper.getBeaconById(id);
            if (b != null) fresh.add(b);
        }
        // 信标被删掉了 → 把失效 id 清掉，别让它一直占着位置
        if (fresh.size() != ids.size()) {
            List<Long> valid = new ArrayList<>();
            for (BeaconEntity b : fresh) valid.add(b.getId());
            BeaconPrefs.setSelectedIds(this, valid);
        }

        // 有几个信标就显示几块，分隔线只出现在相邻两块之间
        int count = fresh.size();
        for (int i = 1; i < PANEL_COUNT; i++) {
            panels[i].container.setVisibility(count > i ? View.VISIBLE : View.GONE);
        }
        for (int i = 0; i < dividers.length; i++) {
            dividers[i].setVisibility(count > i + 1 ? View.VISIBLE : View.GONE);
        }
        applyPanelLayout(count);

        // 选择没变（只是从设置页 / 信标管理页 / 后台回来）→ **保留上次画面**，
        // 不能一回来就把图标刷灰、距离清空。只有真的换了信标才重置。
        if (sameSelection(currentBeacons, fresh)) {
            for (int i = 0; i < panels.length; i++) {
                Panel p = panels[i];
                p.beacon = i < fresh.size() ? fresh.get(i) : null;
                if (p.beacon == null) continue;
                // 管理页可能改过名字
                if (p.hadSignal) p.name.setText(p.beacon.getName());
                // 「已在区内」的真实状态在 prefs 里（信标信息页的「清除冷却」会把它清掉），
                // 内存这份只是镜像 —— 不同步的话，清完冷却回到首页还是不会推，
                // 非得把 APP 杀了重开（那时走的是下面那个分支）才生效。
                p.approachFired = BeaconPrefs.isApproachInside(this, p.beacon.getId());
                p.arrivedFired = BeaconPrefs.isArrivalInside(this, p.beacon.getId());
            }
            if (fresh.isEmpty()) {
                currentBeacons.clear();
                renderNoBeacon(panels[0]);
            }
            return;
        }

        currentBeacons.clear();
        currentBeacons.addAll(fresh);

        for (int i = 0; i < panels.length; i++) {
            Panel p = panels[i];
            p.beacon = i < currentBeacons.size() ? currentBeacons.get(i) : null;
            p.rssiBuffer.clear();
            p.hadSignal = false;
            p.lastSignalAt = 0;
            p.lastShownDistance = -1;
            // 「已经在区内」是持久状态：APP 接完人会自己退出，这里必须从 prefs 恢复，
            // 否则重新打开时人还站在信标旁边，又推一条「已靠近」
            p.arrivedFired = p.beacon != null
                    && BeaconPrefs.isArrivalInside(this, p.beacon.getId());
            p.approachFired = p.beacon != null
                    && BeaconPrefs.isApproachInside(this, p.beacon.getId());
            p.near = false;
            p.frontTipOn = false;
            resetPanelView(p);
        }

        if (currentBeacons.isEmpty()) {
            renderNoBeacon(panels[0]);
        } else {
            for (Panel p : panels) {
                if (p.beacon != null) renderSearching(p);
            }
        }
    }

    /** 两次选择是不是同一批信标（顺序也算） */
    private boolean sameSelection(List<BeaconEntity> old, List<BeaconEntity> fresh) {
        if (old.size() != fresh.size()) return false;
        for (int i = 0; i < old.size(); i++) {
            if (old.get(i).getId() != fresh.get(i).getId()) return false;
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
        int distH = dp(triple ? 32 : 42);
        int signalH = dp(triple ? 16 : 20);
        int stateH = dp(triple ? 28 : 36);
        float stateSp = triple ? 12f : 13f;

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
            p.rssiBuffer.clear();
            if (p.beacon != null && !p.hadSignal) renderSearching(p);
        }
        // 首页同时盯两个信标：用软件层的多目标过滤，硬件过滤一律不设
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
            if (p.beacon != null && p.lastSignalAt > latest) latest = p.lastSignalAt;
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
        if (p == null || p.beacon == null) return;

        // 旧数据里没有 MAC：第一次收到广播就顺手补上。
        // 补完之后这个信标只认自己的 MAC，同品牌三件套一样的另一台就不会串进来。
        if (!p.beacon.hasMac() && !mac.isEmpty()) {
            p.beacon.setMac(mac);
            dbHelper.updateMac(p.beacon.getId(), mac);
        }

        int filtered = smoothRssi(p, data.rssi);
        double distance = p.beacon.estimateDistance(filtered);
        if (distance <= 0) return;

        handler.post(() -> {
            if (p.beacon == null) return;
            p.hadSignal = true;
            p.lastSignalAt = System.currentTimeMillis();
            renderSignal(p, distance, filtered);
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
     * 这条广播属于上/下哪一块。
     *
     * 匹配顺序：有 MAC 的信标先比 MAC，再比 UUID+Major+Minor。
     * 只比三件套是不够的 —— 同品牌的两台信标出厂可能 UUID 一模一样，
     * 光看三件套会把两台设备都算到同一个面板上（A 在楼下 B 在学校，屏幕上两个名字一起跳）。
     */
    private Panel findPanel(IBeaconData data, String mac) {
        String m = mac == null ? "" : mac.trim().toUpperCase();
        for (Panel p : panels) {
            if (p.beacon == null) continue;
            if (p.beacon.hasMac() && !p.beacon.getMac().equals(m)) continue;
            if (p.beacon.getMajor() != data.major) continue;
            if (p.beacon.getMinor() != data.minor) continue;
            if (IBeaconParser.uuidMatch(data.uuid, p.beacon.getUuidFormatted())) return p;
        }
        return null;
    }

    private int smoothRssi(Panel p, int rssi) {
        synchronized (p.rssiBuffer) {
            p.rssiBuffer.addLast(rssi);
            while (p.rssiBuffer.size() > RSSI_WINDOW) p.rssiBuffer.removeFirst();
            int sum = 0;
            for (int r : p.rssiBuffer) sum += r;
            return sum / p.rssiBuffer.size();
        }
    }

    // === 渲染 ===
    //
    // 圆环、信标名、状态、距离几块的高度都在 XML 里写死了，
    // 这里只切换 INVISIBLE/VISIBLE，不改变占用空间，
    // 所以任何状态下图标和名字的纵向位置都不会动。

    /** 清空这一块的残留内容（切换选择时用） */
    private void resetPanelView(Panel p) {
        p.circleBg.setBackgroundResource(R.drawable.bg_circle_gray);
        p.name.setText("");
        p.distance.setText("");
        p.distance.setVisibility(View.INVISIBLE);
        p.rowSignal.setVisibility(View.INVISIBLE);
        p.rssi.setText("");
        p.state.setText("");
    }

    /** 一个信标都没选 */
    private void renderNoBeacon(Panel p) {
        resetPanelView(p);
        setStateText(p, getString(R.string.no_beacon_selected), R.color.text_secondary);
    }

    /** 已选信标但还没收到过信号：图标灰着就够了，不写任何文案 */
    private void renderSearching(Panel p) {
        p.circleBg.setBackgroundResource(R.drawable.bg_circle_gray);
        p.name.setText(p.beacon != null ? p.beacon.getName() : "");
        p.distance.setText("");
        p.distance.setVisibility(View.INVISIBLE);
        p.rowSignal.setVisibility(View.INVISIBLE);
        p.lastShownDistance = -1;
        p.state.setText("");
    }

    /** 超过检测间隔还没收到广播：只把图标变灰，不写"未检测到信号" */
    private void renderLost(Panel p) {
        p.circleBg.setBackgroundResource(R.drawable.bg_circle_gray);
        p.name.setText(p.beacon != null ? p.beacon.getName() : "");
        p.distance.setText("");
        p.distance.setVisibility(View.INVISIBLE);
        p.rowSignal.setVisibility(View.INVISIBLE);
        p.lastShownDistance = -1;
        // 断线不代表走远：15 秒收不到广播（默认 detect_interval）就当离区的话，
        // 家里信号一抖，两条推送都会重新武装一次。离开只认「距离真的超过阈值 + 3 米」。
        p.state.setText("");
    }

    private void renderSignal(Panel p, double distance, int rssi) {
        p.name.setText(p.beacon.getName());
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
        if (p.beacon == null) return;
        if (WifiGate.blocked(this)) return;
        if (inArrivalCooldown(p.beacon.getId())) return;
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
                if (isForeground && !WifiGate.blocked(this)) {
                    showLightTip(p.beacon.getName(), meters);
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

    /** 这个信标是否还在到达冷却期内 */
    private boolean inArrivalCooldown(long beaconId) {
        long cooldownMs = BeaconPrefs.getPushCooldownMin(this) * 60_000L;
        return System.currentTimeMillis() - BeaconPrefs.getLastPushAt(this, beaconId) < cooldownMs;
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
        if (p.beacon == null) return;

        final int approachMeters = BeaconPrefs.getApproachMeters(this);
        final long beaconId = p.beacon.getId();

        if (approachMeters <= 0) {
            p.approachFired = false;
            return;
        }

        // 已经算「在区内」：只要没真的走远就一直保持，中间怎么抖都不推
        if (p.approachFired) {
            if (distance > approachMeters + REARM_EXTRA_METERS) {
                p.approachFired = false;
                BeaconPrefs.setApproachInside(this, beaconId, false);
            }
            return;
        }
        if (distance > approachMeters) return;

        // 人已经站在娃旁边（到达距离内）→ 不用说「已靠近」，后面那条「接到」就够，
        // 否则回家一开 APP 就是「已靠近3米」+「接到了」两条连响
        if (distance <= BeaconPrefs.getPushMeters(this) + 2) {
            p.approachFired = true;
            BeaconPrefs.setApproachInside(this, beaconId, true);
            return;
        }

        // 进区：先把状态落下来，后面无论推没推成，这次进入都不会再重复触发
        p.approachFired = true;
        BeaconPrefs.setApproachInside(this, beaconId, true);

        // 指定 WiFi 不提醒（家里 / 公司这类已知不用提醒的地方）。
        // 状态照常落下 —— 这次进入就不再补推了，只是不发请求出去。
        if (WifiGate.blocked(this)) return;

        final java.util.List<String> tokens = BeaconPrefs.getEnabledTokenValues(this);
        if (tokens.isEmpty()) return;
        if (inApproachCooldown(beaconId) || inApproachGap()) return;

        // 时间段：今天一整天没勾任何分组 → 这天不推（和接送成功推送保持一致）
        final java.util.Date now = new java.util.Date();
        if (!todayHasAnyGroup(now)) return;

        String picker = BeaconPrefs.getPickerName(this);
        String beaconName = p.beacon.getName();
        String meters = String.format(java.util.Locale.getDefault(), "%.0f", distance);
        final String msg = getString(R.string.approach_push_message, picker, beaconName, meters);

        new PushClient().sendAll(tokens, msg, msg, (ok, m) -> {
            if (ok) {
                long at = System.currentTimeMillis();
                BeaconPrefs.setLastApproachAt(MainActivity.this, beaconId, at);
                BeaconPrefs.setLastAnyPushAt(MainActivity.this, at);
                showPushedTip();
            } else {
                // 推失败不写冷却、也退回「不在区内」，下一包还能重试
                p.approachFired = false;
                BeaconPrefs.setApproachInside(MainActivity.this, beaconId, false);
                Feedback.show(MainActivity.this, getString(R.string.push_failed, m));
            }
        });
    }

    /**
     * 近距离推送的冷却：自己上次「已靠近」+ 这个信标上次「接到人」，
     * 两个时间戳取更近的那个 —— 接过人之后短期内就不用再喊「正在靠近」了。
     */
    private boolean inApproachCooldown(long beaconId) {
        long cooldownMs = BeaconPrefs.getPushCooldownMin(this) * 60_000L;
        long now = System.currentTimeMillis();
        if (now - BeaconPrefs.getLastPushAt(this, beaconId) < cooldownMs) return true;
        return now - BeaconPrefs.getLastApproachAt(this, beaconId) < cooldownMs;
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
    private void maybePushArrival(Panel p, double distance) {
        if (p.beacon == null) return;

        final double pushMeters = BeaconPrefs.getPushMeters(this);
        final long beaconId = p.beacon.getId();

        // 同样按「进入事件」处理：进过 2 米就一直算在区内，直到真的走出 5 米外
        if (p.arrivedFired) {
            if (distance > pushMeters + REARM_EXTRA_METERS) {
                p.arrivedFired = false;
                BeaconPrefs.setArrivalInside(this, beaconId, false);
            }
            return;
        }
        if (distance > pushMeters) return;

        // 进区：先落状态，冷却中 / 今天不推也不会在之后再补一条
        p.arrivedFired = true;
        BeaconPrefs.setArrivalInside(this, beaconId, true);

        // 指定 WiFi 不提醒：这里同样先落状态再判断，
        // 免得「在家被屏蔽」这件事在换了网络之后又补推一条迟到的「接到」
        if (WifiGate.blocked(this)) return;

        final java.util.List<String> tokens = BeaconPrefs.getEnabledTokenValues(this);
        if (tokens.isEmpty()) return;
        if (inArrivalCooldown(beaconId)) return;

        String picker = BeaconPrefs.getPickerName(this);
        String beaconName = p.beacon.getName();
        java.util.Date now = new java.util.Date();

        // 时间段类型里今天一天都没勾 → 今天整天不推接送通知（周末不接娃就是这种情况）
        if (!todayHasAnyGroup(now)) return;

        final String msg = buildArriveMessage(picker, beaconName, now);

        new PushClient().sendAll(tokens, msg, msg, (ok, m) -> {
            if (ok) {
                long at = System.currentTimeMillis();
                BeaconPrefs.setLastPushAt(MainActivity.this, beaconId, at);
                BeaconPrefs.setLastAnyPushAt(MainActivity.this, at);
                showPushedTip();
                // 提示条走完再退（设置里可以关掉）
                exitAfterTipIfNeeded();
            } else {
                // 推失败不写冷却，也退回「不在区内」，走出 5 米再回来还能重试
                p.arrivedFired = false;
                BeaconPrefs.setArrivalInside(MainActivity.this, beaconId, false);
                Feedback.show(MainActivity.this, getString(R.string.push_failed, m));
            }
        });
    }

    /** 今天（星期几）有没有至少一个时间段类型勾上了；一个都没勾 = 今天不推送 */
    private boolean todayHasAnyGroup(java.util.Date now) {
        java.util.List<com.kidlink.beacon.data.TimeGroup> groups =
                BeaconPrefs.getTimeGroups(this);
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
     * 接送成功的文案：命中某个时间段类型就把它的名字当标签挂在括号里。
     * 命中「上学」→ 「爸爸接到许小鱼了（上学）」
     * 命中「放学」→ 「爸爸接到许小鱼了（放学）」
     * 命中自己加的「兴趣班」→ 「爸爸接到许小鱼了（兴趣班）」
     * 一个都不命中 → 退回通用文案「爸爸接到许小鱼了」
     *
     * 不再带具体时间：推送本身就是「此刻」发生的，手机通知栏有收到时间，
     * 文案里再写一遍 22:24 只会让句子变长、还容易被误读成「记录」而不是「通知」。
     *
     * 分组名直接取设置里用户填的名字，改名后推送自动跟着变。
     * 时间段**精准匹配**：只在窗口内的那一分钟算命中，窗口结束就不再算。
     * 晚接几分钟就会掉回通用文案「爸爸接到许小鱼了」—— 这是有意的，
     * 宁可少挂一个标签，也不要让 12:20 接到的娃被标成「放学」。
     * 想覆盖晚接，把那个分组的时间段拉长就行（比如放学 17:00-18:30）。
     * 分组还带星期维度：不在它勾选的星期里就跳过。
     * 多个分组同时命中时按设置里的先后顺序取第一个（正常配置不该重叠）。
     */
    private String buildArriveMessage(String picker, String beaconName, java.util.Date now) {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.setTime(now);
        int minuteOfDay = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60
                + cal.get(java.util.Calendar.MINUTE);
        // Calendar 里 1=周日 … 7=周六，换成 ISO 的 1=周一 … 7=周日
        int isoDay = (cal.get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7 + 1;

        for (com.kidlink.beacon.data.TimeGroup g : BeaconPrefs.getTimeGroups(this)) {
            if (g == null) continue;
            if (!g.matchesDay(isoDay)) continue;
            if (com.kidlink.beacon.data.TimeRanges.contains(g.ranges, minuteOfDay)) {
                return getString(R.string.arrive_typed_message, picker, beaconName,
                        g.displayName());
            }
        }
        return getString(R.string.arrive_push_message, picker, beaconName);
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
