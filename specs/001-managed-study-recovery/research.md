# Phase 0 Research: 独立动作与方案闭环

Date: 2026-10-21. 本文依据当前工作树、已安装插件配置和真实只读工具结果；不是网络资料研究，也不宣称产品已通过设备验收。

## R1 Android工具缺失的根因与修复（已完成）

- Decision: 将desktop profile `cordis.patch.yml`末尾唯一的 `dsh-android.disabled` 从true改为false；保留已安装包和bundle，无需改插件代码或重装。
- Rationale: 当前环境指向desktop profile；包版本0.1.0-rc.12、bundle条目均存在，但根补丁显式禁用。`tools/diagnostics/check_android_enabled.py` 修复前输出FAIL且exit1，修复后PASS。配置热加载后，本会话真实调用 `android_devices` 返回华为DKS9K23420020242在线，`android_app_info` 返回应用1.0.60、versionCode60、running=true；`android_ui_tree`成功返回层级。根因和功能恢复均有证据。
- Alternatives considered: 包缺失、插件导入失败、会话缓存未更新。启用后立即能调用工具，后三项无需修改。没有重启整个DSH。
- Limits: 该probe只断言配置，真实工具调用另作验收；恢复工具注册不等于插件已正确支持虚拟屏4。已读投流/截图代码默认`screencap -p`，没有display4选择。
- UI observation: 只读层级工具返回screen1084×2412，但节点主体720×1600且含QQ“继续学习/分享”按钮。这是显示域可能不一致的实测，不应据此按比例点击；也不能将当前结果页认定为历史STOPPED会话的完成证据。未执行任何设备点击。

## R2 生产能力与最小范围

- Decision: 采用三个可选择动作的有限有序方案，顺序WORK→CARE→STUDY，各类型最多一个；学习内部支持1～10节。独立入口只是单动作方案，健康检查继续为只读工具。首版无任意排序、循环、重复工作步骤、优化器接线。
- Rationale: `SessionMode.kt:20`只有HEALTH_CHECK/CARE/FULL；`PetHomeScreen.kt:763`仅健康/护理/托管。`SessionPlan.kt:24`没有动作列表，`PetPlanSheetScreen.kt:166`只提供按需工作开关。纯日优化器仅定义和测试引用，没有生产接线，且不含CARE。
- Alternatives considered: 继续增加runsCare/runsStudy/runsWork布尔组合会把七种模式、工作触发和迁移散落到控制器；完整工作流引擎超出需求。使用受限steps表达统一语义，UI仅呈现勾选和顺序。
- Scope interpretation: 用户要求“每个独立或组合”，固定顺序的七种非空子集满足首版目标；需要先护理再工作时先运行单独护理。此默认已写入spec，后续若需要自定义顺序再扩展。

## R3 打工与课程余额耦合

- Decision: 引入显式 `WorkEvidencePolicy`。首版只开放生产白名单VERIFIED且无金币启动费用的岗位：独立打工不需课程或开工前余额，必须有新鲜且满足岗位体力/清洁的观察、岗位身份/时长/按钮确认和一次启动授权。完成门禁仍要求同一场次点名岗位、实测时长匹配，以及正余额差或同工资页明确正数工资；若实际可读余额差非正，不可用工资文字覆盖矛盾。Unknown保持Unknown，不伪造0。
- Rationale: `DeviceWorkResourceReader.kt:31-57`只因工作详情不显示金币而借用课程详情；`PetWorkFlow.kt:232-237`仍强制Known余额，但`:419-445`已经支持正工资award。这两处前后规则不一致。已验证帮武馆跑腿消耗体力5/清洁2而非金币，独立运行没有货币支出，因此课程余额不是其必要前置。
- Follow-on: 含学习方案在任何消费前已预检课程；工作完成后到课程详情重新读实际余额/学费，再进入后续消费；不得用预测工资或目录收益推算可花余额。CARE沿用真实兑换结果确认，不拿工作奖金额冒充总余额。
- Alternatives considered: 为独立工作强制选一个课程；未知余额当0；发明未验证的房间余额OCR入口。均拒绝。允许Unknown只针对已验证无金币费用岗位的工资取证策略，不放宽有货币支出的门禁。
- Required regressions: 工作前Unknown钱包但最终正award可完成；无award且无正差不能完成；非正差不能被正award覆盖；WORK-only无课程依赖。启动前须同次WorkCostSnapshot确认实际分钟/体力/清洁与目录一致，再同时核当前E/C>=实际耗值；必要cost/current值Unknown、Stale或页面成本变化应阻断，不能只复用readEnergy。已验证目录样例为10分钟、体力5、清洁2、工资68；边界5/2可开，4/2或5/1不可开。现解析unreadFields阻断必须保留，不能把未知成本占位0放行。

