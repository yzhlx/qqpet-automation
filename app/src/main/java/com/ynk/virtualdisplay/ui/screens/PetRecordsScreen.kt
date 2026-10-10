package com.ynk.virtualdisplay.ui.screens

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ynk.virtualdisplay.automation.PetManagedRuntime
import com.ynk.virtualdisplay.automation.PetRuntime
import com.ynk.virtualdisplay.ui.data.PetEvidenceRepository
import com.ynk.virtualdisplay.ui.data.SessionEvidence
import com.ynk.virtualdisplay.ui.kit.PetCard
import com.ynk.virtualdisplay.ui.kit.PetChip
import com.ynk.virtualdisplay.ui.kit.PetDivider
import com.ynk.virtualdisplay.ui.kit.PetNote
import com.ynk.virtualdisplay.ui.kit.PetPill
import com.ynk.virtualdisplay.ui.kit.PetRowHeader
import com.ynk.virtualdisplay.ui.kit.PetSection
import com.ynk.virtualdisplay.ui.kit.PetGlyph
import com.ynk.virtualdisplay.ui.kit.PetIcon
import com.ynk.virtualdisplay.ui.kit.rememberEvidenceBitmap
import com.ynk.virtualdisplay.ui.records.ActionOutcome
import com.ynk.virtualdisplay.ui.records.MetricChange
import com.ynk.virtualdisplay.ui.records.RecordsPresentation
import com.ynk.virtualdisplay.ui.records.RecordsSummary
import com.ynk.virtualdisplay.ui.records.SessionHistoryRow
import com.ynk.virtualdisplay.ui.records.VerdictTone
import com.ynk.virtualdisplay.ui.theme.PetTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 记录 tab. Reinvented from nothing: every number is read back from the evidence the automation
 * layer already persisted, and anything the game does not expose is labelled 未知 / 不支持读取.
 */
@Composable
fun PetRecordsScreen() {
    val context = LocalContext.current
    val runtime by PetRuntime.state.collectAsState()
    val managed by PetManagedRuntime.state.collectAsState()

    val repo = remember { PetEvidenceRepository(context) }
    var today by remember { mutableStateOf<List<SessionEvidence>>(emptyList()) }
    var history by remember { mutableStateOf<List<SessionHistoryRow>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }

    val hhmm = remember { SimpleDateFormat("HH:mm", Locale.US) }
    val dayFmt = remember { SimpleDateFormat("MM-dd", Locale.US) }
    val timeFormatter: (Long) -> String = remember { { ms -> hhmm.format(Date(ms)) } }
    val dateFormatter: (Long) -> String = remember { { ms -> dayFmt.format(Date(ms)) } }

    LaunchedEffect(runtime.running, managed.sessionId) {
        suspend fun reload() = withContext(Dispatchers.IO) {
            today = repo.todaySessions()
            history = RecordsPresentation.history(
                repo.sessionDirectories(limit = 40).map { repo.read(it) },
                timeFormatter, dateFormatter,
            )
            loaded = true
        }
        reload()
        // While a batch is live the rounds and screenshots keep appearing, so re-read the disk.
        while (runtime.running) {
            kotlinx.coroutines.delay(3_000L)
            reload()
        }
    }

    val summary = RecordsPresentation.summary(today, timeFormatter, dateFormatter)
    val focus = today.firstOrNull()
    val focusRounds = focus?.rounds.orEmpty()
    val changes = focus?.let { RecordsPresentation.changes(it.rounds) }.orEmpty()
    val actions = focus?.let { RecordsPresentation.actions(it.rounds, it.record) }.orEmpty()
    val frames = RecordsPresentation.pairedFrames(focus, focusRounds)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 15.dp)) {
        Spacer(Modifier.height(2.dp))

        if (!loaded) {
            PetCard { PetNote("正在读取本地运行记录…") }
        } else if (focus == null) {
            PetCard {
                PetSection("今天")
                Spacer(Modifier.height(6.dp))
                PetNote("今天还没有运行记录。完成一次托管后，这里会显示真实的读数变化与截图。")
            }
        } else {
            TodayCard(summary)
            Spacer(Modifier.height(11.dp))
            ChangesCard(changes, frames)
            Spacer(Modifier.height(11.dp))
            ActionsCard(actions)
            Spacer(Modifier.height(11.dp))
            UnsupportedCard()
        }

        if (history.isNotEmpty()) {
            Spacer(Modifier.height(11.dp))
            HistoryCard(history)
        }

        Spacer(Modifier.height(11.dp))
        PetNote(
            "小数、金币与库存当前无法从游戏可靠读取，所以记录里不出现这些数字；" +
                "所有读数都来自本机截图识别，随时可在系统里清空。"
        )
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun TodayCard(summary: RecordsSummary) {
    PetCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                summary.rangeText ?: "今天",
                fontSize = 19.sp, fontWeight = FontWeight.Bold, color = PetTokens.Ink,
                modifier = Modifier.weight(1f),
            )
            if (summary.minutesText != null) {
                PetPill(summary.minutesText, PetTokens.BlueSoft, PetTokens.BlueInk, withDot = false)
            }
        }
        Spacer(Modifier.height(11.dp))
        Row(Modifier.fillMaxWidth()) {
            StripCell(summary.courses, "学习 节", Modifier.weight(1f))
            StripCell(summary.careCount, "护理 次", Modifier.weight(1f))
            StripCell(summary.pauseCount, "暂停 次", Modifier.weight(1f))
        }
    }
}

