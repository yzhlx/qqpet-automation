# 验收记录约定（spec 002）

本目录是 `specs/002-plan-combination-acceptance-closure` 的验收记录区。矩阵与 SC 总表仍写在 `specs/001-managed-study-recovery/acceptance/results.md`；本目录存放**本轮新增**的基线、清单、缺陷与审计记录，以及取证工具的使用约定。

## 文件清单

| 文件 | 内容 |
| --- | --- |
| [environment-baseline.md](environment-baseline.md) | T001/T002：设备、应用、Shizuku、display、游戏画面读数、课表/岗位、既有结论复核 |
| [legacy-pending-inventory.md](legacy-pending-inventory.md) | T006/T008：七条遗留 CARE attempt 的清算证据清点与放行复核 |
| `combo-defects.md` | T022：组合未达 COMPLETED 时的实锤根因（保守门禁 vs 生产缺陷） |
| `us4-gate-not-wired.md` | T042：US4 未接线的源码证据 |
| `us4-print-time-samples.md` | T043/T044：打印时刻样本与（若实证）接线方案 |
| `record-audit.md` | T049/T050/T052/T055/T056/T058：矩阵字段核对、工具输出、任务板结论、一致性检查 |
| `build-regression.md` | T013/T023/T057：编译/JVM 回归/签名/安装/aidl 还原的实际数字与日志路径 |

## bundle 命名与 label

归档一律走 `tools/diagnostics/capture_acceptance_state.py`，输出到
`VirtualDisplay/.build-tmp/device-acceptance/continuation-<UTC时间戳>-<label>/`。

| label | 场景 |
| --- | --- |
| `plan-work-study` | WS（打工→学习） |
| `plan-care-study` | CS（护理→学习） |
| `plan-work-care-study` | WCS（三步） |
| `fund-low` | FUND-LOW（IF_FUNDS_SHORT 资金不足真实触发） |
| `fund-skip` | FUND-SKIP（资金充足合法跳过） |
| `fund-care-gap` | FUND-CARE-GAP（学费够但护理缺钱） |
| `save7-<combo>` | SAVE-7 七配置逐个（combo ∈ w/c/s/wc/ws/cs/wcs） |
| `wc-recheck` / `env-baseline` | 只读复核与基线采集 |
| `work-writeback-before` / `work-writeback-after` | D1 独立 WORK attempt 证据写回前后核验（观察自身不发输入） |

原始截图与日志**只留本地**（`.build-tmp/device-acceptance/`），不外传、不进入待提交范围。

## 每条记录必备字段（12 项）

1. 场景 ID；2. runId（= 事件流 sessionId）；3. 构建身份（APK 名 + SHA-256 + 证书指纹 `a63a2254…cf77` + versionCode）；4. 所选配置（动作集合、岗位、课程+节数、阈值、每日上限、trigger）；5. 执行前状态（金币、体力/清洁或 Unknown、当日已用分钟、库存观察来源）；6. 计数（INPUT_SENT、courseStarts、feed/clean_count、counts W/C/S）；7. 执行后状态（金币、体力/清洁、当日分钟）；8. 终态（`plan-runs` state / verdict / planRunState / stopReason）；9. 资源证据（正 award、逐笔钱包与 tuitionReserve、学费与余额）；10. bundle 路径；11. 判定（通过/不通过/未定 + 一句理由）；12. 设备实读时间（Asia/Shanghai，取 `summary.json.deviceTime`）。

## 写法禁令

- Unknown 不写 0；预测工资不当余额；已派发鼓励（`high_five_sent_count`）不当业务成功；grade 一律 `UNKNOWN`（US4 未接线）。
- 不用服务层 `SESSION_STATE=STOPPED` 判成败（成功也发 STOPPED）。
- 不跨 run 拼接；不用历史 PASS 顶替本轮未取得项。
- host 文件名里的日期不作为设备业务日期。
- 保守停止（`NeedsObservation` / `*_UNCONFIRMED` / `DAILY_BUDGET_LIMIT` / `COURSE_COINS_INSUFFICIENT` / `PENDING_RUN_UNRESOLVED`）记「未定 + 实际读数 + 保守停止符合预期」，不记应用缺陷。

## 工具回归（T011 记录区）

2026-10-24 对两个已终态 run 做只读 live 复核，当前 display 6：

| 场景 | run / bundle | 实测结果 |
| --- | --- | --- |
| 无 STUDY（WC） | `b5600c4f-f334-4013-9db8-f95d5fc4cb0a` / `continuation-20261024-112902-687931-environment-baseline` | `expectedStudyRounds=0`、`savedPlanRoundFiles=[]`；`plan-v2.json` 与 `persisted-plan-run.json` 正常归档；未生成伪 `plan-round-1-completion.json`。`eventCount=490`、`inputCount=79`、设备时间 `2026-10-24T23:29:07+0800`。 |
| 含 STUDY（WS） | `e978be5e-d68d-43f0-8b9b-faa0414e93ee` / `continuation-20261024-113001-288078-plan-work-study` | `expectedStudyRounds=1`，`savedPlanRoundFiles=[plan-round-1-completion.json]`；原 bundle/summary 字段保留，`plan-v2.json` 与 `persisted-plan-run.json` 正常。`eventCount=3438`、`inputCount=33`、设备时间 `2026-10-24T23:30:05+0800`。 |

对同一终态 WS run 执行只读 `watch_acceptance.py` 后，真实终态输出：

```
VERDICT_SUMMARY {"verdict":"PLAN_PASS","mode":"plan","completed":1,"requested":1,"workCompleted":1,"careCompleted":0,"stopReason":null,"planRunState":"COMPLETED","planRunStopReason":null,"counts":{"workShiftsCompleted":1,"careCompleted":0,"studyRoundsCompleted":1}}
```

watch 的 legacy `SESSION_STATE=STOPPED` 与最终业务通过并存；权威 `persisted-plan-run.json` 给出 `COMPLETED`。回归只读取已存在 run，没有发送新输入。`missingOptionalFiles` 中的旧格式 `round-1*.json`、未选动作文件确实不存在；工具记录缺失而未写成 JSON。`T009`、`T010`、`T011` 均通过。

`read_budget_ledger.py`（只读 v2 账本、fail closed）与 `read_plan_runs.py`（只读 run 与 pending gate）也已实测；2026-10-24 live 输出见 [legacy-pending-inventory.md](legacy-pending-inventory.md) 与 [environment-baseline.md](environment-baseline.md)。
