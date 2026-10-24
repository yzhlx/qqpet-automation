# Quickstart: 组合验收收口的可运行验证指南

**Feature**: `specs/002-plan-combination-acceptance-closure` | **Date**: 2026-10-23
**判定口径**：[contracts/acceptance-evidence.md](contracts/acceptance-evidence.md) · **运行契约**：[contracts/device-run.md](contracts/device-run.md) · **模型约束**：[data-model.md](data-model.md)

本指南只给**可执行步骤与期望结果**，不含实现代码。所有命令在工作区根 `C:\Users\lu158\Desktop\dsh插件\养宠自动化` 下执行；构建/测试类命令必须在 ASCII 联接 `C:\Users\lu158\dsh-qqpet` 语境下（脚本内已写死）。

---

## 0. 前置检查（每个验收日开工前都要做）

### 0.1 设备与 adb

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" devices
```
**期望**：列出 `DKS9K23420020242   device`。
- `offline` ⇒ 通常仍在启动，等待后重试；持续 offline 则记阻塞。
- 列表为空 ⇒ USB 链路掉线（历史发生过），记阻塞，不要猜应用问题。

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" -s DKS9K23420020242 shell date "+%Y-%m-%dT%H:%M:%S%z"
```
**期望**：Asia/Shanghai 时间。**所有业务时间以设备实读为准，不由 host 文件名推断。**

### 0.2 Shizuku（13.6.0）

先看应用首页/设置页的「特权服务」状态。若未连接：用**应用 UI「通过连接电脑启动 → 查看指令」实际显示**的命令（形如下例，路径随安装目录变化，**不要沿用旧路径**）：

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" -s DKS9K23420020242 shell /data/app/~~<随机>/<pkg>-<随机>/lib/arm64/libshizuku.so
```
**期望**：`starter exit with 0` + server PID。随后在应用里点「重试检测」，首页恢复、控制台显示「特权服务：已连接」。

> APK 覆盖安装或进程死亡后 Shizuku 可能失效，需重跑 starter。**PC 重启 ≠ 手机重启**，不要把 Shizuku 未运行归因为手机重启。

### 0.3 虚拟屏编号（**永不假设 4/5/6**）

在应用控制台/设置里读当前自有虚拟屏编号，并核对校准：720×1600、320dpi。随后所有游戏侧截图/点击/监控都显式带该编号：

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" -s DKS9K23420020242 exec-out screencap -d <N> -p > .build-tmp\vd<N>-now.png
```
**期望**：得到 PNG（宠物房间或可识别游戏页）。黑屏 ⇒ 按既有做法 force-stop QQ 后经搜索入口把宠物拉回房间（只导航，不消费）。

`verifyDisplay` 会硬校验：机型 `FOA-AL00`、QQ `9.3.70` / longVersionCode `16410`、dpi 320、尺寸 720×1600、display 属当前 Shizuku 管理。**QQ 升级或校准漂移会导致全链拒绝**，此时记环境阻塞，不放宽校验。

### 0.4 只读复核上一轮结论

```powershell
python -I tools\diagnostics\capture_acceptance_state.py --run-id b5600c4f-f334-4013-9db8-f95d5fc4cb0a --display-id <N> --label wc-recheck
```
**期望**：`persisted-plan-run.json` 中 `snapshot.state == "COMPLETED"`、`stopReason == null`、`counts == {workShiftsCompleted:1, careCompleted:1, studyRoundsCompleted:0}`。
（该 COMPLETED 是 `CommittedPlanFinalizer` 的**收尾**结果，记录时必须标注 finalized，不等于原生同 run COMPLETED。）

### 0.5 Step 0 —— 清算遗留 CARE pending（**阻塞其余全部步骤**）

