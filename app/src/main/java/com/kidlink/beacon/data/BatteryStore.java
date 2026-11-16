package com.kidlink.beacon.data;

import android.content.Context;
import android.util.Log;

import com.kidlink.beacon.ble.TlmData;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 信标电量（Eddystone-TLM）的落点。
 *
 * ## 为什么要单独一个类
 * TLM 包每几百毫秒就可能来一条，但电量这种东西一小时变不了几个毫伏 ——
 * 每条都往 SQLite 写一次纯属浪费（还费电）。所以分两层：
 * <ul>
 *   <li><b>内存</b>：每条 TLM 都更新，界面随时能读到「最新读数」，换了电池立刻反映；</li>
 *   <li><b>数据库</b>：每颗信标<b>最多一小时写一次</b>，重启 App / 换手机后靠它兜底。</li>
 * </ul>
 *
 * ## 认亲靠 MAC
 * TLM 帧里没有 UUID/Major/Minor，规范要求靠蓝牙地址把遥测和识别帧关联起来。
 * 所以这里维护一张 MAC → beaconId 的缓存（查不到也缓存，避免每条包都打一次库）。
 * 代价：信标如果用的是会轮换的随机地址，就永远对不上，界面显示「没开 TLM」。
 */
public final class BatteryStore {

    private static final String TAG = "BatteryStore";

    /** 落库节流：同一个信标一小时内最多写一次 */
    public static final long WRITE_INTERVAL_MS = 60 * 60 * 1000L;

    /**
     * 判断电量的两档门槛（mV）。
     *
     * 纽扣电池（CR2032）满电 3.0~3.3 V，寿命大部分时间停在 2.9~3.0 V，
     * 掉到 2.9 V 以下基本就进入最后那段陡降了。所以分三档：
     * <pre>
     *   ≥ 3.00 V      充足（绿）
     *   2.90–2.99 V   一般（黄）
     *   &lt; 2.90 V      偏低（红）
     * </pre>
     */
    public static final int FULL_MV = 3000;
    public static final int MID_MV = 2900;

    /** 电压显示取最近几次的平均：单次读数会被发射脉冲的负载、温度带着抖 */
    public static final int SAMPLE_WINDOW = 3;

    /** 档位 */
    public static final int LEVEL_FULL = 3;
    public static final int LEVEL_MID = 2;
    public static final int LEVEL_LOW = 1;

    /** 一次读数（已经做过多包平均；也可能来自 GATT 直接读到的百分比） */
    public static final class Reading {
        /** 电压毫伏；-1 = 不知道 */
        public final int mv;
        /** 电量百分比 0–100；-1 = 不知道 */
        public final int pct;
        public final long at;

        Reading(int mv, int pct, long at) {
            this.mv = mv;
            this.pct = pct;
            this.at = at;
        }

        /**
         * 1=偏低 2=一般 3=充足。
         *
         * **电压优先**：标准 Battery Level（2A19）在这批信标上是假的（恒 64 = 100%，
         * 旧电池也 100%），所以只有拿不到 TLM 电压时才退回看百分比。
         */
        public int level() {
            if (mv > 0) {
                if (mv >= FULL_MV) return LEVEL_FULL;
                if (mv >= MID_MV) return LEVEL_MID;
                return LEVEL_LOW;
            }
            if (pct >= 0) {
                if (pct >= 60) return LEVEL_FULL;
                if (pct >= 30) return LEVEL_MID;
                return LEVEL_LOW;
            }
            return LEVEL_LOW;
        }

        public boolean isLow() {
            return level() == LEVEL_LOW;
        }
    }

    /** beaconId → 最新读数（内存层，不节流） */
    private static final Map<Long, Reading> LIVE = new ConcurrentHashMap<>();
    /** beaconId → 最近 N 次原始电压，用来取平均 */
    private static final Map<Long, java.util.ArrayDeque<Integer>> SAMPLES = new ConcurrentHashMap<>();
    /** MAC → beaconId，0 表示「库里没有这颗」 */
    private static final Map<String, Long> MAC_INDEX = new ConcurrentHashMap<>();
    /** 上次落库时间：beaconId → 毫秒时间戳 */
    private static final Map<Long, Long> LAST_WRITE = new ConcurrentHashMap<>();

    private BatteryStore() {}

