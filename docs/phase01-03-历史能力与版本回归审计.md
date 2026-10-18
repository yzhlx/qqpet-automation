# 历史能力与版本回归审计报告

> 审计时间：2026-10-11　性质：**只读**（未改代码、未部署、未购买、未修改业务逻辑）
> HEAD：`97876c2bb5af15bc70d134ca99b3eb66d6675a19`，工作区 0 处已跟踪文件修改

---

## 结论摘要（先回答你的核心问题）

**不是功能退化，也不是版本调用错误。是「过去从未覆盖库存耗尽场景」。**

旧版本能跑通护理，是因为**当时账号里还有食物**。当食物耗尽后，游戏把「喂食」按钮的行为从「直接喂」变成了「弹出兑换框」，而引擎从未实现购买能力 → 停在 `PAGE_MISMATCH`。

**项目历史上从未实现过自动购买。** 这一条我用穷尽式检索确认（见 §3）。

| 你的疑问 | 答案 | 证据 |
|---|---|---|
| 功能退化？ | **否** | `PetCareFlow` 喂食逻辑逐行对比一致（§4） |
| 版本调用错误？ | **否** | 3/3 成功的代码路径在当前 HEAD 中仍存在且被调用（§4） |
| 过去没覆盖库存耗尽？ | **是** | 历史 0 行购买代码（§3）；成功帧食物充足，失败帧弹兑换框（§2） |

---

## 1. 3/3 轮真实成功的 session 与版本

### 1.1 成功记录（设备磁盘原始文件）
路径：`/storage/emulated/0/Android/data/com.ynk.virtualdisplay/files/pet_mvp_session/1791631902297/`

`run-record.json` 关键字段：
| 字段 | 值 |
|---|---|
| `sessionId` | `3a8f7a23-71f5-4a8e-9f51-c817c0288534` |
| `terminalState` | **`COMPLETED`** |
| `rounds` / `completedRounds` | 3 / **3** |
| `courseStarts` | **3** |
| `encouragementSent` | **30**（每轮 10 次击掌） |
| `care` | `feed_count=1`, `clean_count=0` |
| `runtimeMs` | 1993381（33.2 分钟） |
| 结束属性 | 体力 91 / 清洁 92 / 心情 20 |
| `stopReason` | `null` |

`verdict.json`：
```json
{"verdict":"MANAGED_BATCH_PASS","completed":3,"requested":3,"stopReason":null,
 "captureStatistics":{"imageReaderSuccess":0,"imageReaderTimeout":986,
 "shizukuFallbackCount":986,"shizukuFallbackSuccess":986,
 "captureLatencyP50Ms":662,"captureLatencyP95Ms":779}}
```

### 1.2 时间线与版本归属（关键）
| 事件 | 时间 |
|---|---|
| **3/3 成功运行** | 2026-10-10 **19:31:42 → 20:04:55** |
| 提交 `97876c2`（当前 HEAD） | 2026-10-10 **23:39:03** |
| 设备 APK `lastUpdateTime` | 2026-10-10 **23:12:02** |

**成功运行发生在当前 HEAD 提交之前 4 小时。** 因此 3/3 由**更早的源码**产生。

- 成功时可用的最近提交：`b6ebbd7`（2026-10-08 15:44）
- 当前版本：`97876c2`（2026-10-10 23:39），`PetCareFlow.kt` 相对 `b6ebbd7` **+253 / −35 行**

### 1.3 该 session 中各环节的实现
`run-record.json` 中 `"runner":"PetSessionController"`，与当前 HEAD 同一类。
各环节**均可在当前源码中找到对应实现**：

| 环节 | 实现位置（当前 HEAD） | 该 session 的证据文件 |
|---|---|---|
| 护理（喂食） | `PetCareFlow.kt:225-248` | `round-3-48/49-enter-food-*`, `round-3-feed-before/after/health.png` |
| 上课 | `PetNavigation` + `PetSessionController` | `round-N-10/11-select-fixed-course-*`, `round-N-course-start.png` |
| 击掌 | 鼓励输入 | `round-N-high-five-1..10-after.png`（每轮 10 张，共 30） |
| 成绩结算 | `result` 系列 | `round-N-result.png`, `round-N-12/13-result-close-course-*` |
| 批次裁决 | `PetAutomationService.kt:92-94` | `verdict.json` |

