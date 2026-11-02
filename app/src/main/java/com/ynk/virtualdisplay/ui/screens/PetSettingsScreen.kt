package com.ynk.virtualdisplay.ui.screens

import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import com.ynk.virtualdisplay.BuildConfig
import com.ynk.virtualdisplay.automation.DailyBudgetStore
import com.ynk.virtualdisplay.automation.PetAutomationService
import com.ynk.virtualdisplay.automation.PetProfile
import com.ynk.virtualdisplay.data.model.ShizukuState
import com.ynk.virtualdisplay.floating.OverlayPermission
import com.ynk.virtualdisplay.ui.data.PetEvidenceRepository
import com.ynk.virtualdisplay.ui.feedback.FeedbackChannels
import com.ynk.virtualdisplay.ui.feedback.FeedbackDialog
import com.ynk.virtualdisplay.ui.kit.PetCard
import com.ynk.virtualdisplay.ui.kit.PetChip
import com.ynk.virtualdisplay.ui.kit.PetNote
import com.ynk.virtualdisplay.ui.kit.PetListRow
import com.ynk.virtualdisplay.ui.kit.PetGlyph
import com.ynk.virtualdisplay.ui.kit.PetIcon
import com.ynk.virtualdisplay.ui.kit.PetSection
import com.ynk.virtualdisplay.ui.main.MainViewModel
import com.ynk.virtualdisplay.ui.settings.SettingsFacts
import com.ynk.virtualdisplay.ui.theme.PetTokens
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 设置 tab — the single settings entry for the app.
 *
 * Only real state is shown, and features that do not exist get no row at all. The developer console,
 * the live log and the calibration frame saver stay folded under 高级 so the main tabs stay user-facing.
 */