## R4 不隐式执行未选动作

- Decision: WORK只按岗位实际体力/清洁/分钟核准备，不购买或调用cleanTo；STUDY所有profile均不设健康值/健康新鲜度门禁，不recover；CARE按目标、库存和实际购买资金运行。健康可记录但不阻挡学习，课后观察失败不抹已证实完成。学习可能实际消耗健康，页面耗值/课后观察用于后续资源轨迹，不固定为0。
- Rationale: 当前Controller工作前补清洁、FULL强制护理及PetStudyHealthPolicy在开课/恢复硬调用与新独立规则不符。工作/护理需要健康，学习不应继承该门禁；须连Controller、PetStudyFlow、PetFlowContext/SessionRecovery及课后登记顺序一起调整。
- Legacy: LEGACY_MANAGED保留课程、每轮护理、原岗位前已选护理准备与首轮按需工作；统一移除学习健康门禁是用户明确的新规则，不能以v1兼容为由保留。新动作方案CARE一次、WORK需先就绪的差异仍在保存时展示。
- Alternatives considered: 工作内部隐藏护理、学习内部隐藏工作/护理；都会让独立验收失去意义。

## R5 按需工作与资金规则

- Decision: 显式WORK使用ALWAYS，IF_FUNDS_SHORT仅含STUDY且首轮最多一班。FundingDecision先评无需工作路径：按确认库存抵扣后的CARE购买缺口+全部剩余未付学费核实际钱包；库存或必要钱包Unknown先观察，不把库存当0、最坏费用上界当缺钱。确需工作时推演其实际健康消耗造成的护理增量，并复核一次工资路径可行性；预测工资不能授权消费。实际工作后用新健康/库存/钱包重建后缀，与dispatch共用snapshotId。
- Rationale: 旧代码既有needed与tuition-only不一致，又只按工作前健康估CARE且未扣库存；现有ExchangeBudget只限会话支出，不能证明钱包有钱。CARE-only与CARE+STUDY同样需要购买前真实钱包门禁，不是仅条件工作分支的责任。
- Purchase rule: 先确认库存不足才购买；wallet>=本次实际购买总价+全部剩余未付学费，且会话额度允许才pay。库存足够时零现金不阻断；必要钱包无法可靠读取时NeedsObservation、不付款，不用盲付失败代替检查；未知付款不重付。
- Alternatives considered: 两处条件保留、循环增班、按期望收益驱动；拒绝。

## R6 预算、次数与幂等

- Decision: 保留开始前持久预留、启动未知不退款的日预算语义；以sessionId+stepId+iteration作幂等键，工作与学习共享账本，CARE不扣游戏分钟。完成记录与预算占用分离。先做无消费的FundingDecision，再按实际将执行的动作检查必要后缀总分钟；资金足够的IF_FUNDS_SHORT工作先标记合法跳过、不计入后缀。随后每个活动实际启动前单独reserve。
- Rationale: `DailyBudgetStore.kt:55-60/107`明确保守预留，Service`:144-149`开始前reserve；不应改写成完成后才扣。当前工作闭包额外检查一节课程，独立工作要改为只查自己；新runner用计划后缀检查，确保后续有预算而不重复预留。
- Counts: `SessionPlan`允许1..10，但`SessionStartRequest`只认1/3/10，会丢失2/4等值。新启动路径以持久配置快照为准、统一验证1..10，旧intent兼容时修正sanitizer；必须测试2和4节不变成1。
- Migration: v1每日汇总保留为legacyUsedMinutes，不清零；新幂等reservation只追加。进程重启发现pending输入时不得重发，需核对或终止。
- Alternatives considered: 完成时再次扣预算、未知启动自动退款、重启清空预算；均拒绝。

