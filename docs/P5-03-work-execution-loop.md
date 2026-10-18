# P5.3 职业小镇自动打工执行闭环 — 验收报告

- 分支：`feature/qqpet-mvp-session`
- 基线提交：`0d2fab0`（P5.2 已通过验收）
- 本阶段提交：未提交（工作区状态，见「代码改动与回滚方式」）
- 测试基线：上一次记录 `195 tests / 1 failed`（既有 `DialogDigitReaderTest`）
- 测试结果：**244 tests / 0 failed / 0 skipped**（本阶段新增 49 个测试）

---

## 1. 一句话结论

`PetWorkFlow` 已实现：预览与真实启动在结构上分离、授权门禁默认拒绝、四级收益账本不合并、
异常覆盖 12 项。**单元测试与隔离测试全部通过；真机只读预览（Gate 3A）与真实打工（Gate 3C）
尚未执行，因为没有获得明确授权 —— 当前状态停在「去打工」按钮之前。**

---

## 2. 本阶段交付物

### 2.1 新增源码（8 个文件，全部纯 Kotlin，JVM 可测）

| 文件 | 职责 |
|---|---|
| `PetWorkModels.kt` | `WorkOption` / `ResourceSnapshot` / `RewardRecord` / `RewardEvidence` / `RewardRecordKind` |
| `WorkStartGate.kt` | `WorkStartAuthorisation`（真实启动授权）+ `WorkStartConfirmation`（启动闩锁） |
| `PetWorkStateMachine.kt` | `WorkPhase` 15 态、转移表、`WorkOutcome` 6 种结果 |
| `WorkSessionLedger.kt` | 单场次收益入账，防重复入账 |
| `WorkCatalog.kt` | 4 条 catalog + `compare()` 差异检测 |
| `WorkRuntimeSink.kt` | 把 `PetWorkContext` 收窄成可测接口 + `inputHalted`/`pauseRequested` 边界 |
| `PetWorkNavigation.kt` | 职业小镇导航/选选项/读奖励行/等完成（`WorkPageReader` 注入式） |
| `PetWorkFlow.kt` | 主流程：`preview` / `start` / `waitAndVerify` / `run` |

修改 1 个文件：`SessionEvent.kt` 追加 7 个 `EventKind`（`WORK_PREVIEW_VERIFIED` … `WORK_INTERRUPTED`）。

### 2.2 新增测试（2 个文件，49 个测试）

- `PetWorkGateTest.kt` — 25 个：授权默认拒绝、目标绑定、非循环、上限硬顶、撤销、花费不退回、
  启动闩锁不可跳过/不可重排、重复入账防护、目录 UNKNOWN 不当 0、`PAGE_READING` 与 `VERIFIED` 区分。
- `PetWorkFlowTest.kt` — 24 个：预览绝不点击、UNKNOWN 阻止在导航前、奖励变化阻止、
  未授权不点击、点击恰好一次、点击后未确认绝不重复、撤销/耗尽不可启动、精力不足/不可读阻止、
  倒计时归零但页面未结束不算成功、四级收益、页观与实测不一致两者都保留、重复验证只入账一次。

---

## 3. 需求逐条对照

### 3.1 状态机（15 态，5 个事件不合并）

`IDLE → NAVIGATING → WORK_SELECTED → PREVIEW_VERIFIED → AUTHORIZED → START_REQUESTED →
WORKING_CONFIRMED → WAITING → COMPLETION_CHECK → REWARD_VERIFIED → FINISHED`
外加 `BLOCKED` / `FAILED` / `INTERRUPTED` / `RECOVERY_REQUIRED`。

五个必须区分的事件各自独立成态，测试 `theFiveWorkEventsStaySeparatePhases` 断言集合大小为 5：

