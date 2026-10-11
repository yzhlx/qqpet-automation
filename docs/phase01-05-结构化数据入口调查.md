# 兑换弹窗结构化数据入口调查报告

> 日期：2026-10-11　性质：**只读**
> 结论：**兑换弹窗不存在任何可访问的结构化数据入口（原生文本 / WebView / 无障碍节点）。必须走视觉方案。**

---

## 第一阶段结论速览

| 候选路径 | 可用？ | 实测证据 |
|---|---|---|
| A. 原生文本读取（无障碍节点） | ❌ **不可用** | `uiautomator` 只能看到我们自己的 app |
| A2. WebView 调试/内容读取 | ❌ **不可用** | 游戏是 QQ 小程序容器，非 WebView 调试可达 |
| A3. Shizuku/root 级 view introspection | ⚠️ 无现成能力 | 现有代码**明确不使用** Accessibility（`HeadlessDisplayCapture.kt:24`） |
| B. 视觉模板确认 | ✅ **唯一可行** | 见下方 |

---

## 1. 无障碍节点：实测不可用

### 1.1 `uiautomator dump` 看不到游戏
命令：`adb shell uiautomator dump /sdcard/d4.xml`

结果（默认 display）：
```
packages: com.ynk.virtualdisplay
nodes: 51
text: 宠物托管 / 体力 / 81 / 清洁 / 72 / 心情 / 19 / ...
```

**只包含我们自己的 app**，物理屏 1084×2186。

### 1.2 显式指定 display 4 仍然无效
命令：`adb shell uiautomator dump --display 4 /sdcard/d4b.xml`

结果：
```
dumped to: /sdcard/d4b.xml   (19,594 B)
packages: com.ynk.virtualdisplay      ← 仍然只有我们自己的 app
nodes: 55
```

### 1.3 决定性验证：即使把 QQ 拉起来也看不到
命令：`am start -n com.tencent.mobileqq/com.tencent.robot.adelie.AdelieFragmentActivity`
然后 `uiautomator dump /sdcard/qq.xml`

结果：
```
size: 38738        ← 与 1.1 完全相同的文件
packages: com.ynk.virtualdisplay
```

**QQ 活动已启动，dump 仍是同一个文件 → 游戏的 view tree 根本没有暴露给无障碍框架。**

---

## 2. 为什么会这样（架构原因）

### 2.1 游戏运行在 QQ 的小程序容器中
`dumpsys activity activities` 显示：
```
ActivityRecord{... com.tencent.mobileqq/com.tencent.robot.adelie.AdelieFragmentActivity t2245}
displayId=4
```
- 类名 `com.tencent.robot.adelie.AdelieFragmentActivity` = QQ 的**Adelie 小程序/机器人容器**
- 游戏画面由该容器**自绘**（Surface/Canvas），**不是标准 Android View 树**
- 因此不产生无障碍节点

### 2.2 QQ 窗口本身在 display 0 上无 surface
```
Window{40a9edd ... AdelieFragmentActivity}
  mDisplayId=0
  mHasSurface=false
  isOnScreen=false
```
宿主窗口在 display 0 但没有可见 surface —— 实际渲染输出到虚拟屏 display 4。

### 2.3 项目本就不依赖无障碍
`HeadlessDisplayCapture.kt:24` 注释明确：
```
Headless capture for one VirtualDisplay. No MediaProjection / Accessibility / network.
```
**现有架构刻意不使用 Accessibility**，走的是 ImageReader + Shizuku screencap 回退。

---

## 3. Shizuku / root 路径评估

| 检查 | 结果 |
|---|---|
| `start.sh`（Shizuku 启动脚本） | `No such file or directory`（新版 Shizuku 无此文件） |
| 现有代码是否有 view introspection | **无** |
| `dumpsys accessibility` | 已启用服务中**无**与 QQ 相关者；QQ 未注册任何无障碍服务 |

Shizuku 提供的是**系统 API 代理**（可注入 MotionEvent、可 screencap），
但**不提供目标 app 的内部 View 树**。游戏的绘制发生在容器内部，
即便有 root，也没有"读取小程序内部文本"的通用接口。

**结论：A 路线（原生文本）彻底不可用。**

---

## 4. 那么"结构化数据"是否存在于别处？

已检查、均不可用的候选：
| 候选 | 结果 |
|---|---|
| QQ 的 UI dump | 只含我们自己的 app |
| WebView 调试端口 | 游戏非 WebView，无调试入口 |
| `dumpsys window` 中的文本 | 只有窗口元数据，无商品/价格 |
| `dumpsys activity` | 只有 ActivityRecord，无内容 |
| logcat 中的游戏日志 | 需另行实测（见 §6 待办） |

---

## 5. 唯一可行路线：视觉方案（B）

因为 A 不可用，只能回到视觉确认。但**范围可以大幅收窄**（见下一份文档）。

关键点：**不需要读任意数字**，只需要确认**有限几种固定状态**。

### 为什么范围能收窄
兑换食物弹窗的商品与价格是**固定**的：
| 商品 | 单价 | 效果 |
|---|---|---|
| 饼干 | 5 金币 | 体力+10，心情+1 |
| 虾 | 20 金币 | （更高） |

数量有步进器，但**支付金额 = 单价 × 数量**，且**默认数量固定**。

因此可以用**有限个模板**确认：
- "当前选中的是饼干，单价 5"（模板匹配商品行）
- "数量 = N"（模板匹配步进器数字，N 取值有限）
- "合计 = 5×N"（模板匹配支付按钮文本）

**而不是造一个通用 OCR。**

---

## 6. 尚未验证（下一步实测）

| 项 | 方法 | 风险 |
|---|---|---|
| 洗澡兑换弹窗结构 | 需先让清洁度不足并触发 | **完全未知，不得假定与食物弹窗一致** |
| logcat 是否有游戏侧价格信息 | `adb logcat` 过滤 QQ | 可能性低 |
| 步进器是否可调数量 | 需实机点击（不支付） | 可采集不同金额帧 |

---

## 7. 对第二阶段的影响

由于 A 不可用，**第二阶段只能选 B 或 C**：

| 选项 | 评估 |
|---|---|
| A. 原生文本读取 | ❌ **已排除**（实测不可用） |
| B. 有限固定商品与金额的视觉校验 | ⚠️ 可能可行，但需**多张不同金额帧**验证 |
| C. 仅对必要字段新增数字识别 | ⚠️ 需新增金色模板（见上一份报告） |

**B 与 C 的差别**：B 确认"是不是饼干+5金币"这类**有限状态**；
C 读"实际金额数字"。B 更省，但**若数量可变，B 无法覆盖所有金额**。

> **关键待定问题**：能否把数量**固定为 1**？
> 若可以，则金额只有 5 / 20 两种，**B 完全够用，无需任何数字识别**。
> 这是成本最低的路径，需要实测步进器能否设为 1。
