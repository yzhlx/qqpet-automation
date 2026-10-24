# Spec: 方案组合真机验收收口（Seven-Combo Acceptance Closure）

**Feature Directory**: `specs/002-plan-combination-acceptance-closure`
**Created**: 2026-10-23
**Status**: ready-for-agent（本仓库未配置 issue tracker，无法发布到外部追踪器；`ready-for-agent` 标签在此以文档状态记录）
**User confirmation (2026-10-23)**: 四个测试接缝 S1–S4 经用户确认「符合，按此执行」；发布方式经用户确认「就留在仓库里」，不发布外部追踪器、不并入 spec 001。
**Input**: 交接文件 `dsh-handoff-qqpet-2026-10-23.md` + `specs/001-managed-study-recovery/`（spec/tasks/acceptance）+ `progress.md` + 现有执行链源码

## Problem Statement

用户已经能在真机上把打工、护理、学习三个动作各自独立跑通（三项独立同 run 证据齐全），也能在「设置 → 托管方案」里勾选动作、保存方案并「保存并运行」。但七种非空组合中只有 **WC（打工→护理）** 取得了同 run COMPLETED 证据（run `b5600c4f`，且是经 `CommittedPlanFinalizer` 对已提交 attempt 的收尾，不是原生 COMPLETED）。**WS、CS、WCS 以及 FUND-\*、SAVE-7、LEGACY、边界/恢复矩阵全部仍是「未取得同 run 组合证据」或「未定」**。

同时 `acceptance/results.md` 的矩阵与设备真实快照**已经脱节**：WC 行仍写着「未取得同 run 组合证据」，而设备上 `files/plan-runs/b5600c4f….json` 已是 `state=COMPLETED`。用户无法信任这份文档来判断「托管方案到底能不能日常用」，也无法判断哪些组合还差证据、哪些是真缺陷。

US4（开课结果归属）的 `StudyCompletionGate` 严格门禁与解析器已存在并有测试，但**成绩单打印时刻语义未被实证**（至少一张陈旧成绩单打印的完整 `yyyy/MM/dd HH:mm` 与请求分钟一致），因此它**默认 UNKNOWN、未接入 live** `PetStudyFlow.waitResult`。在语义被一次成功 run 的结果 PNG 实证之前，贸然接线会制造错误的完成归属。

## Solution

在**不改写既有执行链**的前提下，按固定纪律完成剩余组合的真机验收收口，并把文档同步到设备真实状态：

1. 按 **WS → CS → WCS** 优先序，在真机上从「设置 → 托管方案」勾选对应组合、保存并运行，用 `watch_acceptance.py --run-id <new> --display-id <当前虚拟屏> --label <combo>` 有界观察终态。
2. 只接受**同 run** 判定：同 runId 的 verdict + 设备 `files/plan-runs/<runId>.json`（persisted snapshot）+ 权威 `files/pet-minute-budget-v2.bin`。不把独立 WORK 与独立 STUDY 两个 run 拼成 WS，不把跨 run 经济变化当组合轨迹。
3. 每个组合用 `capture_acceptance_state.py --run-id <id> --display-id <n> --label <combo>` 归档只读证据包到 `VirtualDisplay/.build-tmp/device-acceptance/`。
4. 随后完成 **FUND-LOW / FUND-SKIP / FUND-CARE-GAP**（按需打工决策与 dispatch 一致）、**SAVE-7**（七非空配置保存/回读/不自动启动）、**LEGACY**（v1 参数与每轮护理兼容）以及 results.md 中其余「未定」的 SC 行。
5. 验收中若暴露生产缺陷，按既有纪律修复：改源码 → 全量 JVM 回归 → 经 ASCII 联接构建 → 还原 aidl → keystore 重签并核对指纹 `a63a2254…cf77` → `adb install -r` 保留数据 → 重跑该组合。绝不靠削弱门禁「凑绿」。
6. 把 `acceptance/results.md` 矩阵、`progress.md` 与共享任务板（task-7/8/12/16）同步到真实证据；**先修正 WC 行的陈旧描述**，注明它是 finalized COMPLETED 而非原生同 run COMPLETED。
7. **US4 保持未接线**，直到一次成功 run 的结果 PNG 实证打印时刻语义；在此之前 `StudyCompletionGate` 默认 UNKNOWN 拒绝，不冒称归属通过。

## User Stories

