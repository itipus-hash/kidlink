package com.kidlink.beacon.data;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 轻量偏好存储：记住当前选中的信标
 */
public class BeaconPrefs {

    private static final String PREF_NAME = "kidlink_prefs";
    private static final String KEY_CURRENT_ID = "current_beacon_id";
    private static final String KEY_SELECTED_IDS = "selected_ids";
    /** 超过这么多秒没收到广播，图标变回灰色 */
    private static final String KEY_DETECT_INTERVAL_SEC = "detect_interval_sec";
    /** 进入这个距离以内，图标才变绿 */
    private static final String KEY_NEAR_METERS = "near_meters";

    public static final int DEFAULT_DETECT_INTERVAL_SEC = 15;
    public static final int DEFAULT_NEAR_METERS = 5;

    private static final String KEY_PICKER_NAME = "picker_name";
    /** 多条 PushPlus 推送通道，存 JSON 数组 */
    private static final String KEY_PUSH_TOKENS = "push_tokens";
    /** 上一次「接送成功」推送的时间，按信标分开记 */
    private static final String KEY_LAST_PUSH_AT = "last_push_at";
    /**
     * 上一次「近距离推送」的时间，按信标分开记。
     *
     * 必须和 KEY_LAST_PUSH_AT 分开：近距离推送在 50 米就发生，接送成功在 2 米才发生，
     * 共用一个时间戳的话，要么靠近那条把「接到」的冷却吃掉，要么靠近自己永远没冷却
     * （1.4.34 之前就是后者 —— 实测 3 分钟连推 3 条）。
     */
    private static final String KEY_LAST_APPROACH_AT = "last_approach_at";
    /** 最近一次任意类型推送的时间，近距离 / 接送成功都算，用于全局防抖 */
    private static final String KEY_LAST_ANY_PUSH_AT = "last_any_push_at";
    /**
     * 「当前是否已经在接近区 / 到达区内」，按信标分开记。
     *
     * 这两条推送都必须是**进入事件**，不是「距离现在还小」这个状态：
     * 小朋友回到家、信标就在桌上，距离一直 3 米 —— 冷却时间一到又推一条「已靠近」，
     * 纯属打扰。所以进区时落一个持久标记，只有**真的走远**（超过阈值 + 迟滞）
     * 才把这个标记清掉，下回再进区才推。
     *
     * 存在 prefs 而不是内存：APP 接完人会自己退出，重启后内存标志就丢了，
     * 一打开又推一条。
     */
    private static final String KEY_APPROACH_INSIDE = "approach_inside";
    private static final String KEY_ARRIVAL_INSIDE = "arrival_inside";
    private static final String KEY_PUSH_COOLDOWN_MIN = "push_cooldown_min";

    /** 图标变色时震动的时长（秒），0 = 关闭 */
    private static final String KEY_VIBRATE_NEAR_SEC = "vibrate_near_sec";
    /** 推送距离（米） */
    private static final String KEY_PUSH_METERS = "push_meters";
    /** 接近提醒的距离（米），0 = 关闭 */
    private static final String KEY_APPROACH_METERS = "approach_meters";
    /** 上学 / 放学这种自定义时间段分组，存 JSON 数组 */
    private static final String KEY_TIME_GROUPS = "time_groups";
    /**
     * 时间段分组的语义版本。
     * v1：days 为空 = 每天都算（1.4.21 及更早）
     * v2：days 为空 = 这几天不推送，新建时默认全选（1.4.22 起）
     * 升级时把 v1 的空 days 补成全选，免得老配置突然就不推了。
     */
    private static final String KEY_TIME_GROUPS_VER = "time_groups_ver";
    private static final int TIME_GROUPS_VER = 2;
    /** 旧版本遗留的两个固定键，只在首次读取时做一次性迁移 */
    private static final String KEY_SCHOOL_RANGES_OLD = "school_ranges";
    private static final String KEY_OFF_RANGES_OLD = "off_school_ranges";
    /** 悬浮提醒触发距离（米），0 = 关闭 */
    private static final String KEY_FRONT_TIP_METERS = "front_tip_meters";
    /** 接送成功推送成功后，提示条走完就退出 APP */
    private static final String KEY_EXIT_AFTER_ARRIVAL = "exit_after_arrival";
    /** 冷启动后自动退到后台（默认关） */
    private static final String KEY_AUTO_MINIMIZE = "auto_minimize";

    private BeaconPrefs() {}

    /** 最多允许同时选中几个信标 */
    public static final int MAX_SELECTED = 3;

