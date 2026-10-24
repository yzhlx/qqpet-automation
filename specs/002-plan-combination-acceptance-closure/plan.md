# Implementation Plan: 方案组合真机验收收口

**Branch**: `feature/qqpet-mvp-session` | **Date**: 2026-10-23 | **Spec**: [spec.md](spec.md)

## Summary

本轮**不是新功能开发**：统一 PLAN 执行链（`PlanV2` → `PlanRunner` → `PlanDeviceExecution` → `FileRunStore` + 持久分钟账本）已在树中并跑通过 WC。要做的是三件事，按此顺序：

1. **解除准入阻塞**（Step 0）——已核实：`PetAutomationService` 的 `PendingGate` 先查 `PersistentBudgetStore` 里未解决的遗留 CARE attempt，**7 条 `UNCONFIRMED_RETAINED` 会让每一次 PLAN 启动被拒并改走观察路径**，因此 WS/CS/WCS 在收口前根本无法开跑。
2. **按 WS → CS → WCS → FUND-\* → SAVE-7 的顺序取得同 run 设备证据**，只接受 verdict + `files/plan-runs/<runId>.json` + 权威 `pet-minute-budget-v2.bin` 三者一致。
3. **把文档同步到真实状态**：修正 `results.md` 中陈旧的 WC 行、如实记录 LEGACY 语义差异、把 US4 保持在未接线状态。

关键设计约束：**不新增测试接缝**（沿用 S1 设备端到端 / S2 PlanRunner+fakes / S3 真实文件 / S4 纯规则），**不重写执行链**，**不削弱门禁凑绿**。

## Technical Context

| 项 | 值（已核实） |
| --- | --- |
| 语言 / 运行时 | Kotlin（JVM 目标 17），Android `compileSdk 36`（minor 1）、`minSdk 29`、`targetSdk 35` |
| 应用仓库 | `VirtualDisplay/`（ASCII 联接 `C:\Users\lu158\dsh-qqpet`），分支 `feature/qqpet-mvp-session`，**保留继承的脏工作树** |
| 版本号 | `versionCode = commitCount`、`versionName = "1.0.$commitCount"`（不提交则不涨号，同号可 `-r` 覆盖安装） |
| JVM 测试 | JUnit 4（`libs.junit`）。`gradle :app:testDebugUnitTest` **不可用**：Gradle worker 在中文路径下读不回自己的 classpath @argfile，所有测试 `ClassNotFoundException`。权威入口 `VirtualDisplay/tools/jvm-tests/run_tests.py`（支持类名 glob 过滤与 `--log`，**`raise SystemExit(r.returncode)` 传播真实退出码**，2026-10-21 起）；`.build-tmp/run_tests.py` 是旧副本，无过滤/无 `--log`/不传播退出码，**不要用它判定**。前置：`.build-tmp/cp.lst`（UTF-8，逐行现存条目，含新编译的单测类、`compile_app_classes_jar/classes.jar`、junit、mockable android.jar）+ 已编译类；cwd 必须 `VirtualDisplay/app`（`DialogDigitReaderTest` 相对解析 `src/main/assets`）。当前基线约 729 项绿 |
| 设备测试 | `androidTest` 依赖已配（androidx junit / espresso / compose ui test），但**本轮不用**：真机经济不可重置，SAVE-7 往返用 S3 真实文件接缝 + 设备只读回读代替 |
| 设备 | Huawei FOA-AL00（`Build.MODEL` 被硬校验）、Android 12、serial `DKS9K23420020242`；QQ `com.tencent.mobileqq` 9.3.70 / longVersionCode 16410（**版本被硬校验**）；游戏虚拟屏 720×1600@320dpi，编号会变（4→5→6），必须显式传 `--display-id` |
| 持久化 | 内部 `files/`：`plan-v2.json`（方案）、`plan-runs/<runId>.json`（运行快照）、`pet-events/<sessionId>.jsonl`（事件流）、`pet-checkpoints/<sessionId>.json`、`pet-minute-budget-v2.bin`（**唯一权威分钟账本**）；`shared_prefs/pet_mvp_budget.xml`（legacy，仅一次性导入）；外部 `pet_mvp_session/<ts>/`（verdict.json、run-record.json、plan-run.json、plan-care.json、plan-round-N-completion.json） |
| 构建 / 签名 | 自带 JDK 21 + Gradle 9.3.1，`GRADLE_USER_HOME=tools/android-build/gradle-cache`，`JAVA_TOOL_OPTIONS` tmpdir → `.build-tmp`，`REAL_AIDL` → `aidl.exe.orig`，`kotlin.compiler.execution.strategy=in-process`；构建后**还原 aidl 原件**；`qqpet-debug.keystore` 重签，证书指纹 `a63a2254…cf77`；`adb install -r` 保留数据 |
| 观测工具 | `tools/diagnostics/watch_acceptance.py`（有界只读监控，默认 45 分钟，终态后自动调用 capture）、`capture_acceptance_state.py`（只读归档 bundle）、`restore_pet_environment.py`（守卫式恢复）。三者均**不发输入** |
| 规模 | 单设备、单用户、串行验收；七组合 + FUND/SKIP/CARE-GAP + SAVE-7 + 边界矩阵 |