1. 作为用户，我希望「打工→学习」（WS）组合在真机上同 run 跑通一次，以便相信托管能自动先挣钱再上课，而不必我手动分两次开。
2. 作为用户，我希望 WS 组合里未勾选的护理完全不被执行（feed/clean=0、无购买），以便确认方案不会偷偷插入我没选的动作。
3. 作为用户，我希望「护理→学习」（CS）组合同 run 跑通，且护理购买时保护后续学费，以便护理不会把上课钱花光导致学习失败。
4. 作为用户，我希望 CS 组合在学费不足时按规则停止而不是误开课，以便我的金币和分钟预算不被浪费。
5. 作为用户，我希望「打工→护理→学习」（WCS）三步组合同 run 跑通：真实工资到账 → 护理达标 → 正确课程完成，以便一次托管完成全套日常。
6. 作为用户，我希望 WCS 中任一步失败/结果不明/被停止时，后续步骤保持 PENDING 不执行，以便前项异常不会引发连锁误消费。
7. 作为用户，我希望按需打工在资金不足时真实触发一班并在同 run 闭环（FUND-LOW），在资金充足时明确记为 SKIPPED(FUNDS_SUFFICIENT) 且工作输入/分钟为 0（FUND-SKIP），以便「先打工再学习」的旧托管语义被准确保留。
8. 作为用户，我希望学费够但护理缺钱的场景（FUND-CARE-GAP）决策与实际 dispatch 一致，以便不会出现「判定要工作却没工作」或「判定跳过却去打工」的矛盾。
9. 作为用户，我希望七种非空组合都能在方案页保存、重开后逐项回读一致、且重新打开方案本身不自动启动消费（SAVE-7），以便我的配置可靠持久。
10. 作为用户，我希望旧 v1 托管方案迁移后保留课程、节数、护理目标、预算与「每轮护理 + 首轮按需工作」语义（LEGACY），以便升级不改变我已习惯的托管行为。
11. 作为用户，我希望新的 WORK-only 方案不隐式做护理（LEGACY-DIFF），以便独立打工和旧托管的护理语义清晰分离。
12. 作为用户，我希望验收文档（results.md 矩阵、progress.md）与设备真实快照一致，特别是 WC 行不再陈旧，以便我能据文档判断还差什么证据。
13. 作为用户，我希望每份验收记录都带 runId、构建身份（APK SHA-256 + 证书指纹）、所选配置、输入/开始/完成计数、资源前后值、预算变化与停止原因，以便任何结论都可独立复核。
14. 作为用户，我希望 Unknown 的资源读数永不被填 0、预测工资永不当余额、已派发鼓励永不当业务成功，以便验收不会虚报通过。
15. 作为用户，我希望验收全程不清账本、不充值、不额外领币、不插未选动作凑通过，以便游戏经济和我的真实进度不被污染。
16. 作为用户，我希望 US4 结果归属在打印时刻语义被实证前保持未接线（默认 UNKNOWN 拒绝），以便不会把陈旧成绩单误归属为新完成。
17. 作为用户，我希望验收中暴露的生产缺陷（如 healthJson 序列化用当前时间导致 HEALTH_STALE）被真正修复并回归，而不是绕过。
18. 作为用户，我希望恢复桥（CarePendingReconciliation）对遗留 7 条 0 分钟 UNCONFIRMED_RETAINED 护理 attempt 只在严格同局时间、最后输入、付款前后模板证据与当前房间/真钱包基线都成立后才原子记 RECONCILED_STOPPED，以便遗留 pending 被诚实清算而不是被抹掉。
19. 作为用户，我希望共享任务板上 task-7（FileRunStore）、task-8（Coordinator 并发）、task-12（Plan UI / PlanV2Store + SAVE-7）、task-16（CommittedPlanFinalization）在标记完成前先重新 get 并对照代码/测试/设备证据确认，以便任务状态不超前于事实。
20. 作为用户，我希望所有游戏侧操作都落在当前自有虚拟屏（上次为 display 6，重建后会变号）并显式传 `--display-id`，以便不会误操作物理屏或过期 display。

## Implementation Decisions

