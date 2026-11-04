package com.ynk.virtualdisplay.ui.screens

import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.ynk.virtualdisplay.automation.CareStep
import com.ynk.virtualdisplay.automation.CourseCatalog
import com.ynk.virtualdisplay.automation.CourseCatalogStore
import com.ynk.virtualdisplay.automation.DailyBudgetStore
import com.ynk.virtualdisplay.automation.ExecutionProfile
import com.ynk.virtualdisplay.automation.PetAutomationService
import com.ynk.virtualdisplay.automation.PetRuntime
import com.ynk.virtualdisplay.automation.PlanStep
import com.ynk.virtualdisplay.automation.PlanStepType
import com.ynk.virtualdisplay.automation.AdventureStep
import com.ynk.virtualdisplay.automation.SignInStep
import com.ynk.virtualdisplay.automation.MailboxStep
import com.ynk.virtualdisplay.automation.LuckyBagStep
import com.ynk.virtualdisplay.automation.PlanV2
import com.ynk.virtualdisplay.automation.PlanV2Store
import com.ynk.virtualdisplay.automation.PlanValidator
import com.ynk.virtualdisplay.automation.RewardConfidence
import com.ynk.virtualdisplay.automation.SessionPlan
import com.ynk.virtualdisplay.automation.SessionPlanStore
import com.ynk.virtualdisplay.automation.StudyStep
import com.ynk.virtualdisplay.automation.WorkCatalog
import com.ynk.virtualdisplay.automation.WorkStep
import com.ynk.virtualdisplay.automation.WorkTrigger
import com.ynk.virtualdisplay.ui.data.SessionStartRequest
import com.ynk.virtualdisplay.ui.kit.PetButtonTone
import com.ynk.virtualdisplay.ui.kit.PetCard
import com.ynk.virtualdisplay.ui.kit.PetFootButton
import com.ynk.virtualdisplay.ui.kit.PetGlyph
import com.ynk.virtualdisplay.ui.kit.PetIcon
import com.ynk.virtualdisplay.ui.kit.PetNote
import com.ynk.virtualdisplay.ui.kit.PetSection
import com.ynk.virtualdisplay.ui.kit.PetStepper
import com.ynk.virtualdisplay.ui.main.PetTitleBar
import com.ynk.virtualdisplay.ui.theme.PetTokens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** Existing parameter controls now edit an explicit selection; unselected parameters never gate save. */
@Composable
fun PetPlanSheetScreen(onClose: () -> Unit, onStarted: () -> Unit, displayId: Int?, onOpenManual: (() -> Unit)? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var plan by remember { mutableStateOf(SessionPlan()) }
    var planId by remember { mutableStateOf(UUID.randomUUID().toString()) }
    var name by remember { mutableStateOf("我的方案") }
    var selectedWork by remember { mutableStateOf(false) }
    var selectedCare by remember { mutableStateOf(false) }
    var selectedStudy by remember { mutableStateOf(false) }
    var selectedAdventure by remember { mutableStateOf(false) }
    var selectedSignIn by remember { mutableStateOf(false) }
    var selectedMailbox by remember { mutableStateOf(false) }
    var selectedLuckyBag by remember { mutableStateOf(false) }
    var luckyBagMaxDaily by remember { mutableStateOf(5) }
    var luckyBagIntervalMinutes by remember { mutableStateOf(30) }
    var coinFloorEnabled by remember { mutableStateOf(false) }
    var coinFloorValue by remember { mutableStateOf(100) }
    var dailySpendCapEnabled by remember { mutableStateOf(false) }
    var dailySpendCapValue by remember { mutableStateOf(500) }
    var adventureMode by remember { mutableStateOf(com.ynk.virtualdisplay.automation.AdventureMode.NEARBY_WALK) }
    var adventureRounds by remember { mutableStateOf(1) }
    var adventureMaxDaily by remember { mutableStateOf(5) }
    var stepIds by remember { mutableStateOf(emptyMap<PlanStepType, String>()) }
    var legacySource by remember { mutableStateOf<String?>(null) }
    var showCompatibilityNote by remember { mutableStateOf(false) }
    var catalog by remember { mutableStateOf<CourseCatalog?>(null) }
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    var lastSavedSnapshot by remember { mutableStateOf<PlanV2?>(null) }
    var loadNote by remember { mutableStateOf<String?>(null) }
    var saveError by remember { mutableStateOf<String?>(null) }
    val runtime by PetRuntime.state.collectAsState()
    val verified = remember { WorkCatalog().all().filter { it.confidence == RewardConfidence.VERIFIED } }

    LaunchedEffect(Unit) {
        val loaded = withContext(Dispatchers.IO) {
            Triple(runCatching { SessionPlanStore(context).load() }, PlanV2Store(context).load(),
                File(context.filesDir, PlanV2Store.FILE_NAME).exists())
        }
        plan = loaded.first.getOrElse { SessionPlan() }
        val stored = loaded.second
        if (stored != null) {
            val work = stored.steps.filterIsInstance<WorkStep>().singleOrNull()
            val care = stored.steps.filterIsInstance<CareStep>().singleOrNull()
            val study = stored.steps.filterIsInstance<StudyStep>().singleOrNull()
            planId = stored.planId
            name = stored.name
            stepIds = stored.steps.associate { it.type to it.stepId }
            legacySource = stored.legacySource
            val signIn = stored.steps.filterIsInstance<SignInStep>().singleOrNull()
            val mailbox = stored.steps.filterIsInstance<MailboxStep>().singleOrNull()
            val luckyBag = stored.steps.filterIsInstance<LuckyBagStep>().singleOrNull()
            val adventure = stored.steps.filterIsInstance<AdventureStep>().singleOrNull()
            selectedWork = work != null; selectedCare = care != null; selectedStudy = study != null; selectedAdventure = adventure != null
            selectedSignIn = signIn != null; selectedMailbox = mailbox != null; selectedLuckyBag = luckyBag != null
            if (luckyBag != null) {
                luckyBagMaxDaily = luckyBag.maxDailyCount
                luckyBagIntervalMinutes = luckyBag.scanIntervalMinutes
            }
            coinFloorEnabled = stored.minCoinFloor != null
            coinFloorValue = stored.minCoinFloor ?: 100
            dailySpendCapEnabled = stored.maxDailyNetSpend != null
            dailySpendCapValue = stored.maxDailyNetSpend ?: 500
            if (adventure != null) {
                adventureMode = adventure.mode
                adventureRounds = adventure.rounds
                adventureMaxDaily = adventure.maxDailyRounds ?: 5
            }
            plan = plan.copy(courseId = study?.courseId ?: plan.courseId, rounds = study?.rounds ?: plan.rounds,
                targetAttribute = study?.targetAttribute ?: plan.targetAttribute,
                energyThreshold = care?.energyThreshold ?: plan.energyThreshold,
                cleanlinessThreshold = care?.cleanlinessThreshold ?: plan.cleanlinessThreshold,
                dailyBudgetMinutes = stored.dailyBudgetMinutes,
                workBeforeStudy = work?.trigger == WorkTrigger.IF_FUNDS_SHORT,
                workOptionId = work?.workOptionId ?: plan.workOptionId)
            showCompatibilityNote = stored.executionProfile == ExecutionProfile.LEGACY_MANAGED
            lastSavedSnapshot = stored
            saved = true
        } else {
            // No saved selection is manufactured, especially when an existing document is invalid.
            loadNote = if (loaded.third) "已保存的方案无法读取，原文件已保留。请重新选择动作并保存。"
                else if (loaded.first.isFailure) "原托管参数无法读取，原文件已保留。请重新填写并选择动作。"
                else "勾选要执行的动作后保存；可以只选一项。"
        }
        loading = false
    }
    LaunchedEffect(runtime.running, runtime.status) {
        catalog = withContext(Dispatchers.IO) { runCatching { CourseCatalogStore(context).load() }.getOrNull() }
    }

    val unlocked = catalog?.unlocked().orEmpty()
    val fresh = catalog?.isUsable(System.currentTimeMillis()) == true
    val busy = loading || saving
    val selectedCourse = unlocked.firstOrNull { it.id == plan.courseId }
    val validationMessage = when {
        loading -> "正在读取方案…"
        !selectedSignIn && !selectedMailbox && !selectedLuckyBag && !selectedWork && !selectedCare && !selectedStudy && !selectedAdventure -> "请选择至少一个动作。"
        selectedWork && verified.none { it.id == plan.workOptionId } -> "请选择打工岗位。"
        selectedStudy && !fresh && plan.targetAttribute == null && catalog == null -> "请先读取我的课程或选择上方目标属性。"
        selectedStudy && plan.targetAttribute == null && selectedCourse?.durationMinutes == null -> "请选择一门时长已读出的课程。"
        else -> null
    }
    fun changed() { saved = false; lastSavedSnapshot = null; saveError = null }
    fun persistSelection(runAfterSave: Boolean) {
        if (loading || saving || validationMessage != null || (!runAfterSave && saved)) return
        if (runAfterSave && (displayId == null || displayId <= 0 || runtime.running)) return
        saving = true
        scope.launch {
            var didSave = false
            try {
                val steps = buildList<PlanStep> {
                    if (selectedSignIn) add(SignInStep(stepIds[PlanStepType.SIGN_IN] ?: "$planId:sign_in"))
                    if (selectedMailbox) add(MailboxStep(stepIds[PlanStepType.MAILBOX] ?: "$planId:mailbox"))
                    if (selectedLuckyBag) add(LuckyBagStep(stepIds[PlanStepType.LUCKY_BAG] ?: "$planId:lucky_bag", maxDailyCount = luckyBagMaxDaily, scanIntervalMinutes = luckyBagIntervalMinutes))
                    if (selectedWork) add(WorkStep(stepIds[PlanStepType.WORK] ?: "$planId:work",
                        plan.workOptionId ?: error("未选择岗位"),
                        if (selectedStudy && plan.workBeforeStudy) WorkTrigger.IF_FUNDS_SHORT else WorkTrigger.ALWAYS))
                    if (selectedCare) add(CareStep(stepIds[PlanStepType.CARE] ?: "$planId:care",
                        plan.energyThreshold, plan.cleanlinessThreshold))
                    if (selectedStudy) add(StudyStep(stepIds[PlanStepType.STUDY] ?: "$planId:study", plan.courseId, plan.rounds, plan.targetAttribute))
                    if (selectedAdventure) add(AdventureStep(stepIds[PlanStepType.ADVENTURE] ?: "$planId:adventure", adventureMode, adventureRounds, maxDailyRounds = adventureMaxDaily))
                }
                val snapshot = PlanV2(planId = planId, name = name, dailyBudgetMinutes = plan.dailyBudgetMinutes,
                    steps = steps, executionProfile = ExecutionProfile.SELECTED_ACTIONS, legacySource = legacySource,
                    minCoinFloor = if (coinFloorEnabled) coinFloorValue else null,
                    maxDailyNetSpend = if (dailySpendCapEnabled) dailySpendCapValue else null)
                PlanValidator.validate(snapshot, verified.map { it.id }.toSet())
                withContext(Dispatchers.IO) { PlanV2Store(context).save(snapshot) }
                didSave = true
                lastSavedSnapshot = snapshot
                stepIds = snapshot.steps.associate { it.type to it.stepId }
                saved = true; saveError = null; loadNote = null; showCompatibilityNote = false
                if (runAfterSave) {
                    val target = displayId?.takeIf { it > 0 } ?: error("请先选择虚拟屏")
                    check(!PetRuntime.state.value.running) { "已有任务正在运行，请先停止或等待完成" }
                    val intent = SessionStartRequest.forPlan(lastSavedSnapshot ?: error("方案尚未保存"),
                        target, UUID.randomUUID().toString())
                    ContextCompat.startForegroundService(context, intent)
                    onStarted()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                saveError = if (didSave) "方案已保存，启动失败：${error.message}" else "保存失败：${error.message}"
            } finally { saving = false }
        }
    }

    Column(Modifier.fillMaxSize()) {
        PetTitleBar("运行方案", onBack = onClose, crab = com.ynk.virtualdisplay.R.drawable.crab_peek)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 15.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp)) {
            if (onOpenManual != null) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        Modifier
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(PetTokens.ChipRadius))
                            .background(PetTokens.Surface)
                            .clickable { onOpenManual() }
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        PetIcon(PetGlyph.Help, 13.dp, PetTokens.Ink2)
                        Spacer(Modifier.width(4.dp))
                        Text("方案配置攻略", fontSize = 11.5.sp, color = PetTokens.Ink2)
                    }
                }
            }
            PetCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PetSection("选择执行动作")
                    Spacer(Modifier.width(6.dp))
                    PetNote("可多选")
                }
                Spacer(Modifier.height(9.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionChoice("每日签到", PetGlyph.Check, selectedSignIn, !busy, Modifier.weight(1f)) { selectedSignIn = it; changed() }
                    ActionChoice("信箱待领", PetGlyph.Bell, selectedMailbox, !busy, Modifier.weight(1f)) { selectedMailbox = it; changed() }
                    ActionChoice("福袋任务", PetGlyph.Play, selectedLuckyBag, !busy, Modifier.weight(1f)) { selectedLuckyBag = it; changed() }
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionChoice("打工", PetGlyph.Coin, selectedWork, !busy, Modifier.weight(1f)) {
                        selectedWork = it
                        if (it && verified.none { option -> option.id == plan.workOptionId }) {
                            verified.firstOrNull()?.let { option -> plan = plan.copy(workOptionId = option.id) }
                        }
                        changed()
                    }
                    ActionChoice("护理", PetGlyph.Brush, selectedCare, !busy, Modifier.weight(1f)) { selectedCare = it; changed() }
                    ActionChoice("学习", PetGlyph.Study, selectedStudy, !busy, Modifier.weight(1f)) { selectedStudy = it; changed() }
                    ActionChoice("冒险", PetGlyph.Locate, selectedAdventure, !busy, Modifier.weight(1f)) { selectedAdventure = it; changed() }
                }
                Spacer(Modifier.height(8.dp))
                PetNote("按签到 → 信箱 → 福袋 → 打工 → 护理 → 学习 → 冒险的顺序执行，只做勾选的动作。")
                loadNote?.let { PetNote(it) }
                if (showCompatibilityNote) PetNote("保存为新版方案后，护理执行一次；打工前需已满足岗位条件。")
            }

            if (selectedWork) PetCard {
                PetSection("打工岗位")
                Spacer(Modifier.height(8.dp))
                if (verified.isEmpty()) PetNote("没有可用的岗位。")
                verified.forEach { option ->
                    ChoiceRow("${option.venue} · ${option.optionLabel}", "一班 ${option.durationMinutes} 分钟",
                        plan.workOptionId == option.id, !busy) { plan = plan.copy(workOptionId = option.id); changed() }
                }
                if (selectedStudy) {
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("仅金币不足时打工", fontSize = 14.sp, color = PetTokens.Ink)
                            PetNote("最多一班")
                        }
                        Switch(checked = plan.workBeforeStudy, enabled = !busy,
                            onCheckedChange = { plan = plan.copy(workBeforeStudy = it); changed() },
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = PetTokens.Blue,
                                uncheckedTrackColor = PetTokens.GraySoft,
                                uncheckedThumbColor = PetTokens.Chevron,
                                uncheckedBorderColor = PetTokens.Chevron,
                            ))
                    }
                }
            }

            if (selectedStudy) PetCard {
                PetSection("培养目标属性")
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (attr in com.ynk.virtualdisplay.automation.StudyAttribute.entries) {
                        val isSelected = plan.targetAttribute == attr.label ||
                            (plan.targetAttribute == null && com.ynk.virtualdisplay.automation.StudyAttribute.forCourseId(plan.courseId) == attr)
                        Box(
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(PetTokens.ChipRadius))
                                .background(if (isSelected) PetTokens.Blue else PetTokens.Surface)
                                .clickable(enabled = !busy) {
                                    val resolved = com.ynk.virtualdisplay.automation.StudyAttribute.resolveCourse(attr, catalog)
                                    plan = plan.copy(targetAttribute = attr.label, courseId = resolved.id)
                                    changed()
                                }
                                .padding(vertical = 7.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                attr.label,
                                fontSize = 13.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) PetTokens.Surface else PetTokens.Ink,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                PetSection("课程")
                Spacer(Modifier.height(8.dp))
                if (!fresh) PetNote("需要 7 天内读取过的课表，请先读取。")
                else if (unlocked.isEmpty()) PetNote("没有读到已解锁的课程。")
                else unlocked.forEach { course ->
                    ChoiceRow(course.name, course.durationMinutes?.let { "$it 分钟" } ?: "时长未读出",
                        course.id == plan.courseId, !busy) { plan = plan.copy(courseId = course.id); changed() }
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PetFootButton(if (runtime.running) "任务进行中" else "读取我的课程",
                        if (displayId != null && !runtime.running && !busy) PetButtonTone.GHOST else PetButtonTone.DISABLED,
                        { displayId?.let { startCourseScan(context, it) } }, Modifier.weight(1f))
                    Spacer(Modifier.width(10.dp))
                    val scannedAt = catalog?.scannedAtMs?.takeIf { it > 0 }?.let {
                        SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(it))
                    }
                    PetNote(scannedAt?.let { "已读取 ${unlocked.size} 门\n$it" } ?: "从未读取")
                }
                if (displayId == null) PetNote("后台画面未就绪，暂不能读取。")
                if (runtime.status.startsWith("课程") || runtime.status.startsWith("读取课程")) PetNote(runtime.status)
            }

            PetCard {
                PetSection("方案参数")
                Spacer(Modifier.height(4.dp))
                if (selectedStudy) ParamRow("节数", PetGlyph.Study, plan.rounds, SessionPlan.MIN_ROUNDS..SessionPlan.MAX_ROUNDS, 1, !busy) {
                    plan = plan.copy(rounds = it); changed()
                }
                if (selectedAdventure) {
                    Spacer(Modifier.height(8.dp))
                    PetCard {
                        PetSection("冒险模式 (CORE-11)")
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (mode in com.ynk.virtualdisplay.automation.AdventureMode.entries) {
                                val isSelected = adventureMode == mode
                                Box(
                                    Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (isSelected) PetTokens.BlueSoft else PetTokens.Bg)
                                        .clickable(!busy) { adventureMode = mode; changed() }
                                        .padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        mode.label,
                                        fontSize = 12.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isSelected) PetTokens.BlueInk else PetTokens.Ink2
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        PetNote(if (adventureMode == com.ynk.virtualdisplay.automation.AdventureMode.NEARBY_WALK) "附近走走：5 分钟短途探索，快速累积奇遇" else "诗和远方：30 分钟深度旅行，奖励丰富但耗时较长")
                    }
                    ParamRow("冒险轮次", PetGlyph.Locate, adventureRounds, 1..10, 1, !busy) {
                        adventureRounds = it; changed()
                    }
                    ParamRow("每日上限（轮）", PetGlyph.Locate, adventureMaxDaily, 1..20, 1, !busy) {
                        adventureMaxDaily = it; changed()
                    }
                }
                if (selectedLuckyBag) {
                    ParamRow("福袋上限（次）", PetGlyph.Play, luckyBagMaxDaily, 1..20, 1, !busy) {
                        luckyBagMaxDaily = it; changed()
                    }
                    ParamRow("扫描间隔（分）", PetGlyph.Clock, luckyBagIntervalMinutes, 5..180, 5, !busy) {
                        luckyBagIntervalMinutes = it; changed()
                    }
                }
                if (selectedStudy) PetCard {
                    PetSection("金币保护（STR-13）")
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("金币底线", fontSize = 14.sp, color = PetTokens.Ink)
                            PetNote("扣完全部学费后余额低于该值则停止学习")
                        }
                        Switch(checked = coinFloorEnabled, enabled = !busy,
                            onCheckedChange = { coinFloorEnabled = it; changed() },
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = PetTokens.Blue,
                                uncheckedTrackColor = PetTokens.GraySoft,
                                uncheckedThumbColor = PetTokens.Chevron,
                                uncheckedBorderColor = PetTokens.Chevron,
                            ))
                    }
                    if (coinFloorEnabled) {
                        ParamRow("底线金额", PetGlyph.Coin, coinFloorValue, 0..10000, 50, !busy) {
                            coinFloorValue = it; changed()
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("每日学习花费上限", fontSize = 14.sp, color = PetTokens.Ink)
                            PetNote("今日已确认花费 + 本次方案学费超过该值则停止")
                        }
                        Switch(checked = dailySpendCapEnabled, enabled = !busy,
                            onCheckedChange = { dailySpendCapEnabled = it; changed() },
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = PetTokens.Blue,
                                uncheckedTrackColor = PetTokens.GraySoft,
                                uncheckedThumbColor = PetTokens.Chevron,
                                uncheckedBorderColor = PetTokens.Chevron,
                            ))
                    }
                    if (dailySpendCapEnabled) {
                        ParamRow("上限金额", PetGlyph.Coin, dailySpendCapValue, 10..10000, 50, !busy) {
                            dailySpendCapValue = it; changed()
                        }
                    }
                }
                if (selectedCare) {
                    ParamRow("体力目标", PetGlyph.Care, plan.energyThreshold, SessionPlan.MIN_THRESHOLD..SessionPlan.MAX_THRESHOLD, 5, !busy) {
                        plan = plan.copy(energyThreshold = it); changed()
                    }
                    ParamRow("清洁目标", PetGlyph.Brush, plan.cleanlinessThreshold, SessionPlan.MIN_THRESHOLD..SessionPlan.MAX_THRESHOLD, 5, !busy) {
                        plan = plan.copy(cleanlinessThreshold = it); changed()
                    }
                }
                ParamRow("每日上限（分钟）", PetGlyph.Clock, plan.dailyBudgetMinutes, 1..DailyBudgetStore.HARD_STOP_MINUTES, 10, !busy) {
                    plan = plan.copy(dailyBudgetMinutes = it); changed()
                }
                PetNote("打工和学习共用每日分钟数，护理不计入。已达标的护理项目会跳过。")
            }
            Spacer(Modifier.height(4.dp))
        }
        Column(Modifier.padding(horizontal = 15.dp).padding(top = 9.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            (saveError ?: validationMessage)?.let { PetNote(it) }
            if (displayId == null || displayId <= 0) PetNote("后台画面未就绪，暂不能运行。")
            if (runtime.running) PetNote("已有任务正在运行，可以保存下次方案，暂不能再次启动。")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PetFootButton(if (saving) "正在保存…" else if (saved) "已保存" else "保存方案",
                    if (busy || saved || validationMessage != null) PetButtonTone.DISABLED else PetButtonTone.GHOST,
                    { persistSelection(false) }, Modifier.weight(1f))
                PetFootButton("保存并运行",
                    if (busy || validationMessage != null || runtime.running || displayId == null || displayId <= 0)
                        PetButtonTone.DISABLED else PetButtonTone.PRIMARY,
                    { persistSelection(true) }, Modifier.weight(1f))
            }
        }
    }
}

