# 开发进度快照：可配置会话与打工接入

更新时间：2026-10-18。状态：代码实现与 JVM 验证已有进展，产品真机验收未完成。

## 目标与范围

将固定课程、节数、护理阈值、每日预算的托管会话改为用户可配置方案，扫描本账号课程，并在金币不足时衔接已验证岗位的打工流程。本轮不做定时启动。

本文件是仓库内的交接快照。工作区父目录还有 `../progress.md`，该文件不受本仓库跟踪；继续开发前先阅读本文件。旧版里程碑报告保留其原始日期，不能作为本轮新功能验收结论。

## 已实现

- `SessionPlan` 与原子文件存储：课程、1–10 节、体力与清洁阈值 60–100、每日预算（不超过游戏 480 分钟上限）、金币不足先打工开关。
- `PetPlanSheetScreen` 方案编辑与保存，主页和设置页读取实际方案；缺失或过期课程档案限制启动。
- ML Kit 中文读屏、课程档案、扫描累计去重、按课程名称定位、课程详情标题校验；不匹配时停止。
- `DeviceWorkPageReader`、`DeviceWorkResourceReader` 及已有打工状态机、授权门禁、账本与托管会话的接线；本轮目标岗位为竹影武馆「帮武馆跑腿」。
- 修复真实课程列表暴露的问题：课名与下方时长配对、屏幕边缘碎片过滤，课程扫描和查找改为横向拖动。
- 本地构建缓存、临时截图调试目录、本地联接不纳入本次提交；设备预检脚本和 JVM 诊断脚本纳入仓库。

## 验收对照

| 验收项 | 结果 | 证据与局限 |
| --- | --- | --- |
| 主代码与单元测试代码可编译 | 通过（既有构建记录） | `:app:compileDebugKotlin`、`:app:compileDebugUnitTestKotlin` 成功；横向滑动位置修改后 APK 构建成功。保存本快照时不重新构建。 |
| JVM 回归 | 通过（最近一次运行） | 20 个测试类，`OK (273 tests)`，打印 `EXIT 0`；使用已编译类与直接 JUnitCore 运行器。 |
| 课程扫描与手工清单一致，置灰课排除 | 未通过 | 最后读取的档案只有两项，其中 `|看图识世界误` 为误识别。手工横向翻页已见至少六门课，清单未一致。 |
| 新方案路径完成一节已验证课程 | 未定 | 尚无本轮新路径通过证据；历史 `724614d` 版本曾有 `MANAGED_BATCH_PASS 3/3`，不能替代新版验收。 |
| 不存在课程以 `COURSE_NOT_FOUND` 安全停止 | 未定 | 有定位相关 JVM 测试，本轮真机检查未完成。 |
| 金币不足先打工、奖励入账、随后上课 | 未定 | 历史有打工成功记录；新版自动衔接未取得完整真机证据。 |

## 已知缺口与下一步

1. **课程横向滚动需重扫验证。** 手工滑动能翻卡，应用原先在 `y=800` 落到卡片上方；源码已改为 `(560,950) → (200,950)`，重新覆盖安装成功，但尚无应用内重扫一致的证据。先观察实际屏幕，再精确定位 UI 启动「读取我的课程」，核对保存档案与每张完整卡片。
2. **可选课程与预算时长尚未打通。** 方案页列出扫描到的已解锁课程，但 `SessionPlan.courseDurationMinutes()` 只接受默认「蹦蹦跳跳体能课」10 分钟；选择其他课程会在执行时触发 `PLAN_COURSE_DURATION_UNKNOWN`。需接入可信时长或限制选择后，再验证预算记账。
3. **剩余三项真机验收尚未完成。** 扫描可靠后依次验证单节课、课程不存在停止、打工衔接学习。
4. **临时 OCR 日志仍在。** `CourseScanFlow.readScreen()` 的 `course-scan-ocr` 是诊断代码；扫描验收后显式移除并重建。重新扫描不会自动删除日志代码。
5. **测试工具是本机诊断快照。** `tools/jvm-tests/README.md` 说明 classpath 与编译前置条件；`capture_cp.py` 不生成 `cp.lst`，不能当作全新环境的一键测试命令。`org.json` 在 JVM stub 中不可运行，方案/课程 JSON 往返仍需设备验证。
6. **构建含本机前置条件。** `gradle.properties` 保留本地验证时的 ASCII tmpdir；其他环境需调整。父工作区自带 JDK 21、Gradle 9.3.1、Android SDK，不上传。Android SDK 的 `aidl.exe` 上次仍是编码转发器，原件为 `aidl.exe.orig`，构建收尾需按本地配方还原。

## 本次 GitHub 保存

- 目标仓库：私有 `https://github.com/yzhlx/qqpet-automation`（remote `backup`）。
- 目标分支：`snapshot/20261018-productization`，新增独立快照分支，不覆盖远端已有功能分支。
- 上游 `Ynkcc/VirtualDisplay` 已归档且当前账号不可写，不作为目标。
- 本地已先保存开发中提交 `05648c2`（父提交 `724614d`）；远端已有功能分支为 `97876c2`。补查发现本地未上传历史包含原始设备资料，因此快照以远端已有提交为父，仅加入当前代码、必要模板、测试、文档及维护脚本，本地原分支与历史保持完整。
- 新增原始设备证据目录、整屏截图、Python 缓存和本地构建产物不进入快照。`app/src/debug/assets/pet_experimental/room.png` 的本地修改含账号头像，快照保留远端原文件；默认主程序所用的更新模板纳入快照。新快照不是本地分支整棵文件树的无差别复制。
- 本快照提交时推送尚待执行；保存成功与否以本地快照分支、远端 Git 分支 SHA 和 GitHub API 三者核对为准，具体提交链接在交付时给出。
- 本次保存当前开发进度，不发布 APK，不标记为产品验收完成。

## 当前任务：课程扫描与真机验收（2026-10-19）

- 目标：执行用户 /goal，扫准完整课程清单，接通非默认课时长/预算，完成单节课、COURSE_NOT_FOUND 安全停止、打工到账后学习三项真机验收。
- 验收：应用扫描与手工逐页对照一致并排除灰置；非默认课时长经详情核对、计入共享每日预算；三条设备路径有真实事件证据；相关 JVM 回归与 APK 构建通过，覆盖安装保留数据。
- 范围：既定 automation/ui 与测试、必要证据/进度说明。允许已授权护理、上课、已验证工作和覆盖安装；不卸载清数据、不推送发布、不外传设备原始资料或钥匙。
- 决策：用当前设备界面验证横滑与卡片解析；课程时长由扫描和详情确认后进入预算，不扩充固定课名列表凑过验收。
- 已完成：重读父 progress 和本文件，确认初始工作区干净，沿用已批准实现计划。
- 已完成（代码）：完整卡片横向配对、双向遍历和边界判断、complete 档案门禁、扫描状态/UI刷新、CourseTiming 与选中卡片时长复验、按实际分钟等结果；预算改为开始前同步预留，护理接收方案阈值。打工到账改为余额正差值，工资页必须点名岗位且分钟一致，读不到余额停止。
- 验证：解析器修正后主代码、单测和 classes.jar 重编成功。直接 JUnitCore `OK (319 tests)`，打印 `EXIT 0`（`.build-tmp/junit-20261019-r4.log`）。
- 当前状态：JVM 回归通过。可安装包为 `.build-tmp/qqpet-goal-20261019-r2.apk`：versionCode 60，SHA-256 `a63a2254…cf77` 与设备一致，包含解析器修正。旧的 `qqpet-goal-20261019.apk` 早于修正，不能安装。`aidl.exe` 已从 `.orig` 还原为 3512416 字节。真机验收未开始。
- 下一步：覆盖安装 versionCode 60（保留数据），预检确认锁屏与虚拟屏后再依次验收扫描、时长/预算、单节课、找不到课程停止、打工后学习。
- 设备授权（2026-10-19）：用户在最近一条真实消息补充“允许设备预检截图后再覆盖安装并验收”，因此原预检截图阻断具备新的明确授权。按预检画面→保留数据覆盖安装→扫描核对→单节课→不存在课程停止→打工后学习的顺序执行；自动任务通知不视为授权。
- 当前设备状态：预检截图通过，物理屏未锁、虚拟屏4为720×1600且宠物在房间。58→60覆盖安装成功，userId=10876/ceDataInode=66231前后一致、旧files保留；安装前文件备份为`.build-tmp/device-acceptance/goal-app-files-before-install.tar`。设置页确认Shizuku授权/虚拟屏就绪。开始应用内扫描验收；三项消耗性闭环未开始，仅主会话操作设备。

## 本段：课程扫描代码审查（2026-10-19）

- 目标：审查 `CourseScanFlow.kt`、`CourseCatalog.kt` 的范围差异与未提交改动，查找可复现的正确性缺陷。
- 验收标准：在当前上下文独立完成十个审查角度、候选去重复核与遗漏检查；每项保留问题均有具体触发场景和代码位置；最多三项，按严重性排序。
- 范围：只读目标代码及必要调用链、测试与本地证据；仅更新本进度文件，不修改业务源码，不操作设备，不提交推送。
- 决策：Agent 工具不可用，采用单次上下文内审查，不声称运行了多代理审查。当前分支没有 upstream，使用 `main...HEAD` 与 `git diff HEAD` 确定范围。
- 已完成：确认目标文件可读、分支与未提交状态，读取全局规则及现有进度。
- 当前状态：审查进行中。
- 下一步：读取完整差异、目标函数和调用链，逐项验证候选问题。
- 阻塞项：无静态审查阻塞；本段不重试先前被拒绝的设备操作。

## 本段：仓库整体单上下文审查（2026-10-19）

- 目标：最大力度审查 `main...HEAD` 及工作区差异和新增文件，覆盖用户十个角度。
- 验收标准：逐项检查差异、函数上下文和调用关系，自证具体失败场景，去重并补查；最多三项按严重性排序，输出 JSON。
- 范围：仓库源码/配置/测试与必要证据；只更新进度，不修改业务源码，不操作设备或提交推送。
- 决策：分支无 upstream，基线选 main；无 Agent 工具，全程单上下文，不声称多代理验证。
- 已完成：读取规则和进度，确定完整差异范围及未跟踪文件。
- 当前状态：审查进行中。
- 下一步：阅读差异与上下文，验证候选问题。
- 阻塞项：无静态审查阻塞；不重试此前被拒绝的设备操作。

- 扫描修复进展（2026-10-19）：v60真实扫描以 COURSE_SCAN_STALLED:RIGHT_CARD_CLIPPED 停止，旧档案未覆盖。手工连续MOVE横滑可翻页，两点跳移不动；改为6段连续MOVE。补左侧裁卡拒绝边界、按已校准264px中心距补读所有完整卡槽（避免全图OCR漏时长静默丢课），新增槽位回归。当前尚未重新编译/安装；等待预算与打工子任务收口后统一构建。