| 事件 | 阶段 |
|---|---|
| 已请求启动 | `START_REQUESTED` |
| 已确认工作开始 | `WORKING_CONFIRMED` |
| 预计结束 | `WAITING`（`expectedEndAtMs` 仅作预测） |
| 已确认工作结束 | `COMPLETION_CHECK` |
| 已确认奖励到账 | `REWARD_VERIFIED` |

每次转移必须带 reason（`everyTransitionCarriesAReason`）；幂等重入被允许（重复 verify 同一页
不算状态变更），非法转移抛 `IllegalStateException` 并带 `WORK_PHASE_ILLEGAL`/`WORK_PHASE_TERMINAL`。

### 3.2 Gate 3A 安全预览（不启动真实打工）

`preview()` 顺序：catalog 查找 → UNKNOWN 阻止（`BLOCKED`）→ `enterCareerTown` → `navigateToVenue`
→ `WORK_SELECTED` → `selectWorkOption`（点选卡片）→ **重新读奖励行** → `PREVIEW_VERIFIED`
→ `catalog.compare` → 差异阻止 → 返回 `WorkOutcome.Preview`。

- 预览成功后**保持 `PREVIEW_VERIFIED`**，不进入 `FINISHED`，让授权运行能继续；预览型调用方
  只需不再调用 `start()`。
- `aPreviewRunNeverTapsTheStartButton` 断言预览路径不触达 `tapStartWork`。
- **预览阶段没有任何代码路径能点到「去打工」**：`tapStartWork()` 只被 `start()` 调用，
  而 `start()` 第一件事就是检查授权。

### 3.3 Gate 3B 真实启动授权门禁

`WorkStartAuthorisation` 用**私有构造器 + 唯一 `grant()` 工厂**实现默认拒绝：

```kotlin
class WorkStartAuthorisation private constructor(...)
fun grant(venue, optionId, maxStarts in 1..3, maxCoinsSpent, maxEnergySpent, grantedBy, nowMs)
```

- 无 default 构造器、无 always-allow、无 release/refund API。
- `maxStarts in 1..3`：一次授权永远不能变成循环授权（`oneGrantNeverBecomesALoopGrant`）。
- 资源上限是硬顶，且**先 reserve 后动作、不退回**（`aSpendIsNeverReleased`）。
- `revoke(reason)` 后唯一出路是新的显式授权。
- **授权不可从导航权限推导**：`start()` 不参考任何导航结果。
- 无授权 → `WORK_NOT_AUTHORISED`，`anUnauthorisedStartNeverTaps` 断言未点击。

### 3.4 启动闩锁（点击后无法确认时绝不重复点击）

`WorkStartConfirmation.mustNotTapAgain` 一旦 tap 交付就**永久为真**，即使工作后来被确认或证伪：

```kotlin
val mustNotTapAgain: Boolean get() = state != StartState.IDLE
```

`start()` 中该检查位于 `grant.consume()` **之前**，所以即使授权还有余额也不会再点。
`markTapDelivered()` 不可重入（`WORK_START_ALREADY_RECORDED`），`markConfirmedWorking()`
必须已交付（`WORK_START_NOT_DELIVERED`）——闩锁不可跳过、不可重排。

### 3.5 计时与低频观察

- 确认开始后记录 `workStartedAtMs`（单调时钟），用**页面展示的实际时长**算 `expectedEndAtMs`。
- `awaitCompletionPage(expectedEnd, extraWindowMs)`：`expectedEnd` 只决定何时开始密切观察，
  `extraWindowMs`（默认 180s）兜住延迟结算。
- 等待期间不重复截图；`matched=false + TIMER_EXPIRED_PAGE_NOT_FINISHED` 一律 `FAILED`，
  **倒计时归零从不等于奖励到账**（`aCountdownReachingZeroWithoutACompletionPageIsNotASuccess`）。
- 启动后未确认 → `UnconfirmedStart`，走状态核查/安全停止，绝不重试点。

### 3.6 四级收益账本（不合并）

`buildRecords()` 产出，`rewardTolerance = 0`：

