# Phase 01 · 01-01 测试环境与可追踪会话

> 取证时间：2026-10-11　**只读核验，未触碰设备自动化**

## 固定测试环境（每轮数据的回溯基准）

| 维度 | 值 | 核验方式 |
|---|---|---|
| 设备 | `DKS9K23420020242` / FOA-AL00 / HWFOA-Q | `adb devices -l` |
| Android | SDK 31 | `getprop ro.build.version.sdk` |
| 物理屏 | 1084 × 2412 @ 480 dpi | `wm size` / `wm density` |
| 虚拟屏 | display 4, 720 × 1600 @ 320 dpi | `dumpsys display` |
| QQ | `com.tencent.mobileqq` 9.3.70 (16410) | `dumpsys package` |
| 本 App | `com.ynk.virtualdisplay` 1.0.51 (51) | `dumpsys package` |
| **源码** | `97876c2bb5af15bc70d134ca99b3eb66d6675a19` | `git rev-parse HEAD` |
| **APK** | `qqpet-mvp-debug.apk` 27,729,763 B | `deliverables/` |
| **APK SHA256** | `841fe9f1864f07ec2d8eab931e0bb86af9e0220a2890272799ebdc9aa5c18254` | `Get-FileHash` |
| **设备已装 SHA256** | `841fe9f1864f07ec2d8eab931e0bb86af9e0220a2890272799ebdc9aa5c18254` | `sha256sum $(pm path …)` |

**源码 ↔ APK ↔ 设备三方哈希一致**，每轮数据可回溯到唯一设备与唯一构建。

## 会话目录结构（可追溯性）
```
/storage/emulated/0/Android/data/com.ynk.virtualdisplay/files/pet_mvp_session/<epochMs>/
  ├── runtime-state.json     # 实时状态
  ├── round-N.json           # 每节明细
  ├── run-record.json        # 终态汇总（含 sessionId）
  └── *.png                  # 逐步骤证据帧
```
每次运行产生独立目录，目录名即起始时间戳；`run-record.json` 内含 UUID `sessionId`。

## 现有 session 清单（全量、含失败）

| 目录 | 时长 | 终态 | 完成 | stopReason |
|---|---|---|---|---|
| 1791268356594 | 1.3 min | FAILED | 0/1 | `HIGH_FIVE_BURST_TIMEOUT` |
| 1791269448236 | 1.3 min | FAILED | 0/0 | `PAGE_MISMATCH:RECOVER_IF_NEEDED:clean-after:0.954` |
| 1791274920759 | 0.6 min | FAILED | 0/0 | `PAGE_MISMATCH:SELECT_COURSE:enter-primary-academy-after:0.635` |
| 1791436294033 | 0.4 min | FAILED | 0/0 | `PAGE_MISMATCH:RECOVER_IF_NEEDED:5-enter-food-after:0.907` |
| 1791629304052 | 12.0 min | FAILED | 1/1 | `CLEAN_NO_GAIN_OR_EMPTY` |
| 1791630656648 | 12.0 min | FAILED | 1/1 | `PAGE_MISMATCH:RECOVER_IF_NEEDED:feed-after:0.779` |
| 1791631902297 | 33.2 min | COMPLETED | **3/3** | — |
| 1791643364521 | 12.1 min | COMPLETED | 1/1 | — |

### 关键发现（必须计入 01-05 统计）
1. **历史成功率 4/8 = 50%**（含早期实验）。近 4 次为 2 成功 / 2 失败 = 50%。
2. **`PAGE_MISMATCH` 是主要失败模式**（4 次），阈值 0.6–0.95，属页面识别不确定。
3. **`CLEAN_NO_GAIN_OR_EMPTY`**（1 次）表示清洁动作后清洁度未上升 —— 安全停止，判定正确。
4. **3 节批次曾成功一次**（33.2 min），证明多节串联可用。
5. 失败会话的 `feed=0 clean=0` —— 失败发生在护理阶段，**未进入课程**，无未授权副作用。

## 批次机制（决定 10 轮可行性的硬约束）

| 项 | 事实 | 证据 |
|---|---|---|
| 单会话轮数上限 | **1–3 节** | `PetSessionController.kt:43` `require(totalRounds in 1..3)` |
| 跨批次循环 | **不存在** | 无循环调度代码 |
| 单节耗时 | **≈12 分钟**（课程约 10 分钟） | 4 次完整运行实测 |
| 3 节批次 | **≈33 分钟** | session 1791631902297 |
| 每日硬上限 | 480 分钟 | `DailyBudgetStore.HARD_STOP_MINUTES` |

**结论：10 轮 ≥ 4 个独立批次；按 3 节/批 = 约 33 min/批，总计 ≥ 2 小时真实运行。**

## 环境先决条件（每轮前需满足）
1. Shizuku 服务存活且已授权
2. 虚拟屏 display 4 存在（720×1600@320dpi）
3. QQ 已登录且在可识别页面
4. 宠物健康数据新鲜（≤60 s）且 energy/cleanliness ≥ 90
5. 累计运行时长未超 480 min
6. 屏幕保持点亮（应用持有 wakelock）

## 已知局限（如实声明）
- **单设备、单 QQ 版本**：结论不可外推到其他机型
- 失败样本占比高（50%），10 轮结果将直接影响 Phase 01 门禁判定
- 无法在不破坏真实运行的前提下人为注入 `Stale` / 异常页面 → R-05 仍不可控验证