- **不重建执行链**。统一 PLAN → `PlanRunner` + `FileRunStore` + 预算意图（`PlanDeviceExecution`、`PlanDevicePreflight`、`SelectedPlanConfiguration`、`PlanBudgetExecutionContext`、`PlanRunPendingGate`）已在树中；本轮是验收 + 缺陷修复 + 文档收口，不是重新设计。
- **组合验收优先序**：WS → CS → WCS（handoff 指定），随后 FUND-LOW / FUND-SKIP / FUND-CARE-GAP，再 SAVE-7、LEGACY / LEGACY-DIFF，最后 results.md 其余「未定」SC 行（W-MIN / S-MIN / C-STOCK / CASH-EXACT / TRAJECTORY / CNF / START-UNKNOWN / RESULT-OWNER / PAYROLL-UNKNOWN / RESOURCE-UNKNOWN / BUDGET / CONCURRENT / RETURN / REENTER / PAGE-UNKNOWN / RECOVERY-LIMIT / STOP-RESTART）。
- **同 run 判定纪律**：只接受同 runId 的 verdict + 设备 `files/plan-runs/<runId>.json` + 权威 `files/pet-minute-budget-v2.bin`；拒绝任何跨 run 拼接。WC 的 COMPLETED 来自 `CommittedPlanFinalizer`（对已提交 attempt 的收尾，audit 先于 commit，不重放输入），文档必须如实标注为 finalized，而非原生同 run COMPLETED。
- **healthJson 序列化修复已落地**：`PetFlowContext.healthJson` 不再调用 `mood.at(System.currentTimeMillis(), …)`，直接序列化已存观测字段；这是此前 CARE 步骤写 `plan-care.json` 触发 HEALTH_STALE → 快照 UNCONFIRMED 的根因修复，构建后约 729 JVM 测试绿，保留数据安装 SHA-256 `45f0298e…b1ec`。
- **US4 保持未接线**：`StudyCompletionGate` 严格门禁 + ROI 解析器 + 测试存在，时间语义默认 UNKNOWN，**不接 live** `PetStudyFlow.waitResult`；接线前置条件是一次成功 run 的结果 PNG 实证打印时刻语义（实证前任何「最近日期 + 首时刻 = 完成时刻」的猜测都已被删除）。
- **资源规则**（已实现，验收时按此判定，不改语义）：WORK 按本次页面实际双耗值 + 固定时长核对，等于即可开、任一不足或必需值 Unknown/过期即停，不受护理阈值影响；STUDY 所有 profile 均不强制体力/清洁或健康新鲜度，只按课程、学费、分钟预算、页面身份与执行结果判定；CARE 先消耗已确认库存、仅买缺口，付款前独立确认真实钱包并保护全部未付后缀学费，库存 Unknown 不当 0。
- **预算规则**：开始前保守预留，结果不明保留占用不自动退款；attempt 幂等，完成不重复扣；跨日更新原日记录、新 attempt 取当前日；`pet-minute-budget-v2.bin` 为唯一权威，旧 `budget.xml` 的 100 不作为新当日数。
- **pending 门禁**：遗留 7 条 0 分钟 UNCONFIRMED_RETAINED 护理 attempt（其中 2 条 `actionPending=true`）会正确阻止新 PLAN admit；不削弱门禁凑绿。恢复桥按严格证据原子记 RECONCILED_STOPPED，不清账本、不改分钟、不重放输入。
- **环境与操作约定**：设备 Huawei FOA-AL00 Android 12，serial `DKS9K23420020242`；应用 `com.ynk.virtualdisplay`；游戏宿主 QQ `com.tencent.mobileqq`；游戏在自有虚拟屏（上次 display 6，720×1600@320，重建后会变号，**永不假设 4**），游戏侧 tap/screencap 需 `-d <当前 display>`；Shizuku 13.6.0，启动命令取自应用 UI（安装目录下 `lib/arm64/libshizuku.so`），APK 安装或进程死亡后可能需重跑 starter 再「重试检测」。
- **UI 操作坑**：底部「设置」tab 的 Compose 文本常为零尺寸，应点可点击父容器（1084×2412 上约 `[747,2116][921,2186]`）或用已识别父节点，再滚动到「托管方案」；`uiautomator` dump 是物理屏 UI，游戏在虚拟屏。
- **构建/安装纪律**：经 ASCII 联接 `C:\Users\lu158\dsh-qqpet` 构建（自带 JDK 21、Gradle 9.3.1，`GRADLE_USER_HOME=tools/android-build/gradle-cache`，`JAVA_TOOL_OPTIONS` tmpdir 指 `.build-tmp`，`REAL_AIDL` 指 `aidl.exe.orig`，`kotlin.compiler.execution.strategy=in-process`）；构建后还原 aidl 原件；用 `qqpet-debug.keystore` 重签并核对指纹 `a63a2254…cf77`；`adb install -r` 保留数据覆盖安装；**禁用 PowerShell 正则批量改含中文 Kotlin 源**（用 edit/read/write 或 Python 显式 UTF-8）。
- **经济前置**：当前金币约 15、学费约 66，WS/WCS 需在 run 内真实打工筹资，不靠借款或额外领币；不自行执行范围外的领金币/充值。
- **任务板收口**：task-7/8/12/16 在标记完成前重新 `team_task_get` 并对照代码/测试/设备证据；task-16 的 WC 设备快照已 COMPLETED，确认代码/测试冻结后再标完成。

