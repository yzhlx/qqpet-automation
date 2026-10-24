# Implementation Plan: 打工、护理、学习独立与组合闭环

**Branch**: `feature/qqpet-mvp-session` | **Date**: 2026-10-21 | **Spec**: [spec.md](spec.md)

## Summary

先修复开发环境的Android工具缺失，再为应用提供独立打工、护理、学习与固定顺序的七种非空组合。插件缺失已确认是desktop根补丁的disabled:true，改false后热加载成功，三个只读android工具已实际调用。本阶段不将恢复工具注册扩大成插件多显示器改造。

产品实现采用版本化PlanV2和统一串行PlanRunner。新单项入口生成单元素计划，组合入口生成WORK→CARE→STUDY的有序子集；三个executor复用底层动作但必须解耦资源门禁：WORK核同次岗位实际体力/清洁耗值及分钟，CARE先用库存、仅买缺口并保护后续全部未付学费，STUDY只核课程/实际学费/分钟及输入结果，不强制健康数值或新鲜度。后者适用于所有profile，旧护理配置/安排仍保留但不作为学习门禁。允许记录课程实际健康消耗及课后变化用于资源轨迹，不硬编码消耗为零。每项结果独立，预算继续开始前持久预留，不确定输入不重放。

先处理上次0/1开课未确认的真实失败，完成单项后再验组合，最后以同一runId取得真实工作、工资、护理和学习证据。

## Technical Context

| 项目 | 已确认选择 |
| --- | --- |
| Language/runtime | Kotlin2.2.10；JDK21运行Gradle；源码JVM target17 |
| Android | compileSdk36.1、min29、target35；现场Huawei FOA-AL00 Android12 |
| UI | 现有Jetpack Compose/Material3；无新前端框架 |
| Automation | Shizuku13.1.5、现有PetFlowContext/InputController、ML Kit中文16.0.1 |
| Build | AGP9.1.0、Gradle9.3.1、ASCII联接和已验证aidl编码流程；签名保留既有流程 |
| Storage | 方案AtomicFile JSON；现有日预算存储迁移为保留旧汇总+attempt幂等明细；运行记录本地JSON/JSONL |
| Testing | 现有JUnit4.13.2与direct runner、新runner fake ports；JSON/迁移/所有真机闭环单独设备验证 |
| Device/display | serial DKS9K23420020242；宠物display4、物理控制台1084×2412；当前插件层级尺寸不一致，不直接转坐标 |
| Scale | 一台设备一个会话；≤3个选中类型，每类一次，工作一班，学习1..10节，无任意编排引擎 |
| Performance | 沿用现有捕获/等待边界；学习开始最多现有120秒，无依据不调大；地图恢复≤3次；不挂长驻logcat |
| Completion evidence | 岗位身份+时长+工资、护理新状态、课程归属与完成；界面点击/倒计时零/预算扣除均不等于完成 |

用户规则与资源接口已确定；实际独立库存/钱包观察来源仍须实施中建立真实反馈并验证，缺必要证据时NeedsObservation、不付款，不能宣称旧购买流程已提供真实钱包门禁。开课失败根因同样待反馈定位。两者是明确实施关口，不以猜测放宽资源或支付条件。

## Constitution Check

项目没有`.specify/memory/constitution.md`，未伪造宪章，也未将此缺失当作新的授权要求。采用用户本次明确范围及已记录边界：

| Gate | 设计前检查 | 设计后检查 |
| --- | --- | --- |
| 插件修复授权 | 用户明确要求先修复 | 只改唯一禁用配置，工具实测通过 |
| 产品范围 | 三项独立与组合验收 | 七种组合，无循环/优化器/任意排序扩张 |
| 未选动作不消费 | 原FULL强制护理不满足 | 新executor只执行选中动作，独立工作无课程依赖 |
| 真实证据 | 旧STOPPED不能算完成 | 逐step/attempt取证，单元测试不替代真机 |
| 不重放不确定副作用 | 现有one-shot及recovery规则 | INPUT_PENDING持久化，恢复不重发，预算不退款 |
| 资源限制 | 日预算与耗材预算既有 | 共用账本、分清占用与完成、无充值/领币 |
| 数据/分发边界 | 无卸载/清数据/提交推送/原图外传 | 无新增服务导出，签名安装保留数据，证据本地 |

无未解释gate违规。未创建/切换分支、没有extensions.yml，before_plan/after_plan均按不存在跳过。

## Project Structure

设计产物：

```text
specs/001-managed-study-recovery/
  spec.md
  plan.md
  research.md
  data-model.md
  contracts/execution.md
  quickstart.md
  checklists/requirements.md
```

主要实现位置（后续阶段）：

