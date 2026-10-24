# Acceptance results — 当前矩阵与历史记录

更新：2026-10-22（Asia/Shanghai）。**当前独立WORK工资/返回窄场景及STUDY-only单节完整闭环已通过；独立CARE、US1整体、四组合及全部七种配置的验收仍未完成。** 保留早期失败，不把不同run拼成条件工作或组合闭环。

本轮仅亲读本地JSON、同run事件、对应时段日志和display-4宠物图，未操作设备、构建或安装；不展示display-0私屏。lead负责progress/memory/tasks，本段只修三份acceptance记录。

## 当前候选与证据口径

- lead回报候选`VirtualDisplay/.build-tmp/qqpet-resource-rules-20261021-135954-183423.apk`，SHA-256=`3aa25ad5efdde1b2e88a0e527b1a4210db42671abd5c01702f78200b934b634e`，550 JVM/EXIT0、覆盖安装保数据；本段未自行重跑或核安装。
- 四组本地bundle的关键JSON/截图已亲读；另读previous-study的旧verdict/summary核历史终态。包名日期20261021-14xxxx为host America/New_York采集名，设备时间已到2026-10-22；下表业务时间均Asia/Shanghai，不由host文件名推断设备日期。
- 每场保留runId/模式、构建、所选配置、输入/开始/完成、资源前后、预算及停止原因。Unknown不填0，工资不是钱包，已派发鼓励不是业务成功，服务SESSION_STATE STOPPED/BATCH_COMPLETE不等同业务失败。

## 新证据索引

| 简称 | bundle / 业务run | 结果 |
| --- | --- | --- |
| STUDY-PASS | [independent-study-completion](../../../VirtualDisplay/.build-tmp/device-acceptance/continuation-20261021-145558-701633-independent-study-completion/)，e3dfcf09-3cd3-4d4d-97e5-cef28dbb21ea | MANAGED_BATCH_PASS、study1/1、run-record COMPLETED |
| STUDY-SHORT | [study-funds-short](../../../VirtualDisplay/.build-tmp/device-acceptance/continuation-20261021-141031-725064-study-funds-short/)，99da769a-effc-4fa0-82d5-fd91a16be821 | 64<66，COURSE_COINS_INSUFFICIENT、starts0、预算不增 |
| WORK-PASS | [tuition-funding-work](../../../VirtualDisplay/.build-tmp/device-acceptance/continuation-20261021-142320-335446-tuition-funding-work/)，19378edc-67b1-4f4d-a586-f29a4830803d | WORK_PASS、返房、award68；无护理/开课 |
| STALE-RESULT | [stale-report-rejected](../../../VirtualDisplay/.build-tmp/device-acceptance/continuation-20261021-140603-091660-stale-report-rejected/)，d53ab509-9923-467f-8daa-1740df45c218 | 迟到00:38成绩单；新局PAGE_MISMATCH、完成0、预算60 |

## 当前场景矩阵