## R7 开课未确认与结果页

- Decision: 保留旧失败现场，先建立真实开课路径回放/探针再修学习识别；请求、开始、结果、归属分别建模，未确认不重点击。若首个可信页面已是结果页，需要请求时间、课程身份、完成内容共同证明归属；单独“继续学习”不足以完成。
- Rationale: 交接会话b2e3dda0-...实际verdict为STOPPED 0/1，原因LEARNING_NOT_CONFIRMED_AFTER_START。当前只读层级出现结果页不能逆推这次会话成功。根因仍待实施前诊断，计划不预设延长超时或改坐标就能解决。
- Validation strategy resolved: 用本地既有course-before-start、run-record和events建立失败序列；若缺少中间帧，只加有界且可移除的观测并从已确认页面单次复验；不重新运行整条长方案来猜原因。
- Alternatives considered: 直接认结果页、自动再点、仅延长timeout、仅凭单测通过收口；拒绝。

## R8 工具链与治理

- Decision: 使用现有Kotlin2.2.10、Compose、Shizuku13.1.5、ML Kit中文16.0.1，Android SDK36.1/target35/min29；JDK21运行Gradle、源码JVM target17。JVM回归继续使用项目辅助runner及新编译classpath。
- Rationale: `app/build.gradle.kts`、`gradle/libs.versions.toml`及`tools/jvm-tests/README.md`。Windows中文路径与aidl编码已有经过验证配方，计划不更换工具链。
- Planning setup: 项目原无setup-plan.ps1和plan-template；已补最小本地实现。默认系统ExecutionPolicy阻止.ps1，改为只对该子进程使用-ExecutionPolicy Bypass运行成功，未改系统策略。当前分支为feature/qqpet-mvp-session，无新分支。
- Constitution: 无`.specify/memory/constitution.md`，不编造批准过的宪章；按用户授权和项目边界做明确gate。没有extensions.yml，前后hooks跳过。
- Remaining product evidence: 本阶段只完成插件修复和设计；开课根因与三项独立/四项组合设备验收在实施阶段执行。设计决策不能伪装成已实现或已实测。

## R9 用户确认的五项资源修订（2026-10-21）

- Decision: 增加InventoryObservation、WorkCostSnapshot、ResourceState、ResourceReadiness、PurchaseReadiness和ResourceTrajectory；详见数据模型。name边界同步1..80，新增FR-019～022与SC-008。任务/检查表由lead后续同步，本次六文档修订不替代业务实现。
- Resource transitions: WORK按本次实际eCost/cCost/minutes扣资源，工资直到真实确认再读钱包；CARE按工作后状态和库存重算缺口，仅采购不足件数并保护所有未付剩余学费；STUDY按实际学费/分钟执行，可能有页面记录的健康消耗但不设健康门禁，Unknown耗值不填0。
- Counterexample: 初始100/100、0金币/库存，工作后95/98、68金币，目标100/100需饼干5+香皂2，剩61不足66学费。不能以68>=66断言整方案可行；预测可行性和实际逐步授权必须分离。
- Verification: 所有profile验证学习低健康/Unknown/Stale可开、课后健康失败不抹完成；工作5/2恰好、少1/Unknown/成本变化；零现金足库存、部分库存仅购缺口、满状态零购买、真实钱包不足与后缀学费保护、多节剩余总学费、工作新增护理缺口。
- Remaining implementation question: 现有库存记录常Unknown、ExchangeBudget明确不是钱包，可靠独立库存/实际余额观察来源仍须实施中建立真实反馈并验证。接口必须如实NeedsObservation/停止必要支付，不宣称旧OCR或购买对话框已解决，也不要求无STUDY方案先选课程。学习可选健康不受此阻塞。
- Alternatives rejected: 全部所需道具都按购买计、Unknown库存当零、付款被拒代替余额检查、预测工资当余额、旧profile继续学习健康门禁、将“不强制健康”解释为学习健康消耗固定0。