只读确认当前阻塞源：

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" -s DKS9K23420020242 exec-out run-as com.ynk.virtualdisplay cat files/plan-runs 2>$null
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" -s DKS9K23420020242 exec-out run-as com.ynk.virtualdisplay ls files/plan-runs
```

然后从 UI 发起一次方案运行以触发准入：`PendingGate` 会因 7 条 `UNCONFIRMED_RETAINED` 返回 `PENDING_RUN_UNRESOLVED:<attemptId>`，服务转入 `LEGACY_ZERO_MINUTE_CARE_RECONCILIATION` 观察任务（20s 级、只读 + 原子写账本，**不发消费输入**）。

**期望（成功）**：日志出现 `LEGACY_CARE_RECONCILED_STOPPED count=7 evidence=<路径>`；复核账本后 7 条 status 均为 `RECONCILED_STOPPED`，分钟未变、旧 checkpoint 未改、无输入重放。
**期望（拒绝）**：`LEGACY_CARE_RECONCILIATION_REFUSED:<原因>` 或 `LEGACY_CARE_OBSERVATION_NOT_ADMITTED`。此时**逐条列出缺哪项证据**（checkpoint / terminal record（mode=care、courseStarts=0）/ 2 条 `actionPending=true` 的付款结算收据 / 房间基线双帧钱包相等且窗口 ≤5s、≤3s）并记阻塞。

> **不得**通过削弱 `unresolved`、清账本、改 bin、改日键来「让新 run 能跑」。

**放行条件**：`CarePendingReconciliation.unresolved` 无命中 **且** `PlanRunPendingGate.blockingRunId` 返回 null。两者都清空才进入第 1 步。

---

## 1. 取证工具最小扩展（Step 1，只读）

改 `tools/diagnostics/capture_acceptance_state.py`：在可选文件名单中增加
`plan-round-1-completion.json … plan-round-<rounds>-completion.json`（rounds 从事件流/方案推得）与 `files/plan-v2.json`；沿用既有 `save_json` 的 JSON 校验（远端 cat 错误记入 `missingOptionalFiles`，不产出伪 JSON）。`watch_acceptance.py` 终态后补打印一行 verdict 摘要（不改判定、不发输入）。

**验证**：对既有 run 重跑一次 capture（如 §0.4），确认原 bundle 结构未破坏、新增项在含 STUDY 的 run 上能取到、在无 STUDY 的 run 上落入 `missingOptionalFiles`。

---

## 2. 编排回归补测（Step 2，S2 接缝）

新增 `VirtualDisplay/app/src/test/java/com/ynk/virtualdisplay/PlanRunnerCompositionTest.kt`，用既有 fakes 覆盖七个非空子集：

- 执行顺序恒为 WORK→CARE→STUDY，**未选类型的 executor 零调用**；
- 前项 Blocked/Failed/Unconfirmed/Cancelled ⇒ 后项保持 `PENDING`（对应 `RUN_EXECUTED_AFTER_STOPPED_STEP` 不变式）；
- 只有全部步骤 COMPLETED/SKIPPED 才整体 COMPLETED；
- 合法跳过仅 `FUNDS_SUFFICIENT`（WORK）与 `ALREADY_SATISFIED`（CARE），STUDY 无合法跳过；
- counts 来自 persisted attempts，与 step 状态解耦。

**编译当前源码与单测**（不打包 APK；脚本自带 aidl wrapper 安装与 `finally` 还原）：

```powershell
python -I C:\Users\lu158\dsh-qqpet\VirtualDisplay\.build-tmp\compile_latest.py
```
内部执行 `:app:compileDebugKotlin :app:compileDebugUnitTestKotlin :app:bundleDebugClassesToCompileJar`，日志 `.build-tmp/compile-latest.log`，失败时打印 `e:` / `error:` / `FAILURE` 尾部并传播退出码。**期望** `EXIT 0` 且日志出现 `aidl.exe restored to original`。

**生成/刷新 classpath**（`cp.lst` 不是 `run_tests.py` 生成的；`capture_cp.py` 只把 worker 的 classpath 文件复制到 `.build-tmp/captured-classpath.txt` 供检查，**也不生成 cp.lst**）。按 `specs/001-managed-study-recovery/acceptance/build-guide.md` §1 的既有流程，在 `VirtualDisplay` 下：

1. 安装 aidl wrapper → `gradlew :app:assembleDebug :app:compileDebugUnitTestKotlin :app:bundleDebugClassesToCompileJar`；
2. `gradlew -I .build-tmp/dumpcp.init.gradle :app:testDebugUnitTest --rerun-tasks …` —— init 脚本在 `doFirst` 写 `cp.lst` 后**故意抛 `classpath dumped`**，因此**这一次 Gradle 调用的非零退出是预期的**；必须核对日志含 `classpath dumped` 且 `cp.lst` 的 LastWriteTime 晚于本次开始时间。除此之外的任何构建失败仍是失败；
3. `finally` 还原 aidl；
4. 用 build-guide 的 `cpFix` 片段把 main jar 强制换成 `compile_app_classes_jar/.../classes.jar`、剔除 `runtime_app_classes_jar` 与不存在条目，并断言含单测类目录、`junit-4.13.2.jar`、mockable `android.jar`。

cp.lst 必须含新编译的单测类目录（`compileDebugUnitTestKotlin\classes`）、`compile_app_classes_jar/classes.jar`（**不是** runtime jar，否则会静默测到旧类——曾因此出现 6 个假回归失败）、junit 与 mockable android.jar。

**跑测试**（权威入口；cwd 由脚本内部固定为 `VirtualDisplay/app`）：

```powershell
python -I C:\Users\lu158\dsh-qqpet\VirtualDisplay\tools\jvm-tests\run_tests.py --log C:\Users\lu158\dsh-qqpet\VirtualDisplay\.build-tmp\junit-current.log
```
只跑相关类（改动期快速反馈；空匹配会以 `No compiled test classes matched` 失败）：
```powershell
python -I C:\Users\lu158\dsh-qqpet\VirtualDisplay\tools\jvm-tests\run_tests.py '*PlanRunnerCompositionTest' --log C:\Users\lu158\dsh-qqpet\VirtualDisplay\.build-tmp\junit-composition.log
```
**期望输出**（当前权威 runner 的真实格式）：
```
classpath <n> test classes <m> filters ['all']
JUnit version 4.13.2
...
OK (<≈729+新增> tests)
EXIT 0
```
缺 junit/android.jar 会以 `Required test classpath entries missing: …` 直接失败。

> **文档不一致提示**：`specs/001-managed-study-recovery/acceptance/build-guide.md` §2 的断言正则仍是**旧** runner 的输出格式（`^classpath [0-9]+ tests [1-9][0-9]* missing \[\]`），而权威 `tools/jvm-tests/run_tests.py` 现在打印 `classpath <n> test classes <m> filters [...]`。直接复用该正则会误判失败；本轮以实际输出为准，并在 Step 8 一并修正 build-guide 的这处陈旧断言。

**注意**：`run_tests.py` 以 `raise SystemExit(r.returncode)` 传播真实退出码，但**外层 shell 成功仍不等于测试通过**——必须同时看到 `OK (… tests)`、非零测试数与 `EXIT 0`。`gradle :app:testDebugUnitTest` 在中文路径下不可用（worker 读不回自己的 classpath @argfile，全部 `ClassNotFoundException`）。`.build-tmp/run_tests.py` 是旧副本（无过滤、无 `--log`、不传播退出码、输出旧格式），**不要用它判定**。

> JVM 测试受 stub `android.jar` 塑形：`Rect` 的方法（`height()`/`hashCode()`/`equals()`）未实现、构造器忽略入参（字段可读写），`org.json` 同样被打桩。因此测试里直接写 `Rect` 字段、用 `bottom - top` 代替 `height()`、不把 `TextLine` 放进 `HashSet`；JSON 往返无 JVM 覆盖，只能在设备上验（这也是 S3 用真实文件而非 org.json 的原因）。

---

## 3. 构建 / 签名 / 覆盖安装（仅在有生产改动时）

```powershell
python -I C:\Users\lu158\dsh-qqpet\VirtualDisplay\.build-tmp\build_sign_install_rules.py
```
脚本内已固化：`JAVA_HOME=tools/android-build/jdk21/jdk-21.0.12.1+1`、`GRADLE_USER_HOME=tools/android-build/gradle-cache`、`ANDROID_HOME=tools/android-build/sdk`、`JAVA_TOOL_OPTIONS`/`TEMP`/`TMP` → `.build-tmp`、`REAL_AIDL=…/aidl.exe.orig`、`kotlin.compiler.execution.strategy=in-process`；keystore 取 `Desktop/dsh插件/.foreman/qqpet-stabilize-20261015/signing/qqpet-debug.keystore` 的**短命 ASCII 临时副本**，`finally` 删除；期望证书 `a63a2254…cf77`；`adb install -r` 保留数据。

**构建后必须还原 aidl**：
```powershell
python -I C:\Users\lu158\dsh-qqpet\VirtualDisplay\.build-tmp\restore_aidl.py
```
**期望**：`aidl.exe restored to original`（原件 3512416 字节）。

**记录构建身份**：APK 文件名（含时间戳，唯一）、SHA-256、证书指纹、versionCode。`versionCode = commitCount`，不提交则不涨号，同号可重复 `-r` 覆盖安装，用构建时间戳区分。

> **学习 run 进行中不安装新包。** 含中文 Kotlin 源**禁用 PowerShell `-replace`/`Set-Content`**（曾损坏 `PetSessionController` 20 处、`PetAutomationService` 13 处），只用 read/edit/write 或显式 UTF-8 Python；改后必编译。

安装后：重跑 Shizuku starter（§0.2）→ 应用「重试检测」→ 重新确认 display 编号（§0.3，可能变号）→ 若 VD 黑屏按既有做法把宠物拉回房间。

---

## 4. 组合验收（Step 3–7，单设备**串行**）

每个组合的通用流程：

1. **改方案**：应用 → 底部「设置」（Compose 文本常为**零尺寸**，点可点击父容器，1084×2412 上约 `[747,2116][921,2186]`，或用已识别父节点）→ 滚动到「托管方案」→「运行方案」。
2. **勾选动作**并按需填参数（岗位 / 课程 + 节数 / 护理阈值 / 每日上限 / 「仅资金不足时打工」开关仅在勾了学习时出现）。
3. **保存并运行**（要求已选虚拟屏且当前无运行中任务）。UI 先落盘 `plan-v2.json` 再发意图；`request_id` 是新 UUID。
4. **监控**（有界只读，默认 45 分钟，终态后自动 capture）：
   ```powershell
   python -I tools\diagnostics\watch_acceptance.py --run-id <新runId> --display-id <N> --label <combo> --minutes 45
   ```
   首行打印 `MONITOR_RUN_ID <runId>`；随后逐条打印新事件与 30s 心跳（含 `events`/`inputs`/`lastState`）；出现 `FRAME_INVALID` 会自动落 `monitor-<runId>/` 诊断。
   **期望**：终态后自动调用 capture 并打印 bundle 摘要 JSON；超时则 `MONITOR_TIMEOUT`（监控自身从不发输入）。
5. **判读**：按 [acceptance-evidence.md §1](contracts/acceptance-evidence.md) 核对三源一致性清单，**不要**用 `SESSION_STATE=STOPPED` 判成败（成功也发 STOPPED）。
6. **记录**：写 `specs/001-managed-study-recovery/acceptance/results.md` 矩阵行 + 明细文件，字段齐备（§4 必备 12 项）。

> 若 `--run-id` 未知，可省略让脚本取 `files/pet-events` 最新一条；但**判定前必须把 runId 写进记录**。

### 4.1 WS（打工→学习）— 优先

- 勾选：WORK（岗位=已验证的「竹影武馆 / 帮武馆跑腿」，trigger=ALWAYS）+ STUDY（课程=已扫描且时长可确认的课，节数=1）。**不勾 CARE**。
- 经济前置：金币约 15 < 学费约 66 ⇒ **必须在同 run 内靠这一班工资筹资**；不得外部补币、不得借款。
- **期望**：`work-reward-payroll-award` 正 award → 工资页左上返回（`PAYROLL_RETURNED_*`，绝不点「继续打工」）→ 回房 → 选课 → 一次开课 → `plan-round-1-completion.json` → counts W1/C0/S1、`planRunState=COMPLETED`、verdict `PLAN_PASS`；`care.feed_count==0 && clean_count==0`；分钟增量 == 工作分钟 + 课程分钟；grade 记 `UNKNOWN`（US4 未接线）。
- **label 建议**：`plan-work-study`。

### 4.2 CS（护理→学习）

- 勾选：CARE（阈值按需，如 100/100）+ STUDY（1 节）。**不勾 WORK**。
- **期望**：counts W0/C1/S1；护理达标（`plan-care-ready` 双值 ≥ 阈值）或 `ALREADY_SATISFIED` 零消费；若购买，逐笔钱包读数 + `tuitionReserve` 可见，且购买后仍保留全部未付学费；工作类 `INPUT_SENT == 0`。
- **label 建议**：`plan-care-study`。

### 4.3 WCS（三步）

- 勾选全部三项。
- **期望**：counts W1/C1/S1；`snapshot.steps` 顺序 WORK→CARE→STUDY 且依次 COMPLETED/SKIPPED；三源一致。**含 STUDY 的 run 不能靠 finalizer 收尾**（`FINALIZATION_STUDY_OR_LEGACY_FORBIDDEN`），必须原生跑完。
- **label 建议**：`plan-work-care-study`。

### 4.4 FUND-*（按需打工）

| 场景 | 配置 | 期望 |
| --- | --- | --- |
| FUND-LOW | WORK(IF_FUNDS_SHORT) + STUDY，且余额 < 学费 | 同 run 真实触发一班 → 正 award → 后续完成；`planSkipWork` 判定与实际 dispatch 一致 |
| FUND-SKIP | WORK(IF_FUNDS_SHORT) + STUDY，且余额 ≥ 学费 | WORK step `SKIPPED` + reason `FUNDS_SUFFICIENT`；`plan-work-skipped` 事件含 `tuitionReserve`；工作 `INPUT_SENT==0`、分钟不增 |
| FUND-CARE-GAP | WORK(IF_FUNDS_SHORT) + CARE + STUDY，学费够但护理需购买且现金不足 | 记录实际门禁（`NeedsObservation("CARE_SUFFIX_EXACT_INVENTORY_AND_COST_REQUIRED")` 或购买前 `CARE_WALLET_UNCONFIRMED` 等）与当时真实读数；**保守拒绝是正确行为**，正向闭环另记未定 |

> 制造 FUND-SKIP 需要余额 ≥ 学费；**只能**用同 run 内打工或用户既有授权范围内的方式达成，不额外领币、不充值。若无法在不违反 G3 的前提下构造，记「未定 + 实际前置阻塞」。

### 4.5 SAVE-7（七配置持久化）

对七个非空子集逐个：保存 → 杀进程/重开应用 → 只读回读比对。

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" -s DKS9K23420020242 exec-out run-as com.ynk.virtualdisplay cat files/plan-v2.json
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" -s DKS9K23420020242 exec-out run-as com.ynk.virtualdisplay ls files/plan-runs
```
**期望**：动作集合、岗位、课程、节数、阈值、每日上限、trigger、name 逐项一致；`plan-runs` **无新文件**、无 `INPUT_SENT`（重开不自动启动消费）。
S3 隔离测试（临时目录，**不动生产 `plan-v2.json`**）另覆盖：损坏/未知 schemaVersion 保留原文并拒绝执行、原子写、运行快照不随编辑变化。
记录写入 `specs/001-managed-study-recovery/acceptance/us2-persistence.md`。

