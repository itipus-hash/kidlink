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

    private boolean isScanning = false;
    private ScanCallbackImpl scanCallback;

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

    // 心跳：每 5 秒打一行「还活着 + 收了多少包」，用来验证后台扫描有没有被系统掐掉
    private long lastHeartbeatAt = 0;
    private int heartbeatCount = 0;

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
        synchronized (seenMacs) {
            seenMacs.clear();
        }

        // 扫描设置 - 低延迟模式，适配后台扫描
        //
        // 注意：这里故意不传 ScanFilter（传 null 扫全部广播）。
        // 原因：部分 ROM（华为/部分 MTK）对 setManufacturerData 的过滤实现不一致，
        // 会导致 iBeacon 明明在广播却一条都收不到；硬件层过滤省的那点电，
        // 不值得换来"扫不到"。所有过滤都在 onScanResult 里用软件完成。
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setReportDelay(0) // 立即回调
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
        return true;
    }

    /**
     * 停止扫描
     */
    @SuppressLint("MissingPermission")
    public void stopScan() {
        if (!isScanning) return;
        isScanning = false;

        if (bleScanner != null && scanCallback != null) {
            try {
                bleScanner.stopScan(scanCallback);
            } catch (Exception e) {
                Log.e(TAG, "Failed to stop scan", e);
            }
        }
        scanCallback = null;
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

            // 解析 iBeacon
            IBeaconData beacon = IBeaconParser.parse(scanData, rssi);
            if (beacon == null) return;

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
