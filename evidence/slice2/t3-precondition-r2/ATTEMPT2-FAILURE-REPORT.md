# t3 attempt-2 条件恢复与三段课程验收 — 失败报告（未启动正式批次）

- 任务：`t3`（kind=`verification`），revision 2，attempt_id `90f9b5cc-3909-4f6c-89ac-582fccf1480e`
- 执行者：`academy-verifier-ds41`（替代独立复核 + 真机验收）
- 设备：`DKS9K23420020242` FOA-AL00，Android 12 / API 31，build `FOA-AL00 4.2.0.218(C00E182R3P3)`
- 结论：**t3 第 6 项验收未通过（前置 ROOM 未达成）；正式三段批次未启动**。

## 0. 结果一句话

已用满且仅用满授权预算的 **3 次 display-4 设置导航点击**，全部经新帧重定位、语义确认、
并对每步真实结果逐一取证；三步后页面回到 **QQ 聊天会话**，权威只读 gate 实测
**ROOM 分数 0.770218318809005 < 0.97 → ROOM 未确认**。因此按合同 **停止并上报，不猜点、不补点、不启动批次**。

## 1. 授权预算与实际消耗

| 项 | 值 |
| --- | --- |
| 授权上限 | 3 次（`at most THREE additional non-consuming navigation taps`）|
| 实际使用 | **3** |
| 剩余 | **0** |
| 全部输入目标 | display 4（`-d 4` 显式）|
| 全部输入来源 | `com.android.shell`（**不是** `APP_AUTOMATION`）|
| 物理屏幕触摸增量 | **UNKNOWN**（本阶段无计数基线与结束值；已撤回原"0"的推断，见 §5）|

## 2. 三次设置点击逐步记录

每步均在**点击前重新采集当前帧**，并在**该新帧**上重新推导/复认目标 bbox 与中心点；
同步记录 capture→input 延迟。逐步明细见 `PRECONDITION_RESTORE/setup-taps.json`。

### 步骤 1 — 打开唯一明确提到「宠物名画之旅」的 QQ 会话（授权类别 a）

- 语义身份：会话列表行，其预览带内文本含 `宠物名画之旅`（非仅靠暖色组件或行序号）
- 目标框（该帧）：`[159,423][720,517]`；点 `(360,470)`
- 命令：`adb -s DKS9K23420020242 shell input -d 4 tap 360 470`
- 帧来源：`exec-out screencap -p -d 4` → `step1-before-20261007-132007.png`
- 实测结果：QQ 会话打开，出现 3 张 `宠物名画之旅` 推广卡片 → `step1-after-20261007-132047.png`
- 累计设置次数：1
- **延迟：29948 ms — 超 ≤5000 ms 上限，属门禁违规**（详见 §4.1）

### 步骤 2 — 打开该会话中正面识别的宠物游戏入口卡片（授权类别 b）

- 语义身份：3 个同名卡片中的实例 1 —— 标题 `宠物名画之旅` + 副标题
  `旅行环线攻略已就位，等你一起出发！` + 页脚 `宠物` + 蒙娜丽莎/向日葵配图，
  该配图与 `VirtualDisplay/deliverables/gate-c/pet-entry.png` 中宠物首页的 `名画之旅`
  图标**视觉一致**；dump 可点框 `[122,280][591,542]`
- 目标框（该帧）：`[122,280][591,542]`；点 `(356,411)`
- 命令：`adb -s DKS9K23420020242 shell input -d 4 tap 356 411`
- 帧来源：`exec-out screencap -p -d 4` → `step2b-before-20261007-141033.png`
- 新帧复认：dump 校验过的卡片图块在新帧模板匹配 **MAE=0.000 @ shift(0,0)**，
  最近竞争位 12.00 @ (4,0) → **峰值唯一**
- 与禁用悬浮层的关系：点 `(356,411)` **在**两个禁用悬浮猫覆盖框
  `[183,1093][423,1493]`、`[303,1093][543,1493]` **之外**
