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
# Shizuku active capture / hybrid 实机报告

2026-10-05，FOA-AL00 Android 12，QQ 9.3.70 (16410)，displayId=4，720×1600@320dpi。

## Gate A: SHIZUKU_CAPTURE_PASS

- Static: 10/10；HOME template score=1.0；physicalDelta=0；p50=736ms，p95=2161ms（含首次绑定启动）。PNG约849KB，采用ParcelFileDescriptor pipe二进制传输，不通过Binder传递整张ByteArray。
- Transition: 首页→健康→首页，3次往返/6次操作全部通过；12/12截图有效，HOME/HEALTH score=1.0；physicalDelta=0；p50=758ms，p95=1209ms。
- 所有截图由手机内Shizuku shell uid=2000执行 /system/bin/screencap -d 4 -p，PNG签名、decode、720×1600尺寸、非全黑、目标模板均通过。物理display0显示测试页，与实际捕获宠物页不同。
- 证据：deliverables/capture-gates-updated/1791201091159（static）；deliverables/capture-gates-updated/1791201186621（transition）。

## Gate B: Hybrid PASS

- 证据：deliverables/hybrid-gates/1791201752518。
- 健康页打开/关闭后将ImageReader等待窗口设为0ms，确定触发TIMEOUT，不使用stale cache。
- 两次fallback成功（636ms、741ms），截图正确识别HEALTH、HOME，score=1.0；physicalDelta=0。
- 日志包含imageReaderResult=TIMEOUT、fallbackAttempted=true、fallbackResult=OK、captureSource=SHIZUKU_FALLBACK、source=SHIZUKU_SCREENCAP、frameDeliveryMiss=true。

## 实现与回归

- 保留ImageReader fast path，默认仅等待350ms。验证调用先清理已排队旧帧；超时转手机内Shizuku主动capture。不再Surface rebind或延长等待，也不以动作前缓存证明动作后状态。
- 使用同一Shizuku授权的最小capture-only UserService，保留现有display-owning UserService和display4句柄；不重启显示服务。UserService仅执行固定参数screencap并返回FD+小型结构化metadata，不注入输入、不写公共相册、不依赖PC/USB/adb。
- 回归test/assembleDebug/assembleDebugAndroidTest成功（hybrid-capture-build.log）；截图回放3 tests通过。
- 编译曾出现已有ALL_DISPLAY_FLAGS顶层常量的增量缓存解析错误，关闭Kotlin增量缓存进行完整编译后成功；未修改模型/ROI/阈值。
- PetInputController及连续SOAP MOVE实现未在本任务修改；模板阈值仍0.97；production/default三项健康校准保护保留；displayId<=0禁止capture/input。
- 主APK deliverables/qqpet-mvp-debug.apk，SHA256 9E8508FC66D465B8BFC9038B7860B28AD516ABF089ACB3D9EEB0C94D8B588A61；已安装。
- SHA仍05a3416f2946a68f54db9121f6d9ce5dc3a37b40，分支feature/qqpet-mvp-session；本次新增/修改未提交。

## Gate C

采集门禁已全部通过。首次hybrid实跑1791201870302因真实页面不匹配STOP：13次ImageReader新帧通过、无timeout/fallback。before已是课程面板，旧路线又点初级学园而关闭面板；使用现有courseListProbe跳过已打开的面板点击，未改变ROI或阈值。该次消耗已有饼干1份，before88/94/78，ready98/94/79，0轮、未开课、physicalDelta=0。后续实跑结果见下文。


## 最终 Verdict：STOPPED（0/3 完成，实际已开课1节）

最新 Run `1791202276391`。20:11:34 在课程详情模板1.0确认后，向display4发送开始输入。900ms后拿到fresh ImageReader画面（等待12ms），但当时仍是学园过渡画面，learning_initial=0.6390273238925199，小于原阈值0.97；立即STOP。没有采集超时，没有降低阈值，没有继续击掌或续课。

