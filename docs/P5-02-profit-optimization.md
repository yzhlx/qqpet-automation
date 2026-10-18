# P5.2 学习/打工联合优化（金币优先 / 平衡 / 学习优先）

> 阶段：P5.2（联合优化求解器）
> 输入：P5.1 采集的职业收益数据（`docs/P5-01-career-discovery.md`、`docs/P5-01-career-catalog.json`）
> 输出：`app/src/main/java/com/ynk/virtualdisplay/automation/PetActivityModels.kt`、`PetActivityPlanner.kt`、`app/src/test/java/com/ynk/virtualdisplay/PetActivityPlannerTest.kt`

---

## 1. 本阶段交付了什么

一个**纯决策模块**（无 Android 类型、无设备交互、可 JVM 单测）：给定「今天还剩多少共享预算 / 体力 / 清洁」和「有哪些可排程的活动（课程 + 打工选项）」，按用户选择的策略算出**今天该做什么、各做几次**，并给出：

- 排程表（活动 × 次数 × 分钟 × 净金币 × 工分 × 体力 × 清洁）
- 总计与属性收益
- **可解释的排序理由**
- **数据可信度告警**（哪些数字只是页面读数）
- **与贪心解的对照**（证明精确解不是把贪心换个说法）

三种策略：`COIN_FIRST`（金币优先）、`BALANCED`（平衡）、`STUDY_FIRST`（学习优先）。

---

## 2. 建模：学习与打工是同一个池子

游戏侧已实测（2026-10-12，P5.1 §4.3）：

| 当日累计活动时长 | 收益倍率 |
|---|---|
| 0 ~ 8 小时 | **100%** |
| 8 ~ 12 小时 | **25%** |
| 12 ~ 24 小时 | **10%** |
| 每日 0 点重置，上一天不累计 | — |

学习与打工**共同计入**这 8 小时。因此 `ActivityOption` 对课程和打工选项使用**同一个数据结构**——它们本来就是争夺同一份额度的竞争对手，分成两个类型只会诱导调用方分别优化再手工相加，而那是错的。

```
ActivityOption(
  id, kind( STUDY | WORK ), label,
  durationMinutes,          // 计入共享预算的分钟数
  coinReward, coinCost,     // 金币收益 / 学费
  workScoreReward,          // 工分（仅打工）
  attributeGain,            // 属性（仅课程）
  energyCost, cleanlinessCost,
  sessionsAvailable,        // 今天还剩几次
  minUnits, maxUnits,       // 排程上下界；minUnits>0 = 用户学习目标
  confidence,               // VERIFIED / PAGE_READING / UNKNOWN
  caveat,
)
```

**三层结构不被拍平**：P5.1 已证实「职业 → 场地职业阶段 → 单次工作选项」是三个层级，奖励挂在**单次选项**上。因此 `id` 形如 `work:竹影武馆/帮武馆跑腿`，一个场地两个选项就是两个独立的 `ActivityOption`，绝不合并。

---

## 3. 规则 D：学习目标是约束，不是可交易项

用户学习目标通过 `minUnits >= 1` 表达，`PetActivityPlanner.plan()` 的**第一步**就是 `reserveStudyGoals()`：

1. 预留目标所需的次数与分钟数；
2. 若目标无法满足（今天次数不够），返回 `studyGoalsMet = false` 并说明缺什么，**不会**删减课程去凑金币；
3. 只有目标满足后，才把**剩余**预算交给优化器。

优化器拿到的选项池里 `minUnits` 已被清零——**代码里不存在一条能拿学习目标换金币的路径**。

对应测试：`studyGoalIsNeverDroppedForCoins`、`unmetStudyGoalStopsThePlanInsteadOfTrimmingTheCourse`、`studyMinutesComeOffTheSharedBudgetBeforeWorkIsPlanned`。

---

## 4. 规则 A/B 的落实