    private static SharedPreferences sp(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    /**
     * 当前选中的信标 id，最多三个。
     * 旧版本只存了一个 current_beacon_id，这里做一次性兼容读取。
     */
    public static java.util.List<Long> getSelectedIds(Context ctx) {
        java.util.List<Long> ids = new java.util.ArrayList<>();
        String raw = sp(ctx).getString(KEY_SELECTED_IDS, "");
        if (raw != null && !raw.trim().isEmpty()) {
            for (String part : raw.split(",")) {
                try {
                    long id = Long.parseLong(part.trim());
                    if (id > 0) ids.add(id);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        // 只有在从未写过新键时才回退旧值，否则"取消全部选中"会被旧值复活
        if (ids.isEmpty() && !sp(ctx).contains(KEY_SELECTED_IDS)) {
            long legacy = sp(ctx).getLong(KEY_CURRENT_ID, -1L);
            if (legacy > 0) ids.add(legacy);
        }
        return ids;
    }

    public static void setSelectedIds(Context ctx, java.util.List<Long> ids) {
        StringBuilder sb = new StringBuilder();
        if (ids != null) {
            for (Long id : ids) {
                if (id == null || id <= 0) continue;
                if (sb.length() > 0) sb.append(",");
                sb.append(id);
            }
        }
        sp(ctx).edit().putString(KEY_SELECTED_IDS, sb.toString()).apply();
    }

    public static boolean isSelected(Context ctx, long id) {
        return getSelectedIds(ctx).contains(id);
    }

    /**
     * 点一下切换选中状态。
     * 规则：最多三个，最少一个（不允许把最后一个也取消掉）。
     */
    public static final int TOGGLE_OK = 0;
    /** 已经选满，不能再多选 */
    public static final int TOGGLE_LIMIT = 1;
    /** 这是最后一个选中的信标，不能取消 */
    public static final int TOGGLE_KEEP_ONE = 2;

    public static int toggleSelected(Context ctx, long id) {
        java.util.List<Long> ids = getSelectedIds(ctx);
        if (ids.contains(id)) {
            if (ids.size() <= 1) return TOGGLE_KEEP_ONE;
            ids.remove(Long.valueOf(id));
            setSelectedIds(ctx, ids);
            return TOGGLE_OK;
        }
        if (ids.size() >= MAX_SELECTED) return TOGGLE_LIMIT;
        ids.add(id);
        setSelectedIds(ctx, ids);
        return TOGGLE_OK;
    }

    /** 兼容旧调用：返回第一个选中的信标 id，没有则 -1 */
    public static long getCurrentBeaconId(Context ctx) {
        java.util.List<Long> ids = getSelectedIds(ctx);
        return ids.isEmpty() ? -1L : ids.get(0);
    }

    /** 兼容旧调用：直接用这一个替换掉整个选择 */
    public static void setCurrentBeaconId(Context ctx, long id) {
        java.util.List<Long> ids = new java.util.ArrayList<>();
        if (id > 0) ids.add(id);
        setSelectedIds(ctx, ids);
    }

    public static void clearCurrentBeaconId(Context ctx) {
        sp(ctx).edit().remove(KEY_SELECTED_IDS).remove(KEY_CURRENT_ID).apply();
    }

    /** 丢掉已经不存在（被删掉）的信标 id */
    public static java.util.List<Long> pruneSelected(Context ctx, BeaconDbHelper db) {
        java.util.List<Long> valid = new java.util.ArrayList<>();
        for (Long id : getSelectedIds(ctx)) {
            if (db.getBeaconById(id) != null) valid.add(id);
        }
        setSelectedIds(ctx, valid);
        return valid;
    }

    /**
     * 保证选择有效：选中的都失效了（或从未选择），且库里只有一个信标
     * → 自动选中它（第一次添加完自动选中）。
     *
     * 因为「至少保留一个」的策略，用户不可能把选中清空，
     * 所以这个兜底只在信标被删光后重新添加时起作用。
     */
    public static void ensureAutoSelect(Context ctx, BeaconDbHelper db) {
        java.util.List<Long> valid = pruneSelected(ctx, db);
        if (!valid.isEmpty()) return;
        java.util.List<BeaconEntity> all = db.getAllBeacons();
        if (all.size() == 1) {
            java.util.List<Long> one = new java.util.ArrayList<>();
            one.add(all.get(0).getId());
            setSelectedIds(ctx, one);
        }
    }

    // === 检测设置 ===

    public static int getDetectIntervalSec(Context ctx) {
        return ctx.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getInt(KEY_DETECT_INTERVAL_SEC, DEFAULT_DETECT_INTERVAL_SEC);
    }

    public static void setDetectIntervalSec(Context ctx, int sec) {
        ctx.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit().putInt(KEY_DETECT_INTERVAL_SEC, sec).apply();
    }

    /** 图标变绿的近距离门槛（米） */
    public static int getNearMeters(Context ctx) {
        return ctx.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getInt(KEY_NEAR_METERS, DEFAULT_NEAR_METERS);
    }

    public static void setNearMeters(Context ctx, int meters) {
        ctx.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit().putInt(KEY_NEAR_METERS, meters).apply();
    }

    // === 接娃人 / 推送 ===

    /** 接娃人名字。没设过时用手机蓝牙名，取不到就用机型名。 */
    public static String getPickerName(Context ctx) {
        String saved = ctx.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getString(KEY_PICKER_NAME, "");
        if (saved != null && !saved.trim().isEmpty()) return saved.trim();

        String fallback = "";
        try {
            android.bluetooth.BluetoothManager bm = (android.bluetooth.BluetoothManager)
                    ctx.getApplicationContext().getSystemService(Context.BLUETOOTH_SERVICE);
            if (bm != null && bm.getAdapter() != null) {
                fallback = bm.getAdapter().getName();
            }
        } catch (Exception ignored) {
        }
        if (fallback == null || fallback.trim().isEmpty()) {
            fallback = android.os.Build.MODEL;
        }
        return fallback == null ? "" : fallback.trim();
    }

    public static void setPickerName(Context ctx, String name) {
        ctx.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_PICKER_NAME, name == null ? "" : name.trim()).apply();
    }

    // === 多条 PushPlus 通道 ===

    /** 全部通道（含已关闭的），顺序就是设置页里的显示顺序 */
    public static java.util.List<PushToken> getPushTokens(Context ctx) {
        String raw = sp(ctx).getString(KEY_PUSH_TOKENS, "");
        java.util.List<PushToken> list = new java.util.ArrayList<>();
        if (raw == null || raw.trim().isEmpty()) return list;
        try {
            org.json.JSONArray arr = new org.json.JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                PushToken t = new PushToken(
                        o.optString("n", ""),
                        o.optString("t", ""),
                        o.optInt("e", 1) == 1);
                if (t.isValid()) list.add(t);
            }
        } catch (Exception ignored) {
        }
        return list;
    }

    public static void setPushTokens(Context ctx, java.util.List<PushToken> list) {
        org.json.JSONArray arr = new org.json.JSONArray();
        if (list != null) {
            for (PushToken t : list) {
                if (t == null || !t.isValid()) continue;
                try {
                    org.json.JSONObject o = new org.json.JSONObject();
                    o.put("n", t.name == null ? "" : t.name);
                    o.put("t", t.token);
                    o.put("e", t.enabled ? 1 : 0);
                    arr.put(o);
                } catch (Exception ignored) {
                }
            }
        }
        sp(ctx).edit().putString(KEY_PUSH_TOKENS, arr.toString()).apply();
    }

    /** 只取开着的那几条 —— 推送时用这个 */
    public static java.util.List<PushToken> getEnabledPushTokens(Context ctx) {
        java.util.List<PushToken> on = new java.util.ArrayList<>();
        for (PushToken t : getPushTokens(ctx)) {
            if (t.enabled) on.add(t);
        }
        return on;
    }

    /** 开着的通道的 token 列表，直接喂给 PushClient */
    public static java.util.List<String> getEnabledTokenValues(Context ctx) {
        java.util.List<String> values = new java.util.ArrayList<>();
        for (PushToken t : getEnabledPushTokens(ctx)) values.add(t.token);
        return values;
    }

    /**
     * 上一次成功推送的时间，按信标分开记 —— 两个信标各推各的，
     * 一个推过不该把另一个的冷却也吃掉。
     */
    public static long getLastPushAt(Context ctx, long beaconId) {
        return ctx.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getLong(KEY_LAST_PUSH_AT + "_" + beaconId, 0L);
    }

    /** 推送冷却（分钟），默认 120 = 2 小时 */
    public static int getPushCooldownMin(Context ctx) {
        return ctx.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getInt(KEY_PUSH_COOLDOWN_MIN, 120);
    }

    public static void setPushCooldownMin(Context ctx, int minutes) {
        ctx.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit().putInt(KEY_PUSH_COOLDOWN_MIN, minutes).apply();
    }

    public static void setLastPushAt(Context ctx, long beaconId, long at) {
        ctx.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit().putLong(KEY_LAST_PUSH_AT + "_" + beaconId, at).apply();
    }

    /** 上一次「近距离推送」的时间，0 = 从没推过；按信标分开记 */
    public static long getLastApproachAt(Context ctx, long beaconId) {
        return ctx.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getLong(KEY_LAST_APPROACH_AT + "_" + beaconId, 0L);
    }

    public static void setLastApproachAt(Context ctx, long beaconId, long at) {
        ctx.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit().putLong(KEY_LAST_APPROACH_AT + "_" + beaconId, at).apply();
    }

    /** 最近一次任意类型推送的时间（近距离 / 接送成功都算），用于全局防抖 */
    public static long getLastAnyPushAt(Context ctx) {
        return ctx.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getLong(KEY_LAST_ANY_PUSH_AT, 0L);
    }

    public static void setLastAnyPushAt(Context ctx, long at) {
        ctx.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit().putLong(KEY_LAST_ANY_PUSH_AT, at).apply();
    }

    /**
     * 把一个信标的推送冷却清零（信标信息页的「清除冷却」）。
     *
     * 冷却时间戳和「已在区内」标记一起清：清完冷却如果还认为「人已经在旁边」，
     * 站在信标边上也不会推，用户会以为按钮坏了。
     */
    public static void clearCooldown(Context ctx, long beaconId) {
        ctx.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_LAST_PUSH_AT + "_" + beaconId)
                .remove(KEY_LAST_APPROACH_AT + "_" + beaconId)
                .remove(KEY_APPROACH_INSIDE + "_" + beaconId)
                .remove(KEY_ARRIVAL_INSIDE + "_" + beaconId)
                .apply();
    }

    /** 这个信标上一次已知是不是已经在接近区内（跨重启保留） */
    public static boolean isApproachInside(Context ctx, long beaconId) {
        return ctx.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_APPROACH_INSIDE + "_" + beaconId, false);
    }

