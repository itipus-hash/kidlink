package com.kidlink.beacon.ui;

import android.content.Context;

import com.kidlink.beacon.R;

/**
 * RSSI 换算出来的距离 → 给人看的文字。
 *
 * 首页（大字）和接娃信息页（卡片第二行）都要写这个，所以抽出来一份，
 * 免得两边的「1 米内 / 约 1.2 米 / 约 30 米」哪天写得不一样。
 *
 * 1 米以内本来就没有分辨率可言（±5dB 噪声 ≈ ±0.5 米），
 * 所以贴着信标时给「1 米内」，而不是编一个 0.1 米出来。
 */
public final class DistanceText {

    private DistanceText() {
    }

    public static String format(Context ctx, double meters) {
        if (meters < 1.0) return ctx.getString(R.string.distance_near);
        if (meters < 10.0) return ctx.getString(R.string.distance_approx, meters);
        return ctx.getString(R.string.distance_far, Math.round(meters));
    }

    /**
     * 分档：<1 米 / 1~10 米 / 10 米以上。
     *
     * 用来判断「这次更新要不要重画数字」—— 同一档里距离抖 0.2 米就不动文字，
     * 否则 RSSI 一抖，屏幕上的字跟着跳，看着像坏了。
     */
    public static int bucketOf(double meters) {
        if (meters < 1.0) return 0;
        if (meters < 10.0) return 1;
        return 2;
    }
}
