# QQPET_PRODUCTIZATION_SLICE1

本轮实现健康观测、截图有效性与事件留痕，未开启新课程。实验 Session、现有页面识别和底层截图/输入实现保留。

```text
QQPET_PRODUCTIZATION_SLICE1

BASELINE
branch: feature/qqpet-mvp-session
previous_sha: 05a3416f2946a68f54db9121f6d9ce5dc3a37b40
baseline_sha: 3a2eb37b26e32addb5e476ece66dc9e11a5830b6
git_state: 实验基准提交后 clean；Slice1 单独提交；无来源不明文件

OBSERVATION_MODEL
Known: value / observedAt / confidence / evidence / source
Unknown: attemptedAt / reason / evidence；无法进入数字业务判断
Stale: previous Known / checkedAt / reason；健康值有效期 60 秒
Unsupported: reason；力量、智力、魅力、金币没有读取器
implemented: YES

HEALTH_READING
energy:
method: 固定健康面板确认 + 固定 ROI + 0~9 二值数字模板
accuracy: 已核对留出帧 19/19；现场成功读取 87
ambiguous_behavior: Unknown；停止依赖该读数的动作
cleanliness:
method: 同上，独立数字 ROI
accuracy: 已核对留出帧 19/19；现场成功读取 90
ambiguous_behavior: Unknown；停止依赖该读数的动作
mood:
method: 同上，独立数字 ROI
accuracy: 已核对留出帧 19/19；现场成功读取 77
ambiguous_behavior: Unknown；不猜数字
bar_estimate:
still_available: YES，CONSERVATIVE_BAR_ESTIMATE
role: 辅助信息和原有保守边界；与 DIGIT_RECOGNITION 分开保存

FRAME_LIVENESS
checks: 非零目标屏、连接/存在、PNG、decode、尺寸、非黑帧、capture identity
failure_behavior: 一次无效立即禁止业务输入；最多三次重新获取；连续三次失效锁定本次 capture 为 STOP
input_blocked_on_invalid: YES；手势中失效停止后续 MOVE，仅发送同屏 UP 收尾
result: PASS

SESSION_EVENTS
storage: App 私有 files/pet-events/<sessionId>.jsonl，append + fd.sync
event_fields: eventId / sessionId / sequence / wallTimeMs / monotonicMs / bootId / source / kind / payload / evidence
sources: APP_AUTOMATION / GAME_OBSERVATION / USER / DEBUG_ADB
sample_events: FRAME_VALID / FRAME_INVALID / HEALTH_OBSERVED / INPUT_SENT

REGRESSION
unit: 14/14，含 13 项新增事实基础测试
instrumentation: 8/8
capture: 静态 10/10；往返截图 12/12；强制漏帧 fallback 2/2；physicalDelta=0
study: 原成绩单/列表/详情/学习中模板回放 3/3；未新开课
build: test / assembleDebug / assembleDebugAndroidTest PASS；两种 APK 安装成功

EXISTING_BEHAVIOR_CHANGED:
YES：新增精确读数和过期保护；无效画面阻断输入；没有放宽原有授权条件

BLOCKERS:
无本轮阻塞；数字验证仅适用于固定 QQ/设备环境；未宣称跨字体/版本准确率

VERDICT:
PASS

NEXT_RECOMMENDED_STEP:
基于这些事实与事件实现托管总控的有界暂停/恢复。

END
```

## 基准的事实范围

`evidence/baseline/audit.md` 逐项说明未提交改动来源；冻结前执行完整构建、原有回放和十次静态截图。没有 reset、clean、强制 checkout 或删除未提交文件。

基准保留三节实际课程的 A / A / A+ 证据，也保留恢复链的限定：不是一次无中断的 App 三轮运行。第三轮的十次补点来自获授权的 **DEBUG_ADB**；App 十连击版本此前仅构建通过。没有把这些补点改记为 App 自动化。

完整原始证据为 `deliverables/frozen-three-round-evidence.zip`，546 个文件；SHA256 清单和精简日志/成绩/报告已随基准提交。ZIP 和 APK 留在本机，不把 457 MB 原始证据塞进源码仓库。

## 数字读取及业务语义

仅使用四张人工核对的训练帧，覆盖 0～9；没有使用颜色条生成数字标签。字体在已核对样本中一致，字高约 17～18 px，左端固定，字符之间能按空列分割。体力、清洁、心情 ROI 分别为 `(256,228)-(308,259)`、`(256,304)-(308,335)`、`(256,380)-(308,411)`。

读取时先用原健康面板模板确认页面，阈值仍为 **0.97**。数字区域用 `max(R,G,B)<100` 形成二值掩码，分割后归一化到 24×32，按二值像素相似度比较 10 个固定模板。数字相似度至少 0.98，第一名与第二名差距至少 0.08；字形尺寸/位置不支持、空白、低相似度、歧义或越界均返回 Unknown。这个数字相似度是模板测量值，不是统计学正确概率，也没有改变原页面模板阈值。

19 张独立留出帧的 57 个读数全部正确；同一字体/页面中的重复样本存在相关性，这个结果不能解释为所有场景下 100% 准确。手机现场健康页九次记录均得到 Known；最后核对的真实画面显示 87 / 90 / 77。环境固定为 FOA-AL00、Android 12、QQ 9.3.70 (16410)、720×1600 @ 320dpi。

