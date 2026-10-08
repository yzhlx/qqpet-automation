# t3 三节实机验收 — 前置条件阻塞报告

- 任务：t3 `Build, install, and run three-course device acceptance`（academy-verifier-ds41）
- attempt_id：`e2ad9d96-b537-441f-996f-710b82cb000f`
- 记录时间：2026-10-07（Asia/Shanghai）
- 结论：**前置条件未满足，未启动验收批次。拒绝伪造通过。**

## 1. 硬性前置（t3 合同）

> Positively verify QQ pet ROOM on display 4 before starting; if precondition cannot be restored
> through established safe app routes, tell the captain rather than manually ADB-tapping, blind
> Back, or touching forbidden areas.

以及仓库文档 `EXPERIMENTAL-3-ROUND-REPORT.md` L103：

> 必须先在现有虚拟屏应用中把 QQ 宠物恢复到 display 4 宠物首页，并确认预览关闭，才能继续屏幕校验。

## 2. 启动时现场事实（只读）

| 项目 | 实测值 |
|---|---|
| 虚拟屏 | display 4 = `Shizuku_VD_1791180519368`，720x1600，density 320，type VIRTUAL，state ON，owner `com.android.shell (uid 2000)`，`FLAG_PRESENTATION`，managed 显示 |
| display 4 初始内容 | **全黑**（`precondition-display4.png`，7,904 B） |
| display 4 初始 resumed | `com.huawei.android.launcher/.unihome.UniHomeLauncher` |
| QQ 进程（初始） | 仅 `com.tencent.mobileqq:MSF`，无主进程 |
| 物理屏 display 0 | 1084x2412，density 480，`mWakefulness=Awake`，keyguard `showing=false`（未锁定） |
| 虚拟屏 App 预览 | 未运行（`dumpsys activity` 无 `com.ynk.virtualdisplay/.ui`） |

即：宠物**未**打开，因此本次操作没有破坏任何正在运行的宠物状态。

## 3. 已执行的恢复动作（仅一种，且为既有安全 App 路由）

使用与 `ShizukuDisplayRepository.launchApp()` 完全相同的机制（一次 launch，**不是**点击）：

```
adb -s DKS9K23420020242 shell "am start --display 4 -f 0x18000000 -n com.tencent.mobileqq/.activity.SplashActivity"
```

返回 `Starting: Intent { flg=0x18000000 cmp=com.tencent.mobileqq/.activity.SplashActivity }`；
新 `Task{80393ad #2230 visible=true sz=1}`，`mCallingPackage=com.android.shell`；
QQ 进程起来：`com.tencent.mobileqq`(23648)、`:MSF`(17884)、`:tool`(24295)。

## 4. 恢复结果：前置**仍未**满足

display 4 显示的是 QQ **消息列表**（"拉马努金" / 消息·频道·联系人·动态），不是宠物 ROOM。

- `precondition-display4-after-launch.png` 219,107 B sha256 `1AE08223F1F805F020E0C55D71BC9821B0ABB9956D3FC597A80E5FFA9CE13EF9`
- `precondition-display4-after-wait.png`   219,126 B sha256 `DC2112CCB0822A645CFE7ABEEDA1330D9EC17BCAD2DBA2C63FC29A48DBB98F6E`
- `precondition-display4-now.png`          219,107 B sha256 `53E817040CC1E5FCF2CA5C43F54B44E5859234C2B33FB6AB9C373D4756AAA1D8`

display 4 resumed：`ActivityRecord{52977d7 com.tencent.mobileqq/.activity.SplashActivity t2230}`。

## 5. 为什么没有"既有安全 App 路由"可用

1. **`PetNavigation.initialise()` 无法从游戏外进入**（`PetNavigation.kt` L1334-1365）。
   它只分类**游戏内**页面：cookie -> bath -> result -> LEARNING -> room -> course_list -> academy -> outdoor，
   否则 `else -> error("INITIAL_PAGE_UNKNOWN")`（L1363）。QQ 消息列表没有分支能进入宠物。
   因此 `PetRecoveryGateTest` L89-107 的 `PetNavigation(navFlow).initialise()` 只在宠物页已经打开时才可用。
2. **`ShizukuDisplayRepository.launchApp/launchHome` 只能重启包**，App 内没有宠物模块的 launch 路由；
   `PetMvpScreen.kt`（145 行，已全文读过）**没有任何进入游戏的控件**。
3. **QQ 无宠物专用组件**：`dumpsys package com.tencent.mobileqq` 的 532 个 exported activity/alias 中
   没有宠物模块（只有 `minibox` 小程序宿主与无关 widget）；34 个 URI scheme 全部落到
   `com.tencent.mobileqq/.activity.JumpActivity`，没有宠物入口的公开 deeplink。
4. `local.petstudy` 是 2026-10-05 就已安装的**无关**第三方包（v0.1.0，仅 `.MainActivity`），
   不是 QQ 宠物路由。

## 6. 唯一剩下的既有安全 App 路由（需人工操作）

虚拟屏 App **自身**的 preview 触摸转发：

`VirtualDisplayScreen.kt` L243-244 -> `Intent(context, DisplayActivity::class.java).putExtra("display_id", displayInfo.id)`
-> `DisplayActivity.handleTouchEvent`（L397-438）-> `injectTouchEvent`（L491-504）
-> `repository.injectInputWithDisplayId(event, displayId)`。

即：在虚拟屏 App 的 display 4 预览窗口上手动点击 QQ，全部输入都落在 display 4，**物理屏触摸增量保持 0**。

历史文档确认这条路由一直是人工完成的：
`调试过程与技术留痕报告.md` L81 与 `SESSION-PROGRESS.md` L26 记录的 `adb input -d 4 tap 200 420`
是**人工 ADB 点击**，并明确标注"不作为 App 内 HeadlessDisplayCapture 验收"。

