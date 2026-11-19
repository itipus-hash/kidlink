# KidLink 协议移植包

从 Android 端「接娃助手」（`com.kidlink.beacon`，v1.6）抽出来的**纯逻辑**，给 Mac 侧的在家检测服务复用。

## 这里有什么

| 文件 | 内容 |
|---|---|
| `PROTOCOL.md` | 广播帧格式、字节序坑、实测数据、判定参数。**先读这个** |
| `python/kidlink_proto.py` | 可运行参考实现：iBeacon 解析、TLM 解析（含字节序自动判定）、到家/离家状态机 |
| `python/kidlink_qr.py` | **信标二维码编解码**：手机上「分享此信标」得到的 `KIDLINK:v1:...`，Mac 端解出三件套/MAC/标定 RSSI |
| `python/test_proto.py` | 用真机抓到的原始帧做的断言，`python3 test_proto.py` 直接跑 |
| `python/scan_probe.py` | 实测脚本 + 常驻守护：`--minutes` 量 RSSI，`--watch` 做判定并写状态文件 / 回调 webhook |
| `python/requirements.txt` | 只依赖 `bleak` |

## 这里没有什么（故意不给）

- Android 的 `BeaconScanner` / Activity / SQLite / 推送——全部绑死 Android API，Mac 上没意义。
- **整套配置导入**（`KIDLINK:b1:...`，娃 + 全部信标，分页轮播）和**设置码**（`KIDLINK:cfg1:...`）——那是「换手机 / 同步另一台手机」用的，Mac 只要一颗信标。这两种前缀在 `kidlink_qr.py` 里会被明确拒绝并提示，不会误解析。
- 电池 GATT 读取（`0xFFE0`/`0x2A19`）——已证实是假数据（恒 100%），别再踩。
- `neverForLocation` 那套权限问题——macOS 没有这个概念。

需要看 Android 原始实现时，对应文件在仓库里：

```
app/src/main/java/com/kidlink/beacon/ble/TlmParser.java      # TLM 解析（含字节序判定）
app/src/main/java/com/kidlink/beacon/ble/TlmEndian.java      # 按 MAC 记忆字节序
app/src/main/java/com/kidlink/beacon/ble/IBeaconParser.java  # iBeacon 解析
app/src/main/java/com/kidlink/beacon/ble/BeaconScanner.java  # 扫描节奏（9 秒重启）
app/src/main/java/com/kidlink/beacon/data/BatteryStore.java  # 电量档位与采样窗口
app/src/main/java/com/kidlink/beacon/data/BeaconBrand.java   # 龙雀判别
```

## 在 Mac 上跑起来

```bash
cd porting/python
python3 -m venv .venv && . .venv/bin/activate
pip install -r requirements.txt

python3 test_proto.py                       # 1. 先确认解析逻辑没问题（不需要蓝牙）

# 2. 把手上的信标码解一下，确认认的是对的那颗（不接蓝牙也能跑）
python3 kidlink_qr.py 'KIDLINK:v1:eyJuIjoi...'

# 3. 实测：让娃带着书包进出门各一次
python3 scan_probe.py --minutes 30 --qr-file ~/kidlink_beacon.txt
```

**信标码怎么拿到 Mac**：手机 App 里选「分享此信标」→ 出来的二维码内容就是一段
`KIDLINK:v1:...` 文本。微信/备忘录复制出来，或者截图后用微信「提取文字」，
存成 `kidlink_beacon.txt` 传过去即可（一行文本，几十个字符）。

`scan_probe.py` 会打印：

```
[48:87:2D:9E:6D:FB] iBeacon E2C56DB5-...-9666  5/6   frames=41  rssi med=-72 min=-81 max=-64
[48:87:2D:9E:6D:FB] TLM                   3070mV  27.0C  frames=18
```

拿到真实 RSSI 分布再定门限，别拍脑袋。默认门限（进 -75 / 出 -88）只是起点。

## 常驻（launchd）

`~/Library/LaunchAgents/cn.kidlink.home.plist`：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>cn.kidlink.home</string>
  <key>ProgramArguments</key>
  <array>
    <string>/Users/你/Projects/kidlink/porting/python/.venv/bin/python3</string>
    <string>/Users/你/Projects/kidlink/porting/python/scan_probe.py</string>
    <string>--watch</string>
    <string>--qr-file</string>
    <string>/Users/你/kidlink_beacon.txt</string>
    <string>--status</string>
    <string>/tmp/kidlink_status.json</string>
  </array>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
  <key>StandardOutPath</key><string>/tmp/kidlink-home.log</string>
  <key>StandardErrorPath</key><string>/tmp/kidlink-home.err</string>
</dict>
</plist>
```

```bash
launchctl load ~/Library/LaunchAgents/cn.kidlink.home.plist
```

**首次运行必须授权蓝牙**：系统设置 → 隐私与安全性 → 蓝牙 → 勾上那个 python 解释器（或终端）。
注意授权是绑在**解释器路径**上的，换 venv 或换 Python 版本要重新授权——这是最常见的「昨天还好今天收不到」原因。

## 三个已知坑（都是实测踩过的）

1. **扫描会被降频**。Android 上连续扫十几秒包数就塌（0.48 包/秒），靠每 9 秒停/启一次救回来。macOS 的 CoreBluetooth 没这么激进，但**长时间扫描仍建议定期重建 scanner**（`scan_probe.py` 每 10 分钟重建一次），代价是几毫秒。
2. **别用 RSSI 单帧判距离**。同一位置 RSSI 抖动 ±10 dBm 是常态，必须中位数 + 滞回 + 持续时间。
3. **TLM 是加密的就读不了**。帧类型 `0x21` 是 EID 加密 TLM，没有厂商密钥解出来是噪声，直接当「无电量信息」处理，别硬解。
