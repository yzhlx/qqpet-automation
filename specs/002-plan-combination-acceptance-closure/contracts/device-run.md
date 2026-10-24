# Contract: 设备运行（Device Run）

**Feature**: `specs/002-plan-combination-acceptance-closure` | **Date**: 2026-10-23

本契约描述「从用户点『保存并运行』到会话终态」的对外可观察行为，全部由源码实证。验收判定以此为准，不以内部调用顺序为准。

---

## 1. 启动意图（`SessionStartRequest.forPlan`）

| 项 | 值 |
| --- | --- |
| action | `com.ynk.virtualdisplay.pet.START` |
| component | `com.ynk.virtualdisplay/.automation.PetAutomationService`（非 exported，只经 UI 启动） |
| `display_id` | **必须 > 0**（`PLAN_DISPLAY_NOT_READY`）；永不取 display 0（物理屏） |
| `plan_json` | `PlanV2JsonCodec.encode(plan)` —— 启动时的**不可变快照**；此后编辑方案不影响本次运行 |
| `request_id` | 非空（`PLAN_REQUEST_ID_BLANK`）；UI 每次点击生成新 UUID，是**幂等去重键** |
| `rounds` | 有 STUDY 步骤时取其 `rounds`，否则 `DEFAULT_ROUNDS=1` |
| `session_mode` | 单步方案取对应 WORK/CARE/STUDY，多步取 FULL —— **仅供参考**：服务在 `plan_json != null` 时一律按 `SessionMode.PLAN` 执行（`PetAutomationService.onStartCommand`），并 `check(mode != PLAN) { "PLAN_JSON_REQUIRED" }` 阻止用 mode 走方案路径 |

**UI 前置**（`PetPlanSheetScreen.persistSelection(runAfterSave=true)`）：校验通过（`validationMessage == null`）、`displayId > 0`、`runtime.running == false`；先 `PlanV2Store.save` 落盘，**保存成功后**才 `startForegroundService`，随后打开控制台。保存成功但启动失败时提示「方案已保存，启动失败：…」。

---

## 2. 准入（三段，任一失败即不执行本次请求）

### 2.1 请求收据查重（仅 plan 路径）

`PlanRunPendingGate.findRequest(files/plan-runs, requestId)`：
- 命中 ⇒ 校验 `receipt.request.planSnapshot` 与本次 `plan_json` **逐字段相等**且 `displayId` 相同，否则 `RUN_REQUEST_CHANGED`；
  - 若 `receipt.state == UNCONFIRMED` 且当前无活动 job ⇒ 转 `launchCommittedPlanFinalization`（**收尾既有 run，不执行新请求**）；
  - 否则记 `SESSION_REQUEST_RECEIPT <runId> <state>`，无活动 job 时 `stopSession("请求已处理：<runId>")`。
- run-store 不可读 ⇒ `SESSION_START_REJECTED RUN_STORE_UNREADABLE` + `stopSession("RUN_STORE_UNREADABLE:…")`（**fail closed**）。

> 含义：**同一 requestId 重投永远不会产生第二次执行**（`Admission.Accepted.shouldExecute == false`）。验收时重复点击/重投不是「再跑一次」，而是取回原 run 的收据。

### 2.2 设备互斥与去重（`PlanRunCoordinator.admit`）

进程级、按 `deviceKey="pet-local-device"` 共享（`ConcurrentHashMap` + `synchronized(device)`）：

| 情形 | 结果 |
| --- | --- |
| requestId 已见过 | `Accepted(原 runId, duplicateRequest=true)` → `shouldExecute=false` |
| 已有活动 run | `Rejected("RUN_ALREADY_ACTIVE:<runId>")` |
| runId 曾用过 | `Rejected("RUN_ID_ALREADY_USED:<runId>")`（runId 永不回收） |
| pendingGate 抛异常 | `Rejected("PENDING_GATE_FAILED:<类型>")`（**读失败即拒绝**） |
| pendingGate 返回非 null | `Rejected("PENDING_RUN_UNRESOLVED:<id>")` |
| 其余 | `Accepted(新 runId, false)`，登记 activeRunId / receipt / usedRunIds |

