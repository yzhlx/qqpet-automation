# t3 前置恢复（PRECONDITION_RESTORE）执行记录 —— 结果：**未达成，已按合同停止**

- 任务：t3 / attempt 1 / attemptId `e2ad9d96-b537-441f-996f-710b82cb000f`
- 合同：t3 revision 1（舰长 `amend_task`，`at=1791348795142`），新第 6 项验收授权
  **正式批次前 0 或 1 次** display 4 shell 入口点击，独立标记 `PRECONDITION_RESTORE`。
- 结论：**前置未达成（NOT ROOM）**。已用掉该允许的**唯一一次**点击；未二次点击、未盲退 Back、
  未起任何批次、未构建/安装/改源码。按第 6 项"failed ROOM confirmation stop the task's
  action sequence and are reported without an automatic retry batch"停止。
- 动作序列**终止于此**：`PetManagedSessionTest#authorizedThreeRoundServiceAcceptance`
  **未启动**（`pet_managed_acceptance` 最新目录仍为旧的 `1791274920283`，无新目录）。

---

## 1. 运行时显示身份与指标（新鲜读取，非默认值）

`adb -s DKS9K23420020242 shell dumpsys display`：

| 项 | 实测值 |
|---|---|
| displayId | **4** |
| uniqueId | `virtual:com.android.shell,2000,Shizuku_VD_1791180519368,0` |
| 尺寸 | **720 x 1600**（`logicalFrame`/`physicalFrame`/`real`/`app` 均一致） |
| densityDpi | **320**（`320.0 x 320.0 dpi`） |
| orientation / rotation | 0 / 0 |
| type | VIRTUAL，`FLAG_PRESENTATION`，state ON |
| owner | `com.android.shell (uid 2000)`，layerStack 4，managed，removeMode 1 |

→ **布局与既有 ROOM 校准所支持的布局一致**（720x1600@320，rotation 0），未调分辨率、未套默认值。

QQ 归属：`Display #4` 的 `RootTask #2230` 仅含
`mResumedActivity: ActivityRecord{52977d7 u0 com.tencent.mobileqq/.activity.SplashActivity t2230}`，
`A=10230:com.tencent.mobileqq`，`visible=true`，
`mCallingPackage=com.android.shell`（我自己上一次的 launch）。
物理屏 0：`mResumedActivity: com.huawei.android.launcher/.unihome.UniHomeLauncher t7`。

安全状态（解锁）：`mIsShowing=false`、`showing=false`、`deviceHasKeyguard=true`，
`mKeyguardOccluded=false` → **未锁屏**，可执行。

预览状态：`dumpsys activity activities` 中**无 DisplayActivity**
→ 符合 `HeadlessDisplayCapture.kt` L25（采集期间不得开预览）。

## 2. 点击前新鲜帧

- 文件：`before-20261007-125532.png`（216,791 B，sha256 `192F2AD021C8B154CF29884FB913B5E1…`）
- 采集：`adb shell screencap -d 4 -p /sdcard/d4-pre.png` → `adb pull` → `rm`
  （用设备端文件而非 PowerShell 二进制重定向，避免 PNG 损坏）
- 内容：QQ **消息列表**（拉马努金 / 在线-4G / 搜索框 / 第 1 条「1·宠物名画之旅」仓鼠头像 /
  腾讯新闻 / QQ游戏中心 / 我的关联QQ账号 / 底部 消息·频道·联系人·动态 tab）。
  → **不是 ROOM**。

## 3. 入口唯一性的语义识别（结合界面与控件形态，非仅颜色）

对**该新鲜帧**做全帧暖色/毛发连通域扫描（`scan-all.py`，4-邻接，L1>100 且 r>g≥b 且 r−b>25）：

