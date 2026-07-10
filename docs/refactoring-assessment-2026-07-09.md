# RunVoice 代码审查与重构评估备忘录

- 日期：2026-07-09
- 审查基线：`main` 分支当前 working tree（包含现有未提交修改）
- 结论：**有必要做定向、分阶段重构；没有必要重写。**
- 建议时点：先处理本文的高风险缺陷，再在新增“跑步历史、汇总分析”等 Roadmap 功能前完成核心边界拆分。

## 结论摘要

当前版本可以编译，已有模块划分也足以支撑渐进式改造。真正推动重构的不是文件行数，而是三类可靠性问题叠在了一起：

1. 跑步会话、前台服务和 Activity 绑定没有单一状态机，进程重建、重复命令和停止流程存在矛盾。
2. GPS、BLE、保存流程含有确定性正确性或兼容性缺陷，但核心逻辑无法脱离 Android 运行，也没有自动化测试。
3. UI 直接依赖具体 Service 和模块对象，保存成功、扫描状态、节拍器状态等缺少统一的结果与错误模型。

因此建议做“先止血、再抽纯核心、最后整理 UI”的定向重构。不要同时改算法阈值、界面样式和模块结构，也不建议现在引入多模块、数据库或大型依赖注入框架。

## 审查与验证范围

- 人工审查 15 个 Kotlin 生产文件，共约 3,851 行。
- 重点检查了 Service/Activity 生命周期、GPS 与计时、BLE、TTS/节拍器、轨迹保存、截图渲染和 Compose 状态流。
- 执行：`./gradlew testDebugUnitTest lintDebug assembleDebug --stacktrace`。
- 结果：构建成功；`lintDebug` 为 0 error、14 warning；`testDebugUnitTest` 为 `NO-SOURCE`，即没有单元测试可执行。
- 只对现有两份 GPS CSV 做了不输出坐标的聚合检查；未做真机权限矩阵、进程杀死恢复、磁盘满或配置变化测试。

## 需要先处理的高风险问题

### 1. GPS 的速度过滤没有过滤距离

`GpsTracker.kt:111-129` 只拒绝单段大于 100 米的跳点；`GpsTracker.kt:245-247` 会累计其余距离。`MIN_SPEED_MPS/MAX_SPEED_MPS` 只在 `GpsTracker.kt:249-264` 决定是否更新配速，没有决定该段距离是否接受。

这与 `README.md:193-194` 所述“速度大于 7 m/s 时丢弃该段”不一致。现有轨迹聚合中也出现过推导速度约 8.36 m/s、仍标记为 `accepted` 的段。另一个问题是 `GpsTracker.kt:88-91` 在判定跳点前就推进 `lastLocation`，一个坏点可能连带丢弃回到真实轨迹的下一点。

影响：距离、公里播报和平均配速可能一起偏高；继续调阈值会增加回归风险。

### 2. Service 宣称可粘性恢复，实际会丢失会话

`RunningService.kt:63-99` 的跑步状态全部在内存；`RunningService.kt:103-121` 对系统重建时的空 Intent 不做恢复，却返回 `START_STICKY`。只有 `ACTION_START` 才会重新启动前台通知、计时和 GPS。

同时，开始、保存和放弃路径在 `MainActivity.kt:99-103,132-143,190-197` 多次绑定同一个 Service，`MainActivity.kt:206-210` 最多解绑一次。绑定客户端仍存在时，`stopSelf()` 不会立即销毁 Service。`startRun/pauseRun/resumeRun` 也没有状态转换校验，重复开始可清零有效会话。

影响：长跑途中进程被回收后可能静默归零；停止后资源可能滞留；双击或迟到命令可能触发非法状态。

### 3. BLE 的“可选能力”没有可选能力所需的保护

权限回调只检查定位（`MainActivity.kt:67-72`），但 Service 创建时会自动连接已保存设备（`RunningService.kt:96-98`）；扫描和连接使用 `@SuppressLint("MissingPermission")` 后直接调用受保护 API（`HeartRateMonitor.kt:47-60,103-114`）。Android 12+ 拒绝或撤销蓝牙权限时存在 `SecurityException` 风险。

此外，`HeartRateMonitor.kt:116-136` 主动断开后，任意断开回调都会再次 `g.connect()`；“断开并清除”、切换设备和销毁资源可能反向触发重连。低延迟扫描没有超时，系统返回手势也不会经过页面自定义的停止扫描回调。