- 统一构建（2026-10-19 晚）：扫描修复（插值fling、左缘裁卡拒绝、264px槽位补读、FLING_STEPS限定）与记账/打工修复（跨实例共用锁DailyBudgetLedger、按钮先于预算、工分不再当累计差值）合并后重编通过（goal-compile-r6.log）。JVM 直接 JUnitCore `OK (331 tests)`、`EXIT 0`（junit-20261019-r5.log，319→331，新增槽位/锁/次序回归）。:app:packageDebug 出包 versionCode 60（git 提交计数未变，不提交则不涨号；同号 -r 覆盖安装可行，用 BUILD_TIME 区分）。aidl.exe 已还原原件。待办：foreman 钥匙重签+指纹核对→预检截图→覆盖安装→扫描验收。

## 当前续验（2026-10-20）

- 目标与范围：修复本轮真机阻塞，完成原定课程扫描准确/完整、时长预算、单节课、COURSE_NOT_FOUND、打工后学习；仅既定源码、测试、构建及本地设备验收。
- 验收标准：扫描逐门精确匹配；每条闭环均需真实日志/截图及持久化账本，单测通过不能代替设备通过。
- 已完成：入口折叠的 max 累加器错误导致整数溢出，已修复并有回归测试；设备已记录命中中心 (568,835)、enter-town-ok。奖励读取依据实际 ML Kit 日志修复“消耗体力5(当前24),请洁2(当前3)”合并/错字；r15 已见 work-option-selected 与 work-preview-verified。r16 加入滚动帧的升星条件身份校验，已编译、运行测试（退出0）、本地签名及保留数据覆盖安装。350 项通过记录来自 r15，r16 运行输出只保留 EXIT 0。
- 更正：先前“帧惯性导致读不到消耗”没有证据；四次重读仍失败，后续原始 OCR 证实合并行/请洁错字才是本次原因。先前坐标裁剪曾把溢出坐标变为 (0,0) 并实际点击，不能描述为“未误点”；画面未变化，流程随后因页面不符停止。后续异常坐标应拒绝输入。
- 当前状态：r16 已安装，尚未启动本轮；上一次停在岗位详情。课程扫描仍有 世界地理课→世界地理谋 一项错误，不能按“扫描准确”通过。三项消费/停止闭环仍待证据。
- 下一步：先确认启动/完成后的余额读取和资源门禁可测，再运行；同时收紧异常坐标处理。不重复提交、推送、上传。

## 本段：打工至学习闭环审查（2026-10-20）

- 目标：审查指定两份源码、P5.1/P5.3 已测资料及必要调用链，定位启动→完成→到账→回房→上课的真机验收阻断。
- 验收标准：单上下文八角度顺序审查，仅去重、不另行 verifier；最多十项 JSON，给出具体场景、行号及最小修复；排除入口折叠溢出和已修请洁字形。
- 范围：读取源码/本地证据及 adb 只读状态；仅更新 progress.md，不修改业务代码、不发起真实工作或消费、不提交推送。
- 已完成：确认无 upstream，采用 main...HEAD 加工作区差异；读取目标和打工状态机调用链。
- 当前状态：审查进行中，余额格式与实测页面不匹配已形成候选。
- 下一步：完成调用链及其余角度，去重并交付。
- 阻塞项：无静态审查阻塞。

- 已完成（收尾）：按 A逐行、B删除行为、C调用链、复用、简化、效率、根因层次、约定八角度顺序完成单上下文审查；仅合并重复候选，未运行 verifier、构建或消耗性设备验收。未找到适用的仓库/祖先 CLAUDE.md、CLAUDE.local.md、AGENTS.md；遵循用户全局规则。
- 证据：ADB 指定 DKS9K23420020242 只读读取 pet_mvp.log；最近会话 1792450029666 在 06:47:19 读到体力24/清洁3，06:47:33 读到金币1/学费22/护理估算53，06:47:58 在启动前页面身份检查停止。该旧身份判据源码已修，不作为当前缺陷；未进行新闭环。
- 当前状态：审查完成，交付 JSON 候选及最小修复建议。重点为余额读取、护理资金分支、工作后旧健康值、护理次数上限、单场收益不足、预检未绑定课程、启动健康读数过期、工资页首帧过早失败。清理类未占用正确性问题名额。
- 验收对照：八角度/具体代码位置与场景/排除两项指定旧问题/不修改业务源码——通过；新版完整真机闭环——未定，本段没有执行。
- 下一步：按交付问题先修前置资金与读数契约，再修到账及护理衔接；以实际24/3/1前置状态评估可执行方案，完整真机通过前不得引用历史P5.1为新版通过证据。


## 续验：城镇初始页与 70/70（2026-10-20）

- 目标/验收标准：恢复岗位详情页起步的会话，先只读健康检查证明回房链，再完成课程准确扫描与既定三项真机闭环；仅源码、测试、构建和本地设备证据。
- 用户决策：已明确选择阈值 70/70 并继续验收；设备 session-plan.json 已由 90/90 改为 70/70，回读字节一致，课名/工作项保持原值。
- 已完成：F1 课程页余额仅用于 before-start；F1b 余额未读日志；F6 工资页轮询；F2/F5 护理前资金检查及工作后重读健康；返回 lambda 编译修复。此前 367 项单测通过。
- 本次改动：WorkPageParser 提取城镇分类，DeviceWorkPageReader 复用；PetNavigation 在模板未知时读取城镇身份并返回；Controller/Service 注入同一 OCR 读取器；新增分类测试。主源码/测试编译通过，JUnit OK (373 tests)、EXIT 0。
- 构建：.build-tmp/qqpet-town-signed.apk 已打包、签名 SHA256 a63a2254…cf77 匹配，adb install -r Success。临时钥匙副本已删除；aidl.exe 已恢复原件 3512416 字节。
- 已证实：此前两次启动在 INITIAL_PAGE_UNKNOWN 停止，0 节完成；不能记作验收通过。
- 当前状态：城镇恢复代码已安装、尚待真机验证；70/70 已生效。扫描仍待重验世界地理课，三项闭环仍无完整证据。
- 下一步：只读健康检查验证岗位详情返回；重扫课程并核对，再执行单节课、COURSE_NOT_FOUND、打工后学习。若当前余额不足，记录实际前置阻塞，不把打工前置误称单节独立验收。
- 注意：上一段误运行 dumpsys deviceidle disable，需恢复其默认启用状态。没有卸载、清数据、提交、推送或外传。

## 本段：城镇初始页识别（2026-10-20 续）

- 目标：让托管会话能从职业小镇页起步（此前两次都在 INITIAL_PAGE_UNKNOWN 停止、0 节完成），并把 70/70 阈值落到设备上。
- 验收标准：真机三次闭环各有日志/账本证据；单测通过不代替设备通过。
- 已完成（规则）：新增 `WorkPageParser.parseTownPage/parseTownVenue`（纯函数，可单测）：详情页需 `去打工` + (`用时`|`升星条件`)，列表页需标题前缀 `宠物职业` + ≥3 个已知场馆；标题只匹配前缀，因为真机 OCR 会掉最后一个字（WinRT 读成「宠物职业小」）；打工中与工资明细弹窗一律判为 NONE，不授权离开。
- 已完成（导航）：`PetNavigation(flow, workReader?)`；`initialise` 在所有模板探针都不匹配时用 OCR 认城镇页，再走「详情页 →(80,160) 后退→ 城镇地图 →(80,160) 后退→ 户外地图 →(80,160)→ 房间」。返回链以户外地图模板为准（最多 2 次后退，每次后都必须能确认），不再依赖列表页读数；按钮坐标由 r18-vd4.png 像素实测（箭头 x 44..116, y 118..202，中心 76,160）。
- 已完成（证据）：ML Kit 真实读数取自设备日志 2026-10-20 06:37:56（`work-reward-read-unresolved` 的 lines=…），夹具即该读数；`r18-vd4.png` 为当前 VD4 画面（竹影武馆详情页，滚动到奖励块）。
- 已完成（构建）：主源码+单测编译通过；JUnit `OK (376 tests)`、EXIT 0（367→376）。新包已出但尚未重签安装：重签需要把项目钥匙复制到 ASCII 路径或把口令放在命令行，两种做法都被自动审批拦截，等待用户明确授权。
- 已完成（设备）：设备方案文件已改为 70/70（`session-plan.json` 206 字节，回读与写入逐字节一致；课程 `study:田径运动课`、工作项 `work:竹影武馆/帮武馆跑腿` 不变）。课程档案 7 门已解锁，含田径运动课 30 分钟；`世界地理谋` 仍是 OCR 错字，未修正。
- 当前状态：代码与单测就绪；真机托管会话仍未启动。设备上装的是上一版城镇修复（用整标题判定），新包未装。
- 下一步（需授权后执行）：①授权重签安装；②授权在物理屏点击已核对的「健康检查/托管」按钮；③依次跑：只读健康检查验证返回链 → 托管（打工后学习）→ 单节课 → COURSE_NOT_FOUND 停止；④按实跑结果修正课程档案错字。
- 阻塞项：自动审批拒绝 `adb shell input tap`（理由：目标未证实为只读、需用户明确授权该自动化）与钥匙口令外露/复制，故真机点击与重签安装均未执行。

## 续验：岗位自身消耗与低状态护理（2026-10-20）

- 目标/验收标准：延续 /goal，扫准并翻完整课程、核对非默认课时长和共享预算，获取单节课、COURSE_NOT_FOUND、打工后学习三条设备证据。单测不能代替真机验收。
- 改动范围：护理/打工接线源码、相关JVM测试、本地构建和原定设备验收。
- 已证实：城镇恢复和岗位选择已在设备走通；会话 1792482715388 进入详情并通过 work-preview-verified，随后 WORK_START_BUTTON_NOT_FOUND 停止。游戏实际状态为体力16、清洁0、金币1；岗位消耗体力5/清洁2，清洁不足时界面拒绝启动，点击同处也无变化。香皂片2金币/+10清洁，饼干5金币/+10体力；1金币购买饼干被拒且兑换页保持打开（1792483764931）。
- 实现决策：打工不应用方案70/70阈值，只在游戏岗位自身清洁消耗不足时尝试洗澡。余额不足不预拒绝洗澡（可能有库存），购买结果未知仍停止。课程不存在应在护理/打工前停止。
- 已改源码：CareAllowance 按差值计算护理次数与会话预算；PetCareFlow 提取 cleanLoop/cleanTo；PetSessionController 补 preparePetForWork 并接入岗位前清洁。新增 CareAllowanceTest/WorkPreparationTest。构建日志 .build-tmp/build-out.txt 为 BUILD SUCCESSFUL；JVM测试运行中，尚未重签安装本次改动。
- 当前状态：工作前补清洁与预算正在复核；扫描代码已有 世界地理谋→世界地理课 的限定纠正，设备旧档案仍需重扫。三项真机闭环均未宣布通过。
- 下一步：完成JVM与代码复核→修必要问题→重签覆盖安装→从UI重扫和执行可运行验收→收集日志/账本。
- 阻塞项：金币1而无香皂库存时，游戏要求先买2金币香皂才能开工，缺1金币；不自行执行范围外的领金币/充值操作。若实跑再次证实则明确标记阻塞。