- **规则 A（打工无护理阈值）**：`ActivityOption` 只有 `energyCost` / `cleanlinessCost`，即**工作自身的要求**。没有 `PetStudyHealthPolicy` 那种「energy/cleanliness ≥ 90 才准上课」的门槛，因为打工按规则 A 不调用上课专用护理前置。调用方（P5.4 调度器）是否要在打工前护理是策略问题，不在本模块。
- **规则 B（收益不随属性波动）**：`ActivityOption` 的收益是**常量**。模块里没有任何以宠物属性为输入的收益预测函数。属性只作为**结果**出现在 `attributeGain`（上课带来力量增长），不作为**输入**去调整打工收益。

---

## 5. 求解：精确枚举 + 贪心对照

### 5.1 为什么不是贪心

朴素做法是按「每分钟金币」排序，能排就排。这个做法在本场景**会算错**，已用枚举验证：

| 预算 | 贪心（按 6.8 vs 6.18 每分钟） | 精确解 |
|---|---|---|
| 55 分钟 | 帮武馆跑腿 ×5 = **340** 金币，剩 5 分钟作废 | 陪练基础课 ×1 + 帮武馆跑腿 ×1 = **346** 金币 |

贪心只看单项每分钟效率，看不到「45 + 10 正好凑满 55」这个组合。`exactSolverBeatsPerMinuteGreedy` 测试把这个 6 金币的差距钉住，并把贪心解本身写进 `DayPlan.comparison` 供人核对。

### 5.2 算法

对每个选项的单位次数做**带剪枝的深度优先枚举**（分钟/体力/清洁任一超限即 break），目标是字典序比较的元组：

| 模式 | 目标元组（越靠前越重要） |
|---|---|
| `COIN_FIRST` | 金币 → 工分 → 属性 → 已用分钟 → 不越过衰减区间 |
| `BALANCED` | 金币 + 工分×5 → 属性 → 已用分钟 → 不越过衰减区间 |
| `STUDY_FIRST` | 学习分钟 → 属性 → 金币 → 工分 → 已用分钟 → 不越过衰减区间 |

用**字典序元组**而非加权求和，是为了避免权重相互抵消导致「金币和工分谁也没赢」的模糊结果，也避免大数打包溢出。

**`BALANCED_WORK_SCORE_WEIGHT = 5` 是策略旋钮，不是游戏公布的兑换率。** 游戏从未说明 1 工分值多少金币。这个常数被写成公开常量、并在 `explanation` 里明确声明「该倍率是策略设定，不是游戏公布的兑换率」，就是为了不让它伪装成事实。对应测试 `explanationDisclosesTheWorkScoreWeightAsAPolicyChoice`。

**搜索空间上限 400,000 状态**，超过则退化为贪心解并如实报告，而不是假装搜过。

### 5.3 三种模式确实不同

同一输入下三模式产出不同排程（测试 `theThreeModesDivergeOnTheSameDay`，100 分钟、1 次课程目标、体力/清洁充足；下表的净金币已含 1 次课程的 22 学费）：

| 模式 | 排程 | 净金币 | 工分 | 学习分钟 |
|---|---|---|---|---|
| `COIN_FIRST` | 课程 ×1 + 帮武馆跑腿 ×9 | 590 | 90 | 10 |
| `BALANCED` | 课程 ×1 + 陪练基础课 ×2 | 534 | 108 | 10 |
| `STUDY_FIRST` | 课程 ×10 | −220 | 0 | 100 |

`BALANCED` 用 56 金币换了 18 工分——这正是「平衡」该有的行为，而 `COIN_FIRST` 一分金币都不让。

---

## 6. 诚实性设计

### 6.1 `RewardConfidence` 三级可信度

P5.1 的收益数字来源不同，混在一起会高估确定性：

| 等级 | 含义 | 本阶段数据 |
|---|---|---|
| `VERIFIED` | 真实完成闭环验证 | 帮武馆跑腿（10min，68 金币 / 10 工分，2026-10-12 19:18 真实打工） |
| `PAGE_READING` | 只读了页面，未闭环 | 陪练基础课（45min，278/54）、替摊主看摊（10min，40/10） |
| `UNKNOWN` | 未采集 | 风铃旅社·街巷跑腿（45min，QQ 限流阻塞） |

