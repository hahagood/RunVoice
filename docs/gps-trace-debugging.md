# GPS 轨迹留档说明

## 目的

为每次跑步保存一份原始 GPS 轨迹 CSV，方便和其他跑步 App 的轨迹或距离结果做对比，定位距离偏差来自哪里。

## 文件位置

每次开始跑步时，应用会先新建一份轨迹文件，保存到应用外部专属目录:

`Android/data/com.runvoice/files/gps-traces/`

结束时选择 **保存海报和轨迹** 后，应用会生成摘要海报，并把轨迹复制一份到公共 Documents 目录:

`Documents/RunVoice/gps-traces/`

公共 Documents 目录不随 App 卸载或重装清除，适合作为长期留档和后续导出位置。App 专属目录仍作为本次跑步、断电续跑和结束页海报生成时的工作目录。

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
- `heart_rate`
- `hr_connected`

其中 `heart_rate` 和 `hr_connected` 记录该定位点写入时的实时心率值和心率带连接状态，可用于复盘心率数据是否中途丢失。

## decision / reason 含义

- `accepted / seed_point`
  第一个有效定位点，用来初始化轨迹。

- `accepted / distance_accumulated`
  该点被用于累计距离。

- `accepted / gps_confirmed_movement`
  加速度计判断静止，但 GPS 已经持续显示真实移动。恢复确认后，从进入静止锁定时的锚点到当前点补回一段直线位移，避免恢复确认期间漏计里程。

- `ignored / accuracy_gt_20m`
  精度太差，被丢弃。

- `ignored / jump_gt_100m`
  与有效定位锚点的距离超过 100 米，并且结合定位间隔推算出的速度也超过跑步上限；该点被视为异常点丢弃，后续点会同时进入隔离定位链以尝试安全恢复。若长时间精度不足后重新定位，但整段推算速度合理，则不会仅因位移超过 100 米而误判跳点。

- `ignored / jump_gt_100m_reanchored`
  连续异常点已经形成时序、速度均合理的定位链，跟踪器已在当前位置重新建立锚点，但不补入不确定的中间距离。下一个正常点可以恢复累计。

- `accepted / jump_gt_100m_bridged`
  跳点后的连续定位已经形成稳定链，并且从最后可信点到当前稳定点的平均直线速度仍处于恢复安全范围；按两个断点间的直线距离补齐里程。

- `ignored / speed_above_7_mps`
  基于定位单调时钟推导的单段速度超过 7 m/s；该点不累计距离，并进入隔离定位链。

- `ignored / speed_above_7_mps_reanchored`
  持续高速点彼此连续且物理速度合理，隔离链开始跟随当前位置。高速期间仍不累计距离，但速度回到跑步范围后可立即恢复，不会继续恶化为永久 `jump_gt_100m`。

- `accepted / speed_above_7_mps_bridged`
  连续超速点已经形成稳定定位链，并且最后可信点到当前稳定点的平均直线速度处于恢复安全范围；按两个断点间的直线距离一次性补齐，不逐点累计隔离期内的抖动折线。

- `ignored / speed_below_0_5_mps`
  非静止锁定状态下推导速度低于 0.5 m/s，按慢速漂移处理，不累计距离。

- `ignored / non_monotonic_location_time`
  定位样本乱序或单调时间没有前进；该点被拒绝，避免负时间间隔和异常速度。

- `ignored / stationary_candidate_gps_still`
  加速度计刚报告静止且 GPS 也没有移动，但尚未持续 5 秒；先忽略该点，不立即进入静止锁，避免起步瞬间的单个静止样本锁死计数。

- `ignored / stationary_resume_speed_above_limit`
  静止恢复候选位移结合静止锚点至当前点的完整时间后，推算速度仍超过跑步上限；保持静止锁定并从当前点重新确认，避免一次补入不合理距离。

- `ignored / stationary_gps_still`
  加速度计和 GPS 都判断当前基本静止，定位漂移不计入距离。

- `ignored / stationary_waiting_for_gps_confirmation`
  加速度计判断静止，GPS 出现移动迹象，但尚未持续达到确认阈值；先缓存观察，不立即计入距离。

旧版本的 `ignored / motion_detector_stationary` 表示只要加速度计判断静止就丢弃该点；这可能在手机运动很平稳时误删真实跑步距离。

## 导出示例

优先从公共 Documents 目录导出:

```bash
adb pull /sdcard/Documents/RunVoice/gps-traces ./gps-traces
```

也可以导出 App 专属工作目录:

```bash
adb pull /sdcard/Android/data/com.runvoice/files/gps-traces ./gps-traces
```

## 建议排查方法

1. 同一路线同时用 RunVoice 和其他 App 记录。
2. 跑完后导出 CSV。
3. 对比:
   - 是否大量点被 `accuracy_gt_20m` 丢弃
   - 是否有很多 `jump_gt_100m`
   - 是否 `stationary_gps_still` 触发过多
   - `accepted` 点的总距离是否明显偏小