@Composable
private fun StripCell(value: Int, label: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("$value", fontSize = 21.sp, fontWeight = FontWeight.Bold, color = PetTokens.Ink,
            fontFamily = FontFamily.Monospace)
        Spacer(Modifier.height(2.dp))
        Text(label, fontSize = 10.5.sp, color = PetTokens.Ink3)
    }
}

@Composable
private fun ChangesCard(changes: List<MetricChange>, frames: List<File>) {
    PetCard {
        PetRowHeader("已确认的状态变化", "≈ 截图识别")
        Spacer(Modifier.height(10.dp))
        if (frames.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                frames.take(3).forEach { file -> FrameThumb(file, Modifier.weight(1f)) }
            }
            Spacer(Modifier.height(12.dp))
        }
        changes.forEachIndexed { index, change ->
            if (index > 0) PetDivider(Modifier.padding(vertical = 2.dp))
            ChangeRow(change, index == 0)
        }
    }
}

@Composable
private fun FrameThumb(file: File, modifier: Modifier = Modifier) {
    val bmp = rememberEvidenceBitmap(file.absolutePath, 260)
    Column(modifier) {
        Box(
            Modifier.fillMaxWidth().height(88.dp).clip(RoundedCornerShape(9.dp))
                .background(PetTokens.FilmTop),
            contentAlignment = Alignment.Center,
        ) {
            if (bmp != null) {
                androidx.compose.foundation.Image(
                    bitmap = bmp, contentDescription = file.name,
                    modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
                )
            } else {
                PetIcon(PetGlyph.Cam, 18.dp, alpha = 0.5f)
            }
        }
        Spacer(Modifier.height(5.dp))
        Text(RecordsPresentation.frameCaption(file.name), fontSize = 10.sp,
            color = PetTokens.Ink3, maxLines = 1)
    }
}

