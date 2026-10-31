package com.ynk.virtualdisplay.ui.main

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ynk.virtualdisplay.MyApplication
import com.ynk.virtualdisplay.data.model.ShizukuState
import com.ynk.virtualdisplay.data.repository.ShizukuDisplayRepository
import com.ynk.virtualdisplay.ui.kit.PetGlyph
import com.ynk.virtualdisplay.ui.kit.PetIcon
import com.ynk.virtualdisplay.ui.screens.PetCalibrationScreen
import com.ynk.virtualdisplay.floating.PetAgentPipHost
import com.ynk.virtualdisplay.ui.screens.PetHomeScreen
import com.ynk.virtualdisplay.ui.screens.PetLogScreen
import com.ynk.virtualdisplay.ui.screens.PetPlanSheetScreen
import com.ynk.virtualdisplay.ui.screens.PetRecordsScreen
import com.ynk.virtualdisplay.ui.screens.PetSettingsScreen
import com.ynk.virtualdisplay.ui.screens.ShizukuPermissionScreen
import com.ynk.virtualdisplay.ui.screens.VirtualDisplayScreen
import com.ynk.virtualdisplay.ui.theme.MyApplicationTheme
import com.ynk.virtualdisplay.ui.theme.PetTokens
import rikka.shizuku.Shizuku
import rikka.sui.Sui

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels {
        MainViewModelFactory((application as MyApplication).displayRepository, applicationContext)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            if (Sui.init(packageName)) {
                Log.i("MainActivity", "Sui initialized successfully")
            }
        } catch (e: Throwable) {
            Log.e("MainActivity", "Sui init failed", e)
        }

        (application as MyApplication).acquireDisplayRepository("MainActivity")
        viewModel.checkShizukuStatus(this)

        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                val uiState by viewModel.uiState.collectAsState()

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = PetTokens.Bg,
                ) { innerPadding ->
                    Box(Modifier.padding(innerPadding).fillMaxSize()) {
                        when (val state = uiState.shizukuState) {
                            is ShizukuState.Checking -> Box(
                                Modifier.fillMaxSize(), contentAlignment = Alignment.Center,
                            ) { CircularProgressIndicator() }

                            is ShizukuState.Ready -> PetApp(
                                uiState = uiState,
                                viewModel = viewModel,
                            )

                            else -> ShizukuPermissionScreen(
                                state = state,
                                onRetry = { viewModel.checkShizukuStatus(this@MainActivity) },
                                onRequestPermission = {
                                    try {
                                        Shizuku.requestPermission(ShizukuDisplayRepository.REQUEST_CODE)
                                    } catch (e: Exception) {
                                        Log.e("MainActivity", "Failed to request permission", e)
                                    }
                                },
                            )
                        }

                        if (uiState.isLoading) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("MainActivity", "onDestroy: releasing repository lease")
        (application as MyApplication).releaseDisplayRepository("MainActivity")
    }
}

/**
 * The app shell: a stacked real screen when one is open, otherwise the selected tab. The tab bar is
 * the reference's own custom bar (icon + label, blue when active) rather than a Material
 * NavigationBar, so the three destinations match the approved mock-ups exactly.
 */
