package com.kidlink.beacon.ui;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.kidlink.beacon.ui.widget.Feedback;
import com.kidlink.beacon.R;
import com.kidlink.beacon.data.BeaconDbHelper;
import com.kidlink.beacon.data.BeaconEntity;
import com.kidlink.beacon.qr.BeaconQrCodec;
import com.kidlink.beacon.qr.QrCodeGenerator;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

/**
 * 二维码展示页
 *
 * mode = "own"            信标二维码：优先展示已储存的二维码内容
 * mode = "share"          分享信标：展示包含标定信息的完整配置码，可调用系统分享
 * extra "settings_payload" 设置页的配置码：内容由 SettingsQrCodec 编码好直接传进来
 * extra "qr_frames"       整套配置（娃 + 全部信标）：可能多页，自动轮播成动态二维码
 */
public class QRShareActivity extends AppCompatActivity {

    private BeaconEntity beacon;
    private Bitmap qrBitmap;
    private String qrContent;
    /** 存相册时的文件名前缀 */
    private String fileBaseName = "kidlink";

    /** 多页动态二维码：每页一张码，按 PAGED_INTERVAL_MS 轮播 */
    private static final long PAGED_INTERVAL_MS = 900L;
    private ArrayList<String> frames;
    private final ArrayList<Bitmap> frameBitmaps = new ArrayList<>();
    private int frameIndex = 0;
    private ImageView imgQrView;
    private TextView txtPageView;
    private final Handler cycleHandler = new Handler(Looper.getMainLooper());
    private final Runnable cycler = new Runnable() {
        @Override
        public void run() {
            if (frames == null || frames.size() <= 1) return;
            frameIndex = (frameIndex + 1) % frames.size();
            showFrame(frameIndex);
            cycleHandler.postDelayed(this, PAGED_INTERVAL_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_qr_share);

        // 管理页「分享配置」：整套娃 + 信标，可能是多页动态码
        ArrayList<String> framesExtra = getIntent().getStringArrayListExtra("qr_frames");
        if (framesExtra != null && !framesExtra.isEmpty()) {
            setupBundleShare(framesExtra);
            return;
        }

        // 设置页分享配置：不涉及信标，直接渲染传进来的内容
        String settingsPayload = getIntent().getStringExtra("settings_payload");
        if (settingsPayload != null && !settingsPayload.isEmpty()) {
            setupSettingsShare(settingsPayload);
            return;
        }

        BeaconDbHelper dbHelper = new BeaconDbHelper(this);

        long beaconId = getIntent().getLongExtra("beacon_id", -1);
        String mode = getIntent().getStringExtra("mode");
        if (mode == null) mode = "share";
        boolean isShare = "share".equals(mode);

        beacon = dbHelper.getBeaconById(beaconId);
        if (beacon == null) {
            finish();
            return;
        }
        fileBaseName = beacon.getName();

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle(isShare ? R.string.qr_share_title : R.string.beacon_qr_title);
        toolbar.setNavigationOnClickListener(v -> finish());

        TextView txtName = findViewById(R.id.txt_name);
        TextView txtHint = findViewById(R.id.txt_hint);
        ImageView imgQr = findViewById(R.id.img_qr);
        Button btnSave = findViewById(R.id.btn_save);
        Button btnShare = findViewById(R.id.btn_share);

        txtName.setText(beacon.getName());

        if (isShare) {
            qrContent = BeaconQrCodec.encodeFull(beacon);
        } else {
            // 只复现"储存过的那个二维码"，不自己造一个：
            // 这个位置的意义是把用户扫进来的现成二维码原样还给别的 App，
            // 自己编码出来的东西对方未必认。
            String stored = beacon.getQrContent();
            qrContent = (stored == null || stored.isEmpty()) ? null : stored;
        }

        if (isShare) {
            txtHint.setText(R.string.qr_share_hint);
        } else {
            txtHint.setText(qrContent == null ? R.string.beacon_qr_empty_hint : R.string.beacon_qr_hint);
        }
        btnShare.setVisibility(isShare ? android.view.View.VISIBLE : android.view.View.GONE);
        btnSave.setVisibility(qrContent == null ? android.view.View.GONE : android.view.View.VISIBLE);

        if (qrContent != null) {
            qrBitmap = QrCodeGenerator.generateWithBorder(qrContent, 600, 30);
            if (qrBitmap != null) {
                imgQr.setImageBitmap(qrBitmap);
            } else {
                Feedback.show(this, "二维码生成失败");
            }
        }

        btnSave.setOnClickListener(v -> saveToGallery());
        btnShare.setOnClickListener(v -> shareText());
    }

    /**
     * 管理页「分享配置」：整套娃 + 信标。
     *
     * 一页装得下就是一张静态码；装不下就按页轮播成动态二维码 ——
     * 每 900ms 换一页，让对方连着扫几秒，几页凑齐才算导完。
     * 这种码不适合存相册（存下来只是其中一页），所以多页时只给「看」，不给存。
     */
    private void setupBundleShare(ArrayList<String> payloads) {
        frames = payloads;
        fileBaseName = "config";

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle(R.string.share_config_title);
        toolbar.setNavigationOnClickListener(v -> finish());

        TextView txtName = findViewById(R.id.txt_name);
        TextView txtHint = findViewById(R.id.txt_hint);
        imgQrView = findViewById(R.id.img_qr);
        txtPageView = findViewById(R.id.txt_page);
        android.widget.Button btnSave = findViewById(R.id.btn_save);
        android.widget.Button btnShare = findViewById(R.id.btn_share);

        txtName.setText(R.string.share_config_name);
        // 系统分享发出去的是纯文字，对方拿到也用不上，这里只留二维码
        btnShare.setVisibility(android.view.View.GONE);

        final boolean paged = frames.size() > 1;
        btnSave.setVisibility(paged ? android.view.View.GONE : android.view.View.VISIBLE);
        if (paged) {
            txtHint.setText(getString(R.string.share_config_hint_paged, frames.size()));
            txtPageView.setVisibility(android.view.View.VISIBLE);
        } else {
            txtHint.setText(R.string.share_config_hint);
        }

        for (String f : frames) {
            frameBitmaps.add(QrCodeGenerator.generateWithBorder(f, 600, 30));
        }
        showFrame(0);
        if (frameBitmaps.get(0) == null) {
            Feedback.show(this, R.string.qr_generate_failed);
        }
        if (paged) {
            cycleHandler.postDelayed(cycler, PAGED_INTERVAL_MS);
        }

        btnSave.setOnClickListener(v -> {
            qrBitmap = frameBitmaps.isEmpty() ? null : frameBitmaps.get(0);
            saveToGallery();
        });
    }

    /** 显示第 i 页（并从相册保存的角度同步当前这张） */
    private void showFrame(int i) {
        if (i < 0 || i >= frameBitmaps.size()) return;
        Bitmap bmp = frameBitmaps.get(i);
        if (bmp != null && imgQrView != null) {
            imgQrView.setImageBitmap(bmp);
        }
        if (paged() && txtPageView != null) {
            txtPageView.setText(getString(R.string.share_config_page, i + 1, frames.size()));
        }
    }

    private boolean paged() {
        return frames != null && frames.size() > 1;
    }

    /** 设置页「分享配置」：内容和提示都不一样，其余渲染逻辑共用 */
    private void setupSettingsShare(String payload) {
        qrContent = payload;
        // 保存文件名是 kidlink_ + fileBaseName + 时间戳，这里只放 settings，别拼成 kidlink_kidlink_settings
        fileBaseName = "settings";

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle(R.string.settings_share_title);
        toolbar.setNavigationOnClickListener(v -> finish());

        TextView txtName = findViewById(R.id.txt_name);
        TextView txtHint = findViewById(R.id.txt_hint);
        ImageView imgQr = findViewById(R.id.img_qr);
        android.widget.Button btnSave = findViewById(R.id.btn_save);
        android.widget.Button btnShare = findViewById(R.id.btn_share);

        txtName.setText(R.string.settings_share_name);
        txtHint.setText(R.string.settings_share_hint);
        btnSave.setVisibility(android.view.View.VISIBLE);
        btnShare.setVisibility(android.view.View.VISIBLE);

        qrBitmap = QrCodeGenerator.generateWithBorder(payload, 600, 30);
        if (qrBitmap != null) {
            imgQr.setImageBitmap(qrBitmap);
        } else {
            Feedback.show(this, R.string.qr_generate_failed);
        }

        btnSave.setOnClickListener(v -> saveToGallery());
        btnShare.setOnClickListener(v -> shareText());
    }

    private void shareText() {
        if (qrContent == null || qrContent.isEmpty()) {
            Feedback.show(this, R.string.import_fail);
            return;
        }
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_SUBJECT, fileBaseName);
        send.putExtra(Intent.EXTRA_TEXT, qrContent);
        startActivity(Intent.createChooser(send, getString(R.string.share_chooser)));
    }

