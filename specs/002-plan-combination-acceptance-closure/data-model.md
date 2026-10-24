# Phase 1 Data Model: 方案、运行快照与分钟账本

**Feature**: `specs/002-plan-combination-acceptance-closure` | **Date**: 2026-10-23

本轮**不引入新实体、不改 schema**。本文件把已在树中的模型字段与约束固化下来，作为验收判读与 S2/S3 测试的对照基准。所有约束均由源码 `init`/`require`/`check` 实证，标注出处。

---

## 1. 配置层：PlanV2 与三种步骤

### PlanV2（`PlanV2.kt`）

| 字段 | 类型 | 约束（实证） |
| --- | --- | --- |
| `schemaVersion` | Int | 必须 `== 2`，否则 `PLAN_SCHEMA_UNSUPPORTED` |
| `planId` | String | 非空，否则 `PLAN_ID_BLANK` |
| `name` | String | 非空且长度 1..80，否则 `PLAN_NAME_INVALID` |
| `dailyBudgetMinutes` | Int | 1..480，否则 `PLAN_BUDGET_OUT_OF_RANGE` |
| `steps` | List\<PlanStep\> | 构造即 `unmodifiableList(toList())`（调用方传 MutableList 也无法回写） |
| `executionProfile` | ExecutionProfile | `SELECTED_ACTIONS` \| `LEGACY_MANAGED`，默认前者 |
| `legacySource` | String? | 仅携带旧来源标识，不驱动执行 |

**步骤集合约束**：`size in 1..3`（`PLAN_STEPS_COUNT_INVALID`）；stepId 互异（`PLAN_STEP_ID_DUPLICATE`）；类型互异（`PLAN_STEP_TYPE_DUPLICATE`）；顺序按 `PlanStepType.ordinal` **严格递增**（`PLAN_STEP_ORDER_INVALID`），即 WORK(0) < CARE(1) < STUDY(2)。

**条件工作约束**：任一 `WorkStep.trigger == IF_FUNDS_SHORT` 时，方案必须含 `StudyStep`，否则 `PLAN_CONDITIONAL_WORK_REQUIRES_STUDY`。

> **七种非空组合的可达性**：类型互异 + 严格顺序 + 1..3 步 ⇒ 恰好是 {WORK, CARE, STUDY} 的七个非空子集，且每个子集只有唯一合法排列。**无需新增模型或排序 UI。**

### WorkStep

| 字段 | 约束 |
| --- | --- |
| `stepId` | 非空（`PLAN_STEP_ID_BLANK`） |
| `workOptionId` | 非空（`PLAN_WORK_OPTION_BLANK`）；`PlanValidator.validate` 另要求属于调用方提供的 **VERIFIED 岗位白名单**，否则 `PLAN_WORK_OPTION_NOT_VERIFIED` |
| `trigger` | `ALWAYS`（默认）\| `IF_FUNDS_SHORT` |
| `shifts` | **必须 `== 1`**（`PLAN_WORK_SHIFTS_INVALID`）——「自动增班」在模型层就被禁止 |

### CareStep

| 字段 | 约束 |
| --- | --- |
| `energyThreshold` | 60..100（`PLAN_CARE_ENERGY_OUT_OF_RANGE`） |
| `cleanlinessThreshold` | 60..100（`PLAN_CARE_CLEANLINESS_OUT_OF_RANGE`） |

> 阈值**只**决定 CARE 动作，不是学习门禁（spec 001 FR-008）。

### StudyStep

| 字段 | 约束 |
| --- | --- |
| `courseId` | 非空（`PLAN_COURSE_BLANK`） |
| `rounds` | 1..10（`PLAN_STUDY_ROUNDS_OUT_OF_RANGE`）；每节一个独立 attempt |

### UI 侧校验（`PetPlanSheetScreen`，与模型约束一一对应）

