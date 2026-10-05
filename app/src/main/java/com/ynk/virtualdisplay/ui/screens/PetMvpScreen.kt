package com.ynk.virtualdisplay.ui.screens

import android.content.Intent
import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.ynk.virtualdisplay.automation.DailyBudgetStore
import com.ynk.virtualdisplay.automation.HeadlessDisplayCapture
import com.ynk.virtualdisplay.automation.PetAutomationService
import com.ynk.virtualdisplay.automation.PetProfile
import com.ynk.virtualdisplay.automation.PetRuntime
import com.ynk.virtualdisplay.ui.main.MainViewModel
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PetMvpScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val runtime by PetRuntime.state.collectAsState()
    val scope = rememberCoroutineScope()
    val profile = remember { PetProfile.calibrated() }
    val profileErrors = remember { profile.validate() }
    var selectedDisplayId by remember { mutableStateOf<Int?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var capturing by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.displays) {
        if (selectedDisplayId !in uiState.displays.map { it.id }) {
            selectedDisplayId = uiState.displays.firstOrNull {
                it.width == profile.displayWidth && it.height == profile.displayHeight
            }?.id
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("QQ 宠物短课 MVP", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "范围：当前设备 / 当前QQ / 720×1600@320dpi / 一种10分钟短课。任何识别失败立即停止。",
            style = MaterialTheme.typography.bodySmall
        )

        AssistChip(
            onClick = {},
            label = { Text(if (runtime.running) "运行中 · ${runtime.status}" else "已停止 · ${runtime.status}") }
        )

        ExposedDropdownMenuBox(expanded = menuOpen, onExpandedChange = { if (!runtime.running) menuOpen = !menuOpen }) {
            OutlinedTextField(
                value = selectedDisplayId?.let { "VirtualDisplay #$it" } ?: "请选择 720×1600 虚拟屏",
                onValueChange = {},
                readOnly = true,
                enabled = !runtime.running,
                label = { Text("目标虚拟屏") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(menuOpen) },
                modifier = Modifier.menuAnchor().fillMaxWidth()
            )
            ExposedDropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                uiState.displays.forEach { d ->
                    DropdownMenuItem(
                        text = { Text("#${d.id} · ${d.width}×${d.height}") },
                        onClick = { selectedDisplayId = d.id; menuOpen = false }
                    )
                }
            }
        }

        if (profileErrors.isNotEmpty()) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("尚未校准，自动循环会被拒绝", fontWeight = FontWeight.Bold)
                    profileErrors.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                    Text("先用下方调试截图 + 现有预览窗口完成坐标/模板校准。", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                enabled = !runtime.running && profileErrors.isEmpty() && selectedDisplayId != null,
                onClick = {
                    val id = selectedDisplayId ?: return@Button
                    ContextCompat.startForegroundService(context, PetAutomationService.startIntent(context, id))
                },
                modifier = Modifier.weight(1f)
            ) { Text("开始短课 Session") }

            OutlinedButton(
                enabled = runtime.running,
                onClick = { context.startService(PetAutomationService.stopIntent(context)) },
                modifier = Modifier.weight(1f)
            ) { Text("立即停止") }
        }

        HorizontalDivider()

        Text("开发者校准", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            "关闭虚拟屏 Play 预览后，保存当前帧到 App 私有目录。分别在“课程结束 / 学校 / 课程确认 / 学习中”页面保存截图，再裁出稳定的小ROI作为4张模板。",
            style = MaterialTheme.typography.bodySmall
        )
        OutlinedButton(
            enabled = !runtime.running && !capturing && selectedDisplayId != null,
            onClick = {
                val id = selectedDisplayId ?: return@OutlinedButton
                scope.launch {
                    capturing = true
                    try {
                        val capture = HeadlessDisplayCapture(viewModel.repository, id, profile.displayWidth, profile.displayHeight)
                        capture.start()
                        val bmp = try { capture.awaitFrame(5_000L) } finally { capture.stop() }
                        val dir = File(context.getExternalFilesDir(null), "pet_mvp_debug").apply { mkdirs() }
                        val file = File(dir, "frame_${System.currentTimeMillis()}.png")
                        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        bmp.recycle()
                        Toast.makeText(context, "已保存：${file.absolutePath}", Toast.LENGTH_LONG).show()
                    } catch (t: Throwable) {
                        Toast.makeText(context, "截图失败：${t.message}", Toast.LENGTH_LONG).show()
                    } finally { capturing = false }
                }
            }
        ) { Text(if (capturing) "正在截图…" else "保存当前虚拟屏调试帧") }

        val used = remember(runtime.cycles, runtime.running) { DailyBudgetStore(context).usedMinutes() }
        Text("MVP记录的今日自动学习：$used / ${DailyBudgetStore.HARD_STOP_MINUTES} 分钟", style = MaterialTheme.typography.bodySmall)
        Text("注意：这一计数看不到你在 App 外手动进行的学习/打工，因此正式产品必须补全统一疲劳账本。", style = MaterialTheme.typography.bodySmall)

        runtime.nextCheckAtMs?.let { Text("下一次检查时间戳：$it", style = MaterialTheme.typography.labelSmall) }
        Text("日志：${File(context.filesDir, "pet_mvp.log").absolutePath}", style = MaterialTheme.typography.labelSmall)
    }
}
