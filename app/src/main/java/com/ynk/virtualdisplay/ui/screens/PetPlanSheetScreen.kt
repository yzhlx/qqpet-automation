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
import com.ynk.virtualdisplay.automation.CourseComparison
import com.ynk.virtualdisplay.automation.CourseReading
import com.ynk.virtualdisplay.automation.CourseReadingStore
import com.ynk.virtualdisplay.automation.WorkReading
import com.ynk.virtualdisplay.automation.WorkReadingStore
import com.ynk.virtualdisplay.automation.WorkYieldComparison
import com.ynk.virtualdisplay.automation.CourseCatalogStore
import com.ynk.virtualdisplay.automation.DailyBudgetStore
import com.ynk.virtualdisplay.automation.ExecutionProfile
import com.ynk.virtualdisplay.automation.PetAutomationService
import com.ynk.virtualdisplay.automation.PendingAction
import com.ynk.virtualdisplay.automation.PendingActionGuard
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
import com.ynk.virtualdisplay.automation.StudyAttribute
import com.ynk.virtualdisplay.automation.StudyRotation
import com.ynk.virtualdisplay.automation.StudyStep
import com.ynk.virtualdisplay.automation.StudyDailyCapPolicy
import com.ynk.virtualdisplay.automation.WorkCatalog
import com.ynk.virtualdisplay.automation.WorkStep
import com.ynk.virtualdisplay.automation.WorkTrigger
import com.ynk.virtualdisplay.ui.data.PetEvidenceRepository
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
import kotlinx.coroutines.CoroutineScope
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
    var stepIntervalEnabled by remember { mutableStateOf(false) }
    var stepIntervalValue by remember { mutableStateOf(10) }
    var failureCooldownEnabled by remember { mutableStateOf(false) }
    var failureCooldownValue by remember { mutableStateOf(30) }
    var transferOnBlock by remember { mutableStateOf(false) }
    var studyCapEnabled by remember { mutableStateOf(false) }
    var studyCapValue by remember { mutableStateOf(5) }
    /** STR-09：均衡目标的三门课（力量 → 智力 → 魅力）；空表示单属性目标。 */
    var balancedRotation by remember { mutableStateOf<List<StudyRotation>>(emptyList()) }
    var todayStudyRounds by remember { mutableStateOf(0) }
    var adventureMode by remember { mutableStateOf(com.ynk.virtualdisplay.automation.AdventureMode.NEARBY_WALK) }
    var adventureRounds by remember { mutableStateOf(1) }
    var adventureMaxDaily by remember { mutableStateOf(5) }
    var stepIds by remember { mutableStateOf(emptyMap<PlanStepType, String>()) }
    var legacySource by remember { mutableStateOf<String?>(null) }
    var showCompatibilityNote by remember { mutableStateOf(false) }
    var catalog by remember { mutableStateOf<CourseCatalog?>(null) }
    var courseReadings by remember { mutableStateOf<Map<String, CourseReading>>(emptyMap()) }
    var workReadings by remember { mutableStateOf<Map<String, WorkReading>>(emptyMap()) }
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    var lastSavedSnapshot by remember { mutableStateOf<PlanV2?>(null) }
    var loadNote by remember { mutableStateOf<String?>(null) }
    var saveError by remember { mutableStateOf<String?>(null) }
    /** 中断后结果未知的动作：等用户确认「做没做」之后才允许再开始。 */
    var pendingActions by remember { mutableStateOf<List<PendingAction>>(emptyList()) }
    var pendingRefresh by remember { mutableStateOf(0) }
    val runtime by PetRuntime.state.collectAsState()
    val verified = remember { WorkCatalog().all().filter { it.confidence == RewardConfidence.VERIFIED } }

    LaunchedEffect(pendingRefresh) {
        pendingActions = withContext(Dispatchers.IO) {
            runCatching { PendingActionGuard.pendingActions(DailyBudgetStore(context).attempts()) }
                .getOrDefault(emptyList())
        }
    }

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
            stepIntervalEnabled = stored.stepIntervalMinutes != null
            stepIntervalValue = stored.stepIntervalMinutes ?: 10
            failureCooldownEnabled = stored.failureCooldownMinutes != null
            failureCooldownValue = stored.failureCooldownMinutes ?: 30
            transferOnBlock = stored.transferOnBlock
            studyCapEnabled = stored.dailyStudyRoundCap != null
            studyCapValue = stored.dailyStudyRoundCap ?: 5
            if (adventure != null) {
                adventureMode = adventure.mode
                adventureRounds = adventure.rounds
                adventureMaxDaily = adventure.maxDailyRounds ?: 5
            }
            // STR-09：均衡方案恢复出三门轮换课，单属性方案恢复出目标属性。
            balancedRotation = study?.rotation.orEmpty()
            plan = plan.copy(courseId = study?.courseId ?: plan.courseId, rounds = study?.rounds ?: plan.rounds,
                targetAttribute = if (balancedRotation.isNotEmpty()) null else (study?.targetAttribute ?: plan.targetAttribute),
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
        todayStudyRounds = withContext(Dispatchers.IO) {
            runCatching { PetEvidenceRepository(context).confirmedStudyRoundsToday() }.getOrDefault(0)
        }
        courseReadings = withContext(Dispatchers.IO) {
            runCatching { CourseReadingStore(context).load() }.getOrDefault(emptyMap())
        }
        workReadings = withContext(Dispatchers.IO) {
            runCatching { WorkReadingStore(context).load() }.getOrDefault(emptyMap())
        }
        loading = false
    }
    LaunchedEffect(runtime.running, runtime.status) {
        catalog = withContext(Dispatchers.IO) { runCatching { CourseCatalogStore(context).load() }.getOrNull() }
        courseReadings = withContext(Dispatchers.IO) {
            runCatching { CourseReadingStore(context).load() }.getOrDefault(emptyMap())
        }
        workReadings = withContext(Dispatchers.IO) {
            runCatching { WorkReadingStore(context).load() }.getOrDefault(emptyMap())
        }
    }

    val unlocked = catalog?.unlocked().orEmpty()
    val fresh = catalog?.isUsable(System.currentTimeMillis()) == true
    val busy = loading || saving
    val selectedCourse = unlocked.firstOrNull { it.id == plan.courseId }
    val courseComparison = CourseComparison.compare(unlocked, courseReadings, System.currentTimeMillis())
    val workComparison = WorkYieldComparison.compare(WorkCatalog().all(), workReadings, System.currentTimeMillis())
    val selectedActionCount = listOf(selectedSignIn, selectedMailbox, selectedLuckyBag,
        selectedWork, selectedCare, selectedStudy, selectedAdventure).count { it }
    val validationMessage = when {
        loading -> "正在读取方案…"
        !selectedSignIn && !selectedMailbox && !selectedLuckyBag && !selectedWork && !selectedCare && !selectedStudy && !selectedAdventure -> "请选择至少一个动作。"
        selectedWork && verified.none { it.id == plan.workOptionId } -> "请选择打工岗位。"
        selectedStudy && !fresh && plan.targetAttribute == null && catalog == null && balancedRotation.isEmpty() -> "请先读取我的课程或选择上方目标属性。"
        selectedStudy && balancedRotation.isEmpty() && plan.targetAttribute == null && selectedCourse?.durationMinutes == null -> "请选择一门时长已读出的课程。"
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
                    if (selectedStudy) add(StudyStep(stepIds[PlanStepType.STUDY] ?: "$planId:study", plan.courseId, plan.rounds,
                        // 用户没点属性但选的就是该属性的课程时，按课程如实记下属性，运行记录里才分得清学的是什么。
                        // 均衡目标没有单一属性：目标属性留空，由轮换本身说明练什么。
                        if (balancedRotation.isNotEmpty()) null
                        else plan.targetAttribute ?: StudyAttribute.forCourseId(plan.courseId)?.label,
                        balancedRotation))
                    if (selectedAdventure) add(AdventureStep(stepIds[PlanStepType.ADVENTURE] ?: "$planId:adventure", adventureMode, adventureRounds, maxDailyRounds = adventureMaxDaily))
                }
                val snapshot = PlanV2(planId = planId, name = name, dailyBudgetMinutes = plan.dailyBudgetMinutes,
                    steps = steps, executionProfile = ExecutionProfile.SELECTED_ACTIONS, legacySource = legacySource,
                    minCoinFloor = if (coinFloorEnabled) coinFloorValue else null,
                    maxDailyNetSpend = if (dailySpendCapEnabled) dailySpendCapValue else null,
                    stepIntervalMinutes = if (stepIntervalEnabled) stepIntervalValue else null,
                    failureCooldownMinutes = if (failureCooldownEnabled) failureCooldownValue else null,
                    transferOnBlock = transferOnBlock,
                    dailyStudyRoundCap = if (studyCapEnabled) studyCapValue else null)
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
            if (pendingActions.isNotEmpty()) PetCard {
                PetSection("待确认动作")
                Spacer(Modifier.height(8.dp))
                PetNote("上一次任务被中断，下面这个动作到底做没做无法确认。先告诉蟹啦实际情况，避免重复扣费或重复领取。")
                pendingActions.forEach { action ->
                    Spacer(Modifier.height(8.dp))
                    Text(pendingActionLabel(action.stepType) +
                        (if (action.minutes > 0) " · " + action.minutes + " 分钟" else "") +
                        " · " + action.day, fontSize = 14.sp, color = PetTokens.Ink)
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PetFootButton("已经做完", PetButtonTone.GHOST,
                            { resolvePending(scope, context, action, true) { pendingRefresh += 1 } },
                            Modifier.weight(1f))
                        PetFootButton("没有发生", PetButtonTone.GHOST,
                            { resolvePending(scope, context, action, false) { pendingRefresh += 1 } },
                            Modifier.weight(1f))
                    }
                }
                Spacer(Modifier.height(6.dp))
                PetNote("确认「已经做完」后今天不会再重复这个动作；确认「没有发生」后可以重新开始。")
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
                    ActionChoice("学习", PetGlyph.Study, selectedStudy, !busy, Modifier.weight(1f)) {
                    selectedStudy = it
                    // 停止线只对学习有意义：不学的时候一起关掉，否则方案会因为「没有学习步骤」保存失败。
                    if (!it) studyCapEnabled = false
                    changed()
                }
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
                    val live = workComparison.liveRows.firstOrNull { it.optionId == option.id }
                    val note = if (live != null)
                        "一班 ${live.durationMinutes} 分钟 · ${live.coinReward} 金币 · ${live.workScoreReward} 工分（现场读取）"
                    else
                        "一班 ${option.durationMinutes} 分钟 · ${option.coinReward} 金币 · ${option.workScoreReward} 工分（目录）"
                    ChoiceRow("${option.venue} · ${option.optionLabel}", note,
                        plan.workOptionId == option.id, !busy) { plan = plan.copy(workOptionId = option.id); changed() }
                }
                Spacer(Modifier.height(6.dp))
                PetNote(workYieldSummary(workComparison))
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PetFootButton(if (runtime.running) "任务进行中" else "读取岗位收益",
                        if (displayId != null && !runtime.running && !busy) PetButtonTone.GHOST else PetButtonTone.DISABLED,
                        { displayId?.let { startWorkReading(context, it) } }, Modifier.weight(1f))
                    Spacer(Modifier.width(10.dp))
                    PetNote("只读，不开工\n现场读金币/工分/消耗")
                }
                if (displayId == null) PetNote("后台画面未就绪，暂不能读取。")
                if (runtime.status.startsWith("读取岗位收益") || runtime.status.startsWith("岗位收益")) PetNote(runtime.status)
                if (selectedStudy) {
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("仅金币不足时打工", fontSize = 14.sp, color = PetTokens.Ink)
                            PetNote("最多一班 · 打开表示金币够时先学习")
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
                    for (attr in StudyAttribute.entries) {
                        val isSelected = balancedRotation.isEmpty() && (plan.targetAttribute == attr.label ||
                            (plan.targetAttribute == null && StudyAttribute.forCourseId(plan.courseId) == attr))
                        Box(
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(PetTokens.ChipRadius))
                                .background(if (isSelected) PetTokens.Blue else PetTokens.Surface)
                                .clickable(enabled = !busy) {
                                    val resolved = StudyAttribute.resolveCourse(attr, catalog)
                                    balancedRotation = emptyList()
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
                    // STR-09 均衡：第 1 节力量、第 2 节智力、第 3 节魅力，第 4 节回到力量。
                    val balancedSelected = balancedRotation.isNotEmpty()
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(PetTokens.ChipRadius))
                            .background(if (balancedSelected) PetTokens.Blue else PetTokens.Surface)
                            .clickable(enabled = !busy) {
                                val rotation = StudyAttribute.entries.map { attr ->
                                    StudyRotation(attr.label, StudyAttribute.resolveCourse(attr, catalog).id)
                                }
                                balancedRotation = rotation
                                plan = plan.copy(targetAttribute = null, courseId = rotation.first().courseId)
                                changed()
                            }
                            .padding(vertical = 7.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "均衡",
                            fontSize = 13.sp,
                            fontWeight = if (balancedSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (balancedSelected) PetTokens.Surface else PetTokens.Ink,
                        )
                    }
                }
                if (balancedRotation.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    PetNote("均衡：" + balancedRotation.joinToString(" → ") { it.attribute } +
                        "（每节课轮换一门）\n" +
                        balancedRotation.joinToString(" → ") { it.courseId.substringAfter(':', it.courseId) })
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("今日停止线", fontSize = 14.sp, color = PetTokens.Ink)
                        PetNote(if (todayStudyRounds > 0)
                            "今日已确认学习 $todayStudyRounds 节，达到上限后不再开课"
                            else "今天最多上几节课，达到上限后本轮自动跳过学习")
                    }
                    Switch(checked = studyCapEnabled, enabled = !busy,
                        onCheckedChange = { studyCapEnabled = it; changed() },
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = PetTokens.Blue,
                            uncheckedTrackColor = PetTokens.GraySoft,
                            uncheckedThumbColor = PetTokens.Chevron,
                            uncheckedBorderColor = PetTokens.Chevron,
                        ))
                }
                if (studyCapEnabled) {
                    ParamRow("今日上限（节）", PetGlyph.Clock, studyCapValue, 1..StudyDailyCapPolicy.MAX_CAP, 1, !busy) {
                        studyCapValue = it; changed()
                    }
                }
                Spacer(Modifier.height(10.dp))
                PetSection("课程")
                Spacer(Modifier.height(8.dp))
                if (!fresh) PetNote("需要 7 天内读取过的课表，请先读取。")
                else if (unlocked.isEmpty()) PetNote("没有读到已解锁的课程。")
                else unlocked.forEach { course ->
                    val inRotation = balancedRotation.any { it.courseId == course.id }
                    val row = courseComparison.rows.firstOrNull { it.courseId == course.id }
                    val note = buildString {
                        append(row?.durationMinutes?.let { "$it 分钟" } ?: "时长未读出")
                        if (row?.tuitionCoins != null) {
                            append(" · 学费 ${row.tuitionCoins}")
                            append(row.attributeGain?.let { " · ${it.attribute}+${it.amount}" } ?: " · 收益未读到")
                        } else {
                            append(" · 学费/收益未读取或已过期")
                        }
                    }
                    ChoiceRow(course.name, note,
                        inRotation || (balancedRotation.isEmpty() && course.id == plan.courseId),
                        !busy && balancedRotation.isEmpty()) { plan = plan.copy(courseId = course.id); changed() }
                }
                if (balancedRotation.isNotEmpty()) PetNote("均衡模式下按上面三门课轮换，不手动选单门课。")
                if (fresh && unlocked.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    PetNote(courseComparisonSummary(courseComparison))
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
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PetFootButton(if (runtime.running) "任务进行中" else "读取课程收益",
                        if (displayId != null && !runtime.running && !busy) PetButtonTone.GHOST else PetButtonTone.DISABLED,
                        { displayId?.let { startCourseReading(context, it) } }, Modifier.weight(1f))
                    Spacer(Modifier.width(10.dp))
                    PetNote("只读，不花金币\n逐门读取学费与收益")
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
                PetCard {
                    PetSection("任务节奏（STR-15）")
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("步骤最小间隔", fontSize = 14.sp, color = PetTokens.Ink)
                            PetNote("相邻动作之间至少等待这么久，避免连续高频操作")
                        }
                        Switch(checked = stepIntervalEnabled, enabled = !busy,
                            onCheckedChange = { stepIntervalEnabled = it; changed() },
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = PetTokens.Blue,
                                uncheckedTrackColor = PetTokens.GraySoft,
                                uncheckedThumbColor = PetTokens.Chevron,
                                uncheckedBorderColor = PetTokens.Chevron,
                            ))
                    }
                    if (stepIntervalEnabled) {
                        ParamRow("间隔（分）", PetGlyph.Clock, stepIntervalValue, 1..120, 5, !busy) {
                            stepIntervalValue = it; changed()
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("失败后冷却", fontSize = 14.sp, color = PetTokens.Ink)
                            PetNote("上一次失败后这段时间内不再重开同一方案")
                        }
                        Switch(checked = failureCooldownEnabled, enabled = !busy,
                            onCheckedChange = { failureCooldownEnabled = it; changed() },
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = PetTokens.Blue,
                                uncheckedTrackColor = PetTokens.GraySoft,
                                uncheckedThumbColor = PetTokens.Chevron,
                                uncheckedBorderColor = PetTokens.Chevron,
                            ))
                    }
                    if (failureCooldownEnabled) {
                        ParamRow("冷却（分）", PetGlyph.Clock, failureCooldownValue, 1..240, 5, !busy) {
                            failureCooldownValue = it; changed()
                        }
                    }
                }
                if (selectedActionCount >= 2) PetCard {
                    PetSection("优先与转移（STR-01/02）")
                    Spacer(Modifier.height(8.dp))
                    PetNote("「仅金币不足时打工」打开是学习优先（金币够时先学习），关闭是打工优先（先打工）。")
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("受阻时按规则转移", fontSize = 14.sp, color = PetTokens.Ink)
                            PetNote("某个动作受阻时继续做后面的动作，每轮最多转移一次")
                        }
                        Switch(checked = transferOnBlock, enabled = !busy,
                            onCheckedChange = { transferOnBlock = it; changed() },
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = PetTokens.Blue,
                                uncheckedTrackColor = PetTokens.GraySoft,
                                uncheckedThumbColor = PetTokens.Chevron,
                                uncheckedBorderColor = PetTokens.Chevron,
                            ))
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
            if (pendingActions.isNotEmpty()) PetNote("有 " + pendingActions.size + " 个待确认动作，确认后才能开始本次任务。")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PetFootButton(if (saving) "正在保存…" else if (saved) "已保存" else "保存方案",
                    if (busy || saved || validationMessage != null) PetButtonTone.DISABLED else PetButtonTone.GHOST,
                    { persistSelection(false) }, Modifier.weight(1f))
                PetFootButton("保存并运行",
                    if (busy || validationMessage != null || runtime.running || displayId == null || displayId <= 0 ||
                        pendingActions.isNotEmpty())
                        PetButtonTone.DISABLED else PetButtonTone.PRIMARY,
                    { persistSelection(true) }, Modifier.weight(1f))
            }
        }
    }
}

