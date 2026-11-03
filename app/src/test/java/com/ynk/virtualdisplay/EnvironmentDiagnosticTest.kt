package com.ynk.virtualdisplay

import android.content.Context
import android.graphics.Bitmap
import android.view.InputEvent
import android.view.Surface
import com.ynk.virtualdisplay.automation.ActiveCapturedFrame
import com.ynk.virtualdisplay.automation.DiagnosticLevel
import com.ynk.virtualdisplay.automation.EnvironmentDiagnostic
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
        var createDisplaySucceeds: Boolean = true
        var releasedDisplayId: Int? = null
        var captureSucceeds: Boolean = true
        var hostingPackages: Set<Int> = setOf(88)

        override fun isShizukuAvailable(): Boolean = available
        override fun bindService(context: Context) = Unit
        override fun unbindService() = Unit
        override fun destroyService() = Unit

        override suspend fun createDisplay(name: String, width: Int, height: Int, dpi: Int): Result<Int> {
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
                Result.failure(IllegalStateException("CAPTURE_FAILED"))
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
        assertEquals(4, baseItems.size)
        assertEquals(3, capItems.size)
        assertEquals(1, targetItems.size)
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
}
