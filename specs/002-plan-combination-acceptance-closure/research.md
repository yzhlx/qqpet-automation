# Phase 0 Research: 组合验收收口

**Feature**: `specs/002-plan-combination-acceptance-closure` | **Date**: 2026-10-23

本轮 Technical Context 无未知项需要外部研究；四项决策全部由**本地源码核实**得出，逐条给出 Decision / Rationale / Alternatives。R2 已于 2026-10-23 由用户裁决为 (a)，四项均已关闭。

---

## R1 — 准入阻塞：遗留 CARE pending 会拒绝每一次 PLAN 启动

**Decision**：Step 0 必须先通过既有观察路径把 7 条遗留 `UNCONFIRMED_RETAINED` 的 0 分钟 CARE attempt 原子记为 `RECONCILED_STOPPED`，在 `CarePendingReconciliation.unresolved()` 与 `PlanRunPendingGate.blockingRunId()` 双双清空后，才允许开跑任何新组合。不删账本、不改分钟、不重放输入。

**Rationale（源码实证）**：
- `PetAutomationService.coordinator` 的 `PendingGate`（构造于 service 字段）求值顺序为：① 触发 `DailyBudgetStore(this).usedMinutes()` 的一次性 legacy 导入 → ② `PersistentBudgetStore(...).read().attempts.values.firstOrNull(CarePendingReconciliation::unresolved)?.attemptId` → ③ 否则 `PlanRunPendingGate.blockingRunId(files/plan-runs)`。第②步优先于第③步，**遗留 CARE pending 是第一位阻塞源**。
- `CarePendingReconciliation.unresolved(attempt) = attempt.status !in {COMPLETED, RECONCILED_STOPPED}`，因此 `UNCONFIRMED_RETAINED` 判为未解决。progress.md 记录账本现存 7 条此类 attempt（全 0 分钟 care，2 条 `actionPending=true`）。
- 准入被拒后 `launchOwned` 走 `admission.reason.startsWith("PENDING_RUN_UNRESOLVED:")` 分支：若 v2 无阻塞则 `launchLegacyCareObservation(intent, block)`，即**先做遗留观察，不执行本次请求**。
- 清算证据要求（`CarePendingReconciliation.validate`）：`noV2Pending`、每条 pending 都在 7 个 allowlist sessionId 内且 `minutes==0 && status==UNCONFIRMED_RETAINED && day=="2026-10-22"`、proofs 与 pending 一一对应、房间基线（`displayId>0`、双帧间隔 ≤5000ms、`now-secondAt ≤3000ms`、stable/roomVerified/overlaysAbsent、双帧钱包 Known 且 confidence≥0.95、来源 DIGIT_RECOGNITION、两帧钱包相等且 evidence 不同）；`actionPending=true` 的两条还必须有通过 `LegacyPaySettlementPolicy.requireValid` 的付款结算收据（`LegacyPaySettlementReader` 对最后输入 TAP 360/1425、before/after 帧尺寸 720×1600、food xor soap、时间序等逐条硬校验）。

**Alternatives considered**：
- *直接削弱 `unresolved` 或清空账本让新 run 通过* —— **拒绝**：违反 G2/G3（不得削弱门禁凑绿、不清账本），且会把「结果不明」伪装成「已解决」。
- *把遗留观察路径跳过、直接发 PLAN 意图* —— **无效**：准入在 service 层就被拒，请求不会执行，只会留下一条 `SESSION_START_REJECTED`。
- *逐条手工改 bin* —— **拒绝**：账本二进制是唯一权威，手工改写破坏可复核性且违反 G3。

---

## R2 — LEGACY 语义缺口：生产中不存在 LegacyManagedAdapter（已裁决 (a)）

**Decision（用户已裁决 (a)）**：本轮**不新建** legacy 适配器。LEGACY / LEGACY-DIFF 两行按「已实现的显式转换语义」如实记录，**不声称** 001 FR-014 的「迁移后保持每轮护理 + 首轮按需工作」达成；该行判「未定」，SC-004 不判通过。

**Rationale（源码实证）**：
- 全仓库 grep 无 `LegacyManagedAdapter` 类型；001 tasks.md 的 T043 要求新增它，但树中不存在。
- `SelectedPlanConfiguration.toSessionPlan` 首行 `require(selected.executionProfile==ExecutionProfile.SELECTED_ACTIONS) { "LEGACY_PROFILE_REQUIRES_LEGACY_ADAPTER" }` —— 一旦方案是 `LEGACY_MANAGED`，转换直接抛错，没有可执行路径。
- UI（`PetPlanSheetScreen.persistSelection`）保存时**硬编码** `executionProfile = ExecutionProfile.SELECTED_ACTIONS`，仅把旧 `legacySource` 字符串带过去；读回旧 `LEGACY_MANAGED` 方案时只显示兼容提示「保存为新版方案后，护理执行一次；打工前需已满足岗位条件，后置护理不会提前执行」，并在下次保存时转成 SELECTED_ACTIONS。
- `ExecutionProfile.LEGACY_MANAGED` 在生产中仅被两处**读取**：UI 的兼容提示判断、`PlanExecutionModels` 的 `RUN_CARE_COUNT_EXCEEDED` 放宽分支；没有任何路径**产生**它。
- 结论：旧 v1 托管语义（每轮护理、首轮条件工作）实际由 `SessionMode.FULL` + `SessionPlan`（`PetSessionController.runRounds`）承载，与 PlanV2 的 SELECTED_ACTIONS 是**两条并存路径**，不是「迁移」。

