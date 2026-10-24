# 独立学习（STUDY-only）真机验收 — 2026-10-22

## 当前判定与时间口径

**独立STUDY单节完整闭环窄场景通过**：run `e3dfcf09-3cd3-4d4d-97e5-cef28dbb21ea` 同时有课程开始、同run完成登记、MANAGED_BATCH_PASS 1/1、返房与30分钟预算增量。健康全程Unknown/NOT_OBSERVED、无喂食/清洁。此结论不等于US1三独立全部通过，不覆盖七组合、全部profile、低健康实值阈值、恢复健壮性或鼓励效果。

本文亲读本地bundle关键JSON、事件/日志片段及display-4宠物画面；未操作设备、构建或安装，不输出display-0私屏。下列业务时间统一为**Asia/Shanghai（2026-10-22）**。bundle名中的20261021-14xxxx来自host America/New_York采集命名，不是设备业务日期；保留路径原名以便定位。

## 本次候选构建

- lead回报候选：`VirtualDisplay/.build-tmp/qqpet-resource-rules-20261021-135954-183423.apk`。
- SHA-256：`3aa25ad5efdde1b2e88a0e527b1a4210db42671abd5c01702f78200b934b634e`。
- lead回报JUnit **550 tests / EXIT 0**，覆盖安装保留数据。本文不把回报描述成自己重跑，也不以单测替代下列设备链。
- 历史候选`.build-tmp/qqpet-resource-rules-20261021-121121-798320.apk`、SHA `9b5f839eda55fb7bd872c4170656461a6c5bf1b03121e826c05209f8b6a403d9`、526测试为先前阶段，不能替代当前候选身份。

## 本次通过链：e3dfcf09

bundle：[independent-study-completion](../../../VirtualDisplay/.build-tmp/device-acceptance/continuation-20261021-145558-701633-independent-study-completion/)。

| 事实 | 直接证据 |
| --- | --- |
| mode=study、runsCare=false、runsStudy=true，保留workBeforeStudy=true但未隐式工作 | events.jsonl的同run SESSION_CONFIG；全run及日志上下文 |
| 02:25:31.076学费66、余额132；田径运动课30分钟 | pet_mvp.log该时段course-selected/course-tuition |
| 02:25:34.529预留30分钟，34.621一次开课tap；02:25:38.648 COURSE_STARTED | 日志course-budget-reserved及events seq29/33 |
| 02:55:40.045 ROUND_RESULT_CONFIRMED completed=1 | 同run时段日志 |
| 02:55:57.981 ROUND_COMPLETE、57.983 SESSION_COMPLETE 1/1 | 日志、round-1.json及completion记录 |
| verdict MANAGED_BATCH_PASS，mode study，completed1/requested1，stopReason=null | [verdict.json](../../../VirtualDisplay/.build-tmp/device-acceptance/continuation-20261021-145558-701633-independent-study-completion/verdict.json) |
| run-record terminalState COMPLETED、completedRounds1、courseStarts1、pauseCount0；feed_count0/clean_count0 | [run-record.json](../../../VirtualDisplay/.build-tmp/device-acceptance/continuation-20261021-145558-701633-independent-study-completion/run-record.json) |
| energy/cleanliness/mood全部Unknown、reason NOT_OBSERVED；before/ready/after均NOT_REQUESTED | run-record.json与[round-1.json](../../../VirtualDisplay/.build-tmp/device-acceptance/continuation-20261021-145558-701633-independent-study-completion/round-1.json) |
| 当日分钟70→100；本次占用30；金币132→66 | 前一WORK bundle budget.xml=70，本bundle[budget.xml](../../../VirtualDisplay/.build-tmp/device-acceptance/continuation-20261021-145558-701633-independent-study-completion/budget.xml)=100；开课学费日志和[返房截图](../../../VirtualDisplay/.build-tmp/device-acceptance/continuation-20261021-145558-701633-independent-study-completion/display-4.png)顶栏66 |

