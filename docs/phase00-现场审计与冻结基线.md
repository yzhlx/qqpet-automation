# Phase 00 · 现场审计与冻结基线

> **性质：只读取证报告。本阶段未修改任何代码、未重装 APK、未触碰 QQ 或虚拟屏。**
> 取证时间：2026-10-11　设备：FOA-AL00 / `DKS9K23420020242`
> 对应路线图阶段：**Phase 00 · M0 / 基线**

---

## 00-01　盘点 Git、工作树与远端备份

### 仓库定位（未凭历史路径猜测）
`git rev-parse --show-toplevel` →
`C:/Users/lu158/Desktop/dsh插件/养宠自动化/VirtualDisplay`

全盘扫描确认：工作区内**唯一** Git 仓库即此目录（`.git` 只出现一处）。

### 基线
| 项 | 值 |
|---|---|
| 当前分支 | `feature/qqpet-mvp-session` |
| HEAD | `97876c2bb5af15bc70d134ca99b3eb66d6675a19` |
| HEAD 说明 | `chore: checkpoint qqpet v0.1 before endurance validation` |
| 提交时间 | 2026-10-10 23:39:03 +0800 |
| 分支提交深度 | 52 |
| 其他分支 | `main` @ `0390d8f`（跟踪 `origin/main`，未合并） |

### 工作树状态
```
$ git status --porcelain=v1 | wc -l
85
```
| 分类 | 数量 | 说明 |
|---|---|---|
| **已跟踪文件被修改** | **0** | ✅ 无未提交的源码改动 |
| 未跟踪 `evidence/slice2` | 85 | 按 Phase A 决定**有意不提交**（调试脚本/XML dump） |

**关键结论：app 源码与 HEAD 完全一致（`git status -- app` 为 0 行）。**

### 远端
| remote | 地址 | 用途 |
|---|---|---|
| `backup` | `https://github.com/yzhlx/qqpet-automation.git` | **本阶段备份目标** |
| `origin` | `https://github.com/Ynkcc/VirtualDisplay.git` | 另一个仓库，**未触碰** |

### 备份证明（独立于 `git push` 返回码）
```
$ git ls-remote backup
97876c2bb5af15bc70d134ca99b3eb66d6675a19  HEAD
97876c2bb5af15bc70d134ca99b3eb66d6675a19  refs/heads/feature/qqpet-mvp-session
8887293f4bfd0c5fe8c5f2c31c238ae40c6c2ed2  refs/tags/qqpet-v0.1-pre-endurance-20261010
97876c2bb5af15bc70d134ca99b3eb66d6675a19  refs/tags/qqpet-v0.1-pre-endurance-20261010^{}
```
- 本地 HEAD == 远端分支 HEAD ✅
- 附注 tag `qqpet-v0.1-pre-endurance-20261010` 解引用 → `97876c2` == HEAD ✅
- 相对 `backup` 的未推送提交数：**0**（本地 HEAD 已完整存在于私有远端）
- 仓库可见性：**private**（`gh api` 认证核验）

> 说明：分支领先 `origin/main` 14 个提交。`origin` 是**另一个仓库**（`Ynkcc/VirtualDisplay`），
> 不属本阶段备份目标，本阶段未向其推送、也未做任何合并。这不影响基线可信性。

### 结论
**唯一可信基线 = `97876c2` @ `feature/qqpet-mvp-session`**，本地与私有远端一致，可回退。

---

## 00-02　对齐源码、构建产物和真机安装版本

### 三方对齐（这是"不再用旧 APK 冒充验收对象"的核心证据）

| 对象 | 标识 |
|---|---|
| **源码** | `97876c2`，`git status -- app` = 0 行改动 |
| **APK 工件** | `deliverables/qqpet-mvp-debug.apk`，27,729,763 B，2026-10-10 23:11:54 |
| **APK SHA256** | `841fe9f1864f07ec2d8eab931e0bb86af9e0220a2890272799ebdc9aa5c18254` |
| **设备已安装** | `versionName=1.0.51` / `versionCode=51`，`targetSdk=35` |
| **设备 APK SHA256** | `841fe9f1864f07ec2d8eab931e0bb86af9e0220a2890272799ebdc9aa5c18254` |

### 结论：**哈希完全相同 → 设备上运行的正是本轮验收对象。**

安装时间线：`firstInstallTime=2026-10-05 13:48:25`，`lastUpdateTime=2026-10-10 23:12:02`。

### 工件区分（排除"旧版定位器 APK"）
`deliverables/` 下共 **10 个 APK**，仅 1 个与设备一致：