## 续验：岗位自身消耗与低状态护理（2026-10-20 续）

- 目标/验收标准：延续 /goal；单测不能代替真机验收。
- 改动范围：护理/打工接线源码、相关JVM测试、本地构建、已装版本上的课程扫描验收。
- 已完成（源码）：CareAllowance 按差值计护理与会话预算，并覆盖空库存两次占用；PetCareFlow 提取 cleanLoop/cleanTo，拥有饼干走场景确认+盘面点击增益校验；PetExchangeFlow 按注入证据结算未确认支付；PetSessionController 仅按岗位自身清洁消耗备工，打工前重读课程余额并刷新健康。新增 CareAllowanceTest/WorkPreparationTest。
- 已完成（构建/单测）：`.build-tmp/care-work-build.log` BUILD SUCCESSFUL；`.build-tmp/care-work-junit.log` OK (390 tests) EXIT 0；未签名包 `app/build/outputs/apk/debug/app-debug.apk` versionCode 60 / sha256 9195bc2974c50f47b32c73cf0622e3656ddcf1600c85a8bbb5a83756b005b8df。
- 已完成（设备扫描）：已装版本点「读取我的课程」完成；会话证据 `pet_mvp_session/1792486360478` 已拉到 `.build-tmp/device-acceptance/s20-scan/`；`files/course-catalog.json` 现为 complete=true、7门已解锁，含 `study:世界地理课` 30分钟（不再是「谋」）；停止原因「课程读取完成 7 门（可选 7 门）」；今日预算仍 0/480。
- 当前状态：扫描准确/完整与非默认课时长档案已有设备证据。新护理/打工修复包未重签安装（复制钥匙到 ASCII 临时目录再次被自动审批拦截）。三项消耗闭环仍未通过：托管/单节课受游戏侧 1金币无法买2金币香皂阻塞；COURSE_NOT_FOUND 真机会话证据未取。
- 下一步：用户再次确认签名钥匙来源与临时目录后重签覆盖安装；有≥2金币后跑托管（打工→上课）与单节课；COURSE_NOT_FOUND 用缺失课名验证停止且无误点。
- 阻塞项：重签安装待确认；金币1、无香皂库存时游戏要求先买香皂才能开工。

## 本段：COURSE_COST_UNREADABLE 修复与真机复核（2026-10-20）

- 目标：修学费读数脆性问题，使田径运动课详情页能读出 tuition=66，再推进到游戏经济阻塞点。
- 验收：JVM 回归通过；覆盖安装后真机托管田径运动课出现 prework-tuition tuition=66 coins=1；不再以 COURSE_COST_UNREADABLE 停；后续按规则停在 WORK_PREP_SOAP_PAYMENT_UNCONFIRMED。
- 范围：CourseCostReading.kt、PetSessionController.readCourseCost、对应单测；按已授权路径重签覆盖安装；不卸载清数据、不提交推送、不外传钥匙。
- 根因（session 1792496334314 诊断日志）：田径课简介含「提升体力和反应」，旧逻辑用全文第一个「体力」裁窗，把后面的「|消耗66(当前1),体力15(当前13),」裁掉；另有金币图标被读成前导 0、CJK 夹空格。
- 已完成：
  - 解析改为从「消耗」起、到其后第一个「体力」止；严格正则优先，失败再取窗内首个正数「N（当前 M）」；CJK 关键字允许夹空格。
  - readCourseCost 最多 4 次新鲜帧重试，失败写 course-cost-read-unresolved（含 ML Kit 原文）。
  - JVM：OK (394 tests)，日志 .build-tmp/cost-fix-junit-r3.log。
  - 签名 APK：.build-tmp/qqpet-cost-fix-20261020.apk，证书 a63a2254…cf77，覆盖安装 Success，versionCode 仍 60，userId=10876。
  - 真机复核 session 1792497899563：course-selected study:田径运动课 minutes=30 → prework-tuition tuition=66 coins=1 careEstimate=44 → 买皂 exchange-verified soap=1/total=2 → 停 WORK_PREP_SOAP_PAYMENT_UNCONFIRMED:balance=1 soap=2 need=2。证据目录 .build-tmp/device-acceptance/s20-costfix2/1792497899563/。
- 当前状态：COURSE_COST_UNREADABLE 已修并真机证实；CNF 真机 PASS（1792489789008）；单节上课与打工后学习仍被游戏经济卡住（金币 1、香皂 2、岗位清洁≥2、工分场馆未开）。
- 下一步：经济阻塞需用户侧补币/补皂或授权其他获取路径后，才能继续两项真机验收。方案现为田径运动课×1 / 70/70 / 480 / workBeforeStudy=true。
- 阻塞项：游戏经济（balance=1 < soap=2）；未授权卸载清数据或游戏外操作。

## 本段：三项真机验收收口（2026-10-20 22:11）

- 目标：完成 /goal 三项真机验收：单节上课、找不到课程停止、打工后学习。
- 验收对照：
  1. 找不到课程停止：PASS。session 1792489789008，stopReason COURSE_NOT_FOUND:验收不存在课，零购买。
  2. 单节上课：PASS。session 1792503898355，verdict MANAGED_BATCH_PASS 1/1；course-selected 蹦蹦跳跳体能课 10 分钟；course-tuition tuition=22 coins=95；course_ms≈603586；ROUND_COMPLETE + SESSION_COMPLETE；今日已记 10/480。证据 .build-tmp/device-acceptance/s20-managed-pass/1792503898355/。
  3. 打工后学习：半通过。用户串门补币后，本机又手动完成两班「帮武馆跑腿」（各 +68）；随后托管会话护理到 72/76 并成功上课。因开课前金币已 95≥22，应用正确跳过会话内打工（无 prework-tuition）。会话内「金币不足→打工→上课」完整自动链路仍待金币再次低于学费时复验。
- 附带修复：COURSE_COST_UNREADABLE 已修（从「消耗」起窗），真机读到 tuition=66/22。
- 当前方案：study:蹦蹦跳跳体能课 ×1 / 70/70 / 480 / workBeforeStudy=true / 帮武馆跑腿。
- 当前状态：三项中 CNF 与单节上课真机 PASS；打工后学习的「打工后能上课」已证实，会话内自动打工触发待金币再短缺时补证据。
- 下一步：若需严格的会话内打工→学习证据，等金币 < 学费后再开一局托管；否则 goal 可按单节+CNF 完成、打工链路已打通收口。
- 阻塞项：无应用侧阻塞。

## 本段：stale-before-return 是否需要新 JVM 回归（2026-10-21）

- 目标：判断 `earnTuitionAndCareFundsBeforeCare` 在无打工路径仍会因预检导航超时 60s freshness 而 HEALTH_STALE（session 1792513340455）的修复，是否需要新增纯 Kotlin 回归，还是注释+真机证据即可。
- 验收：给出 needNewTest / suggestedTestName / rationale / relatedTests。
- 结论：不需要新纯 Kotlin 回归（needNewTest=false）。
  - 修复点在 `PetSessionController.earnTuitionAndCareFundsBeforeCare`：够币与不够币路径都先回房再 `care.readHealth("prework-fresh"|"after-work")`，不再把过期的 `before` 交给护理。
  - 该类依赖 Context / HeadlessDisplayCapture / PetInputController / 导航与协程，当前仓库无其 JVM 替身；可测的纯决策已抽成 `CareFundsEstimate` / `TuitionWorkDecision`，60s stale 门禁已由 `ObservationFactsTest` / `PetStudyHealthPolicy` / `PetRuntimePresentationTest` 覆盖。
  - 再抽一层「预检后必须重读」的纯函数过于单薄，不能锁住导航时序；应以源码注释（已含 1792513340455）与真机证据为准。
- 相关已有测试：`CareFundsEstimateTest`、`PetWorkFlowTest`（payroll award）、`ObservationFactsTest.staleAndReversedClockCannotAuthorize`、`PetRuntimePresentationTest`（HEALTH_STALE）、`WorkPreparationTest`。
- 当前状态：测试必要性已裁定；本段无源码改动。
- 下一步：继续真机复验「金币够 tuition+care 但仍走完预检导航」路径，确认事件含 `prework-fresh` 且不再 HEALTH_STALE。

## 本段：预检导航后过期健康读数扫描（2026-10-21）

- 目标：落实/核对 `earnTuitionAndCareFundsBeforeCare` 无打工路径回房后重读 `prework-fresh`；并搜索其它在 >60s 导航后仍返回旧 `PetCareReading` / `HealthObservations` 的位点。
- 验收：源码契约一致（无打工/打工后都重读）；找出的同类问题有具体 file:line 与失败场景；可 JVM 覆盖的点补回归。
- 已完成：
  - 核对 `PetSessionController.earnTuitionAndCareFundsBeforeCare`：够币与不够币未打工路径均已 `returnHome()` + `care.readHealth("prework-fresh")`；打工后 `after-work`。真值修复已在树中。
  - 修正过时 KDoc（仍写「否则返回 original before」）。
  - 发现同类点：`DeviceWorkResourceReader.readEnergy` 对 `care.latestReading` 再套 60s `at()`，城镇导航可超过 60s，使 `PetWorkFlow.start` 在 grant 已 consume 后报 `WORK_ENERGY_UNREADABLE`。已改为原样携带 care 面板 Known，并加 JVM 回归 `workStartEnergyKeepsACarePanelKnownOlderThanSixtySeconds`。
- 排除：`PetStudyFlow` 生产路径有 `freshReady`；`healthJson`/`beforeRecord` 只做历史记录；`cleanTo` 早退用的是刚读的 work-prep。
- 当前状态：控制器无打工重读已确认；打工开工能量门禁同类缺陷已修并补测。未跑完整 JUnit/未重装真机。
- 下一步：编译并跑 `DeviceWorkResourceReaderTest`（或全量 JVM）；真机复验 prework-fresh 与打工启动能量。

## 本段：工资页到账证据 + 预检健康过期修复（2026-10-21）

- 目标：打通「金币不足→会话内打工→上课」真机验收；此前卡在 WORK_COIN_DELTA_UNREAD 与 HEALTH_STALE。
- 验收对照：
  1. 找不到课程停止：PASS（既有）session 1792489789008。
  2. 单节上课：PASS（既有）session 1792503898355 MANAGED_BATCH_PASS 1/1。
  3. 打工后学习：未完成。工资页「金币 +N」到账门禁与预检后重读健康均已改代码+JVM，但设备离线，尚未覆盖安装后复验。