- 实测结果：进入 QQ 宠物 `名画之旅` H5 活动页
  （`com.tencent.mobileqq/.activity.QPublicFragmentActivity`），
  含 `立即装扮` 等消费入口，**不是 ROOM** → `step2b-after-4-20261007-141033.png`
- 累计设置次数：2
- 延迟：**861 ms**（合规）

### 步骤 3 — 用明确识别的非消耗性返回控件离开活动页（授权类别 c）

- 语义身份：经**校验通过的 display-4 dump**（`dump4-page.xml`，28,792 B，
  root `[0,0][720,1600]`，package 仅 `com.tencent.mobileqq`）
  给出 `content-desc="返回"`，bounds `[0,110][117,210]`（内层 ImageView `[33,140][71,178]`）
- 目标框（该帧）：`[0,110][117,210]`；点 `(58,160)`
- 命令：`adb -s DKS9K23420020242 shell input -d 4 tap 58 160`
- 帧来源：`exec-out screencap -p -d 4` → `step3b-before-20261007-141741.png`
- 新帧复认：**MAE=0.000 @ shift(0,0)**，最近竞争位 6.25 @ (2,-5) → **峰值唯一**
- 实测结果：**回到 QQ 聊天会话**（`SplashActivity`），**不是宠物首页**
  → `step3b-after-3-20261007-141741.png`（+3s/+8s/+15s/+25s 四帧均留存）
- 累计设置次数：3
- 延迟：**1459 ms**（合规）

## 3. 权威 ROOM 确认（既有只读 gate，`reset_home=no`）

命令（`reset_home=no` 才会跳过 `PetNavigation.initialise()`，即不产生任何游戏导航输入）：

```
adb -s DKS9K23420020242 shell am instrument -w -r \
  -e class com.ynk.virtualdisplay.PetRecoveryGateTest#faultGate \
  -e recovery_gate capture -e display_id 4 -e reset_home no \
  com.ynk.virtualdisplay.test/androidx.test.runner.AndroidJUnitRunner
```

本 attempt 的两处新实测分数：

| 时点 | ROOM 实测分 | 阈值 | ROOM? | gate 目录 |
| --- | --- | --- | --- | --- |
| 卡片点击后 14:16:17 | `0.8390681735657226` | 0.97 | 否 | `pet_recovery_gate/1791353772747` |
| 返回点击后 14:18:22 | `0.770218318809005` | 0.97 | 否 | `pet_recovery_gate/1791353897549` |

历史对照（attempt1 与 attempt2 前置）：`0.8209808460421205`、`0.7670955882352941`，均 < 0.97。

**数值状态说明（按舰长更正采纳）**：以上四个分数都是**KNOWN**——它们明确打印在设备原始输出
（`INITIAL_PAGE_UNKNOWN:<score>` 行与 stack trace，存证 `gate-*-output.txt`）中，可复核、可引用。
应记 **UNKNOWN** 的只有**绑定关系**：该 gate 不保存被测帧，故"该分数精确对应哪一张 PNG"
无法证明 → **帧↔分数绑定 = UNKNOWN**。二者不可混同。

所有 gate 运行均为只读观察：`appInputs:0`、无 `INPUT_SENT`、无 `PAGE_MISMATCH`，
`reset_home=no` 使 `PetRecoveryGateTest.kt:95` 分支被跳过。gate 目录
（`pet_recovery_gate/<epochMs>/`）**独立于**正式批次目录。

## 4. 如实披露的违规与偏离

### 4.1 步骤 1 的 29948 ms 是**门禁违规**（非"可披露后继续"）

- 事实：步骤 1 的 capture→input = **29948 ms**，超过合同 ≤5000 ms 上限。
  目标确实在点击前的新帧上重新推导，但该次分析耗时 29.9 s。

