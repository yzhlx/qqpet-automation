# Data Model: 独立动作与资源闭环

Date: 2026-10-21. 以下为待实现设计，不描述成现有持久化格式。学习无强制健康门禁的用户最新规则适用于所有profile。

## 1. PlanV2

| 字段 | 类型/规则 | 含义 |
| --- | --- | --- |
| schemaVersion | integer=2 | 与无版本旧方案区分 |
| planId | stable nonempty string | 保存方案身份，独立入口使用临时ID |
| name | string，1..80字符 | 显示名称，与任务约束一致 |
| dailyBudgetMinutes | integer，1..480 | 与现有日上限兼容 |
| steps | 1..3 PlanStep | 唯一类型，按WORK/CARE/STUDY排序；拒绝非法次序与空数组 |
| executionProfile | SELECTED_ACTIONS / LEGACY_MANAGED | 新方案与旧托管护理安排隔离；不隔离学习新规则 |
| legacySource | optional original v1 snapshot | 仅迁移追溯，不能重复执行 |

PlanStep为带类型互斥参数。未选类型不校验其配置或加载其依赖。

### WorkStep

- stepId、type=WORK、workOptionId；仅WorkCatalog VERIFIED、已确认零金币启动费用岗位。
- trigger=ALWAYS / IF_FUNDS_SHORT；独立WORK固定ALWAYS，条件工作必须有后续STUDY。
- shifts固定1；一班后后缀不可支付则停止，不隐式追加。
- SELECTED_ACTIONS的preparePolicy=REQUIRE_READY，不护理或购买；后置CARE不能满足本次WORK的前置要求。LEGACY_MANAGED保留原选中护理中的岗位前清洁准备和每轮护理。
- 本次WorkCostSnapshot及当前健康是启动必需值，不用CARE目标60..100代替岗位成本。

### CareStep

- stepId、type=CARE、energyThreshold/cleanlinessThreshold=60..100。目标值只属于CARE。
- 依赖当前HealthObservation、InventoryObservation；优先已有库存，仅为确认缺口采购，受真实钱包、后缀学费保护及独立CareConsumableBudget/ExchangeBudget共同约束。
- 已达标可SKIPPED(ALREADY_SATISFIED)，不消费。

### StudyStep

- stepId、type=STUDY、courseId、rounds=1..10，持续使用CourseTiming及实际课程学费。
- 所有profile的开始、恢复、完成登记均不依赖体力/清洁值、CARE目标或健康读数新鲜度；健康观察失败不撤销课程完成。
- 仍须课程身份/可选性、名称时长一致、真实学费余额、时间预算及唯一输入/结果归属。无WORK不筹资，无CARE不recover。
- 可记录课程页实际energyCost/cleanlinessCost及课后健康，用于后续资源轨迹；这些值Unknown不会阻挡当前学习，不硬编码成0，不表示学习没有健康消耗。
- 新方案CARE一次；旧profile保留每轮护理安排。旧阈值留作CARE配置，不再作为学习门禁。

## 2. RunRequest / RunSnapshot

- requestId去重；runId由受理产生，deviceSerial/displayId固定。
- origin=WORK_SHORTCUT / CARE_SHORTCUT / STUDY_SHORTCUT / PLAN / LEGACY。
- planSnapshot不可变，保存编辑不改变正在运行快照。
- createdAt/startedAt/endedAt、state、stopReason。
- state=CREATED→PREFLIGHT→RUNNING→COMPLETED / STOPPED / FAILED / UNCONFIRMED。
- 单设备只允许一个非终态；重复启动不创建第二输入通道；只读HEALTH_CHECK同受锁约束。

## 3. StepExecution / ActivityAttempt

