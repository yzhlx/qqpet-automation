package com.ynk.virtualdisplay

import android.content.Context
import android.graphics.Bitmap
import android.view.InputEvent
import android.view.Surface
import com.ynk.virtualdisplay.automation.ActiveCapturedFrame
import com.ynk.virtualdisplay.automation.CapabilityCheckCache
import com.ynk.virtualdisplay.automation.CapabilityCheckPass
import com.ynk.virtualdisplay.automation.DiagnosticLevel
import com.ynk.virtualdisplay.automation.EnvironmentDiagnostic
import com.ynk.virtualdisplay.automation.EnvironmentIdentity
import com.ynk.virtualdisplay.data.model.ShizukuState
import com.ynk.virtualdisplay.data.repository.ConnectionStatus
import com.ynk.virtualdisplay.data.repository.IDisplayRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import sun.misc.Unsafe
import java.io.File

class EnvironmentDiagnosticTest {

    private class FakeDiagnosticRepo : IDisplayRepository {
        override val automationEventsDirectory: File = File("../.build-tmp")
        override val connectionStatus: StateFlow<ConnectionStatus> = MutableStateFlow(ConnectionStatus.CONNECTED)
        override val managedDisplayIds: StateFlow<Set<Int>> = MutableStateFlow(emptySet())

        var available: Boolean = true
        var createdDisplayId: Int = 88
        var createDisplayCount: Int = 0
        var createDisplaySucceeds: Boolean = true
        var releasedDisplayId: Int? = null
        var captureSucceeds: Boolean = true
        var captureFailureMessage: String = "CAPTURE_FAILED"
        var hostingPackages: Set<Int> = setOf(88)

        override fun isShizukuAvailable(): Boolean = available
        override fun bindService(context: Context) = Unit
        override fun unbindService() = Unit
        override fun destroyService() = Unit

        override suspend fun createDisplay(name: String, width: Int, height: Int, dpi: Int): Result<Int> {
            createDisplayCount++
            return if (createDisplaySucceeds) Result.success(createdDisplayId)
            else Result.failure(IllegalStateException("SYSTEM_REJECTED_DISPLAY"))
        }

        override suspend fun releaseDisplay(displayId: Int): Result<Unit> {
            releasedDisplayId = displayId
            return Result.success(Unit)
        }

        private fun allocateDummyBitmap(): Bitmap {
            val field = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
            val unsafe = field.get(null) as Unsafe
            return unsafe.allocateInstance(Bitmap::class.java) as Bitmap
        }

        override suspend fun captureDisplay(displayId: Int): Result<ActiveCapturedFrame> {
            return if (captureSucceeds) {
                val mockBitmap = allocateDummyBitmap()
                Result.success(ActiveCapturedFrame(mockBitmap, displayId, 1024, 10, 10, "test", 0))
            } else {
                Result.failure(IllegalStateException(captureFailureMessage))
            }
        }

        override suspend fun displaysHosting(packageName: String): Result<Set<Int>> {
            return if (packageName == "com.tencent.mobileqq") Result.success(hostingPackages)
            else Result.success(emptySet())
        }

        override suspend fun setDisplaySurface(displayId: Int, surface: Surface?): Result<Unit> = Result.success(Unit)
        override suspend fun resizeDisplay(displayId: Int, width: Int, height: Int, dpi: Int): Result<Unit> = Result.success(Unit)
        override suspend fun launchApp(packageName: String, displayId: Int): Result<Int> = Result.success(0)
        override suspend fun launchHome(displayId: Int): Result<Int> = Result.success(0)
        override suspend fun injectInput(event: InputEvent): Result<Boolean> = Result.success(true)
        override suspend fun injectInputWithDisplayId(event: InputEvent, displayId: Int): Result<Boolean> = Result.success(true)
    }


    private class InMemoryCapabilityCache : CapabilityCheckCache {
        var entry: CapabilityCheckPass? = null

