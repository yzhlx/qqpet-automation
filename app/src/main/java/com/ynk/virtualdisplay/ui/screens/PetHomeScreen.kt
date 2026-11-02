package com.ynk.virtualdisplay.ui.screens

import android.content.Intent
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.ynk.virtualdisplay.automation.CareStep
import com.ynk.virtualdisplay.automation.CourseCatalogStore
import com.ynk.virtualdisplay.automation.DailyBudgetStore
import com.ynk.virtualdisplay.automation.PetAutomationService
import com.ynk.virtualdisplay.automation.PetManagedRuntime
import com.ynk.virtualdisplay.automation.PetProfile
import com.ynk.virtualdisplay.automation.PetRuntime
import com.ynk.virtualdisplay.automation.PlanV2
import com.ynk.virtualdisplay.automation.PlanV2Store
import com.ynk.virtualdisplay.automation.SessionMode
import com.ynk.virtualdisplay.automation.SessionPlan
import com.ynk.virtualdisplay.floating.OverlayPermission
import com.ynk.virtualdisplay.automation.SessionPlanStore
import com.ynk.virtualdisplay.automation.StudyStep
import com.ynk.virtualdisplay.automation.WorkStep
import com.ynk.virtualdisplay.data.model.ShizukuState
import com.ynk.virtualdisplay.ui.data.LogEntry
import com.ynk.virtualdisplay.ui.data.PetEvidenceRepository
import com.ynk.virtualdisplay.ui.data.SessionEvidence
import com.ynk.virtualdisplay.ui.data.SessionRunRecord
import com.ynk.virtualdisplay.ui.data.SessionStartRequest
import com.ynk.virtualdisplay.ui.display.DisplayActivity
import com.ynk.virtualdisplay.ui.kit.PetButtonTone
import com.ynk.virtualdisplay.ui.kit.PetCard
import com.ynk.virtualdisplay.ui.kit.PetChevron
import com.ynk.virtualdisplay.ui.kit.PetChip
import com.ynk.virtualdisplay.ui.kit.PetDivider
import com.ynk.virtualdisplay.ui.kit.PetDot
import com.ynk.virtualdisplay.ui.kit.PetFootButton
import com.ynk.virtualdisplay.ui.kit.PetGlyph
import com.ynk.virtualdisplay.ui.kit.PetIcon
import com.ynk.virtualdisplay.ui.kit.PetNote
import com.ynk.virtualdisplay.ui.kit.PetPill
import com.ynk.virtualdisplay.ui.kit.PetProgressBar
import com.ynk.virtualdisplay.ui.kit.PetSegmentBar
import com.ynk.virtualdisplay.ui.kit.PetSection
import com.ynk.virtualdisplay.ui.log.PetLogEntry
import com.ynk.virtualdisplay.ui.log.PetLogPresentation
import com.ynk.virtualdisplay.R
import com.ynk.virtualdisplay.ui.main.MainViewModel
import com.ynk.virtualdisplay.ui.main.PetTitleBar
import com.ynk.virtualdisplay.ui.theme.PetTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * 托管 home. Every value on this screen comes from a real source:
 *  - live run state / progress / pause reason : PetManagedRuntime + PetRuntime (read-only flows)
 *  - screenshots and the last-run summary     : the evidence directory written by the controller
 *  - the log timeline                          : files/pet_mvp.log
 *
 * The control footer exposes ONLY what the automation layer really has: START and STOP. There is no
 * manual pause/resume API, so no pause button is drawn.
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
    var planV2 by remember { mutableStateOf<PlanV2?>(null) }
    var logRows by remember { mutableStateOf<List<PetLogEntry>>(emptyList()) }
    var budgetUsed by remember { mutableStateOf(0) }
    // The saved plan decides the batch: course, round count, thresholds and budget. It replaces the
    // old in-memory 1/3/10 selector, which reset every time the screen was left.
    var plan by remember { mutableStateOf(SessionPlan()) }
    var catalogFresh by remember { mutableStateOf(false) }

    LaunchedEffect(runtime.running, managed.sessionId) {
        suspend fun reload() = withContext(Dispatchers.IO) {
            latest = repo.latestSession()
            logRows = PetLogPresentation.timeline(repo.logEntries(limit = 600))
            budgetUsed = DailyBudgetStore(context).usedMinutes()
            plan = SessionPlanStore(context).load()
            planV2 = runCatching { PlanV2Store(context).load() }.getOrNull()
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

    var dialog by remember { mutableStateOf<HomeDialog?>(null) }

    Column(Modifier.fillMaxSize()) {
        if (runtime.running) {
            PetTitleBar("宠物托管", crab = R.drawable.crab_peek)
        } else {
            PetTitleBar(
                "养宠自动化",
                subtitle = "一只厚礼蟹 · 宠物托管助手",
                crab = when {
                    !controls.startEnabled -> R.drawable.crab_clipboard
                    pill.tone == StatusPillTone.SUCCESS -> R.drawable.crab_gift
                    else -> R.drawable.crab_reading
                },
            )
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 15.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            Spacer(Modifier.height(2.dp))

            // ── 状态卡：三项真实读数 + 状态胶囊 ──
            PetCard {
                // One confirmation line when every reading agrees; otherwise each cell keeps its own,
                // so a stale or unknown reading is never covered by another reading's time.
                val sharedMeta = health.map { it.meta }.distinct().singleOrNull()
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                    health.forEachIndexed { index, stat ->
                        if (index > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(PetTokens.Line))
                        HealthStatCell(stat, showMeta = sharedMeta == null, modifier = Modifier.weight(1f))
                    }
                }
                if (sharedMeta != null) {
                    Spacer(Modifier.height(7.dp))
                    Text(sharedMeta, Modifier.fillMaxWidth(), fontSize = 11.5.sp, color = PetTokens.Ink3,
                        textAlign = TextAlign.Center)
                }
                Spacer(Modifier.height(10.dp))
                PetDivider()
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    PetPill(pill.text, pillBackground(pill.tone), pillForeground(pill.tone))
                    Spacer(Modifier.width(8.dp))
                    PetNote(subtitleFor(managedView, runtime.running, latest?.record, fmt))
                }
            }

            if (runtime.running) {
                CurrentTaskCard(managedView, progress)
                HomeLogCard(logRows, onOpenLogs)
            } else {
                if (!controls.startEnabled || !catalogFresh) {
                    SetupChecklistCard(
                        startReady = controls.startEnabled,
                        shizukuReady = uiState.shizukuState is ShizukuState.Ready,
                        displayReady = readyDisplay != null && readyDisplay.id in managedIds,
                        catalogReady = catalogFresh,
                        onOpenDisplay = {
                            viewModel.createPetDisplay { displayId ->
                                android.widget.Toast.makeText(
                                    context, "虚拟屏已创建，打开宠物界面即可",
                                    android.widget.Toast.LENGTH_LONG,
                                ).show()
                                context.startActivity(
                                    Intent(context, DisplayActivity::class.java).putExtra("display_id", displayId)
                                )
                            }
                        },
                        onOpenPlanSheet = onOpenPlanSheet,
                    )
                }
                TodayProgressCard(budgetUsed, budgetCap)
                // Separate entries, one per capability: reading the pet must never also spend coins
                // or the shared daily budget.
                SessionModeRow(
                    enabled = controls.startEnabled,
                    studyEnabled = controls.startEnabled && catalogFresh,
                    displayId = readyDisplay?.id,
                    rounds = plan.rounds,
                    context = context,
                )
                PetNote("这四项各自独立，只做所选动作。")
                if (!OverlayPermission.canDrawOverlays(context)) {
                    PetNote("未开启悬浮窗：托管照常运行，但离开 App 后看不到状态胶囊（设置 → 悬浮窗）。")
                }
            }

            Spacer(Modifier.height(4.dp))
        }

        // ── 底部主控：只有开始 / 停止是真实能力 ──
        // Opaque background: the scrolled cards above end where this footer begins, so nothing may
        // show through underneath the buttons.
        Column(
            Modifier.background(PetTokens.Bg)
                .padding(horizontal = 15.dp).padding(top = 8.dp, bottom = 12.dp),
        ) {
            if (!runtime.running) {
                val planReady = controls.startEnabled &&
                    (planV2?.steps?.none { it is StudyStep } == true || catalogFresh)
                Row(
                    Modifier.fillMaxWidth().clickable { onOpenPlanSheet() }
                        .padding(horizontal = 4.dp).padding(bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("托管方案", fontSize = 12.5.sp, color = PetTokens.Ink2)
                    Spacer(Modifier.weight(1f))
                    Text(planSummary(planV2, plan), fontSize = 12.5.sp, color = PetTokens.Ink2, maxLines = 1)
                    PetChevron()
                }
                StartManagedButton(planReady) {
                    val displayId = readyDisplay?.id ?: return@StartManagedButton
                    val saved = planV2
                    if (saved != null) {
                        ContextCompat.startForegroundService(
                            context, SessionStartRequest.forPlan(saved, displayId, UUID.randomUUID().toString()),
                        )
                    } else {
                        ContextCompat.startForegroundService(context, PetAutomationService.startIntent(
                            context, displayId,
                            SessionStartRequest.plannedRounds(SessionMode.FULL, SessionStartRequest.sanitize(plan.rounds)),
                            SessionMode.FULL,
                        ))
                    }
                }
            } else {
                PetFootButton(
                    text = "停止托管",
                    tone = PetButtonTone.DANGER,
                    onClick = { dialog = HomeDialog.ConfirmStop },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    when (dialog) {
        HomeDialog.ConfirmStop -> AlertDialog(
            onDismissRequest = { dialog = null },
            containerColor = PetTokens.Surface,
            titleContentColor = PetTokens.Ink,
            textContentColor = PetTokens.Ink2,
            title = { Text("停止托管？") },
            text = { Text("将结束当前批次。已完成的节会写入运行记录，未完成的不会被标记为已完成。") },
            confirmButton = {
                TextButton(onClick = {
                    dialog = null
                    context.startService(PetAutomationService.stopIntent(context))
                }) { Text("停止", color = PetTokens.Red) }
            },
            dismissButton = {
                TextButton(onClick = { dialog = null }) { Text("取消", color = PetTokens.Ink2) }
            },
        )
        null -> Unit
    }
}

private enum class HomeDialog { ConfirmStop }

private fun planSummary(saved: PlanV2?, plan: SessionPlan): String {
    if (saved == null) return "${plan.courseName} × ${plan.rounds} 节"
    return saved.steps.joinToString(" + ") { step ->
        when (step) {
            is StudyStep -> "学习 ${step.rounds} 节"
            is CareStep -> "护理"
            is WorkStep -> "打工"
        }
    }
}

/** Minutes are the real source: the daily cap is counted in minutes shared by 打工 and 学习. */
@Composable
private fun TodayProgressCard(used: Int, cap: Int) {
    PetCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PetIcon(PetGlyph.Study, 24.dp)
            Spacer(Modifier.width(10.dp))
            Column {
                Text("今日进度", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = PetTokens.Ink)
                Spacer(Modifier.height(2.dp))
                Text("已用 $used / 共 $cap 分钟", fontSize = 12.sp, color = PetTokens.Ink2)
            }
        }
        Spacer(Modifier.height(10.dp))
        PetProgressBar(if (cap > 0) used.toFloat() / cap else 0f)
    }
}

@Composable
private fun StartManagedButton(enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(PetTokens.BtnRadius)
    Row(
        Modifier.fillMaxWidth().height(PetTokens.BtnHeight)
            .background(if (enabled) PetTokens.Blue else PetTokens.GraySoft, shape)
            .clickable(enabled = enabled, onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val fg = if (enabled) Color.White else PetTokens.Ink3
        PetIcon(PetGlyph.Play, 17.dp, fg)
        Spacer(Modifier.width(8.dp))
        Text("开始托管", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = fg)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.width(1.dp).height(14.dp).background(fg.copy(alpha = 0.4f)))
        Spacer(Modifier.width(10.dp))
        Text("执行托管方案", fontSize = 12.sp, color = fg)
    }
}

/**
 * Shown instead of a dead, greyed-out footer: when the environment gate is closed the user gets the
 * three concrete preconditions with their real state and a jump target for each. This exists because
 * "buttons disabled + one sentence of prose" is indistinguishable from "点运行没反应" on a fresh
 * install (the automation needs a managed 720×1600 display and a course catalog first).
 */
@Composable
private fun SetupChecklistCard(
    startReady: Boolean,
    shizukuReady: Boolean,
    displayReady: Boolean,
    catalogReady: Boolean,
    onOpenDisplay: () -> Unit,
    onOpenPlanSheet: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PetCard(modifier) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (startReady) "还差一步：读取课表后才能上课/托管"
                else "还不能开始：先完成以下 3 步",
                fontSize = 14.sp, fontWeight = FontWeight.Bold, color = PetTokens.Ink,
                modifier = Modifier.weight(1f),
            )
            PetChip(
                if (startReady) "差课表" else "未就绪",
                PetTokens.AmberSoft, PetTokens.AmberInk,
            )
        }
        Spacer(Modifier.height(8.dp))
        SetupStepRow(
            index = "1", title = "Shizuku",
            state = if (shizukuReady) "已连接" else "未连接", done = shizukuReady,
        )
        SetupStepRow(
            index = "2", title = "后台画面",
            state = if (displayReady) "已托管" else "未托管", done = displayReady,
            actionText = "一键创建", onAction = onOpenDisplay,
        )
        SetupStepRow(
            index = "3", title = "课表已读取",
            state = if (catalogReady) "已完成" else "未完成", done = catalogReady,
            actionText = "去刷新", onAction = onOpenPlanSheet,
        )
        Spacer(Modifier.height(6.dp))
        PetNote(
            "完成前下面的按钮保持灰色，这些步骤都不花游戏金币。" +
                "点「一键创建」会自动建好后台画面并打开宠物界面，不用手动搜索。"
        )
    }
}

