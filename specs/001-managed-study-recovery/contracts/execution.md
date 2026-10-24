# Application Contracts: 独立动作与资源方案

Date: 2026-10-21. 本文为用户入口及进程内契约，不新增网络API或导出服务。资源模型见 [data-model.md](../data-model.md)。

## 1. 用户入口

| 入口 | 快照 | 行为边界 |
| --- | --- | --- |
| 健康检查 | HEALTH_CHECK | 只读，不消费；同受设备锁 |
| 打工 | WORK(ALWAYS,一班) | 按实际岗位体力/清洁/分钟执行，不查课程、不隐式护理 |
| 护理 | CARE(targets) | 先用库存、仅买缺口，无工作/学习 |
| 学习 | STUDY(courseId,rounds1..10) | 检查课程、学费、分钟；无强制健康值/新鲜度门禁，无隐式打工/护理 |
| 运行方案 | 已保存有效PlanV2快照 | 仅选中项与明确条件跳过 |

所有profile的STUDY，包括LEGACY_MANAGED，统一遵循无强制健康规则。健康可作可选观察；课后读取失败不撤销已证实课程完成。课程可能实际消耗健康，允许记录其页面耗值与课后变化供后续资源推演，不能固定为零。

运行中允许停止/查看状态，拒绝第二启动。编辑下次方案不改变运行快照；显示整体/逐项状态、三类完成计数、实际钱包与购买额度的区别、预算占用和短缺原因。

## 2. 方案编辑与保存

- 三个选择项，固定WORK→CARE→STUDY，至少一项，每类至多一个。name1..80字符，日分钟1..480。
- WORK仅VERIFIED零金币费用岗位，一班；默认ALWAYS，只有含STUDY可选IF_FUNDS_SHORT。
- CARE阈值60..100仅作护理目标；STUDY课程及节数1..10不附带健康目标。
- 未选动作不必填配置；选STUDY时课程档案/名称/时长须可确认。
- 新CARE一次，旧每轮护理与原工作前已选护理准备保持，并明确展示；所有profile均移除旧学习健康门禁。
- 工作前需岗位实际资源足够，后置CARE不自动前置；保存新方案展示此差异，不隐式增步骤。
- AtomicFile版本化读写，坏/未知版本保留拒绝，不默认执行；旧配置原子留源，不因打开/迁移自动启动。

契约示例：

```json
{
  "schemaVersion": 2,
  "planId": "work-care-study",
  "name": "打工后护理学习",
  "dailyBudgetMinutes": 480,
  "executionProfile": "SELECTED_ACTIONS",
  "steps": [
    {"stepId":"work-1","type":"WORK","workOptionId":"work:竹影武馆/帮武馆跑腿","trigger":"ALWAYS"},
    {"stepId":"care-1","type":"CARE","energyThreshold":100,"cleanlinessThreshold":100},
    {"stepId":"study-1","type":"STUDY","courseId":"study:田径运动课","rounds":1}
  ]
}
```

## 3. 启动、停止与观察

- `start(requestId,planSnapshot,deviceTarget)`先验证、互斥和所选依赖预检；requestId重复不建第二输入会话。
- `stop(runId)`撤销后续授权，保留已派发事实/占用；不能声称撤销游戏已开始的活动。
- `observe(runId)`不推进消费。设备serial/displayId固定，物理尺寸不能作为display4坐标授权。
- 生产Service仍非exported，仅应用UI和startForegroundService；rounds2/4不得被改成1。启动非法/旧参数显式适配或拒绝。
- 含STUDY方案在任何消费前校验课程存在；无STUDY不加载课程依赖。

## 4. 执行器与资源接口

