package com.kidlink.beacon.qr;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.util.Log;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.util.HashMap;
import java.util.Map;

/**
 * 二维码生成工具 - 使用 ZXing
 */
public class QrCodeGenerator {

    private static final String TAG = "QrCodeGenerator";

    /**
     * 生成二维码 Bitmap
     * @param content 二维码内容
     * @param size 尺寸（像素）
     * @return Bitmap
     */
    public static Bitmap generate(String content, int size) {
        return generate(content, size, size);
    }

    /**
     * 生成二维码 Bitmap（带自定义宽高）
     */
    public static Bitmap generate(String content, int width, int height) {
        try {
            return encodeWith(content, width, height, ErrorCorrectionLevel.M);
        } catch (WriterException e) {
            // 内容较长时 M 级纠错可能装不下，降到 L 级再试一次
            try {
                return encodeWith(content, width, height, ErrorCorrectionLevel.L);
            } catch (WriterException e2) {
                Log.e(TAG, "QR generation failed", e2);
                return null;
            }
        }
    }

    private static Bitmap encodeWith(String content, int width, int height, ErrorCorrectionLevel level)
            throws WriterException {
        Map<EncodeHintType, Object> hints = new HashMap<>();
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        hints.put(EncodeHintType.ERROR_CORRECTION, level);
        hints.put(EncodeHintType.MARGIN, 1);

        QRCodeWriter writer = new QRCodeWriter();
        BitMatrix bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, width, height, hints);

        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                bitmap.setPixel(x, y, bitMatrix.get(x, y) ? Color.BLACK : Color.WHITE);
            }
        }
        return bitmap;
    }

    /**
     * 生成带白色边框的二维码
     */
    public static Bitmap generateWithBorder(String content, int size, int borderSize) {
        Bitmap qrBitmap = generate(content, size - borderSize * 2);
        if (qrBitmap == null) return null;

        Bitmap result = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(result);
        canvas.drawColor(Color.WHITE);
        canvas.drawBitmap(qrBitmap, borderSize, borderSize, null);
        return result;
    }
}
