# 历史购买能力调查报告

> 调查时间：2026-10-11 13:5x
> 目的：遵循「先搜索历史，不要重新开发」，确认项目是否已具备游戏内购买能力
> **结论：不具备。现有代码只处理「解锁奖励」提示气泡，与「兑换食物/洗澡道具」购买弹窗是两回事。**

---

## 一、已找到的相关基础设施

### 1. `dismissCareUpgradeIfPresent()`（`PetNavigation.kt:1173`）
```kotlin
suspend fun dismissCareUpgradeIfPresent(): Boolean = with(flow) {
    val matched = score(current,listOf(careUpgradeHint)) >= matchThreshold &&
                  score(current,listOf(careUpgradeReward)) >= matchThreshold
    if (!matched) return@with false
    verify(listOf(careUpgradeHint),"care-upgrade-before")
    safety(); input.tap(360f,1180f); delay(pageSettleMs)
    verifySettled(listOf(foodScene,bath,room),"care-upgrade-after")
    true
}
```

### 2. 两个探针（`PetPageProbes.kt:69-70`）
| 探针 | ROI | 资源 |
|---|---|---|
| `careUpgradeHint` | `[250,1160]-[478,1200]` | `pet_experimental/care_upgrade_hint.png` |
| `careUpgradeReward` | `[240,1020]-[480,1080]` | `pet_experimental/care_upgrade_reward.png` |

### 3. 来源提交 `fae88c5`
"Recognize measured care upgrade popup before checking health gain"（2026-10-06）
在 `feed` 与 `clean` 动作后插入 `dismissCareUpgradeIfPresent()`。

---

## 二、关键发现：它针对的是**另一个弹窗**

我直接读取了两个探针图片（不靠推测）：

| 探针 | 图片实际内容 |
|---|---|
| `care_upgrade_hint.png` | **「点击空白处关闭」** |
| `care_upgrade_reward.png` | **「解锁奖励」** |

**即：现有实现处理的是「解锁奖励」提示气泡**（一条可点空白关闭的 toast/hint）。

而本次实测遇到的是 **「兑换食物」购买弹窗**：
| 元素 | 内容 |
|---|---|
| 标题 | **兑换食物** |
| 商品 | 饼干（体力+10/心情+1，5 金币）、虾（20 金币） |
| 步进器 | 数量 5 |
| 按钮 | **支付 25 金币** |
| 余额 | 437 |

**两者完全不同**：
- 前者是**被动提示**，点空白即关（`tap(360,1180)` 落在 hint 区域）
- 后者是**模态购买框**，含商品/价格/数量/支付，必须显式交互

### 因此 `dismissCareUpgradeIfPresent()` 关不掉兑换弹窗
它的 `matched` 判据要求两个特定 ROI 同时命中；兑换弹窗在 `y≈1020-1200` 区域有**商品图与价格**，与 `解锁奖励/点击空白处关闭` 图案不匹配 → `matched=false` → **直接 return，什么都不做**。

这正是 `feed-after` 报 `PAGE_MISMATCH:...:0.7788444081336239` 的原因：弹窗仍在，画面与期望的 `cookie`（食物页）不符。

---

## 三、历史是否有购买实现？—— 没有

| 检查项 | 结果 |
|---|---|
| `git log --grep 购买/兑换/purchase/coins/金币/shop` | **0 条** |
| `git log --grep cookie/soap/care/feed/bath` | 5 条，均为**消费**道具，无购买 |
| 代码内 `purchase/buy/shop/store/Exchange` 关键字 | 仅 `dismissCareUpgrade`，无购买逻辑 |
| `ResourceLedger` / `ActivityEvent` | 存在，但为**记账**用途，非购买 |

**结论：项目从未实现过游戏内购买。** 本次需新增，且是**最小改动**。

---

## 四、可复用的既有资产（避免重造）

| 资产 | 位置 | 复用方式 |
|---|---|---|
| `ReservationGuard` / `reserve*` 模式 | `PetCareFlow.kt:77-101` | 金币预算沿用同一「先预留、失败不退还」语义 |
| `ConsumableReservation` | `PetCareFlow.kt:89-101` | 扩展一个 `COIN` 类别 |
| `TemplateMatcher` / `verify(listOf(probe))` | `PetNavigation` | 用真实帧做弹窗识别与前后校验 |
| `emit(...)` 事件 | `PetCareFlow` | 记录每次购买的完整证据 |
| `PetRecoveryBoundary` | `PetRecoveryBoundary.kt` | 失败时安全停止 |

---

## 五、确定性的弹窗签名（可直接用于识别）

历史所有 `feed-after` 失败**只有两个** matchScore：
| score | 时间 |
|---|---|
| `0.6584672294843863` | 2026-10-06 12:25 |
| `0.7788444081336239` | 2026-10-10 19:22 / 2026-10-11 13:52 |

**逐位相同 = 同一画面**，说明兑换弹窗是可稳定复现的确定性场景，适合用模板匹配识别。

---

## 六、实现方案（最小改动）

### 新增探针（需从真实帧裁切，**不硬编码价格**）
- `food_exchange_title`：标题「兑换食物」区域
- `soap_exchange_title`：标题「兑换洗澡道具」区域

### 新增流程
```
需要护理 && 属性未达标
  → 打开护理页
  → 若出现兑换弹窗：
      1. 模板校验标题（兑换食物 / 兑换洗澡道具）
      2. 从画面读取商品名、单价、数量、合计、余额   ← 不硬编码
      3. 预算校验：单笔 ≤50 / 累计 ≤100 / 购买后余额 ≥100
      4. 点击「支付」
      5. 核验金币扣减 + 道具增加（前后读数）
      6. 记录购买事件
  → 执行护理
  → 重新读属性，确认 ≥90
```

### 硬约束（来自用户授权）
- 每笔最多 **50 金币**
- 每次运行累计最多 **100 金币**
- 购买后余额不得低于 **100 金币**
- **不得凭假设硬编码**金币价格与道具效果 → 必须从画面读取
- 超预算 → 停止并报告，**不得自动放宽**
- 不确定是否成功 → **不得重复提交**

---

## 七、需要用户知晓的风险

1. **价格读取若失败**：必须 `Unknown` 停止，不能猜。这会降低自动化成功率，但符合「不硬编码」要求。
2. **金币扣除的验证**：宠物页金币显示（437）需可读；若无法稳定识别，则购买后无法核验 → 应停止而非盲目继续。
3. **本方案会真实消费金币**（已获授权），但**不会**触发人民币充值。
4. 购买弹窗在**虚拟屏 display 4**，只能由应用内 Shizuku 注入操作，符合现有架构。

---

## 八、下一步

按用户要求执行 **A 单次补给测试**：
- 当前体力 81 / 清洁 72，均 < 90 → 需要护理
- 食物与洗澡道具均不足 → 应先购买再护理

但因**当前代码尚无购买能力**，需先实现最小购买流程，再做 A→B→C→D 分级验收。