| APK | 判定 |
|---|---|
| `qqpet-mvp-debug.apk` | ✅ **== 设备当前版本** |
| `qqpet-mvp-gates.apk` | 旧（gates 变体，非本轮对象） |
| `baseline-*` (2) | 旧 |
| `frozen-slice1-*` (2) | 旧 |
| `qqpet-managed-*` (2) | 旧 |
| `qqpet-recovery-*` (2) | 旧 |

> 风险提示：9 个历史 APK 仍在同一目录，无版本标注，容易误用。见 00-04 台账 R-03。

### 构建一致性
APK 构建于 23:11:54，提交于 23:39:03。检查 `app/src` 下是否有关键文件晚于 APK：
**无（NONE）** → 提交只是把已验证源码入库，未产生新构建差异。

---

## 00-03　取证已实现能力与未覆盖流程

### 能力-证据矩阵

| 能力 | 代码位置 | 自动化测试 | 真机证据 | 状态 |
|---|---|---|---|---|
| 基础护理（喂食） | `PetCareFlow.kt`（`ConsumableReservation`/`CareConsumableBudget`） | `PetCareBudgetTest` ✅ | `feed_count=1`，19766 ms | **已测（1 次）** |
| 基础护理（清洁） | 同上 | `PetCareBudgetTest` ✅ | `clean_count=1`，42765 ms | **已测（1 次）** |
| 开课前自动护理并越过阈值 | `PetStudyHealthPolicy.kt` | `PetStudyHealthPolicyTest` ✅ | 87/88 → **97/100**，真实跨过 90/90 门禁 | **已测** |
| 自主学习全链路 | `PetStudyFlow.kt` / `PetNavigation.kt`(1329行) | — | `course_ms=603377`，进入 12962 ms | **已测（1 次）** |
| 成绩确认 | `PetNavigation.kt` | — | `result_wait_ms=533318`，`grade=UNKNOWN` | **部分：等待已测，等级不可读** |
| 会话终态与落盘 | `PetSessionController.kt` | `PetManagedRuntimeTest` ✅ | `terminalState=COMPLETED`，`runtimeMs=727381` | **已测** |
| 运行状态展示 | `PetRuntimePresentation.kt` | `PetRuntimePresentationTest` ✅ | 首页/记录页截图 | **已测** |
| 运行日志与折叠 | `PetLogPresentation.kt` | `PetLogTimelineTest`（7 项）✅ | 读取 1200 行 → 显示 75 行 | **已测** |
| 记录页与截图回看 | `PetEvidence.kt` / `RecordsPresentation.kt` | `PetHomePresentationTest` ✅ | 68 张 PNG 可回看 | **已测** |
| **手动暂停 / 恢复** | **不存在** | — | `pauseCount=0` | **❌ 缺失（P0）** |
| **运行中停止** | `PetAutomationService.kt:49` `ACTION_STOP` | — | **未实测点击** | **⚠️ 待测（P0）** |
| 自动暂停→恢复 | `SessionRecovery.kt` | `SessionRecoveryTest` ✅ | `pauseCount=0`（未触发） | **⚠️ 未触发（P1）** |
| 连续 2–3 节 | `PetSessionController` `totalRounds 1..3` | — | 仅跑过 1 节 | **⚠️ 待测（P1）** |
| 异常页面安全停止 | `PetRecoveryBoundary.kt` | `PetManagedReconcileTest` ✅ | 历史有 `PAGE_MISMATCH` 记录 | **部分：有边界，样本有限** |
| 力量/智力/魅力/金币/库存 | 无读取器 | — | 界面显示"不支持读取" | **明确不支持** |

### 真机 session 明细
- **会话目录**：`/storage/emulated/0/Android/data/com.ynk.virtualdisplay/files/pet_mvp_session/1791643364521/`
- `sessionId`：`fe4be64d-66b3-44c0-b4fe-a300cdbc9770`
- 文件 72 个（68 张 PNG）
- 起止：`startedAt=1791643364551`，`runtimeMs=727381`（12 分 07 秒）
- 终态：`COMPLETED`，`completedRounds=1/1`
- 健康：energy 92 / cleanliness 98 / mood 21，均 `KNOWN`，confidence 1.0，来源 `DIGIT_RECOGNITION`

### 单元测试
`testDebugUnitTest`：**165 项，0 失败，0 错误**（报告时间 2026-10-10 23:10:56）。

> ⚠️ **单元测试 ≠ 真机 E2E。** 上表"已测"仅指该单轮真机运行留下的可追溯证据，**不代表多轮回归通过**。
> 路线图 Phase 01 要求"不少于 10 轮"，当前仅 1 轮 —— 这是 M1 的主要缺口。

---

## 00-04　冻结 v0.1 功能和风险边界

