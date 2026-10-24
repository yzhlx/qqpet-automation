# Tasks: 打工、护理、学习独立运行与方案闭环

**Feature**: `001-managed-study-recovery`  
**Branch**: `feature/qqpet-mvp-session`  
**Date**: 2026-10-21  
**Input**: [spec.md](spec.md)、[plan.md](plan.md)、[research.md](research.md)、[data-model.md](data-model.md)、[执行契约](contracts/execution.md)、[quickstart.md](quickstart.md)

## Format and Execution Rules

所有路径以工作区根 `C:/Users/lu158/Desktop/dsh插件/养宠自动化` 为基准；新增文件标明“新增”，无需预先存在。T编号连续，但执行受下文依赖约束；不得把后面的安全任务未完成作为跳过现有门禁的理由。

- 任务均为待执行；此前Android插件启用和工具调用已通过，仅需保存基线，不重复修改配置。
- `[P]`表示在列明前置完成后，能与无依赖且写范围不重叠的任务并行；同一设备验收一律串行。
- 故事中的测试依据设计要求的回归与设备验收安排；只为真实执行、预算、迁移和副作用规则建测试，不为文案/模板改动堆测试。
- `SELECTED_ACTIONS`为新独立/组合规则；`LEGACY_MANAGED`为旧托管兼容。禁止用旧FULL实现新的“仅学习”，也不把旧工作前护理泄漏给WORK-only。
- 真实消费只能在已识别页面、单设备互斥和有效授权下执行。未确认输入不得重放；保留数据覆盖安装，不卸载/清数据/推送/发布/外传原始画面或钥匙。
- 每个实现增量完成相应新编译/必要回归后才可设备验证；不得把历史408项JVM或旧单节PASS视作本次通过。停止后先识别现场，不盲目重开。

## Phase 1: Setup

目标：固定工具与失败证据基线，建立可复用的当前源码验证方式；不重新开展插件改造。

- [ ] T001 将已修复Android插件、真实android_devices/app_info/ui_tree结果及显示域不一致限制整理到 `specs/001-managed-study-recovery/acceptance/baseline.md`（新增），引用 `tools/diagnostics/check_android_enabled.py` 和现有研究结论；记录当前构建身份，不重新禁用/启用插件或点击设备。
- [ ] T002 分析 `VirtualDisplay/.build-tmp/device-acceptance/s21-reenter/round-1-course-before-start.png`、同目录run-record/verdict及对应pet-events，创建 `specs/001-managed-study-recovery/acceptance/study-start-diagnosis.md`（新增）：对齐请求/观察时间与课程，建立能在真实确认路径上判失败的反馈；缺中间帧时设计有界观测并记录采集位置，不能预设改坐标或延长120秒可解决。
- [ ] T003 整理当前源码构建/JVM/签名安装的可重复验证命令至 `specs/001-managed-study-recovery/acceptance/build-guide.md`（新增），以 `VirtualDisplay/tools/jvm-tests/README.md`、当前cp.lst及compile_app_classes_jar为依据，明确aidl finally还原、证书核对、同号覆盖保留数据及JUnit实际退出码判定；不在文档中写钥匙口令。
- [ ] T004 在 `specs/001-managed-study-recovery/acceptance/results.md`（新增）建立场景W/C/S/WC/WS/CS/WCS、按需工作、兼容/异常/恢复的待验表，字段包含构建身份、方案快照、runId、step/attempt、初始资源、预算前后、本地证据目录、结果与限制，全部初始为未定。

## Phase 2: Foundational

目标：建立所有用户故事共用的类型、输入意图、互斥、记录和预算基础。完成后才能接生产新入口。以下测试属于共享不变量，不替代故事设备验收。

2026-10-22只读复核更正：Foundation当前仅有interface/pure模型与fake测试，尚未生产接线。RunStore无生产原子持久化适配；预算账本未接DailyBudgetStore/上限/实际扣时；协调器未实现进程级、按设备同步互斥和重启保护。T010/T012撤勾，不能以局部单测宣称这些完整任务完成。