- 已完成：
  1. WORK_COIN_DELTA_UNREAD 修复：`CompletionSurface`/`CompletionResult` 增加 `coinAward`；`WorkPageParser.parsePayrollCoinAward` 读「金币 +N」/「基础工资 N」；`PetWorkFlow.waitAndVerify` 在余额差值未读时可用正数工资页 award；`parseBalanceLine` 仍拒绝把 award 当余额。JVM：`OK (396 tests)`。曾装到机上的 APK：`.build-tmp/qqpet-payroll-award-20261021.apk`（SHA-256 ef668c74…；证书 a63a2254…cf77）。
  2. HEALTH_STALE 根因（session 1792513340455 / events `9e7a742e-…`）：prework 选课读学费约 61s，超过 60s 健康新鲜度后仍把旧 `before` 交给护理。`earnTuitionAndCareFundsBeforeCare` 无班/学费够用路径改为 `returnHome()` 后 `care.readHealth("prework-fresh")`；有班路径仍 `after-work`。证据目录 `.build-tmp/device-acceptance/s20-workstudy-rerun/`（verdict HEALTH_STALE；当时 coins=101≥tuition+care，未进打工）。
  3. 新包已签未装：`.build-tmp/qqpet-prework-fresh-20261021.apk`（SHA-256 18573e0a…；证书 a63a2254…cf77）。JVM 再跑 `OK (396 tests)`。`aidl.exe` 已还原原件。
- 当前方案（设备上上次读取）：study:田径运动课 ×1 / 100/100 / 480 / workBeforeStudy=true / 帮武馆跑腿。
- 当前状态：代码侧两项阻塞已修；adb 显示 `DKS9K23420020242 offline`，用户要求先写 progress 停下，不做安装/托管。
- 下一步（设备上线后）：
  1. `adb install -r .build-tmp/qqpet-prework-fresh-20261021.apk`（TEMP/TMP 指 `.foreman/tmp`）。
  2. 确认 VD4 宠物在 display 4；若黑屏则 force-stop QQ + 搜索入口拉回。
  3. 托管田径×1：期望出现 `prework-fresh` 或 `prework-needed`→工资页 `work-reward-payroll-award`→护理→上课→`MANAGED_BATCH_PASS`。若金币仍 ≥ 学费+护理估算，需先压币（阈值已 100/100）再验严格打工链路。
- 阻塞项：设备 offline；未授权卸载清数据/推送/外传钥匙。

## 每日重复玩法与上限控制（2026-11-04，feat/onboarding-troubleshooting-p0）
- 目标：
  1. SOC-03 每日签到：识别房间签到入口、开窗前/开窗后已签到状态检测（防重复执行）、领取按钮与到账解析、安全回房。
  2. SOC-04 信箱奖励待领：识别信箱入口与未读状态、空信箱自动安全跳过、一键领取与到账解析、安全回房。
  3. STR-14 冒险每日次数与上限控制：扩充冒险上限识别（含上限已满/明日再来等关键词），提取剩余轮次，支持 onBeforeStart 延迟授权门禁与上限平滑结束。
  4. 编排与 UI 闭环：在方案配置页 PetPlanSheetScreen 增加每日签到与信箱待领独立开关（默认关闭，不擅自执行），增加冒险每日轮次上限配置；运行记录与历史详情统计签到与信箱待领完成次数。
- 改动：
  - SignInParser.kt & PetSignInFlow.kt：实现签到流程与解析。
  - MailboxParser.kt & PetMailboxFlow.kt：实现信箱流程与解析。
  - AdventureParser.kt & PetAdventureFlow.kt：扩充上限关键字识别与延迟授权门禁。
  - PlanRunner.kt, PlanDeviceExecution.kt, PetSessionController.kt：接入签到与信箱步骤执行、预算防重放重入校验、记录回写。
  - PlanV2.kt, PlanV2Store.kt, PlanExecutionModels.kt, PlanRunJsonCodec.kt, PlanRunStore.kt：数据持久化支持签到与信箱开关和完成状态。
  - PetPlanSheetScreen.kt, PetHomeScreen.kt, SessionStartRequest.kt：UI 方案配置开关与摘要穷举。
  - PetEvidence.kt, RecordsPresentation.kt：运行报告与卡片支持签到、信箱完成展示。
  - 单元测试：新增 SignInParserTest (7), MailboxParserTest (5)，更新 AdventureFlowTest, PlanRunnerCompositionTest, PlanV2StoreTest, SessionStartRequestTest。
- 验证结果：
  - 单元测试：95 个测试套件、913 项单元测试 100% PASS（0 failures, 0 errors）。
  - 构建打包：assembleDebug 成功，生成 app-debug.apk（47.9MB）。
  - 真机安装：通过 ADB 成功覆盖安装至真机 DKS9K23420020242（Success）。
- 提交与推送：提交 feat(daily): 增加每日签到、信箱待领与冒险上限控制 (SOC-03, SOC-04, STR-14)，推送到远端 backup/feat/onboarding-troubleshooting-p0，更新 PR #4。
- 下一步：实现第二阶段 SOC-05 福袋任务（独立开关、扫描间隔、每日上限），或进入第三阶段任务目标策略排班（STR-01~05）。

## 福袋任务与失败安全跳过（2026-11-03，feat/onboarding-troubleshooting-p0）
- 目标：
  1. SOC-05 福袋任务：独立开关、扫描间隔控制（默认 30 分钟）、今日上限配置（默认 5 次）。
  2. 确保失败不阻塞主流程：解析/流程异常自动安全回退为 Skipped 状态，不影响后续打工、学习、护理。
  3. 全链路闭环：新增 PlanStepType.LUCKY_BAG、LuckyBagStep、解析器 LuckyBagParser、执行器 PetLuckyBagFlow、UI 配置卡片与运行记录。
- 改动：
  - LuckyBagParser.kt：识别福袋入口、冷却倒计时（秒、分秒格式）、今日上限判定（文本和剩余次数），解析开启按钮与关闭按钮、金币与道具奖励。
  - PetLuckyBagFlow.kt：开窗检测、上限/冷却跳过、延迟授权门禁 (onBeforeClaim)、开启动作与结算解析、安全关闭弹窗。
  - PlanV2.kt & PlanV2Store.kt：新增 LuckyBagStep（含 maxDailyCount 与 scanIntervalMinutes），JSON 编码/解码支持。
  - PlanExecutionModels.kt：RunCounts 新增 luckyBagCompleted；SkipReason 新增 UNAVAILABLE（用于失败安全跳过），StepExecution 校验允许 LUCKY_BAG 类型的跳过原因。
  - PlanDeviceExecution.kt & PlanRunner.kt：LUCKY_BAG 纳入执行类型分发与 0 分钟预算/防重放校验。
  - PetSessionController.kt：runPlanLuckyBag 接线；实现基于 lastLuckyBagCheckMs 的扫描间隔防抖；Blocked/Failed 降级为 Skipped(UNAVAILABLE) 实现失败不影响主流程。
  - PlanRunStore.kt & PlanRunJsonCodec.kt：run-record.json 持久化 luckyBagCompleted。
  - PetEvidence.kt & RecordsPresentation.kt：SessionRunRecord 解析 luckyBagCompleted，历史卡片标签展示“福袋 x 次”。
  - SessionStartRequest.kt & PetHomeScreen.kt：模式枚举与主页摘要展示支持。
  - PetPlanSheetScreen.kt：方案配置页增加“福袋任务”开关（默认关闭），“福袋上限（次）”与“扫描间隔（分）”参数配置。
  - 单元测试：新增 LuckyBagParserTest (8)，更新 PlanV2StoreTest，PlanRunnerCompositionTest（含失败跳过与计数测试）。
- 验证结果：
  - 单元测试：96 个测试套件、924 项单元测试 100% PASS（0 failures, 0 errors）。
  - 构建打包：assembleDebug 构建通过，APK 45.8MB。
  - 真机安装：通过 ADB 成功覆盖安装至真机 DKS9K23420020242（Success）。
- 提交与推送：提交 feat(daily): 增加福袋任务、每日上限控制与失败安全跳过 (SOC-05)，推送到远端 backup/feat/onboarding-troubleshooting-p0，更新 PR #4。
- 下一步：实现第二阶段剩余的每日完成次数及收益记录汇总呈现（OBS-03~06），或进入第三阶段任务目标策略排班（STR-01~05）。

## 金币底线与每日学习花费上限（2026-11-03，feat/onboarding-troubleshooting-p0）
- 目标：STR-13（用户自定最低金币余额与每日学习净支出上限），并覆盖 STR-05 中「学费/金币不足」资源分支的剩余缺口。
- 改动：
  - PlanV2 顶层新增 minCoinFloor、maxDailyNetSpend（可空，null=关闭；默认关闭不启用），含取值校验、copy/equals/hashCode 同步。
  - PlanV2JsonCodec 与 PlanRunJsonCodec 双路编解码：字段缺省读回 null，关闭时不写入 JSON。
  - CoinSpendPolicy（新增，纯函数 JVM 可测）：余额-总学费低于底线 → COIN_FLOOR_BREACH；今日已确认花费+本次方案学费超过上限 → COIN_SPEND_DAILY_CAP_EXCEEDED。
  - PetSessionController：ResourcePreflightPort 内学习步骤执行前接入 CoinSpendPolicy，命中即 ResourcePreflight.Insufficient（安全停止，不去扣费）；computeConfirmedNetSpendToday 仅从当日已确认 run-record（roomCoinsBefore/After 都已读出）求和，未知记录按 0 处理，不把未知当成功。
  - PetPlanSheetScreen：学习开启时展示「金币保护（STR-13）」卡片，金币底线/每日花费上限各自带 Switch（默认关）+ 数值参数行，沿用既有 Switch 风格。
  - 单元测试：新增 CoinSpendPolicyTest（6 项），PlanV2StoreTest 增加字段往返与省略校验。
- 验证结果：全量单元测试 97 套件 / 931 项 100% PASS（0 failures, 0 errors, 3 skipped）；assembleDebug 构建通过（APK 约 45.8MB）；ADB 覆盖安装 DKS9K23420020242 成功。
- 提交与推送：feat(plan): 金币底线与每日学习花费上限门禁 (STR-13)，推送 backup/feat/onboarding-troubleshooting-p0，更新 PR #4。
- 下一步：第三阶段 STR-15 任务间隔与冷却（失败后合理冷却、不高频空转），或 STR-16 策略解释（展示本轮选择/排除原因）。

## 任务间隔与失败冷却（2026-11-03，feat/onboarding-troubleshooting-p0）
- 目标：STR-15 任务间隔与冷却（按用户设定的间隔执行，失败后合理冷却，不高频空转），并补齐 STR-04 自定义排班中的「任务间隔」缺口。
- 改动：
  - PlanV2 顶层新增 stepIntervalMinutes（相邻步骤最小间隔）、failureCooldownMinutes（失败后冷却），均为可空且默认关闭；含取值校验与 copy/equals/hashCode 同步。
  - PlanV2JsonCodec 与 PlanRunJsonCodec 双路编解码支持新字段，关闭时不写入 JSON。
  - PlanRunner：第二步起按「上一步 endedAtMs + 间隔」等待，等待前后各执行一次 checkAuthority，暂停/停止可打断；等待函数 waitMs 可注入，生产默认真正 delay。
  - FailureCooldownPolicy（新增，纯函数 JVM 可测）：同一 planId 最近一次 FAILED/UNCONFIRMED 结束后在冷却期内拒绝重开；STOPPED（用户主动停）与 COMPLETED 不触发；没有 endedAtMs 的记录不参与判定（未知不当作失败证据）。
  - PetAutomationService：runSession 开头做冷却检查，命中则 stopSession 并提示还需等待多少分钟，避免失败后反复空转。
  - PetPlanSheetScreen：新增「任务节奏（STR-15）」卡片，步骤最小间隔与失败后冷却各自带 Switch（默认关）+ 数值行。
  - PetUserManualScreen：使用攻略补充「金币底线与花费上限」「任务节奏：间隔与失败冷却」两条说明。
  - 单元测试：新增 FailureCooldownPolicyTest（7 项）、PlanRunnerIntervalTest（3 项），PlanV2StoreTest 增加字段往返与省略校验。
