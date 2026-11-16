package com.kidlink.beacon.ble;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.bluetooth.le.BluetoothLeScanner;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.core.content.ContextCompat;

import com.kidlink.beacon.data.BeaconEntity;

import java.util.LinkedList;
import java.util.List;

/**
 * BLE 扫描器 - 专门扫描 iBeacon 格式广播
 *
 * 特性:
 * - 只解析 iBeacon 格式，过滤无关设备
 * - 顺便解析 Eddystone-TLM（明文），把电池电压交给 BatteryStore（每颗信标最多一小时落库一次）
 * - 支持指定 UUID+Major+Minor 精确过滤
 * - RSSI 滑动平均滤波抑制跳动
 * - 适配 Android 12+ 权限和后台扫描规则
 * - 兼容小米/OPPO/vivo 省电策略
 */
public class BeaconScanner {

    private static final String TAG = "BeaconScanner";
    private static final int SCAN_PERIOD_MS = 0; // 0 = 持续扫描
    private static final int RSSI_WINDOW_SIZE = 5; // 滑动平均窗口

    private final Context context;
    private final BluetoothManager bluetoothManager;
    private final BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner bleScanner;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private volatile boolean isScanning = false;
    private ScanCallbackImpl scanCallback;

    /**
     * 自动重启扫描。
     *
     * 实测：持续扫描满 12 秒之后，系统把送给 App 的广播掐到只剩 1/9
     * （间隔全是 2.6s 的整数倍），而厂商 App 每次只扫 12 秒，所以它一直是满速
     * （120 条/12 秒 ≈ 100ms 一条）。这里照抄它的节奏：每 9 秒停一下再开，
     * 用「重启」把限流计数器清零，收包率就能维持在满速。
     */
    private static final long SCAN_RESTART_MS = 9_000L;

    /**
     * 要不要同时收「扩展广播」。
     *
     * 背景：这颗信标本机只发一种 legacy 包（纯 iBeacon，30 字节），
     * 但厂商 App 能显示 TLM 的 mV。我们已经确认收包是满速的（每 9 秒 15 条，
     * 约 600ms 一条，正好是厂商测到的 298ms 间隔的 2 倍）——说明它很可能是
     * 「iBeacon / TLM 交替发」，而交替的那一半走的是扩展广播 PDU，
     * legacy 扫描在框架层就把它们过滤掉了，根本不会回调给我们。
     *
     * 实测结论（2026-11-16，满速扫描条件下重测）：
     * 打开后同一颗信标 9 秒只剩 3~7 条（纯 legacy 是 15 条），
     * 因为射频要轮流守 1M 和 Coded PHY，反而漏包；而且指纹里
     * `sid=255`（没有第二套 advertising set）、没有出现任何 `AA FE`。
     * 即这颗信标在空中**只发 iBeacon**，TLM 不存在于广播里。故关闭。
     */
    private static final boolean USE_EXTENDED_SCAN = false;

    /** true = 本次 startScan 是自动刷新发起的，不要清掉统计 */
    private volatile boolean refreshing = false;

    // RSSI 滑动平均滤波器
    private final LinkedList<Integer> rssiBuffer = new LinkedList<>();

    // 过滤条件（可选）
    private String filterUuid = null;
    private int filterMajor = -1;
    private int filterMinor = -1;

    /** 同时关注的多个信标（首页最多显示两个） */
    private final java.util.List<String> targetKeys = new java.util.ArrayList<>();

    /** 本次扫描中已经上报过的 MAC，保证 onBleDeviceSeen 每台设备只回调一次 */
    private final java.util.Set<String> seenMacs = new java.util.HashSet<>();

    /**
     * 已经记过「有 Eddystone 数据但没解出 TLM」的设备。
     *
     * 用来支持排查「信标说开了 TLM，App 却显示未开」：每台设备只打一行原始广播，
     * 看是帧类型不对（0x21 加密）、长度被截断，还是压根没发。
     */
    private final java.util.Set<String> eddystoneLogged = new java.util.HashSet<>();

