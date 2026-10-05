# 最终实测汇总（2026-10-05 21:15）

## Verdict：3_ROUNDS_PASS

固定蹦蹦跳跳体能课三节完成，成绩A、A、A+；末次instrumentation OK(1 test)，completed=3，stopReason=null，physicalDelta=0。末次Run1791205436000。期间真实发现升级弹窗、肥皂模板姿态及结束转场问题，修复后跨Run恢复，没有重复计算已完成课程。因此本结果是**恢复后完成三轮**，不是单次无干预连续三轮，更不是连续五轮验收。

末轮按用户新授权在学习中补点10次，来源明确为ADB_AUTHORIZED_DEBUG_INPUT、显式displayId4；不会把这些补点记成App自动执行。App原自动发送每轮1次。最终App十连击动作代码已构建安装，但该新动作尚未真实开课实跑验证；没有额外开第4节。

## Round results

健康值来自现有颜色进度条保守估计；成绩A/A+来自成绩单截图目视核对，未增加OCR。App对第三轮原始grade仍记UNKNOWN，原日志未覆盖。

| Round | before体力/清洁/心情 | 开课前ready | 饼干 | 肥皂 | App击掌attempted/sent | 调试补点attempted/sent | 成绩 | after体力/清洁/心情 | 时长 |
|---|---|---|---:|---:|---|---|---|---|---|
| 1 | 94/91/79 | 94/91/79 | 0 | 0 | 1/1 | 0/0 | A | 88/89/79 | 课程约10分；含升级修复与恢复约14分 |
| 2 | 88/89/79（恢复前） | 98/99/80 | 1 | 1 | 1/1 | 0/0 | A | 94/97/80 | 课程约10分；含恢复与转场修复约20分 |
| 3 | 94/97/80 | 94/97/80 | 0 | 0 | 1/1 | 10/10 | A+ | 88/95/80 | round_total=635384ms，course=603269ms |

第2轮跨Run恢复消耗合计已有基础饼干1份、基础肥皂1份（库存6→5）；不能只读末次Run的feed/clean=0而漏计。第3轮补点发生21:11:16.555至21:11:18.753，约2.2秒，开始后约6分46秒；之前App在T+29.739秒自动点1次。补点后实帧出现爱心和星星反馈，但无法逐次确认业务接受数，businessHighFiveCount保留UNKNOWN。第三轮A+及力量+5有截图证据，不能仅凭一次成绩将全部提升归因于补点。

## Capture statistics

统计范围：1791203355328 → 1791204208048 → 1791204552957 → 1791205436000四个恢复链Run；不包含独立静态/转换/强制timeout门禁或其他校准测试。

- ImageReader success=1827；timeout=3。
- Shizuku fallback=3；success=3；成功率100%。
- fallback频率3/1830=0.164%。
- fallback延迟样本498、946、1122ms；p50=946ms，p95=1122ms（nearest rank，仅3个样本）。
- 最后Run独立统计：ImageReader success593、timeout2、fallback2/2，p50=498ms、p95=946ms。
- 每个Run physicalDelta=0，包括第3轮补点期间。转换门禁6次动作及强制超时门禁也均physicalDelta=0。

SHIZUKU_CAPTURE_PASS：静态10/10、转换12/12，准确获取display4，PNG约849KB，由手机内部Shizuku shell uid2000执行screencap，经PFD pipe传输，签名/尺寸/非黑帧/模板验证通过。Hybrid强制timeout门禁2/2通过；真实实验又遇到3次漏帧并成功fallback。App正式capture不依赖PC/USB/外部adb。PC只用于安装、测试调度、收集证据及此次用户明确授权的调试补点。

## 最终实现和实际修复

1. ImageReader fast path350ms；无fresh buffer时转手机内Shizuku主动capture。动作后不以动作前cache PASS。
2. Capture-only UserService复用同一Shizuku权限，不重启持有display4的显示服务；PNG使用PFD pipe，不经过Binder整图ByteArray，不写公共相册。
3. 原模板阈值0.97、课程/学习ROI、PetInputController目标display及SOAP连续MOVE语义不变；default/production健康保护不删除，低心情仅debug实验override。
4. 已展开课程面板不再重复点击初级学园；成绩单可以返回学园地图或已展开面板。只使用已有目标模板。
5. 动作后与结束时最多8秒只读观察稳定目标；这是页面过渡确认，不是延长ImageReader awaitFrame，不重复发点击，无法确认仍STOP。
6. 实跑升级弹窗增加一个固定debug模板及关闭动作；SOAP复用已有pet_mvp/soap.png，修复实验误选姿态模板，未降低阈值。
7. 仅debug恢复已验证失败Run，必须有原课程/结果日志或现场结果模板确认；保留原课后测量，总课程计数不重复。
8. 用户纠正击掌玩法后，默认T+30秒识别目标一次，连续10次固定点击掌；每次检查LEARNING，点击后同阈值验证。移除30至40秒作为游戏硬窗口的假设，目标搜索与输入burst仍有局部时间上限用于fail-closed。当前三轮使用旧单次动作，10次为授权调试补点；新App动作仅完成构建/安装，待下一次实机验证。

