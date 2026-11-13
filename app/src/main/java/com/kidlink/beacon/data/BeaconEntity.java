package com.kidlink.beacon.data;

/**
 * 信标实体类 - 对应数据库一行记录
 * 存储在 SQLite 中，纯本地数据
 */
public class BeaconEntity {
    private long id;
    private String name;        // 标签名称
    private String remark;      // 备注
    private String uuid;        // iBeacon UUID (大写带横线)
    private int major;          // iBeacon Major
    private int minor;          // iBeacon Minor
    private String mac;         // 设备蓝牙地址（AA:BB:CC:DD:EE:FF），用于区分三件套相同的两个信标
    private int rssiAt1m;       // RSSI@1m 基准值 (dBm)，默认 -59
    private int thresholdRssi;  // 触发RSSI阈值，信号强于此值触发提醒
    private long createTime;    // 创建时间戳

    private int sortOrder;          // 显示排序（升序）
    private double calibDistance;   // 标定距离（米），0 表示未标定
    private int calibRssi;          // 标定距离处采集的 RSSI，0 表示未标定
    private String qrContent;       // 已储存的二维码内容

    // 默认值
    public static final int DEFAULT_RSSI_1M = -59;
    public static final int DEFAULT_THRESHOLD = -70;

    public BeaconEntity() {
        this.rssiAt1m = DEFAULT_RSSI_1M;
        this.thresholdRssi = DEFAULT_THRESHOLD;
        this.createTime = System.currentTimeMillis();
        this.sortOrder = 0;
        this.calibDistance = 0;
        this.calibRssi = 0;
        this.qrContent = "";
    }

    public BeaconEntity(long id, String name, String remark, String uuid,
                        int major, int minor, int rssiAt1m, int thresholdRssi, long createTime) {
        this();
        this.id = id;
        this.name = name;
        this.remark = remark;
        this.uuid = uuid;
        this.major = major;
        this.minor = minor;
        this.rssiAt1m = rssiAt1m;
        this.thresholdRssi = thresholdRssi;
        this.createTime = createTime;
    }

    // Getters & Setters
    public long getId() { return id; }
    public void setId(long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }

    public String getUuid() { return uuid; }
    public void setUuid(String uuid) { this.uuid = uuid; }

    public int getMajor() { return major; }
    public void setMajor(int major) { this.major = major; }

    public int getMinor() { return minor; }
    public void setMinor(int minor) { this.minor = minor; }

    /** 蓝牙地址，未记录过时为空串 */
    public String getMac() { return mac == null ? "" : mac.trim().toUpperCase(); }
    public void setMac(String mac) { this.mac = mac == null ? "" : mac.trim().toUpperCase(); }

    /** 是否已经知道这台信标的 MAC —— 有才能在三件套相同的两个信标之间区分开 */
    public boolean hasMac() { return !getMac().isEmpty(); }

    public int getRssiAt1m() { return rssiAt1m; }
    public void setRssiAt1m(int rssiAt1m) { this.rssiAt1m = rssiAt1m; }

    public int getThresholdRssi() { return thresholdRssi; }
    public void setThresholdRssi(int thresholdRssi) { this.thresholdRssi = thresholdRssi; }

    public long getCreateTime() { return createTime; }
    public void setCreateTime(long createTime) { this.createTime = createTime; }

    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }

    public double getCalibDistance() { return calibDistance; }
    public void setCalibDistance(double calibDistance) { this.calibDistance = calibDistance; }

    public int getCalibRssi() { return calibRssi; }
    public void setCalibRssi(int calibRssi) { this.calibRssi = calibRssi; }

    public String getQrContent() { return qrContent == null ? "" : qrContent; }
    public void setQrContent(String qrContent) { this.qrContent = qrContent; }


    /** 是否已做过距离标定 */
    public boolean hasCalibration() {
        return calibDistance > 0.05 && calibRssi < 0;
    }

    /**
     * 获取 UUID 的无横线小写形式，用于 BLE 扫描匹配
     */
    public String getUuidRaw() {
        if (uuid == null) return "";
        return uuid.replace("-", "").toLowerCase();
    }

    /**
     * 获取 UUID 的标准大写带横线形式
     */
    public String getUuidFormatted() {
        if (uuid == null) return "";
        String raw = uuid.replace("-", "").toUpperCase();
        if (raw.length() == 32) {
            return raw.substring(0, 8) + "-" + raw.substring(8, 12) + "-" +
                   raw.substring(12, 16) + "-" + raw.substring(16, 20) + "-" +
                   raw.substring(20, 32);
        }
        return uuid.toUpperCase();
    }

    /**
     * 根据实时 RSSI 估算距离
     *
     * 未标定时使用标准对数路径损耗模型（n = 2.0）：
     *   d = 10 ^ ((RSSI@1m - RSSI) / (10 * n))
     *
     * 已标定时（标定点 d0 / RSSI0）由两点反推环境衰减因子 n：
     *   n = (RSSI@1m - RSSI0) / (10 * log10(d0))
     * 再代入模型计算距离，更贴合实际摆放环境。
     *
     * @param rssi 当前信号强度
     * @return 估算距离（米），无有效信号时返回 -1
     */
    public double estimateDistance(int rssi) {
        if (rssi >= 0) return -1;

        int refRssi = rssiAt1m != 0 ? rssiAt1m : DEFAULT_RSSI_1M;
        double n = 2.0;

        if (hasCalibration()) {
            if (Math.abs(calibDistance - 1.0) <= 0.05) {
                // 标定点就是 1 米，直接用它作为基准
                refRssi = calibRssi;
            } else {
                double denom = 10.0 * Math.log10(calibDistance);
                if (Math.abs(denom) > 0.05) {
                    double calc = (refRssi - calibRssi) / denom;
                    if (calc >= 0.8 && calc <= 6.0) n = calc;
                }
            }
        }

        double dist = Math.pow(10.0, (refRssi - rssi) / (10.0 * n));
        if (dist < 0.1) dist = 0.1;
        if (dist > 200) dist = 200;
        return dist;
    }

    @Override
    public String toString() {
        return "BeaconEntity{id=" + id + ", name='" + name + "', uuid='" + getUuidFormatted() +
               "', major=" + major + ", minor=" + minor + ", mac=" + getMac() + ", rssi1m=" + rssiAt1m +
               ", threshold=" + thresholdRssi + ", calib=" + calibDistance + "m/" + calibRssi +
               "dBm}";
    }
}
