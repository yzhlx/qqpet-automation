# 独立打工（WORK-only）真机验收 — 2026-10-21/22

## 历史已通过班次的构建身份（2026-10-21 22:46）

- APK：`.build-tmp/qqpet-resource-rules-20261021-224402-215836.apk`
- SHA-256：`528c490c281d5ed7ce17f12c8ec17568318b49bb1affbab13b31a8e455c60fa4`
- 证书：`a63a225405105cd99ab3a31621ab455c8e917ab8d703638fcc881e80a325cf77`（与设备一致）
- 安装：`adb install -r` Success，保留数据，versionCode 60
- JVM：`OK (523 tests)`，EXIT 0

## 场景：独立打工一班（display 4，720x1600，serial DKS9K23420020242）

起点：房间页，金币 219，宠物 4 级。计划岗位 `work:竹影武馆/帮武馆跑腿`（VERIFIED，实测 10 分钟 / -体力5 / -清洁2 / +68 币，entryFeeCoins=0 已证）。

### 证据链（pet_mvp.log，Asia/Shanghai 2026-10-21）

| 时间 | 事件 | 结果 |
| --- | --- | --- |
| 22:46:12 | `work-started` | PASS（单次派发，无重复） |
| 22:56:16 | `work-reward-payroll-award` | PASS `award=68` |
| 22:56:20 | `work-completion-dismiss` | PASS `PAYROLL_RETURNED_OUTDOOR`（左上箭头返回，非“继续打工”） |
| 22:56:22 | `work-completion-outdoor` | 0.9997 PASS |
| 22:56:27 | `SESSION_STOP` | **`WORK_PASS`** |

### 真实经济证据

- 房间金币：**219 → 287（+68）**（`final-room.png`），与同工资页 `+68` 奖励一致。
- 班次结算浮层：`-5 体力 / -3 清洁 / EXP +7`。浮层清洁-3与目录清洁成本2存在差异，不能称一致；差异来源尚未确认，不由此推定岗位成本已改变或资源门禁已验收。
- 宠物在房间，未开课、未护理、未隐式追加第二班。

## 判定

- **独立WORK工资/返回窄场景：通过。** 本班10分钟、正工资到账、工资页正常返回及WORK_PASS有证据；未开课、未护理。浮层清洁-3与目录2的差异尚未解释，不能将此窄场景等同于实际双耗值一致性或完整资源门禁通过。
- 本轮修复的关键缺陷：① 工资页无“确定/继续/知道了”，旧 `dismissCompletion` 必然失败 → 改为识别左上返回箭头像素定位并只返回一次、分类 OUTDOOR/ROOM/WORK_DETAIL；② 房间→户外“出门”入口坐标 `(360,1390)` 错误 → 修正为 `(360,1255)`；③ service 只认 `MANAGED_BATCH_PASS`，把 `WORK_PASS` 误标 STOPPED → 识别各独立成功 verdict。

## 当前追加复核：筹学费的独立WORK（2026-10-22，Asia/Shanghai）

本节更新早期20:20仅核心工资通过的状态：22:46那班已取得工资页返回与WORK_PASS；本节又亲读一次新的独立WORK完整结果。因此当前**WORK-only工资/返回窄场景已通过**，不能继续把旧DISMISS_BUTTON_NOT_LOCATED写成所有独立WORK当前仍失败。

- lead回报当前候选：`VirtualDisplay/.build-tmp/qqpet-resource-rules-20261021-135954-183423.apk`，SHA-256=`3aa25ad5efdde1b2e88a0e527b1a4210db42671abd5c01702f78200b934b634e`，550 JVM/EXIT0，保留数据覆盖安装。本节只读证据，未重跑构建/安装。
- runId=`19378edc-67b1-4f4d-a586-f29a4830803d`；[bundle](../../../VirtualDisplay/.build-tmp/device-acceptance/continuation-20261021-142320-335446-tuition-funding-work/)路径使用host America/New_York命名，原路径保留；下列业务时间按设备Asia/Shanghai 2026-10-22。

| 时间 | 本地日志/JSON事实 |
| --- | --- |
| 02:12:56.287 | work-start-requested，DELIVERED_UNCONFIRMED（派发尚不等于开工） |
| 02:12:57.539 | work-started PASS |
| 02:23:00.884 | 同工资页work-reward-payroll-award PASS，award=68，balanceDelta=unread |
| 02:23:05.796 | work-completion-dismiss PASS，PAYROLL_RETURNED_OUTDOOR |
| 02:23:07.117 | work-completion-outdoor约0.99973 PASS |
| 02:23:12.342 | SESSION_STOP WORK_PASS |

[verdict.json](../../../VirtualDisplay/.build-tmp/device-acceptance/continuation-20261021-142320-335446-tuition-funding-work/verdict.json)为WORK_PASS、mode work、stopReason=null；[run-record.json](../../../VirtualDisplay/.build-tmp/device-acceptance/continuation-20261021-142320-335446-tuition-funding-work/run-record.json)为COMPLETED、completedRounds0、courseStarts0、feed/clean0。completedRounds0是学习节数，不能据此判一班工作失败。

经济/时间证据：前一独立学习不足余额bundle已读金币64、账本60；本次WORK返房[截图](../../../VirtualDisplay/.build-tmp/device-acceptance/continuation-20261021-142320-335446-tuition-funding-work/display-4.png)金币132，[预算](../../../VirtualDisplay/.build-tmp/device-acceptance/continuation-20261021-142320-335446-tuition-funding-work/budget.xml)70，即相邻真实观察64→132、60→70，与本run正award68/10分钟一致。截图浮层仍见-5/-3，保留前述清洁差异，不把它解释成岗位成本已改变或全部门禁通过。

本班是**用户另起独立WORK筹资**，随后STUDY为另一run；不得把两场拼成IF_FUNDS_SHORT、WORK→STUDY组合或同run闭环通过。当前STUDY-only另有完整1/1证据，见[独立学习](us1-study.md)。

## 未覆盖

- 独立CARE未取得此轮完整真机证据，US1三独立整体尚未完成。
- 四组合、条件工作同run闭环、全部profile、资源最低值与Unknown门禁、配置迁移和恢复/异常完整矩阵未因此通过。
- 历史20:20 run13711e66因DISMISS_BUTTON_NOT_LOCATED停止仍保留为失败；新两次WORK通过不改写其终态。本结论仅为独立WORK工资/返回窄场景PASS。
