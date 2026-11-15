# 接娃助手 · KidLink Beacon

基于 iBeacon 的接娃提醒 App。孩子书包里放一个蓝牙信标，家长手机装这个 App，走到校门口就能收到微信推送。

纯 Java、无第三方 BLE 库、单模块。个人自用项目，**不承诺响应 issue**。

## 它做什么

- 扫描 iBeacon，实时显示距离与信号强度
- **首页按娃显示，不按信标显示**：一个娃名下可以挂好几颗信标（书包一颗、奥数包一颗），
  谁信号强就用谁，小字标出「当前：奥数包」。奥数课只带奥数包的时候，屏幕上是同一个娃、同一个距离，不会变成另一块面板
- 进入设定的「接近距离」→ 微信推一条「妈妈距离许小鱼还有 28 米」
- 进入「接到距离」→ 推「妈妈接到许小鱼了（放学）」
  文案三层：信标设了场景标签就用它（「接到许小鱼（奥数）」），否则按时间段分组猜，都没有就只写「接到许小鱼」
- **推送冷却按娃算**：一趟带两个包出门，两个包先后进区也只推一条
- 推送发出后可自动退出 App，停扫描、撤通知
- 时段分组：上学 / 放学 / 兴趣班各自一套时间段，按当前时间精准匹配
- 二维码分享与导入配置，换手机不用重新配
- 两台手机各配各的信标，各推各的，互不干扰
- 指定 WiFi 不提醒：连上家里的 WiFi，推送 / 震动 / 悬浮提示全部停掉，同时冒一条「已暂停提醒」免得以为坏了；此时「自动最小化」也不生效，App 留在界面上
- 信标管理页在「一个信标都没有」时给出推荐型号（iBeacon，十来块），点一下去淘宝

## 下载安装

不想自己编译的话，直接下现成的 APK：

