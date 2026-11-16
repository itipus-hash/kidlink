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
 *   [2..3] 电压     1 mV/bit；0x0000 = 不支持
 *   [4..5] 温度     8.8 定点；0x8000 = 不支持
 *   [6..9] 广播计数
 *   [10..13] 运行秒数 ×0.1 s
 * </pre>
 *
 * ## 字节序：标准和现场固件不一样，两端都认
 * Eddystone 规范写的是**全大端**，但手上这批 DX 固件（DA14531）把电压、计数、
 * 运行时间写成了**小端**（温度倒是老实按大端写的）—— 和厂商 App 的
 * `getByteInt()`（从高索引往低索引拼字节）对得上，已现场对拍验证。
 * 为了两种设备都能读，这里不写死方向，而是**按数值合理性现场判定**：
 * 电压必须落在 {@link #VOLTAGE_MIN_MV}~{@link #VOLTAGE_MAX_MV} 之间，
 * 两个方向只有一个说得通时就用说得通的（字节读反几乎只会把值放大 256 倍，
 * 3068 mV 读反就是 64523 mV，一眼假）；两个都说得通或都说不通时，
 * 优先小端（现场的 DX 固件），否则取小的那个。判定结果再套给计数和运行时间。
 *
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

    /** 电压合理区间（mV）：低于 1.2 V 电池已报废，高于 4.2 V 不存在这种电池 */
    private static final int VOLTAGE_MIN_MV = 1200;
    private static final int VOLTAGE_MAX_MV = 4200;
    /** 温度合理区间（℃）：芯片自己都到不了 -40 以下、125 以上 */
    private static final float TEMP_MIN_C = -40f;
    private static final float TEMP_MAX_C = 125f;
    /** 运行时间上限（0.1 s 单位）：5 年。超过就是字节序读反了 */
    private static final long MAX_UPTIME_TENTHS = 5L * 365 * 24 * 3600 * 10;

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
        // 这颗是小端设备：电压 `03 0C` 按小端读 → 0x0C03 = 3075 mV（厂商 App 同时显示 3068~3075）；
        // 按大端读是 0x030C = 780 mV，连电池的最低电压都够不上，一眼假 —— 判方向的依据就是它。
        // 标准 Eddystone 设备正好反过来（大端），所以下面先判方向、再套给各字段。

        int b0 = data[start + 2] & 0xFF;
        int b1 = data[start + 3] & 0xFF;
        int voltageBe = (b0 << 8) | b1;
        int voltageLe = b0 | (b1 << 8);

        long upBe = readU32(data, start + 10, false);
        long upLe = readU32(data, start + 10, true);

        // 整帧整数是不是小端写的
        boolean little;
        if (voltageBe == 0 && voltageLe == 0) {
            // 信标自己说「不支持电压」，只能拿运行时间反推方向
            little = betterLittle(upBe <= MAX_UPTIME_TENTHS, upLe <= MAX_UPTIME_TENTHS, upBe, upLe);
        } else {
            boolean beOk = voltageBe >= VOLTAGE_MIN_MV && voltageBe <= VOLTAGE_MAX_MV;
            boolean leOk = voltageLe >= VOLTAGE_MIN_MV && voltageLe <= VOLTAGE_MAX_MV;
            if (beOk && !leOk) {
                little = false;
            } else if (leOk && !beOk) {
                little = true;
            } else if (beOk) {
                // 两个方向都说得通（0x0B0C / 0x0C0B 这种），现场固件是小端，优先小端
                little = true;
            } else {
                // 两个都不合理，取小的 —— 字节读反几乎只会把值放大 256 倍
                little = voltageLe <= voltageBe;
            }
        }

        int voltage = little ? voltageLe : voltageBe;
        long adv = readU32(data, start + 6, little);
        long secTenths = readU32(data, start + 10, little);

        // 计数和运行时间跟电压共用同一个方向判定，但 4 字节字段读反几乎必然爆表，
        // 爆了说明电压那边的判定在这帧上没站住，能换就换回来
        if (secTenths > MAX_UPTIME_TENTHS) {
            long other = readU32(data, start + 10, !little);
            if (other <= MAX_UPTIME_TENTHS) {
                little = !little;
                voltage = little ? voltageLe : voltageBe;
                adv = readU32(data, start + 6, little);
                secTenths = other;
            }
        }

        return new TlmData(voltage, parseTemp(data, start), adv, secTenths / 10);
    }

    /**
     * 温度：8.8 定点（高字节整数、低字节小数，有符号）。
     * 标准和 DX 固件在这两个字节上都是大端，所以默认大端，
     * 只有大端读出个不可能的数值（-40 ℃ 以下 / 125 ℃ 以上）时才反过来试小端。
     */
    private static float parseTemp(byte[] data, int start) {
        int rawBe = ((data[start + 4] & 0xFF) << 8) | (data[start + 5] & 0xFF);
        int rawLe = (data[start + 4] & 0xFF) | ((data[start + 5] & 0xFF) << 8);
        if (rawBe == 0x8000 || rawLe == 0x8000) return TlmData.TEMP_UNSUPPORTED;   // -128 ℃ = 不支持

        float tBe = (short) rawBe / 256f;
        if (tBe >= TEMP_MIN_C && tBe <= TEMP_MAX_C) return tBe;

        float tLe = (short) rawLe / 256f;
        if (tLe >= TEMP_MIN_C && tLe <= TEMP_MAX_C) return tLe;
        return tBe;
    }

    /** 小端/大端两种拼法，按给定的偏移读一个 32 位无符号整数 */
    private static long readU32(byte[] data, int at, boolean little) {
        if (little) {
            return (data[at] & 0xFFL)
                    | ((data[at + 1] & 0xFFL) << 8)
                    | ((data[at + 2] & 0xFFL) << 16)
                    | ((data[at + 3] & 0xFFL) << 24);
        }
        return ((data[at] & 0xFFL) << 24)
                | ((data[at + 1] & 0xFFL) << 16)
                | ((data[at + 2] & 0xFFL) << 8)
                | (data[at + 3] & 0xFFL);
    }

    /** 两个方向都没有硬性证据时怎么选：一个合理一个不合理听合理的，否则取小的 */
    private static boolean betterLittle(boolean beOk, boolean leOk, long be, long le) {
        if (leOk != beOk) return leOk;
        if (leOk) return true;          // 都合理 → 现场固件是小端
        return le <= be;
    }
}