- 名称 1..80；至少勾选一个动作（`请选择至少一个动作。`）；仅对**已选动作**的参数做数字校验（`relevantFields` 只含 budget + 已选项）。
- 选 WORK 时必须选中 VERIFIED 岗位；选 STUDY 时要求课表 `isUsable`（7 天内且 complete）且该课程 `durationMinutes != null`。
- 「仅资金不足时打工」开关**仅在同时勾选 STUDY 时出现**（`if (selectedStudy)`），与 `PLAN_CONDITIONAL_WORK_REQUIRES_STUDY` 对齐；未选学习时显示「固定执行一班打工」。
- `IF_FUNDS_SHORT` 由 `selectedStudy && plan.workBeforeStudy` 推导；保存时 `executionProfile` **恒为 `SELECTED_ACTIONS`**（见 research R2）。
- 「保存并运行」额外要求 `displayId > 0` 且当前无运行中任务。

---

## 2. 执行层：请求、快照、步骤执行、attempt

### RunOrigin / RunState / StepState / AttemptState / SkipReason（`PlanExecutionModels.kt`）

```
RunOrigin   : WORK_SHORTCUT | CARE_SHORTCUT | STUDY_SHORTCUT | PLAN | LEGACY
RunState    : CREATED | PREFLIGHT | RUNNING | COMPLETED | STOPPED | FAILED | UNCONFIRMED
StepState   : PENDING | RUNNING | COMPLETED | SKIPPED | BLOCKED | FAILED | UNCONFIRMED | CANCELLED
AttemptState: PREPARED | BUDGET_RESERVED | INPUT_PENDING | START_CONFIRMED | COMPLETED | UNCONFIRMED
SkipReason  : FUNDS_SUFFICIENT | ALREADY_SATISFIED      ← 合法跳过只有这两种
```

### AttemptId

`value = "<runId.length>:<runId><stepId.length>:<stepId>:<iteration>"`（长度前缀避免分隔符碰撞）；`runId`/`stepId` 非空、`iteration >= 1`。
生产中的 stepId 实际取值为 `"work"` / `"care"` / `"study"`（见 `PetAutomationService` 的 `studyAttempt`/`careAttempt`/`workAttempt`），因此**同一 run 内三类 attempt 天然不冲突**。

### RunRequest

| 字段 | 约束 |
| --- | --- |
| `requestId` | 非空；用于**幂等去重**（同 requestId 重投不产生新 run） |
| `planSnapshot` | 启动时的不可变方案副本；编辑下次方案不影响运行中快照 |
| `deviceSerial` | 非空 |
| `displayId` | **必须 > 0**（`RUN_DISPLAY_ID_INVALID`）——display 0 是物理屏，永不承载会话 |
| `origin` | PLAN 用于组合；三个 SHORTCUT 要求 `steps.size==1` 且类型匹配（`RUN_SHORTCUT_PLAN_MISMATCH`），`WORK_SHORTCUT` 还要求 `trigger==ALWAYS`（`RUN_WORK_SHORTCUT_MUST_BE_EXPLICIT`） |
| `createdAtMs` | ≥ 0 |

### RunCounts

`workShiftsCompleted` / `careCompleted` / `studyRoundsCompleted`，均 ≥ 0。
**计数来源是 persisted attempts，不是 step 状态**（`PlanRunner` 注释 + `store.countsOf(runId, plan)`）；因此「已确认发奖但收尾失败」的步骤仍能显示完成数，而整体保持 UNCONFIRMED。

### StepExecution

- `SKIPPED` 必须带对应理由：WORK→`FUNDS_SUFFICIENT`、CARE→`ALREADY_SATISFIED`（`STEP_SKIP_REASON_INVALID`）。**STUDY 没有合法跳过**。
- `BLOCKED/FAILED/UNCONFIRMED/CANCELLED` 必须有非空 reason（`STEP_STOP_REASON_REQUIRED`）。
- 时间单调：`endedAtMs >= startedAtMs`。

### ActivityAttempt

- `durationMinutes >= 0`；**CARE 恒为 0**（护理不扣游戏分钟），WORK/STUDY 由端口校验 > 0（`PlanRunner.ExecutingContext.beforeConsumingInput`：`if(step is CareStep) minutes == 0 else minutes > 0`）。
- `INPUT_PENDING` / `UNCONFIRMED` ⇒ `inputPending == true`（`ATTEMPT_UNCONFIRMED_INPUT_MUST_STAY_PENDING`）。
- `START_CONFIRMED` ⇒ `startConfirmedAtMs != null`；`COMPLETED` ⇒ `completionConfirmedAtMs != null` **且** `inputPending == false`（完成必须有证据且不再挂起）。
- 时间链单调：dispatch ≤ startConfirmed ≤ completionConfirmed。

