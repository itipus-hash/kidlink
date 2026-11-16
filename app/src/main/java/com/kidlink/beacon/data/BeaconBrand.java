package com.kidlink.beacon.data;

/**
 * 信标品牌判断 —— 只做一件事：给「没读到 TLM」那颗信标挑一个合适的间隔建议。
 *
 * ## 为什么需要分品牌
 * 「龙雀」这套固件（DA14531）的 TLM 间隔**最大只能设 1500ms**，别的牌子能设到
 * 6000ms 甚至更长。TLM 和 iBeacon 是轮流占同一个射频的，TLM 发得越勤 iBeacon 越稀、
 * 到校检测越慢 —— 所以推荐值必须按设备能力给，写一个用户设不出来的数字没有意义。
 *
 * ## 判据：只看 UUID 前缀
 * 龙雀出厂 UUID 是 `E2C56DB5-DFFB-48D2-B060-D0F5A71096xx`，同批信标只改最后 4 位
 * （…96E0 / …9666），所以取到 `…A71096` 为止共 30 位做前缀比对。
 *
 * 曾经想再加 Company ID 做双重校验（自家 0x4458 / 苹果 0x004C），但实测这颗信标
 * **同时广播两种 iBeacon**，两条随机先后到达，单值存不住、要存集合才能可靠判断，
 * 为了一个提示文案不值得 —— 前缀已经够用，就只保留前缀。
 */
public final class BeaconBrand {

    /**
     * 龙雀固件的出厂 UUID 前缀（去掉横线、大写）。
     *
     * 只取前 4 段 `E2C56DB5-DFFB-48D2-B060`，共 20 位十六进制 ——
     * 后两段（`D0F5A71096xx`）在同批信标里会因产线序号不同而变化，
     * 前面这 20 位才是这套固件的固定特征。
     */
    public static final String LONGQUE_UUID_PREFIX = "E2C56DB5DFFB48D2B060";

    /** 龙雀：TLM 间隔上限就是 1500ms */
    public static final int TLM_INTERVAL_LONGQUE_MS = 1500;
    /** 其他品牌：能设更长，给到 6000ms，尽量把射频时隙让给 iBeacon */
    public static final int TLM_INTERVAL_OTHER_MS = 6000;

    private BeaconBrand() {}

    /** 是不是龙雀那颗 —— 只看 UUID 前缀 */
    public static boolean isLongque(BeaconEntity b) {
        if (b == null) return false;
        String uuid = b.getUuidFormatted();
        if (uuid == null || uuid.isEmpty()) return false;
        return uuid.replace("-", "").toUpperCase().startsWith(LONGQUE_UUID_PREFIX);
    }

    /** 详情里「无 TLM 信息（推荐 Nms）」用的 N */
    public static int recommendedTlmIntervalMs(BeaconEntity b) {
        return isLongque(b) ? TLM_INTERVAL_LONGQUE_MS : TLM_INTERVAL_OTHER_MS;
    }
}