    /**
     * 收到一条 TLM。界面立刻可见（内存），数据库按小时节流。
     *
     * 电压取最近 {@link #SAMPLE_WINDOW} 次的平均值 —— 单条 TLM 的读数会被
     * 广播瞬间的电流脉冲、环境温度带着上下跳几十毫伏，看单条容易误判档位。
     *
     * @param mac 广播来源设备的蓝牙地址
     */
    public static void record(Context ctx, String mac, TlmData tlm) {
        if (ctx == null || tlm == null || !tlm.hasVoltage()) return;   // 0 = 信标自己说不支持
        String m = mac == null ? "" : mac.trim().toUpperCase();
        if (m.isEmpty()) return;

        long now = System.currentTimeMillis();
        long beaconId = resolveId(ctx, m);
        if (beaconId <= 0) return;

        final int avgMv = pushSample(beaconId, tlm.voltageMv);
        LIVE.put(beaconId, new Reading(avgMv, -1, now));

        Long last = LAST_WRITE.get(beaconId);
        if (last != null && now - last < WRITE_INTERVAL_MS) return;   // 还没到一小时，只更内存
        LAST_WRITE.put(beaconId, now);
        try {
            new BeaconDbHelper(ctx.getApplicationContext()).saveBattery(beaconId, avgMv, now);
        } catch (Exception e) {
            Log.w(TAG, "saveBattery failed", e);
            LAST_WRITE.remove(beaconId);
        }
    }

    /** 往滑动窗口里塞一条原始读数，返回窗口内的平均值 */
    private static int pushSample(long beaconId, int mv) {
        java.util.ArrayDeque<Integer> q = SAMPLES.get(beaconId);
        if (q == null) {
            q = new java.util.ArrayDeque<>(SAMPLE_WINDOW);
            SAMPLES.put(beaconId, q);
        }
        synchronized (q) {
            q.addLast(mv);
            while (q.size() > SAMPLE_WINDOW) q.removeFirst();
            int sum = 0;
            for (int v : q) sum += v;
            return Math.round(sum / (float) q.size());
        }
    }

    /**
     * 取某颗信标的电量：优先内存里的最新读数，其次数据库里存的那份。
     *
     * @return 没有读数（没开 TLM / 还没收到过）时返回 null
     */
    public static Reading get(Context ctx, BeaconEntity beacon) {
        if (beacon == null) return null;

        Reading live = LIVE.get(beacon.getId());
        if (live != null) {
            // 拨了电池/换机之后可能很久没更新，但数据本身仍然有效 —— 时间戳交给界面去写「几小时前」
            return live;
        }
        if (beacon.hasAnyBattery()) {
            return new Reading(beacon.getBatteryMv(), beacon.getBatteryPct(), beacon.getBatteryAt());
        }
        return null;
    }

    /**
     * 用户主动「读取电量」时，从 GATT 拿到的结果。
     *
     * **只认电压，不认百分比**：标准 Battery Level（0x2A19）在这批信标上恒为 64，
     * 也就是永远「100%」，连快没电的旧电池也是 100%，存下来只会误导。
     * 真电压来自广播里的 Eddystone-TLM（{@link #record}），这条 GATT 路径是兜底。
     */
    public static void recordGatt(Context ctx, long beaconId, int pct, int mv) {
        if (beaconId <= 0) return;
        if (mv <= 0) return;   // 没有电压就当没读到，别把 100% 写进去
        long now = System.currentTimeMillis();
        LIVE.put(beaconId, new Reading(mv, -1, now));
        if (ctx == null) return;
        try {
            new BeaconDbHelper(ctx.getApplicationContext()).saveBattery(beaconId, mv, now);
        } catch (Exception e) {
            Log.w(TAG, "saveBattery failed", e);
        }
    }

    /** 这颗 MAC 对应的信标 id，查过就记住（包括查不到的结果） */
    private static long resolveId(Context ctx, String mac) {
        Long cached = MAC_INDEX.get(mac);
        if (cached != null) return cached;
        long id = 0;
        try {
            id = new BeaconDbHelper(ctx.getApplicationContext()).findIdByMac(mac);
        } catch (Exception e) {
            Log.w(TAG, "findIdByMac failed", e);
        }
        MAC_INDEX.put(mac, id);
        return id;
    }

    /** 信标被删 / 新加之后清掉缓存，免得旧映射一直指着已经不存在的行 */
    public static void invalidate() {
        MAC_INDEX.clear();
    }
}