/** 用户确认一次中断动作的结局：已经做完记成完成，没有发生允许重做。 */
private fun resolvePending(scope: CoroutineScope, context: Context, action: PendingAction, done: Boolean,
    onResolved: () -> Unit) {
    scope.launch {
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                DailyBudgetStore(context).confirmOutcome(action.attemptId, done,
                    if (done) "用户确认已完成" else "用户确认没有发生")
            }.isSuccess
        }
        if (ok) onResolved()
    }
}

/** 给用户看的动作名字，不出现英文代号。 */
private fun pendingActionLabel(type: PlanStepType): String = when (type) {
    PlanStepType.STUDY -> "学习"
    PlanStepType.SIGN_IN -> "签到"
    PlanStepType.MAILBOX -> "信箱"
    PlanStepType.LUCKY_BAG -> "福袋"
    else -> type.name
}

private fun startCourseScan(context: Context, displayId: Int) {
    ContextCompat.startForegroundService(context, PetAutomationService.scanCoursesIntent(context, displayId))
}

private fun startCourseReading(context: Context, displayId: Int) {
    ContextCompat.startForegroundService(context, PetAutomationService.readCourseGainsIntent(context, displayId))
}

private fun startWorkReading(context: Context, displayId: Int) {
    ContextCompat.startForegroundService(context, PetAutomationService.readWorkGainsIntent(context, displayId))
}

