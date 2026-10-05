# 当前结果：2026-10-05 21:15

恢复后的固定三轮实验3_ROUNDS_PASS，完成3节，A/A/A+，physicalDelta=0。第3轮App自动1次+用户授权display4调试补点10次。已有饼干1、肥皂1恢复第2轮健康，三轮开课前体力/清洁均≥90。最终健康88/95/80，不再开第4节。

capture门禁与hybrid真实漏帧fallback均通过；合计ImageReader1827成功/3超时，fallback3/3，p50/p95=946/1122ms。最终APK已构建安装，截图回归3/3。详细报告CAPTURE-HYBRID-REPORT.md；机器摘要deliverables/experimental/three-round-summary.json。

本实验跨真实失败修复后恢复，有调试参与；不代表单次无干预连续三轮或五轮。App“30秒后10次burst”新代码已编译安装，尚未实跑该新动作。以下为历史进度。

---
# 有界真实 Session 进度

## 最新：2026-10-05 20:11，capture修复通过，三轮STOPPED

手机App内Shizuku主动截图门禁SHIZUKU_CAPTURE_PASS：静态10/10、转换12/12，全部display4、physicalDelta=0。Hybrid人为制造2次ImageReader timeout，两次fallback正确确认新页面，模板1.0。test/assembleDebug/assembleDebugAndroidTest成功，最新APK已安装。详细技术、统计、证据与changed files见CAPTURE-HYBRID-REPORT.md。

最新Run1791202276391实际已启动1节蹦蹦跳跳体能课，但自动流程在第一次动作后新帧仍为过渡画面时STOP：learning score0.639，ImageReader等待12ms，非capture timeout；完成0/3，physicalDelta=0，before98/94/79，本次无喂食/清洁/击掌。只读App截图随后确认“正在学习”、剩余08:12，没有继续点击。旧的“未开课/采集阻塞”段落为历史记录，不代表最新状态。当前唯一新阻塞为开课后过渡页面确认；未修改ROI或阈值。

## 19:30 即时重试结果

用户要求后立即启动 3 轮；Run `1791199799477` 仍在健康面板关闭后、开课前因 headless 新帧超时 STOP（0/3）。健康体力90/清洁94/心情79，课程未开始、物品未消耗、physicalDelta=0。ADB 截图确认关闭动作成功，实际页面已回宠物首页；失败是采集无后续帧，之后未盲目重试。当前无法完成自动学习三轮，原因和证据见实验报告。

## 最新续验（2026-10-05 19:23）

Headless 静态回归通过（3 个截图状态测试 + 1 次捕获），但实跑 Run ID `1791198749090` 在学园地图等待时 capture timeout，`STOPPED 0/3`，物理主屏输入增量 0、消耗品 0、课程 0。最后帧清楚显示“宠物学园”地图。定位到旧路由漏点“初级学园”入口，已改为地图识别后点击 `(200,420)` 并验证课程列表；修复版 test/assembleDebug/assembleDebugAndroidTest 全通过。

仅为校准导航，已在先前截图确认地图后使用 `adb input -d 4 tap 200 420` 打开课程面板。截图显示“蹦蹦跳跳体能课”默认选中、10分钟，未点“去上课”。随后 App 内新一轮 headless 导航校准仍在静态页面首帧超时；ADB系统截图可读取，但不算 App 内 headless capture 通过。当前阻塞是 ImageReader 在 display 4 静态页面下无法获取新帧，surface rebind 也未恢复。三轮自动课程未启动、未完成。详见最新 `EXPERIMENTAL-3-ROUND-REPORT.md`。

2026-10-05，北京时间。此文件补充此前报告，不将准备工作计为真实续课 PASS。

最新阶段见 `EXPERIMENTAL-3-ROUND-REPORT.md`：用户改为限定3轮、仅实验路径允许低心情。实际启动在学园ROI识别失败后STOP，完成0/3；本实验未开课，physicalDelta=0。下文1/5/10与全三项≥90说明为前阶段范围，正式/default健康保护仍保留。