### RunSnapshot（不可变，`unmodifiableList`）

| 不变式 | 出处 |
| --- | --- |
| `steps.size == planSnapshot.steps.size` | `RUN_STEP_COUNT_MISMATCH` |
| 每步 runId/index/stepId/type 与方案逐项一致 | `RUN_STEP_IDENTITY_MISMATCH` |
| **首个非 COMPLETED/SKIPPED 步骤之后的所有步骤必须仍为 PENDING** | `RUN_EXECUTED_AFTER_STOPPED_STEP`（stop-on-failure 的模型级保证） |
| `state == COMPLETED` ⇒ 所有步骤 ∈ {COMPLETED, SKIPPED} | `RUN_COMPLETED_WITH_UNFINISHED_STEPS` |
| counts 不得超过方案声明的上限（work=Σshifts、care=步骤数、study=Σrounds） | `RUN_*_COUNT_EXCEEDED`；care 上限对 `LEGACY_MANAGED` 放宽（唯一 legacy 读取点之一） |

### 运行状态迁移（`PlanRunner.run`）

```
CREATED ──save──> PREFLIGHT
   ├─ preflight Blocked        ──> FAILED       (stopReason=pre.reason)
   ├─ preflight NeedsObservation ─> UNCONFIRMED (stopReason="PREFLIGHT_NEEDS_OBSERVATION:<missing…>")
   ├─ checkAuthority/preflight 抛 CancellationException ─> STOPPED（并向上抛）
   ├─ 其它异常                  ──> UNCONFIRMED (stopReason=异常信息)
   └─ Ready ──> RUNNING
RUNNING ──每步──> 写回 steps[index] + counts(来自 store) 
   ├─ 步骤 COMPLETED / SKIPPED ──> 继续下一步（state 保持 RUNNING）
   ├─ 步骤 BLOCKED / FAILED    ──> 整体 FAILED      ，后续步骤留 PENDING，立即返回
   ├─ 步骤 UNCONFIRMED         ──> 整体 UNCONFIRMED  ，同上
   ├─ 步骤 CANCELLED           ──> 整体 STOPPED      ，同上（并抛 CancellationException）
   └─ 无 executor              ──> BLOCKED "NO_EXECUTOR_FOR:<type>" → FAILED
所有步骤 COMPLETED/SKIPPED ──> COMPLETED (endedAtMs=now)
```

**重入语义**（`PlanRunner.run` 开头，`PlanRunnerReuseTest` 钉住）：`store.load(runId)` 已存在时——
- 请求不一致 ⇒ `RUN_REQUEST_CHANGED`；
- 已处终态（COMPLETED/STOPPED/FAILED/UNCONFIRMED）⇒ **原样返回既有记录**，绝不重新执行；
- 非终态 ⇒ 改写为 `UNCONFIRMED` + `PERSISTED_RUN_REQUIRES_OBSERVATION_NO_REPLAY`，counts 重算，要求人工/观察收口。

**未确认输入的保留**：executor 抛异常时，`store.attempts(runId).filter { it.inputPending }` 全部 `persistUnconfirmed(reason)`；`PlanBudgetExecutionContext.retainPending("STEP_ENDED_WITHOUT_COMPLETION_PROOF")` 在 `finally` 中对「已预留但未完成」的 iteration 做同样处理。**任何既有 attempt 都不会被再次授权**（`ATTEMPT_ALREADY_EXISTS_NO_REPLAY`）。

### 双写一致性（`PlanBudgetExecutionContext`）

文件意图（RunStore）**先于**预算预留（ledger），预留**先于**输入派发：
`beforeConsumingInput` → `delegate.beforeConsumingInput`（写 INPUT_PENDING）→ `budget.reserve`（失败即 `BUDGET_ATTEMPT_ALREADY_RESERVED_NO_REPLAY`）→ 记录到本地 `reserved` → `checkAuthority()`。
CARE 的 0 分钟允许同一 iteration 重复进入（`check(step is CareStep && iteration !in done && minutes == 0)`），其余类型重复预留直接拒绝。
`completed` 要求 `reserved[iteration] == minutes` 且未重复完成（`ATTEMPT_COMPLETION_MISMATCH`）。

