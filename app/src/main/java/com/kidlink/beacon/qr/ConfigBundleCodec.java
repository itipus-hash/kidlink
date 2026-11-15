package com.kidlink.beacon.qr;

import android.util.Base64;
import android.util.Log;

import com.kidlink.beacon.data.BeaconDbHelper;
import com.kidlink.beacon.data.BeaconEntity;
import com.kidlink.beacon.data.ChildEntity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 整套配置（娃 + 信标）的二维码编解码。
 *
 * 和 {@link BeaconQrCodec} 的区别：那个是「一颗信标一张码」，这里是「一次带走全部」——
 * 换手机、给家里人配一台，不用一颗颗扫。
 *
 * ## 格式
 * 载荷是一整块 JSON（娃名数组 + 信标数组）→ UTF-8 → Base64(URL_SAFE)。
 * 这一整串再按固定长度切成若干页，每页一张码：
 * <pre>
 *   KIDLINK:b1:&lt;id&gt;:&lt;i&gt;/&lt;n&gt;:&lt;这段 Base64&gt;
 * </pre>
 * - id：整串 Base64 的哈希后缀，用来判断「这几页是不是同一套配置」
 * - i/n：第几页 / 共几页
 *
 * 只有一页时照样是 {@code 1/1} 的形式 —— 扫码端不用分两种格式处理。
 *
 * ## 为什么要分页
 * 二维码版本越高格子越密，单张塞几千字符，手机屏幕上的模块只有两三个像素，
 * 另一个手机基本扫不动。切成 400 多字符一页，单页版本低、格子大，屏幕亮度够就能扫；
 * 代价是得轮播，所以展示端按页自动切换，扫码端把收到的页攒起来，
 * 攒齐了再一次性导入（见 {@link #collect(String)}）。
 */
public final class ConfigBundleCodec {

    private static final String TAG = "ConfigBundleCodec";

    /** 分页码的固定前缀，扫码端靠它认「这是整套配置」 */
    public static final String PREFIX = "KIDLINK:b1:";

    /** 载荷格式版本。字段增删都要 +1，扫码端对不上就整包拒收 */
    public static final int VERSION = 1;

    /**
     * 一页装多少字符。
     * 420 个字符在 M 级纠错下大约是版本 16 左右，600px 的图上每格还有 5px 上下，
     * 对着屏幕扫够用；再大就开始扫不动了。
     */
    private static final int CHUNK = 420;

    /** 分页拼装缓存：id → 各页内容（null 表示还没收到） */
    private static final Map<String, String[]> PENDING = new HashMap<>();

    private ConfigBundleCodec() {}

    // ==================== 编码 ====================

    /**
     * 把库里所有娃和信标编码成若干页二维码内容。
     *
     * @return 至少一页；库里一颗信标都没有时返回空列表
     */
    public static List<String> encode(BeaconDbHelper db) {
        List<String> frames = new ArrayList<>();
        List<BeaconEntity> beacons = db.getAllBeacons();
        if (beacons == null || beacons.isEmpty()) return frames;

        List<ChildEntity> children = db.getAllChildren();
        try {
            JSONObject root = new JSONObject();
            root.put("v", VERSION);

            JSONArray childArr = new JSONArray();
            for (ChildEntity c : children) childArr.put(c.getName());
            root.put("c", childArr);

            JSONArray beaconArr = new JSONArray();
            for (BeaconEntity b : beacons) {
                JSONObject o = new JSONObject();
                o.put("n", b.getName());
                String remark = b.getRemark();
                if (remark != null && !remark.trim().isEmpty()) o.put("rm", remark);
                o.put("u", b.getUuidRaw());
                o.put("ma", b.getMajor());
                o.put("mi", b.getMinor());
                if (b.hasMac()) o.put("mc", b.getMac());
                o.put("rs", b.getRssiAt1m());
                // 娃用下标表示，不重复写娃名 —— 十几个信标能省下不少体积
                int ci = indexOfChild(children, b.getChildId());
                if (ci >= 0) o.put("ci", ci);
                if (!b.getLabel().isEmpty()) o.put("lb", b.getLabel());
                beaconArr.put(o);
            }
            root.put("b", beaconArr);

            String payload = Base64.encodeToString(
                    root.toString().getBytes(StandardCharsets.UTF_8),
                    Base64.NO_WRAP | Base64.URL_SAFE);
            frames.addAll(split(payload));
        } catch (Exception e) {
            Log.e(TAG, "encode failed", e);
        }
        return frames;
    }

    /** 把一整串 Base64 切成若干页，每页带上「第几页 / 共几页」和这套配置的 id */
    private static List<String> split(String payload) {
        List<String> frames = new ArrayList<>();
        String id = bundleId(payload);
        int total = Math.max(1, (payload.length() + CHUNK - 1) / CHUNK);
        for (int i = 0; i < total; i++) {
            int from = i * CHUNK;
            int to = Math.min(payload.length(), from + CHUNK);
            frames.add(String.format(Locale.US, "%s%s:%d/%d:%s",
                    PREFIX, id, i + 1, total, payload.substring(from, to)));
        }
        return frames;
    }

    /**
     * 给这套配置算一个短 id。
     * 只用来区分「这几页是不是同一套」，不需要防碰撞强度。
     */
    private static String bundleId(String payload) {
        return String.format(Locale.US, "%06x", payload.hashCode() & 0xFFFFFF);
    }

    // ==================== 解码 / 拼装 ====================

    /** 是不是整套配置的分页码 */
    public static boolean isFrame(String value) {
        return parse(value) != null;
    }

    /**
     * 收一页。攒齐了返回整串 Base64（交给 {@link #importInto}），没攒齐返回 null。
     *
     * 调用方应该**继续扫**而不是报错：动态二维码每几百毫秒换一页，
     * 同一套配置的几页会在几秒内陆续被扫到。
     */
    public static String collect(String value) {
        String[] p = parse(value);
        if (p == null) return null;
        String id = p[0];
        int index;
        int total;
        try {
            index = Integer.parseInt(p[1]);
            total = Integer.parseInt(p[2]);
        } catch (NumberFormatException e) {
            return null;
        }
        if (index <= 0 || total <= 0 || index > total) return null;

        String[] slots = PENDING.get(id);
        if (slots == null || slots.length != total) {
            slots = new String[total];
            PENDING.put(id, slots);
        }
        slots[index - 1] = p[3];

        for (String s : slots) {
            if (s == null) return null;
        }
        PENDING.remove(id);

        StringBuilder sb = new StringBuilder();
        for (String s : slots) sb.append(s);
        return sb.toString();
    }

    /** 已经收到这套配置的几页了，例如 "2/3"；不是分页码返回 null */
    public static String progress(String value) {
        String[] p = parse(value);
        if (p == null) return null;
        String[] slots = PENDING.get(p[0]);
        int got = 0;
        if (slots != null) {
            for (String s : slots) if (s != null) got++;
        } else {
            got = 1;
        }
        return got + "/" + p[2];
    }

    /** 丢掉半截的会话（换了一套配置 / 退出扫码页） */
    public static void reset() {
        PENDING.clear();
    }

    /**
     * 解析分页码，返回 {id, 当前页, 总页数, 这段内容}；格式不对返回 null。
     * Base64 里不会出现冒号，所以按前两个冒号切就够了。
     */
    private static String[] parse(String value) {
        if (value == null || !value.startsWith(PREFIX)) return null;
        String rest = value.substring(PREFIX.length()).trim();
        int a = rest.indexOf(':');
        if (a <= 0) return null;
        int b = rest.indexOf(':', a + 1);
        if (b <= 0) return null;

        String id = rest.substring(0, a);
        String ivn = rest.substring(a + 1, b);
        String chunk = rest.substring(b + 1);

        int slash = ivn.indexOf('/');
        if (slash <= 0 || slash == ivn.length() - 1) return null;

        String i = ivn.substring(0, slash).trim();
        String n = ivn.substring(slash + 1).trim();
        if (i.isEmpty() || n.isEmpty() || chunk.isEmpty()) return null;
        return new String[]{id, i, n, chunk};
    }

    // ==================== 导入 ====================

    /**
     * 把拼好的整套配置写进本机。
     *
     * 娃按名字对：同名的直接复用，不重复建；信标按 MAC / 三件套去重，
     * 已经有的跳过 —— 所以同一张码扫两遍不会变成双份。
     *
     * @return 新导入了多少颗信标；格式或版本不对返回 -1
     */
    public static int importInto(BeaconDbHelper db, String payload) {
        try {
            String b64 = payload.trim();
            while (b64.length() % 4 != 0) b64 = b64 + "=";
            byte[] raw = Base64.decode(b64, Base64.NO_WRAP | Base64.URL_SAFE);
            JSONObject root = new JSONObject(new String(raw, StandardCharsets.UTF_8));
            if (root.optInt("v", -1) != VERSION) return -1;

            // 1) 娃：同名复用，没有就建
            List<Long> childIds = new ArrayList<>();
            JSONArray childArr = root.optJSONArray("c");
            List<ChildEntity> existing = db.getAllChildren();
            if (childArr != null) {
                for (int i = 0; i < childArr.length(); i++) {
                    String name = childArr.optString(i, "").trim();
                    long id = findChildId(existing, name);
                    if (id <= 0 && !name.isEmpty()) {
                        id = db.insertChild(name);
                        existing = db.getAllChildren();
                    }
                    childIds.add(id);
                }
            }

            // 2) 信标：去重后插进去
            int inserted = 0;
            JSONArray beaconArr = root.optJSONArray("b");
            if (beaconArr != null) {
                for (int i = 0; i < beaconArr.length(); i++) {
                    JSONObject o = beaconArr.optJSONObject(i);
                    if (o == null) continue;
                    String uuid = o.optString("u", "");
                    if (uuid.length() < 32) continue;

                    int major = o.optInt("ma", 0);
                    int minor = o.optInt("mi", 0);
                    String mac = o.optString("mc", "");
                    if (db.isDuplicate(uuid, major, minor, mac)) continue;

                    BeaconEntity b = new BeaconEntity();
                    b.setName(o.optString("n", "未命名信标"));
                    b.setRemark(o.optString("rm", ""));
                    b.setUuid(uuid);
                    b.setMajor(major);
                    b.setMinor(minor);
                    b.setMac(mac);
                    b.setRssiAt1m(o.optInt("rs", BeaconEntity.DEFAULT_RSSI_1M));
                    b.setLabel(o.optString("lb", ""));
                    int ci = o.optInt("ci", -1);
                    if (ci >= 0 && ci < childIds.size()) b.setChildId(childIds.get(ci));

                    if (db.insertBeacon(b) > 0) inserted++;
                }
            }
            return inserted;
        } catch (Exception e) {
            Log.e(TAG, "import failed", e);
            return -1;
        }
    }

    private static int indexOfChild(List<ChildEntity> children, long childId) {
        for (int i = 0; i < children.size(); i++) {
            if (children.get(i).getId() == childId) return i;
        }
        return -1;
    }

    private static long findChildId(List<ChildEntity> children, String name) {
        if (name == null || name.isEmpty()) return -1;
        for (ChildEntity c : children) {
            if (name.equals(c.getName())) return c.getId();
        }
        return -1;
    }
}
