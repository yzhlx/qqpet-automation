# 本地遗留能力排查报告：「护理用品不足 → 自动金币购买 → 完成护理」

> 排查日期：本轮只读定向排查（未修改任何工程代码，未执行任何真实购买）。
> 结论先行：**该能力链确实已在本地实现并真实跑通过 —— 但实现形态是「Agent 用 adb 亲手操作」，
> 不是 Android App 内代码，也不是可复用的独立脚本。这就是它没有进入 GitHub 仓库的原因：
> 仓库里从来没有过这段代码，不存在"遗留在本地未提交"的代码文件。**

---

## 1. 核心结论（三个形态逐一判定）

| 形态 | 是否存在 | 证据 |
|---|---|---|
| **A. Agent 亲手操作（adb input -d 4 tap）** | ✅ **完整存在，两次真实跑通** | §2 账本 + §3 会话记录 |
| **B. 独立可复用脚本** | ❌ 不存在（仅有输入辅助工具 SoapGesture，不含购买） | §4 |
| **C. Android App 内部自动化** | ❌ 不存在（app/src 全文无 兑换/exchange/purchase 代码） | §5 |

完整链路每一环都有实证：**检测体力/清洁<90 → 发现消耗品耗尽 → 打开兑换对话框 → 选商品/调数量 → 点击支付(360,1390) → 购买成功(余额差自证) → 喂食/连续擦洗完成护理 → 数值≥90**。

---

## 2. 决定性证据：两份真实购买账本（Agent-operated）

### 2.1 `resume9-ledger.csv`（2026-10-09 20:12，DSH Agent）
`C:\Users\lu158\Desktop\dsh插件\养宠自动化\VirtualDisplay\deliverables\takeover-runtime\three-course-live\single-regression\direct-one-round\ui-run\supply-course-7\resume9-ledger.csv`

逐步骤记录 香皂片 @2 金币：余额 636→634→620，库存增减，清洁 7→17→27→97，随后课程消耗 17 金币（620→603）。
配套 `resume9-RESULT.txt`（10-09 20:16）原文定性：
> 「真实护理学习这一轮完成（**QQ UI手动操作 + SOAP输入辅助工具，不是App自动化回归通过**）」

同目录 `ledger.csv`（10-09 17:26）记录 饼干 @5×5 共 25 金币、香皂片 @2×9 共 18 金币，余额 663→603，体力 44→94 每块 +10 全部对账。

### 2.2 `topup-ledger.md`（2026-10-10 18:42，DSH Agent）
`C:\Users\lu158\Desktop\dsh插件\养宠自动化\.foreman\qqpet-three-round-benchmark\live-run-2\topup\topup-ledger.md`

初始 体力66/清洁63 → 购买 饼干×3（15币）+ 香皂片×3（6币）→ 喂食 + 连续擦洗 → 清洁 63→92。
记录支付按钮坐标 **(360,1390)**，并如实标注余额疑问（对话框 648 vs 房间页 603，`OPEN_QUESTION_BALANCE`）。

### 2.3 为什么 HEAD 源码审计会漏掉它们
`git check-ignore -v` 实证：以上全部文件位于 `.gitignore:15` 的 **`/deliverables/`** 忽略规则之下，`.foreman` 本就不在仓库内。**证据在仓库之外/被忽略目录，不在任何 Git 对象里。**

---

## 3. 会话记录：购买动作的真实执行序列

### 3.1 Codex（10-05，早于 DSH 账本）
`C:\Users\lu158\.codex\sessions\2026\10\05\rollout-2026-10-05T13-18-24-*.jsonl`（226 MB，cwd=本项目）
提取到 49 条去重执行命令，含：
- `input -d 4 tap 467 828 / 180 830 / 415 830`（10-05 14:31 会话）——**商品栏/步进器位置**（对话框商品行 y≈830，与截图实测一致）
- `input -d 4 tap 360 1390`——**支付按钮**（与 10-09/10-10 账本坐标相同）
- `input -d 4 tap 516 160 / 355 454 / 638 720 / 80 160 / 638 868`——健康面板开关、食物场景、洗澡场景（与 App `PetCareFlow.kt` L190/L202/L225/L236/L264 的坐标**逐一对应**：Agent 用 adb 复刻了 App 自己的导航坐标）