---

## 3. 预算层：权威分钟账本

### BudgetLedgerState（`PlanBudgetLedger.kt`）

| 字段 | 约束 |
| --- | --- |
| `day` | 必须能被 `LocalDate.parse` 且往返相等（`BUDGET_DAY_INVALID`） |
| `legacyUsedMinutes` | ≥ 0；**与新 attempt 分开累加**，迁移不二次计费 |
| `attempts` | key 必须等于 `value.attemptId`（`BUDGET_ATTEMPT_KEY_MISMATCH`） |
| `legacyByDay` | 各值 ≥ 0，且**不得包含当前 `day`**（`LEGACY_BUDGET_DAY_INVALID`） |

权威当日总量 = `legacyUsedMinutes`（当日）或 `legacyByDay[day]`（历史日）+ 该日全部 keyed attempts 的分钟之和。

### BudgetAttempt

`attemptId`（非空）、`day`（合法日期）、`minutes ≥ 0`、`status: ReserveStatus`、`reason?`、`limitMinutes ∈ 1..480`。

### ReserveStatus

```
RESERVED_BEFORE_INPUT → START_CONFIRMED → COMPLETED
                     ↘ UNCONFIRMED_RETAINED → RECONCILED_STOPPED
```
- `RECONCILED_STOPPED`：旧结果仍未知，但当前基线已重新确立——**不是成功、不退款、不重放**。
- 二进制 codec 以 UTF 枚举名存储；**不认识该状态的旧 APK 必须在 `valueOf` 处 fail closed**，而不是退款或回退导入 prefs。

### PersistentBudgetStore（`files/pet-minute-budget-v2.bin`）

- 魔数 + 版本校验（`BUDGET_FORMAT_UNSUPPORTED`）；attempts/日键重复即拒（`BUDGET_DUPLICATE_*`）；尾部多余字节即拒（`BUDGET_TRAILING_DATA`）。
- **只有当权威文件不存在时**才读取 legacy prefs 做一次导入；损坏/截断一律 fail closed，**绝不回退成空预算或重复导入**。
- 写入：先 fsync 兄弟临时文件，再 `ATOMIC_MOVE` 覆盖。
- 所有读与读-改-写共享进程级 `BudgetLedgerLock.monitor`，**跨 store 实例**同样互斥。

### 预算后缀策略（`PlanBudgetPolicy` / `PlanDevicePreflight`）

- `suffixLessonMinutes(selected) { courseId -> durationMinutes }`：**只按方案自身的 STUDY 步骤**计算后缀；无学习步骤 ⇒ 后缀 0，**不得回查课表**（此前 `PLAN_COURSE_DURATION_UNKNOWN:study:田径运动课` 正是无学习方案去问课表导致 WC 首跑停止）。
- 时长未知 ⇒ 失败关闭。
- `careNeedsNoItemsAfterWork(energy, cleanliness, care, willWork, energyCost, cleanlinessCost)`：任一健康读数为 null 即返回 false；否则要求「工作后（若将工作则扣除岗位耗值）的体力/清洁仍 ≥ 护理阈值」。返回 false 时 `PetSessionController.runPlanSteps` 的资源预检给出 `NeedsObservation("CARE_SUFFIX_EXACT_INVENTORY_AND_COST_REQUIRED")` —— **这是无精确库存时的保守拒绝，不是缺陷**（见 research R4 与 spec 的 FUND-CARE-GAP 判据）。
- 班次与后缀必须**同时**装得下每日上限才允许开工（`shiftFits`）。

---

## 4. 遗留清算层（Step 0 的模型依据）

