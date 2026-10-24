# Tasks: 方案组合真机验收收口（Plan Combination Acceptance Closure）

**Input**: Current feature design documents
**Prerequisites**: [spec.md](spec.md), [plan.md](plan.md); available [research.md](research.md), [data-model.md](data-model.md), [contracts/](contracts/), [quickstart.md](quickstart.md)

**Feature Directory**: `specs/002-plan-combination-acceptance-closure`
**Branch**: `feature/qqpet-mvp-session` | **Date**: 2026-10-23

## Format and Execution Rules

每个任务形如 `- [ ] TNNN [P?] [USn?] 描述 + 精确文件路径`。Setup / Foundational / Polish 阶段**不带**故事标签；用户故事阶段**必须**带 `[US1]`–`[US5]`。`[P]` 仅在前置已完成且**写作用域互不相交**时有效。

**故事分组**（spec.md 的 20 条用户故事按可独立验收的目标归并为 5 组）：

| 故事 | 覆盖 spec.md 用户故事 | 目标 |
| --- | --- | --- |
| US1 | 1–6 | WS / CS / WCS 三个组合取得**同 run** 设备证据 |
| US2 | 7–8 | 按需打工的资金决策与实际 dispatch 一致（FUND-LOW / SKIP / CARE-GAP） |
| US3 | 9–11 | 七配置持久化回读（SAVE-7）与旧语义如实记录（LEGACY / LEGACY-DIFF） |
| US4 | 16–17 | 结果归属保持未接线、保守门禁行为可复核 |
| US5 | 12–15, 18–20 | 证据完整性、文档诚实收口、遗留 pending 清算记录 |

**全局硬约束（每个任务都适用，违反即判任务未完成）**

- **G1 同 run**：只接受同 runId 的 `files/plan-runs/<runId>.json` + `pet_mvp_session/<ts>/verdict.json` + `files/pet-minute-budget-v2.bin` 三源一致；禁止跨 run 拼接（详见 [contracts/acceptance-evidence.md](contracts/acceptance-evidence.md)）。
- **G2 fail-closed**：Unknown 不填 0；预测工资不当余额；已派发鼓励不当业务成功；**不得削弱门禁凑绿**。保守停止（`NeedsObservation` / `*_UNCONFIRMED` / `DAILY_BUDGET_LIMIT`）是正确行为，记「未定 + 实际读数」，不记应用缺陷。
- **G3 经济与数据边界**：不清账本、不充值、不额外领币、不插未选动作、不卸载清数据；未获用户明确授权不 `git commit` / `push`；不外传原始设备截图与签名钥匙。
- **G4 可复核**：每条结论附 runId、构建 SHA-256 + 证书指纹 `a63a2254…cf77`、versionCode、计数、资源前后值、分钟变化、停止原因、bundle 路径、**设备实读时间**（Asia/Shanghai，不由 host 文件名推断）。
- **G5 单测不代替真机**：JVM 绿只证规则；产品判定唯一来源是设备同 run 证据。外层 shell 成功不等于 `EXIT 0`。
- **单设备串行**：所有设备任务（T007、T015–T021、T028–T032、T035–T036、T040）**不得并行**；`[P]` 只适用于纯本地文件任务。
- **编辑纪律**：含中文的 Kotlin 源**禁用** PowerShell `-replace` / `Set-Content`（曾损坏 `PetSessionController` 20 处、`PetAutomationService` 13 处），一律用 read/edit/write 或显式 UTF-8 Python。
- **display 编号**：每次开工重新确认（历史 4→5→6 变过），所有游戏侧截图/点击/监控显式传 `--display-id`，永不假设。

---

## Phase 1: Setup

**目标**：建立本轮验收的只读环境基线与取证能力，不改动任何业务源码、不启动任何 run。

- [X] T001 只读采集设备与环境基线（`adb devices` 在线状态、设备实读时间、Shizuku 版本与连接状态、当前自有虚拟屏编号与 720×1600@320 校准、房间金币、当前课表 `isUsable` 状态、已验证岗位清单），逐条附实际命令输出，写入 `specs/002-plan-combination-acceptance-closure/acceptance/environment-baseline.md`
- [X] T002 只读复核既有结论未被推翻：`b5600c4f-f334-4013-9db8-f95d5fc4cb0a` 的 `snapshot.state`/`stopReason`/`counts` 与权威账本当前 `day`/`legacyUsedMinutes`/`attempts` 状态，写入 `specs/002-plan-combination-acceptance-closure/acceptance/environment-baseline.md`（追加「既有结论复核」小节，注明 WC 为 finalized COMPLETED）
- [X] T003 新增只读账本解析工具 `tools/diagnostics/read_budget_ledger.py`：按 `VirtualDisplay/app/src/main/java/com/ynk/virtualdisplay/automation/PersistentBudgetStore.kt` 的二进制格式解析 `files/pet-minute-budget-v2.bin`（MAGIC=`QQPET_BUDGET` 以 `\x00\x0c` 长度前缀、VERSION=`2`、`day`、`legacyUsedMinutes`、`legacyByDay`、attempts 的 id/day/minutes/status/reason/limitMinutes），输出 JSON；**只读，绝不写设备文件**；格式不符时 fail closed 报错而非回退空账本
- [X] T004 [P] 新增只读运行快照汇总工具 `tools/diagnostics/read_plan_runs.py`：遍历 `files/plan-runs/*.json` 用 `PlanRunJsonCodec` 的字段口径输出每个 run 的 `state`/`stopReason`/`counts`/`steps[].state`/`attempts[].inputPending`，并给出 `PlanRunPendingGate.pendingRunIds` 等价的阻塞清单；**只读**，结果写入 `specs/002-plan-combination-acceptance-closure/acceptance/environment-baseline.md` 的「run 清单」小节
- [X] T005 建立本轮验收记录约定（bundle 命名 `continuation-<UTC时间戳>-<label>`、label 取值表 `plan-work-study`/`plan-care-study`/`plan-work-care-study`/`fund-low`/`fund-skip`/`fund-care-gap`/`save7-<combo>`/`work-writeback-before`/`work-writeback-after`、每条记录必备 12 字段、原始资料只留 `VirtualDisplay/.build-tmp/device-acceptance/` 不外传），写入 `specs/002-plan-combination-acceptance-closure/acceptance/README.md`

## Phase 2: Foundational

**目标**：解除准入阻塞（research R1）、补齐取证缺口（research R4）、补编排回归（S2 接缝）、修正构建文档。**本阶段全部完成前，Phase 3–7 的任何设备任务都不得开始。**