## Testing Decisions

- **好测试只验外部行为，不验实现细节**：断言可观察输出（同 run verdict、persisted snapshot 状态、attempt 计数、预算变化、资源前后值、停止原因、证据路径），不断言私有调用顺序或内部字段名。
- **四个测试接缝，全部复用既有接缝，不新增**（越少越好）：
  - **S1 设备端到端（最高接缝，本轮主接缝）**：从「设置 → 托管方案」勾选 → 保存并运行 → `watch_acceptance.py`（有界只读观察终态）→ `capture_acceptance_state.py`（只读归档 bundle）。这是七组合矩阵、FUND-\*、SAVE-7、LEGACY 的唯一判定来源。
  - **S2 编排规则（`PlanRunner` + fakes）**：`PlanRunnerTest` / `PlanRunnerReuseTest` / `PlanDeviceExecutionIntegrationTest`。验证固定 WORK→CARE→STUDY 顺序、stop-on-failure、计数来自 persisted attempts、终态只回原记录、非终态转 UNCONFIRMED 要求观察、既有 attempt 不再授权。七组合的编排规则在此接缝补测（对应 tasks.md T029 `PlanRunnerCompositionTest`）。
  - **S3 真实文件持久化/收尾**：`FileRunStoreTest`、`PlanRunJsonCodec`、`CommittedPlanFinalizationTest`。验证原子持久化、codec 往返、收尾资格与基线门禁、audit 先于 commit、不重放输入。SAVE-7 的保存/回读往返在此接缝补测（对应 T030 `PlanStoreDeviceTest`，用隔离测试文件，不动生产 `plan-v2.json`）。
  - **S4 纯规则**：`PlanResourcesTest`（45 项）、`PlanBudgetPolicyTest`（4 项）、`PlanV2Test`、`StudyCompletionGateTest`（18 项，US4 未接线）。资源决策、预算后缀、七非空子集校验在此接缝判定。
- **prior art（既有测试即先例）**：`PlanRunnerTest`、`PlanRunnerReuseTest`、`PlanDeviceExecutionIntegrationTest`、`FileRunStoreTest`、`CommittedPlanFinalizationTest`、`PlanResourcesTest`、`PlanBudgetPolicyTest`、`PlanBudgetLedgerTest`、`PlanRunCoordinatorTest`、`PlanV2StoreTest`、`PlanV2Test`、`StudyCompletionGateTest`、`WorkEvidencePolicyTest`、`PayrollNavigationPolicyTest`、`CarePaymentPolicyTest`、`SceneEmptyStockPolicyTest` / `SceneOwnedStockPolicyTest` / `SceneCounterDetectorTest`、`PetBackNavigationTest`、`SessionRecoveryTest`。
- **回归门槛**：任何生产改动后必须全量 JVM 回归绿（当前基线约 729 项）。权威入口是 `VirtualDisplay/tools/jvm-tests/run_tests.py`（直跑 JUnitCore、支持类名 glob 与 `--log`、以 `raise SystemExit(r.returncode)` 传播真实退出码），工作目录 `VirtualDisplay/app`，主代码取 `compile_app_classes_jar` 而非 runtime jar；`gradle :app:testDebugUnitTest` 在中文路径下不可用。外层 shell 成功不能替代 `OK (… tests)` + `EXIT 0`。
- **设备验收工具**：`watch_acceptance.py --run-id <new> --display-id <当前> --label <combo>`（有界观察，代替固定长 sleep）；`capture_acceptance_state.py --run-id <id> --display-id <n> --label <combo>`（只读归档 events/checkpoint/verdict/run-record/权威预算/截图/persisted-plan-run）；`restore_pet_environment.py`（守卫式恢复，自动化服务活跃时拒绝）。三者均不发输入、不改设备设置。
- **判定纪律**：同 run + persisted-plan-run + 权威 budget bin 三者齐备才判通过；Unknown 不填 0；工资不当余额；已派发鼓励不当业务成功；服务 SESSION_STATE STOPPED/BATCH_COMPLETE 不等同业务失败；设备夹具验证的异常必须明确标注，不冒充真实游戏正向。

