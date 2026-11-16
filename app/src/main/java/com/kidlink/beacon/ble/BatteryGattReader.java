package com.kidlink.beacon.ble;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.Locale;
import java.util.UUID;

/**
 * 连上去读电量（GATT）。
 *
 * ## 为什么需要它
 * 纯广播只能旁听 iBeacon；电量要么走 Eddystone-TLM（很多信标根本没开），
 * 要么就得像厂商 App 那样**建立连接**再读特征值。实测这批信标在广播里不发 TLM，
 * 但公开的 GATT 里带标准电池服务（0x180F / 0x2A19），所以补这条路径。
 *
 * ## 读什么
 * 1. 标准 Battery Service `0x180F` → Battery Level `0x2A19`：一个字节，0–100 的百分比
 * 2. 顺带扫描所有可读特征值并把 UUID + 原始字节打到 logcat，方便以后支持厂商私有电压通道
 *
 * ## 注意
 * 连接是**独占**的：连上期间这颗信标通常停止广播，所以只能在用户主动要看电量时连，
 * 不能在后台常连。读完立刻断开，并设了 12 秒超时兜底。
 */
public final class BatteryGattReader {

    private static final String TAG = "BatteryGatt";

    /** 标准电池服务 / 电量百分比 */
    private static final UUID SERVICE_BATTERY = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb");
    private static final UUID CHAR_BATTERY_LEVEL = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb");

    // ==== 厂商私有通道（DX-SMART 那套协议，从厂商 APK 反汇编出来的） ====
    //
    // 帧格式（请求和响应一样）：
    //   4E 4F <cmd> <len> <payload...> <xor>
    //   xor = cmd ^ len ^ payload[0] ^ ... ^ payload[len-1]
    //
    // 已知命令：
    //   0x10  读全部帧配置      （readFrames）
    //   0x46  读 Eddystone 配置 （readEddystone）
    //   0x25  密码校验          （readPwdAhth，带 4 字节密码，默认 "dx1234"）
    //   0x22  设备开关
    //
    // 电量百分比走标准 2A19；电压/温度/adv 计数只在厂商私有帧里，
    // 下面这段探针就是用来抓它的——调试版才跑。
    private static final UUID SERVICE_VENDOR = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb");
    private static final UUID CHAR_VENDOR = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb");
    private static final UUID DESC_CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    /** 按厂商协议封一帧 */
    public static byte[] packFrame(int cmd, byte[] payload) {
        int len = payload == null ? 0 : payload.length;
        byte[] out = new byte[len + 5];
        out[0] = 0x4E;              // 'N'
        out[1] = 0x4F;              // 'O'
        out[2] = (byte) cmd;
        out[3] = (byte) (len & 0xFF);
        int xor = cmd ^ (len & 0xFF);
        for (int i = 0; i < len; i++) {
            out[4 + i] = payload[i];
            xor ^= payload[i];
        }
        out[4 + len] = (byte) xor;
        return out;
    }

    /** 私有通道探针：默认关。要再排查这颗信标时改 true 再编译一次 */
    private static final boolean PROBE_VENDOR = true;

    private static final long TIMEOUT_MS = 12_000L;

    /** 读到的结果；两个都可能为空 */
    public interface Callback {
        /**
         * @param percent    电量百分比 0–100；读不到为 -1
         * @param voltageMv  电压毫伏；读不到为 -1
         * @param ok         是否至少读到一样东西
         */
        void onResult(int percent, int voltageMv, boolean ok);
    }

    private BatteryGattReader() {}

    @SuppressLint("MissingPermission")
    public static void read(Context context, String mac, Callback callback) {
        read(context, mac, TIMEOUT_MS, callback);
    }

    @SuppressLint("MissingPermission")
    public static void read(Context context, String mac, long timeoutMs, Callback callback) {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled() || mac == null || mac.isEmpty()) {
            callback.onResult(-1, -1, false);
            return;
        }

        final Handler handler = new Handler(Looper.getMainLooper());
        final BluetoothDevice device;
        try {
            device = adapter.getRemoteDevice(mac);
        } catch (Exception e) {
            callback.onResult(-1, -1, false);
            return;
        }

        final int[] result = {-1, -1};
        final boolean[] done = {false};
        final BluetoothGatt[] gattRef = new BluetoothGatt[1];
        // 待读队列：发现服务后把「所有可读特征值」排进去，一个一个读
        // （GATT 不支持并发读，必须等上一个回调到了再发下一个）
        final java.util.List<BluetoothGattCharacteristic> pending = new java.util.ArrayList<>();
        /** 读队列的「取下一个」，在回调里被调用，所以先占个位再赋值 */
        final Runnable[] readNext = new Runnable[1];
        /** 调试探针只跑一次 */
        final boolean[] probed = {false};