- [X] T006 逐条清点 7 条遗留 `UNCONFIRMED_RETAINED` 的 0 分钟 CARE attempt 的清算证据是否齐备（各自 `files/pet-checkpoints/<sessionId>.json` 是否存在且 `sessionId` 匹配、terminal run-record 是否 `mode=care` 且 `courseStarts==0`、`terminalState ∈ {STOPPED,FAILED,COMPLETED}`、2 条 `actionPending=true` 是否有满足 `VirtualDisplay/app/src/main/java/com/ynk/virtualdisplay/automation/LegacyPaySettlementReader.kt` 全部硬校验的付款结算收据），缺口逐条列明，写入 `specs/002-plan-combination-acceptance-closure/acceptance/legacy-pending-inventory.md`
- [X] T007 从生产 UI 发起一次方案运行以触发准入拒绝与 `LEGACY_ZERO_MINUTE_CARE_RECONCILIATION` 观察任务（**该观察只读 + 原子写账本，不发消费输入**），记录实际结果：成功则 `LEGACY_CARE_RECONCILED_STOPPED count=<n> evidence=<路径>`，拒绝则完整拒绝码与原因；bundle 用 `tools/diagnostics/capture_acceptance_state.py` 归档，写入 `specs/002-plan-combination-acceptance-closure/acceptance/legacy-pending-inventory.md`
- [ ] T008 用 T003/T004 工具复核放行条件成立：`CarePendingReconciliation.unresolved` 无命中（7 条均为 `RECONCILED_STOPPED`）**且** `PlanRunPendingGate.blockingRunId` 返回 null，且分钟数与旧 checkpoint 未被改写；把前后对照写入 `specs/002-plan-combination-acceptance-closure/acceptance/legacy-pending-inventory.md`（未成立则记阻塞并停止，不得绕过）
- [ ] T009 扩展 `tools/diagnostics/capture_acceptance_state.py` 的只读采集名单：新增 `plan-round-<i>-completion.json`（i 从 1 到事件流/方案推得的 rounds，PLAN 路径实际写的是该文件名而非 `round-1-completion.json`）与 `files/plan-v2.json`；沿用既有 `save_json` 的 JSON 校验，取不到时记入 `missingOptionalFiles` 而**不产出伪 JSON**；不改任何判定逻辑
- [ ] T010 [P] 扩展 `tools/diagnostics/watch_acceptance.py`：终态后额外打印一行 `verdict.json` 摘要（`verdict`/`planRunState`/`completed`/`requested`/`workCompleted`/`careCompleted`/`stopReason`），并在注释中写明「`SESSION_STATE=STOPPED` 不等于失败，成功路径也发 STOPPED」（research R3）；不改终态检测条件、不发输入
- [ ] T011 用一个既有 run（如 `b5600c4f-f334-4013-9db8-f95d5fc4cb0a`）回归验证 T009/T010：原 bundle 结构与 `summary.json` 字段未被破坏、新增项在含 STUDY 的 run 上能取到、在无 STUDY 的 run 上落入 `missingOptionalFiles`；把实际输出写入 `specs/002-plan-combination-acceptance-closure/acceptance/README.md` 的「工具回归」小节
- [ ] T012 [P] 新增 `VirtualDisplay/app/src/test/java/com/ynk/virtualdisplay/PlanRunnerCompositionTest.kt`（S2 接缝，对应 001 T029），用既有 fakes 覆盖七个非空子集：执行顺序恒为 WORK→CARE→STUDY、**未选类型的 executor 零调用**、前项 `BLOCKED/FAILED/UNCONFIRMED/CANCELLED` 使后项保持 `PENDING`（钉住 `RunSnapshot` 的 `RUN_EXECUTED_AFTER_STOPPED_STEP` 不变式）、只有全部步骤 `COMPLETED/SKIPPED` 才整体 `COMPLETED`、合法跳过仅 `SkipReason.FUNDS_SUFFICIENT`(WORK) 与 `SkipReason.ALREADY_SATISFIED`(CARE) 且 STUDY 无合法跳过、`counts` 来自 persisted attempts 而非 step 状态
- [X] T013 按 [quickstart.md](quickstart.md) §2 执行编译与全量 JVM 回归：`VirtualDisplay/.build-tmp/compile_latest.py` 期望 `EXIT 0` 且日志出现 `aidl.exe restored to original`；随后 `VirtualDisplay/tools/jvm-tests/run_tests.py --log VirtualDisplay/.build-tmp/junit-current.log` 期望同时出现 `classpath <n> test classes <m> filters [...]`、`OK (<≥729> tests)`、`EXIT 0`；把实际数字与日志路径写入 `specs/002-plan-combination-acceptance-closure/acceptance/build-regression.md`（实测：70 类、756 tests、EXIT 0；当前日志见 build-regression.md）
- [X] T014 修正 `specs/001-managed-study-recovery/acceptance/build-guide.md` §2 的陈旧断言正则：`^classpath [0-9]+ tests [1-9][0-9]* missing \[\]` 对应的是旧 runner 输出，权威 `VirtualDisplay/tools/jvm-tests/run_tests.py` 现打印 `classpath <n> test classes <m> filters [...]`，照抄会恒判「JUnit not proven passing」；改前先以 T013 的实际输出核对格式，并在该文件注明 `.build-tmp/run_tests.py` 是不传播退出码的旧副本、不得用于判定

---

## Phase 3: US1 — 三个组合的同 run 设备验收

**故事目标**（spec.md 用户故事 1–6）：WS / CS / WCS 各取得一次同 run 完成证据，执行顺序与方案一致，未选动作执行与消费次数为 0，前项失败后后项不执行。

**独立验收判据**：仅凭本阶段证据即可判定，不依赖 US2–US5。每个组合按 [contracts/acceptance-evidence.md](contracts/acceptance-evidence.md) §1 的三源一致性清单逐项核对；`counts` 与 `attempts` 中 `state==COMPLETED` 的条目逐类数目一致；所有 attempts `inputPending==false`；CARE attempt 的 `durationMinutes==0`；**含 STUDY 的组合必须原生跑到 COMPLETED，不得靠 `CommittedPlanFinalizer` 收尾**（`FINALIZATION_STUDY_OR_LEGACY_FORBIDDEN`）。