### 4.6 LEGACY / LEGACY-DIFF

按 [research.md R2](research.md) 的**已作出裁决 (a)** 记录**实际**语义（UI 保存恒为 `SELECTED_ACTIONS`；`LEGACY_MANAGED` 在 `toSessionPlan` 抛 `LEGACY_PROFILE_REQUIRES_LEGACY_ADAPTER`；兼容提示文案）。该行判未定、**不得判通过**，不得声称 001 FR-014 迁移保真已达成。

---

## 5. 常见拒绝码速查

| 码 | 含义 | 处置 |
| --- | --- | --- |
| `PENDING_RUN_UNRESOLVED:<id>` | 有未解决 pending（遗留 CARE 优先，其次 v2 非终态 run） | 走 §0.5 清算；不削弱门禁 |
| `PENDING_GATE_FAILED:<类型>` | 门禁读取抛异常（fail closed） | 查账本/plan-runs 可读性，不绕过 |
| `RUN_ALREADY_ACTIVE:<id>` / `RUN_ID_ALREADY_USED:<id>` | 设备互斥 / runId 不回收 | 等终态清理；用新 requestId |
| `RUN_REQUEST_CHANGED` | 同 requestId 但方案或 display 变了 | 用新 requestId 重发 |
| `RUN_STORE_UNREADABLE:…` | plan-runs 不可读 | 记阻塞，查权限/文件 |
| `PLAN_JSON_REQUIRED` | 想用 mode 走方案路径 | 必须带 `plan_json` |
| `LEGACY_PROFILE_REQUIRES_LEGACY_ADAPTER` | 方案是 LEGACY_MANAGED | 见 R2；UI 保存会转成 SELECTED_ACTIONS |
| `PLAN_WORK_OPTION_NOT_VERIFIED:<id>` | 岗位不在 VERIFIED 白名单 | 选已验证岗位 |
| `COURSE_CATALOG_RESCAN_REQUIRED` | 课表缺失/过期/不完整 | 先在方案页「读取我的课程」 |
| `COURSE_COINS_INSUFFICIENT` | 含学习无工作且余额 < 后缀学费 | 正确停止；记实际余额与学费 |
| `DAILY_BUDGET_LIMIT` | 工作+学习分钟超当日上限 | 正确停止；记账本前后分钟 |
| `CARE_SUFFIX_EXACT_INVENTORY_AND_COST_REQUIRED` | 无精确库存 ⇒ 条件工作不可判 | **保守拒绝，非缺陷** |
| `CARE_INVENTORY_UNCONFIRMED` / `CARE_WALLET_UNCONFIRMED` | 库存/钱包 Unknown | 正确停止；Unknown 不填 0 |
| `CARE_COMPLETION_UNCONFIRMED` | 护理后复核未达阈值 | 记实际读数 |
| `WORK_COMPLETION_DISMISS_UNCONFIRMED` | 工资页返回未确认 | 查 `PayrollNavigationPolicy` 证据，不点「继续打工」 |
| `HEALTH_STALE` | 健康读数过期 | 已修 `healthJson` 序列化；若再现按缺陷处理 |
| `FINALIZATION_STUDY_OR_LEGACY_FORBIDDEN` | 试图对含 STUDY 方案收尾 | 含学习组合必须原生跑完 |
| `FINALIZATION_OTHER_RUN_PENDING` / `…_BASELINE_UNCONFIRMED` / `…_WALLET_CHANGED` | 收尾前置不成立 | 清算其它 pending / 重取房间基线 |
| `ATTEMPT_ALREADY_EXISTS_NO_REPLAY` / `BUDGET_ATTEMPT_ALREADY_RESERVED_NO_REPLAY` | 既有 attempt 不再授权 | 正确行为；不重放输入 |