- [X] T005 在 `VirtualDisplay/app/src/main/java/com/ynk/virtualdisplay/automation/PlanV2.kt`（新增）定义PlanV2/PlanStep及验证器，落实原约束“integer=2”“stable string”“nonempty bounded string”“integer，1..480”“1..3 PlanStep”“唯一类型，按WORK/CARE/STUDY排序”；name固定1..80字符并同步 `specs/001-managed-study-recovery/data-model.md` 此上限；执行profile仅SELECTED_ACTIONS/LEGACY_MANAGED，legacySource为“optional original v1 snapshot”，未知版本/空计划拒绝，不加载未选参数依赖。
- [X] T006 在 `VirtualDisplay/app/src/main/java/com/ynk/virtualdisplay/automation/PlanV2.kt` 补动作约束：WORK“VERIFIED生产白名单”“ALWAYS / IF_FUNDS_SHORT”“shifts固定1”，独立WORK固定ALWAYS且条件工作必须有后续STUDY；CARE阈值“60..100”；STUDY“rounds=1..10”；SELECTED_ACTIONS的WORK仅REQUIRE_READY、CARE一次，保留legacy兼容边界；在 `VirtualDisplay/app/src/test/java/com/ynk/virtualdisplay/PlanV2Test.kt`（新增）验证各边界及未选配置缺失合法。
- [X] T007 在 `VirtualDisplay/app/src/main/java/com/ynk/virtualdisplay/automation/PlanExecutionModels.kt`（新增）定义RunRequest/不可变RunSnapshot/StepExecution/ActivityAttempt，逐项落实数据模型中的origin枚举、整体与步骤状态枚举、`attemptId=(runId,stepId,iteration)`、STUDY iteration1..rounds/WORK固定1；SKIPPED仅FUNDS_SUFFICIENT或ALREADY_SATISFIED，未选项不伪造执行项，work/care/study计数分离，Unknown不填0。
- [ ] T008 在 `VirtualDisplay/app/src/main/java/com/ynk/virtualdisplay/automation/PlanExecutionPorts.kt`（新增）定义StepExecutor、BudgetPort、EvidencePort、PreflightPort及只读资源观察接口，并新增 `VirtualDisplay/app/src/main/java/com/ynk/virtualdisplay/automation/PlanResources.kt` 的InventoryObservation(Known/Unknown)、ResourceState、实际WorkCostSnapshot、护理购买缺口/钱包与后缀学费、ResourceReadiness和逐步资源轨迹；必要字段携带sourcePage、observedAt、pageIdentity、evidenceId和attempt归属，未知库存不填0、工资预测不当余额、学习健康仅可选观察；实现可注入fake的 `VirtualDisplay/app/src/test/java/com/ynk/virtualdisplay/PlanExecutionFakes.kt`（新增），能捕获所有输入、消费、选课和未选executor调用。
- [ ] T009 在 `VirtualDisplay/app/src/main/java/com/ynk/virtualdisplay/automation/PlanRunStore.kt`（新增）实现关键输入边界及终态的原子持久化，记录inputPending、派发/确认/完成时间；启动重启后pending会话只恢复观察/明确停止，不自动发消费；旧mode/completed/requested字段可读但不伪造steps完成。
- [ ] T010 在 `VirtualDisplay/app/src/main/java/com/ynk/virtualdisplay/automation/PlanBudgetLedger.kt`（新增）及 `VirtualDisplay/app/src/test/java/com/ynk/virtualdisplay/PlanBudgetLedgerTest.kt`（新增）先锁定reserve-before-input、按钮未找到不占用、未知发送保留、完成不二次加、同attempt幂等、跨午夜更新原日记录/新attempt取当前日的行为；status使用RESERVED_BEFORE_INPUT/START_CONFIRMED/COMPLETED/UNCONFIRMED_RETAINED。
- [ ] T011 在 `VirtualDisplay/app/src/main/java/com/ynk/virtualdisplay/automation/DailyBudgetStore.kt` 和 `VirtualDisplay/app/src/main/java/com/ynk/virtualdisplay/automation/PlanBudgetLedger.kt` 接入持久账本、共用锁和事务式更新，迁移保留原日键与legacyUsedMinutes，不清零、不把旧汇总再累加；CARE耗材预算仍独立，不扣游戏分钟；持久写失败必须阻止输入。
- [ ] T012 在 `VirtualDisplay/app/src/main/java/com/ynk/virtualdisplay/automation/PlanRunCoordinator.kt`（新增）实现单设备互斥与requestId去重，所有非终态阻止第二套输入控制、HEALTH_CHECK同受锁约束；在 `VirtualDisplay/app/src/test/java/com/ynk/virtualdisplay/PlanRunCoordinatorTest.kt`（新增）验证独立/组合/只读健康并发启动、STOP、重复投递和pending重启不重放。
- [ ] T013 在 `VirtualDisplay/app/src/main/java/com/ynk/virtualdisplay/automation/PlanPreflight.kt`（新增）实现消费前所选依赖预检：含STUDY先校验档案完整与当前课程可选/名称时长，不存在COURSE_NOT_FOUND且零消费；无STUDY不调用课程读取；资源按动作必要性判断，未选依赖不可阻断；在 `VirtualDisplay/app/src/test/java/com/ynk/virtualdisplay/PlanPreflightTest.kt`（新增）用fake验证该生产门禁。
- [ ] T014 在 `VirtualDisplay/app/src/main/java/com/ynk/virtualdisplay/automation/PlanRunner.kt`（新增）实现单步骤与公共生命周期骨架，统一调用validator/coordinator/preflight/预算/记录端口；输入前持久意图、结果不明不重发、失败不增加成功计数；以 `VirtualDisplay/app/src/test/java/com/ynk/virtualdisplay/PlanRunnerSingleStepTest.kt`（新增）验证三类型单元素计划与执行副作用唯一性，暂不接真实UI。

## Phase 3: US1 — 三个动作独立运行（P1，首个交付里程碑）

目标：生产用户入口可分别运行W/C/S，不查未选依赖、不隐式护理/打工。独立验证：W成功一班并确认工资，C达阈值或合法跳过，S完成指定课程1/1；未选动作调用/消费为0。开始条件不足时明确Blocked。

测试先于对应实现；T015/T016/T017在基础接口固定后可并行。T018/T019/T020写不同生产范围，可在各自测试就绪后并行。

