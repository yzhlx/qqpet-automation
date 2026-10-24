# 组合缺陷记录（T022）

## D1 — 独立 WORK 完成不记账，事后正确阻塞所有新 run

**发现时间**：设备 2026-10-24 03:26:47 +0800（CS 启动被拒时实锤）。

**现象**（同 run 证据，session `d5bf86e8-9c57-4369-b311-f758c94023ca`，独立「打工」筹资班次）：

- 班次真实完成并结算：`work-reward-payroll-award award=68|balanceDelta=unread`（seq 56）、`PAYROLL_RETURNED_OUTDOOR`（seq 61）、verdict **WORK_PASS**、`SESSION_STOP WORK_PASS`（pet_mvp.log 03:20:23）。
- 但权威账本中该 run 的 attempt `36:d5bf86e8-…4:work:1` 停在 `UNCONFIRMED_RETAINED`、reason `SESSION_ENDED_WITHOUT_COMPLETION_PROOF`、10 分钟照扣。
- 下一次启动（CS 方案，正确保存为 CARE+STUDY）被准入拒绝：`SESSION_START_REJECTED PENDING_RUN_UNRESOLVED:36:d5bf86e8-…work:1`；恢复桥又报 `LEGACY_CARE_ATTEMPT_NOT_ELIGIBLE`（该桥只认 7 条 2026-10-22 的 0 分钟 care），最终 `SESSION_STOP LEGACY_CARE_RECONCILIATION_REFUSED`。**账本未被改动、未发任何输入、零消费。**

**根因**（源码实锤，非猜测）：`PetSessionController.runWorkOnly()`（`VirtualDisplay/app/src/main/java/com/ynk/virtualdisplay/automation/PetSessionController.kt` 约 247–296 行）在 `work.waitAndVerify` 返回 `Completed` 后直接走到 `WORK_COMPLETE` 与收尾，**缺少 `onWorkCompleted(started.durationMinutes)` 调用**。对照三条同类路径全部有此记账：

- FULL 托管内的筹资班次：约 711 行 `onWorkCompleted(started.durationMinutes)`；
- 方案 WORK 步骤：`runPlanWork` 约 398 行 `checkNotNull(activePlanContext).completed(1, started.durationMinutes)`（WS run `e978be5e` 的账本 attempt 因此正确 COMPLETED）；
- 而 `runWorkOnly` 只有 269 行 `onWorkStarted(...)`，没有对应 completed —— 服务层 `finally` 兜底 sweep 把它保守转成 `UNCONFIRMED_RETAINED`（这是正确的 fail-closed 行为，不是第二个缺陷）。

**影响**：独立 WORK 每成功一次，就在唯一权威账本里留下一个永久未解决 attempt；pending 门禁（设计正确）从此拒绝**一切**新启动（含组合、独立、健康检查），且现有恢复桥不收此类 attempt ⇒ 设备验收被单点卡死，直到该 attempt 被诚实清算。

**性质判定**：生产缺陷（记账缺失），不是门禁过严；门禁与 sweep 的行为都符合 G2 fail-closed。修复分两半：

1. **止血**：`runWorkOnly` 在 `done is WorkOutcome.Completed` 之后补 `onWorkCompleted(started.durationMinutes)`（与 FULL 路径逐字对齐）。
2. **清算已卡死的 `d5bf86e8` attempt**：需要一个证据回写的收口路径（该班次完成与工资已有同 run 证据，但没有任何生产代码路径能把 `UNCONFIRMED_RETAINED` 转成 `COMPLETED`/`RECONCILED_STOPPED`）。**这属于动账本的机制扩展，需用户裁决后才实施**（选项见 progress.md / 会话记录）。不得手工改写 bin、不得清账本、不得退款（10 分钟必须保留）。

**关联事实**：WS（`e978be5e`）与 WC（`b5600c4f`）走方案路径，均无此缺陷；缺陷仅影响独立 WORK 快捷入口。