注意：`HeadlessDisplayCapture.kt` L25 要求 headless 采集期间**不要**打开 preview，
所以进入 ROOM 后须先关闭预览（与 `EXPERIMENTAL-3-ROUND-REPORT.md` L103 一致）。

## 6.5 新增可操作发现：display 4 上宠物入口**可见**

像素分析 `precondition-display4-now.png`（720x1600）确认 QQ 消息列表上存在两处宠物入口视觉要素：

1. **悬浮猫头像（宠物入口强候选）**：逐行像素扫描得紧致边界
   **x 446-522、y 1386-1497**（约 77x112 px），中心 **`(484, 1441)`**
   （占屏 x=67.2%、y=90.1%），位于底部 tab 栏（y≈1520 起）**上方**。
   放大示意：`evidence/t3-display4-floating-pet-entry.png`
   （280x280，由 `(420,1370)-(560,1510)` 最近邻放大，18,048 B，sha256 `1a3526ae…`）。
   该猫的毛色/五官与 ROOM 参考图 `deliverables/gate-c/pet-entry.png` 中的宠物**外观一致**，
   故判断为宠物悬浮入口；因未实际点击，**不作为已证实结论**，仅列为强候选。
   （注：先前一次粗略阈值扫描给出 `(439,1386)-(650,1534)`，那是把 tab 栏图标并入了同一包围盒，
   已用逐行扫描修正，勿引用旧值。）
   `dumpsys window windows` 显示 display 4 上 QQ 只有
   `Window{b6d9e67 com.tencent.mobileqq/activity.SplashActivity}` 一个窗口，
   说明该悬浮物是 **QQ 自身 UI**，不是第三方覆盖层。
2. **会话行「宠物名画之旅」**：消息列表**第 3 条**（发送者显示名「1」，头像为仓鼠），
   位置约 y≈770（列表顺序：QQ游戏中心 / 腾讯新闻 / 1-宠物名画之旅 / 我的关联QQ账号）；
   裁图 `evidence/t3-display4-pet-chat-row.png`（32,041 B，sha256 `965AF523…`）。

这两处说明历史文档 `当前完成内容报告.md` L69「已找到宠物入口」在 display 4 上**依然呈现**，
入口只是需要一次点击；点击必须由**用户**在 App 预览中完成（见下）。

## 7. 需要的单次人工动作

请用户通过上述安全路由，把 QQ 宠物打开到 **display 4 的宠物首页/ROOM**，然后关闭预览。
ROOM 一旦确认在位，立即执行**唯一一批** `authorizedThreeRoundServiceAcceptance`
（`managed_authorized=yes`、`rounds=3`），并在安全的 `WAIT_RESULT` 时刻通知舰长，
以便用户在 120 秒内完成物理锁屏/解锁。

## 7.5 已预先备妥的执行命令（ROOM 在位后可立即执行，未执行）

工作目录 `VirtualDisplay`，`$adb = ..\tools\android-build\sdk\platform-tools\adb.exe`，
设备 `DKS9K23420020242`。**唯一批次**：

```powershell
# 0) 前置复核：ROOM 是否在位 + display 4 仍在（只读）
& $adb -s DKS9K23420020242 shell "dumpsys display" | Select-String "Shizuku_VD|mDisplayId=4"
& $adb -s DKS9K23420020242 shell "dumpsys activity activities" | Select-String "Display #4" -Context 0,8

# 1) 单批 3 节验收（managed_authorized=yes, rounds=3）
& $adb -s DKS9K23420020242 shell "am instrument -w -r \
  -e class com.ynk.virtualdisplay.PetManagedSessionTest#authorizedThreeRoundServiceAcceptance \
  -e managed_authorized yes -e rounds 3 \
  com.ynk.virtualdisplay.test/androidx.test.runner.AndroidJUnitRunner"
# 输出包含 MANAGED_ACCEPTANCE <dir>；该目录含 status.json / verdict.json / events

# 2) 取证回取（ASCII 临时路径，避免中文路径失败）
& $adb -s DKS9K23420020242 shell "ls /sdcard/Android/data/com.ynk.virtualdisplay/files/pet_managed_acceptance/"
& $adb -s DKS9K23420020242 pull <dir> "$env:TEMP\managed_t3"
```

时间预算：主测试 `withTimeout(40*60*1000L)`（40 分钟）内完成 3 节，
`WAIT_RESULT` 期间须由舰长通知用户，用户在 120 秒内物理锁屏/解锁以产生真实 PAUSED/RESUMING。

判定要点（全部来自 `PetManagedSessionTest` 现有断言）：
`3 == state.completedRounds`、`COMPLETED`、`states` 含 `PAUSED`/`RESUMING`/`RUNNING`、
`state.pauseCount >= 1`、`COURSE_STARTED` 恰好 3 次且 rounds={1,2,3}、
全部 `INPUT_SENT` 的 `source=="APP_AUTOMATION"` 且 `payload.displayId==4`、
`taps(325.0,375.0)==30`、`taps(530.0,1488.0)==3`、暂停期间无 `INPUT_SENT`、
物理触摸增量 == 0（否则 `PHYSICAL_TOUCH_DETECTED`）。

## 8. 合规声明

- 未执行任何 ADB 点击、盲退 Back、模拟 POWER、物理屏输入、游戏内输入。
- 未改源码、未构建、未安装、未跑测试、未启动新批次。
- 未将任何被动测试结果或旧记录冒充为本次实机成功。
- 旧的失败会话（`3fb96212-...` / `PAGE_MISMATCH:SELECT_COURSE:enter-primary-academy-after:0.6345633623819898`）
  仍是最后一次真实实机结果，**不得**改标为通过。