### 3.2 DSH（10-09/10-10）
110 个 `.zstd` 会话全部解码（工具：`VirtualDisplay\tools\legacy-scan\scan_sessions.py` 等）。
命中购买链的会话：`a201e82f` / `b797ec57` / `cb61db57`，均为 `adb shell input -d 4 tap` 序列 + 截图复核，无购买脚本落盘。
高命中干扰项已排除：`db2f8e1d`(10-04) 与 `94035573`(10-04) 的"兑换"是 LLM 额度兑换语境；WorkBuddy traces 的"香皂/饼干/兑换"是 QQ宠物攻略网文抓取内容，无 adb 执行。

---

## 4. 唯一的"半脚本"资产：continuous-soap-tool（不是购买）
`...\ui-run\continuous-soap-tool\retry-reject-stop\`
- `SoapGesture.java`（10-08，18,657 B）+ `dex\classes.dex`（10,860 B，sha256 DA93…02A2）+ README
- 作用：单进程注入 25s 连续手势（1 DOWN + 162 MOVE + 1 UP，同一 downTime），一次擦洗消耗多块香皂。运行方式 `app_process /system/bin SoapGesture 4`。
- 它**只解决"用"，不含"买"**；且其 Java 注释明确写着它是给"操作者"（Agent/人）用的临时工具，不是 App 功能。

## 5. App 源码判定：无购买分支
`grep 兑换|exchange|purchase app/src/**/*.kt` → **0 命中**。
`PetCareFlow.kt` 已有：健康面板读值(L190)、食物场景进入(L225 `638,720`)、洗澡场景(L264 `638,868`)、擦洗手势路线(L271 起，与 SoapGesture 同一路由)——**唯独没有兑换对话框分支**。库存耗尽时 App 会失败退出，当时的补货全靠 Agent 手工 adb。

## 6. 对话框几何实测（本轮新证据，修正此前"零样本"错误）
17 张对话框截图存在于 `supply-course-7\`（`cookie-price.png` 10-09 16:09、`soap-price.png` 10-09 16:41、`resume9-soap-*` 10-09 18:34-18:46 等）。
- 两类对话框**布局相同**：标题暗带 y863–895，蓝色支付按钮 y1370–1446（中心≈1408；历史点击 360,1390 落在按钮内）。
- **余额药丸位置不同**：食物类在右上 y245–285，洗护类在标题行右端 y855–905。
- **数字读取仍不可编程**（`test_dialog_digits2.py`/`test_raw_mask.py` 实测）：对话框数字是黑字（暗像素门限可检出），但单字宽 12–14px、高 20–21px，与现有 52×31 模板窗口几何不符；按笔迹对齐后现有模板最高置信度仅 0.68–0.93（437/638/663 全部 <0.98 门限）。**历史购买成功判定从不依赖程序读余额，靠的是 Agent 目读 + 前后账本对账。**

## 7. 复用 / 迁移成本评估
可复用资产（全部已实证）：
1. **坐标序列**：商品行 y≈830、步进器、支付 (360,1390)、场景入口 638,720/638,868、返回 80,160 —— 10-05→10-10 跨 5 天坐标稳定。
2. **SoapGesture dex**：直接复用为"用香皂"环节。
3. **账本格式**（ledger.csv 列结构）：可直接作为验证证据模板。
4. **17 张对话框帧**：做商品/支付按钮模板的现成素材。

迁移到 App 的工作量（若将来解除暂停）：在 `PetCareFlow` 增加一个兑换分支（进入→模板匹配商品→步进→支付→关闭复核），约 150–250 行 + 2–4 个新模板；**无需新权限、无新坐标风险等级**（与现有硬编码坐标同类）。
关键设计约束：**购买成功信号不能依赖余额 OCR**（§6 已证伪）——应改用"库存增量/对话框消失/确认帧模板"判定，这正是当年 Agent 用目视+账本绕开的点。

## 8. 排查范围声明
**已查**：Git 全对象（分支/stash/reflog/dangling `dca3f51`/worktree；`/deliverables/` 被 .gitignore:15 忽略）；整个工作区文件 grep（含 .foreman、screenshots、tools）；DSH 110 会话（zstd 解码）；Codex 157 会话；WorkBuddy sessions/traces/projects；APK 清单（仅 test/debug 构建，无购买代码）；WSL（本机未安装，范围 N/A）。
**未查（无法声明）**：GitHub 远端历史中本地克隆未含的部分；QQ 服务端数据。
**明确不宣称**："整个历史从未实现"——恰恰相反，本地历史**已实现且跑通**，只是形态为 Agent 手工操作，从未固化为代码。
