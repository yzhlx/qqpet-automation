# MVP v1 构建及验证状态

日期：2026-10-05（Asia/Shanghai）

## 基线与补丁

- 当前 HEAD：`0390d8f07ac4071658e77bff9b0974c57f16a186`（detached HEAD，未提交补丁）。
- 应用前 git 工作树干净；完整阅读 README.md、STEELMAN.md、TO_CODEX.md。
- 用户给出的目录已经解压。复制原包到工作区 `../qqpet-mvp-v1` 后审计并运行 `apply_mvp.py`；未使用 allow-other-commit。
- scrcpy 固定提交：`3fcc177da5b6b4514d0e8e8d90d7d58d6731eac9`。
- Shizuku-API 固定提交：`a27f6e4151ba7b39965ca47edb2bf0aeed7102e5`。

## changed files

- 修改：`app/src/main/AndroidManifest.xml`、`MyApplication.kt`、`ui/main/MainActivity.kt`、`ui/main/MainUiState.kt`。
- 新增：`app/src/main/java/com/ynk/virtualdisplay/automation/` 中的 8 个补丁文件、`ui/screens/PetMvpScreen.kt`、`app/src/main/assets/pet_mvp/README.txt`。
- 验证专用：`app/src/debug/AndroidManifest.xml`、`app/src/debug/java/com/ynk/virtualdisplay/automation/GateTestActivity.kt`、`app/src/androidTest/java/com/ynk/virtualdisplay/PetDisplayGateTest.kt`。
- 复跑脚本：`build-mvp.ps1`、`run-mvp-gates.ps1`，及本报告。
- 增量包本地副本修正：`../qqpet-mvp-v1/tests/test_apply.py` 的 UTF-8 测试夹具写入；原 Documents 包未修改。
- 上游 settings.gradle.kts、gradle.properties、依赖版本和模块结构保持原内容。

## build/test result

- 补丁 Python 测试：2/2 通过。
- 固定仓库 wrapper `gradlew.bat test`：BUILD SUCCESSFUL；上游 ExampleUnitTest 1/1 通过。这个上游测试本身不证明 capture/input 正确。
- `gradlew.bat assembleDebug`：BUILD SUCCESSFUL。
- `gradlew.bat assembleDebugAndroidTest`：BUILD SUCCESSFUL。
- 主 APK 与 Gate 测试 APK 均 adb install 返回 Success。
- 主页面启动返回 Starting Intent。随后真机 Gate A/B JUnit 测试通过：`OK (1 test)`。
- 使用固定 Gradle 9.3.1、JDK 21、compileSdk 36.1、build-tools 36.0.0。Gradle ZIP 通过仓库指定 SHA-256 校验，SDK ZIP 通过官方索引 SHA-1 校验。
- Windows 中文路径导致 Kotlin DSL/AIDL/测试进程错误。解决方案是临时目录下真实 ASCII 副本，包括工程、SDK、Gradle、JDK、缓存；不降级依赖，不改业务架构。
- 复跑：在本机运行 `./build-mvp.ps1`，依次 test、assembleDebug。该脚本依赖本次已准备的 `%TEMP%/QQPetMvpTools`、`QQPetMvpSdk`；临时目录被清理后需重新准备工具。
- 已保存构建日志和单元测试 XML 到 `deliverables/`。

## APK 路径

- `deliverables/qqpet-mvp-debug.apk`
- SHA-256：`8cb90699bbdb757dd637da910f8e511aa9b8c1a49c26f14a59b40b7d9f841b8a`
- `deliverables/qqpet-mvp-gates.apk` 为真机测试 APK。

## 真机 Gate 结果及阻塞项

- 设备：FOA_AL00，Android 12 / API 31，ADB device 状态。
- Shizuku server 运行中。
- 用户解锁及授权后，确认 showing=false、screenState=SCREEN_STATE_ON、API_V23 granted=true。
- 2026-10-05 14:00:15.533：`GATE_A_PASS display=3 preview=false frames=3 size=720x1600`。
- 2026-10-05 14:00:16.224：`GATE_B_PASS display=3 targetDelta=1 physicalDelta=0 taps=1`。
- JUnit：`headlessThenSingleScopedTap` 通过，`OK (1 test)`，耗时 2.989 秒。
- Gate A 直接使用补丁 HeadlessDisplayCapture，未打开 DisplayActivity 预览，成功保存三张 720×1600 帧，断言像素颜色、尺寸和 Bitmap 未被回收。
- Gate B 直接使用补丁 PetInputController，在测试 displayId=3 的 (360,800) 注入一次 tap；目标测试页面收到一次 ACTION_UP，物理屏测试页面触摸计数不变。
- 已目视检查截图中的测试页面：蓝色背景，display=3；没有 QQ 页面。
- 截图：`deliverables/pet_mvp_gates/headless_0.png`、`headless_1.png`、`headless_2.png`。
- 日志：`deliverables/gate-instrumentation.log`、`deliverables/gate-logcat.log`。
- 测试后 dumpsys display 仅有 displayId=0，测试虚拟屏已释放。
- 最初 adb pull 因 Windows 中文目标路径失败，随后经 ASCII 临时路径成功取回三张截图；run-mvp-gates.ps1 已修正取回路径。未重复发送测试 tap。
- Gate A/B 无剩余阻塞。本次结果仅证明当前手机、当前 APK 下的短时 capture 与单次输入；不代表 QQ 流程、锁屏稳定性或多轮续课已经验证。

