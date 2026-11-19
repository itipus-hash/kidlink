#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""接娃助手协议解析 + 在家判定 —— 从 Android 端 (com.kidlink.beacon v1.6) 抽出的纯逻辑。

不依赖 bleak、不依赖任何 Android API。输入是「一条广播里解出来的 manufacturer_data / service_data」，
输出是结构化数据。Mac 侧用 bleak 扫到 AdvertisementData 后原样喂进来即可（见 scan_probe.py）。

三块内容：
  1. parse_ibeacon()    iBeacon 识别（Apple 0x004C 与厂商私有 0x4458 都认）
  2. parse_tlm()        Eddystone-TLM 电量（带字节序自动判定，规范大端 / 现场小端都吃）
  3. PresenceTracker    到家/离家状态机（RSSI 中位数 + 滞回 + 持续时间）

背景与实测数据见 ../PROTOCOL.md。参数改了记得同步改那边的表。
"""

import json
import statistics
from collections import deque
from dataclasses import dataclass
from typing import Dict, Optional

# ---------------------------------------------------------------------------
# 常量（全部来自真机实测，改之前先看 PROTOCOL.md）
# ---------------------------------------------------------------------------

EDDYSTONE_SERVICE_HINT = "feaa"          # 0xFEAA 的 UUID 尾部，beak 给的是完整 128 位
COMPANY_APPLE = 0x004C
COMPANY_DX = 0x4458                      # 这家厂商私有 company id

VOLTAGE_MIN_MV = 2700                    # 有效电压区间：低于这个当「不像电池电压」
VOLTAGE_MAX_MV = 3400
TEMP_MIN_C = -40.0
TEMP_MAX_C = 125.0
MAX_UPTIME_TENTHS = 5 * 365 * 24 * 3600 * 10   # 5 年，超过就是字节序读反了

FULL_MV = 3000                           # 电量档位分界（充足 / 一般）
MID_MV = 2900                            # 一般 / 偏低

LONGQUE_UUID_PREFIX = "E2C56DB5DFFB48D2B060"
TLM_INTERVAL_LONGQUE_MS = 1500
TLM_INTERVAL_OTHER_MS = 6000


# ---------------------------------------------------------------------------
# iBeacon
# ---------------------------------------------------------------------------

@dataclass
class IBeacon:
    uuid: str
    major: int
    minor: int
    tx_power: int          # 1 米处标定 RSSI，dBm，通常 -59 左右
    company_id: int

    @property
    def key(self) -> str:
        """三件套，用来区分不同信标（TLM 帧里没有 ID，只能靠 MAC 关联）"""
        return "{}|{}|{}".format(self.uuid, self.major, self.minor)

    def __str__(self) -> str:
        return "{} {}/{} tx={}".format(self.uuid, self.major, self.minor, self.tx_power)


def _uuid_text(raw: bytes) -> str:
    h = raw.hex().upper()
    return "{}-{}-{}-{}-{}".format(h[0:8], h[8:12], h[12:16], h[16:20], h[20:32])


def parse_ibeacon(manufacturer_data: Optional[Dict[int, bytes]]) -> Optional[IBeacon]:
    """从 bleak 的 AdvertisementData.manufacturer_data 里解 iBeacon。

    结构：02 15 <uuid:16> <major:2 大端> <minor:2 大端> <tx:1 有符号>
    不限制 company id —— 只要前缀对就认，这样同频段的其它品牌 iBeacon 也能看到。
    """
    if not manufacturer_data:
        return None
    for company_id, payload in manufacturer_data.items():
        if len(payload) < 23 or payload[0] != 0x02 or payload[1] != 0x15:
            continue
        tx = payload[22]
        if tx > 127:
            tx -= 256                      # 有符号：0xC7 = -57
        return IBeacon(
            uuid=_uuid_text(payload[2:18]),
            major=int.from_bytes(payload[18:20], "big"),
            minor=int.from_bytes(payload[20:22], "big"),
            tx_power=tx,
            company_id=company_id,
        )
    return None


# ---------------------------------------------------------------------------
# Eddystone-TLM
# ---------------------------------------------------------------------------

@dataclass
class Tlm:
    voltage_mv: int
    temp_c: Optional[float]      # None = 信标说温度不支持（0x8000）
    adv_count: int
    uptime_s: int
    little_endian: bool
    endian_certain: bool         # 这个方向判定有没有硬证据（有就值得按 MAC 记住）

    def has_voltage(self) -> bool:
        return self.voltage_mv > 0

    def __str__(self) -> str:
        t = "n/a" if self.temp_c is None else "{:.2f}".format(self.temp_c)
        return "Tlm({}mV, temp={}, adv={}, uptime={}s, {}{})".format(
            self.voltage_mv, t, self.adv_count, self.uptime_s,
            "LE" if self.little_endian else "BE",
            "" if self.endian_certain else "?")


def _order(little: bool) -> str:
    return "little" if little else "big"


def _signed16(v: int) -> int:
    return v - 65536 if v > 32767 else v


def _better_little(be_ok: bool, le_ok: bool, be: int, le: int) -> bool:
    """两个方向都没有硬证据时怎么选：一合理一不合理听合理的，否则取小的。"""
    if le_ok != be_ok:
        return le_ok
    if le_ok:
        return True                      # 都合理 → 现场固件是小端
    return le <= be                      # 都不合理 → 读反几乎只会放大 256 倍


def _parse_temperature(payload: bytes) -> Optional[float]:
    raw_be = (payload[4] << 8) | payload[5]
    raw_le = payload[4] | (payload[5] << 8)
    if raw_be == 0x8000 or raw_le == 0x8000:
        return None                      # -128 ℃ = 不支持
    t_be = _signed16(raw_be) / 256.0
    if TEMP_MIN_C <= t_be <= TEMP_MAX_C:
        return t_be                      # 规范和现场固件在这里都是大端
    t_le = _signed16(raw_le) / 256.0
    if TEMP_MIN_C <= t_le <= TEMP_MAX_C:
        return t_le
    return t_be


def parse_tlm_payload(payload: bytes, forced_little: Optional[bool] = None) -> Optional[Tlm]:
    """解析 14 字节明文 TLM 载荷（帧类型 0x20 开头）。

    forced_little：这颗 MAC 之前学到的字节序。给了就优先用，只有发现它读出荒谬电压、
                   另一方向才合理时（换固件了）才推翻。
    """
    if payload is None or len(payload) < 14 or payload[0] != 0x20:
        return None

    v_be = (payload[2] << 8) | payload[3]
    v_le = payload[2] | (payload[3] << 8)
    be_ok = VOLTAGE_MIN_MV <= v_be <= VOLTAGE_MAX_MV
    le_ok = VOLTAGE_MIN_MV <= v_le <= VOLTAGE_MAX_MV

    up_be = int.from_bytes(payload[10:14], "big")
    up_le = int.from_bytes(payload[10:14], "little")
    up_be_ok = up_be <= MAX_UPTIME_TENTHS
    up_le_ok = up_le <= MAX_UPTIME_TENTHS

    if forced_little is not None:
        little = forced_little
        certain = True
        chosen_ok = le_ok if little else be_ok
        other_ok = be_ok if little else le_ok
        if not chosen_ok and other_ok:
            little = not little          # 学到的方向不成立了，翻转重学
    elif v_be == 0 and v_le == 0:
        little = _better_little(up_be_ok, up_le_ok, up_be, up_le)
        certain = up_be_ok != up_le_ok
    elif be_ok != le_ok:
        little = le_ok                   # 只有一个方向说得通，铁证
        certain = True
    elif be_ok:
        little = True                    # 两端都合理，先按现场固件取小端
        certain = v_be == v_le
    else:
        little = v_le <= v_be
        certain = False

    voltage = v_le if little else v_be
    adv = int.from_bytes(payload[6:10], _order(little))
    sec_tenths = int.from_bytes(payload[10:14], _order(little))

    # 4 字节字段读反几乎必然爆表，爆了说明方向判定没站住，能换就换回来
    if sec_tenths > MAX_UPTIME_TENTHS:
        other = int.from_bytes(payload[10:14], _order(not little))
        if other <= MAX_UPTIME_TENTHS:
            little = not little
            certain = True
            voltage = v_le if little else v_be
            adv = int.from_bytes(payload[6:10], _order(little))
            sec_tenths = other

    return Tlm(voltage, _parse_temperature(payload), adv,
               sec_tenths // 10, little, certain)


def _eddystone_payload(service_data: Optional[Dict[str, bytes]]) -> Optional[bytes]:
    if not service_data:
        return None
    for uuid, payload in service_data.items():
        # bleak 给的是规范 128 位 UUID（0000feaa-...），有的平台给短名 / 大写 / 带横线
        u = uuid.lower().replace("-", "")
        if u in ("feaa", "0000feaa") or u.startswith("0000feaa"):
            return payload
    return None


def parse_tlm(service_data: Optional[Dict[str, bytes]],
              forced_little: Optional[bool] = None) -> Optional[Tlm]:
    """从 bleak 的 AdvertisementData.service_data 里解 TLM。"""
    return parse_tlm_payload(_eddystone_payload(service_data), forced_little)


# ---------------------------------------------------------------------------
# 字节序记忆（对应 Android 的 TlmEndian / shared_prefs/tlm_endian.xml）
# ---------------------------------------------------------------------------

class EndianStore:
    """按 MAC 记住 TLM 字节序，落一个 JSON 文件，进程重启还在。

    为什么需要：电压掉出 2700~3400 或偶发被干扰的包会让单帧变成「两端都不合理」，
    没有缓存的话方向会来回翻，电量数字跟着跳。
    """

    def __init__(self, path: str = "tlm_endian.json"):
        self.path = path
        self._map: Dict[str, bool] = {}
        try:
            with open(path, "r", encoding="utf-8") as f:
                for mac, v in json.load(f).items():
                    self._map[mac.upper()] = bool(v)
        except (IOError, ValueError):
            pass

    def get(self, mac: str) -> Optional[bool]:
        return self._map.get(mac.upper())

    def remember(self, mac: str, little: bool) -> bool:
        """记下方向；真变化了才写盘。返回是否发生变化。"""
        key = mac.upper()
        if self._map.get(key) is little:
            return False
        self._map[key] = little
        try:
            with open(self.path, "w", encoding="utf-8") as f:
                json.dump(self._map, f, ensure_ascii=False, indent=2, sort_keys=True)
        except IOError:
            pass
        return True


# ---------------------------------------------------------------------------
# 电量语义
# ---------------------------------------------------------------------------

def battery_level(mv: int) -> str:
    """返回 full / mid / low / unknown"""
    if mv <= 0:
        return "unknown"
    if mv >= FULL_MV:
        return "full"
    if mv >= MID_MV:
        return "mid"
    return "low"


def recommended_tlm_interval_ms(uuid: str) -> int:
    """龙雀固定 1500（它最多只能设到 1500），其它品牌给 6000。

    规范没有强制值：Eddystone 文档只说按用途定比例，Google 工程师给的示例是 10 秒。
    """
    if uuid and uuid.replace("-", "").upper().startswith(LONGQUE_UUID_PREFIX):
        return TLM_INTERVAL_LONGQUE_MS
    return TLM_INTERVAL_OTHER_MS


def distance_hint(rssi: int, tx_power: int = -57) -> float:
    """粗略距离（米）。只能定性看趋势，别当成真的米数。"""
    return 10.0 ** ((tx_power - rssi) / 20.0)


# ---------------------------------------------------------------------------
# 到家 / 离家状态机
# ---------------------------------------------------------------------------

class PresenceTracker:
    """滞回 + 持续时间 + 中位数，三件套缺一个都会误报。

    - 进门：最近 5 帧的中位数 ≥ enter_rssi，且持续 enter_hold_s
    - 出门：连续 exit_timeout_s 收不到包，或中位数 ≤ exit_rssi 且持续 exit_hold_s

    feed() 每帧调一次；没有包的时候每几秒调一次 tick()，靠它触发「离家」。
    """

    def __init__(self,
                 name: str = "kid",
                 enter_rssi: int = -75,
                 exit_rssi: int = -88,
                 enter_hold_s: float = 10.0,
                 exit_hold_s: float = 30.0,
                 exit_timeout_s: float = 60.0,
                 window: int = 5):
        self.name = name
        self.enter_rssi = enter_rssi
        self.exit_rssi = exit_rssi
        self.enter_hold_s = enter_hold_s
        self.exit_hold_s = exit_hold_s
        self.exit_timeout_s = exit_timeout_s
        self._buf = deque(maxlen=window)
        self.median: Optional[float] = None
        self.state = "away"
        self.last_seen: Optional[float] = None
        self._above_since: Optional[float] = None
        self._below_since: Optional[float] = None

    def feed(self, rssi: int, now: float) -> Optional[str]:
        """喂一帧。状态翻转时返回 'arrived' / 'left'，否则 None。"""
        self.last_seen = now
        self._buf.append(rssi)
        self.median = statistics.median(self._buf)

        if self.state == "away":
            self._below_since = None
            if self.median >= self.enter_rssi:
                if self._above_since is None:
                    self._above_since = now
                elif now - self._above_since >= self.enter_hold_s:
                    self.state = "home"
                    self._above_since = None
                    return "arrived"
            else:
                self._above_since = None
        else:
            self._above_since = None
            if self.median <= self.exit_rssi:
                if self._below_since is None:
                    self._below_since = now
                elif now - self._below_since >= self.exit_hold_s:
                    self._leave()
                    return "left"
            else:
                self._below_since = None
        return None

    def tick(self, now: float) -> Optional[str]:
        """没有新包时定期调一下，处理「一直收不到」的出门判定。"""
        if self.state != "home":
            return None
        if self.last_seen is None or now - self.last_seen >= self.exit_timeout_s:
            self._leave()
            return "left"
        return None

    def _leave(self) -> None:
        self.state = "away"
        self._buf.clear()
        self.median = None
        self._above_since = None
        self._below_since = None


if __name__ == "__main__":
    # 拿真机抓到的帧自测一遍：03 0C 是电压、1D 00 是温度、E5 06 00 00 是计数
    frame = bytes.fromhex("2000030C1D00E5060000" + "1C050000" + "00050006C7")
    t = parse_tlm_payload(frame)
    print("解析:", t)
    print("电量档位:", battery_level(t.voltage_mv))
    print("推荐 TLM 周期(龙雀):", recommended_tlm_interval_ms("E2C56DB5-DFFB-48D2-B060-D0F5A0469666"), "ms")