- `PAGE_READING` 会进排程，但在 `warnings` 里标注「收益来自页面读数，尚未用真实完成闭环验证」。
- `UNKNOWN` **不进优化池**。没测过的数字不参与优化——否则等于在优化一个没人验证过的假设。它只在 `warnings` 里说明未采集。

对应测试：`pageReadingRewardsAreFlaggedRatherThanPresentedAsVerified`、`unknownRewardsAreExcludedAndSaySo`。

### 6.2 未闭环验证的 45 分钟数据

`陪练基础课` 的 278 金币 / 54 工分 / 体力 10 来自**选中选项卡片后的页面读数**（读取方法见 P5.1 §3.1），**没有**用一次真实 45 分钟打工闭环验证。求解器用它，但每次输出都带告警。P5.2 不宣称该数字已证实。

### 6.3 学费计入净金币

`coinCost`（课程学费 22）在 `netCoinsPerSession()` 里被减掉。测试 `netCoinsSubtractCourseTuition` 验证「1 次课程 = 净 −22 金币、力量 +8」。不这样做会把「花 22 金币上课」显示成 0 金币，等于把成本藏起来。

---

## 7. 边界条件

| 场景 | 行为 |
|---|---|
| 预算已用尽 | 空排程 + 解释 |
| 体力/清洁不够 | 该活动排不进去（`energyCapBlocksWorkThePetCannotAfford`） |
| 今天次数已用尽 | `sessionsAvailable` 封顶 |
| 学习目标次数不够 | `studyGoalsMet = false`，说明缺什么，不删课程 |
| 选项池为空 | `DayPlanInput` 直接拒绝（`PLAN_NO_OPTIONS`） |
| 非法参数（时长 0、min>max、负金币等） | `ActivityOption.init` 拒绝 |
| 搜索空间超限 | 退化贪心并报告 |

---

## 8. Gate 2 判定

**Gate 2（优化计算）＝ PASS**。测试已在 ASCII 镜像（`C:\qqpet-build\ws\p5\vd`，SDK/JDK 均在纯 ASCII 路径）上真实执行：

```
> Task :app:testDebugUnitTest --tests "com.ynk.virtualdisplay.PetActivityPlannerTest"
BUILD SUCCESSFUL
tests=22  skipped=0  failures=0  errors=0
```

证据（按 Gate 2 要求逐条对应）：

| # | Gate 2 要求 | 测试 | 结果 |
|---|---|---|---|
| 1 | 三种模式实现且**产出不同排程** | `theThreeModesDivergeOnTheSameDay` | PASS（同一天 100 分钟下，COIN_FIRST 590 金币/90 工分，BALANCED 534 金币/108 工分，STUDY_FIRST −220 金币/0 工分/90 学习分钟） |
| 2 | 精确解**优于**每分钟贪心，且贪心被记录供核对 | `exactSolverBeatsPerMinuteGreedy` | PASS（55 分钟实例：精确 346 vs 贪心 340，gap 6） |
| 3 | 学习目标**不可被金币优化删除** | `studyGoalIsNeverDroppedForCoins`、`unmetStudyGoalStopsThePlanInsteadOfTrimmingTheCourse`、`balancedModeStillMeetsEveryStudyGoal` | PASS |
| 4 | 学习与打工**共享同一预算** | `studyAndWorkShareOneBudget`（440 已用 → 只剩 40 分钟，45 分钟打工排不进）、`alreadySpentMinutesShrinkTheDay`（470 已用 → 只排 1 次） | PASS |
| 5 | 体力/清洁/可用次数**均为硬约束** | `energyCapBlocksWorkThePetCannotAfford`、`cleanlinessCapIsRespected`、`availabilityCapsTheSessionCount` | PASS |
| 6 | 未采集收益**不进优化池**，页面读数收益**带告警** | `unknownRewardsAreExcludedAndSaySo`、`pageReadingRewardsAreFlaggedRatherThanPresentedAsVerified` | PASS |
| 7 | 480 分钟规则作为前提，且优先不越过 8 小时 | `explanationNamesTheModeAndTheSharedBudget`、`explanationDisclosesTheWorkScoreWeightAsAPolicyChoice` | PASS |