    /**
     * 调试用：本次扫描里每台设备都解析出了什么帧。
     * mac → 标记串（"IB" = iBeacon，"TLM" = 明文遥测，"IB+TLM" = 两种都有）。
     * 只用来回答「我们到底有没有收到那台设备」这类问题。
     */
    private final java.util.Map<String, String> frameTags =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 调试用：每台设备发过的「非 iBeacon 包」的指纹（前 12 字节）→ 已经记过。
     *
     * 排查「厂商 App 能看到 TLM、我们看不到」时用：把解不出 iBeacon 的包按指纹去重后
     * 打一行 hex，就能看出对方到底发的是标准 Eddystone 服务数据，还是塞在厂商数据里、
     * 或者根本没发。指纹取前 12 字节，同一类帧只记一次，日志不会被计数位刷屏。
     */
    private final java.util.Map<String, java.util.Set<String>> rawSignatures =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** 全局上限：调试日志最多打这么多行，防止周围设备太多把 logcat 冲掉 */
    private int sigLogBudget = 200;
    /**
     * 调试：每台设备每种「包长相」（前 12 字节）出现了多少次。
     *
     * 用来一句话回答「这颗信标到底只发一种包，还是两种包交替发、我们只收到一种」——
     * 把 map 打出来，如果同一颗信标只有一个指纹，那就是真没发第二种；
     * 有两个以上，说明我们其实收到了，只是没解出来。
     */
    private final java.util.Map<String, java.util.Map<String, Integer>> sigStats =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 心跳日志里盯着看的 MAC（排查特定信标时改这里，空串 = 不打） */
    private static final String WATCH_MAC = "48:87:2D:9E:6D:FB";

    /**
     * 抓包落盘：把盯着的那颗信标的每一条广播、以及任何带 Eddystone 服务数据的广播
     * 原样写进文件（logcat 会被刷掉，文件不会）。
     *
     * 用来回答「到底有没有收到 TLM 包」：跑几分钟后 adb 把文件拉下来数一数就行。
     */
    private static final boolean CAPTURE_TO_FILE = com.kidlink.beacon.BuildConfig.DEBUG;
    private java.io.Writer captureWriter;
    private int captureLines = 0;
    private static final int CAPTURE_MAX_LINES = 4000;

    // 心跳：每 5 秒打一行「还活着 + 收了多少包」，用来验证后台扫描有没有被系统掐掉
    private long lastHeartbeatAt = 0;
    private int heartbeatCount = 0;

    /**
     * 每 SCAN_RESTART_MS 停一次再开一次。
     *
     * 必须是**全局唯一**的：这个 App 有三处会 new BeaconScanner（首页 / 信标管理 / 添加信标），
     * 如果每个实例各挂一个定时器，就会叠成「每 0.6 秒重启一次」的风暴，
     * 实测那种状态下连 iBeacon 都收不到几包（日志里成片的 "0 results in last 9s"）。
     * 所以定时器和「当前该由谁刷新」都做成静态的，只保留一份。
     */
    private static final Handler RESTART_TIMER = new Handler(Looper.getMainLooper());
    private static final java.util.concurrent.atomic.AtomicReference<BeaconScanner> ACTIVE_SCANNER =
            new java.util.concurrent.atomic.AtomicReference<>();

    private static final Runnable AUTO_RESTART = new Runnable() {
        @Override
        public void run() {
            BeaconScanner s = ACTIVE_SCANNER.get();
            if (s == null || !s.isScanning) return;
            Log.d(TAG, "scan refresh: " + s.heartbeatCount + " results in last "
                    + (SCAN_RESTART_MS / 1000) + "s");
            s.refreshScan();
            if (s.isScanning) {
                RESTART_TIMER.removeCallbacks(this);
                RESTART_TIMER.postDelayed(this, SCAN_RESTART_MS);
            }
        }
    };

    /** 停一下再开，用于绕过系统对长时扫描的限流；不清统计 */
    @SuppressLint("MissingPermission")
    private void refreshScan() {
        refreshing = true;
        try {
            stopScan();
            startScan();
        } catch (Exception e) {
            Log.e(TAG, "scan refresh failed", e);
        } finally {
            refreshing = false;
        }
    }

