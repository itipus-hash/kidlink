package com.kidlink.beacon.ui;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.kidlink.beacon.ui.widget.Feedback;
import com.kidlink.beacon.R;
import com.kidlink.beacon.data.BeaconDbHelper;
import com.kidlink.beacon.data.BeaconEntity;

public class BeaconDetailActivity extends AppCompatActivity {

    private BeaconDbHelper dbHelper;
    private BeaconEntity beacon;

    private final ActivityResultLauncher<Intent> editLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK) {
                    // 刷新数据
                    long id = beacon.getId();
                    beacon = dbHelper.getBeaconById(id);
                    if (beacon != null) {
                        updateDisplay();
                    }
                    setResult(RESULT_OK);
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_beacon_detail);

        dbHelper = new BeaconDbHelper(this);

        long beaconId = getIntent().getLongExtra("beacon_id", -1);
        beacon = dbHelper.getBeaconById(beaconId);
        if (beacon == null) {
            finish();
            return;
        }

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle(R.string.detail_title);
        toolbar.setNavigationOnClickListener(v -> finish());
        toolbar.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.action_share_qr) {
                goToQRShare();
                return true;
            } else if (id == R.id.action_edit) {
                goToEdit();
                return true;
            } else if (id == R.id.action_delete) {
                confirmDelete();
                return true;
            }
            return false;
        });

        Button btnShareQr = findViewById(R.id.btn_share_qr);
        Button btnEdit = findViewById(R.id.btn_edit);
        Button btnDelete = findViewById(R.id.btn_delete);

        btnShareQr.setOnClickListener(v -> goToQRShare());
        btnEdit.setOnClickListener(v -> goToEdit());
        btnDelete.setOnClickListener(v -> confirmDelete());

        updateDisplay();
    }

    private void updateDisplay() {
        TextView txtName = findViewById(R.id.txt_name);
        TextView txtRemark = findViewById(R.id.txt_remark);
        TextView txtUuid = findViewById(R.id.txt_uuid);
        TextView txtMajorMinor = findViewById(R.id.txt_major_minor);
        TextView txtRssi1m = findViewById(R.id.txt_rssi_1m);
        TextView txtThreshold = findViewById(R.id.txt_threshold);

        txtName.setText(beacon.getName());
        txtRemark.setText(beacon.getRemark() != null && !beacon.getRemark().isEmpty() ?
                beacon.getRemark() : "无备注");
        txtUuid.setText("UUID: " + beacon.getUuidFormatted());
        txtMajorMinor.setText("Major: " + beacon.getMajor() + " | Minor: " + beacon.getMinor());
        txtRssi1m.setText("RSSI@1m: " + beacon.getRssiAt1m() + " dBm");
        txtThreshold.setText("触发阈值: " + beacon.getThresholdRssi() + " dBm");
    }

    private void goToQRShare() {
        Intent intent = new Intent(this, QRShareActivity.class);
        intent.putExtra("beacon_id", beacon.getId());
        startActivity(intent);
    }

    private void goToEdit() {
        Intent intent = new Intent(this, EditBeaconActivity.class);
        intent.putExtra("beacon_id", beacon.getId());
        editLauncher.launch(intent);
    }

    private void confirmDelete() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.delete_beacon)
                .setMessage(R.string.confirm_delete)
                .setPositiveButton(R.string.ok, (dialog, which) -> {
                    dbHelper.deleteBeacon(beacon.getId());
                    setResult(RESULT_OK);
                    finish();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }
}
