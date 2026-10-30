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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.ynk.virtualdisplay.automation.CourseCatalog
import com.ynk.virtualdisplay.automation.CourseCatalogStore
import com.ynk.virtualdisplay.automation.DailyBudgetStore
import com.ynk.virtualdisplay.automation.PetAutomationService
import com.ynk.virtualdisplay.automation.SessionPlan
import com.ynk.virtualdisplay.automation.SessionPlanStore
import com.ynk.virtualdisplay.automation.WorkCatalog
import com.ynk.virtualdisplay.ui.kit.PetButtonTone
import com.ynk.virtualdisplay.ui.kit.PetCard
import com.ynk.virtualdisplay.ui.kit.PetChip
import com.ynk.virtualdisplay.ui.kit.PetFootButton
import com.ynk.virtualdisplay.ui.kit.PetNote
import com.ynk.virtualdisplay.ui.kit.PetSection
import com.ynk.virtualdisplay.ui.main.PetTitleBar
import com.ynk.virtualdisplay.ui.theme.PetTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 托管方案, now editable. Every value here is what the next 托管 session will actually do: the course
 * it opens, how many lessons, the health gate and the daily cap. Nothing on this screen is
 * decorative — a control that cannot change behaviour is not shown.
 *
 * The course list comes only from this account's scanned catalog, so a course another account
 * unlocked can never be picked here.
 */
