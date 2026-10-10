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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.ynk.virtualdisplay.automation.PetManagedRuntime
import com.ynk.virtualdisplay.automation.PetRuntime
import com.ynk.virtualdisplay.ui.data.PetEvidenceRepository
import com.ynk.virtualdisplay.ui.kit.PetChip
import com.ynk.virtualdisplay.ui.kit.PetCard
import com.ynk.virtualdisplay.ui.kit.PetNote
import com.ynk.virtualdisplay.ui.log.PetLogEntry
import com.ynk.virtualdisplay.ui.log.PetLogPresentation
import com.ynk.virtualdisplay.ui.main.PetTitleBar
import com.ynk.virtualdisplay.ui.theme.PetTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The full 运行日志. It reads `files/pet_mvp.log` — the same file the automation layer appends to —
 * so what is on screen is the raw record, not a summary the UI made up.
 *
 * 简洁 mode drops per-frame capture telemetry (thousands of lines per session); 原始 shows every
 * line so a failure can be traced without pulling the file off the device.
 */
@Composable
fun PetLogScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val runtime by PetRuntime.state.collectAsState()
    val managed by PetManagedRuntime.state.collectAsState()

    val repo = remember { PetEvidenceRepository(context) }
    var raw by remember { mutableStateOf(false) }
    var rows by remember { mutableStateOf<List<PetLogEntry>>(emptyList()) }
    var totalRaw by remember { mutableStateOf(0) }

    LaunchedEffect(runtime.running, managed.sessionId, raw) {
        suspend fun reload() = withContext(Dispatchers.IO) {
            val entries = repo.logEntries(limit = 1200)
            totalRaw = entries.size
            rows = if (raw) PetLogPresentation.rawTimeline(entries)
            else PetLogPresentation.timeline(entries)
        }
        reload()
        while (runtime.running) {
            delay(2_500L)
            reload()
        }
    }

    Column(Modifier.fillMaxSize()) {
        PetTitleBar("运行日志", onBack = onClose)

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ModeChip("简洁", !raw) { raw = false }
            ModeChip("原始", raw) { raw = true }
            Spacer(Modifier.weight(1f))
            PetNote("读取 $totalRaw 行 · 显示 ${rows.size}")
        }

        if (rows.isEmpty()) {
            Column(Modifier.padding(horizontal = 15.dp)) {
                PetCard {
                    PetNote(
                        "暂无日志。日志由自动化层在托管过程中写入；如果刚刚开始，稍等几秒会出现" +
                            "「启动导航」等事件。"
                    )
                }
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(horizontal = 15.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                items(rows) { row -> LogLine(row) }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}

@Composable
private fun ModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .background(
                if (selected) PetTokens.BlueSoft else PetTokens.GraySoft,
                RoundedCornerShape(PetTokens.PillRadius),
            )
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            label, fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) PetTokens.BlueInk else PetTokens.Ink2,
        )
    }
}

@Composable
private fun LogLine(row: PetLogEntry) {
    Row(
        Modifier.fillMaxWidth().background(PetTokens.Surface, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            row.time, fontSize = 10.5.sp, color = PetTokens.Ink3,
            fontFamily = FontFamily.Monospace, modifier = Modifier.width(40.dp),
        )
        Box(Modifier.padding(top = 5.dp).size(6.dp).background(row.dot, RoundedCornerShape(3.dp)))
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Text(row.text, fontSize = 12.5.sp, color = PetTokens.Ink, lineHeight = 17.sp)
            if (row.chip != null || row.occurrences > 1) {
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (row.chip != null) PetChip(row.chip, row.chipBg, row.chipFg)
                    if (row.occurrences > 1) {
                        if (row.chip != null) Spacer(Modifier.width(6.dp))
                        // Never hide repetition: the count makes a collapsed run explicit.
                        Text("×${row.occurrences}", fontSize = 10.5.sp, color = PetTokens.Ink3,
                            fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}