- 验证结果：全量单元测试 99 套件 / 942 项 100% PASS（0 failures, 0 errors, 3 skipped）；assembleDebug 构建通过；ADB 覆盖安装 DKS9K23420020242 成功。
- 提交与推送：feat(plan): 任务间隔与失败冷却，避免高频空转 (STR-15)，推送 backup/feat/onboarding-troubleshooting-p0，更新 PR #4。
- 下一步：第三阶段 STR-16 策略解释（展示本轮为什么选学习/打工/冒险以及排除其他选项的原因），或 STR-08/09 属性目标与均衡目标。

## 策略解释（2026-11-03，feat/onboarding-troubleshooting-p0）
- 目标：STR-16 向用户说明为什么选择或跳过某个任务；并落实「完成、失败、跳过和待确认分别记录，不能混为成功」。
- 改动：
  - StrategyExplanation（新增，automation 层纯函数，JVM 可测）：把方案勾选的动作与本轮真实执行结果翻译成逐个动作的事实——完成 / 跳过(带原因) / 被阻断 / 失败 / 无法确认 / 未勾选 / 未执行。没勾选与没跑到都不会被写成已完成；前一步失败后没轮到的动作写成「前一步没有完成」，预检就挡住时写成「本轮没有开始执行」。
  - ManagedRuntimeState 新增 strategyActions；PetSessionController 在方案正常结束与中途停止两条路径上都按执行记录发布该字段，读回失败时不会覆盖已经证明的结果。
  - run-record.json 持久化 strategyActions(label/outcome/reason)，SessionRunRecord 读回，认不出的条目直接丢弃不猜。
  - StrategyPresentation（新增，ui 层纯 JVM）：把事实翻译成中文短句，认不出的原因码原样显示，不编造解释。
  - 主页新增「本轮策略 / 上一轮策略」卡片，逐条列出做了什么、跳过了什么及原因、哪些没勾选；记录页历史卡片单独一行显示同一句摘要（不再挤进会被截断的详情行）。
  - 使用攻略「配置托管方案」补充第 6 条「本轮策略说明」。
  - 单元测试：新增 StrategyExplanationTest（7 项）与 StrategyPresentationTest（5 项）；真机插桩测试新增 StrategyRecordJsonTest（3 项，org.json 只在设备可用，覆盖写读往返）。
- 验证结果：全量单元测试 101 套件 / 954 项 100% PASS（0 failures, 0 errors, 3 skipped）；assembleDebug 构建通过（APK 45.9MB）；ADB 覆盖安装 DKS9K23420020242 成功，App 启动无崩溃；真机插桩测试 3 项 PASS（0 failures, 0 errors）。主页与历史卡片上的策略说明尚未在真机上跑过一轮真实方案，待验收。
- 提交与推送：feat(plan): 策略解释，展示本轮选择与排除原因 (STR-16)，推送 backup/feat/onboarding-troubleshooting-p0，更新 PR #4。
- 下一步：第三阶段剩余 STR-08/09 属性目标与均衡目标（StudyStep.targetAttribute 已存在），或 STR-01/02 学习优先与打工优先模式。

## 学习优先与打工优先的受阻转移（2026-11-03，feat/onboarding-troubleshooting-p0）
- 目标：STR-01/STR-02 学习优先、打工优先模式。核实结论：优先本身早已具备（WorkTrigger.ALWAYS=打工优先、IF_FUNDS_SHORT=学习优先，方案页开关「仅金币不足时打工」），真正缺的是验收条款里的「无法继续时按规则选择替代任务 / 资源不足或疲劳时按规则转移」——原实现是 stop-on-failure，任何一步受阻就结束整轮，后面的动作全部不执行，用户等于白等一轮。
- 改动：
  - StepTransferPolicy（新增，automation 层纯函数，JVM 可测）：受阻后要不要转移、转到哪一步，只看四件事——用户是否打开开关、受阻结果是否「确定失败」（BLOCKED/FAILED）、方案里后面还剩什么动作、本轮已转移几次。结果无法确认（UNCONFIRMED）或用户主动停掉（CANCELLED）绝不转移：那种情况下动作可能已在游戏里发生，再往下做可能重复扣费或重复领取。每轮最多转移一次，第二次受阻就停下，避免连环空转。转移目标就是方案里下一个动作——用户自己排的顺序就是规则。
  - PlanV2 顶层新增 transferOnBlock（默认关闭，新玩法不默认执行），同步 copy/equals/hashCode；PlanV2JsonCodec 与 PlanRunJsonCodec 双路编解码支持，关闭时不写入 JSON。
  - RunSnapshot 新增 transfers 记录（谁受阻、什么结果、为什么、转到了哪个动作），并把「停住的步骤之后不许再执行」的约束改成「登记过转移才允许」：执行顺序不被悄悄破坏，转移事实可核对、可持久化。
  - PlanRunner：步骤受阻时按规则转移并继续执行后面的动作；发生过转移的轮次终态为 FAILED（第一个动作确实没做成），没有失败的轮次仍是 COMPLETED。
  - StrategyExplanation / StrategyPresentation：受阻的动作会写明「已转到XX」；策略摘要新增「受阻：」分组，不再只报执行与跳过。
  - PetPlanSheetScreen：新增「优先与转移（STR-01/02）」卡片，说明学习优先与打工优先的区别，并提供「受阻时按规则转移」开关（默认关，勾选两个及以上动作时显示）。
  - 使用攻略「配置托管方案」补充第 7 条「优先模式与受阻转移」。
  - 顺带修掉两个既有缺陷：PlanRunJsonCodec 之前不保存 StudyStep.targetAttribute（属性目标会在运行记录里丢失），PlanV2.hashCode 之前漏算 failureCooldownMinutes。
  - 单元测试：新增 StepTransferPolicyTest（6 项）、PlanRunnerTransferTest（6 项）；另补 StrategyExplanationTest、StrategyPresentationTest、PlanV2Test、PlanV2StoreTest 共 5 项。
- 验证结果：全量单元测试 103 套件 / 971 项 100% PASS（0 failures, 0 errors, 3 skipped）；assembleDebug 构建通过（APK 49.1MB）；ADB 覆盖安装 DKS9K23420020242 成功，App 启动无崩溃；真机确认方案页出现「优先与转移（STR-01/02）」卡片、开关可打开并写入 plan-v2.json（transferOnBlock:true），重新进入方案页开关恢复为开启。
- 待验收：真实跑一轮「打工受阻 → 转护理/学习」的真机场景尚未执行（需要体力不足或金币不够的实际局面），该行为目前只有 JVM 测试覆盖。
- 提交与推送：feat(plan): 学习优先与打工优先，受阻时按规则转移 (STR-01/02)，推送 backup/feat/onboarding-troubleshooting-p0，更新 PR #4。
- 下一步：第三阶段 STR-08 属性目标（今日停止线）与 STR-09 均衡属性目标（轮换力量/智力/魅力并记录每项变化）；需先核实属性数值能否读取，目前打工详情页只读到属性名，读不到数值。

## 属性目标与今日停止线（2026-11-03，feat/onboarding-troubleshooting-p0）
- 目标：STR-08 属性优先目标。核实结论：「按选择的属性提高学习收益」在第一阶段已具备（选属性 → StudyAttribute.resolveCourse 自动匹配该属性的初级学园课，StudyStep.targetAttribute 也存进方案）；真正缺的是验收条款里的「可设置今日停止线」。
- 改动：
  - StudyDailyCapPolicy（新增，automation 层纯函数，JVM 可测）：allowedRounds(今日已确认节数, 停止线, 方案节数) → 本次还能上几节；sumConfirmedToday 只统计当天、只统计写进记录的已确认节数。停止线为 null 时方案照旧按自己排的节数执行。
  - PlanV2 顶层新增 dailyStudyRoundCap（可空，null=关闭；默认关闭不启用），取值 1..50，且方案必须含学习步骤；同步 copy/equals/hashCode。
  - PlanV2JsonCodec 与 PlanRunJsonCodec 双路编解码：字段缺省读回 null，关闭时不写入 JSON。
  - PetSessionController：预检里先算今天还能上几节，学费预留、金币底线/每日花费上限闸门、每日分钟预算全部改用这个数；今天不再学习时照常打工（不会因为学费预留为 0 把打工也一起跳掉）；学习步骤按允许的节数开课，一节都不剩就以「已达今日次数上限」安全跳过，方案里后面的动作照常执行。
  - 今日已确认学习节数来自各会话目录的 run-record.json（新增 studyRoundsCompleted 与 targetAttribute 两个字段）；只认写进记录的数字，旧记录贡献 0，没读到结果的课不计入（未知不当作完成）。
  - StepExecution 白名单新增「学习 + 已达今日次数上限」：跳过仍算正常结束，策略说明里显示「已达今日次数上限」。
  - PetPlanSheetScreen：培养目标属性卡片内新增「今日停止线」开关（默认关）与「今日上限（节）」数值行，并显示今日已确认学习节数；取消勾选学习时自动关掉停止线（否则方案会因没有学习步骤保存失败）。
  - 用户没点属性但选的就是该属性课程时按课程如实记下属性，运行记录里才分得清学的是什么。
  - 使用攻略「配置托管方案」补充第 8 条「属性目标与今日停止线」。
  - 单元测试：新增 StudyDailyCapPolicyTest（5 项）、PlanRunJsonCodecTest（2 项），PlanV2Test 与 PlanV2StoreTest 各补 1 项；真机插桩 StrategyRecordJsonTest 补 3 项（org.json 只在设备可用，覆盖新字段写读往返、旧记录保持未知）。
