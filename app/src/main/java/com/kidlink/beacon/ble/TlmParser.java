package com.kidlink.beacon.ble;

/**
 * Eddystone-TLM（遥测帧）解析器 —— 只认明文 TLM，用来读电池电压。
 *
 * ## 帧长什么样
 * TLM 挂在 **Service Data（AD type 0x16）** 里，16 位 UUID 是 Eddystone 的
 * `0xFEAA`（空口小端，所以字节顺序是 `AA FE`）。载荷固定 14 字节：
 * <pre>
 *   [0]    帧类型   0x20 = 明文 TLM（0x21 = 加密 TLM，读不了）
 *   [1]    版本     0x00
 *   [2..3] 电压     **小端**，1 mV/bit；0x0000 = 不支持
 *   [4..5] 温度     8.8 定点，**小端**，0x8000 = 不支持
 *   [6..9] 广播计数 **小端**
 *   [10..13] 运行秒数 ×0.1 s，**小端**
 * </pre>
 * 标准 Eddystone 规定是大端，但这批 DX 固件（DA14531）实际用小端，
 * 和厂商 App 的 `getByteInt()`（从高索引向低索引拼字节）一致，已现场对拍验证。
 * 整条 AD 结构的长度字节这里是 0x17（23），载荷比标准的 14 字节多几个厂商字节，
 * 我们只取前面标准的 14 个。
 *
 * ## 为什么只认 0x20
 * 加密 TLM 的载荷用厂商的 EID 密钥做了 AES-CTR，没有密钥解出来是一串噪声。
 * 硬解只会显示一个随机电压，比不显示更糟，所以直接放弃，
 * 界面上就落到「没开 TLM」那一档。
 *
 * ## 和 iBeacon 的关系
 * 一包广播装不下两套帧，开了 TLM 的信标只能把 TLM 和 iBeacon 交替广播，
 * 所以同一颗信标会两种包轮流出现 —— 两条解析路径都要跑一遍，
 * 谁在这条包里就认谁（见 {@code BeaconScanner.onScanResult}）。
 */
public final class TlmParser {

    /** Eddystone Service UUID 0xFEAA，空口小端 */
    public static final int EDDYSTONE_UUID_LO = 0xAA;
    public static final int EDDYSTONE_UUID_HI = 0xFE;

    public static final int AD_TYPE_SERVICE_DATA_16 = 0x16;

    /** 明文 TLM */
    public static final int FRAME_TYPE_TLM = 0x20;
    /** 加密 TLM（EID）—— 没密钥，不解 */
    public static final int FRAME_TYPE_TLM_ENCRYPTED = 0x21;

    /** 载荷 14 字节：帧类型(1) + 版本(1) + 电压(2) + 温度(2) + 计数(4) + 秒数(4) */
    private static final int TLM_PAYLOAD_LENGTH = 14;

    private TlmParser() {}

    /**
     * 从完整广播数据里找 TLM 帧。
     *
     * @return 解出来的遥测数据；这条广播里没有明文 TLM 时返回 null
     */
    public static TlmData parse(byte[] scanData) {
        if (scanData == null || scanData.length < 4) return null;

        int offset = 0;
        while (offset + 1 < scanData.length) {
            int length = scanData[offset] & 0xFF;
            if (length == 0) break;
            if (offset + length >= scanData.length) break;

            int type = scanData[offset + 1] & 0xFF;
            if (type == AD_TYPE_SERVICE_DATA_16 && length >= 3 + TLM_PAYLOAD_LENGTH) {
                int uuidLo = scanData[offset + 2] & 0xFF;
                int uuidHi = scanData[offset + 3] & 0xFF;
                if (isEddystoneUuid(uuidLo, uuidHi)) {
                    int frameType = scanData[offset + 4] & 0xFF;
                    if (frameType == FRAME_TYPE_TLM) {
                        TlmData data = parsePayload(scanData, offset + 4);
                        if (data != null) return data;
                    }
                    // 0x21：加密 TLM，跳过（见类头说明）
                }
            }
            offset += (length + 1);
        }
        return null;
    }

    /**
     * 0xFEAA 在空口上是小端（AA FE），但确实有固件写成大端（FE AA）。
     * 两种都认 —— 认错了最多是把一个别的服务当成 Eddystone 解一下，解不出就返回 null。
     */
    private static boolean isEddystoneUuid(int b0, int b1) {
        return (b0 == EDDYSTONE_UUID_LO && b1 == EDDYSTONE_UUID_HI)
                || (b0 == EDDYSTONE_UUID_HI && b1 == EDDYSTONE_UUID_LO);
    }

    /** 这条广播里是不是有（明文的）TLM 帧 —— 诊断用 */
    public static boolean hasTlm(byte[] scanData) {
        return parse(scanData) != null;
    }

    /**
     * 这条广播里有没有 Eddystone 服务数据（不管帧类型、解不解得开）。
     * 用来区分「根本没发 TLM」和「发了但我们没解出来」—— 差得很远。
     */
    public static boolean hasEddystoneService(byte[] scanData) {
        if (scanData == null || scanData.length < 4) return false;
        int offset = 0;
        while (offset + 1 < scanData.length) {
            int length = scanData[offset] & 0xFF;
            if (length == 0) break;
            if (offset + length >= scanData.length) break;
            int type = scanData[offset + 1] & 0xFF;
            if (type == AD_TYPE_SERVICE_DATA_16 && length >= 3
                    && isEddystoneUuid(scanData[offset + 2] & 0xFF, scanData[offset + 3] & 0xFF)) {
                return true;
            }
            offset += (length + 1);
        }
        return false;
    }

    private static TlmData parsePayload(byte[] data, int start) {
        // start 指向帧类型字节，后面还需要 13 个字节
        if (start < 0 || start + TLM_PAYLOAD_LENGTH > data.length) return null;

        // 现场抓到的真帧（48:87:2D:9E:6D:FB），逐字段对拍过：
        //   17 16 | AA FE | 20 00 | 03 0C | 1D 00 | E5 06 00 00 | 1C 05 00 00 | 00 05 00 06 C7
        //           uuid    帧/版本  电压     温度     adv 计数       运行 130.8s    major/minor/tx
        //
        // 电压 `03 0C` 必须按**小端**读 → 0x0C03 = 3075 mV（厂商 App 同时显示 3068~3075）
        // 温度 `1D 00` 必须按**大端**读 → 0x1D00 / 256 = 29.0℃（8.8 定点，整数部分在高字节）
        //
        // 也就是说：标准 Eddystone 说全大端，这批 DX 固件除了温度以外全写成了小端。
        // 早期按全大端解，电压会变成 0xFF0B = 65291 mV 那种一眼假的值。
        int voltage = (data[start + 2] & 0xFF) | ((data[start + 3] & 0xFF) << 8);
        int rawTemp = ((data[start + 4] & 0xFF) << 8) | (data[start + 5] & 0xFF);
        float temp = rawTemp == 0x8000 ? TlmData.TEMP_UNSUPPORTED : rawTemp / 256f;

        long adv = (data[start + 6] & 0xFFL)
                | ((data[start + 7] & 0xFFL) << 8)
                | ((data[start + 8] & 0xFFL) << 16)
                | ((data[start + 9] & 0xFFL) << 24);

        long secTenths = (data[start + 10] & 0xFFL)
                | ((data[start + 11] & 0xFFL) << 8)
                | ((data[start + 12] & 0xFFL) << 16)
                | ((data[start + 13] & 0xFFL) << 24);

        return new TlmData(voltage, temp, adv, secTenths / 10);
    }
}