训练/留出标签、原帧哈希和固定模板哈希在 `evidence/slice1/health-ground-truth.json`。`calibrate-health-digits.py` 只读取这些已校验原帧，能够重现 10 个模板；不接触手机，不建立通用 OCR。模板哈希按 LF 规范化，避免 Windows CRLF 差异。

业务判断要求精确数字 Known 且未过期，再取精确数字与保守颜色条的较小值，保留原有边界保护。Unknown、Stale、Unsupported 都抛出明确停止原因，不转成 0。颜色条不能代替失败的精确读取。颜色条的测量来源单独标为 CONSERVATIVE_BAR_ESTIMATE；不把它展示成精确值。

PetExperimentalSession 保留；健康读取改为正式观测，点击开始前再验证新鲜度。开课前历史读数单独用于课后差值，不能用来授权后续开课。实验低心情例外仍保留；production/default 健康保护未删除。

## 截图与输入的改动边界

ImageReader fast path、350 ms fresh-frame 窗口、手机内 Shizuku screencap/PFD fallback、动作后重新观察、8 秒页面转场确认全部保留。没有重写 capture、VirtualDisplay 或 TemplateMatcher。

HeadlessDisplayCapture 在返回新帧或近期缓存之前执行基础检查。无效帧会立即让共享 FrameTrustGate 禁止输入，再有界获取新的画面；连续三次失效锁定本次 capture，不实现产品暂停/恢复状态机。Shizuku 主动截图也使用同一基础检查，原有 PNG 签名、decode 和 shell identity 验证保留。

PetInputController 增加共享 gate 和目标屏存在/连接检查。坐标、displayId 注入、DOWN/MOVE/UP 和相同 downTime 未重写。连续手势中也检查 gate；失效后没有后续 MOVE，只在同一虚拟屏发送 UP 清理已发出的 DOWN。

有界失败测试直接运行手机上的 HeadlessDisplayCapture，使用测试 transport 提供黑色已解码 Bitmap：三次失败后输入发送数为 0；另一测试一次黑帧后重新获取有效帧，才允许输入。这些故障注入没有向 QQ 发送输入。实际健康页往返和 Hybrid 测试均确认 physicalDelta=0；合成测试虚拟屏的单次输入也确认 physicalDelta=0。

最后一次 Hybrid 门禁 `1791218554877`：两次 ImageReader timeout、两次 fallback 成功；fallback p50/p95 = 639/760 ms，端到端验证样本 p50/p95 = 1089/1427 ms。静态主动截图 p50/p95 = 759/1399 ms；往返截图 = 736/1202 ms。没有进行性能架构优化。

## 事件与证据

正式事件写入 App 私有目录，UUID eventId、sessionId、连续 sequence，现实时间、elapsedRealtime、bootId 和来源都持久化。序号在同一事件流重开时继续；使用同步 append 和 fd.sync。bootId 读取失败时会明示 Unknown，不造一个值。本机实际导出的 bootId 均为 Known。

导出了 15 个事件流、114 个事件；序号逐文件核对连续，eventId 无重复。包含 86 FRAME_VALID、5 FRAME_INVALID、9 HEALTH_OBSERVED、14 INPUT_SENT。5 个无效事件是门禁刻意调用 display0 的拒绝记录。实际来源为 GAME_OBSERVATION 和 APP_AUTOMATION；USER 与 DEBUG_ADB 的持久化/区分由测试覆盖，并没有伪造真实用户或 ADB 游戏操作事件。

健康 Unknown 和 ERROR 已接入；COURSE_STARTED / LEARNING_CONFIRMED 在既有 Session/FSM 成功确认学习页面后接入。本轮未开课，所以没有声称现场产生过新的课程事件。

`evidence/slice1/` 保存测试 XML、门禁 JSON/JSONL、实际事件与清单。原始截图包为 `deliverables/slice1-evidence.zip`。`deliverables/slice1-artifact.json` 记录最终交付 APK 的源 SHA、大小与 SHA256。构建通过 QQPET_SOURCE_REPO 指向真实源仓库，避免 ASCII 镜像的旧 HEAD 被写进 APK。

## 改动文件

- 新增事实类型：ObservedValue、HealthObservations、HealthDigitReader、FrameLiveness、SessionEvent、AutomationFacts。
- 接入：PetHealthCheck、HeadlessDisplayCapture、ShizukuActiveCapture、PetInputController、两个 repository、PetStateMachine、PetAutomationService、保留的 PetExperimentalSession。
- 测试：ObservationFactsTest、PetHealthReadingTest、PetRuntimeFactsTest，既有门禁传入共享检查器；固定训练模板与独立留出资产。
- 构建/留痕：build.gradle.kts、build-mvp.ps1、离线模板重现脚本、本报告、调试报告、evidence/slice1。

构建路径仍使用 Windows ASCII 镜像。事件导出曾尝试 run-as 复制到 App 外部目录，被 Android 拒绝；最终用只读 run-as 二进制管道导出原文，不改变正式存储位置。
