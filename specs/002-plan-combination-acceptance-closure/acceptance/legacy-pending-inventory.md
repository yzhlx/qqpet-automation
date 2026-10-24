# 遗留 CARE pending 清算证据（只读）

采集时间：工作站 2026-10-23 13:15 -04:00 / 设备 2026-10-24T01:15:54+0800
本段**未发起**新的观察任务：清算已在更早的会话完成（账本 `at=1792717134685`，晚于 WC run `b5600c4f` 的 10:29 收尾）。这里只做**证据清点与放行复核**，不重放、不改账本。

## T006 七条 attempt 的清算证据逐条清点

权威账本 `files/pet-minute-budget-v2.bin`（`tools/diagnostics/read_budget_ledger.py --show-attempts`）：7 条 `2026-10-22` 的 0 分钟 care attempt 全部为 **`RECONCILED_STOPPED`**，且 `reason` 中带有完整证据链（checkpoint / terminal / baseline / actionPending / 付款结算）。逐条核对：

| sessionId | 原停止原因 | actionPending | 付款结算 | checkpoint 存在 | terminal run-record | before/after pay 帧 |
| --- | --- | --- | --- | --- | --- | --- |
| `35ac319f-…` | `CARE_INVENTORY_UNCONFIRMED` | true | `LAST_PAY_SETTLED`（sequence 54，before/after mtime 齐全） | ✅ 2087 字节 | ✅ FAILED, courseStarts=0 | ✅/✅ |
| `43bced43-…` | `CARE_INVENTORY_UNCONFIRMED` | false | `NO_PENDING_INPUT` | ✅ | ✅ FAILED, courseStarts=0 | ✅/✅ |
| `60e4935f-…` | `HEALTH_STALE:OBSERVATION_EXPIRED_OR_CLOCK_REVERSED` | false | `NO_PENDING_INPUT` | ✅ | ✅ FAILED, courseStarts=0 | ✅/✅ |
| `af6a82db-…` | `CARE_WALLET_UNCONFIRMED` | false | `NO_PENDING_INPUT` | ✅ | ✅ FAILED, courseStarts=0 | ✅/✅ |
| `ce43ca0a-…` | `CARE_INVENTORY_UNCONFIRMED` | true | `LAST_PAY_SETTLED`（sequence 42） | ✅ 2104 字节 | ✅ FAILED, courseStarts=0 | ✅/✅ |
| `d8f87152-…` | `CARE_INVENTORY_UNCONFIRMED` | false | `NO_PENDING_INPUT` | ✅ | ✅ FAILED, courseStarts=0 | ✅/✅ |
| `e460a1fe-…` | `CARE_INVENTORY_UNCONFIRMED` | false | `NO_PENDING_INPUT` | ✅ | ✅ FAILED, courseStarts=0 | 不需要（actionPending=false） |

核对方式：

- **checkpoint**：`run-as com.ynk.virtualdisplay cat files/pet-checkpoints/<sessionId>.json` 可读且非空（两条 actionPending=true 的分别为 2087 / 2104 字节）。
- **terminal run-record**：`shell cat …/pet_mvp_session/<dir>/run-record.json` 解析成功，`terminalState=FAILED`、`courseStarts=0`、`sessionId` 前缀与 attempt 一致。七条的目录分别为 1792631909533 / 1792634536839 / 1792642776892 / 1792633249676 / 1792642047619 / 1792639325602 / 1792640783928。
- **before/after pay 帧**：`shell ls` 确认 6 条目录含 `round-1-exchange-before-pay.png` 与 `round-1-exchange-after-pay.png`；第 7 条（`e460a1fe`）无这两帧，但其 `actionPending=false`，按 `CarePendingReconciliation.validate` 不需要付款结算。
- **房间基线**：`shell ls …/files/pet_reconciliation/579e4dd3-4b5f-4535-9979-b4688d17e7dd/` 含 `baseline-1.png`、`baseline-2.png` 及两份 `baseline-*-observations.json`（与账本 `reason` 中的 baseline 路径一致）。
- **付款结算收据**：两条 actionPending=true 的 `reason` 内嵌 `LAST_PAY_SETTLED:` 段（session / sequence / wall / beforeMtime / afterMtime / runStart / runEnd / stoppedWall / beforeFoodTitle / beforeSoapTitle / afterFoodScene / afterBathScene / journal / before / after），字段与 `LegacyPaySettlementReader` 的硬校验项一一对应。