    public interface OnBeaconScanListener {
        /**
         * 扫描到匹配的 iBeacon
         * @param beacon 解析后的 iBeacon 数据
         * @param filteredRssi 滑动平均后的 RSSI
         * @param distance 估算距离
         */
        void onBeaconFound(IBeaconData beacon, int filteredRssi, double distance);

        /**
         * 扫描到匹配的 iBeacon（原始数据，用于长按查看原始广播）
         * @param beacon iBeacon 数据
         * @param scanResult 完整扫描结果
         */
        void onRawBeaconData(IBeaconData beacon, ScanResult scanResult);

        /**
         * 扫描错误
         */
        void onScanError(int errorCode, String message);

        /**
         * 每收到一条原始广播就回调一次（不管是不是 iBeacon）
         * 用于诊断"为什么扫不到"，页面可据此显示已发现多少台 BLE 设备
         */
        default void onBleDeviceSeen(ScanResult scanResult) {
        }
    }

    private OnBeaconScanListener listener;

    public BeaconScanner(Context context) {
        this.context = context;
        this.bluetoothManager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        if (bluetoothManager != null) {
            this.bluetoothAdapter = bluetoothManager.getAdapter();
        } else {
            this.bluetoothAdapter = null;
        }
    }

    public void setListener(OnBeaconScanListener listener) {
        this.listener = listener;
    }

    /**
     * 设置过滤条件（只匹配指定 UUID+Major+Minor 的 iBeacon）
     */
    public void setFilter(String uuid, int major, int minor) {
        this.filterUuid = uuid;
        this.filterMajor = major;
        this.filterMinor = minor;
    }

    /** 同时关注多个信标（首页上下两块） */
    public void setTargets(java.util.List<BeaconEntity> beacons) {
        targetKeys.clear();
        if (beacons != null) {
            for (BeaconEntity b : beacons) {
                targetKeys.add(keyOf(b.getUuidFormatted(), b.getMajor(), b.getMinor()));
            }
        }
    }

    public void clearTargets() {
        targetKeys.clear();
    }

    private static String keyOf(String uuid, int major, int minor) {
        return (uuid == null ? "" : uuid.replace("-", "").toUpperCase())
                + "|" + major + "|" + minor;
    }

    /**
     * 清除过滤条件（扫描所有 iBeacon）
     */
    public void clearFilter() {
        this.filterUuid = null;
        this.filterMajor = -1;
        this.filterMinor = -1;
    }

    /**
     * 清空 RSSI 滤波缓冲区（切换标签时调用）
     */
    public void resetRssiFilter() {
        rssiBuffer.clear();
    }

    /**
     * 检查蓝牙是否可用
     */
    public boolean isBluetoothReady() {
        if (bluetoothAdapter == null) return false;
        return bluetoothAdapter.isEnabled();
    }