@Composable
fun PetPlanSheetScreen(onClose: () -> Unit, onOpenConsole: () -> Unit, displayId: Int?) {
    val context = LocalContext.current
    var plan by remember { mutableStateOf(SessionPlan()) }
    var catalog by remember { mutableStateOf<CourseCatalog?>(null) }
    var saved by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val loaded = withContext(Dispatchers.IO) {
            SessionPlanStore(context).load() to CourseCatalogStore(context).load()
        }
        plan = loaded.first
        catalog = loaded.second
    }

    val unlocked = catalog?.unlocked().orEmpty()
    val fresh = catalog?.isFresh(System.currentTimeMillis()) == true

    Column(Modifier.fillMaxSize()) {
        PetTitleBar("托管方案", onBack = onClose)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 15.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            PetCard {
                PetSection("课程")
                Spacer(Modifier.height(8.dp))
                if (!fresh) {
                    PetNote("还没有本号的课程档案，或档案已超过 7 天。先读取一次，托管才会按你的课表跑。")
                } else if (unlocked.isEmpty()) {
                    PetNote("扫描完成，但没有读到已解锁的课程。")
                } else {
                    unlocked.forEach { course ->
                        val selected = course.id == plan.courseId
                        // Only a course whose lesson length was measured can be charged against the
                        // daily budget, so it is the only kind this sheet lets a session start on.
                        val verified = SessionPlan.hasMeasuredDuration(course.id)
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp)
                                .background(
                                    if (selected) PetTokens.BlueSoft else PetTokens.GraySoft,
                                    RoundedCornerShape(PetTokens.NextRadius),
                                )
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(course.name, fontSize = 14.sp, fontWeight = FontWeight(650),
                                    color = if (selected) PetTokens.BlueInk else PetTokens.Ink)
                                val duration = course.durationMinutes?.let { "$it 分钟" } ?: "时长未读出"
                                Text(if (verified) duration else "$duration · 时长未实测",
                                    fontSize = 11.5.sp, color = PetTokens.Ink3)
                            }
                            PetFootButton(
                                text = when {
                                    selected -> "已选"
                                    !verified -> "未实测"
                                    else -> "选择"
                                },
                                tone = if (selected || !verified) PetButtonTone.DISABLED else PetButtonTone.PRIMARY,
                                onClick = {
                                    if (verified) {
                                        plan = plan.copy(courseId = course.id); saved = false
                                    }
                                },
                            )
                        }
                    }
                    PetNote("托管只接受时长已实测的课程：每日上限按时长记账，没测过的时长会让这条判断失真。")
                }
                if (!plan.hasKnownDuration) {
                    Spacer(Modifier.height(6.dp))
                    PetNote("当前方案里的课程还没有实测时长，托管会拒绝启动。请在下面选一门已验证的课程。")
                }
                Spacer(Modifier.height(8.dp))
                val scannedAt = catalog?.scannedAtMs?.takeIf { it > 0 }?.let {
                    SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(it))
                }
                PetNote(if (scannedAt != null) "上次读取 $scannedAt" else "从未读取")
                Spacer(Modifier.height(8.dp))
                PetFootButton(
                    text = "读取我的课程",
                    tone = if (displayId != null) PetButtonTone.PRIMARY else PetButtonTone.DISABLED,
                    onClick = { displayId?.let { startCourseScan(context, it) } },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (displayId == null) PetNote("虚拟屏未就绪，读取暂不可用。")
            }

            PetCard {
                PetSection("节数与阈值")
                Spacer(Modifier.height(8.dp))
                NumberField("节数（${SessionPlan.MIN_ROUNDS}–${SessionPlan.MAX_ROUNDS}）", plan.rounds, SessionPlan.ROUNDS_RANGE) {
                    plan = plan.copy(rounds = it); saved = false
                }
                NumberField("体力阈值（${SessionPlan.MIN_THRESHOLD}–${SessionPlan.MAX_THRESHOLD}）", plan.energyThreshold, SessionPlan.THRESHOLD_RANGE) {
                    plan = plan.copy(energyThreshold = it); saved = false
                }
                NumberField("清洁阈值（${SessionPlan.MIN_THRESHOLD}–${SessionPlan.MAX_THRESHOLD}）", plan.cleanlinessThreshold, SessionPlan.THRESHOLD_RANGE) {
                    plan = plan.copy(cleanlinessThreshold = it); saved = false
                }
                NumberField("每日上限（分钟，最多 ${DailyBudgetStore.HARD_STOP_MINUTES}）", plan.dailyBudgetMinutes, SessionPlan.BUDGET_RANGE) {
                    plan = plan.copy(dailyBudgetMinutes = it); saved = false
                }
                PetNote("低于阈值会先护理再开课。每日上限只能调低，不能超过游戏的 480 分钟。")
            }

            PetCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        PetSection("金币不足时先打工")
                        Spacer(Modifier.height(3.dp))
                        Text("只跑已验证的「帮武馆跑腿」，赚够学费再上课", fontSize = 11.5.sp, color = PetTokens.Ink3)
                    }
                    Switch(
                        checked = plan.workBeforeStudy,
                        onCheckedChange = { plan = plan.copy(workBeforeStudy = it); saved = false },
                    )
                }
                if (plan.workBeforeStudy) {
                    Spacer(Modifier.height(6.dp))
                    val verified = WorkCatalog().all().filter {
                        it.confidence == com.ynk.virtualdisplay.automation.RewardConfidence.VERIFIED
                    }
                    verified.forEach { option ->
                        PetChip("${option.venue} · ${option.optionLabel} · ${option.durationMinutes} 分钟",
                            PetTokens.GreenSoft, PetTokens.GreenInk)
                    }
                }
            }

            Spacer(Modifier.height(4.dp))
        }
        Column(Modifier.padding(horizontal = 15.dp).padding(top = 9.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PetFootButton(
                text = if (saved) "已保存" else "保存方案",
                tone = if (saved) PetButtonTone.DISABLED else PetButtonTone.PRIMARY,
                onClick = {
                    val error = runCatching { SessionPlanStore(context).save(plan) }.exceptionOrNull()
                    saved = error == null
                },
                modifier = Modifier.fillMaxWidth(),
            )
            PetFootButton("返回托管", PetButtonTone.GHOST, onClose, Modifier.fillMaxWidth())
        }
    }
}

/** A scan is a real session: it reuses the foreground service, reads the list and stops itself. */
private fun startCourseScan(context: Context, displayId: Int) {
    ContextCompat.startForegroundService(context, PetAutomationService.scanCoursesIntent(context, displayId))
}

/**
 * A numeric plan field.
 *
 * The field owns its text while it is being typed, and only text that already is a legal value
 * reaches the plan: "6" on the way to "65" stays in the box instead of being committed and
 * rejected. Leaving the field drops whatever is left over, so the box can never show a number the
 * plan does not hold.
 */
@Composable
private fun NumberField(label: String, value: Int, range: IntRange, onValue: (Int) -> Unit) {
    var text by remember { mutableStateOf(value.toString()) }
    LaunchedEffect(value) { if (text.toIntOrNull() != value) text = value.toString() }
    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            if (raw.all { it.isDigit() }) {
                text = raw
                SessionPlan.parseIntOrNull(raw, range)?.let(onValue)
            }
        },
        label = { Text(label) },
        singleLine = true,
        isError = SessionPlan.parseIntOrNull(text, range) == null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
            .onFocusChanged { if (!it.isFocused) text = value.toString() },
    )
}