        override fun load(): CapabilityCheckPass? = entry
        override fun save(pass: CapabilityCheckPass) { entry = pass }
        override fun clear() { entry = null }
    }

    @Test
    fun allComponentsReadyProducesAllPassedReport() = runBlocking {
        val repo = FakeDiagnosticRepo()
        val diag = EnvironmentDiagnostic(packageInstalled = { true }, repository = repo)

        val report = diag.runCheck(ShizukuState.Ready)

        assertTrue("基础状态应全通过", report.allBasePassed)
        assertTrue("实际能力应全通过", report.allCapabilitiesPassed)
        assertTrue("应已就绪可开始托管", report.isReadyForAutomation)
        assertNull("不应有阻塞项", report.firstBlocker)

        // 验证关键约束：临时测试虚拟屏自检完毕后必须立即释放，绝不留下残留
        assertEquals("测试虚拟屏必须被及时释放", repo.createdDisplayId, repo.releasedDisplayId)

        // 验证三层均有检查项
        val baseItems = report.items.filter { it.level == DiagnosticLevel.BASE }
        val capItems = report.items.filter { it.level == DiagnosticLevel.CAPABILITY }
        val targetItems = report.items.filter { it.level == DiagnosticLevel.TARGET_APP }
        assertEquals(5, baseItems.size)
        assertEquals(3, capItems.size)
        assertEquals(1, targetItems.size)
    }

    @Test
    fun unverifiedModelIsFlaggedForReviewWithoutBlocking() = runBlocking {
        val repo = FakeDiagnosticRepo()
        val diag = EnvironmentDiagnostic(
            packageInstalled = { true },
            repository = repo,
            deviceModel = "Pixel 7",
        )

        val report = diag.runCheck(ShizukuState.Checking)

        val modelItem = report.items.first { it.title == "机型适配" }
        assertTrue("未实测机型不算失败", modelItem.passed)
        assertTrue("但必须标成待核对", modelItem.unknown)
        assertNotNull("并给出下一步", modelItem.remedy)
        assertTrue("未实测不能当成已实测", modelItem.message.contains("未实测"))
    }

    @Test
    fun calibratedModelShowsNoModelWarning() = runBlocking {
        val repo = FakeDiagnosticRepo()
        val diag = EnvironmentDiagnostic(
            packageInstalled = { true },
            repository = repo,
            deviceModel = "FOA-AL00",
        )

        val report = diag.runCheck(ShizukuState.Checking)

        val modelItem = report.items.first { it.title == "机型适配" }
        assertFalse("已实测机型不该标待核对", modelItem.unknown)
        assertNull("已实测机型不需要下一步", modelItem.remedy)
    }

    @Test
    fun shizukuNotRunningHaltsCapabilityLayerSafely() = runBlocking {
        val repo = FakeDiagnosticRepo()
        val diag = EnvironmentDiagnostic(packageInstalled = { true }, repository = repo)

        val report = diag.runCheck(ShizukuState.Checking)

        assertFalse("基础状态不应通过", report.allBasePassed)
        assertFalse("不满足托管条件", report.isReadyForAutomation)

        val runningItem = report.items.first { it.title == "Shizuku 运行状态" }
        assertFalse(runningItem.passed)
        assertNotNull(runningItem.remedy)

        // 能力层不应盲目执行（未授权时不创建虚拟屏）
        val capItems = report.items.filter { it.level == DiagnosticLevel.CAPABILITY }
        assertTrue("未授权时不应执行第二层能力检测", capItems.isEmpty())
        assertNull("不应创建测试虚拟屏", repo.releasedDisplayId)
    }

    @Test
    fun shizukuPermissionDeniedProvidesDirectRemedy() = runBlocking {
        val repo = FakeDiagnosticRepo()
        val diag = EnvironmentDiagnostic(packageInstalled = { true }, repository = repo)

        val report = diag.runCheck(ShizukuState.PermissionDenied)

        val grantItem = report.items.first { it.title == "Shizuku 授权状态" }
        assertFalse(grantItem.passed)
        assertTrue("提示必须包含已授权应用勾选指引", grantItem.remedy!!.contains("Shizuku"))
    }