## Out of Scope

- **重新实现执行链**（PlanRunner / FileRunStore / 预算意图 / 统一 runner）——已在树中，handoff 明令不得从零重建。
- **US4 live 接线**——打印时刻语义实证前不接 `PetStudyFlow.waitResult`。
- **清账本 / 充值 / 额外领币 / 插未选动作凑通过 / 削弱门禁凑绿**。
- **任意拖拽排序、重复步骤、自动增班、日计划优化器接线**（spec 001 已声明本轮不提供）。
- **git commit / push / 卸载清数据 / 外传原始设备资料或签名钥匙**（未获用户明确授权前不做）。
- **OCR 再迭代**——课程名识别已定稿 A 预处理，`世界地理课` 等误读经 `CourseTiming.confirmDetail` 安全失败，不再迭代。
- **Android 插件 / 虚拟屏插件改造**——插件为开发辅助，display-id 显式传递即可。
- **把历史单项 PASS 或跨 run 拼接当组合通过**。

## Further Notes

- **设备/环境快查**：serial `DKS9K23420020242`；应用 `com.ynk.virtualdisplay`（versionCode 60 / 1.0.60）；游戏宿主 QQ `com.tencent.mobileqq`；游戏虚拟屏上次为 display 6（720×1600@320），物理屏 display 0；Shizuku 13.6.0，启动命令取自应用 UI 的 `lib/arm64/libshizuku.so` 路径；`restore_pet_environment.py` 守卫式恢复。
- **WC 审计 bundle**：`VirtualDisplay/.build-tmp/device-acceptance/continuation-20261022-211328-845483-plan-work-care-recovered` 同时保留 legacy run-record（FAILED/HEALTH_STALE）与 persisted-plan-run（收尾后 COMPLETED），供审计 finalized 与原生 COMPLETED 的区别。
- **遗留 CARE pending（2026-10-22）**：7 条 0 分钟 UNCONFIRMED_RETAINED；恢复桥组件 `CarePendingReconciliation`、`LegacyPaySettlementReader`、`CarePendingBaselineObserver`、`CareBaselinePagePolicy`；房间基线误拒（学习探针 ~0.81）已通过分离 modal 0.80 / learning 0.95 + room-exit proof 修复。不清账本。
- **任务板**：task-1..6、9..11、13..15 已完成；task-7（FileRunStore，树中大概率已完成）、task-8（Coordinator 并发测试）、task-12（Plan UI / PlanV2Store，UI 已在，SAVE-7 设备矩阵未完）、task-16（CommittedPlanFinalization，WC 设备快照已 COMPLETED，确认代码/测试冻结后标完成）仍 in_progress，写前先 re-get。
- **经济现状**：金币约 15、学费约 66；WS/WCS 需 run 内打工筹资。
- **已知坑（已付学费）**：PowerShell `-replace`/`Set-Content` 损坏中文 Kotlin（用 Python/edit）；display id 重建后变号（4→5→6，永不假设 4）；设置 tab Compose 零尺寸文本（点父容器）；`uiautomator` dump 是物理屏（游戏在 VD，用 `-d N`）；pending/UNCONFIRMED_RETAINED 正确阻止新 run（不削弱）；PC 重启 ≠ 手机重启（Shizuku 可能在 APK 安装/进程死亡后失效，重跑 starter 再「重试检测」）。
- **发布说明**：本仓库未配置 issue tracker 与 triage 标签词表（`.specify/feature.json` 仅指向 feature 目录，无 tracker；GitHub `yzhlx/qqpet-automation` 是快照备份仓库而非 issue tracker）。按 /to-spec 流程，发布到外部追踪器并打 `ready-for-agent` 标签这一步**被阻塞**；如需发布，请先运行 `/setup-matt-pocock-skills` 配置追踪器与标签词表。在此之前本 spec 以仓库内文档形式存在，状态标记为 ready-for-agent。
- **与 spec 001 的关系**：本 spec 是 `specs/001-managed-study-recovery/` 的验收收口延续，复用其 FR/SC 词表（尤其 SC-002 四组合、SC-003 按需工作、SC-004 七配置、SC-005 异常、SC-006 恢复、SC-007 预算幂等、SC-008 边界），不重开已完成的资源规则设计。