## Root cause conclusion

1. 原STOP确认为动作成功但ImageReader未交付新buffer导致误判；底层驱动/渲染器根因没有诊断。
2. Hybrid解决了该故障路径：强制门禁及真实3次fallback均确认当前画面继续。之后的STOP是升级遮罩、旧肥皂姿态、游戏转场，分别修复，不归咎capture。
3. 底层ImageReader偶发漏帧仍存在；现在与页面失败语义已分离，不需要为此次MVP继续改其底层实现。
4. 实测fallback低频、约0.5至1.1秒，当前足够快；统计量小，继续保留遥测。
5. 当前没有理由升级为direct SurfaceControl/ScreenCapture，暂不做版本兼容框架。

## 交付与验证

- SHA：05a3416f2946a68f54db9121f6d9ce5dc3a37b40；branch feature/qqpet-mvp-session；本阶段未提交。
- 最终test / assembleDebug / assembleDebugAndroidTest成功，high-five-after30-build.log；安装主/测试APK均Success；final-recorded-regression.log截图回归3/3。
- 主APK：deliverables/qqpet-mvp-debug.apk；SHA256 13FD7B16793DA7EDA7D845BDE514310762BF30356CD0F98A4AD3C3CAEA751D10。
- 测试APK：deliverables/qqpet-mvp-gates.apk；SHA256 3D66CBFD99E01A70245D3B0659B15A59C60CD1F0B4CCC1A5FD1FDA11A7122C05。
- 本次changed files：ICaptureService.aidl、CaptureUserService.kt、ShizukuActiveCapture.kt、HeadlessDisplayCapture.kt、IDisplayRepository.kt、ShizukuDisplayRepository.kt、PetActiveCaptureTest.kt、PetExperimentalSession.kt、PetExperimentalSessionTest.kt、debug升级模板upgrade.png、run-experimental-session.ps1及报告。git status其他改动属于前阶段，不冒认本次新增。
- 阻塞项：capture阻塞已解决；App自动十连击尚未实跑验证，单次无干预连续三轮/五轮尚未证明。
- 下一步：当前限定三轮已结束，未开启第4节。后续获授权实测App十连击，再判断是否能无调试介入连续运行。

## Evidence

- 合并摘要：deliverables/experimental/three-round-summary.json，注明恢复链、统计范围和手动补点，不修改原日志。
- 四个Run目录各保存events.jsonl、verdict.json、before/after与成绩单帧。
- 补点日志：deliverables/experimental/round3-high-five-supplement.json；前后帧../test-evidence/round3-supplement-before.png、round3-supplement-after.png。
- SOAP：round2-soap-recovery.log、round2-leave-bath.log、../test-evidence/round2-soap-after.png。
- Gate A：deliverables/capture-gates-updated/1791201091159与1791201186621。
- Gate B：deliverables/hybrid-gates/1791201752518。

以下保留阶段历史记录；历史“最新/STOPPED/未开课”段落不代表最终状态。

---
# 3 轮 experimental session 实跑报告

## 最新：2026-10-05 20:11，capture修复通过，三轮STOPPED

手机App内Shizuku主动截图门禁SHIZUKU_CAPTURE_PASS：静态10/10、转换12/12，全部display4、physicalDelta=0。Hybrid人为制造2次ImageReader timeout，两次fallback正确确认新页面，模板1.0。test/assembleDebug/assembleDebugAndroidTest成功，最新APK已安装。详细技术、统计、证据与changed files见CAPTURE-HYBRID-REPORT.md。

最新Run1791202276391实际已启动1节蹦蹦跳跳体能课，但自动流程在第一次动作后新帧仍为过渡画面时STOP：learning score0.639，ImageReader等待12ms，非capture timeout；完成0/3，physicalDelta=0，before98/94/79，本次无喂食/清洁/击掌。只读App截图随后确认“正在学习”、剩余08:12，没有继续点击。旧的“未开课/采集阻塞”段落为历史记录，不代表最新状态。当前唯一新阻塞为开课后过渡页面确认；未修改ROI或阈值。

