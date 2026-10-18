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
                                Text(duration, fontSize = 11.5.sp, color = PetTokens.Ink3)
                            }
                            PetFootButton(
                                text = if (selected) "已选" else "选择",
                                tone = if (selected) PetButtonTone.DISABLED else PetButtonTone.PRIMARY,
                                onClick = { plan = plan.copy(courseId = course.id); saved = false },
                            )
                        }
                    }
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
                NumberField("节数（1–10）", plan.rounds.toString()) {
                    it.toIntOrNull()?.let { value -> plan = plan.copy(rounds = value); saved = false }
                }
                NumberField("体力阈值（60–100）", plan.energyThreshold.toString()) {
                    it.toIntOrNull()?.let { value -> plan = plan.copy(energyThreshold = value); saved = false }
                }
                NumberField("清洁阈值（60–100）", plan.cleanlinessThreshold.toString()) {
                    it.toIntOrNull()?.let { value -> plan = plan.copy(cleanlinessThreshold = value); saved = false }
                }
                NumberField("每日上限（分钟，最多 ${DailyBudgetStore.HARD_STOP_MINUTES}）", plan.dailyBudgetMinutes.toString()) {
                    it.toIntOrNull()?.let { value -> plan = plan.copy(dailyBudgetMinutes = value); saved = false }
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

@Composable
private fun NumberField(label: String, value: String, onValue: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
    )
}
