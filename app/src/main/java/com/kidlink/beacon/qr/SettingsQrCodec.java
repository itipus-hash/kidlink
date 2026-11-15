package com.kidlink.beacon.qr;

import android.content.Context;
import android.util.Base64;

import com.kidlink.beacon.data.BeaconPrefs;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;

/**
 * 设置项二维码编解码
 *
 * 格式：KIDLINK:cfg1:<Base64(JSON)>
 *
 * 打包的是设置页里除了「接娃人」以外的全部配置：
 * PushPlus 通道列表（不含开关状态）、推送冷却、接送成功推送距离、近距离推送距离、图标颜色超时时间、
 * 图标变色触发距离、震动时长、悬浮提醒触发距离。
 *
 * 接娃人是「这台手机的持有人」的身份，换机同步没意义，故意不带。
 * 通道的开关也是：哪条通道在这台手机上开不开，是这台手机自己的选择，
 * 同步过去会把对方关掉的通道又打开 —— 所以只带通道本身，不带开关。
 */
public class SettingsQrCodec {

    public static final String PREFIX = "KIDLINK:cfg1:";

    /**
     * 配置格式版本。字段增删都要 +1 —— 收到不同版本的码直接拒绝，
     * 免得把对方的设置按自己的理解硬塞进来。
     * v8：推送通道不再带开关状态（开关是每台手机自己的事，同步过去会覆盖错）
     */
    public static final int VERSION = 8;

    private SettingsQrCodec() {}

