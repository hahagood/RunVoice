# RunVoice 重构完成记录

- 日期：2026-07-10
- 重构前基线：`e6dfd23f143dd1bd13577643ec9ac5f30976e037`
- 结论：代码重构完成，Debug/Release 构建、JVM 单元测试和 Lint 均通过。

## 已完成

- 清除本地 GPS CSV 与临时交接文件，并通过 `.gitignore` 阻止路线和健康数据误提交。
- 引入纯 Kotlin `RunSessionController`，串行约束 Start/Pause/Resume/Finish，拒绝重复和迟到命令。
- 引入纯 Kotlin `TrackingEngine`，使用定位单调时钟统一处理精度、乱序、速度、跳点、静止恢复、距离和配速。
- Service 改为 `START_NOT_STICKY`；在没有 checkpoint 的情况下不再伪恢复空白会话。
- `RunTimer` 改用 `SystemClock.elapsedRealtime()`。
- BLE 增加权限检查、扫描超时、主动断开保护、连接状态模型和异常心率包长度校验。
- 保存接口返回 `Saved/Discarded/Failed`；公共导出移到 IO dispatcher，MediaStore 失败会回滚 pending 项。
- Activity 改为一次成对绑定，不再直接访问 Tracker；节拍器状态在跑步和暂停时都保持响应式。
- TTS 初始化队列改为顺序消费，不再在 flush 时只留下最后一条。
- 截图拆为 CSV 解析、轨迹几何、Canvas 渲染和存储四个边界。
- 主跑步页和结束页支持滚动并解除固定竖屏；补充备份禁用规则和产品内轨迹隐私说明。

## 自动验证

```text
testDebugUnitTest: 20 tests, 0 failures
lintDebug: 0 errors, 5 warnings（均为既有传统启动图标形状提示）
assembleDebug: success
assembleRelease: success
```

测试覆盖会话非法转换、高速坏点与坏点返回、低速静止漂移、静止恢复、无上界恢复位移、乱序、无效坐标、pause/resume、心率包解析、播报边界、平均配速、CSV 引号解析和轨迹投影。

## 仍需真机回归（不阻塞代码完成）

- Android 8、9、10、12、15 的权限拒绝/撤销与保存结果。
- 系统杀进程后的非粘性结束表现。
- 主动断开、切换心率设备和扫描页系统返回。
- 真实跑步轨迹距离、静止恢复阈值、语音与耳机媒体键。
- 大字体、小屏、横屏以及磁盘满场景。
