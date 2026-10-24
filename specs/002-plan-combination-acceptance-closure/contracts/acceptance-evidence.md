# Contract: 验收证据（Acceptance Evidence）

**Feature**: `specs/002-plan-combination-acceptance-closure` | **Date**: 2026-10-23

本契约规定**什么才算证据**、**证据必须含哪些字段**、**如何判通过/不通过/未定**。它是 G1（同 run）与 G4（可复核）的执行细则；任何与本契约冲突的口头结论一律无效。

---

## 1. 同 run 三源一致性（唯一判据）

一次组合验收**通过**，当且仅当以下三个来源属于**同一个 runId**且互相一致：

| 源 | 路径 | 读取内容 |
| --- | --- | --- |
| A. 权威运行快照 | `files/plan-runs/<runId>.json`（`StoredRun`） | `snapshot.state`、`snapshot.stopReason`、`snapshot.steps[]`（type/state/reason）、`snapshot.counts`、`attempts[]`（state/durationMinutes/inputPending/三个时间戳） |
| B. 会话判定 | `pet_mvp_session/<ts>/verdict.json` + `run-record.json` | `verdict`、`planRunState`、`completed/requested`、`workCompleted`、`careCompleted`、`stopReason`；run-record 的 `terminalState`、`courseStarts`、`care.feed_count`/`clean_count`、`energy`/`cleanliness` |
| C. 权威分钟账本 | `files/pet-minute-budget-v2.bin` | 该 run 各 attempt 的 `day`/`minutes`/`status`，以及当日总量前后差 |

**同键保证**：`runId == 事件流 sessionId`，故 `pet-events/<runId>.jsonl`、`pet-checkpoints/<runId>.json`、`plan-runs/<runId>.json` 可直接交叉核对（见 [device-run.md](device-run.md) §4）。

### 一致性检查清单

- [ ] A.state == `COMPLETED` 且 A.stopReason == `null`
- [ ] B.verdict == `PLAN_PASS` 且 B.planRunState == `COMPLETED`
- [ ] A.counts 与方案声明相符（W/C/S 各等于所选步骤的 shifts / 1 / rounds）
- [ ] A.counts 与 A.attempts 中 `state==COMPLETED` 的条目**逐类数目一致**（计数来自 persisted attempts，不是 step 状态）
- [ ] 所有 A.attempts 的 `inputPending == false`
- [ ] CARE attempt 的 `durationMinutes == 0`；WORK/STUDY attempt `> 0`
- [ ] C 中该 run 的 attempt 状态为 `COMPLETED`，当日总量增量 == Σ(WORK/STUDY 分钟)
- [ ] 未选动作：A.steps 不含该类型，且事件流中无归属该动作的 `INPUT_SENT`
- [ ] B.run-record 的 `care.feed_count`/`clean_count`/`courseStarts` 与所选动作一致（未选护理 ⇒ 0/0；未选学习 ⇒ courseStarts 0）

### 明确禁止的拼接

- ❌ 用独立 WORK 的 run + 独立 STUDY 的 run 拼成「WS 通过」。
- ❌ 用另起一班打工补币后跑通的 STUDY 当作「会话内按需打工闭环」（FUND-LOW）。
- ❌ 用跨 run 的金币/分钟变化当作组合的资源轨迹（TRAJECTORY）。
- ❌ 用 `CommittedPlanFinalizer` 收尾得到的 COMPLETED 当作**含 STUDY**组合的原生通过（模型层禁止：`FINALIZATION_STUDY_OR_LEGACY_FORBIDDEN`）。
- ❌ 用服务层 `SESSION_STATE{state:"STOPPED"}` 判失败（成功也发 STOPPED，research R3），或用它判成功。
- ❌ 用旧成绩单 PNG 补判本次完成（grade 一律记 UNKNOWN，除非同 attempt 归属被实证）。

---

## 2. 通过 / 不通过 / 未定 的口径