- [ ] T015 [P] [US1] 在 `VirtualDisplay/app/src/test/java/com/ynk/virtualdisplay/CareExecutorTest.kt`（新增）验证仅CARE无课程/岗位仍可运行、零金币有足库存可成功、部分库存只买缺口、Unknown库存不当零、ALREADY_SATISFIED零消费；付款前可用金币足够且保留后缀学费，额度不当余额，任何必要付款/库存未知不重复消费、不自动打工，使用T008的副作用fake。
- [ ] T016 [P] [US1] 在 `VirtualDisplay/app/src/test/java/com/ynk/virtualdisplay/StudyExecutorTest.kt`（新增）验证仅STUDY无工作/护理调用、学费和时间满足时低体力/低清洁/Unknown或Stale健康仍可学习，课后健康读失败不抹已证实完成；课程详情不一致停止、合法rounds2/4保持原值、后续节只按实际学费/课程/时间核对；可以记录真实健康消耗但不以其为学习门槛。
- [ ] T017 [P] [US1] 在 `VirtualDisplay/app/src/test/java/com/ynk/virtualdisplay/WorkEvidencePolicyTest.kt`（新增）及 `VirtualDisplay/app/src/test/java/com/ynk/virtualdisplay/DeviceWorkResourceReaderTest.kt` 验证独立零金币岗位Unknown余额基线+同场正award可完成、无award/正差不能完成、已知非正差不能被正award覆盖、无课程注入依赖，Unknown不写0；实际岗位CostSnapshot为体力5/清洁2/10分钟时5和2恰好可开、4或1/任一必需健康或cost Unknown不能开，启动前耗值变化必须重新核对；钱包Unknown豁免不得扩大到双健康Unknown。
- [ ] T018 [P] [US1] 在 `VirtualDisplay/app/src/main/java/com/ynk/virtualdisplay/automation/CareExecutor.kt`（新增）适配 `VirtualDisplay/app/src/main/java/com/ynk/virtualdisplay/automation/PetCareFlow.kt`，复用目标恢复、耗材与支付确认，仅依赖CARE参数；先用可确认库存、仅买缺口，付款前通过独立余额观察核对实际可付金币、商品/数量/金额与保留后缀学费，不把ExchangeBudget上限当钱包；达标即停/合法跳过，不扣游戏分钟、不自动筹资；相应门禁接 `VirtualDisplay/app/src/main/java/com/ynk/virtualdisplay/automation/PetExchangeFlow.kt`，不明付款不得重发。
- [ ] T019 [P] [US1] 在 `VirtualDisplay/app/src/main/java/com/ynk/virtualdisplay/automation/StudyExecutor.kt`（新增）适配现有CourseTiming/PetStudyFlow，剥离所有profile中学习的强制健康阈值和健康新鲜度门禁，禁用强制护理/工作回调；核对实际学费、余额、课名、时长与时间预算，rounds严格1..10且每节独立attempt；`PetStudyHealthPolicy`/`PetFlowContext`/恢复在STUDY阶段不得因非必要健康Unknown阻断，课后健康失败不抹掉已证实完成；仅保留可选真实健康消耗观察，不自动护理。
- [ ] T020 [P] [US1] 在 `VirtualDisplay/app/src/main/java/com/ynk/virtualdisplay/automation/WorkEvidencePolicy.kt`（新增）、`VirtualDisplay/app/src/main/java/com/ynk/virtualdisplay/automation/PetWorkFlow.kt`、`VirtualDisplay/app/src/main/java/com/ynk/virtualdisplay/automation/DeviceWorkResourceReader.kt`、`VirtualDisplay/app/src/main/java/com/ynk/virtualdisplay/automation/WorkCatalog.kt` 建立显式VERIFIED零金币费用政策，去掉此类独立工作开工前硬性Known余额要求；保留一次授权、岗位/实测分钟匹配和严格到账门禁，任何金币支出前置不得放宽。
- [ ] T021 [US1] 在 `VirtualDisplay/app/src/main/java/com/ynk/virtualdisplay/automation/WorkExecutor.kt`（新增）提取每次新建PetWorkFlow的一班执行路径，读取并核对岗位所需体力/清洁、页面身份/时长/按钮及工作分钟；不访问课程、不调用cleanTo、不购买香皂；低状态或不可读返回Blocked，工资确认后关闭工资页并确认返回。
- [ ] T022 [US1] 在 `VirtualDisplay/app/src/main/java/com/ynk/virtualdisplay/ui/data/SessionStartRequest.kt`、`VirtualDisplay/app/src/main/java/com/ynk/virtualdisplay/automation/SessionMode.kt`、`VirtualDisplay/app/src/main/java/com/ynk/virtualdisplay/automation/PetAutomationService.kt` 接统一request/planSnapshot工厂，三个独立请求生成单元素SELECTED_ACTIONS并注入对应executor，修正合法1..10被sanitize成1的问题；旧FULL暂保留原兼容分支，服务保持非exported且只经UI启动。
- [ ] T023 [US1] 在 `VirtualDisplay/app/src/main/java/com/ynk/virtualdisplay/ui/screens/PetHomeScreen.kt` 增加明确的打工/护理/学习独立按钮及所需参数摘要，只提交单元素计划；保留健康检查与旧托管入口，运行中禁止第二启动，未选课程/岗位不影响无关入口；UI变更前加载适用UI技能。
- [ ] T024 [US1] 依据T002的真实反馈最小修复 `VirtualDisplay/app/src/main/java/com/ynk/virtualdisplay/automation/PetStudyFlow.kt` 及已证实有关的页面识别代码，先让正常选中课程→一次开课→确认学习→真实完成路径通过；在 `VirtualDisplay/app/src/test/java/com/ynk/virtualdisplay/PetStudyStartRegressionTest.kt`（新增）锁定实锤根因，不能仅改超时/坐标或用当前结果页替旧会话补判PASS；直接结果归属扩展留US4。
- [ ] T025 [US1] 在 `VirtualDisplay/app/src/main/java/com/ynk/virtualdisplay/automation/PetManagedRuntime.kt`、`VirtualDisplay/app/src/main/java/com/ynk/virtualdisplay/ui/screens/PetHomeScreen.kt` 展示独立动作类型、工作班次/学习节数/护理结果及预算占用，WORK或CARE的studyCompleted=0不得显示失败；终态复读不再次执行。
- [ ] T026 [US1] 按T003执行当前源码编译、针对性及全量JVM回归、签名指纹核对与保留数据覆盖安装，写入 `specs/001-managed-study-recovery/acceptance/us1-build.md`（新增）并链接本地编译/JUnit日志；还原aidl，清理临时签名副本，外层shell成功不能替代实际JUnit EXIT0。
- [ ] T027 [US1] 从已识别生产UI分别验证独立WORK和CARE，含无课程档案条件、真实工资/新健康与未选动作0次，将runId、构建、预算与证据写入 `specs/001-managed-study-recovery/acceptance/us1-independent.md`（新增）；原始资料留 `VirtualDisplay/.build-tmp/device-acceptance/independent/`，单设备串行，不能清数据来制造空档案。
- [ ] T028 [US1] 继续在同一构建验证独立STUDY完成田径课1/1、学费/时间足够时低或未知健康不阻断且无工作/护理消费，另验证W体力或清洁不足明确停止、CARE零现金有库存成功及无库存缺资金不自动工作，更新 `specs/001-managed-study-recovery/acceptance/us1-independent.md` 和 `specs/001-managed-study-recovery/acceptance/results.md`；三项实际证据齐全才判US1完成。

