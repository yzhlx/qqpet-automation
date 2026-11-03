package com.ynk.virtualdisplay.automation

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.ynk.virtualdisplay.data.model.ShizukuState
import com.ynk.virtualdisplay.data.repository.IDisplayRepository

enum class DiagnosticLevel(val label: String) {
    BASE("基础状态"),
    CAPABILITY("实际能力"),
    TARGET_APP("QQ 准备状态"),
}

data class DiagnosticItem(
    val level: DiagnosticLevel,
    val title: String,
    val passed: Boolean,
    val message: String,
    val remedy: String? = null,
)

data class DiagnosticReport(
    val timestamp: Long = System.currentTimeMillis(),
    val androidVersion: String = "Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")",
    val manufacturer: String = Build.MANUFACTURER + " " + Build.MODEL,
    val items: List<DiagnosticItem>,
) {
    val allBasePassed: Boolean get() = items.filter { it.level == DiagnosticLevel.BASE }.all { it.passed }
    val allCapabilitiesPassed: Boolean get() = items.filter { it.level == DiagnosticLevel.CAPABILITY }.all { it.passed }
    val isReadyForAutomation: Boolean get() = allBasePassed && allCapabilitiesPassed
    val firstBlocker: DiagnosticItem? get() = items.firstOrNull { !it.passed }
}

/**
 * 实际运行环境与能力分层诊断探针。
 * 覆盖：
 * 1. 基础状态：工具安装、服务可达性与授权
 * 2. 实际能力：服务握手、虚拟屏创建、截图捕获；自检过程及时释放测试虚拟屏，不留多余垃圾
 * 3. QQ 准备状态：区分「工具设置完成」与「QQ 宠物准备完成」
 */
class EnvironmentDiagnostic(
    private val packageInstalled: (String) -> Boolean,
    private val repository: IDisplayRepository,
) {
    companion object {
        fun defaultPackageChecker(context: Context): (String) -> Boolean = { pkg ->
            runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess
        }
    }

    constructor(context: Context, repository: IDisplayRepository) : this(
        packageInstalled = defaultPackageChecker(context),
        repository = repository,
    )
    suspend fun runCheck(shizukuState: ShizukuState): DiagnosticReport {
        val items = mutableListOf<DiagnosticItem>()
        // ── 第一层：基础状态 ──
        val shizukuInstalled = packageInstalled("moe.shizuku.privileged.api")
        items.add(
            DiagnosticItem(
                level = DiagnosticLevel.BASE,
                title = "Shizuku 安装状态",
                passed = shizukuInstalled,
                message = if (shizukuInstalled) "已安装" else "未安装 Shizuku",
                remedy = if (!shizukuInstalled) "请先下载安装 Shizuku 配套工具（可从设置攻略或官方 Release 下载）" else null,
            )
        )

        val shizukuRunning = shizukuState is ShizukuState.Ready || shizukuState is ShizukuState.PermissionDenied
        items.add(
            DiagnosticItem(
                level = DiagnosticLevel.BASE,
                title = "Shizuku 运行状态",
                passed = shizukuRunning,
                message = if (shizukuRunning) "服务已启动" else "服务未启动",
                remedy = if (!shizukuRunning) "请打开 Shizuku 应用点击「启动」，手机重启后需要重新启动" else null,
            )
        )

        val shizukuGranted = shizukuState is ShizukuState.Ready
        items.add(
            DiagnosticItem(
                level = DiagnosticLevel.BASE,
                title = "Shizuku 授权状态",
                passed = shizukuGranted,
                message = if (shizukuGranted) "已授权" else "未授权蟹啦使用 Shizuku",
                remedy = if (!shizukuGranted) "请在 Shizuku 授权窗口中选择「始终允许」，或在 Shizuku 中手动勾选蟹啦" else null,
            )
        )

        val qqInstalled = packageInstalled("com.tencent.mobileqq")
        items.add(
            DiagnosticItem(
                level = DiagnosticLevel.BASE,
                title = "QQ 安装状态",
                passed = qqInstalled,
                message = if (qqInstalled) "已安装" else "未安装手机 QQ",
                remedy = if (!qqInstalled) "请先安装并登录手机 QQ，托管需在 QQ 内进行" else null,
            )
        )

        // ── 第二层：实际能力检测 ──
        if (shizukuGranted) {
            val serviceAvailable = repository.isShizukuAvailable()
            items.add(
                DiagnosticItem(
                    level = DiagnosticLevel.CAPABILITY,
                    title = "Privileged 服务握手",
                    passed = serviceAvailable,
                    message = if (serviceAvailable) "服务通信正常" else "无法与 Shizuku 后台服务通信",
                    remedy = if (!serviceAvailable) "请在设置中点击「强制重启服务」或重新启动 Shizuku" else null,
                )
            )

            if (serviceAvailable) {
                var testDisplayId: Int? = null
                var displayCreated = false
                var captureOk = false
                try {
                    // 自有虚拟屏测试创建
                    val createResult = repository.createDisplay("crab_diag_test", 720, 1280, 320)
                    testDisplayId = createResult.getOrNull()
                    displayCreated = testDisplayId != null && testDisplayId > 0

                    if (displayCreated && testDisplayId != null) {
                        // 针对测试屏进行帧捕获验证
                        val captureResult = repository.captureDisplay(testDisplayId)
                        val frame = captureResult.getOrNull()
                        captureOk = frame != null && frame.byteCount > 0
                    }
                } catch (_: Exception) {
                    // 仅记录状态，不崩溃
                } finally {
                    // 临时测试资源必须及时释放，绝不留下多余虚拟屏！
                    testDisplayId?.let { id ->
                        repository.releaseDisplay(id)
                    }
                }

                items.add(
                    DiagnosticItem(
                        level = DiagnosticLevel.CAPABILITY,
                        title = "虚拟显示器创建",
                        passed = displayCreated,
                        message = if (displayCreated) "测试虚拟屏幕创建成功并已自动释放" else "系统限制或创建虚拟屏幕失败",
                        remedy = if (!displayCreated) "请检查系统开发者选项是否限制副屏，或重启手机后再试" else null,
                    )
                )

                items.add(
                    DiagnosticItem(
                        level = DiagnosticLevel.CAPABILITY,
                        title = "后台画面捕获能力",
                        passed = captureOk,
                        message = if (captureOk) "画面传输与帧捕获正常" else "无法读取后台显示器画面",
                        remedy = if (!captureOk) "请检查 Shizuku 权限与系统多显示器支持" else null,
                    )
                )
            }
        }

        // ── 第三层：QQ 准备状态 ──
        val qqDisplays = if (shizukuGranted && repository.isShizukuAvailable()) {
            repository.displaysHosting("com.tencent.mobileqq").getOrDefault(emptySet())
        } else {
            emptySet()
        }
        val qqRunning = qqDisplays.isNotEmpty()
        val displayListStr = qqDisplays.joinToString()
        items.add(
            DiagnosticItem(
                level = DiagnosticLevel.TARGET_APP,
                title = "QQ 宠物后台运行状态",
                passed = qqRunning,
                message = if (qqRunning) "QQ 已在后台虚拟屏 (ID: " + displayListStr + ") 中启动" else "QQ 尚未在后台虚拟屏中启动",
                remedy = if (!qqRunning) "工具设置已完成。请在主页点击「一键创建」或在后台画面中启动 QQ 宠物" else null,
            )
        )

        return DiagnosticReport(items = items)
    }
}