## 最新实跑（2026-10-05 19:30）

按用户要求立即重启固定三轮实验。Run ID `1791199799477` 在第 1 轮读取健康值（体力90、清洁94、心情79）后，关闭健康面板动作已发至 display 4；App 内等待动作后的新帧超时，遂 fail-closed STOP。ADB 只读现场截图确认宠物首页已恢复，说明输入动作本身成功但 HeadlessDisplayCapture 没收到后续帧。结果 `STOPPED`、完成 `0/3`、`physicalDelta=0`、未开课、未消耗物品。日志和前后帧留在该 Run ID 目录及 `../test-evidence/run1791199799477-current.png`。本次没有重试，遵守识别失败立即停止规则。

## 最新续验记录（2026-10-05 19:23）

用户要求继续后安装了 headless surface rebind 构建。只读 `PetRecordedStateTest` 通过 3 项，displayId=4 的捕获回归通过 1 项，并取得宠物首页截图。真实 3 轮实验 Run ID `1791198749090` 在第 1 轮进入课程前 STOP：体力估计 90、清洁 95、心情 79（本实验明确允许 mood override），恢复物品均未使用；事件序列确认首页、健康页开关、室外页、学园地图匹配均通过。随后程序等待课程面板自行出现，最后帧是稳定的“宠物学园”地图，触发 capture timeout。verdict `STOPPED`、completed `0/3`、physicalDelta `0`。没有点击“去上课”，没有开课。

根据 STOP 截图发现真实路由缺一步：学园地图显示可用的“初级学园”，需先点击其入口才会展开课程列表。debug 实验路由已改为在地图模板匹配后点击 `(200,420)` 并校验 `courseListProbe`；`test assembleDebug assembleDebugAndroidTest` 均成功，构建记录为 `experimental-academy-entry-fix-build.log`。随后一次 instrumentation 导航校准在发出输入前因 headless 初帧超时而停止；用只读 ADB 截图确认地图后，显式 `input -d 4 tap 200 420` 成功打开初级学园课程面板。前后截图位于工作区 `test-evidence/academy-map-before.png` 与 `test-evidence/primary-school-after.png`。面板默认选中“蹦蹦跳跳体能课”，页面显示 10 分钟；未点开始。该次只证实坐标与游戏导航，不代表 App 内 HeadlessDisplayCapture 验证通过。

当前主 APK SHA256 为 `3FACBDD09A26FF198736628894649CD7198894939AB418EDAAF5778B13542206`，测试 APK SHA256 为 `4BD2236135766549B1028CDCE90B7F883CB4E4DF8CF9A6E65C8E38EEE479785D`，两者已安装。随后在静态页面开始下一次 headless 导航校准仍遇到首帧超时；尚未点击课程列表或开始按钮。待解决阻塞收敛为 display 4 静态画面下 ImageReader 未收到新帧（surface rebind 后仍未恢复）；不能将 ADB 系统截图代替 App 内 headless capture 验收，也不能进入课程步骤。

下一步先修复并复测 display 4 的 headless 新帧获取与恢复，再从宠物首页开始 3 轮。任何识别/采集失败继续 fail-closed；当前 3 轮实验仍未完成。

2026-10-05，Asia/Shanghai。原始请求留存于 `deliverables/experimental/request.txt`。

## 最新续验记录（2026-10-05 18:12）

用户已解锁并要求继续完整验证。ADB 设备已恢复可读，型号 FOA-AL00；`showing=false`、屏幕 ON。虚拟 display 4 存在且报告 720×1600，但当前亮度为 0.0。独立运行 `screencap -d 4` 得到全黑画面（本地留证 `../test-evidence/resume-display4-check.png`，7904 bytes）。只读 Activity/Window 检查显示 QQ task #2066 不可见，QQ 两个窗口 `NO_SURFACE`，没有可识别的宠物页面。未对 QQ 发送输入，也未启动新实验。

因此，上一轮 STOP 仍为 0/3；本次仅完成解锁后设备与显示诊断。必须先在现有虚拟屏应用中把 QQ 宠物恢复到 display 4 宠物首页，并确认预览关闭，才能继续屏幕校验。当前自动操作按黑帧 fail-closed 停止。

## 此前续验记录（约 17:xx）