**编辑纪律（已付学费）**：含中文的 Kotlin 源**禁用 PowerShell `-replace`/`Set-Content`**（曾导致 `PetSessionController` 20 处、`PetAutomationService` 13 处 UTF-8 损坏），一律用 read/edit/write 或显式 UTF-8 的 Python。

## Constitution Check

`.specify/memory/constitution.md` **不存在**，`.specify/extensions.yml` **不存在**。

- 无成文宪章可校验，因此**不编造原则、不伪造门禁结论**。按 speckit 规则，前后 hook 均跳过。
- 本轮改用的治理约束来自项目自身的既有硬规则（在 [spec.md](spec.md) 与 `progress.md` 中反复确立），作为事实性门禁记录：
  - **G1 同 run 判定**：跨 run 拼接不得判通过（WC 的 COMPLETED 是 `CommittedPlanFinalizer` 收尾结果，必须如实标注）。
  - **G2 fail-closed**：Unknown 不填 0、预测工资不当余额、已派发鼓励不当业务成功；pending / `UNCONFIRMED_RETAINED` 正确阻止新 run，**不得削弱门禁凑绿**。
  - **G3 经济与数据边界**：不清账本、不充值、不额外领币、不插未选动作、不卸载清数据；未获授权不 commit/push。
  - **G4 证据可复核**：每条结论附 runId、构建 SHA-256 + 证书指纹、计数、资源前后值、预算变化、停止原因与 bundle 路径。
  - **G5 单测不代替真机**：JVM 绿只证明规则，不证明设备闭环；外层 shell 成功不代替 JUnit `EXIT 0`。
- **门禁结论**：无违反项需要辩护；G1–G5 全部作为本轮验收判定标准继承。

## Project Structure

**设计产物（本目录）**

| 文件 | 内容 |
| --- | --- |
| [spec.md](spec.md) | 需求（已确认接缝与发布方式） |
| [plan.md](plan.md) | 本文件 |
| [research.md](research.md) | Phase 0：4 项已核实决策（准入门禁、LEGACY 缺口、监控终态语义、证据采集缺口） |
| [data-model.md](data-model.md) | Phase 1：`PlanV2`/步骤/运行快照/attempt/账本 的字段、约束与状态迁移 |
| [contracts/acceptance-evidence.md](contracts/acceptance-evidence.md) | 证据契约：同 run 三源一致性 + bundle 必备文件 + 判定口径 |
| [contracts/device-run.md](contracts/device-run.md) | 设备运行契约：启动意图、准入门禁、终态与 verdict 字段 |
| [quickstart.md](quickstart.md) | 可运行验证指南（前置检查 → 每组合步骤 → 判读 → 常见拒绝码） |

**实施位置（既有文件，本轮以「验证 + 最小缺陷修复」为主）**

