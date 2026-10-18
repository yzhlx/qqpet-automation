# Gate 3B 断点恢复与设备验收报告

日期：2026-10-14 → 2026-10-15
项目：`C:\Users\lu158\Desktop\dsh插件\养宠自动化`
仓库：`VirtualDisplay`，分支 `feature/qqpet-mvp-session`，HEAD `0d2fab0`（未变）
目标设备：`DKS9K23420020242` — HUAWEI FOA-AL00 / Android 12 / 物理 1084×2412@480dpi

---

## 1. 状态表

| # | 项 | 状态 | 证据 |
|---|----|------|------|
| 1 | 项目路径与 Git | **PASS** | 仓库 `VirtualDisplay`，分支 `feature/qqpet-mvp-session`，HEAD `0d2fab0`；远端 `github.com/Ynkcc/VirtualDisplay` 已归档只读（push 403），提交仅在本地分支 |
| 2 | Shizuku Binder | **PASS** | `Test-QQPetDevice.ps1` `shizuku-binder` PASS — app 侧授权文案「后台截图与输入已授权」；`shizuku_server` PID 19724（shell 身份）在运行 |
| 3 | Shizuku 应用授权 | **PASS** | `app-permission` PASS；设置页「Shizuku 权限 / 后台截图与输入已授权 / 已授权」；Manager UI 自报「已授权 1 个应用」 |
| 4 | UserService | **PASS** | `userservice` PASS — 虚拟屏控制台「特权服务: 已连接」 |
| 5 | VirtualDisplay 创建 | **PASS** | `virtualdisplay` PASS — 「显示器 #4 / Shizuku_VD_1791990984016 / 720 × 1600 (竖屏) / 已连接」；`dumpsys display` 只有 `displayId=0` 与 `displayId=4` |
| 6 | 截帧与 Profile 校验 | **PASS** | `screencap` PASS — `screencap -p -d 4` 成功，PNG IHDR 解出 **720×1600**，与 `PetProfile`（720×1600@320）完全一致 |
| 7 | 自动恢复机制固化 | **PARTIAL** | 检测与报告路径已固化并实测 PASS；`-Recover` 自动启动分支**本轮未执行**（设备健康，无需启动），其 PASS 尚未在真机取得 |
| 8 | Gate 3B | **PASS** | C1 六项全部实测；C2 四项全部取得运行证据；C3 未取得识别失败证据故不提出校准需求 |
| 9 | Gate 3C | **NOT AUTHORISED** | 未获用户批准。**未启动 QQ 真实打工、未点「去打工」、未消耗金币/道具、未进行真实收益操作** |

---

## 2. 关键真实运行证据及保存位置

全部证据位于 `VirtualDisplay\deliverables\device-precheck\`（该目录已被 `.gitignore` 排除，不入库）：

| 文件 | 内容 |
|------|------|
| `precheck-20261015-005035.txt` | `Test-QQPetDevice.ps1` 完整 7/7 PASS |
| `precheck-20261015-005158.txt` | 第二次连续运行，同样 7/7 PASS（稳定性确认） |
| `gate3b-c2-hometab-20261015-010136.txt` | C2 四项运行证据，含「开始托管」按钮 `enabled=true` 的实际节点属性 |

预检脚本直接读取的内容：

- Shizuku Manager：`pm path moe.shizuku.privileged.api` → `/data/app/~~jxjMK1IhpOfRAok6B4jtPQ==/moe.shizuku.privileged-api-a5rqNkx2rPn9P3UwtsnGCg==/base.apk`，版本 `13.6.0.r1086.2650830c`
- app 侧授权：设置页 `后台截图与输入已授权` + `已授权`
- UserService：控制台 `特权服务: 已连接`
- VD：`显示器 #4`、`Shizuku_VD_1791990984016`、`720 × 1600 (竖屏)`、`已连接`
- 截帧：设备侧 `od -An -tu1 -j16 -N8` 解 PNG IHDR，得十进制字节 `0 0 2 208 0 0 6 40` → 大端拼出 **720×1600**
- 开始托管按钮：`class=android.widget.TextView text="开始托管" enabled="true" bounds="[443,1813][642,1885]"`
- 原 blocker 文案「未找到已托管的 720×1600 虚拟屏…」已从 dump 中消失

---

## 3. 本次新增或修改的文件

新增：

- `VirtualDisplay\tools\device\Test-QQPetDevice.ps1`（580 行）— 设备预检 + Shizuku 自动恢复入口
- `VirtualDisplay\tools\device\README.md` — 项目内维护方法（**仅项目内，未写任何全局规则/Skills/跨项目模板**）