**Alternatives considered**：
- *本轮实现 LegacyManagedAdapter* —— **不建议**：属于 001 T043 的开发任务，超出「验收收口」范围，会引入新的生产路径与回归面，违反 spec 的 Out of Scope（不重建执行链）。
- *把 LEGACY 行直接判通过* —— **拒绝**：会伪造迁移保真结论，违反 G1/G4。
- *删除 LEGACY_MANAGED 枚举* —— **拒绝**：会破坏既有 `PlanExecutionModels` 分支与历史文档一致性，且属范围外重构。

**裁决结果（用户，2026-10-23）**：选 **(a) 按现状如实记录**。LEGACY 行记为「显式转换、非迁移保真」并判未定；本轮**不**新建 `LegacyManagedAdapter`（属 001 T043 开发任务，超出验收收口范围）；SC-004 相应不判通过。T039 由「向用户取得裁决」降级为「记录本裁决原文 + 据此写定判定」。

---

## R3 — 监控终态语义：成功也发 STOPPED

**Decision**：`watch_acceptance.py` 的终态检测只用于「何时停止轮询并触发 capture」，**不用于判成败**。成败判定唯一来源是 `verdict.json`（`verdict` / `planRunState` / counts）+ `files/plan-runs/<runId>.json`。

**Rationale（源码实证）**：
- `PetSessionController.run()` 成功收尾调用 `flow.recovery.recovery.finish(stopReason ?: "BATCH_COMPLETE")`；`SessionRecovery.finish` → `change(SessionRunState.STOPPED, reason)` → 通过 `PetRecoveryBoundary` 的 transition 回调 append 一条 `EventKind.SESSION_STATE` payload `state=STOPPED`。
- 因此**成功与失败都会出现 `state in {STOPPED, FAILED, COMPLETED}`**；`watch_acceptance.py` 正是以这三个值作为退出条件（随后自动调 capture），它无法区分「PLAN_PASS 后的正常 STOPPED」与「异常 STOPPED」。
- 真正区分在 `PetAutomationService`：`verdict` 为 `PLAN_PASS` 时 stopReason 写成 `"PLAN_PASS work=… care=… study=…/…"`，并把 `planRunState`、counts 写入 `verdict.json`；失败时 verdict 为 `STOPPED` 且带具体 stopReason。
- `PlanRunner` 侧：整体成功 → `RunState.COMPLETED`；前项 Blocked/Failed → `FAILED`；Unconfirmed → `UNCONFIRMED`；Cancelled → `STOPPED`。这与 service 的 `SESSION_STATE` 是**两套状态机**，判定必须读 persisted plan-run 而非事件流的 STOPPED。

**Alternatives considered**：
- *以 SESSION_STATE=COMPLETED 判成功* —— **拒绝**：成功路径根本不发 COMPLETED（发的是 STOPPED/BATCH_COMPLETE）。
- *以 stopReason 文本前缀判成功* —— 可作辅助，但不如直接读 `verdict.json.verdict` 与 `planRunState` 权威。

---

## R4 — 证据采集缺口：STUDY 组合与 SAVE-7 取证不足

**Decision**：Step 1 对 `capture_acceptance_state.py` 做**最小只读扩展**：新增采集 `plan-round-*-completion.json`（按事件流里出现的 round 枚举）与 `files/plan-v2.json`；`watch_acceptance.py` 终态后补打印一行 verdict 摘要（不改判定逻辑、不发输入）。

**Rationale（源码实证）**：
- `capture_acceptance_state.py` 现有可选文件名单为 `verdict.json, run-record.json, round-1.json, round-1-completion.json, plan-steps.json, plan-care.json, plan-run.json, work-only.json, care-only.json` —— **含 `round-1-completion.json` 但不含 `plan-round-N-completion.json`**（PLAN 路径写的是后者，见 `PetSessionController.runPlanStudy`：`File(evidence,"plan-round-${flow.round}-completion.json")`）。含 STUDY 的组合因此拿不到逐节完成证据。
- 该脚本采集 `files/plan-runs/<run>.json`（persisted snapshot）但**不采集 `files/plan-v2.json`**（方案本体），SAVE-7 的「保存/回读一致」缺权威对照物。
- 两处都属**只读归档**扩展，不触碰判定门禁，符合 G2；脚本已内建 `save_json` 对 `adb exec-out` 的远端 cat 错误做 JSON 校验（`NOT_A_JSON_DOCUMENT`），扩展沿用同一保护即可。

**Alternatives considered**：
- *不改脚本、手工 adb pull* —— 可行但不可复核、易漏，且违背「工具化取证」的既有方向。
- *改判定逻辑让 STUDY 组合更易通过* —— **拒绝**：违反 G2，采集缺口与判定宽严是两件事。

---

## 汇总

| 编号 | 状态 | 结论 |
| --- | --- | --- |
| R1 | 已解决（源码实证） | Step 0 先清算 7 条遗留 pending，双门禁清空后才开跑 |
| R2 | **已裁决（用户 2026-10-23）** | 选 (a)：按现状记录 LEGACY 为「显式转换、非迁移保真」并判未定，不新建适配器，SC-004 不判通过 |
| R3 | 已解决（源码实证） | 成败只读 verdict + persisted plan-run，SESSION_STATE 不判成败 |
| R4 | 已解决（源码实证） | Step 1 最小只读扩展 capture 脚本，补 plan-round-N-completion 与 plan-v2 |

无遗留 NEEDS CLARIFICATION：R2 的范围裁决已由用户在 2026-10-23 作出（选 (a)），LEGACY 行按现状记录并判未定，SC-004 不判通过。