@Composable
private fun SetupStepRow(
    index: String,
    title: String,
    state: String,
    done: Boolean,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PetDot(if (done) PetTokens.GreenInk else PetTokens.AmberInk)
        Spacer(Modifier.width(7.dp))
        Text("$index. $title", fontSize = 13.sp, fontWeight = FontWeight(650), color = PetTokens.Ink)
        Spacer(Modifier.width(6.dp))
        Text(
            state, fontSize = 12.sp,
            color = if (done) PetTokens.GreenInk else PetTokens.AmberInk,
            modifier = Modifier.weight(1f),
        )
        if (actionText != null && onAction != null) {
            Text(
                actionText, fontSize = 12.5.sp, fontWeight = FontWeight(650), color = PetTokens.Blue,
                modifier = Modifier.clickable(onClick = onAction).padding(horizontal = 6.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun HealthStatCell(stat: HealthStat, showMeta: Boolean, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
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
                fontSize = 26.sp, fontWeight = FontWeight.Bold,
                color = if (stat.status == HealthStatStatus.KNOWN && stat.current) PetTokens.Ink else PetTokens.Ink3,
                lineHeight = 28.sp,
            )
        }
        if (showMeta) {
            Spacer(Modifier.height(5.dp))
            Text(
                stat.meta, fontSize = 10.sp,
                color = if (stat.status == HealthStatStatus.STALE) PetTokens.AmberInk else PetTokens.Ink3,
                maxLines = 1,
            )
        }
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
        val studying = view.flowText.contains("STUDY", true) || view.flowText.contains("学习", true)
        if (progress.total > 0 && studying) {
            Spacer(Modifier.height(12.dp))
            PetSegmentBar(progress.total, progress.completed, view.lifecycle == ManagedLifecycle.RUNNING)
            Spacer(Modifier.height(7.dp))
            Text("已完成 ${progress.completed} / 共 ${progress.total} 节", fontSize = 12.5.sp, color = PetTokens.Ink2)
        }
        val stage = StudyStepTrack.indexOf(view.stepText)
        if (studying && stage != null) {
            Spacer(Modifier.height(11.dp))
            Row(Modifier.fillMaxWidth()) {
                StudyStepTrack.LABELS.forEachIndexed { index, label ->
                    StageCell(label, index, stage, Modifier.weight(1f))
                }
            }
        }
        Spacer(Modifier.height(9.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            PetIcon(if (view.sessionIsCurrent) PetGlyph.Check else PetGlyph.Info, 13.dp,
                if (view.sessionIsCurrent) PetTokens.Green else PetTokens.Ink3)
            Spacer(Modifier.width(5.dp))
            Text(
                if (view.sessionIsCurrent) "已运行 ${view.durationValue}"
                else "显示的不是本次读数 · 已运行 ${view.durationValue}",
                fontSize = 11.5.sp, color = PetTokens.Ink2, maxLines = 1,
            )
        }
        if (view.lifecycle == ManagedLifecycle.PAUSED || view.lifecycle == ManagedLifecycle.RESUMING) {
            Spacer(Modifier.height(10.dp))
            PausedBanner(view)
        }
        if (!studying || stage == null) {
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
}

@Composable
private fun StageCell(label: String, index: Int, stage: Int, modifier: Modifier = Modifier) {
    val current = index == stage
    Column(
        modifier.background(if (current) PetTokens.BlueSoft else Color.Transparent, RoundedCornerShape(PetTokens.TileRadius))
            .padding(vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PetIcon(
            when {
                index < stage -> PetGlyph.Check
                current -> PetGlyph.Clock
                else -> PetGlyph.Info
            },
            17.dp,
            if (index > stage) PetTokens.Chevron else PetTokens.Blue,
        )
        Spacer(Modifier.height(4.dp))
        Text(label, fontSize = 10.sp, maxLines = 1,
            color = if (current) PetTokens.BlueInk else PetTokens.Ink3,
            fontWeight = if (current) FontWeight(650) else FontWeight.Normal)
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

// ─────────────────────────── helpers ───────────────────────────

private fun flowTitle(view: ManagedRuntimeView): String {
    val base = when {
        view.flowText.contains("STUDY", true) || view.flowText.contains("学习", true) -> "自动学习"
        view.flowText.contains("CARE", true) || view.flowText.contains("护理", true) -> "日常护理"
        view.flowText.contains("WORK", true) -> "打工"
        view.flowText.contains("NAV", true) -> "页面导航"
        view.flowText.contains("OBS", true) -> "状态读取"
        else -> view.flowText
    }
    // Round 0 is the part of a plan before the first lesson; 打工 and 护理 have no 节 to count.
    val round = view.roundOfTotal?.takeIf { it.first > 0 && base == "自动学习" }
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
    else -> when {
        view.flowText.contains("WORK", true) -> "正在打工"
        view.flowText.contains("CARE", true) -> "正在护理宠物"
        else -> "正在执行本步骤"
    }
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
        "上次 ${fmt(record.startedAtMs)} · ${lastRunNote(record)}"
    else -> "尚未进行托管"
}

private fun lastRunNote(record: SessionRunRecord?): String = when (record?.terminalState) {
    null -> "无记录"
    "COMPLETED" -> "正常结束"
    "STOPPED" -> "手动停止"
    "FAILED" -> "已自动停止"
    else -> record.terminalState
}

private fun dotColorFor(name: String): Color = when (name) {
    "体力" -> PetTokens.Green
    "清洁" -> PetTokens.WaterDot
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
 * The four single-action entries, cheapest first: 健康检查 spends nothing, the others spend what their
 * own label says. Disabled tiles stay in place so the row never reflows while the setup is incomplete.
 */
@Composable
private fun SessionModeRow(
    enabled: Boolean,
    studyEnabled: Boolean,
    displayId: Int?,
    rounds: Int,
    context: android.content.Context,
) {
    fun start(mode: SessionMode) {
        if (displayId == null) return
        // 学习 carries the selected batch; 健康检查/护理/打工 are always a single pass.
        val requested = SessionStartRequest.plannedRounds(mode, SessionStartRequest.sanitize(rounds))
        ContextCompat.startForegroundService(
            context,
            PetAutomationService.startIntent(context, displayId, requested, mode),
        )
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ActionTile("健康检查", PetGlyph.Care, enabled, Modifier.weight(1f)) { start(SessionMode.HEALTH_CHECK) }
        ActionTile("打工", PetGlyph.Coin, enabled, Modifier.weight(1f)) { start(SessionMode.WORK) }
        ActionTile("护理", PetGlyph.Brush, enabled, Modifier.weight(1f)) { start(SessionMode.CARE) }
        ActionTile("学习", PetGlyph.Study, studyEnabled, Modifier.weight(1f)) { start(SessionMode.STUDY) }
    }
}

@Composable
private fun ActionTile(
    label: String,
    glyph: PetGlyph,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier.background(PetTokens.Surface, RoundedCornerShape(PetTokens.CardRadius))
            .clickable(enabled = enabled, onClick = onClick).padding(vertical = 13.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PetIcon(glyph, 24.dp, alpha = if (enabled) 1f else 0.35f)
        Spacer(Modifier.height(7.dp))
        Text(label, fontSize = 12.5.sp, fontWeight = FontWeight(650),
            color = if (enabled) PetTokens.Ink else PetTokens.Ink3, maxLines = 1)
    }
}