| 连通域 | bbox | 尺寸 | 中心 | 所处区域 |
|---|---|---|---|---|
| **#1（唯一悬浮候选）** | **(446,1383)-(522,1493)** | **77x111** | **(484.0,1438.0)** | **列表下方、tab 栏（y≥1494）之上** |
| #2 | (52,419)-(109,519) | 58x101 | (80.5,469) | 列表区（仓鼠头像） |
| #3..#9 | 列表区各处 | — | — | 列表区（头像/图标） |
| #10 | (99,1497)-(134,1513) | 36x17 | (116.5,1505) | **tab 栏内**（已排除） |

**语义指认**（不只靠颜色）：恰好 **1 个**连通域位于"列表之下、tab 栏之上"的悬浮区，
且**控件形态与外观**与该页其它暖色元素（列表头像/缩略图）完全不同类别——它是一枚
**独立悬浮的猫形象控件**；其毛色/粉耳/粉鼻/黄瞳的**外观与已知 ROOM 参考**
`deliverables/gate-c/pet-entry.png` 中的宠物**高度一致**（同款奶白猫）。
→ 当时指认为 QQ 宠物入口候选控件，中心由本帧 bbox 求得 = **(484.0, 1438.0)**。

> **事后更正（§9.2）**：该指认**经实测推翻**——单击后仅出现撸猫互动动画、无页面跳转。
> 故此控件是**宠物互动装饰**，不是 ROOM 导航入口。
> 边界：该实测**不能**推断它在任何其它手势下也永不导航，故 rev2 不再点它。

**未复用任何历史坐标**：`(544,1460)` 与 `(484,1441)` 均**未使用**；坐标由本帧 bbox 独立得出。
（纠正留档：`(544,1460)` 是把 tab 栏图标并入包围盒的**粗测**；`(484,1441)` 是随后的**紧致修正**。
两者都是历史值，本次一律不用；本帧实测中心 y=1438，与旧紧致值差 3 px 属同一次测量族。）

## 4. 唯一的受控点击（PRECONDITION_RESTORE）

| 项 | 值 |
|---|---|
| 命令 | `adb -s DKS9K23420020242 shell input -d 4 tap 484 1438` |
| 源 | `com.android.shell` —— **不是** `APP_AUTOMATION` |
| display | `-d 4` 显式 |
| x / y | **484 / 1438** |
| 计数 | **1**（本任务全部动作中的唯一一次） |
| 本地时刻 | 2026-10-07 12:58:18.825 |
| 纪元毫秒 | 1791349098827 |
| 退出码 / 输出 | **0** / 空 |
| 标签 | `PRECONDITION_RESTORE` |

归属与命令存证：`restore-command.txt`、`restore-attribution.txt`。

## 5. 点击后帧（first-observed，非 first-rendered）

- `after-1-20261007-1258.png`（216,763 B，sha256 `C91BEB91DCD55416B09ABF7E10680CD6…`）
  —— **裁剪设备端上报时刻 12:58:40**，**本步骤首次实际观测到的**点击后帧
  （local `first_observed_at` 12:58:41.058）。**不主张**它是点击后"第一张被渲染的"帧
  （first-rendered 不可证），故按合同标注为 **first-observed**。
- `after-2-settled.png`（216,380 B，sha256 `80FE6C798867D1C4FCCF37EAB99D2C8C…`）
  —— 有限被动沉降后（12:59:24）复采，**期间未再点击**。

**差分证据**（`diff.py`，相对点击前帧）：

| 对比 | 差异像素 | 差异 bbox |
|---|---|---|
| before → after-1 | 3,957（0.343%） | **(445,1383)-(522,1493)** |
| before → after-2 | 6,888（0.598%） | **(428,1381)-(522,1493)** |

→ 变化**完全局限在猫的包围盒内**（与其 bbox `(446,1383)-(522,1493)` 基本重合），
证明点击**准确命中**了指认的控件；**页面其余部分零变化**，即**未发生页面跳转**。