```text
VirtualDisplay/app/src/main/java/com/ynk/virtualdisplay/
  automation/
    SessionPlan.kt / SessionMode.kt
    PetAutomationService.kt / PetSessionController.kt
    PlanRunner / PlanStep / StepExecution（拟新增）
    WorkExecutor / CareExecutor / StudyExecutor（拟新增或提取）
    PetWorkFlow.kt / DeviceWorkResourceReader.kt
    PetCareFlow.kt / PetStudyFlow.kt / CourseTiming.kt
    DailyBudgetStore.kt / SessionRecovery.kt / PetManagedRuntime.kt
  ui/data/SessionStartRequest.kt
  ui/screens/PetHomeScreen.kt / PetPlanSheetScreen.kt
VirtualDisplay/app/src/test/java/com/ynk/virtualdisplay/
  对应计划、启动参数、runner、预算、工作/学习门禁回归
```

本轮已新增本地`.specify/templates/plan-template.md`及`.specify/scripts/powershell/setup-plan.ps1`，不是上游生成器的完整安装。setup已从项目根以-Json实际运行并返回四个要求字段；Windows默认脚本策略拒绝首次运行后，仅子进程ExecutionPolicy Bypass执行成功，系统配置未改。

## Phase 0: Research Decisions

详见 [research.md](research.md)。已解决：

1. 插件安装完整但显式禁用；启用配置即可热加载，已验证工具调用。
2. 现有生产UI无独立WORK/STUDY，模式/控制器是CARE+STUDY与首轮可选工作；不能只改按钮。
3. 独立工作去除课程依赖；仅VERIFIED零金币费用岗位允许开工前余额Unknown，最终仍需严格正工资证据；后续货币消费重新读实际余额。
4. 固定顺序七组合、单项同runner；没有CARE不得cleanTo/recover，没有WORK不得筹资。
5. 按需资金决策使用库存调整后的护理实际购买缺口+全部未付剩余学费；工作后健康下降会新增护理需求，决策与dispatch共用资源快照。
6. 预算是开始前持久占用，新增attempt幂等与重启保护，不改变为成功后再加一次。
7. rounds统一1..10，修复UI允许2/4但启动转1的不一致；name同步1..80字符。
8. 旧兼容profile保留每轮护理/岗位前已选护理，所有profile统一无学习健康门禁；学习后续节仅因实际课程/学费/分钟或执行结果条件停止，不因健康低值/未知停止。新方案CARE一次的差异仍展示。
9. 开课请求、开始确认、结果及归属独立；当前“继续学习”节点不能为旧失败补判PASS。

## Phase 1: Design

实体与状态见 [data-model.md](data-model.md)，用户入口/进程边界见 [执行契约](contracts/execution.md)。

- `PlanValidator`先校验所选依赖与参数；含STUDY时消费前统一课程预检，不含STUDY时不加载课程档案。
- `PlanRunner`负责串行状态与互斥，UI只提交快照；HealthCheck保留为只读入口并受设备锁约束。
- WORK ALWAYS与IF_FUNDS_SHORT严格区分；显式三动作方案不会因金币充足而跳过工作；旧方案仅首轮按需一班。
- 预算门禁从Service中的work+course闭包提取为动作/计划后缀策略；只在按钮确认后、输入前对当前attempt持久reserve。
- 记录`steps[]`和工作/护理/学习分离计数；旧completedRounds兼容字段不能成为唯一成功指标。
- 启动意图和结果归属持久化，重启不自动重放；不确定消费保持停止与预算占用。
- 工资前余额Unknown并不等于0：特定零金币岗位可以不需该前置，但任何购买和学费门禁都没有放宽。若读到非正余额差，正award不能覆盖矛盾。
- 新方案只在明确保存时生效，运行快照不可变；迁移或损坏JSON不能自动启动。
- 增加InventoryObservation、WorkCostSnapshot、ResourceState、ResourceReadiness、CarePurchasePlan/PurchaseReadiness及ResourceTrajectory；接口字段与Known/Unknown/证据规则详见数据模型。WORK同次成本和当前E/C分别>=消耗；STUDY的requiredFields不含健康，恢复/完成登记也不能调用旧健康门禁。
- CARE先确认库存、仅购缺口；支付前需真实钱包>=购买总价+全部剩余未付学费，另检查会话支出额度。不能将ExchangeBudget当钱包，也不能强迫无课程的CARE去选课程读钱；独立钱包/库存观察尚需在实施中证实可靠来源，缺证据时返回NeedsObservation并不付款。
- 预测/观察资源轨迹分离：打工扣实际体力/清洁/分钟，工资实际确认并重读钱包后才可用；护理按工作后健康和库存重算；学习逐节扣实际学费/分钟，并允许记录课程页实际健康消耗/课后变化，未知不填0、不影响当前学习。已确认完成先持久登记，再作可选健康读取。

## Implementation Sequence

此处给依赖顺序，具体可执行任务在 `/speckit-tasks` 生成，不在本阶段写完整实现。