**[→ Releases 页面下载最新版](https://github.com/itipus-hash/kidlink/releases/latest)**

下载 `kidlink-vX.Y.Z.apk` 直接安装即可（debug 签名，需允许「安装未知来源应用」）。
Android 12 及以上，首次打开会申请蓝牙、位置信息、通知权限。

## 技术栈

| 项 | 说明 |
|---|---|
| 平台 | Android，minSdk 31 / targetSdk 34 |
| 语言 | Java |
| BLE | 原生 `BluetoothLeScanner`，自研 iBeacon 解析 |
| 推送 | PushPlus（HTTP POST，不依赖 FCM / APNs） |
| 存储 | SQLite + SharedPreferences |
| 保活 | 前台服务（`connectedDevice`）+ 常驻通知 |

## 构建

```bash
git clone https://github.com/itipus-hash/kidlink.git
cd kidlink
./gradlew assembleDebug
```

产物在 `app/build/outputs/apk/debug/app-debug.apk`。需要 JDK 17 与 Android SDK 34。

`local.properties` 不在仓库里，用 Android Studio 打开时会自动生成，或手动写 `sdk.dir=<你的 SDK 路径>`。

## 只支持 iBeacon，为什么

iBeacon 是 Apple 定的广播格式，每包里固定带三段标识：**UUID + Major + Minor**。出厂写死、每次广播一模一样，程序才认得出「这是我塞进书包的那一颗」。

Eddystone（Google 的另一套格式）和普通 BLE 设备做不到：

| 格式 | 问题 |
|---|---|
| Eddystone-UID | 命名空间 instance 可随意改写 |
| Eddystone-URL | 根本没有 Major / Minor |
| 很多普通 BLE 设备 | 随机 MAC / 轮换 ID，每次扫到的「身份」都不同 |
| 纯 BLE 外设 | 压根没有标识字段 |

结论就是：**扫得到，但没法稳定区分是哪一颗**，也就做不了「只对这一个推提醒」。所以解析器只认 iBeacon 包（`1A FF 4C 00 02 15`）。

买设备看两点就够：标题写「iBeacon」；用本 App 扫一下，能显示 UUID 的就能用。

## 几个踩过的坑

写在这里，省得下一个人再踩一遍。

**1. iBeacon 的 AD 长度字节是 `0x1A`，不是 `0x1B`**

标准 iBeacon 广播实测是 `02 01 06 | 1A FF 4C 00 02 15 ...`。长度字节统计的是「它之后的字节数」：AD Type(1) + CompanyID(2) + SubType(1) + SubLength(1) + Payload(21) = 26 = `0x1A`。写成 `0x1B` 会导致所有标准 iBeacon 包被直接跳过，表现为「扫得到设备但一个都解析不出来」。

**2. 故意不传 `ScanFilter`**

`startScan(null, ...)` 扫全部广播，过滤全在软件层做。原因是部分 ROM（华为、部分 MTK）对 `setManufacturerData` 的过滤实现不一致，会导致信标明明在广播却一条都收不到。硬件过滤省的那点电，不值得换来「扫不到」。

**3. 推送的语义是「进入事件」，不是「距离状态」**

早期版本按「当前距离小于阈值」推送，结果冷却到期后会为同一个静止状态再推一次——孩子回家了，人一直待在接近距离内，每过一个冷却周期就响一次。

改成事件模型：进区 → 落一个持久标记（`approach_inside` / `arrival_inside`）→ 推不推都只处理这一次；只有距离真的超过「阈值 + 3 米」才清标记。站在旁边两小时、信号断了又接上、App 重启，都不会重推。

**4. `inside` 标记必须落盘，不能只放内存**

只放内存的话，`renderLost()`（信号丢失）一跑就复位，断线 15 秒接回来又推一次。落进 SharedPreferences 后，跨重启也准。

**5. 冷却时间戳要分两个**

「接近推送」在 50 米就发生，「接到推送」在 2 米才发生。如果共用一个 `last_push_at`，接近推送永远写不进去（因为到不了 2 米），冷却形同虚设。现在用 `last_approach_at` 与 `last_push_at` 取更近者，外加 90 秒全局最小间隔挡住 RSSI 抖动。

**6. 拖动排序不能用 `removeView` + `addView`**

`ViewGroup.removeViewInternal()` 里会调 `cancelTouchTarget(view)`，发一个 `ACTION_CANCEL`，手势当场中断——表现就是「拖过一行就自己跳出去」。改用 `detachViewFromParent` / `attachViewToParent`（protected，需要子类暴露一个 `moveChild`），只动子 View 数组，不打断触摸。

**7. 前台服务用 `START_NOT_STICKY`**

扫描逻辑在 Activity 里，服务只负责挂通知吊住进程。用 `START_STICKY` 的话，进程被杀后系统会把服务拉起来，但没有任何扫描在跑，只在通知栏留一条点不掉的通知，而且会让「接送成功后退出」退不干净。

**8. 匹配时间用精准区间，不给宽限**

早先给时间区间加了 30 分钟宽限，结果 8:10 也会命中「8:30 放学」。改成 `minuteOfDay` 直接落在区间内才算，晚接几分钟就退回通用文案。

**9. 读 WiFi 名称（SSID）算「位置信息」**

Android 10 起 `WifiInfo.getSSID()` 被归类为位置数据，必须同时满足两条：应用有 `ACCESS_FINE_LOCATION`，且系统「位置信息」总开关是打开的。缺任何一条，返回的不是名字而是 `<unknown ssid>`。所以「指定 WiFi 不提醒」里把占位串当「读不到」处理，并明确告诉用户是缺权限还是缺开关——静默失败会让人以为功能坏了。

**10. WiFi 屏蔽管的是「提醒」，不是「推送」**

它要盖住三样东西：微信推送、变色震动、底部悬浮提示。所以判定不能只塞在 `PushClient` 前面，而是要分别落在三个触发点。另外顺序也有讲究：`inside` 标记负责「这次进入只算一次」，WiFi 屏蔽负责「这次不打扰你」。先判断 WiFi 再落 `inside`，人在家被挡住，出了门网络一变就补推一条迟到的「接到」。先落状态再判断，语义才是「这次到过，只是没吵你」。

## 许可证

[MIT](LICENSE)

## 关于捐赠

本项目完全免费开源，无付费解锁、无强制收费。

如果觉得对你有帮助，可以自愿支持。捐献只代表心意，不会解锁任何功能，也不会因此获得新版本、新特性或优先支持。

支付宝：`supit@163.com`