`admitObservation(requestId, runId, task)` 走 `checkDurable=false`（观察任务不被 pending 门禁挡住），requestId 前缀为 `observation:<TASK>:<原 requestId>`；两类任务：`LEGACY_ZERO_MINUTE_CARE_RECONCILIATION`、`COMMITTED_NON_STUDY_FINALIZATION`。

`stop(runId)`：仅当前 owner 可置 `stopRequested`（陈旧 id 无法停别人的 run），重复 stop 幂等；**不转移设备所有权**。`finish(runId)`：仅在终态清理后释放；收据在本进程内**永久不可执行**。
`HEALTH_CHECK` 走同一 deviceKey 与准入路径，**无锁旁路**。

### 2.3 持久 pending 门禁（`PendingGate`，生产实现）

求值顺序（`PetAutomationService.coordinator`）：

1. `DailyBudgetStore(this).usedMinutes()` —— 触发 legacy prefs 的**一次性**导入；
2. `PersistentBudgetStore(files/pet-minute-budget-v2.bin).read().attempts.values.firstOrNull(CarePendingReconciliation::unresolved)?.attemptId` —— **遗留 CARE pending 优先**；
3. 否则 `PlanRunPendingGate.blockingRunId(files/plan-runs)` —— v2 运行快照中处于 `CREATED/PREFLIGHT/RUNNING/UNCONFIRMED` 或 `hasPendingInput` 的 run。

`unresolved(a) = a.status !in {COMPLETED, RECONCILED_STOPPED}`。

**被拒后的分支**（`launchOwned`，`allowReconciliation=true` 且无活动 job）：
- reason 以 `PENDING_RUN_UNRESOLVED:` 开头 ⇒ 再查 v2：有阻塞 run 则 `launchCommittedPlanFinalization`，否则 `launchLegacyCareObservation`；
- 该恢复分支自身抛错 ⇒ `stopSession("PENDING_RECOVERY_REFUSED:…")`；
- 其它拒绝原因 ⇒ 直接 `stopSession(reason)`。

> **对本轮的硬约束（research R1）**：7 条遗留 `UNCONFIRMED_RETAINED` 未清算前，**任何新组合请求都不会执行**，只会触发遗留观察。Step 0 必须先清空。

---

## 3. 会话执行前置（`runSession`）

按序校验，任一失败即 `stopSession(<原因>)`：

1. `profile.validate(healthGuardInstalled=true)` 无错误，否则「配置未完成：…」；
2. `displayId > 0`，否则「拒绝默认/无效 displayId」；
3. `mode.runsStudy` 时 `rounds ∈ 1..10`；方案含 STUDY 时其 `rounds ∈ 1..10`（「方案学习节数必须在1..10」）；
4. 7 秒内等到 `ConnectionStatus.CONNECTED` **且** `displayId ∈ managedDisplayIds`（CONNECTED 早于异步归属快照发布，故两者都要等）；
5. `verifyDisplay(displayId)` 硬校验：display 存在、`densityDpi == 320`、`Build.MODEL == "FOA-AL00"`、QQ `versionName == "9.3.70"` 且 `longVersionCode == 16410`、实际尺寸 == `profile.displayWidth×displayHeight`、display 属于当前 Shizuku 服务管理；
6. 取 wakeLock；
7. `plan = effectivePlan(selected)`（= `SelectedPlanConfiguration.toSessionPlan`，**对 `LEGACY_MANAGED` 抛 `LEGACY_PROFILE_REQUIRES_LEGACY_ADAPTER`**）；
8. `PlanValidator.validate(snapshot, VERIFIED 岗位 id 集合)`；
9. 含学习时加载课表并 `check(catalog.isUsable(now))`，否则 `COURSE_CATALOG_RESCAN_REQUIRED`。

`controllerRounds = selected?.let { max(1, studyRounds) } ?: rounds`；`requestedRounds = studyRounds(selected)`（无学习则 0）。

---

## 4. 方案执行（`PetSessionController.runPlanSteps`）

