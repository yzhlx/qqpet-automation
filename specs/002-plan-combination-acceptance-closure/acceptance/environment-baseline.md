# 环境与设备基线（只读）

采集时间：工作站 2026-10-23 13:15 -04:00 / **设备 2026-10-24T01:15:54+0800（已跨日到 10-24）**
本文件全部为**只读采集**，未构建、未安装、未启动任何 run、未发送任何游戏输入。业务时间一律取设备 `date` 实读，不由 host 文件名推断。

## T001 设备与应用

| 项 | 实测值 |
| --- | --- |
| adb | `DKS9K23420020242 device`（唯一在线），`android_devices` 亦确认 state=device |
| 机型 / 系统 | FOA-AL00（FOA AL00）、Android 12、sdk 31 |
| 设备时间 | `2026-10-24T01:15:54+0800`（Asia/Shanghai） |
| 应用 | `com.ynk.virtualdisplay` 1.0.60 / versionCode 60，running=true（pid 15484） |
| 应用安装 | firstInstall 2026-10-15 14:43:31，lastUpdate **2026-10-23 10:01:36**（healthJson 修复包 `45f0298e…b1ec`） |
| min/target SDK | 29 / 35 |
| Shizuku | `moe.shizuku.privileged.api` **13.6.0.r1086.2650830c**；`shizuku_server` PID 31413 在跑 |
| 虚拟屏 | **display 6** `Shizuku_VD_1792715713750`，720×1600、density 320、type VIRTUAL、owner com.android.shell；物理屏 display 0 = 1084×2412 density 480 |
| display 6 截图 | PNG 853011 字节，魔数校验通过 |

## T001 游戏画面（display 6，房间）

`-d 6` screencap 实读：宠物在**房间**（床/地毯/喂食/洗澡/好友/出门入口齐备），顶栏读数：

- **金币 60**（金币图标行）
- 爪印 15、星 5（顶栏另两枚计数，未据此推断健康值——房间顶栏不直接给体力/清洁数字）
- 称号徽章「习武小童」

> 判定 WS/WCS 经济前置用：**金币 60 < 学费 66**，因此含学习且不含工作的方案会在预检处以 `COURSE_COINS_INSUFFICIENT` 停止（正确行为）；WS/WCS 必须**同 run 内靠打工一班（工资 68）筹资**，不外部补币、不充值。

## T001 课表与岗位

`files/course-catalog.json`：`complete=true`、`scannedAtMs=1792486447549`（2026-10-20 04:54:07），距设备当前时间约 **3.8 天 < 7 天**，`isUsable` 满足；7 门全部 `unlocked=true`：

| 课程 id | 时长 |
| --- | --- |
| `study:蹦蹦跳跳体能课` | 10 |
| `study:看图识世界课` | 10 |
| `study:闪亮自我表达课` | 10 |
| `study:田径运动课` | 30 |
| `study:世界地理课` | 30 |
| `study:演说表达课` | 30 |
| `study:萌芽夏令营` | 30 |

`files/session-plan.json`：`courseId=study:田径运动课`、`rounds=1`、阈值 100/100、`dailyBudgetMinutes=480`、`workBeforeStudy=true`、`workOptionId=work:竹影武馆/帮武馆跑腿`。

已验证岗位（`WorkCatalog.kt` 中唯一 `RewardConfidence.VERIFIED`）：

- `work:竹影武馆/帮武馆跑腿`：10 分钟、体力 5、清洁 2、入场费 `entryFeeCoins=0`（证据 `2026-10-21 run13711e66: 消耗行仅体力5/清洁2，单次开工与10分钟工资68已确认`）

其余条目为 `PAGE_READING` / `UNKNOWN`，不可用于方案（`PlanValidator` 会以 `PLAN_WORK_OPTION_NOT_VERIFIED` 拒绝）。

## T002 既有结论复核（未被推翻）

`files/plan-runs/` 现状：仅 1 个 run + `finalizations/` 目录。

run `b5600c4f-f334-4013-9db8-f95d5fc4cb0a`（只读解析，`tools/diagnostics/read_plan_runs.py`）：

| 字段 | 实测 |
| --- | --- |
| `state` | **COMPLETED** |
| `stopReason` | null |
| `counts` | `workShiftsCompleted=1, careCompleted=1, studyRoundsCompleted=0` |
| steps | WORK=COMPLETED、CARE=COMPLETED |
| attempts | 2 条：`…:work:1` 10 分钟 COMPLETED、`…:care:1` 0 分钟 COMPLETED，`inputPending` 均 false |
| `request.origin` / `displayId` / `executionProfile` | PLAN / **6** / SELECTED_ACTIONS |

**该 COMPLETED 是收尾结果，不是原生同 run COMPLETED**：`files/plan-runs/finalizations/b5600c4f-…/` 存在 `finalization.json` 与 `original-run.json`，即由 `CommittedPlanFinalizer` 对已提交 attempt 记账收尾（`FINALIZE_ALREADY_COMMITTED_NON_STUDY`）。审计对照见 `VirtualDisplay/.build-tmp/device-acceptance/continuation-20261022-211328-845483-plan-work-care-recovered/`（legacy run-record FAILED/HEALTH_STALE vs persisted COMPLETED）。

## T004 run 清单与准入门禁（只读工具输出）

`tools/diagnostics/read_plan_runs.py`：