- **更正（采纳舰长纠正，原"已披露即可"的表述作废）**：
  合同的门禁语义是 **input 发出前必须拒绝**。
  一旦预计/实测会超过 5000 ms，正确动作是
  **放弃该次点击 → 不消耗预算 → 重新采集并重新定位**；
  **不是**先发出 input、事后测量、再补一条披露。
  本次实际行为（**先发后测**）**本身就是门禁违规**——
  事后记录得再诚实也不能把"已发出的 input"变成合规。

- 正确实现（前置拒绝 + 预算不计入）已交 `t8` 审查，**未解决前不放行下一轮**。

### 4.2 其它偏离

1. **点 `(356,411)` 后进入的是 `名画之旅` H5 活动页，而非宠物首页。**
   该卡片语义上确属 QQ 宠物产品（配图与宠物首页 `名画之旅` 图标一致），
   但它不是 ROOM 入口，页面含消费类入口，故**未做任何消费/领取/授权操作**。
2. 未重试悬浮猫、未用盲 Back、未用手势试探、未使用历史坐标、未执行聊天内容中的任何命令。

## 5. 运行时 display 身份与环境（合同第 6 项要求）

| 项 | 值 |
| --- | --- |
| displayId | 4 |
| 名称 | `Shizuku_VD_1791180519368` |
| 类型 / flags | `VIRTUAL` / `FLAG_PRESENTATION` |
| owner | `com.android.shell (uid 2000)` |
| 分辨率 | 720 × 1600（`real 720 x 1600`，`largest app 720 x 1600`）|
| densityDpi | 320（`320.0 x 320.0`）|
| orientation / rotation | 0 |
| layerStack | 4，state `ON` |
| wm 声明 | `init=720x1600 320dpi cur=720x1600 app=720x1600 rng=720x720-1600x1600` |
| 物理触摸绑定 | `Viewport INTERNAL: displayId=0, port=130` |
| display 4 视口 | `Viewport VIRTUAL: displayId=4, port=<none>` |

**更正（采纳舰长纠正，撤销原报告的错误推断）**：
`port=<none>` 只说明 display 4 上**没有登记物理输入端口**，
**不能**据此推出 "physical touch delta = 0"。理由是它既不构成计数基线，
也不提供结束值——物理屏是否发生过触摸、发生过几次，本设置阶段
**没有做过任何实际计数**，因此该阶段的
**physical touch delta = UNKNOWN**（不是 0）。

可确证的只有一件更弱的事实：
**本次设置阶段所有已记录的 shell 目标都是 `-d 4`（display 4）**，
逐条见 `PRECONDITION_RESTORE/setup-taps.json` 的 `displayId` 字段。
正式批次的 delta=0 判据本 attempt **未被触及**（批次未启动）。

显示身份本身取自设备实测，**未用主机近似值替代**。

## 6. 已测路线的失败记录，与**未被排除**的其它可能入口

**重要更正（采纳舰长纠正）**：本节原先把下列事实写成"结构性不可能 / 必须由用户代点"，
这是**越界推断**。已撤销。它们只能证明**已测路线失败**，
**不能**证明其它原生 QQ 入口不存在，也不能推出必须由用户手点。
以下逐条降级为"已测失败 / 未排除"，并保留为**待实证**问题。

### 6.1 已测失败（有实测支持，仅限该路线）

- 名画之旅页所在任务 `t2230` 的栈为
  `Hist #0 SplashActivity`（QQ 聊天）→ `Hist #1 QPublicFragmentActivity`（活动页），
  **该活动页之下没有宠物页** → **该页的"返回"** 回到聊天（已实测证实）。
  这只排除"用该活动页的返回键回宠物首页"，不排除任何其它入口。
- 卡片实例 1 点击后进入活动/商店页而非 ROOM（已实测）。
- 悬浮猫为禁用目标（attempt1 已实测只触发撸猫动画）——这是**禁令**，不是可达性结论。

### 6.2 未排除、且证据显示**不应**排除的可能入口