- [X] T015 [US1] 在 `VirtualDisplay/app/src/main/java/com/ynk/virtualdisplay/ui/screens/PetPlanSheetScreen.kt` 的生产 UI（设置 → 托管方案 → 运行方案；底部「设置」tab 的 Compose 文本常为零尺寸，点可点击父容器）勾选 WORK(ALWAYS，岗位取 `WorkCatalog` 中 `RewardConfidence.VERIFIED` 的「竹影武馆 / 帮武馆跑腿」) + STUDY(1 节，课程取已扫描且 `durationMinutes != null` 的课)、**不勾 CARE**，保存后用 `tools/diagnostics/read_plan_runs.py` 与只读 `cat files/plan-v2.json` 核对 steps 恰为 WORK→STUDY、trigger=ALWAYS、rounds=1，把配置快照写入 `specs/001-managed-study-recovery/acceptance/us2-combinations.md`
- [X] T016 [US1] 点「保存并运行」启动 WS，随即 `python -I tools/diagnostics/watch_acceptance.py --run-id <新runId> --display-id <当前> --label plan-work-study --minutes 45` 有界监控至终态并自动归档 bundle；记录 `MONITOR_RUN_ID`、INPUT_SENT 计数与全部 `SESSION_STATE`/`WORK_STARTED`/`WORK_COMPLETED`/`COURSE_STARTED`/`LEARNING_CONFIRMED` 事件时间线，写入 `specs/001-managed-study-recovery/acceptance/us2-combinations.md`
- [X] T017 [US1] 按三源清单判定 WS 并落档：`verdict==PLAN_PASS`、`planRunState==COMPLETED`、`counts=={workShiftsCompleted:1,careCompleted:0,studyRoundsCompleted:1}`、`care.feed_count==0 && clean_count==0`、`courseStarts==1`、工资证据为同工资页正 award（`work-reward-payroll-award`）且返回经左上箭头而非「继续打工」、分钟增量==工作分钟+课程分钟、grade 记 `UNKNOWN`（US4 未接线）；写入 `specs/001-managed-study-recovery/acceptance/us2-combinations.md` 并新增 `specs/001-managed-study-recovery/acceptance/results.md` 的 WS 行（替换「未取得同run组合证据」）
- [ ] T018 [US1] 同法保存并运行 CS（CARE 阈值按需 + STUDY 1 节、**不勾 WORK**），`--label plan-care-study`，记录时间线与 bundle 路径到 `specs/001-managed-study-recovery/acceptance/us2-combinations.md`
- [ ] T019 [US1] 判定 CS：`counts=={0,1,1}`、护理达标（`plan-care-ready` 双值 ≥ 阈值）或合法 `ALREADY_SATISFIED` 零消费、若发生购买则逐笔钱包读数与 `plan-care.json` 的 `tuitionReserve` 可见且购买后仍保留全部未付学费、工作类 `INPUT_SENT==0`、CARE attempt `durationMinutes==0`；写入 `specs/001-managed-study-recovery/acceptance/us2-combinations.md` 与 `specs/001-managed-study-recovery/acceptance/results.md` 的 CS 行
- [ ] T020 [US1] 同法保存并运行 WCS（三项全勾），`--label plan-work-care-study`，记录时间线与 bundle 路径到 `specs/001-managed-study-recovery/acceptance/us2-combinations.md`
- [ ] T021 [US1] 判定 WCS：`counts=={1,1,1}`、`snapshot.steps` 顺序为 WORK→CARE→STUDY 且状态依次 COMPLETED/SKIPPED、同 runId 内「正工资到账 → 护理达标 → 正确课程完成」三段证据齐备、`planRunState==COMPLETED` 为**原生**终态（核对 `files/plan-runs/<runId>.json` 无 `finalizations/` 审计文件）；写入 `specs/001-managed-study-recovery/acceptance/us2-combinations.md` 与 `specs/001-managed-study-recovery/acceptance/results.md` 的 WCS 行
- [X] T022 [US1] 若 T016/T018/T020 任一局未达 COMPLETED：从 bundle 的 `events.jsonl` + `pet_mvp.log` + `plan-run.json` 定位实锤根因（区分「保守门禁正确停止」与「生产缺陷」，前者记未定不改码），把时间线、根因证据与判定写入 `specs/002-plan-combination-acceptance-closure/acceptance/combo-defects.md`
- [ ] T023 [US1] 仅当 T022 判定为生产缺陷时修复对应源码（禁止削弱门禁、禁止用 PowerShell 正则改中文 Kotlin），随后按 [quickstart.md](quickstart.md) §3 走完整链路：`VirtualDisplay/.build-tmp/compile_latest.py` → `VirtualDisplay/tools/jvm-tests/run_tests.py`（`OK (… tests)` + `EXIT 0`）→ `VirtualDisplay/.build-tmp/build_sign_install_rules.py`（核对证书 `a63a2254…cf77`、`adb install -r` 保留数据）→ `VirtualDisplay/.build-tmp/restore_aidl.py`；把改动点、构建身份与回归数字写入 `specs/002-plan-combination-acceptance-closure/acceptance/build-regression.md`
- [ ] T024 [US1] 用 T023 的新构建重跑受影响的组合（回到 T016/T018/T020 对应步骤）并重新判定；旧构建的失败记录**保留为历史**，不回写为通过；更新 `specs/001-managed-study-recovery/acceptance/us2-combinations.md`
- [ ] T025 [US1] 取得「前项失败/结果不明/被停止后后项未执行」的设备证据：优先复用 T022 的真实失败局（核对失败步骤之后的 `steps[*].state` 全为 `PENDING`、后续动作 `INPUT_SENT==0`、无消费），不制造伪通过、不为凑证据插入未选动作；写入 `specs/001-managed-study-recovery/acceptance/us2-combinations.md` 的「stop-on-failure 证据」小节
- [ ] T026 [US1] 汇总更新 `specs/001-managed-study-recovery/acceptance/results.md` 的 SC-002 行（四组合：WC finalized + WS/CS/WCS 本轮结果）与矩阵中 WC/WS/CS/WCS 四行，每行附 runId、构建 SHA-256、counts、分钟前后、bundle 路径与通过/不通过/未定判定

## Phase 4: US2 — 按需打工的资金决策一致性

**故事目标**（spec.md 用户故事 7–8）：`IF_FUNDS_SHORT` 在资金不足时同 run 真实触发一班并闭环、资金充足时明确记为跳过且工作输入与分钟为 0、学费够但护理缺钱时决策与实际 dispatch 一致。

**独立验收判据**：仅凭本阶段证据判定。`planSkipWork` 的判定值必须与实际 dispatch 行为一致（判定跳过 ⇒ 零工作输入；判定需要 ⇒ 真实开工一班）；不得用另起独立 WORK 筹资的 run 冒充 FUND-LOW。

