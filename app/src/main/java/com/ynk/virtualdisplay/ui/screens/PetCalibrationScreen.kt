package com.ynk.virtualdisplay.ui.screens

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ynk.virtualdisplay.automation.HeadlessDisplayCapture
import com.ynk.virtualdisplay.automation.PetProfile
import com.ynk.virtualdisplay.automation.PetRuntime
import com.ynk.virtualdisplay.ui.kit.PetButtonTone
import com.ynk.virtualdisplay.ui.kit.PetCard
import com.ynk.virtualdisplay.ui.kit.PetFootButton
import com.ynk.virtualdisplay.ui.kit.PetNote
import com.ynk.virtualdisplay.ui.kit.PetSection
import com.ynk.virtualdisplay.ui.main.MainViewModel
import com.ynk.virtualdisplay.ui.theme.PetTokens
import kotlinx.coroutines.launch
import java.io.File

/**
 * 模板校准. This preserves the calibration-frame capability the previous developer screen had —
 * capturing a real frame off the managed virtual display into the app's private directory so ROI
 * templates can be cut from it.
 *
 * It is deliberately NOT a "screenshot" feature for users: it only works while no session is running
 * (a live session owns the display), and it states exactly where the file went.
 */
@Composable
fun PetCalibrationScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val runtime by PetRuntime.state.collectAsState()
    val scope = rememberCoroutineScope()
    val profile = remember { PetProfile.calibrated() }

    var capturing by remember { mutableStateOf(false) }
    var lastSaved by remember { mutableStateOf<String?>(null) }

    val uiState by viewModel.uiState.collectAsState()
    val target = uiState.displays.firstOrNull {
        it.width == profile.displayWidth && it.height == profile.displayHeight
    }

    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 15.dp),
        ) {
            Spacer(Modifier.height(2.dp))
            PetCard {
                PetSection("模板校准帧")
                Spacer(Modifier.height(8.dp))
                PetNote(
                    "在虚拟屏上把 QQ 停在目标页面，然后在托管未运行时保存一帧。文件写入 App 私有目录，"
                        + "用来裁出稳定的小 ROI 作为识别模板。"
                )
                Spacer(Modifier.height(10.dp))
                PetNote(
                    "目标页：课程结束 / 学校 / 课程确认 / 学习中。当前虚拟屏：" +
                        (target?.let { "display ${it.id} · ${it.width}×${it.height}" }
                            ?: "未找到 ${profile.displayWidth}×${profile.displayHeight}")
                )
            }
            Spacer(Modifier.height(11.dp))
            PetCard {
                PetSection("最近一次保存")
                Spacer(Modifier.height(7.dp))
                Text(
                    lastSaved ?: "本次打开还没有保存过帧。",
                    fontSize = 11.5.sp, color = PetTokens.Ink2, fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.height(9.dp))
                PetNote("目录：${File(context.getExternalFilesDir(null), "pet_mvp_debug").absolutePath}")
            }
            Spacer(Modifier.height(11.dp))
            if (runtime.running) {
                PetNote("托管运行中：虚拟屏由会话占用，无法同时截图。停止托管后再试。")
            }
            Spacer(Modifier.height(12.dp))
        }

        Column(Modifier.padding(horizontal = 15.dp).padding(top = 9.dp, bottom = 12.dp)) {
            PetFootButton(
                text = when {
                    capturing -> "正在截图…"
                    runtime.running -> "托管中不可截图"
                    target == null -> "未找到可截图的虚拟屏"
                    else -> "保存当前虚拟屏调试帧"
                },
                tone = if (!runtime.running && target != null && !capturing) PetButtonTone.PRIMARY
                else PetButtonTone.DISABLED,
                onClick = {
                    val id = target?.id ?: return@PetFootButton
                    if (runtime.running || capturing) return@PetFootButton
                    scope.launch {
                        capturing = true
                        try {
                            val capture = HeadlessDisplayCapture(
                                viewModel.repository, id, profile.displayWidth, profile.displayHeight,
                            )
                            capture.start()
                            val bmp = try { capture.awaitFrame(5_000L) } finally { capture.stop() }
                            val dir = File(context.getExternalFilesDir(null), "pet_mvp_debug").apply { mkdirs() }
                            val file = File(dir, "frame_${System.currentTimeMillis()}.png")
                            file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                            bmp.recycle()
                            lastSaved = file.absolutePath
                            Toast.makeText(context, "已保存：${file.name}", Toast.LENGTH_LONG).show()
                        } catch (t: Throwable) {
                            Toast.makeText(context, "截图失败：${t.message}", Toast.LENGTH_LONG).show()
                        } finally {
                            capturing = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