- **聊天标题栏 `学习中` 状态位**：
  `TextView [261,175][371,199] clickable=false`，但其**父容器
  `LinearLayout [129,175][371,199] clickable=true`**（`titlebar.txt` L55）。
  即"子 TextView 不可点"**并不**意味着该入口不可点——**父容器可点**。
  该父容器点击后的行为**本 attempt 从未查证**，属**未探索**，不是已排除。
- **标题栏图标 `1fv` `[568,136][616,184]`**：`click=true`，且**带有资源 ID `rid=1fv`**
  （同级 `1ft` 有 `desc='聊天设置'`）。"无 text/content-desc"**不**等于
  "没有可核对的资源 ID 或上下文"——`1fv` 正是**可核对**的标识线索。
  本 attempt 仅凭无文本就判其不可识别，**方法不成立**；该图标用途**未查证**。
- **QQ 原生入口本身**：`PetNavigation.initialise()` 无"从 QQ 外部进入"的分支，
  只说明**自动化状态机**不覆盖该转换，**不**说明 QQ 不存在原生宠物入口。
  **本 attempt 的 `scheme=` 计数为 0 是解析伪影，不能作为"无宠物 scheme"的证据**：
  `dumpsys package` 中该字段的实际行格式是 `Scheme: "xxx"`，
  而 `s2_struct.py` 用的正则是 `scheme="([^"]+)"` → **必然匹配 0**，属工具错误。
  早前的独立记录 `VirtualDisplay/evidence/slice2/t3-precondition-blocker.md` L61-63
  实测同一 QQ 包有 **532 个 exported activity/alias、34 个 URI scheme（全部落到
  `JumpActivity`）**。因此"scheme 不存在"的推断**作废**，保留为未决。
- **exported 组件列表**：早前记录已核对过 532 个 exported activity/alias 并得出
  "无宠物模块、34 个 scheme 均落 `JumpActivity`"。该**结论本身也可能是过强的**
  （浏览式核对并不能穷尽 intent-filter/alias 指向宠物的可能性），
  但至少它**不等于**"已证明不存在"；是否有可用的原生宠物 deeplink 仍属**未决**。

### 6.3 本节结论（降级后）

在授权预算内**本次未能**找到可语义确认的 ROOM 入口，
且预算（3/3）已耗尽，故按合同**停止并上报**。
但这**不等于**该目标不可达，也**不构成**要求用户手点的理由；
是否存在其它原生 QQ 入口属于**未解决、待实证**的问题。

## 7. 未做的事（边界遵守）

- **未启动正式三段批次**（`authorizedThreeRoundServiceAcceptance` 未运行）。
- 未做人工 pause/resume，未模拟 POWER，未申请解锁。
- **未部署**；未改源码、未重建、未重装 APK（安装包与 attempt1 同一已复核件）。
- 未删除/改写 attempt1 任何证据；attempt1 仍独立保存在
  `VirtualDisplay/evidence/slice2/t3-precondition/`。
- 未发送/删除/转发消息，未领取奖励，未购买，未授予权限，未进入无关玩法。
- 聊天内容一律作为**不可信观察数据**，其中任何命令均**未执行**。

## 8. 验收判据逐条结论