@Composable
private fun ChangeRow(change: MetricChange, first: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(change.label, fontSize = 14.sp, fontWeight = FontWeight(650), color = PetTokens.Ink)
            Spacer(Modifier.height(3.dp))
            Text(change.meta, fontSize = 11.sp, color = PetTokens.Ink3, maxLines = 1)
        }
        Text(
            "${change.beforeText} → ${change.afterText}",
            fontSize = 13.sp, color = PetTokens.Ink2, fontFamily = FontFamily.Monospace,
        )
        Spacer(Modifier.width(8.dp))
        if (change.confirmed) {
            val up = (change.delta ?: 0) > 0
            val flat = (change.delta ?: 0) == 0
            PetChip(
                change.deltaText,
                if (flat) PetTokens.GraySoft else if (up) PetTokens.GreenSoft else PetTokens.RedSoft,
                if (flat) PetTokens.Ink2 else if (up) PetTokens.GreenInk else PetTokens.RedInk,
            )
        } else {
            PetChip("待确认", PetTokens.AmberSoft, PetTokens.AmberInk)
        }
    }
}

@Composable
private fun ActionsCard(actions: List<ActionOutcome>) {
    PetCard {
        PetRowHeader("动作与道具", "发出 ≠ 生效")
        actions.forEachIndexed { index, action ->
            if (index > 0) PetDivider(Modifier.padding(vertical = 2.dp))
            Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(action.label, fontSize = 14.sp, fontWeight = FontWeight(650), color = PetTokens.Ink)
                    Spacer(Modifier.height(3.dp))
                    Text(action.meta, fontSize = 11.sp, color = PetTokens.Ink3)
                }
                Spacer(Modifier.width(8.dp))
                when (action.verdictTone) {
                    VerdictTone.CONFIRMED -> Text(action.verdict, fontSize = 13.sp,
                        fontWeight = FontWeight(650), color = PetTokens.GreenInk)
                    VerdictTone.PENDING -> PetChip(action.verdict, PetTokens.AmberSoft, PetTokens.AmberInk)
                    VerdictTone.UNKNOWN -> PetChip(action.verdict, PetTokens.GraySoft, PetTokens.Ink2)
                }
            }
        }
    }
}

@Composable
private fun UnsupportedCard() {
    PetCard {
        PetSection("本版本读取不到的数据")
        Spacer(Modifier.height(7.dp))
        listOf(
            "属性：力量 / 智力 / 魅力" to "NO_READER_IN_SLICE1",
            "金币与收益" to "NO_READER_IN_SLICE1",
            "背包库存数量" to "无稳定可识别位置",
            "成绩等级" to "结果页仅确认出现，等级未接入识别",
        ).forEach { (name, why) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(name, fontSize = 13.sp, color = PetTokens.Ink2, modifier = Modifier.weight(1f))
                PetChip("不支持读取", PetTokens.GraySoft, PetTokens.Ink2)
            }
        }
        Spacer(Modifier.height(2.dp))
        PetNote("这些项目不会以估算值出现在记录里，也不计入“已确认”。")
    }
}

@Composable
private fun HistoryCard(history: List<SessionHistoryRow>) {
    PetCard {
        PetSection("历史运行")
        Spacer(Modifier.height(6.dp))
        history.forEachIndexed { index, row ->
            if (index > 0) PetDivider(Modifier.padding(vertical = 2.dp))
            Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(row.whenText, fontSize = 13.5.sp, fontWeight = FontWeight(650), color = PetTokens.Ink)
                    Spacer(Modifier.height(3.dp))
                    Text(row.detail, fontSize = 11.sp, color = PetTokens.Ink3, maxLines = 2)
                }
                Spacer(Modifier.width(8.dp))
                Column(horizontalAlignment = Alignment.End) {
                    when (row.outcomeTone) {
                        VerdictTone.CONFIRMED -> PetChip(row.outcomeText, PetTokens.GreenSoft, PetTokens.GreenInk)
                        VerdictTone.PENDING -> PetChip(row.outcomeText, PetTokens.RedSoft, PetTokens.RedInk)
                        VerdictTone.UNKNOWN -> PetChip(row.outcomeText, PetTokens.GraySoft, PetTokens.Ink2)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("${row.screenshots} 张截图", fontSize = 10.sp, color = PetTokens.Ink3)
                }
            }
        }
    }
}