    private void saveToGallery() {
        if (qrBitmap == null) {
            Feedback.show(this, R.string.save_fail);
            return;
        }

        String fileName = "kidlink_" + fileBaseName + "_" +
                new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date()) + ".png";
        // 清理文件名中的非法字符
        fileName = fileName.replaceAll("[^a-zA-Z0-9._-]", "_");

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+ 使用 MediaStore
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
            values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/KidLink");

            ContentResolver resolver = getContentResolver();
            Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                Feedback.show(this, R.string.save_fail);
                return;
            }

            try (OutputStream out = resolver.openOutputStream(uri)) {
                if (out != null) {
                    qrBitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
                    Feedback.show(this, R.string.save_success);
                }
            } catch (Exception e) {
                Feedback.show(this, R.string.save_fail);
            }
        } else {
            // Android 9 及以下直接写文件
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "KidLink");
            if (!dir.exists()) dir.mkdirs();
            File file = new File(dir, fileName);
            try (FileOutputStream out = new FileOutputStream(file)) {
                qrBitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
                // 通知媒体扫描
                android.media.MediaScannerConnection.scanFile(this,
                        new String[]{file.getAbsolutePath()}, null, null);
                Feedback.show(this, R.string.save_success);
            } catch (Exception e) {
                Feedback.show(this, R.string.save_fail);
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cycleHandler.removeCallbacks(cycler);
        for (Bitmap b : frameBitmaps) {
            if (b != null && !b.isRecycled()) b.recycle();
        }
        frameBitmaps.clear();
        if (qrBitmap != null && !qrBitmap.isRecycled()) {
            qrBitmap.recycle();
        }
        qrBitmap = null;
    }
}