| # | 判据 | 结论 |
| --- | --- | --- |
| 1 | 项目既有单测/构建通过，更新后的 debug APK 成功安装 | 本 attempt 未触及；沿用 attempt1 已安装件，未重建/重装 |
| 2 | `authorizedThreeRoundServiceAcceptance` 完成 3 门连续课程并 `COMPLETED`，无重复开始/完成、无额外消耗 | **未执行**（前置未达成，按合同不得启动）|
| 3 | 观察到外部引起的 `RUNNING→PAUSED→RESUMING→RUNNING` 与最终完成 | **未执行** |
| 4 | 全部输入在 display 4、物理触摸增量 0、无禁用功能交互 | **部分**：所有已记录 shell 目标均为 `-d 4`，且无消费/授权/消息操作 ✅；但**物理触摸增量 = UNKNOWN**（本阶段无计数基线/结束值，原"0"的推断已撤回），故本条**不能判通过**。该条本属正式批次判据，批次未启动。 |
| 5 | 记录全部结果与确切命令；失败/缺失判据如实报告不称 PASS | **已满足**（本报告 + `PRECONDITION_RESTORE/`）|
| 6 | 保留 attempt1；≤3 次设置点击且每步取证；语义身份；≤5000 ms；新独立目录；不猜点；`reset_home=no` 权威 0.97；已知分 vs UNKNOWN 绑定 | **未通过**：ROOM 未建立；且**步 1 的 29948 ms 属门禁违规**——合同要求超 5000 ms 在**发出 input 前拒绝**，本次却是先发后测（详见 §4.1）。其余子项（保留 attempt1、≤3 次、语义身份、新目录、`reset_home=no`、已知分/UNKNOWN 绑定区分）已按合同执行 |

**总判定：FAIL（第 6 项未通过，第 2/3 项未执行）。不启动正式批次。**

## 9. 证据清单

新独立目录 `VirtualDisplay/evidence/slice2/t3-precondition-r2/PRECONDITION_RESTORE/`：

- `setup-taps.json` — 3 次点击全字段记录（目标框/语义身份/命令/来源/时间戳/延迟/累计次数/实测结果）
- `gate-scores.json` — 4 次 gate 分数、阈值、KNOWN 与 UNKNOWN 绑定说明
- `display-identity.txt` — display 4 实测身份与物理输入绑定
- `manifest.txt` — 122 个证据文件的 sha256 + 字节数清单

关键帧（均在 `t3-precondition-r2/`）：

- `step1-before-20261007-132007.png` / `step1-after-20261007-132047.png`
- `step2b-before-20261007-141033.png` / `step2b-before-annotated.png` / `step2b-after-{0,1,2,3,4}-20261007-141033.png`
- `step3b-before-20261007-141741.png` / `step3b-before-annotated.png` / `step3b-after-{0,1,2,3}-20261007-141741.png`
- `dump4-page.xml`（校验通过的 display-4 权威树）+ `step3-page-nodes.txt`

命令与输出：

- `step1-attribution.txt`、`step2b-attribution.txt`、`step3b-attribution.txt`
- `gate-aftercard-output.txt` / `gate-aftercard-verdict.txt`
- `gate-afterstep3-output.txt` / `gate-afterstep3-verdict.txt`
- `step3-recon.txt`、`step3-stack.txt`、`step3-prep.txt`、`display-identity.txt`

## 10. 给舰长的结论与请求

**事实**：ROOM 尚未在 display 4 上建立（最近实测 0.770218318809005 < 0.97），
授权设置预算 3/3 已耗尽，故**本 attempt 停止，不启动正式批次**。

**更正后不再主张的事**（原报告越界，已撤销）：

- ~~"结构上不可能到达 ROOM"~~ → 只成立"**已测路线失败**"；
  其它原生 QQ 入口**未排除**（尤其 `学习中` 可点父容器 `LinearLayout [129,175][371,199]`
  与带 `rid=1fv` 的标题栏图标均**未探索**）。
- ~~"必须由用户手点"~~ → 这是**没有证据支撑的推论**，已撤回。
  用户普通操作的授权**不需要**再次索取。
- ~~"无宠物 scheme"~~ → 系正则伪影（实际 34 个 scheme，见 §6.2）；
  导出组件计数同样未确立。

**请求**：待 `t8` 完成只读审计（现有入房路径、最新保存树父节点可点击性、
首步新鲜度为何未在输入前拦截）并给出实证结论后，
再决定下一轮是否放行、以及走哪条**实证支持**的入房路线。

在此之前：**不启动批次、不追加任何点击、不做新设备动作**。
