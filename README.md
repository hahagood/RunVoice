# RunVoice — 轻量跑步语音播报 APP

跑步时不想看手机？RunVoice 通过蓝牙耳机**语音播报**跑步数据，让你专注于跑步本身。

## 功能

- **GPS 追踪** — 实时距离累加、配速计算（FusedLocationProvider，2s 间隔，精度过滤）
- **BLE 心率** — 标准蓝牙心率带（0x180D 协议），扫描/配对/自动重连
- **语音播报** — 每跑完 1 公里中文语音播报：距离、用时、心率、配速
- **前台服务** — 息屏后 GPS/BLE/计时器持续运行，通知栏显示状态
- **极简 UI** — 深色背景、大字体（42sp）、高对比度，三态按钮操作

## 截图

```
┌─────────────────────────┐
│       RunVoice           │
│  心率带: 已连接           │
│                          │
│  时间     03:25          │
│  心率     142 bpm        │
│  配速     6'15"/km       │
│  距离     0.52 km        │
│                          │
│  [ ⏹  停止跑步 ]         │
└─────────────────────────┘
```

## 按钮交互

1. **开始跑步**（绿色大按钮）→ 进入跑步中
2. **停止跑步**（红色大按钮）→ 计时暂停，数据保留
3. **继续跑步** / **结束跑步**（两个按钮）→ 恢复跑步 或 结束本次

## 语音播报示例

> "已跑2公里，用时12分30秒，当前心率142，配速6分15秒"

## 技术栈

- Kotlin + Jetpack Compose
- Android Foreground Service
- FusedLocationProviderClient（Google Play Services）
- BLE 标准心率协议
- Android TextToSpeech（中文）

## 项目结构

```
app/src/main/java/com/runvoice/
├── MainActivity.kt              # 入口 + 导航 + 权限处理
├── ui/
│   ├── RunScreen.kt             # 跑步主界面
│   └── HrDeviceScreen.kt       # 心率带扫描/连接
├── service/
│   └── RunningService.kt       # 前台服务，整合所有模块
├── tracker/
│   ├── GpsTracker.kt           # GPS 定位 + 距离/配速
│   ├── HeartRateMonitor.kt     # BLE 心率
│   └── RunTimer.kt             # 计时器
├── voice/
│   └── VoiceAnnouncer.kt       # TTS 语音播报
└── model/
    └── RunData.kt              # 数据模型
```

## 权限

| 权限 | 用途 |
|------|------|
| `ACCESS_FINE_LOCATION` | GPS 定位 |
| `FOREGROUND_SERVICE` | 息屏后持续运行 |
| `BLUETOOTH` / `BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT` | 连接心率带 |
| `POST_NOTIFICATIONS` | 前台服务通知 |

## 构建

```bash
export JAVA_HOME=/path/to/jdk17
export ANDROID_HOME=/path/to/android-sdk
./gradlew assembleDebug
```

APK 输出：`app/build/outputs/apk/debug/app-debug.apk`

## 安装

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 使用

1. 打开 APP，授予定位权限（蓝牙权限可选）
2. （可选）点击"心率带: 未连接"进入配对页面，扫描并选择心率带
3. 点击"开始跑步"，放入口袋，戴上蓝牙耳机
4. 每跑完 1 公里自动语音播报
5. 跑完后点"停止跑步" → "结束跑步"

## 要求

- Android 8.0+（API 26）
- Google Play Services（GPS）
- 蓝牙 4.0+（心率带功能）

## Roadmap

### GPS 轨迹平滑算法

当前采用精度过滤（>20m 丢弃）+ 跳跃检测（>100m 忽略）。后续计划加入：

- **卡尔曼滤波** — 融合 GPS 位置与速度，平滑轨迹抖动，减少静止/慢跑时的漂移虚高
- **速度异常检测** — 根据前后点速度突变判断 GPS 跳点，插值修正而非简单丢弃
- **角度异常检测** — 过滤不合理的急转角（如 180° 折返），避免信号反射导致的锯齿路径
- **自适应采样** — 弯道密集区域提高采样频率，直道降低，兼顾精度与功耗

### 心率区间控制

基于最大心率（220 - 年龄）划分训练区间，跑步中实时语音提醒：

| 区间 | 心率范围 | 用途 | 语音提示 |
|------|---------|------|---------|
| 热身区 | 50-60% HRmax | 热身/恢复 | — |
| 燃脂区 | 60-70% HRmax | 减脂慢跑 | — |
| 有氧区 | 70-80% HRmax | 耐力训练 | — |
| 无氧区 | 80-90% HRmax | 速度训练 | "心率偏高，注意控制" |
| 极限区 | 90-100% HRmax | 冲刺 | "心率过高，请减速" |

- 用户设置年龄和目标区间，超出上限/下限时语音警告
- 警告间隔可配置（如每 30 秒最多提醒一次，避免频繁打扰）

## 许可证

[MIT License](LICENSE)
