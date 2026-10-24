package com.ynk.virtualdisplay.ui.screens

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.KeyboardType
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
import com.ynk.virtualdisplay.ui.kit.PetNote
import com.ynk.virtualdisplay.ui.kit.PetSection
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
fun PetPlanSheetScreen(onClose: () -> Unit, onOpenConsole: () -> Unit, displayId: Int?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var plan by remember { mutableStateOf(SessionPlan()) }
    var planId by remember { mutableStateOf(UUID.randomUUID().toString()) }
    var name by remember { mutableStateOf("我的方案") }
    var selectedWork by remember { mutableStateOf(false) }
    var selectedCare by remember { mutableStateOf(false) }
    var selectedStudy by remember { mutableStateOf(false) }
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
    var validNumbers by remember { mutableStateOf(mapOf<String, Boolean>()) }
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
            selectedWork = work != null; selectedCare = care != null; selectedStudy = study != null
            plan = plan.copy(courseId = study?.courseId ?: plan.courseId, rounds = study?.rounds ?: plan.rounds,
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
    val relevantFields = buildSet {
        add("budget")
        if (selectedCare) { add("energy"); add("cleanliness") }
        if (selectedStudy) add("rounds")
    }
    val invalidNumber = relevantFields.any { validNumbers[it] == false }
    val selectedCourse = unlocked.firstOrNull { it.id == plan.courseId }
    val validationMessage = when {
        loading -> "正在读取方案…"
        name.isBlank() || name.length !in 1..80 -> "方案名称需为1–80个字符。"
        !selectedWork && !selectedCare && !selectedStudy -> "请选择至少一个动作。"
        invalidNumber -> "请修正已选动作的参数。"
        selectedWork && verified.none { it.id == plan.workOptionId } -> "请选择已验证的打工岗位。"
        selectedStudy && !fresh -> "请先读取本号完整且有效的课表。"
        selectedStudy && selectedCourse?.durationMinutes == null -> "请选择已解锁且时长可确认的课程。"
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
                    if (selectedWork) add(WorkStep(stepIds[PlanStepType.WORK] ?: "$planId:work",
                        plan.workOptionId ?: error("未选择岗位"),
                        if (selectedStudy && plan.workBeforeStudy) WorkTrigger.IF_FUNDS_SHORT else WorkTrigger.ALWAYS))
                    if (selectedCare) add(CareStep(stepIds[PlanStepType.CARE] ?: "$planId:care",
                        plan.energyThreshold, plan.cleanlinessThreshold))
                    if (selectedStudy) add(StudyStep(stepIds[PlanStepType.STUDY] ?: "$planId:study", plan.courseId, plan.rounds))
                }
                val snapshot = PlanV2(planId = planId, name = name, dailyBudgetMinutes = plan.dailyBudgetMinutes,
                    steps = steps, executionProfile = ExecutionProfile.SELECTED_ACTIONS, legacySource = legacySource)
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
                    onOpenConsole()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                saveError = if (didSave) "方案已保存，启动失败：${error.message}" else "保存失败：${error.message}"
            } finally { saving = false }
        }
    }

    Column(Modifier.fillMaxSize()) {
        PetTitleBar("运行方案", onBack = onClose)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 15.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp)) {
            PetCard {
                PetSection("选择动作")
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = name, onValueChange = { name = it; changed() },
                    label = { Text("方案名称") }, singleLine = true, enabled = !busy,
                    isError = name.isBlank() || name.length !in 1..80,
                    supportingText = { Text("${name.length}/80") }, modifier = Modifier.fillMaxWidth())
                ActionChoice("打工", "执行一班，开始前需满足岗位体力与清洁要求", selectedWork, !busy) {
                    selectedWork = it
                    if (it && verified.none { option -> option.id == plan.workOptionId }) {
                        verified.firstOrNull()?.let { option -> plan = plan.copy(workOptionId = option.id) }
                    }
                    changed()
                }
                ActionChoice("护理", "按目标使用已有道具，只购买缺少的部分", selectedCare, !busy) { selectedCare = it; changed() }
                ActionChoice("学习", "按课程支付学费，不要求达到护理目标", selectedStudy, !busy) { selectedStudy = it; changed() }
                PetNote("执行顺序固定为打工 → 护理 → 学习，只执行勾选的动作。")
                loadNote?.let { PetNote(it) }
                if (showCompatibilityNote) PetNote("保存为新版方案后，护理执行一次；打工前需已满足岗位条件，后置护理不会提前执行。")
            }

            if (selectedWork) PetCard {
                PetSection("打工岗位")
                Spacer(Modifier.height(8.dp))
                if (verified.isEmpty()) PetNote("没有可用的已验证岗位。")
                verified.forEach { option ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${option.venue} · ${option.optionLabel}", fontSize = 14.sp, color = PetTokens.Ink)
                            PetNote("一班 ${option.durationMinutes} 分钟")
                        }
                        val selected = plan.workOptionId == option.id
                        PetFootButton(if (selected) "已选" else "选择",
                            if (selected || busy) PetButtonTone.DISABLED else PetButtonTone.PRIMARY,
                            { plan = plan.copy(workOptionId = option.id); changed() })
                    }
                }
                if (selectedStudy) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("仅资金不足时打工", fontSize = 14.sp, color = PetTokens.Ink)
                            PetNote("结合护理库存和后续学费判断，最多一班")
                        }
                        Switch(checked = plan.workBeforeStudy, enabled = !busy,
                            onCheckedChange = { plan = plan.copy(workBeforeStudy = it); changed() })
                    }
                } else PetNote("未选择学习时，固定执行一班打工。")
            }

            if (selectedStudy) PetCard {
                PetSection("学习课程")
                Spacer(Modifier.height(8.dp))
                if (!fresh) PetNote("需要本号完整且7天内的课表，请先读取。")
                else if (unlocked.isEmpty()) PetNote("扫描完成，但没有读到已解锁的课程。")
                else unlocked.forEach { course ->
                    val selected = course.id == plan.courseId
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        .background(if (selected) PetTokens.BlueSoft else PetTokens.GraySoft,
                            RoundedCornerShape(PetTokens.NextRadius)).padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(course.name, fontSize = 14.sp, fontWeight = FontWeight(650),
                                color = if (selected) PetTokens.BlueInk else PetTokens.Ink)
                            Text(course.durationMinutes?.let { "$it 分钟" } ?: "时长未读出", fontSize = 11.5.sp, color = PetTokens.Ink3)
                        }
                        PetFootButton(if (selected) "已选" else "选择",
                            if (selected || busy) PetButtonTone.DISABLED else PetButtonTone.PRIMARY,
                            { plan = plan.copy(courseId = course.id); changed() })
                    }
                }
                val scannedAt = catalog?.scannedAtMs?.takeIf { it > 0 }?.let {
                    SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(it))
                }
                PetNote(scannedAt?.let { "上次读取 $it" } ?: "从未读取")
                Spacer(Modifier.height(8.dp))
                PetFootButton(if (runtime.running) "任务进行中" else "读取我的课程",
                    if (displayId != null && !runtime.running && !busy) PetButtonTone.PRIMARY else PetButtonTone.DISABLED,
                    { displayId?.let { startCourseScan(context, it) } }, Modifier.fillMaxWidth())
                if (displayId == null) PetNote("虚拟屏未就绪，读取暂不可用。")
                if (runtime.status.startsWith("课程") || runtime.status.startsWith("读取课程")) PetNote(runtime.status)
                Spacer(Modifier.height(8.dp))
                NumberField("节数（1–10）", plan.rounds.toString(), SessionPlan.MIN_ROUNDS..SessionPlan.MAX_ROUNDS,
                    enabled = !busy, onValidity = { validNumbers = validNumbers + ("rounds" to it) }) {
                    it.toIntOrNull()?.takeIf { value -> value in SessionPlan.MIN_ROUNDS..SessionPlan.MAX_ROUNDS }
                        ?.let { value -> plan = plan.copy(rounds = value); changed() }
                }
            }

            if (selectedCare) PetCard {
                PetSection("护理目标")
                Spacer(Modifier.height(8.dp))
                NumberField("体力目标（60–100）", plan.energyThreshold.toString(), SessionPlan.MIN_THRESHOLD..SessionPlan.MAX_THRESHOLD,
                    enabled = !busy, onValidity = { validNumbers = validNumbers + ("energy" to it) }) {
                    it.toIntOrNull()?.takeIf { value -> value in SessionPlan.MIN_THRESHOLD..SessionPlan.MAX_THRESHOLD }
                        ?.let { value -> plan = plan.copy(energyThreshold = value); changed() }
                }
                NumberField("清洁目标（60–100）", plan.cleanlinessThreshold.toString(), SessionPlan.MIN_THRESHOLD..SessionPlan.MAX_THRESHOLD,
                    enabled = !busy, onValidity = { validNumbers = validNumbers + ("cleanliness" to it) }) {
                    it.toIntOrNull()?.takeIf { value -> value in SessionPlan.MIN_THRESHOLD..SessionPlan.MAX_THRESHOLD }
                        ?.let { value -> plan = plan.copy(cleanlinessThreshold = value); changed() }
                }
                PetNote("已达标的项目跳过。护理目标只用于护理，不是学习门槛。")
            }

            PetCard {
                PetSection("每日时间预算")
                Spacer(Modifier.height(8.dp))
                NumberField("每日上限（分钟，最多 ${DailyBudgetStore.HARD_STOP_MINUTES}）", plan.dailyBudgetMinutes.toString(),
                    1..DailyBudgetStore.HARD_STOP_MINUTES, enabled = !busy,
                    onValidity = { validNumbers = validNumbers + ("budget" to it) }) {
                    it.toIntOrNull()?.takeIf { value -> value in 1..DailyBudgetStore.HARD_STOP_MINUTES }
                        ?.let { value -> plan = plan.copy(dailyBudgetMinutes = value); changed() }
                }
                PetNote("打工与学习共用每日分钟数；护理不扣游戏分钟。")
            }
            Spacer(Modifier.height(4.dp))
        }
        Column(Modifier.padding(horizontal = 15.dp).padding(top = 9.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            (saveError ?: validationMessage)?.let { PetNote(it) }
            if (displayId == null || displayId <= 0) PetNote("请先选择虚拟屏，才能保存并运行。")
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
            PetFootButton("返回", PetButtonTone.GHOST, onClose, Modifier.fillMaxWidth())
        }
    }
}

private fun startCourseScan(context: Context, displayId: Int) {
    ContextCompat.startForegroundService(context, PetAutomationService.scanCoursesIntent(context, displayId))
}

@Composable
private fun ActionChoice(label: String, detail: String, checked: Boolean, enabled: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
        .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(label, fontSize = 14.sp, color = PetTokens.Ink, fontWeight = FontWeight(650))
            PetNote(detail)
        }
    }
}

@Composable
private fun NumberField(label: String, value: String, range: IntRange, enabled: Boolean = true,
    onValidity: (Boolean) -> Unit, onValue: (String) -> Unit) {
    var draft by remember(label) { mutableStateOf(value) }
    LaunchedEffect(value) { draft = value }
    val valid = draft.toIntOrNull()?.let { it in range } == true
    LaunchedEffect(valid) { onValidity(valid) }
    OutlinedTextField(value = draft, enabled = enabled, onValueChange = { text ->
        if (text.length <= 3 && text.all { it.isDigit() }) {
            draft = text
            onValidity(text.toIntOrNull()?.let { it in range } == true)
            onValue(text)
        }
    }, label = { Text(label) }, isError = !valid,
        supportingText = { if (!valid) Text("请输入 ${range.first}–${range.last}") }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))
}