## Phase 4: US2 — 选择动作组成方案（P1）

目标：保存并运行七种非空组合，执行顺序明确、逐项结果真实。独立验证：七配置重启回读一致，四个多动作组合分别成功，前项失败/不明/停止后不执行后项。US1完成后复用其三个executor，不增加另一套输入通道。

- [ ] T029 [P] [US2] 在 `VirtualDisplay/app/src/test/java/com/ynk/virtualdisplay/PlanRunnerCompositionTest.kt`（新增）验证七非空子集按WORK→CARE→STUDY执行、无未选executor调用、CARE只一次、前项Blocked/Failed/Unconfirmed/Cancelled使后项PENDING、合法SKIPPED才可继续，只有全部选中项完成/合法跳过才整体COMPLETED。
- [ ] T030 [P] [US2] 在 `VirtualDisplay/app/src/androidTest/java/com/ynk/virtualdisplay/PlanStoreDeviceTest.kt`（新增）设计隔离测试文件上的七配置JSON往返、AtomicFile故障、未知/损坏版本保留拒绝、重开不自动启动及运行快照不随编辑变化验证；不使用org.json JVM桩声称持久化通过、不修改真实生产文件制造故障。
- [ ] T031 [US2] 在 `VirtualDisplay/app/src/main/java/com/ynk/virtualdisplay/automation/PlanV2Store.kt`（新增）实现版本化AtomicFile保存与只读加载，遵循T005/T006字段边界，未选参数不必填；损坏/未知版本保留原文并拒绝执行，不回退默认消费方案，旧格式暂交US3适配器而非静默转新语义。
- [ ] T032 [US2] 在 `VirtualDisplay/app/src/main/java/com/ynk/virtualdisplay/automation/PlanRunner.kt` 完成多步骤串行调度及执行前后页面/资源观察边界，使用已具备的课程预检和预算端口，不重复创建并发runner；STOP/失败/结果不明不启动后续项，完成/跳过均保留原因与证据。
- [ ] T033 [US2] 在 `VirtualDisplay/app/src/main/java/com/ynk/virtualdisplay/ui/screens/PetPlanSheetScreen.kt` 实现三动作勾选、固定顺序预览、只对选中项校验参数、至少一项、name1..80/预算1..480/阈值60..100/学习1..10输入，WORK先提供显式ALWAYS；显示工作需预先满足岗位状态以及新CARE仅一次。
- [ ] T034 [US2] 在 `VirtualDisplay/app/src/main/java/com/ynk/virtualdisplay/ui/screens/PetHomeScreen.kt`、`VirtualDisplay/app/src/main/java/com/ynk/virtualdisplay/automation/PetAutomationService.kt` 连接保存方案快照到已有统一工厂/runner；编辑下次方案不改变正在运行快照，运行方案与三个独立入口共用设备锁。
- [ ] T035 [US2] 在 `VirtualDisplay/app/src/main/java/com/ynk/virtualdisplay/automation/PlanRunStore.kt`、`VirtualDisplay/app/src/main/java/com/ynk/virtualdisplay/automation/PetManagedRuntime.kt`、`VirtualDisplay/app/src/main/java/com/ynk/virtualdisplay/ui/screens/PetHomeScreen.kt` 输出并展示RunRecordV2的steps[]、index/type/state/reason、工资与健康/课程证据关联和三类完成计数；保留旧记录读取，不能为v1补造步骤证据。
- [ ] T036 [US2] 新编译/回归并覆盖安装本增量，在设备隔离文件执行T030，再从真实方案UI保存/重开七种非空配置，记录 `specs/001-managed-study-recovery/acceptance/us2-persistence.md`（新增）；确认空方案不可启动、未选参数缺失不阻断、重开不消费。
- [ ] T037 [US2] 串行验收W+C、W+S、C+S三种双动作方案（WORK为ALWAYS），各自核对选中顺序、未选动作0次、预算及完成证据，写入 `specs/001-managed-study-recovery/acceptance/us2-combinations.md`（新增）；W+S初始状态必须足够，失败不能隐式插护理凑成功。
- [ ] T038 [US2] 验收显式ALWAYS的W+C+S，必须同runId真实工作完成→正工资→护理达标→正确课程完成，并补前项失败/停止后后项未执行证据，更新 `specs/001-managed-study-recovery/acceptance/us2-combinations.md` 与 `specs/001-managed-study-recovery/acceptance/results.md`；不能拼接独立会话替代。