- [ ] T027 [US2] 评估三个资金场景的前置可达性并写明构造方式与合规边界（FUND-SKIP 需余额 ≥ 后缀学费、FUND-LOW 需余额 < 后缀学费、FUND-CARE-GAP 需学费够但护理需购买且现金不足）；明确**只能**用同 run 内打工或用户既有授权达成前置，不额外领币/不充值/不清账本；无法合规构造的场景直接记「未定 + 实际前置阻塞」，写入 `specs/001-managed-study-recovery/acceptance/us3-funding.md`
- [ ] T028 [US2] 按 T027 的前置保存并运行 FUND-SKIP 方案（WORK trigger=`IF_FUNDS_SHORT` + STUDY，余额 ≥ `tuitionReserveCoins`），`--label fund-skip`；记录 `plan-work-skipped` 事件的 `FUNDS_SUFFICIENT:tuitionReserve=<n>` 与实际余额读数到 `specs/001-managed-study-recovery/acceptance/us3-funding.md`
- [ ] T029 [US2] 判定 FUND-SKIP：WORK step `state==SKIPPED` 且 `reason=="FUNDS_SUFFICIENT"`、该 run 工作相关 `INPUT_SENT==0`、账本无 work attempt 或分钟不因工作增加、`counts.workShiftsCompleted==0`、后续 STUDY 仍正常完成；写入 `specs/001-managed-study-recovery/acceptance/us3-funding.md` 与 `specs/001-managed-study-recovery/acceptance/results.md` 的 FUND-SKIP 行
- [ ] T030 [US2] 按 T027 的前置保存并运行 FUND-LOW 方案（`IF_FUNDS_SHORT` + STUDY，余额 < 后缀学费），`--label fund-low`；记录同 run 内「余额不足 → 真实开工一班 → 同工资页正 award → 后续步骤完成」的完整时间线到 `specs/001-managed-study-recovery/acceptance/us3-funding.md`
- [ ] T031 [US2] 判定 FUND-LOW：WORK step 为 `COMPLETED`（非 SKIPPED）、`counts.workShiftsCompleted==1`、工资为正 award 且非余额、`planSkipWork` 判定与 dispatch 一致、后续学习同 run 完成、一班后仍不足则必须停止且不自行增班（`WorkStep.shifts` 模型层恒为 1）；写入 `specs/001-managed-study-recovery/acceptance/us3-funding.md` 与 `specs/001-managed-study-recovery/acceptance/results.md` 的 FUND-LOW 行
- [ ] T032 [US2] 按 T027 的前置运行 FUND-CARE-GAP 方案（`IF_FUNDS_SHORT` + CARE + STUDY，学费够但护理需购买且现金不足），`--label fund-care-gap`；记录实际触发的门禁（`CARE_SUFFIX_EXACT_INVENTORY_AND_COST_REQUIRED` 的 `NeedsObservation`、或购买前 `CARE_WALLET_UNCONFIRMED` / `CARE_INVENTORY_UNCONFIRMED`）与当时的真实钱包、库存观察来源（`EMPTY_STOCK_SCENE_PROOF` / `OWNED_STOCK_SCENE_PROOF` / `SCENE_COUNTER_LOWER_BOUND` / Unknown）到 `specs/001-managed-study-recovery/acceptance/us3-funding.md`
- [ ] T033 [US2] 判定 FUND-CARE-GAP 为「保守语义通过、正向闭环未定」：核对决策与 dispatch 无矛盾（判定需工作则真工作、判定不需要则跳过）、Unknown 未被当 0、未发生未授权购买；明确写明 `VirtualDisplay/app/src/main/java/com/ynk/virtualdisplay/automation/PlanDevicePreflight.kt` 在无精确库存时返回 `NeedsObservation` 是**设计上的保守拒绝而非缺陷**；写入 `specs/001-managed-study-recovery/acceptance/us3-funding.md` 与 `specs/001-managed-study-recovery/acceptance/results.md` 的 FUND-CARE-GAP 行
- [ ] T034 [US2] 更新 `specs/001-managed-study-recovery/acceptance/results.md` 的 SC-003 行（显式/条件工作全链）与 FUND-LOW/FUND-SKIP/FUND-30/FUND-CARE-GAP 四行，注明 FUND-30 的 30 分钟临界案例是否在本轮取得（未取得则保留未定，不用其它 run 顶替）

## Phase 5: US3 — 七配置持久化与旧语义

**故事目标**（spec.md 用户故事 9–11）：七种非空组合保存/重开回读一致且重开不自动启动消费；旧托管语义按**实际实现**如实记录，不声称迁移保真。

**独立验收判据**：SAVE-7 仅凭设备只读回读 + S3 隔离测试判定；LEGACY 仅凭源码实证 + UI 实际文案判定，按 R2 已作出的裁决 (a) 记为「显式转换、非迁移保真」并判未定，**不得判通过**。

- [ ] T035 [US3] 对七个非空子集（W / C / S / WC / WS / CS / WCS）逐个在生产 UI 保存方案 → 杀进程重开应用 → 只读 `cat files/plan-v2.json` 与 UI 展示逐项核对（动作集合、岗位 id、课程 id、节数 1..10、体力/清洁阈值 60..100、每日上限 1..480、trigger、name 1..80、steps 顺序 WORK<CARE<STUDY），每个配置记 `--label save7-<combo>` 的只读 bundle，写入 `specs/001-managed-study-recovery/acceptance/us2-persistence.md`
- [ ] T036 [US3] 验证「重开不自动启动消费」：每次重开后用 `tools/diagnostics/read_plan_runs.py` 确认 `files/plan-runs` 无新文件、事件流无新增 `INPUT_SENT`、账本分钟未变；同时核对空方案不可启动（UI 提示「请选择至少一个动作。」）与未选动作参数缺失不阻断（`relevantFields` 只校验已选项），写入 `specs/001-managed-study-recovery/acceptance/us2-persistence.md`
- [ ] T037 [P] [US3] 新增 `VirtualDisplay/app/src/test/java/com/ynk/virtualdisplay/PlanV2SevenComboRoundTripTest.kt`（S3 接缝，用**隔离临时目录**，绝不触碰生产 `files/plan-v2.json`）：七配置经 `PlanV2Store`/`PlanV2JsonCodec` 往返逐字段相等、`schemaVersion != 2` 与损坏 JSON 时保留原文并拒绝执行（不回退默认消费方案）、原子写不留半成品、运行中快照不随后续编辑变化；参照既有 `VirtualDisplay/app/src/test/java/com/ynk/virtualdisplay/PlanV2StoreTest.kt` 的写法
- [ ] T038 [US3] 以源码实证记录 LEGACY 的**实际**语义（research R2）：全仓库无 `LegacyManagedAdapter`；`VirtualDisplay/app/src/main/java/com/ynk/virtualdisplay/automation/SelectedPlanConfiguration.kt` 对 `LEGACY_MANAGED` 抛 `LEGACY_PROFILE_REQUIRES_LEGACY_ADAPTER`；`VirtualDisplay/app/src/main/java/com/ynk/virtualdisplay/ui/screens/PetPlanSheetScreen.kt` 保存时恒写 `SELECTED_ACTIONS` 并只携带 `legacySource` 字符串；兼容提示实际文案为「保存为新版方案后，护理执行一次；打工前需已满足岗位条件，后置护理不会提前执行。」；旧 v1 语义实际由 `SessionMode.FULL` + `PetSessionController.runRounds` 承载而非迁移；写入 `specs/001-managed-study-recovery/acceptance/us3-legacy.md`
- [ ] T039 [US3] 记录 R2 的**已作出裁决**（用户 2026-10-23 选 (a)：LEGACY 行按现状记「显式转换、非迁移保真」并判未定，本轮不建 `LegacyManagedAdapter`，SC-004 不判通过），把裁决原文、日期与据此写定的判定记入 `specs/001-managed-study-recovery/acceptance/us3-legacy.md`；**不得声称 001 FR-014 迁移保真已达成**（裁决原文见 [research.md](research.md) R2）
- [ ] T040 [US3] 复核 LEGACY-DIFF（新 WORK-only 不隐式准备护理）：用独立 WORK 入口跑一班并核对 `care.feed_count==0 && clean_count==0`、无 `cleanTo` 调用、无香皂购买，同时如实保留已知的「浮层清洁 -3 与目录 2 的差异未确认」这一限制，写入 `specs/001-managed-study-recovery/acceptance/us3-legacy.md`
- [ ] T041 [US3] 更新 `specs/001-managed-study-recovery/acceptance/results.md` 的 SAVE-7 / LEGACY / LEGACY-DIFF 三行与 SC-004 行（配置与迁移），SAVE-7 附七配置逐项核对结论，LEGACY 按 T039 裁决如实标注

