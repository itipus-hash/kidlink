package com.kidlink.beacon.data;

import java.util.ArrayList;
import java.util.List;

/**
 * 「上学时间」「放学时间」这种多时间段。
 *
 * 存储格式就一行字符串，方便直接看 prefs 调试：
 * <pre>7:40-8:30,13:50-14:20</pre>
 * 解析成「当天第几分钟」的区间，判断某个时刻落在哪一段里。
 *
 * 不处理跨午夜（22:30-1:00 这种），结束必须大于开始，否则整段丢弃。
 */
public final class TimeRanges {

    private TimeRanges() {
    }

    /** 解析成 [startMinute, endMinute] 的列表，非法段直接跳过 */
    public static List<int[]> parse(String raw) {
        List<int[]> out = new ArrayList<>();
        if (raw == null || raw.trim().isEmpty()) return out;
        for (String part : raw.split(",")) {
            String s = part.trim();
            if (s.isEmpty()) continue;
            int dash = s.indexOf('-');
            if (dash <= 0 || dash >= s.length() - 1) continue;
            int start = minuteOf(s.substring(0, dash).trim());
            int end = minuteOf(s.substring(dash + 1).trim());
            if (start < 0 || end < 0 || end <= start) continue;
            out.add(new int[]{start, end});
        }
        return out;
    }

    /** 反向拼回存储字符串 */
    public static String format(List<int[]> ranges) {
        StringBuilder sb = new StringBuilder();
        if (ranges != null) {
            for (int[] r : ranges) {
                if (r == null || r.length < 2 || r[1] <= r[0]) continue;
                if (sb.length() > 0) sb.append(',');
                sb.append(hhmm(r[0])).append('-').append(hhmm(r[1]));
            }
        }
        return sb.toString();
    }

    /** 给设置页看的版本：7:40-8:30、13:50-14:20 */
    public static String display(String raw) {
        List<int[]> ranges = parse(raw);
        if (ranges.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int[] r : ranges) {
            if (sb.length() > 0) sb.append("、");
            sb.append(hhmm(r[0])).append('-').append(hhmm(r[1]));
        }
        return sb.toString();
    }

    /**
     * 这一分钟落在任意一段里吗（起止都算在内）。
     *
     * 精准匹配，没有「宽限」这一说：窗口外就不算命中。
     * 想覆盖晚接的情况，把结束时间往后拉，而不是让代码偷偷多算半小时 ——
     * 否则 12:20 接到的娃会被标成「放学 11:30-12:30」。
     */
    public static boolean contains(String raw, int minuteOfDay) {
        for (int[] r : parse(raw)) {
            if (minuteOfDay >= r[0] && minuteOfDay <= r[1]) return true;
        }
        return false;
    }

    /** "7:40" / "07:40" → 460；非法返回 -1 */
    public static int minuteOf(String hhmm) {
        if (hhmm == null) return -1;
        String s = hhmm.trim();
        int colon = s.indexOf(':');
        if (colon <= 0) return -1;
        try {
            int h = Integer.parseInt(s.substring(0, colon).trim());
            int m = Integer.parseInt(s.substring(colon + 1).trim());
            if (h < 0 || h > 24 || m < 0 || m > 59) return -1;
            if (h == 24 && m != 0) return -1;
            return h * 60 + m;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** 460 → "7:40"（小时不补零，分钟补零） */
    public static String hhmm(int minuteOfDay) {
        int m = Math.max(0, Math.min(24 * 60, minuteOfDay));
        return (m / 60) + ":" + String.format(java.util.Locale.US, "%02d", m % 60);
    }
}