| 接口 | 契约 |
| --- | --- |
| PlanValidator / PlanRunner | 验证类型/参数，持有不可变快照、串行状态、设备锁及证据，不让UI负责资源/导航 |
| WorkExecutor | 每班新WorkFlow；启动前同次核实际cost与目录、当前E/C分别>=cost、分钟、身份/按钮；不调用cleanTo，不用护理目标代替成本 |
| CareExecutor | 复用护理动作与确认，但新增库存/钱包/学费保护；不把现有ExchangeBudget等同余额 |
| StudyExecutor | 复用课程身份、付费、时长与结果，必须去掉PetStudyFlow、Controller、恢复回调中的健康门禁；先登记已证实完成，再附加可选健康观察 |
| ResourceObservationPort | 返回HealthObservation、InventoryObservation、真实BalanceObservation、WorkCostSnapshot/CourseObservation，保留Known/Unknown、时间/页面/证据；不依赖独立动作未选的课程 |
| ResourceReadiness | `evaluate(action,observedState,costOrPurchase,suffix)`→Ready / NeedsObservation / Insufficient / Unconfirmed；证明绑定快照，变化后重算 |
| ResourceTrajectory | 根据当前状态推演WORK/CARE/STUDY后缀；PREDICTED不作支付授权，实际步骤结束重建OBSERVED状态 |
| FundingDecision | 库存调整后的护理购买缺口+全部剩余未付学费；评估工作会增加的护理缺口，与dispatch共用snapshotId，Unknown不得武断多打工 |
| BudgetPort | `reserve(attemptId,minutes)`在按钮确认后/输入前唯一持久占用，完成不再加、未知输入不退款 |
| EvidencePort | runId/stepId/iteration贯穿输入、预算、库存/钱包观察、工资及完成 |
| LegacyManagedAdapter | 保留原每轮护理/岗位前已选护理、首轮条件工作安排；学习健康规则与新profile一致，不能为兼容继续阻挡学习 |

## 5. 动作资源合同

### WORK

需要同次确认的WorkCostSnapshot(actualMinutes>0、energyCost>=0、cleanlinessCost>=0)与有效当前E/C，分别满足E>=energyCost、C>=cleanlinessCost和分钟条件。任何必要成本/当前值Unknown、过期或页面/目录冲突，在授权消费输入前停止；保留unreadFields拒识。白名单零金币岗位可以钱包Unknown，但不能豁免体力或清洁。

完成必须同场岗位名、实际分钟一致、正余额差或同工资页正award。已知非正差不可被award覆盖；工资不是累计钱包、时间到零不是完成。

### CARE与PurchaseReadiness

1. 读健康缺口与确认可用库存。needUnits=ceil(max(0,target-current)/实际gain)，仅Known可用件数抵扣；buyUnits=max(0,needUnits-knownOwned)。Unknown库存先观察，不能按0直接买或判钱不足。
2. 已拥有道具可消费时，零现金不是阻塞条件；已达标零消费。每次用后读真实增益/库存，不凭预测宣称达标。
3. 确需购买才要求真实钱包、数量、单价/总价和会话额度；含后续STUDY时要求wallet>=purchaseTotal+remainingTuitionCoins，remainingTuitionCoins合并所有未付节数。
4. 购买额度是支出上限，不是可花余额；没有可靠钱包证据→NeedsObservation、不付款。必须实现可独立于未选课程的真实资金观察，不能用盲付被拒代替检查。
5. 付款前持久意图，确认支付与库存到账；未知结果停止、不重付，保留可能已花额度。既有不重放规则不放松。

### STUDY

课程身份/可选性、名称时长、当前学费与真实钱包、分钟预算、唯一输入和本attempt结果归属为必要条件。E/C低、Unknown/Stale或缺少护理配置均不阻挡开始/恢复/完成登记，所有profile一致。实际健康消耗仅观察/后续推演，不当作零或硬门槛。

## 6. 组合资源与预算

先评所选完整后缀的资源可行性：WORK扣实际E/C和分钟，工资暂为预测；CARE先库存后购买并保护剩余学费；STUDY逐节扣实际学费/分钟，可记录实际健康变化。每步结束重读实际所需状态再授权下一消费，预测工资绝不可提前消费。

按需工作先用实际库存/钱包判断不工作路径；可跳过时不计工作分钟。需要工作时评一次岗位消耗后新增护理费用，若后缀仍可确定不可行则说明差额，不增班。实际一班后重新核钱包、库存与健康，不够则在后续购买前停止。

日分钟账本共用锁且attempt全局幂等，未知派发保留占用，跨午夜结果更新原记录；CARE耗材/购买额度独立。仅余30分钟、学习30分钟且资金足够时，可跳过10分钟工作后学习，不能要求最大40分钟。

## 7. 结果、恢复与持久化

COMPLETED或有证据的合法SKIPPED才继续；Blocked/Failed/Unconfirmed/Cancelled后项不执行。未知页面不试点、恢复≤3次、pending重启不重发，保留原始停止码及中文原因。STUDY恢复不得调用共用健康强门禁。

RunRecordV2保存steps[]、三类计数、资源快照/轨迹、预算前后及证据；旧字段可读但不补造历史。课程完成证据先登记，附带健康读取失败仅标观察缺失。原始图片/日志只留本地，不上传。
