package com.ynk.virtualdisplay.ui.main

import com.ynk.virtualdisplay.data.model.ShizukuState
import com.ynk.virtualdisplay.data.repository.ConnectionStatus

/**
 * 虚拟显示器元数据模型
 */
data class DisplayInfoModel(
    val id: Int,
    val name: String,
    val width: Int,
    val height: Int
)

/** The three user-facing tabs of the v0.1 redesign: 托管 / 记录 / 设置. */
enum class ScreenTab {
    HOST,
    RECORDS,
    SETTINGS
}

/**
 * Screens that are real but not tabs. They are reached from 设置 (console / developer tools) or
 * stack over 托管 (the plan sheet and the full log). Keeping them out of [ScreenTab] means the tab
 * bar always shows the three user-facing destinations.
 */
enum class OverlayScreen {
    NONE,
    PLAN_SHEET,
    LOGS,
    CONSOLE,
    CALIBRATION,
}

/**
 * 统一的界面状态聚合类
 */
data class MainUiState(
    val shizukuState: ShizukuState = ShizukuState.Checking,
    val connectionStatus: ConnectionStatus = ConnectionStatus.IDLE,
    val displays: List<DisplayInfoModel> = emptyList(),
    val orphanDisplayIds: List<Int> = emptyList(),
    val statusMessage: String = "Ready",
    val isLoading: Boolean = false,
    val currentTab: ScreenTab = ScreenTab.HOST,
    /** A real screen stacked over the tabs; [OverlayScreen.NONE] shows the current tab. */
    val overlay: OverlayScreen = OverlayScreen.NONE,
    
    // 输入相关状态
    val inputWidth: String = "",
    val inputHeight: String = "",
    val inputDpi: String = ""
)
