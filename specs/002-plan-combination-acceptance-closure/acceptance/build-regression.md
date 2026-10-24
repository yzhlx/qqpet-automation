# 构建与回归记录（spec 002）

## T023 D1 候选构建与安装（2026-10-25 设备包管理器时间）

- 候选 APK：`VirtualDisplay/.build-tmp/qqpet-resource-rules-20261024-122554-020110.apk`，SHA-256 `e34225da47419e42358ac4c4e640cd29260038ce0a01bfe0e58aa8fb3c60f698`；签名证书指纹 `a63a2254…cf77`。
- `adb install -r` 成功，安装包 `com.ynk.virtualdisplay` 为 versionName `1.0.60` / versionCode `60`。`dataDir` 与安装前相同，`firstInstallTime=2026-10-15 14:43:31` 保持不变，`lastUpdateTime=2026-10-25 00:27:24`。
- 构建后的 `VirtualDisplay/.build-tmp/restore_aidl.py` 输出 `aidl.exe restored to original`。
- 安装后、观察前 bundle：`VirtualDisplay/.build-tmp/device-acceptance/continuation-20261024-122952-056650-work-writeback-before/`。其中权威账本 SHA-256 `6DF83DC2768F9FF5B2D915FA85857D2212F79F1E7C1AEE456970CCE0ECC61EFA` 与安装前一致；目标 `36:d5bf86e8-9c57-4369-b311-f758c94023ca4:work:1` 仍为 10 分钟 `UNCONFIRMED_RETAINED`，尚无写回审计。bundle 的 9 个 `INPUT_SENT` 属原 WORK session 的历史事件，不是观察任务输入。

---


## T013 D1 修复后验证（2026-10-24，工作站 America/New_York）

- 编译：从 ASCII junction 执行 `python -I VirtualDisplay/.build-tmp/compile_latest.py`，输出 `EXIT 0` 且 `aidl.exe restored to original`；日志 `VirtualDisplay/.build-tmp/compile-latest.log`。
- 目标回归：`WorkCompletionWritebackTest` + `SessionEventPayloadTest`，classpath 191、2 个测试类、`OK (19 tests)`、`EXIT 0`；日志 `VirtualDisplay/.build-tmp/junit-writeback-corroboration.log`。
- 全量首轮：classpath 191、70 个测试类、756 项中 1 项失败：`CommittedPlanFinalizationTest.revokedObservationCannotPrepareOrCommitACompletion` 在 fixture 初始化时遭遇 Windows `AccessDeniedException`，原子移动 `budget.bin.pending -> budget.bin` 失败。目标类隔离回归为 `OK (10 tests)`、`EXIT 0`，日志 `VirtualDisplay/.build-tmp/junit-committed-finalization-isolated.log`。
- 全量重跑：classpath 191、70 个测试类、`OK (756 tests)`、`EXIT 0`；日志 `VirtualDisplay/.build-tmp/junit-work-writeback-full-retry.log`。未为首轮文件移动异常改生产逻辑。

T013 当前源码验证通过。JVM 结果只证明规则回归，不替代设备同 run 验收。

## 较早的 T013 验证（749 项，2026-10-24）

本轮当前源码包含 D1 `WorkCompletionWritebackPolicy/Runner`、`PlanBudgetLedger.completedWithAudit` allowlist 与 `PetAutomationService` 的零输入观察分流。`compile_latest.py` 两次均 `EXIT 0`，并报告 `aidl.exe restored to original`；最新日志：`VirtualDisplay/.build-tmp/compile-latest.log`。

目标回归：`python tools/jvm-tests/run_tests.py WorkCompletionWritebackTest --log .build-tmp/junit-writeback.log` → `OK (12 tests)`、`EXIT 0`。

### 全量 JVM 回归

首跑 `VirtualDisplay/.build-tmp/junit-current.log`：classpath 191、69 个测试类，749 项中 1 项失败。失败在 `CommittedPlanFinalizationTest.skipCannotHideASelectedConsumedStepOrUnprovenWork` 的 fixture 初始化：Windows `AccessDeniedException`，`Files.move(budget.bin.pending, budget.bin, ATOMIC_MOVE, REPLACE_EXISTING)`。失败路径下 pending 与目标文件均存在；单类隔离运行 20 次均未复现；未因此修改生产代码或测试。

随后全量重跑 `VirtualDisplay/.build-tmp/junit-current-retry.log`：

```
classpath 191 test classes 69 filters ['all']
JUnit version 4.13.2
OK (749 tests)
EXIT 0
```

因此 T013 当前结果为通过（749 项）。第一次拒绝的具体锁定者未能确认，记录为一次未复现的 Windows 文件移动异常；第二次全量回归及 20 次目标类回归均绿。

### T014