## 下一步及停止边界

本阶段目标已经完成，停止在 QQ 校准之前。如需复跑无害 Gate A/B，可运行 `./run-mvp-gates.ps1`。测试先新建 720×1600@320dpi 显示器，只打开无害 Debug 测试 Activity，在没有 DisplayActivity 预览的情况下获取 3 张帧；Gate A 通过后才使用 PetInputController 在指定非零 displayId 注入一次 tap，并断言目标屏触摸计数 +1、物理屏计数不变。最后释放测试虚拟屏。

只有真实的 `GATE_A_PASS`、`GATE_B_PASS` 日志、JUnit 通过和截图能证明对应 Gate 通过。测试没有 QQ 包名、坐标、模板和业务点击。

Gate C 已开始只读导航与截图，尚未完成状态模板校准；三个业务坐标、四个 ROI 和 PNG 模板仍未正式校准，自动 Session 被拒绝。启动真实课程前必须再次向用户报告并等待明确确认。未引入 AI/OCR/Accessibility/Root/LSPosed 框架/Hook/广告/会员/UI 重构，未抽取 core。上游已有的 HiddenApiBypass 依赖保持不变。

## Gate C 只读课程审计（2026-10-05）

- QQ 位于 displayId=4，720×1600@320dpi。通过 ADB 指定屏幕导航；保存帧直接使用 HeadlessDisplayCapture，未打开 DisplayActivity 预览。未点击“去上课”，未启动 Session。
- PetMvpScreen 原布局无法滚动，调试截图按钮被挤出屏幕。仅增加 verticalScroll；修复后重新 test、assembleDebug 均成功，安装 Success。日志：deliverables/gate-c/build.log。
- 当前初级学园 1 年级横向列表到达末端，共 7 项。逐项选择后目视读取完整消耗、学分、奖励，证据 course-1-details.png 至 course-7-details.png。

| 课程 | 分钟 | 金币 | 体力 | 清洁 | 学分 | 奖励 |
|---|---:|---:|---:|---:|---:|---|
| 蹦蹦跳跳体能课 | 10 | 15 | 5 | 2 | 10 | 力量+2 |
| 看图识世界课 | 10 | 15 | 5 | 2 | 10 | 智力+2 |
| 闪亮自我表达课 | 10 | 15 | 5 | 2 | 10 | 魅力+2 |
| 田径运动课 | 30 | 45 | 15 | 6 | 25 | 力量+5 |
| 世界地理课 | 30 | 45 | 15 | 6 | 25 | 智力+5 |
| 演说表达课 | 30 | 45 | 15 | 6 | 25 | 魅力+5 |
| 萌芽夏令营 | 30 | 45 | 15 | 6 | 25 | 随机属性+5 |

当前资源始终为金币600、体力72、清洁68，阶段学分0/30，力量/智力/魅力均1；课程选择没有扣费。其他学园仍锁定，未推测其课程。10分钟课程按时间和资源计算的收益更高：30分钟可获得30学分、属性+6，而一节30分钟课为25学分、属性+5（仅依据页面数值，不含未验证的结束流程）。

本轮只新增截图与报告，没有新增代码变更。下一步需用户选择固定10分钟课程，并明确允许启动1节，才能采集学习中/结束状态；不会自动续课。

### 获授权的单节实测

用户随后明确允许启动1节“蹦蹦跳跳体能课”。2026-10-05 14:43:58左右，在displayId=4核对该课选中、10分钟、消耗15/5/2后，仅点击一次“去上课”。learning-start.png显示正在学习、剩余00:09:59。没有启动MVP Session，没有召回或使用道具，没有续课。

用户补充职业规则：不同属性解锁不同职业；任意一项属性严格大于另外两项之和的两倍时，有几率解锁隐藏职业勋章。该规则来自用户说明，未在游戏内验证概率/触发机制。以力量为例条件为力量 > 2 × (智力 + 魅力)；初始1/1/1，本节预期3/1/1，尚不满足条件。此信息仅记录，不扩展当前MVP选课架构。

学习中截图的底部面板从首帧标题y≈1148自动移动到y≈963，不能把首帧坐标作为固定学习ROI。capture_course.py只点击物理屏上的调试保存帧按钮，未向QQ发送任何输入。

2026-10-05 14:54:53保存的observed-08.png确认课程自动结束，显示独立“宠物成绩单”：蹦蹦跳跳体能课、成绩A、学分+10、力量+2。未点击“分享”或“继续学习”，本次授权的一节已完成，没有续课。开始时间约14:43:58；observed-07.png在14:53:57显示剩余4秒，随后自动切换成绩单。

实测流程阻塞：结束页不是户外地图，而是成绩单，有“继续学习”按钮。现有状态机在finished后只允许school/course/start三次点击；目前没有验证从成绩单返回学校/课程的路径，因此不能将旧地图坐标填入schoolButton，也不能宣布Gate C或自动续课通过。学习面板高度另有自动变化，ROI必须排除倒计时、剧情及动画。继续保留未校准默认配置、Session拒绝启动，不增加盲点或重设计。

本阶段changed files：新增deliverables/gate-c的授权前、学习中、结束截图以及只读capture_course.py，更新本报告；没有修改APK源代码。test/assembleDebug/安装沿用本节之前已成功的当前APK，未因文档与截图重复构建。Gate A/B已通过，Gate C单节状态采集成功，自动流程校准仍未完成。
