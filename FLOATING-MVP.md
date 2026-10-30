# QQPet 悬浮体验 MVP

本轮目标：在不改动护理 / 学习 / 打工 / 预算 / 状态识别规则的前提下，为 QQPet 托管系统补上
"应用内悬浮虚拟屏 + 跨应用悬浮胶囊 + 共享实时预览帧"三项能力。

参考实现：Mika（https://github.com/wangzan101/Mika，Apache-2.0）。复用范围与改写说明见
NOTICE.floating-mika.md。

---

## 1. 共享实时预览帧（功能 C）

自动化链路仍然是唯一的画面来源：

    Shizuku ImageReader (HeadlessDisplayCapture)
        |-- 识别引擎：模板匹配 / OCR / 点击决策   <- 原有路径，未改
        |-- DisplayPreviewHub.publishFrom(...)   <- 新增：只读缩略副本

- DisplayPreviewHub 只接收已经捕获并校验过的帧，缩放到宽度 180 后放进 StateFlow。
- 没有任何 UI 组件调用 setDisplaySurface()，也不新建 VirtualDisplay，
  因此预览不会与自动化抢 Surface。
- 上限 5 FPS（MIN_INTERVAL_MS = 200），无订阅者时直接返回，不做任何缩放。
- 帧对象是普通堆内存 Bitmap，替换时不 recycle：Compose 可能仍在绘制上一帧，
  recycle 会直接让 UI 崩溃（真机上已验证过一次，见下）。

## 2. 应用内 PiP（功能 A）

PetAgentPipHost（app/src/main/java/com/ynk/virtualdisplay/floating/PetAgentPip.kt）

- 收起 96dp / 展开 220dp，比例固定 720:1600，图片用 ContentScale.Fit，不拉伸不裁剪。
- 点击展开 / 收起；拖动后按甩出速度投影到最近的角落，再用临界阻尼弹簧归位
  （物理参数直接复用 Mika PipPhysics）。
- 位置限制在内容区内，底部留 96dp，避开底部托管按钮。
- 只显示，不接受触摸控制虚拟屏；DisplayActivity 的完整触摸能力保持不变。
- 边框颜色跟随真实运行状态（运行中绿 / 暂停琥珀 / 失败红 / 完成蓝）。

## 3. 跨应用悬浮胶囊（功能 B）

PetCapsuleOverlay（app/src/main/java/com/ynk/virtualdisplay/floating/PetCapsuleOverlay.kt）

- TYPE_APPLICATION_OVERLAY，不需要无障碍权限。
- 40dp 圆形气泡，可拖动、左右贴边、点击展开卡片、点外部收起。
- 状态全部来自 PetManagedRuntime / PetRuntime，不新建状态机、不编造步骤。
- 卡片内容：任务名、状态、已运行时长、实时缩略图、阶段 / 步骤 / 轮次 / 下一步，
  以及「回到 QQPet」「停止托管」两个真实动作（停止走 PetAutomationService.stopIntent）。
- QQPet 自己在前台时不显示气泡（应用内 PiP 已经承担预览），退到其它 App 后自动出现。

## 4. 权限

- Manifest 增加 android.permission.SYSTEM_ALERT_WINDOW。
- 「设置 -> 权限与环境 -> 悬浮窗权限」提供授权入口。
- 未授权时托管照常运行，托管页只显示一行提示，不做任何拦截。

## 5. 真机验证（FOA-AL00，QQ 9.3.70，display 720x1600@320）

| 场景 | 结果 |
| --- | --- |
| 应用启动 / UI 渲染 | 通过，无崩溃 |
| 健康检查会话 | 通过，真实读到体力约 8、清洁约 0、心情约 10 |
| 应用内 PiP | 通过，运行中出现实时宠物画面，与「正在读取宠物状态」同步 |
| 跨应用气泡 | 通过，微信界面右上角出现绿色运行中气泡 |
| 气泡展开卡片 | 通过，显示真实缩略图 + OBSERVATION / EXCHANGE_BUY / 1-1 / 下一步 |
| 停止托管 | 通过，服务停止，胶囊随之收起 |
| 捕获来源 | SHIZUKU_FALLBACK（screencap）取到 880KB 真实帧 |

真机上修掉的两个真实缺陷：

1. ComposeView 挂在 overlay window 上时缺少 SavedStateRegistryOwner，
   AndroidComposeView 拒绝 attach，抛 IllegalStateException 闪退。
   修复：installOwners 补上 androidx.savedstate.setViewTreeSavedStateRegistryOwner。
2. 预览帧被替换时 recycle 旧 Bitmap，Compose 若仍在绘制会崩。
   修复：改为不 recycle，交给 GC。

## 6. 构建与测试

- ./gradlew :app:assembleDebug PASS。
- 单元测试：新增 PipPhysicsTest（2）与 CapsuleUiMapperTest（2），全部 PASS。
  既有 20 个测试类逐类对比基线，结果与基线完全一致（无回归）。
- Gradle 的 testDebugUnitTest worker 在本机 Windows 环境起不来（基线同样失败），
  因此用 JUnitCore 直接跑同一批 class，结论等价。

## 7. 使用方式

1. 安装后进「设置 -> 权限与环境 -> 悬浮窗权限」，授予悬浮窗权限。
2. 「托管」页启动托管任务。
3. 应用内右下角出现可拖动的虚拟屏预览，点一下展开。
4. 切到微信 / QQ / 桌面，右上角出现胶囊气泡，点开看实时状态，可直接「停止托管」。

## 8. 未做 / 已知限制

- 不支持在小窗上操作虚拟屏（第一版只看不操作）。
- 暂停 / 恢复没有可靠的用户控制 API，因此卡片里没有这两个按钮。
- 预览为 5 FPS 上限，长按拖动时不做特殊优化。
- 宠物体力耗尽时托管按钮会被既有业务规则禁用，与本轮改动无关。
- 真机验证期间为绕开签名不一致，卸载过设备上原有的同包名 App 并重装本构建；
  原私有数据已从 _device-evidence 备份恢复课程档案，预算账本与运行记录为重新累积。
