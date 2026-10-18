# P5-00 现有工程能力审计（复用方案）

日期：2026-10-12 ｜ 仓库：`VirtualDisplay` ｜ 分支：`feature/qqpet-mvp-session`
已核实：本地源码（含未提交改动，commit `484cb33` 之前全部已入库）、Git 历史、远端（`github.com/Ynkcc/VirtualDisplay` **已归档只读**，无法 push，提交只存在于本地分支）。

## 一、可直接复用的能力清单（源码事实）

| 能力 | 载体 | 对 P5 的意义 | 状态 |
|---|---|---|---|
| 页面识别与门禁 | `PetPageProbes.kt` + `PetProfile.calibrated()` + `TemplateMatcher` | 职业小镇所有页面 identity 都走同一套 24×24 下采样 + 0.97 阈值 | 复用，零改动 |
| 仅实测坐标的输入 | `PetInputController.tap/drag` + `AutomationFacts` 的帧新鲜度/信任门禁 | 打工页所有点击自动继承「输入前必须有一个新鲜可信帧」的硬约束 | 复用，零改动 |
| 页面导航 | `PetNavigation.tap(before, point, after, action)` / `initialise()` / `returnHomeFromResult()` | 出门→地图→建筑的链路与学园完全同构：tap + before/after 双探针核验 | 复用 tap 原语，新增职业小镇段 |
| 有界状态确认 | `PetFlowContext.verify / verifySettled / settleLatest` | 打工开始、完成确认、领奖确认都用同一套有界轮询，不允许盲目连点 | 复用 |
| 活动计时 | `PetStudyFlow` 的 monotonic deadline 模式（`SystemClock.elapsedRealtime()+X`）与 `DailyBudgetStore.HARD_STOP_MINUTES=480` | 打工等待复用同一「单调时钟 + 预计剩余时间 + 临近完成才观测」模式；480 分钟已有常量 | 复用 |
| 失败恢复 | `SessionRecovery` + `PetRecoveryBoundary`（暂停→3 次尝试→120s 窗口→安全停止） | 游戏卡顿、截图不可用、App 被切后台全部由现有 recover 链路处理 | 复用，新增 page→probe 映射即可 |
| 事件账本 | `SessionEventStore`（有序 JSONL）+ `EventKind` | 打工事件新增 `WORK_STARTED/COMPLETED/REWARD_CONFIRMED/FAILED/INTERRUPTED` | 扩展枚举，不改存储格式 |
| 消耗/收益预算 | `CareConsumableBudget` / `ExchangeBudget`（先预留、未确认不退回） | 打工次数上限、金币支出上限照搬同一语义 | 复用模式，新增 `WorkBudget` |
| 真机运行时状态 | `PetManagedRuntime` + `runtime-state.json` | UI 打工卡片数据源 | 扩展 snapshot 字段 |
| 健康读数 | `PetHealthCheck.observe` / `PetStudyHealthPolicy`（仅 energy/cleanliness ≥90，mood 只观测） | 学习侧门槛保持原样；打工侧**不调用**该 policy | 学习侧零改动 |

## 二、职业小镇相关存量代码

- 资产目录无任何 career/work/job/town/coin 模板（`pet_exchange` 6、`pet_health` 2、`pet_mvp` 12）。
- 源码 grep「打工/职业/小镇/工分」仅命中 UI 占位文案：`PetHomeScreen.kt:564`「收益行动 · 打工 · PK · 福袋 · 冒险」、`PetPlanSheetScreen.kt:71`「打工 / 工分 · 预计下一版开放」。
- 结论：**职业小镇没有任何已有模板、坐标、截图或旧代码**，P5.1 必须从零采集。这不构成复用，须明示。

## 三、本地 vs 远端差异

远端归档前的最后一个远端提交为 `381590c`（MANAGED_BATCH_PASS 1/1）；本地领先远端两个提交（`e171a79`、`484cb33`，2026-10-12 游戏 UI 改版适配）。本地是唯一完整版本，继续在本地分支开发，P5 每个 Gate 打本地提交。

## 四、复用方案（决定，不重构）

1. **保留 `PetStudyFlow` 的学习业务判断不动**：护理前置、课程选择、鼓励、成绩单确认一行不改。
2. **新增 `PetWorkFlow`**（类比 `PetStudyFlow`）：`enterTown → discover → selectJob → start → waitCompletion → confirmReward → returnRoom`，与学习 flow 平行但共享 `PetFlowContext`/`PetNavigation` 全部原语。
3. **共享已验证的计时、状态观察、结果确认、事件记录**：`PetFlowContext.verifySettled`、`SessionEventStore`、`PetRecoveryBoundary`。
4. **不抽取 ActivityFlow 公共基类**：学习与打工的等待语义差异足够大（学习 10 分钟固定课、打工时长随职业变且要领奖），强行统一只会给已验证代码增加风险。共享发生在「原语」层而非「流程」层。
5. **调度**：现有 `PetSessionController.run()` 是 `repeat(rounds){care→study→next}` 的顺序批次。P5.4 在其外新增 `PetActivityPlanner`（纯 Kotlin 决策函数），由 controller 在每轮结束/开始前查询下一活动；不改 controller 主体。
6. **UI**：`PetHomeScreen` 已有「收益行动」分组（当前 dimmed），P5.5 只解除该分组占位并接入真实 runtime 字段，不重构界面结构。

## 五、已记录的 480 分钟语义（关键前提，勿直接等同）

`DailyBudgetStore` 只累计**本 App 自己完成**的课程分钟数，注释明确写着「手动学习/打工不可见，因此 8h 门禁只有在宠物当天没有外部学习/打工时才精确」。因此：

- 480 分钟是**App 侧预算常量**，不是已证实的游戏计时规则。
- P5.2 的联合优化以 480 为可用预算上界，并在输出中标注其来源与该限制。
- 游戏侧「每日有效活动时长」的真实计算方式（是否含打工、是否跨活动共享、何时重置）**尚未实测**，列入 P5.1 采集项。

## 六、P5.0 结论

不需要第二套自动化框架，也不需要大规模重构。可复用度：识别/输入/导航/恢复/账本/预算 100% 复用；唯一从零开始的是**职业小镇的页面资产与收益数据**，即 P5.1 的核心交付。
