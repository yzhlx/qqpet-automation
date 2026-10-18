# 设备预检与 Shizuku 自动恢复

本目录只有一个脚本：`Test-QQPetDevice.ps1`。它是本项目**唯一**的设备启动前预检入口，
也是 Shizuku 自动恢复的入口。没有第二个平行实现。

---

## 1. 怎么跑

```powershell
$path = "<repo>\tools\device\Test-QQPetDevice.ps1"

# 每次编辑脚本后必须重写 UTF-8 BOM，否则 Windows PowerShell 5.1 会用 GBK 读文件，
# 路径里的中文会乱码、adb 直接找不到。
$raw = [System.IO.File]::ReadAllText($path, [System.Text.Encoding]::UTF8)
[System.IO.File]::WriteAllText($path, $raw, (New-Object System.Text.UTF8Encoding $true))

& powershell -ExecutionPolicy Bypass -File $path 2>&1 | Out-String
```

- 本机 PowerShell 执行策略禁止直接 `& .\xxx.ps1`，必须 `-ExecutionPolicy Bypass -File`。
- 本机**没有独立 `pwsh`**，只有 `C:\WINDOWS\System32\WindowsPowerShell\v1.0\powershell.exe`（5.1）。
- 默认设备序列号 `DKS9K23420020242`，可用 `-Device` 覆盖。
- 需要自动启动 Shizuku 时才加 `-Recover`。**设备健康时不要加**，避免不必要的启动尝试。

### 退出码

| 码 | 含义 |
|----|------|
| 0  | 全部通过 |
| 1  | 有 BLOCKED / FAIL / ABSENT 项 |
| 3  | Binder 不可用且未指定 `-Recover` |
| 5  | 启动过一次但 app 仍未授权 |
| 6  | server 活着但 app 未授权（只能人工授权） |

---

## 2. 检查顺序（固定，不要调换）

1. `device-connection` — `get-state` == `device`
2. `shizuku-manager` — `pm path moe.shizuku.privileged.api` 取 `/base.apk`
3. `shizuku-binder` — **app 侧** `Shizuku.pingBinder()`，即读 app 自己的授权文案
4. `app-permission` — 设置页「后台截图与输入已授权」
5. `userservice` — 虚拟屏控制台「特权服务: 已连接」
6. `virtualdisplay` — 控制台「显示器 #N」+ 「720 × 1600 (竖屏)」+ 已连接/已就绪
7. `screencap` — `screencap -p -d <id>` 后解 PNG IHDR，确认 720×1600

### 为什么第 3 项不能省

`service check shizuku` **不能**作为存活依据：shell 域（uid 2000）被 SELinux 拒绝
`{ find } name=shizuku`，即使 `shizuku_server` 正常运行也报
`Service shizuku: not found`。脚本仍然记录它的输出作为证据，但**不拿它当门禁**。

---

## 3. Shizuku 自动恢复（`-Recover`）

只在 `shizuku_server` **没有**运行时触发，且**最多启动一次**：

```
pm path moe.shizuku.privileged.api      -> /base.apk
替换路径为 /lib/arm64/libshizuku.so
ls -l                                    确认文件存在
直接执行该 .so
```

启动后以 2000 / 3000 / 5000 ms 三次复查 app 侧 Binder，**不重跑启动**。
失败即停止并报告具体错误。

### 明确不做的操作

- 不用旧版 `start.sh`（此版本不存在）
- 不对 `libshizuku.so` 加 `sh` 前缀
- 不硬编码 APK 安装目录
- 不用 `pkill -f shizuku_server`
- 不强制停止 / 卸载 Shizuku
- 不清除 Shizuku 应用数据
- 不修改系统安全配置
- 不用 `service check shizuku` 作为唯一存活依据
- 不无限循环重试

### 授权不可绕过

服务启动和授权是两件事。Binder 正常但未授权时，脚本报
`BLOCKED` + `exit 6`，提示去 Shizuku Manager UI 的「已授权的应用」里批准。
**严禁**用 ADB、自动点击、appops 或其他方式绕过用户授权确认。

---

## 4. UI 导航的几个坑（改脚本前先读）