用户要求继续完整验证后，完成了新一轮学园ROI离线校准和构建。手机再次自动锁屏；尚未安装/实跑此轮最终模板，也尚无新增QQ动作。前一轮 STOP 结果仍是下文所记录的真实结果，不能覆盖为通过。

- 稳定学园ROI收窄到 `(115,400)-(139,420)`，模板从上一轮真实学园页截图裁出。两个时间不同的学园截图在本地同算法重采样得分0.987；旧出门页/home/room负例为0.569/0.871/0.871。Android实机复核仍待完成。
- 最新构建日志 `experimental-academy-roi-final-build.log`：test、assembleDebug、assembleDebugAndroidTest 均 BUILD SUCCESSFUL；`git diff --check` 无空白错误。
- 最新主APK哈希 SHA256 `62010c0995efe37c8c141560ba0a34d450c2cff4cfe83caf9e43d09021315837`，最新测试APK SHA256 `0df7cfee03647b47846e95658a53e59423ac0f8d0d1d7ad4ef0e47d9880b4688`。两者均已 `adb install -r` 返回 Success；displayId=4仍在。安装没有对QQ或物理屏发送输入。实际 ROI 与 session 实机验证仍待解锁。
- 当时物理手机锁屏，displayId=4仍存在；已请求人工解锁。用户后来回复已解锁，最新的18:12检查见上节。

## 1. Verdict

**STOPPED**。完成 **0/3 轮**。最后一次实跑在 17:36:33 停止，停止前没有开课、喂食、擦洗或击掌。不是 3_ROUNDS_PASS。

```text
stopReason = PAGE_MISMATCH:SELECT_COURSE:11-academy-after:0.7492533587509078
lastKnownState = SELECT_COURSE
threshold = 0.97
physicalDelta = 0
```

进入学园后，新帧没有匹配已配置的学园 ROI，立即停止；没有继续点击课程或开始按钮。后续只读复查也显示该地图布局与旧校准截图不同。本次按照请求允许的安全 STOP 分支结束，不继续尝试业务动作。

直接证据：

- `deliverables/experimental/1791192978270/verdict.json`
- `deliverables/experimental/1791192978270/events.jsonl`
- `deliverables/experimental/1791192978270/round-1-11-academy-after.png`
- `deliverables/experimental/1791192978270/round-1-STOP-last-frame.png`
- `deliverables/experimental/academy-stable-recheck.png`

## 2. 每轮结果

表中健康值是程序的保守颜色条估计，不能视为精确文本读值。Ready 截图目视显示体力 95、清洁 98、心情 83；程序记录为 95/97/82。没有将估计值加 1 来放行。

| Round | 开课前体力 | 清洁 | 心情 | 喂食次数 | 清洁次数 | High-five | 成绩 | 结束心情 | 总耗时 |
|---|---:|---:|---:|---:|---:|---|---|---|---|
| 1，未开课即停止 | 95 | 97 | 82 | 0 | 0 | NOT ATTEMPTED | 未开课，无成绩 | 未读取 | 14.945 秒，截至停止 |
| 2 | — | — | — | — | — | NOT RUN | — | — | — |
| 3 | — | — | — | — | — | NOT RUN | — | — | — |

日志明确记录 `experimentalSession=true`、`allowLowMood=true`、`roundLimit=3`、`experimental_mood_override=true`。本次低心情没有阻止 Ready Check。体力与清洁均达程序要求，但页面识别失败仍阻止开课。

本实验未消耗饼干或肥皂，没有新成绩。前一阶段的消耗与那一节人工授权课程不计入本实验。

## 3. Performance

| 项目 | 本次实际记录 |
|---|---|
| 3 轮总耗时、平均每轮耗时 | NOT MEASURED，没有完整轮次 |
| 最后一次受限启动到 STOP | 14.945 秒，monotonic 总计时 |
| 两次健康读取，至读值日志 | 2,509 ms + 2,535 ms = 5,044 ms；不含各自后续关闭面板 |
| 首页检查至最终 Ready 面板关闭 | 约 9.8 秒，根据日志时间区间 |
| 进入学园的导航阶段 | 约 4.2 秒，至失败帧日志 |
| 喂食、擦洗 | 0 次；该路径业务耗时未测 |
| 课程本体、30 秒等待、击掌、结果等待 | 未执行，不能给性能结论 |

三次已记录的受限启动尝试分别持续 8.555、13.807、14.945 秒，合计 37.307 秒；这是失败尝试的运行时间，不是 3 轮课程耗时，也不含构建、校准或等待用户恢复环境的时间。