| 级别 | 来源 | 代码 |
|---|---|---|
| `EXPECTED_REWARD` | catalog | 仅当 confidence != UNKNOWN |
| `PAGE_READING` | 详情页重新读取 | `recordPageReading()` |
| `OBSERVED_DELTA` | 前后资源快照差 | 仅当金币与工分都可读 |
| `CONFIRMED_REWARD` | 页面读数与实测**完全一致** | `abs(diff) <= 0` |

不一致时**两者都保留**，不覆写（`aPageReadingThatDisagreesWithTheObservedDeltaKeepsBothRecords`：
页面 68、实测 40，两个 rung 都在，无 `CONFIRMED_REWARD`）。
精力读数不可读时不产生 `OBSERVED_DELTA`（`anUnreadableEnergyReadingBlocksTheStart` /
`anUnreadableResourceReadingIsNeverConvertedToANumber`）。
`WorkCatalog.compare()` 对 UNKNOWN 返回「未采集」差异而非视作匹配。

### 3.7 12 种异常

| # | 异常 | 落点 |
|---|---|---|
| 1 | 职业小镇导航失败 | `CAREER_TOWN_ENTRY_NOT_FOUND` / `CAREER_TOWN_PAGE_UNCONFIRMED` / `CAREER_VENUE_NAVIGATION_FAILED`（4 次尝试上限） |
| 2 | 目标职业未解锁 | catalog `find()` 返回 null → `BLOCKED` |
| 3 | 选项卡切换后奖励数据变化 | `catalog.compare` 差异 → `BLOCKED`（含页面值） |
| 4 | 奖励文字识别失败 | `unreadFields` 透传，`WORK_DURATION_UNREAD_BEFORE_START` |
| 5 | 工作无法开始 | `WORK_START_BUTTON_NOT_FOUND` / `WORKING_CONFIRM_WINDOW_EXPIRED` |
| 6 | 精力不足 | `WORK_ENERGY_INSUFFICIENT`（只查工作自身消耗） |
| 7 | 启动点击后未确认成功 | `WORK_START_UNCONFIRMED` + 闩锁禁重试 |
| 8 | 工作结束页面无法识别 | `TIMER_EXPIRED_PAGE_NOT_FINISHED` → `FAILED` |
| 9 | 奖励未领取/结算延迟 | `extraWindowMs` 等待 + 未匹配则 `FAILED` |
| 10 | 执行中断/App 重启 | `inputHalted` → `SESSION_INPUT_HALTED`；`RECOVERY_REQUIRED` 态 |
| 11 | 无法可靠获取资源读数 | `WORK_ENERGY_UNREADABLE`，**不因未知放行** |
| 12 | 用户主动暂停/停止 | `pauseRequested` → `INTERRUPTED`，不计失败、不入账 |

未知状态一律保守处理；无连续盲点（每个等待都有 deadline）、无无限重试（`MAX_VENUE_ATTEMPTS=4`）、
不重复发起实际工作（闩锁 + ledger 双重保证）、失败的工作不记录为成功。

### 3.8 边界 15 条

| 边界 | 实现 |
|---|---|
| 打工不要求体力/清洁达 90 | `start()` 只查 `catalogEntry.energyCost`，不调用 `PetStudyHealthPolicy` |
| 打工收益不随宠物属性变化 | 无任何基于属性的收益预测代码 |
| 不自动调用学习专用护理阈值 | `PetWorkFlow` 不引用 `PetStudyHealthPolicy` |
| 不修改现有稳定自动学习逻辑 | 只改 `SessionEvent.kt` 追加枚举，学习链路零改动 |
| 不为打工重开发导航/截图/计时 | 复用 `PetNavigation` tap 原语、`HeadlessDisplayCapture`、`PetRecoveryBoundary` |
| 不修改主力 APK / 不合并主力分支 | 未触碰构建产物与分支 |
| 不自动购买/充值/未知消费 | 无任何消费代码路径 |
| 不进行未授权真实工作测试 | 授权门禁默认拒绝（见 3.3） |
| 不把模拟回放冒充真机证据 | 本报告如实标注 Gate 3A/3C 未执行 |
| 不重复提交实际工作 | 闩锁 + `WORK_REWARD_ALREADY_RECORDED` |
| 不记录虚假金币/工分 | 只有 `OBSERVED_DELTA` 可入账，未知值不入账 |
| 不扩展无关玩法 | 仅职业小镇打工 |
| 不做不必要架构重构 | 复用 P5.1/P5.2 全部既有模型 |
| 不强行修复 P5.2 范围外既有测试问题 | 见 §5 |
| 收益未知的工作不参与收益最大化 | `preview()` 对 UNKNOWN 直接 `BLOCKED`；`WorkCatalog.compare` 返回差异而非匹配 |

