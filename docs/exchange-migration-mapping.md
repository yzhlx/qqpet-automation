# 历史能力迁移映射表：证据 → App 实现位置 → 验收测试

> 目的：把 2026-10-05/09/10 由 Agent 用 adb 实测跑通的「金币购买→护理达标」链路固化为 App 内自主能力。
> 本表是实施前的对照基线；实施完成后每行必须有对应的测试或真机验收记录，防止能力再次遗漏。
> 证据索引详见 `docs/legacy-purchase-search-report.md` 与 `docs/exchange-evidence-index.md`。

## 1. 历史操作规则（从真实账本与会话提取，行为参考）

| # | 规则 | 证据 |
|---|---|---|
| R1 | 食物场景点击 (360,1287)（盘中"＋"位）打开「兑换食物」弹窗；饼干默认选中（蓝框） | a201e82f 会话 @991102；resume9 截图序列 |
| R2 | 洗澡场景点击 (360,1287) 打开「兑换洗护道具」弹窗；香皂片默认选中 | a201e82f @1663722；soap-price.png |
| R3 | 数量默认：食物 5 / 洗护 10，**弹窗记忆上次购买数量**；减号 (516,1287)、加号 (655,1287)、数量显示 (≈585,1287) | a201e82f @1004867；topup-ledger |
| R4 | 减号步进不可信（曾 7 击产生 -9，双击/漏击并存）→ 数量必须以屏幕读数为准，禁止按点击数推断 | a201e82f @1835213 推演记录 |
| R5 | 支付按钮 (360,1390)，按钮区 y1370–1446；历史 1390/1405/1408/1418 均命中 | ledger×3 + 实测几何 |
| R6 | 支付后弹窗关闭并自动进入喂食场景（洗护同理停留洗澡场景）；喂食=逐块点盘中 (360,1287) | topup-ledger「使用 1」 |
| R7 | 弹窗内余额显示与房间页不一致（648 vs 603）→ **余额不可作为购买判定依据**；金币预算只能用会话内账本 | topup-ledger OPEN_QUESTION_BALANCE |
| R8 | 单价恒定：饼干 5 / 香皂片 2 / 虾 20 / 浴花 12（多次购买余额差自证） | ledger.csv 663→658→638；topup 633→627 |
| R9 | 效果恒定：饼干 体力+10 心情+1；香皂片 清洁+10 心情+1 | ledger 两处 +10/块对账 |
| R10 | 擦洗=单进程单 downTime 连续手势 DOWN(360,1345)→162 MOVE→UP，~25s；App `PetCareFlow.kt:270-279` 与 SoapGesture 路由逐点相同 | SoapGesture.java 头注释 |
| R11 | 数量框可直接点选键盘输入（keyevent 66 提交）——但 App 无文本注入，故采用「步进+读数核验」 | a201e82f @2105042 |

## 2. 证据 → App 实现位置 → 验收测试

| 历史证据 | App 实现位置 | 验收测试 |
|---|---|---|
| R1/R2 弹窗入口 | `PetExchangeFlow.openDialog(scene)`：scene 内 `tap(360,1287)` + 弹窗标题模板核验（新 probe `exchange_food` / `exchange_soap`） | T1 记录帧回放：17 张历史弹窗帧全部 PASS 标题核验；T2 真机单轮：弹窗打开并被核验 |
| R3/R4 数量 | `PetExchangeFlow.setQuantity(target)`：单步减号→读数量数字→与期望比对→循环；`DialogDigitReader`（对话框字体数字模板，从历史帧提取，非通用 OCR） | T3 记录帧回放：qty=1/3/4/5/7/8/10 全部读对；T4 真机：读到数量=1 才允许支付 |
| R5/R8 金额 | 支付按钮白字金额读数（同一 DialogDigitReader，亮字反相）；强校验 `total == unitPrice × qty`，不符 → 硬停 `EXCHANGE_TOTAL_MISMATCH` | T5 回放：5/20/25/16/14/6/2 全对；T6 真机：支付前日志含 total=5（饼干×1）|
| R7 预算 | `ExchangeBudget`（新，模式同 `CareConsumableBudget`）：会话金币上限（默认 100）、单次购买上限、CARE_BUDGET 事件流 | T7 JVM：超预算在 tap 前拒绝（`EXCHANGE_BUDGET_EXHAUSTED`） |
| R5 重复购买保护 | 购买原子单元：读数核验→支付→结果确认三段；支付前记录 injectionAttempts 基线；结果未知 → `settleAfterFailure` 同款 RETAINED 语义 + 硬停 `EXCHANGE_PAY_UNCONFIRMED` | T8 JVM：未知结果不返还预算、不二次支付；T9 真机：支付后必须见到确认态（弹窗关闭/成功帧）才计 1 次购买 |
| R6 支付后路径 | 购买后核验「弹窗已关闭」（回 scene 模板）；喂食复用现有 `recover()` feed 循环 | T10 真机闭环：购买→自动喂食→体力≥90 |
| 触发点：库存耗尽 | `PetCareFlow.recover()`：feed/clean 循环捕获 `*_NO_EXPECTED_GAIN_OR_EMPTY` → 调 `PetExchangeFlow.ensureStock()` 购买 1 件 → 重试一次；再失败 → 停 | T11 真机：耗尽→自动购买→恢复（App 自主，无 ADB） |
| 受控批量 | 单件验收通过后：`buyQuantity = ceil(gap/10)` 上限 3，逐件步进读数核验 | T12 真机：一次购买 qty=2/3 且 total 读数=unit×qty |
| R10 SoapGesture | **不迁移**。App `input.drag(points,150)` 单进程单 downTime 与工具同原理；工具仅因 Agent 侧多进程 adb 无法复现而存在。真机清洁 +10/片 即为 App 擦洗可靠性的验收 | T13 真机：清洁 63→≥90 每片 +10（与 R9 对账） |
| 全链路 | `PetSessionController.run()` 不改结构；10 轮支持：`require(totalRounds in 1..10)` | T14 真机单轮闭环 PASS；T15 3 轮；T16 10 轮 |
| 知识入库 | 本文件 + `docs/exchange-evidence-index.md`（脱敏索引：sha256/时间/证明点）+ DialogDigitReader 记录帧回放测试 | T17 `gradle test` 全绿；git 提交 |

## 3. 硬停止清单（任一触发即整 session 停，不做输入）

1. `EXCHANGE_BUDGET_EXHAUSTED`：会话金币预算耗尽（支付前拒绝）。
2. `EXCHANGE_TOTAL_MISMATCH`：屏幕金额 ≠ 单价×数量。
3. `EXCHANGE_PAY_UNCONFIRMED`：支付后无法在限时内确认结果（同 RETAINED 语义，绝不重试支付）。
4. `EXCHANGE_QUANTITY_UNREADABLE` / `EXCHANGE_DIALOG_UNCONFIRMED`：数量/弹窗身份读不出。
5. 购买后喂食仍无增益 → 原 `FEED_NO_EXPECTED_GAIN_OR_EMPTY` 上抛（不掩盖）。