- 生产执行链：`VirtualDisplay/app/src/main/java/com/ynk/virtualdisplay/automation/` 下 `PlanV2.kt`、`PlanRunner.kt`、`PlanDeviceExecution.kt`、`PlanRunStore.kt`/`FileRunStore.kt`、`PlanRunJsonCodec.kt`、`PlanBudgetLedger.kt`/`PersistentBudgetStore.kt`/`DailyBudgetStore.kt`、`PlanRunCoordinator.kt`、`PlanResources.kt`、`PlanBudgetPolicy.kt`、`PlanDevicePreflight.kt`、`SelectedPlanConfiguration.kt`、`CommittedPlanFinalization.kt`、`CarePendingReconciliation.kt`/`LegacyPaySettlementReader.kt`/`CarePendingBaselineObserver.kt`、`PetSessionController.kt`（`runPlanSteps`/`runPlanWork`/`runPlanCare`/`runPlanStudy`）、`PetAutomationService.kt`、`StudyCompletionGate.kt`（**保持未接线**）
- UI：`ui/screens/PetPlanSheetScreen.kt`（勾选/校验/保存/保存并运行）、`ui/screens/PetHomeScreen.kt`、`ui/data/SessionStartRequest.kt`
- 测试（S2–S4）：`VirtualDisplay/app/src/test/java/com/ynk/virtualdisplay/` 下 `PlanRunnerTest`、`PlanRunnerReuseTest`、`PlanDeviceExecutionIntegrationTest`、`FileRunStoreTest`、`CommittedPlanFinalizationTest`、`PlanResourcesTest`、`PlanBudgetPolicyTest`、`PlanV2Test`、`PlanV2StoreTest`、`PlanRunCoordinatorTest`、`StudyCompletionGateTest`、`CarePendingReconciliationTest`、`LegacyPaySettlementTest`；**待新增**：`PlanRunnerCompositionTest`（七子集编排，对应 001 T029）
- 验收工具：`tools/diagnostics/watch_acceptance.py`、`capture_acceptance_state.py`
- 文档：`specs/001-managed-study-recovery/acceptance/results.md`、工作区 `progress.md`、`memory.md`

## Phase 0: Research Decisions

完整论证见 [research.md](research.md)。四项均已由源码核实，**无遗留 NEEDS CLARIFICATION**（其中 R2 需要用户裁决，已单列）。

- **R1 准入门禁（本轮第一阻塞）**：`PendingGate` 优先返回未解决的遗留 CARE attempt；`CarePendingReconciliation.unresolved()` 把 `UNCONFIRMED_RETAINED` 判为未解决 → 7 条遗留 pending 未清算前，任何 PLAN 启动都会被 `PENDING_RUN_UNRESOLVED:` 拒绝并改走 `launchLegacyCareObservation`。→ Step 0 必须先跑观察路径把 7 条原子记为 `RECONCILED_STOPPED`（需要各自 checkpoint、terminal record（mode=care、courseStarts=0）、2 条 `actionPending=true` 的付款结算收据、以及房间基线双帧钱包相等）。
- **R2 LEGACY 语义缺口（需用户裁决）**：生产中**不存在** `LegacyManagedAdapter`；`SelectedPlanConfiguration.toSessionPlan` 对 `LEGACY_MANAGED` 直接抛 `LEGACY_PROFILE_REQUIRES_LEGACY_ADAPTER`，而 UI 保存时**一律写成 `SELECTED_ACTIONS`** 并提示「护理执行一次；打工前需已满足岗位条件，后置护理不会提前执行」。→ 001 FR-014 的「保持原每轮护理/首轮按需工作」**未被实现**；本轮建议把 LEGACY 行判为「已实现的显式转换语义」而非「迁移保真」，不在验收期新建适配器。
- **R3 监控终态语义**：成功路径也会发出 `SESSION_STATE state=STOPPED`（`recovery.finish(stopReason ?: "BATCH_COMPLETE")`），因此 `watch_acceptance.py` 的终态检测**不等于成功**；判定必须读 `verdict.json` 的 `verdict`/`planRunState`/counts 与 `plan-runs/<runId>.json`。
- **R4 证据采集缺口**：`capture_acceptance_state.py` 的可选文件名单含 `plan-run.json`/`plan-care.json` 但**不含** `plan-round-*-completion.json` 与 `plan-v2.json`，对含 STUDY 的组合与 SAVE-7 回读取证不足 → 需最小扩展（只增只读采集项，不改判定逻辑）。