---

## 4. 测试结果

```
BUILD SUCCESSFUL
244 tests completed, 0 failed, 0 skipped

com.ynk.virtualdisplay.PetWorkFlowTest          24 tests, 0 fail
com.ynk.virtualdisplay.PetWorkGateTest          25 tests, 0 fail
com.ynk.virtualdisplay.DialogDigitReaderTest     8 tests, 0 fail
（其余 15 个既有套件全部 0 fail）
```

编译警告仅剩 2 条既有噪音（`ExchangeBudget.kt` copy 可见性、`CaptureUserService.kt` 弃用 API），
均非本阶段引入。

### 测试顺序（3A → 3D）

- **3A 单元/隔离测试：PASS**（49 个新测试覆盖状态转换、导航失败、选项重新读取、奖励变化、
  授权失效、预算超限、重复启动防护、计时完成但页面未结束、重启后状态恢复、奖励重复入账防护）。
- **3B 真机只读预览：未执行**（需用户批准在设备上跑到「去打工」之前）。
- **3C 单次真实打工：未执行**（需用户明确批准工作地点、选项、次数与资源上限）。
- **3D 连续执行：未执行**（3C 通过前不允许）。

---

## 5. 既有失败单独记录（不改断言、不跳过）

### `DialogDigitReaderTest`

- 上一次记录的基线状态：`DIALOG_ROI_SIZE:74` 失败，已通过「把 P5.2 三个新文件临时移出镜像后
  单独跑该测试」证实与 P5.2 无关。
- **本次结果：8 个测试全部通过**（含此前失败的 `recorded frames replay with exact ledger ground truth`
  与 `reader rejects unexpected roi width`），每项均有真实执行耗时（0.001–0.016s），非跳过。
- 判定：**未复现（NOT REPRODUCED）**。本阶段未修改该测试、其 fixture 或其断言；fixture PNG
  SHA256 与记录一致（`0FFB9EBD6B2172E2758E097897E49761AB59E41D1E2F6FBCE5DA1D6354744F06`）。
  差异来自构建环境：本次使用全新 `GRADLE_USER_HOME=%TEMP%\QQPetMvpCacheP53` 并做了完整编译，
  此前那次是在缓存已损坏的 workspace 中跑的。
- **不宣称已修复**：没有证据表明是代码改动解决的，也不排除在另一台机器/另一种缓存状态下复现。

---

## 6. 数据可信度现状（打工收益）

| 工作 | 时长 | 金币/工分 | confidence | 说明 |
|---|---|---|---|---|
| 竹影武馆·帮武馆跑腿 | 10min | 68 / 10 | `VERIFIED` | 2026-10-12 19:18–19:28 真机闭环实测（金币 8→76、工分 +10、体力 80→75） |
| 竹影武馆·陪练基础课 | 45min | 278 / 54 | `PAGE_READING` | 仅页面读数；清洁消耗 OCR 不确定。**需一次真实闭环后才可提升** |
| 风铃旅社·替摊主看摊 | 10min | 40 / 10 | `PAGE_READING` | 仅页面读数 |
| 风铃旅社·街巷跑腿 | 45min | 未采集 | `UNKNOWN` | **不得外推为 0**；QQ 限流外部阻塞，待服务端恢复后补采 |