**结论**：7 条 attempt 的清算证据**全部齐备**，且已被原子写入账本（`RECONCILED_STOPPED` + 追加 reason），分钟数保持 0、未退款、未重放。

## T008 放行条件复核（成立）

| 检查 | 实测 | 结论 |
| --- | --- | --- |
| `CarePendingReconciliation.unresolved` 无命中 | `unresolvedAttemptIds = []`（10 条全 ∈ {COMPLETED, RECONCILED_STOPPED}） | ✅ |
| `PlanRunPendingGate.blockingRunId` 为 null | `blockingRunIds = []`、`blockingRunId = null`、`decodeErrors = []` | ✅ |
| 分钟未被改写 | 7 条遗留 attempt `minutes=0`；账本 `day=2026-10-22`、`legacyUsedMinutes=100` 与交接记录一致 | ✅ |
| 旧 checkpoint 未被改写 | 两条 actionPending=true 的 checkpoint 仍可读且字节数稳定（2087 / 2104） | ✅ |

⇒ **放行条件成立**：新的方案 run 不会被 `PENDING_RUN_UNRESOLVED:` 拒绝。T007（触发观察任务）因此**无需执行**——观察任务只会在准入被拒时触发，当前准入畅通；强行触发反而可能产生一条无意义的观察记录。把 T007 记为「不适用（阻塞已不存在）」而非「未做」。

## 与账本交叉核对的额外发现（供后续 run 判读）

- 账本 `day=2026-10-22` 而设备已是 `2026-10-24`：`legacyUsedMinutes=100` 属于 10-22 的遗留导入值，**不是** 10-24 的当日已用分钟。`usedMinutesToday` 的 100 是「legacy 100 + 当日(10-22) attempt 0」之和。任何新 run 的「开工前当日已用分钟」必须按 run 发生当日的 attempt 重新计算，不能沿用 100。
- `b5600c4f` 的 work/care 两条 attempt 记在 `day=2026-10-23`，与 WC run 的设备时间一致；其 10 分钟只计入 10-23 的当日总量。

## 2026-10-24 live ledger 复核（America/New_York 约 11:21）

`read_budget_ledger.py --show-attempts` 新鲜设备读取：`attemptCount=13`、`day=2026-10-22`、`legacyUsedMinutes=100`、`usedMinutesOnDeviceDay=50`。七条 CARE 仍各为 `minutes=0/status=RECONCILED_STOPPED`；PLAN run 工具现读到 2 条终态记录，`blockingRunId=null`、`decodeErrors=[]`。

需区分两层结论：T008 的**七条 CARE 清算**和 `PlanRunPendingGate` 子检查成立；全局账本仍有一个不同类型的 D1 例外：`36:d5bf86e8-9c57-4369-b311-f758c94023ca4:work:1`（10 分钟 `UNCONFIRMED_RETAINED`）。故本次 before 快照不是“无 unresolved”：`unresolvedAttemptIds` 仅此 WORK id。该项与七条 CARE 的 checkpoint/付款证据无关，须按 `combo-defects.md` 的同 run 证据写回路径处理；写回前只读快照保存在 `VirtualDisplay/.build-tmp/budget-before-work-writeback.json`。本节记录时尚未安装候选，也尚未执行 WORK 写回观察。

T007 的 legacy CARE 观察仍按前述结论标为不适用：七条 CARE 的原清算已在 10-23 工作站会话以前完成；再次触发没有 pending CARE 目标。不得为了满足任务文字而发起无意义消费 run。
