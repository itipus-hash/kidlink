package com.kidlink.beacon.data;

import java.util.ArrayList;
import java.util.List;

/**
 * 一个「时间段类型」，比如「上学」「放学」，或者用户自己加的「补习班」。
 *
 * - name   名称，长按可以改，同时会出现在推送文案里
 * - days   生效的星期，1=周一 … 7=周日，逗号分隔；空字符串表示**这几天都不推送**
 * - ranges 时间段，格式见 {@link TimeRanges}，例如 "7:40-8:30,13:50-14:20"
 * - childId 挂在哪一个娃名下（children.id），0 = 老数据、还没归属
 *
 * 为什么要绑娃：不同娃的作息本来就不一样（小学 7:40 到校、幼儿园 8:20），
 * 以前一张全局表只能顾一头，所以时间段从「设置」挪到了「信标管理 → 每个娃」。
 */
public class TimeGroup {

    /** 一周七天全选，新建分组时的默认值 */
    public static final String ALL_DAYS = "1,2,3,4,5,6,7";

    public String name;
    public String days;
    public String ranges;
    /** 归属的娃，0 = 还没归属（老版本的全局数据） */
    public long childId;

    public TimeGroup(String name, String days, String ranges) {
        this(name, days, ranges, 0);
    }

    public TimeGroup(String name, String days, String ranges, long childId) {
        this.name = name == null ? "" : name.trim();
        this.days = days == null ? "" : days.trim();
        this.ranges = ranges == null ? "" : ranges.trim();
        this.childId = Math.max(0, childId);
    }

    public String displayName() {
        return name.isEmpty() ? "未命名" : name;
    }

    /** 星期这一天的编号（1=周一 … 7=周日）是否命中；一个都没勾 = 不推送 */
    public boolean matchesDay(int isoDayOfWeek) {
        return pickedDays().contains(isoDayOfWeek);
    }

    /** 勾了哪几天，已排序去重 */
    public List<Integer> pickedDays() {
        List<Integer> picked = new ArrayList<>();
        if (days == null || days.trim().isEmpty()) return picked;
        for (String d : days.split(",")) {
            try {
                int v = Integer.parseInt(d.trim());
                if (v >= 1 && v <= 7 && !picked.contains(v)) picked.add(v);
            } catch (NumberFormatException ignored) {
            }
        }
        java.util.Collections.sort(picked);
        return picked;
    }

    /** 给设置页看的一行摘要：每天 7:40-8:30、13:50-14:20 / 周一~周五 … / 不推送 */
    public String summary() {
        List<Integer> picked = pickedDays();
        StringBuilder sb = new StringBuilder();
        if (picked.isEmpty()) {
            return "不推送";
        }
        sb.append(picked.size() == 7 ? "每天" : daysLabel(picked)).append(' ');
        String rangeText = TimeRanges.display(ranges);
        sb.append(rangeText.isEmpty() ? "未设置时间" : rangeText);
        return sb.toString();
    }

    /** [1,2,3] → "周一~周三"；不连续的用顿号连起来 */
    private String daysLabel(List<Integer> picked) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < picked.size()) {
            int start = picked.get(i);
            int end = start;
            while (i + 1 < picked.size() && picked.get(i + 1) == end + 1) {
                i++;
                end = picked.get(i);
            }
            if (sb.length() > 0) sb.append('、');
            if (end == start) sb.append(dayName(start));
            else if (end == start + 1) sb.append(dayName(start)).append('、').append(dayName(end));
            else sb.append(dayName(start)).append('~').append(dayName(end));
            i++;
        }
        return sb.toString();
    }

    private static String dayName(int isoDay) {
        switch (isoDay) {
            case 1: return "周一";
            case 2: return "周二";
            case 3: return "周三";
            case 4: return "周四";
            case 5: return "周五";
            case 6: return "周六";
            default: return "周日";
        }
    }

    public TimeGroup copy() {
        return new TimeGroup(name, days, ranges, childId);
    }
}