STOP后仅通过App内capture读取现场，截图确认“正在学习”、剩余00:08:12。因此开始输入实际成功，但首次动作后取样过早，未观察到稳定学习页。证据 `../test-evidence/hybrid-stop-current.png`。这属于新出现的页面过渡/状态确认问题，不能记为ImageReader frame-delivery miss，也不能将后来截图倒算为原流程PASS。课程自然继续；本轮成绩未采集。

| Round | before stamina | cleanliness | mood | feed | clean | high-five attempted/sent | grade | after stamina/cleanliness/mood | duration |
|---|---:|---:|---:|---:|---:|---|---|---|---|
| 1 | 98 | 94 | 79 | 0 | 0 | 0/0 | 未采集 | 未采集 | 19.714秒至STOP，非完整课程时长 |
| 2 | — | — | — | — | — | 未执行 | 未执行 | 未执行 | — |
| 3 | — | — | — | — | — | 未执行 | 未执行 | 未执行 | — |

健康值为现有进度条保守估计，并非OCR。最新Run physicalDelta=0。证据目录 `deliverables/experimental/1791202276391` 保存events.jsonl、verdict.json、动作前后帧。

## Capture statistics（门禁与真实Session分开）

- 最新Session：ImageReader success=9，timeout=0，fallback=0，fallback success=0；fallback p50/p95无样本，不报告为0ms。
- 两次真实Session合计：ImageReader success=22，timeout=0，fallback=0；观察到fallback频率0/22，样本短，不能推断长期漏帧已消失。
- 强制timeout门禁：timeout=2，fallback=2，success=2；fallback p50=636ms、p95=741ms。
- 主动静态门禁10/10，转换门禁12/12；时延见Gate A。首次绑定冷启动计入静态p95。

## Root cause conclusion

1. 原Run1791199799477的STOP可确认是动作成功后ImageReader未交付新buffer导致的误判；具体驱动/渲染器根因尚未诊断，不能声称已修复底层漏帧。
2. Hybrid已在真实display4上证明：新buffer缺失时主动截图能确认真实页面并通过同一模板。该故障路径已修复。最新STOP属于实际新帧中的页面模板不匹配。
3. ImageReader底层漏帧仍可能存在；当前保留fast path和遥测，不需要为此次MVP先重写采集器。
4. screencap实测暖启动约0.7秒，当前MVP足够；正式三轮尚未完成，长期fallback频率与总体耗时未验证。
5. 目前没有证据值得扩大为direct SurfaceControl实现；只有长期fallback高频并明显拖慢流程后再考虑。

## 当前交付、changed files与下一步

- 当前SHA：05a3416f2946a68f54db9121f6d9ce5dc3a37b40；feature/qqpet-mvp-session；本阶段改动未提交。
- 本阶段新增：ICaptureService.aidl、CaptureUserService.kt、ShizukuActiveCapture.kt、PetActiveCaptureTest.kt、本报告。
- 本阶段修改：HeadlessDisplayCapture.kt、IDisplayRepository.kt、ShizukuDisplayRepository.kt、PetExperimentalSession.kt（capture遥测和已展开课程面板判断）、PetExperimentalSessionTest.kt（统计）；三份阶段/技术报告同步更新。其他git status改动来自前阶段，未归入本次capture修复。
- test / assembleDebug / assembleDebugAndroidTest成功，最新完整构建日志hybrid-panel-guard-build.log；截图状态回归3/3，采集与输入门禁全部通过；git diff --check通过。
- 已安装主APK：deliverables/qqpet-mvp-debug.apk；测试APK：deliverables/qqpet-mvp-gates.apk。
- 测试APK SHA256：DEB1481E04389241BA9D3D948A23C4128933B579C0FDBD315D593747AA2BA498。
- 当前阻塞：开始课程后，第一次新帧仍可能处于过渡页；按用户“不改ROI/阈值、识别失败STOP”要求停止，没有追加学习页修正或继续开课。
- 下一步需要针对这个实跑新失败处理稳定页面确认，再重新验收限定三轮；本次不能声明3_ROUNDS_PASS。

## 2026-10-05 20:30：用户授权继续后的实际进展