## Phase 1: Design

### 数据模型

见 [data-model.md](data-model.md)。已核实的硬约束（`PlanV2.init` / 各 step `init`）：`schemaVersion==2`、`name` 1..80、`dailyBudgetMinutes` 1..480、`steps` 1..3、stepId 唯一、类型唯一、顺序严格递增（WORK<CARE<STUDY）、`WorkStep.shifts==1`、`CareStep` 阈值 60..100、`StudyStep.rounds` 1..10、`IF_FUNDS_SHORT` 必须伴随 STUDY。七种非空组合因此**全部可由同一 UI 表达**，无需新模型。

### 契约

- [contracts/device-run.md](contracts/device-run.md)：`ACTION_START` + `EXTRA_PLAN_JSON` + `EXTRA_REQUEST_ID` + `EXTRA_DISPLAY_ID` 的启动契约；准入三段（收据查重 → `coordinator.admit` → PendingGate）；`verifyDisplay` 的硬校验（机型/QQ 版本/dpi/尺寸/归属）；终态与 verdict 字段。
- [contracts/acceptance-evidence.md](contracts/acceptance-evidence.md)：同 run 三源一致性判定、bundle 必备清单、通过/不通过/未定的口径、禁止的拼接方式。

### 兼容性与验证

- **向后兼容**：不改 `PlanV2` schema、不改账本二进制格式、不动 legacy `budget.xml` 的一次性导入语义；遗留 7 条 pending 只走既有恢复桥，不删不改分钟。
- **验证方式**：S1 设备端到端为唯一产品判定；S2 新增 `PlanRunnerCompositionTest` 覆盖七子集编排（未选 executor 零调用、前项非 COMPLETED/SKIPPED 使后项 PENDING、只有全部完成或合法跳过才整体 COMPLETED）；S3 用隔离临时目录跑七配置 codec/存储往返（**不动生产 `plan-v2.json`**）；S4 沿用既有纯规则测试。
- **回归门槛**：任何生产改动后全量 JVM 回归绿（基线约 729，`EXIT 0`），再构建 → 还原 aidl → 重签核对指纹 → `adb install -r` → 重跑该组合。

## Implementation Sequence

依赖有序，**单设备串行**，每步完成即更新文档；任一步暴露缺陷则「修复 → 全量回归 → 重装 → 重跑该步」，不跳到下一步。

1. **Step 0 — 环境与准入前置**（阻塞其余全部）
   0.1 确认 adb 在线、Shizuku 13.6.0 已连接（必要时用应用 UI 显示的 `lib/arm64/libshizuku.so` 命令重启 starter，再点「重试检测」）；0.2 确认当前自有虚拟屏编号与 720×1600@320 校准（**不假设 4/5/6**），QQ 在房间；0.3 只读复核 `b5600c4f` 仍为 COMPLETED；0.4 跑遗留 CARE 观察路径，把 7 条 pending 原子记为 `RECONCILED_STOPPED`，并复核 `PlanRunPendingGate.blockingRunId` 与 `unresolved` 均已清空。**未清空则停止并记录阻塞，不得绕过。**
