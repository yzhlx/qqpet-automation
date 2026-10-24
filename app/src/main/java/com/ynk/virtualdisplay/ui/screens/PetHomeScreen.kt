package com.ynk.virtualdisplay.ui.screens

import android.os.SystemClock
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.ynk.virtualdisplay.automation.CourseCatalogStore
import com.ynk.virtualdisplay.automation.DailyBudgetStore
import com.ynk.virtualdisplay.automation.PetAutomationService
import com.ynk.virtualdisplay.automation.PetManagedRuntime
import com.ynk.virtualdisplay.automation.PetProfile
import com.ynk.virtualdisplay.automation.PetRuntime
import com.ynk.virtualdisplay.automation.SessionMode
import com.ynk.virtualdisplay.automation.SessionPlan
import com.ynk.virtualdisplay.automation.SessionPlanStore
import com.ynk.virtualdisplay.data.model.ShizukuState
import com.ynk.virtualdisplay.ui.data.LogEntry
import com.ynk.virtualdisplay.ui.data.PetEvidenceRepository
import com.ynk.virtualdisplay.ui.data.SessionEvidence
import com.ynk.virtualdisplay.ui.data.SessionRunRecord
import com.ynk.virtualdisplay.ui.data.SessionStartRequest
import com.ynk.virtualdisplay.ui.kit.PetButtonTone
import com.ynk.virtualdisplay.ui.kit.PetCard
import com.ynk.virtualdisplay.ui.kit.PetChevron
import com.ynk.virtualdisplay.ui.kit.PetChip
import com.ynk.virtualdisplay.ui.kit.PetDivider
import com.ynk.virtualdisplay.ui.kit.PetDot
import com.ynk.virtualdisplay.ui.kit.PetFootButton
import com.ynk.virtualdisplay.ui.kit.PetGlyph
import com.ynk.virtualdisplay.ui.kit.PetIcon
import com.ynk.virtualdisplay.ui.kit.PetListRow
import com.ynk.virtualdisplay.ui.kit.PetNote
import com.ynk.virtualdisplay.ui.kit.PetPill
import com.ynk.virtualdisplay.ui.kit.PetProgressBar
import com.ynk.virtualdisplay.ui.kit.PetRowHeader
import com.ynk.virtualdisplay.ui.kit.PetSection
import com.ynk.virtualdisplay.ui.kit.rememberEvidenceBitmap
import com.ynk.virtualdisplay.ui.log.PetLogEntry
import com.ynk.virtualdisplay.ui.log.PetLogPresentation
import com.ynk.virtualdisplay.ui.main.MainViewModel
import com.ynk.virtualdisplay.ui.theme.PetTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 托管 home. Every value on this screen comes from a real source:
 *  - live run state / progress / pause reason : PetManagedRuntime + PetRuntime (read-only flows)
 *  - screenshots and the last-run summary     : the evidence directory written by the controller
 *  - the log timeline                          : files/pet_mvp.log
 *
 * The control footer exposes ONLY what the automation layer really has: START and STOP. There is no
 * manual pause/resume API, so 暂停 explains itself and is never wired to a fake action.
 */