| ID | 验证目标 | 当前结果与限制 | 证据 |
| --- | --- | --- | --- |
| W | 独立显式一班、工资确认、正常返回、无护理/学习 | **窄场景通过**；不代表实际双耗值/最低门槛全部通过 | 22:46历史WORK_PASS及本次WORK-PASS；见[WORK明细](us1-independent.md) |
| C | 独立护理达目标或合法跳过、无课程/岗位依赖 | 未取得此轮完整证据 | 待验证 |
| S | 独立学习指定课程1/1、无隐式工作/护理 | **单节完整闭环通过**，仅本次STUDY-only | STUDY-PASS；见[STUDY明细](us1-study.md) |
| S-FUNDS | 学费不足不输入开课、不隐式工作/护理 | **本例通过**；差2而非少1边界 | STUDY-SHORT，workBeforeStudy=true仍无work，60分钟不变 |
| WC | 工作→护理、不读课程/学习 | 未取得同run组合证据 | 不借独立WORK补判 |
| WS | 工作→学习、无护理 | **同run通过**：run `e978be5e-d68d-43f0-8b9b-faa0414e93ee`，PLAN_PASS/COMPLETED，counts W1/C0/S1，工资 award=68 同页确认，学费 66 同run支付（金币 60→62=60+68−66），分钟 +40（10+30），care feed/clean=0；grade UNKNOWN、high_five SENT_UNCONFIRMED 保留 | [WS明细](us2-combinations.md)；bundle `continuation-20261023-145519-369473-plan-work-study` |
| CS | 护理→学习，护必需学费 | 未取得同run组合证据 | 待验证 |
| WCS | ALWAYS工资→护理→正确课程完成 | 未取得同run组合证据 | 待验证 |
| FUND-LOW | 条件工作一班后同run闭环 | 未定 | 另起独立WORK筹资不计此项 |
| FUND-SKIP | 资金足跳过、工作输入/分钟0 | 未定 | 无对应组合证据 |
| FUND-30 | 剩余30、课30、可选工作10且钱足可跳过 | 未定 | 本次账本70→100不是该临界案例 |
| FUND-CARE-GAP | 学费够、护理缺钱时决策与dispatch一致 | 未定 | 无库存/采购完整组合证据 |
| SAVE-7 | 七非空配置保存/回读/不自动启动 | 未定 | 尚无完整设备矩阵 |
| LEGACY | v1参数、每轮护理、原岗位准备及预算兼容 | 未定 | 当前单项PASS不覆盖全部profile |
| LEGACY-DIFF | 新WORK-only不隐式准备护理 | 部分：WORK实跑无护理；低岗位状态差异未验 | WORK-PASS，feed/clean0 |
| W-MIN | 5/2恰好、少1/Unknown/成本变化 | 未定 | 正向一班不能替代这些边界；浮层清洁-3与目录2差异仍保留 |
| S-MIN | 学费恰好/少1、健康低/Unknown不拦 | 部分：Unknown健康已完成，64<66未开课；其他边界未验 | STUDY-PASS/STUDY-SHORT，不能声称恰好66或差1已测 |
| C-STOCK | 零现金足库存、部分库存仅补缺口、满状态零购买 | 未定 | 历史静态库存图/纯函数不替代新CARE闭环 |
| CASH-EXACT | 护理购买+全部后续学费恰好或少1 | 未定 | STUDY-SHORT是学费检查，不是护理付款保护 |
| TRAJECTORY | 工作后护理重算/库存耗尽/多节后缀学费 | 未定 | 独立跨run经济变化不证明组合轨迹 |
| CNF | 课程不存在时消费0 | 新路径未定 | 历史CNF只作基线 |
| START-UNKNOWN | 输入可能已发时不重放/完成0/占用保留 | 历史停止证据保留，完整异常验收未定 | 不用新单节成功覆盖旧局失败 |
| RESULT-OWNER | 结果归属本attempt，旧结果不冒认 | 部分：新S完成及旧成绩未计新d53；完整归属机制未定 | STUDY-PASS和STALE-RESULT |
| PAYROLL-UNKNOWN | 无工资证据时不继续、award不当钱包 | 正向award通过，未知工资反例未定 | WORK-PASS |
| RESOURCE-UNKNOWN | 仅本动作必要未知值阻止 | 部分：S健康Unknown仍完成；其他资源未知矩阵未定 | STUDY-PASS |
| BUDGET | 用时不足、未知保留、跨日/重启/重复幂等 | 部分：本次WORK+10、S+30、钱不足+0；其余未定 | 各bundle budget.xml及动作日志 |
| CONCURRENT | 单项/组合/健康检查同锁 | 未定 | 无并发新证据 |
| RETURN | 正常返回确认 | 狭窄正向证据通过，完整恢复矩阵未定 | WORK返房、STUDY返房 |
| REENTER | 已识别入口有限恢复、不重消费 | 历史01:39能恢复现场；健壮性未定 | 不能推为所有黑帧/入口已通过 |
| PAGE-UNKNOWN | 未知页停止、不探点 | d53 PAGE_MISMATCH停止完成0；完整矩阵未定 | STALE-RESULT |
| RECOVERY-LIMIT | ≤3次、耗尽停止 | 旧00:43耗尽停止证据，不是课程完成或恢复成功 | 旧096a4507 |
| STOP-RESTART | STOP/断连/pending重启不重放 | 未定 | 无新完整场景 |

必要异常若以设备夹具验证须明确标注，不能冒充真实游戏正向；不得清账本、充值、额外领币或插未选动作凑通过。