```json
{ "runCount": 1,
  "blockingRunIds": [],
  "blockingRunId": null,
  "decodeErrors": [],
  "admissionBlockedByPlanRuns": false }
```

`tools/diagnostics/read_budget_ledger.py --show-attempts`（权威 `files/pet-minute-budget-v2.bin`，7736 字节）：

- `day` = `2026-10-22`、`legacyUsedMinutes` = **100**、`legacyByDay` = {}、`attemptCount` = **10**、`usedMinutesToday` = 100
- **`unresolvedAttemptIds` = []**（10 条 attempt 全部 ∈ {COMPLETED, RECONCILED_STOPPED}）
- 明细：7 条 `2026-10-22` 的 0 分钟 care → **`RECONCILED_STOPPED`**；`f608ec60…:care:1` → `COMPLETED`；`b5600c4f…` 的 work(10) 与 care(0) → `COMPLETED`（day 2026-10-23）

## 准入门禁结论

`PetAutomationService.PendingGate` 的两道检查现状：

1. 遗留 CARE pending（优先）→ **无命中**；
2. v2 非终态/pending run → **无阻塞**（唯一 run 已 COMPLETED 且无 pending input）。

⇒ **准入未被阻塞**，可直接发起新的方案 run；research R1 记录的阻塞已被此前会话的观察任务解除（证据见 [legacy-pending-inventory.md](legacy-pending-inventory.md)）。

## 本轮环境风险（只读观察，未处置）

- 设备已跨日到 **2026-10-24**，而账本存储的 `day` 仍是 `2026-10-22`：跑 WS/WCS 前需确认按日滚动的记账行为（`PlanBudgetLedger`/`DailyBudgetStore`），并把「开工前当日已用分钟」如实记入每个 run 的前后对照，不得沿用 10-22 的 100 分钟当今日数。
- 本机 OCR 后端不可用（无 Tesseract、非 macOS Vision）：`android_find_text` / `android_tap_text` / `android_wait_for` 不可用，读屏改用 `android_ui_tree`（物理屏可识别控件）+ `-d 6` 截图直读图像。这会影响 US4 的「结果 PNG 实证」——仍需截图，但无法用 OCR 断言文本，需人工读图并如实记录读数方式。

## 2026-10-24 11:29–11:31 EDT 实时复核（安装前）

本节以设备实读时间为准，覆盖本文件较早快照中的“当前”措辞；均为只读命令与截图，未启动 run、未发送游戏输入。

- 设备：`DKS9K23420020242` / FOA-AL00 / Android 12；Shizuku `moe.shizuku.privileged.api` 13.6.0.r1086.2650830c，`shizuku_server` PID 31413 在跑。
- 应用安装前：`com.ynk.virtualdisplay` 1.0.60 / versionCode 60、dataDir `/data/user/0/com.ynk.virtualdisplay`、running=true；`firstInstallTime=2026-10-15 14:43:31`，`lastUpdateTime=2026-10-24 13:20:40`。之后的候选覆盖安装另记于 [build-regression.md](build-regression.md)。
- `dumpsys display` 实读：物理 display 0 为 1084×2412@480；当前 Shizuku 虚拟 display **6**、720×1600@320、type=VIRTUAL、owner=com.android.shell，名称 `Shizuku_VD_1792715713750`。原图本地仅存于 `VirtualDisplay/.build-tmp/device-acceptance/continuation-20261024-112902-687931-environment-baseline/display-6.png`。
- display 6 房间截图读数：金币 **130**、爪印 15、星 5；截图没有可确认的体力/清洁数值，故二者为 Unknown。
- `files/course-catalog.json`：`complete=true`、`scannedAtMs=1792486447549`；按设备 `2026-10-24T23:31:31+0800` 计算年龄约 4.28 天，`isUsable=true`；7 门课程均 `unlocked=true`，时长为 10/30 分钟。已验证可选岗位仍为 `work:竹影武馆/帮武馆跑腿`（WorkCatalog 中唯一 VERIFIED 项）。
- 当前 `files/plan-v2.json` 为保存的 CS 配置：CARE(energy/cleanliness 100/100) → STUDY(`study:田径运动课`, 1 节)，daily budget 480，`SELECTED_ACTIONS`。在 D1 写回观察完成后可按此配置另行发起 CS run。
- 实时 run 只读清单：`b5600c4f-f334-4013-9db8-f95d5fc4cb0a` 与 `e978be5e-d68d-43f0-8b9b-faa0414e93ee` 共 2 条终态，无 decode errors，`blockingRunId=null`；e978 的同 run 判定为 `PLAN_PASS / COMPLETED / W1-C0-S1`，工具回归 bundle 见 [README.md](README.md)。
- 新鲜权威账本：`day=2026-10-22`、`legacyUsedMinutes=100`、`attemptCount=13`、`usedMinutesOnDeviceDay=50`；7 条遗留 CARE 为 0 分钟 `RECONCILED_STOPPED`。唯一未解决项是 `36:d5bf86e8-9c57-4369-b311-f758c94023ca4:work:1`，10 分钟 `UNCONFIRMED_RETAINED`；写回前快照在 `VirtualDisplay/.build-tmp/budget-before-work-writeback.json`。CARE 清算与 PLAN run 门禁无阻塞，但 D1 独立 WORK 写回观察尚待执行，不能把此时状态写成“全局 pending 已清空”。