@Composable
fun PetHomeScreen(
    viewModel: MainViewModel,
    onOpenPlanSheet: () -> Unit,
    onOpenLogs: () -> Unit,
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val runtime by PetRuntime.state.collectAsState()
    val managed by PetManagedRuntime.state.collectAsState()

    var wallNowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    val healthExpiryAtMs = ManagedRuntimePresentation.nextHealthExpiryAtMs(managed)
    LaunchedEffect(runtime.running, managed.sessionId, healthExpiryAtMs) {
        wallNowMs = System.currentTimeMillis()
        while (ManagedRuntimePresentation.needsFreshnessTicker(
                managed, runtime.running, System.currentTimeMillis(),
            )
        ) {
            delay(1_000L)
            wallNowMs = System.currentTimeMillis()
        }
    }

    val profile = remember { PetProfile.calibrated() }
    val profileErrors = remember { profile.validate(healthGuardInstalled = true) }
    val managedView = ManagedRuntimePresentation.of(managed, runtime, wallNowMs, SystemClock.elapsedRealtime())
    val fmt = remember { hhmmFormatter() }

    val repo = remember { PetEvidenceRepository(context) }
    var latest by remember { mutableStateOf<SessionEvidence?>(null) }
    var shots by remember { mutableStateOf<List<File>>(emptyList()) }
    var logRows by remember { mutableStateOf<List<PetLogEntry>>(emptyList()) }
    var budgetUsed by remember { mutableStateOf(0) }
    // The saved plan decides the batch: course, round count, thresholds and budget. It replaces the
    // old in-memory 1/3/10 selector, which reset every time the screen was left.
    var plan by remember { mutableStateOf(SessionPlan()) }
    var catalogFresh by remember { mutableStateOf(false) }

    LaunchedEffect(runtime.running, managed.sessionId) {
        suspend fun reload() = withContext(Dispatchers.IO) {
            latest = repo.latestSession()
            val liveDir = repo.liveSessionDirectory(managed.sessionId)
            shots = repo.screenshotsFor(liveDir ?: latest?.directory, null)
            logRows = PetLogPresentation.timeline(repo.logEntries(limit = 600))
            budgetUsed = DailyBudgetStore(context).usedMinutes()
            plan = SessionPlanStore(context).load()
            val catalog = CourseCatalogStore(context).load()
            catalogFresh = catalog?.isUsable(System.currentTimeMillis()) == true &&
                catalog.find(plan.courseId)?.let { it.unlocked && it.durationMinutes in 1..DailyBudgetStore.HARD_STOP_MINUTES } == true
        }
        reload()
        while (runtime.running) {
            delay(2_500L)
            reload()
        }
    }

    val health = HealthStatPresentation.stats(managed, wallNowMs, managedView.sessionIsCurrent, fmt)
    val pill = StatusPillPresentation.of(managedView, runtime.running)

    val readyDisplay = uiState.displays.firstOrNull {
        it.width == profile.displayWidth && it.height == profile.displayHeight
    }
    val managedIds by viewModel.repository.managedDisplayIds.collectAsState()
    val canStart = uiState.shizukuState is ShizukuState.Ready &&
        readyDisplay != null && readyDisplay.id in managedIds && profileErrors.isEmpty()
    val controls = ManagedControlsPresentation.of(
        legacyActive = runtime.running,
        runState = managed.runState,
        canStart = canStart,
        profileBlockers = profileErrors,
        displayReady = readyDisplay != null,
    )
    val progress = RoundProgressPresentation.of(managed.totalRounds, managed.completedRounds)
    val budgetCap = minOf(plan.dailyBudgetMinutes, DailyBudgetStore.HARD_STOP_MINUTES)
    val budgetLeft = budgetCap - budgetUsed

    var dialog by remember { mutableStateOf<HomeDialog?>(null) }

    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 15.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            Spacer(Modifier.height(2.dp))

            // ── 状态卡：三项真实读数 + 状态胶囊 ──
            PetCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    health.forEach { stat -> HealthStatCell(stat, Modifier.weight(1f)) }
                }
                Spacer(Modifier.height(12.dp))
                PetDivider()
                Spacer(Modifier.height(11.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    PetPill(pill.text, pillBackground(pill.tone), pillForeground(pill.tone))
                    Spacer(Modifier.width(8.dp))
                    PetNote(subtitleFor(managedView, runtime.running, latest?.record, fmt))
                }
            }

            if (runtime.running) {
                CurrentTaskCard(managedView, progress)
                FilmStripCard(shots)
                HomeLogCard(logRows, onOpenLogs)
            } else {
                LastRunCard(latest, fmt)
                PlanCard(plan, catalogFresh, onOpenPlanSheet)
                ThresholdCard(plan)
                PetNote("金币、属性、库存当前无法从游戏可靠读取，记录里不以估算值代填。")
            }

            Spacer(Modifier.height(4.dp))
        }

        // ── 底部主控：只有开始 / 停止是真实能力 ──
        // Opaque background: the scrolled cards above end where this footer begins, so nothing may
        // show through underneath the buttons.
        Column(
            Modifier.background(PetTokens.Bg)
                .padding(horizontal = 15.dp).padding(top = 9.dp, bottom = 12.dp),
        ) {
            if (!runtime.running && !controls.startEnabled && controls.blockerNote != null) {
                PetNote(controls.blockerNote, Modifier.padding(bottom = 7.dp))
            }
            if (!runtime.running) {
                // Three separate entries, one per capability. v0.1 bundled 健康检查+护理+学习 into a
                // single 开始托管, so reading the pet's current 体力/清洁 also spent coins and burned
                // the shared daily budget. Each stage now runs on its own.
                if (!catalogFresh) {
                    PetNote("请先读取完整课表并选择已解锁课程，托管暂不可用。到「托管方案」刷新课表。", Modifier.padding(bottom = 7.dp))
                }
                SessionModeRow(
                    enabled = controls.startEnabled,
                    studyEnabled = controls.startEnabled && catalogFresh,
                    workEnabled = controls.startEnabled,
                    displayId = readyDisplay?.id,
                    rounds = plan.rounds,
                    context = context,
                )
                Spacer(Modifier.height(7.dp))
                PetNote("托管：${plan.courseName} × ${plan.rounds} 节；打工/护理/学习各自独立，只做所选动作")
                Spacer(Modifier.height(2.dp))
                PetNote("打工：按岗位体力/清洁消耗和固定时长换金币；不查课表、不护理")
                Spacer(Modifier.height(2.dp))
                PetNote("学习：只花学费，不要求体力/清洁；金币不足会停止，不会自动去打工")
                Spacer(Modifier.height(2.dp))
                PetNote("护理：先用背包里的饼干/香皂，缺了才买；购买前核对真实金币并保留学费")
                Spacer(Modifier.height(2.dp))
                PetNote("今日已记 $budgetUsed / $budgetCap 分钟 · 剩余 $budgetLeft")
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PetFootButton(
                        text = "暂停",
                        tone = PetButtonTone.GHOST,
                        onClick = { dialog = HomeDialog.PauseUnavailable },
                        modifier = Modifier.weight(1f),
                    )
                    PetFootButton(
                        text = "停止托管",
                        tone = PetButtonTone.DANGER,
                        onClick = { dialog = HomeDialog.ConfirmStop },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }

    when (dialog) {
        HomeDialog.PauseUnavailable -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("手动暂停暂未开放") },
            text = {
                Text(
                    "v0.1 只能开始与停止。检测到锁屏、虚拟屏失效或页面无法确认时，App 会自动暂停、" +
                        "在 120 秒内最多尝试恢复 3 次，期间不发送任何输入；恢复失败则安全停止。\n\n" +
                        "当前：${managedView.lifecycleText}" +
                        (managed.pauseReason?.let { " · $it" } ?: "") +
                        "（累计自动暂停 ${managed.pauseCount} 次）"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    dialog = null
                    context.startService(PetAutomationService.stopIntent(context))
                }) { Text("改为停止托管") }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("知道了") } },
        )
        HomeDialog.ConfirmStop -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("停止托管？") },
            text = { Text("将结束当前批次。已完成的节会写入运行记录，未完成的不会被标记为已完成。") },
            confirmButton = {
                TextButton(onClick = {
                    dialog = null
                    context.startService(PetAutomationService.stopIntent(context))
                }) { Text("停止") }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("取消") } },
        )
        null -> Unit
    }
}