    /** 把当前所有设置编码成二维码内容 */
    public static String encode(Context ctx) {
        try {
            JSONObject json = new JSONObject();
            json.put("v", VERSION);
            json.put("pt", encodeTokens(ctx));
            json.put("pc", BeaconPrefs.getPushCooldownMin(ctx));
            json.put("pd", BeaconPrefs.getPushMeters(ctx));
            json.put("am", BeaconPrefs.getApproachMeters(ctx));
            json.put("tg", encodeTimeGroups(ctx));
            json.put("di", BeaconPrefs.getDetectIntervalSec(ctx));
            json.put("nm", BeaconPrefs.getNearMeters(ctx));
            json.put("vs", BeaconPrefs.getVibrateNearSec(ctx));
            json.put("ft", BeaconPrefs.getFrontTipMeters(ctx));

            byte[] raw = json.toString().getBytes(StandardCharsets.UTF_8);
            return PREFIX + Base64.encodeToString(raw, Base64.NO_WRAP | Base64.URL_SAFE);
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean isValid(String value) {
        return value != null && value.trim().startsWith(PREFIX);
    }

    /**
     * 只读出码里的版本号，不写入任何东西。
     * @return 版本号；不是配置码或解不开时返回 -1
     */
    public static int versionOf(String value) {
        if (!isValid(value)) return -1;
        try {
            JSONObject json = decodeJson(value);
            if (json == null) return -1;
            return json.optInt("v", -1);
        } catch (Exception e) {
            return -1;
        }
    }

    /** Base64 段解回 JSON；扫码/复制偶尔会丢结尾的 '='，先补齐 */
    private static JSONObject decodeJson(String value) throws Exception {
        String b64 = value.trim().substring(PREFIX.length()).trim();
        while (b64.length() % 4 != 0) b64 = b64 + "=";
        byte[] raw = Base64.decode(b64, Base64.NO_WRAP | Base64.URL_SAFE);
        return new JSONObject(new String(raw, StandardCharsets.UTF_8));
    }

    /**
     * 多条通道编成一个数组，二维码体积可控（每条大概 50 字节）。
     * 只带名字和 token —— 开关状态不带，见类头注释。
     */
    private static org.json.JSONArray encodeTokens(Context ctx) {
        org.json.JSONArray arr = new org.json.JSONArray();
        for (com.kidlink.beacon.data.PushToken t : BeaconPrefs.getPushTokens(ctx)) {
            try {
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("n", t.name);
                o.put("t", t.token);
                arr.put(o);
            } catch (Exception ignored) {
            }
        }
        return arr;
    }

    /**
     * 把码里的通道写进本机。
     *
     * 按 token 认通道：本机已有的保留它自己的开关状态（码里没有这个信息，
     * 也轮不到码来决定）；新出现的通道默认打开。所以扫一次码不会把对方
     * 关掉的通道在本机也关掉，本机特意关掉的也不会被偷偷打开。
     */
    private static void applyTokens(Context ctx, org.json.JSONArray arr) {
        if (arr == null) return;
        java.util.List<com.kidlink.beacon.data.PushToken> local = BeaconPrefs.getPushTokens(ctx);
        java.util.List<com.kidlink.beacon.data.PushToken> list = new java.util.ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            org.json.JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String token = o.optString("t", "").trim();
            if (token.isEmpty()) continue;   // token 空白的直接丢掉，不留空壳
            // 本机已有的通道：原样保留（含开关状态），码里的名字也不覆盖
            com.kidlink.beacon.data.PushToken kept = findByToken(local, token);
            if (kept != null) {
                list.add(kept);
                continue;
            }
            String name = o.optString("n", "").trim();
            if (name.isEmpty()) name = "通道 " + (list.size() + 1);
            list.add(new com.kidlink.beacon.data.PushToken(name, token, true));
        }
        BeaconPrefs.setPushTokens(ctx, list);
    }

    private static com.kidlink.beacon.data.PushToken findByToken(
            java.util.List<com.kidlink.beacon.data.PushToken> list, String token) {
        for (com.kidlink.beacon.data.PushToken t : list) {
            if (t.token.equals(token)) return t;
        }
        return null;
    }

    /**
     * 时间段分组编成数组：n 名称 / d 星期 / r 时间段 / c 归属娃的名字。
     *
     * 时间段本身还留在设置码里，是为了「只同步设置」的人也能把作息带走；
     * 但它现在挂在娃下面，所以额外带一个娃名 —— 对方手机上有同名娃就归过去，
     * 没有就先当「共享」放着（不至于丢）。
     */
    private static org.json.JSONArray encodeTimeGroups(Context ctx) {
        org.json.JSONArray arr = new org.json.JSONArray();
        java.util.Map<Long, String> names = new java.util.HashMap<>();
        for (com.kidlink.beacon.data.ChildEntity c
                : new com.kidlink.beacon.data.BeaconDbHelper(ctx).getAllChildren()) {
            names.put(c.getId(), c.getName());
        }
        for (com.kidlink.beacon.data.TimeGroup g : BeaconPrefs.getTimeGroups(ctx)) {
            try {
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("n", g.name);
                o.put("d", g.days);
                o.put("r", g.ranges);
                String owner = names.get(g.childId);
                if (owner != null && !owner.isEmpty()) o.put("c", owner);
                arr.put(o);
            } catch (Exception ignored) {
            }
        }
        return arr;
    }

    private static void applyTimeGroups(Context ctx, org.json.JSONArray arr) {
        if (arr == null) return;
        java.util.Map<String, Long> ids = new java.util.HashMap<>();
        for (com.kidlink.beacon.data.ChildEntity c
                : new com.kidlink.beacon.data.BeaconDbHelper(ctx).getAllChildren()) {
            ids.put(c.getName(), c.getId());
        }
        java.util.List<com.kidlink.beacon.data.TimeGroup> list = new java.util.ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            org.json.JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            com.kidlink.beacon.data.TimeGroup g = new com.kidlink.beacon.data.TimeGroup(
                    o.optString("n", ""),
                    o.optString("d", ""),
                    o.optString("r", ""));
            // 名称和时间都空的条目没意义，不写进去
            if (g.name.isEmpty() && g.ranges.isEmpty()) continue;
            Long owner = ids.get(o.optString("c", ""));
            g.childId = owner == null ? 0 : owner;
            list.add(g);
        }
        BeaconPrefs.setTimeGroups(ctx, list);
    }

    /**
     * 把扫到的配置写进本机。
     * @return true 写入成功
     */
    public static boolean apply(Context ctx, String value) {
        if (!isValid(value)) return false;
        try {
            JSONObject json = decodeJson(value);
            if (json == null) return false;
            // 版本对不上一律不写，字段含义可能已经变了
            if (json.optInt("v", -1) != VERSION) return false;

            if (json.has("pt")) applyTokens(ctx, json.optJSONArray("pt"));
            if (json.has("pc")) BeaconPrefs.setPushCooldownMin(ctx, json.optInt("pc", 120));
            if (json.has("pd")) BeaconPrefs.setPushMeters(ctx, json.optInt("pd", 2));
            if (json.has("am")) BeaconPrefs.setApproachMeters(ctx, json.optInt("am", 0));
            if (json.has("tg")) applyTimeGroups(ctx, json.optJSONArray("tg"));
            if (json.has("di")) BeaconPrefs.setDetectIntervalSec(ctx, json.optInt("di", 15));
            if (json.has("nm")) BeaconPrefs.setNearMeters(ctx, json.optInt("nm", 5));
            if (json.has("vs")) BeaconPrefs.setVibrateNearSec(ctx, json.optInt("vs", 5));
            if (json.has("ft")) BeaconPrefs.setFrontTipMeters(ctx, json.optInt("ft", 10));

            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