- 预检由 `PlanPreflight(courses=…, resources=…)` 提供：
  - **courses 端口**：只在方案含 STUDY 时被调用；读课程详情成本并 `CourseTiming.resolve(...).confirmDetail(name, durationMinutes)`（名称/时长不符即失败关闭）。
  - **resources 端口**：设置 `tuitionReserveCoins = CarePaymentPolicy.unpaidTuition(学费, rounds)`（无学习则 0）；`IF_FUNDS_SHORT` 时 `planSkipWork = balanceCoins >= tuitionReserveCoins`，若还含 CARE 则用 `PlanDevicePreflight.careNeedsNoItemsAfterWork` 判断「工作后是否无需购买道具」，判不出 ⇒ `NeedsObservation("CARE_SUFFIX_EXACT_INVENTORY_AND_COST_REQUIRED")`；含学习但无工作且余额 < 后缀学费 ⇒ `Insufficient("COURSE_COINS_INSUFFICIENT")`；工作分钟（跳过则 0）+ 学习分钟（rounds×时长）超上限 ⇒ `Insufficient("DAILY_BUDGET_LIMIT")`；否则 `Ready("device-preflight-<ts>", planSkipWork, false)`。
- 执行由 `PlanDeviceExecution(store=FileRunStore(files/plan-runs), budget, preflight, work/care/study, checkAuthority)` 驱动 `PlanRunner`；三个 step executor 分别绑定 `runPlanWork` / `runPlanCare` / `runPlanStudy`，并把 `activePlanContext` 指向本步骤的 `PlanBudgetExecutionContext`。
- **runId == 事件流 sessionId**（`flow.capture.facts.events.sessionId`），因此 `pet-events/<runId>.jsonl`、`pet-checkpoints/<runId>.json`、`plan-runs/<runId>.json` 三者同键，可直接交叉核对。
- 结束后写 `pet_mvp_session/<ts>/plan-run.json`（`PlanRunJsonCodec.encode(StoredRun(snapshot, attempts, emptyMap()))`）；`finally` 中重读 `store.load(runId)` 回填 `planRunSnapshot` 与 `completed`（**终态只回读，不再执行**）。
- 返回值：`state == COMPLETED` ⇒ `"PLAN_PASS"`，否则 `"STOPPED"`。

### 各步骤的可观察承诺

| 步骤 | 承诺 |
| --- | --- |
| WORK | 岗位必须 `RewardConfidence.VERIFIED`；`IF_FUNDS_SHORT && planSkipWork` ⇒ 发 `plan-work-skipped`（`FUNDS_SUFFICIENT:tuitionReserve=…`）并 `Skipped(FUNDS_SUFFICIENT)`，**零输入**；否则读健康(`work-before`)→出门→`WorkStartAuthorisation.grant(maxStarts=1, maxCoinsSpent=0, maxEnergySpent=岗位耗值)`→`work.run`→`startConfirmed(1)`→`waitAndVerify`→`completed(1, 实际分钟)`→发 `WORK_COMPLETE`→`dismissCompletion()`（工资页返回，**绝不点「继续打工」**）→确认户外→回房 |
| CARE | 先 `readHealth("plan-care-before")`；体力与清洁**都已达标** ⇒ `Skipped(ALREADY_SATISFIED)`（零消费）；否则 `care.recover(...)` 到本步骤阈值 → `readHealth("plan-care-ready")` 复核达标（否则 `CARE_COMPLETION_UNCONFIRMED`）→ 仅当**真实发生消费**（`planCareConsumed`）才 `completed(1, 0)`（0 分钟）→ 写 `plan-care.json`（thresholds / before / ready / careStatistics / tuitionReserve） |
| STUDY | 每节独立：`CourseTiming.resolve` → `study.start(beforeStart={ ctx.beforeConsumingInput(i, minutes) }, ensureTuition={ 学费未变 + 余额 ≥ 学费 })` → `startConfirmed(i)` → 高五窗口 → `waitResult` → 写 `plan-round-<i>-completion.json` → `completed(i, durationMinutes)` → `ledger.complete(round)`（重复即 `DUPLICATE_ROUND_COMPLETION`）→ 发 `ROUND_RESULT_CONFIRMED` → 回房。**健康只观察，不设门禁** |

