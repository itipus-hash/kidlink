package com.kidlink.beacon.ble;

import java.util.Arrays;

/**
 * iBeacon 广播包解析器
 * 解析 BLE 广播数据中的 Apple iBeacon 格式
 *
 * iBeacon 帧格式（在 Manufacturer Specific Data 中）:
 * - Company ID: 0x004C (Apple)
 * - Sub Type: 0x02 (iBeacon)
 * - Sub Length: 0x15 (21 bytes)
 * - UUID: 16 bytes
 * - Major: 2 bytes (big-endian)
 * - Minor: 2 bytes (big-endian)
 * - Tx Power: 1 byte (signed, dBm @ 1m)
 */
public class IBeaconParser {

    public static final int APPLE_COMPANY_ID = 0x004C;
    public static final int IBEACON_SUB_TYPE = 0x02;
    public static final int IBEACON_SUB_LENGTH = 0x15;

    /**
     * Manufacturer Specific Data 这个 AD 结构里「长度字节」的最小值。
     * 长度字节统计的是它自己之后的字节数：AD Type(1) + CompanyID(2) + SubType(1)
     * + SubLength(1) + Payload(21) = 26，也就是 0x1A。
     * 标准 iBeacon 广播实测就是 `02 01 06 | 1A FF 4C 00 02 15 ...`。
     * （原先误写成 0x1B/27，导致所有标准 iBeacon 包被直接跳过。）
     */
    private static final int MIN_MANUFACTURER_AD_LENGTH = 0x1A;

    /** iBeacon payload 长度：UUID(16) + Major(2) + Minor(2) + TxPower(1) = 21 */
    private static final int IBEACON_PAYLOAD_LENGTH = 21;

    /**
     * 解析扫描到的 iBeacon 数据
     */
    public static IBeaconData parse(byte[] scanData, int rssi) {
        if (scanData == null || scanData.length < 2) {
            return null;
        }

        int offset = 0;
        while (offset + 1 < scanData.length) {
            int length = scanData[offset] & 0xFF;
            if (length == 0) break;
            if (offset + length >= scanData.length) break;

            int type = scanData[offset + 1] & 0xFF;

            // Manufacturer Specific Data (type 0xFF)
            if (type == 0xFF && length >= MIN_MANUFACTURER_AD_LENGTH) {
                // Check Apple company ID (little-endian in advertising data)
                int companyId = ((scanData[offset + 3] & 0xFF) << 8) | (scanData[offset + 2] & 0xFF);
                // Check iBeacon subtype
                int subType = scanData[offset + 4] & 0xFF;
                int subLength = scanData[offset + 5] & 0xFF;

                // 只要 0x02 0x15 这个 iBeacon 帧头对得上就认，不再强制 Company ID == 0x004C。
                // 原因：部分信标厂（以及一些自定义固件）会把 Company ID 写成自己的私有值，
                // 帧结构却是标准 iBeacon；强制校验 Apple ID 会让这类设备永远识别不到。
                if (subType == IBEACON_SUB_TYPE && subLength == IBEACON_SUB_LENGTH) {
                    IBeaconData data = parseIBeaconPayload(scanData, offset + 6, rssi);
                    if (data != null) {
                        data.companyId = companyId;
                        return data;
                    }
                }
            }

            offset += (length + 1);
        }

        return null;
    }

    /**
     * 解析 iBeacon payload（UUID + Major + Minor + TxPower）
     */
    private static IBeaconData parseIBeaconPayload(byte[] data, int start, int rssi) {
        if (start < 0 || start + IBEACON_PAYLOAD_LENGTH > data.length) return null;

        // UUID: 16 bytes
        byte[] uuidBytes = Arrays.copyOfRange(data, start, start + 16);
        String uuid = formatUuid(uuidBytes);

        // Major: 2 bytes big-endian
        int major = ((data[start + 16] & 0xFF) << 8) | (data[start + 17] & 0xFF);

        // Minor: 2 bytes big-endian
        int minor = ((data[start + 18] & 0xFF) << 8) | (data[start + 19] & 0xFF);

        // Tx Power: 1 byte signed
        int txPower = data[start + 20];
        if (txPower > 127) txPower -= 256;

        return new IBeaconData(uuid, major, minor, txPower, rssi);
    }

    /**
     * 将 16 字节 UUID 格式化为标准 8-4-4-4-12 大写带横线格式
     */
    public static String formatUuid(byte[] bytes) {
        if (bytes == null || bytes.length != 16) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 16; i++) {
            sb.append(String.format("%02X", bytes[i] & 0xFF));
            if (i == 3 || i == 5 || i == 7 || i == 9) {
                sb.append("-");
            }
        }
        return sb.toString();
    }

    /**
     * 判断两个 UUID 是否匹配（忽略大小写和横线）
     */
    public static boolean uuidMatch(String uuid1, String uuid2) {
        if (uuid1 == null || uuid2 == null) return false;
        String u1 = uuid1.replace("-", "").toUpperCase();
        String u2 = uuid2.replace("-", "").toUpperCase();
        return u1.equals(u2);
    }

    /**
     * 对一条原始广播做判读，给扫描页的「原始广播」用。
     *
     * 为什么要它：远程用户说「扫不到」时，只有一句「扫不到」是没法定位的 ——
     * 可能是没收到包（手机/权限问题），可能是收到了但格式不对（设备问题），
     * 也可能是格式对但我们没解出来（我们的问题）。这三种在 hex 上长得完全不一样，
     * 一眼判出来才能让对方知道该改哪一头。
     */
    public static String diagnose(byte[] scanData) {
        if (scanData == null || scanData.length < 2) return "空广播";

        int offset = 0;
        boolean sawApple = false;
        String appleDetail = null;

        while (offset + 1 < scanData.length) {
            int length = scanData[offset] & 0xFF;
            if (length == 0) break;
            if (offset + length >= scanData.length) {
                return String.format("长度字节 %02X 超出包长，数据被截断", length);
            }
            int type = scanData[offset + 1] & 0xFF;

            if (type == 0xFF && length >= 4) {
                int companyId = ((scanData[offset + 3] & 0xFF) << 8) | (scanData[offset + 2] & 0xFF);
                if (companyId == APPLE_COMPANY_ID) {
                    sawApple = true;
                    if (length >= MIN_MANUFACTURER_AD_LENGTH && offset + 6 < scanData.length) {
                        int subType = scanData[offset + 4] & 0xFF;
                        int subLength = scanData[offset + 5] & 0xFF;
                        if (subType == IBEACON_SUB_TYPE && subLength == IBEACON_SUB_LENGTH) {
                            IBeaconData d = parse(scanData, -60);
                            return d == null
                                    ? "iBeacon 帧头正常，但 payload 解析失败"
                                    : "iBeacon ✓ " + d.uuid + "  " + d.major + "/" + d.minor;
                        }
                        appleDetail = String.format(
                                "Apple 厂商数据，但帧头是 %02X %02X（标准应为 02 15）",
                                subType, subLength);
                    } else {
                        appleDetail = String.format(
                                "Apple 厂商数据，长度字节 %02X 偏短（标准应为 1A）", length);
                    }
                }
            }
            offset += (length + 1);
        }

        if (sawApple) return appleDetail == null ? "Apple 厂商数据，结构异常" : appleDetail;
        return "没有厂商自定义数据（FF），不是 iBeacon";
    }
}