**逐轮文件链完整**（round-1/2/3 各有独立的 health→outdoor→course→high-five→result 序列），与当前流程结构一致。

---

## 2. 决定性对比：成功帧 vs 失败帧

| | 3/3 成功（10-10 19:31） | 失败（10-11 13:52） |
|---|---|---|
| 文件 | `round-3-feed-after.png` | `round-1-feed-after.png` |
| 大小 | 839,497 B | 333,412 B |
| 画面 | **正常喂食场景**（猫在吃饼干，盘中有食物） | **「兑换食物」购买弹窗** |
| 金币 | **528** | **437** |
| 爪印 | 4 | 5 |

**成功时账号有食物**，点「喂食」直接进入喂食场景 → 校验通过。
**失败时食物耗尽**，点「喂食」弹出兑换框 → 与期望的 `foodScene` 不符 → `PAGE_MISMATCH`。

> 金币 528 → 437 说明期间确实有消耗，账号资源在被正常消耗，而非程序异常扣费。

---

## 3. 自动购买能力：**历史上从未实现**

穷尽式检索，全部为 0：

| 检索方式 | 结果 |
|---|---|
| `git log --all -i --grep='购买\|purchase\|buy\|coin\|金币\|shop\|store\|exchange'` | 命中 6 条，**全部属于无关的上游 VirtualDisplay 仓库**（Shizuku/Koin 架构重构），与宠物自动化无关 |
| `git grep -l -i -E 'purchase\|buyItem\|shopItem\|payCoins\|兑换' HEAD -- '*.kt'` | **0 个文件** |
| `git grep -n -i -E '支付\|payButton\|pay\(' HEAD -- '*.kt'` | **0 处** |
| 代码内购买关键字 | 仅 `dismissCareUpgradeIfPresent`，非购买 |

### 关键澄清：现有 `dismissCareUpgradeIfPresent()` 不是购买
我直接读取了它的两个探针图片：
| 探针 | 图片实际内容 |
|---|---|
| `care_upgrade_hint.png` | **「点击空白处关闭」** |
| `care_upgrade_reward.png` | **「解锁奖励」** |

它处理的是**可点空白关闭的奖励提示气泡**（`tap(360,1180)`），
与**模态购买框**（商品/价格/数量/支付按钮）完全不同，因此关不掉兑换框。

**结论：不存在「历史自动购买能力」。过往只有手动购买，或截图能识别，均不构成程序自动购买成功。**

---

## 4. 版本对比：有无能力被删除/失效/未接入

### 4.1 `PetCareFlow.kt`（`b6ebbd7` → `97876c2`，+253 / −35）
diff 中看似"被删"的 35 行，实为**结构调整**，非删除。逐行核对：

| 项目 | `b6ebbd7`（成功版本） | `97876c2`（当前） | 判定 |
|---|---|---|---|
| 进入食物页 | `tap(room,(638,720),foodScene,"enter-food")` | `PetCareFlow.kt:225` 完全相同 | 一致 |
| 喂食点击 | `input.tap(360f,1287f); delay(2500)` | `PetCareFlow.kt:231` 完全相同 | 一致 |
| 喂后校验 | `verify(listOf(foodScene),"feed-after")` | `PetCareFlow.kt:233` 完全相同 | 一致 |
| 读属性 | `readHealth("feed-health",foodScene)` | `PetCareFlow.kt:234` 完全相同 | 一致 |
| 增益检查 | `check(energy>before && before in 5..12)` | `PetCareFlow.kt:235` 完全相同 | 一致 |
| 离开 | `tap(foodScene,(80,160),room,"leave-food")` | `PetCareFlow.kt:236` 完全相同 | 一致 |

**新增**内容为：`Consumable`/`ReservationState`/`ReservationOutcome` 预留机制、`reservation.confirmConsumed()`、`settle(...)`、`budgetEvent(...)`、`injectionAttempts` 防重复提交。
→ 这是**加固**，不是退化。