@Composable
fun PetSettingsScreen(
    viewModel: MainViewModel,
    onOpenConsole: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenPlanSheet: () -> Unit,
    onOpenCalibration: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uiState by viewModel.uiState.collectAsState()
    val managedIds by viewModel.repository.managedDisplayIds.collectAsState()

    val profile = remember { PetProfile.calibrated() }
    val plan = remember { com.ynk.virtualdisplay.automation.SessionPlanStore(context).load() }
    val repo = remember { PetEvidenceRepository(context) }

    var screenshots by remember { mutableStateOf(0) }
    var sessions by remember { mutableStateOf(0) }
    var budgetUsed by remember { mutableStateOf(0) }
    var notificationsEnabled by remember { mutableStateOf(true) }
    var showFeedback by remember { mutableStateOf(false) }
    var showAdvanced by remember { mutableStateOf(false) }

    if (showFeedback) FeedbackDialog(onDismiss = { showFeedback = false })

    // The display list is only filled by the console, so the page must ask for it itself; otherwise a
    // screen created elsewhere still reads as 未创建.
    LaunchedEffect(Unit) { viewModel.refreshDisplays(context) }

    LaunchedEffect(uiState.displays) {
        withContext(Dispatchers.IO) {
            val dirs = repo.sessionDirectories(limit = 100).map { repo.read(it) }
            screenshots = dirs.sumOf { it.screenshots.size }
            sessions = dirs.count { it.record != null }
        }
        budgetUsed = DailyBudgetStore(context).usedMinutes()
    }

    // Permissions change in system screens; re-read them whenever this page comes back to the front.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
                viewModel.refreshDisplays(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val shizukuReady = uiState.shizukuState is ShizukuState.Ready
    val petDisplay = uiState.displays.firstOrNull {
        it.width == profile.displayWidth && it.height == profile.displayHeight
    }
    val petDisplayManaged = petDisplay != null && petDisplay.id in managedIds

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 15.dp)) {
        Spacer(Modifier.height(2.dp))

        // ── 权限与环境 ──
        PetCard(pad0 = true) {
            GroupHeader("权限", PetGlyph.Shield)
            val overlayGranted = OverlayPermission.canDrawOverlays(context)
            Column(Modifier.clickable { context.startActivity(OverlayPermission.settingsIntent(context)) }) {
                PetListRow(
                    tileBg = if (overlayGranted) PetTokens.GreenSoft else PetTokens.AmberSoft,
                    icon = { PetIcon(PetGlyph.TabSet, 18.dp, if (overlayGranted) PetTokens.GreenInk else PetTokens.AmberInk) },
                    title = "悬浮窗",
                    sub = if (overlayGranted) "离开 App 也能看到进度" else "未开启：离开 App 后看不到进度胶囊",
                    trailing = {
                        if (overlayGranted) PetChip("已授权", PetTokens.GreenSoft, PetTokens.GreenInk)
                        else PetChip("未授权", PetTokens.AmberSoft, PetTokens.AmberInk)
                    },
                )
            }
            PetListRow(
                tileBg = PetTokens.BlueSoft,
                icon = { PetIcon(PetGlyph.Shield, 17.dp) },
                title = "Shizuku",
                sub = if (shizukuReady) "后台画面和操作已授权" else "未授权，托管无法启动",
                trailing = {
                    if (shizukuReady) PetChip("已授权", PetTokens.GreenSoft, PetTokens.GreenInk)
                    else PetChip("未授权", PetTokens.RedSoft, PetTokens.RedInk)
                },
            )
            Column(Modifier.clickable { onOpenConsole() }) {
                PetListRow(
                    tileBg = PetTokens.GreenSoft,
                    icon = { PetIcon(PetGlyph.Display, 17.dp) },
                    title = "后台画面",
                    sub = if (petDisplay != null) SettingsFacts.displayFacts(profile) else "还没有创建",
                    trailing = {
                        when {
                            petDisplayManaged -> PetChip("已就绪", PetTokens.GreenSoft, PetTokens.GreenInk)
                            petDisplay != null -> PetChip("未托管", PetTokens.AmberSoft, PetTokens.AmberInk)
                            else -> PetChip("未创建", PetTokens.GraySoft, PetTokens.Ink2)
                        }
                    },
                    showChevron = true,
                )
            }
            Column(Modifier.clickable { openNotificationSettings(context) }) {
                PetListRow(
                    tileBg = PetTokens.AmberSoft,
                    icon = { PetIcon(PetGlyph.Bell, 17.dp) },
                    title = "通知",
                    sub = if (notificationsEnabled) "完成和中途停止时提醒" else "未开启：点这里去打开，完成和停止时才会提醒",
                    trailing = {
                        if (notificationsEnabled) PetChip("已开启", PetTokens.GreenSoft, PetTokens.GreenInk)
                        else PetChip("去开启", PetTokens.RedSoft, PetTokens.RedInk)
                    },
                    showChevron = !notificationsEnabled,
                )
            }
            if (uiState.shizukuState !is ShizukuState.Ready) {
                Row(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                    PetNote("Shizuku 未就绪时不能开始托管。")
                }
            }
        }
        Spacer(Modifier.height(11.dp))

        // ── 托管 ──
        PetCard(pad0 = true) {
            GroupHeader("托管", PetGlyph.Play)
            Column(Modifier.clickable { onOpenPlanSheet() }) {
                PetListRow(
                    tileBg = PetTokens.BlueSoft,
                    icon = { PetIcon(PetGlyph.Play, 16.dp) },
                    title = "默认运行方案",
                    sub = "选择动作、课程、节数和目标",
                    showChevron = true,
                )
            }
            PetListRow(
                tileBg = PetTokens.GreenSoft,
                icon = { PetIcon(PetGlyph.Clock, 17.dp) },
                title = "今日已用",
                sub = "打工和学习共用",
                trailing = { ValueText("$budgetUsed / ${plan.dailyBudgetMinutes} 分钟") },
            )
        }
        Spacer(Modifier.height(11.dp))

        // ── 关于 ──
        PetCard(pad0 = true) {
            GroupHeader("关于", PetGlyph.Info)
            Column(Modifier.clickable { showFeedback = true }) {
                PetListRow(
                    tileBg = PetTokens.AmberSoft,
                    icon = { PetIcon(PetGlyph.Bug, 17.dp, PetTokens.AmberInk) },
                    title = "问题反馈",
                    sub = "把出错情况和运行日志发给开发者",
                    showChevron = true,
                )
            }
            Column(Modifier.clickable {
                scope.launch {
                    val channels = FeedbackChannels(context)
                    val name = withContext(Dispatchers.IO) {
                        channels.exportToDownloads(channels.buildZip("手动导出运行日志", includeScreenshots = false))
                    }
                    android.widget.Toast.makeText(context, "已保存到「下载」：$name", android.widget.Toast.LENGTH_LONG).show()
                }
            }) {
                PetListRow(
                    tileBg = PetTokens.BlueSoft,
                    icon = { PetIcon(PetGlyph.TabRec, 17.dp) },
                    title = "导出运行日志",
                    sub = "存到手机「下载」，出问题时可直接发给开发者",
                    showChevron = true,
                )
            }
            PetListRow(
                tileBg = PetTokens.VioletSoft,
                icon = { PetIcon(PetGlyph.Info, 17.dp) },
                title = "版本",
                sub = "设备 ${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE}",
                trailing = { ValueText(BuildConfig.VERSION_NAME) },
            )
            Row(Modifier.fillMaxWidth().padding(bottom = 10.dp, top = 2.dp)) {
                PetNote("托管画面固定为 720×1600，与手机型号无关。坐标在 FOA-AL00 上校准过，其他机型未实测，页面对不上时会自动停止。")
            }
        }
        Spacer(Modifier.height(11.dp))

        // ── 高级：收起，普通用户用不到 ──
        PetCard(pad0 = true) {
            Row(
                Modifier.fillMaxWidth().clickable { showAdvanced = !showAdvanced }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PetSection("高级")
                Spacer(Modifier.weight(1f))
                PetNote(if (showAdvanced) "收起" else "展开")
            }
            if (showAdvanced) {
                Column(Modifier.clickable { onOpenConsole() }) {
                    PetListRow(
                        tileBg = PetTokens.GreenSoft,
                        icon = { PetIcon(PetGlyph.Display, 17.dp) },
                        title = "虚拟屏控制台",
                        sub = "创建 / 释放虚拟屏，查看画面",
                        showChevron = true,
                    )
                }
                Column(Modifier.clickable { onOpenLogs() }) {
                    PetListRow(
                        tileBg = PetTokens.BlueSoft,
                        icon = { PetIcon(PetGlyph.TabRec, 17.dp) },
                        title = "运行日志",
                        sub = "$sessions 次运行 · $screenshots 张截图 · 仅本机",
                        showChevron = true,
                    )
                }
                Column(Modifier.clickable { onOpenCalibration() }) {
                    PetListRow(
                        tileBg = PetTokens.VioletSoft,
                        icon = { PetIcon(PetGlyph.Cam, 17.dp) },
                        title = "模板校准",
                        sub = "保存虚拟屏调试帧，用于裁剪识别模板",
                        showChevron = true,
                    )
                }
                Column(Modifier.clickable {
                    context.startService(PetAutomationService.stopIntent(context))
                }) {
                    PetListRow(
                        tileBg = PetTokens.RedSoft,
                        icon = { PetIcon(PetGlyph.Warn, 16.dp) },
                        title = "紧急停止",
                        sub = "立即结束当前批次，不做任何输入",
                    )
                }
                PetListRow(
                    tileBg = PetTokens.GraySoft,
                    icon = { PetIcon(PetGlyph.Bug, 17.dp) },
                    title = "构建",
                    sub = BuildConfig.GIT_HASH,
                    trailing = { ValueText(BuildConfig.BUILD_TIME.take(16)) },
                )
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}

private fun openNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        context.startActivity(Intent(Settings.ACTION_SETTINGS))
    }
}

@Composable
private fun GroupHeader(text: String, glyph: PetGlyph) {
    Row(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        PetIcon(glyph, 15.dp)
        Spacer(Modifier.width(7.dp))
        PetSection(text)
    }
}

@Composable
private fun ValueText(value: String) {
    Text(value, fontSize = 13.sp, fontWeight = FontWeight(650), color = PetTokens.Ink2,
        fontFamily = FontFamily.Monospace, maxLines = 1)
}
