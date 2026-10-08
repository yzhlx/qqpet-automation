# t3 前置恢复 —— 准备方案（合同修订前不执行）

状态：**已备妥、未执行**。等待舰长 `amend_task` 确认修订（含机制选择与所需文件 scope）。
任务：t3 / attempt 1 / attemptId `e2ad9d96-b537-441f-996f-710b82cb000f`。
授权来源：舰长 2026-10-07 转达——用户对总目标内所有低风险操作授权，高风险须先报备；
不再要求用户亲自点进 ROOM。**但合同 t3 的 description 仍禁止 manual ADB taps，
聊天授权不能越过合同**，故先修订、后执行。

---

## 0. 合同事实（修订针对哪里）

t3 的 `objective` / `acceptance`(5) 见 `t3-precondition-blocker.md` 与发舰长的咨询消息。
**关键纠正：t3 没有 `inScope` / `outOfScope` / `verify` 字段**——核对了 team.json 全部 7 个任务的键
集合，只有 t1/t4/t6 有 inScope+outOfScope+verify，t5/t7 有 inScope，**t2/t3 三者全无**。
待修订的限制实际住在 **`description`** 的原句：

> "Positively verify QQ pet ROOM on display 4 before starting; if precondition cannot be restored
> through established safe app routes, tell the captain rather than manually ADB-tapping, blind
> Back, or touching forbidden areas."

修订必须**保留**的同在 description 的安全条款：
- "No deployments, new study campaigns, extra real-course batches, manual supplementary game taps,
  or unsupported PASS claims."
- "never inject physical POWER or simulate human pause"
- "If it fails, report immediately and do not launch another batch."

## 1. 机制选择（决定是否需要重建测试包）

| 选项 | 输入源 | 证据强度 | 代价 |
|---|---|---|---|
| A. App 内 `PetInputController.tap` | `APP_AUTOMATION`，`displayId=4` | 最强，与 criterion 4 同源，可计数 | 需新增/改动 androidTest 文件 → **须重建+重装 test APK**（app APK 无需重建） |
| B. `adb shell input -d 4 tap x y` | `com.android.shell` | 落 display 4、物理 delta 仍 0 | 字面即"ADB tapping"，description 明令禁止 |

首选 A。已核验 A 的可用原语（全部为**既有**生产代码，不需改生产逻辑）：
- `PetInputController(repository, displayId=4, 720, 1600, facts)`；`init` 断言
  `displayId > 0 && facts.displayId == displayId`（L19），`tap()` 内部走
  `repository.injectInputWithDisplayId(event, displayId)`（L39/L48）→ 输入必落 display 4。
- `facts.input("TAP", mapOf("x" to x, "y" to y, "pressMs" to pressMs))`（L53）提供可计数的独立记账。
- 帧采集：`HeadlessDisplayCapture(repository, 4, 720, 1600)`（与 `PetRecoveryGateTest` L70 同构）；
  **注意** `HeadlessDisplayCapture.kt` L25：采集期间不得开着 DisplayActivity 预览。
- 判分：`TemplateMatcher(app).match(frame, roomProbe, 0.97).matched`（与 `PetRecoveryGateTest` L112/L154 同构）。
- ROOM probe：`PetProfile.Probe("HOME", Rect(320,1340,405,1465), "pet_experimental/room.png")`
  （`PetPageProbes.room` L24 同定义）。**模板确在已装 debug APK 内**：
  `assets/pet_experimental/room.png` 8,689 B（zip 条目已核）。
- 仓库获取与释放：`MyApplication.acquireDisplayRepository(owner)` L19 /
  `releaseDisplayRepository(owner)` L26（引用计数，计数 0 时 unbind）。
  **撤回我此前的一处错误归因**：我曾称该 gate 缺配对 acquire；实际
  `PetRecoveryGateTest:83` 有 `withContext(Dispatchers.Main) {app.acquireDisplayRepository("PetRecoveryGate")}`，
  与 L184 的 release **正确配对**。无 lease 缺陷，文件不需改动（舰长已纠正）。

## 2. 前置步骤（严格按舰长给的边界）

1. **新鲜当前帧 + 运行时确认**：display 4 存在、720x1600、density 320、managed；
   对**新帧**跑 ROOM probe —— **若已 ROOM（>=0.97）则整个前置跳过**。
2. 若非 ROOM：在**同一张新帧**上确认宠物入口**唯一**。操作化定义：
   暖色/毛发色簇与背景 L1 差异超阈值，且**恰好 1 个**候选连通簇；
   **0 个或 ≥2 个即停并报**（不猜）。
3. **不使用旧的 (484,1441)**：上一轮该值是粗阈值把 tab 栏图标并入包围盒的产物，已作废；
   中心一律取**本轮新帧**簇质心。
4. **至多一次**受控点击（机制见 §1 选项 A/B）。
5. 点击后**立即取新帧**核 `match(frame, room, 0.97).matched`：
   - `>=0.97` → 前置达成，进入批次；
   - `<0.97` 或非 ROOM → **停并报**，不二次点击、不盲退 Back、不自动重开。
6. **独立记账**（不混入批次完成证据）：单独目录保存 前图/后图、
   `displayId/x/y/时间戳`、点击计数。批次是新 sessionId，前置点击不会进入批次 events。

## 3. 批次（前置达成后，同一 attempt）

唯一一批：`PetManagedSessionTest#authorizedThreeRoundServiceAcceptance`，
`managed_authorized=yes`、`rounds=3`，主超时 40 分钟。
到安全 `WAIT_RESULT` 时通知舰长，让用户在 120 秒内**物理**锁屏/解锁（不注入 POWER、不模拟）。
判定要点（既有断言）：`completedRounds==3`、`COMPLETED`、states 含 `PAUSED/RESUMING/RUNNING`、
`pauseCount>=1`、`COURSE_STARTED` 恰 3 次 rounds={1,2,3}、全部 `INPUT_SENT` 为
`APP_AUTOMATION` 且 `displayId==4`、`taps(325,375)==30`、`taps(530,1488)==3`、
暂停期无 `INPUT_SENT`、物理 delta==0。
**失败不得自动重开批次**；失败即报。

## 4. 当前已核验的现场与制品（本轮只读复核）

- display 4：存活，720x1600、density 320、VIRTUAL、managed、layerStack 4；QQ 主进程显示**消息列表**。
- 物理 display 0：已解锁，处于桌面。
- app APK 与 `deliverables/qqpet-mvp-debug.apk` **逐字节一致**
  （sha256 `ED1E1168…FB662`，26,882,333 B）；test 包 `lastUpdateTime 02:14:26`。
  → criterion 1 的"已装且已核"部分已成立；**只有选选项 A 时才需重装 test APK**。
- 已装 debug APK assets 含 `pet_experimental/room.png`（8,689 B），故 ROOM 校验无需新增资源。

## 5. 合规声明

本文件为**准备**，未执行：无点击、无 ADB tap、无盲退 Back、无模拟 POWER、无物理屏输入、
无游戏输入、无源码改动、无构建、无安装、无测试运行、无批次启动。
旧失败会话（`PAGE_MISMATCH:SELECT_COURSE:enter-primary-academy-after:0.6345633623819898`）
仍为最后一次真实实机结果，未改标为通过。