1. **开发前置与失败复现**：插件启用已完成；保存工具验证结论。分析s21-reenter帧/事件，建立开课未确认反馈和缺少归属的结果页回归。缺中间帧只补有界观测，不先盲目开长方案。
2. **模型、持久化与启动一致性**：PlanV2、受限Step、验证器、旧兼容profile、run结果与attempt模型；统一rounds1..10；读写/迁移与设备锁。先用fake executor验证选择与顺序，不触发真实游戏。
3. **预算与恢复底座**：保留旧汇总迁移、attempt幂等预留、pending持久化、不明不退款与不重放；明确日跨越与重复请求行为。
4. **独立executor与资源准备**：先CARE库存/钱包/后缀学费保护，再STUDY全profile移除健康值/新鲜度/恢复门禁，并将已证实完成登记先于可选课后健康；WORK抽同次实际双成本/当前双健康/分钟检查，不隐式clean。定义可靠库存/钱包观察，Unknown明确阻断必要购买而非盲付，按需与显式方案共用ResourceReadiness。
5. **统一runner和UI入口**：生产Service创建同一个runner，独立动作=单元素计划；方案勾选和固定顺序预览、逐项进度及结果、兼容方案差异展示。保留只读健康检查。
6. **学习确认修复与必要回归**：依据第1步确定根因后最小修改；请求/确认/结果归属分别验证；解决问题后编译并跑新旧回归。不是默认延长timeout。
7. **构建安装与真机单项**：当前源码编译、JUnit、签名核对和保留数据覆盖安装；先W/C/S各自验收，含不相关参数缺失和资源不足停止。
8. **组合、兼容与异常验收**：四种多动作组合逐个验证；另跑资金不足按需工作全链与资金充足跳过；回归CNF、预算、未知输入、并发、停止、迁移和恢复。逐场记录证据，最后才评SC-001～007。

步骤2和3完成前不并行改Service/Controller入口；后续分工按文件范围避免共享源码覆盖。产品UI变更实施时再加载适用UI技能，本计划不引入未经验证的视觉改版。

## Validation and Acceptance

完整流程见 [quickstart.md](quickstart.md)。

| 验收层 | 标准 | 当前状态 |
| --- | --- | --- |
| 插件配置红/绿 | probe修复前exit1，修复后exit0 | 通过 |
| 当前会话工具调用 | android_devices/app_info/ui_tree真实返回 | 通过 |
| 配置与模型质量 | spec22项FR、8项SC，资源规则和设计产物齐备；任务映射待lead同步 | 本阶段检查 |
| JVM与编译 | 本次源新编译、有效JUnit输出及EXIT0 | 本阶段未执行业务构建 |
| 独立W/C/S | 每项成功，未选动作0次，无无关依赖 | 待实施后真机验证 |
| 四种多动作组合 | 所选顺序/结果与方案一致 | 待实施后真机验证 |
| 严格按需工作闭环 | 同runId工作→正工资→护理→正确课程完成 | 待实施后真机验证 |
| 异常/恢复/持久化 | SC-004～007全部有证据 | 待实施后验证 |

不能把过去408个测试或历史单节PASS当作新模型已验收。新测试仅覆盖runner行为、实际耦合、状态/预算和迁移，避免给纯文案/可逆配置改动堆镜像测试。

## Post-design Gate Check

- 所有新FR均在实体/契约/验证指南中有对应，未遗漏独立WORK/STUDY。
- 固定顺序与CARE一次语义已显式写入规格；旧每轮护理以兼容profile保留，不静默退化。
- WORK-only不再需要选课；其无基线余额策略有明确零金币费用白名单与工资证据限制，Unknown不被填0。
- 不确定支付/启动不重放，保守预算不退款；单设备互斥覆盖所有入口。
- 三项独立+四项组合及按需策略都有可验证设备场景；原始画面只留本地。
- 没有宪章和hooks的事实已记录；没有新增审批或分发动作。Phase1设计可进入 `/speckit-tasks`。

## Risks and Remaining Work

1. s21开课真实根因尚未证实；先用反馈闭环定位，不能为了过验收直接认结果页。
2. 当前插件未验证多显示器输入，ui_tree总尺寸与主体尺寸不一致；现有display4专用路径继续使用。
3. 游戏初始状态/资金可能阻止独立W或无CARE组合；应记录前置不足，不插入未选动作来凑成功。
4. 账本v1迁移与输入意图跨重启幂等会影响真实预算，需真实JSON设备验证，保留原文件可回滚。
5. 七组合及额外按需场景需要较长真机运行；受每日480分钟与实际服务情况约束，不能跨场次拼接严格闭环证据。
6. 本阶段仅修插件启用配置和完成设计，没有修改或重新安装宠物应用业务代码；所有产品验收状态保留待完成。
