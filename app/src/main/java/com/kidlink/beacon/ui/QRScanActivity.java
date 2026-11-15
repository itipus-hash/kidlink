package com.kidlink.beacon.ui;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.Button;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScannerOptions;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;
import com.kidlink.beacon.ui.widget.Feedback;
import com.kidlink.beacon.R;
import com.kidlink.beacon.data.BeaconDbHelper;
import com.kidlink.beacon.data.BeaconEntity;
import com.kidlink.beacon.qr.BeaconQrCodec;
import com.kidlink.beacon.qr.SettingsQrCodec;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class QRScanActivity extends AppCompatActivity {

    private PreviewView previewView;
    private Button btnFlash;
    private Camera camera;
    private boolean flashOn = false;

    private BarcodeScanner barcodeScanner;
    private ExecutorService cameraExecutor;
    private boolean scanned = false;
    /** 扫的是「接收配置」码，不是信标码 */
    private boolean settingsMode = false;

    private static final int CAMERA_PERM_CODE = 100;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_qr_scan);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        boolean storeMode = getIntent().getLongExtra("store_for_beacon_id", -1) > 0;
        settingsMode = getIntent().getBooleanExtra("scan_settings", false);
        if (settingsMode) {
            toolbar.setTitle(R.string.settings_scan_title);
        } else {
            toolbar.setTitle(storeMode ? R.string.qr_scan_store_title : R.string.qr_scan_title);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        previewView = findViewById(R.id.preview_view);
        btnFlash = findViewById(R.id.btn_flash);
        btnFlash.setOnClickListener(v -> toggleFlash());

        BarcodeScannerOptions options = new BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build();
        barcodeScanner = BarcodeScanning.getClient(options);

        cameraExecutor = Executors.newSingleThreadExecutor();

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, CAMERA_PERM_CODE);
        }
    }

    private void startCamera() {
        ProcessCameraProvider.getInstance(this).addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = ProcessCameraProvider.getInstance(this).get();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;

                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();
                imageAnalysis.setAnalyzer(cameraExecutor, this::processImage);

                cameraProvider.unbindAll();
                camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis);

            } catch (Exception e) {
                e.printStackTrace();
                Feedback.show(this, "相机启动失败");
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void processImage(ImageProxy imageProxy) {
        if (scanned) {
            imageProxy.close();
            return;
        }

        try {
            @SuppressWarnings("UnsafeOptInUsageError")
            android.media.Image mediaImage = imageProxy.getImage();
            if (mediaImage != null) {
                InputImage image = InputImage.fromMediaImage(mediaImage, imageProxy.getImageInfo().getRotationDegrees());
                barcodeScanner.process(image)
                        .addOnSuccessListener(barcodes -> {
                            for (Barcode barcode : barcodes) {
                                String rawValue = barcode.getRawValue();
                                if (rawValue != null) {
                                    handleScannedResult(rawValue);
                                }
                            }
                        })
                        .addOnCompleteListener(task -> imageProxy.close());
            } else {
                imageProxy.close();
            }
        } catch (Exception e) {
            imageProxy.close();
        }
    }

    private void handleScannedResult(String rawValue) {
        if (scanned) return;
        if (rawValue == null || rawValue.isEmpty()) return;

        // 模式 0：接收配置 —— 把对方的全部设置写进本机
        if (settingsMode) {
            int ver = SettingsQrCodec.versionOf(rawValue);
            if (ver < 0) {
                runOnUiThread(() -> Feedback.show(this, R.string.settings_qr_invalid));
                return;
            }
            // 版本不一致直接拒收：字段可能已经增删，硬套会写出错乱的设置
            if (ver != SettingsQrCodec.VERSION) {
                runOnUiThread(() -> Feedback.show(this,
                        getString(R.string.settings_qr_version_mismatch,
                                ver, SettingsQrCodec.VERSION), true));
                return;
            }
            if (!SettingsQrCodec.apply(this, rawValue)) {
                runOnUiThread(() -> Feedback.show(this, R.string.import_fail));
                return;
            }
            scanned = true;
            // 提示交给设置页在 onActivityResult 里弹 —— 这里弹完马上 finish，根本看不到
            runOnUiThread(() -> {
                setResult(RESULT_OK);
                finish();
            });
            return;
        }

        // 模式 A：为某个已存信标储存一个"现成的"二维码
        //
        // 这里刻意不做任何格式校验：扫到的内容原样存下来，之后原样再显示出来，
        // 供别的 App 去读（比如信标厂商自己的配置码）。所以不能因为"不是接娃助手的
        // 信标配置码"就把它挡掉 —— 那不是错误，那正是这个功能要存的东西。
        long storeForId = getIntent().getLongExtra("store_for_beacon_id", -1);
        if (storeForId > 0) {
            scanned = true;
            BeaconDbHelper dbHelper = new BeaconDbHelper(this);
            BeaconEntity target = dbHelper.getBeaconById(storeForId);
            if (target != null) {
                target.setQrContent(rawValue);
                dbHelper.updateBeacon(target);
                runOnUiThread(() -> {
                    Feedback.show(this, R.string.qr_store_success);
                    setResult(RESULT_OK);
                    finish();
                });
            } else {
                runOnUiThread(() -> {
                    Feedback.show(this, R.string.import_fail);
                    scanned = false;
                });
            }
            return;
        }

        // 模式 B：扫码导入新信标 —— 这个才需要是本 APP 的格式
        if (!BeaconQrCodec.isValidFormat(rawValue)) {
            runOnUiThread(() -> Feedback.show(this, R.string.invalid_qr));
            return;
        }

        scanned = true;
        BeaconEntity beacon = BeaconQrCodec.decode(rawValue);
        if (beacon == null) {
            runOnUiThread(() -> {
                Feedback.show(this, R.string.import_fail);
                scanned = false;
            });
            return;
        }

        BeaconDbHelper dbHelper = new BeaconDbHelper(this);

        // 检查重复
        if (dbHelper.isDuplicate(beacon.getUuid(), beacon.getMajor(), beacon.getMinor(),
                beacon.getMac())) {
            runOnUiThread(() -> {
                Feedback.show(this, "该信标已存在，无需重复导入");
                scanned = false;
            });
            return;
        }

        long id = dbHelper.insertBeacon(beacon);
        if (id > 0) {
            // 归属交给调用方决定：管理页会把它认领到点「+」的那一组，
            // 首页则走 ensureChildren() 兜底。这里别抢着分配。
            runOnUiThread(() -> {
                Feedback.show(this, R.string.import_success);
                setResult(RESULT_OK);
                finish();
            });
        } else {
            runOnUiThread(() -> {
                Feedback.show(this, R.string.import_fail);
                scanned = false;
            });
        }
    }

    private void toggleFlash() {
        if (camera == null) return;
        flashOn = !flashOn;
        camera.getCameraControl().enableTorch(flashOn);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERM_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera();
            } else {
                Feedback.show(this, R.string.perm_camera);
                finish();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cameraExecutor != null) {
            cameraExecutor.shutdown();
        }
        if (barcodeScanner != null) {
            barcodeScanner.close();
        }
    }
}
