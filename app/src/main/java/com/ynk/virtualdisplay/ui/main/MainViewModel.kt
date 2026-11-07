package com.ynk.virtualdisplay.ui.main

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import android.graphics.Point
import android.util.DisplayMetrics
import android.view.Display
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ynk.virtualdisplay.automation.PetDisplayPicker
import com.ynk.virtualdisplay.automation.PetProfile
import com.ynk.virtualdisplay.automation.PetRuntime
import com.ynk.virtualdisplay.data.model.ShizukuState
import com.ynk.virtualdisplay.data.repository.IDisplayRepository
import com.ynk.virtualdisplay.data.repository.ConnectionStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku

class MainViewModel(
    val repository: IDisplayRepository,
    context: Context
) : ViewModel() {

    private val appContext = context.applicationContext
    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()
    private val displayListener = object : android.hardware.display.DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            refreshDisplays(appContext)
        }
        override fun onDisplayRemoved(displayId: Int) {
            refreshDisplays(appContext)
        }
        override fun onDisplayChanged(displayId: Int) {
            refreshDisplays(appContext)
        }
    }

    private val REQUEST_PERMISSION_RESULT_LISTENER = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        val granted = grantResult == PackageManager.PERMISSION_GRANTED
        Log.d("MainViewModel", "Shizuku permission result: requestCode=$requestCode, granted=$granted")
        _uiState.update { it.copy(
            shizukuState = if (granted) ShizukuState.Ready else ShizukuState.PermissionDenied
        ) }
    }

    init {
        Shizuku.addRequestPermissionResultListener(REQUEST_PERMISSION_RESULT_LISTENER)
        
        val dm = appContext.getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
        dm.registerDisplayListener(displayListener, null)
        
        // 订阅 Repository 状态
        viewModelScope.launch {
            repository.connectionStatus.collect { status ->
                _uiState.update { it.copy(connectionStatus = status) }
            }
        }
        
        viewModelScope.launch {
            repository.managedDisplayIds.collect { _ ->
                refreshDisplays(appContext)
            }
        }
    }

    fun switchTab(tab: ScreenTab) {
        _uiState.update { it.copy(currentTab = tab, overlay = OverlayScreen.NONE) }
    }

    /** Opens a real stacked screen (plan sheet / full log / display console). */
    fun openOverlay(screen: OverlayScreen) {
        _uiState.update { it.copy(overlay = screen) }
        if (screen == OverlayScreen.CONSOLE) {
            reloadDefaultInputsFromSettings(appContext)
        }
    }

    /** Returns to the tab that opened the overlay. */
    fun closeOverlay() {
        _uiState.update { it.copy(overlay = OverlayScreen.NONE) }
    }

    @Suppress("DEPRECATION")
    fun reloadDefaultInputsFromSettings(context: Context) {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager

        val defaultDisplay = dm.getDisplay(Display.DEFAULT_DISPLAY)
        val size = Point()
        defaultDisplay?.getRealSize(size)
        val metrics = DisplayMetrics()
        defaultDisplay?.getRealMetrics(metrics)

        val prefs = context.getSharedPreferences("virtual_display_settings", Context.MODE_PRIVATE)
        val defaultW = prefs.getString("pref_default_width", "") ?: ""
        val defaultH = prefs.getString("pref_default_height", "") ?: ""
        val defaultDpi = prefs.getString("pref_default_dpi", "") ?: ""

        val w = defaultW.ifEmpty { size.x.toString() }
        val h = defaultH.ifEmpty { size.y.toString() }
        val dpi = defaultDpi.ifEmpty { metrics.densityDpi.toString() }

        _uiState.update { state ->
            state.copy(
                inputWidth = w,
                inputHeight = h,
                inputDpi = dpi
            )
        }
    }

    fun launchSelectedApp(context: Context, displayId: Int, packageName: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, statusMessage = "Launching $packageName on $displayId...") }
            repository.launchApp(packageName, displayId)
                .onSuccess {
                    _uiState.update { it.copy(isLoading = false, statusMessage = "Launched $packageName") }
                }
                .onFailure { e ->
                    Log.e("MainViewModel", "Failed to launch app", e)
                    _uiState.update { it.copy(isLoading = false, statusMessage = "Launch failed: ${e.message}") }
                }
        }
    }

    /**
     * Binds the privileged service when it is not connected yet and waits for it, so the one-tap
     * button also works from a cold start instead of failing with "Service not connected".
     */
    private suspend fun ensureServiceConnected(): Boolean {
        if (repository.connectionStatus.value != ConnectionStatus.CONNECTED) {
            repository.bindService(appContext)
            val connected = withTimeoutOrNull(8_000L) {
                repository.connectionStatus.first { it == ConnectionStatus.CONNECTED }
            } ?: return false
            // The status flips before the service handle is stored; a short pause covers that gap.
            delay(300L)
            if (connected != ConnectionStatus.CONNECTED) return false
        }
        return true
    }

    /** One tap for beginners: the calibrated screen with QQ already on it. */
    fun createPetDisplay(onReady: (Int) -> Unit) {
        val profile = com.ynk.virtualdisplay.automation.PetProfile.calibrated()
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, statusMessage = "正在创建虚拟屏…") }
            if (_uiState.value.shizukuState !is ShizukuState.Ready) {
                _uiState.update { it.copy(isLoading = false, statusMessage = "Error: Shizuku 未授权，请先完成前面的授权步骤") }
                return@launch
            }
            if (!ensureServiceConnected()) {
                _uiState.update { it.copy(isLoading = false, statusMessage = "Error: 特权服务连接超时，请确认 Shizuku 已启动后再试") }
                return@launch
            }
            repository.createDisplay(
                name = "Shizuku_VD_${System.currentTimeMillis()}",
                width = profile.displayWidth,
                height = profile.displayHeight,
                dpi = 320,
            ).onSuccess { displayId ->
                refreshDisplays(appContext)
                // QQ opening is best effort: the user only needs the pet screen, which they open next.
                val launched = repository.launchApp(PetDisplayPicker.QQ_PACKAGE, displayId).isSuccess
                refreshDisplays(appContext)
                _uiState.update { it.copy(
                    isLoading = false,
                    statusMessage = if (launched) "虚拟屏已创建" else "虚拟屏已创建，QQ 没有自动打开",
                ) }
                onReady(displayId)
            }.onFailure { e ->
                Log.e("MainViewModel", "Failed to create pet display", e)
                _uiState.update { it.copy(isLoading = false, statusMessage = "虚拟屏创建失败：${e.message}") }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun checkAndInitDeviceMetrics(context: Context) {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
        val defaultDisplay = dm.getDisplay(Display.DEFAULT_DISPLAY)
        val size = Point()
        defaultDisplay?.getRealSize(size)
        val metrics = DisplayMetrics()
        defaultDisplay?.getRealMetrics(metrics)

        val prefs = context.getSharedPreferences("virtual_display_settings", Context.MODE_PRIVATE)
        val defaultW = prefs.getString("pref_default_width", "") ?: ""
        val defaultH = prefs.getString("pref_default_height", "") ?: ""
        val defaultDpi = prefs.getString("pref_default_dpi", "") ?: ""

        val w = defaultW.ifEmpty { size.x.toString() }
        val h = defaultH.ifEmpty { size.y.toString() }
        val dpi = defaultDpi.ifEmpty { metrics.densityDpi.toString() }

        _uiState.update { state ->
            state.copy(
                inputWidth = state.inputWidth.ifEmpty { w },
                inputHeight = state.inputHeight.ifEmpty { h },
                inputDpi = state.inputDpi.ifEmpty { dpi }
            )
        }
    }

    fun checkShizukuStatus(context: Context) {
        checkAndInitDeviceMetrics(context)
        if (!repository.isShizukuAvailable()) {
            _uiState.update { it.copy(shizukuState = ShizukuState.NotRunning) }
            return
        }

        try {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                _uiState.update { it.copy(shizukuState = ShizukuState.Ready) }
                repository.bindService(context)
            } else {
                _uiState.update { it.copy(shizukuState = ShizukuState.PermissionDenied) }
            }
        } catch (e: IllegalStateException) {
            Log.w("MainViewModel", "Shizuku not attached yet, waiting for binder...", e)
            _uiState.update { it.copy(shizukuState = ShizukuState.Checking) }
            val listener = object : Shizuku.OnBinderReceivedListener {
                override fun onBinderReceived() {
                    Shizuku.removeBinderReceivedListener(this)
                    checkShizukuStatus(context)
                }
            }
            Shizuku.addBinderReceivedListener(listener)
        }
    }

    fun updateInputs(width: String? = null, height: String? = null, dpi: String? = null) {
        _uiState.update { state ->
            state.copy(
                inputWidth = width ?: state.inputWidth,
                inputHeight = height ?: state.inputHeight,
                inputDpi = dpi ?: state.inputDpi
            )
        }
    }

    fun createVirtualDisplay(context: Context) {
        val state = _uiState.value
        val w = state.inputWidth.toIntOrNull() ?: 0
        val h = state.inputHeight.toIntOrNull() ?: 0
        val d = state.inputDpi.toIntOrNull() ?: 0

        if (w <= 0 || h <= 0 || d <= 0) {
            _uiState.update { it.copy(statusMessage = "Error: Invalid dimensions or DPI") }
            return
        }

        // 校验比例：长宽比/宽长比 =< 2.5
        val ratio = if (w > h) w.toFloat() / h else h.toFloat() / w
        if (ratio > 2.5f) {
            _uiState.update { it.copy(statusMessage = "Error: Aspect ratio too extreme (max 2.5, current ${String.format(java.util.Locale.US, "%.2f", ratio)})") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, statusMessage = "Creating display ${w}x${h}...") }
            repository.createDisplay(
                name = "Shizuku_VD_${System.currentTimeMillis()}",
                width = w,
                height = h,
                dpi = d
            ).onSuccess { displayId ->
                _uiState.update { it.copy(
                    isLoading = false, 
                    statusMessage = "Created Display ID: $displayId"
                ) }
                refreshDisplays(context)
            }.onFailure { e ->
                Log.e("MainViewModel", "Failed to create display", e)
                _uiState.update { it.copy(
                    isLoading = false,
                    statusMessage = "Error: ${e.message}"
                ) }
            }
        }
    }

    fun refreshDisplays(context: Context) {
        checkAndInitDeviceMetrics(context)
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
        val managedByService = repository.managedDisplayIds.value
        
        val displaysList = mutableListOf<DisplayInfoModel>()
        val orphans = mutableListOf<Int>()
        
        dm.displays.forEach { display ->
            if (display.displayId != Display.DEFAULT_DISPLAY) {
                val size = Point()
                @Suppress("DEPRECATION")
                display.getRealSize(size)
                displaysList.add(
                    DisplayInfoModel(
                        id = display.displayId,
                        name = display.name,
                        width = size.x,
                        height = size.y
                    )
                )
                if (repository.connectionStatus.value == ConnectionStatus.CONNECTED && display.displayId !in managedByService) {
                    orphans.add(display.displayId)
                }
            }
        }
        
        _uiState.update { it.copy(
            displays = displaysList,
            orphanDisplayIds = orphans,
            petDisplayId = pickPetDisplay(displaysList, managedByService, it.qqDisplayIds),
            statusMessage = if (it.statusMessage.startsWith("Error:")) it.statusMessage else "Displays refreshed"
        ) }
        viewModelScope.launch {
            val hosting = repository.displaysHosting(PetDisplayPicker.QQ_PACKAGE).getOrNull() ?: return@launch
            _uiState.update { it.copy(
                qqDisplayIds = hosting,
                petDisplayId = pickPetDisplay(it.displays, repository.managedDisplayIds.value, hosting),
            ) }
            releaseLeftoverPetDisplays(context)
        }
    }

    /**
     * Releases the empty screens earlier 一键创建 runs left beside the QQ screen (2026-11-03: 28 and
     * 29 beside QQ on 30). Never during a creation, whose new screen is empty until QQ arrives, and
     * never while a session runs.
     */
    private suspend fun releaseLeftoverPetDisplays(context: Context) {
        val state = _uiState.value
        if (state.isLoading || PetRuntime.state.value.running) return
        val profile = PetProfile.calibrated()
        val leftovers = PetDisplayPicker.leftovers(
            state.displays.map { PetDisplayPicker.Candidate(it.id, it.width, it.height) },
            profile.displayWidth, profile.displayHeight,
            repository.managedDisplayIds.value, state.qqDisplayIds, state.petDisplayId,
        )
        if (leftovers.isEmpty()) return
        leftovers.forEach { id ->
            repository.releaseDisplay(id).onFailure { Log.w("MainViewModel", "release leftover $id failed", it) }
        }
        Log.i("MainViewModel", "released leftover pet displays $leftovers")
        refreshDisplays(context)
    }

    private fun pickPetDisplay(displays: List<DisplayInfoModel>, managed: Set<Int>, qq: Set<Int>): Int? {
        val profile = PetProfile.calibrated()
        return PetDisplayPicker.pick(
            displays.map { PetDisplayPicker.Candidate(it.id, it.width, it.height) },
            profile.displayWidth, profile.displayHeight, managed, qq,
        )
    }

    fun releaseDisplay(displayId: Int, context: Context) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, statusMessage = "Releasing display $displayId...") }
            repository.releaseDisplay(displayId)
                .onSuccess {
                    // Only a screen that is really gone counts as deleted; the system removes it asynchronously.
                    val stillShowing = isStillShowing(context, displayId)
                    _uiState.update { it.copy(
                        isLoading = false,
                        statusMessage = if (stillShowing) "Error: 虚拟屏 $displayId 仍在运行，删除未生效。可点右上角「重启服务」后再试"
                        else "Released $displayId",
                    ) }
                    refreshDisplays(context)
                }
                .onFailure { e ->
                    _uiState.update { it.copy(isLoading = false, statusMessage = "Error: ${releaseFailureText(e, displayId)}") }
                }
        }
    }

    private suspend fun isStillShowing(context: Context, displayId: Int): Boolean {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
        repeat(10) {
            if (dm.getDisplay(displayId) == null) return false
            delay(150L)
        }
        return dm.getDisplay(displayId) != null
    }

    private fun releaseFailureText(e: Throwable, displayId: Int): String {
        val message = e.message.orEmpty()
        return when {
            message.contains("ORPHAN_NOT_RELEASABLE") || message.contains("NOT_OWNED") ->
                "这个屏幕是上次运行留下的，App 无法直接删除。请点右上角「重启服务」清理"
            message.contains("RELEASE_FAILED") -> "虚拟屏 $displayId 释放出错，请点「重启服务」后再试"
            message.contains("Service not connected") -> "特权服务未连接，请先点「重启服务」"
            else -> "删除失败：$message"
        }
    }

    fun forceRestartService(context: Context) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, statusMessage = "Force restarting service...") }
            try {
                repository.destroyService()
            } catch (e: Exception) {
                Log.e("MainViewModel", "Failed to destroy service", e)
            }
            kotlinx.coroutines.delay(1000)
            repository.bindService(context)
            _uiState.update { it.copy(isLoading = false, statusMessage = "Service restarted") }
        }
    }



    override fun onCleared() {
        super.onCleared()
        val dm = appContext.getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
        dm.unregisterDisplayListener(displayListener)
        Shizuku.removeRequestPermissionResultListener(REQUEST_PERMISSION_RESULT_LISTENER)
    }
}

class MainViewModelFactory(
    private val repository: IDisplayRepository,
    private val context: Context
) : androidx.lifecycle.ViewModelProvider.Factory {
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return MainViewModel(repository, context) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