用户明确要求恢复健康、学习中击掌、课后继续学习，原三轮授权持续有效。恢复环境时发现display4仍在但QQ任务2066位于display0且不可见。通过系统am display move-stack 2066 4恢复已有任务，再指定display4将现有QQ启动任务置前；没有向物理屏触摸。App截图恢复正常。旧课程已经结束，点击出门弹出20:11课程成绩单，人工读图为B+。旧课程不计入本次三轮验收。

实跑发现“继续学习”返回学园地图，不能固定假设课程面板已经展开。最小修正仅复用已有模板：结果→学园地图，确认面板存在时关闭；否则直接返回室外→首页。实验可以从已验证的结果/课程面板/学园/室外页恢复到首页。开课与其他已发动作后增加最多8秒只读目标状态观察，目标阈值仍0.97，不重复发送动作；未确认则STOP。此窗口处理游戏页面过渡，与ImageReader等待窗口350ms不同，没有延长采集timeout。

test、assembleDebug、assembleDebugAndroidTest均成功；构建日志start-transition-settle-build.log、pending-result-resume-build.log、result-map-transition-build.log。保留真实失败Run1791203090192（出门触发旧成绩单）、1791203218382（继续学习返回学园地图）。

运行Run1791203355328，固定三轮：第1轮before/ready=体力94、清洁91、心情79，无需恢复物品。20:29:41发出开始，学习页1.0确认；20:30:10.498击掌目标0.9974287400145243，发出1次，开始偏移29.416秒。后续目标消失，窗口内重新识别未命中，不盲点；最多10次的上限未改变。20:30:21进入WAIT_RESULT。游戏业务是否接受击掌尚未验证，不将INPUT_SENT标为业务成功。

## 2026-10-05 20:50：第1轮成绩、升级弹窗及第2轮恢复链

Run1791203355328第1轮课程完成，实际成绩截图人工读为A、学分+10、力量+2，提示收到主人鼓励。之后“继续学习”出现真实升级弹窗：升入初级学园2年级。遮罩使academy模板0.8497，8秒只读观察未通过后STOP，physicalDelta=0。该Run采集609次ImageReader成功、1次timeout、1次Shizuku fallback成功（1122ms）。真实漏帧已被fallback覆盖，没有因此STOP。

只针对实跑新问题增加debug升级弹窗模板upgrade.png（实帧固定ROI245,558,480,610），识别后只点击关闭(360,1248)，继续使用原学园/课程模板，阈值0.97。未改变课程或学习ROI。增加一次性实验恢复参数，仅允许已验证Run ID，必须存在原课程/result日志；恢复不重复开课，三轮总上限不变。

Run1791204208048关闭弹窗、回首页、读到第1轮after体力88/清洁89/心情79，确认完成1轮；第2轮恢复已使用饼干1份。进入洗澡页面后实验soap模板0.9692107未通过，停止在拖动前。对比实帧发现项目已有pet_mvp/soap.png准确匹配当前肥皂，旧实验模板来自另一个soap姿态。复用已有主模板和相同ROI，不降低阈值、不新增课程模板。

立即运行已有PetCalibrationGateTest，对已有肥皂执行约24秒连续DOWN/MOVE/UP擦洗，测试通过，物理屏无输入。库存6→5，页面“已经很干净啦”；校准前后帧和round2-soap-recovery.log保留。已有App校准返回首页测试通过。此次恢复消耗计入第2轮，不能因为跨Run而漏计。

Run1791204552957从已完成第1轮恢复，保留原after88/89/79，不用恢复后的健康值覆盖课程后测量。第2轮before/ready98/99/80，无额外消耗品；20:49:35.584开课，20:50:05.013识别目标0.9990264161并击掌1次，偏移29.429秒。目标随后消失，不盲点；第2轮已进入WAIT_RESULT。剩余只有第2、第3轮，无无限循环。

构建upgrade-round-resume-build.log、existing-soap-resume-build.log均test/assembleDebug/assembleDebugAndroidTest成功。改动局限debug实验Session、instrumentation恢复参数、固定升级模板及运行脚本；production/default健康保护及PetInputController没有修改。