影响：可选心率功能可能拖垮核心跑步流程，并造成持续扫描、重连和耗电。

### 4. 保存流程会假报成功，旧系统公共导出存在缺口

项目支持 API 26，但 Manifest 没有旧版公共存储写权限；`GpsTraceRecorder.kt:171-181` 在 API 26–28 直接写公共 Documents。失败只写日志，`RunScreen.kt:85-90` 仍无条件标记“数据已保存”。即使 Service 已断开，`MainActivity.kt:132-143` 的空安全调用什么也没做，UI 仍会成功。

公共复制还在 Service 主线程同步执行；截图保存也没有统一异常回传、MediaStore 回滚和防重复提交。

影响：用户以为轨迹已经稳定留档，实际只有 App 专属副本，或完全没有得到预期文件。

### 5. 计时使用可回拨时钟

`RunTimer.kt:18-24` 使用 `System.currentTimeMillis()` 计算时长；GPS 配速和静止确认使用 `Location.time`。系统校时、乱序或批量定位可能制造负间隔或异常速度。区间计时应基于单调时钟，GPS 样本应基于 `elapsedRealtimeNanos` 排序和计算。

### 6. 运行状态不是完整的响应式状态

`RunningService.kt:203-227` 的主 `combine` 没有订阅节拍器 Flow，只读取当前值。暂停后 Timer 和 GPS 不再发射；如果心率也不变化，声音已经切换或 BPM 已改变，UI 可以一直显示旧值，连续点击加减还会反复基于旧 BPM 计算。

结束确认和保存状态也保存在 `RunScreen.kt:74-110` 的普通 `remember` 中，配置重建会丢失，并且没有 `Saving/Saved/Error` 状态。

### 7. 原始轨迹需要隐私护栏

CSV 包含精确经纬度、时间、速度、海拔和心率（`GpsTraceRecorder.kt:38-40,59-76`），保存后会复制到卸载仍保留的公共 Documents。App 内权限页和 About 页没有解释保留位置、删除方式或公开导出的含义。

本地 `gps-traces/` 当前还是未跟踪且未被 `.gitignore` 排除；使用 `git add .` 可能把个人路线和健康数据提交进仓库。这是应在任何重构前处理的隐私问题，不必等待架构调整。

## 次级问题与待验证项

- TTS 初始化前的提示虽然进入队列，但 flush 时每条都会停止上一条并使用 `QUEUE_FLUSH`，实际只会留下最后一条；初始化失败后也没有失败状态。
- BLE 心率包解析没有在读取 `value[0..2]` 前校验长度，异常设备数据可能触发越界。
- API 26–30 的 MediaButton Receiver 回退链路、目标 SDK 35 下节拍器/BLE 所需的前台服务类型，应做版本矩阵验证；活跃 MediaSession 的真机路径已有正向基础，不宜仅凭静态审查改配置。
- 主跑步页大量使用固定高度且不可滚动，指标溢出直接裁切；大字体、小屏和 TalkBack 语义需要补测。
- 颜色、文案和平均配速格式分散在多处，属于低风险维护债，不应排在生命周期和数据正确性之前。

## 值得保留的基础

- `RunningService` 已设置 `exported=false`，开始跑步时先进入前台，再启动 GPS 和传感器。
- `RunData` 不可变，对外 Flow 基本是只读的，Activity 使用了生命周期感知收集。
- GPS 每个样本都有 `decision/reason` 轨迹记录，现有真实数据很适合作为脱敏回放夹具。
- GPS、传感器、TTS、AudioTrack、GATT 都已有集中释放入口；问题是所有权和调用时机，而不是完全没有清理。
- UI 已拆出多个 Composable，截图渲染也已放到 IO dispatcher。这些都支持渐进式重构，不支持推倒重写。

## 为什么需要重构，而不只是逐点修补