2. **Step 1 — 取证工具最小扩展**（S1 接缝）：给 `capture_acceptance_state.py` 增加 `plan-round-*-completion.json`（按 events 里的 round 数枚举）与 `files/plan-v2.json` 两个只读采集项；`watch_acceptance.py` 终态输出补一行 verdict 摘要提示（不改判定）。改后对既有 run 跑一次只读复核，确认不破坏原 bundle 结构。
3. **Step 2 — 编排回归补测**（S2 接缝）：新增 `PlanRunnerCompositionTest` 覆盖七非空子集；全量 JVM 回归绿。此步不动生产源码，若测试暴露真实编排缺陷则按纪律修复后重跑。
4. **Step 3 — WS（打工→学习）**：方案页勾 WORK(ALWAYS) + STUDY(1 节) → 保存并运行 → `watch_acceptance.py --run-id <new> --display-id <n> --label plan-work-study`。经济前置：金币 15 < 学费 66，**必须在同 run 内靠打工筹资**；判据：同 run 真实工资到账（`work-reward-payroll-award` 正 award）→ 开课 → 完成登记 1/1；未选护理 feed/clean=0、无购买。
5. **Step 4 — CS（护理→学习）**：勾 CARE + STUDY；判据：护理达标或合法跳过、购买时保护后缀学费（`tuitionReserve`）、随后课程完成；未选打工 0 次派发。
6. **Step 5 — WCS（三步）**：勾全三项；判据：同 runId 工资到账 → 护理达标 → 正确课程完成，counts 为 W1/C1/S1，`planRunState=COMPLETED`。
7. **Step 6 — FUND-\***：FUND-LOW（IF_FUNDS_SHORT 资金不足真实触发一班并同 run 闭环）、FUND-SKIP（资金充足 → `SKIPPED/FUNDS_SUFFICIENT`、工作输入与分钟均 0）、FUND-CARE-GAP（学费够但护理缺钱时决策与 dispatch 一致；注意 `PlanDevicePreflight.careNeedsNoItemsAfterWork` 在无精确库存时会返回 `NeedsObservation`，这是**保守拒绝**而非缺陷）。
8. **Step 7 — SAVE-7**：七种非空配置逐个保存 → 杀进程/重开 → 只读回读 `plan-v2.json` 与 UI 展示一致，且**重开不自动启动消费**；S3 隔离往返测试同时覆盖损坏/未知版本保留拒绝。
9. **Step 8 — 文档收口**：修正 `results.md` 的 WC 行（标注 finalized COMPLETED）与本轮新增行；按 R2 的裁决如实写 LEGACY / LEGACY-DIFF；US4 保持未接线并注明前置条件；**修正 `acceptance/build-guide.md` §2 的陈旧断言正则**（旧格式 `classpath <n> tests <m> missing []`，权威 runner 现打印 `classpath <n> test classes <m> filters [...]`，直接复用会误判失败）；任务板 task-7/8/12/16 先 re-get 再按证据决定完成；更新 `progress.md` 与 `memory.md`。

> Step 3–7 之间若有生产改动，必须先完成「全量回归 → 构建 → 还原 aidl → 重签 → 覆盖安装」再继续，且**学习 run 进行中不安装新包**。

## Validation and Acceptance

判定口径详见 [contracts/acceptance-evidence.md](contracts/acceptance-evidence.md)；可执行步骤见 [quickstart.md](quickstart.md)。

| 场景 | 通过条件（同 run） | 记录位置 |
| --- | --- | --- |
| WS | verdict=PLAN_PASS、`planRunState=COMPLETED`、counts W1/C0/S1、正 award 证据、feed/clean=0 | `results.md` 新行 + bundle `…-plan-work-study` |
| CS | counts W0/C1/S1、护理达标或 ALREADY_SATISFIED、后缀学费保护可见、courseStarts=rounds | `results.md` + bundle `…-plan-care-study` |
| WCS | counts W1/C1/S1、三步顺序与方案一致、未选动作 0 | `results.md` + bundle `…-plan-work-care-study` |
| FUND-LOW | 同 run 内 IF_FUNDS_SHORT 真实触发一班 → 工资 → 后续完成 | `results.md` + bundle `…-fund-low` |
| FUND-SKIP | WORK step=SKIPPED/FUNDS_SUFFICIENT、工作 INPUT_SENT=0、分钟不增 | `results.md` + bundle `…-fund-skip` |
| FUND-CARE-GAP | 决策与实际 dispatch 一致（need 则真工作、不需要则跳过），不出现矛盾 | `results.md` + bundle `…-fund-care-gap` |
| SAVE-7 | 七配置保存/重开回读一致、重开不消费；隔离 S3 往返含损坏拒绝 | `specs/001-managed-study-recovery/acceptance/us2-persistence.md` |
| LEGACY / LEGACY-DIFF | 按 R2 裁决：记录**实际**转换语义与 UI 提示，不声称迁移保真 | `results.md`（如实标注） |
| US4 | 保持未接线；仅在成功 run 的结果 PNG 实证打印时刻语义后才评估接线 | `results.md` RESULT-OWNER 行 |