    @Test
    fun temporaryVirtualDisplayCreationFailureReportsBlocker() = runBlocking {
        val repo = FakeDiagnosticRepo().apply { createDisplaySucceeds = false }
        val diag = EnvironmentDiagnostic(packageInstalled = { true }, repository = repo)

        val report = diag.runCheck(ShizukuState.Ready)

        assertTrue(report.allBasePassed)
        assertFalse(report.allCapabilitiesPassed)
        assertFalse(report.isReadyForAutomation)

        val displayItem = report.items.first { it.title == "虚拟显示器创建" }
        assertFalse(displayItem.passed)
        assertNotNull(displayItem.remedy)
    }

    @Test
    fun releaseDisplayAlwaysCalledEvenIfCaptureFails() = runBlocking {
        val repo = FakeDiagnosticRepo().apply { captureSucceeds = false }
        val diag = EnvironmentDiagnostic(packageInstalled = { true }, repository = repo)

        val report = diag.runCheck(ShizukuState.Ready)

        // 即使截图捕获失败，finally 保证已创建的虚拟屏必须释放
        assertEquals(repo.createdDisplayId, repo.releasedDisplayId)
        val captureItem = report.items.first { it.title == "后台画面捕获能力" }
        assertFalse(captureItem.passed)
    }

    @Test
    fun blackFrameOnEmptyTestDisplayIsUnknownNotFailure() = runBlocking {
        // 空测试屏没有内容，screencap 出来必然全黑。这不能证明设备不能捕获，
        // 也不能当作成功：必须显示「待核对」，开始托管后会用真实画面复检。
        val repo = FakeDiagnosticRepo().apply {
            captureSucceeds = false
            captureFailureMessage = "CAPTURE_INVALID_BLACK_FRAME"
        }
        val cache = InMemoryCapabilityCache()
        val diag = EnvironmentDiagnostic(
            packageInstalled = { true },
            repository = repo,
            capabilityCache = cache,
        )

        val report = diag.runCheck(ShizukuState.Ready)

        val captureItem = report.items.first { it.title == "后台画面捕获能力" }
        assertFalse(captureItem.passed)
        assertTrue("黑帧是未知，不是失败", captureItem.unknown)
        assertNull("未知项不给出误导性的失败修复建议", captureItem.remedy)
        assertFalse(report.isReadyForAutomation)
        assertNull("未知项不是阻断项，不能拦住托管", report.firstBlocker?.takeIf { it.title == "后台画面捕获能力" })
        assertNull("黑帧不能留下「上次已通过」缓存", cache.entry)
    }

    @Test
    fun passedCapabilityCheckIsReusedUntilEnvironmentChanges() = runBlocking {
        val repo = FakeDiagnosticRepo()
        val cache = InMemoryCapabilityCache()
        val identity = EnvironmentIdentity(bootCount = 7, appUpdatedAt = 1000L)
        val diag = EnvironmentDiagnostic(
            packageInstalled = { true },
            repository = repo,
            identity = identity,
            capabilityCache = cache,
        )

        val first = diag.runCheck(ShizukuState.Ready)
        assertEquals(1, repo.createDisplayCount)
        assertFalse("第一次必须真正检测", first.capabilityChecksReused)

        val second = diag.runCheck(ShizukuState.Ready)
        assertEquals("环境未变化时不应重复创建测试虚拟屏", 1, repo.createDisplayCount)
        assertTrue("第二次应沿用上次通过的检测", second.capabilityChecksReused)
        assertTrue(second.allCapabilitiesPassed)

        val forced = diag.runCheck(ShizukuState.Ready, forceCapabilities = true)
        assertEquals("用户点「重新检测」时必须真正复查", 2, repo.createDisplayCount)
        assertFalse(forced.capabilityChecksReused)
    }