## Phase 5: US3 — 按需打工兼容原有托管（P1）

目标：按需与显式工作分离，统一资金决策，保留旧托管参数和护理语义。独立验证：低资金真实工作闭环、充足时合法跳过、显式工作不跳过、旧方案兼容；剩余30分钟/学习30分钟/条件工作10分钟且资金足够时应允许学习。

- [ ] T039 [P] [US3] 在 `VirtualDisplay/app/src/test/java/com/ynk/virtualdisplay/FundingDecisionTest.kt`（新增）验证requiredTotal=所选学习剩余实际学费+按当前/预计工作后健康与已确认库存计算的护理购买缺口，已有库存不重复计现金、无CARE购买费0、Unknown库存不当0；工作后5/2健康下降导致多需道具、100/100零库存工资68却不足7护理+66学费时方案不能误判可闭环；学费够但真实护理购买钱不足必须need，必要余额Unknown停止；ALWAYS不受资金充足跳过，IF_FUNDS_SHORT无STUDY拒绝，决策与dispatch使用同snapshotId。
- [ ] T040 [P] [US3] 在 `VirtualDisplay/app/src/test/java/com/ynk/virtualdisplay/LegacyManagedAdapterTest.kt`（新增）验证v1无版本参数完整保留、workBeforeStudy仅首轮条件一班、每轮护理及工作前岗位清洁准备保留、重开不自动启动、保存新方案明确切换SELECTED_ACTIONS，不静默变成每节工作或ALWAYS。
- [ ] T041 [US3] 在 `VirtualDisplay/app/src/main/java/com/ynk/virtualdisplay/automation/FundingDecision.kt`（新增）、`VirtualDisplay/app/src/main/java/com/ynk/virtualdisplay/automation/PetSessionController.kt` 统一needed与实际工作触发，消除tuition+careEstimate和tuition-only双条件；新方案依赖显式decision，不循环增班、不用预计工资充当余额。
- [ ] T042 [US3] 在 `VirtualDisplay/app/src/main/java/com/ynk/virtualdisplay/automation/PlanRunner.kt`、`VirtualDisplay/app/src/main/java/com/ynk/virtualdisplay/automation/PlanPreflight.kt`、`VirtualDisplay/app/src/main/java/com/ynk/virtualdisplay/automation/PlanBudgetLedger.kt` 接入“无消费资金决策→条件工作合法跳过→实际可执行后缀预算”顺序；工作后按实际到账余额、新健康和库存重建资源轨迹，重新计算护理购买缺口并保护全部待执行学习学费；护理前核对总可行性，每笔购买前再核实际可付金额及后缀学费，不能只比较工资与一节学费或把预测工资当现金；现金仍不足消费前停止；在 `VirtualDisplay/app/src/test/java/com/ynk/virtualdisplay/ConditionalWorkBudgetTest.kt`（新增）验证30分钟边界、跳过无工作reserve及跨日后缀重查。
- [ ] T043 [US3] 在 `VirtualDisplay/app/src/main/java/com/ynk/virtualdisplay/automation/LegacyManagedAdapter.kt`（新增）、`VirtualDisplay/app/src/main/java/com/ynk/virtualdisplay/automation/SessionPlan.kt`、`VirtualDisplay/app/src/main/java/com/ynk/virtualdisplay/automation/PlanV2Store.kt`、`VirtualDisplay/app/src/main/java/com/ynk/virtualdisplay/automation/PetAutomationService.kt` 接v1只读兼容映射与原子备份，LEGACY_MANAGED继续原每轮护理/首轮条件工作及岗位前清洁准备；三个新独立入口不能走旧控制器；损坏/未知版本拒绝且保留源。
- [ ] T044 [US3] 在 `VirtualDisplay/app/src/main/java/com/ynk/virtualdisplay/ui/screens/PetPlanSheetScreen.kt` 和 `VirtualDisplay/app/src/main/java/com/ynk/virtualdisplay/ui/screens/PetHomeScreen.kt` 增加仅含学习时可用的IF_FUNDS_SHORT选项、合法跳过原因与兼容profile展示，保存新方案前明确展示护理一次/工作预先就绪差异；不新增隐藏消费确认或改变已有用户授权。
- [ ] T045 [US3] 新编译/回归并覆盖安装后，在设备验证v1参数/预算保留、首轮按需、低清洁但有香皂时旧托管仍可岗位准备、新WORK-only同条件停止且不护理，记录 `specs/001-managed-study-recovery/acceptance/us3-legacy.md`（新增）；使用隔离备份/回读验证，不清生产数据。
- [ ] T046 [US3] 串行验证低资金IF_FUNDS_SHORT的同会话工作→工资→护理→学习、资金足够的SKIPPED(FUNDS_SUFFICIENT)、30分钟边界及ALWAYS仍工作，更新 `specs/001-managed-study-recovery/acceptance/us3-funding.md`（新增）与 `specs/001-managed-study-recovery/acceptance/results.md`；一班后不够资金必须停止，不手动补工作来拼接通过。

## Phase 6: US4 — 准确开课与结果确认（P1）