- `RunningService` 约 552 行，同时负责状态转换、模块编排、通知、MediaSession、播报策略和保存入口。
- `GpsTracker` 约 384 行，把 Android `Location`、静止恢复状态机、距离、配速和 CSV 副作用绑在一起，难以对坏点和时钟回拨做确定性测试。
- `RunSummaryImageSaver` 约 720 行，混合 CSV 解析、地理投影、复走检测、Canvas 渲染和 MediaStore 写入。
- 平均配速在 Service、UI 和图片导出中重复计算；权限、连接、保存错误没有统一模型。
- Roadmap 下一阶段是持久化、历史记录和汇总分析。若继续把状态与副作用塞进 Service，后续每个功能都会扩大上述风险面。

## 建议的目标边界

```text
Compose UI
    ↓ RunUiState / RunCommand
ViewModel 或轻量 Controller
    ↓
纯 Kotlin RunSessionController（唯一状态机）
    ├── TrackingEngine（LocationSample → TrackingDecision）
    ├── HeartRateStateMachine
    ├── AnnouncementPolicy
    └── RunStore / TraceStore（suspend + Result）
            ↓
RunningService 仅作为 Android 前台宿主和各平台适配器的所有者
```

保持单一 `app` 模块即可。关键是让 Service 不再拥有业务规则，让 GPS 决策和会话转换可以用普通 JVM 测试运行。

## 分阶段建议

### 阶段 A：行为护栏与止血

1. 将现有真实轨迹脱敏后变成回放夹具，先锁定当前距离、过滤原因和配速结果。
2. 修正 GPS 接受规则、单调时钟、保存真实结果、BLE 权限/主动断开/扫描清理。
3. 明确进程重建策略：能恢复就持久化 checkpoint；暂时不能恢复就使用非粘性策略并明确告知会话结束。
4. 给 `gps-traces/`、临时文件和导出数据建立 Git 与产品内隐私护栏。

### 阶段 B：抽出纯核心

1. 建立 `Idle → Running → Paused → Finishing → Finished` 的串行状态机，重复或非法命令明确 no-op。
2. 把 GPS 输入转换为纯数据 `LocationSample`，输出 `TrackingDecision`，文件记录只消费结果。
3. 把 BLE 建模为 `Unavailable/PermissionDenied/Scanning/Connecting/Connected/Error`，明确重连意图、代次和退避。
4. 保存 API 改为挂起函数并返回类型化 `Result`；UI 只根据结果显示成功或失败。

### 阶段 C：整理展示层

1. Activity 只负责权限、导航和一次成对绑定；UI 不直接访问具体 Service/Tracker。
2. 拆分截图的 CSV 解析、轨迹几何、渲染和存储；分别测试，保留现有视觉输出。
3. 统一主题、字符串资源、平均配速格式和大字体/小屏适配。

## 完成标准

- 重复 Start、非法 Resume、Stop 后迟到命令都不会清零或复活会话。
- 进程杀死后的行为是“可恢复”或“明确结束”之一，不会静默归零。
- 蓝牙权限拒绝/撤销不崩溃；退出扫描页会停止扫描；主动断开不会自动重连。
- 暂停时切换节拍器和连续调整 BPM，声音与 UI 始终一致。
- API 26、28、29、31、35 的保存流程都返回真实成功/失败；磁盘满时不假报成功、不遗留 pending 项。
- GPS 回放覆盖高速坏点、静止/恢复、跳点返回、乱序、时钟回拨和 pause/resume；距离不会出现 NaN、负增长或无上界单点增量。
- `testDebugUnitTest` 不再是 `NO-SOURCE`，核心状态机和 GPS 决策不依赖模拟器即可测试。
- 原始路线不会被默认提交到 Git，公共导出前有清晰告知和删除策略。

## 暂不建议做的事

- 不重写 Compose 页面，不为了“架构完整”引入多模块或 DI 框架。
- 不在提取 GPS 核心的同一批改动中重新调全部阈值。
- 不提前实现数据库、历史分析或地图 SDK；先稳定会话与数据契约。
- 不把 14 条 Lint warning 全部当作重构阻塞项；优先处理权限、存储和生命周期相关项。

## 平台依据

- [Android Bound services 生命周期](https://developer.android.com/develop/background-work/services/bound-services)
- [Android Service 与 START_STICKY](https://developer.android.com/reference/android/app/Service)
- [Android Bluetooth 运行时权限](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions)
- [Android SystemClock：区间计时应使用单调时钟](https://developer.android.com/reference/android/os/SystemClock)
- [Android 旧版共享存储权限](https://developer.android.com/training/data-storage/shared/media)