当前阻塞是地图页面识别稳定性。在已执行阶段中，健康检查占用时间较多；没有完整课程数据，不能据此判断正式循环的最大耗时项。

## 4. Mood observations

最后一次 Ready 截图显示心情 83，程序保守估计 82；实验路径允许继续导航。没有成绩样本，无法给出 mood → grade 观察序列。没有任何击掌数据，不能宣称击掌提高 A/A+ 概率，也不能宣称心情无影响。

## 5. Attribute observations

自动读取：**NOT MEASURED**。日志 attr1/attr2/attr3 为 UNKNOWN，未影响实验放行；没有属性增量或 ratio 数据。保存了包含属性显示的学园截图，未实现职业决策。

## 6. Optimization recommendation

本次不开始性能优化。

1. 先实测学园固定 ROI 在页面稳定后的可靠性，再继续课程链路。最终构建已更新到本次观察的 ROI，但尚未实跑验证，不能宣称此阻塞已经解决。
2. 喂食、清洁与击掌路径尚未在完整实验中执行，不建议提速，也没有可量化的预计节省。获取完整轮次数据后，再评估现有 900 ms 页面等待、2,500 ms 喂食等待和约 25 秒擦洗；现在不改这些时长。

## 工程与验证留痕

- 分支 `feature/qqpet-mvp-session`；HEAD `05a3416f2946a68f54db9121f6d9ce5dc3a37b40`；基线 `0390d8f07ac4071658e77bff9b0974c57f16a186`。原有未提交修改保留，本次新增内容也未提交，未推送。
- 实测型号字符串是 `FOA-AL00`，不是旧检查中的 `FOA_AL00`；手机、ROM、QQ 版本没有因此改变。QQ 9.3.70 (16410)，目标 displayId=4，720×1600@320dpi。
- 实验实现位于 `app/src/debug/java/com/ynk/virtualdisplay/automation/PetExperimentalSession.kt`；入口是明确 opt-in 的 `PetExperimentalSessionTest`。固定 3 轮，不能扩展为 5/10 或无限循环。正式/default Service 的健康未校准保护保留。
- 实验实现包含每轮检查/恢复/最终 Ready、固定课程、monotonic T+28～40 秒目标窗口、逐次目标验证的最多 10 次输入、690 秒结果期限、可选成绩 A 的严格模板和 UNKNOWN、课程后状态与增量。除已执行的前置部分外，这些路径均未通过完整实跑验证。
- 连续擦洗沿用同一 downTime 的 DOWN → MOVE → UP 和 FINGER 事件，finally 释放。没有优化擦洗速度。日志将击掌输入发送数与业务确认区分；业务无可靠确认时不填造假的成功次数。
- 复用 `pet_mvp.log` 的六字段事件格式，result 可包含结构化 round 数据；每次实验目录保存同一格式的本次事件证据。不是新建 benchmark 架构。
- 最终 `test`、`assembleDebug`、`assembleDebugAndroidTest` 成功。最终日志在工作区 `experimental-academy-build.log`。
- 两项真实截图回放检查通过。另建测试屏的 Gate 回归仍出现就绪/过渡帧/新帧超时，未宣称通过。实际 displayId=4 上单次打开健康面板的回归通过，正确页面变化且物理屏计数不变，见工作区 `experimental-target-gate.log`；最后实跑在每次校验与输入前继续检查物理屏计数，最终增量 0。
- 最后实跑使用学园 ROI 更新前的安装包，对应工作区 `experimental-navigation-build.log`。最终学园 ROI 更新后的 APK 已编译，尚未安装或实跑；不要把最终 APK 的哈希当作最后实跑二进制的哈希。
- 最新主 APK：`deliverables/qqpet-mvp-debug.apk`，SHA256 `d776de90c35324203d57319cdd0023bcf70f9c537f51d41b84db58503b883853`；匹配的测试 APK：`deliverables/qqpet-mvp-gates.apk`。
- 新增 debug 实验类、测试入口、九个固定 ROI PNG、`calibrate-experimental-templates.py`、`run-experimental-session.ps1`、`collect-experimental-evidence.py` 和本报告。修改 HeadlessDisplayCapture、测试页与 Gate 测试，以及精确机型检查；完整工作区改动包含前一阶段尚未提交的文件，不能全部算作本次新增。

没有引入 OCR、AI/LLM、Accessibility、Root/LSPosed、通用 anchor、多课程策略、UI 重构、core 抽取或性能框架。当前停止后不启动下一阶段优化或额外课程。