---

## 6. 收尾（Step 8）

1. 修正 `specs/001-managed-study-recovery/acceptance/results.md`：**WC 行**改为 finalized COMPLETED（附审计 bundle `continuation-20261022-211328-845483-plan-work-care-recovered`，保留 legacy run-record FAILED/HEALTH_STALE 与 persisted COMPLETED 的对照）；新增 WS/CS/WCS/FUND-\*/SAVE-7 行；按 R2 如实写 LEGACY；US4 行保持未接线并注明前置条件；同步 SC 总表。
1b. 修正 `specs/001-managed-study-recovery/acceptance/build-guide.md` §2 的断言正则（`^classpath [0-9]+ tests [1-9][0-9]* missing \[\]` → 当前 runner 的 `^classpath [0-9]+ test classes [1-9][0-9]* filters`），否则按该 guide 校验会恒判「JUnit not proven passing」。改前先用实际 runner 输出核对格式。
2. 更新工作区 `progress.md` 与 `memory.md`：本段目标/验收标准/已完成/当前状态/下一步/阻塞项，附构建身份与 bundle 路径。
3. 共享任务板 task-7 / task-8 / task-12 / task-16：先 `team_task_get` 取最新 revision，再对照代码、测试与设备证据决定是否 `complete`；**状态不得超前于事实**。
4. 未取得的证据一律记「未定 + 实际阻塞」，不用历史 PASS 或跨 run 拼接顶替。
