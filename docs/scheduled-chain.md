# 定时任务链式触发（AFTER_TASK）

## 是什么

任务默认按闹钟时间触发（`ScheduledTriggerKind.SCHEDULE`）。链式任务不装闹钟，
只在**指定的上游任务成功跑完**之后，由 `ScheduledAgentRunner` 的完成回调拉起一次。

典型用途：`调研 → 整理 → 发送`，三段各自是一个 prompt，前一段成了自动接下一段。

## 数据模型

`ScheduledTask` 新增两个字段（都在末尾，带默认值，老 JSON 直接兼容）：

| 字段 | 类型 | 默认 | 含义 |
|---|---|---|---|
| `triggerKind` | `ScheduledTriggerKind` | `SCHEDULE` | `AFTER_TASK` 表示由上游完成事件触发 |
| `afterTaskId` | `String?` | `null` | 上游任务 id；为空则永不自动触发 |

JSON 只在非默认时写 `triggerKind`，`afterTaskId` 非空才写。`fromJson` 缺键回落
`SCHEDULE` / `null`，老客户端写下的任务读进来行为不变。

## 三道闸门

`ScheduledChain.selectDependents(tasks, completedTaskId, depth)`：

1. `enabled == false` 的下游不拉起；
2. 只认 `triggerKind == AFTER_TASK && afterTaskId == completedTaskId` 的显式边，按时间触发的任务不会被连带；
3. `depth >= MAX_DEPTH(3)` 停止。A→B→A 的环因此必然终止，不会变成自激循环；
   同一批下游按 `createdAt` 排序，执行顺序稳定。

## 执行路径

```
task A 跑完 → runUnattended.onCompleted(ok)
   └─ ScheduledTaskManager.markFired(A)
   └─ fireDependents(A, depth)
        └─ selectDependents(...) → [B, C]
             └─ run(B, waitForCompletion=false, chainDepth+1)
                  └─ B 跑完 → fireDependents(B, depth+1) …
```

- 只在 `ok == true` 时触发：上游失败即断链，不把错误传给下游；
- 下游一律异步（`waitForCompletion=false`），一条链不会长时间占用调度线程；
- `ScheduledTaskManager.rescheduleAll()` 与 `registerAlarm()` 双处跳过 `AFTER_TASK`，
  避免链式任务被当成每日任务空跑。

## 用法

工具侧（`cronjob`）：

```json
{"action":"create","name":"整理调研","prompt":"读取昨天的调研结果并整理成简报",
 "after_task":"<上游任务 id 或唯一前缀>"}
```

给了 `after_task` 就不需要 `schedule`；回执里 `schedule` 字段显示为 `after_task:<id>`。

## 限制

- 一条链最多 3 级，超出的下游保持待触发状态（等人工或另外的调度）；
- 链式任务自身 `repeatMode=ONCE`，跑完即 `enabled=false`；
- 上游被删除后，下游保留但永不触发——`ScheduledChain.isValidLink` 可用于保存期校验；
- 不支持跨设备/跨账号的上游任务。

## 测试

`src/android/app/src/test/java/com/openminis/app/scheduled/ScheduledChainTest.kt`
（7 例）：显式边选择、停用跳过、深度上限与环终止、顺序稳定、自指/缺失上游校验、
JSON 往返、老 JSON 兼容。本地 `kotlinc` + JUnit 4.13.2 实跑通过。