### v0.1 受控范围（scope-v0.1）
**纳入（P0）**
1. 基础护理：喂食 + 清洁，含消耗品预算保护（cookie 5 / soap 3）
2. 自主学习：进学院 → 选课 → 上课 → 等待结果 → 课后读状态
3. 开课前自动护理：体力/清洁 < 90 时先护理，再上课
4. 安全停止：健康数据过期（>60 s）拒绝授权；未知页面有限重试后停止
5. 状态与记录：首页三状态、当前任务卡、运行日志、历史记录
6. 单一设置入口

**明确排除（不得挤占 P0）**
- 金币 / 背包全量系统、收益面板
- 社交 / PK / 打工 / 冒险 / 福袋
- 个性化推荐、AI 选课
- 商业化、广告、收费
- 后台虚拟屏架构重写

### P0 / P1 台账

| ID | 级别 | 问题 | 证据 | 影响 |
|---|---|---|---|---|
| R-01 | **P0** | **无手动暂停/恢复 API**。`PetAutomationService` 仅 `ACTION_START`/`ACTION_STOP`；界面只能弹"暂未开放" | 代码 + `pauseCount=0` | 违反路线图 02-05；用户无法暂停 |
| R-02 | **P0** | 运行中「停止托管」从未实测 | 无停止 session 记录 | 停止能力未经证明，阻塞进入长时无人值守 |
| R-03 | **P0** | `deliverables/` 混放 10 个历史 APK，仅 1 个是当前版本，无版本标注 | 哈希比对 | 极易拿错验收对象 |
| R-04 | P1 | 仅 1 轮真机闭环，Phase 01 要求 ≥10 轮 | 1 个 session 目录 | 成功率/稳定性无统计意义 |
| R-05 | P1 | 自动暂停→恢复链路从未触发 | `pauseCount=0` | 恢复逻辑只有单测，无真机证据 |
| R-06 | P1 | 连续 2–3 节未验证 | 仅 1 节 | 跨节状态复位未证明 |
| R-07 | P1 | 证据无保留策略：累积 **969 MB** / 12 个会话 | `du -sh` | 长期运行会耗尽存储 |
| R-08 | P1 | `pet_mvp.log` 无轮转，已 2.67 MB | `run-as ls -l` | 持续增长 |
| R-09 | P2 | 力量/智力/魅力/金币/库存无读取器 | 界面"不支持读取" | 已如实标注，非遗漏 |
| R-10 | P2 | `SettingsScreen.kt`(277 行) 为死代码，Tab 不可达 | 无引用 | 维护噪音 |

### 不可改事项（冻结）
- 不改自动化业务逻辑与安全门禁
- 不降低 60 s 健康数据新鲜度校验
- 不容忍把 Unknown/Stale 伪装为正常数值
- 不为无数据源的功能补假占位
- 不重装 APK / 不触碰设备（未经批准）

---

## 00-05　Agent 交付与人工审批契约

### 交付模板（每轮必须包含 6 项）
1. **基线**：分支 / HEAD / 工作区状态 / APK 哈希 / 设备
2. **范围**：本轮任务 ID、修改文件、未触碰事项
3. **证据**：命令与结果、sessionId、日志/文件路径
4. **指标**：轮次、耗时、识别次数、异常恢复（适用时）
5. **结论**：PASS / FAIL / BLOCKED + 推测与待测标记
6. **等待审批**：不得自行判定用户已批准

### 证据存储位置（约定）
| 类型 | 位置 |
|---|---|
| 代码 | Git `97876c2`（tag `qqpet-v0.1-pre-endurance-20261010`） |
| 构建产物 | `VirtualDisplay/deliverables/*.apk` |
| 真机会话证据 | 设备 `.../files/pet_mvp_session/<sessionId>/` |
| 运行日志 | 设备私有 `files/pet_mvp.log` |
| 报告 | `VirtualDisplay/docs/v0.1-*.md`（脱敏） |

### 审批责任边界
- **DSH 只能声明"已完成并提交证据"，不能标记"用户已批准"**
- 路线图的 `② 我已核验并审批` 仅由用户勾选
- 证据不足 12 字时前端会自动撤销审批（路线图内置逻辑）
- 阶段推进需用户明确授权，不得自动进入下一阶段

---

## 阶段门禁自检

| 门禁项 | 状态 |
|---|---|
| 有可回退 Git 基线 | ✅ `97876c2` + tag，本地/远端一致 |
| 源码 ↔ APK ↔ 设备版本一一对应 | ✅ SHA256 完全相同 |
| 形成缺陷台账 | ✅ R-01 … R-10 |
| 形成 v0.1 受控范围 | ✅ scope-v0.1 已冻结 |

**Phase 00 门禁：技术上已满足。** 但 **R-02（停止未实测）** 是硬约束 —— 约束 8 规定"停止能力未通过不得进入无人值守阶段"，因此 Phase 01 的首要动作应是**真机停止验证**。