private fun startCourseScan(context: Context, displayId: Int) {
    ContextCompat.startForegroundService(context, PetAutomationService.scanCoursesIntent(context, displayId))
}

@Composable
private fun ActionChoice(label: String, glyph: PetGlyph, checked: Boolean, enabled: Boolean,
    modifier: Modifier = Modifier, onCheckedChange: (Boolean) -> Unit) {
    Row(modifier.heightIn(min = 60.dp)
        .background(if (checked) PetTokens.BlueSoft else PetTokens.GraySoft, RoundedCornerShape(PetTokens.NextRadius))
        .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onCheckedChange)
        .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled,
            colors = CheckboxDefaults.colors(checkedColor = PetTokens.Blue, uncheckedColor = PetTokens.Chevron))
        Spacer(Modifier.width(7.dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            PetIcon(glyph, 19.dp)
            Spacer(Modifier.height(3.dp))
            Text(label, fontSize = 13.sp, color = PetTokens.Ink, fontWeight = FontWeight(650), maxLines = 1)
        }
    }
}

@Composable
private fun ChoiceRow(title: String, detail: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)
        .background(if (selected) PetTokens.BlueSoft else PetTokens.GraySoft, RoundedCornerShape(PetTokens.NextRadius))
        .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight(650),
                color = if (selected) PetTokens.BlueInk else PetTokens.Ink)
            Spacer(Modifier.height(2.dp))
            Text(detail, fontSize = 11.5.sp, color = PetTokens.Ink3)
        }
        if (selected) PetIcon(PetGlyph.Check, 17.dp, PetTokens.Blue)
    }
}

@Composable
private fun ParamRow(label: String, glyph: PetGlyph, value: Int, range: IntRange, step: Int, enabled: Boolean, onValue: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        PetIcon(glyph, 20.dp)
        Spacer(Modifier.width(10.dp))
        Text(label, Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight(650), color = PetTokens.Ink)
        PetStepper(value, range, step, enabled, onValue)
    }
}
