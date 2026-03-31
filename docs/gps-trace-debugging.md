# GPS 轨迹留档说明

## 目的

为每次跑步保存一份原始 GPS 轨迹 CSV，方便和其他跑步 App 的轨迹或距离结果做对比，定位距离偏差来自哪里。

## 文件位置

每次开始跑步时，应用会新建一份轨迹文件，保存到应用外部专属目录:

`Android/data/com.runvoice/files/gps-traces/`

文件名示例:

`run-20260331-143501.csv`

## CSV 内容

每一行代表一个收到的定位点，包含:

- `timestamp`
- `latitude`
- `longitude`
- `accuracy_m`
- `speed_mps`
- `bearing_deg`
- `altitude_m`
- `provider`
- `motion_state`
- `decision`
- `reason`
- `delta_m`
- `total_distance_m`
- `segment_distance_m`
- `pace_sec_per_km`

## decision / reason 含义

- `accepted / seed_point`
  第一个有效定位点，用来初始化轨迹。

- `accepted / distance_accumulated`
  该点被用于累计距离。

- `ignored / accuracy_gt_20m`
  精度太差，被丢弃。

- `ignored / jump_gt_100m`
  与前一点距离跳变过大，被视为异常点丢弃。

- `ignored / motion_detector_stationary`
  加速度计判断当前基本静止，定位漂移不计入距离。

## 导出示例

用 adb 导出整个目录:

```bash
adb pull /sdcard/Android/data/com.runvoice/files/gps-traces ./gps-traces
```

## 建议排查方法

1. 同一路线同时用 RunVoice 和其他 App 记录。
2. 跑完后导出 CSV。
3. 对比:
   - 是否大量点被 `accuracy_gt_20m` 丢弃
   - 是否有很多 `jump_gt_100m`
   - 是否 `motion_detector_stationary` 触发过多
   - `accepted` 点的总距离是否明显偏小
