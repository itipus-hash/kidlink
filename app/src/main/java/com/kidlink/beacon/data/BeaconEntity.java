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
    private long createTime;    // 创建时间戳

    private int sortOrder;          // 显示排序（升序）
    private String qrContent;       // 已储存的二维码内容
    /** 挂在哪个娃下面（children.id），0 = 还没归属 */
    private long childId;
    /** 场景标签，比如「奥数」：同一个娃的不同包靠它区分，留空表示不限场景 */
    private String label;

    // 默认值
    public static final int DEFAULT_RSSI_1M = -59;

    public BeaconEntity() {
        this.rssiAt1m = DEFAULT_RSSI_1M;
        this.createTime = System.currentTimeMillis();
        this.sortOrder = 0;
        this.qrContent = "";
        this.childId = 0;
        this.label = "";
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

    public long getCreateTime() { return createTime; }
    public void setCreateTime(long createTime) { this.createTime = createTime; }

    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }

    public String getQrContent() { return qrContent == null ? "" : qrContent; }
    public void setQrContent(String qrContent) { this.qrContent = qrContent; }

    /** 归属的娃，0 = 还没归属（首页加载时会自动并到第一个娃） */
    public long getChildId() { return childId; }
    public void setChildId(long childId) { this.childId = Math.max(0, childId); }

    /** 场景标签，比如「奥数」；空串 = 不限场景，推送文案走时间段兜底 */
    public String getLabel() { return label == null ? "" : label.trim(); }
    public void setLabel(String label) { this.label = label == null ? "" : label.trim(); }

    public boolean hasLabel() { return !getLabel().isEmpty(); }

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
     * 标准对数路径损耗模型（n = 2.0）：
     *   d = 10 ^ ((RSSI@1m - RSSI) / (10 * n))
     *
     * n 固定 2.0（自由空间）。标定功能已移除 —— 采集它的页面长期没有入口，
     * 与其留一堆「能读不能采」的字段，不如只保留 rssiAt1m 一个可调基准值。
     *
     * @param rssi 当前信号强度
     * @return 估算距离（米），无有效信号时返回 -1
     */
    public double estimateDistance(int rssi) {
        if (rssi >= 0) return -1;

        int refRssi = rssiAt1m != 0 ? rssiAt1m : DEFAULT_RSSI_1M;

        double dist = Math.pow(10.0, (refRssi - rssi) / (10.0 * 2.0));
        if (dist < 0.1) dist = 0.1;
        if (dist > 200) dist = 200;
        return dist;
    }

    @Override
    public String toString() {
        return "BeaconEntity{id=" + id + ", name='" + name + "', uuid='" + getUuidFormatted() +
               "', major=" + major + ", minor=" + minor + ", mac=" + getMac() + ", rssi1m=" + rssiAt1m +
               "dBm}";
    }
}