## Phase 6: US4 — 结果归属保持未接线与保守门禁可复核

**故事目标**（spec.md 用户故事 16–17）：`StudyCompletionGate` 在打印时刻语义被实证前保持未接线；验收中暴露的缺陷被真正修复而非绕过；保守门禁的实际触发有可复核记录。

**独立验收判据**：仅凭源码 grep 证据 + 既有 run 的只读分析判定，**本阶段不接线、不改 `PetStudyFlow`**。

- [ ] T042 [P] [US4] 以源码证据确认 US4 仍未接线：`VirtualDisplay/app/src/main/java/com/ynk/virtualdisplay/automation/StudyCompletionGate.kt` 与 `VirtualDisplay/app/src/main/java/com/ynk/virtualdisplay/automation/StudyResultPageParser.kt` 存在且有测试，但 `VirtualDisplay/app/src/main/java/com/ynk/virtualdisplay/automation/PetStudyFlow.kt` 的 `waitResult` 路径无 gate 调用、时间语义默认 `UNKNOWN`；把 grep 结果与结论写入 `specs/002-plan-combination-acceptance-closure/acceptance/us4-gate-not-wired.md`
- [ ] T043 [P] [US4] 从本轮成功 run 的结果页截图（bundle 内 `display-<n>.png` 与 `pet_mvp_session/<ts>/` 画面）收集打印时刻样本，只读分析「成绩单打印的完整 `yyyy/MM/dd HH:mm`」与请求分钟/完成分钟的关系，明确它**能否**作为完成时刻；不做接线、不下超出样本的结论，写入 `specs/002-plan-combination-acceptance-closure/acceptance/us4-print-time-samples.md`
- [ ] T044 [US4] 仅当 T043 实证了打印时刻语义：提出把 gate 接入 `VirtualDisplay/app/src/main/java/com/ynk/virtualdisplay/automation/PetStudyFlow.kt` 的最小方案（改动点、回归范围、风险），写入 `specs/002-plan-combination-acceptance-closure/acceptance/us4-print-time-samples.md` 并**等待用户授权后才实施**；未实证则明确记录「语义仍未证实，保持未接线」
- [ ] T045 [US4] 汇总本轮实际遇到的保守停止案例及其真实读数（`COURSE_COINS_INSUFFICIENT` 附余额与学费、`DAILY_BUDGET_LIMIT` 附分钟账、`CARE_INVENTORY_UNCONFIRMED` / `CARE_WALLET_UNCONFIRMED` 附观察来源、`PENDING_RUN_UNRESOLVED` 附阻塞 id），逐条标注「保守停止符合预期」而非应用缺陷，写入 `specs/001-managed-study-recovery/acceptance/us5-safety.md`
- [ ] T046 [US4] 跨本轮全部已完成组合复核「未选动作零执行」：对每个 run 核对 `snapshot.steps` 只含所选类型、`counts` 中未选动作为 0、事件流中无归属该动作的 `INPUT_SENT`、`run-record.json` 的 `care.feed_count`/`clean_count`/`courseStarts` 与所选一致；把逐 run 对照表写入 `specs/001-managed-study-recovery/acceptance/us2-combinations.md` 的「未选动作零执行」小节
- [ ] T047 [US4] 更新 `specs/001-managed-study-recovery/acceptance/results.md` 的 RESULT-OWNER / RESOURCE-UNKNOWN / BUDGET / PAYROLL-UNKNOWN 四行与 SC-005、SC-007、SC-008 行：RESULT-OWNER 保持「部分：完整归属机制未定（US4 未接线）」，BUDGET 附本轮各 run 的分钟前后与幂等观察，Unknown 一律不填 0

## Phase 7: US5 — 证据完整性与文档诚实收口

**故事目标**（spec.md 用户故事 12–15, 18–20）：文档与设备真实状态一致（含修正陈旧的 WC 行）、每条结论可独立复核、遗留 pending 诚实清算、任务板状态不超前于事实。

**独立验收判据**：仅凭文档与 bundle 的交叉核对判定，不需要新的设备 run。