- StepExecution字段runId/stepId/index/type/state/reason/时间/evidenceIds。
- state=PENDING→RUNNING→COMPLETED / SKIPPED / BLOCKED / FAILED / UNCONFIRMED / CANCELLED。
- SKIPPED仅有观测依据的FUNDS_SUFFICIENT或ALREADY_SATISFIED；未选项无伪造执行记录。
- 失败/不明/取消使后项保持PENDING，不能整体完成。
- attemptId=(runId,stepId,iteration)，STUDY iteration1..rounds，WORK固定1；每班新建WorkFlow上下文。
- attempt=PREPARED→BUDGET_RESERVED→INPUT_PENDING→START_CONFIRMED→COMPLETED；不确定发送转UNCONFIRMED，不重派。
- 分开workShiftsCompleted/careCompleted/studyRoundsCompleted。课程完成已归属后先持久登记，附带健康失败只写观察缺失，不回滚计数。

## 4. Observation / Evidence

- `ObservedValue<T>`=Known(value,observedAt,evidenceId,source) / Unknown(reason,observedAt,evidenceId)。未知不填0；过期不冒充当前事实。
- `HealthObservation`：energy/cleanliness、时间、pageIdentity/generation。工作/护理按有效性规则使用；STUDY只可选观察。
- `BalanceObservation`：availableCoins、sourcePage、时间/evidenceId；必须是实际钱包，不是工资行、目录预计奖励或会话购买额度。
- `InventoryObservation`：itemId、quantity=Known/Unknown、quantityPrecision=EXACT / LOWER_BOUND（仅Known）、时间/页面/evidenceId。确认库存数字可得精确件数；仅确认一件道具可用可记录Known(1,LOWER_BOUND)，不得将“看不出数量”当0。仅Known数量足够时才可证明无需购买；LOWER_BOUND被用尽后余量需重观察。
- `CourseObservation`：courseId/name/unlocked、durationMinutes、tuitionCoins、balance；可选observedEnergyCost/observedCleanlinessCost，未读出保持Unknown，后两者不作学习门禁。
- `WorkCostSnapshot`：snapshotId、venue/optionId/label、actualMinutes、energyCost、cleanlinessCost、observedAt/pageIdentity/evidenceId。三成本必须Known，minutes>0、耗值>=0；启动前再次同次核对目录/页面一致与当前两项健康。成本或当前值Unknown、过期、变化均停止，保留解析unreadFields阻断。
- `PayrollAward`：optionLabel、actualMinutes、positiveCoins、evidenceId、对应attempt；必须同工资页身份/时长匹配。
- `WorkEvidencePolicy`：仅零金币白名单允许开工前钱包Unknown；不得豁免必要健康/成本。完成优先正余额差；已知非正差拒绝；无可读差可用同场正工资，不能把award当钱包。

## 5. ResourceState / ResourceReadiness / ResourceTrajectory

### ResourceState

- snapshotId、runId/stepId/iteration、observedAt/generation、basis=OBSERVED / PREDICTED。
- health、wallet、inventoryByItem、remainingBudgetMinutes、remainingStudyCharges（每节courseId/tuitionCoins/paidState）、evidenceIds。
- 已支付学费从remainingStudyCharges移除；支付结果不明保持pending并锁住对应授权，禁止再次付费。remainingTuitionCoins为全部未付剩余节真实学费之和，不能仅留一节的钱或把预算分钟当金币。
- OBSERVED可授权；PREDICTED只做可行性分析，不能授予支付或证明到账。信息不够可保持Unknown，不伪造乐观轨迹。

### ResourceReadiness

- `evaluate(action, observedState, actualCostOrPurchase, suffix)`返回Ready(proofSnapshotId)、NeedsObservation(missingFields)、Insufficient(shortfalls)、Unconfirmed(pendingAttempt)。Ready绑定本次快照，导航/消费改变资源后失效。
- WORK：E>=actualEnergyCost、C>=actualCleanlinessCost、分钟足够、白名单/身份/按钮/一次授权；任一必要值未知则NeedsObservation。等于成本可执行，不套CARE目标。
- STUDY：课程及实际学费/钱包、分钟、页面/唯一输入必要值；不把health列为requiredFields。低值/Unknown/Stale不影响Ready，所有profile含恢复同规则。
- CARE消费库存：缺口和确认可用物品足够、护理额度有效，不因钱包为0拒绝已拥有道具；只在需要购买时进入PurchaseReadiness。
- `CarePurchasePlan`：按实际道具增益计算needUnits=ceil(max(0,target-current)/gain)，buyUnits=max(0,needUnits-knownOwnedUnits)；Unknown库存先观察，不能当0，也不能把最坏现金上界当“已证实不足”。LOWER_BOUND只支持已证明的数量，不足部分仍未知。
- `PurchaseReadiness`：商品/数量/实际单价与总价、Known实际钱包、会话剩余额度、remainingTuitionCoins同时满足：wallet>=purchaseTotal+remainingTuitionCoins且额度允许才可pay。单独CARE的remainingTuitionCoins=0；买后重新确认钱包与库存，不把支付额度视为钱。
- 没有可靠钱包/库存观察接口时返回NeedsObservation并停止付款，不能通过盲付让游戏拒绝来代替资金门禁。

