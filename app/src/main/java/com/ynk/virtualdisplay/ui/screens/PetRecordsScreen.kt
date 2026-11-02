package com.ynk.virtualdisplay.ui.screens

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
import com.ynk.virtualdisplay.ui.records.MetricChange
import com.ynk.virtualdisplay.ui.records.RecordsPresentation
import com.ynk.virtualdisplay.ui.records.RecordsSummary
import com.ynk.virtualdisplay.ui.records.SessionHistoryRow
import com.ynk.virtualdisplay.ui.records.VerdictTone
import com.ynk.virtualdisplay.ui.theme.PetTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    val changes = focus?.let { RecordsPresentation.changes(it.rounds) }.orEmpty()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 15.dp)) {
        Spacer(Modifier.height(2.dp))

        if (!loaded) {
            PetCard { PetNote("正在读取本地运行记录…") }
        } else if (focus == null) {
            PetCard {
                PetSection("今天")
                Spacer(Modifier.height(6.dp))
                PetNote("今天还没有运行记录。完成一次托管后，这里会显示读数变化。")
            }
        } else {
            TodayCard(summary)
            Spacer(Modifier.height(11.dp))
            if (changes.any { it.beforeText != "—" || it.afterText != "—" }) ChangesCard(changes)
            else PetNote("这次运行没有读到状态数值。")
        }

        if (history.isNotEmpty()) {
            Spacer(Modifier.height(11.dp))
            HistoryCard(history)
        }

        Spacer(Modifier.height(11.dp))
        PetNote("读数来自本机截图识别，只保存在这台手机上。")
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
            StripCell(summary.pauseCount, "自动暂停 次", Modifier.weight(1f))
        }
    }
}

@Composable
private fun StripCell(value: Int, label: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("$value", fontSize = 21.sp, fontWeight = FontWeight.Bold, color = PetTokens.Ink)
        Spacer(Modifier.height(2.dp))
        Text(label, fontSize = 10.5.sp, color = PetTokens.Ink3)
    }
}

@Composable
private fun ChangesCard(changes: List<MetricChange>) {
    PetCard {
        PetRowHeader("状态变化", "截图识别")
        Spacer(Modifier.height(6.dp))
        changes.forEachIndexed { index, change ->
            if (index > 0) PetDivider(Modifier.padding(vertical = 2.dp))
            ChangeRow(change, index == 0)
        }
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
            fontSize = 13.sp, color = PetTokens.Ink2,
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
private fun HistoryCard(history: List<SessionHistoryRow>) {
    PetCard {
        PetSection("历史记录")
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
                when (row.outcomeTone) {
                    VerdictTone.CONFIRMED -> PetChip(row.outcomeText, PetTokens.GreenSoft, PetTokens.GreenInk)
                    VerdictTone.PENDING -> PetChip(row.outcomeText, PetTokens.RedSoft, PetTokens.RedInk)
                    VerdictTone.UNKNOWN -> PetChip(row.outcomeText, PetTokens.GraySoft, PetTokens.Ink2)
                }
            }
        }
    }
}
