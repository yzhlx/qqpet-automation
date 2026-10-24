# US2 组合验收明细（WS / CS / WCS）

**Feature**: `specs/002-plan-combination-acceptance-closure`（US1 组） | 判定口径见 [contracts/acceptance-evidence.md](../../002-plan-combination-acceptance-closure/contracts/acceptance-evidence.md)
矩阵汇总在 [results.md](results.md)。本文件只记**同 run** 证据，不拼接跨 run。

---

## WS（打工 → 学习）

### T015 配置快照（保存后只读回读，2026-10-24 02:14 设备时间）

`files/plan-v2.json`：

```json
{"schemaVersion":2,"planId":"fd55224d-92d1-408a-afc2-3ed631e2ca65","name":"我的方案",
 "dailyBudgetMinutes":480,"executionProfile":"SELECTED_ACTIONS",
 "steps":[
   {"stepId":"fd55224d-92d1-408a-afc2-3ed631e2ca65:work","type":"WORK",
    "workOptionId":"work:竹影武馆/帮武馆跑腿","trigger":"ALWAYS","shifts":1},
   {"stepId":"fd55224d-92d1-408a-afc2-3ed631e2ca65:study","type":"STUDY",
    "courseId":"study:田径运动课","rounds":1}]}
```

- steps 恰为 WORK→STUDY（无 CARE）；`trigger=ALWAYS`（UI 的「仅资金不足时打工」开关为关）；`shifts=1`；`rounds=1`。
- 岗位为 `WorkCatalog` 唯一 `VERIFIED` 项（10 分钟、体力 5、清洁 2、入场费 0）；课程为已扫描且时长 30 的 `study:田径运动课`。
- 保存前经济前置：房间金币 **60** < 学费 **66** ⇒ 必须同 run 内靠这一班工资筹资。

### T016 运行与监控

- runId：`e978be5e-d68d-43f0-8b9b-faa0414e93ee`（= 事件流 sessionId），origin=PLAN，displayId=6。
- 启动方式：生产 UI「保存并运行」（设置 → 高级与开发 → 托管方案 → 运行方案）。
- 监控：`watch_acceptance.py --run-id e978be5e-… --display-id 6 --label plan-work-study --minutes 55`（后台 job，终态后自动 capture）。
- 启动后快照：`steps=[WORK:PENDING, STUDY:PENDING]`、`attemptCount=0`、`blocking=true`（RUNNING 期间正确占锁）。

### T017 判定：**通过**（同 run 三源一致）

- runId `e978be5e-d68d-43f0-8b9b-faa0414e93ee`（= sessionId），设备时间 2026-10-24 02:14 启动 → 02:55 终态。
- 构建身份：healthJson 修复包 `45f0298ef943e593c53e52f46effd098b047a887019f4a73088f0ca5b3bdb1ec`，versionCode 60 / 1.0.60，证书 `a63a2254…cf77`（本轮未重装，沿用）。
- **源 A**（`files/plan-runs/e978be5e….json`）：`state=COMPLETED`、`stopReason=null`、`counts={workShiftsCompleted:1, careCompleted:0, studyRoundsCompleted:1}`；steps WORK=COMPLETED、STUDY=COMPLETED；attempts：work 10 分钟 COMPLETED（dispatch 02:14:38 → start 02:14:42 → done 02:24:47）、study 30 分钟 COMPLETED（dispatch 02:25:03 → start 02:25:05 → done 02:55:06），`inputPending` 均 false。
- **源 B**（bundle `continuation-20261023-145519-369473-plan-work-study`）：`verdict=PLAN_PASS`、`mode=plan`、`completed=1/requested=1`、`workCompleted=1`、`careCompleted=0`、`planRunState=COMPLETED`、`stopReason=null`；run-record `terminalState=COMPLETED`、`courseStarts=1`、`care.feed_count=0 && clean_count=0`；`plan-round-1-completion.json` 存在（course_ms=1803391，high_five SENT_UNCONFIRMED、grade=UNKNOWN）。
- **源 C**（`pet-minute-budget-v2.bin`）：两条 attempt 均 `day=2026-10-24`、status COMPLETED、minutes 10+30；设备当日已用 **40 分钟**（≤480）；`unresolvedAttemptIds=[]`。
- **工资证据**：seq 90 `work-reward-payroll-award award=68|balanceDelta=unread`（同工资页「基础工资68 / 金币+68」，2026-10-24 02:14）；seq 95 `work-completion-dismiss PAYROLL_RETURNED_OUTDOOR`（左上箭头返回，未点「继续打工」）。开工前余额读不出（seq 73/74）→ 按 VERIFIED 零入场费政策走 award-only 证明，符合 `WorkEvidencePolicy`。
- **学费证据**：开课前 `ensureTuition` 通过（未抛 `COURSE_COINS_INSUFFICIENT`）；终态房间金币 **62**（bundle display-6.png 02:55 实读）= 60 + 68 − 66，与「同 run 打工筹资后付学费」一致。
- **未选动作零执行**：steps 仅 WORK/STUDY；care feed/clean=0、无购买、无 work 之外的 INPUT_SENT 归属护理。
- **原生 COMPLETED**：`files/plan-runs/finalizations/` 仅含 b5600c4f，**无** e978be5e 审计 ⇒ 非 finalizer 收尾。
- 限制如实保留：grade=UNKNOWN、high_five SENT_UNCONFIRMED（US4 未接线）；开工前/工资页余额读不出（award-only 证明路径）。
- bundle：`VirtualDisplay/.build-tmp/device-acceptance/continuation-20261023-145519-369473-plan-work-study/`（events 3438 条、INPUT_SENT 33 次、deviceTime 2026-10-24T02:55:23+0800）。

---

### 筹资班次（不计组合证据）

WS 之后金币 62 < 学费 66，CS/WCS 无法自筹资。按用户既有授权（真实工作/学习验收）另起**独立 WORK** 一班筹资：

- run/session `d5bf86e8-9c57-4369-b311-f758c94023ca`，mode=work，verdict **WORK_PASS**，设备 03:10 开工 → 03:20 终态。
- 工资 `work-reward-payroll-award award=68|balanceDelta=unread`（同工资页 2026-10-24 03:10），`PAYROLL_RETURNED_OUTDOOR` 返回。
- bundle `continuation-20261023-152023-389098-funding-work`。
- **本班次只作筹资**：不计入 FUND-LOW（那要求 IF_FUNDS_SHORT 同 run 触发），不计入任何组合；CS/WCS 的同 run 判定仍各自独立。
- 筹资后金币 ≈ 62+68 = 130 ≥ 66 学费 + 护理购买估算。

---

## CS（护理 → 学习）

（待 T018/T019 填写）

---

## WCS（打工 → 护理 → 学习）

（待 T020/T021 填写）

---

## stop-on-failure 证据（T025）

（待填写：优先复用真实失败局）

## 未选动作零执行复核（T046 输入）

（待各组合判定后逐 run 填表）