    public static void setApproachInside(Context ctx, long beaconId, boolean inside) {
        ctx.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_APPROACH_INSIDE + "_" + beaconId, inside).apply();
    }

    /** 这个信标上一次已知是不是已经在到达区内（跨重启保留） */
    public static boolean isArrivalInside(Context ctx, long beaconId) {
        return ctx.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_ARRIVAL_INSIDE + "_" + beaconId, false);
    }

    public static void setArrivalInside(Context ctx, long beaconId, boolean inside) {
        ctx.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_ARRIVAL_INSIDE + "_" + beaconId, inside).apply();
    }

    // === 后台扫描 / 到达提醒 ===
    //
    // 后台运行和扫描是固定开启的，没有开关（前台服务常驻），
    // 下面这几项才是可调的。

    private static final int DEFAULT_VIBRATE_SEC = 5;
    private static final int DEFAULT_PUSH_METERS = 2;

    /** 图标变色时震动时长（秒），0 = 关闭。可选 0 / 5 / 10 / 15 */
    public static int getVibrateNearSec(Context ctx) {
        return ctx.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getInt(KEY_VIBRATE_NEAR_SEC, DEFAULT_VIBRATE_SEC);
    }

    public static void setVibrateNearSec(Context ctx, int seconds) {
        ctx.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit().putInt(KEY_VIBRATE_NEAR_SEC, Math.max(0, seconds)).apply();
    }

    /** 推送距离（米），默认 2 */
    public static int getPushMeters(Context ctx) {
        return ctx.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getInt(KEY_PUSH_METERS, DEFAULT_PUSH_METERS);
    }

    public static void setPushMeters(Context ctx, int meters) {
        ctx.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit().putInt(KEY_PUSH_METERS, Math.max(1, meters)).apply();
    }

    /** 接近提醒距离（米），0 = 关闭；默认关，要用得自己填 */
    public static int getApproachMeters(Context ctx) {
        return ctx.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getInt(KEY_APPROACH_METERS, 0);
    }

    public static void setApproachMeters(Context ctx, int meters) {
        ctx.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit().putInt(KEY_APPROACH_METERS, Math.max(0, meters)).apply();
    }

    /**
     * 全部时间段分组（上学 / 放学 / 用户自加的），顺序就是设置页里的显示顺序。
     *
     * 第一次读的时候如果这个键还不存在，会把旧版本那两个固定的上学 / 放学时间段
     * 迁成两个分组 —— 一次性搬运，之后代码不再认旧键。
     *
     * 语义版本 1 → 2 的迁移也在这里：老版本「days 为空 = 每天」，
     * 新版本「days 为空 = 不推送」，所以升级时把空 days 补成全选，否则老配置会静默失效。
     */
    public static java.util.List<TimeGroup> getTimeGroups(Context ctx) {
        if (!sp(ctx).contains(KEY_TIME_GROUPS)) {
            java.util.List<TimeGroup> migrated = new java.util.ArrayList<>();
            String school = sp(ctx).getString(KEY_SCHOOL_RANGES_OLD, "");
            if (school != null && !school.trim().isEmpty()) {
                migrated.add(new TimeGroup("上学", TimeGroup.ALL_DAYS, school.trim()));
            }
            String off = sp(ctx).getString(KEY_OFF_RANGES_OLD, "");
            if (off != null && !off.trim().isEmpty()) {
                migrated.add(new TimeGroup("放学", TimeGroup.ALL_DAYS, off.trim()));
            }
            setTimeGroups(ctx, migrated);
            sp(ctx).edit().putInt(KEY_TIME_GROUPS_VER, TIME_GROUPS_VER).apply();
            return migrated;
        }

        java.util.List<TimeGroup> list = new java.util.ArrayList<>();
        String raw = sp(ctx).getString(KEY_TIME_GROUPS, "");
        if (raw != null && !raw.trim().isEmpty()) {
            try {
                org.json.JSONArray arr = new org.json.JSONArray(raw);
                for (int i = 0; i < arr.length(); i++) {
                    org.json.JSONObject o = arr.optJSONObject(i);
                    if (o == null) continue;
                    list.add(new TimeGroup(
                            o.optString("n", ""),
                            o.optString("d", ""),
                            o.optString("r", "")));
                }
            } catch (Exception ignored) {
            }
        }

        // v1 → v2：空 days 曾经表示「每天」，现在表示「不推送」，补成全选
        if (sp(ctx).getInt(KEY_TIME_GROUPS_VER, 1) < TIME_GROUPS_VER) {
            boolean changed = false;
            for (TimeGroup g : list) {
                if (g.days == null || g.days.trim().isEmpty()) {
                    g.days = TimeGroup.ALL_DAYS;
                    changed = true;
                }
            }
            if (changed) setTimeGroups(ctx, list);
            sp(ctx).edit().putInt(KEY_TIME_GROUPS_VER, TIME_GROUPS_VER).apply();
        }
        return list;
    }

    public static void setTimeGroups(Context ctx, java.util.List<TimeGroup> list) {
        org.json.JSONArray arr = new org.json.JSONArray();
        if (list != null) {
            for (TimeGroup g : list) {
                if (g == null) continue;
                try {
                    org.json.JSONObject o = new org.json.JSONObject();
                    o.put("n", g.name == null ? "" : g.name);
                    o.put("d", g.days == null ? "" : g.days);
                    o.put("r", g.ranges == null ? "" : g.ranges);
                    arr.put(o);
                } catch (Exception ignored) {
                }
            }
        }
        sp(ctx).edit().putString(KEY_TIME_GROUPS, arr.toString()).apply();
    }

    /** 悬浮提醒触发距离（米），0 = 关闭；默认 10 米 */
    public static int getFrontTipMeters(Context ctx) {
        return ctx.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getInt(KEY_FRONT_TIP_METERS, 10);
    }

    public static void setFrontTipMeters(Context ctx, int meters) {
        ctx.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit().putInt(KEY_FRONT_TIP_METERS, Math.max(0, meters)).apply();
    }

    /** 接送成功推送完成后，提示走完就退出 APP；默认开 */
    public static boolean isExitAfterArrival(Context ctx) {
        return sp(ctx).getBoolean(KEY_EXIT_AFTER_ARRIVAL, true);
    }

    public static void setExitAfterArrival(Context ctx, boolean on) {
        sp(ctx).edit().putBoolean(KEY_EXIT_AFTER_ARRIVAL, on).apply();
    }

    /**
     * 打开 APP 后自动最小化：冷启动直接退到后台，界面不停留。
     * 默认关 —— 这是给「只想让它在后台跑、不想看界面」的用法准备的。
     */
    public static boolean isAutoMinimize(Context ctx) {
        return sp(ctx).getBoolean(KEY_AUTO_MINIMIZE, false);
    }

    public static void setAutoMinimize(Context ctx, boolean on) {
        sp(ctx).edit().putBoolean(KEY_AUTO_MINIMIZE, on).apply();
    }
}
