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

## 本次 GitHub 保存（2026-10-24 方案验收收口快照）

- 目标仓库：私有 `https://github.com/yzhlx/qqpet-automation`（remote `backup`）。
- 目标分支：`snapshot/20261024-plan-acceptance`，以远端既有快照 `0032757` 为父新增独立快照；不覆盖 `feature/qqpet-mvp-session` 与旧快照分支，不强推；本地分支与历史保持完整。
- 快照内容：当前 `app/src` 源码与测试（含写回路径加固与 22 个写回测试）、`docs`、`tools`（含只读 `tools/diagnostics`）、`specs/001`、`specs/002` 验收文档、构建脚本与 `progress.md`。
- 明确排除：`/work/`、`evidence/slice2/*` 原始采集、整屏截图、`app/src/debug/assets/pet_experimental/room.png` 的本地头像改动（保留远端原图）、Python 缓存、`.build-tmp` 与 Gradle 构建产物、APK 与签名材料。
- 本快照不代表验收完成：独立 WORK 写回结算与 CS/WCS 真机验收仍未完成。