/** STR-11：只写现场读到的数；没读过的岗位按未知处理，不参与推荐。 */
private fun workYieldSummary(summary: WorkYieldComparison.Summary): String {
    if (!summary.hasLive) {
        return "岗位收益未读取。点「读取岗位收益」现场读取已开放岗位与不同时长档；读不到时保持安全默认岗位。"
    }
    val lines = summary.liveRows.map { row ->
        val suffix = if (row.selectable) "" else " · 暂不可选（未闭环验证）"
        val fee = if (row.entryFeeKnown) "" else "，未含入场费"
        "${row.venue} · ${row.optionLabel}：${row.durationMinutes} 分钟 / ${row.coinReward} 金币 / " +
            "${row.workScoreReward} 工分（${courseNumber(row.coinsPerMinute)} 金币/分、" +
            "${courseNumber(row.workScorePerMinute)} 工分/分$fee）$suffix"
    }
    val head = "现场读取：" + "\n" + lines.joinToString("\n")
    val ranking = if (summary.selectableLiveRows.size >= 2) {
        val coins = summary.bestCoins?.let { "金币最快 ${it.optionLabel} ${courseNumber(it.coinsPerMinute)}/分" }
        val score = summary.bestWorkScore?.let { "工分最快 ${it.optionLabel} ${courseNumber(it.workScorePerMinute)}/分" }
        val energy = summary.leastEnergy?.let { "体力最省 ${it.optionLabel} ${courseNumber(it.energyPerMinute)}/分" }
        val parts = listOfNotNull(coins, score, energy)
        if (parts.isEmpty()) "" else "\n" + "比较：" + parts.joinToString(" · ")
    } else {
        "\n" + "当前可执行岗位不足两个，仍按安全默认岗位执行；上面列出其他时长档供参考。"
    }
    val catalogOnly = if (summary.rows.any { !it.live }) "\n" + "目录里还有未现场核对的岗位，不参与比较。" else ""
    return head + ranking + catalogOnly
}

/** STR-10：只写读到的数；读不到就说明未知，不参与排名。 */
private fun courseComparisonSummary(summary: CourseComparison.Summary): String {
    if (!summary.hasAnyReading) {
        return "还没有可用的课程收益数据（未读取或已过期）。点「读取课程收益」逐门读取学费和属性收益；读不到的会标为未知。"
    }
    val parts = mutableListOf<String>()
    summary.cheapestTuition?.let { parts += "最省学费 ${it.name} ${it.tuitionCoins} 金币" }
    summary.cheapestPerMinute?.let { parts += "每分钟最省 ${it.name} ${courseNumber(it.coinsPerMinute)} 金币" }
    summary.bestAttributeValue?.let { parts += "每点属性最省 ${it.name} ${courseNumber(it.coinsPerAttribute)} 金币" }
    val head = "按上次读取比较：" + parts.joinToString(" · ")
    return if (summary.unknownNames.isEmpty()) head
        else head + "\n未读到收益：" + summary.unknownNames.joinToString("、")
}

private fun courseNumber(value: Double?): String {
    if (value == null) return "未知"
    return String.format(Locale.US, "%.2f", value).trimEnd('0').trimEnd('.')
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