summary最后事件为SESSION_STATE STOPPED/BATCH_COMPLETE，表示Service结束运行；业务run-record为COMPLETED、verdict为PASS，不能单看事件STOPPED误判失败。截图返房浮层还显示饼干图标-7、香皂图标-3、EXP+47，仅作页面变化观察，不能由图标直接推断道具库存或精确健康成本；本run健康值没有读取。

### 鼓励与成绩的明确限制

[round-1-completion.json](../../../VirtualDisplay/.build-tmp/device-acceptance/continuation-20261021-145558-701633-independent-study-completion/round-1-completion.json)记录：

- high_five_sent_count=10，success_count=null，result=SENT_UNCONFIRMED。
- first_offset=39338ms，last_offset=48628ms；只表示输入派发时序，不证明游戏计入10次鼓励。
- grade=UNKNOWN，course_ms=1805511（约30分钟5.5秒），result_wait_ms=1756520。

因此可以确认课程完成，**不能称鼓励业务成功或本次成绩B+**。也不能借另一张迟到成绩单补填本次grade。

## 学费不足的独立学习反例：99da769a

bundle：[study-funds-short](../../../VirtualDisplay/.build-tmp/device-acceptance/continuation-20261021-141031-725064-study-funds-short/)，run `99da769a-effc-4fa0-82d5-fd91a16be821`。

02:09:57读到金币64<学费66，verdict STOPPED/COURSE_COINS_INSUFFICIENT，run-record FAILED、courseStarts0、completed0、feed/clean0、encouragement0；账本仍60分钟。SESSION_CONFIG仍有workBeforeStudy=true，但mode=study未触发工作，未尝试补护理或再次付费。[课程页截图](../../../VirtualDisplay/.build-tmp/device-acceptance/continuation-20261021-141031-725064-study-funds-short/display-4.png)显示金额和金币不足按钮。

**通过范围**：实际余额不足时未开课、预算未增加、无隐式工作/护理。不是“学费少1”的边界样例（本例差2），也不是IF_FUNDS_SHORT组合闭环。

## 迟到成绩单与历史失败必须分开

- 旧00:38 run `096a4507-3837-4487-8634-7b6e20eba003`进入学习/WAIT_RESULT，但00:43终止RECOVERY_ATTEMPTS_EXHAUSTED、App完成0。亲读[previous-study verdict](../../../VirtualDisplay/.build-tmp/device-acceptance/continuation-20261021-135724-374080-previous-study/verdict.json)仍为STOPPED 0/1；黑帧/回退是已记录现象，因果上游未定，不能称纯设备问题。
- 02:04新run `d53ab509-9923-467f-8daa-1740df45c218`出门后遇到页面标示00:38的田径成绩单。亲读[stale-report-rejected截图](../../../VirtualDisplay/.build-tmp/device-acceptance/continuation-20261021-140603-091660-stale-report-rejected/display-4.png)：B+、学分+25、力量+20；这是迟到游戏结果的补证，不能把旧App恢复失败改成完成。
- d53本身verdict STOPPED、PAGE_MISMATCH:SELECT_COURSE:0-outdoor-after:0.8086033950617284，courseStarts0、completed0、预算仍60。它没有把旧成绩登记为新局完成；这不等于结果归属机制与全部恢复分支已经验收。
- 更早23:27和00:02两张成绩单只保留其画面证据；此前“连续三节App全部完成”“鼓励通过”“必须重启手机”均已撤回。历史16:19成绩单仍缺同attempt归属，不能据此确定旧s21根因。

01:39时VD4经真实QQ Splash新任务和搜索历史返回房间，无需重启手机，是当次恢复现场的历史事实，不足以证明未知页面/黑帧恢复健壮性。

## 后续验收

仍需独立CARE、四组合、条件工作同run闭环、配置迁移、真实低健康/恰好学费边界、结果归属和恢复矩阵。当前只能将本次STUDY-only单节完成及学费不足零消费反例记为通过；其他结果按[总矩阵](results.md)保持未定或部分覆盖。
