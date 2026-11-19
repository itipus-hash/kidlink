#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""信标二维码（单颗）编解码 —— 对应 Android 端的 qr/BeaconQrCodec.java。

## 格式

    KIDLINK:v1:<Base64(JSON)>

JSON 的 key 都是缩写（省二维码格子）：

| key | 含义 | 说明 |
|---|---|---|
| `n` | 名字 | 如「书包」 |
| `r` | 备注 | 可空 |
| `u` | UUID | **32 位小写 hex，不带横线** |
| `ma` | major | 整数 |
| `mi` | minor | 整数 |
| `rs` | 1 米标定 RSSI | 缺省 -59，用来算距离 |
| `mc` | 蓝牙 MAC | 同品牌信标三件套可能完全一样，靠它区分两台设备；老码可能没有 |

## 为什么 Mac 只需要这一种码

App 里还有两种二维码，**都不用移植**：

- `KIDLINK:b1:...`（整套配置，娃 + 全部信标，分页轮播）——换手机才用得到，Mac 只要一颗信标
- `KIDLINK:cfg1:...`（推送通道等设置）——是那台手机自己的事

Mac 端只要认出 `v1` 这一种，其余前缀直接明确拒绝（提示「这是整包/设置码」），
免得糊里糊涂解析出一堆没用的字段。

## 怎么把码给 Mac

手机上「分享信标」出的是二维码，内容就是这段字符串。三种给法都行：

1. 微信/备忘录里把文本复制出来，`--qr '<粘贴>'`
2. 存成文件：`--qr-file ~/kidlink_beacon.txt`
3. 手机扫码 → 复制文本 → AirDrop / 局域网共享过去

命令行直接解一下看看是什么：

    python3 kidlink_qr.py 'KIDLINK:v1:eyJuIjoi...'
"""

import base64
import binascii
import json
from dataclasses import dataclass, field
from typing import Optional

PREFIX_BEACON = "KIDLINK:v1:"
PREFIX_BUNDLE = "KIDLINK:b1:"
PREFIX_SETTINGS = "KIDLINK:cfg1:"

DEFAULT_RSSI_1M = -59


def _fmt_uuid(raw: str) -> str:
    h = raw.replace("-", "").upper()
    if len(h) != 32:
        return h
    return "{}-{}-{}-{}-{}".format(h[0:8], h[8:12], h[12:16], h[16:20], h[20:32])


def _b64decode(text: str) -> bytes:
    """容忍漏掉的补齐符与换行；标准/URL 安全两种字母表都试。"""
    s = "".join(text.split())
    pad = (-len(s)) % 4
    s += "=" * pad
    for decoder in (base64.b64decode, base64.urlsafe_b64decode):
        try:
            return decoder(s)
        except (binascii.Error, ValueError):
            continue
    raise ValueError("Base64 解不开")


@dataclass
class BeaconConfig:
    name: str = "未命名信标"
    remark: str = ""
    uuid: str = ""                  # 32 位小写 hex，不带横线（和 App 的 getUuidRaw 一致）
    major: int = 0
    minor: int = 0
    rssi_1m: int = DEFAULT_RSSI_1M
    mac: str = ""                   # 大写带冒号；老码可能为空
    raw: str = field(default="", repr=False)

    @property
    def uuid_formatted(self) -> str:
        return _fmt_uuid(self.uuid)

    def matches(self, ib) -> bool:
        """和一条解析出来的 iBeacon 比对三件套。"""
        if ib is None:
            return False
        return (ib.uuid.replace("-", "").lower() == self.uuid.replace("-", "").lower()
                and ib.major == self.major and ib.minor == self.minor)

    def __str__(self) -> str:
        return "{} {} {}/{} rs={} mac={}".format(
            self.name, self.uuid_formatted, self.major, self.minor,
            self.rssi_1m, self.mac or "(无)")


def classify(text: str) -> str:
    """beacon / bundle / settings / unknown"""
    if not text:
        return "unknown"
    s = text.strip()
    if s.startswith(PREFIX_BEACON):
        return "beacon"
    if s.startswith(PREFIX_BUNDLE):
        return "bundle"
    if s.startswith(PREFIX_SETTINGS):
        return "settings"
    return "unknown"


def decode(text: str) -> Optional[BeaconConfig]:
    """解析单颗信标码。不是本格式 / 字段不合法时返回 None。"""
    if classify(text) != "beacon":
        return None
    body = text.strip()[len(PREFIX_BEACON):]
    try:
        obj = json.loads(_b64decode(body).decode("utf-8"))
    except (ValueError, UnicodeDecodeError):
        return None
    if not isinstance(obj, dict):
        return None

    uuid = str(obj.get("u", "")).replace("-", "").lower()
    if len(uuid) < 32:
        return None                              # 和 App 一样：UUID 不合法就整条拒收
    mac = str(obj.get("mc", "")).strip().upper()
    return BeaconConfig(
        name=str(obj.get("n", "未命名信标")) or "未命名信标",
        remark=str(obj.get("r", "")),
        uuid=uuid,
        major=int(obj.get("ma", 0) or 0),
        minor=int(obj.get("mi", 0) or 0),
        rssi_1m=int(obj.get("rs", DEFAULT_RSSI_1M) or DEFAULT_RSSI_1M),
        mac=mac,
        raw=text.strip(),
    )


def decode_or_explain(text: str):
    """返回 (config, 错误说明)。给人看的那种说明，CLI 直接用。"""
    kind = classify(text)
    if kind == "beacon":
        cfg = decode(text)
        if cfg is None:
            return None, "格式对但内容解不开（Base64/JSON 坏了，或 UUID 不足 32 位）"
        return cfg, ""
    if kind == "bundle":
        return None, "这是「整套配置」码（KIDLINK:b1），Mac 只需要单颗信标码；在手机上选「分享此信标」"
    if kind == "settings":
        return None, "这是「设置」码（KIDLINK:cfg1），和信标无关"
    return None, "认不出来：不是接娃助手的二维码（应以 KIDLINK:v1: 开头）"


def encode(cfg: BeaconConfig) -> str:
    """反过来编码，方便 Mac 侧生成自己的配置码/写测试。"""
    obj = {
        "n": cfg.name,
        "r": cfg.remark,
        "u": cfg.uuid.replace("-", "").lower(),
        "ma": cfg.major,
        "mi": cfg.minor,
        "rs": cfg.rssi_1m,
    }
    if cfg.mac:
        obj["mc"] = cfg.mac.upper()
    payload = json.dumps(obj, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    return PREFIX_BEACON + base64.b64encode(payload).decode("ascii")


if __name__ == "__main__":
    import sys

    if len(sys.argv) < 2:
        demo = BeaconConfig(name="书包", remark="一年级", uuid="E2C56DB5DFFB48D2B060D0F5A0469666",
                            major=5, minor=6, rssi_1m=-57, mac="48:87:2D:9E:6D:FB")
        text = encode(demo)
        print("示例编码:", text)
        print("解码回来:", decode(text))
        sys.exit(0)

    cfg, err = decode_or_explain(sys.argv[1])
    if cfg is None:
        print("解析失败:", err)
        sys.exit(1)
    print("名字   :", cfg.name)
    print("备注   :", cfg.remark or "(空)")
    print("UUID   :", cfg.uuid_formatted)
    print("major  :", cfg.major)
    print("minor  :", cfg.minor)
    print("1米RSSI:", cfg.rssi_1m)
    print("MAC    :", cfg.mac or "(老码没有，只能靠三件套认)")
