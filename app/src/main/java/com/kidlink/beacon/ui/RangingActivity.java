package com.kidlink.beacon.ui;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.le.ScanResult;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.kidlink.beacon.ui.widget.Feedback;
import com.kidlink.beacon.R;
import com.kidlink.beacon.ble.BeaconScanner;
import com.kidlink.beacon.ble.IBeaconData;
import com.kidlink.beacon.data.BeaconDbHelper;
import com.kidlink.beacon.data.BeaconEntity;

public class RangingActivity extends AppCompatActivity {

    private BeaconDbHelper dbHelper;
    private BeaconEntity beacon;
    private BeaconScanner scanner;

    private TextView txtDistance, txtRawRssi, txtFilteredRssi, txtTxPower;
    private TextView txtAlert, txtRssi1m, txtThreshold;
    private SeekBar seekThreshold;
    private Button btnScan, btnCalibrate;

    private int currentThreshold;
    private int lastFilteredRssi = -200;
    private boolean alertActive = false;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // 用于校准的临时最新原始 RSSI
    private int latestRawRssiForCalibration = -200;
    private int calibrationSampleCount = 0;
    private int calibrationSampleSum = 0;

    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                Boolean scanGranted = result.get(Manifest.permission.BLUETOOTH_SCAN);
                Boolean connectGranted = result.get(Manifest.permission.BLUETOOTH_CONNECT);
                Boolean locationGranted = result.get(Manifest.permission.ACCESS_FINE_LOCATION);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (scanGranted != null && scanGranted && connectGranted != null && connectGranted) {
                        tryStartScan();
                    }
                } else {
                    if (locationGranted != null && locationGranted) {
                        tryStartScan();
                    }
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ranging);

        dbHelper = new BeaconDbHelper(this);

        long beaconId = getIntent().getLongExtra("beacon_id", -1);
        beacon = dbHelper.getBeaconById(beaconId);
        if (beacon == null) {
            finish();
            return;
        }

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle(beacon.getName());
        toolbar.setNavigationOnClickListener(v -> finish());

        txtDistance = findViewById(R.id.txt_distance);
        txtRawRssi = findViewById(R.id.txt_raw_rssi);
        txtFilteredRssi = findViewById(R.id.txt_filtered_rssi);
        txtTxPower = findViewById(R.id.txt_tx_power);
        txtAlert = findViewById(R.id.txt_alert);
        txtRssi1m = findViewById(R.id.txt_rssi_1m);
        txtThreshold = findViewById(R.id.txt_threshold);
        seekThreshold = findViewById(R.id.seek_threshold);
        btnScan = findViewById(R.id.btn_scan);
        btnCalibrate = findViewById(R.id.btn_calibrate);

        currentThreshold = beacon.getThresholdRssi();
        updateThresholdDisplay();
        txtRssi1m.setText("RSSI@1m: " + beacon.getRssiAt1m() + " dBm");

        // SeekBar 映射：progress 0-40 对应 RSSI -100 ~ -60
        // progress = 100 + rssi (即 rssi = progress - 100)
        // rssi=-100 → progress=0, rssi=-60 → progress=40
        seekThreshold.setMax(40);
        seekThreshold.setProgress(100 + currentThreshold);
        seekThreshold.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                currentThreshold = progress - 100;
                updateThresholdDisplay();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                // 保存阈值到数据库
                beacon.setThresholdRssi(currentThreshold);
                dbHelper.updateBeacon(beacon);
            }
        });

        btnScan.setOnClickListener(v -> {
            if (scanner != null && scanner.isScanning()) {
                stopScanning();
            } else {
                startScanningWithPermissionCheck();
            }
        });

        btnCalibrate.setOnClickListener(v -> doCalibration());

        scanner = new BeaconScanner(this);
        scanner.setListener(new BeaconScanner.OnBeaconScanListener() {
            @Override
            public void onBeaconFound(IBeaconData beaconData, int filteredRssi, double distance) {
                // unused - handled in onRawBeaconData
            }

            @Override
            public void onRawBeaconData(IBeaconData beaconData, ScanResult scanResult) {
                latestRawRssiForCalibration = beaconData.rssi;

                // 使用标签的 RSSI@1m 基准值计算距离
                double distance = beaconData.estimateDistance(beacon.getRssiAt1m());

                // 更新 UI
                mainHandler.post(() -> {
                    txtRawRssi.setText(beaconData.rssi + " dBm");

                    // 滤波 RSSI
                    int filtered = computeFilteredRssi(beaconData.rssi);
                    lastFilteredRssi = filtered;
                    txtFilteredRssi.setText(filtered + " dBm");

                    txtTxPower.setText(beaconData.txPower + " dBm");
                    txtDistance.setText(String.format("%.2f m", distance));

                    // 阈值判断
                    checkThreshold(filtered);
                });
            }

            @Override
            public void onScanError(int errorCode, String message) {
                mainHandler.post(() -> {
                    Feedback.show(RangingActivity.this, message);
                    if (scanner != null && !scanner.isScanning()) {
                        btnScan.setText(R.string.start_scan);
                    }
                });
            }
        });
    }

    private void updateThresholdDisplay() {
        txtThreshold.setText(currentThreshold + " dBm");
    }

    // RSSI 滑动平均（与 scanner 内部独立的一份，用于 UI 显示）
    private final java.util.LinkedList<Integer> uiRssiBuffer = new java.util.LinkedList<>();
    private static final int UI_RSSI_WINDOW = 5;

    private int computeFilteredRssi(int rssi) {
        synchronized (uiRssiBuffer) {
            uiRssiBuffer.addLast(rssi);
            while (uiRssiBuffer.size() > UI_RSSI_WINDOW) {
                uiRssiBuffer.removeFirst();
            }
            int sum = 0;
            for (int r : uiRssiBuffer) sum += r;
            return sum / uiRssiBuffer.size();
        }
    }

    private void checkThreshold(int filteredRssi) {
        // 信号强于阈值（RSSI 值更大 = 距离更近）
        if (filteredRssi >= currentThreshold) {
            if (!alertActive) {
                alertActive = true;
                txtAlert.setVisibility(android.view.View.VISIBLE);
                txtAlert.setText(R.string.within_threshold);
                triggerVibration();
            }
        } else {
            if (alertActive) {
                alertActive = false;
                txtAlert.setVisibility(android.view.View.GONE);
            }
        }
    }

    private void triggerVibration() {
        android.os.Vibrator vibrator = (android.os.Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (vibrator == null || !vibrator.hasVibrator()) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            long[] pattern = {0, 500, 200, 500};
            VibrationEffect effect = VibrationEffect.createWaveform(pattern, -1);
            vibrator.vibrate(effect);
        } else {
            vibrator.vibrate(new long[]{0, 500, 200, 500}, -1);
        }
    }

    private void startScanningWithPermissionCheck() {
        if (scanner.hasScanPermission()) {
            tryStartScan();
        } else {
            // 请求权限
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                permissionLauncher.launch(new String[]{
                        Manifest.permission.BLUETOOTH_SCAN,
                        Manifest.permission.BLUETOOTH_CONNECT,
                        Manifest.permission.ACCESS_FINE_LOCATION
                });
            } else {
                permissionLauncher.launch(new String[]{Manifest.permission.ACCESS_FINE_LOCATION});
            }
        }
    }

    private void tryStartScan() {
        if (!scanner.isBluetoothReady()) {
            Feedback.show(this, R.string.bluetooth_off);
            return;
        }

        uiRssiBuffer.clear();
        scanner.resetRssiFilter();
        scanner.setFilter(beacon.getUuidFormatted(), beacon.getMajor(), beacon.getMinor());
        if (scanner.startScan()) {
            btnScan.setText(R.string.stop_scan);
        }
    }

    private void stopScanning() {
        scanner.stopScan();
        btnScan.setText(R.string.start_scan);
        txtAlert.setVisibility(android.view.View.GONE);
        alertActive = false;
    }

    /**
     * 1 米距离校准
     * 采集当前 RSSI（取 3 次平均值），更新标签的 RSSI@1m 基准值
     */
    private void doCalibration() {
        if (scanner == null || !scanner.isScanning()) {
            Feedback.show(this, "请先开始扫描");
            return;
        }

        calibrationSampleCount = 0;
        calibrationSampleSum = 0;

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.calibrate_1m)
                .setMessage("正在采集 RSSI 样本…\n请确保站在距离信标约 1 米处")
                .setCancelable(false)
                .create();
        dialog.show();

        // 采样 3 次，每次间隔 500ms
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (latestRawRssiForCalibration > -100) {
                    calibrationSampleCount++;
                    calibrationSampleSum += latestRawRssiForCalibration;
                }

                if (calibrationSampleCount < 3) {
                    mainHandler.postDelayed(this, 500);
                } else {
                    int avgRssi = calibrationSampleSum / calibrationSampleCount;
                    beacon.setRssiAt1m(avgRssi);
                    dbHelper.updateBeacon(beacon);
                    txtRssi1m.setText("RSSI@1m: " + avgRssi + " dBm");
                    dialog.dismiss();
                    Feedback.show(RangingActivity.this, getString(R.string.calibrate_success, avgRssi), true);
                }
            }
        }, 500);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (scanner != null) {
            scanner.stopScan();
        }
    }
}