| 判定 | 条件 |
| --- | --- |
| **通过** | §1 清单全部满足，且证据 bundle 已归档、字段齐备（§3） |
| **不通过** | 有同 run 证据表明违反规则：误执行未选动作、计数与 attempts 不一致、重复扣分钟、失败后仍执行后项、非法跳过理由、未确认输入被重放、护理花了游戏分钟等 |
| **未定** | 未取得同 run 证据；或 run 停在保守门禁（`NeedsObservation`、`Unknown` 读数、pending 阻塞、经济前置不足）而**该行为本身正确**——此时记「保守停止符合预期，正向闭环未定」，不得写成通过，也不得写成应用缺陷 |

> **保守停止 ≠ 缺陷**：`CARE_SUFFIX_EXACT_INVENTORY_AND_COST_REQUIRED`（无精确库存）、`CARE_INVENTORY_UNCONFIRMED`、`CARE_WALLET_UNCONFIRMED`、`COURSE_COINS_INSUFFICIENT`、`DAILY_BUDGET_LIMIT`、`PENDING_RUN_UNRESOLVED` 都是 fail-closed 的**正确**行为。验收记录必须写明触发它的实际读数。

---

## 3. 证据 bundle 必备内容

由 `tools/diagnostics/capture_acceptance_state.py --run-id <id> --display-id <n> --label <combo>` 归档到
`VirtualDisplay/.build-tmp/device-acceptance/continuation-<UTC时间戳>-<label>/`：

**现有采集项**（脚本已实现）：
`events.jsonl`（原始事件流）、`pet_mvp.log`、`budget.xml`（legacy，仅对照）、`checkpoint.json`、`pet-minute-budget-v2.bin`（**权威**，魔数 `\x00\x0cQQPET_BUDGET` 校验）、`display-0.png` + `display-<n>.png`（PNG 魔数校验）、`activities.txt`、`power.txt`、`verdict.json`、`run-record.json`、`plan-run.json`、`plan-care.json`、`persisted-plan-run.json`、`summary.json`。

`summary.json` 含：`runId`、`displayId`、`deviceTime`（设备 `date` 实读）、`budgetAuthority`、`eventCount`、`inputCount`（`INPUT_SENT` 计数）、`lastStates`（末 8 条 SESSION_STATE）、`missingOptionalFiles`。

**Step 1 待补采集项**（research R4，只读扩展）：
- `plan-round-<i>-completion.json`（i = 1..rounds；含 STUDY 的组合逐节完成证据）
- `files/plan-v2.json`（方案本体，SAVE-7 回读对照）

**采集纪律**：`save_json` 先 `json.loads` 校验再落盘，`adb exec-out` 把远端 cat 错误混进 stdout 时记入 `missingOptionalFiles` 而**不产出伪 JSON**；`--display-id` 必传（脚本拒绝 `display <= 0`，`DISPLAY_ID_REQUIRED`）。原始画面只留本地，不外传。

---

## 4. 每条验收记录的必备字段

写入 `specs/001-managed-study-recovery/acceptance/results.md`（矩阵行）与对应明细文件时，每行至少含：

1. **场景 ID**（WS / CS / WCS / FUND-LOW / FUND-SKIP / FUND-CARE-GAP / SAVE-7 / LEGACY / …）
2. **runId**（= sessionId）
3. **构建身份**：APK 文件名 + SHA-256 + 证书指纹 `a63a2254…cf77` + versionCode
4. **所选配置**：勾选的动作、岗位、课程与节数、护理阈值、每日上限、WORK trigger
5. **执行前状态**：金币、体力/清洁（或 Unknown）、当日已用分钟、库存观察（Known/Unknown 及来源）
6. **计数**：`INPUT_SENT` 次数、courseStarts、feed_count/clean_count、counts(W/C/S)
7. **执行后状态**：金币、体力/清洁、当日分钟
8. **终态**：A.state / B.verdict / B.planRunState / stopReason
9. **资源证据**：工资 award（正数、同工资页）、护理购买逐笔钱包与 tuitionReserve、学费与余额
10. **bundle 路径**
11. **判定**：通过 / 不通过 / 未定 + 一句理由
12. **设备时间**（Asia/Shanghai，取自 `summary.json.deviceTime`，**不由 host 文件名推断**）

**写法禁令**：Unknown 不写 0；预测工资不当余额；已派发鼓励（`high_five_sent_count`）不当业务成功；`SENT_UNCONFIRMED` / grade `UNKNOWN` 必须如实保留；host 侧文件名里的日期不作为设备业务日期。

