package com.ynk.virtualdisplay.ui.screens

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.core.content.ContextCompat
import com.ynk.virtualdisplay.bot.BotConfig
import com.ynk.virtualdisplay.bot.BotConfigCodec
import com.ynk.virtualdisplay.bot.BotRuntime
import com.ynk.virtualdisplay.bot.BotService
import com.ynk.virtualdisplay.ui.kit.PetButtonTone
import com.ynk.virtualdisplay.ui.kit.PetCard
import com.ynk.virtualdisplay.ui.kit.PetFootButton
import com.ynk.virtualdisplay.ui.kit.PetMono
import com.ynk.virtualdisplay.ui.kit.PetSection
import com.ynk.virtualdisplay.ui.theme.PetTokens

private const val PREFS = "bot"
private const val KEY_CONFIG = "config"

private fun loadConfig(context: Context): BotConfig = runCatching {
    BotConfigCodec.decode(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CONFIG, "") ?: "")
}.getOrDefault(BotConfig())

private fun saveConfig(context: Context, config: BotConfig) =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_CONFIG, BotConfigCodec.encode(config)).apply()

/**
 * The bot's one screen: what to do, start / stop, and what it is doing. [displayId] is the pet's
 * 720x1600 virtual display, or null when none is open.
 */
@Composable
fun BotScreen(displayId: Int?) {
    val context = LocalContext.current
    val state by BotRuntime.state.collectAsState()
    val saved = remember { loadConfig(context) }
    var course by remember { mutableStateOf(saved.course ?: "") }
    var minutes by remember { mutableStateOf(saved.courseMinutes.toString()) }
    var lessons by remember { mutableStateOf(saved.lessons.toString()) }
    var energy by remember { mutableStateOf(saved.energyTarget.toString()) }
    var clean by remember { mutableStateOf(saved.cleanTarget.toString()) }
    var work by remember { mutableStateOf(saved.workWhenShort) }
    var error by remember { mutableStateOf<String?>(null) }

    fun config(): BotConfig? = runCatching {
        BotConfig(
            course = course.trim().ifEmpty { null },
            courseMinutes = minutes.trim().toInt(),
            lessons = lessons.trim().toInt(),
            energyTarget = energy.trim().toInt(),
            cleanTarget = clean.trim().toInt(),
            workWhenShort = work,
        )
    }.getOrNull()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 15.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PetCard {
            Text(state.status, fontSize = 17.sp, fontWeight = FontWeight.Bold,
                color = if (state.running) PetTokens.GreenInk else PetTokens.Ink)
            state.lastReport?.let {
                Spacer(Modifier.height(4.dp))
                Text("上次：上课 ${it.lessons} 节 · 打工 ${it.shifts} 班 · 工资 ${it.coinsEarned} 币", fontSize = 13.sp, color = PetTokens.Ink2)
            }
            if (displayId == null) {
                Spacer(Modifier.height(4.dp))
                Text("没有 720x1600 的虚拟屏：先在 设置 → 虚拟屏控制台 里创建并打开 QQ 宠物。", fontSize = 13.sp, color = PetTokens.AmberInk)
            }
        }

        PetCard {
            PetSection("本次任务")
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(course, { course = it }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("课程名（留空 = 面板默认课程）") })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(minutes, { minutes = it }, Modifier.weight(1f), singleLine = true, label = { Text("每节分钟") })
                OutlinedTextField(lessons, { lessons = it }, Modifier.weight(1f), singleLine = true, label = { Text("节数 1–10") })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(energy, { energy = it }, Modifier.weight(1f), singleLine = true, label = { Text("体力目标") })
                OutlinedTextField(clean, { clean = it }, Modifier.weight(1f), singleLine = true, label = { Text("清洁目标") })
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("金币不够时先打一班工（竹影武馆·帮武馆跑腿）", Modifier.weight(1f), fontSize = 14.sp, color = PetTokens.Ink)
                Spacer(Modifier.width(8.dp))
                Switch(work, { work = it })
            }
            error?.let { Text(it, fontSize = 13.sp, color = PetTokens.RedInk) }
        }

        if (state.running) {
            PetFootButton("停止", PetButtonTone.DANGER, { context.startService(BotService.stopIntent(context)) }, Modifier.fillMaxWidth())
        } else {
            PetFootButton("开始", if (displayId == null) PetButtonTone.DISABLED else PetButtonTone.PRIMARY, {
                val c = config()
                if (c == null || displayId == null) {
                    error = "请检查：节数 1–10，分钟 1–480，目标 0–100。"
                } else {
                    error = null
                    saveConfig(context, c)
                    ContextCompat.startForegroundService(context, BotService.startIntent(context, displayId, c))
                }
            }, Modifier.fillMaxWidth())
        }

        PetCard {
            PetSection("运行日志")
            Spacer(Modifier.height(6.dp))
            if (state.log.isEmpty()) Text("还没有日志", fontSize = 13.sp, color = PetTokens.Ink3)
            state.log.asReversed().take(60).forEach {
                Text(it, fontSize = 12.sp, fontFamily = PetMono, color = PetTokens.Ink2)
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}