目标：在US1正常开课修复基础上完善“请求/确认/结果/归属”边界，解决直接出现结果页的受控接受与旧结果拒绝。独立验证：正常一节完成且只记一次；未确认不重点击；有本attempt归属的结果可接受，无法归属即不明停止。

- [ ] T047 [US4] 在 `VirtualDisplay/app/src/test/java/com/ynk/virtualdisplay/StudyCompletionAttributionTest.kt`（新增）建立先失败的场景：请求后正常学习、仅旧“继续学习”页面、相同课名但不同attempt、无请求时间、同attempt完整结果、重复观察；完成与预算不得重复，fixture仅用脱敏结构化观察，不上传原图。
- [ ] T048 [US4] 在 `VirtualDisplay/app/src/main/java/com/ynk/virtualdisplay/automation/StudyOutcome.kt`（新增）定义开始请求、开始确认、课程完成及Unconfirmed等明确结果，并在 `VirtualDisplay/app/src/main/java/com/ynk/virtualdisplay/automation/StudyCompletionGate.kt`（新增）以请求时间、课程身份、完成内容和attempt证据校验归属；单个继续学习标签不能放行。
- [ ] T049 [US4] 在 `VirtualDisplay/app/src/main/java/com/ynk/virtualdisplay/automation/PetStudyFlow.kt` 接归属门禁，处理首个可信页面直接是结果的情况；保留现有有限120秒开始等待和真实课程时长+宽限，不自动再次付费，不仅凭页模板匹配补判完成。
- [ ] T050 [US4] 在 `VirtualDisplay/app/src/main/java/com/ynk/virtualdisplay/automation/StudyExecutor.kt`、`VirtualDisplay/app/src/main/java/com/ynk/virtualdisplay/automation/PlanRunner.kt`、`VirtualDisplay/app/src/main/java/com/ynk/virtualdisplay/automation/PlanRunStore.kt` 接typed outcome，Unconfirmed终止后项且保留预算；每attempt完成只增加一次studyRoundsCompleted，不把预算占用当学习完成。
- [ ] T051 [US4] 在 `VirtualDisplay/app/src/test/java/com/ynk/virtualdisplay/PetStudyStartRegressionTest.kt` 和 `VirtualDisplay/app/src/test/java/com/ynk/virtualdisplay/PlanRunnerCompositionTest.kt` 回归实际失败反馈、详情课名/时长冲突、结果归属失败后不执行后项及重复结果不二次记账；若有界诊断仍在生产路径，明确其移除范围。
- [ ] T052 [US4] 新编译/回归并覆盖安装后，验证正常田径课1/1及旧/无归属结果页不消费重放；直接结果的正向归属用真实同attempt证据或明确标记的设备集成夹具验证，不能声称夹具为真实上课，记录 `specs/001-managed-study-recovery/acceptance/us4-study-confirmation.md`（新增）并更新T002诊断结论。

## Phase 7: US5 — 恢复与安全停止（P2）

目标：强化已具备门禁的运行集成与恢复，未知状态及时停，课程缺失在任何消费前停。独立验证：四类页面恢复≤3次，缺课零消费，无关课程缺失不阻断W/C；断连/停止/重启不重放。

- [ ] T053 [US5] 在 `VirtualDisplay/app/src/test/java/com/ynk/virtualdisplay/PlanSafetyIntegrationTest.kt`（新增）验证缺课/未知必要资源/过期健康/预算不足/工资不明/重复启动的端到端fake调用轨迹，断言停止位置、未选及后续动作0次、输入未重发，覆盖Foundation门禁在真实组合路径没有被绕过。
- [ ] T054 [US5] 在 `VirtualDisplay/app/src/main/java/com/ynk/virtualdisplay/automation/HealthFreshnessGate.kt`（新增）、`VirtualDisplay/app/src/main/java/com/ynk/virtualdisplay/automation/WorkExecutor.kt`、`VirtualDisplay/app/src/main/java/com/ynk/virtualdisplay/automation/CareExecutor.kt`、`VirtualDisplay/app/src/main/java/com/ynk/virtualdisplay/automation/StudyExecutor.kt` 统一WORK/CARE工作后及长导航后的必要健康刷新与新generation归属，保留Unknown和有限重试；STUDY健康仅可选观察，低值/Unknown/Stale不能阻断学习、恢复或完成登记；不能无限回房读数，也不以刷新名义执行未选护理。
- [ ] T055 [US5] 在 `VirtualDisplay/app/src/main/java/com/ynk/virtualdisplay/automation/PetNavigation.kt`、`VirtualDisplay/app/src/main/java/com/ynk/virtualdisplay/automation/PetBackNavigation.kt`、`VirtualDisplay/app/src/main/java/com/ynk/virtualdisplay/automation/PetPageProbes.kt` 核对并复用已识别入口重入/地图返回规则，最多三次、未知页面不试点，补 `VirtualDisplay/app/src/test/java/com/ynk/virtualdisplay/PetBackNavigationTest.kt` 对正常/入口/未知/耗尽四类断言，避免复写已修分支。
- [ ] T056 [US5] 在 `VirtualDisplay/app/src/main/java/com/ynk/virtualdisplay/automation/SessionRecovery.kt`、`VirtualDisplay/app/src/main/java/com/ynk/virtualdisplay/automation/PlanRunCoordinator.kt`、`VirtualDisplay/app/src/main/java/com/ynk/virtualdisplay/automation/PlanRunner.kt`、`VirtualDisplay/app/src/main/java/com/ynk/virtualdisplay/automation/PetAutomationService.kt` 集成STOP/断连/重启后的输入锁、授权撤销、pending核对与终态展示，保持已完成项和保守预算，不能宣称撤销游戏内已开始活动；补 `VirtualDisplay/app/src/test/java/com/ynk/virtualdisplay/PlanRecoveryTest.kt`（新增）跨步骤不重放断言。
- [ ] T057 [US5] 在 `VirtualDisplay/app/src/main/java/com/ynk/virtualdisplay/automation/PlanPreflight.kt`、`VirtualDisplay/app/src/main/java/com/ynk/virtualdisplay/automation/PlanRunner.kt` 运行T053并修正任何生产集成绕过：含STUDY在工作/护理购买前COURSE_NOT_FOUND停止，无STUDY不读取课程；必要学费/健康/余额未知停止，但不得重新把零金币独立工作未知余额基线变成阻塞。
- [ ] T058 [US5] 新编译/回归并覆盖安装后，从生产UI验收COURSE_NOT_FOUND零工作/购买/开课、W/C无课程仍可启动、重复启动被拒、工资/资源/预算不足正确停止，记录 `specs/001-managed-study-recovery/acceptance/us5-safety.md`（新增）；用受控夹具验证不可安全制造的故障并标明范围，不操纵游戏经济或清账本凑场景。
- [ ] T059 [US5] 串行验证正常返回、入口重入、未知页停止、恢复耗尽、STOP与pending重启，核对≤3次和无重复消费/预算，写入 `specs/001-managed-study-recovery/acceptance/us5-recovery.md`（新增）；需要夹具时与真实设备证据分开，不对未知真机页面探点。

