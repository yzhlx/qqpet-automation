package com.ynk.virtualdisplay.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ynk.virtualdisplay.automation.QuietWindow
import com.ynk.virtualdisplay.ui.kit.PetNote
import com.ynk.virtualdisplay.ui.theme.PetTokens

/**
 * STR-17 夜间暂停窗口的设置对话框。
 *
 * 时间以 30 分钟为步进：用户要的是「夜里别动」，不需要分钟级的钟。起止相同视为没有窗口，
 * 这里在界面上直接说明，免得用户以为设了 24 小时静默。
 */
@Composable
fun QuietWindowDialog(initial: QuietWindow, onSave: (QuietWindow) -> Unit, onDismiss: () -> Unit) {
    var enabled by remember { mutableStateOf(initial.enabled) }
    var startMinute by remember { mutableStateOf(initial.startMinute) }
    var endMinute by remember { mutableStateOf(initial.endMinute) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PetTokens.Surface,
        title = { Text("夜间暂停") },
        text = {
            Column {
                Row(
                    Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("开启夜间暂停", fontSize = 14.sp, color = PetTokens.Ink)
                        PetNote("这段时间里不开始新的托管")
                    }
                    Switch(checked = enabled, onCheckedChange = { enabled = it },
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = PetTokens.Blue,
                            uncheckedTrackColor = PetTokens.GraySoft,
                            uncheckedThumbColor = PetTokens.Chevron,
                            uncheckedBorderColor = PetTokens.Chevron,
                        ))
                }
                if (enabled) {
                    Spacer(Modifier.height(8.dp))
                    TimeRow("开始", startMinute) { startMinute = it }
                    TimeRow("结束", endMinute) { endMinute = it }
                    Spacer(Modifier.height(4.dp))
                    PetNote("运行中的任务走到这个时段会先停下，到点重新确认画面再继续。改完设置最多半分钟内生效。")
                    if (startMinute == endMinute) {
                        PetNote("开始和结束相同等于没有设置窗口。")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(QuietWindow(enabled, startMinute, endMinute)) }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** − 23:00 + 的时间行；跨零点的时段由文案说明，不在这里做换算。 */
@Composable
private fun TimeRow(label: String, minute: Int, onValue: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label, Modifier.weight(1f), fontSize = 14.sp, color = PetTokens.Ink)
        TimeKey("−") { onValue((minute - 30 + 1440) % 1440) }
        Text(QuietWindow.formatMinute(minute), Modifier.width(56.dp), fontSize = 16.sp,
            fontWeight = FontWeight.Bold, color = PetTokens.Ink, textAlign = TextAlign.Center)
        TimeKey("+") { onValue((minute + 30) % 1440) }
    }
}

@Composable
private fun TimeKey(text: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(PetTokens.TileRadius)
    Box(
        Modifier.size(36.dp).border(1.dp, PetTokens.Line, shape).background(PetTokens.Surface, shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 18.sp, color = PetTokens.Ink2)
    }
}