- [ ] T048 [P] [US5] 修正 `specs/001-managed-study-recovery/acceptance/results.md` 中陈旧的 WC 行：由「未取得同run组合证据」改为如实描述——run `b5600c4f-f334-4013-9db8-f95d5fc4cb0a` 的 `state=COMPLETED` 是 `CommittedPlanFinalizer` 对已提交 attempt 的**收尾**结果（`FINALIZE_ALREADY_COMMITTED_NON_STUDY`），附审计 bundle `VirtualDisplay/.build-tmp/device-acceptance/continuation-20261022-211328-845483-plan-work-care-recovered/` 中 legacy run-record（FAILED/HEALTH_STALE）与 persisted-plan-run（COMPLETED）的对照，并注明这不等于原生同 run COMPLETED
- [ ] T049 [US5] 全矩阵逐行核对 `specs/001-managed-study-recovery/acceptance/results.md`：每行有 runId、构建 SHA-256 + 证书指纹、所选配置、INPUT_SENT 计数、开始/完成计数、资源前后值、分钟前后、停止原因、bundle 路径、判定与一句理由；Unknown 未写成 0、未出现跨 run 拼接、host 文件名未被当作设备业务日期；把核对清单与发现的缺口写入 `specs/002-plan-combination-acceptance-closure/acceptance/record-audit.md`
- [ ] T050 [P] [US5] 新增只读校验工具 `tools/diagnostics/validate_acceptance_record.py`：按 [contracts/acceptance-evidence.md](contracts/acceptance-evidence.md) §4 的 12 项必备字段检查 `specs/001-managed-study-recovery/acceptance/results.md` 的矩阵行与指定明细文件，缺字段或出现「Unknown→0」可疑写法时非零退出并列明行号；**只读，不改被检文件**；把首次运行输出写入 `specs/002-plan-combination-acceptance-closure/acceptance/record-audit.md`
- [ ] T051 [US5] 更新 `specs/001-managed-study-recovery/acceptance/results.md` 的 SC 总表 SC-001..SC-008：每项给通过/不通过/未定 + 理由 + 支撑 runId 或 bundle，SC-002 按本轮四组合实际结果、SC-003 按 US2、SC-004 按 US3（含 T039 裁决）、SC-005/SC-007/SC-008 按 US4 与既有证据；不得用历史 PASS 顶替本轮未取得项
- [ ] T052 [US5] 对共享任务板 task-7（FileRunStore）、task-8（Coordinator 并发测试）、task-12（Plan UI / PlanV2Store + SAVE-7）、task-16（`CommittedPlanFinalization`）逐个先 `team_task_get` 取最新 revision，再对照树中源码、对应 JVM 测试与本轮设备证据决定是否 `team_task_update` 标 complete；证据不足则保留 in_progress 并写明缺什么，**状态不得超前于事实**；把逐项结论写入 `specs/002-plan-combination-acceptance-closure/acceptance/record-audit.md`
- [ ] T053 [US5] 更新工作区根部的 `./progress.md` 与 `./memory.md`：本段目标/验收标准/改动范围/已完成/当前状态/下一步/阻塞项，附最终构建身份（APK 名 + SHA-256 + 证书指纹 + versionCode）、JVM 回归实际数字与日志路径、全部 bundle 路径、未取得证据的诚实说明
- [ ] T054 [US5] 若本轮中途停止，写交接文件 `%TEMP%/dsh-handoff-qqpet-<日期>.md`：包含目标、工作区与设备约束、当前 display 编号、已验证进度（不得让下一轮重复声称）、准入门禁状态、剩余矩阵、已付学费的坑、建议技能；并在 `./progress.md` 末尾指向它

---

## Final Phase: Polish and Cross-Cutting Concerns

- [ ] T055 参数化 `tools/diagnostics/validate_feature_tasks.py`：当前实现把 001 的形状写死（断言 ID 恰为 T001–T064、`counts == {'shared':19,'US1':14,'US2':10,'US3':8,'US4':6,'US5':7}`、`FR` 18 项 / `SC` 7 项、`[US1-5]` 标签），对 002 直接运行必然失败；改为从被检 `tasks.md` 自身或命令行参数推导期望计数，保持对 001 的既有行为不变，并对 002 跑一次记录实际输出到 `specs/002-plan-combination-acceptance-closure/acceptance/record-audit.md`
- [ ] T056 清理本轮产生的临时诊断输出与一次性脚本（保留结构化证据与可复用工具），确认 `VirtualDisplay/.build-tmp/device-acceptance/` 下原始截图只在本地、未外传、未进入待提交范围，把清理清单写入 `specs/002-plan-combination-acceptance-closure/acceptance/record-audit.md`
- [ ] T057 复核构建环境已还原：`tools/android-build/sdk/build-tools/36.0.0/aidl.exe` 为原件（3512416 字节，可用 `VirtualDisplay/.build-tmp/restore_aidl.py` 还原）、无临时 keystore 副本残留、无钥匙口令写入任何文档或脚本，把核对结果写入 `specs/002-plan-combination-acceptance-closure/acceptance/build-regression.md`
- [ ] T058 最终只读一致性检查：`specs/002-plan-combination-acceptance-closure/spec.md`、`plan.md`、`research.md`、`data-model.md`、`contracts/device-run.md`、`contracts/acceptance-evidence.md`、`quickstart.md`、`tasks.md` 与 `specs/001-managed-study-recovery/acceptance/results.md` 之间无相互矛盾（尤其 WC 的 finalized 性质、US4 未接线、LEGACY 裁决、runner 输出格式），发现矛盾即修正并记录到 `specs/002-plan-combination-acceptance-closure/acceptance/record-audit.md`

---

## Dependencies

### 阶段与故事

```
Phase 1 (Setup, T001–T005)
        │
        ▼
Phase 2 (Foundational, T006–T014)   ← 阻塞全部设备任务
        │
        ├──► Phase 3 (US1, T015–T026)   组合三件套
        │            │
        │            ├──► Phase 4 (US2, T027–T034)   FUND-* 依赖 US1 已跑通含 WORK/STUDY 的方案路径
        │            └──► Phase 6 (US4, T042–T047)   T043/T045/T046 需要 US1 的成功 run 与保守停止样本
        │
        ├──► Phase 5 (US3, T035–T041)   SAVE-7 只需方案 UI 与只读回读，可与 US1 并行推进（但设备任务仍串行）
        │
        └──► Phase 7 (US5, T048–T054)   文档收口，需 US1–US4 的结论
                     │
                     ▼
             Final Phase (T055–T058)
```

### 关键任务前置