private enum class HomeDialog { PauseUnavailable, ConfirmStop }

@Composable
private fun HealthStatCell(stat: HealthStat, modifier: Modifier = Modifier) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PetDot(dotColorFor(stat.name))
            Spacer(Modifier.width(5.dp))
            Text(stat.name, fontSize = 12.5.sp, fontWeight = FontWeight(650), color = PetTokens.Ink2)
        }
        Spacer(Modifier.height(5.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            if (stat.valueText.startsWith("≈")) {
                Text("≈", fontSize = 15.sp, fontWeight = FontWeight(600), color = PetTokens.Ink3)
            }
            Text(
                stat.valueText.removePrefix("≈"),
                fontSize = 23.sp, fontWeight = FontWeight.Bold,
                color = if (stat.status == HealthStatStatus.KNOWN && stat.current) PetTokens.Ink else PetTokens.Ink3,
                fontFamily = FontFamily.Monospace, lineHeight = 24.sp,
            )
        }
        Spacer(Modifier.height(5.dp))
        Text(
            stat.meta, fontSize = 10.sp,
            color = if (stat.status == HealthStatStatus.STALE) PetTokens.AmberInk else PetTokens.Ink3,
            maxLines = 1,
        )
    }
}

@Composable
private fun CurrentTaskCard(view: ManagedRuntimeView, progress: RoundProgress) {
    PetCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                flowTitle(view), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = PetTokens.Ink,
                modifier = Modifier.weight(1f),
            )
            val (chipText, chipBg, chipFg) = when (view.lifecycle) {
                ManagedLifecycle.RUNNING -> Triple("进行中", PetTokens.GreenSoft, PetTokens.GreenInk)
                ManagedLifecycle.PAUSED -> Triple("已暂停", PetTokens.AmberSoft, PetTokens.AmberInk)
                ManagedLifecycle.RESUMING -> Triple("恢复中", PetTokens.AmberSoft, PetTokens.AmberInk)
                ManagedLifecycle.COMPLETED -> Triple("已完成", PetTokens.GreenSoft, PetTokens.GreenInk)
                ManagedLifecycle.FAILED -> Triple("失败", PetTokens.RedSoft, PetTokens.RedInk)
                else -> Triple(view.lifecycleText, PetTokens.GraySoft, PetTokens.Ink2)
            }
            PetChip(chipText, chipBg, chipFg)
        }
        Spacer(Modifier.height(5.dp))
        Text(stepHeroText(view), fontSize = 24.sp, fontWeight = FontWeight.Bold, color = PetTokens.Ink,
            letterSpacing = (-0.3).sp)
        Spacer(Modifier.height(13.dp))
        PetProgressBar(progress.fraction)
        Spacer(Modifier.height(7.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("第 ${progress.completed} / 共 ${progress.total} 节", fontSize = 12.5.sp,
                color = PetTokens.Ink2, fontFamily = FontFamily.Monospace)
            Text(progress.percentText, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                color = PetTokens.Ink, fontFamily = FontFamily.Monospace)
        }
        Spacer(Modifier.height(9.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            PetIcon(if (view.sessionIsCurrent) PetGlyph.Check else PetGlyph.Info, 13.dp,
                if (view.sessionIsCurrent) PetTokens.Green else PetTokens.Ink3)
            Spacer(Modifier.width(5.dp))
            Text(
                if (view.sessionIsCurrent) "体力和清洁是本会话最新读数 · 已运行 ${view.durationValue}"
                else "显示的不是本会话读数 · 已运行 ${view.durationValue}",
                fontSize = 11.5.sp, color = PetTokens.Ink2, maxLines = 1,
            )
        }
        if (view.lifecycle == ManagedLifecycle.PAUSED || view.lifecycle == ManagedLifecycle.RESUMING) {
            Spacer(Modifier.height(10.dp))
            PausedBanner(view)
        }
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.fillMaxWidth().background(PetTokens.GraySoft, RoundedCornerShape(PetTokens.NextRadius))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("下一步", fontSize = 11.5.sp, color = PetTokens.Ink3)
            Spacer(Modifier.width(10.dp))
            Text(view.nextStepText, fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = PetTokens.Ink)
        }
    }
}