`buildRecords()` 只在「页面读数与实测完全一致」时才产出 `CONFIRMED_REWARD`，
所以 45min 选项的真实闭环（Gate 3C）正是把它从 `PAGE_READING` 提升的唯一途径。
**在 Gate 3C 之前，不得宣称 P5.3 真实执行闭环 PASS。**

---

## 7. 代码改动与回滚方式

### 7.1 改动清单（相对 `0d2fab0`）

新增：
```
app/src/main/java/com/ynk/virtualdisplay/automation/PetWorkModels.kt
app/src/main/java/com/ynk/virtualdisplay/automation/WorkStartGate.kt
app/src/main/java/com/ynk/virtualdisplay/automation/PetWorkStateMachine.kt
app/src/main/java/com/ynk/virtualdisplay/automation/WorkSessionLedger.kt
app/src/main/java/com/ynk/virtualdisplay/automation/WorkCatalog.kt
app/src/main/java/com/ynk/virtualdisplay/automation/WorkRuntimeSink.kt
app/src/main/java/com/ynk/virtualdisplay/automation/PetWorkNavigation.kt
app/src/main/java/com/ynk/virtualdisplay/automation/PetWorkFlow.kt
app/src/test/java/com/ynk/virtualdisplay/PetWorkGateTest.kt
app/src/test/java/com/ynk/virtualdisplay/PetWorkFlowTest.kt
```

修改：
```
app/src/main/java/com/ynk/virtualdisplay/automation/SessionEvent.kt   # 仅追加 7 个 EventKind
```

未触碰：`build.gradle.kts`、真实仓库的 `gradle.properties`、`PetStudyFlow.kt`、
`PetCareFlow.kt`、`PetNavigation.kt`、`PetProfile.kt`、`PetActivityModels.kt`、`PetActivityPlanner.kt`、
`DialogDigitReaderTest.kt` 及其 fixture。

### 7.2 回滚方式

远端 `github.com/Ynkcc/VirtualDisplay` 已归档只读（push 403），提交只存在于本地分支。
当前为**未提交工作区状态**，回滚即丢弃这批新文件：

```powershell
cd C:\Users\lu158\Desktop\dsh插件\养宠自动化\VirtualDisplay
# 查看将丢弃什么
git status --porcelain
# 回滚 P5.3（保留已提交的 P5.1/P5.2）
git checkout -- app/src/main/java/com/ynk/virtualdisplay/automation/SessionEvent.kt
git clean -fd app/src/main/java/com/ynk/virtualdisplay/automation/PetWork*.kt
git clean -fd app/src/main/java/com/ynk/virtualdisplay/automation/Work*.kt
git clean -fd app/src/test/java/com/ynk/virtualdisplay/PetWork*.kt
```

若已提交，则 `git revert <commit>` 或 `git reset --hard 0d2fab0`。
P5.1/P5.2 的代码与数据完全不受影响（`git log` 中 `0d2fab0`、`6a037c4` 保持不变）。

---

## 8. 下一步（需用户决定）

1. **Gate 3B / 3C 授权**：若批准真机测试，需要明确给出
   - 工作地点与工作选项（建议 `竹影武馆/帮武馆跑腿` 10min，唯一 `VERIFIED` 项）
   - 允许的实际执行次数（建议 1）
   - 可接受的资源消耗上限（精力 5 / 清洁 2 的硬顶）
2. **Gate 3A 只读预览**：即使不批准真实启动，也建议先在设备上跑一次只读预览以验证
   `WorkPageReader` 的真实实现（当前只有接口与 stub，OCR/模板实现尚未接真机）。
3. **待 QQ 服务端恢复**：补采 `风铃旅社/街巷跑腿` 45min 收益。

**在没有明确授权前，代码不会点击「去打工」。**