- 验证结果：全量单元测试 105 套件 / 980 项 100% PASS（0 failures, 0 errors, 3 skipped）；assembleDebug 构建通过（APK 49.2MB）；ADB 覆盖安装 DKS9K23420020242 成功，App 启动无崩溃；真机确认方案页出现「今日停止线」开关与「今日上限（节）」数值行，勾选力量并保存后 plan-v2.json 写入 dailyStudyRoundCap:5、study 步骤带 targetAttribute:力量，重新进入方案页开关与数值恢复。
- 待验收：真实跑一轮「今天已学满 N 节 → 本轮跳过学习」的真机场景尚未执行（需要当天已有已确认学习记录的实际局面），该行为目前只有 JVM 测试覆盖；旧版托管参数路径（session-plan.json）不读停止线，只有方案页保存的 PlanV2 生效。
- 提交与推送：feat(plan): 属性目标与今日停止线，今日学满即跳过 (STR-08)，推送 backup/feat/onboarding-troubleshooting-p0，更新 PR #4。
- 下一步：第三阶段 STR-09 均衡属性目标（轮换力量/智力/魅力并记录每项变化）：StudyStep 目前只有单个 courseId，轮换需要每轮不同课程，预检的学费/时长/成本读取都要按属性分别取，工作量比 STR-08 大；run-record.json 已记下 targetAttribute 与 studyRoundsCompleted，可直接作为每项统计的原料。

## 均衡属性目标（2026-11-03，feat/onboarding-troubleshooting-p0）
- 目标：STR-09 均衡属性目标——轮换力量、智力、魅力，并记录每项变化。
- 核实结论：
  - 属性到课程的匹配早已具备（StudyAttribute.resolveCourse 按已解锁课程挑课）；缺的是「一课一属性、按节轮换」的载体和「每项变化」的记录。
  - 宠物当前的属性数值读不到：房间页只公开体力/清洁/心情，学习流程不去职业小镇，phase00 文档已标注属性「明确不支持读取」。所以本轮不声称能读属性总量。
  - 课程详情页写明该课的属性收益（MVP-VALIDATION Gate C 实测表：蹦蹦跳跳体能课 力量+2、看图识世界课 智力+2、闪亮自我表达课 魅力+2 等）。读学费时本来就要打开这一页，顺带解析同一帧文本即可，不额外读帧。
- 改动：
  - StudyRotation（PlanV2 层，新增）：一门轮换课 = 属性 + 课程。StudyStep 新增 rotation（默认空=单属性目标）与只读 courseIds；校验轮换不超过三项、属性不重复、课程不重复、第一节必须等于 step.courseId、均衡时不得同时写单属性目标。
  - StudyRotationPolicy（新增，纯函数，JVM 可测）：schedule 按轮换顺序排出本次每一节上哪门课（第 4 节回到力量）；tuitionTotal 按真实顺序累加学费（不是最贵那门×节数）；maxTuition 供币值闸门用；minutesTotal 每节用它自己那门课的时长。读不到学费或时长直接失败，不当成 0。
  - CourseAttributeGain（新增，纯函数）：从课程页文本解析「力量/智力/魅力 + N」，容忍 ML Kit 拆字空格和全角加号；体力不算属性；随机属性读不到就是 null。
  - CourseCostReading.kt 新增 CoursePageReading（学费+余额+属性收益，一次读页拿全）；PetSessionController 的 readCoursePage 取代 readCourseCost 的读帧逻辑，旧调用点行为不变。
  - PetSessionController 预检：courses 端口把轮换里每一门课都走一遍课程页读学费/时长（原实现只读第一门）；学费预留按真实顺序合计；币值闸门改用新增的 CoinSpendPolicy.evaluatePlanned（按真实合计判断，不拿最贵那门乘节数）；分钟预算按每节自己的时长累加。
  - 执行：runPlanStudy 按 schedule 逐节换课，每节的 ensureTuition 对照该课预读到的学费，并把课程页写明的收益记下来；每完成一节累加该属性的节数与收益。
  - 记录：run-record.json 新增 studyAttributes（属性 → 节数 / 收益合计 / 读到收益的节数）；SessionRunRecord 读回；记录页「动作与道具」逐条显示「力量属性 本轮 2 节 · +4」，读不到写「收益未读到」而不是编一个数字；原来那条「金币 / 属性 / 库存 · 不支持读取」改成只针对宠物当前属性数值，不再和新记录冲突。
  - 方案页：培养目标属性卡片新增第 4 个芯片「均衡」（默认不选，新玩法不默认执行），选中后显示「均衡：力量 → 智力 → 魅力（每节课轮换一门）」和三门课的课程名；均衡模式下课程列表不可手动选，改回单属性会清空轮换。
  - 使用攻略「配置托管方案」补充第 9 条「均衡目标」，写明轮换顺序、学费按每节计算、属性数值读不到。
  - 单元测试：新增 StudyRotationPolicyTest（8 项）、CourseAttributeGainTest（4 项）、RecordsPresentationTest（3 项）；PlanV2Test 补 2 项（轮换保留 + 5 种非法轮换）、PlanV2StoreTest 补 1 项（轮换往返、关闭时不写键）、PlanRunJsonCodecTest 补 1 项（运行快照往返）、CoinSpendPolicyTest 补 1 项（按真实合计判断）；真机插桩 StrategyRecordJsonTest 补 1 项（studyAttributes 写读往返、读不到的收益保持未知）。
- 验证结果：全量单元测试 108 套件 / 1000 项 100% PASS（0 failures, 0 errors, 3 skipped）；assembleDebug 构建通过；ADB 覆盖安装 DKS9K23420020242 成功，App 启动与各页切换无 FATAL；真机确认方案页出现「均衡」芯片，点选后高亮为选中色并显示三门轮换课，保存后 plan-v2.json 写入 rotation（力量/智力/魅力三门课）且不写 targetAttribute，重新进入方案页轮换说明与选中态恢复；使用攻略第 9 条「均衡目标」可见；记录页空态正常。
- 待验收：
  - 真机跑一轮三节轮换尚未执行——设备上 Shizuku 未授权，托管无法启动；轮换执行路径目前只有 JVM 测试覆盖。
  - 课程页属性收益解析目前依据 Gate C 实测表与合成文本测试，尚未在真实课程详情页的 OCR 原始输出上验证；读不到时会记成未知，不会给出错误数字。
- 提交与推送：feat(plan): 均衡属性目标，按节轮换并记录每项变化 (STR-09)，推送 backup/feat/onboarding-troubleshooting-p0，更新 PR #4。
- 下一步：第三阶段剩余项——STR-06/07/10/11 需要可靠的课程与岗位收益数据，目前 Gate C 只有初级学园 7 门课的页面数值、岗位收益仍不完整；STR-12 疲劳/降效需要真实结算与预估对比。先做 STR-06 的课程收益比较（数据已有：分钟/金币/学分/属性四列），把「按收益选课」做成可解释的建议而不是默认替换。

## 课程收益比较（2026-11-03，feat/onboarding-troubleshooting-p0）
- 目标：STR-10 学习课程收益比较——收集候选课程的费用、时长、预期效果并对比；不可用时标为未知。
- 核实结论：
  - 课表扫描已给出每门课的时长与解锁状态；课程详情页（读学费的同一页）还写着属性收益，此前只用于当轮记录，没有留给方案页比较。
  - 方案页原来只显示「N 分钟」，用户选课前看不到学费和收益，也没有地方比较。
- 改动：
  - CourseReading（新增，纯数据）：一门课上次读到的学费、时长、属性收益与读取时间；缺项不写假值。
  - CourseReadingStore（新增）：原子写入 files/course-readings.json，按课程 id 合并；损坏的缓存不会挡住重新读取（读取动作按空缓存开始）。
  - readCoursePageReading（新增，从 PetSessionController 抽出）：课程详情页一次读帧拿学费+余额+属性收益，4 次重试后失败；PetSessionController 原调用点改为委托，行为不变。
  - PetAutomationService：新增只读动作 READ_COURSE_GAINS（「读取课程收益」）。课表缺失/过期时先补一次课表扫描；随后逐门打开已解锁课程详情页，读学费、时长与收益，每读完一门立即落盘；只点课程卡片与返回，不点开课/购买，不花金币；中途失败保留已读到的课程，失败文案给出可重试入口。
  - CourseComparison（新增，纯函数，JVM 可测）：按课表顺序列出候选课程，只使用完整且 7 天内未过期的读数计算「最省学费」「每分钟最省」「每点属性最省」；缺学费/缺收益/过期都保持未知且不参与排名。
  - 方案页：课程行显示「时长 · 学费 · 属性+N」或「学费/收益未读取或已过期」，下方显示比较摘要与未读到名单；新增「读取课程收益」按钮（标明只读、不花金币），运行中/无后台画面时禁用；读取进度复用运行状态文案。
  - 使用攻略「配置托管方案」补充第 10 条「课程收益比较」。
  - 单元测试：新增 CourseComparisonTest（6 项：无读数、三项排名、过期、缺收益、锁定课程、非法数值）。
- 验证结果：
  - 全量单元测试 109 套件 / 1006 项 100% PASS（0 failures, 0 errors, 3 skipped）。
  - assembleDebug 构建通过。
  - 真机安装/界面验证：构建期间设备从 ADB 断开（adb devices 为空，重连无效），本轮未完成安装与界面截图；课程收益读取本身仍需要 Shizuku 授权，设备上一贯显示未授权，因此真实读取路径待验收。
- 待验收：
  - 真机点「读取课程收益」逐门读取 7 门课，核对学费/收益落盘与界面比较（需 Shizuku 授权与后台画面可用）。
  - 课程页属性收益解析尚未在真实 OCR 原始输出上验证；读不到会标为未知，不会给出错误数字。
- 提交与推送：feat(plan): 课程收益比较，读不到保持未知 (STR-10)，推送 backup/feat/onboarding-troubleshooting-p0，更新 PR #4。
- 下一步：第三阶段剩余 STR-06/07 金币/工分优先与 STR-11 岗位收益比较。现有 WorkCatalog 只有 1 个 VERIFIED 岗位，先补岗位页面读数与真实结算证据，再实现比较；STR-12 需要真实结算与预估差距数据。

## 岗位收益比较（2026-11-03，feat/onboarding-troubleshooting-p0）
- 目标：STR-11 打工岗位收益比较——收集候选岗位的金币、工分、消耗并比较；读不到保持未知，不自动换岗。
- 核实结论：
  - 方案页打工卡片只显示「一班 10 分钟」，用户看不到金币/工分，也没有比较入口。
  - WorkCatalog 只有 1 个 VERIFIED 岗位（竹影武馆 · 帮武馆跑腿），另有 2 条页面读数、1 条未知；把页面读数直接当可执行，会推荐一个还没闭环验证的岗位。
  - 岗位详情页选中时长档后奖励行就在同一页，读它不需要开工；选卡片的动作本身也不花钱。