    /**
     * 检查是否有蓝牙扫描权限
     */
    public boolean hasScanPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                    && ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        } else {
            return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        }
    }

    /**
     * 开始扫描
     */
    @SuppressLint("MissingPermission")
    public boolean startScan() {
        if (!isBluetoothReady()) {
            if (listener != null) listener.onScanError(-1, "蓝牙未开启");
            return false;
        }
        if (!hasScanPermission()) {
            if (listener != null) listener.onScanError(-2, "缺少蓝牙扫描权限");
            return false;
        }
        if (isScanning) return true;

        if (bluetoothAdapter == null) {
            if (listener != null) listener.onScanError(-3, "蓝牙适配器不可用");
            return false;
        }

        bleScanner = bluetoothAdapter.getBluetoothLeScanner();
        if (bleScanner == null) {
            if (listener != null) listener.onScanError(-4, "BLE Scanner 不可用");
            return false;
        }

        scanCallback = new ScanCallbackImpl();
        resetRssiFilter();
        heartbeatCount = 0;
        lastHeartbeatAt = 0;
        // 定期重启扫描时不要把统计清掉，否则心跳永远是空的
        if (!refreshing) {
            synchronized (seenMacs) {
                seenMacs.clear();
            }
            synchronized (eddystoneLogged) {
                eddystoneLogged.clear();
            }
            frameTags.clear();
            rawSignatures.clear();
            sigLogBudget = 200;
            sigStats.clear();
        }

        if (CAPTURE_TO_FILE && captureWriter == null) {
            try {
                java.io.File f = new java.io.File(context.getExternalFilesDir(null), "tlm_capture.log");
                captureWriter = new java.io.OutputStreamWriter(
                        new java.io.FileOutputStream(f, true), "UTF-8");
                captureLines = 0;
                Log.d(TAG, "capture -> " + f.getAbsolutePath());
            } catch (Exception e) {
                Log.w(TAG, "capture open failed", e);
            }
        }

        // 扫描设置 - 低延迟模式，适配后台扫描
        //
        // 注意 1：这里故意不传 ScanFilter（传 null 扫全部广播）。
        // 原因：部分 ROM（华为/部分 MTK）对 setManufacturerData 的过滤实现不一致，
        // 会导致 iBeacon 明明在广播却一条都收不到；硬件层过滤省的那点电，
        // 不值得换来"扫不到"。所有过滤都在 onScanResult 里用软件完成。
        //
        // 注意 2：**不要**开 setLegacy(false) + setPhy。
        // 曾经为了"能收到扩展广播里的 TLM"加过这段，结果是这台机器上
        // 只报主广播、丢掉交替发的第二种包（TLM 就藏在里面），
        // 而厂商 App 用的是最朴素的 legacy 设置，两种包都收得到。
        // 现在跟厂商保持一致：默认设置，什么都不加。
        // 扫描设置 —— 逐项对齐厂商 App（从 com.dxlq.ibeacon 的 ScanLeDevice 反汇编得到）：
        //     new ScanSettings.Builder().setScanMode(2).setCallbackType(1).build()
        //     startScan(null, settings, cb)  +  postDelayed(stopLeScan, 12000)
        // 也就是说它只设了 LOW_LATENCY 和 ALL_MATCHES，别的全是默认值，而且扫 12 秒就停。
        //
        // 曾经在这里加过 setMatchMode(MATCH_MODE_AGGRESSIVE) + setNumOfMatches(NUM_MAX)，
        // 结果：iBeacon 帧收得到，同一个 MAC 交替发出来的 Eddystone-TLM 帧一包都收不到
        // （厂商 App 同一时刻能显示 3068mv）。这两个参数只有在**带硬件过滤器**时才有意义，
        // 不带过滤器时行为未定义，部分 HAL 会据此按 advertiser 名单裁剪广播。
        // 现在一律不加，跟厂商保持一致。
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .build();

        try {
            bleScanner.startScan(null, settings, scanCallback);
            isScanning = true;
            Log.d(TAG, "BLE scan started (no hardware filter)");
        } catch (Exception e) {
            Log.e(TAG, "Failed to start scan", e);
            if (listener != null) listener.onScanError(-5, "启动扫描失败: " + e.getMessage());
            return false;
        }

        // 自动刷新定时器：全局只留一份，且只在外部启动时挂（刷新流程自己挂下一次）
        ACTIVE_SCANNER.set(this);
        if (!refreshing) {
            RESTART_TIMER.removeCallbacks(AUTO_RESTART);
            RESTART_TIMER.postDelayed(AUTO_RESTART, SCAN_RESTART_MS);
        }
        return true;
    }

    /**
     * 停止扫描
     */
    @SuppressLint("MissingPermission")
    public void stopScan() {
        if (!isScanning) return;
        isScanning = false;

        // 真正停止（切页面/退出）才取消自动刷新；刷新流程里的 stop 不能取消
        if (!refreshing) {
            RESTART_TIMER.removeCallbacks(AUTO_RESTART);
            ACTIVE_SCANNER.compareAndSet(this, null);
        }

        if (bleScanner != null && scanCallback != null) {
            try {
                bleScanner.stopScan(scanCallback);
            } catch (Exception e) {
                Log.e(TAG, "Failed to stop scan", e);
            }
        }
        scanCallback = null;
        if (captureWriter != null) {
            try {
                captureWriter.flush();
            } catch (Exception ignored) {
            }
        }
        Log.d(TAG, "BLE scan stopped");
    }

    public boolean isScanning() {
        return isScanning;
    }

    /**
     * 字节数组转大写 hex（空格分隔），用于诊断原始广播
     */
    public static String toHex(byte[] data) {
        if (data == null) return "";
        StringBuilder sb = new StringBuilder(data.length * 3);
        for (byte b : data) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(String.format("%02X", b & 0xFF));
        }
        return sb.toString();
    }

    /** 整包里是否出现过 FE AA 或 AA FE（不要求是合法的 AD 结构） */
    private static boolean containsFeAa(byte[] data) {
        if (data == null) return false;
        for (int i = 0; i + 1 < data.length; i++) {
            int a = data[i] & 0xFF;
            int b = data[i + 1] & 0xFF;
            if ((a == 0xAA && b == 0xFE) || (a == 0xFE && b == 0xAA)) return true;
        }
        return false;
    }

    /**
     * RSSI 滑动平均滤波
     */
    private int filterRssi(int rssi) {
        synchronized (rssiBuffer) {
            rssiBuffer.addLast(rssi);
            while (rssiBuffer.size() > RSSI_WINDOW_SIZE) {
                rssiBuffer.removeFirst();
            }
            int sum = 0;
            for (int r : rssiBuffer) sum += r;
            return sum / rssiBuffer.size();
        }
    }

    /**
     * 获取最近一次的原始 RSSI（不滤波）
     */
    public int getLatestRawRssi() {
        synchronized (rssiBuffer) {
            if (rssiBuffer.isEmpty()) return 0;
            return rssiBuffer.getLast();
        }
    }

    /**
     * 扫描回调实现
     */
    private class ScanCallbackImpl extends android.bluetooth.le.ScanCallback {
        @Override
        @SuppressLint("MissingPermission")
        public void onScanResult(int callbackType, ScanResult result) {
            if (result == null) return;

            BluetoothDevice device = result.getDevice();
            byte[] scanData = result.getScanRecord() != null ?
                    result.getScanRecord().getBytes() : null;
            int rssi = result.getRssi();

            if (scanData == null) return;

            // 心跳日志：只要还有广播进来就每 5 秒打一行。
            // 后台跑的时候用 `adb logcat -s BeaconScanner` 一看就知道扫描是不是还活着。
            heartbeatCount++;
            long nowMs = System.currentTimeMillis();
            if (nowMs - lastHeartbeatAt > 5000) {
                lastHeartbeatAt = nowMs;
                Log.d(TAG, "alive: " + heartbeatCount + " results, last rssi=" + rssi);
                if (com.kidlink.beacon.BuildConfig.DEBUG) {
                    // 每 5 秒把「收到了哪些设备、各自是什么帧」摊开一次：
                    // 排查「厂商 App 看得到、我们看不到」时，一眼就知道是没收到还是没解出来
                    int ib = 0, tlmCount = 0;
                    StringBuilder sb = new StringBuilder();
                    for (java.util.Map.Entry<String, String> e : frameTags.entrySet()) {
                        if (e.getValue().contains("IB")) ib++;
                        if (e.getValue().contains("TLM")) tlmCount++;
                        if (sb.length() < 500) {
                            sb.append(e.getKey()).append('=').append(e.getValue()).append(' ');
                        }
                    }
                    Log.d(TAG, "frames: dev=" + frameTags.size() + " ib=" + ib
                            + " tlm=" + tlmCount + " | " + sb);
                    // 盯着的那颗信标：把它收到的「包长相」及次数摊开
                    java.util.Map<String, Integer> watch = sigStats.get(WATCH_MAC);
                    if (!WATCH_MAC.isEmpty() && watch != null) {
                        Log.d(TAG, "watch " + WATCH_MAC + " sigs=" + watch);
                    }
                }
                // 调试版才落盘：后台被 ROM 掐掉时 logcat 也可能看不到，
                // 用 adb 读这个文件就能确认到底有没有在收包
                if (com.kidlink.beacon.BuildConfig.DEBUG) {
                    context.getApplicationContext()
                            .getSharedPreferences("kidlink_prefs", Context.MODE_PRIVATE)
                            .edit().putLong("last_scan_result_at", nowMs).apply();
                }
            }

            // 每台设备只上报一次，既做诊断计数，也把原始广播打进 logcat
            String mac = device != null ? device.getAddress() : null;
            boolean firstSeen = false;
            if (mac != null) {
                synchronized (seenMacs) {
                    firstSeen = seenMacs.add(mac);
                }
            }
            if (firstSeen) {
                Log.d(TAG, "ADV " + mac + " rssi=" + rssi + " raw=" + toHex(scanData));
                if (listener != null) {
                    final ScanResult rawResult = result;
                    mainHandler.post(() -> {
                        if (listener != null) listener.onBleDeviceSeen(rawResult);
                    });
                }
            }

            // 电量和识别帧是「交替广播」的两种包：这条广播多半是 iBeacon，
            // 但没开 iBeacon 的那几条里可能带着 TLM 遥测。先顺手解一下 TLM ——
            // 解不出就是一次字节遍历的开销，解出来就把电量交给 BatteryStore 落内存/落库。
            TlmData tlm = TlmParser.parse(scanData);
            if (tlm != null && mac != null) {
                if (com.kidlink.beacon.BuildConfig.DEBUG) {
                    Log.d(TAG, "TLM " + mac + " " + tlm);
                }
                com.kidlink.beacon.data.BatteryStore.record(context, mac, tlm);
            } else if (mac != null && TlmParser.hasEddystoneService(scanData)) {
                // 有 Eddystone 服务数据但没解出明文 TLM：多半是加密 TLM(0x21)
                // 或者帧被截断。每台设备只记一次，留作排查用。
                synchronized (eddystoneLogged) {
                    if (eddystoneLogged.add(mac)) {
                        Log.d(TAG, "EDDYSTONE-no-TLM " + mac + " raw=" + toHex(scanData));
                    }
                }
            } else if (mac != null && containsFeAa(scanData)) {
                // 连 AD 结构都没走出来、但整包里出现 FE AA / AA FE：
                // 说明有设备在发 Eddystone，只是包结构与预期不同（写错 UUID 端序、被截断等）
                synchronized (eddystoneLogged) {
                    if (eddystoneLogged.add(mac + "|raw")) {
                        Log.d(TAG, "FEAA-in-raw " + mac + " raw=" + toHex(scanData));
                    }
                }
            }

            // 解析 iBeacon
            IBeaconData beacon = IBeaconParser.parse(scanData, rssi);

            // 抓包落盘（调试版）：盯着的 MAC 的每一条，以及任何含 Eddystone 服务数据的包
            if (CAPTURE_TO_FILE && mac != null && captureWriter != null
                    && captureLines < CAPTURE_MAX_LINES
                    && (mac.equalsIgnoreCase(WATCH_MAC) || containsFeAa(scanData))) {
                try {
                    captureWriter.write(System.currentTimeMillis() + "\t" + mac + "\t"
                            + (result.isLegacy() ? "L" : "E") + "\t"
                            + scanData.length + "\t" + toHex(scanData) + "\n");
                    captureLines++;
                    // 逐行 flush：以前是攒 20 行才落盘，短时间抓包（60 秒只有二十来帧）
                    // 会有一半留在缓冲区里，统计出来的间隔全是错的。
                    // 每秒最多一两帧，这点 IO 可以忽略。
                    captureWriter.flush();
                } catch (Exception ignored) {
                }
            }

            // 调试：给这条广播记一个「包长相」指纹（前 12 字节）的出现次数。
            // 同一颗信标如果只发一种包，这里就只有一个指纹；有两种（iBeacon + TLM 交替）
            // 就说明我们其实收得到，只是之前没解出来 —— 这是判断「到底谁的问题」最硬的证据。
            if (com.kidlink.beacon.BuildConfig.DEBUG && mac != null) {
                StringBuilder fp = new StringBuilder();
                for (int i = 0; i < Math.min(12, scanData.length); i++) {
                    fp.append(String.format("%02X", scanData[i] & 0xFF));
                }
                // 把「legacy 还是扩展、哪个 PHY、哪个 advertising set」也揉进指纹，
                // 否则同一颗信标的两套广播会被算成同一种，白白掩盖掉线索。
                fp.append('|').append(result.isLegacy() ? "L" : "E");
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    fp.append("ph").append(result.getPrimaryPhy())
                      .append("sid").append(result.getAdvertisingSid())
                      .append("tx").append(result.getTxPower());
                }
                java.util.Map<String, Integer> m = sigStats.get(mac);
                if (m == null) {
                    m = new java.util.concurrent.ConcurrentHashMap<>();
                    sigStats.put(mac, m);
                }
                m.merge(fp.toString(), 1, Integer::sum);
                if (WATCH_MAC.equalsIgnoreCase(mac) && tlm == null && beacon == null) {
                    // 认不出来的帧，头两包直接把原文打出来
                    Log.d(TAG, "UNKNOWN " + mac + " raw=" + toHex(scanData));
                }
            }

            if (com.kidlink.beacon.BuildConfig.DEBUG && mac != null) {
                final String frameTag;
                if (beacon != null && tlm != null) frameTag = "IB+TLM";
                else if (beacon != null) frameTag = "IB";
                else if (tlm != null) frameTag = "TLM";
                else frameTag = "?";
                // 已知是 iBeacon / TLM 的，随时更新；未知的只记第一次，免得覆盖掉之前的判断
                if (!"?".equals(frameTag) || !frameTags.containsKey(mac)) {
                    frameTags.put(mac, frameTag);
                }
            }

            if (beacon == null) {
                // 解不出 iBeacon 的包：按「前 12 字节」去重记一次原始数据。
                // 标准 iBeacon 的点位不会走到这里，所以这几行就是「别的帧」的全部种类。
                if (com.kidlink.beacon.BuildConfig.DEBUG && mac != null && sigLogBudget > 0) {
                    StringBuilder sig = new StringBuilder();
                    for (int i = 0; i < Math.min(12, scanData.length); i++) {
                        sig.append(String.format("%02X", scanData[i] & 0xFF));
                    }
                    java.util.Set<String> set = rawSignatures.get(mac);
                    if (set == null) {
                        set = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
                        rawSignatures.put(mac, set);
                    }
                    if (set.add(sig.toString())) {
                        sigLogBudget--;
                        Log.d(TAG, "non-IB " + mac + " sig=" + sig + " len=" + scanData.length
                                + " raw=" + toHex(scanData));
                    }
                }
                return;
            }

            // 多目标匹配（首页同时看两个信标）
            if (!targetKeys.isEmpty()
                    && !targetKeys.contains(keyOf(beacon.uuid, beacon.major, beacon.minor))) return;

            // 过滤匹配
            if (filterUuid != null && !IBeaconParser.uuidMatch(beacon.uuid, filterUuid)) return;
            if (filterMajor >= 0 && beacon.major != filterMajor) return;
            if (filterMinor >= 0 && beacon.minor != filterMinor) return;

            // 滑动平均滤波
            int filteredRssi = filterRssi(rssi);

            if (listener != null) {
                mainHandler.post(() -> {
                    if (listener != null) {
                        listener.onRawBeaconData(beacon, result);
                    }
                });
            }
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            if (results == null) return;
            for (ScanResult result : results) {
                onScanResult(0, result);
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            Log.e(TAG, "Scan failed, error code: " + errorCode);
            String msg;
            switch (errorCode) {
                case 1: // SCAN_FAILED_ALREADY_STARTED
                    msg = "扫描已在运行";
                    break;
                case 2: // SCAN_FAILED_APP_REGISTRATION_FAILED
                    msg = "应用注册失败";
                    break;
                case 3: // SCAN_FAILED_FEATURE_UNSUPPORTED
                    msg = "设备不支持BLE扫描";
                    break;
                case 4: // SCAN_FAILED_INTERNAL_ERROR
                    msg = "蓝牙内部错误";
                    break;
                default:
                    msg = "扫描失败，错误码: " + errorCode;
            }
            isScanning = false;
            if (listener != null) listener.onScanError(errorCode, msg);
        }
    }
}
