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
     * 把信标配置编码成二维码内容。带备注与 MAC，对方扫一下就能还原出同一台信标。
     * 前缀仍是 v1：标定字段移除后旧二维码照样能解析（多出来的 key 会被忽略）。
     */
    public static String encodeFull(BeaconEntity beacon) {
        try {
            JSONObject json = new JSONObject();
            json.put("n", beacon.getName());
            json.put("r", beacon.getRemark() != null ? beacon.getRemark() : "");
            json.put("u", beacon.getUuidRaw());
            json.put("ma", beacon.getMajor());
            json.put("mi", beacon.getMinor());
            json.put("rs", beacon.getRssiAt1m());
            if (beacon.hasMac()) {
                json.put("mc", beacon.getMac());
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