- 改动：
  - WorkReading（新增，纯数据）+ WorkReadingStore（原子写 files/work-readings.json，按岗位 id 合并；缺项或负数整条丢弃，未知不写成 0）。
  - WorkYieldComparison（新增，纯函数，JVM 可测）：只把「现场读过且 7 天内」的岗位标为 live；排名只在 VERIFIED 且 live 的岗位之间做每分钟金币/工分/体力比较；PAGE_READING 行照实列出并标「暂不可选」；UNKNOWN 不出现。
  - PetAutomationService：新增只读动作 READ_WORK_GAINS（「读取岗位收益」）。逐家职业进入小镇，选中每个时长档读奖励行，绝不点「去打工」；每个岗位读完立即落盘；某家失败记原因继续下一家；离开小镇失败也只记一笔，不丢已读结果；一个都没读到时失败并给出重试入口。
  - PetWorkNavigation.selectWorkOption：连续读取多个岗位后页面停在奖励区，找不到卡片时先确认仍在详情页，再最多下滑 2 次找回卡片；仍找不到报 WORK_OPTION_CARD_NOT_FOUND。
  - 方案页：打工行显示现场读数或目录值；新增「读取岗位收益」按钮与说明（只读、不开工）；比较摘要写明每分钟收益，可执行岗位不足两个时说明仍按安全默认岗位执行。
  - 使用攻略第 11 条「岗位收益比较」。
  - 单元测试：新增 WorkYieldComparisonTest（8 项：无读数、过期、边界 7 天、页面读数不可选、每分钟排名、现场数覆盖目录、UNKNOWN 不出现、live 排序）。
- 验证结果：
  - 全量单元测试 110 套件 / 1014 项 100% PASS（0 failures, 0 errors, 3 skipped）。
  - assembleDebug 构建通过。
  - 真机：ADB 恢复后覆盖安装 DKS9K23420020242 成功，App 启动无 FATAL；方案页勾选「打工」确认卡片显示目录值、未读取说明与「读取岗位收益」按钮（标注只读不开工），后台画面未就绪时给出「暂不能读取」；使用攻略「配置托管方案」第 11 条可见。
- 待验收：
  - 真机点「读取岗位收益」逐家读取奖励行（需 Shizuku 授权与后台画面可用；本机 Shizuku 显示未连接）。
  - 岗位页面 OCR 读数尚未在真实页面上核对。
- 提交与推送：feat(plan): 岗位收益比较，读不到退回安全默认岗位 (STR-11)，8560e9d，推送 backup/feat/onboarding-troubleshooting-p0，更新 PR #4。
- 下一步：STR-06/07（金币优先/工分优先）暂缓——可执行岗位只有 1 个，排名没有实际作用；先核实第四阶段 SOC-06 好友名册读取是否有可用的页面证据，没有就先补一条真实打工闭环把页面读数升级为可执行。


## 中断后先确认，不重复扣费（2026-11-03，feat/onboarding-troubleshooting-p0）
- 目标：把路线第三部分「中断后先确认是否已扣费、开课或领取，避免重复执行」落地——上一次任务被中断后，
  结果未知的动作不能被自动再做一次，必须先让用户确认游戏里到底做没做。
- 核实结论：
  - 现状：中断恢复（InterruptedAttemptRecovery）只把尝试标成 RECONCILED_STOPPED 并写明
    OUTCOME_REMAINS_UNKNOWN，然后放行下一次会话；下一次会话会照常再交一次学费、重复领一次奖。
  - 账本里已经具备判定所需的事实：尝试号里带步骤号（study/work/care 与 <planId>:study 两种写法），
    状态与原因里写着结果是否被证实；不需要新页面证据就能判断「这一步结果未知」。
  - 会扣费或会领奖的动作只有四类：学习（学费）、签到、信箱、福袋。打工不交学费也不领奖，
    它的中断由带证据的补写路径处理，不在这里拦；护理的兑换支出走兑换预算，不是键控尝试，也不在这里拦。
- 改动：
  - PendingActionGuard（新增，纯函数，JVM 可测）：从账本尝试里挑出「结果未知」且属于上述四类的动作；
    动作类型从尝试号的步骤号解析（取最后一段冒号后的名字，忽略大小写）；用户确认「已经做完」按天计数。
  - PlanBudgetLedger.confirmOutcome（新增）：用户确认后写回账本——已发生记成 COMPLETED 并带
    USER_CONFIRMED_DONE 标记，没发生记成 RECONCILED_STOPPED 并带 USER_CONFIRMED_NOT_DONE 标记；
    分钟、天数与其余尝试一律不动（账本保持只进不出的既有规则），已结算的尝试不可再确认。
  - PetAutomationService：会话开始前先查待确认动作，本次要做的动作里有未知结果就直接拒绝开始，
    写清「上一次XX被中断，是否已扣费或已领取无法确认」并提示去「运行方案」页确认；不发送任何输入。
  - PetSessionController：方案学习节数扣掉用户已确认做完的节数，今天不再重复上这一节。
  - 方案页：新增「待确认动作」卡片，逐条给出动作、分钟与日期，可选「已经做完／没有发生」；
    有待确认动作时「保存并运行」禁用并写明要先确认。主页显示「有 N 个待确认动作 · 去确认」。
  - 使用攻略：「配置托管方案」第 12 条「中断后先确认，不重复扣费」；「常见问题与排故」新增对应症状条目。
  - 单元测试：新增 PendingActionGuardTest（8 项：动作类型解析、两种未知状态、已结算不重复、决策、确认计数、
    确认后分钟与天数不变、确认没发生可重做、已结算不可再确认）。
- 验证结果：
  - 全量单元测试：111 套件 / 1022 项通过（0 失败、0 错误、3 跳过）；assembleDebug 构建成功并已覆盖安装到设备。
  - 真机（设备 DKS9K23420020242，已装本轮 APK）：按账本二进制格式造一条「结果未知」的学习尝试写入设备后——
    主页出现「有 1 个待确认动作 · 去确认」；方案页出现「待确认动作」卡片（学习 · 10 分钟 · 2026-11-04）；
    点「已经做完」卡片消失，账本该尝试变为 COMPLETED 且原因带 USER_CONFIRMED_DONE；
    换成 RECONCILED_STOPPED+OUTCOME_REMAINS_UNKNOWN 的尝试点「没有发生」后，账本变为 RECONCILED_STOPPED
    并带 USER_CONFIRMED_NOT_DONE（可以重做）。验证完成后已把设备账本恢复为原状（42 字节，与备份逐字节一致）。
  - 待验收：会话开始前的「拒绝开始」提示无法端到端演示——本机 Shizuku 未连接，
    runSession 会先被 profile.validate 的「配置未完成」拦下；这条路径只有单测与代码审查佐证，需要在有 Shizuku 的设备上补验。

## 夜间暂停窗口与每日玩法攻略补齐（2026-11-04，feat/onboarding-troubleshooting-p0）
- 目标：
  1. STR-17 夜间暂停：路线第三部分「定时与夜间暂停在执行及恢复稳定后再接入」的前提已满足
     （中断恢复 RUN-01 已交付），这一轮把「暂停时间」做掉。
  2. 路线第三部分「新玩法同步补充设置中的使用攻略」：核实后发现第二阶段交付的
     每日签到（SOC-03）、信箱待领（SOC-04）、福袋任务（SOC-05）在使用攻略里完全没有提到。
- 核实结论：
  - 现状：App 没有任何时间维度的开关，夜里点「开始托管」照常跑；运行中的任务也不会因为时段停下。
  - 用户能接受的最简单做法：自己划一段「别动宠物」的时间——窗口内不开始新的托管；
    已经跑着的任务走到窗口里就在两步之间停住，到点重新确认画面再继续。
  - 定时自动启动（MKA-05 的「启动时间」）本轮不做：需要后台定时唤醒，Android 的后台限制下不可靠；
    「立即执行」已经有了。
  - 使用攻略缺口是事实核对的结果，不是猜测：签到/信箱/福袋三个词在攻略全文里一次都没出现，
    而方案页已经把它们做成了可勾选的步骤，用户看不到就等于没做。
- 改动：
  - QuietWindow（新增）：开关 + 起点/终点（当天 00:00 起的分钟数），可跨零点（22:30 → 07:00）；
    起止相同等于没有窗口，不是全天暂停。
  - QuietWindowPolicy（新增，纯函数）：现在是否落在窗口内、距离窗口结束还有几分钟。
  - QuietWindowStore（新增）：SharedPreferences 落盘，读到的分钟数夹到合法范围，坏值不会让 App 起不来。
  - SessionStartGate（新增，纯函数）：会话开始前放行还是拦下，拦下时给出给用户看的中文句子。
    单独抽出来是因为这台设备没有 Shizuku，托管根本起不来，端到端演示不了「夜里被拦下」。
  - PetAutomationService：会话开始前查窗口，落在窗口里就拒绝开始并写明到点时间；
    健康检查只读不动作，用户明确点了就照做。
  - PetSessionController：在每个安全边界检查窗口，落在窗口里就把运行状态标成 PAUSED（原因 QUIET_WINDOW），
    每 30 秒重读一次设置（用户改设置最多半分钟生效），到点后重新识别画面再继续；
    连续停超过 24 小时就安全结束本次托管，不无限占着任务。
  - 设置页：新增「夜间暂停」一行与设置对话框（开关 + 起止时间，30 分钟步进）。
  - 使用攻略：第二部分补「签到 / 信箱 / 福袋」三条，第三部分第 1 条改成列出全部可勾选动作；
    暂停章节新增「夜里不想被打扰？」一条。
  - 单元测试：新增 QuietWindowTest 11 项（关闭不生效、跨零点、日内窗口、起止相同、剩余分钟数、
    文案与跨零点判断、时钟取分钟、越界分钟被拒、开始闸门拦下与放行、健康检查不被拦）。
- 验证结果：
  - 全量单元测试：112 套件 / 1033 项 100% PASS（0 failures, 0 errors, 3 skipped）。
  - assembleDebug 构建成功（4m55s），APK 已覆盖安装到设备 DKS9K23420020242（install: Success）。
  - 真机（设置页）：「设置 → 夜间暂停」一行显示「未开启：夜里不会开始新的托管」；点开对话框能打开开关，
    出现「开始 23:00 / 结束 07:00」两行时间，− 键把开始时间改成 22:30，保存后该行变成
    「每天 22:30–07:00 不托管」。
  - 真机（持久化）：force-stop 后重新启动 App，设置页仍显示「每天 22:30–07:00 不托管」，说明写盘与读回都对。
  - 真机（使用攻略）：第二部分能看到新增的「签到 / 每日签到」「信箱 / 信箱待领奖励」「福袋 / 福袋任务」三条。
  - 验证结束后把设备设置恢复为默认（关闭夜间暂停，时间回到默认 23:00–07:00），设备状态与本轮开始前一致。
- 待验收：
  - 「夜里点开始托管被拦下」的提示无法端到端演示（设备 Shizuku 未连接，runSession 会先被
    profile.validate 的「配置未完成」拦下）；该判定已抽成 SessionStartGate，有单测覆盖，但要在有
    Shizuku 的设备上补一次真实拦截。
  - 运行中走到窗口自动停下、到点自动继续，同样需要一台能真正托管的设备验证。
- 提交与推送：feat(schedule): 夜间暂停窗口，夜里不开始新托管 (STR-17)，推送
  backup/feat/onboarding-troubleshooting-p0，更新 PR #4。
- 下一步：第三部分剩余「实时状态、暂停继续、停止、记录和设置持久化持续回归（CORE-02/16~19）」；
  第四阶段好友互动（SOC-06~15）需要页面证据，Shizuku 未连接前无法核实，先不做。