    @Test
    fun rebootOrAppUpdateInvalidatesCapabilityCache() = runBlocking {
        val repo = FakeDiagnosticRepo()
        val cache = InMemoryCapabilityCache()
        val firstIdentity = EnvironmentIdentity(bootCount = 7, appUpdatedAt = 1000L)
        EnvironmentDiagnostic(
            packageInstalled = { true },
            repository = repo,
            identity = firstIdentity,
            capabilityCache = cache,
        ).runCheck(ShizukuState.Ready)
        assertEquals(1, repo.createDisplayCount)

        val afterBoot = EnvironmentDiagnostic(
            packageInstalled = { true },
            repository = repo,
            identity = firstIdentity.copy(bootCount = 8),
            capabilityCache = cache,
        ).runCheck(ShizukuState.Ready)
        assertEquals("设备重启后必须重新检测", 2, repo.createDisplayCount)
        assertFalse(afterBoot.capabilityChecksReused)

        val afterUpdate = EnvironmentDiagnostic(
            packageInstalled = { true },
            repository = repo,
            identity = firstIdentity.copy(appUpdatedAt = 2000L),
            capabilityCache = cache,
        ).runCheck(ShizukuState.Ready)
        assertEquals("应用升级后必须重新检测", 3, repo.createDisplayCount)
        assertFalse(afterUpdate.capabilityChecksReused)
    }

    @Test
    fun serviceDisconnectClearsCapabilityCache() = runBlocking {
        val repo = FakeDiagnosticRepo()
        val cache = InMemoryCapabilityCache()
        val identity = EnvironmentIdentity(bootCount = 7, appUpdatedAt = 1000L)
        val diag = EnvironmentDiagnostic(
            packageInstalled = { true },
            repository = repo,
            identity = identity,
            capabilityCache = cache,
        )

        diag.runCheck(ShizukuState.Ready)
        assertEquals(1, repo.createDisplayCount)
        assertNotNull(cache.entry)

        repo.available = false
        diag.runCheck(ShizukuState.Ready)
        assertNull("服务断连后应清掉旧的通过记录", cache.entry)

        repo.available = true
        val reconnected = diag.runCheck(ShizukuState.Ready)
        assertEquals("重连后必须重新检测", 2, repo.createDisplayCount)
        assertFalse(reconnected.capabilityChecksReused)
    }

    @Test
    fun qqRunningWithoutRoomRecognitionIsMarkedForVerification() = runBlocking {
        val repo = FakeDiagnosticRepo().apply { hostingPackages = setOf(88) }
        val report = EnvironmentDiagnostic(
            packageInstalled = { true },
            repository = repo,
        ).runCheck(ShizukuState.Ready)

        val qqItem = report.items.first { it.level == DiagnosticLevel.TARGET_APP }
        assertTrue("QQ 已启动但房间未核对时应标为待核对", qqItem.unknown)
        assertTrue(qqItem.passed)
        assertTrue("必须说明房间尚未核对", qqItem.message.contains("尚未核对"))
        assertTrue("必须说明不读取账号或密码", qqItem.remedy!!.contains("不读取账号或密码"))
        assertNull("待核对不是阻塞项", report.firstBlocker)
    }

    @Test
    fun qqStoppedExplainsLoginIsUserOwned() = runBlocking {
        val repo = FakeDiagnosticRepo().apply { hostingPackages = emptySet() }
        val qqItem = EnvironmentDiagnostic(
            packageInstalled = { true },
            repository = repo,
        ).runCheck(ShizukuState.Ready).items.first { it.level == DiagnosticLevel.TARGET_APP }

        assertFalse(qqItem.passed)
        assertFalse(qqItem.unknown)
        assertTrue("未启动 QQ 时也要说明登录由用户完成", qqItem.remedy!!.contains("不读取账号或密码"))
    }
}