## Final Phase: Polish and Cross-Cutting Concerns

目标：清理诊断、完成当前源码验证，按证据收口；不因某次测试绿而自动宣称产品通过。

- [ ] T060 移除仅为T002/T024添加的临时诊断和原始OCR输出，检查 `VirtualDisplay/app/src/main/java/com/ynk/virtualdisplay/automation/PetStudyFlow.kt` 及实际被加标记文件，将必要长期日志收敛为结构化run/step/attempt证据；保留测试夹具，原始设备资料只在本地排除目录。
- [ ] T061 对最终当前源码执行构建/新编译JVM及必要设备集成测试，核对主jar/classpath、实际JUnit结果、aidl还原、临时钥匙清理及签名指纹，记录 `specs/001-managed-study-recovery/acceptance/final-build.md`（新增）；不提交版本计数或推送发布。
- [ ] T062 对照 `specs/001-managed-study-recovery/acceptance/results.md` 核查三独立/四组合、严格按需工作、七配置重启、旧护理兼容、跨日幂等与六类异常/四类恢复的构建有效性；只对被后续改动影响或缺证据的场景补验，若最终包需覆盖安装仍保留数据；每一SC给通过/不通过/未定，不能拼接不同run冒充同会话。
- [ ] T063 在 `specs/001-managed-study-recovery/quickstart.md` 和 `specs/001-managed-study-recovery/acceptance/results.md` 更新实际可用命令、字段、场景与FR/SC证据索引；写明真实设备/夹具的验证边界、初始资源或日预算阻塞，不以删除预算或额外游戏动作绕开。
- [ ] T064 更新工作区 `progress.md`、`memory.md` 和 `specs/001-managed-study-recovery/tasks.md` 的真实完成状态，附当前构建身份、本地证据路径、未完成任务和下一步；所有所需设备证据齐全才交付产品验收完成，否则保持相应任务未完成。

## Dependencies

### 阶段与故事

```text
Setup T001-T004
  -> Foundation T005-T014
     -> US1 T015-T028  （三项独立MVP，含正常开课根因修复）
        -> US2 T029-T038  （七组合与逐项结果）
           -> US3 T039-T046  （按需工作与旧托管兼容）
              -> US4 T047-T052  （完整结果归属与未确认边界）
                 -> US5 T053-T059  （恢复/故障集成）
                    -> Final T060-T064
```

五个故事共享生产Service/runner/UI，默认按上图集成，保证每阶段可独立构建并验证。US4的失败取证T002及正常路径修复T024已前置到US1，不会要求US1依赖尚未实现的US4才可正常学习。US5是强化集成，不是允许此前没有课程预检、预算、设备锁或不重放规则。

### 关键任务前置

| 任务/组 | 必须已完成 |
| --- | --- |
| T005 | T001、T004；T002诊断可并行继续，但必须在T024前有明确反馈 |
| T006 | T005 |
| T007 | T005、T006 |
| T008 | T007 |
| T009-T014 | 顺序完成；T010需T007/T008，T011需T010，T012需T009/T011，T013需T006/T008，T014需T009-T013 |
| T015/T016/T017 | T014与固定端口；三个测试文件互不覆盖 |
| T018/T019/T020 | 分别T015/T016/T017；可并行但不共改Service/Controller |
| T021 | T020；先做独立工资取证政策再接executor |
| T022 | T018、T019、T021 |
| T023/T024 | T022；默认串行，T024另需T002；不并发改PetStudyFlow和StudyExecutor |
| T025-T028 | 顺序；T026需T024/T025，所有设备动作需T026与当次新鲜预检 |
| T029/T030 | US1；测试可并行 |
| T031-T038 | 顺序；T032需T029，T036需T030/T031/T035；设备同serial串行 |
| T039/T040 | US2；两个测试文件可并行 |
| T041-T046 | 顺序；T041需T039，T043需T040，T046需资金决策/预算/兼容就绪和新构建 |
| T047-T052 | 顺序；T047复用T002/T024的已证实正常路径反馈 |
| T053-T059 | 顺序；先集成故障测试，再改freshness/恢复，T058/T059需当前新构建 |
| T060-T064 | 全故事代码/对应验收已收敛；待验或条件阻塞必须在T062/T064保持未定 |