## 独立学习通过范围（e3dfcf09）

同run mode=study、runsCare=false，courseStarts1、feed/clean0，energy/cleanliness/mood均Unknown/NOT_OBSERVED。02:25:31学费66/余额132；02:25:34一次开课并预留30；02:25:38开始确认；02:55:40结果登记，02:55:57完成1/1，stopReason=null；返房金币66、当日账本100（前一WORK后70）。before/ready/after为NOT_REQUESTED，证明此条独立学习路径无需强制健康读取，不能推为所有profile/恢复分支均已解耦。

completion的high_five_sent_count10、success_count=null、SENT_UNCONFIRMED，first39338ms/last48628ms，gradeUNKNOWN；只确认派发时序，不记鼓励业务通过或成绩B+。截图浮层健康图标变化只作观察，不硬编码学习零健康消耗，也不把图标数当实际库存。

## 独立WORK与历史失败

- 当前WORK-PASS run19378edc在02:12:57开始，02:23:00同工资页award68，02:23:05 PAYROLL_RETURNED_OUTDOOR，02:23:12 WORK_PASS；run-record COMPLETED、courseStarts/feed/clean0。相邻真实余额64→132、账本60→70。本场为另起独立WORK，不是条件工作同run闭环。
- 2026-10-21 22:46那班已有WORK_PASS、工资页返回及219→287；[明细](us1-independent.md)保留其523测试候选与浮层清洁-3/目录2差异，不补判资源门槛全部一致。
- 历史2026-10-21 20:10～20:20 run13711e66-246b-4892-a1ab-459fc190e0a7仍是核心工资确认后DISMISS_BUTTON_NOT_LOCATED失败，SHA1b00…dfc44/450测试为当时构建；不把后续两次成功回写成该旧局PASS。其工资图、失败原因及当时本地启动片段限制仍留于历史[实施记录](resource-rules-implemented.md)。

## 迟到成绩单与失败终态

旧00:38 run096a4507-3837-4487-8634-7b6e20eba003仍为RECOVERY_ATTEMPTS_EXHAUSTED、App0/1。02:04新d53出门后截图出现页面标示00:38的田径B+、学分+25、力量+20，补充的是游戏结果画面，不补旧App完成；d53自己PAGE_MISMATCH、starts0/completed0、预算60。新STUDY-PASS的gradeUNKNOWN不得从该旧页借值。

更早s21/b2e3dda0为LEARNING_NOT_CONFIRMED_AFTER_START、STOPPED/FAILED0/1，16:19成绩单缺同attempt归属，仍不能证明旧局成功或确定漏识别根因。23:27/00:02成绩单也不能替代00:38旧App失败。历史“连续三节App成功”“鼓励通过”“必须重启手机”等过度说法已撤回，详见[学习记录](us1-study.md)。

## SC总表

| 标准 | 当前结果 | 理由 |
| --- | --- | --- |
| SC-001 三独立 | **部分完成，整体未通过** | WORK/S单项窄场景通过，CARE缺本轮完整证据 |
| SC-002 四组合 | 未定 | 没有四种同run组合完成矩阵 |
| SC-003 显式/条件工作全链 | 未定 | 不拼接独立WORK与另一次STUDY |
| SC-004 配置与迁移 | 未定 | 七配置/旧格式设备矩阵未完整 |
| SC-005 异常 | 部分覆盖 | 金币64<66不消费、d53页不符停止；不能称全部异常通过 |
| SC-006 四类恢复 | 未定 | 有历史回房/耗尽，黑帧上游及完整恢复健壮性未定 |
| SC-007 预算幂等 | 部分正常路径，整项未定 | +10/+30/+0已见，跨日/重启/未知派发幂等未整体验证 |
| SC-008 最低资源边界 | 部分覆盖 | S健康Unknown通过，金额差2拒绝；所有profile/恰好/少1/库存采购边界未完 |

旧工具启用、reenter408测试、历史CNF/托管1节PASS保留在[baseline](baseline.md)，不替新矩阵通过。构建复现见[build-guide](build-guide.md)，新S/W具体场景分别见[学习](us1-study.md)与[工作](us1-independent.md)。