### 4.2 其他能力
| 能力 | 状态 |
|---|---|
| `verdict.json` 输出 | **仍在**（`PetAutomationService.kt:94`） |
| 击掌 30 次 | **仍在**（该 session `encouragementSent=30`） |
| 成绩结算 | **仍在**，但 `grade` 恒为 `UNKNOWN`（历史遗留，非本次回归） |
| `dismissCareUpgradeIfPresent` | 仍被调用（`PetCareFlow.kt:232,280`），但**对象不匹配**（§3） |
| 调用链未接入 | **未发现**。所有关键函数均有调用点 |

**未发现任何被删除、覆盖或失效的能力。**

---

## 5. DIGIT_RECOGNITION 复用评估

### 5.1 实现（`HealthDigitReader.kt`）
高质量的模板 OCR，非通用文字识别：
- 10 个数字模板（`app/src/main/assets/pet_health/digits.txt`，覆盖 0–9）
- 24×32 归一化 mask 与模板逐位比对（768 位）
- **严格安全门禁**：`confidence < 0.98` → Unknown；前二名差 `< 0.08` → `AMBIGUOUS_DIGIT`
- ROI：`PetHealthCheck.kt:28`，**52×31**，像素源 `(256+x, y+j)`，暗阈值 `max(R,G,B) < 100`
- 三个纵向位置：y = 228 / 304 / 380
- 前置锚定校验：`pet_health/health.png` @ 0.97，未确认则 `HEALTH_PANEL_UNCONFIRMED`

### 5.2 能否最小修改复用于金币/价格？—— **结构可复用，但有 3 处硬约束必须改**
| 行 | 约束 | 对金币/价格的影响 |
|---|---|---|
| `L12` | `width != 52 \|\| height != 31` → `DIGIT_ROI_SIZE` | 金币/价格 ROI 尺寸不同，**必改** |
| `L20` | `groups.size !in 1..3` → `DIGIT_LAYOUT_UNSUPPORTED` | 上限 3 位；金币 437 是 3 位（够用），但价格若 ≥1000 会失败 |
| **`L39`** | **`value !in 0..100` → `HEALTH_NUMBER_OUT_OF_RANGE`** | **致命**：437 金币直接被拒 |

**结论**：`HealthDigitReader` 是**健康值专用**的（0–100 语义已写死在 L39）。
若要做金币/价格读取，**不能直接复用**，需抽出一个「位数与范围参数化」的通用数字读取器。

好消息：**核心算法（mask 比对 + 置信度门禁 + 模板集）完全可复用**，
只需把 `52×31`、`1..3`、`0..100`、暗阈值、ROI 起点参数化即可 —— 是**最小增量**，不是新建 OCR 系统。

**但模板质量需实测**：现有 10 个模板是从**健康值字体**取样。金币数字的字体/字号/颜色（黄色描边）与健康值（深色实心）不同，**现有模板很可能无法达到 0.98 阈值**。这需要实测验证，不能假设。

---

## 6. 能力分类

### ✅ A. 历史实机已证实
| 能力 | 证据 |
|---|---|
| 3 轮连跑批次 | `1791631902297`，`COMPLETED` 3/3，`MANAGED_BATCH_PASS` |
| 单轮喂食 | `round-3-feed-*` 完整 + 属性增益校验通过 |
| 课程起止 | `courseStarts=3`，每轮 603s 量级 |
| 击掌鼓励 | 30 次，`high-five-1..10-after.png` |
| 成绩结算页导航 | `result-close-course` / `leave-academy` / `return-home` |
| 停止能力 | session `1791650140498`，`STOPPED`，停止后 0 日志 |
| 过期数据不授权 | `OBSERVATION_EXPIRED_OR_CLOCK_REVERSED` |
| 虚拟屏抓帧回退 | `SHIZUKU_FALLBACK` 986/986 成功 |

### ✅ B. 当前仍可用
- 全部上述能力在 `97876c2` 中代码路径存在且被调用
- 新增：预留/消费账本、防重复注入（`injectionAttempts`）、`settle()` 结算
- 单元测试 165 项 / 0 失败（**不等于**真机 E2E）

### ❌ C. 已发生回归
**无。未发现任何能力退化。**