`specs/001-managed-study-recovery/acceptance/build-guide.md` §2 已改用权威 runner 的 `classpath <n> test classes <m> filters [...]` 格式，并标明 `.build-tmp/run_tests.py` 是旧副本；修改基于本轮实际输出。

### 较早记录中的设备安装状态

该记录编写时曾计划构建、验签并以 `adb install -r` 安装候选包；D1 修复后的当前候选尚待构建。此处保留历史 749 项回归，最新编译和全量结果见上方 T013 D1 修复后验证。

---

## T013 编译 + 全量 JVM 回归（2026-10-23 13:35–13:50 工作站 / 设备 2026-10-24 01:3x–01:5x +0800）

本轮**未改动任何生产源码**（改动仅：`tools/diagnostics/` 4 个只读工具 + 新增测试 `PlanRunnerCompositionTest.kt`），故**未重新打包/签名/安装**；设备上的当前安装包仍为 healthJson 修复包 `45f0298e…b1ec`（versionCode 60，lastUpdate 设备时间 2026-10-23 10:01:36）。

### 编译

```
python -I C:\Users\lu158\dsh-qqpet\VirtualDisplay\.build-tmp\compile_latest.py
```
实际输出（两轮，修测试前后各一次）：
```
aidl wrapper installed
running gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin :app:bundleDebugClassesToCompileJar …
aidl.exe restored to original
EXIT 0 log C:\Users\lu158\dsh-qqpet\VirtualDisplay\.build-tmp\compile-latest.log
```
✅ EXIT 0；✅ `aidl.exe restored to original`（构建结束 aidl 已还原，见 T057 复核）。

### JVM 全量回归（权威 runner）

```
python -I C:\Users\lu158\dsh-qqpet\VirtualDisplay\tools\jvm-tests\run_tests.py --log .build-tmp/junit-current.log
```
```
classpath 191 test classes 68 filters ['all']
JUnit version 4.13.2
OK (737 tests)
EXIT 0
```
- 基线 729 → **737**（+8：`PlanRunnerCompositionTest` 8 项）。
- 日志：`VirtualDisplay/.build-tmp/junit-current.log`；scoped 单类日志：`junit-composition.log`（`OK (8 tests)`、`EXIT 0`）。
- ✅ `OK (… tests)` + 非零测试数 + `EXIT 0` 三者同时成立（不以 shell 成功替代）。

### 新测试暴露的两处**我的**错误断言（已修，非生产缺陷）

首次全量回归 737 项中 2 项失败，均为 `PlanRunnerCompositionTest` 自身断言与既有语义不符，生产代码行为正确：

1. `onlyCompletedOrSkippedStepsLetTheRunComplete`：`counts.careCompleted` 期望 1 实得 0。**根因**：计数只来自 **persisted COMPLETED attempts**（`RunStore.countsOf`），而 stub 只返回 `Completed()` 未记录 attempt。修法：stub 补 `beforeConsumingInput(1,0)+completed(1,0)`。
2. `aCompletedPrefixSurvivesAnUnconfirmedLaterStep`：care attempt 状态期望 `UNCONFIRMED` 实得 `INPUT_PENDING`。**根因**：executor **返回** `Unconfirmed` 时 `PlanRunner` 不会自动落账该 attempt，pending intent 原样保留——这正是「不重放」的信号（只有 executor 抛异常时才由 `persistUnconfirmed` 收敛）；生产链路由 `PlanBudgetExecutionContext.retainPending` 在 finally 补这一层。修法：断言改为 `INPUT_PENDING` + `inputPending=true` + `hasPendingInput=true`，并在注释写明两种路径的分工。

### T014 文档修正（已完成）

`specs/001-managed-study-recovery/acceptance/build-guide.md`：
- §2 校验正则由旧格式 `^classpath [0-9]+ tests [1-9][0-9]* missing \[\]` 更正为 `^classpath [0-9]+ test classes [1-9][0-9]* filters`（已实测匹配权威 runner 输出）；
- 「已读配方」小节新增更正条目：权威 runner 现打印 `test classes`/`filters`，且 `.build-tmp/run_tests.py` 为旧副本（无 glob/无 `--log`/不传播退出码），不得用于判定。

## 设备侧构建状态

| 项 | 值 |
| --- | --- |
| 当前安装 APK | healthJson 修复包，SHA-256 `45f0298ef943e593c53e52f46effd098b047a887019f4a73088f0ca5b3bdb1ec`，versionCode 60 / 1.0.60，证书 `a63a2254…cf77` |
| 本轮是否重装 | **否**（生产源码零改动） |
| aidl.exe | 已还原原件（compile_latest.py finally 打印确认） |
| 临时 keystore 副本 | 本轮未签名，无副本产生 |