**每份记录必备**：runId、构建 SHA-256 + 证书指纹、所选配置、INPUT_SENT 次数、开始/完成计数、资源前后值、`pet-minute-budget-v2.bin` 前后分钟、停止原因、bundle 路径。Unknown 不填 0；不以服务 `SESSION_STATE=STOPPED` 判失败，也不以它判成功。

## Post-design Gate Check

- **G1 同 run**：设计全程以同 runId 三源一致为唯一判据，WC 的 finalized 性质明确标注 → 通过。
- **G2 fail-closed**：Step 0 要求先诚实清算遗留 pending（走既有恢复桥，不删账本）；FUND-CARE-GAP 明确把 `NeedsObservation` 认定为保守拒绝而非待修缺陷；不新增任何放宽门禁的路径 → 通过。
- **G3 经济/数据边界**：WS/WCS 明确「run 内打工筹资」，不借款不领币；S3 往返用隔离临时目录，不动生产 `plan-v2.json`；无 commit/push/清数据 → 通过。
- **G4 可复核**：证据契约固化必备字段与 bundle 命名；Step 1 先补齐采集缺口，避免「结论有、证据缺」 → 通过。
- **G5 单测不代替真机**：S2/S3/S4 只用于编排与持久化规则，产品判定唯一来源是 S1 → 通过。
- **未决项**：无。R2（LEGACY 语义）已由用户 2026-10-23 裁决为 (a)：按现状记录、判未定、不建适配器、SC-004 不判通过；LEGACY 行不得默认按迁移保真通过。

## Risks and Remaining Work

| 风险 | 影响 | 处置 |
| --- | --- | --- |
| 遗留 7 条 pending 无法全部取证（缺 checkpoint/terminal/付款收据） | Step 0 失败 → 全部组合无法开跑 | 逐条列出缺哪一项证据，记为阻塞；不削弱 `unresolved` 判定、不清账本 |
| 房间基线双帧钱包不相等或超 5s/3s 窗口 | 恢复桥与 finalizer 均拒绝 | 重取基线；仍失败则记 `ROOM_BASELINE_UNCONFIRMED` 并停止 |
| 金币 15 与学费 66 的经济前置 | WS/WCS 需真实打工筹资，run 时长与失败面增大 | 按 spec 在 run 内筹资；不额外领币；失败如实记录 |
| 虚拟屏编号变化 / Shizuku 进程死亡 | 点击与截图落到错误 display | 每步开工前重新确认编号并显式传 `--display-id`；APK 安装后重跑 starter + 重试检测 |
| `verifyDisplay` 硬校验机型与 QQ 9.3.70/16410 | QQ 升级即全链拒绝 | 记录为环境依赖，不在本轮放宽校验 |
| 成功也发 `STOPPED`（R3） | 误判成败 | 判定只读 verdict + persisted plan-run，不以 SESSION_STATE 为准 |
| STUDY 组合的结果归属仍 UNKNOWN（US4 未接线） | 完成登记依赖既有 `waitResult`，陈旧成绩单风险仍在 | 保持未接线；WS/CS/WCS 的完成判定以同 run 的 `plan-round-N-completion.json` + 预算分钟 + 回房金币为准，并在记录中标注 grade UNKNOWN |
| LEGACY 适配器缺失（R2） | 001 FR-014 与 SC-004 的迁移保真无法满足 | 交用户裁决；裁决前不声称通过 |
| 含中文源码被 PowerShell 破坏 | 编译失败或静默语义损坏 | 只用 read/edit/write 或显式 UTF-8 Python；改后必编译 |

**本轮明确不做**：重建执行链、US4 live 接线、OCR 再迭代、拖拽排序/重复步骤/自动增班、插件改造、git commit/push、清数据或发布。