### 🆕 D. 新场景尚未实现
| 场景 | 状态 |
|---|---|
| **食物耗尽 → 兑换食物弹窗** | **未实现**（历史 0 行代码） |
| **洗澡道具耗尽 → 兑换洗澡道具弹窗** | **未实现** |
| 金币/价格数字读取 | 未实现（现有 reader 被 `0..100` 拒） |
| 购买预算控制 | 未实现 |
| 购买后核验（金币扣减/道具增加） | 未实现 |

### ⚠️ E. 证据不足
| 项 | 原因 |
|---|---|
| 成绩等级 | `grade` 恒 `UNKNOWN`，无法判定 |
| 洗澡成功增益 | 3/3 session `clean_count=0`，**从未在真机成功洗澡过** |
| 现模板读金币 | 未实测 |
| 兑换弹窗在"道具充足"时是否出现 | 未验证 |

---

## 7. 最小恢复方案（仅建议，未实施）

### 方案主线：**新增一个「消耗品耗尽 → 兑换」分支，不做通用采购系统**

```
护理前读属性
  ├─ 达标 → 不护理（保持现状，已证实）
  └─ 未达标 → 进入护理页
        ├─ 页面 = foodScene / bath（正常）→ 走既有已验证路径  ← 不改动
        └─ 页面 = 兑换弹窗（新分支）
              ├─ 模板确认标题（兑换食物 / 兑换洗澡道具）
              ├─ 读单价、数量、合计、余额（复用 digit 算法，参数化）
              ├─ 预算校验：单笔≤50 / 累计≤100 / 余额≥100
              ├─ 点「支付」→ 核验扣减与道具增加
              ├─ 记录购买事件（原因/商品/数量/金币变化/核验结果）
              └─ 任一不确认 → 安全停止，不重复提交
```

### 具体最小改动点
| # | 改动 | 复用 |
|---|---|---|
| 1 | 从 `HealthDigitReader` 抽出通用数字读取（ROI 尺寸、位数、范围、阈值参数化） | **核心算法 100% 复用** |
| 2 | 新增金币/价格 ROI + 模板（**需先用真实帧实测**能否达 0.98） | `TemplateMatcher` |
| 3 | 新增 2 个弹窗标题探针（从真实帧裁切） | `pet_experimental` 同模式 |
| 4 | `PetCareFlow` 中 `verify(listOf(foodScene),"feed-after")` 前插入弹窗分支 | 现有 `dismissCareUpgradeIfPresent` 同结构 |
| 5 | 购买预算沿用 `ReservationGuard` 语义 | **现成** |
| 6 | 购买事件用 `emit(...)` | **现成** |

**不新增**：商城模块、采购管理、任务调度引擎（符合你的架构约束）。

### 建议的验证顺序（先测量，不先开发）
1. **先测模板复用性**：抓一张金币区域真实图，用现有 10 模板跑匹配，看最高分。
   - 若 ≥0.98 → 直接复用，改动极小
   - 若 <0.98 → 需为金币字体补模板（仍是最小增量）
2. 再测价格区域同样问题
3. 通过后才做分支接线

> 这一步应在写任何业务代码**之前**完成，避免为一个可能不可行的方案投入。

---

## 8. 本轮遵守的边界

| 约束 | 遵守情况 |
|---|---|
| 只读审计 | ✅ 未改任何业务代码（`git status` 已跟踪修改 = 0） |
| 禁止大规模重构 | ✅ 未改动 |
| 禁止重新部署 APK | ✅ 未部署 |
| 禁止未经授权真实购买 | ✅ **未发生任何购买**，未消耗金币 |

> 说明：本次会话为定位问题，执行过 **1 次**已授权的 1 节测试（session `1791697915016`），
> 该次运行 **未购买任何物品**（`feed_count=0`，无支付动作），金币未变动。
> 你最新的"暂停开发"指令下达后，**未再启动任何设备操作**。

---

## 9. 剩余风险

1. **洗澡路径从未真机成功**（`clean_count=0`）—— 即使购买问题解决，洗澡仍需首次验证。
2. **金币字体模板未知** —— 决定 §7 改动量，需实测。
3. **`grade` 恒 UNKNOWN** —— 历史遗留，影响 01-03 验收。
4. **购买弹窗是否可控** —— 支付按钮点击后是否二次确认，未验证。
5. **重复扣款风险** —— 现有 `injectionAttempts` 防重复机制**未覆盖购买动作**，需扩展。