---

## 5. 场景专项判据

### WS（打工→学习）
同 run 内：`work-reward-payroll-award` 正 award → 工资页返回（`PAYROLL_RETURNED_*`，非「继续打工」）→ 回房 → 选课 → 一次开课 → 完成登记 1/1；counts W1/C0/S1；`care.feed_count == 0 && clean_count == 0`；分钟增量 == 工作分钟 + 课程分钟。经济前置：金币 < 学费时**必须**靠本班工资补足，不得外部补币。

### CS（护理→学习）
counts W0/C1/S1；护理达标（`plan-care-ready` 双值 ≥ 阈值）或 `ALREADY_SATISFIED` 零消费；若发生购买，逐笔钱包读数与 `tuitionReserve` 可见且购买后仍保留全部未付学费；工作类 `INPUT_SENT` 为 0。

### WCS（三步）
counts W1/C1/S1；`snapshot.steps` 顺序为 WORK→CARE→STUDY 且状态依次 COMPLETED/SKIPPED；三源一致。

### FUND-LOW
WORK step `trigger==IF_FUNDS_SHORT` 且**真实执行**（非 SKIPPED）：同 run 内 balance < tuitionReserve → 一班 → 正 award → 后续步骤完成。`planSkipWork` 判定与实际 dispatch 必须一致。

### FUND-SKIP
WORK step `state==SKIPPED` 且 `reason=="FUNDS_SUFFICIENT"`；`plan-work-skipped` 事件含 `tuitionReserve`；该 run 工作相关 `INPUT_SENT == 0`；C 中无 work attempt 或分钟不增。

### FUND-CARE-GAP
学费足够但护理需购买且现金不足：记录实际触发的门禁（`NeedsObservation("CARE_SUFFIX_EXACT_INVENTORY_AND_COST_REQUIRED")` 或购买前的 `CARE_WALLET_UNCONFIRMED` 等）与当时的真实读数；判定「决策与 dispatch 一致」即通过该行的**保守语义**，正向闭环另记未定。

### SAVE-7
七种非空配置逐个：保存 → 杀进程/重开 → `files/plan-v2.json` 与 UI 展示逐项一致（动作集合、岗位、课程、节数、阈值、上限、trigger、name）；**重开不得产生任何 run**（`plan-runs` 无新文件、无 `INPUT_SENT`）。S3 隔离测试另覆盖损坏/未知版本「保留原文并拒绝执行」。

### LEGACY / LEGACY-DIFF
按 research R2 的裁决记录**实际**语义：UI 保存恒为 `SELECTED_ACTIONS`、`LEGACY_MANAGED` 方案在 `toSessionPlan` 处抛 `LEGACY_PROFILE_REQUIRES_LEGACY_ADAPTER`、兼容提示文案为「护理执行一次；打工前需已满足岗位条件，后置护理不会提前执行」。**不得**声称 001 FR-014 的迁移保真已达成。

### US4（RESULT-OWNER）
保持未接线。仅当一次成功 run 的结果 PNG 实证打印时刻语义后，才评估把 `StudyCompletionGate` 接入 `PetStudyFlow.waitResult`；在此之前该行记「部分：新 S 完成及旧成绩未计新 run；完整归属机制未定」。

---

## 6. 与既有文档的关系

- 矩阵与 SC 总表在 `specs/001-managed-study-recovery/acceptance/results.md`；本轮**修正** WC 行（标注为 finalized COMPLETED，附 `continuation-20261022-211328-845483-plan-work-care-recovered` 审计 bundle），并新增 WS/CS/WCS/FUND-\*/SAVE-7 行。
- 明细沿用既有文件命名：`us1-independent.md`、`us1-care.md`、`us1-study.md`、`us2-combinations.md`、`us2-persistence.md`、`us3-funding.md`、`us3-legacy.md`、`us5-safety.md`、`us5-recovery.md`、`build-guide.md`、`baseline.md`。
- 构建可复现性记入 `build-guide.md`（ASCII 联接、GRADLE_USER_HOME、aidl 还原、keystore 指纹、`run_tests.py` 入口与 `EXIT 0`）。
