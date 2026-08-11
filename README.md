# RunVoice

轻量、离线优先的 Android 跑步语音播报应用。跑步时无需反复查看手机，RunVoice 会通过手机扬声器或蓝牙耳机播报距离、时间、配速和心率。

当前稳定里程碑：**v1.0.7**。项目面向 Android 8.0+，核心记录链路不依赖账号、云服务或在线地图。

[下载最新 Release](https://github.com/hahagood/RunVoice/releases/latest)

<img src="screenshot.png" width="300" alt="RunVoice 跑步主界面" />

## 核心能力

### 跑步记录

- 使用 `FusedLocationProviderClient` 每 2 秒采样 GPS，实时累计距离并计算配速。
- 过滤低于 0.5 m/s 的静止漂移、超过 7 m/s 的异常速度及明显定位跳点。
- 每 50 米生成配速样本，对最近 5 段取中位数；暂停或续跑后清空旧配速窗口。
- 结合加速度传感器与 GPS 判断静止：两者持续静止 5 秒才锁定计数，恢复前需确认持续位移和稳定速度。
- 异常定位链会隔离、确认并安全重锚；异常持续 15 秒才提醒，稳定 10 秒后才播报恢复。
- 自动发现局部闭环并固定圈锚点，返回入口时播报上一段平均配速和距离。

### 心率与语音

- 支持标准 BLE 心率服务 `0x180D`，扫描、配对、连接超时及阶梯退避重连。
- 心率带在跑步中断开 5 秒后提醒，订阅恢复后播报一次恢复状态。
- 心率连续超过 180 bpm 达 3 秒后发出安全提醒；同一次持续超标只提醒一次。
- 每个整公里播报距离、用时、心率和当前配速。
- 在每公里的 0.25 / 0.50 / 0.75 km 处播报当前配速和心率。
- 支持蓝牙耳机单击媒体键即时播报当前时间、距离、用时、心率、最大心率和平均配速。
- 所有 TTS 前播放极短提示音预热，降低部分手机或耳机吞掉句首的概率；TTS 故障会自动重建并播报恢复状态。
- 内置 160–220 BPM 步频节拍器，使用 `AudioTrack` 硬件时钟避免累计误差。

### 数据可靠性

- 前台服务在息屏后继续运行 GPS、BLE、计时和播报。
- 运行中每 5 秒先刷新 GPS CSV，再原子写入 checkpoint。
- 进程终止、强制停止或手机断电后，再次打开应用可继续原记录或放弃并开始新记录。
- 续跑不会计入应用停止期间的时间，也不会跨数据空档补一条直线距离；第一个新定位点只用于建立锚点。
- 跑步期间保存原始定位 CSV，包含接受/过滤决策、原因、累计距离、分段配速、心率和连接状态。
- 结束后可一次保存摘要海报和 GPS 轨迹；未保存并返回首页时会删除本次临时轨迹。

## 基本使用

1. 打开应用并授予精确定位权限。蓝牙和通知权限按需授予。
2. 可选：进入“心率监控”，扫描并连接标准 BLE 心率设备。
3. 可选：开启节拍器并调整 BPM；开关与 BPM 会被记忆。
4. 点击“开始跑步”，锁屏后放入口袋即可。
5. 跑步中点击“暂停跑步”，可继续、结束，或“暂存本次跑步并退出”。
6. 结束确认页可选择“保存海报和轨迹”，或直接返回首页放弃未保存记录。
7. 若启动时发现 checkpoint，选择继续上次跑步或删除旧记录并开始新的跑步。

## 播报示例

整公里：

> 已跑2公里，用时12分30秒，心率：幺四二，配速：六一五

四分之一公里节点：

> 配速：六一零，心率：幺四二

耳机按键即时播报：

> 现在时间14点5分，当前已跑2.35公里，用时12分30秒，心率：幺四二，最大心率：幺五六，平均配速：五一八

闭环分段：

> 第一段完成，平均配速：五一八，距离800米。第二段开始

为缩短提示，配速采用“分钟 + 两位秒数”的逐位读法，例如 3:46 播报为“三四六”；心率也逐位播报，例如 155 播报为“幺五五”。活动超过 1 小时后，用时会包含小时。

## 数据保存与隐私

临时 GPS 轨迹默认位于应用专属目录：

```text
/sdcard/Android/data/com.runvoice/files/gps-traces/
```

选择“保存海报和轨迹”后，CSV 会另存到：

```text
/sdcard/Documents/RunVoice/gps-traces/
```

可通过 ADB 导出：

```bash
adb pull /sdcard/Documents/RunVoice/gps-traces ./gps-traces
```

公共 Documents 副本不会随应用卸载而删除。CSV 含精确经纬度、时间、速度、海拔和心率，可能暴露住所、工作地点与健康信息；分享、提交 Issue 或上传轨迹前请先脱敏。

字段、过滤原因和断电残行修复说明见 [GPS 轨迹留档说明](docs/gps-trace-debugging.md)。应用已关闭 Android 云备份和设备迁移备份，避免系统自动复制私密轨迹与 checkpoint。

## 摘要海报

- 有至少 2 个有效 GPS 点时，使用 CSV 中 `decision=accepted` 的点绘制完整航迹。
- 闭环复跑使用不同颜色和分层偏移，往返路线会拆分为相邻层，连续多圈后的退出线保持独立层级。
- 日期、距离、时间、平均配速和最大心率最后绘制，并使用自适应宽度的半透明衬底保证可读性。
- 轨迹点不足时自动降级为纯摘要海报。
- 当前版本不加载在线地图瓦片，离线底图仍属于后续规划。

## 技术栈

- Kotlin、Jetpack Compose、Coroutines / Flow
- Android Foreground Service、MediaSession、TextToSpeech、AudioTrack
- Google Play Services Location
- Android BLE GATT 标准心率协议
- Canvas / MediaStore 图片生成与保存
- JUnit 4 JVM 单元测试

## 项目结构

```text
app/src/main/java/com/runvoice/
├── MainActivity.kt                    # 权限、服务绑定、导航与恢复入口
├── core/
│   ├── RunSessionController.kt        # 跑步会话状态机
│   ├── TrackingEngine.kt              # GPS 接受、距离、静止与配速决策
│   ├── LapDetector.kt                 # 局部闭环与固定虚拟计时门
│   ├── AnnouncementPolicy.kt          # 公里和阶段播报边界
│   └── HeartRateAlertPolicy.kt        # 持续高心率告警策略
├── recovery/
│   └── RunCheckpoint.kt               # checkpoint 编解码与原子存储
├── service/
│   └── RunningService.kt              # 前台服务及模块编排
├── tracker/
│   ├── GpsTracker.kt                  # Android 定位适配
│   ├── GpsTraceRecorder.kt            # GPS CSV 记录与公共导出
│   ├── RecoveryTraceCsv.kt            # 断电残行修复与轨迹重放
│   ├── MotionDetector.kt              # 加速度静止检测
│   ├── HeartRateMonitor.kt            # BLE 心率与自动重连
│   └── RunTimer.kt                    # 单调时钟计时
├── voice/
│   ├── VoiceAnnouncer.kt              # TTS 队列、预热与自恢复
│   ├── VoiceStatsText.kt              # 配速和心率逐位读法
│   └── Metronome.kt                   # PCM 步频节拍器
├── share/                              # CSV 解析、轨迹几何与海报存储
└── ui/                                 # 跑步、恢复、心率和关于页面
```

核心状态机、GPS 决策、恢复格式、播报边界和轨迹几何尽量保持为可在 JVM 中测试的纯 Kotlin 代码。

## 权限与环境要求

| 项目 | 要求 / 用途 |
| --- | --- |
| Android | 8.0+（API 26） |
| Google Play Services | 融合定位 |
| `ACCESS_FINE_LOCATION` | 跑步 GPS 记录，必需 |
| `FOREGROUND_SERVICE_LOCATION` | 息屏后继续定位 |
| `BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT` | BLE 心率，可选 |
| `POST_NOTIFICATIONS` | Android 13+ 前台服务通知 |
| JDK | 17 |

## 构建与验证

设置 Android SDK 和 JDK 17 后执行：

```bash
export JAVA_HOME=/path/to/jdk17
export ANDROID_HOME=/path/to/android-sdk
./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease
```

产物：

```text
app/build/outputs/apk/debug/app-debug.apk
app/build/outputs/apk/release/app-release-unsigned.apk
```

安装 Debug 版本：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Gradle 生成的 Release APK 默认未签名。GitHub Releases 中的测试 APK 使用项目既有的 Android Debug 证书签名，可覆盖测试设备上的旧版本并保留应用数据，但不作为应用商店生产签名。

## 发布约定

- `versionName` 使用语义版本号，例如 `1.0.7`；标签和 Release 标题使用对应的 `v1.0.7`。
- `versionCode` 每次发布递增。
- APK 文件名固定为 `RunVoice-v<version>-<MD5前8位>.apk`。
- Release 说明记录主要变更、验证结果、APK 的 MD5 / SHA-256 以及签名用途。

## 已知边界与后续计划

- 当前只保存 GPS CSV 和摘要海报，尚无单次跑步摘要数据库、历史列表或周/月汇总。
- 180 bpm 为固定安全提示阈值，不是个性化医学或训练建议。
- 当前海报没有地图底图；后续若加入地图，优先评估离线数据方案，不直接依赖 OpenStreetMap 公共在线瓦片。
- 真实设备上的定位质量、BLE 稳定性和后台限制仍受厂商系统、电源策略与硬件影响。

完整计划见 [Roadmap](docs/roadmap.md)。

## License

[MIT License](LICENSE)
