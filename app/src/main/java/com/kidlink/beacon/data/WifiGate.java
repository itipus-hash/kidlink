package com.kidlink.beacon.data;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;

import java.util.List;

/**
 * 「指定 WiFi 不推送」的判定入口。
 *
 * 用途：家里 / 公司这类「人在那儿，但本来就不需要提醒」的地方，
 * 把那里的 WiFi 名称加进名单，连上时就不推任何提醒 —— 比调距离更准，
 * 因为距离只能表达远近，表达不了「我在哪儿」。
 *
 * ## 为什么读取 SSID 这么别扭
 * Android 10 起 `WifiInfo.getSSID()` 属于位置信息：必须同时满足
 * 1. 应用拿到 `ACCESS_FINE_LOCATION`（本项目为了 BLE 扫描本来就申请了）；
 * 2. 系统的「位置信息」总开关是打开的。
 * 只要缺一条，返回的就是 `<unknown ssid>` 这种占位串，不是真的名字。
 * 所以这里统一把占位串当「读不到」处理，并把「为什么读不到」的原因
 * 交给设置页去提示（见 {@link #canReadSsid} / {@link #isLocationEnabled}）。
 *
 * 另外 `getConnectionInfo()` 走的是 binder，开销不算零，所以只在「真要推送之前」
 * 调一次，不要放进每秒都在跑的渲染逻辑里。
 */
public final class WifiGate {

    private WifiGate() {}

    /**
     * 当前连着的 WiFi 名称（已去掉系统加的双引号）。
     * 没连 WiFi / 没有权限 / 读不到，都返回 null —— 调用方按「不屏蔽」处理，
     * 宁可多推一条，也不要因为读不到名字把所有推送都堵死。
     */
    public static String currentSsid(Context ctx) {
        if (ctx == null) return null;
        Context app = ctx.getApplicationContext();

        String ssid = null;
        try {
            WifiManager wm = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
            if (wm != null && wm.isWifiEnabled()) {
                ssid = normalize(wm.getConnectionInfo());
            }
        } catch (Exception ignored) {
        }
        // WiFi 关着 / 老接口拿不到时，再从当前活动网络的能力里捞一次
        if (ssid == null) ssid = fromActiveNetwork(app);
        return ssid;
    }

    /** 当前 WiFi 是否在「不推送」名单里 */
    public static boolean blocked(Context ctx) {
        if (ctx == null) return false;
        List<String> list = BeaconPrefs.getWifiBlockList(ctx);
        if (list.isEmpty()) return false;
        String ssid = currentSsid(ctx);
        if (ssid == null) return false;
        return BeaconPrefs.isWifiBlocked(ctx, ssid);
    }

    /** 有没有「位置信息」权限 —— 没有就一定读不到 SSID */
    public static boolean canReadSsid(Context ctx) {
        if (ctx == null) return false;
        try {
            return ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED;
        } catch (Exception e) {
            return false;
        }
    }

    /** 系统「位置信息」总开关是否打开 —— 关着的话有权限也读不到 SSID */
    public static boolean isLocationEnabled(Context ctx) {
        if (ctx == null) return false;
        try {
            LocationManager lm =
                    (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
            return lm != null && lm.isLocationEnabled();
        } catch (Exception e) {
            return false;
        }
    }

    private static String fromActiveNetwork(Context app) {
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return null;
            Network network = cm.getActiveNetwork();
            if (network == null) return null;
            NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            if (caps == null
                    || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                return null;
            }
            android.net.TransportInfo info = caps.getTransportInfo();
            if (info instanceof WifiInfo) return normalize((WifiInfo) info);
        } catch (Exception ignored) {
        }
        return null;
    }

    /** 去掉系统包上去的双引号；占位串（{@code <unknown ssid>}）当成 null */
    private static String normalize(WifiInfo info) {
        if (info == null) return null;
        String raw;
        try {
            raw = info.getSSID();
        } catch (Exception e) {
            return null;
        }
        if (raw == null) return null;
        raw = raw.trim();
        if (raw.length() >= 2 && raw.startsWith("\"") && raw.endsWith("\"")) {
            raw = raw.substring(1, raw.length() - 1).trim();
        }
        if (raw.isEmpty() || raw.startsWith("<")) return null;
        return raw;
    }
}