| 实体 | 关键字段 / 约束 |
| --- | --- |
| `CareRoomBaseline` | `displayId>0`、`stable`、`roomVerified`、`overlaysAbsent`、`secondAt>firstAt` 且间隔 ≤5000ms、`now-secondAt ≤3000ms`、双 evidence 非空且互异、双帧钱包 Known/confidence≥0.95/`DIGIT_RECOGNITION`/`observedAt` 与 evidence 逐一对应且两帧**数值相等**（`BASELINE_WALLET_CHANGED`） |
| `LegacyCareStoppedProof` | `checkpointSessionId == terminalSessionId == attempt 的 sessionId`、`terminalState ∈ {STOPPED, FAILED, COMPLETED}`、`mode == "care"`、`courseStarts == 0`、两份 evidence 非空；`actionPending == true` 时必须附 `paySettlement` |
| `LegacyPaySettlementReceipt` | 由 `LegacyPaySettlementReader` 严格校验：allowlist sessionId、runDirectory 与 lastInputWall 匹配、最后输入必须是 `TAP 360.0/1425.0`、`stoppedWall ∈ [lastInputWall, endedAt+1000]`、before/after 均 720×1600、`food xor soap`、after 已结算（bathScene≥0.97 或 foodScene≥0.97 且互斥）、图片 mtime 与 journal 一致、terminal record 的 `courseStarts==0` |
| `CarePendingReconciliation.legacySessionIds` | **恰好 7 个固定 sessionId**；attempt 必须 `minutes==0 && status==UNCONFIRMED_RETAINED && day=="2026-10-22"`，否则 `LEGACY_CARE_ATTEMPT_NOT_ELIGIBLE`；不在 allowlist ⇒ `LEGACY_CARE_ATTEMPT_NOT_ALLOWLISTED` |
| `FinalizationCheckpoint` | `sessionId`、`actionPending`、`evidence`；finalizer 要求 `sessionId == run.runId`、`actionPending == false`、evidence 非空 |

**写入语义**：清算只在 `reason` 后**追加** `RECONCILED_STOPPED;OUTCOME_REMAINS_UNKNOWN;at=…;checkpoint=…;terminal=…;baseline=…;checkpoint.actionPending=…;<付款证据|NO_PENDING_INPUT>`，并把 status 置为 `RECONCILED_STOPPED`。**不改分钟、不改旧 checkpoint、不重放输入、不宣称成功。**

`CommittedPlanFinalizer`（task-16，WC 已用）额外要求：`origin==PLAN`、`executionProfile==SELECTED_ACTIONS`、**方案不含 StudyStep**（`FINALIZATION_STUDY_OR_LEGACY_FORBIDDEN`）、`state==UNCONFIRMED` 且 `stopReason` 以 `HEALTH_STALE` 开头、最后一步 UNCONFIRMED 且其余步骤均 COMPLETED/SKIPPED、attempts 全 COMPLETED 且非 pending 且都属于本 run 与方案、counts 与推算一致；audit 文件**先写后提交**，已完成且 audit 已存在时幂等返回原快照。

> **对本轮的直接影响**：含 STUDY 的组合**不能**用 finalizer 收尾（模型层禁止），因此 WS/CS/WCS 必须**原生**跑到 COMPLETED，不能指望事后收尾。

---

## 5. 与验收判定的对应

| 判定问题 | 读哪个字段 |
| --- | --- |
| 组合整体是否成功 | `plan-runs/<runId>.json` 的 `snapshot.state == COMPLETED` 且 `stopReason == null` |
| 各动作完成了几次 | `snapshot.counts`（work/care/study），且与 `attempts` 中 `state==COMPLETED` 的条目一致 |
| 未选动作是否真的没跑 | `snapshot.steps` 只含所选步骤（模型不允许为未选动作伪造执行记录）+ 事件流 `INPUT_SENT` 归属 |
| 前项失败后后项是否未执行 | 失败步骤之后的 `steps[*].state` 全为 `PENDING` |
| 是否合法跳过 | `StepState.SKIPPED` + reason ∈ {FUNDS_SUFFICIENT, ALREADY_SATISFIED} |
| 分钟是否只扣一次 | `pet-minute-budget-v2.bin` 中该 attempt 的 status 与 minutes；重复观察/恢复不得新增 attempt |
| 护理是否花了游戏分钟 | CARE attempt 的 `durationMinutes == 0` |
| 服务层终态 | `verdict.json` 的 `verdict` / `planRunState` / counts（**不是** 事件流的 `SESSION_STATE`，见 research R3） |
