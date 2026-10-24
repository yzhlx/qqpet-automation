# 实施记录：五项资源规则的部分接入（2026-10-21 20:32更正）

本文件早期标题/表格曾把规则写成整体“已落地”，并声称学习低健康/Unknown/Stale均不会阻断生产路径。这超出了现有证据，现以本节为准。本文记录lead最新构建/真机回报及本段只读本地检查；本段没有构建、测试、安装或操作设备。

## 实现状态与证据边界

| 规则/能力 | 当前实际状态 | 不能据此声称 |
| --- | --- | --- |
| WORK实际双耗值+固定时长 | 相关纯函数和底层生产检查已部分接入；新独立WORK真实开工并确认同工资页+68 | 最低值全部边界已真机通过，或独立WORK整体完成 |
| STUDY无强制健康门禁 | 纯函数checkStudy无健康条件；部分requireReady已删除、课程完成登记调整；**生产STUDY仍有强制健康读取，解耦未完成** | 所有profile开课/恢复/完成全链已无健康依赖 |
| CARE库存优先、仅买缺口 | checkCare纯函数和测试已表达Known/Unknown规则；真实库存观察与全生产消费链未完成验收 | 库存未知已可靠观测、零现金库存场景已真机通过 |
| 真钱包/护后续学费 | RoomWalletReader/购买payGuard等已有部分代码；观测可靠性与全后缀资金链尚未真机验收 | 会话额度等同钱包、全部profile已护全部未付学费 |
| 逐步资源轨迹 | PlanResourceForecast等纯函数已有测试 | 统一PlanV2/PlanRunner、七种组合、attempt账本及迁移已在生产实现 |
| 独立入口 | WORK/STUDY生产入口已新增并有WORK实际运行 | 三项独立全部成功，或单项入口已等价于统一单元素runner |

## 最新构建（lead回报，2026-10-21）

- APK：`VirtualDisplay/.build-tmp/qqpet-resource-rules-20261021.apk`。
- 最新SHA-256：`1b00d258d88ac03805bc70dc961b494c29cacbe593d3272d96a8e7f01a1dfc44`；旧`cb29ad6390808019b3fd5128732011317ab2829ffdf07823ac62812bc62bdfb7`只作此前候选历史，不能替代当前身份。
- JUnit：lead回报 **450 tests通过**；前文446/448是历史阶段数，不是当前数。本段没有重跑测试。
- 本段只读`compile-latest.log`看到BUILD SUCCESSFUL；读取`resource-rules-signature.txt`确认v3验证通过、证书SHA-256为`a63a225405105cd99ab3a31621ab455c8e917ab8d703638fcc881e80a325cf77`。aidl还原和保留数据安装按lead既有执行记录；本文未重新执行。
- JUnit通过只证明测试覆盖的代码，不证明五项规则已全量接入生产或设备闭环成功。

## 最新独立WORK：核心通过、收尾失败

runId=`13711e66-246b-4892-a1ab-459fc190e0a7`，mode=work、runsCare=false、runsStudy=false。时间为2026-10-21 Asia/Shanghai，来自lead最新真机回报：

| 时间 | 事件 | 判定 |
| --- | --- | --- |
| 20:10:25 | 一次启动派发 | 不代表完成，不可重放 |
| 20:10:27 | 工作开始确认 | 开工成立 |
| 20:20:30 | completion observed | 进入完成/工资观察 |
| 20:20:31 | 同工资页岗位/10分钟、award68确认 | 核心岗位完成与正工资证据成立 |
| 20:20:32 | DISMISS_BUTTON_NOT_LOCATED | 收尾未完成，整个独立WORK/US1不能判PASS |

本地`VirtualDisplay/.build-tmp/device-acceptance/work-only.jsonl`本段读到的是启动片段（截至sequence54），不是后续完整事件归档；结束/工资/停止时间以lead真机回报记录，后续应补齐完整同run事件、run-record和预算前后，不能伪称此本地片段已覆盖全部时间线。

本段查看`VirtualDisplay/.build-tmp/device-acceptance/payroll-observe.png`（720×1600）：工资明细、帮武馆跑腿(10分钟)、基础工资68、金币+68、工分+10；左上箭头，底部“分享”“继续打工”，没有独立的“确定”“继续”“知道了”收尾按钮。“继续打工”是再次开班入口，不能当关闭工资页使用。核心工资证据与整体失败必须同时保存。

## 仍需完成

1. 截至20:30真实工资页已说明旧dismiss逻辑不适配；收尾修复尚未构建/验证。依据该页确认合法返回/关闭链，修复DISMISS_BUTTON_NOT_LOCATED；禁止用分享或继续打工冒充收尾，不因收尾失败重开一班。450通过和上述APK SHA仅属于此前候选，不为未构建修复背书。
2. 全部profile解耦生产STUDY的强制健康读取，包括入口、导航、恢复、freshReady及课后观察；“不强制”不等于零消耗，课程页实际15体力/6清洁仍可记录供轨迹使用。
3. CARE真实库存/钱包、后续全部学费保护与资源轨迹完整接线；统一runner、七组合、attempt账本及迁移基础仍待实现。
4. 完成后再做三独立、四组合、条件工作及异常验收。当前核心WORK/工资通过不覆盖US1整体，也不覆盖SC-001～008。
5. 历史学习成绩单16:19只提示可能开课/学习，缺同attempt归属不能证明旧局成功；不得声称成绩单始终未被触碰，见[学习诊断](study-start-diagnosis.md)。