        final Runnable finish = new Runnable() {
            @Override
            public void run() {
                handler.removeCallbacks(this);
                if (done[0]) return;
                done[0] = true;
                try {
                    if (gattRef[0] != null) {
                        gattRef[0].disconnect();
                        gattRef[0].close();
                    }
                } catch (Exception ignored) {
                }
                final int pct = result[0];
                final int mv = result[1];
                // 回调统一扔回主线程：GATT 回调跑在 Binder 线程，直接改 UI 会崩
                handler.post(() -> callback.onResult(pct, mv, pct >= 0 || mv >= 0));
            }
        };

        BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
            @Override
            public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
                Log.d(TAG, "state=" + newState + " status=" + status);
                if (newState == BluetoothGatt.STATE_CONNECTED) {
                    gatt.discoverServices();
                } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                    finish.run();
                }
            }

            @Override
            public void onServicesDiscovered(BluetoothGatt gatt, int status) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.w(TAG, "discover failed: " + status);
                    finish.run();
                    return;
                }
                // 把所有服务/特征值打出来：以后要读厂商私有电压通道，就靠这份清单
                for (BluetoothGattService s : gatt.getServices()) {
                    Log.d(TAG, "service " + s.getUuid());
                    for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
                        Log.d(TAG, "  char " + c.getUuid()
                                + " props=" + Integer.toHexString(c.getProperties()));
                    }
                }

                // 电量百分比优先排在最前面，保证 12 秒超时前一定读到它。
                // 剩下的可读特征值只在调试包里「顺便全读一遍」，纯诊断用 ——
                // 正式包不读，因为连接期间信标停播，能少连一会儿就少一会儿。
                BluetoothGattService battery = gatt.getService(SERVICE_BATTERY);
                BluetoothGattCharacteristic level = battery == null
                        ? null : battery.getCharacteristic(CHAR_BATTERY_LEVEL);
                if (level != null) pending.add(level);

                if (com.kidlink.beacon.BuildConfig.DEBUG) {
                    for (BluetoothGattService s : gatt.getServices()) {
                        for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
                            boolean readable = (c.getProperties() & 0x02) != 0;
                            if (readable && !pending.contains(c)) pending.add(c);
                        }
                    }
                }

                if (level == null) Log.w(TAG, "no standard battery level char");
                readNext[0].run();
            }

            @Override
            public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic ch) {
                byte[] v = ch.getValue();
                Log.d(TAG, "notify " + ch.getUuid() + " " + (v == null ? "null" : toHex(v))
                        + (v == null ? "" : " (" + printable(v) + ")"));
            }

            @Override
            public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor d, int status) {
                Log.d(TAG, "descriptor " + d.getUuid() + " status=" + status);
            }

            @Override
            public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic ch,
                                              int status) {
                Log.d(TAG, "write " + ch.getUuid() + " status=" + status
                        + " value=" + toHex(ch.getValue()));
            }

            @Override
            public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic ch,
                                             int status) {
                byte[] v = ch.getValue();
                Log.d(TAG, String.format(Locale.US, "read %s status=%d value=%s%s",
                        ch.getUuid(), status,
                        v == null ? "null" : toHex(v),
                        v == null ? "" : " (" + printable(v) + ")"));
                if (CHAR_BATTERY_LEVEL.equals(ch.getUuid()) && v != null && v.length >= 1) {
                    result[0] = v[0] & 0xFF;
                }
                if (status == BluetoothGatt.GATT_SUCCESS && v != null) {
                    int mv = guessMillivolts(ch.getUuid(), v);
                    if (mv > 0) result[1] = mv;
                }
                // 探针阶段读回来的 FFE1 不走队列推进，否则会把连接提前关掉
                if (!probed[0]) readNext[0].run();
            }
        };

        // 读队列的「取下一个」：队列空 = 收工
        readNext[0] = new Runnable() {
            @Override
            public void run() {
                if (done[0] || gattRef[0] == null) return;
                if (pending.isEmpty()) {
                    // 调试版：读完标准特征值后，再往厂商私有通道问一发电压/温度
                    if (PROBE_VENDOR && !probed[0]) {
                        probed[0] = true;
                        runVendorProbe(gattRef[0], handler, finish);
                        return;
                    }
                    finish.run();
                    return;
                }
                BluetoothGattCharacteristic c = pending.remove(0);
                try {
                    if (!gattRef[0].readCharacteristic(c)) {
                        Log.w(TAG, "readCharacteristic false for " + c.getUuid());
                        handler.post(this);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "readCharacteristic threw", e);
                    handler.post(this);
                }
            }
        };

        gattRef[0] = device.connectGatt(context.getApplicationContext(), false,
                gattCallback, BluetoothDevice.TRANSPORT_LE);
        handler.postDelayed(finish, timeoutMs);
    }

    /**
     * 调试探针：连上厂商私有通道（FFE0/FFE1），开通知，然后按顺序问几个命令，
     * 把设备回的原始字节打进 logcat。用来确认「电压/温度/adv 计数到底在哪条命令的响应里」。
     *
     * 只在 DEBUG 包跑；确认协议之后再改成正式读取。
     */
    @SuppressLint("MissingPermission")
    private static void runVendorProbe(BluetoothGatt gatt, Handler handler, Runnable finish) {
        if (gatt == null) {
            finish.run();
            return;
        }
        BluetoothGattService svc = gatt.getService(SERVICE_VENDOR);
        final BluetoothGattCharacteristic ch = svc == null ? null : svc.getCharacteristic(CHAR_VENDOR);
        if (ch == null) {
            Log.w(TAG, "probe: no FFE1 on this beacon");
            finish.run();
            return;
        }
        Log.d(TAG, "probe: FFE1 found, request MTU 517 -> " + gatt.requestMtu(517));
        // MTU 协商是异步的，等它谈完再开通知、再发命令，否则 writeCharacteristic 会直接返回 false
        handler.postDelayed(() -> {
            gatt.setCharacteristicNotification(ch, true);
            BluetoothGattDescriptor cccd = ch.getDescriptor(DESC_CCCD);
            if (cccd != null) {
                cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                Log.d(TAG, "probe: write CCCD -> " + gatt.writeDescriptor(cccd));
            } else {
                Log.w(TAG, "probe: no CCCD on FFE1");
            }
        }, 1200);

        // 一条一条问，中间留出时间收通知；写完 400ms 再主动把 FFE1 读回来
        // （这类设备有的命令走通知、有的把结果放在特征值里，两条路都试）
        final int[] cmds = {0x25, 0x10};
        for (int i = 0; i < cmds.length; i++) {
            final int cmd = cmds[i];
            final long base = 2400 + i * 1500L;
            handler.postDelayed(() -> {
                byte[] payload = cmd == 0x25 ? "dx1234".getBytes() : null;
                byte[] f = packFrame(cmd, payload);
                ch.setValue(f);
                ch.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
                boolean ok = gatt.writeCharacteristic(ch);
                Log.d(TAG, "probe: cmd " + Integer.toHexString(cmd)
                        + " frame=" + toHex(f) + " write=" + ok);
            }, base);
            handler.postDelayed(() -> {
                boolean ok = gatt.readCharacteristic(ch);
                Log.d(TAG, "probe: read-back FFE1 -> " + ok);
            }, base + 400);
        }
        handler.postDelayed(finish, 11_000);
    }

    private static String toHex(byte[] data) {        StringBuilder sb = new StringBuilder();
        for (byte b : data) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(String.format("%02X", b & 0xFF));
        }
        return sb.toString();
    }

    /** 可打印字符原样留，其余用点占位 —— 型号/固件那些特征值都是 ASCII 字符串 */
    private static String printable(byte[] data) {
        StringBuilder sb = new StringBuilder();
        for (byte b : data) {
            int c = b & 0xFF;
            sb.append(c >= 0x20 && c < 0x7F ? (char) c : '.');
        }
        return sb.toString();
    }

    /**
     * 从原始字节里猜电压（毫伏）—— 只认 2 字节、落在 2.0–3.6 V 这个锂电池区间的值。
     *
     * 这一段是「诊断优先」：要是厂商把电压塞在 FFE1/FFE2 里，日志会把这个值打出来，
     * 我们就能确认要不要用它替换标准电量百分比。一个字节的值（0–100）按百分比看待，不算电压。
     */
    private static int guessMillivolts(UUID uuid, byte[] v) {
        if (v.length != 2) return -1;
        int le = (v[0] & 0xFF) | ((v[1] & 0xFF) << 8);
        int be = ((v[0] & 0xFF) << 8) | (v[1] & 0xFF);
        if (le >= 2000 && le <= 3600) return le;
        if (be >= 2000 && be <= 3600) return be;
        // 有的固件按 0.01 V 上报（298 = 2.98 V）
        if (le >= 200 && le <= 360) return le * 10;
        return -1;
    }
}