### FundingDecision与资源轨迹

- FundingDecision包含snapshotId、inventoryEvidence、carePurchasePlan、remainingTuitionCoins、observedWallet、workNeeded、decision=RUN / SKIP / NEEDS_OBSERVATION / INSUFFICIENT、reason及两条候选后缀轨迹。
- 先评不工作路径：真实钱包覆盖库存调整后的必要护理购买与全部未付学费则可SKIP；不足且允许工作时，再评一次实际岗位成本后的健康/库存需求和预计工资路径。Unknown库存或必要钱包不能武断决定额外工作。
- WORK轨迹扣实际体力/清洁和分钟；预计工资不写入可花钱包，真实工资确认后读取实际钱包再重建状态。
- CARE轨迹先扣库存，再为缺口扣实际购买金币，按实际增益/上限100更新健康；每次实际消费后重读，不能用固定增益替代成功证据。
- STUDY轨迹按每节实际学费和时长扣金币/分钟；可记录页面实际健康消耗并标预测，实际课后观察补齐；Unknown健康不阻挡此学习或完成，但后续WORK/CARE使用前必须重新观察。
- 固定顺序不隐式补动作/增班；可确定整个后缀不够则在下一消费前停止，保留已完成记录与差额。WORK→CARE后必须重算护理缺口，不能用工作前健康估价。
- 示例：初始100/100、0币、无库存，一班-5/-2、+68币后为95/98；目标100/100需5币饼干+2币香皂，66币学费使总需求73>68，不可判闭环可行。此处工资/价格为既有记录，运行仍核实际页面与到账。

## 6. BudgetReservation

- 同一本地日账本/锁用于工作与学习；CARE耗材/购买额度独立，不扣游戏分钟。
- budgetDay/attemptId/kind/minutes/status/evidenceId/createdAt；status=RESERVED_BEFORE_INPUT / START_CONFIRMED / COMPLETED / UNCONFIRMED_RETAINED。
- 已占用分钟不是已完成分钟；同attempt全局幂等，完成只更新状态不重复加。按钮确认后、输入前原子reserve，未知派发不退款。
- 先无消费资源/资金判定，再检查实际可执行后缀分钟。资金足够跳过10分钟工作、仅剩30分钟学习时，剩余30分钟可启动；不按40分钟最大值拒绝。
- 跨午夜结果更新原reservation日期，新的attempt取当前日并重查后缀。v1汇总保留legacyUsedMinutes及日键，不清零、不二次累加。

## 7. RunRecordV2与兼容

- schemaVersion=2、runId、planSnapshot、steps[]、overallState/reason/counts、预算前后、资源快照/轨迹、evidenceRoot；保留旧mode/completed/requested读取。
- 输入意图和终态原子保存，重启不自动执行旧任务；INPUT_PENDING只观察或停止，不重放。
- 无版本按v1验证并原子留源；workBeforeStudy只首轮条件工作，每轮护理/岗位前已选护理准备保留。旧profile的CARE目标仍保留，但学习健康门禁在所有profile统一移除，不能把“兼容”解释为继续错误拦学习。
- 保存新版展示CARE一次与WORK需先就绪的差异；坏版本保留拒绝，无默认消费回退。JSON在设备实测，不靠org.json JVM桩宣称通过。
