#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""kidlink_proto 的断言测试。不需要蓝牙，随便哪台机器都能跑：

    python3 test_proto.py

用例全部来自真机抓包（信标 48:87:2D:9E:6D:FB）与现场踩过的坑，
改解析逻辑后先跑这个，别拿真信标当测试环境。
"""

import unittest

from kidlink_proto import (
    PresenceTracker,
    battery_level,
    parse_ibeacon,
    parse_tlm,
    parse_tlm_payload,
    recommended_tlm_interval_ms,
)
from kidlink_qr import (
    BeaconConfig,
    classify,
    decode,
    decode_or_explain,
    encode,
)

# 真机原始帧：电压 03 0C、温度 1D 00、计数 E5 06 00 00、运行 1C 05 00 00
# （末尾 00 05 00 06 C7 是厂商追加的 major/minor/tx，解析时忽略）
REAL_FRAME = bytes.fromhex("2000030C" + "1D00" + "E5060000" + "1C050000" + "00050006C7")


def _frame(voltage_hex: str, temp_hex: str = "1D00",
           adv_hex: str = "E5060000", up_hex: str = "1C050000") -> bytes:
    return bytes.fromhex("2000" + voltage_hex + temp_hex + adv_hex + up_hex)


class TestTlm(unittest.TestCase):

    def test_real_frame_little_endian(self):
        """现场固件是小端：03 0C 读成 3075 mV，不是 780。"""
        t = parse_tlm_payload(REAL_FRAME)
        self.assertEqual(t.voltage_mv, 3075)
        self.assertEqual(t.temp_c, 29.0)
        self.assertEqual(t.adv_count, 1765)
        self.assertEqual(t.uptime_s, 130)
        self.assertTrue(t.little_endian)
        self.assertTrue(t.endian_certain)

    def test_standard_big_endian_device(self):
        """标准 Eddystone 设备是大端：0B FC 读成 3068 mV，小端会读成 64523。"""
        t = parse_tlm_payload(_frame("0BFC"))
        self.assertEqual(t.voltage_mv, 3068)
        self.assertFalse(t.little_endian)
        self.assertTrue(t.endian_certain)

    def test_same_value_little_side(self):
        """FX 0B 是小端的 3068 mV；大端 64523 一眼假。"""
        t = parse_tlm_payload(_frame("FC0B"))
        self.assertEqual(t.voltage_mv, 3068)
        self.assertTrue(t.little_endian)

    def test_ambiguous_window_is_not_certain(self):
        """0B 0C：小端 3083 / 大端 2828，两端都在 2700~3400，只能算临时判定。"""
        t = parse_tlm_payload(_frame("0B0C"))
        self.assertEqual(t.voltage_mv, 3083)
        self.assertTrue(t.little_endian)
        self.assertFalse(t.endian_certain)

    def test_both_implausible_takes_smaller(self):
        """A0 0F：大端 40975 / 小端 4000，都不合理，取小的。"""
        t = parse_tlm_payload(_frame("A00F"))
        self.assertEqual(t.voltage_mv, 4000)
        self.assertFalse(t.endian_certain)

    def test_forced_endian_wins(self):
        """缓存说大端，但这帧只有小端合理 —— 说明换了固件，要推翻重学。"""
        t = parse_tlm_payload(_frame("030C"), forced_little=False)
        self.assertEqual(t.voltage_mv, 3075)
        self.assertTrue(t.little_endian)

    def test_forced_endian_kept_on_ambiguous_frame(self):
        """歧义帧上，缓存必须压过现场判定（这正是缓存存在的理由）。"""
        t = parse_tlm_payload(_frame("0B0C"), forced_little=False)
        self.assertEqual(t.voltage_mv, 2828)
        self.assertFalse(t.little_endian)

    def test_temperature_unsupported(self):
        t = parse_tlm_payload(_frame("030C", temp_hex="8000"))
        self.assertIsNone(t.temp_c)

    def test_negative_temperature(self):
        """-5 ℃ = 0xFB00（有符号 8.8 定点）"""
        t = parse_tlm_payload(_frame("030C", temp_hex="FB00"))
        self.assertAlmostEqual(t.temp_c, -5.0, places=2)

    def test_zero_voltage_is_unsupported(self):
        t = parse_tlm_payload(_frame("0000"))
        self.assertEqual(t.voltage_mv, 0)
        self.assertFalse(t.has_voltage())

    def test_encrypted_tlm_ignored(self):
        """帧类型 0x21 是 EID 加密 TLM，解不了，必须返回 None。"""
        self.assertIsNone(parse_tlm_payload(bytes.fromhex("2100030C1D00E50600001C050000")))

    def test_too_short(self):
        self.assertIsNone(parse_tlm_payload(bytes.fromhex("2000030C1D00")))

    def test_service_data_lookup(self):
        """bleak 给的 service_data 是 {uuid: payload}，UUID 大小写/长短不一。"""
        for key in ("0000feaa-0000-1000-8000-00805f9b34fb", "FEAA", "0000FEAA-0000-1000-8000-00805F9B34FB"):
            t = parse_tlm({key: REAL_FRAME})
            self.assertEqual(t.voltage_mv, 3075, key)


class TestIBeacon(unittest.TestCase):

    def test_real_beacon(self):
        """书包信标：UUID 尾三 hex 9666，major 5，minor 6，tx -57。"""
        payload = bytes.fromhex("0215E2C56DB5DFFB48D2B060D0F5A0469666" + "0005" + "0006" + "C7")
        b = parse_ibeacon({0x4458: payload})
        self.assertEqual(b.uuid, "E2C56DB5-DFFB-48D2-B060-D0F5A0469666")
        self.assertEqual(b.major, 5)
        self.assertEqual(b.minor, 6)
        self.assertEqual(b.tx_power, -57)
        self.assertEqual(b.company_id, 0x4458)

    def test_apple_company_id(self):
        payload = bytes.fromhex("0215E2C56DB5DFFB48D2B060D0F5A04696E0" + "0005" + "0007" + "C5")
        b = parse_ibeacon({0x004C: payload})
        self.assertEqual(b.uuid, "E2C56DB5-DFFB-48D2-B060-D0F5A04696E0")
        self.assertEqual(b.tx_power, -59)

    def test_ignores_other_manufacturer_data(self):
        self.assertIsNone(parse_ibeacon({0x0059: bytes.fromhex("0102030405")}))
        self.assertIsNone(parse_ibeacon({}))
        self.assertIsNone(parse_ibeacon(None))


class TestBatterySemantics(unittest.TestCase):

    def test_levels(self):
        self.assertEqual(battery_level(3070), "full")
        self.assertEqual(battery_level(3000), "full")
        self.assertEqual(battery_level(2950), "mid")
        self.assertEqual(battery_level(2900), "mid")
        self.assertEqual(battery_level(2828), "low")
        self.assertEqual(battery_level(0), "unknown")

    def test_tlm_interval_by_brand(self):
        self.assertEqual(recommended_tlm_interval_ms("E2C56DB5-DFFB-48D2-B060-D0F5A0469666"), 1500)
        self.assertEqual(recommended_tlm_interval_ms("E2C56DB5-DFFB-48D2-B060-ZZZZ"), 1500)
        self.assertEqual(recommended_tlm_interval_ms("F7826DA6-4FA2-4E98-8024-BC5B71E0893E"), 6000)


class TestPresence(unittest.TestCase):

    def test_arrives_after_hold(self):
        p = PresenceTracker(enter_hold_s=10.0)
        events = []
        for i in range(6):                      # 每 2 秒一帧，共 10 秒
            events.append(p.feed(-60, now=i * 2.0))
        self.assertEqual(events[-1], "arrived")
        self.assertEqual(p.state, "home")

    def test_brief_pass_by_does_not_trigger(self):
        """在门口路过 6 秒就走 —— 不该判定到家。"""
        p = PresenceTracker(enter_hold_s=10.0)
        for i in range(4):
            p.feed(-60, now=i * 2.0)
        self.assertEqual(p.state, "away")

    def test_leaves_on_timeout(self):
        """最后一个包在 t=10，超时 60 s —— t=70 起算离开。"""
        p = PresenceTracker(enter_hold_s=10.0, exit_timeout_s=60.0)
        for i in range(6):
            p.feed(-60, now=i * 2.0)
        self.assertEqual(p.state, "home")
        self.assertIsNone(p.tick(now=60.0))
        self.assertEqual(p.tick(now=70.0), "left")
        self.assertEqual(p.state, "away")

    def test_leaves_on_weak_rssi(self):
        """中位数掉到 -95 且持续 10 秒 —— 人走了，信号弱但没断。"""
        p = PresenceTracker(enter_hold_s=4.0, exit_rssi=-88, exit_hold_s=10.0)
        for i in range(3):
            p.feed(-60, now=i * 2.0)
        self.assertEqual(p.state, "home")
        self.assertIsNone(p.feed(-95, now=10.0))     # 中位数还是 -60
        self.assertIsNone(p.feed(-95, now=12.0))
        self.assertIsNone(p.feed(-95, now=14.0))     # 中位数终于到 -95，开始计时
        self.assertIsNone(p.feed(-95, now=20.0))
        self.assertEqual(p.feed(-95, now=24.0), "left")

    def test_hysteresis_band_does_nothing(self):
        """-80 dBm 落在滞回带里：既不算进也不算出，状态不动。"""
        p = PresenceTracker(enter_hold_s=4.0, exit_rssi=-88)
        for i in range(3):
            p.feed(-60, now=i * 2.0)
        self.assertEqual(p.state, "home")
        for i in range(5):
            self.assertIsNone(p.feed(-80, now=10.0 + i * 2.0))
        self.assertEqual(p.state, "home")


class TestQrCodec(unittest.TestCase):
    """信标二维码：手机上「分享此信标」出来的那段 KIDLINK:v1:..."""

    def _cfg(self, mac="48:87:2D:9E:6D:FB"):
        return BeaconConfig(name="书包", remark="一年级", uuid="E2C56DB5DFFB48D2B060D0F5A0469666",
                            major=5, minor=6, rssi_1m=-57, mac=mac)

    def test_round_trip(self):
        cfg = self._cfg()
        text = encode(cfg)
        self.assertTrue(text.startswith("KIDLINK:v1:"))
        back = decode(text)
        self.assertEqual(back.name, "书包")
        self.assertEqual(back.remark, "一年级")
        self.assertEqual(back.uuid, "e2c56db5dffb48d2b060d0f5a0469666")
        self.assertEqual(back.uuid_formatted, "E2C56DB5-DFFB-48D2-B060-D0F5A0469666")
        self.assertEqual((back.major, back.minor), (5, 6))
        self.assertEqual(back.rssi_1m, -57)
        self.assertEqual(back.mac, "48:87:2D:9E:6D:FB")

    def test_old_code_without_mac(self):
        """老二维码没有 mc 字段：靠三件套认，不能报错。"""
        cfg = self._cfg(mac="")
        back = decode(encode(cfg))
        self.assertEqual(back.mac, "")
        self.assertEqual(back.rssi_1m, -57)

    def test_matches_scanned_ibeacon(self):
        cfg = self._cfg()
        payload = bytes.fromhex("0215E2C56DB5DFFB48D2B060D0F5A0469666" + "0005" + "0006" + "C7")
        ib = parse_ibeacon({0x4458: payload})
        self.assertTrue(cfg.matches(ib))
        ib.minor = 7
        self.assertFalse(cfg.matches(ib))
        self.assertFalse(cfg.matches(None))

    def test_whitespace_and_missing_padding(self):
        """二维码/聊天软件常带换行，也可能吃掉 '=' 补齐符。"""
        text = encode(self._cfg())
        mangled = text.replace("=", "").strip()
        mangled = mangled[:20] + "\n" + mangled[20:]
        self.assertIsNotNone(decode(mangled))

    def test_rejects_other_kidlINK_codes(self):
        """整包码 / 设置码要给出人话提示，不能当成信标码硬解。"""
        for text, kind, hint in (
            ("KIDLINK:b1:abcd:1/1:AAAA", "bundle", "整套配置"),
            ("KIDLINK:cfg1:AAAA", "settings", "设置"),
            ("https://example.com", "unknown", "接娃助手"),
            ("", "unknown", "接娃助手"),
        ):
            self.assertEqual(classify(text), kind)
            cfg, err = decode_or_explain(text)
            self.assertIsNone(cfg)
            self.assertIn(hint, err)

    def test_rejects_short_uuid(self):
        import base64 as _b64
        import json as _json
        bad = "KIDLINK:v1:" + _b64.b64encode(
            _json.dumps({"u": "ABCD", "ma": 1, "mi": 2}).encode()).decode()
        self.assertIsNone(decode(bad))
        cfg, err = decode_or_explain(bad)
        self.assertIsNone(cfg)
        self.assertIn("UUID", err)

    def test_garbage_base64(self):
        self.assertIsNone(decode("KIDLINK:v1:!!!!not-base64!!!!"))


if __name__ == "__main__":
    unittest.main(verbosity=2)
