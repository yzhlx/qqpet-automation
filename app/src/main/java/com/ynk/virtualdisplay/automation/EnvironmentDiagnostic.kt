package com.ynk.virtualdisplay.automation

import android.content.Context
import android.os.Build
import com.ynk.virtualdisplay.data.model.ShizukuState
import com.ynk.virtualdisplay.data.repository.IDisplayRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    /** 能确认“正在运行”，但无法确认用户真正需要的那一步（例如宠物房间）时为 true。 */
    val unknown: Boolean = false,
)

data class DiagnosticReport(
    val timestamp: Long = System.currentTimeMillis(),
    val androidVersion: String = "Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")",
    val manufacturer: String = Build.MANUFACTURER + " " + Build.MODEL,
    val items: List<DiagnosticItem>,
    /** true 表示本次沿用了上次已通过的能力检测，没有重复创建测试屏幕。 */
    val capabilityChecksReused: Boolean = false,
) {
    val allBasePassed: Boolean get() = items.filter { it.level == DiagnosticLevel.BASE }.all { it.passed }
    val allCapabilitiesPassed: Boolean get() = items.filter { it.level == DiagnosticLevel.CAPABILITY }.all { it.passed }
    val isReadyForAutomation: Boolean get() = allBasePassed && allCapabilitiesPassed

    /** 只把明确的失败当作阻塞；待核对项给出下一步，但不冒充失败。 */
    val firstBlocker: DiagnosticItem? get() = items.firstOrNull { !it.passed && !it.unknown }
}

/**
 * 实际运行环境与能力分层诊断探针。
 * 覆盖：
 * 1. 基础状态：工具安装、服务可达性与授权
 * 2. 实际能力：服务握手、虚拟屏创建、截图捕获；自检过程及时释放测试虚拟屏，不留多余垃圾
 * 3. QQ 准备状态：区分「QQ 已启动」「宠物房间已识别」与「无法确认」
 *
 * 同一启动周期、同一安装版本内，已通过的能力检测会复用，避免每次打开设置都创建测试屏；
 * 设备重启、应用升级或 Shizuku 断连后自动重查。
 */
class EnvironmentDiagnostic(
    private val packageInstalled: (String) -> Boolean,
    private val repository: IDisplayRepository,
    private val identity: EnvironmentIdentity = EnvironmentIdentity.UNKNOWN,
    private val capabilityCache: CapabilityCheckCache? = null,
    /** 机型只用于说明适配情况；测试可注入，真机默认读 Build.MODEL。 */
    private val deviceModel: String? = Build.MODEL,
) {
    companion object {
        fun defaultPackageChecker(context: Context): (String) -> Boolean = { pkg ->
            runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess
        }

        private fun formatPassedAt(millis: Long): String =
            SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
    }

    constructor(context: Context, repository: IDisplayRepository) : this(
        packageInstalled = defaultPackageChecker(context),
        repository = repository,
        identity = EnvironmentIdentity.read(context),
        capabilityCache = SharedPreferencesCapabilityCheckCache(context),
    )

    suspend fun runCheck(
        shizukuState: ShizukuState,
        forceCapabilities: Boolean = false,
    ): DiagnosticReport {
        val items = mutableListOf<DiagnosticItem>()
        var capabilityChecksReused = false

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
                remedy = if (!shizukuRunning) "请打开 Shizuku 应用点击「启动」；手机重启、系统清理后台或手动停止都可能让服务退出，通常无需重新配对" else null,
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

        // 机型适配：没在这台机器上量过不等于不能用，所以只标「待核对」，不拦托管。
        items.add(
            DiagnosticItem(
                level = DiagnosticLevel.BASE,
                title = "机型适配",
                passed = true,
                unknown = !DeviceSupport.isCalibrated(deviceModel),
                message = DeviceSupport.diagnosticMessage(deviceModel),
                remedy = DeviceSupport.diagnosticRemedy(deviceModel),
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
                val cached = if (!forceCapabilities && identity.isStable) {
                    capabilityCache?.load()?.takeIf {
                        it.bootCount == identity.bootCount && it.appUpdatedAt == identity.appUpdatedAt
                    }
                } else {
                    null
                }

                if (cached != null) {
                    capabilityChecksReused = true
                    items.add(
                        DiagnosticItem(
                            level = DiagnosticLevel.CAPABILITY,
                            title = "虚拟显示器创建",
                            passed = true,
                            message = "上次检测已通过（" + formatPassedAt(cached.passedAt) + "），环境未变化，本次未重复创建测试屏幕",
                        )
                    )
                    items.add(
                        DiagnosticItem(
                            level = DiagnosticLevel.CAPABILITY,
                            title = "后台画面捕获能力",
                            passed = true,
                            message = "上次检测已通过（" + formatPassedAt(cached.passedAt) + "），环境未变化，本次未重复截屏",
                        )
                    )
                } else {
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

                    if (displayCreated && captureOk && identity.isStable) {
                        capabilityCache?.save(
                            CapabilityCheckPass(
                                bootCount = identity.bootCount,
                                appUpdatedAt = identity.appUpdatedAt,
                                passedAt = System.currentTimeMillis(),
                            )
                        )
                    } else {
                        // 没通过就不能留下“上次已通过”的记录，下一次必须重新检测。
                        capabilityCache?.clear()
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
            } else {
                // 服务断开后，旧的能力通过记录不再可信，下一次连接时重新检测。
                capabilityCache?.clear()
            }
        } else {
            capabilityCache?.clear()
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
                title = "QQ 后台运行状态",
                passed = qqRunning,
                message = if (qqRunning) {
                    "QQ 已在后台虚拟屏 (ID: " + displayListStr + ") 中启动；宠物房间和登录账号尚未核对"
                } else {
                    "QQ 尚未在后台虚拟屏中启动"
                },
                remedy = if (qqRunning) {
                    "开始任务前会再识别一次宠物房间；若画面停在登录页，请在 QQ 内自行登录，蟹啦不读取账号或密码"
                } else {
                    "工具设置已完成。请在主页点击「一键创建」或在后台画面中启动 QQ；登录由你在 QQ 内完成，蟹啦不读取账号或密码"
                },
                // QQ 进程存在不等于宠物房间已打开。只有开始任务时的画面识别才能确认房间。
                unknown = qqRunning,
            )
        )

        return DiagnosticReport(items = items, capabilityChecksReused = capabilityChecksReused)
    }
}