| 任务 | 硬前置 | 原因 |
| --- | --- | --- |
| T007 | T006 | 先知道缺哪项证据，才能预判观察任务会被拒的原因 |
| T008 | T003, T004, T007 | 放行判定需要账本与 run 清单工具，且必须在观察任务之后 |
| **T015–T021, T028–T032, T035–T036, T040（全部设备任务）** | **T008** | research R1：7 条遗留 pending 未清算 ⇒ 每次 PLAN 启动都被 `PENDING_RUN_UNRESOLVED:` 拒绝并转入观察，组合根本开跑不了 |
| T016, T018, T020 | T009, T010, T011 | 否则含 STUDY 的组合归档不到 `plan-round-N-completion.json`，SAVE-7 拿不到 `plan-v2.json`（research R4） |
| T017, T019, T021 | T012, T013 | 编排规则须先有 S2 回归兜底，且当前源码必须编译 + 全量绿 |
| T023 | T022 | 只有判定为生产缺陷才改码；保守门禁不改 |
| T024 | T023 | 新构建装机后才重跑 |
| T029, T031, T033 | T027 | 前置构造方式必须合规且可达，否则直接记未定 |
| T039 | T038 | 裁决已由用户作出（R2 选 (a)）；先有源码实证才能把判定写实 |
| T041 | T035, T036, T037, T039 | SAVE-7 与 LEGACY 结论齐备后才写矩阵行 |
| T044 | T043 | 语义未实证不得提出接线 |
| T046, T047 | T017, T019, T021（及 US2 各行） | 跨 run 复核需要各组合已判定 |
| T049, T051 | T026, T034, T041, T047 | 矩阵与 SC 总表是各故事结论的汇总 |
| T050 | T049 | 先人工核对定稿字段口径，再固化为工具 |
| T052 | T051 | 任务板结论要引用最终 SC 判定 |
| T058 | 全部 | 一致性检查是最后一步 |

### 故事独立性

- **US1 可独立验收**：只需 T001–T014，产出 WS/CS/WCS 三行同 run 证据即可判 SC-002。
- **US2 依赖 US1 的方案路径已跑通**（否则无法区分「资金逻辑问题」与「组合路径本身不通」），但其判定不依赖 US3–US5。
- **US3 可与 US1 并行推进**：SAVE-7 只用方案 UI + 只读回读 + S3 隔离测试，不需要组合跑通；LEGACY 纯源码实证 + 用户裁决。设备任务仍须与 US1 串行（单设备）。
- **US4 不接线、不改 `PetStudyFlow`**，因此不会与 US1–US3 产生写冲突；T042/T043 可与设备任务并行（纯本地只读）。
- **US5 是汇总层**，依赖前四个故事的结论，但其 T048（修 WC 行）与 T050（新工具）可提前并行做。

## Parallel Execution Examples

`[P]` 任务写作用域互不相交，可并行；**设备任务永远串行**（单设备、单运行会话，`PlanRunCoordinator` 本身也会拒绝并发：`RUN_ALREADY_ACTIVE`）。

**Phase 1（T003 与 T004 并行）**
```
Agent A: T003 → tools/diagnostics/read_budget_ledger.py（新文件）
Agent B: T004 → tools/diagnostics/read_plan_runs.py（新文件）
```

**Phase 2（三个不同文件并行，须在 T008 之后、T011 之前协调）**
```
Agent A: T009 → tools/diagnostics/capture_acceptance_state.py
Agent B: T010 → tools/diagnostics/watch_acceptance.py
Agent C: T012 → VirtualDisplay/app/src/test/java/com/ynk/virtualdisplay/PlanRunnerCompositionTest.kt（新文件）
```
> T011 需要 T009 与 T010 都完成（它同时回归两者），因此排在两者之后。

**Phase 5（隔离测试与设备取证并行）**
```
Agent A: T037 → VirtualDisplay/app/src/test/java/com/ynk/virtualdisplay/PlanV2SevenComboRoundTripTest.kt（新文件，隔离临时目录）
Lead   : T035/T036 → 设备只读回读（串行，独占设备）
```

**Phase 6（两个只读分析并行，输出到不同文件）**
```
Agent A: T042 → specs/002-plan-combination-acceptance-closure/acceptance/us4-gate-not-wired.md
Agent B: T043 → specs/002-plan-combination-acceptance-closure/acceptance/us4-print-time-samples.md
```

**Phase 7（文档修正与新工具并行）**
```
Agent A: T048 → specs/001-managed-study-recovery/acceptance/results.md（仅 WC 行）
Agent B: T050 → tools/diagnostics/validate_acceptance_record.py（新文件）
```
> T048 与 T049/T051 都写 `results.md`，必须串行：T048 先落，T049 再核对，T051 最后汇总。

## Implementation Strategy

### MVP：Phase 1 + Phase 2 + Phase 3（US1）

**T001–T026**。这是本轮的最小可交付价值：解除准入阻塞、补齐取证工具、取得 WS/CS/WCS 三个组合的同 run 证据，把 SC-002 从「未定」推到可判定状态，并修正矩阵中最陈旧的组合行。交付后即可回答用户最关心的问题——「托管方案能不能日常用」。

### 增量 2：Phase 4（US2）+ Phase 5（US3）

FUND-* 三场景把 SC-003 推到可判定；SAVE-7 把 SC-004 的持久化部分推到可判定，LEGACY 部分待 T039 裁决。两者可在 US1 之后按设备空闲串行推进，US3 的 T037/T038/T039 不占设备。

### 增量 3：Phase 6（US4）+ Phase 7（US5）+ Final Phase

US4 保持未接线并留下可复核的保守门禁记录；US5 做全矩阵字段核对、SC 总表定稿、任务板收口与交接；Final Phase 修工具与做一致性检查。

### 每增量的完成定义

1. 该增量的设备任务全部有同 run 三源一致证据（或如实记未定 + 实际阻塞）；
2. 若改了生产源码：`compile_latest.py` `EXIT 0` + `run_tests.py` 出现 `OK (… tests)` 与 `EXIT 0` + 重签指纹 `a63a2254…cf77` + `adb install -r` 保留数据 + `restore_aidl.py` 已还原；
3. `results.md` 对应行与 SC 行已更新，字段齐备（12 项）；
4. `progress.md` 已记录本段目标/已完成/当前状态/下一步/阻塞项。

### 停止条件（任一成立即停下交接，不硬撑）

- T008 放行条件不成立（遗留 pending 无法诚实清算）；
- 设备离线 / 虚拟屏无法恢复 / Shizuku 无法启动；
- 经济前置无法在 G3 边界内构造（金币 15 < 学费 66 且不允许外部补币）；
- QQ 版本或机型校验不再满足 `verifyDisplay` 的硬校验；
- T039 的裁决记录未落档（R2 已由用户选 (a)，LEGACY 行按现状判未定即可继续其它故事，但 SC-004 不得判通过）。

## Coverage and Format Validation

### 任务统计

| 阶段 | 任务 | 数量 |
| --- | --- | --- |
| Phase 1: Setup | T001–T005 | 5 |
| Phase 2: Foundational | T006–T014 | 9 |
| Phase 3: US1 组合同 run 验收 | T015–T026 | 12 |
| Phase 4: US2 按需打工资金决策 | T027–T034 | 8 |
| Phase 5: US3 七配置持久化与旧语义 | T035–T041 | 7 |
| Phase 6: US4 结果归属与保守门禁 | T042–T047 | 6 |
| Phase 7: US5 证据与文档收口 | T048–T054 | 7 |
| Final Phase: Polish | T055–T058 | 4 |
| **合计** | T001–T058 | **58** |

