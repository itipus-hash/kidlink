package com.kidlink.beacon.qr;

import android.util.Log;

import com.kidlink.beacon.data.BeaconEntity;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * 信标配置二维码编解码
 *
 * 二维码内容格式: KIDLINK:v1:<Base64(JSON)>
 * JSON 包含: name, remark, uuid, major, minor, rssi1m, threshold
 * 分享时额外包含标定信息: cd(标定距离), cr(标定RSSI)
 * 以及蓝牙地址 mc —— 同品牌信标三件套可能一样，导入端靠它区分两台设备
 *
 * 给其他安装了【接娃助手】的安卓手机扫码导入此信标配置
 */
public class BeaconQrCodec {

    private static final String TAG = "BeaconQrCodec";
    public static final String PREFIX = "KIDLINK:v1:";

    /**
     * 将信标配置编码为二维码内容字符串（基础字段）
     */
    public static String encode(BeaconEntity beacon) {
        return encode(beacon, false);
    }

    /**
     * 完整分享编码：包含标定数据与备注，方便对方手机直接还原距离手感
     */
    public static String encodeFull(BeaconEntity beacon) {
        return encode(beacon, true);
    }

    private static String encode(BeaconEntity beacon, boolean withCalibration) {
        try {
            JSONObject json = new JSONObject();
            json.put("n", beacon.getName());
            json.put("r", beacon.getRemark() != null ? beacon.getRemark() : "");
            json.put("u", beacon.getUuidRaw());
            json.put("ma", beacon.getMajor());
            json.put("mi", beacon.getMinor());
            json.put("rs", beacon.getRssiAt1m());
            json.put("th", beacon.getThresholdRssi());
            if (beacon.hasMac()) {
                json.put("mc", beacon.getMac());
            }
            if (withCalibration && beacon.hasCalibration()) {
                json.put("cd", beacon.getCalibDistance());
                json.put("cr", beacon.getCalibRssi());
            }

            String jsonStr = json.toString();
            String base64 = android.util.Base64.encodeToString(jsonStr.getBytes(), android.util.Base64.NO_WRAP);
            return PREFIX + base64;
        } catch (JSONException e) {
            Log.e(TAG, "Encode error", e);
            return null;
        }
    }

    /**
     * 解码二维码内容为信标配置
     * @return BeaconEntity 或 null（如果不是本APP格式的二维码）
     */
    public static BeaconEntity decode(String qrContent) {
        if (qrContent == null || !qrContent.startsWith(PREFIX)) {
            return null;
        }

        try {
            String base64 = qrContent.substring(PREFIX.length());
            String jsonStr = new String(android.util.Base64.decode(base64, android.util.Base64.DEFAULT));
            JSONObject json = new JSONObject(jsonStr);

            BeaconEntity beacon = new BeaconEntity();
            beacon.setName(json.optString("n", "未命名信标"));
            beacon.setRemark(json.optString("r", ""));
            beacon.setUuid(json.optString("u", ""));
            beacon.setMajor(json.optInt("ma", 0));
            beacon.setMinor(json.optInt("mi", 0));
            beacon.setRssiAt1m(json.optInt("rs", BeaconEntity.DEFAULT_RSSI_1M));
            beacon.setThresholdRssi(json.optInt("th", BeaconEntity.DEFAULT_THRESHOLD));
            beacon.setCalibDistance(json.optDouble("cd", 0));
            beacon.setCalibRssi(json.optInt("cr", 0));
            beacon.setMac(json.optString("mc", ""));
            beacon.setQrContent(qrContent);

            // 验证必填字段
            if (beacon.getUuid().isEmpty() || beacon.getUuid().length() < 32) {
                Log.e(TAG, "Invalid UUID in QR data");
                return null;
            }

            return beacon;
        } catch (Exception e) {
            Log.e(TAG, "Decode error", e);
            return null;
        }
    }

    /**
     * 判断是否是本APP的二维码格式
     */
    public static boolean isValidFormat(String qrContent) {
        return qrContent != null && qrContent.startsWith(PREFIX);
    }
}