未修改任何 Kotlin 源码或测试。`SessionEvent.kt`、`PetWork*.kt`、`Work*.kt`、`PetWorkFlowTest.kt`、`PetWorkGateTest.kt`、`docs/P5-03-work-execution-loop.md` 保持前序会话的未提交状态，本轮未触碰。

已清理：`VirtualDisplay\build-ascii\` 下 39 个一次性调试脚本与 2 张设备截图（含 `vd-frame.png`、`gate3b-appui.png`）全部删除，目录已移除。

---

## 4. 构建与测试结果

```
:app:testDebugUnitTest   BUILD SUCCESSFUL in 4m 26s
17 classes / 244 tests / 0 failures / 0 errors / 0 skipped
```

其中 `DialogDigitReaderTest` 8 项全过 —— 前序记录的 8 项失败**未复现**（fixture SHA256 `0FFB9EBD…5444F06` 未变），按约定**不宣称修复**，仅记录为 NOT REPRODUCED。

pwsh 末尾仍报 `[exit code: 1]`，这是已知现象，以 `BUILD SUCCESSFUL` 与 XML 汇总为准。

---

## 5. 是否引入跨项目依赖

**否。**

- 未读取、修改或引用其他项目的需求、架构、路线图、执行状态
- 未访问其他 Git 仓库
- 未修改 DSH、Codex、WorkBuddy 等工具的全局规则、全局 Skills 或共享 Agent 配置
- 本次新增的维护规则只写在 `VirtualDisplay\tools\device\README.md`（项目内部）
- 未引入新的 Agent 调度系统、云端服务、路由架构或项目管理模块
- 未为预检开发新的任务调度器、插件系统或通用 Android 管理框架；`tools/` 下原有 `digit-recon` / `legacy-scan` 两个 Python OCR 工具未动
- 未因其他项目状态调整本项目目标

---

## 6. 职业小镇自动打工主线进度

| 阶段 | 状态 |
|------|------|
| P5.1 职业小镇采集 | 已完成并验收 |
| P5.2 收益目录 + 最优化 | 已完成并验收 |
| P5.3 `PetWorkFlow` + 49 个新测试 + 244/244 | 已完成，`docs/P5-03-work-execution-loop.md` 已写 |
| Gate 3B 真实验收 | **本轮通过** |
| 步骤 2 真机读取器接入（`WorkPageReader` 10 个方法的真实实现） | **未开始** |
| 步骤 3 Gate 3B 真机预览（竹影武馆 / 帮武馆跑腿 / 10min，停在「去打工」之前） | **未开始** |
| 步骤 4 预览结果验证 + 读取器测试 + 全量回归 | 未开始 |
| Gate 3C 真实打工启动 | **NOT AUTHORISED** |

`WorkPageReader` 当前仍是**零真实实现**的接口（10 个方法全部无实现），`PetWorkFlow` 目前只能靠测试替身驱动。这是主线的下一个断点。

Gate 3C 已知 Gap（本轮不修，Gate 3C 才需要）：`mustNotTapAgain` 与 `WorkSessionLedger` 均未持久化，「进程重启后必须能识别可能已经提交的工作」尚未满足。

---

## 7. C3 ROI 检查结论

未做校准，也未提出校准需求。

- ROI 基于 VirtualDisplay（720×1600），不基于手机物理屏。目标机物理屏 1084×2412 **不会**触发 ROI 重标定。
- VD 尺寸由 app 固定创建为 720×1600@320，与 `PetProfile` 的 `require` 校验一致，`PetProfile.calibrated()` 的 ROI 继续有效。
- 本轮对 VD 取过一帧（`screencap -p -d 4`），VD 上 `rootTasks=0`、`mCurrentFocus=null`，即 VD 上没有任何 Activity，画面为空白。**没有可供识别的内容，因此没有取得任何识别失败证据。**
- 按任务书约定：**只有取得实际识别失败证据后才允许提出局部校准需求**。条件不满足，故不提出。
- 本轮未主动扩大到职业小镇真实打工操作。

---

## 8. 脚本修复记录（`Test-QQPetDevice.ps1`）

本轮从「完全无法执行」修到 7/7 PASS。根因与修复：

1. **PowerShell 5.1 字符串拼接当算术**：`"18" + "162"` 得 `"18162"`，`/2` 得 9081，导致 back 按钮 tap 坐标变成 `9081 52124`。修复：每个操作数先 `[int]`。这是本轮最关键的 bug。
2. **`Split-UiNodes` 非幂等**：`Get-UiDump` 已分割数组，再 `join + split '<node '` 会丢分隔符，整个文档塌成一个元素，所有 label 查找静默失败。修复：多元素输入直接透传。
3. **重复定义 `Invoke-TapLabel` / `Split-UiNodes`**：后者覆盖前者，行为不可预期。删除重复定义。
4. **`od -An -tu4` 字节序错乱**：该设备 od 版本输出字节反转的 word（720 → 3489792000）。改用 `od -An -tu1` 取十进制字节，本地拼大端。
5. **od 输出是一行多列**：对整行匹配 `^\d+$` 得 0 个字节。改为 `-split '\s+'` 后过滤。
6. **`Get-BoundsOf` 匹配整个节点字符串**：会命中 `resource-id` / `content-desc` / `class`，产生完全错误的中心点（542,1093）。改为只匹配 `text="..."` 属性。
7. **Compose 语义合并导致同文本多节点**：选**面积最小**的非退化框（真正画字的叶子），避免点到部分滚出屏幕的祖先。
8. **底栏 band 混入页面容器**：按「高度 ≤140 且宽度 ≤320」筛出真正的 tab 单元格，再按 x 排序取第 N 个。等分算式是错的（栏有水平 padding）。
9. **`Test-OnSettingsTab` 判据需要滚动才可见**（`权限与环境`）：改用顶栏标题 `text="设置"` 且 `T < 400`。
10. **设置页长滚动列，目标行常不在可见区**：新增 `Show-SettingsTop`，进入设置页后 4 次向上 swipe 到顶。
11. **`Show-PetApp` 的 back 循环失效**：上面第 1 项（坐标拼接 bug）导致 tap 打到屏幕外，页面从不变化。修 1 后即恢复。
12. **`Get-BottomTabCentre` 未接收 Dump**：改为从参数传入，不再用 `wm size` 推算式。
13. **`virtualdisplay` 详情输出整个 XML 节点**：新增 `Get-NodeText` 只取 `text` 属性。
14. **`.ps1` 编辑后丢失 BOM**：5.1 用 GBK 读，路径中文乱码 → adb not found。每次编辑后必须重写 UTF-8 BOM。
15. **`$ErrorActionPreference='Stop'` 把 adb native stderr 变终止错误**：`Invoke-Adb` 内临时设为 `'Continue'`。
16. **`dumpsys display` 长输出在 PowerShell 里丢 display 4**：改设备侧 `grep -o "displayId=[0-9]*" | cut -d= -f2 | sort -u`。
17. **`"attempt $i:"` ParserError**：`$i:` 被当 drive 限定符，写 `${i}`。
18. **CJK 字面量破坏解析**：文件写 UTF-8 BOM，关键匹配用 `[char]0xXXXX` 拼接。

脚本内注释已列出「Explicitly NOT used」清单：`start.sh`、`sh` 前缀、硬编码 APK 目录、`pkill -f shizuku_server`、force-stop、清数据、改系统安全配置、`service check shizuku` 作唯一依据。

---

## 9. 下一步需要人工授权的具体内容

**请求授权：Gate 3C —— 职业小镇真实打工（单次、限定范围）。**

范围与边界（逐条需用户明确批准）：

1. 允许把 QQ 宠物 app 启动到已创建的 VD（display 4）上，用于读取职业小镇页面。
2. 允许接入 `WorkPageReader` 真实实现并做**只读**页面采集（`竹影武馆` / `帮武馆跑腿` / 10min）。
3. **仍然不点「去打工」**：预览停在确认页，「去打工」按钮无二次确认，一点即真实开始。
4. 不消耗金币/道具，不进行真实收益操作，不自动跨 Gate。

若要进一步授权真实打工启动，需要额外明确：

- 授权次数上限（建议 1 次）
- 指定工作项（建议 `竹影武馆` 或 `帮武馆跑腿`）
- 点击后无法确认是否已启动时，**不得再次点击**，进入状态核查流程
- 不因倒计时归零就认定奖励到账
- 不把失败的工作记录为成功完成，不记录虚假金币/工分

另外需要用户知晓的两件事：

- **Shizuku 授权状态**：当前已授权且正常。若重启设备后 app 报未授权，需用户在 Shizuku Manager UI 的「已授权的应用」里手动批准，脚本不会绕过。
- **远端只读**：`github.com/Ynkcc/VirtualDisplay` 已归档，push 会 403。所有提交只存在于本地分支 `feature/qqpet-mvp-session`。如需备份请用户决定如何处理。

---

## 10. 结论

```
Gate 3B   PASS
Gate 3C   NOT AUTHORISED
STOPPED BEFORE REAL WORK START
```

Gate 3B 的全部验收条件已实际满足并取得运行证据。未启动任何真实打工操作。等待用户对 Gate 3C 的明确批准。
