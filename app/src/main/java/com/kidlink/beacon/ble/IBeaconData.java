package com.kidlink.beacon.ble;

/**
 * 解析后的 iBeacon 数据
 */
public class IBeaconData {
    public final String uuid;     // 大写带横线格式
    public final int major;
    public final int minor;
    public final int txPower;     // 发射功率 dBm @ 1m
    public final int rssi;        // 接收信号强度 dBm

    /** 广播包里的 Company ID（诊断用：0x004C 是 Apple，其他值说明是私有格式） */
    public int companyId = 0;

    public IBeaconData(String uuid, int major, int minor, int txPower, int rssi) {
        this.uuid = uuid;
        this.major = major;
        this.minor = minor;
        this.txPower = txPower;
        this.rssi = rssi;
    }

    /**
     * 使用路径损耗模型估算距离
     * 公式: distance = 10 ^ ((txPower - rssi) / (10 * n))
     * n = 环境衰减因子，默认 2.0
     *
     * @param rssi1m  RSSI@1m 基准值（优先使用校准值，否则用 txPower）
     * @return 估算距离（米）
     */
    public double estimateDistance(int rssi1m) {
        double refRssi = rssi1m != 0 ? rssi1m : txPower;
        if (rssi == 0) return -1.0;

        double ratio = (refRssi - rssi) / 10.0;
        double n = 2.0; // 路径损耗指数
        return Math.pow(10, ratio / n);
    }

    /**
     * 使用默认 txPower 估算距离
     */
    public double estimateDistance() {
        return estimateDistance(txPower);
    }

    @Override
    public String toString() {
        return "IBeaconData{uuid=" + uuid + ", major=" + major + ", minor=" + minor +
               ", txPower=" + txPower + ", rssi=" + rssi + "}";
    }
}