**点击后的行为含义**（`entry-tap-before-vs-after.png` 左右对照）：猫由"站立正视"变为
"耳朵后压、眯眼、抬爪并出现心形"——这是**抚摸/互动(撸猫)动画反馈**，
**不是页面导航**。即该悬浮猫是**互动装饰控件**，而非 ROOM 入口。

## 6. 权威 ROOM 确认（既有只读 gate，reset_home=no）

命令（**务必 `reset_home=no`**，否则会执行导航输入）：

```
adb -s DKS9K23420020242 shell am instrument -w -r \
  -e class com.ynk.virtualdisplay.PetRecoveryGateTest#faultGate \
  -e recovery_gate capture -e display_id 4 -e reset_home no \
  com.ynk.virtualdisplay.test/androidx.test.runner.AndroidJUnitRunner
```

结果（存证 `gate-capture-output.txt`，7,102 B）：

```
java.lang.IllegalStateException: INITIAL_PAGE_UNKNOWN:0.8209808460421205
  at com.ynk.virtualdisplay.PetRecoveryGateTest$faultGate$1.invokeSuspend(PetRecoveryGateTest.kt:113)
Tests run: 1,  Failures: 1     Time: 2.437
```

- 该分数即**已安装的公共 `TemplateMatcher`** 对 ROOM probe
  （`PetProfile.Probe("HOME", Rect(320,1340,405,1465), "pet_experimental/room.png")`）
  在本帧上的实测分，来自 L112-113 的 `check(score.matched)`。
- **0.8209808460421205 < 0.97 → ROOM 未确认（NOT ROOM）**。
- **该 0.8209808460421205 是 KNOWN（已知权威失败值）**——它已明确打印在设备原始输出
  （`gate-capture-output.txt` 的 `INITIAL_PAGE_UNKNOWN:` 行与 stack trace）中，可复核、可引用。
  **"不存在 passing score"不等于"这个实测失败值 UNKNOWN"**，二者不可混同（舰长更正，已采纳）。
- 真正应记 **UNKNOWN** 的只有**绑定关系**：该 gate **未保存所测的那一帧**，
  因此"这个 0.8209808460421205 精确对应哪一张 after PNG"**无法证明** → 帧-分数绑定记 UNKNOWN。
  （按时刻推断它测的是 12:58–13:00 间的 display 4 实时帧，但无落盘帧可比对，故不作断言。）
- **无游戏导航输入**：输出中 `navigationInputs` / `INPUT_SENT` / `setupNavigation` / `PAGE_MISMATCH`
  **全部为空**；`reset_home=no` 使 L95 分支被跳过（未构造 navCapture、未调 `PetNavigation.initialise()`），
  与该 gate 的"read-only observation, no game input"定位一致。
- 该辅助 gate 目录：`pet_recovery_gate/1791349244795`（**独立**于正式批次目录）。
  其（若发生的）合成 `PAUSED/RESUMING` **绝不**用作真实人锁屏证据。

## 7. 未启动正式批次 / 未动其他东西

- `pet_managed_acceptance` 最新目录仍为 `1791274920283`（**无新目录**）→ 3 节批次**未启动**。
- 无二次点击、无盲退 Back、无物理屏输入、无模拟 POWER、无游戏输入。
- 无源码改动、无 `build-mvp.ps1` 调用、无构建、无 APK 重装。
  已装 app APK 与 `deliverables/qqpet-mvp-debug.apk` 保持逐字节一致（`ED1E1168…FB662`，26,882,333 B）；
  测试包保持 `lastUpdateTime 02:14:26`。
- 旧结果未删改；本目录为其**独立新增**证据。

## 8. 结论与如实报告

**t3 第 6 项验收未通过（前置未达成）**，且**不是**因为坐标不准或点击未生效——
点击经差分证明**精确命中**指认控件、QQ 也**产生了明确交互响应**；
而是因为**在 that 帧、that 单击下**该悬浮猫未导航（仅触发撸猫动画，见 §9.2 的结论边界），
且**权威 ROOM 分 0.8209808460421205 < 0.97**（该失败值 KNOWN，见 §6）。