---

## 5. 终态与对外状态

### 5.1 事件流终态（**不用于判成败**，research R3）

成功收尾也走 `SessionRecovery.finish(stopReason ?: "BATCH_COMPLETE")` → `SessionRunState.STOPPED` → append `SESSION_STATE{state:"STOPPED", reason:…}`。因此 `watch_acceptance.py` 的 `{STOPPED, FAILED, COMPLETED}` 只表示「可以停止轮询并归档」。

### 5.2 权威判定字段

`pet_mvp_session/<ts>/verdict.json`：

```json
{ "verdict": "PLAN_PASS|STOPPED|WORK_PASS|CARE_PASS|HEALTH_CHECK_PASS|MANAGED_BATCH_PASS",
  "mode": "plan", "completed": <studyRoundsCompleted>, "requested": <requestedRounds>,
  "workCompleted": <counts.workShiftsCompleted|null>, "careCompleted": <counts.careCompleted|null>,
  "planRunState": "COMPLETED|FAILED|UNCONFIRMED|STOPPED|RUNNING|…|null",
  "stopReason": <string|null>, "captureStatistics": {…} }
```

服务层 stopReason 文案：`PLAN_PASS` ⇒ `"PLAN_PASS work=<W> care=<C> study=<S>/<requested>"`；其余取 `controller.stopReason` 或 `"STOPPED"`。

`files/plan-runs/<runId>.json`（`StoredRun`）：`snapshot`（state / steps[] / counts / stopReason / 时间）+ `attempts[]`。**这是运行真相的权威来源**，verdict.json 是其摘要。

### 5.3 收尾与预算兜底

- `finally` 中对所有 `reservedAttempts` 里 status ≠ COMPLETED 的 attempt 调 `budget.unconfirmed(id, controller.stopReason ?: "SESSION_ENDED_WITHOUT_COMPLETION_PROOF")` —— **保守保留占用，不退款**；随后 `capture.stop()`。
- job 完成回调：`coordinator.finish(runId)` → `finalizeStop(runId)`（校验 owner 未变）→ `logLine("SESSION_STOP <reason>")` → `PetRuntime` 置 `running=false, runState=STOPPED` → 更新通知 → 释放 wakeLock → `stopForeground` + `stopSelf`。
- 会话异常 ⇒ `stopSession("会话异常：<类型>: <消息>")`；`CancellationException` 原样上抛。

---

## 6. 只读观察任务（不执行消费动作）

| 任务 | 触发 | 关键约束 |
| --- | --- | --- |
| `COMMITTED_NON_STUDY_FINALIZATION` | 收据为 UNCONFIRMED，或 v2 有阻塞 run | 20s 超时；`noOtherPending()`（v2 只剩目标 run + 账本无 unresolved）；checkpoint 存在且 ≤1MB、`sessionId==runId`、`actionPending==false`；`displayId == run.request.displayId`；等 CONNECTED + 归属；取房间基线双帧；`finalizer.finish(...)` 前再次 `authority()` + `noOtherPending()` + checkpoint 未变；成功记 `PLAN_FINALIZED_NO_REPLAY run=… work=… care=… study=0`，失败记 `PLAN_FINALIZATION_REFUSED:…`。**方案含 STUDY 时模型层禁止**（`FINALIZATION_STUDY_OR_LEGACY_FORBIDDEN`） |
| `LEGACY_ZERO_MINUTE_CARE_RECONCILIATION` | 遗留 CARE pending 阻塞 | 需 7 条 proof 齐备 + 房间基线 + （2 条 actionPending）付款结算收据；成功记 `LEGACY_CARE_RECONCILED_STOPPED count=… evidence=…`，拒绝记 `LEGACY_CARE_RECONCILIATION_REFUSED:…` 或 `LEGACY_CARE_OBSERVATION_NOT_ADMITTED`。**只追加 RECONCILED_STOPPED，不改分钟、不重放** |

两者都经 `admitObservation`（`checkDurable=false`）取得独立 observationId，并在 `finally` 中 `coordinator.finish(observationId)`。