`[P]` 并行任务 8 个：T004、T010、T012、T037、T042、T043、T048、T050。其中 T004/T012/T037/T050 是互不相交的新文件，T010/T048 是对既有单文件的不相交改动区段，T042/T043 输出到两个不同的新记录文件；所有 `[P]` 任务都不触碰设备。

### spec.md 用户故事覆盖

| spec.md 用户故事 | 覆盖任务 |
| --- | --- |
| 1–2（WS 同 run 跑通、未选护理零执行） | T015, T016, T017, T046 |
| 3–4（CS 同 run、学费不足按规则停止） | T018, T019, T045 |
| 5–6（WCS 三步、前项失败后项 PENDING） | T020, T021, T025, T012 |
| 7（FUND-LOW / FUND-SKIP） | T027, T028, T029, T030, T031 |
| 8（FUND-CARE-GAP 决策一致） | T027, T032, T033 |
| 9（SAVE-7 保存回读不自动启动） | T035, T036, T037 |
| 10（LEGACY 迁移语义） | T038, T039, T041 |
| 11（LEGACY-DIFF WORK-only 不护理） | T040 |
| 12（文档与设备一致、WC 行不陈旧） | T048, T049, T051, T058 |
| 13（记录字段齐备可复核） | T005, T049, T050 |
| 14（Unknown 不填 0 等写法禁令） | T049, T050, T051 |
| 15（不清账本/不充值/不插未选动作） | T006, T007, T008, T027, T046 |
| 16（US4 保持未接线） | T042, T043, T044, T047 |
| 17（暴露的缺陷真修不绕过） | T022, T023, T024 |
| 18（遗留 pending 诚实清算） | T006, T007, T008 |
| 19（任务板 re-get 后按证据定状态） | T052 |
| 20（display 编号显式传递） | T001, T009, T010, T016, T018, T020, T028, T030, T032 |

### 001 FR / SC 覆盖（本 feature 复用 001 的需求词表）

| 需求 | 覆盖任务 |
| --- | --- |
| FR-002（七非空组合、固定顺序、空方案不可启动） | T012, T015, T018, T020, T035, T036, T037 |
| FR-005（显式打工必完成一班；按需打工可带原因跳过） | T028, T029, T030, T031 |
| FR-006（工资确认后才记完成；工资不当余额） | T017, T021, T031 |
| FR-007（护理按阈值、已达标跳过、不隐式护理） | T019, T040, T046 |
| FR-008（学习不强制健康；未选动作不插入） | T017, T019, T021, T046 |
| FR-009 / FR-010（区分请求/开始/完成；结果归属） | T042, T043, T044, T047 |
| FR-011（工作与学习共用每日上限、保守占用、幂等） | T003, T045, T047 |
| FR-014（方案保存与旧方案兼容语义） | T035, T036, T037, T038, T039, T041 |
| FR-015 / FR-016（逐项状态展示；证据关联；前项失败不执行后项） | T012, T025, T046 |
| FR-017（单设备单会话互斥） | T004, T008, T012 |
| FR-018（三独立 + 四组合设备证据；严格打工后学习须同会话） | T015–T021, T026, T030, T031 |
| FR-019 / FR-020 / FR-021 / FR-022（工作双耗值、库存已知/未知、付款前真实钱包与后缀学费、资源轨迹） | T019, T032, T033, T045 |
| SC-001（三独立） | T040（WORK-only 复核）；既有证据保留于 `specs/001-managed-study-recovery/acceptance/us1-independent.md` |
| SC-002（四组合） | T015–T021, T026, T048 |
| SC-003（显式/条件工作全链） | T027–T031, T034 |
| SC-004（配置与迁移） | T035–T039, T041 |
| SC-005（六类异常） | T045, T047 |
| SC-006（四类恢复） | 本轮不新增；保留既有记录，T049 核对其行是否仍标未定 |
| SC-007（预算幂等） | T003, T047 |
| SC-008（最低资源边界） | T032, T033, T045, T047；W-MIN/S-MIN/C-STOCK/CASH-EXACT 的完整边界本轮**不承诺**，T049 如实保留未定 |

### 格式自检结果

按 `- [ ] TNNN [P?] [USn?] 描述 + 精确路径` / `- [X] TNNN [P?] [USn?] 描述 + 精确路径` 逐条核对（下列各项为**已完成的核对结论**，故意不使用任务复选框语法，以免被解析为任务行）：

1. 任务 ID 为 T001–T058 连续唯一，无跳号无重复；未完成项标 `[ ]`，已完成项标 `[X]`。
2. Setup（T001–T005）、Foundational（T006–T014）、Polish（T055–T058）**无**故事标签。
3. Phase 3–7 的每个任务都带与所在阶段一致的故事标签（`[US1]`…`[US5]`），阶段标题形如 `## Phase N: USn — …`。
4. 每个任务描述都含至少一个反引号包裹、带 `/` 且以 `.kt`/`.md`/`.py`/`.ps1`/`.gradle`/`.kts` 结尾的精确文件路径。
5. `[P]` 标记仅出现在写作用域互不相交的本地文件任务上；所有设备任务未标 `[P]`。
6. 本文档内所有本地链接（`spec.md`、`plan.md`、`research.md`、`data-model.md`、`quickstart.md`、`contracts/device-run.md`、`contracts/acceptance-evidence.md`）均指向本 feature 目录下已存在的文件。
7. 以上 1–6 已由脚本机械核对通过（临时脚本 `.selfcheck-tasks-002.py`，核对后删除；实际统计：tasks=58，counts={US1:12, US2:8, US3:7, US4:6, US5:7, shared:18}，parallel=8 项 T004/T010/T012/T037/T042/T043/T048/T050）。

> **工具限制（诚实记录）**：`tools/diagnostics/validate_feature_tasks.py` 把 feature 001 的形状写死（断言 ID 恰为 T001–T064、`counts == {'shared':19,'US1':14,'US2':10,'US3':8,'US4':6,'US5':7}`、FR 18 项 / SC 7 项、故事标签限 `[US1-5]`），而它从 `.specify/feature.json` 读当前 feature（现已指向 002），因此**对本文件直接运行必然失败**，该失败源于工具的硬编码假设，不是本文件的格式缺陷。本文件的格式以上述机械自检为准；T055 负责把该校验器参数化后再跑一次并记录实际输出。