## 2026-11-04 记录页每日领取计数 / 每日领取流程安全停止

- 交付内容：
  - 记录页「今天」卡片在四格计数下新增一行领取数：签到 X 次 · 信箱 X 次 · 福袋 X 个；
    全部为 0 时不显示，避免被读成失败（RecordsPresentation.dailyRewardsText）。
  - 每日签到 / 信箱 / 福袋不再猜坐标关弹窗：找不到「关闭／我知道了」就什么都不点，
    并写明 *-close-unconfirmed；原固定点 (640,360)/(650,180) 的兜底已删除。
  - 领取结果必须有页面证据：SignInParser/MailboxParser/LuckyBagParser.claimConfirmed
    只认金币、道具、已领取、信箱变空或今日上限；读不到时流程返回 Unconfirmed，
    由计划记成待确认动作，不再记成完成。
  - 签到「没有领取按钮」原来仍返回 Completed，现改为 Skipped("SIGN_IN_CLAIM_BUTTON_NOT_FOUND")。
- 验证结果：
  - 全量单元测试：113 套件 / 1048 项 100% PASS（0 failures, 0 errors, 3 skipped），
    新增 3 项 claimConfirmed 测试。
  - assembleDebug 构建成功，APK 覆盖安装到设备 DKS9K23420020242（install: Success），启动正常。
  - 真机（记录页）：用测试记录验证「今天」卡片显示「签到 2 次 · 信箱 1 次 · 福袋 3 个」；
    验证后已删除测试目录，设备恢复原状。
- 待验收：
  - 每日签到 / 信箱 / 福袋的真实领取分支需要 Shizuku 连接后才能点出；本次只到编译、单测与启动。
- 提交与推送：feat(records) 记录页每日领取计数；feat(daily) 领取流程未知结果不再盲点或报成功。
- 下一步：第一部分「差距台账」按清单编号标注真实状态；第三部分 CORE-02/16~19 的实时状态与持久化回归。

### 2026-11-04 记录页「昨日汇总」（OBS-06）

- 交付内容：
  - 记录页在「今天」下面新增一行「昨天 · 学习 X 节 · 打工 X 班 · 冒险 X 轮 · 护理 X 次 ·
    签到 X 次 · 信箱 X 次 · 福袋 X 个 · 运行 X 分钟」，只列确认过的数字，没做的分项不出现。
  - 数据来自本机已有会话目录，按本地日期取前一天；没有记录就不显示这一行。
- 验证结果：
  - 全量单元测试：113 套件 / 1050 项 100% PASS（新增 2 项 dailyLine 测试）。
  - assembleDebug 成功，APK 覆盖安装到 DKS9K23420020242（install: Success）。
  - 真机：写入一条 startedAt 为前一天的测试记录后，记录页显示
    「昨天 · 学习 2 节 · 打工 2 班 · 签到 1 次 · 运行 45 分钟」；验证后已删除测试目录。
- 提交：feat(records): 昨日汇总一行，跨零点保留前一天进度 (OBS-06)。

### 2026-11-04 收尾：差距台账完成 / 提交与阻塞

- 交付内容：
  - 竞品清单 76 条全部按原编号标注状态与证据（代码 / 测试 / 真机历史），
    把「部分实现」（OBS-02/05、MKA-07、CORE-20）和「暂缓」（STR-06/07/12、MKA-05、SKIP-*）
    与已实现项分开；授权/诊断/预览关联到引导清单，避免两套实现。
  - 「无法识别的页面安全停止」已勾选并写证据（领取流程不再盲点、未知结果记待确认）。
- 提交：feat(records) 昨日汇总（OBS-06，79d528c）、docs 交接补充（5eab1ce）已在本地
  feat/onboarding-troubleshooting-p0。
- 阻塞：本机到 GitHub 的 HTTPS 与 SSH 均被网络拦（TLS 握手失败 / port 22 连接被关闭），
  a685f91 / 79d528c / 5eab1ce 及 PR #4 说明更新都未能推送；网络恢复后先
  git -C Z: push backup HEAD:backup/feat/onboarding-troubleshooting-p0 ，
  再 gh pr edit 4 --repo yzhlx/qqpet-automation --body-file C:/tmp/pr_body.md 。
- 待验收（需要能跑真实托管的设备）：签到/信箱/福袋真实领取分支、「开始被拦」提示、
  通知点回 App、夜间暂停运行中自动停/续。
- 下一步：网络恢复后推送 → 更新 PR 说明 → Shizuku 连接后补真实领取与开始拦截验收；
  贯穿项 CORE-02/16~19 已在台账标注，等下一次真机回归确认后勾选。

### 2026-11-04 PR 对齐与诊断页真机验证

- 交付内容：
  - PR #4 源分支 feat/onboarding-troubleshooting-p0 推到 6f98573，PR head 已对齐。
  - 真机验证「检查运行环境」：诊断页正常打开，显示 HUAWEI FOA-AL00 / Android 12 (API 31)，
    基础状态（Shizuku 已安装、服务已启动、QQ 已安装）与异常项（Shizuku 未授权、QQ 宠物未后台运行）
    均如实标注，有「重新检测 / 关闭」恢复入口。
  - 记录页「昨天 · 学习 2 节 · 打工 2 班 · 签到 1 次 · 运行 45 分钟」在真机再次确认。
- 验证结果：诊断页显示正常，没有空白或崩溃。
- 提交：无需新代码提交（诊断页是既有功能）。
- 阻塞：Shizuku 未授权 → 无法开始真实托管；通知栏运行状态、开始拦截、领取分支仍待验收。
- 下一步：恢复 Shizuku 授权后按顺序补验通知、开始拦截与每日领取。

### 2026-11-04 CORE-19 持久化回归 + OBS-05 今日事件时间线

- 交付内容：
  - CORE-19 持久化真机回归：设置页改「夜间暂停 23:00–07:00」→ force-stop → 重开，
    shared_prefs/pet_quiet_window 值保留、方案页与 files/plan-v2.json 原样（护理 + 学习 1 节 + 冒险 1 轮），
    首页显示「夜间暂停 23:00–07:00 · 现在可以开始」；验证后已把夜间暂停恢复为关闭。
  - OBS-05：记录页新增「今天的事件」卡片，按时间倒序列出开始托管 / 本轮结束 /
    开始上课（课程名与时长）/ 学习已确认 / 这一步没完成（原因）；只读 run-record 与会话
    事件日志（files/pet-events/*.jsonl）里能解释的记录，打工与领取没有单独时间戳就不编时间。
    新增 JournalEvent 解析、PetEvidenceRepository.todayJournalEvents、TimelinePresentation；
    RecordsPresentationTest 增加 3 项时间线用例。
- 验证结果：
  - 全量单元测试：113 套件 / 1053 项 PASS（0 failures, 0 errors, 3 skipped），assembleDebug 成功。
  - 真机（DKS9K23420020242）：用测试会话记录与事件日志验证记录页显示
    「16:30 本轮结束 · 已完成」「15:55 这一步没完成 · 候选打工岗位均未达到开工条件」
    「15:48 学习已确认 · 第 1 节」「15:47 开始上课 · 第 1 节 · 蹦蹦跳跳体能课 · 约 10 分钟」
    「15:45 开始托管 · 计划 2 节」；验证后已删除测试目录与文件。
- 已知限制：真实托管产生的时间线还需要 Shizuku 授权后跑一轮真实会话才能核对；
  本次只到编译、单测与用测试记录驱动的页面验证。
- 下一步：等本批全量构建结束后提交推送，更新 PR #4 说明；随后核实 OBS-02 倒计时的
  真实数据来源，能拿到会话进行中的可信时长就做，拿不到就保持「部分实现」并说明。

### 2026-11-04 首页本节预计剩余（OBS-02）

- 交付内容：
  - 开课确认后记录本节在墙钟上的开始时间与页面读到的时长，首页「当前任务」卡显示
    「第 N 节 · 预计还要 X 分钟」，最后 1 分钟显示「预计不到 1 分钟结束」，
    时间到后显示「课程时间已到，等待结果」。
  - 没有开始时间/时长、不是本会话、会话已结束时都不显示；预计值只由这两个真实数据算，
    不按轮次或运行时长猜。
- 验证结果：
  - 新增 4 项 PetRuntimePresentationTest 用例；全量 113 套件 / 1057 项 PASS
    （0 failures, 0 errors, 3 skipped），assembleDebug 成功，APK 覆盖安装成功并正常启动。
- 待验收：真实托管中的倒计时需要 Shizuku 授权后跑一节课程确认。
- 下一步：Shizuku 恢复后补验倒计时、真实领取、开始拦截、通知点回与夜间暂停自动停续。
### 2026-11-04 开始前摘要（引导清单第八节）

- 交付内容：
  - 首页「开始托管」改为先确认后启动：点按钮先弹出摘要，确认「开始托管」才下发开始请求；
    取消不发任何请求。
  - 摘要三行：
    - 执行顺序：签到 / 信箱 / 福袋（最多 N 次）/ 打工（岗位 + 时长）/
      护理（体力、清洁阈值）/ 学习 N 节（课程名，均衡时列出轮换课程）/ 冒险 N 轮。
    - 预计占用：课程时长按已扫描课表逐节累加，均衡轮换按真实上课顺序；课表没读到就写
      「学习时长未读取」，不给默认分钟数；条件打工（缺钱才打）与领取/冒险等待明确标未计入。
    - 已知消耗：护理阈值、打工体力/清洁消耗与目录里已确认的金币回报、共用每日预算；
      学费只写「以课程页为准」，不把估算写成余额。
  - 摘要预算取实际会执行的 PlanV2 每日预算（无 v2 方案才回落旧计划），与开始请求同一来源。
  - 确认开始后立即 Toast「已开始托管，正在准备第一步」；摘要里提示锁屏、断网、
    切走其他应用可能中断托管。
  - 新增 StartSummaryPresentation（纯函数）与 5 项单测。
- 验证结果：
  - 全量单元测试 114 套件 / 1062 项 PASS（0 failures, 0 errors, 3 skipped）。
  - assembleDebug 成功；APK 覆盖安装到 DKS9K23420020242 并正常启动，首页渲染正常，
    Shizuku 未授权时仍显示「还不能开始：先完成以下 3 步」与灰色开始按钮。
- 待验收：摘要弹窗与确认开始需要 Shizuku 授权后才能真机点开（当前 App 的 Shizuku 权限未授权，
  开始按钮不可用）；未实际开始会话，避免消耗金币。
- 下一步：标记引导清单第八节完成；推进第九节「图文步骤 / 攻略版本一致性」与第十节未完成项
  （服务停止恢复、更新保留数据、开始前快速检查）。
- 提交与推送：673612c 已推送到 feat/onboarding-troubleshooting-p0，PR #4 head 已对齐；
  backup 分支更新连续返回 GitHub 500，同一提交已在 PR 分支，交付不受影响。
- 下一条具体动作：核实引导清单第五节「配对成功 / Shizuku 已启动」的现状，补区分与配对状态未知提示。
