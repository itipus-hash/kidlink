package com.kidlink.beacon.ui;

import android.os.Bundle;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.textfield.TextInputEditText;
import com.kidlink.beacon.ui.widget.Feedback;
import com.kidlink.beacon.R;
import com.kidlink.beacon.data.BeaconDbHelper;
import com.kidlink.beacon.data.BeaconEntity;

public class EditBeaconActivity extends AppCompatActivity {

    private BeaconDbHelper dbHelper;
    private BeaconEntity beacon; // null = 新增模式

    private TextInputEditText editName, editRemark, editUuid, editMajor, editMinor, editRssi1m, editThreshold;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_edit_beacon);

        dbHelper = new BeaconDbHelper(this);

        long beaconId = getIntent().getLongExtra("beacon_id", -1);
        if (beaconId > 0) {
            beacon = dbHelper.getBeaconById(beaconId);
        }

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle(beacon != null ? R.string.edit_title : R.string.manual_add);
        toolbar.setNavigationOnClickListener(v -> finish());
        toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.action_save) {
                saveBeacon();
                return true;
            }
            return false;
        });

        editName = findViewById(R.id.edit_name);
        editRemark = findViewById(R.id.edit_remark);
        editUuid = findViewById(R.id.edit_uuid);
        editMajor = findViewById(R.id.edit_major);
        editMinor = findViewById(R.id.edit_minor);
        editRssi1m = findViewById(R.id.edit_rssi_1m);
        editThreshold = findViewById(R.id.edit_threshold);

        if (beacon != null) {
            // 编辑模式 - 填充现有数据
            editName.setText(beacon.getName());
            editRemark.setText(beacon.getRemark());
            editUuid.setText(beacon.getUuidFormatted());
            editMajor.setText(String.valueOf(beacon.getMajor()));
            editMinor.setText(String.valueOf(beacon.getMinor()));
            editRssi1m.setText(String.valueOf(beacon.getRssiAt1m()));
            editThreshold.setText(String.valueOf(beacon.getThresholdRssi()));
        }
    }

    private void saveBeacon() {
        String name = getText(editName);
        String remark = getText(editRemark);
        String uuid = getText(editUuid);
        String majorStr = getText(editMajor);
        String minorStr = getText(editMinor);
        String rssi1mStr = getText(editRssi1m);
        String thresholdStr = getText(editThreshold);

        // 校验
        if (name.isEmpty()) {
            Feedback.show(this, "请填写标签名称");
            return;
        }
        if (uuid.isEmpty()) {
            Feedback.show(this, "请填写 UUID");
            return;
        }

        // 校验 UUID 格式（去横线后 32 位 hex）
        String uuidRaw = uuid.replace("-", "");
        if (uuidRaw.length() != 32 || !uuidRaw.matches("[0-9a-fA-F]+")) {
            Feedback.show(this, "UUID 格式不正确，应为 32 位十六进制");
            return;
        }

        int major, minor;
        try {
            major = Integer.parseInt(majorStr);
            minor = Integer.parseInt(minorStr);
            if (major < 0 || major > 65535 || minor < 0 || minor > 65535) {
                throw new NumberFormatException();
            }
        } catch (NumberFormatException e) {
            Feedback.show(this, "Major/Minor 应为 0-65535 的整数");
            return;
        }

        int rssi1m, threshold;
        try {
            rssi1m = rssi1mStr.isEmpty() ? BeaconEntity.DEFAULT_RSSI_1M : Integer.parseInt(rssi1mStr);
            threshold = thresholdStr.isEmpty() ? BeaconEntity.DEFAULT_THRESHOLD : Integer.parseInt(thresholdStr);
            if (rssi1m < -127 || rssi1m > 0) {
                Feedback.show(this, "RSSI@1m 应在 -127 ~ 0 dBm");
                return;
            }
            if (threshold < -127 || threshold > 0) {
                Feedback.show(this, "阈值应在 -127 ~ 0 dBm");
                return;
            }
        } catch (NumberFormatException e) {
            Feedback.show(this, "RSSI/阈值格式不正确");
            return;
        }

        if (beacon == null) {
            // 新增
            beacon = new BeaconEntity();
        }
        beacon.setName(name);
        beacon.setRemark(remark);
        beacon.setUuid(uuidRaw);
        beacon.setMajor(major);
        beacon.setMinor(minor);
        beacon.setRssiAt1m(rssi1m);
        beacon.setThresholdRssi(threshold);

        if (beacon.getId() > 0) {
            dbHelper.updateBeacon(beacon);
        } else {
            dbHelper.insertBeacon(beacon);
        }

        setResult(RESULT_OK);
        finish();
    }

    private String getText(TextInputEditText edit) {
        return edit.getText() != null ? edit.getText().toString().trim() : "";
    }
}