@Composable
private fun PetApp(uiState: MainUiState, viewModel: MainViewModel) {
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            when (uiState.overlay) {
                OverlayScreen.PLAN_SHEET -> PetPlanSheetScreen(
                    onClose = { viewModel.closeOverlay() },
                    onOpenConsole = { viewModel.openOverlay(OverlayScreen.CONSOLE) },
                    displayId = uiState.displays.firstOrNull {
                        it.width == com.ynk.virtualdisplay.automation.PetProfile.calibrated().displayWidth &&
                            it.height == com.ynk.virtualdisplay.automation.PetProfile.calibrated().displayHeight
                    }?.id,
                )

                OverlayScreen.LOGS -> PetLogScreen(onClose = { viewModel.closeOverlay() })

                OverlayScreen.CONSOLE -> Column(Modifier.fillMaxSize()) {
                    PetOverlayHeader("虚拟屏控制台", onBack = { viewModel.closeOverlay() })
                    VirtualDisplayScreen(viewModel = viewModel)
                }

                OverlayScreen.CALIBRATION -> Column(Modifier.fillMaxSize()) {
                    PetOverlayHeader("模板校准", onBack = { viewModel.closeOverlay() })
                    PetCalibrationScreen(viewModel = viewModel)
                }

                OverlayScreen.NONE -> when (uiState.currentTab) {
                    ScreenTab.HOST -> Column(Modifier.fillMaxSize()) {
                        PetTitleBar("宠物托管")
                        PetHomeScreen(
                            viewModel = viewModel,
                            onOpenPlanSheet = { viewModel.openOverlay(OverlayScreen.PLAN_SHEET) },
                            onOpenLogs = { viewModel.openOverlay(OverlayScreen.LOGS) },
                        )
                    }

                    ScreenTab.RECORDS -> Column(Modifier.fillMaxSize()) {
                        PetTitleBar("记录")
                        PetRecordsScreen()
                    }

                    ScreenTab.SETTINGS -> Column(Modifier.fillMaxSize()) {
                        PetTitleBar("设置")
                        PetSettingsScreen(
                            viewModel = viewModel,
                            onOpenConsole = { viewModel.openOverlay(OverlayScreen.CONSOLE) },
                            onOpenLogs = { viewModel.openOverlay(OverlayScreen.LOGS) },
                            onOpenPlanSheet = { viewModel.openOverlay(OverlayScreen.PLAN_SHEET) },
                            onOpenCalibration = { viewModel.openOverlay(OverlayScreen.CALIBRATION) },
                        )
                    }
                }
            }
        }
            if (uiState.overlay == OverlayScreen.NONE) {
                PetTabBar(uiState.currentTab) { viewModel.switchTab(it) }
            }
        }
    // In-app floating preview sits above tabs/content but below system dialogs.
        PetAgentPipHost(Modifier.fillMaxSize())
    }
}

/** Reference `.ab` title bar: 19px/700, left-aligned, on the app background. */
@Composable
fun PetTitleBar(title: String, onBack: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            Box(Modifier.clickable { onBack() }.padding(end = 10.dp)) {
                PetIcon(PetGlyph.ArrowBack, 20.dp, PetTokens.Ink)
            }
        }
        Text(title, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = PetTokens.Ink)
    }
}

@Composable
private fun PetOverlayHeader(title: String, onBack: () -> Unit) {
    PetTitleBar(title, onBack)
}

/** Reference `.tabbar`: 3 items, icon over label, blue when selected. */
@Composable
private fun PetTabBar(current: ScreenTab, onSelect: (ScreenTab) -> Unit) {
    Column {
        Box(Modifier.fillMaxWidth().height(1.dp).background(PetTokens.Line))
        Row(
            Modifier.fillMaxWidth().background(PetTokens.Surface)
                .padding(horizontal = 15.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TabItem("托管", PetGlyph.TabHome, current == ScreenTab.HOST) { onSelect(ScreenTab.HOST) }
            TabItem("记录", PetGlyph.TabRec, current == ScreenTab.RECORDS) { onSelect(ScreenTab.RECORDS) }
            TabItem("设置", PetGlyph.TabSet, current == ScreenTab.SETTINGS) { onSelect(ScreenTab.SETTINGS) }
        }
    }
}

@Composable
private fun TabItem(label: String, glyph: PetGlyph, selected: Boolean, onClick: () -> Unit) {
    val tint = if (selected) PetTokens.Blue else PetTokens.TabInactive
    Column(
        Modifier.clickable { onClick() }.padding(horizontal = 18.dp, vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PetIcon(glyph, 21.dp, tint)
        Spacer(Modifier.height(3.dp))
        Text(
            label, fontSize = 10.5.sp,
            fontWeight = if (selected) FontWeight(650) else FontWeight.Normal,
            color = tint,
        )
    }
}