@Composable
private fun PausedBanner(view: ManagedRuntimeView) {
    Row(
        Modifier.fillMaxWidth().background(PetTokens.AmberSoft, RoundedCornerShape(PetTokens.NextRadius))
            .padding(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        PetIcon(PetGlyph.Locate, 17.dp, PetTokens.AmberStrong)
        Spacer(Modifier.width(9.dp))
        Column {
            Text("自动暂停", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = PetTokens.AmberHead)
            Spacer(Modifier.height(3.dp))
            Text(
                "${view.pauseText}。App 正在自动尝试恢复，期间不发送任何输入；" +
                    "120 秒内无法确认页面会安全停止，不会盲点。",
                fontSize = 11.5.sp, color = PetTokens.AmberInk, lineHeight = 16.sp,
            )
        }
    }
}

@Composable
private fun FilmStripCard(shots: List<File>) {
    PetCard {
        PetRowHeader(
            "运行截图",
            if (shots.isEmpty()) "本次暂无" else "共 ${shots.size} 张 · 仅本地",
        )
        Spacer(Modifier.height(10.dp))
        if (shots.isEmpty()) {
            PetNote("本会话尚未写入截图。截图由自动化层落盘，UI 只读取，不会伪造。")
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                shots.takeLast(4).forEach { file -> FilmFrame(file, Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun FilmFrame(file: File, modifier: Modifier = Modifier) {
    val bmp = rememberEvidenceBitmap(file.absolutePath, 260)
    Column(modifier) {
        Box(
            Modifier.fillMaxWidth().height(96.dp).clip(RoundedCornerShape(9.dp))
                .background(PetTokens.FilmTop),
            contentAlignment = Alignment.Center,
        ) {
            if (bmp != null) {
                androidx.compose.foundation.Image(
                    bitmap = bmp, contentDescription = file.name,
                    modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
                )
            } else {
                PetIcon(PetGlyph.Cam, 20.dp, alpha = 0.5f)
            }
        }
        Spacer(Modifier.height(5.dp))
        Text(frameCaption(file.name), fontSize = 10.sp, color = PetTokens.Ink3, maxLines = 1)
    }
}

@Composable
private fun HomeLogCard(rows: List<PetLogEntry>, onOpenLogs: () -> Unit) {
    PetCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            PetSection("运行日志")
            Spacer(Modifier.weight(1f))
            Row(Modifier.clickable { onOpenLogs() }, verticalAlignment = Alignment.CenterVertically) {
                PetNote("全部")
                Spacer(Modifier.width(3.dp))
                PetChevron()
            }
        }
        Spacer(Modifier.height(9.dp))
        if (rows.isEmpty()) {
            PetNote("暂无日志。")
        } else {
            rows.takeLast(6).forEach { row -> LogRow(row) }
        }
    }
}

@Composable
private fun LogRow(row: PetLogEntry) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
        Text(row.time, fontSize = 10.5.sp, color = PetTokens.Ink3, fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(38.dp))
        Box(Modifier.padding(top = 5.dp).size(6.dp).background(row.dot, RoundedCornerShape(3.dp)))
        Spacer(Modifier.width(9.dp))
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(row.text, fontSize = 12.5.sp, color = PetTokens.Ink, lineHeight = 17.sp,
                modifier = Modifier.weight(1f, fill = false))
            if (row.chip != null) {
                Spacer(Modifier.width(6.dp))
                PetChip(row.chip, row.chipBg, row.chipFg)
            }
        }
    }
}

@Composable
private fun LastRunCard(latest: SessionEvidence?, fmt: (Long) -> String) {
    val record = latest?.record
    PetCard {
        PetRowHeader("上次运行", lastRunNote(record))
        Spacer(Modifier.height(10.dp))
        if (record == null) {
            PetNote("尚无运行记录。完成一次托管后，这里会显示真实统计。")
        } else {
            Row(Modifier.fillMaxWidth()) {
                StripCell(record.completedRounds, "已完成 节", Modifier.weight(1f))
                Spacer(Modifier.width(7.dp))
                StripCell(record.care?.total ?: 0, "护理 次", Modifier.weight(1f))
                Spacer(Modifier.width(7.dp))
                StripCell((record.runtimeMs / 60000).toInt(), "分钟", Modifier.weight(1f))
            }
            Spacer(Modifier.height(11.dp))
            PetDivider()
            Spacer(Modifier.height(11.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                PetNote(
                    if (record.startedAtMs != null) "开始于 ${fmt(record.startedAtMs)}" else "开始时间未记录"
                )
                PetNote("已保留 ${latest.screenshots.size} 张运行截图")
            }
        }
    }
}

@Composable
private fun StripCell(value: Int, label: String, modifier: Modifier = Modifier) {
    // Reference draws these as tinted tiles rather than bare numbers, which keeps the three
    // statistics from reading as loose text.
    Column(
        modifier
            .background(PetTokens.BarTrack, RoundedCornerShape(PetTokens.TileRadius))
            .padding(vertical = 9.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("$value", fontSize = 21.sp, fontWeight = FontWeight.Bold, color = PetTokens.Ink,
            fontFamily = FontFamily.Monospace)
        Spacer(Modifier.height(2.dp))
        Text(label, fontSize = 10.5.sp, color = PetTokens.Ink3)
    }
}

@Composable
private fun PlanCard(plan: SessionPlan, catalogFresh: Boolean, onOpenPlanSheet: () -> Unit) {
    PetCard(pad0 = true) {
        Row(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            PetSection("本次方案")
            Spacer(Modifier.weight(1f))
            PetNote("${plan.courseName} × ${plan.rounds} 节")
        }
        Box(Modifier.clickable { onOpenPlanSheet() }) {
            PetListRow(
                tileBg = PetTokens.GreenSoft,
                icon = { PetIcon(PetGlyph.Care, 18.dp) },
                title = "日常维持",
                sub = "健康检查 · 自动护理 · 自动学习",
                trailing = { PetChip("已启用", PetTokens.GreenSoft, PetTokens.GreenInk) },
                showChevron = true,
            )
        }
        PetListRow(
            tileBg = PetTokens.AmberSoft,
            icon = { PetIcon(PetGlyph.Coin, 18.dp) },
            title = "打工赚学费",
            sub = if (plan.workBeforeStudy) "金币不足时先打一轮工" else "未开启",
            dimmed = !plan.workBeforeStudy,
            trailing = {
                PetChip(
                    if (plan.workBeforeStudy) "已开启" else "未开启",
                    if (plan.workBeforeStudy) PetTokens.GreenSoft else PetTokens.GraySoft,
                    if (plan.workBeforeStudy) PetTokens.GreenInk else PetTokens.Ink2,
                )
            },
        )
        PetListRow(
            tileBg = PetTokens.VioletSoft,
            icon = { PetIcon(PetGlyph.Friend, 18.dp) },
            title = "社交互动",
            sub = "串门 · 踩踩 · 好友护理",
            dimmed = true,
            trailing = { PetChip("暂未开放", PetTokens.GraySoft, PetTokens.Ink2) },
        )
    }
}

@Composable
private fun ThresholdCard(plan: SessionPlan) {
    PetCard(pad0 = true) {
        Row(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically) {
            PetSection("开课阈值")
            Spacer(Modifier.weight(1f))
            PetNote("低于时先护理")
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth().padding(bottom = 13.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("体力", "清洁", "心情").forEach { name ->
                val gate = when (name) {
                    "体力" -> "≥ ${plan.energyThreshold}"
                    "清洁" -> "≥ ${plan.cleanlinessThreshold}"
                    else -> "不参与"
                }
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PetDot(dotColorFor(name))
                        Spacer(Modifier.width(5.dp))
                        Text(name, fontSize = 11.5.sp, color = PetTokens.Ink2)
                    }
                    Spacer(Modifier.height(3.dp))
                    Text(
                        gate,
                        fontSize = 17.sp, fontWeight = FontWeight.Bold, color = PetTokens.Ink,
                        fontFamily = FontFamily.Monospace,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        if (name == "心情") "仅观察记录" else "低于时先护理",
                        fontSize = 9.5.sp, color = PetTokens.Ink3, maxLines = 1,
                    )
                }
            }
        }
    }
}

// ─────────────────────────── helpers ───────────────────────────

private fun flowTitle(view: ManagedRuntimeView): String {
    val base = when {
        view.flowText.contains("STUDY", true) || view.flowText.contains("学习", true) -> "自动学习"
        view.flowText.contains("CARE", true) || view.flowText.contains("护理", true) -> "日常护理"
        view.flowText.contains("NAV", true) -> "页面导航"
        view.flowText.contains("OBS", true) -> "状态读取"
        else -> view.flowText
    }
    val round = view.roundOfTotal
    return if (round != null) "$base · 第 ${round.first} 节 / 共 ${round.second} 节" else base
}

/**
 * The hero line of the current-task card.
 *
 * The engine records its internal step token (e.g. `RECOVER_IF_NEEDED`), which is meaningful in the
 * log but is not product copy. Known tokens are translated; anything unrecognised degrades to a
 * generic "正在执行本步骤" rather than printing an internal identifier at the user.
 */
private fun stepHeroText(view: ManagedRuntimeView): String = when (view.stepText.uppercase()) {
    "IDLE" -> "等待开始"
    "INITIAL_NAVIGATION" -> "正在进入学院"
    "CHECK_STATE" -> "正在读取宠物状态"
    "VERIFY_READY" -> "正在确认状态是否达标"
    "RECOVER_IF_NEEDED" -> "正在护理宠物"
    "SELECT_COURSE" -> "正在选择课程"
    "CLEAR_PREVIOUS_RESULT" -> "正在清理上次结果"
    "START_COURSE" -> "正在开始上课"
    "HIGH_FIVE" -> "正在互动鼓励"
    "WAIT_HIGH_FIVE_WINDOW" -> "等待互动窗口"
    "WAIT_RESULT" -> "正在上课，等成绩"
    "READ_POST_STATE" -> "正在读取课后状态"
    "NEXT_ROUND" -> "准备下一节"
    "DONE", "SESSION" -> "正在收尾"
    "STOPPED" -> "已停止"
    else -> "正在执行本步骤"
}

private fun subtitleFor(
    view: ManagedRuntimeView,
    running: Boolean,
    record: SessionRunRecord?,
    fmt: (Long) -> String,
): String = when {
    running && (view.lifecycle == ManagedLifecycle.PAUSED || view.lifecycle == ManagedLifecycle.RESUMING) ->
        "${view.pauseText} · 自动恢复中"
    running -> "已运行 ${view.durationValue} · 完成 ${view.completedValue}"
    record?.startedAtMs != null ->
        "上次 ${fmt(record.startedAtMs)} · 完成 ${record.completedRounds}/${record.rounds} 节"
    else -> "尚未运行"
}

private fun lastRunNote(record: SessionRunRecord?): String = when (record?.terminalState) {
    null -> "无记录"
    "COMPLETED" -> "正常结束"
    "STOPPED" -> "手动停止"
    "FAILED" -> "异常结束"
    else -> record.terminalState
}

// One caption implementation for both 托管 and 记录, so the same file never gets two labels.
private fun frameCaption(fileName: String): String =
    com.ynk.virtualdisplay.ui.records.RecordsPresentation.frameCaption(fileName)

private fun dotColorFor(name: String): Color = when (name) {
    "体力" -> PetTokens.Green
    "清洁" -> PetTokens.Blue
    else -> PetTokens.MoodDot
}

private fun pillBackground(tone: StatusPillTone): Color = when (tone) {
    StatusPillTone.ACTIVE, StatusPillTone.SUCCESS -> PetTokens.GreenSoft
    StatusPillTone.ATTENTION -> PetTokens.AmberSoft
    StatusPillTone.FAILURE -> PetTokens.RedSoft
    StatusPillTone.NEUTRAL -> PetTokens.GraySoft
}

private fun pillForeground(tone: StatusPillTone): Color = when (tone) {
    StatusPillTone.ACTIVE, StatusPillTone.SUCCESS -> PetTokens.GreenInk
    StatusPillTone.ATTENTION -> PetTokens.AmberInk
    StatusPillTone.FAILURE -> PetTokens.RedInk
    StatusPillTone.NEUTRAL -> PetTokens.Ink2
}

private fun hhmmFormatter(): (Long) -> String {
    val fmt = SimpleDateFormat("HH:mm", Locale.US)
    return { ms -> fmt.format(Date(ms)) }
}

/**
 * 1 / 3 / 10 节. Restores the choice that the worktree had dropped, in the original visual style.
 *
 * Disabled (and dimmed) whenever the environment cannot start, so the selector never looks like it
 * will do something while 开始 is blocked for a real reason.
 */
@Composable
private fun RoundSelectorRow(selected: Int, enabled: Boolean, onSelect: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SessionStartRequest.ROUND_OPTIONS.forEach { n ->
            val isSelected = n == selected
            Box(
                Modifier.weight(1f).height(36.dp)
                    .background(
                        if (isSelected) PetTokens.BlueSoft else PetTokens.GraySoft,
                        RoundedCornerShape(PetTokens.NextRadius),
                    )
                    .clickable(enabled = enabled) { onSelect(n) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "$n 节", fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    color = if (isSelected) PetTokens.BlueInk else PetTokens.Ink2,
                )
            }
        }
    }
}

/**
 * The three stage entries, one row each, so every capability can be started on its own.
 *
 * Ordering is by cost, cheapest first: 健康检查 spends nothing, 护理 spends coins on
 * cookies/soap, 托管 adds a lesson on top and consumes the shared daily budget. Each button
 * names what it does in its own label, so the cost of a tap is never a surprise.
 */
@Composable
private fun SessionModeRow(
    enabled: Boolean,
    studyEnabled: Boolean,
    workEnabled: Boolean,
    displayId: Int?,
    rounds: Int,
    context: android.content.Context,
) {
    fun start(mode: SessionMode) {
        if (!enabled || displayId == null) return
        // 托管/学习 carry the selected batch; 健康检查/护理/打工 are always a single pass.
        val requested = SessionStartRequest.plannedRounds(mode, SessionStartRequest.sanitize(rounds))
        ContextCompat.startForegroundService(
            context,
            PetAutomationService.startIntent(context, displayId, requested, mode),
        )
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PetFootButton(
                text = "健康检查",
                tone = if (enabled) PetButtonTone.PRIMARY else PetButtonTone.DISABLED,
                onClick = { start(SessionMode.HEALTH_CHECK) },
                modifier = Modifier.weight(1f),
            )
            PetFootButton(
                text = "打工",
                tone = if (workEnabled) PetButtonTone.PRIMARY else PetButtonTone.DISABLED,
                onClick = { if (workEnabled) start(SessionMode.WORK) },
                modifier = Modifier.weight(1f),
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PetFootButton(
                text = "护理",
                tone = if (enabled) PetButtonTone.PRIMARY else PetButtonTone.DISABLED,
                onClick = { start(SessionMode.CARE) },
                modifier = Modifier.weight(1f),
            )
            PetFootButton(
                text = "学习",
                tone = if (studyEnabled) PetButtonTone.PRIMARY else PetButtonTone.DISABLED,
                onClick = { if (studyEnabled) start(SessionMode.STUDY) },
                modifier = Modifier.weight(1f),
            )
        }
        PetFootButton(
            text = "托管（护理+上课）",
            tone = if (studyEnabled) PetButtonTone.PRIMARY else PetButtonTone.DISABLED,
            onClick = { if (studyEnabled) start(SessionMode.FULL) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