| 现象 | 原因 / 做法 |
|------|-------------|
| `am start` 不能退出控制台 | 重复发 launch intent 只是把已有 Activity 提到前台，**不会** unwind app 内 overlay |
| `keyevent 4` 完全不可用 | BACK 会连整个 task 一起 pop，露出任务栈里宠物 app 下面的 Shizuku，宠物 app 直接消失。必须点控制台页左上角返回按钮（节点约 `[18,104][162,248]`） |
| Compose TextView 的 bounds 是 `[0,0][0,0]` | 底栏 tab 的文字框退化，必须读**父容器**（clickable 行）。tab 不是均分，栏有水平 padding，等分算式是错的 |
| 底栏 band 里还有别的 clickable | 页面底部容器也在同一 band 且更宽更高。按「高度 ≤140 且宽度 ≤320」筛出真正的 tab 单元格 |
| 同一个 text 出现在多个节点 | Compose 会把文本合并到祖先语义节点。`Get-BoundsOf` 选**面积最小**的非退化框（真正画字的叶子） |
| `Where-Object` 匹配失效 | `uiautomator dump` 输出是**单行** XML，必须先 `-split '<node '` |
| `$Dump.Count` 变成 1 | `Get-UiDump` 已经分割过，**不能再** join + split（会丢 `<node ` 分隔符，整个文档塌成一个元素）。`Split-UiNodes` 因此对多元素输入直接透传 |
| `"18" + "162"` 得到 `"18162"` | PowerShell 里 `+` 对字符串是拼接。坐标运算必须先 `[int]` 每个操作数 |
| `"$taskW" + 'x' + "$taskH"` | 5.1 会把 `"${taskW}x${taskH}"` 解析坏，尺寸标签必须显式拼接 |
| XML 片段含引号/反斜杠 | 不可插值进双引号字符串，用 `[string]::Join` |
| CJK 字面量破坏解析 | 文件写 UTF-8 BOM，关键匹配用 `[char]0xXXXX` 拼接 |
| `$ErrorActionPreference='Stop'` 把 adb 的 native stderr 变终止错误 | `Invoke-Adb` 内部临时设为 `'Continue'`；`2>&1` 和 `2>file` 都挡不住 |
| `od -An -tu4` 数字是字节反转的 | 该设备 od 版本输出字节序错乱的 word。必须用 `od -An -tu1` 取**十进制字节**再自己拼大端 |
| od 输出是一行多列 | 不能对整行匹配 `^\d+$`，要 `-split '\s+'` 后再过滤 |
| `dumpsys display` 长输出丢 display 4 | 设备侧解析：`grep -o "displayId=[0-9]*" \| cut -d= -f2 \| sort -u`，只回传 id 列表 |
| `"attempt $i:"` 报 ParserError | `$i:` 被当成 drive 限定符，写 `${i}` |

---

## 5. VirtualDisplay 只能由 app 创建

VD **只能**由本 app 的 Shizuku 特权服务创建：

```
ShizukuDisplayRepository.createVirtualDisplay
  -> DisplayUserService.createVirtualDisplay
    -> displayManager.createNewDisplay
```

以下三条路径**均已验证失败**，不要再试：

- `settings put global overlay_display_devices` — 产生不可截帧的 display 3
- `service call DisplayManager 43` — 服务不存在
- `adb root` — `adbd cannot run as root in production builds`

手工创建的路径是：设置 › 虚拟屏控制台 › 新建显示器 › 「手机 720p」预设 › 高度 1280 改 1600 ›
创建并启动。脚本的 `-CreateDisplay` 参数封装了同一组操作。

---

## 6. 与 PetProfile 的关系

`PetProfile` 硬固定 720×1600@320（`PetProfile.kt` 默认值 + `require` 校验）。
ROI 基于 VirtualDisplay，**不是手机物理屏**。

目标机物理屏是 1084×2412@480。物理屏变化**不会**触发 ROI 重标定——
VD 尺寸由 app 固定，与物理屏无关。

---

## 7. 证据

每次运行写一份 `..\..\deliverables\device-precheck\precheck-<yyyyMMdd-HHmmss>.txt`。
有 BLOCKED / FAIL / ABSENT 时 `RESULT: BLOCKED` 并不产生退出码 0。

最近两次连续运行（2026-10-15）均为 `RESULT: PASS`，7/7 全过。

---

## 8. 已知限制

- `managedDisplayIds`（app 侧托管集合）没有单独读出的步骤。设置页显示
  `display 4 · 720×1600 · 320dpi → 已就绪` 且「开始托管」按钮已启用，
  可强推断该 VD 已被托管，但这是间接证据。
- 恢复分支（`-Recover`）在本次验收中**未执行**（设备健康，无需启动）。
  脚本逻辑已按任务书实现，但它的 PASS 尚未在真机上取得。
- 脚本只做非消耗性检查，不点游戏内任何按钮。