不存在依赖循环。同一路径被多个任务改动时，按此表顺序执行；`[P]`不允许跳过自身前置。

## Parallel Execution Examples

| 故事 | 可并行示例及边界 |
| --- | --- |
| US1 | Foundation就绪后T015(CareExecutorTest)、T016(StudyExecutorTest)、T017(工资政策测试)并行；各测试就绪后T018(CareExecutor)、T019(StudyExecutor)、T020(WorkEvidencePolicy/WorkFlow/ResourceReader/Catalog)并行。三者不得顺手改Service、runner或Controller；集成统一由T022接管。 |
| US2 | T029的runner fake测试与T030的设备存储测试代码可并行编写；T030只是编写测试，不同时操作设备。生产PlanRunner/Store/UI改动按T031-T035串行。 |
| US3 | T039的FundingDecisionTest与T040的LegacyManagedAdapterTest可并行编写；后续T041-T044共享Controller/runner/Store/UI，串行集成。 |
| US4 | T047完成后，可由只读协作者复核旧失败证据，主实现依T048-T051串行；无额外[P]生产修改，避免同时改StudyFlow/runner造成归属状态不一致。 |
| US5 | 可在主实现T054-T057期间让只读协作者审查四类恢复/六类异常的覆盖；生产Service/runner/executor共享较多，默认无并行写任务。所有设备故障验证由一个执行者串行操作。 |

## Implementation Strategy

1. **首个可交付MVP**：Setup+Foundation+US1（T001-T028）。可单独打工一班、护理、学习且无隐式其他消费，保留旧托管入口；必须正常开课实锤修复并取得三份设备记录，不能仅增加按钮就交付。
2. **组合增量**：US2在相同runner上打开七组合保存与执行，验四种多动作组合，尤其ALWAYS的真实W+C+S。
3. **策略/兼容增量**：US3引入IF_FUNDS_SHORT、统一资金与预算顺序和v1兼容。保留“旧方案工作前护理”与“新WORK-only不护理”的明确差异。
4. **完整可靠性增量**：US4/US5处理结果归属、异常/恢复/重启；它们不撤销基础阶段已有的不重放与安全预检。
5. **证据收口**：最终仅补受后续修改影响或缺失的验证；尊重真实资源和480分钟日上限，条件不足就记录未定，不自动清零/增班/领币。各阶段更新进度、任务勾选和实际证据，不提交/推送。

## Coverage and Format Validation

### 任务统计

| 分类 | 数量 | 范围 |
| --- | --- | --- |
| Setup | 4 | T001-T004 |
| Foundation | 10 | T005-T014 |
| US1 | 14 | T015-T028 |
| US2 | 10 | T029-T038 |
| US3 | 8 | T039-T046 |
| US4 | 6 | T047-T052 |
| US5 | 7 | T053-T059 |
| Polish/交付 | 5 | T060-T064 |
| 合计 | 64 | 全部待实施 |

并行标记共10项：T015/T016/T017/T018/T019/T020/T029/T030/T039/T040，均须满足自身前置且写范围互不覆盖。各故事的独立验收已写在对应phase；没有独立真机证据的故事不能因后续组合成功自动勾完。

### 功能需求映射

| 需求 | 主任务 |
| --- | --- |
| FR-001 | T018-T023、T033-T034 |
| FR-002 | T005-T006、T029、T031-T038 |
| FR-003 | T006、T013、T015-T021、T027 |
| FR-004 | T013、T053、T057-T058 |
| FR-005 | T006、T021、T039-T042、T046 |
| FR-006 | T017、T020-T021、T042、T046 |
| FR-007 | T015、T018、T029、T032 |
| FR-008 | T016、T019、T024、T054 |
| FR-009 | T009-T014、T024、T047-T052 |
| FR-010 | T047-T050、T052 |
| FR-011 | T010-T011、T026、T042、T050、T062 |
| FR-012 | T008、T032、T042、T054 |
| FR-013 | T055-T056、T059 |
| FR-014 | T030-T031、T033-T036、T040、T043-T045 |
| FR-015 | T007、T025、T029、T032、T035 |
| FR-016 | T007-T009、T035、T038、T050、T062-T063 |
| FR-017 | T012、T022-T023、T034、T053、T056、T058 |
| FR-018 | T027-T028、T037-T038、T046、T062 |

### 成功标准映射

| 标准 | 验收任务/证据 |
| --- | --- |
| SC-001 | T027-T028，us1-independent |
| SC-002 | T037-T038，us2-combinations |
| SC-003 | T038与T046，显式及按需同run闭环 |
| SC-004 | T030/T036、T040/T045，七配置与v1行为 |
| SC-005 | T012/T028/T047/T051/T053/T058，六类异常 |
| SC-006 | T055/T059，四类恢复 |
| SC-007 | T010-T011/T026-T028/T042/T046/T050/T062，每次预算前后及跨日幂等 |

格式校验已运行 `tools/diagnostics/validate_feature_tasks.py` 并PASS：64个连续唯一ID、故事/非故事phase标签、全部复选框与明确路径、10个并行标记、18项FR与7项SC覆盖行及本地文档链接均通过。该脚本不执行任务，也不将覆盖索引当产品通过证据。没有extensions.yml和constitution.md，before_tasks/after_tasks按不存在跳过。
