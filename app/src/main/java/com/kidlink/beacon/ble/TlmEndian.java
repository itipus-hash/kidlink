package com.kidlink.beacon.ble;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.util.HashMap;
import java.util.Map;

/**
 * 每颗信标的 TLM 字节序「学习结果」，按 MAC 存在本地。
 *
 * ## 为什么要记
 * {@link TlmParser} 判方向靠电压的合理性（有效区间 2700~3400 mV，用户定的现场口径）。
 * 只要电压在这个区间里，两个方向不可能同时成立，所以单帧基本判不错。但一旦电压
 * 掉出区间（电池快见底、或者偶发一条被干扰的包），单帧就成了「两个方向都不合理」，
 * 那时只能取小的那个糊过去，方向随时可能翻。同一颗信标的固件方向是固定的，
 * 所以只要有一次判得毫无歧义，就把结论按 MAC 存下来，之后不管电压多离谱都按它读，
 * 不再每帧重新猜 —— 电量数字也就不会忽高忽低。
 *
 * ## 存哪
 * 单独的 SharedPreferences（{@code tlm_endian}），key 是大写 MAC，value 是
 * {@link #VALUE_LITTLE} / {@link #VALUE_BIG}。首次访问把整张表读进内存，
 * 之后只走内存；写入时内存和磁盘一起更新，进程重启也还在。
 */
public final class TlmEndian {

    private static final String TAG = "TlmEndian";
    private static final String PREFS = "tlm_endian";
    private static final String VALUE_LITTLE = "LE";
    private static final String VALUE_BIG = "BE";

    /** MAC(大写) → 是否小端 */
    private static final Map<String, Boolean> CACHE = new HashMap<>();
    private static boolean loaded = false;

    private TlmEndian() {}

    /**
     * 这颗信标之前学到的字节序。
     *
     * @return true = 小端，false = 大端，null = 还没学到（或者缓存被判废了）
     */
    public static Boolean get(Context ctx, String mac) {
        String key = key(mac);
        if (key == null) return null;
        synchronized (CACHE) {
            if (!loaded) load(ctx);
            return CACHE.get(key);
        }
    }

    /** 记下这颗信标的方向；已经记过且一致时不重复写盘 */
    public static void remember(Context ctx, String mac, boolean little) {
        String key = key(mac);
        if (key == null) return;
        synchronized (CACHE) {
            if (!loaded) load(ctx);
            Boolean old = CACHE.get(key);
            if (old != null && old == little) return;
            CACHE.put(key, little);
            if (ctx != null) {
                prefs(ctx).edit().putString(key, little ? VALUE_LITTLE : VALUE_BIG).apply();
            }
            Log.d(TAG, "endian " + (old == null ? "learned" : "corrected") + " "
                    + key + " -> " + (little ? "LE" : "BE"));
        }
    }

    /** 忘掉一颗信标（换固件/换设备时手动清，暂不接界面） */
    public static void forget(Context ctx, String mac) {
        String key = key(mac);
        if (key == null) return;
        synchronized (CACHE) {
            CACHE.remove(key);
            if (ctx != null) prefs(ctx).edit().remove(key).apply();
        }
    }

    private static void load(Context ctx) {
        loaded = true;
        if (ctx == null) return;
        try {
            Map<String, ?> all = prefs(ctx).getAll();
            for (Map.Entry<String, ?> e : all.entrySet()) {
                Object v = e.getValue();
                if (v instanceof String) CACHE.put(e.getKey(), VALUE_LITTLE.equals(v));
            }
        } catch (Exception e) {
            Log.w(TAG, "load failed", e);
        }
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String key(String mac) {
        if (mac == null) return null;
        String k = mac.trim().toUpperCase(java.util.Locale.US);
        return k.isEmpty() ? null : k;
    }
}
