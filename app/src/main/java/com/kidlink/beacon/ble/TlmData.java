package com.kidlink.beacon.ble;

/**
 * 从一条广播里解出来的 Eddystone-TLM 遥测数据。
 *
 * 只保留我们真正用得上的：电压（电量）、芯片温度（解释冬天电压偏低）、
 * 广播计数和运行秒数（判断这颗信标是不是刚重启过）。
 */
public class TlmData {

    /** 电池电压，毫伏；0 表示信标自己说「不支持」（比如 USB 供电） */
    public final int voltageMv;
    /** 芯片温度（摄氏度）；unsupported 时为 {@link Float#NaN} */
    public final float tempC;
    /** 开机以来的广播包数 */
    public final long advCount;
    /** 开机以来的运行时间（秒，0.1 s 精度换算而来） */
    public final long uptimeSec;
    /** 这帧是按小端解的（true）还是大端解的（false） */
    public final boolean littleEndian;
    /**
     * 字节序这个结论有没有硬证据。
     * true 表示可以拿去做「按 MAC 记住方向」的依据（见 {@link TlmEndian}）；
     * false 表示两个方向都说得通或者都不像，只是临时挑了一个。
     */
    public final boolean endianCertain;

    /** 信标说温度不支持时的固定值（8.8 定点 -128 °C） */
    public static final float TEMP_UNSUPPORTED = Float.NaN;

    public TlmData(int voltageMv, float tempC, long advCount, long uptimeSec) {
        this(voltageMv, tempC, advCount, uptimeSec, true, false);
    }

    public TlmData(int voltageMv, float tempC, long advCount, long uptimeSec,
                   boolean littleEndian, boolean endianCertain) {
        this.voltageMv = voltageMv;
        this.tempC = tempC;
        this.advCount = advCount;
        this.uptimeSec = uptimeSec;
        this.littleEndian = littleEndian;
        this.endianCertain = endianCertain;
    }

    /** 有没有可用的电压读数 */
    public boolean hasVoltage() {
        return voltageMv > 0;
    }

    @Override
    public String toString() {
        return "TlmData{" + voltageMv + "mV, temp="
                + (Float.isNaN(tempC) ? "n/a" : String.format(java.util.Locale.US, "%.2f", tempC))
                + ", adv=" + advCount + ", uptime=" + uptimeSec + "s"
                + ", " + (littleEndian ? "LE" : "BE")
                + (endianCertain ? "" : "?")
                + "}";
    }
}
