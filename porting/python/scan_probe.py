#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Mac 上的实测/常驻脚本：扫 BLE 广播，解 iBeacon + TLM，做「到家/离家」判定。

两种用法：

    # 1) 先量数据（推荐第一步）：跑 30 分钟，让娃带着书包进出门一次
    python3 scan_probe.py --minutes 30

    # 2) 常驻：状态变化时打印、写 status.json、可选回调 webhook
    python3 scan_probe.py --watch --status /tmp/kidlink_status.json \
        --webhook https://your-n8n/webhook/kidlink-home

用的是 bleak（CoreBluetooth），首次运行要授权蓝牙：
    系统设置 → 隐私与安全性 → 蓝牙 → 允许承载脚本的那个解释器 / 终端
换 venv、换 Python 版本都要重新授权 —— 「昨天还好今天收不到」基本都是这个原因。

判定参数在 kidlink_proto.PresenceTracker 里，默认只是起点，务必先用 --minutes 量出真实 RSSI。
"""

import argparse
import asyncio
import json
import statistics
import sys
import time
from datetime import datetime

try:
    from bleak import BleakScanner
except ImportError:
    sys.exit("缺少 bleak：pip install -r requirements.txt")

from kidlink_proto import (
    EndianStore,
    PresenceTracker,
    battery_level,
    parse_ibeacon,
    parse_tlm,
)

BUCKETS = [-100, -90, -80, -70, -60, -50, -40]


class MacStat(object):
    __slots__ = ("frames", "rssi", "beacon", "tlm", "mac")

    def __init__(self, mac):
        self.mac = mac
        self.frames = 0
        self.rssi = []
        self.beacon = None
        self.tlm = None

    def histogram(self):
        if not self.rssi:
            return ""
        counts = []
        for i in range(len(BUCKETS) - 1):
            lo, hi = BUCKETS[i], BUCKETS[i + 1]
            counts.append(sum(1 for r in self.rssi if lo <= r < hi))
        peak = max(counts) or 1
        # 用字符数表示相对高度，一行看完分布，比数字直观
        chars = " ▁▂▃▄▅▆▇█"
        return "".join(chars[min(8, int(c * 8 / peak + 0.5))] for c in counts)


def now_str():
    return datetime.now().strftime("%H:%M:%S")


def build_parser():
    p = argparse.ArgumentParser(description="KidLink 信标扫描 / 在家检测")
    p.add_argument("--minutes", type=float, default=0, help="跑多少分钟统计（0 = 不按时间停）")
    p.add_argument("--watch", action="store_true", help="常驻模式：做判定、写状态、回调 webhook")
    p.add_argument("--mac", default="", help="只盯这颗信标（大写，冒号分隔）")
    p.add_argument("--status", default="/tmp/kidlink_status.json", help="状态文件路径")
    p.add_argument("--webhook", default="", help="状态翻转时 POST 的地址（n8n / HA 都行）")
    p.add_argument("--endian-file", default="tlm_endian.json", help="字节序缓存文件")
    p.add_argument("--report-s", type=float, default=60, help="常驻模式多久打一次报告")
    p.add_argument("--recycle-s", type=float, default=600, help="多久重建一次 scanner（防长时间扫描衰减）")
    p.add_argument("--enter-rssi", type=int, default=-75)
    p.add_argument("--exit-rssi", type=int, default=-88)
    p.add_argument("--enter-hold", type=float, default=10.0)
    p.add_argument("--exit-timeout", type=float, default=60.0)
    return p


async def run(args):
    stats = {}
    trackers = {}
    endian = EndianStore(args.endian_file)
    only = args.mac.strip().upper()
    started = time.time()
    deadline = started + args.minutes * 60 if args.minutes > 0 else None
    last_report = started

    def push_status(state, mac, rssi, tlm):
        payload = {
            "state": state,
            "mac": mac,
            "rssi": rssi,
            "voltage_mv": tlm.voltage_mv if tlm else None,
            "battery": battery_level(tlm.voltage_mv) if tlm else "unknown",
            "temp_c": tlm.temp_c if tlm else None,
            "updated_at": datetime.now().isoformat(timespec="seconds"),
            "ts": time.time(),
        }
        if args.status:
            try:
                with open(args.status, "w", encoding="utf-8") as f:
                    json.dump(payload, f, ensure_ascii=False, indent=2)
            except IOError as e:
                print("写状态文件失败:", e)
        if args.webhook:
            try:
                import urllib.request
                req = urllib.request.Request(
                    args.webhook,
                    data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
                    headers={"Content-Type": "application/json"})
                urllib.request.urlopen(req, timeout=5).read()
            except Exception as e:                       # 网络问题不该弄死主循环
                print("webhook 失败:", e)

    def on_advert(device, adv):
        mac = (device.address or "").upper()
        if not mac:
            return
        st = stats.get(mac)
        if st is None:
            st = MacStat(mac)
            stats[mac] = st

        st.frames += 1
        if adv.rssi:
            st.rssi.append(adv.rssi)

        ib = parse_ibeacon(adv.manufacturer_data)
        if ib:
            st.beacon = ib

        tlm = parse_tlm(adv.service_data, endian.get(mac))
        if tlm:
            st.tlm = tlm
            if tlm.endian_certain and endian.remember(mac, tlm.little_endian):
                print("[{}] 字节序 {} -> {}".format(
                    now_str(), mac, "LE" if tlm.little_endian else "BE"))

        if not args.watch:
            return
        if only and mac != only:
            return

        tr = trackers.get(mac)
        if tr is None:
            tr = PresenceTracker(
                name=mac,
                enter_rssi=args.enter_rssi,
                exit_rssi=args.exit_rssi,
                enter_hold_s=args.enter_hold,
                exit_timeout_s=args.exit_timeout,
            )
            trackers[mac] = tr
        event = tr.feed(adv.rssi, time.time())
        if event:
            label = "到家" if event == "arrived" else "离家"
            print("[{}] === {} {}  rssi中位={:.0f}  {} ===".format(
                now_str(), mac, label, tr.median,
                "电量 {:.3f}V".format(tlm.voltage_mv / 1000.0) if tlm else "无 TLM"))
            push_status("home" if event == "arrived" else "away", mac, adv.rssi, tlm)

    def report(prefix=""):
        rows = sorted(stats.values(), key=lambda s: -s.frames)
        if only:
            rows = [r for r in rows if r.mac == only]
        if not rows:
            print("{}还没收到任何广播".format(prefix))
            return
        print("\n{}{} 累计 {:.1f} 分钟，{} 台设备".format(
            prefix, now_str(), (time.time() - started) / 60.0, len(rows)))
        print("{:<18} {:<44} {:>5} {:>7} {:>6} {:>6}  {}".format(
            "MAC", "iBeacon 三件套 / TLM", "帧数", "RSSI中位", "最强", "最弱", "分布 -100..-40"))
        for s in rows[:20]:
            if s.beacon:
                ident = "{} {}/{}".format(s.beacon.uuid[-8:], s.beacon.major, s.beacon.minor)
            else:
                ident = "(无 iBeacon)"
            if s.tlm:
                ident += "  {}".format(s.tlm)
            med = statistics.median(s.rssi) if s.rssi else 0
            print("{:<18} {:<44} {:>5} {:>7.0f} {:>6} {:>6}  {}".format(
                s.mac, ident[:44], s.frames, med,
                max(s.rssi) if s.rssi else 0, min(s.rssi) if s.rssi else 0, s.histogram()))
        print()

    scanner = None
    recycled_at = time.time()

    async def start_scanner():
        nonlocal scanner
        scanner = BleakScanner(detection_callback=on_advert)
        await scanner.start()

    await start_scanner()

    try:
        while True:
            await asyncio.sleep(1.0)
            now = time.time()

            # 长时间扫描会衰减（Android 上实测 0.48 包/秒），定期重建最省事
            if now - recycled_at >= args.recycle_s:
                await scanner.stop()
                await start_scanner()
                recycled_at = now

            if args.watch:
                for mac, tr in trackers.items():
                    if tr.tick(now) == "left":
                        print("[{}] === {} 离家  rssi中位={} ===".format(
                            now_str(), mac, "-" if tr.median is None else "%.0f" % tr.median))
                        push_status("away", mac, tr.median, stats[mac].tlm)

            if now - last_report >= args.report_s:
                last_report = now
                if args.watch:
                    report("[常驻] ")

            if deadline and now >= deadline:
                break
    except KeyboardInterrupt:
        print("\n中断")
    finally:
        await scanner.stop()
        report()


def main():
    args = build_parser().parse_args()
    if not args.watch and args.minutes <= 0:
        print("提示：加 --minutes 30 做统计，或 --watch 常驻判定。先量数据再定门限。\n")
    try:
        asyncio.run(run(args))
    except Exception as e:
        print("运行失败:", e)
        print("如果是权限问题：系统设置 → 隐私与安全性 → 蓝牙 → 勾上这个解释器 / 终端")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