- 分支：feature/qqpet-mvp-session。
- WIP checkpoint：05a3416f2946a68f54db9121f6d9ce5dc3a37b40；其父提交为用户指定基线0390d8f07ac4071658e77bff9b0974c57f16a186。
- 新开发工作尚未提交。APK、个人QQ截图与日志保留本地，未纳入checkpoint。
- 已人工验证成绩单“继续学习”跳到初级学园课程面板，不开课；体能课默认已选中。列表和详情同屏，以不同ROI验证。
- PetProfile已填入实测continue/course/start坐标，result/course list/course detail模板，以及初始、展开两种learning模板。模板由真实截图提取，没有OCR或anchor detector。
- 状态机已改为RESULT → continue → COURSE_LIST → course → COURSE_DETAIL → start → LEARNING，每次动作前后核对模板，识别失败停止。保存before/after帧。
- Service已限定1/5/10轮，等待实际成绩单后才记完成轮数和10分钟预算；检查当前机型、QQ 9.3.70 (16410)、720×1600@320dpi，保留六字段JSONL日志。
- 用户追加健康规则：每次学习、打工、冒险前三项（体力/清洁/心情）均≥90。新增固定颜色进度条读取代码供校准，尚未接入完整恢复路径；Profile目前明确拒绝启动Session，不能忽略新前置条件。
- 用户授权本次健康校准仅使用已有基础饼干、基础肥皂和摸宠物；没有物品购买或新开课授权。

## 已确认的物品与健康记录

| 项目 | 校准初始 | 已确认使用 | 最后已确认库存 | 实测效果 |
|---|---:|---:|---:|---|
| 基础饼干 | 10 | 3 | 7 | 每份体力+10、心情+1 |
| 基础肥皂 | 10 | 0 | 10 | 完成洗澡及消耗仍未确认 |

首页初始健康67/66/74；3份饼干后97/66/77。此为截图时的数值，健康随时间可能变化，不能当作当前开课凭证。用户要求后续消耗品库存、使用统计及恢复操作自动化；目前仅完成上述人工校准记录，应用内库存账本尚未实现。

## 实际测试结果

- 当前Session改动曾遇到一处Kotlin字符串插值编译错误，已修正；随后test、assembleDebug、assembleDebugAndroidTest成功，主/测试APK安装Success。
- 原肥皂校准一次出现黑帧、主屏计数+2。用户确认期间手动触摸过，且预览Surface被打开，该次不能作为自动输入越屏或隔离通过证据。
- 第二次实际SOAP模板分数0.9692106935低于0.97，动作前拒绝，没有发送手势。根据已观察到的两种肥皂位置增加真实候选模板，没有降低阈值。
- 第三次连续约25秒擦洗输入校准：JUnit OK (1 test)，29.801秒，物理测试页触摸计数不变。after截图仍显示肥皂库存10，不能据此宣布洗澡业务成功。
- PetHealthCheck与截图回放测试已包含在最新构建，test、assembleDebug、assembleDebugAndroidTest成功并安装。回放2项中页面模板项通过，健康项失败：67/66/74读为66/65/73，须修正并补健康正例；不可启用Session。
- 最新显式FINGER事件持续擦洗：OK (1 test)，30.186秒，物理屏计数不增加；during帧确认肥皂随手势移动。before/after库存均为6且均提示已经很干净；此前10→6的消耗时点待定位，不计为本次消耗。
- 全过程技术与问题证据记录见《调试过程与技术留痕报告.md》，包含已解决与未解决问题。
- 1轮、5轮、10轮真实Session均未执行，Done条件未达到。

## 阻塞与下一步

先确认有效的洗澡手势与心情恢复冷却，完成健康值≥90的正例校准、恢复路径和开课前检查。再补齐可核对的基础物品账本。之后向用户说明具体真实Session动作与物品消耗范围，取得授权后依次执行1轮、5轮、10轮；任意状态识别失败停止，不改用盲点或降阈值凑PASS。