按合同"missing/ambiguous targets … or failed ROOM confirmation stop the task's action
sequence and are reported without an automatic retry batch"，我**就此停止**：
不再点击、不复用历史坐标、不盲退 Back、不自动重开批次。

**可供下一步判断的事实**（不构成结论，且**已按舰长更正修订**）：
点击前帧（12:55:32）中第一条会话行是「**1 · 宠物名画之旅**」（仓鼠头像），
**实数位置在广告横幅之下、搜索框下方，y≈405–530**（详见 §9 的实测行距）。
**我先前的"y≈640–780"是错的**——该带实际是「腾讯新闻」(y≈575–665) 与
「QQ游戏中心」(y≈700–810) 的区域，**不能沿用作宠物入口**（舰长更正，已采纳）。
合同只授权**一次**入口点击，已用尽；是否改由该会话行进入、再在会话内查看
**实际的宠物入口卡片/按钮**，**须舰长另行决定/授权**。

## 9. rev2 前必须纳入的三项事实更正（实测复核）

舰长提出三点更正，我逐项在本地对已存帧做了只读复核，**三点全部成立**：

### 9.1 首条会话行的真实位置（我先前的定位错误）

对 `before-20261007-125532.png` 加 y 标尺（`list-ruler-250-1000.png`，
每 50 px 一条线）与头像圆盘扫描（`rowgeom.py`，左栏 x=20..130 的强非背景像素行游程）：

| 行 | 实测 y 范围 | 中心 | 内容 |
|---|---|---|---|
| 头像圆盘 | 423–517（高 95） | 470.0 | **「1 · 宠物名画之旅」仓鼠头像** |
| — | 613–624 / 646–648 | ~618/647 | 腾讯新闻头像与文字带 |
| 头像圆盘 | 707–763 + 772–810 | 735 / 791 | **QQ游戏中心** |

→ 首条会话**整体约 y=405–530**（含 1 与"下午12:15"文字带），
与舰长所述"广告横幅下第一条会话"一致；**我原报告的 y≈640–780 属错误归行**。
历史参考 `deliverables/gate-c/qq-home.png` 同法测得首行头像在 y=380–428、
腾讯新闻 y≈491–559、QQ游戏中心 y=618–721 —— **两图除首行内容不同外，行距族一致**，
说明该列表几何稳定，但**首行 y 需按当次实际帧重新定位，不得沿用任何历史值**。

（保留自我纠错记录：`ref-qqhome-ruler-250-1000.png` 为该历史图的同标尺对照图。）

### 9.2 不再点猫；且不得由一次单击推断"猫没有其它入口手势"

- 12:58:18 命中猫后**只播放撸猫动画、无页面跳转**（§5 差分已证变化局限在猫 bbox 内）。
- **结论边界**：这只证明"在 that 帧、that 单击"下猫不导航；
  **不能**推断猫在任何其它手势（长按/双击/拖动）下永远不是入口。
  故 rev2 **不再点猫**，也**不把"猫非入口"写成普遍结论**——仅记录该单击的实测结果。

### 9.3 新鲜度：capture→input 间隔 166.825 s（必须在 rev2 修正）

- 点击前帧设备上报时刻 **12:55:32**，实际发出输入 **12:58:18.825**。
- **capture_to_input_interval = 166.825 s（≈2 min 46.8 s）**。
- 即"新采的图"**在发出输入时已不新鲜**：坐标虽由该帧 bbox 求得，但帧已过期约 167 s。
- **rev2 强制要求**：点击前**再取（或再确认）一张新帧并重新定位目标**，
  记录并报告实际 capture→input 间隔，**不得**继续使用数分钟前的坐标。
  （合同 rev2 会写入此约束；本次仅如实记录既有间隔。）
