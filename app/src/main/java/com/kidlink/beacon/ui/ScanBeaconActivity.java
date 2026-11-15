package com.kidlink.beacon.ui;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.le.ScanResult;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.kidlink.beacon.ui.widget.Feedback;
import com.kidlink.beacon.R;
import com.kidlink.beacon.ble.BeaconScanner;
import com.kidlink.beacon.ble.IBeaconData;
import com.kidlink.beacon.data.BeaconDbHelper;
import com.kidlink.beacon.data.BeaconEntity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 蓝牙扫描页：扫描附近的 iBeacon，点击即保存为已存信标
 */
public class ScanBeaconActivity extends AppCompatActivity {

    private BeaconDbHelper dbHelper;
    private BeaconScanner scanner;
    private ScanAdapter adapter;
    private ProgressBar progress;
    private TextView txtState;
    private TextView txtEmpty;
    private RecyclerView recyclerView;

    private final Map<String, ScanItem> found = new LinkedHashMap<>();
    /** 扫描期间见到的所有 BLE 设备（含原始 hex），用于诊断"为什么扫不到" */
    private final Map<String, RawAdv> rawDevices = new LinkedHashMap<>();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable stateRefresh = this::refreshStateText;

    private void scheduleStateRefresh() {
        handler.removeCallbacks(stateRefresh);
        handler.postDelayed(stateRefresh, 250);
    }

    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(),
                    result -> {
                        if (hasScanPermission()) startScan();
                        else Feedback.show(this, R.string.perm_bluetooth);
                    });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_scan_beacon);

        dbHelper = new BeaconDbHelper(this);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        toolbar.inflateMenu(R.menu.menu_scan);
        toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.action_raw_dump) {
                showRawDump();
                return true;
            }
            return false;
        });

        progress = findViewById(R.id.progress);
        txtState = findViewById(R.id.txt_scan_state);
        txtEmpty = findViewById(R.id.txt_empty);
        recyclerView = findViewById(R.id.recycler_scan);

        adapter = new ScanAdapter();
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);

        scanner = new BeaconScanner(this);
        scanner.setListener(new BeaconScanner.OnBeaconScanListener() {
            @Override
            public void onBeaconFound(IBeaconData beacon, int filteredRssi, double distance) {}

            @Override
            public void onRawBeaconData(IBeaconData beacon, ScanResult scanResult) {
                handleFound(beacon, scanResult);
            }

            @Override
            public void onScanError(int errorCode, String message) {
                handler.post(() -> {
                    progress.setVisibility(View.GONE);
                    txtState.setText(message);
                });
            }

            @Override
            public void onBleDeviceSeen(ScanResult scanResult) {
                if (scanResult == null || scanResult.getDevice() == null) return;
                String mac = scanResult.getDevice().getAddress();
                if (mac == null) return;

                RawAdv adv = new RawAdv();
                adv.mac = mac;
                adv.rssi = scanResult.getRssi();
                try {
                    if (scanResult.getScanRecord() != null) {
                        adv.name = scanResult.getScanRecord().getDeviceName();
                        adv.raw = scanResult.getScanRecord().getBytes();
                        adv.hex = BeaconScanner.toHex(adv.raw);
                    }
                    if (adv.name == null || adv.name.trim().isEmpty()) {
                        adv.name = scanResult.getDevice().getName();
                    }
                } catch (SecurityException ignored) {
                }
                boolean isNew = !rawDevices.containsKey(mac);
                rawDevices.put(mac, adv);
                if (isNew) scheduleStateRefresh();
            }
        });

        if (hasScanPermission()) {
            startScan();
        } else {
            permissionLauncher.launch(requiredPermissions());
        }
    }

    private String[] requiredPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return new String[]{Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.ACCESS_FINE_LOCATION};
        }
        return new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
    }

    private boolean hasScanPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                    == PackageManager.PERMISSION_GRANTED
                    && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void startScan() {
        if (!scanner.isBluetoothReady()) {
            progress.setVisibility(View.GONE);
            txtState.setText(R.string.bluetooth_need_on);
            Feedback.show(this, R.string.bluetooth_need_on);
            return;
        }
        found.clear();
        rawDevices.clear();
        adapter.setItems(new ArrayList<>());
        refreshStateText();
        scanner.clearFilter();
        scanner.resetRssiFilter();
        if (scanner.startScan()) {
            progress.setVisibility(View.VISIBLE);
            txtState.setText(R.string.scanning);
        }
    }

    private void handleFound(IBeaconData data, ScanResult result) {
        String mac = null;
        String deviceName = null;
        // 优先用广播包里的设备名（AD type 0x08/0x09），取不到再退回系统缓存的 GATT 名。
        // 很多 iBeacon 的名字只出现在 scan response 里，只读 getDevice().getName() 会拿到 null。
        try {
            if (result != null && result.getScanRecord() != null) {
                deviceName = result.getScanRecord().getDeviceName();
            }
            if (result != null && result.getDevice() != null) {
                mac = result.getDevice().getAddress();
                if (deviceName == null || deviceName.trim().isEmpty()) {
                    deviceName = result.getDevice().getName();
                }
            }
        } catch (SecurityException ignored) {
        }

        String cleanMac = mac == null ? "" : mac.trim().toUpperCase();
        // 同一个 UUID/Major/Minor 可能是两台不同的信标（同品牌同型号出厂三件套一样），
        // 所以列表的 key 里带上 MAC，才不会把两台合成一条
        String key = data.uuid + "|" + data.major + "|" + data.minor + "|" + cleanMac;

        ScanItem item = found.get(key);
        if (item == null) {
            item = new ScanItem(data.uuid, data.major, data.minor, data.txPower, cleanMac);
            found.put(key, item);
        }
        item.rssi = data.rssi;
        if (deviceName != null && !deviceName.trim().isEmpty()) {
            item.name = deviceName.trim();
        }

        handler.post(this::publishList);
    }

    private void publishList() {
        List<ScanItem> list = new ArrayList<>(found.values());
        Collections.sort(list, (a, b) -> Integer.compare(b.rssi, a.rssi));
        adapter.setItems(list);
        refreshStateText();
    }

    /**
     * 刷新顶部状态文案。
     * 收到广播但一个 iBeacon 都没解出来时，换成"没有发现信标"的提示，
     * 这样能区分「蓝牙没工作」和「信标不是 iBeacon 格式」。
     */
    private void refreshStateText() {
        int total = rawDevices.size();
        int hits = adapter.getItemCount();

        if (total > 0) {
            txtState.setText(getString(R.string.scan_state_with_count, total));
        } else {
            txtState.setText(R.string.scanning);
        }

        boolean empty = hits == 0;
        txtEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        txtEmpty.setText(total > 0 ? R.string.scan_beacon_none_found : R.string.scan_beacon_empty);
        progress.setVisibility(View.VISIBLE);
    }

    private void saveBeacon(ScanItem item) {
        if (dbHelper.isDuplicate(item.uuid, item.major, item.minor, item.mac)) {
            Feedback.show(this, R.string.scan_beacon_duplicate);
            return;
        }

        BeaconEntity beacon = new BeaconEntity();
        String name = item.name;
        if (name == null || name.trim().isEmpty()) {
            // 第一颗信标默认就叫「书包」—— 绝大多数人第一颗就是放书包里的，
            // 省得先面对一串「未命名信标 5/6」再去改名；后面几颗还是按 major/minor 区分
            name = dbHelper.getAllBeacons().isEmpty()
                    ? getString(R.string.beacon_default_name)
                    : getString(R.string.scan_beacon_unknown, item.major, item.minor);
        }
        beacon.setName(name);
        beacon.setRemark("");
        beacon.setUuid(item.uuid);
        beacon.setMajor(item.major);
        beacon.setMinor(item.minor);
        beacon.setMac(item.mac);
        beacon.setRssiAt1m(item.txPower != 0 ? item.txPower : BeaconEntity.DEFAULT_RSSI_1M);

        long id = dbHelper.insertBeacon(beacon);
        if (id > 0) {
            // 归属交给调用方决定：管理页会把它认领到点「+」的那一组，
            // 首页则走 ensureChildren() 兜底。这里别抢着分配。
            Feedback.show(this, getString(R.string.scan_beacon_saved, name));
            setResult(RESULT_OK);
            finish();
        } else {
            Feedback.show(this, R.string.import_fail);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (scanner != null) scanner.stopScan();
    }

    // === 数据 & 列表 ===

    /** 一台 BLE 设备的原始广播，仅用于诊断 */
    private static class RawAdv {
        String mac;
        String name;
        int rssi;
        String hex;
        byte[] raw;
    }

    /**
     * 把所有收到的原始广播按信号强弱列出来，可一键复制。
     * 扫不到信标时用它看清对方到底在发什么字节，比猜格式靠谱。
     */
    private void showRawDump() {
        if (rawDevices.isEmpty()) {
            Feedback.show(this, R.string.raw_dump_empty);
            return;
        }

        List<RawAdv> list = new ArrayList<>(rawDevices.values());
        Collections.sort(list, (a, b) -> Integer.compare(b.rssi, a.rssi));

        StringBuilder sb = new StringBuilder();
        sb.append(getString(R.string.raw_dump_hint, list.size())).append("\n\n");
        for (RawAdv a : list) {
            sb.append(a.name != null && !a.name.trim().isEmpty() ? a.name.trim() : "(无名)");
            sb.append("  ").append(a.mac).append("  ").append(a.rssi).append("dBm\n");
            sb.append(a.hex == null || a.hex.isEmpty() ? "(空)" : a.hex).append("\n");
            // 每条后面直接给结论：收到包但没解出来 = 设备格式问题；连包都没收到 = 手机侧问题
            sb.append("    → ").append(com.kidlink.beacon.ble.IBeaconParser.diagnose(a.raw))
                    .append("\n\n");
        }
        final String dump = sb.toString();

        float density = getResources().getDisplayMetrics().density;
        int pad = (int) (16 * density);

        TextView body = new TextView(this);
        body.setPadding(pad, pad, pad, pad);
        body.setTextSize(11f);
        body.setTypeface(android.graphics.Typeface.MONOSPACE);
        body.setTextIsSelectable(true);
        body.setText(dump);

        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(body);

        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.raw_dump_title)
                .setView(scroll)
                .setPositiveButton(R.string.raw_dump_copy, (d, w) -> {
                    android.content.ClipboardManager cm =
                            (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("ble", dump));
                    }
                    Feedback.show(this, R.string.raw_dump_copied);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static class ScanItem {
        final String uuid;
        final int major;
        final int minor;
        final int txPower;
        final String mac;
        int rssi;
        String name;

        ScanItem(String uuid, int major, int minor, int txPower, String mac) {
            this.uuid = uuid;
            this.major = major;
            this.minor = minor;
            this.txPower = txPower;
            this.mac = mac == null ? "" : mac;
        }
    }

    private class ScanAdapter extends RecyclerView.Adapter<ScanAdapter.VH> {
        private final List<ScanItem> data = new ArrayList<>();

        void setItems(List<ScanItem> list) {
            data.clear();
            data.addAll(list);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_scan_beacon, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            holder.bind(data.get(position));
        }

        @Override
        public int getItemCount() {
            return data.size();
        }

        class VH extends RecyclerView.ViewHolder {
            final TextView txtName;
            final TextView txtDetail;
            final TextView txtRssi;

            VH(@NonNull View itemView) {
                super(itemView);
                txtName = itemView.findViewById(R.id.txt_name);
                txtDetail = itemView.findViewById(R.id.txt_detail);
                txtRssi = itemView.findViewById(R.id.txt_rssi);
            }

            @SuppressLint("SetTextI18n")
            void bind(ScanItem item) {
                txtName.setText(item.name != null && !item.name.isEmpty()
                        ? item.name
                        : getString(R.string.scan_beacon_unknown, item.major, item.minor));

                String uuid = item.uuid.length() == 32
                        ? item.uuid.substring(0, 8) + "…" + item.uuid.substring(28)
                        : item.uuid;
                String detail = "UUID " + uuid + "  ·  " + item.major + " / " + item.minor;
                // MAC 是唯一能把「同品牌、三件套一样」的两台信标分开的东西，直接摊开给用户看
                if (item.mac != null && !item.mac.isEmpty()) {
                    detail = detail + "\n" + item.mac;
                }
                txtDetail.setText(detail);
                txtRssi.setText(item.rssi + " dBm");
                itemView.setOnClickListener(v -> saveBeacon(item));
            }
        }
    }
}
