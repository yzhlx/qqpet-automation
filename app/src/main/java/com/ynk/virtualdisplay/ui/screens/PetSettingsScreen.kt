package com.ynk.virtualdisplay.ui.screens

import android.app.NotificationManager
import android.content.Context
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import com.ynk.virtualdisplay.ui.data.PetEvidenceRepository
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 设置 tab — the single settings entry for the app.
 *
 * Only real state is shown. Rows for features that do not exist are marked 暂未开放 and are NOT
 * rendered as toggles, because a switch that silently does nothing is a lie about capability. The
 * developer console, the live log and the calibration frame saver all live here so the main tabs
 * stay user-facing.
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
    val uiState by viewModel.uiState.collectAsState()
    val managedIds by viewModel.repository.managedDisplayIds.collectAsState()

    val profile = remember { PetProfile.calibrated() }
    val plan = remember { com.ynk.virtualdisplay.automation.SessionPlanStore(context).load() }
    val thresholds = remember { SettingsFacts.thresholds(plan) }
    val batch = remember { SettingsFacts.batch(plan) }
    val repo = remember { PetEvidenceRepository(context) }

    var screenshots by remember { mutableStateOf(0) }
    var sessions by remember { mutableStateOf(0) }
    var budgetUsed by remember { mutableStateOf(0) }
    var notificationsEnabled by remember { mutableStateOf(true) }

    LaunchedEffect(uiState.displays) {
        withContext(Dispatchers.IO) {
            val dirs = repo.sessionDirectories(limit = 100).map { repo.read(it) }
            screenshots = dirs.sumOf { it.screenshots.size }
            sessions = dirs.count { it.record != null }
        }
        budgetUsed = DailyBudgetStore(context).usedMinutes()
        notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
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
            GroupHeader("权限与环境")
            PetListRow(
                tileBg = PetTokens.BlueSoft,
                icon = { PetIcon(PetGlyph.Shield, 17.dp) },
                title = "Shizuku 权限",
                sub = if (shizukuReady) "后台截图与输入已授权" else "未授权，托管无法启动",
                trailing = {
                    if (shizukuReady) PetChip("已授权", PetTokens.GreenSoft, PetTokens.GreenInk)
                    else PetChip("未授权", PetTokens.RedSoft, PetTokens.RedInk)
                },
            )
            Column(Modifier.clickable { onOpenConsole() }) {
                PetListRow(
                    tileBg = PetTokens.GreenSoft,
                    icon = { PetIcon(PetGlyph.Display, 17.dp) },
                    title = "虚拟屏",
                    sub = if (petDisplay != null) "display ${petDisplay.id} · ${SettingsFacts.displayFacts(profile)}"
                    else "未找到 ${profile.displayWidth}×${profile.displayHeight} 虚拟屏",
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
            PetListRow(
                tileBg = PetTokens.AmberSoft,
                icon = { PetIcon(PetGlyph.Bell, 17.dp) },
                title = "通知",
                sub = "完成、暂停与异常提醒",
                trailing = {
                    if (notificationsEnabled) PetChip("已开启", PetTokens.GreenSoft, PetTokens.GreenInk)
                    else PetChip("已关闭", PetTokens.RedSoft, PetTokens.RedInk)
                },
            )
            if (uiState.shizukuState !is ShizukuState.Ready) {
                Row(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                    PetNote("Shizuku 未就绪时，托管按钮会被禁用——这与后端服务实际会拒绝启动的条件一致。")
                }
            }
        }
        Spacer(Modifier.height(11.dp))

        // ── 托管行为（全部为真实常量） ──
        PetCard(pad0 = true) {
            GroupHeader("托管行为")
            PetListRow(
                tileBg = PetTokens.BlueSoft,
                icon = { PetIcon(PetGlyph.Clock, 17.dp) },
                title = "单批托管节数",
                sub = "每节约 10 分钟，可随时停止",
                trailing = { ValueText(batch.roundsText) },
            )
            PetListRow(
                tileBg = PetTokens.AmberSoft,
                icon = { PetIcon(PetGlyph.Shield, 17.dp) },
                title = "每日课程上限",
                sub = "达到后当天不再开始新课程",
                trailing = { ValueText(batch.dailyText) },
            )
            PetListRow(
                tileBg = PetTokens.VioletSoft,
                icon = { PetIcon(PetGlyph.Grip, 17.dp) },
                title = "开课阈值",
                sub = "体力 / 清洁，低于时先护理",
                trailing = { ValueText(thresholds.text) },
            )
            PetListRow(
                tileBg = PetTokens.GraySoft,
                icon = { PetIcon(PetGlyph.Cam, 17.dp) },
                title = "运行截图",
                sub = "仅本地保存，仅系统可写",
                trailing = { ValueText(SettingsFacts.SCREENSHOT_POLICY) },
            )
            Row(Modifier.fillMaxWidth().padding(bottom = 10.dp, top = 2.dp)) {
                PetNote(
                    "阈值、节数与每日上限取自「托管方案」里保存的设置，在那里修改。" +
                        "心情不参与判定，仅观察记录。"
                )
            }
        }
        Spacer(Modifier.height(11.dp))

        // ── 本地数据 ──
        PetCard(pad0 = true) {
            GroupHeader("本地数据")
            PetListRow(
                tileBg = PetTokens.BlueSoft,
                icon = { PetIcon(PetGlyph.Db, 17.dp) },
                title = "运行记录",
                sub = "$sessions 次会话 · $screenshots 张截图",
                trailing = { PetChip("仅本机", PetTokens.GraySoft, PetTokens.Ink2) },
            )
            PetListRow(
                tileBg = PetTokens.GreenSoft,
                icon = { PetIcon(PetGlyph.Clock, 17.dp) },
                title = "今日已记录",
                sub = "课程时长会计入每日上限",
                trailing = { ValueText("$budgetUsed / ${DailyBudgetStore.HARD_STOP_MINUTES} 分钟") },
            )
            Row(Modifier.fillMaxWidth().padding(bottom = 10.dp, top = 2.dp)) {
                PetNote("App 不会自动删除截图或日志；需要清理时请在系统「应用信息 › 存储」里操作。")
            }
        }
        Spacer(Modifier.height(11.dp))

        // ── 高级与开发 ──
        PetCard(pad0 = true) {
            GroupHeader("高级与开发")
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
                    sub = "自动化层写入的原始事件",
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
            Column(Modifier.clickable { onOpenPlanSheet() }) {
                PetListRow(
                    tileBg = PetTokens.GreenSoft,
                    icon = { PetIcon(PetGlyph.Play, 17.dp) },
                    title = "托管方案",
                    sub = "查看已启用与暂未开放的功能",
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
        }
        Spacer(Modifier.height(11.dp))

        // ── 暂未开放（明确不提供假开关） ──
        PetCard(pad0 = true) {
            GroupHeader("暂未开放")
            SettingsFacts.UNAVAILABLE_ROWS.forEach { (name, why) ->
                PetListRow(
                    tileBg = PetTokens.GraySoft,
                    icon = { PetIcon(PetGlyph.Info, 17.dp) },
                    title = name,
                    sub = why,
                    dimmed = true,
                    trailing = { PetChip("暂未开放", PetTokens.GraySoft, PetTokens.Ink2) },
                )
            }
            Row(Modifier.fillMaxWidth().padding(bottom = 10.dp, top = 2.dp)) {
                PetNote("这些项目不显示开关：v0.1 没有对应实现，做出可点的开关会误导。")
            }
        }
        Spacer(Modifier.height(11.dp))

        // ── 版本信息 ──
        PetCard(pad0 = true) {
            GroupHeader("版本信息")
            PetListRow(
                tileBg = PetTokens.GraySoft,
                icon = { PetIcon(PetGlyph.Info, 17.dp) },
                title = "QQ 宠物托管 v0.1",
                sub = "设备 ${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE}",
                trailing = { ValueText(BuildConfig.VERSION_NAME) },
            )
            PetListRow(
                tileBg = PetTokens.GraySoft,
                icon = { PetIcon(PetGlyph.Bug, 17.dp) },
                title = "构建",
                sub = "${BuildConfig.GIT_HASH}${if (BuildConfig.GIT_DIRTY) " · 未提交改动" else ""}",
                trailing = { ValueText(BuildConfig.BUILD_TIME.take(16)) },
            )
            Row(Modifier.fillMaxWidth().padding(bottom = 10.dp, top = 2.dp)) {
                PetNote("仅支持已校准的 FOA-AL00 与 QQ 9.3.70(16410)；其他设备会在服务层被拒绝启动。")
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun GroupHeader(text: String) {
    Row(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        PetSection(text)
    }
}

@Composable
private fun ValueText(value: String) {
    Text(value, fontSize = 13.sp, fontWeight = FontWeight(650), color = PetTokens.Ink2,
        fontFamily = FontFamily.Monospace, maxLines = 1)
}
