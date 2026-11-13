package com.kidlink.beacon.ui;

import android.bluetooth.le.ScanResult;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.kidlink.beacon.ui.widget.Feedback;
import com.kidlink.beacon.R;
import com.kidlink.beacon.ble.BeaconScanner;
import com.kidlink.beacon.ble.IBeaconData;
import com.kidlink.beacon.data.BeaconDbHelper;
import com.kidlink.beacon.data.BeaconEntity;

public class RawBeaconActivity extends AppCompatActivity {

    private BeaconDbHelper dbHelper;
    private BeaconEntity beacon;
    private BeaconScanner scanner;
    private TextView txtUuid, txtMajor, txtMinor, txtTxPower, txtRssi, txtRawHex, txtScanning;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_raw_beacon);

        dbHelper = new BeaconDbHelper(this);

        long beaconId = getIntent().getLongExtra("beacon_id", -1);
        beacon = dbHelper.getBeaconById(beaconId);
        if (beacon == null) {
            finish();
            return;
        }

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle(beacon.getName() + " - 原始广播");
        toolbar.setNavigationOnClickListener(v -> finish());

        txtUuid = findViewById(R.id.txt_uuid);
        txtMajor = findViewById(R.id.txt_major);
        txtMinor = findViewById(R.id.txt_minor);
        txtTxPower = findViewById(R.id.txt_tx_power);
        txtRssi = findViewById(R.id.txt_rssi);
        txtRawHex = findViewById(R.id.txt_raw_hex);
        txtScanning = findViewById(R.id.txt_scanning);

        txtUuid.setText("UUID: " + beacon.getUuidFormatted());
        txtMajor.setText("Major: " + beacon.getMajor());
        txtMinor.setText("Minor: " + beacon.getMinor());
        txtTxPower.setText("Tx Power: 等待扫描…");
        txtRssi.setText("RSSI: 等待扫描…");
        txtRawHex.setText("等待广播数据…");

        scanner = new BeaconScanner(this);
        scanner.setListener(new BeaconScanner.OnBeaconScanListener() {
            @Override
            public void onBeaconFound(IBeaconData beaconData, int filteredRssi, double distance) {}

            @Override
            public void onRawBeaconData(IBeaconData beaconData, ScanResult scanResult) {
                mainHandler.post(() -> {
                    txtScanning.setVisibility(android.view.View.GONE);
                    txtUuid.setText("UUID: " + beaconData.uuid);
                    txtMajor.setText("Major: " + beaconData.major);
                    txtMinor.setText("Minor: " + beaconData.minor);
                    txtTxPower.setText("Tx Power: " + beaconData.txPower + " dBm");
                    txtRssi.setText("RSSI: " + beaconData.rssi + " dBm");

                    // 原始广播 Hex
                    byte[] rawBytes = scanResult.getScanRecord() != null ?
                            scanResult.getScanRecord().getBytes() : null;
                    if (rawBytes != null) {
                        StringBuilder hex = new StringBuilder();
                        for (int i = 0; i < rawBytes.length; i++) {
                            hex.append(String.format("%02X", rawBytes[i] & 0xFF));
                            if (i > 0 && i % 16 == 15) hex.append("\n");
                            else if (i % 2 == 1) hex.append(" ");
                        }
                        txtRawHex.setText(hex.toString());
                    }
                });
            }

            @Override
            public void onScanError(int errorCode, String message) {
                mainHandler.post(() -> Feedback.show(RawBeaconActivity.this, message));
            }
        });

        startScan();
    }

    private void startScan() {
        if (!scanner.isBluetoothReady()) {
            Feedback.show(this, R.string.bluetooth_off);
            return;
        }
        if (!scanner.hasScanPermission()) {
            Feedback.show(this, R.string.perm_bluetooth);
            return;
        }
        scanner.setFilter(beacon.getUuidFormatted(), beacon.getMajor(), beacon.getMinor());
        scanner.startScan();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (scanner != null) {
            scanner.stopScan();
        }
    }
}