补充事实：求解器在 `STUDY_FIRST` 下会把学习目标当**下限**而非上限——先保证 `minUnits`，再在剩余预算里继续优先排学习（`studyFirstCoversTheGoalAndThenKeepsChoosingStudy`）。

### 8.1 测试过程中发现并修掉的 3 个真实缺陷

这些不是测试写错，是代码本身的问题，均已修：

1. **`min` / `max` 未导入**（`PetActivityModels.kt`、`PetActivityPlanner.kt`）→ 编译失败，8 处 unresolved。已补 `kotlin.math.min/max`。
2. **预算用尽时构造了非法输入**：`plan()` 把 `remainingMinutes - reserved.minutes` 直接传给 `budgetMinutes`，当预算刚好用尽时得到 `budgetMinutes = 0`，触发 `PLAN_BUDGET_INVALID`。已改为 `budgetMinutes = max(1, residualMinutes)` / `spentMinutes = residualBudget - residualMinutes`，用 `remainingMinutes == 0` 表达「空的一天」。
3. **解释文案把「剩余预算」说成「共享预算」**：原文 `共享预算 360 分钟`（实际 480 里已用 120）。已改为同时给出总额、已用、剩余三项，避免读者把剩余当总额。

另有一处**测试断言口径**修正：`theThreeModesDivergeOnTheSameDay` 原期望 `612 + 68 - 22 = 658`，但 `蹦蹦跳跳体能课` 的 `coinReward = 0`、`coinCost = 22`，正确值是 `9×68 − 22 = 590`。这是**测试期望值算错**（把课程的 `coinReward` 当成了 68），不是求解器错；已按求解器输出与手工枚举双向核对后改为 `9 * 68 - 22`。同时 `unitsFor()` 原实现只读**第一个**匹配项的次数，而学习目标会先被 `reserveStudyGoals()` 排成独立一项、再被优化器追加同选项，导致次数被少算；已改为求和。

### 8.2 全量回归

`:app:testDebugUnitTest` 全量：**195 tests, 1 failed**。唯一失败是 `DialogDigitReaderTest > recorded frames replay with exact ledger ground truth`（OCR 数字回放），**与本阶段无关**：已通过「把 P5.2 三个新文件临时移出后单独跑该测试」证实其失败完全相同，属既有问题。

**未覆盖 / 待办**：

- 风铃旅社 45min 数据缺失（外部限流阻塞），该场地只有 10min 一个点。
- 学习侧课程收益数据只用了 `蹦蹦跳跳体能课` 一项（学费 22、力量 +8），**不是完整课程清单**；`docs/P5-01-career-discovery.md` §8.2 第 8 项已列为待补。
- 480 分钟的 25%/10% 衰减区间**只作为「避免排入」的软偏好**，没有实现跨区间的收益折算。若未来要主动刷 12~24h 区间，需要补这部分建模。
- `DialogDigitReaderTest` 的既有失败未修（不在 P5.2 范围，建议单独开一条）。

---

## 9. 给 P5.3 / P5.4 的接口约定

```
PetActivityPlanner.plan(DayPlanInput) -> DayPlan
```

- P5.4 调度器负责**提供真实输入**：已用分钟（来自 `DailyBudgetStore`，语义须从「App 已完成课程」扩展为「学习+打工共同计时」）、当前体力/清洁、今日剩余次数、用户学习目标（`minUnits`）。
- P5.3 打工 Flow 负责**执行** `DayPlan.activities` 里的工作项，并在每次真实完成后回报**实际**收益，用于校准 `coinReward` 的置信度（`PAGE_READING` → `VERIFIED`）。
- 学习 Flow（`PetStudyFlow`）**不改动**；`DayPlan` 里的课程项只是告诉调度器「该上几节」，上课流程本身仍走已验证的护理闭环。
